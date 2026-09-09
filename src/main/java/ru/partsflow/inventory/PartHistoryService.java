package ru.partsflow.inventory;

import ru.partsflow.shared.AuditedColumns;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * История позиции: кто её правил и чем двигался остаток.
 *
 * <p><b>Две ленты, а не одна, и это не оформление.</b> «Кто уронил цену»
 * и «куда делась деталь» — разные вопросы, их задают в разные моменты
 * и разные люди. Смешанные в один список, они дают ленту, где правка заметки
 * стоит между продажей и возвратом, и найти в ней движение остатка нельзя.
 * Так же разведено и в кабинете, с которого снят паритет.
 *
 * <p><b>Правки берутся из {@code audit_log}, который пишет триггер.</b>
 * Не из кода: прямой SQL мимо приложения журнал не обойдёт, а ровно от этого
 * он и защищает. Даром это досталось не полностью — триггер кладёт снимок всей
 * строки на каждое изменение, и большинство записей рождают сами движения
 * склада: остаток и статус ведут триггеры. У переехавшего клиента таких
 * снимков 141 955 на 35 841 позицию. Поэтому снимки сравниваются между собой,
 * и в ленту попадает только то, что человек менял руками.
 *
 * <p><b>Сравнение идёт в SQL, а не в Java.</b> Вытаскивать полные снимки
 * строки, чтобы отбросить девять десятых, — это мегабайты jsonb на карточку.
 * Postgres отдаёт сразу изменившиеся поля.
 *
 * <p><b>Список полей закрытый, и это не про удобство.</b> Себестоимость
 * и минимальная цена — деньги владельца, а карточку открывает и продавец:
 * они не «скрыты» на экране, а не приезжают с сервера. Остаток же и статус
 * не показываются в первой ленте вовсе — они целиком во второй, и дублировать
 * их значит вернуть ту самую кашу, ради которой лент две.
 */
@Service
public class PartHistoryService {

    /**
     * Поля, изменение которых видно человеку, и как они называются, —
     * в {@code shared.AuditedColumns}, а не здесь.
     *
     * <p>Тот же словарь читает журнал действий организации
     * ({@code platform.audit.OrganizationAuditService}): владелец, увидевший
     * там «Оценка состояния», открывает эту карточку и обязан увидеть то же
     * слово. Копия разошлась бы не в момент копирования, а позже — когда
     * правят одну из двух.
     *
     * <p>Себестоимость и минимальная цена — деньги владельца, а карточку
     * открывает и продавец: они не «скрываются на экране», а не приезжают
     * с сервера. Остаток же и статус не показываются в первой ленте вовсе —
     * они целиком во второй, и дублировать их значит вернуть ту самую кашу,
     * ради которой лент две.
     */
    private static final Map<String, String> MOVEMENTS = Map.of(
            "INTAKE", "Поступление", "MOVE", "Перемещение", "SALE", "Продажа",
            "RETURN", "Возврат", "WRITE_OFF", "Списание",
            "INVENTORY_ADJUST", "Корректировка");

    private static final Map<String, String> DOCUMENTS = Map.of(
            "INTAKE", "Поступление", "MOVE", "Перемещение", "WRITE_OFF", "Списание",
            "RETURN", "Возврат", "INVENTORY", "Пересчёт");

    private static final Map<String, String> DOC_STATUSES =
            Map.of("DRAFT", "Черновик", "DONE", "Проведён", "CANCELLED", "Отменён");

    private final JdbcTemplate jdbc;

    public PartHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param money отдавать ли себестоимость и минимальную цену —
     *              право проверяет контроллер, сюда приезжает решение
     */
    @Transactional(readOnly = true)
    public History of(long partId, boolean money) {
        return new History(changes(partId, money), movements(partId));
    }

    // ------------------------------------------------------------------ правки

    private List<Change> changes(long partId, boolean money) {
        String columns = String.join(",", visibleFields(money));

        String was = AuditedColumns.normalized("a.old_value ->> f.key");
        String now = AuditedColumns.normalized("a.new_value ->> f.key");

        List<Diff> diffs = jdbc.query("""
                        SELECT a.id, a.changed_at, m.display_name AS author, f.key,
                        """ + was + """
                            AS was,
                        """ + now + """
                            AS now
                          FROM audit_log a
                          CROSS JOIN unnest(string_to_array(?, ',')) AS f(key)
                          LEFT JOIN tenant_member m ON m.id = a.changed_by
                         WHERE a.table_name = 'part' AND a.record_id = ?
                           AND a.operation = 'UPDATE'
                           AND\s""" + was + " IS DISTINCT FROM " + now + """

                         ORDER BY a.changed_at DESC, a.id DESC,
                                  array_position(string_to_array(?, ','), f.key)""",
                (rs, i) -> new Diff(rs.getLong("id"),
                        rs.getTimestamp("changed_at").toInstant(),
                        rs.getString("author"),
                        rs.getString("key"),
                        rs.getString("was"),
                        rs.getString("now")),
                columns, partId, columns);

        Map<String, Map<Long, String>> titles = resolveReferences(diffs);

        Map<Long, Change> byAudit = new LinkedHashMap<>();
        for (Diff diff : diffs) {
            Change change = byAudit.computeIfAbsent(diff.auditId(),
                    id -> new Change(diff.at(), diff.author(), null, new ArrayList<>()));
            Map<Long, String> lookup = titles.getOrDefault(diff.column(), Map.of());
            change.fields().add(new Field(AuditedColumns.PART.get(diff.column()),
                    AuditedColumns.display(diff.column(), diff.was(), lookup),
                    AuditedColumns.display(diff.column(), diff.now(), lookup)));
        }

        List<Change> changes = new ArrayList<>(byAudit.values());
        created(partId).ifPresent(changes::add);
        return changes;
    }

