package ru.partsflow.intake;

import ru.partsflow.shared.NotFound;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Итоги поставки на её карточке: как отработал контейнер (задача 0375).
 *
 * <p><b>Зачем.</b> «Окупается ли партия» владелец спрашивает после каждого
 * контейнера, а ответ лежал в отчётах, куда со строки поставки не попасть.
 * У ориентира он стоит в окне поставки тремя блоками — «Поступления»,
 * «Товары», «Авто-доноры», — и переходящий клиент ищет его там же.
 *
 * <p><b>Штуки — по журналу движений, а не по полю карточки.</b>
 * {@code part.quantity} у принятой партией позиции остаётся единицей,
 * а у перенесённой из предыдущей системы — единицей у всех; ровно на этом
 * врал разрез по поставке в отчётах, пока не перешёл на журнал
 * ({@code reports/CLAUDE.md}). Выражения взяты у него же: «Всего» — то же,
 * что подвал вкладки «Поступило» (приход со знаком), «Списано» — подвал
 * «Списано». Знак, а не модуль: удалённый документ снимается встречным
 * движением того же вида (задача 0367), и модуль посчитал бы его вторым
 * приходом.
 *
 * <p><b>«Продано» — по журналу, а не по статусу позиции</b>, и в этом
 * итоги расходятся с вкладкой «Продано» отчёта. Вкладка отбирает
 * {@code p.status = 'SOLD'} и не видит колеса, проданного одно из двух;
 * здесь проданное считается штуками: продажи минус возвраты покупателей.
 * Иначе критерий задачи — «продажа одной позиции увеличивает «Продано»,
 * уменьшает «Остаток»» — не выполнялся бы для позиции из нескольких штук.
 *
 * <p><b>«Остаток» — {@code qty_on_hand}</b>, то же, по чему вкладка
 * «Остатки» считает {@code stock_value}: лежит на полке, включая отложенное.
 * Остаток + продано + списано равны «Всего», пока по позициям поставки
 * не было пересчётов: излишек и недостача пересчёта двигают остаток мимо
 * трёх названных строк, и разница на экране тогда — правда о складе.
 *
 * <p><b>Удалённое поступление не считается ни в одной строке «Поступлений».</b>
 * Его товар снят встречным движением, и «Выполнено — 3» при двух
 * действующих документах обещало бы приход, которого нет; отдельной строки
 * «Удалены» у ориентира нет.
 *
 * <p><b>Сумма поступления — закупочная:</b> цена строки документа («по чём
 * пришло»), а пока её никто не пишет — себестоимость карточки. Накладных
 * расходов у нас нет, поэтому у ориентира «0+0», у нас одно число.
 *
 * <p>Читается через {@code JdbcTemplate} внутри транзакции: вне её запрос
 * ушёл бы в {@code public}.
 */
@Service
public class SupplySummaryService {

    private final JdbcTemplate jdbc;
    private final SupplyRepository supplies;

    public SupplySummaryService(JdbcTemplate jdbc, SupplyRepository supplies) {
        this.jdbc = jdbc;
        this.supplies = supplies;
    }

    @Transactional(readOnly = true)
    public Summary of(long supplyId) {
        if (!supplies.existsById(supplyId)) {
            throw NotFound.SUPPLY.error(supplyId);
        }
        return new Summary(receipts(supplyId), goods(supplyId), donors(supplyId));
    }

    private Receipts receipts(long supplyId) {
        List<Receipts> rows = jdbc.query("""
                SELECT count(*) FILTER (WHERE d.status = 'DRAFT')     AS drafts,
                       COALESCE(sum(d.amount) FILTER (WHERE d.status = 'DRAFT'), 0) AS drafts_amount,
                       count(*) FILTER (WHERE d.status = 'DONE')      AS done,
                       COALESCE(sum(d.amount) FILTER (WHERE d.status = 'DONE'), 0) AS done_amount,
                       count(*) FILTER (WHERE d.status = 'CANCELLED') AS cancelled,
                       COALESCE(sum(d.amount) FILTER (WHERE d.status = 'CANCELLED'), 0) AS cancelled_amount,
                       count(*)                                       AS total,
                       COALESCE(sum(d.amount), 0)                     AS total_amount
                  FROM (SELECT sd.id, sd.status,
                               (SELECT COALESCE(sum(l.qty * COALESCE(l.price, p.cost_price, 0)), 0)
                                  FROM stock_document_line l
                                  JOIN part p ON p.id = l.part_id
                                 WHERE l.document_id = sd.id) AS amount
                          FROM stock_document sd
                         WHERE sd.supply_id = ?
                           AND sd.doc_type = 'INTAKE'
                           AND sd.deleted_at IS NULL) d""",
                (rs, i) -> new Receipts(
                        new Count(rs.getLong("drafts"), rs.getBigDecimal("drafts_amount")),
                        new Count(rs.getLong("done"), rs.getBigDecimal("done_amount")),
                        new Count(rs.getLong("cancelled"), rs.getBigDecimal("cancelled_amount")),
                        new Count(rs.getLong("total"), rs.getBigDecimal("total_amount"))),
                supplyId);
        return rows.get(0);
    }

    private Goods goods(long supplyId) {
        List<Goods> rows = jdbc.query("""
                SELECT COALESCE(sum(p.qty_on_hand), 0) AS remaining,
                       COALESCE(sum(m.sold), 0)        AS sold,
                       COALESCE(sum(m.written_off), 0) AS written_off,
                       COALESCE(sum(m.received), 0)    AS total
                  FROM part p
                  LEFT JOIN (SELECT part_id,
                                    sum(qty_delta) FILTER (WHERE movement_type = 'INTAKE')
                                        AS received,
                                    -sum(qty_delta) FILTER (WHERE movement_type IN ('SALE', 'RETURN'))
                                        AS sold,
                                    -sum(qty_delta) FILTER (WHERE movement_type = 'WRITE_OFF')
                                        AS written_off
                               FROM stock_movement
                              WHERE movement_type IN ('INTAKE', 'SALE', 'RETURN', 'WRITE_OFF')
                              GROUP BY part_id) m ON m.part_id = p.id
                 WHERE p.supply_id = ?""",
                (rs, i) -> new Goods(
                        rs.getBigDecimal("remaining"),
                        rs.getBigDecimal("sold"),
                        rs.getBigDecimal("written_off"),
                        rs.getBigDecimal("total")),
                supplyId);
        return rows.get(0);
    }

    private Donors donors(long supplyId) {
        List<Donors> rows = jdbc.query("""
                SELECT count(*) AS donors,
                       COALESCE(sum((SELECT sum(c.amount) FROM donor_cost c
                                      WHERE c.donor_id = d.id)), 0) AS cost
                  FROM donor d
                 WHERE d.supply_id = ?""",
                (rs, i) -> new Donors(rs.getLong("donors"), rs.getBigDecimal("cost")),
                supplyId);
        return rows.get(0);
    }

    /** Итоги поставки — три блока окна поставки у ориентира. */
    public record Summary(Receipts receipts, Goods goods, Donors donors) {
    }

    /** Поступления поставки по статусам документа и всего. */
    public record Receipts(Count drafts, Count done, Count cancelled, Count total) {
    }

    /** Сколько документов и на какую закупочную сумму. */
    public record Count(long count, BigDecimal amount) {
    }

    /** Товары поставки в штуках. */
    public record Goods(BigDecimal remaining, BigDecimal sold, BigDecimal writtenOff,
                        BigDecimal total) {
    }

    /** Машины поставки и сумма их затрат ({@code donor_cost}). */
    public record Donors(long count, BigDecimal cost) {
    }
}
