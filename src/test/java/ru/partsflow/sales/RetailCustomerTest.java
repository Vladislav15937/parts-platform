package ru.partsflow.sales;

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
import ru.partsflow.inventory.StockLedger;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Розничная продажа оформляется без заведения клиента (задача 0011).
 *
 * <p>Половина продаж на разборке — человек с улицы. Пока клиент был
 * обязателен, продавец набирал имя и жал «Завести клиента», и справочник
 * зарастал «мужиками на приоре»; теперь в форме стоит один контрагент
 * «Частное лицо», а имя покупателя выясняется после — если он назвался.
 *
 * <p><b>Схема своя ({@code t_000150}) и в ней не заведено ни одного
 * контрагента розничной продажи.</b> Так выглядит арендатор, созданный
 * раньше этой правки: {@code provisionTenants} накатывает миграции, а
 * провижининг, который «Частное лицо» и заводит, мимо. То есть проверяется
 * заодно и путь дозаведения — а он единственный доступный живым клиентам,
 * потому что наполнить их схемы миграцией нельзя.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class RetailCustomerTest extends PostgresTestBase {

    private static final String TENANT = "t_000150";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StockLedger ledger;

    private Long warehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 150");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (150, ?, 'Разборка', 'retailco')""", TENANT);

        inTenant(() -> {
            member("seller", "Продавец", "SELLER");
            member("owner", "Хозяин", "OWNER");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Продажа без клиента оформляется: контрагент — «Частное лицо»")
    void dealWithoutCustomerGetsRetailContractor() throws Exception {
        Long partId = partWithStock("Бампер передний розничный", 1);

        mvc.perform(post("/api/deals").session(login("seller")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(partId, warehouse)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Частное лицо"));
    }

    @Test
    @DisplayName("Две розничные продажи подряд не заводят двух «Частных лиц»")
    void twoRetailDealsShareOneContractor() throws Exception {
        MockHttpSession session = login("seller");
        Long first = partWithStock("Фара розничная первая", 1);
        Long second = partWithStock("Фара розничная вторая", 1);

        long firstCustomer = customerOfDeal(session, first);
        long secondCustomer = customerOfDeal(session, second);

        assertThat(secondCustomer).isEqualTo(firstCustomer);
        // И в справочнике он ровно один: разойдись подстановка с проверкой,
        // владелец нашёл бы двух «Частных лиц» с разбитой пополам историей.
        assertThat(retailRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("Клиента сделки меняют после создания, и это видно в истории")
    void customerIsChangedAfterCreationAndLogged() throws Exception {
        MockHttpSession session = login("seller");
        Long partId = partWithStock("Крыло розничное", 1);
        long dealId = dealOf(session, partId);
        Long real = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Евгений Гридин') RETURNING id", Long.class));

        mvc.perform(post("/api/deals/" + dealId + "/customer").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":%d}".formatted(real)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(real))
                .andExpect(jsonPath("$.customerName").value("Евгений Гридин"));

        // Отдельной строкой и с обоими именами: «сделка была оформлена
        // на кого-то другого» выясняется через недели, при возврате или
        // разборе долга, и ответ должен читаться прямо из журнала.
        mvc.perform(get("/api/deals/" + dealId + "/history").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType == 'CUSTOMER_CHANGED')].message")
                        .value(org.hamcrest.Matchers.hasItem(
                                "Изменён контрагент с Частное лицо на Евгений Гридин")));
    }

    @Test
    @DisplayName("По оплаченной сделке контрагента не меняют — словами, а не пятисоткой")
    void customerIsNotChangedWhenMoneyMoved() throws Exception {
        MockHttpSession session = login("seller");
        Long partId = partWithStock("Дверь розничная", 1);
        long dealId = dealOf(session, partId);

        mvc.perform(post("/api/deals/" + dealId + "/payments").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isCreated());

        Long real = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Пётр Оплативший') RETURNING id", Long.class));

        // Платёж записан на прежнего клиента: сменив контрагента, мы оставили
        // бы деньги одного человека в документе другого.
        mvc.perform(post("/api/deals/" + dealId + "/customer").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":%d}".formatted(real)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("уже проходили деньги")));
    }

    @Test
    @DisplayName("Второго «Частного лица» руками не завести: возвращается прежний")
    void retailNameIsNotDuplicatedByHand() throws Exception {
        MockHttpSession session = login("seller");
        long fromEndpoint = retailId(session);

        var result = mvc.perform(post("/api/customers").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Частное лицо\",\"phone\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(idOf(result.getResponse().getContentAsString())).isEqualTo(fromEndpoint);
        assertThat(retailRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("«Частное лицо» не переименовывается: имя — единственный его признак")
    void retailContractorIsNotRenamed() throws Exception {
        MockHttpSession owner = login("owner");
        long id = retailId(owner);

        mvc.perform(put("/api/customers/" + id).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Розница\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("переименованию не подлежит")));
    }

    @Test
    @DisplayName("Отчёт по клиентам помечает «Частное лицо», а не выдаёт за покупателя")
    void settlementReportMarksRetailRow() throws Exception {
        MockHttpSession seller = login("seller");
        long retail = retailId(seller);
        Long partId = partWithStock("Капот розничный", 1);
        long dealId = dealOf(seller, partId);
        // Долг возникает только у выданной сделки: пока товар не отдан,
        // это обещание, и в расчёты клиент не попадает вовсе.
        mvc.perform(post("/api/deals/" + dealId + "/issue").session(seller).with(csrf()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/reports/customers").session(login("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.customerId == %d)].retail".formatted(retail))
                        .value(org.hamcrest.Matchers.hasItem(true)));
    }

    private long retailId(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/customers/retail").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Частное лицо"))
                .andReturn();
        return idOf(result.getResponse().getContentAsString());
    }

    /** Сколько строк с именем розничного контрагента лежит в справочнике. */
    private int retailRows() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM customer WHERE name = ?", Integer.class,
                CustomerService.RETAIL_NAME));
    }

    private long customerOfDeal(MockHttpSession session, Long partId) throws Exception {
        var result = mvc.perform(post("/api/deals").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(partId, warehouse)))
                .andExpect(status().isCreated())
                .andReturn();
        Number customerId = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.customerId");
        assertThat(customerId)
                .as("продажа без клиента обязана уехать на контрагента розничной продажи")
                .isNotNull();
        return customerId.longValue();
    }

    private long dealOf(MockHttpSession session, Long partId) throws Exception {
        var result = mvc.perform(post("/api/deals").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(partId, warehouse)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result.getResponse().getContentAsString());
    }

    private static long idOf(String body) {
        return Long.parseLong(body.replaceAll("^\\{\"id\":(\\d+).*$", "$1"));
    }

    private Long partWithStock(String title, int qty) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price)
                    VALUES (1, ?, 5000, 2000) RETURNING id""", Long.class, title);
            ledger.record(StockMovement.intake(partId, BigDecimal.valueOf(qty), warehouse, null));
            return partId;
        });
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
                                {"company":"retailco","login":"%s","password":"пароль"}"""
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
