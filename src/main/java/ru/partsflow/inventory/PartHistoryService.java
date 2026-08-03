package ru.partsflow.inventory;

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
     * Поля, изменение которых видно человеку, и как они называются.
     *
     * <p>Порядок задаёт порядок вывода внутри одной правки: сначала то, ради
     * чего карточку чаще всего и открывают.
     */
    private static final Map<String, String> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("price", "Цена");
        FIELDS.put("min_price", "Минимальная цена");
        FIELDS.put("cost_price", "Себестоимость");
        FIELDS.put("installation_price", "Стоимость установки");
        FIELDS.put("title", "Наименование");
        FIELDS.put("condition", "Состояние");
        FIELDS.put("quality_grade", "Оценка состояния");
        FIELDS.put("description", "Комментарий");
        FIELDS.put("note", "Заметка");
        FIELDS.put("section", "Секция");
        FIELDS.put("is_published", "Выгружать");
        FIELDS.put("storage_cell_id", "Ячейка");
        FIELDS.put("donor_id", "Донор");
        FIELDS.put("part_kind_id", "Вид детали");
        FIELDS.put("supply_id", "Поставка");
        FIELDS.put("manufacturer", "Производитель");
        FIELDS.put("marking", "Маркировка");
        FIELDS.put("color", "Цвет");
        FIELDS.put("barcode", "Ст. баркод");
        FIELDS.put("legacy_code", "Старые данные");
        FIELDS.put("side_lr", "Левый / Правый");
        FIELDS.put("side_fr", "Передний / Задний");
        FIELDS.put("side_ud", "Верхний / Нижний");
        FIELDS.put("video_url", "Видео");
        FIELDS.put("text_block", "Текстовый блок");
        FIELDS.put("weight_kg", "Вес, кг");
        FIELDS.put("length_mm", "Длина, мм");
        FIELDS.put("width_mm", "Ширина, мм");
        FIELDS.put("height_mm", "Высота, мм");
        FIELDS.put("package_weight_kg", "Вес в упаковке, кг");
        FIELDS.put("package_length_mm", "Длина упаковки, мм");
        FIELDS.put("package_width_mm", "Ширина упаковки, мм");
        FIELDS.put("package_height_mm", "Высота упаковки, мм");
        FIELDS.put("product_line", "Вид товара");
    }

    /** Деньги владельца: продавцу эти правки не отдаются вовсе. */
    private static final Set<String> OWNER_ONLY = Set.of("cost_price", "min_price");

    /** В снимке лежит идентификатор, человеку нужно имя. */
    private static final Set<String> REFERENCES =
            Set.of("storage_cell_id", "donor_id", "part_kind_id", "supply_id");

    private static final Map<String, String> CONDITIONS =
            Map.of("NEW", "Новая", "USED", "Б/у", "REFURBISHED", "Восстановленная");

    private static final Map<String, String> GRADES = Map.of(
            "AS_NEW", "Как новая", "NO_DEFECTS", "Без дефектов",
            "WITH_DEFECTS", "С дефектами", "NEEDS_REPAIR", "Требует ремонт");

    private static final Map<String, String> SIDES = Map.of(
            "LEFT", "Левый", "RIGHT", "Правый", "FRONT", "Передний",
            "REAR", "Задний", "UPPER", "Верхний", "LOWER", "Нижний");

    private static final Map<String, String> LINES = Map.of(
            "PART", "Запчасть", "TYRE", "Шина", "DISC", "Диск", "WHEEL", "Колесо");

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

        List<Diff> diffs = jdbc.query("""
                        SELECT a.id, a.changed_at, m.display_name AS author, f.key,
                               a.old_value ->> f.key AS was,
                               a.new_value ->> f.key AS now
                          FROM audit_log a
                          CROSS JOIN unnest(string_to_array(?, ',')) AS f(key)
                          LEFT JOIN tenant_member m ON m.id = a.changed_by
                         WHERE a.table_name = 'part' AND a.record_id = ?
                           AND a.operation = 'UPDATE'
                           AND (a.old_value ->> f.key) IS DISTINCT FROM (a.new_value ->> f.key)
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
            change.fields().add(new Field(FIELDS.get(diff.column()),
                    display(diff.column(), diff.was(), lookup),
                    display(diff.column(), diff.now(), lookup)));
        }

        List<Change> changes = new ArrayList<>(byAudit.values());
        created(partId).ifPresent(changes::add);
        return changes;
    }

    private List<String> visibleFields(boolean money) {
        return FIELDS.keySet().stream()
                .filter(column -> money || !OWNER_ONLY.contains(column))
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
            if (!REFERENCES.contains(diff.column())) {
                continue;
            }
            Set<Long> ids = wanted.computeIfAbsent(diff.column(), k -> new LinkedHashSet<>());
            addId(ids, diff.was());
            addId(ids, diff.now());
        }

        Map<String, Map<Long, String>> titles = new HashMap<>();
        wanted.forEach((column, ids) -> titles.put(column, titlesOf(column, ids)));
        return titles;
    }

    private Map<Long, String> titlesOf(String column, Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String sql = switch (column) {
            case "storage_cell_id" -> "SELECT id, code AS title FROM storage_cell WHERE id IN (%s)";
            case "donor_id" -> """
                    SELECT id, coalesce(legacy_code, public_code) AS title
                      FROM donor WHERE id IN (%s)""";
            case "part_kind_id" ->
                    "SELECT id, name AS title FROM catalog.part_kind WHERE id IN (%s)";
            case "supply_id" ->
                    "SELECT id, kind || ' №' || number AS title FROM supply WHERE id IN (%s)";
            default -> null;
        };
        if (sql == null) {
            return Map.of();
        }
        Map<Long, String> found = new HashMap<>();
        jdbc.query(sql.formatted(placeholders(ids)),
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> found.put(rs.getLong("id"), rs.getString("title")),
                ids.toArray());
        return found;
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

    private static String display(String column, String raw, Map<Long, String> lookup) {
        if (raw == null) {
            return null;
        }
        if (REFERENCES.contains(column)) {
            try {
                // Ссылка на то, чего уже нет, — не повод показать пустоту:
                // номер говорит больше прочерка.
                return lookup.getOrDefault(Long.parseLong(raw), "№" + raw);
            } catch (NumberFormatException e) {
                return raw;
            }
        }
        return switch (column) {
            case "condition" -> CONDITIONS.getOrDefault(raw, raw);
            case "quality_grade" -> GRADES.getOrDefault(raw, raw);
            case "side_lr", "side_fr", "side_ud" -> SIDES.getOrDefault(raw, raw);
            case "product_line" -> LINES.getOrDefault(raw, raw);
            case "is_published" -> "true".equals(raw) ? "да" : "нет";
            default -> raw;
        };
    }

    private static String placeholders(Collection<Long> ids) {
        return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
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
                        SELECT m.created_at, m.movement_type, m.qty_delta, m.reason,
                               d.number AS doc_number, d.doc_type, d.status AS doc_status,
                               wf.name AS from_warehouse, wt.name AS to_warehouse,
                               deal.number AS deal_number, ret.number AS return_number,
                               inv.id AS inventory_id,
                               who.display_name AS author
                          FROM stock_movement m
                          LEFT JOIN stock_document d  ON d.id = m.document_id
                          LEFT JOIN warehouse wf      ON wf.id = m.from_warehouse_id
                          LEFT JOIN warehouse wt      ON wt.id = m.to_warehouse_id
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
                        warehouseOf(rs.getString("from_warehouse"), rs.getString("to_warehouse")),
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

    private static String warehouseOf(String from, String to) {
        if (from != null && to != null) {
            return from + " → " + to;
        }
        return from != null ? from : to;
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
