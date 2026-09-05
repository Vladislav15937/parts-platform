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

    /*
     * Отбор по колонкам собирают сами экраны склада: у запчастей свой белый
     * список колонок и выражений, у колёс свой. Повторить их здесь значило бы
     * завести вторую правду об одном и том же — и получить колонку, которую
     * таблица показывает, а выгрузка не отбирает.
     */
    private final ru.partsflow.inventory.CatalogService parts;
    private final ru.partsflow.inventory.WheelService wheels;

    public MarketplaceAccountService(JdbcTemplate jdbc, SecretCipher cipher,
                                     ru.partsflow.inventory.CatalogService parts,
                                     ru.partsflow.inventory.WheelService wheels) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.parts = parts;
        this.wheels = wheels;
    }

    @Transactional(readOnly = true)
    public List<Account> list() {
        return jdbc.query("""
                SELECT id, marketplace, title, status, last_sync_at IS NOT NULL AS synced,
                       last_feed_download_at,
                       last_error, credentials IS NOT NULL AS has_credentials,
                       credentials, feed_token IS NOT NULL AS has_feed,
                       feed_file_name,
                       product_line,
                       settings::text AS settings,
                       price_from, price_to, conditions, warehouse_ids,
                       kind_ids, kinds_excluded, brand_ids, brands_excluded,
                       filter_columns::text AS filter_columns,
                       filter_words::text AS filter_words
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
                        // Пусто — имени не задавали, и ссылка кончается токеном:
                        // это рабочее состояние, а не незаполненное поле.
                        rs.getString("feed_file_name"),
                        rs.getString("product_line"),
                        // Ключ кабинета лежит в соседней колонке и наружу
                        // не выходит; настройки сборки прайса — не секрет,
                        // их владелец сам и задаёт с экрана.
                        FeedSettings.parse(rs.getString("settings")),
                        rs.getString("last_error"),
                        instant(rs.getObject("last_feed_download_at",
                                java.time.OffsetDateTime.class)),
                        rs.getBigDecimal("price_from"),
                        rs.getBigDecimal("price_to"),
                        textList(rs.getArray("conditions")),
                        longList(rs.getArray("warehouse_ids")),
                        longList(rs.getArray("kind_ids")),
                        rs.getBoolean("kinds_excluded"),
                        longList(rs.getArray("brand_ids")),
                        rs.getBoolean("brands_excluded"),
                        map(rs.getString("filter_columns")),
                        map(rs.getString("filter_words"))));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Карта «колонка → значение» из jsonb.
     *
     * <p>Читается текстом и разбирается Jackson'ом, а не собирается руками:
     * {@code jsonb} возвращает не тот текст, который в него записали, —
     * свой порядок ключей и свои пробелы. На этом уже спотыкался список
     * пропущенных строк импорта.
     */
    private static java.util.Map<String, String> map(String json) {
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

    private static String json(java.util.Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        try {
            return JSON.writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Отбор выгрузки не записывается", e);
        }
    }

    /**
     * Момент времени или {@code null}.
     *
     * <p>{@code null} здесь означает «не забирали ни разу», а не «ноль»:
     * подставленная эпоха Unix превратилась бы на экране в «01.01.1970» —
     * поломку на месте честного ответа.
     */
    private static java.time.Instant instant(java.time.OffsetDateTime at) {
        return at == null ? null : at.toInstant();
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
                             List<Long> brandIds, boolean brandsExcluded,
                             java.util.Map<String, String> columns,
                             java.util.Map<String, String> words) {
        if (priceFrom != null && priceTo != null && priceFrom.compareTo(priceTo) > 0) {
            throw new IllegalArgumentException("Нижняя граница цены больше верхней");
        }
        /*
         * Колонка проверяется той линией товара, которой торгует выгрузка.
         *
         * У колеса нет стороны, у запчасти нет сезона, и списки колонок
         * у них разные. Принятое молча чужое условие ломается не здесь,
         * а при заборе прайса — и до появления проверки площадка получала
         * пустой файл, то есть команду снять все объявления. Владелец при
         * этом видел сохранённый отбор и ничего подозрительного.
         */
        checkColumns(productLineOf(id), columns, words);
        int updated = jdbc.update("""
                UPDATE marketplace_account
                   SET price_from = ?, price_to = ?,
                       conditions = ?::text[], warehouse_ids = ?::bigint[],
                       kind_ids = ?::bigint[], kinds_excluded = ?,
                       brand_ids = ?::bigint[], brands_excluded = ?,
                       filter_columns = ?::jsonb, filter_words = ?::jsonb
                 WHERE id = ?""",
                priceFrom, priceTo,
                conditions == null || conditions.isEmpty() ? null : arrayLiteral(conditions),
                warehouseIds == null || warehouseIds.isEmpty() ? null : arrayLiteral(warehouseIds),
                kindIds == null || kindIds.isEmpty() ? null : arrayLiteral(kindIds),
                kindsExcluded,
                brandIds == null || brandIds.isEmpty() ? null : arrayLiteral(brandIds),
                brandsExcluded,
                json(columns), json(words),
                id);
        if (updated == 0) {
            throw new IllegalArgumentException("Выгрузка не найдена: " + id);
        }
        return list().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Проверяет имена колонок белым списком той линии, которой торгует
     * выгрузка, — тем же, каким потом собирается прайс.
     */
    private void checkColumns(String productLine, java.util.Map<String, String> columns,
                              java.util.Map<String, String> words) {
        if ("WHEEL".equals(line(productLine))) {
            wheels.columnFilter(columns, words);
        } else {
            parts.columnFilter(columns, words);
        }
    }

    private String productLineOf(Long id) {
        List<String> found = jdbc.queryForList(
                "SELECT product_line FROM marketplace_account WHERE id = ?", String.class, id);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("Выгрузка не найдена: " + id);
        }
        return found.get(0);
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
    public Account create(String marketplace, String title, String settingsJson,
                          String productLine) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Название кабинета обязательно");
        }
        // Вид товара проверяется белым списком, хотя в колонке и стоит CHECK:
        // отказ базы приезжает пятисоткой, а это ошибка запроса.
        String line = productLine == null || productLine.isBlank() ? "PART" : productLine;
        if (!"PART".equals(line) && !"WHEEL".equals(line)) {
            throw new IllegalArgumentException("Выгрузка бывает по запчастям или по колёсам: "
                    + productLine);
        }
        // Название уникально в пределах площадки, и стережёт это индекс.
        // Но у владельца прайс-листов на Дром пять («новые», «низкая»,
        // «средняя»…), названия он придумывает сам и рано или поздно
        // повторится — а ответ «Операция нарушает целостность данных»
        // не говорит ни что случилось, ни что делать. Проверка ради текста,
        // сторожем остаётся индекс.
        Integer taken = jdbc.queryForObject("""
                SELECT count(*) FROM marketplace_account
                 WHERE marketplace = ? AND title = ?""",
                Integer.class, marketplace, title.strip());
        if (taken != null && taken > 0) {
            throw new IllegalArgumentException(
                    "Выгрузка «%s» на этой площадке уже заведена: у названия своя ссылка "
                            .formatted(title.strip()) + "на прайс, и двух одинаковых быть не может");
        }

        Long id = jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, settings, product_line)
                VALUES (?, ?, COALESCE(?::jsonb, '{}'::jsonb), ?)
                RETURNING id""",
                Long.class, marketplace, title.strip(),
                settingsJson == null || settingsJson.isBlank() ? null : settingsJson,
                line);

        return list().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Меняет настройки сборки прайса: наценку на прайс-лист и округление.
     *
     * <p><b>Отдельно от отбора, потому что это разные вопросы.</b> Отбор
     * говорит, какой товар уедет в этот прайс-лист; настройки — каким он
     * уедет. Цена на складе, на витрине и у продавца от них не меняется:
     * площадка берёт комиссию, и закладывать её в цену товара значило бы
     * поднять цену и в зале, и по телефону.
     *
     * <p><b>Слиянием, а не заменой.</b> В тех же настройках лежит номер
     * прайс-листа в кабинете площадки ({@code packetId}), и записанный
     * целиком объект стёр бы его — дельты перестали бы уходить вовсе,
     * а по экрану этого не видно: очередь разгребается, журнал публикаций
     * пуст, всё выглядит работающим.
     */
    @Transactional
    public Account setSettings(Long id, FeedSettings settings) {
        FeedSettings checked = (settings == null ? FeedSettings.none() : settings).validated();
        int updated = jdbc.update(
                "UPDATE marketplace_account SET settings = settings || ?::jsonb WHERE id = ?",
                checked.toJson(), id);
        if (updated == 0) {
            throw new IllegalArgumentException("Выгрузка не найдена: " + id);
        }
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
     * Задаёт или снимает имя файла прайса — читаемый хвост постоянной ссылки.
     *
     * <p><b>Отдельным действием от смены ссылки, и секрета не трогает.</b>
     * Смена токена останавливает выгрузку до тех пор, пока новый адрес
     * не пропишет техспециалист площадки; переименование файла заводит
     * второй, читаемый адрес к тому же прайсу, а прежний продолжает работать —
     * иначе правка имени тихо становилась бы остановкой выгрузки.
     *
     * <p><b>Занятое имя отвечает словами и называет, кем занято.</b> Само
     * по себе нарушение уникального индекса — это «Операция нарушает
     * целостность данных»: ни что случилось, ни что делать. А искать владельцу
     * надо именно ту выгрузку, у которой это имя уже стоит.
     */
    @Transactional
    public Account setFeedFileName(Long id, String typed) {
        String name = FeedFileName.normalize(typed);
        String marketplace = marketplaceOf(id);

        if (name != null) {
            takenBy(marketplace, name, id).ifPresent(title -> {
                throw new IllegalArgumentException(occupied(name, title));
            });
        }
        /*
         * Проверка чтением выше ловит обычный повтор, но не одновременный:
         * между чтением и записью второй ещё ничего не видит. Данные защищает
         * индекс, а нарушение его уходит наружу — контроллер перечитывает,
         * кем занято имя, и отвечает теми же словами. Само по себе нарушение
         * читается как «Операция нарушает целостность данных», то есть
         * не говорит ни что случилось, ни что делать.
         */
        jdbc.update("UPDATE marketplace_account SET feed_file_name = ? WHERE id = ?", name, id);
        return list().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Кем занято имя — прочитанное новой транзакцией.
     *
     * <p>Прежняя к этому моменту помечена на откат нарушением уникальности,
     * и запрос из неё не пройдёт. Та же причина, что у повтора приёмки
     * и у повтора заказа с площадки.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
                   readOnly = true)
    public String nameConflictMessage(Long id, String typed) {
        String name = FeedFileName.normalize(typed);
        if (name == null) {
            return "Имя файла не сохранено";
        }
        return takenBy(marketplaceOf(id), name, id)
                .map(title -> occupied(name, title))
                .orElse("Имя файла «%s» уже занято другой выгрузкой".formatted(name));
    }

    private static String occupied(String name, String title) {
        return "Имя файла «%s» занято выгрузкой «%s»: у каждой ссылки свой файл, "
                .formatted(name, title)
                + "иначе техспециалист площадки перепутает два адреса, "
                + "различающихся только секретом посередине";
    }

    /** Название выгрузки, у которой это имя уже стоит. Пусто — свободно. */
    private Optional<String> takenBy(String marketplace, String name, Long exceptId) {
        List<String> found = jdbc.queryForList("""
                SELECT title FROM marketplace_account
                 WHERE marketplace = ? AND feed_file_name = ?
                   AND (?::bigint IS NULL OR id <> ?::bigint)""",
                String.class, marketplace, name, exceptId, exceptId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private String marketplaceOf(Long id) {
        List<String> found = jdbc.queryForList(
                "SELECT marketplace FROM marketplace_account WHERE id = ?", String.class, id);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("Выгрузка не найдена: " + id);
        }
        return found.get(0);
    }

    /**
     * Путь к прайсу, который прописывают в кабинете площадки.
     *
     * <p>Код компании берётся из реестра по текущей схеме, а не из параметра:
     * подставленный снаружи, он дал бы ссылку, ведущую к чужому арендатору —
     * точнее, к отказу, но искать причину пришлось бы долго.
     *
     * <p>Имя файла, если оно задано, становится последним куском адреса:
     * его человек и сверяет глазами, перенося ссылку в кабинет площадки.
     * Секрет при этом остаётся отдельной частью пути — он и открывает доступ,
     * а имя только подписывает ссылку.
     */
    @Transactional(readOnly = true)
    public Optional<String> feedPath(long accountId) {
        String companyCode = jdbc.queryForObject(
                "SELECT code FROM public.tenant_registry WHERE schema_name = ?",
                String.class, TenantContext.require());
        List<String[]> found = jdbc.query(
                "SELECT feed_token, feed_file_name FROM marketplace_account WHERE id = ?",
                (rs, i) -> new String[] {rs.getString("feed_token"), rs.getString("feed_file_name")},
                accountId);

        if (found.isEmpty() || found.get(0)[0] == null) {
            return Optional.empty();
        }
        String token = found.get(0)[0];
        String fileName = found.get(0)[1];
        return Optional.of(fileName == null
                ? "/feeds/drom/%s/%s.xml".formatted(companyCode, token)
                : "/feeds/drom/%s/%s/%s".formatted(companyCode, token, fileName));
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
     * @param lastDownloadAt когда площадка последний раз забрала прайс
     *                       по постоянной ссылке; пусто — не забирала ни разу.
     *                       Это <b>не</b> {@code last_sync_at}: та колонка про
     *                       «мы отправили дельту», а здесь «у нас забрали»,
     *                       и на вопрос «прайс вообще уехал?» отвечает только
     *                       второе
     * @param feedFileName читаемое имя файла в конце ссылки; пусто — имени
     *                     не задавали, и ссылка кончается токеном
     * @param priceFrom   пусто — без нижней границы, а не «ноль»
     * @param conditions  пусто — любое состояние детали
     * @param warehouseIds пусто — все склады
     */
    public record Account(Long id, String marketplace, String title, String status,
                          boolean hasCredentials, boolean plaintextSecret, boolean hasFeed,
                          String feedFileName,
                          String productLine, FeedSettings settings,
                          String lastError, java.time.Instant lastDownloadAt,
                          java.math.BigDecimal priceFrom,
                          java.math.BigDecimal priceTo, List<String> conditions,
                          List<Long> warehouseIds, List<Long> kindIds, boolean kindsExcluded,
                          List<Long> brandIds, boolean brandsExcluded,
                          java.util.Map<String, String> filterColumns,
                          java.util.Map<String, String> filterWords) {
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
     *
     * <p><b>Считать надо ту линию товара, которой торгует выгрузка.</b>
     * Пока счётчик знал только запчасти, у выгрузки колёс он показывал
     * 35 835 позиций там, где уезжало 60: число, ради которого он заведён,
     * врало в шестьсот раз и успокаивало вместо того, чтобы предупреждать.
     * Та же ошибка, что и с колёсами в прайсе запчастей, только с другой
     * стороны — и та же причина: счётчик отбирает не тем условием,
     * что генератор.
     *
     * <p>Виды деталей и марки при этом остаются в запросе и для колёс,
     * хотя генератор колёс их не применяет: экран их для колёсной выгрузки
     * и не показывает, а молча менять смысл параметра хуже, чем его
     * не прислать.
     */
    @Transactional(readOnly = true)
    public long countMatching(java.math.BigDecimal priceFrom, java.math.BigDecimal priceTo,
                              List<String> conditions, List<Long> warehouseIds,
                              List<Long> kindIds, boolean kindsExcluded,
                              List<Long> brandIds, boolean brandsExcluded,
                              String productLine,
                              java.util.Map<String, String> columns,
                              java.util.Map<String, String> words) {
        // Колонки отбираются тем же выражением, каким показаны на экране —
        // и у каждой линии товара своим: у колеса нет вида детали, у запчасти
        // нет сезона.
        String columnsSql = "WHEEL".equals(line(productLine))
                ? wheels.columnFilter(columns, words)
                        .map(f -> " AND " + f.sql()).orElse("")
                : parts.columnFilter(columns, words)
                        .map(f -> " AND " + f.sql()).orElse("");
        List<Object> columnArgs = "WHEEL".equals(line(productLine))
                ? wheels.columnFilter(columns, words)
                        .map(ru.partsflow.inventory.WheelService.ColumnFilter::args)
                        .orElseGet(List::of)
                : parts.columnFilter(columns, words)
                        .map(ru.partsflow.inventory.CatalogService.ColumnFilter::args)
                        .orElseGet(List::of);

        List<Object> args = new java.util.ArrayList<>(List.of());
        Long found = jdbc.queryForObject("""
                SELECT count(*) FROM part p
                  LEFT JOIN donor d ON d.id = p.donor_id
                 WHERE p.is_published
                   -- Тем же условием, что и генератор: у каждой линии товара
                   -- свой прайс, и колесо в прайсе запчастей — чужая категория,
                   -- из которой объявление снимут.
                   AND p.product_line = ?::text
                   AND p.status IN ('IN_STOCK', 'SOLD')
                   -- Тем же условием, что и генератор: нулевая цена в прайс
                   -- не идёт, потому что «0 ₽» в объявлении — обещание отдать
                   -- деталь даром, а на деле это незаполненное поле.
                   AND p.price > 0
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
                   -- Марка берётся и из применимости — тем же условием,
                   -- что и генератор. У контрактной детали донора нет,
                   -- а марка есть, и прайс её публикует: пока отбор смотрел
                   -- только на донора, счётчик обещал 12 537 позиций там,
                   -- где витрина по той же марке показывала 16 529.
                   AND (?::bigint[] IS NULL OR
                        CASE WHEN ?::boolean
                             THEN COALESCE(d.brand_id <> ALL (?::bigint[]), true)
                                  AND NOT EXISTS (SELECT 1 FROM part_applicability pa
                                                   WHERE pa.part_id = p.id
                                                     AND pa.brand_id = ANY (?::bigint[]))
                             ELSE (d.brand_id = ANY (?::bigint[])
                                   OR EXISTS (SELECT 1 FROM part_applicability pa
                                               WHERE pa.part_id = p.id
                                                 AND pa.brand_id = ANY (?::bigint[]))) END)"""
                + columnsSql,
                Long.class,
                argsOf(args, columnArgs,
                line(productLine),
                priceFrom, priceFrom, priceTo, priceTo,
                text(conditions), text(conditions),
                longs(warehouseIds), longs(warehouseIds),
                longs(kindIds), kindsExcluded, longs(kindIds), longs(kindIds),
                longs(brandIds), brandsExcluded,
                longs(brandIds), longs(brandIds), longs(brandIds), longs(brandIds)));
        return found == null ? 0 : found;
    }

    /** Условия колонок идут последними, чтобы не сдвигать номера параметров. */
    private static Object[] argsOf(List<Object> buffer, List<Object> columnArgs,
                                   Object... head) {
        buffer.addAll(java.util.Arrays.asList(head));
        buffer.addAll(columnArgs);
        return buffer.toArray();
    }

    /** Пусто — запчасти: так выгрузка вела себя до появления колёс. */
    private static String line(String productLine) {
        return productLine == null || productLine.isBlank() ? "PART" : productLine.strip();
    }

    private static String text(List<String> values) {
        return values == null || values.isEmpty() ? null : arrayLiteral(values);
    }

    private static String longs(List<Long> values) {
        return values == null || values.isEmpty() ? null : arrayLiteral(values);
    }
}
