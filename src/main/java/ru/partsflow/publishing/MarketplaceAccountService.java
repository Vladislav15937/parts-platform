package ru.partsflow.publishing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.crypto.SecretCipher;
import ru.partsflow.platform.tenant.TenantContext;

import java.security.SecureRandom;
import java.util.Base64;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Кабинеты площадок.
 *
 * <p>Единственное место, где секрет кабинета попадает в базу и достаётся
 * из неё. Заводился он до этого только руками через SQL — то есть открытым
 * текстом, потому что зашифровать его в psql нечем.
 *
 * <p><b>Секрет наружу не отдаётся никогда.</b> Ни в списке, ни поштучно:
 * его вводят один раз и потом только заменяют. Эндпоинт «показать ключ»
 * превращает права на чтение настроек в доступ к кабинету клиента.
 */
@Service
public class MarketplaceAccountService {

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public MarketplaceAccountService(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public List<Account> list() {
        return jdbc.query("""
                SELECT id, marketplace, title, status, last_sync_at IS NOT NULL AS synced,
                       last_error, credentials IS NOT NULL AS has_credentials,
                       credentials, feed_token IS NOT NULL AS has_feed,
                       price_from, price_to, conditions, warehouse_ids,
                       kind_ids, kinds_excluded, brand_ids, brands_excluded
                  FROM marketplace_account
                 ORDER BY marketplace, title""",
                (rs, i) -> new Account(
                        rs.getLong("id"),
                        rs.getString("marketplace"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getBoolean("has_credentials"),
                        // Открытый текст в базе — повод показать это в интерфейсе,
                        // а не только в журнале: чинит это человек.
                        rs.getBoolean("has_credentials")
                                && !SecretCipher.isEncrypted(rs.getBytes("credentials")),
                        rs.getBoolean("has_feed"),
                        rs.getString("last_error"),
                        rs.getBigDecimal("price_from"),
                        rs.getBigDecimal("price_to"),
                        textList(rs.getArray("conditions")),
                        longList(rs.getArray("warehouse_ids")),
                        longList(rs.getArray("kind_ids")),
                        rs.getBoolean("kinds_excluded"),
                        longList(rs.getArray("brand_ids")),
                        rs.getBoolean("brands_excluded")));
    }

    private static List<String> textList(java.sql.Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static List<Long> longList(java.sql.Array array) throws SQLException {
        return array == null ? List.of() : List.of((Long[]) array.getArray());
    }

    /**
     * Меняет отбор товара в выгрузку.
     *
     * <p>Пустое значение — «без ограничения», а не «ничего»: выгрузка,
     * у которой стёрли цену, обязана вернуться к отдаче всего склада.
     * Пустой прайс площадка примет молча, и объявления пропадут вместе
     * с накопленными просмотрами.
     *
     * <p>Отдельным действием от смены ссылки: смена ссылки останавливает
     * выгрузку до тех пор, пока техспециалист площадки не пропишет новую,
     * а правка отбора применяется к следующему забору сама.
     */
    @Transactional
    public Account setFilter(Long id, java.math.BigDecimal priceFrom,
                             java.math.BigDecimal priceTo,
                             List<String> conditions, List<Long> warehouseIds,
                             List<Long> kindIds, boolean kindsExcluded,
                             List<Long> brandIds, boolean brandsExcluded) {
        if (priceFrom != null && priceTo != null && priceFrom.compareTo(priceTo) > 0) {
            throw new IllegalArgumentException("Нижняя граница цены больше верхней");
        }
        int updated = jdbc.update("""
                UPDATE marketplace_account
                   SET price_from = ?, price_to = ?,
                       conditions = ?::text[], warehouse_ids = ?::bigint[],
                       kind_ids = ?::bigint[], kinds_excluded = ?,
                       brand_ids = ?::bigint[], brands_excluded = ?
                 WHERE id = ?""",
                priceFrom, priceTo,
                conditions == null || conditions.isEmpty() ? null : arrayLiteral(conditions),
                warehouseIds == null || warehouseIds.isEmpty() ? null : arrayLiteral(warehouseIds),
                kindIds == null || kindIds.isEmpty() ? null : arrayLiteral(kindIds),
                kindsExcluded,
                brandIds == null || brandIds.isEmpty() ? null : arrayLiteral(brandIds),
                brandsExcluded,
                id);
        if (updated == 0) {
            throw new IllegalArgumentException("Выгрузка не найдена: " + id);
        }
        return list().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    private static String arrayLiteral(List<?> values) {
        return values.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    /**
     * Заводит кабинет площадки.
     *
     * <p>Настройки приходят как есть: у каждой площадки они свои, и у Дрома
     * это {@code packetId} из адреса прайс-листа в кабинете клиента. Проверять
     * их состав здесь нечем — правильность видна только по ответу площадки,
     * и он попадает в {@code publication_log}.
     *
     * <p>Ключ отдельным запросом, а не здесь: он секрет, и смешивать его
     * с настройками значит однажды вернуть его в ответе на чтение списка.
     */
    @Transactional
    public Account create(String marketplace, String title, String settingsJson) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Название кабинета обязательно");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, settings)
                VALUES (?, ?, COALESCE(?::jsonb, '{}'::jsonb))
                RETURNING id""",
                Long.class, marketplace, title.strip(),
                settingsJson == null || settingsJson.isBlank() ? null : settingsJson);

        return list().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    /** Заводит или заменяет секрет кабинета. Возврата наружу у него нет. */
    @Transactional
    public void setCredentials(long accountId, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Пустой ключ кабинета не имеет смысла");
        }
        int updated = jdbc.update("UPDATE marketplace_account SET credentials = ? WHERE id = ?",
                cipher.encrypt(secret), accountId);

        if (updated == 0) {
            throw new IllegalArgumentException("Кабинет не найден: " + accountId);
        }
    }

    /**
     * Заводит ссылку на прайс или меняет её.
     *
     * <p>Смена — не косметика: старая ссылка перестаёт работать сразу, и прайс
     * у площадки замрёт, пока новую не пропишет её техспециалист. Поэтому
     * отдельным действием, а не автоматически при каждом сохранении настроек.
     *
     * @return новый токен
     */
    @Transactional
    public String rotateFeedToken(long accountId) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        // Без padding: токен едет в пути URL, а '=' там лишний.
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        int updated = jdbc.update(
                "UPDATE marketplace_account SET feed_token = ? WHERE id = ?", token, accountId);
        if (updated == 0) {
            throw new IllegalArgumentException("Кабинет не найден: " + accountId);
        }
        return token;
    }

    /**
     * Путь к прайсу, который прописывают в кабинете площадки.
     *
     * <p>Код компании берётся из реестра по текущей схеме, а не из параметра:
     * подставленный снаружи, он дал бы ссылку, ведущую к чужому арендатору —
     * точнее, к отказу, но искать причину пришлось бы долго.
     */
    @Transactional(readOnly = true)
    public Optional<String> feedPath(long accountId) {
        String companyCode = jdbc.queryForObject(
                "SELECT code FROM public.tenant_registry WHERE schema_name = ?",
                String.class, TenantContext.require());
        List<String> found = jdbc.query(
                "SELECT feed_token FROM marketplace_account WHERE id = ?",
                (rs, i) -> rs.getString("feed_token"), accountId);

        if (found.isEmpty() || found.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of("/feeds/drom/%s/%s.xml".formatted(companyCode, found.get(0)));
    }

    /**
     * Секрет для обращения к площадке.
     *
     * <p>Только для кода выгрузки. Наружу этот метод не выходит и выходить
     * не должен.
     */
    @Transactional(readOnly = true)
    public Optional<String> secretOf(long accountId) {
        List<byte[]> found = jdbc.query(
                "SELECT credentials FROM marketplace_account WHERE id = ?",
                (rs, i) -> rs.getBytes("credentials"), accountId);

        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(cipher.decrypt(found.get(0)));
    }

    /**
     * @param plaintextSecret секрет лежит незашифрованным — его надо перезаписать
     */
    /**
     * @param priceFrom   пусто — без нижней границы, а не «ноль»
     * @param conditions  пусто — любое состояние детали
     * @param warehouseIds пусто — все склады
     */
    public record Account(Long id, String marketplace, String title, String status,
                          boolean hasCredentials, boolean plaintextSecret, boolean hasFeed,
                          String lastError, java.math.BigDecimal priceFrom,
                          java.math.BigDecimal priceTo, List<String> conditions,
                          List<Long> warehouseIds, List<Long> kindIds, boolean kindsExcluded,
                          List<Long> brandIds, boolean brandsExcluded) {
    }

    /**
     * Сколько позиций попадёт в выгрузку с таким отбором.
     *
     * <p>Ради этого числа отдельный запрос и существует. Список по видам
     * деталей на неразобранном справочнике даёт пустой прайс: у только что
     * переехавшего клиента {@code part_kind_id} не заполнен ни у одной
     * позиции, пока наименования не сопоставлены. Площадка пустой прайс
     * примет молча, и объявления пропадут вместе с просмотрами — а узнают
     * об этом через сутки. Поэтому владелец видит число до сохранения,
     * как и в кабинете Bazon.
     */
    @Transactional(readOnly = true)
    public long countMatching(java.math.BigDecimal priceFrom, java.math.BigDecimal priceTo,
                              List<String> conditions, List<Long> warehouseIds,
                              List<Long> kindIds, boolean kindsExcluded,
                              List<Long> brandIds, boolean brandsExcluded) {
        Long found = jdbc.queryForObject("""
                SELECT count(*) FROM part p
                  LEFT JOIN donor d ON d.id = p.donor_id
                 WHERE p.is_published
                   -- Колёса из прайса запчастей исключает генератор, и счётчик
                   -- обязан исключать их тем же условием. Пока он их считал,
                   -- он обещал вдвое больше, чем уезжало, — то есть врал ровно
                   -- в ту сторону, ради которой заведён: успокаивал числом.
                   AND p.product_line = 'PART'
                   AND p.status IN ('IN_STOCK', 'SOLD')
                   AND p.price IS NOT NULL
                   AND (?::numeric IS NULL OR p.price >= ?::numeric)
                   AND (?::numeric IS NULL OR p.price <= ?::numeric)
                   AND (?::text[] IS NULL OR p.condition = ANY (?::text[]))
                   AND (?::bigint[] IS NULL OR EXISTS (
                           SELECT 1 FROM part_stock s
                            WHERE s.part_id = p.id AND s.warehouse_id = ANY (?::bigint[])))
                   AND (?::bigint[] IS NULL OR
                        CASE WHEN ?::boolean
                             THEN COALESCE(p.part_kind_id <> ALL (?::bigint[]), true)
                             ELSE p.part_kind_id = ANY (?::bigint[]) END)
                   AND (?::bigint[] IS NULL OR
                        CASE WHEN ?::boolean
                             THEN COALESCE(d.brand_id <> ALL (?::bigint[]), true)
                             ELSE d.brand_id = ANY (?::bigint[]) END)""",
                Long.class,
                priceFrom, priceFrom, priceTo, priceTo,
                text(conditions), text(conditions),
                longs(warehouseIds), longs(warehouseIds),
                longs(kindIds), kindsExcluded, longs(kindIds), longs(kindIds),
                longs(brandIds), brandsExcluded, longs(brandIds), longs(brandIds));
        return found == null ? 0 : found;
    }

    private static String text(List<String> values) {
        return values == null || values.isEmpty() ? null : arrayLiteral(values);
    }

    private static String longs(List<Long> values) {
        return values == null || values.isEmpty() ? null : arrayLiteral(values);
    }
}
