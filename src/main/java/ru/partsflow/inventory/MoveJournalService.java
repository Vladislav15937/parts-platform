package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Журнал перевозок между складами.
 *
 * <p><b>Зачем.</b> Единственное место, где было видно движение, — вкладка
 * «Движения» в истории одной позиции: ответить «что мы увезли на второй склад
 * в августе» было нельзя, хотя документ и его строки лежат в базе с самого
 * начала — {@code stock_document} и {@code stock_document_line} писала
 * перевозка так же, как приёмка и списание.
 *
 * <p><b>Список — по документам, а не по строкам.</b> У переехавшего клиента
 * документ на семьдесят позиций, и построчный отчёт ориентира удобен для
 * выгрузки в таблицу и неудобен для чтения: тот же ответ на «что уехало»
 * даёт восемнадцать строк документов вместо тысячи с лишним строк товара.
 * Состав документа читается отдельным запросом, по нажатию на строку, —
 * список журнала от него не тяжелеет.
 */
@Service
public class MoveJournalService {

    private final JdbcTemplate jdbc;

    public MoveJournalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Проведённые документы перевозки, свежие сверху.
     *
     * <p>Только {@code DONE}: черновика у перевозки не бывает — она создаётся
     * и проводится одним запросом, — а отменённый документ ничего не увёз.
     */
    @Transactional(readOnly = true)
    public List<MoveDocument> list() {
        return jdbc.query("""
                        SELECT d.id, d.number, d.created_at, d.note,
                               wf.name AS from_warehouse, wt.name AS to_warehouse,
                               who.display_name AS author,
                               count(l.id) AS lines
                          FROM stock_document d
                          JOIN warehouse wf ON wf.id = d.warehouse_id
                          JOIN warehouse wt ON wt.id = d.to_warehouse_id
                          LEFT JOIN tenant_member who ON who.id = d.created_by
                          LEFT JOIN stock_document_line l ON l.document_id = d.id
                         WHERE d.doc_type = 'MOVE' AND d.status = 'DONE'
                         GROUP BY d.id, d.number, d.created_at, d.note,
                                  wf.name, wt.name, who.display_name
                         ORDER BY d.created_at DESC, d.id DESC""",
                (rs, i) -> new MoveDocument(rs.getLong("id"), rs.getLong("number"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("from_warehouse"), rs.getString("to_warehouse"),
                        rs.getInt("lines"), rs.getString("note"), rs.getString("author")));
    }

    /**
     * Состав документа: что и сколько уехало.
     *
     * <p>Ссылка в карточку позиции строится на клиенте по {@code partId},
     * а публичный код и наименование — чтобы кладовщик узнал деталь, не
     * открывая карточку.
     */
    @Transactional(readOnly = true)
    public List<MoveLine> lines(long documentId) {
        return jdbc.query("""
                        SELECT p.id AS part_id, p.public_code, p.title, l.qty
                          FROM stock_document_line l
                          JOIN part p ON p.id = l.part_id
                         WHERE l.document_id = ?
                         ORDER BY l.id""",
                (rs, i) -> new MoveLine(rs.getLong("part_id"), rs.getString("public_code"),
                        rs.getString("title"), rs.getBigDecimal("qty")),
                documentId);
    }

    public record MoveDocument(Long id, Long number, Instant createdAt,
                               String fromWarehouse, String toWarehouse,
                               int lines, String note, String author) {
    }

    public record MoveLine(Long partId, String publicCode, String title, BigDecimal qty) {
    }
}