    private List<String> visibleFields(boolean money) {
        return AuditedColumns.PART.keySet().stream()
                .filter(column -> money || !AuditedColumns.MONEY.contains(column))
                .toList();
    }

    /**
     * Заведение карточки — последней строкой ленты: она читается сверху вниз,
     * от свежего к старому.
     */
    private java.util.Optional<Change> created(long partId) {
        return jdbc.query("""
                        SELECT a.changed_at, m.display_name AS author
                          FROM audit_log a
                          LEFT JOIN tenant_member m ON m.id = a.changed_by
                         WHERE a.table_name = 'part' AND a.record_id = ?
                           AND a.operation = 'INSERT'
                         ORDER BY a.changed_at LIMIT 1""",
                (rs, i) -> new Change(rs.getTimestamp("changed_at").toInstant(),
                        rs.getString("author"), "Товар создан", List.of()),
                partId).stream().findFirst();
    }

    /**
     * Имена вместо идентификаторов — по одному запросу на вид ссылки,
     * а не по запросу на строку: правок у позиции бывают десятки.
     */
    private Map<String, Map<Long, String>> resolveReferences(List<Diff> diffs) {
        Map<String, Set<Long>> wanted = new HashMap<>();
        for (Diff diff : diffs) {
            if (!AuditedColumns.REFERENCES.contains(diff.column())) {
                continue;
            }
            Set<Long> ids = wanted.computeIfAbsent(diff.column(), k -> new LinkedHashSet<>());
            addId(ids, diff.was());
            addId(ids, diff.now());
        }

        Map<String, Map<Long, String>> titles = new HashMap<>();
        wanted.forEach((column, ids) -> titles.put(column, AuditedColumns.titlesOf(jdbc, column, ids)));
        return titles;
    }

    private static void addId(Set<Long> into, String raw) {
        if (raw == null) {
            return;
        }
        try {
            into.add(Long.parseLong(raw));
        } catch (NumberFormatException ignored) {
            // Не идентификатор — покажется как есть.
        }
    }

    /**
     * Словарь названий, терпимый к пустоте.
     *
     * <p>{@code Map.of} — неизменяемая карта, и она бросает
     * {@code NullPointerException} даже на чтении по {@code null}-ключу,
     * а не отвечает «нет такого». У движения перенесённого склада статуса
     * документа нет вовсе, и вся история падала пятисоткой на первой же
     * такой строке. Поймано тестом, а не чтением кода.
     */
    private static String label(Map<String, String> dictionary, String key) {
        if (key == null) {
            return null;
        }
        return dictionary.getOrDefault(key, key);
    }

    // --------------------------------------------------------------- движения

