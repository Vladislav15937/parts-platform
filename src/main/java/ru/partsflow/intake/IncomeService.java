package ru.partsflow.intake;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.inventory.StockDocumentHistory;
import ru.partsflow.shared.NotFound;
import ru.partsflow.shared.SupplyKinds;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Раздел «Поступления» (задача 0360): список документов поступления по вкладкам
 * и сам документ — шапка, позиции, история.
 *
 * <p><b>Список страницами по номеру, а не целиком.</b> У переходящего клиента
 * ≈7 тысяч выполненных документов за жизнь склада; собрать их в память на каждое
 * открытие раздела — то, от чего предостерегает {@code docs/java-rules.md} §7.
 * Страница — 50 свежих сверху, следующая — от номера последней строки (курсор
 * по {@code number}: колонка без {@code NULL} и уникальна), а счётчики вкладок
 * считает отдельный запрос по индексу статуса.
 *
 * <p><b>Сумма — закупочная</b>, тем же выражением, что итоги поставки
 * ({@code SupplySummaryService}): цена строки, а пока её не пишет никто —
 * себестоимость карточки на количество строки. Накладных расходов у нас нет —
 * у ориентира «0+0», у нас одно число и ноль в «Наклад. расх.».
 *
 * <p>Удалённый документ (задача 0367) остаётся во вкладке «Выполненные»
 * с отметкой — его движения в журнале никуда не делись, а исчезнувший номер
 * человек искал бы.
 */
@Service
public class IncomeService {

    /** Сколько документов на странице списка. */
    static final int PAGE = 50;

    /** Вкладка → условие по статусу. Белый список, а не текст из запроса. */
    private static final Map<String, String> TABS = Map.of(
            "drafts", " AND d.status = 'DRAFT'",
            "done", " AND d.status = 'DONE'",
            "cancelled", " AND d.status = 'CANCELLED'",
            "all", "");

    private static final String AMOUNT = """
            (SELECT COALESCE(sum(l.qty * COALESCE(l.price, p.cost_price, 0)), 0)
               FROM stock_document_line l JOIN part p ON p.id = l.part_id
              WHERE l.document_id = d.id)""";

    private final JdbcTemplate jdbc;
    private final StockDocumentHistory history;

    public IncomeService(JdbcTemplate jdbc, StockDocumentHistory history) {
        this.jdbc = jdbc;
        this.history = history;
    }

    /**
     * @param tab    вкладка: drafts, done, cancelled, all; незнакомая — «все»
     * @param before номер последнего документа прошлой страницы; пусто — первая
     */
    @Transactional(readOnly = true)
    public Page list(String tab, Long before) {
        String where = TABS.getOrDefault(tab == null ? "all" : tab, "");
        List<Object> args = new ArrayList<>();
        String cursor = "";
        if (before != null) {
            cursor = " AND d.number < ?";
            args.add(before);
        }
        args.add(PAGE + 1);
        List<Row> rows = jdbc.query("""
                        SELECT d.id, d.number, d.status, d.created_at, d.completed_at,
                               d.deleted_at, d.note, w.name AS warehouse,
                               (SELECT count(*) FROM stock_document_line l
                                 WHERE l.document_id = d.id) AS lines,
                        """
                        + "       " + SupplyKinds.sqlLabel("s") + " AS supply,\n"
                        + "       " + AMOUNT + " AS amount\n"
                        + """
                          FROM stock_document d
                          JOIN warehouse w ON w.id = d.warehouse_id
                          LEFT JOIN supply s ON s.id = d.supply_id
                         WHERE d.doc_type = 'INTAKE'"""
                        + where + cursor
                        + " ORDER BY d.number DESC LIMIT ?",
                (rs, i) -> new Row(rs.getLong("id"), rs.getLong("number"), rs.getString("status"),
                        rs.getTimestamp("deleted_at") != null,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null
                                : rs.getTimestamp("completed_at").toInstant(),
                        rs.getString("supply"), rs.getString("warehouse"),
                        rs.getBigDecimal("amount"), rs.getString("note"), rs.getLong("lines")),
                args.toArray());
        boolean more = rows.size() > PAGE;
        return new Page(more ? rows.subList(0, PAGE) : rows, counts(), more);
    }

