package ru.partsflow.reports;

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

import java.time.YearMonth;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Отчёты владельца.
 *
 * <p>Главная проверка одна: возвращённый товар выпадает из зарплатной базы.
 * При частичном возврате сделка остаётся выданной, и отчёт, смотрящий только
 * на статус документа, начислит менеджеру премию за деталь, которую клиент
 * привёз обратно. Ошибка тихая — расходится не отчёт, а зарплата.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class ReportControllerTest extends PostgresTestBase {

    private static final String TENANT = "t_000073";

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

    private Long warehouse;
    private Long customer;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 73");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (73, ?, 'Разборка', 'repco')""", TENANT);

        inTenant(() -> {
            member("vladelec", "Владелец", "OWNER");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            customer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id", Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Возвращённая позиция из зарплатной базы выпадает")
    void returnedItemLeavesManagerRevenue() throws Exception {
        Long staying = partWithStock("Фара для отчёта", 1);
        Long returning = partWithStock("Бампер для отчёта", 1);
        MockHttpSession seller = seller("возврата");

        long dealId = issuedDeal(seller, staying, returning);
        long itemId = itemOf(dealId, returning);

        // Сделка остаётся ISSUED — вернули только одну позицию из двух.
        mvc.perform(post("/api/deals/" + dealId + "/returns").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"не подошла",
                                 "items":[{"dealItemId":%d,"restocked":true}]}"""
                                .formatted(warehouse, itemId)))
                .andExpect(status().isCreated());

        // Продано на 10 000, вернули половину: премия считается с 5 000.
        // Смотри отчёт на статус документа — было бы 10 000.
        mvc.perform(get("/api/reports/managers?month=" + YearMonth.now())
                        .session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.displayName == 'Продавец возврата')].revenue")
                        .value(org.hamcrest.Matchers.contains(5000.00)))
                .andExpect(jsonPath("$.rows[?(@.displayName == 'Продавец возврата')].dealsCount")
                        .value(org.hamcrest.Matchers.contains(1)));
    }

    @Test
    @DisplayName("Наценка считается по снимку себестоимости, а не по нынешней цене")
    void marginUsesCostSnapshot() throws Exception {
        Long partId = partWithStock("Стартер для отчёта", 1);
        MockHttpSession seller = seller("наценки");
        issuedDeal(seller, partId);

        // Себестоимость детали задрана после продажи. Отчёт за прошлый период
        // от этого меняться не должен: в строке сделки лежит снимок.
        inTenant(() -> jdbc.update("UPDATE part SET cost_price = 4900 WHERE id = ?", partId));

        // Цена 5 000, снимок себестоимости 2 000 — наценка 3 000.
        mvc.perform(get("/api/reports/managers").session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.displayName == 'Продавец наценки')].margin")
                        .value(org.hamcrest.Matchers.contains(3000.00)));
    }

    @Test
    @DisplayName("Позиция без себестоимости в наценку не идёт, а считается отдельно")
    void itemWithoutCostIsNotProfit() throws Exception {
        // Так приходит склад из чужой таблицы: цена есть, закупка — нет.
        Long partId = inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, 'Дверь без закупки', 5000)
                    RETURNING id""", Long.class);
            ledger.record(StockMovement.intake(id, java.math.BigDecimal.ONE, warehouse, null));
            return id;
        });
        issuedDeal(seller("без себестоимости"), partId);

        // Наценка пуста, а не равна выручке: «себестоимость неизвестна»
        // и «продали с нулевой закупкой» — разные утверждения, и второе
        // говорит владельцу, что он заработал 5 000 чистыми.
        mvc.perform(get("/api/reports/managers").session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.displayName == 'Продавец без себестоимости')].margin")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath(
                        "$.rows[?(@.displayName == 'Продавец без себестоимости')].itemsWithoutCost")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.rows[?(@.displayName == 'Продавец без себестоимости')].revenue")
                        .value(org.hamcrest.Matchers.contains(5000.00)));
    }

    @Test
    @DisplayName("Месяц без продаж отдаёт пустой отчёт, а не прошлый")
    void emptyMonthIsEmpty() throws Exception {
        issuedDeal(seller("месяца"), partWithStock("Капот для отчёта", 1));

        mvc.perform(get("/api/reports/managers?month=2020-01").session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2020-01"))
                .andExpect(jsonPath("$.rows.length()").value(0));
    }

    @Test
    @DisplayName("Кривой месяц — 400, а не 500")
    void brokenMonthIsRejected() throws Exception {
        mvc.perform(get("/api/reports/managers?month=июль").session(login("vladelec")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Окупаемость донора: вложено, выручено, сколько ещё лежит")
    void donorProfitability() throws Exception {
        Long donorId = inTenant(() -> {
            // brand_id обязателен: донор без марки — это не машина.
            Long brandId = jdbc.queryForObject(
                    "SELECT id FROM catalog.brand ORDER BY id LIMIT 1", Long.class);
            Long id = jdbc.queryForObject("""
                    INSERT INTO donor (public_code, vin, brand_id, year)
                    VALUES ('D-1', 'VIN1', ?, 2006) RETURNING id""", Long.class, brandId);
            jdbc.update("""
                    INSERT INTO donor_cost (donor_id, cost_type, amount)
                    VALUES (?, 'PURCHASE', 30000)""", id);
            return id;
        });

        Long sold = partOfDonor(donorId, "Дверь донорская", 1);
        Long shelved = partOfDonor(donorId, "Крыло донорское", 1);
        issuedDeal(seller("донора"), sold);

        mvc.perform(get("/api/reports/donors").session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.publicCode == 'D-1')].totalCost")
                        .value(org.hamcrest.Matchers.contains(30000.00)))
                .andExpect(jsonPath("$.rows[?(@.publicCode == 'D-1')].revenue")
                        .value(org.hamcrest.Matchers.contains(5000.00)))
                .andExpect(jsonPath("$.rows[?(@.publicCode == 'D-1')].partsSold")
                        .value(org.hamcrest.Matchers.contains(1)))
                // Без остатка на складе свежая машина неотличима от убыточной:
                // вложили 30 000, выручили 5 000 — и ещё 5 000 лежит на полке.
                .andExpect(jsonPath("$.rows[?(@.publicCode == 'D-1')].stockValue")
                        .value(org.hamcrest.Matchers.contains(5000.00)))
                .andExpect(jsonPath("$.totals.donors").value(1));

        // Отмечаем, что вторая деталь действительно осталась на складе.
        org.assertj.core.api.Assertions.assertThat(statusOf(shelved)).isEqualTo("IN_STOCK");
    }

    @Test
    @DisplayName("Продавец зарплатную базу и себестоимость не видит")
    void sellerCannotReadReports() throws Exception {
        MockHttpSession seller = seller("роли");

        mvc.perform(get("/api/reports/managers").session(seller))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/donors").session(seller))
                .andExpect(status().isForbidden());
    }

    /**
     * Свой продавец на каждый тест.
     *
     * <p>Отчёт группирует по менеджеру и месяцу, а схема между тестами
     * не чистится: журнал движений неизменяем, и сделки с него не снять.
     * Общий продавец сложил бы выручку соседних тестов в одну строку,
     * и проверка абсолютных чисел зависела бы от порядка запуска.
     */
    private MockHttpSession seller(String name) throws Exception {
        String login = "prodavets-" + name;
        inTenant(() -> member(login, "Продавец " + name, "SELLER"));
        return login(login);
    }

    @Test
    @DisplayName("Продажи разложены по каналам, а безымянные видны отдельной строкой")
    void salesAreSplitBySource() throws Exception {
        MockHttpSession session = login("vladelec");
        Long drom = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM deal_source WHERE name = 'Дром'", Long.class));

        Long fromDrom = partWithStock("Фара с Дрома", 1);
        Long noSource = partWithStock("Фара без источника", 1);

        issuedDealFrom(session, drom, fromDrom);
        issuedDealFrom(session, null, noSource);

        var response = mvc.perform(get("/api/reports/sources")
                        .param("month", java.time.YearMonth.now().toString())
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Канал назван — по нему и считают счёт от площадки. Сверяемся
        // по идентификатору, а не по названию: MockMvc отдаёт тело
        // в ISO-8859-1, и кириллица в сравнении превращается в кашу.
        assertThat(response).contains("\"sourceId\":" + drom);
        // Сделка без источника не выброшена: невидимая часть выручки делает
        // отчёт бесполезным — по нему нельзя понять, канал не приносит денег
        // или продавцы не отмечают источник.
        assertThat(response)
                .as("выручка без источника растворилась — отчёт врёт молча")
                .contains("\"sourceName\":null");
    }

    /**
     * Отчёт по расчётам: у кого наши деньги и кто должен нам.
     *
     * <p>Владелец не видел своих обязательств перед клиентами ни одним числом.
     */
    @Test
    @DisplayName("Расчёты показывают аванс клиента и его долг")
    void settlementsShowAdvanceAndDebt() throws Exception {
        MockHttpSession seller = seller("расчётов");
        Long partId = partWithStock("Фара в долг", 1);
        long dealId = issuedDeal(seller, partId);

        // Оплатили половину: остальное — долг по выданному товару.
        mvc.perform(post("/api/deals/" + dealId + "/payments").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":2000}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/reports/customers").session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.customerId == %d)].debt".formatted(customer))
                        .value(org.hamcrest.Matchers.contains(3000.00)))
                // Сверка едет вместе с итогом: число обязательств без ответа
                // «сходится ли» — спокойствие без основания.
                .andExpect(jsonPath("$.totals.problems.length()").value(0))
                // Общее число клиентов: список обрезан пределом, и без него
                // экран не может сказать, что показывает не всех.
                .andExpect(jsonPath("$.totals.customers").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    /**
     * Сверка обязана ловить ровно ту потерю, ради которой заведена.
     *
     * <p>Отменённая сделка с оплатой, по которой деньги не вернулись, —
     * это и есть расхождение, найденное когда-то руками: сделка закрыта,
     * со счёта списано, а деньги не числятся нигде.
     */
    @Test
    @DisplayName("Сверка находит отменённую сделку, по которой деньги не вернулись")
    void reconciliationFindsLostMoney() throws Exception {
        MockHttpSession seller = seller("потери");
        Long partId = partWithStock("Бампер потерянных денег", 1);

        long dealId = reservedDeal(seller, partId);

        mvc.perform(post("/api/deals/" + dealId + "/payments").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5000}"))
                .andExpect(status().isCreated());

        // Ломаем руками так, как ломалось до правки: сделку закрыли,
        // а деньги не вернули.
        inTenant(() -> jdbc.update(
                "UPDATE deal SET status = 'CANCELLED' WHERE id = ?", dealId));

        mvc.perform(get("/api/reports/customers").session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.problems.length()").value(1))
                .andExpect(jsonPath("$.totals.problems[0].problem")
                        .value(org.hamcrest.Matchers.containsString("оплата не возвращена")))
                .andExpect(jsonPath("$.totals.problems[0].amount").value(5000.00));
    }

    /** Отложенная сделка без выдачи: нужна там, где её потом закрывают отменой. */
    private long reservedDeal(MockHttpSession session, Long partId) throws Exception {
        var created = mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":%d,\"items\":[{\"partId\":%d,\"quantity\":1,\"warehouseId\":%d}]}"
                                .formatted(customer, partId, warehouse)))
                .andExpect(status().isCreated())
                .andReturn();
        // Разбираем разбором, а не регуляркой: у ответа со списком позиций
        // она молча возвращает всю строку, и падает это уже на parseLong.
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsByteArray())
                .get("id").asLong();
    }

    private long issuedDeal(MockHttpSession session, Long... partIds) throws Exception {
        return issuedDealFrom(session, null, partIds);
    }

    private long issuedDealFrom(MockHttpSession session, Long sourceId, Long... partIds)
            throws Exception {
        String items = java.util.Arrays.stream(partIds)
                .map(id -> "{\"partId\":%d,\"quantity\":1,\"warehouseId\":%d}"
                        .formatted(id, warehouse))
                .reduce((a, b) -> a + "," + b)
                .orElseThrow();

        var created = mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":%d,\"dealSourceId\":%s,\"items\":[%s]}"
                                .formatted(customer, sourceId, items)))
                .andExpect(status().isCreated())
                .andReturn();

        long dealId = Long.parseLong(created.getResponse().getContentAsString()
                .replaceAll("^\\{\"id\":(\\d+).*$", "$1"));

        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());
        return dealId;
    }

    private long itemOf(long dealId, Long partId) throws Exception {
        var result = mvc.perform(get("/api/deals/" + dealId).session(login("vladelec")))
                .andExpect(status().isOk())
                .andReturn();
        var matcher = java.util.regex.Pattern
                .compile("\\{\"id\":(\\d+),\"partId\":" + partId + ",")
                .matcher(result.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(matcher.find()).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private Long partWithStock(String title, int qty) {
        return partOfDonor(null, title, qty);
    }

    private Long partOfDonor(Long donorId, String title, int qty) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, donor_id, title, price, cost_price)
                    VALUES (1, ?, ?, 5000, 2000) RETURNING id""", Long.class, donorId, title);
            ledger.record(StockMovement.intake(partId, java.math.BigDecimal.valueOf(qty), warehouse, null));
            return partId;
        });
    }

    private String statusOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM part WHERE id = ?", String.class, partId));
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

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"repco","login":"%s","password":"пароль"}"""
                                .formatted(login)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
