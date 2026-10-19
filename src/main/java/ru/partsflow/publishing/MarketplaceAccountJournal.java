package ru.partsflow.publishing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.inventory.PartCondition;
import ru.partsflow.platform.audit.AuditJournal;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Что в выгрузке изменилось — для журнала действий (задача 0270).
 *
 * <p>Выгрузка правится прямым SQL мимо Hibernate, поэтому слушатель её не видит
 * и в журнал пишет сервис. Этот класс собирает «снимок» того, что владелец
 * вправе спросить («кто поставил наценку», «почему выгрузка выключена»), и
 * записывает разницу: только изменившиеся поля, по одному на строку экрана.
 *
 * <p><b>Порядок вызова один на все правки.</b> {@link #lock} до {@code UPDATE}
 * (читает прежнее под блокировкой строки — два одновременных сохранения иначе
 * записали бы одно «было»), потом {@link #changed} после него. Всё в транзакции
 * вызывающего ({@code MANDATORY}): откат правки не оставляет записи о том, чего
 * не было.
 *
 * <p><b>Снимок хранит слова, а не номера.</b> Склады, виды деталей и марки
 * записываются названиями на момент правки: номер строки базы журнал не назовёт,
 * а переименованный склад задним числом соврал бы о прошлой правке.
 *
 * <p><b>Секретов в снимке нет по построению</b> — ни ключа синхронизации, ни
 * ссылки на прайс, ни их шифрованного вида: читаются только перечисленные
 * ниже колонки. Замена секрета записывается фактом ({@link #secretReplaced}).
 */
@Component
class MarketplaceAccountJournal {

    static final String TABLE = "marketplace_account";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final AuditJournal journal;

    MarketplaceAccountJournal(JdbcTemplate jdbc, AuditJournal journal) {
        this.jdbc = jdbc;
        this.journal = journal;
    }

    /**
     * Прежнее состояние живой выгрузки под блокировкой строки.
     *
     * @return {@code null}, если живой выгрузки с таким номером нет: вызывающий
     *         сам откажет своим текстом, как отказывал и до журнала
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Map<String, String> lock(long id) {
        return read(id, true);
    }

    /**
     * Записывает то, что изменилось после {@code UPDATE}, по полям.
     * Ничего не изменилось — строки нет (повторное сохранение журнал не засоряет).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void changed(long id, Map<String, String> before) {
        if (before == null) {
            return;
        }
        Map<String, String> after = read(id, false);
        if (after == null) {
            return;
        }
        Map<String, String> was = new LinkedHashMap<>();
        Map<String, String> now = new LinkedHashMap<>();
        for (String key : after.keySet()) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                was.put(key, before.get(key));
                now.put(key, after.get(key));
            }
        }
        if (!now.isEmpty()) {
            journal.changed(TABLE, id, was, now);
        }
    }

    /**
     * Прежнее «есть ли секрет» под блокировкой строки.
     *
     * @param column {@code credentials} либо {@code feed_token}
     * @return {@code null}, если живой выгрузки нет
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Boolean lockSecret(long id, String column) {
        String expression = switch (column) {
            case "credentials" -> "credentials IS NOT NULL";
            case "feed_token" -> "feed_token IS NOT NULL";
            default -> throw new IllegalArgumentException("Не секрет: " + column);
        };
        List<Boolean> found = jdbc.query("SELECT " + expression
                        + " FROM marketplace_account WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                (rs, i) -> rs.getBoolean(1), id);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Замена секрета — фактом, без значения.
     *
     * <p>«Было» и «стало» — слова, а не ключ: журнал открыт ревизору, а ключ
     * даёт доступ к кабинету клиента на площадке. Замена всегда меняет слово
     * («задан» → «заменён»), поэтому строка не отфильтровывается как правка
     * без изменившегося поля.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void secretReplaced(long id, String column, Boolean hadBefore) {
        if (hadBefore == null) {
            return;
        }
        Map<String, String> was = new HashMap<>();
        Map<String, String> now = new HashMap<>();
        was.put(column, hadBefore ? "задан" : null);
        now.put(column, hadBefore ? "заменён" : "задан");
        journal.changed(TABLE, id, was, now);
    }

    /**
     * Заведение выгрузки. В «стало» только номер прайс-листа, и только если он
     * задан: пустой номер строкой не пишется.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void created(long id) {
        List<String> packet = jdbc.queryForList(
                "SELECT settings ->> 'packetId' FROM marketplace_account WHERE id = ?",
                String.class, id);
        Map<String, String> now = new LinkedHashMap<>();
        if (!packet.isEmpty() && packet.get(0) != null && !packet.get(0).isBlank()) {
            now.put("packet_id", packet.get(0));
        }
        journal.created(TABLE, id, now);
    }

    /**
     * Удаление. В «было» — название на этот момент: выгрузка скрыта
     * ({@code deleted_at}), и без него запись осталась бы без имени.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void deleted(long id, Map<String, String> before) {
        if (before == null) {
            return;
        }
        Map<String, String> was = new LinkedHashMap<>();
        was.put("feed_title", before.get("feed_title"));
        journal.deleted(TABLE, id, was);
    }

    // ------------------------------------------------------------------ снимок

    private Map<String, String> read(long id, boolean lock) {
        List<Map<String, String>> found = jdbc.query("""
                SELECT a.title, a.status, a.feed_file_name, a.settings::text AS settings,
                       a.price_from, a.price_to, a.conditions,
                       a.kinds_excluded, a.brands_excluded,
                       a.filter_columns::text AS filter_columns,
                       a.filter_words::text AS filter_words,
                       (SELECT string_agg(w.name, ', ' ORDER BY w.name)
                          FROM warehouse w WHERE w.id = ANY(a.warehouse_ids)) AS warehouses,
                       (SELECT string_agg(k.name, ', ' ORDER BY k.name)
                          FROM catalog.part_kind k WHERE k.id = ANY(a.kind_ids)) AS kinds,
                       (SELECT string_agg(b.name, ', ' ORDER BY b.name)
                          FROM catalog.brand b WHERE b.id = ANY(a.brand_ids)) AS brands
                  FROM marketplace_account a
                 WHERE a.id = ? AND a.deleted_at IS NULL""" + (lock ? " FOR UPDATE OF a" : ""),
                (rs, i) -> {
                    Map<String, String> snapshot = new LinkedHashMap<>();
                    snapshot.put("feed_title", rs.getString("title"));
                    snapshot.put("feed_status", rs.getString("status"));
                    snapshot.put("feed_file_name", rs.getString("feed_file_name"));
                    putSettings(snapshot, rs.getString("settings"));
                    snapshot.put("filter_price_from", plain(rs.getBigDecimal("price_from")));
                    snapshot.put("filter_price_to", plain(rs.getBigDecimal("price_to")));
                    snapshot.put("filter_conditions", conditions(rs.getArray("conditions")));
                    snapshot.put("filter_warehouses", rs.getString("warehouses"));
                    snapshot.put("filter_kinds", rs.getString("kinds"));
                    snapshot.put("filter_kinds_excluded",
                            String.valueOf(rs.getBoolean("kinds_excluded")));
                    snapshot.put("filter_brands", rs.getString("brands"));
                    snapshot.put("filter_brands_excluded",
                            String.valueOf(rs.getBoolean("brands_excluded")));
                    snapshot.put("filter_columns", pairs(rs.getString("filter_columns")));
                    snapshot.put("filter_words", pairs(rs.getString("filter_words")));
                    return snapshot;
                }, id);
        if (found.isEmpty()) {
            return null;
        }
        Map<String, String> snapshot = found.get(0);
        snapshot.put("donor_photo_kinds", donorKinds(snapshot.remove("donor_photo_kind_ids")));
        return snapshot;
    }

    /** Поля настроек сборки, по одному; номер прайс-листа здесь не участвует. */
    private static void putSettings(Map<String, String> into, String settingsJson) {
        JsonNode settings = parse(settingsJson);
        into.put("price_percent", number(settings, "pricePercent"));
        into.put("price_rounding", number(settings, "priceRounding"));
        into.put("photo_limit", number(settings, "photoLimit"));
        // Не заданное и «нет» — одно состояние: выключено. Иначе экран, впервые
        // сохранивший false вместо отсутствия, написал бы правку, которой не было.
        into.put("installation_note", String.valueOf(settings.path("installationNote").asBoolean(false)));
        into.put("installation_template", text(settings, "installationTemplate"));
        into.put("expected_goods", String.valueOf(settings.path("expectedGoods").asBoolean(false)));
        into.put("expected_goods_note", text(settings, "expectedGoodsNote"));
        into.put("reserved_goods", String.valueOf(settings.path("reservedGoods").asBoolean(false)));
        into.put("reserved_goods_note", text(settings, "reservedGoodsNote"));
        List<String> ids = new ArrayList<>();
        settings.path("donorPhotoKinds").forEach(node -> ids.add(node.asText()));
        into.put("donor_photo_kind_ids", ids.isEmpty() ? null : String.join(",", ids));
    }

    /** Наименования со снимками машины — названиями, как и остальные списки. */
    private String donorKinds(String ids) {
        if (ids == null || ids.isBlank()) {
            return null;
        }
        List<String> names = jdbc.queryForList(
                "SELECT name FROM catalog.part_kind WHERE id = ANY(?::bigint[]) ORDER BY name",
                String.class, "{" + ids + "}");
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private static JsonNode parse(String json) {
        try {
            return json == null ? JSON.createObjectNode() : JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return JSON.createObjectNode();
        }
    }

    private static String number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.isNumber() ? plain(value.decimalValue()) : value.asText();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** Число без хвоста нулей: «5.00» и «5» — одно и то же значение. */
    private static String plain(java.math.BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String conditions(Array array) {
        try {
            if (array == null) {
                return null;
            }
            Map<String, String> titles = PartCondition.titles();
            List<String> names = new ArrayList<>();
            for (Object code : (Object[]) array.getArray()) {
                names.add(titles.getOrDefault(String.valueOf(code), String.valueOf(code)));
            }
            names.sort(String::compareTo);
            return names.isEmpty() ? null : String.join(", ", names);
        } catch (SQLException e) {
            throw new IllegalStateException("Состояния отбора не прочитаны", e);
        }
    }

    /** Условия по колонкам одной строкой «колонка: значение; …», по алфавиту ключей. */
    private static String pairs(String json) {
        JsonNode node = parse(json);
        if (!node.isObject() || node.isEmpty()) {
            return null;
        }
        Map<String, String> sorted = new TreeMap<>();
        node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue().asText()));
        List<String> parts = new ArrayList<>();
        sorted.forEach((key, value) -> parts.add(key + ": " + value));
        return String.join("; ", parts);
    }
}
