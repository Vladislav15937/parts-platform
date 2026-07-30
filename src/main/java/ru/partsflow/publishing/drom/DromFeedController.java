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
        if (schema == null || !tokenMatches(schema, token)) {
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
            int offers = generator.writeTo(out);
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
     * Совпадает ли токен с тем, что лежит у арендатора.
     *
     * <p>Сравнение постоянного времени: обычное {@code equals} прекращает
     * сравнивать на первом несовпавшем байте, и по времени ответа токен
     * подбирается посимвольно.
     */
    private boolean tokenMatches(String schema, String token) {
        List<String> stored = jdbc.queryForList("""
                SELECT feed_token FROM %s.marketplace_account
                 WHERE marketplace = 'DROM' AND status = 'ACTIVE' AND feed_token IS NOT NULL"""
                .formatted(schema), String.class);

        byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (String candidate : stored) {
            // Без раннего выхода из цикла: он вернул бы разное время для
            // «первый кабинет совпал» и «совпал третий».
            matched |= MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), presented);
        }
        return matched;
    }
}
