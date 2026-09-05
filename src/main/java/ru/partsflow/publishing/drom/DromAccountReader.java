package ru.partsflow.publishing.drom;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Кабинеты Дрома вместе с отбором их выгрузки.
 *
 * <p>Отдельный класс, потому что читают их двое и по-разному: выдача прайса —
 * по коду компании из ссылки, отправка дельты — по арендатору из сессии.
 * Разъехавшись, они дали бы дельту, собранную не тем отбором, что прайс:
 * позиция уехала бы в прайс-лист, в котором её быть не должно, и осталась бы
 * там до следующего полного забора.
 *
 * <p>Выгрузок на площадку у клиента несколько — у живого их пять, разложенных
 * по ценовым диапазонам, — поэтому это список, а не одна запись. Дельта
 * уходит в каждую: позиция, подорожавшая с двух тысяч до двадцати, покидает
 * один прайс-лист и появляется в другом, и узнать об этом должны оба.
 */
@Component
public class DromAccountReader {

    private static final String SQL = """
            SELECT id, feed_token, settings ->> 'packetId' AS packet_id,
                   settings::text AS settings, credentials,
                   product_line,
                   price_from, price_to, conditions, warehouse_ids,
                   kind_ids, kinds_excluded, brand_ids, brands_excluded,
                   filter_columns::text AS filter_columns,
                   filter_words::text AS filter_words
              FROM %smarketplace_account
             WHERE marketplace = 'DROM' AND status = 'ACTIVE'
             ORDER BY id""";

    private final JdbcTemplate jdbc;

    public DromAccountReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param schema имя схемы либо {@code null} — тогда имя не квалифицируется
     *               и запрос идёт по {@code search_path}, то есть по арендатору
     *               из сессии. Вызов с {@code null} обязан быть в транзакции:
     *               {@code search_path} выставляет провайдер соединений
     *               Hibernate, и снаружи запрос уйдёт в {@code public}
     */
    public List<Account> active(String schema) {
        return jdbc.query(SQL.formatted(schema == null ? "" : schema + "."),
                DromAccountReader::map);
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getLong("id"),
                rs.getString("feed_token"),
                rs.getString("packet_id"),
                rs.getBytes("credentials"),
                rs.getString("product_line"),
                ru.partsflow.publishing.FeedSettings.parse(rs.getString("settings")),
                new DromPriceGenerator.FeedFilter(
                        rs.getBigDecimal("price_from"),
                        rs.getBigDecimal("price_to"),
                        textList(rs.getArray("conditions")),
                        longList(rs.getArray("warehouse_ids")),
                        longList(rs.getArray("kind_ids")),
                        rs.getBoolean("kinds_excluded"),
                        longList(rs.getArray("brand_ids")),
                        rs.getBoolean("brands_excluded"),
                        filterMap(rs.getString("filter_columns")),
                        filterMap(rs.getString("filter_words"))));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Карта «колонка → значение» из jsonb: собирать её текстом руками нельзя. */
    private static java.util.Map<String, String> filterMap(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Map.of();
        }
        try {
            return JSON.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<
                            java.util.LinkedHashMap<String, String>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Отбор выгрузки не читается: " + json, e);
        }
    }

    private static List<String> textList(java.sql.Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static List<Long> longList(java.sql.Array array) throws SQLException {
        return array == null ? List.of() : List.of((Long[]) array.getArray());
    }

    /**
     * @param packetId    номер прайс-листа в кабинете; пусто — выгрузку ещё
     *                    не завёл технический специалист площадки, и слать
     *                    некуда
     * @param credentials ключ кабинета как он лежит в базе, зашифрованным
     * @param settings    как собирается файл: наценка на прайс и округление.
     *                    Читаются здесь, потому что и прайс, и дельта обязаны
     *                    собираться одними и теми же — иначе полный забор
     *                    поставит на площадке одну цену, а первая же дельта
     *                    перебьёт её другой
     */
    public record Account(long id, String feedToken, String packetId, byte[] credentials,
                          String productLine,
                          ru.partsflow.publishing.FeedSettings settings,
                          DromPriceGenerator.FeedFilter filter) {

        /** Выгрузка шин и дисков — у неё свой формат и свой генератор. */
        public boolean isWheelFeed() {
            return "WHEEL".equals(productLine);
        }

        /** Готов принимать дельты: есть и номер прайс-листа, и ключ. */
        public boolean canReceiveDelta() {
            return packetId != null && !packetId.isBlank()
                    && credentials != null && credentials.length > 0;
        }
    }
}
