package ru.partsflow.publishing.drom;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.partsflow.inventory.PhotoStorage;
import ru.partsflow.platform.tenant.TenantContext;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Постоянная ссылка на полный прайс Дрома.
 *
 * <p>Дром забирает прайс сам — раз в трое суток, а при платном
 * позиционировании раз в сутки. Ссылку техспециалист площадки
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
    private final PhotoStorage photos;
    private final DromAccountReader accounts;
    private final DromWheelGenerator wheels;
    private final String publicUrl;

    public DromFeedController(JdbcTemplate jdbc, DromPriceGenerator generator,
                              DromAccountReader accounts,
                              DromWheelGenerator wheels,
                              PhotoStorage photos,
                              @Value("${app.public-url:}") String publicUrl) {
        this.jdbc = jdbc;
        this.generator = generator;
        this.accounts = accounts;
        this.wheels = wheels;
        this.photos = photos;
        this.publicUrl = publicUrl;
    }

    @GetMapping(value = "/feeds/drom/{company}/{token}.xml", produces = "application/xml")
    public void feed(@PathVariable String company,
                     @PathVariable String token,
                     HttpServletRequest request,
                     HttpServletResponse response) throws IOException {

        String schema = schemaOf(company);
        DromAccountReader.Account account = schema == null ? null : accountFor(schema, token);
        if (account == null) {
            // Неверный код и неверный токен неразличимы: иначе по коду ответа
            // ссылка работает справочником действующих компаний.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        /*
         * Отбор разбирается до того, как открыт поток ответа.
         *
         * Битое условие — колонка чужой линии товара, оставшаяся от прежней
         * настройки, — всплывало посреди записи: заголовки отправлены,
         * и площадка получала 200 с нулём байт. Пустой файл она понимает
         * буквально: «товаров нет», и снимает все объявления вместе
         * с просмотрами, за которые платят. Отказ до первой строки оставляет
         * прежний прайс в силе — площадка просто попробует позже.
         */
        TenantContext.set(schema);
        try {
            if (account.isWheelFeed()) {
                wheels.checkFilter(account.filter());
            } else {
                generator.checkFilter(account.filter());
            }
        } catch (RuntimeException e) {
            log.error("Прайс арендатора {} не собран: отбор выгрузки не разбирается — {}",
                    schema, e.getMessage());
            throw e;
        } finally {
            TenantContext.clear();
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
            // Выгрузка знает, чем торгует: у шин свой формат со своими полями,
            // и площадка сама требует держать их отдельным прайс-листом.
            String base = photoBase(request, company, token);
            // Отбор говорит, что уедет; настройки — каким оно уедет: наценка
            // на прайс-лист и округление принадлежат выгрузке, а не товару.
            int offers = account.isWheelFeed()
                    ? wheels.writeTo(out, account.filter(), base, account.settings())
                    : generator.writeTo(out, account.filter(), base, account.settings());
            log.info("Дром забрал прайс арендатора {} ({}): {} позиций",
                    schema, account.isWheelFeed() ? "колёса" : "запчасти", offers);
        } finally {
            TenantContext.clear();
        }

        // Отметка ставится после того, как файл дописан и отдан целиком:
        // о полузабранном прайсе она отвечала бы «да» на вопрос, ради
        // которого и заведена.
        markDownloaded(schema, account.id());
    }

    /**
     * Отмечает, что площадка забрала прайс этой выгрузки.
     *
     * <p><b>Это не то же, что {@code last_sync_at}.</b> Ту колонку пишет
     * отправка дельт — «мы отправили», — а здесь «у нас забрали». События
     * разные, и путаница между ними как раз и мешает ответить владельцу
     * на вопрос «прайс вообще уехал?»: до этой отметки на него отвечал
     * разработчик по логам приложения, то есть клиент ждал человека, чтобы
     * узнать факт, который система знает.
     *
     * <p><b>Отметка не роняет отдачу.</b> Прайс к этому моменту уже уехал
     * целиком, и падать из-за незаписанной строки значило бы отдать площадке
     * ошибку на успешный забор. Поэтому отказ базы здесь только пишется
     * в лог — прайс важнее отметки о нём.
     *
     * <p><b>Отмечается та выгрузка, за которой пришли.</b> Прайс-листов
     * у клиента пять, и отметка не на той отвечает на вопрос про чужую
     * ссылку — то есть врёт ровно там, где её и спрашивают.
     *
     * <p>Схема квалифицируется в SQL руками, как и везде в этом контроллере:
     * запрос идёт вне транзакции отдачи, и {@code search_path} для него
     * никто не выставляет.
     */
    private void markDownloaded(String schema, long accountId) {
        try {
            jdbc.update("""
                    UPDATE %s.marketplace_account
                       SET last_feed_download_at = now()
                     WHERE id = ?""".formatted(schema), accountId);
        } catch (RuntimeException e) {
            log.warn("Отметка о заборе прайса арендатора {} не записана: {}",
                    schema, e.getMessage());
        }
    }

    /**
     * Снимок по постоянному адресу.
     *
     * <p><b>Редирект, а не отдача файла.</b> Многомегабайтные снимки не должны
     * идти через приложение — по той же причине, по которой телефон грузит их
     * в хранилище напрямую. Здесь уходит только 302, а байты Дром берёт из S3
     * подписанной ссылкой, которую мы подписываем в этот самый момент.
     *
     * <p><b>И постоянный адрес нужен именно поэтому.</b> Подписанная ссылка
     * живёт часы: положенная прямо в прайс, она протухнет между заборами,
     * а объявление с мёртвой картинкой площадка снимает.
     *
     * <p>Снимок непубликуемой позиции отсюда не отдаётся: в прайс она
     * не уезжает, значит и фотографии её наружу смотреть незачем. Токен
     * проверяется тот же, что у прайса, — иначе адрес превращается
     * в перебор по номеру снимка.
     */
    @GetMapping("/feeds/drom/{company}/{token}/photo/{photoId}.jpg")
    public void photo(@PathVariable String company,
                      @PathVariable String token,
                      @PathVariable long photoId,
                      HttpServletResponse response) throws IOException {

        String schema = schemaOf(company);
        String key = schema == null || accountFor(schema, token) == null
                ? null
                : keyOf(schema, photoId);
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        response.sendRedirect(photos.presignView(key));
    }

    /**
     * Ключ снимка публикуемой позиции; {@code null} — нет такого либо позиция
     * не выгружается.
     *
     * <p>Схема квалифицируется в SQL руками, как и при проверке токена:
     * {@code TenantContext} здесь ещё пуст.
     */
    private String keyOf(String schema, long photoId) {
        try {
            return jdbc.queryForObject("""
                    SELECT ph.s3_key
                      FROM %s.part_photo ph
                      JOIN %s.part p ON p.id = ph.part_id
                     WHERE ph.id = ? AND ph.status = 'PROCESSED' AND p.is_published"""
                    .formatted(schema, schema), String.class, photoId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Постоянный адрес выдачи снимков этой выгрузки.
     *
     * <p>Берётся из настройки, а не из запроса: за терминатором адрес
     * в запросе — это внутреннее имя контейнера, и прайс уехал бы со ссылками
     * вида {@code http://app:8080/…}, по которым Дром не сходит никуда.
     * Из запроса строится только в разработке, где настройки нет.
     */
    private String photoBase(HttpServletRequest request, String company, String token) {
        String origin = publicUrl == null || publicUrl.isBlank()
                ? request.getRequestURL().toString().replaceFirst("/feeds/drom/.*$", "")
                : publicUrl.replaceFirst("/+$", "");
        return "%s/feeds/drom/%s/%s/photo/".formatted(origin, company, token);
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
    private DromAccountReader.Account accountFor(String schema, String token) {
        List<DromAccountReader.Account> feeds = accounts.active(schema);

        byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        DromAccountReader.Account found = null;
        for (DromAccountReader.Account candidate : feeds) {
            if (candidate.feedToken() == null) {
                continue;
            }
            if (MessageDigest.isEqual(
                    candidate.feedToken().getBytes(StandardCharsets.UTF_8), presented)) {
                found = candidate;
            }
        }
        return found;
    }

}
