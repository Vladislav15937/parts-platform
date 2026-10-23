package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.security.MemberSignature;

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
     *
     * <p><b>Удалённый (задача 0367) остаётся в журнале с отметкой, а не
     * пропадает.</b> Журнал отвечает на «что мы увозили в августе», и документ,
     * который увезли, а потом удалили, — часть ответа: его движения в журнале
     * склада остаются, и спрятанный здесь, он был бы следом, которому нечем
     * объясниться. Удалить его второй раз нельзя ({@code deletable}).
     */
    @Transactional(readOnly = true)
    public List<MoveDocument> list() {
        boolean mayDelete = PostedDocumentDeletion.allowed();
        return jdbc.query("""
                        SELECT d.id, d.number, d.created_at, d.note, d.doc_type, d.status,
                               wf.name AS from_warehouse, wt.name AS to_warehouse,
                        """
                        // Кто перевёз и кто удалил — подписью «Имя (логин, ID N)»
                        // (задача 0373, решение владельца 23.10.2026, пункт 5а).
                        + "       " + MemberSignature.sql("who") + " AS author,\n"
                        + "       d.deleted_at, " + MemberSignature.sql("del") + " AS deleted_by,\n"
                        + """
                               count(l.id) AS lines
                          FROM stock_document d
                          JOIN warehouse wf ON wf.id = d.warehouse_id
                          JOIN warehouse wt ON wt.id = d.to_warehouse_id
                          LEFT JOIN tenant_member who ON who.id = d.created_by
                          LEFT JOIN tenant_member del ON del.id = d.deleted_by
                          LEFT JOIN stock_document_line l ON l.document_id = d.id
                         WHERE d.doc_type = 'MOVE' AND d.status = 'DONE'
                         GROUP BY d.id, d.number, d.created_at, d.note, d.doc_type, d.status,
                                  wf.name, wt.name, who.id, d.deleted_at, del.id
                         ORDER BY d.created_at DESC, d.id DESC""",
                (rs, i) -> {
                    java.sql.Timestamp deletedAt = rs.getTimestamp("deleted_at");
                    // Правило «что удаляется» одно — StockDocument.isDeletable;
                    // фильтр запроса (MOVE, DONE) его не заменяет.
                    boolean removable = StockDocument.isDeletable(rs.getString("doc_type"),
                            rs.getString("status"), deletedAt != null);
                    return new MoveDocument(rs.getLong("id"), rs.getLong("number"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("from_warehouse"), rs.getString("to_warehouse"),
                            rs.getInt("lines"), rs.getString("note"), rs.getString("author"),
                            deletedAt == null ? null : deletedAt.toInstant(),
                            rs.getString("deleted_by"),
                            mayDelete && removable,
                            removable && !mayDelete ? PostedDocumentDeletion.NEEDS : null);
                });
    }

    /**
     * Состав документа: что и сколько уехало.
     *
     * <p>Ссылка в карточку позиции строится на клиенте по {@code partId},
     * а публичный код и наименование — чтобы кладовщик узнал деталь, не
     * открывая карточку.
     *
     * <p><b>И номер позиции</b> (задача 0168): состав документа открывают,
     * чтобы назвать увезённое другому человеку — «позиция 347 уехала
     * на Ангар», — а публичный код для разговора не годится, он шесть
     * случайных байт и живёт на этикетке.
     */
    @Transactional(readOnly = true)
    public List<MoveLine> lines(long documentId) {
        // Основной номер производителя (задача 0329): принимающий сверяет
        // деталь по номеру, когда наименование у двух одно.
        return jdbc.query("""
                        SELECT p.id AS part_id, p.number, p.public_code, p.title, p.condition,
                               l.qty,
                        """ + "       " + PartNaming.primaryOemSql("p") + "\n" + """
                          FROM stock_document_line l
                          JOIN part p ON p.id = l.part_id
                         WHERE l.document_id = ?
                         ORDER BY l.id""",
                (rs, i) -> new MoveLine(rs.getLong("part_id"), rs.getLong("number"),
                        rs.getString("public_code"),
                        rs.getString("title"), rs.getString("condition"),
                        rs.getString(PartNaming.OEM_COLUMN),
                        rs.getBigDecimal("qty")),
                documentId);
    }

    /**
     * @param deletedAt когда документ удалили (задача 0367); {@code null} — не удалён
     * @param deletedBy кто удалил
     * @param deletable может ли вошедший его удалить: не удалён и право есть
     *                  ({@link PostedDocumentDeletion}). Экран рисует кнопку
     *                  по этому признаку, а не по своему списку ролей
     * @param deleteNeeds право, которого не хватает, чтобы удалить документ,
     *                  который удалить можно в принципе: «Удалить» погашена
     *                  с причиной, а не спрятана (задача 0044, вопрос 3)
     */
    public record MoveDocument(Long id, Long number, Instant createdAt,
                               String fromWarehouse, String toWarehouse,
                               int lines, String note, String author,
                               Instant deletedAt, String deletedBy, boolean deletable,
                               String deleteNeeds) {
    }

    /**
     * @param number порядковый номер позиции — тот, которым её называют вслух
     *               (задача 0060). Публичный код рядом остаётся: его читают
     *               с этикетки на самой детали
     * @param condition состояние кодом (задача 0318), слово ставит экран:
     *                  кладовщик, принимающий перевозку, сверяет, та ли деталь
     *                  приехала, а новая и б/у с одним наименованием — разные
     * @param primaryOem основной номер производителя как хранится (задача 0329);
     *                   основного нет — пусто, кросс-номер не подставляется
     */
    public record MoveLine(Long partId, Long number, String publicCode, String title,
                           String condition, String primaryOem, BigDecimal qty) {
    }
}