    private List<Movement> movements(long partId) {
        return jdbc.query("""
                        SELECT m.created_at, m.movement_type, m.qty_delta,
                               -- Причина списания лежит в документе, а не
                               -- в движении: списание оформляется документом,
                               -- и «почему» пишется там. Пока история читала
                               -- только m.reason, у списания в ленте стоял
                               -- прочерк — при том что причина обязательна
                               -- и её только что ввели руками. Единственная
                               -- операция, уносящая товар без покупателя
                               -- и без денег, и «почему» через месяц
                               -- не восстановить ничем, кроме этой строки.
                               coalesce(m.reason, d.note) AS reason,
                               d.number AS doc_number, d.doc_type, d.status AS doc_status,
                               m.from_warehouse_id, m.to_warehouse_id,
                               wf.name AS from_warehouse, wt.name AS to_warehouse,
                               -- Полки нужны перестановке: у неё склад один
                               -- и тот же, и «Основной → Основной» не говорит
                               -- ничего — а сказать надо, с какой полки
                               -- на какую.
                               cf.code AS from_cell, ct.code AS to_cell,
                               deal.number AS deal_number, ret.number AS return_number,
                               inv.id AS inventory_id,
                               who.display_name AS author
                          FROM stock_movement m
                          LEFT JOIN stock_document d  ON d.id = m.document_id
                          LEFT JOIN warehouse wf      ON wf.id = m.from_warehouse_id
                          LEFT JOIN warehouse wt      ON wt.id = m.to_warehouse_id
                          LEFT JOIN storage_cell cf   ON cf.id = m.from_cell_id
                          LEFT JOIN storage_cell ct   ON ct.id = m.to_cell_id
                          LEFT JOIN deal              ON m.ref_type = 'DEAL' AND deal.id = m.ref_id
                          LEFT JOIN deal_return ret   ON m.ref_type = 'RETURN' AND ret.id = m.ref_id
                          -- Недостача объясняется пересчётом: без этого в ленте
                          -- стоит «Корректировка −2» и больше ничего.
                          LEFT JOIN inventory_session inv ON m.ref_type = 'INVENTORY'
                                                         AND inv.id = m.ref_id
                          LEFT JOIN tenant_member who ON who.id = m.created_by
                         WHERE m.part_id = ?
                         ORDER BY m.created_at DESC, m.id DESC""",
                (rs, i) -> new Movement(
                        rs.getTimestamp("created_at").toInstant(),
                        label(MOVEMENTS, rs.getString("movement_type")),
                        rs.getBigDecimal("qty_delta"),
                        documentOf(rs.getObject("doc_number"), rs.getString("doc_type"),
                                rs.getObject("deal_number"), rs.getObject("return_number"),
                                rs.getObject("inventory_id")),
                        label(DOC_STATUSES, rs.getString("doc_status")),
                        placeOf(rs.getString("from_warehouse"), rs.getString("to_warehouse"),
                                (Long) rs.getObject("from_warehouse_id"),
                                (Long) rs.getObject("to_warehouse_id"),
                                rs.getString("from_cell"), rs.getString("to_cell")),
                        rs.getString("reason"),
                        rs.getString("author")),
                partId);
    }

    /**
     * Чем движение оформлено.
     *
     * <p>У перенесённого склада документа нет вовсе: импорт пишет движение
     * напрямую, потому что документа в чужой выгрузке не было. Прочерк тут
     * честнее выдуманного номера.
     */
    private static String documentOf(Object docNumber, String docType,
                                     Object dealNumber, Object returnNumber,
                                     Object inventoryId) {
        if (docNumber != null) {
            return "%s №%s".formatted(label(DOCUMENTS, docType), docNumber);
        }
        if (dealNumber != null) {
            return "Сделка №" + dealNumber;
        }
        if (returnNumber != null) {
            return "Возврат №" + returnNumber;
        }
        if (inventoryId != null) {
            return "Пересчёт №" + inventoryId;
        }
        return null;
    }

    /**
     * Где это произошло: склад, а у перестановки — ещё и обе полки.
     *
     * <p>Перестановка на другую полку того же склада записана движением
     * с одинаковыми складами, и «Основной → Основной» не говорит ничего.
     * Между тем это единственное место, где след перекладки виден человеку
     * у позиции, лежащей на двух складах: лента правок про неё молчит
     * намеренно — `part.storage_cell_id` одно поле на весь товар, и правка
     * его сравнила бы полку одного склада с полкой другого.
     *
     * <p>Склады сравниваются по номеру, а не по названию: два склада могут
     * называться одинаково, и «Ткацкая → Ткацкая» тогда стало бы
     * перестановкой, которой не было.
     */
    private static String placeOf(String from, String to, Long fromId, Long toId,
                                  String fromCell, String toCell) {
        if (fromId != null && fromId.equals(toId)) {
            return "%s · %s → %s".formatted(from, cellName(fromCell), cellName(toCell));
        }
        if (from != null && to != null) {
            return from + " → " + to;
        }
        return from != null ? from : to;
    }

    /**
     * Пусто — «без адреса», а не прочерк: у клиента без полок ячеек нет
     * вовсе, и это «не заведено», а не «не знаем». Тем же словом называет
     * пустой адрес карточка.
     */
    private static String cellName(String code) {
        return code == null ? "без адреса" : code;
    }

    // ----------------------------------------------------------------- ответы

    public record History(List<Change> changes, List<Movement> movements) {
    }

    /**
     * @param author  {@code null} у правок, сделанных до того, как приложение
     *                начало сообщать базе вошедшего, и у всего, что приехало
     *                переносом. Врать тут нельзя — экран показывает прочерк
     * @param action  заполнен у событий без полей («Товар создан»)
     */
    public record Change(Instant at, String author, String action, List<Field> fields) {
    }

    public record Field(String label, String before, String after) {
    }

    public record Movement(Instant at, String type, BigDecimal qty, String document,
                           String status, String warehouse, String reason, String author) {
    }

    private record Diff(long auditId, Instant at, String author,
                        String column, String was, String now) {
    }
}
