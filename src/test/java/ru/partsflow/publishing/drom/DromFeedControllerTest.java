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
