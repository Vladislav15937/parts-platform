package ru.partsflow.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.StockLedger;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проданные позиции строками: цена продажи, себестоимость, выгода.
 *
 * <p>Главная проверка — <b>строка и подвал говорят об одном</b>. Сумма цен
 * продажи по показанным строкам обязана равняться выручке в подвале, который
 * считается независимым запросом по всему отбору. Ровно этого равенства
 * не было у вкладки «Продано» в разрезе по машине: строка брала цену
 * из карточки, подвал — из сделок, и владелец, сложивший колонку глазами,
 * подвала не получал.
 *
 * <p>Вторая по важности — <b>цена не следует за карточкой</b>. После продажи
 * цену позиции поднимают (на разборке её двигают ежедневно), и отчёт о том,
 * сколько заработали в июле, обязан остаться июльским.
 *
 * <p><b>Фикстура заводится один раз на весь класс</b>: отчёт считает
 * абсолютные числа по арендатору, а схема между тестами не чистится —
 * журнал движений неизменяем. Заводись продажи на каждый метод, вторая
 * из них удвоила бы подвал, и падал бы не тот тест, который виноват.
 *
 * <p>Схема своя: продажа соседнего теста сдвинула бы те же числа.
 *
 * <p>Через HTTP, а не вызовом сервиса: обычный класс в ответе контроллера
 * Jackson не сериализует, и тест, зовущий сервис напрямую, этого не увидит.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class SoldItemsReportTest extends PostgresTestBase {

    private static final String TENANT = "t_000124";

    private static Long nearWarehouse;
    private static Long farWarehouse;
    private static Long customer;
    private static Long lampDonor;
    private static Long doorDonor;
    private static Long supply;
    private static Long doorPart;
    private static Long secondSellerId;
    private static boolean prepared;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockHttpSession owner;
    private MockHttpSession seller;
    private MockHttpSession secondSeller;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        register(124, TENANT, "prodano");
        inTenant(TENANT, () -> {
            member("vladelec", "Владелец", "OWNER");
            member("prodavets", "Иван Продавцов", "SELLER");
            secondSellerId = member("prodavets2", "Пётр Сменщиков", "SELLER");
            return null;
        });

        owner = login("prodano", "vladelec");
        seller = login("prodano", "prodavets");
        secondSeller = login("prodano", "prodavets2");

        prepare();
    }

    /**
     * То, ради чего отчёт заведён: три числа, и все три из сделки.
     *
     * <p>Выгода считается по строке — «продать деталь с себестоимостью 1 000
     * за 1 500 — в строке 500». У двери это 1 200 при закупке 700.
     */
    @Test
    @DisplayName("Строка показывает цену продажи, себестоимость и выгоду — из сделки")
    void rowCarriesSalePriceCostAndProfit() throws Exception {
        JsonNode door = row(page(""), "Дверь");

        assertThat(door.path("price").decimalValue())
                .as("цена в строке — не та, за которую продали")
                .isEqualByComparingTo("1200");
        assertThat(door.path("costPrice").decimalValue()).isEqualByComparingTo("700");
        assertThat(door.path("profit").decimalValue())
                .as("выгода — это цена продажи минус себестоимость")
                .isEqualByComparingTo("500");

        // И то, чем позицию зовут: номер с витрины, состояние словом,
        // склад выдачи, ответственный и происхождение.
        assertThat(door.path("publicCode").asText()).isNotEmpty();
        assertThat(door.path("condition").asText()).isEqualTo("б/у");
        assertThat(door.path("warehouse").asText()).isEqualTo("Ткацкая");
        assertThat(door.path("manager").asText()).isEqualTo("Иван Продавцов");
        assertThat(door.path("donorCode").asText()).isEqualTo("350");
        assertThat(door.path("dealNumber").asLong()).isPositive();
    }

    /**
     * Цена карточки после продажи меняется — цена продажи нет.
     *
     * <p>На разборке цену двигают ежедневно, и отчёт о том, сколько
     * заработали в июле, обязан остаться июльским. То же и с себестоимостью:
     * переоценка донора задним числом не должна переписывать прошлую прибыль.
     */
    @Test
    @DisplayName("Поднятая после продажи цена карточки строку не меняет")
    void raisingTheCatalogPriceLeavesTheRowAlone() throws Exception {
        BigDecimal catalogPrice = inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT price FROM part WHERE id = ?", BigDecimal.class, doorPart));

        assertThat(catalogPrice)
                .as("фикстура не подняла цену карточки — проверять нечего")
                .isEqualByComparingTo("2000");

        JsonNode door = row(page(""), "Дверь");
        assertThat(door.path("price").decimalValue()).isEqualByComparingTo("1200");
        assertThat(door.path("costPrice").decimalValue()).isEqualByComparingTo("700");
    }

    /**
     * Сумма показанных цен равна подвалу.
     *
     * <p>Это и есть проверка на разъехавшиеся поверхности: подвал считается
     * независимым запросом по всему отбору, а строки — своим. Пока строка
     * брала цену из карточки, а подвал из сделок, эти два числа отвечали
     * на один вопрос по-разному.
     */
    @Test
    @DisplayName("Сумма цен по строкам равна выручке в подвале")
    void rowsAddUpToTheFooter() throws Exception {
        JsonNode page = page("");

        BigDecimal byRows = BigDecimal.ZERO;
        for (JsonNode row : page.path("rows")) {
            byRows = byRows.add(row.path("price").decimalValue()
                    .multiply(row.path("quantity").decimalValue()));
        }

        assertThat(byRows)
                .as("строка и подвал говорят о разном: сложенная колонка "
                        + "«Цена продажи» не даёт выручку подвала")
                .isEqualByComparingTo(page.path("totals").path("revenue").decimalValue());
        assertThat(byRows).isEqualByComparingTo("3200");

        // Подвал считает и остальное: штуки, себестоимость, выгоду и то,
        // сколько строк в себестоимость не вошло.
        JsonNode totals = page.path("totals");
        assertThat(totals.path("items").asInt()).isEqualTo(3);
        assertThat(totals.path("quantity").decimalValue()).isEqualByComparingTo("3");
        assertThat(totals.path("cost").decimalValue()).isEqualByComparingTo("1700");
        assertThat(totals.path("profit").decimalValue()).isEqualByComparingTo("700");
        assertThat(totals.path("withoutCost").asInt())
                .as("позиция без закупки не названа: посчитанная по ней прибыль "
                        + "завышена на всю её стоимость")
                .isEqualTo(1);
    }

    /**
     * Проданное со скидкой видно как таковое.
     *
     * <p>Прежняя цена едет рядом отдельным числом, и экран рисует её
     * зачёркнутой. Без неё «продали за 1 200» и «продали за 1 500 со скидкой
     * 300» с экрана выглядят одинаково.
     */
    @Test
    @DisplayName("У проданного со скидкой прежняя цена приезжает отдельным числом")
    void discountedRowCarriesThePriceItHadBefore() throws Exception {
        JsonNode lamp = row(page(""), "Фара");

        assertThat(lamp.path("price").decimalValue()).isEqualByComparingTo("1200");
        assertThat(lamp.path("listPrice").decimalValue())
                .as("прежняя цена не приехала — скидку с экрана не увидеть")
                .isEqualByComparingTo("1500");

        // А у проданного без скидки зачёркивать нечего: числа совпадают,
        // и экран второе не рисует.
        JsonNode door = row(page(""), "Дверь");
        assertThat(door.path("listPrice").decimalValue())
                .isEqualByComparingTo(door.path("price").decimalValue());
    }

    /** Отбор уходит в запрос, а подвал считается по нему же. */
    @Test
    @DisplayName("Отбор по складу, продавцу, машине, поставке и периоду")
    void filtersNarrowBothRowsAndFooter() throws Exception {
        JsonNode byWarehouse = page("warehouseId=" + farWarehouse);
        assertThat(byWarehouse.path("rows").size()).isEqualTo(1);
        assertThat(revenue(byWarehouse)).isEqualByComparingTo("800");

        JsonNode byManager = page("managerId=" + secondSellerId);
        assertThat(byManager.path("rows").size()).isEqualTo(1);
        assertThat(row(byManager, "Стекло").path("manager").asText())
                .isEqualTo("Пётр Сменщиков");

        JsonNode byDonor = page("donorId=" + lampDonor);
        assertThat(byDonor.path("totals").path("items").asInt()).isEqualTo(2);
        assertThat(revenue(byDonor)).isEqualByComparingTo("2000");

        JsonNode bySupply = page("supplyId=" + supply);
        assertThat(bySupply.path("rows").size()).isEqualTo(1);
        assertThat(revenue(bySupply)).isEqualByComparingTo("1200");

        // Период включительно с обеих сторон: «по сегодня» означает сегодня
        // целиком, а не полночь перед ним — иначе из отчёта пропадает день.
        LocalDate today = LocalDate.now();
        JsonNode todayOnly = page("from=" + today + "&to=" + today);
        assertThat(todayOnly.path("totals").path("items").asInt()).isEqualTo(3);

        JsonNode tomorrow = page("from=" + today.plusDays(1));
        assertThat(tomorrow.path("rows").size()).isZero();
        assertThat(tomorrow.path("totals").path("items").asInt()).isZero();
        assertThat(revenue(tomorrow)).isEqualByComparingTo("0");
    }

    /**
     * Страница, а не всё сразу — и подвал по-прежнему по всему отбору.
     *
     * <p>У живого клиента 82 549 проданных позиций. Сумма первой сотни,
     * выданная за итог, — враньё тем более наглядное, чем длиннее история.
     */
    @Test
    @DisplayName("Строки отдаются страницей, а итог остаётся по всему отбору")
    void itemsComeInPages() throws Exception {
        JsonNode first = page("size=2");
        assertThat(first.path("rows").size()).isEqualTo(2);
        assertThat(first.path("totals").path("items").asInt()).isEqualTo(3);
        assertThat(revenue(first)).isEqualByComparingTo("3200");

        String after = first.path("nextAfter").asText();
        assertThat(after).isNotEmpty();

        // Метка уезжает в адрес как есть: в ней только цифры, точка
        // и подчёркивание — экран возвращает её тем же, чем получил.
        JsonNode second = page("size=2&after=" + after);
        assertThat(second.path("rows").size()).isEqualTo(1);
        assertThat(revenue(second)).isEqualByComparingTo("3200");
        assertThat(second.path("nextAfter").isNull())
                .as("страница кончилась, а экран получил бы «Показать ещё»")
                .isTrue();
    }

    /**
     * Список продавцов для отбора — кто продавал, а не весь штат.
     *
     * <p>Отбор, предлагающий человека без единой продажи, врёт; а справочник
     * сотрудников открыт только владельцу, отчёты же читает и менеджер.
     */
    @Test
    @DisplayName("Список ответственных считается по продажам")
    void managersComeFromSalesAndNotFromTheStaffList() throws Exception {
        JsonNode managers = page("").path("managers");
        assertThat(managers.findValuesAsText("name"))
                .containsExactlyInAnyOrder("Иван Продавцов", "Пётр Сменщиков");
        assertThat(managers.findValuesAsText("name"))
                .as("в отборе предложен владелец, который ничего не продавал")
                .doesNotContain("Владелец");
    }

    /** Себестоимость — то, ради чего отчёты закрыты от продавца. */
    @Test
    @DisplayName("Отчёт видит владелец или менеджер, но не продавец")
    void sellerCannotReadSoldItems() throws Exception {
        mvc.perform(get("/api/reports/sold-items").session(seller))
                .andExpect(status().isForbidden());
        // И файл тоже: проверка стоит до первого байта, иначе отказ уехал бы
        // двухсотым с себестоимостью внутри.
        mvc.perform(get("/api/reports/sold-items/export").session(seller))
                .andExpect(status().isForbidden());
    }

    /** Выгрузка отдаёт то же, что экран, — и словами, а не кодами. */
    @Test
    @DisplayName("Таблица скачивается с теми же строками и тем же отбором")
    void exportWritesTheSameRows() throws Exception {
        String all = mvc.perform(get("/api/reports/sold-items/export").session(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(all).contains("Цена продажи", "Себестоимость", "Выгода");
        assertThat(all).contains("Дверь", "Фара", "Стекло");
        // Состояние словом: файл открывают в Excel и читают глазами,
        // и «USED» там означал бы утечку внутреннего представления.
        assertThat(all).contains("б/у").doesNotContain("USED");

        String narrowed = mvc.perform(
                        get("/api/reports/sold-items/export?warehouseId=" + farWarehouse)
                                .session(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(narrowed)
                .as("отбор файла разошёлся с отбором экрана")
                .contains("Стекло").doesNotContain("Дверь");
    }

    /** Опечатка в дате — ошибка запроса, а не поломка сервера. */
    @Test
    @DisplayName("Неразобранная дата — 400 со словами, а не пятисотка")
    void brokenDateIsRefusedWithExplanation() throws Exception {
        mvc.perform(get("/api/reports/sold-items?from=вчера").session(owner))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/sold-items?after=мусор").session(owner))
                .andExpect(status().isBadRequest());
        // И у выгрузки тоже, а это условие сильнее: разбор даты обязан стоять
        // до `getOutputStream()`. Отдав первый байт, статус уже не сменить —
        // отказ уехал бы двухсотым с оборванным файлом внутри.
        mvc.perform(get("/api/reports/sold-items/export?to=позавчера").session(owner))
                .andExpect(status().isBadRequest());
    }

    /**
     * Три продажи: со скидкой, по правленой потом карточке и без закупки.
     *
     * <p>Позиция без себестоимости заведена намеренно: склад, приехавший
     * из чужой таблицы, приходит без закупок целиком, и именно на ней ноль
     * вместо прочерка превратил бы выручку в прибыль.
     */
    private void prepare() throws Exception {
        if (prepared) {
            return;
        }
        prepared = true;

        Long lampPart = inTenant(TENANT, () -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            nearWarehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            farWarehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Дальний') RETURNING id",
                    Long.class, branch);
            customer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id", Long.class);

            supply = jdbc.queryForObject("""
                    INSERT INTO supply (kind, number, supplier_name, status)
                    VALUES ('CONTAINER', 'К-9', 'Armtek', 'ARRIVED') RETURNING id""", Long.class);

            lampDonor = donor("ПРОД-1", "500", "Toyota Camry");
            doorDonor = donor("ПРОД-2", "350", "Nissan Note");

            Long lamp = part("Фара", 1500, 1000, lampDonor, supply);
            stock(lamp, nearWarehouse);
            doorPart = part("Дверь", 1500, 700, doorDonor, null);
            stock(doorPart, nearWarehouse);
            // Без закупочной цены: так приезжает склад из чужой таблицы.
            Long glass = part("Стекло", 800, null, lampDonor, null);
            stock(glass, farWarehouse);
            return lamp;
        });

        Long glassPart = inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT id FROM part WHERE title = 'Стекло'", Long.class));

        // Фара: цена сделки 1 500 и скидка 300 — то есть продана по 1 200.
        // Скидку ставим прямо в строку: с экрана её сегодня не записать,
        // и это названо в отчёте по задаче отдельным пунктом.
        long lampDeal = sell(seller, lampPart, 1500, nearWarehouse);
        inTenant(TENANT, () -> jdbc.update(
                "UPDATE deal_item SET discount = 300 WHERE deal_id = ?", lampDeal));
        issue(seller, lampDeal);

        // Дверь: продана за 1 200 при карточке 1 500, потом карточку правят.
        issue(seller, sell(seller, doorPart, 1200, nearWarehouse));
        inTenant(TENANT, () -> jdbc.update(
                "UPDATE part SET price = 2000, cost_price = 1900 WHERE id = ?", doorPart));

        // Стекло: другой продавец, другой склад, закупки нет.
        issue(secondSeller, sell(secondSeller, glassPart, 800, farWarehouse));
    }

    private Long donor(String code, String legacy, String note) {
        return jdbc.queryForObject("""
                INSERT INTO donor (public_code, legacy_code, brand_id, note, status)
                VALUES (?, ?, (SELECT id FROM catalog.brand ORDER BY id LIMIT 1), ?, 'DISMANTLING')
                RETURNING id""", Long.class, code, legacy, note);
    }

    private Long part(String title, int price, Integer cost, Long donorId, Long supplyId) {
        return jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, cost_price, condition,
                                  quantity, donor_id, supply_id, product_line)
                VALUES (1, ?, ?, ?, 'USED', 1, ?, ?, 'PART') RETURNING id""",
                Long.class, title, price, cost, donorId, supplyId);
    }

    private void stock(Long partId, Long warehouseId) {
        ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouseId, null));
    }

    private long sell(MockHttpSession who, Long partId, int price, Long warehouseId)
            throws Exception {
        var created = mvc.perform(post("/api/deals").with(csrf()).session(who)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"items":[
                                  {"partId":%d,"quantity":1,"price":%d,"warehouseId":%d}]}"""
                                .formatted(customer, partId, price, warehouseId)))
                .andExpect(status().isCreated())
                .andReturn();
        return new ObjectMapper()
                .readTree(created.getResponse().getContentAsByteArray())
                .get("id").asLong();
    }

    private void issue(MockHttpSession who, long dealId) throws Exception {
        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(who))
                .andExpect(status().isOk());
    }

    private JsonNode page(String query) throws Exception {
        return json(get("/api/reports/sold-items?" + query).session(owner));
    }

    private static JsonNode row(JsonNode page, String title) {
        for (JsonNode row : page.path("rows")) {
            if (title.equals(row.path("title").asText())) {
                return row;
            }
        }
        throw new AssertionError("строки «" + title + "» нет в отчёте: " + page.path("rows"));
    }

    private static BigDecimal revenue(JsonNode page) {
        return page.path("totals").path("revenue").decimalValue();
    }

    private JsonNode json(RequestBuilder request) throws Exception {
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
    }

    private void register(int id, String schema, String code) {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", id);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка', ?)""", id, schema, code);
    }

    private Long member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, displayName, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String company, String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"%s","password":"пароль"}"""
                                .formatted(company, login)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(String tenant, Supplier<T> body) {
        TenantContext.set(tenant);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
