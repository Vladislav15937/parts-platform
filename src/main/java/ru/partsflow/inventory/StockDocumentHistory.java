package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.partsflow.platform.security.CurrentUser;
import ru.partsflow.platform.security.MemberSignature;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * «История документа» (задача 0360): кто и когда создал поступление, добавил
 * позицию, провёл, отменил проводку, отменил.
 *
 * <p>Пишется тем же действием, что меняет документ, и в той же транзакции —
 * вызывающий обязан быть в ней ({@code stock_document_event}, {@code tenant/107}).
 * Запись о действии, которое откатилось, не останется; действие без записи —
 * тоже.
 *
 * <p><b>Слова — с ориентира дословно</b> (решение владельца 19.10.2026 «Всё как
 * у bazon»): «Создано поступление», «Добавлена позиция N», «Поступление
 * проведено», «Отменено». «Проводка отменена» — наше: что пишет ориентир при
 * отмене проводки, не видели (кнопку не нажимали), слово взято с кнопки.
 * Номер позиции — её {@code part.number}, тот, что называют вслух.
 */
@Component
public class StockDocumentHistory {

    public enum Kind { CREATED, LINE_ADDED, POSTED, UNPOSTED, CANCELLED }

    private static final Map<String, String> WORDS = Map.of(
            "CREATED", "Создано поступление",
            "POSTED", "Поступление проведено",
            "UNPOSTED", "Проводка отменена",
            "CANCELLED", "Отменено");

    private final JdbcTemplate jdbc;

    public StockDocumentHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Действие без позиции: создание, проведение, отмена проводки, отмена. */
    public void record(Long documentId, Kind kind) {
        if (kind == Kind.LINE_ADDED) {
            throw new IllegalArgumentException("«Добавлена позиция» пишется с позицией");
        }
        insert(documentId, kind, null);
    }

    /** «Добавлена позиция N». */
    public void lineAdded(Long documentId, Long partId) {
        insert(documentId, Kind.LINE_ADDED, partId);
    }

    /** Последнее действие документа — этого вида: по нему узнаётся повтор нажатия. */
    public boolean lastIs(Long documentId, Kind kind) {
        return jdbc.query("""
                        SELECT kind FROM stock_document_event
                         WHERE document_id = ? ORDER BY id DESC LIMIT 1""",
                (rs, i) -> rs.getString(1), documentId)
                .stream().findFirst().map(kind.name()::equals).orElse(false);
    }

    private void insert(Long documentId, Kind kind, Long partId) {
        jdbc.update("""
                INSERT INTO stock_document_event (document_id, kind, part_id, created_by)
                VALUES (?, ?, ?, ?)""", documentId, kind.name(), partId, CurrentUser.memberId());
    }

    /**
     * Записи документа по порядку, свежие снизу — как лента ориентира.
     * Автор — подписью «Имя (логин, ID N)», как во всех журналах (задача 0373).
     */
    public List<Entry> of(Long documentId) {
        return jdbc.query("""
                        SELECT e.kind, e.created_at, p.id AS part_id, p.number, p.title,
                        """ + "       " + MemberSignature.sql("m") + " AS author\n" + """
                          FROM stock_document_event e
                          LEFT JOIN part p ON p.id = e.part_id
                          LEFT JOIN tenant_member m ON m.id = e.created_by
                         WHERE e.document_id = ?
                         ORDER BY e.id""",
                (rs, i) -> {
                    String kind = rs.getString("kind");
                    Long partId = (Long) rs.getObject("part_id");
                    String text = "LINE_ADDED".equals(kind)
                            ? "Добавлена позиция " + rs.getLong("number")
                            : WORDS.getOrDefault(kind, kind);
                    return new Entry(rs.getTimestamp("created_at").toInstant(),
                            rs.getString("author"), text, partId,
                            partId == null ? null : rs.getString("title"));
                },
                documentId);
    }

    /**
     * @param author   подпись сотрудника; пусто — действие без вошедшего
     * @param partId   позиция записи «Добавлена позиция» — экран открывает по ней карточку
     * @param partTitle наименование позиции под записью, как у ориентира
     */
    public record Entry(Instant at, String author, String text, Long partId, String partTitle) {
    }
}
