package ru.partsflow.publishing.drom;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.partsflow.platform.tenant.TenantContext;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.List;

/**
 * Постоянная ссылка на полный прайс Дрома.
 *
 * <p>Дром забирает прайс сам раз в сутки. Ссылку техспециалист площадки
 * прописывает в настройках прайс-листа один раз, поэтому она обязана быть
 * постоянной: подписанная на пятнадцать минут, как ссылки на фотографии,
 * протухнет до первого же забора.
 *
 * <p><b>Без сессии и без CSRF, но не без проверки.</b> Ходит сюда чужой сервер,
 * cookie у него нет и не будет. Права даёт секрет в самом адресе: знающий
 * ссылку видит склад, поэтому токен длинный, случайный и сменяемый.
 *
 * <p><b>Арендатор берётся из адреса — и это не то же самое, что убранный
 * `X-Tenant-Id`.</b> Там номер схемы подставлял кто угодно и получал чужой
 * склад. Здесь код компании сам по себе не даёт ничего: он только указывает,
 * в какой схеме искать токен, а доступ открывает совпадение токена с тем,
 * что лежит у арендатора.
 *
 * <p><b>Пишет прямо в поток ответа.</b> Пятьдесят тысяч позиций, собранные
 * в память перед отправкой, — это гигабайты на арендатора; отдавать их надо
 * по мере чтения из базы, чем {@link DromPriceGenerator} и занимается.
 */
@RestController
public class DromFeedController {

    private static final Logger log = LoggerFactory.getLogger(DromFeedController.class);

    private final JdbcTemplate jdbc;
    private final DromPriceGenerator generator;

    public DromFeedController(JdbcTemplate jdbc, DromPriceGenerator generator) {
        this.jdbc = jdbc;
        this.generator = generator;
    }

    @GetMapping(value = "/feeds/drom/{company}/{token}.xml", produces = "application/xml")
    public void feed(@PathVariable String company,
                     @PathVariable String token,
                     HttpServletResponse response) throws IOException {

        String schema = schemaOf(company);
        DromPriceGenerator.FeedFilter filter = schema == null ? null : filterFor(schema, token);
        if (filter == null) {
            // Неверный код и неверный токен неразличимы: иначе по коду ответа
            // ссылка работает справочником действующих компаний.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Кэшировать прайс нельзя: между заборами склад меняется весь день,
        // а Дром показывает то, что забрал.
        response.setHeader("Cache-Control", "no-store");
        // Content-Length заранее неизвестен — прайс собирается на лету.
        // Без него ответ уйдёт chunked, и это правильное поведение.
        response.setHeader("Content-Disposition", "inline; filename=\"price.xml\"");

        TenantContext.set(schema);
        try (OutputStream out = response.getOutputStream()) {
            int offers = generator.writeTo(out, filter);
            log.info("Дром забрал прайс арендатора {}: {} позиций", schema, offers);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Схема по коду компании.
     *
     * <p>Схема квалифицируется в SQL руками там, где токен проверяется:
     * {@code TenantContext} на этот момент ещё пуст, потому что арендатор
     * как раз и определяется. Подстановка безопасна — имя приходит из реестра,
     * где стоит {@code CHECK (schema_name ~ '^t_[0-9]{6,}$')}.
     */
    private String schemaOf(String company) {
        try {
            return jdbc.queryForObject("""
                    SELECT schema_name FROM public.tenant_registry
                     WHERE code = lower(btrim(?)) AND status = 'ACTIVE'""",
                    String.class, company);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Отбор той выгрузки, чей токен предъявили; {@code null} — токен не подошёл.
     *
     * <p>Выгрузок у арендатора несколько, и токен не просто открывает дверь,
     * а говорит, за каким прайсом пришли: «Дром: низкая цена» и «Дром: новые»
     * отдают разный товар по разным ссылкам.
     *
     * <p>Сравнение постоянного времени: обычное {@code equals} прекращает
     * сравнивать на первом несовпавшем байте, и по времени ответа токен
     * подбирается посимвольно. По той же причине цикл проходит все строки
     * без раннего выхода — иначе «совпал первый кабинет» и «совпал третий»
     * отвечают за разное время.
     */
    private DromPriceGenerator.FeedFilter filterFor(String schema, String token) {
        List<Feed> feeds = jdbc.query("""
                SELECT feed_token, price_from, price_to, conditions, warehouse_ids
                  FROM %s.marketplace_account
                 WHERE marketplace = 'DROM' AND status = 'ACTIVE'
                   AND feed_token IS NOT NULL""".formatted(schema),
                (rs, i) -> new Feed(rs.getString("feed_token"),
                        rs.getBigDecimal("price_from"),
                        rs.getBigDecimal("price_to"),
                        textList(rs.getArray("conditions")),
                        longList(rs.getArray("warehouse_ids"))));

        byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        Feed found = null;
        for (Feed candidate : feeds) {
            if (MessageDigest.isEqual(
                    candidate.token().getBytes(StandardCharsets.UTF_8), presented)) {
                found = candidate;
            }
        }
        return found == null ? null : new DromPriceGenerator.FeedFilter(
                found.priceFrom(), found.priceTo(), found.conditions(), found.warehouseIds());
    }

    private static List<String> textList(java.sql.Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static List<Long> longList(java.sql.Array array) throws SQLException {
        return array == null ? List.of() : List.of((Long[]) array.getArray());
    }

    private record Feed(String token, java.math.BigDecimal priceFrom, java.math.BigDecimal priceTo,
                        List<String> conditions, List<Long> warehouseIds) {
    }
}
