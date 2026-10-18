package ru.partsflow.intake;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.audit.AuditJournal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Затраты по машине: покупка, доставка, растаможка, разбор, хранение.
 *
 * <p><b>Журналом, а не полем «стоимость».</b> Машину покупают за одну сумму,
 * потом платят за эвакуатор, потом за разбор — и всё это входит в то, что
 * в неё вложено. Одно поле пришлось бы переписывать при каждом платеже,
 * а вопрос «из чего сложились эти сто тысяч» остался бы без ответа.
 *
 * <p>Отсюда же читает отчёт окупаемости: {@code v_donor_profitability}
 * суммирует этот журнал. Пока писать в него было нечем, отчёт честно
 * показывал «вложено 0 ₽» — ему нечего было читать.
 */
@Service
public class DonorCostService {

    /** Виды затрат. Совпадают с ограничением в схеме — иначе вставка упадёт. */
    public static final Set<String> TYPES =
            Set.of("PURCHASE", "DELIVERY", "CUSTOMS", "DISMANTLING", "STORAGE", "OTHER");

    private final JdbcTemplate jdbc;

    /**
     * Затрата пишется прямым SQL, и слушатель Hibernate её не видел: журнал
     * действий предлагал вид «Затрата по машине» и не находил ни одной
     * (задача 0325). Записывается здесь, в той же транзакции.
     */
    private final AuditJournal journal;

    public DonorCostService(JdbcTemplate jdbc, AuditJournal journal) {
        this.jdbc = jdbc;
        this.journal = journal;
    }

    @Transactional(readOnly = true)
    public List<Cost> of(long donorId) {
        return jdbc.query("""
                SELECT id, cost_type, amount, incurred_on, note
                  FROM donor_cost WHERE donor_id = ?
                 ORDER BY incurred_on, id""", COST, donorId);
    }

    @Transactional
    public List<Cost> add(long donorId, String type, BigDecimal amount, LocalDate on,
                          String note, Long authorId) {
        // Пустой вид отбиваем до белого списка: TYPES — это Set.of, а он
        // на null-ключе бросает NPE, то есть сообщение ниже при незаполненном
        // виде не появлялось никогда. Снаружи это прикрывал @NotBlank
        // в IntakeController — защита в другом файле, а сумму рядом
        // на null проверяют явно.
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Вид затрат обязателен");
        }
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("Неизвестный вид затрат: " + type);
        }
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("Сумма затрат не может быть отрицательной");
        }
        Cost added = jdbc.queryForObject("""
                INSERT INTO donor_cost (donor_id, cost_type, amount, incurred_on, note, created_by)
                VALUES (?, ?, ?, coalesce(?, current_date), ?, ?)
                RETURNING id, cost_type, amount, incurred_on, note""",
                COST, donorId, type, amount, on, note, authorId);
        journal.created("donor_cost", added.id(), snapshot(donorId, added));
        return of(donorId);
    }

    /**
     * Удаление — не «исправление ошибки задним числом», а снятие того, чего
     * не было. Журнал движений неизменяем, а журнал затрат нет: платёж,
     * записанный дважды, надо убрать, и встречной записи с минусом
     * ограничение {@code amount >= 0} не позволит.
     */
    @Transactional
    public List<Cost> remove(long donorId, long costId) {
        List<Cost> removed = jdbc.query("""
                DELETE FROM donor_cost WHERE id = ? AND donor_id = ?
                RETURNING id, cost_type, amount, incurred_on, note""", COST, costId, donorId);
        // «Было» — сама затрата: после удаления строки журнал назовёт её
        // только по снимку, а «кто удалил затрату на 40 000» и спрашивают.
        removed.forEach(cost -> journal.deleted("donor_cost", cost.id(), snapshot(donorId, cost)));
        return of(donorId);
    }

    private static final org.springframework.jdbc.core.RowMapper<Cost> COST =
            (rs, i) -> new Cost(rs.getLong("id"), rs.getString("cost_type"),
                    rs.getBigDecimal("amount"),
                    rs.getObject("incurred_on", LocalDate.class), rs.getString("note"));

    /**
     * Колонки, которые журнал называет ({@code AuditedColumns.DONOR_COST}),
     * и машина: у удалённой затраты строки уже нет, и «Машина …» в журнале
     * берётся из снимка.
     */
    private static Map<String, Object> snapshot(long donorId, Cost cost) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("donor_id", donorId);
        row.put("amount", cost.amount());
        row.put("cost_type", cost.type());
        row.put("incurred_on", cost.incurredOn());
        row.put("note", cost.note());
        return row;
    }

    public record Cost(long id, String type, BigDecimal amount, LocalDate incurredOn,
                       String note) {
    }
}
