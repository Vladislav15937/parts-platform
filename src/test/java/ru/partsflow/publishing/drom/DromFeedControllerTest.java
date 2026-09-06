package ru.partsflow.publishing.drom;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.support.PostgresTestBase;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Постоянная ссылка на прайс Дрома.
 *
 * <p>Единственный путь в системе, открытый без сессии и отдающий данные склада.
 * Поэтому проверяется прежде всего то, чего он делать не должен: пускать
 * по чужому токену, по одному коду компании, и — главное — отдавать склад
 * одного арендатора по ссылке другого.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class DromFeedControllerTest extends PostgresTestBase {

    private static final String TENANT = "t_000066";
    private static final String OTHER_TENANT = "t_000067";

    @Autowired
    private ru.partsflow.inventory.StockLedger ledger;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long accountId;
    private String feedPath;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT, OTHER_TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        register(66, TENANT, "feedco");
        register(67, OTHER_TENANT, "otherco");

        inTenant(TENANT, () -> {
            jdbc.update("DELETE FROM marketplace_account");
            member("owner", "OWNER");
            member("manager", "MANAGER");
            accountId = jdbc.queryForObject("""
                    INSERT INTO marketplace_account (marketplace, title, settings)
                    VALUES ('DROM', 'Кабинет', '{"packetId":"777"}'::jsonb) RETURNING id""",
                    Long.class);
            part("Фара левая Camry");
            return null;
        });

        inTenant(OTHER_TENANT, () -> {
            jdbc.update("DELETE FROM marketplace_account");
            part("Бампер чужой разборки");
            return null;
        });

        feedPath = rotate();
    }

    @Test
    @DisplayName("Дром забирает прайс по ссылке без всякого входа")
    void feedIsServedWithoutSession() throws Exception {
        String body = mvc.perform(get(feedPath))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Cookie у сервера площадки нет и не будет: права даёт секрет в адресе.
        assertThat(body).contains("Фара левая Camry");
    }

    @Test
    @DisplayName("Снимки уходят ссылками на постоянный адрес, а не подписанными")
    void feedCarriesPermanentPhotoLinks() throws Exception {
        Long photoId = inTenant(TENANT, () -> photo("Фара левая Camry", true));

        String body = mvc.perform(get(feedPath))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = tokenOf(feedPath);
        assertThat(body)
                .as("прайс ушёл без единой ссылки на снимок при том, что снимки есть")
                .contains("/feeds/drom/feedco/%s/photo/%d.jpg".formatted(token, photoId));
        // Подписанная ссылка живёт часы и протухнет между заборами прайса,
        // а объявление с мёртвой картинкой площадка снимает.
        assertThat(body).doesNotContain("X-Amz-Signature");
    }

    @Test
    @DisplayName("По ссылке на снимок уходит редирект в хранилище")
    void photoRedirectsToStorage() throws Exception {
        Long photoId = inTenant(TENANT, () -> photo("Фара левая Camry", true));

        // Многомегабайтные снимки через приложение не идут: отсюда уезжает
        // только 302, а байты Дром берёт из хранилища напрямую.
        mvc.perform(get("/feeds/drom/feedco/%s/photo/%d.jpg".formatted(tokenOf(feedPath), photoId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .contains("X-Amz-Signature"));
    }

    @Test
    @DisplayName("Снимок непубликуемой позиции наружу не отдаётся")
    void photoOfUnpublishedPartIsHidden() throws Exception {
        Long hidden = inTenant(TENANT, () -> photo("Битая дверь", false));

        // В прайс такая позиция не уезжает — значит и смотреть её фотографии
        // площадке (и всякому, кто знает ссылку) незачем.
        mvc.perform(get("/feeds/drom/feedco/%s/photo/%d.jpg".formatted(tokenOf(feedPath), hidden)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Чужой токен не открывает и снимок")
    void photoNeedsTheSameToken() throws Exception {
        Long photoId = inTenant(TENANT, () -> photo("Фара левая Camry", true));

        mvc.perform(get("/feeds/drom/feedco/%s/photo/%d.jpg".formatted("z".repeat(43), photoId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Прайс не кэшируется: склад меняется между заборами")
    void feedIsNotCached() throws Exception {
        mvc.perform(get(feedPath))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Cache-Control"))
                        .isEqualTo("no-store"));
    }

    @Test
    @DisplayName("Чужой токен склад не открывает")
    void wrongTokenIsRejected() throws Exception {
        mvc.perform(get("/feeds/drom/feedco/" + "x".repeat(43) + ".xml"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Одного кода компании мало")
    void companyCodeAloneIsNotEnough() throws Exception {
        // Код компании публичен — он же логин в форму входа и будущий
        // поддомен. Доступ открывает только токен.
        mvc.perform(get("/feeds/drom/feedco/.xml"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Токен одного арендатора не открывает склад другого")
    void tokenDoesNotCrossTenants() throws Exception {
        String token = feedPath.substring(feedPath.lastIndexOf('/') + 1, feedPath.length() - 4);

        // Ровно та утечка, ради предотвращения которой убран X-Tenant-Id:
        // подставить чужой код компании и получить чужой склад.
        mvc.perform(get("/feeds/drom/otherco/" + token + ".xml"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Несуществующая компания отвечает так же, как неверный токен")
    void unknownCompanyLooksTheSame() throws Exception {
        mvc.perform(get("/feeds/drom/нетакой/" + "y".repeat(43) + ".xml"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Смена ссылки ломает прежнюю")
    void rotationInvalidatesOldLink() throws Exception {
        String old = feedPath;
        String fresh = rotate();

        assertThat(fresh).isNotEqualTo(old);
        // Прайс у площадки замрёт, пока новую ссылку не пропишет её
        // техспециалист — поэтому смена и сделана отдельным действием.
        mvc.perform(get(old)).andExpect(status().isNotFound());
        mvc.perform(get(fresh)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Ссылку видит владелец и управляющий, но не заводит управляющий")
    void feedUrlIsOwnerBusiness() throws Exception {
        mvc.perform(get("/api/marketplace-accounts/" + accountId + "/feed-url")
                        .session(login("manager")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(feedPath));

        mvc.perform(post("/api/marketplace-accounts/" + accountId + "/feed-url")
                        .with(csrf()).session(login("manager")))
                .andExpect(status().isForbidden());
    }

    /**
     * Условие по чужой колонке отбивается словами при сохранении.
     *
     * <p><b>Зачем.</b> Списки колонок у запчастей и у колёс разные: у колеса
     * нет стороны, у запчасти нет сезона. Принятое молча чужое условие
     * ломается не при сохранении, а при заборе прайса — то есть у площадки,
     * и молча: до появления проверки она получала пустой файл, читала его
     * как «товаров нет» и снимала объявления. Владелец при этом видел
     * сохранённый отбор и ничего подозрительного.
     */
    @Test
    @DisplayName("Условие по колонке чужой линии товара не сохраняется")
    void alienColumnIsRefusedOnSave() throws Exception {
        mvc.perform(put("/api/marketplace-accounts/" + accountId + "/filter")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":{\"season\":\"летняя\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("season")));

        // А своя колонка сохраняется как была: проверка не должна перекрыть
        // то, ради чего отбор и заведён.
        mvc.perform(put("/api/marketplace-accounts/" + accountId + "/filter")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":{\"section\":\"A-01\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filterColumns.section").value("A-01"));
    }

    /**
     * Ссылка отдаётся полным адресом, а не одним путём.
     *
     * <p>Её передают человеку на той стороне — инструкция так и говорит,
     * «передать ссылку техспециалисту площадки». По {@code /feeds/drom/…}
     * он не сходит никуда, и владельцу приходилось дописывать домен руками.
     * Домен сервер знает: тот же {@code app.public-url} уже подставляется
     * в ссылки на снимки внутри самого прайса.
     *
     * <p>Путь при этом остаётся: по нему прайс качается с того же источника,
     * где открыт экран, и в разработке это единственный работающий адрес.
     */
    @Test
    @DisplayName("Ссылка на прайс отдаётся полным адресом")
    void feedUrlIsAbsolute() throws Exception {
        mvc.perform(get("/api/marketplace-accounts/" + accountId + "/feed-url")
                        .session(login("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(feedPath))
                .andExpect(jsonPath("$.url")
                        .value(org.hamcrest.Matchers.startsWith("http")))
                .andExpect(jsonPath("$.url")
                        .value(org.hamcrest.Matchers.endsWith(feedPath)));
    }

    /**
     * Имя файла — читаемый хвост ссылки, а не пропуск по ней.
     *
     * <p><b>Зачем.</b> Адрес прописывает в кабинете площадки её техспециалист
     * руками, и хвост из сорока случайных символов токена он переносит
     * с ошибками — а ошибку видно только по тому, что объявления
     * не появились: о неверном адресе площадка не сообщает никому.
     *
     * <p>Проверяются обе стороны сразу: по названному адресу прайс отдаётся,
     * а подделанный секрет с тем же именем файла по-прежнему 404. Секрет
     * остаётся отдельной частью пути, имя его не заменяет.
     */
    @Test
    @DisplayName("Ссылка кончается заданным именем, а доступ по-прежнему даёт токен")
    void namedLinkServesTheFeed() throws Exception {
        setFileName(accountId, "drom-parts.xml").andExpect(status().isOk());

        String named = pathOf(mvc.perform(
                        get("/api/marketplace-accounts/" + accountId + "/feed-url")
                                .session(login("owner")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(named).endsWith("/drom-parts.xml");
        assertThat(mvc.perform(get(named))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .contains("Фара левая Camry");

        // Подделанный секрет с верным именем файла не открывает ничего:
        // имя подписывает ссылку, а не заменяет токен.
        mvc.perform(get("/feeds/drom/feedco/%s/drom-parts.xml".formatted("z".repeat(43))))
                .andExpect(status().isNotFound());

        // И прежний адрес продолжает работать: имя не меняет секрет,
        // а смена ссылки — отдельное действие, которое останавливает выгрузку
        // до тех пор, пока новый адрес не пропишет техспециалист площадки.
        mvc.perform(get(feedPath)).andExpect(status().isOk());
    }

    /**
     * Занятое имя отвечает словами и называет, у какой выгрузки оно стоит.
     *
     * <p>Само по себе нарушение уникального индекса читается как «Операция
     * нарушает целостность данных»: ни что случилось, ни что делать. А искать
     * владельцу надо именно ту выгрузку, у которой имя уже стоит, — прайсов
     * на Дром у него пять.
     */
    @Test
    @DisplayName("Занятое имя файла называет выгрузку, у которой оно стоит")
    void takenFileNameIsAnsweredWithWords() throws Exception {
        setFileName(accountId, "drom-parts.xml").andExpect(status().isOk());
        Long second = inTenant(TENANT, () -> jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, settings)
                VALUES ('DROM', 'Дром: низкая цена', '{}'::jsonb) RETURNING id""", Long.class));

        setFileName(second, "drom-parts.xml")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("drom-parts.xml")))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Кабинет")));

        // На соседней площадке то же имя свободно: адреса у них разные,
        // и путать нечего — как и с названием выгрузки.
        Long avito = inTenant(TENANT, () -> jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, settings)
                VALUES ('AVITO', 'Авито: основной', '{}'::jsonb) RETURNING id""", Long.class));
        setFileName(avito, "drom-parts.xml").andExpect(status().isOk());
    }

    /**
     * Незаданное имя — {@code NULL}, а не пустая строка.
     *
     * <p>Пустых строк в уникальном индексе может быть только одна, и вторая
     * выгрузка без имени получила бы отказ на ровном месте. Ровно этим болел
     * снятый штрихкод: пусто означает «не заполнено», а не значение.
     */
    @Test
    @DisplayName("Двум выгрузкам без имени файла ничто не мешает")
    void emptyNamesDoNotCollide() throws Exception {
        Long second = inTenant(TENANT, () -> jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, settings)
                VALUES ('DROM', 'Дром: без имени', '{}'::jsonb) RETURNING id""", Long.class));

        setFileName(accountId, "").andExpect(status().isOk());
        setFileName(second, "").andExpect(status().isOk());

        assertThat(inTenant(TENANT, () -> jdbc.queryForList(
                "SELECT id FROM marketplace_account WHERE feed_file_name = ''")))
                .as("пустая строка записана вместо NULL — второй такой выгрузки "
                        + "уникальный индекс уже не пропустит")
                .isEmpty();
    }

    /**
     * Имя, которого нельзя в адресе, отбивается до сохранения.
     *
     * <p>Сохранённое, оно дало бы ссылку, которая не открывается, и узнать
     * об этом можно было бы только от площадки — то есть через дни
     * и через объявления, которых нет.
     */
    @Test
    @DisplayName("Пробелы и кириллица в имени файла отбиваются словами")
    void impossibleFileNameIsRefused() throws Exception {
        setFileName(accountId, "прайс дрома.xml")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("пробел")));

        setFileName(accountId, "прайс.xml")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("латинские")));

        // Ссылка при этом осталась прежней: отказ на сохранении ничего
        // не меняет.
        mvc.perform(get("/api/marketplace-accounts/" + accountId + "/feed-url")
                        .session(login("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(feedPath));
    }

    @Test
    @DisplayName("Имя файла задаёт владелец, а не управляющий")
    void fileNameIsOwnerBusiness() throws Exception {
        mvc.perform(put("/api/marketplace-accounts/" + accountId + "/feed-file")
                        .with(csrf()).session(login("manager"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"drom-parts.xml\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Две выгрузки одной площадки отдают разный товар")
    void feedsAreFilteredIndependently() throws Exception {
        // Ровно то, что делает живой клиент: пять прайсов на Дром, разложенных
        // по ценовым диапазонам, у каждого свой прайс-лист в кабинете
        // и своя цена размещения.
        Long cheapId = inTenant(TENANT, () -> {
            pricedPart("Заглушка бампера", 500);
            pricedPart("Двигатель в сборе", 90000);
            return jdbc.queryForObject("""
                    INSERT INTO marketplace_account (marketplace, title, settings,
                                                     price_from, price_to)
                    VALUES ('DROM', 'Дром: дешёвое', '{}'::jsonb, 0, 1000)
                    RETURNING id""", Long.class);
        });
        String cheapFeed = rotate(cheapId);

        String cheap = mvc.perform(get(cheapFeed))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(cheap).contains("Заглушка бампера");
        assertThat(cheap)
                .as("в прайс дешёвого диапазона попал товар за 90 000 — "
                        + "разложить склад по прайс-листам не получится")
                .doesNotContain("Двигатель в сборе");

        // Прежняя выгрузка без фильтра продолжает отдавать всё: пустой фильтр
        // означает «без ограничения», а не «ничего».
        String all = mvc.perform(get(feedPath))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(all).contains("Двигатель в сборе").contains("Заглушка бампера");
    }

    @Test
    @DisplayName("Выгрузка склада показывает остаток этого склада")
    void warehouseFeedShowsItsOwnStock() throws Exception {
        Long feedId = inTenant(TENANT, () -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Второй филиал') RETURNING id", Long.class);
            Long far = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Дальний') RETURNING id",
                    Long.class, branch);
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, is_published)
                    VALUES (1, 'Дверь на дальнем складе', 7000, 3000, true) RETURNING id""",
                    Long.class);
            ledger.record(StockMovement.intake(partId, java.math.BigDecimal.ONE, far, null));

            return jdbc.queryForObject("""
                    INSERT INTO marketplace_account (marketplace, title, settings, warehouse_ids)
                    VALUES ('DROM', 'Дром: дальний склад', '{}'::jsonb, ARRAY[?::bigint])
                    RETURNING id""", Long.class, far);
        });

        String body = mvc.perform(get(rotate(feedId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Деталь этого склада — доступна. Деталь соседнего склада в прайс
        // попадает (объявление живёт и копит просмотры), но доступной
        // числиться не должна: иначе покупатель приедет за ней на другой
        // конец города, а её там нет.
        assertThat(body).contains("Дверь на дальнем складе");
        assertThat(offerOf(body, "Дверь на дальнем складе"))
                .as("остаток склада выгрузки потерялся")
                .contains("<available>true</available>");
        assertThat(offerOf(body, "Фара левая Camry"))
                .as("остаток посчитан по всем складам: покупателю обещана "
                        + "деталь, которой на этом складе нет")
                .contains("<available>false</available>");
    }

    @Test
    @DisplayName("Список марок работает в обе стороны и не выкидывает контрактные")
    void brandListExcludesAndIncludes() throws Exception {
        Long toyota = inTenant(TENANT, () -> {
            Long brandId = jdbc.queryForObject(
                    "SELECT id FROM catalog.brand ORDER BY id LIMIT 1", Long.class);
            Long donorId = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, note) VALUES (?, 'Донор') RETURNING id""",
                    Long.class, brandId);
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Ф') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'С') RETURNING id",
                    Long.class, branch);
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, donor_id, title, price, is_published)
                    VALUES (1, ?, 'Дверь с донора', 8000, true) RETURNING id""",
                    Long.class, donorId);
            ledger.record(StockMovement.intake(partId, java.math.BigDecimal.ONE, warehouse, null));
            return brandId;
        });

        Long excludingId = inTenant(TENANT, () -> jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, settings,
                                                 brand_ids, brands_excluded)
                VALUES ('DROM', 'Дром: кроме марки', '{}'::jsonb, ARRAY[?::bigint], true)
                RETURNING id""", Long.class, toyota));

        String body = mvc.perform(get(rotate(excludingId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("исключённая марка всё равно уехала в прайс")
                .doesNotContain("Дверь с донора");
        // Позиция без донора — контрактная: марки у неё нет, и исключение
        // чужой марки не повод выкинуть её из прайса.
        assertThat(body)
                .as("вместе с исключённой маркой пропали позиции без донора")
                .contains("Фара левая Camry");
    }

    @Test
    @DisplayName("Колесо в прайс запчастей не уезжает")
    void wheelsStayOutOfThePartsFeed() throws Exception {
        inTenant(TENANT, () -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Ф') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'С') RETURNING id",
                    Long.class, branch);
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published, product_line)
                    VALUES (1, 'Шина 195/65 R15 Goodyear', 3500, true, 'WHEEL') RETURNING id""",
                    Long.class);
            ledger.record(StockMovement.intake(partId, new java.math.BigDecimal("4"), warehouse, null));
            return null;
        });

        String body = mvc.perform(get(feedPath))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // У площадки для шин и дисков свой формат со своими полями:
        // «Шина 195/65 R15» среди запчастей уедет в чужую категорию,
        // и объявление снимут.
        assertThat(body)
                .as("колесо уехало в прайс запчастей — площадка положит его "
                        + "в чужую категорию")
                .doesNotContain("Шина 195/65 R15");
        // Запчасти при этом на месте: исключение по линии товара, а не отказ
        // отдавать прайс целиком.
        assertThat(body).contains("Фара левая Camry");
    }

    /**
     * Наценка на прайс-лист меняет цену в файле и больше нигде.
     *
     * <p><b>Зачем.</b> Площадка берёт комиссию, и продавцы закладывают её
     * в цену объявления: у живого клиента на прайсе Авито стоит −20 %.
     * Пока задать это было негде, заложить комиссию можно было только
     * испортив цену товара — то есть подняв её и для прилавка, и для звонка
     * по телефону.
     *
     * <p>Проверяется вся дорога разом: экран сохраняет настройку, она ложится
     * в {@code marketplace_account.settings}, оттуда её читает выдача прайса.
     * И три вещи, каждая из которых стоит дороже самой наценки: цена товара
     * не изменилась, соседняя выгрузка отдаёт прежнюю цену, а «цену
     * не назначили» наценка не превращает в цену.
     */
    @Test
    @DisplayName("Наценка выгрузки меняет цену в прайсе и не трогает склад")
    void markupChangesThePriceInTheFeedOnly() throws Exception {
        Long round = inTenant(TENANT, () -> pricedPartId("Прайс: наценка ровная", 4500));
        Long odd = inTenant(TENANT, () -> pricedPartId("Прайс: наценка с копейками", 4505));
        Long free = inTenant(TENANT, () -> pricedPartId("Прайс: цену не назначили", 0));

        Long markedUpId = inTenant(TENANT, () -> jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, settings)
                VALUES ('DROM', 'Дром: с наценкой', '{"packetId":"555"}'::jsonb)
                RETURNING id""", Long.class));
        String markedUpFeed = rotate(markedUpId);

        // Настройка задаётся с экрана, а не запросом в базу: восемь раз подряд
        // возможность оказывалась написанной и недоступной человеку.
        mvc.perform(put("/api/marketplace-accounts/" + markedUpId + "/settings")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePercent\":10,\"priceRounding\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.pricePercent").value(10));

        String marked = mvc.perform(get(markedUpFeed))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(offerOf(marked, "Прайс: наценка ровная"))
                .as("наценка не доехала до прайса — комиссия площадки уйдёт из кармана")
                .contains("<price>4950.00</price>");
        // 4 505 + 10 % = 4 955,50, и округление до десятки идёт вверх:
        // вниз оно однажды отдаст деталь дешевле, чем владелец задал.
        assertThat(offerOf(marked, "Прайс: наценка с копейками"))
                .as("округление отбросило копейки вместо того, чтобы поднять до шага")
                .contains("<price>4960.00</price>");
        // Ноль у нас означает «цену не назначили»: наценка на незаполненное
        // поле дала бы цену там, где её нет.
        assertThat(marked)
                .as("позиция без цены уехала в прайс — «0 ₽» это обещание отдать даром")
                .doesNotContain("Прайс: цену не назначили");

        // Соседняя выгрузка отдаёт тот же товар за его цену: процент
        // принадлежит прайс-листу, а не товару, и протечь между выгрузками
        // не может — иначе одна настройка меняет цену во всех пяти прайсах
        // живого клиента разом.
        String plain = mvc.perform(get(feedPath))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(offerOf(plain, "Прайс: наценка ровная"))
                .as("наценка одной выгрузки протекла в соседнюю")
                .contains("<price>4500.00</price>");

        // И главное: на складе, на витрине и у продавца цена прежняя.
        // Иначе комиссия площадки поднимет цену тому же товару в зале
        // и по телефону — ровно то, ради чего настройка и заведена.
        assertThat(inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT price FROM part WHERE id = ?", java.math.BigDecimal.class, round)))
                .as("наценка выгрузки изменила цену товара на складе")
                .isEqualByComparingTo("4500");
        assertThat(inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT price FROM part WHERE id = ?", java.math.BigDecimal.class, odd)))
                .isEqualByComparingTo("4505");
        assertThat(inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT price FROM part WHERE id = ?", java.math.BigDecimal.class, free)))
                .isEqualByComparingTo("0");
    }

    /**
     * Скидка в сто процентов — это «0 ₽» в объявлении, и отбивается она
     * словами.
     *
     * <p>Отказ базы или молча уехавший ноль здесь одинаково плохи: первое
     * не говорит человеку ничего, второе — публичное обещание отдать деталь
     * даром, за которым идут звонки.
     */
    @Test
    @DisplayName("Скидка в 100 % не сохраняется и объясняет почему")
    void fullDiscountIsRefusedWithWords() throws Exception {
        mvc.perform(put("/api/marketplace-accounts/" + accountId + "/settings")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePercent\":-100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("0 ₽")));
    }

    /**
     * Сколько снимков уходит в объявление, решает владелец выгрузки.
     *
     * <p><b>Зачем.</b> Предел в десять был зашит в сборку прайса, то есть
     * правка его означала релиз, а площадки считают снимки по-разному:
     * где-то десять лишние, где-то мало. Проверяется вся дорога разом —
     * экран сохраняет настройку, она ложится в
     * {@code marketplace_account.settings}, оттуда её читает выдача прайса.
     *
     * <p>И три вещи сразу, каждая из которых стоит дороже самой настройки:
     * обрезка берёт <b>первые</b> снимки по порядку показа (первую ссылку
     * площадка ставит обложкой объявления, и случайная обложка хуже
     * отсутствующей), ноль означает «без ограничения», а не «ни одного»,
     * и незаданная настройка оставляет прежние десять — иначе у клиентов,
     * которые её не трогали, прайс молча изменился бы.
     */
    @Test
    @DisplayName("Число снимков в объявлении задаёт выгрузка")
    void photoLimitBelongsToTheFeed() throws Exception {
        // Своё имя на каждый прогон: позиции между прогонами не чистятся —
        // журнал движений неизменяем, приход не удалить, — и одноимённая
        // из прошлого прогона нашлась бы в прайсе первой, без единого снимка.
        String run = java.util.UUID.randomUUID().toString().substring(0, 8);
        String name = "Прайс: восемь снимков " + run;
        Long partId = inTenant(TENANT, () -> pricedPartId(name, 6000));
        // Главный снимок заведён не первым по расстановке: иначе «первые три»
        // и «три случайных» в этом тесте выглядели бы одинаково.
        java.util.List<Long> shown = inTenant(TENANT, () -> photos(partId, 8, 5));

        Long limitedId = inTenant(TENANT, () -> jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, settings)
                VALUES ('DROM', 'Дром: три снимка', '{"packetId":"556"}'::jsonb)
                RETURNING id""", Long.class));
        String limitedFeed = rotate(limitedId);

        // Настройка задаётся с экрана, а не запросом в базу.
        mvc.perform(put("/api/marketplace-accounts/" + limitedId + "/settings")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoLimit\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.photoLimit").value(3));

        String three = offerOf(mvc.perform(get(limitedFeed))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), name);

        assertThat(photoLinksIn(three))
                .as("выгрузка отдала не три снимка — предел так и остался зашитым")
                .isEqualTo(3);
        assertThat(three)
                .as("обрезка взяла не первые снимки: обложкой объявления "
                        + "площадка ставит первую ссылку")
                .contains("/photo/%d.jpg".formatted(shown.get(0)))
                .contains("/photo/%d.jpg".formatted(shown.get(1)))
                .contains("/photo/%d.jpg".formatted(shown.get(2)))
                .doesNotContain("/photo/%d.jpg".formatted(shown.get(3)));

        // Ноль — «без ограничения», как у системы, с которой переходят
        // клиенты. Прочитанный как «ни одного», он оставил бы объявления
        // без фотографий, а на разборке продаёт именно фотография.
        mvc.perform(put("/api/marketplace-accounts/" + limitedId + "/settings")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoLimit\":0}"))
                .andExpect(status().isOk());

        assertThat(photoLinksIn(offerOf(mvc.perform(get(limitedFeed))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), name)))
                .as("ноль прочитан как «ни одного» или как прежние десять")
                .isEqualTo(8);

        // Соседняя выгрузка настройки не задавала: у неё прежние десять,
        // и появление поля не должно менять её прайс молча.
        String many = "Прайс: двенадцать снимков " + run;
        Long richId = inTenant(TENANT, () -> pricedPartId(many, 7000));
        inTenant(TENANT, () -> photos(richId, 12, 0));

        assertThat(photoLinksIn(offerOf(mvc.perform(get(feedPath))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), many)))
                .as("у выгрузки без настройки число снимков изменилось само")
                .isEqualTo(10);
    }

    /**
     * Стоимость установки дописывается к описанию объявления.
     *
     * <p><b>Зачем.</b> Поле «Цена установки» есть в карточке и даже в отборе
     * выгрузки, а до объявления не доезжало ни одной строкой: услуга заведена,
     * стоит денег и невидима там, где её покупают.
     *
     * <p>Проверяется вся дорога разом — экран сохраняет настройку, она ложится
     * в {@code marketplace_account.settings}, оттуда её читает выдача прайса, —
     * и четыре состояния, каждое из которых на живом складе встречается:
     * позиция с ценой установки, позиция без неё, позиция с нулём (в выгрузке
     * прежней системы незаполненная «Установка» приходит именно нулём)
     * и соседняя выгрузка, которая приписку не включала.
     */
    @Test
    @DisplayName("Стоимость установки уезжает в описание только там, где включена")
    void installationNoteBelongsToTheFeed() throws Exception {
        // Своё имя на каждый прогон: позиции между прогонами не чистятся —
        // журнал движений неизменяем, приход не удалить.
        String run = java.util.UUID.randomUUID().toString().substring(0, 8);
        String priced = "Прайс: услуга есть " + run;
        String unpriced = "Прайс: услуги нет " + run;
        String zeroed = "Прайс: услуга нулём " + run;

        inTenant(TENANT, () -> {
            jdbc.update("UPDATE part SET installation_price = 1500 WHERE id = ?",
                    pricedPartId(priced, 6000));
            pricedPartId(unpriced, 6100);
            jdbc.update("UPDATE part SET installation_price = 0 WHERE id = ?",
                    pricedPartId(zeroed, 6200));
            return null;
        });

        Long noteId = inTenant(TENANT, () -> jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, settings)
                VALUES ('DROM', 'Дром: с установкой', '{"packetId":"558"}'::jsonb)
                RETURNING id""", Long.class));
        String noteFeed = rotate(noteId);

        assertThat(offerOf(feedOf(noteFeed), priced))
                .as("приписка появилась в прайсе до того, как её включили")
                .doesNotContain("Стоимость установки");

        // Включает владелец с экрана, а не запросом в базу: настройка без
        // места, откуда ею воспользоваться, — это отсутствующая возможность.
        mvc.perform(put("/api/marketplace-accounts/" + noteId + "/settings")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"installationNote": true,
                                 "installationTemplate":
                                   "Стоимость установки на нашем автосервисе: {цена} р."}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.installationNote").value(true));

        String feed = feedOf(noteFeed);

        assertThat(offerOf(feed, priced))
                .as("цена установки так и не доехала до объявления")
                .contains("Стоимость установки на нашем автосервисе: 1500 р.");
        assertThat(offerOf(feed, unpriced))
                .as("у позиции без цены установки в объявлении появилась строка "
                        + "про услугу, которой нет")
                .doesNotContain("Стоимость установки");
        assertThat(offerOf(feed, zeroed))
                .as("ноль прочитан как «бесплатно»: объявление обещает покупателю "
                        + "работу даром от лица разборки, которая её не обещала")
                .doesNotContain("Стоимость установки");

        // Соседняя выгрузка приписку не включала: появление настройки
        // не должно менять чужие прайсы молча.
        assertThat(offerOf(feedOf(feedPath), priced))
                .as("приписка уехала в выгрузку, у которой её не включали")
                .doesNotContain("Стоимость установки");

        // Выключение — это отсутствие строки, а не пустая «Стоимость установки:».
        mvc.perform(put("/api/marketplace-accounts/" + noteId + "/settings")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"installationNote": false,
                                 "installationTemplate":
                                   "Стоимость установки на нашем автосервисе: {цена} р."}"""))
                .andExpect(status().isOk());

        assertThat(offerOf(feedOf(noteFeed), priced))
                .as("выключенная приписка продолжает уезжать в объявление")
                .doesNotContain("Стоимость установки");
    }

    /**
     * Текст приписки без подстановки цены отбивается словами.
     *
     * <p>Пропущенный, он даёт включённую настройку, которая не делает того,
     * ради чего её включили: покупатель читает про стоимость установки
     * и не видит суммы, а владелец узнаёт об этом с чужого сайта.
     */
    @Test
    @DisplayName("Приписка без подстановки цены не сохраняется")
    void installationTemplateWithoutPlaceholderIsRefused() throws Exception {
        mvc.perform(put("/api/marketplace-accounts/" + accountId + "/settings")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installationNote\":true,"
                                + "\"installationTemplate\":\"Установка недорого\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("{цена}")));
    }

    /** Прайс целиком: тело ответа по постоянной ссылке выгрузки. */
    private String feedOf(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Отрицательное число снимков отбивается словами, а не пятисоткой
     * посреди файла.
     *
     * <p>Пропущенное, оно уронило бы сборку прайса после того, как заголовки
     * ответа отправлены: площадка получает 200 и ноль байт, а пустой прайс
     * она понимает буквально — «товаров нет» — и снимает объявления вместе
     * с накопленными просмотрами.
     */
    @Test
    @DisplayName("Отрицательное число снимков не сохраняется")
    void negativePhotoLimitIsRefused() throws Exception {
        mvc.perform(put("/api/marketplace-accounts/" + accountId + "/settings")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoLimit\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("без ограничения")));
    }

    /** Сколько ссылок на снимки стоит в одном {@code <offer>}. */
    private int photoLinksIn(String offer) {
        return offer.split("<photo>", -1).length - 1;
    }

    /**
     * Снимки позиции в том порядке, в каком их показывает прайс: главный
     * первым, дальше по расстановке.
     *
     * @param mainAt какой по счёту снимок отмечен главным — он и обязан
     *               оказаться первой ссылкой
     * @return номера снимков в порядке показа
     */
    private java.util.List<Long> photos(Long partId, int count, int mainAt) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(jdbc.queryForObject("""
                    INSERT INTO part_photo (part_id, s3_key, sort_order, is_main, status)
                    VALUES (?, ?, ?, ?, 'PROCESSED') RETURNING id""",
                    Long.class, partId,
                    "t_000066/parts/%d/snimok-%d.jpg".formatted(partId, i), i, i == mainAt));
        }
        java.util.List<Long> shown = new java.util.ArrayList<>();
        shown.add(ids.get(mainAt));
        for (int i = 0; i < count; i++) {
            if (i != mainAt) {
                shown.add(ids.get(i));
            }
        }
        return shown;
    }

    /**
     * Настройки кладутся слиянием, а не заменой.
     *
     * <p>Рядом в тех же настройках лежит номер прайс-листа в кабинете
     * площадки. Затерев его, мы выключили бы дельты по API целиком —
     * и заметить это нечем: очередь отметок разгребается, журнал публикаций
     * пуст, всё выглядит работающим, а площадка узнаёт о продаже только
     * с полным забором, то есть через трое суток.
     */
    @Test
    @DisplayName("Наценка не затирает номер прайс-листа площадки")
    void savingSettingsKeepsThePacketId() throws Exception {
        mvc.perform(put("/api/marketplace-accounts/" + accountId + "/settings")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePercent\":5}"))
                .andExpect(status().isOk());

        assertThat(inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT settings ->> 'packetId' FROM marketplace_account WHERE id = ?",
                String.class, accountId)))
                .as("номер прайс-листа стёрт сохранением наценки — дельты по API "
                        + "перестанут уходить, и заметить это нечем")
                .isEqualTo("777");
    }

    /** Позиция с ценой и остатком; возвращает её номер. */
    private Long pricedPartId(String title, int price) {
        Long branch = jdbc.queryForObject(
                "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
        Long warehouse = jdbc.queryForObject(
                "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад') RETURNING id",
                Long.class, branch);
        Long partId = jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, cost_price, is_published)
                VALUES (1, ?, ?, 100, true) RETURNING id""", Long.class, title, price);
        ledger.record(StockMovement.intake(partId, java.math.BigDecimal.ONE, warehouse, null));
        return partId;
    }

    @Test
    @DisplayName("Справочник видов отдаётся целиком, а не поиском")
    void kindsAreServedWhole() throws Exception {
        // Экрану отбора нужны названия уже выбранных видов, а поиск
        // по идентификатору не ищет: выбранное показывалось бы пустой строкой.
        String body = mvc.perform(get("/api/part-names/kinds/all").session(login("owner")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Справочник наполняется миграцией: 178 эталонов. Проверяем, что
        // ответ не обрезан пределом поиска, а не точное число — оно растёт
        // с релизами.
        long rows = body.chars().filter(c -> c == '{').count();
        assertThat(rows)
                .as("справочник видов пришёл обрезанным — выбранное в отборе "
                        + "покажется номером вместо названия")
                .isGreaterThan(100);
    }

    /** Кусок прайса про одну позицию: остальные предложения не мешают смотреть. */
    private String offerOf(String feed, String title) {
        int at = feed.indexOf(title);
        assertThat(at).as("позиции «%s» в прайсе нет вовсе", title).isGreaterThan(0);
        int end = feed.indexOf("</offer>", at);
        return feed.substring(at, end < 0 ? feed.length() : end);
    }

    private void pricedPart(String title, int price) {
        Long branch = jdbc.queryForObject(
                "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
        Long warehouse = jdbc.queryForObject(
                "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад') RETURNING id",
                Long.class, branch);
        Long partId = jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, cost_price, is_published)
                VALUES (1, ?, ?, 100, true) RETURNING id""", Long.class, title, price);
        ledger.record(StockMovement.intake(partId, java.math.BigDecimal.ONE, warehouse, null));
    }

    /** Пустое имя — это снятие имени, а не пустая строка в базе. */
    private org.springframework.test.web.servlet.ResultActions setFileName(Long id, String name)
            throws Exception {
        return mvc.perform(put("/api/marketplace-accounts/" + id + "/feed-file")
                .with(csrf()).session(login("owner"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"%s\"}".formatted(name)));
    }

    private String rotate(Long id) throws Exception {
        String body = mvc.perform(post("/api/marketplace-accounts/" + id + "/feed-url")
                        .with(csrf()).session(login("owner")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return pathOf(body);
    }

    private String rotate() throws Exception {
        String body = mvc.perform(post("/api/marketplace-accounts/" + accountId + "/feed-url")
                        .with(csrf()).session(login("owner")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return pathOf(body);
    }

    /**
     * Путь из ответа.
     *
     * <p>Именно разбором поля, а не «всё между кавычками»: рядом с путём
     * теперь едет полный адрес, и наивное выдирание захватывало его вместе
     * с закрывающей скобкой.
     */
    private static String pathOf(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"path\":\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private void register(long id, String schema, String code) {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", id);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка', ?)""", id, schema, code);
    }

    /** Токен из пути прайса: он же открывает и снимки. */
    private static String tokenOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
    }

    /**
     * Снимок существующей позиции либо новой непубликуемой.
     *
     * @param published {@code false} — заводится своя позиция, снятая
     *                  с выгрузки: у публикуемой снимок и должен быть виден
     */
    private Long photo(String title, boolean published) {
        Long partId;
        if (published) {
            // Свежайшая: позиции между прогонами не чистятся — журнал движений
            // неизменяем, и приход не удалить, — поэтому одноимённых накопится
            // столько же, сколько было прогонов.
            partId = jdbc.queryForObject(
                    "SELECT id FROM part WHERE title = ? ORDER BY id DESC LIMIT 1",
                    Long.class, title);
        } else {
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published)
                    VALUES (1, ?, 3000, false) RETURNING id""", Long.class, title);
        }
        return jdbc.queryForObject("""
                INSERT INTO part_photo (part_id, s3_key, sort_order, is_main, status)
                VALUES (?, ?, 0, true, 'PROCESSED') RETURNING id""",
                Long.class, partId, "t_000066/parts/%d/snimok.jpg".formatted(partId));
    }

    private void part(String title) {
        Long branch = jdbc.queryForObject(
                "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
        Long warehouse = jdbc.queryForObject(
                "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад') RETURNING id",
                Long.class, branch);
        Long partId = jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, cost_price, is_published)
                VALUES (1, ?, 5000, 2000, true) RETURNING id""", Long.class, title);
        ledger.record(StockMovement.intake(partId, java.math.BigDecimal.ONE, warehouse, null));
    }

    private void member(String login, String role) {
        if (!jdbc.queryForList("SELECT id FROM tenant_member WHERE login = ?", login).isEmpty()) {
            return;
        }
        jdbc.update("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?)""", login, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"feedco","login":"%s","password":"пароль"}"""
                                .formatted(login)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(String schema, Supplier<T> body) {
        TenantContext.set(schema);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
