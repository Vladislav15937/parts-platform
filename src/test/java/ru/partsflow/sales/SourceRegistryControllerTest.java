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

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST источников платежей и источников сделок — экран «Настройки» (0024).
 *
 * <p>Обе таблицы жили в схеме и в контроллерах с самого начала — продавец
 * выбирает их при каждой продаже и оплате, — а завести новый источник или
 * снять лишний с работы можно было только SQL: поле есть, человеку
 * недоступно.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class SourceRegistryControllerTest extends PostgresTestBase {

    private static final String TENANT = "t_000099";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 99");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (99, ?, 'Разборка', 'sourceco')""", TENANT);

        inTenant(() -> {
            // Полная очистка, а не только справочников: один тест платит
            // по-настоящему (paymentRecordsChosenSource) и оставляет payment,
            // ссылающийся на payment_source, — без этого DELETE справочника
            // в соседнем тесте падает на внешнем ключе.
            jdbc.update("DELETE FROM deal_return_item");
            jdbc.update("DELETE FROM deal_return");
            jdbc.update("DELETE FROM payment");
            jdbc.update("DELETE FROM customer_account_entry");
            jdbc.update("DELETE FROM deal_item");
            jdbc.update("DELETE FROM deal");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM part");
            jdbc.update("DELETE FROM customer");
            jdbc.update("DELETE FROM warehouse");
            jdbc.update("DELETE FROM branch");
            jdbc.update("DELETE FROM payment_source");
            jdbc.update("DELETE FROM deal_source");
            member("owner", "Владелец", "OWNER");
            member("seller", "Продавец", "SELLER");
            member("viewer", "Смотрящий", "VIEWER");
            return null;
        });
    }

    // ---------------------------------------------------------------
    // Источники платежей
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Владелец заводит источник платежа с типом")
    void ownerCreatesPaymentSource() throws Exception {
        mvc.perform(post("/api/payment-sources").with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ККМ","sourceType":"CASH"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("ККМ"))
                .andExpect(jsonPath("$.sourceType").value("CASH"))
                .andExpect(jsonPath("$.archived").value(false));

        mvc.perform(get("/api/payment-sources").session(login("seller")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("ККМ"));
    }

    @Test
    @DisplayName("Повтор названия отвечает словами, а не «Операция нарушает целостность данных»")
    void duplicatePaymentSourceNameIsRejectedWithWords() throws Exception {
        MockHttpSession session = login("owner");
        mvc.perform(post("/api/payment-sources").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Карта Сбер","sourceType":"BANK_ACCOUNT"}"""))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/payment-sources").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Карта Сбер","sourceType":null}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Источник «Карта Сбер» уже заведён"));
    }

    @Test
    @DisplayName("Одновременное заведение того же названия: ровно один успех, "
            + "и отказ читается словами")
    void concurrentDuplicatePaymentSourceNames() throws Exception {
        int count = 6;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Callable<Integer>> tasks = java.util.stream.IntStream.range(0, count)
                    .<Callable<Integer>>mapToObj(i -> () -> mvc.perform(
                                    post("/api/payment-sources").with(csrf()).session(login("owner"))
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("""
                                                    {"name":"ККМ","sourceType":"CASH"}"""))
                            .andReturn().getResponse().getStatus())
                    .toList();

            List<Future<Integer>> futures = pool.invokeAll(tasks);
            List<Integer> statuses = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList());

            assertThat(statuses).as("шесть параллельных заведений одного имени")
                    .containsOnly(201, 400);
            assertThat(statuses.stream().filter(s -> s == 201).count())
                    .as("ровно один источник должен завестись")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // Ни одного 409 «Операция нарушает целостность данных» — только 201
        // и 400 со словами. Складе остаётся ровно одна строка «ККМ».
        Integer left = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM payment_source WHERE name = 'ККМ'", Integer.class));
        assertThat(left).isEqualTo(1);
    }

    @Test
    @DisplayName("Неизвестный тип источника отбивается словами, а не CHECK базы")
    void unknownSourceTypeIsRejected() throws Exception {
        mvc.perform(post("/api/payment-sources").with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ЮКасса","sourceType":"BITCOIN"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Архивация и возврат из архива")
    void archiveAndUnarchivePaymentSource() throws Exception {
        MockHttpSession session = login("owner");
        long id = createPaymentSource(session, "р/с Альфа банк", "BANK_ACCOUNT");

        mvc.perform(post("/api/payment-sources/" + id + "/archive").with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        mvc.perform(post("/api/payment-sources/" + id + "/unarchive")
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    @DisplayName("Продавец читает список, но завести источник не может")
    void sellerCanReadNotWritePaymentSources() throws Exception {
        mvc.perform(get("/api/payment-sources").session(login("seller")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/payment-sources").with(csrf()).session(login("seller"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Наличные","sourceType":null}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Оплата с выбранным источником записывает payment_source_id")
    void paymentRecordsChosenSource() throws Exception {
        MockHttpSession ownerSession = login("owner");
        long sourceId = createPaymentSource(ownerSession, "ККМ", "CASH");

        MockHttpSession sellerSession = login("seller");
        Fixture fixture = deal(sellerSession);

        mvc.perform(post("/api/deals/" + fixture.dealId() + "/payments")
                        .with(csrf()).session(sellerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1000,"paymentSourceId":%d}""".formatted(sourceId)))
                .andExpect(status().isCreated());

        Long stored = inTenant(() -> jdbc.queryForObject(
                "SELECT payment_source_id FROM payment WHERE deal_id = ?",
                Long.class, fixture.dealId()));
        assertThat(stored).isEqualTo(sourceId);
    }

    /**
     * Возврат денег из кассы — вторая из трёх поверхностей, где выбирают способ.
     *
     * <p>Проверяется отдельно от оплаты, а не «заодно»: `SalesService.refund`
     * кладёт источник в свой собственный {@link Payment}, и снятое
     * с фронтенда поле перестало бы доезжать сюда, не задев ни одной проверки
     * на оплате сделки. Возврат при этом идёт **из кассы**: зачисление
     * на лицевой счёт платежа не создаёт вовсе, и спрашивать там способ
     * нечего.
     */
    @Test
    @DisplayName("Возврат денег из кассы записывает выбранный источник")
    void cashRefundRecordsChosenSource() throws Exception {
        MockHttpSession ownerSession = login("owner");
        long cash = createPaymentSource(ownerSession, "ККМ", "CASH");
        long bank = createPaymentSource(ownerSession, "р/с Альфа банк", "BANK_ACCOUNT");

        MockHttpSession sellerSession = login("seller");
        Fixture fixture = deal(sellerSession);

        mvc.perform(post("/api/deals/" + fixture.dealId() + "/payments")
                        .with(csrf()).session(sellerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":5000,"paymentSourceId":%d}""".formatted(bank)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/deals/" + fixture.dealId() + "/issue")
                        .with(csrf()).session(sellerSession))
                .andExpect(status().isOk());

        Long itemId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM deal_item WHERE deal_id = ?", Long.class, fixture.dealId()));

        mvc.perform(post("/api/deals/" + fixture.dealId() + "/returns")
                        .with(csrf()).session(sellerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"items":[{"dealItemId":%d,"restocked":true}],\
                                "reason":"Не подошла","refundToAccount":false,\
                                "paymentSourceId":%d}"""
                                .formatted(fixture.warehouseId(), itemId, cash)))
                .andExpect(status().isCreated());

        Long refundSource = inTenant(() -> jdbc.queryForObject(
                "SELECT payment_source_id FROM payment WHERE deal_id = ? AND direction = 'OUT'",
                Long.class, fixture.dealId()));
        assertThat(refundSource).as("возврат из кассы записан способом, которым отдали деньги")
                .isEqualTo(cash);

        // Приход остался при своём источнике: два платежа по одной сделке
        // не обязаны идти одним способом — приняли переводом, вернули наличными.
        Long paymentSource = inTenant(() -> jdbc.queryForObject(
                "SELECT payment_source_id FROM payment WHERE deal_id = ? AND direction = 'IN'",
                Long.class, fixture.dealId()));
        assertThat(paymentSource).isEqualTo(bank);
    }

    /**
     * Лицевой счёт — третья поверхность, и обе её операции создают платёж.
     *
     * <p>Пополнение и выдача проверяются одним тестом, потому что второе
     * возможно только после первого: выдать со счёта больше остатка нельзя.
     */
    @Test
    @DisplayName("Пополнение и выдача лицевого счёта записывают выбранный источник")
    void accountOperationsRecordChosenSource() throws Exception {
        MockHttpSession ownerSession = login("owner");
        long cash = createPaymentSource(ownerSession, "ККМ", "CASH");
        long bank = createPaymentSource(ownerSession, "р/с Альфа банк", "BANK_ACCOUNT");

        Long customerId = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Клиент') RETURNING id", Long.class));

        MockHttpSession sellerSession = login("seller");
        mvc.perform(post("/api/customers/" + customerId + "/account/top-up")
                        .with(csrf()).session(sellerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1000,"paymentSourceId":%d}""".formatted(bank)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/customers/" + customerId + "/account/withdraw")
                        .with(csrf()).session(sellerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":400,"paymentSourceId":%d}""".formatted(cash)))
                .andExpect(status().isCreated());

        Long topUpSource = inTenant(() -> jdbc.queryForObject("""
                SELECT payment_source_id FROM payment
                WHERE customer_id = ? AND direction = 'IN'""", Long.class, customerId));
        assertThat(topUpSource).as("пополнение записано способом, которым принесли деньги")
                .isEqualTo(bank);

        Long withdrawSource = inTenant(() -> jdbc.queryForObject("""
                SELECT payment_source_id FROM payment
                WHERE customer_id = ? AND direction = 'OUT'""", Long.class, customerId));
        assertThat(withdrawSource).as("выдача записана способом, которым отдали деньги")
                .isEqualTo(cash);
    }

    // ---------------------------------------------------------------
    // Источники сделок
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Владелец заводит источник сделки без типа")
    void ownerCreatesDealSource() throws Exception {
        mvc.perform(post("/api/deal-sources").with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Авито"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Авито"))
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    @DisplayName("Повтор названия источника сделки — тоже словами")
    void duplicateDealSourceNameIsRejectedWithWords() throws Exception {
        MockHttpSession session = login("owner");
        mvc.perform(post("/api/deal-sources").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Авито\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/deal-sources").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Авито\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Источник «Авито» уже заведён"));
    }

    @Test
    @DisplayName("Смотрящий читает источники сделок, но не заводит")
    void viewerCanReadNotWriteDealSources() throws Exception {
        mvc.perform(get("/api/deal-sources").session(login("viewer")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/deal-sources").with(csrf()).session(login("viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Сайт\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Архивация источника сделки")
    void archiveDealSource() throws Exception {
        MockHttpSession session = login("owner");
        var created = mvc.perform(post("/api/deal-sources").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Сайт\"}"))
                .andReturn();
        long id = Long.parseLong(created.getResponse().getContentAsString()
                .replaceAll("^\\{\"id\":(\\d+).*$", "$1"));

        mvc.perform(post("/api/deal-sources/" + id + "/archive").with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
    }

    // ---------------------------------------------------------------

    /** Что нужно, чтобы дойти до денег: клиент, склад и оформленная сделка. */
    private record Fixture(long customerId, long warehouseId, long dealId) {
    }

    /** Клиент, склад, деталь на остатке и отложенная сделка на 5 000 ₽. */
    private Fixture deal(MockHttpSession sellerSession) throws Exception {
        Long customerId = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Клиент') RETURNING id", Long.class));
        Long branch = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class));
        Long warehouse = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад') RETURNING id",
                Long.class, branch));
        Long partId = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, cost_price)
                VALUES (1, 'Фара', 5000, 2000) RETURNING id""", Long.class));
        inTenant(() -> {
            ledger.record(StockMovement.intake(
                    partId, java.math.BigDecimal.ONE, warehouse, null));
            return null;
        });

        String dealBody = "{\"customerId\":" + customerId + ",\"items\":[{\"partId\":" + partId
                + ",\"quantity\":1,\"warehouseId\":" + warehouse + "}]}";
        var created = mvc.perform(post("/api/deals").with(csrf()).session(sellerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dealBody))
                .andExpect(status().isCreated())
                .andReturn();
        long dealId = Long.parseLong(created.getResponse().getContentAsString()
                .replaceAll("^\\{\"id\":(\\d+).*$", "$1"));
        return new Fixture(customerId, warehouse, dealId);
    }

    private long createPaymentSource(MockHttpSession session, String name, String type)
            throws Exception {
        var result = mvc.perform(post("/api/payment-sources").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","sourceType":%s}"""
                                .formatted(name, type == null ? "null" : "\"" + type + "\"")))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(result.getResponse().getContentAsString()
                .replaceAll("^\\{\"id\":(\\d+).*$", "$1"));
    }

    /** Заводит сотрудника, если его ещё нет, и возвращает идентификатор. */
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
                                {"company":"sourceco","login":"%s","password":"пароль"}"""
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
