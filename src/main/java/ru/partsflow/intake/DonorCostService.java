package ru.partsflow.intake;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    public DonorCostService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Cost> of(long donorId) {
        return jdbc.query("""
                SELECT id, cost_type, amount, incurred_on, note
                  FROM donor_cost WHERE donor_id = ?
                 ORDER BY incurred_on, id""",
                (rs, i) -> new Cost(rs.getLong("id"), rs.getString("cost_type"),
                        rs.getBigDecimal("amount"),
                        rs.getObject("incurred_on", LocalDate.class), rs.getString("note")),
                donorId);
    }

    @Transactional
    public List<Cost> add(long donorId, String type, BigDecimal amount, LocalDate on,
                          String note, Long authorId) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("Неизвестный вид затрат: " + type);
        }
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("Сумма затрат не может быть отрицательной");
        }
        jdbc.update("""
                INSERT INTO donor_cost (donor_id, cost_type, amount, incurred_on, note, created_by)
                VALUES (?, ?, ?, coalesce(?, current_date), ?, ?)""",
                donorId, type, amount, on, note, authorId);
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
        jdbc.update("DELETE FROM donor_cost WHERE id = ? AND donor_id = ?", costId, donorId);
        return of(donorId);
    }

    public record Cost(long id, String type, BigDecimal amount, LocalDate incurredOn,
                       String note) {
    }
}