    private Counts counts() {
        return jdbc.queryForObject("""
                        SELECT count(*) FILTER (WHERE status = 'DRAFT')     AS drafts,
                               count(*) FILTER (WHERE status = 'DONE')      AS done,
                               count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                               count(*)                                     AS total
                          FROM stock_document WHERE doc_type = 'INTAKE'""",
                (rs, i) -> new Counts(rs.getLong("drafts"), rs.getLong("done"),
                        rs.getLong("cancelled"), rs.getLong("total")));
    }

    /** Документ поступления целиком: шапка, позиции, история. */
    @Transactional(readOnly = true)
    public Income of(long documentId) {
        List<Income> found = jdbc.query("""
                        SELECT d.id, d.number, d.status, d.created_at, d.completed_at,
                               d.deleted_at, d.note, d.supply_id, d.warehouse_id,
                               w.name AS warehouse,
                        """
                        + "       " + SupplyKinds.sqlLabel("s") + " AS supply,\n"
                        + "       " + AMOUNT + " AS amount\n"
                        + """
                          FROM stock_document d
                          JOIN warehouse w ON w.id = d.warehouse_id
                          LEFT JOIN supply s ON s.id = d.supply_id
                         WHERE d.id = ? AND d.doc_type = 'INTAKE'""",
                (rs, i) -> new Income(rs.getLong("id"), rs.getLong("number"),
                        rs.getString("status"), rs.getTimestamp("deleted_at") != null,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null
                                : rs.getTimestamp("completed_at").toInstant(),
                        (Long) rs.getObject("supply_id"), rs.getString("supply"),
                        rs.getLong("warehouse_id"), rs.getString("warehouse"),
                        rs.getString("note"), rs.getBigDecimal("amount"),
                        lines(documentId), history.of(documentId)),
                documentId);
        if (found.isEmpty()) {
            throw NotFound.INCOME.error(documentId);
        }
        return found.get(0);
    }

    private List<Line> lines(long documentId) {
        return jdbc.query("""
                        SELECT p.id, p.number, p.public_code, p.title, p.condition, l.qty,
                               COALESCE(l.price, p.cost_price) AS cost
                          FROM stock_document_line l
                          JOIN part p ON p.id = l.part_id
                         WHERE l.document_id = ?
                         ORDER BY l.id""",
                (rs, i) -> new Line(rs.getLong("id"), rs.getLong("number"),
                        rs.getString("public_code"), rs.getString("title"),
                        rs.getString("condition"), rs.getBigDecimal("qty"),
                        rs.getBigDecimal("cost")),
                documentId);
    }

    /**
     * @param status  код статуса (DRAFT, DONE, CANCELLED); слово ставит экран
     * @param deleted выполненный документ удалён (задача 0367)
     * @param supply  поставка словами («Поставка №Иванов»); пусто — не выбрана
     * @param amount  закупочная сумма
     */
    public record Row(long id, long number, String status, boolean deleted, Instant createdAt,
                      Instant completedAt, String supply, String warehouse,
                      BigDecimal amount, String note, long lines) {
    }

    /** Счётчики вкладок — по всем документам, а не по странице. */
    public record Counts(long drafts, long done, long cancelled, long all) {
    }

    /** @param more есть ли следующая страница — экран говорит, что показано не всё */
    public record Page(List<Row> rows, Counts counts, boolean more) {
    }

    public record Income(long id, long number, String status, boolean deleted, Instant createdAt,
                         Instant completedAt, Long supplyId, String supply,
                         long warehouseId, String warehouse, String note, BigDecimal amount,
                         List<Line> lines, List<StockDocumentHistory.Entry> history) {
    }

    /**
     * Строка документа.
     *
     * @param number    номер позиции — ссылка на карточку и то, что называют вслух
     * @param condition код состояния; слово ставит экран
     * @param cost      закупочная цена; пусто — не указана
     */
    public record Line(long partId, long number, String publicCode, String title,
                       String condition, BigDecimal qty, BigDecimal cost) {
    }
}
