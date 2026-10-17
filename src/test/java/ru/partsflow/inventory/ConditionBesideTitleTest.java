package ru.partsflow.inventory;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Состояние позиции едет полем во всех ответах, где человек видит её
 * наименование (задача 0318).
 *
 * <p><b>Как это выглядело для человека.</b> Состав сделки у продавца, страница
 * сделки по ссылке у покупателя, заказы с площадок, лист обхода и сведение
 * пересчёта, состав перевозки, разрез по машине и поставке, ожидаемое
 * по поставке на приёмке и у владельца — всё это показывало наименование,
 * а состояние в нём несла только пометка «(б/у)». Пометка из наименования
 * уходит (0315 перестаёт её дописывать, 0316 снимет с хранимого), и у каждой
 * новой позиции «новая» и «б/у» стали бы неразличимы — а это разный товар
 * и разная цена.
 *
 * <p>Наименования здесь заведены <b>без</b> пометки — состояние обязано
 * приехать полем. Каждая проверка идёт по всем четырём значениям
 * перечисления, а не по одному: подмена поля константой прошла бы проверку
 * одного значения. Через HTTP, а не вызовом сервиса: ответ наружу — record,
 * и сериализацию видит только настоящий запрос.
 *
 * <p>Пустого состояния у позиции не бывает: колонка {@code part.condition}
 * — {@code NOT NULL DEFAULT 'USED'} (tenant/004). Пусто бывает у услуги
 * в составе сделки, и это проверено там, где услуга в ответе есть.
 *
 * <p>Своя схема ({@code t_000958}, тот же номер в реестре): проверки ищут свои
 * строки по наименованию и по складу, и чужая позиция с тем же складом или
 * поставкой меняла бы лист обхода и разрез.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class ConditionBesideTitleTest extends PostgresTestBase {

    private static final String TENANT = "t_000958";

    private static final long TENANT_ID = 958;

    private static final String COMPANY = "sostoyanie";

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

    private Long branch;
    private Long customer;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка', ?)""", TENANT_ID, TENANT, COMPANY);

        inTenant(() -> {
            member("vladelec", "Владелец", "OWNER");
            member("prodavec", "Продавец", "SELLER");
            branch = soleId("SELECT id FROM branch ORDER BY id LIMIT 1");
            if (branch == null) {
                branch = jdbc.queryForObject(
                        "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            }
            customer = soleId("SELECT id FROM customer WHERE name = 'Покупатель состояния'");
            if (customer == null) {
                customer = jdbc.queryForObject(
                        "INSERT INTO customer (name) VALUES ('Покупатель состояния') RETURNING id",
                        Long.class);
            }
        });
    }

    /**
     * Состав сделки у продавца и выбор позиций на возврат
     * ({@code SalesController.ItemView}).
     */
    @Test
    @DisplayName("Состав сделки несёт состояние каждой позиции")
    void dealItemsCarryTheCondition() throws Exception {
        Long warehouse = warehouse("Склад сделки");
        Map<PartCondition, Long> parts = fourParts("Фара для сделки", warehouse, null);

        long dealId = createDeal(parts, warehouse, false);

        ResultActions deal = mvc.perform(get("/api/deals/" + dealId).session(login("prodavec")))
                .andExpect(status().isOk());
        for (var part : parts.entrySet()) {
            deal.andExpect(jsonPath("$.items[?(@.partId == %d)].condition".formatted(part.getValue()))
                    .value(hasItem(part.getKey().name())));
        }
    }

    /**
     * Страница сделки по ссылке: единственный документ, который читает
     * не наш сотрудник, и «б/у» против «новая» — то, за что он платит.
     * У услуги состояния нет — «Доставка · б/у» не значит ничего.
     */
    @Test
    @DisplayName("Страница по ссылке несёт состояние позиции, у услуги его нет")
    void sharedPageCarriesTheCondition() throws Exception {
        Long warehouse = warehouse("Склад ссылки");
        Map<PartCondition, Long> parts = fourParts("Фара для ссылки", warehouse, null);

        long dealId = createDeal(parts, warehouse, true);
        String path = mvc.perform(post("/api/deals/" + dealId + "/share").with(csrf())
                        .session(login("prodavec")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var token = Pattern.compile("/s/[^/]+/([^\"]+)").matcher(path);
        if (!token.find()) {
            throw new AssertionError("В ответе нет ссылки: " + path);
        }

        ResultActions shared = mvc.perform(get("/api/shared/%s/%s".formatted(COMPANY, token.group(1))))
                .andExpect(status().isOk());
        for (PartCondition condition : PartCondition.values()) {
            shared.andExpect(jsonPath("$.items[?(@.title == '%s')].condition"
                            .formatted(titleOf("Фара для ссылки", condition)))
                    .value(hasItem(condition.name())));
        }
        shared.andExpect(jsonPath("$.items[4].title").value("Доставка для ссылки"))
                .andExpect(jsonPath("$.items[4].condition").doesNotExist());
    }

    /**
     * Заказ с площадки: строки заказа на экране «Заказы» и отказ «чего
     * не хватает». В отказе сервер собирает текст сам, и слово состояния
     * там — {@code PartCondition.title()}.
     */
    @Test
    @DisplayName("Заказ с площадки несёт состояние в строках и в нехватке")
    void marketplaceOrderCarriesTheCondition() throws Exception {
        Long warehouse = warehouse("Склад заказа");
        Map<PartCondition, Long> parts = fourParts("Фара для заказа", warehouse, null);

        StringBuilder items = new StringBuilder();
        for (Long partId : parts.values()) {
            items.append(items.isEmpty() ? "" : ",").append(
                    "{\"partId\":%d,\"quantity\":2,\"warehouseId\":%d}".formatted(partId, warehouse));
        }
        mvc.perform(post("/api/deals/orders").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","orderNo":"%s","items":[%s]}"""
                                .formatted(UUID.randomUUID(), items)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.missing").value(hasItem(
                        titleOf("Фара для заказа", PartCondition.NEW)
                                + " · новая — нужно 2, свободно 1")))
                .andExpect(jsonPath("$.missing").value(hasItem(
                        titleOf("Фара для заказа", PartCondition.CONTRACT)
                                + " · контрактная — нужно 2, свободно 1")));

        ResultActions awaiting = mvc.perform(get("/api/deals/orders/awaiting-reply")
                        .session(login("prodavec")))
                .andExpect(status().isOk());
        for (var part : parts.entrySet()) {
            awaiting.andExpect(jsonPath("$[*].items[?(@.partId == %d)].condition"
                            .formatted(part.getValue()))
                    .value(hasItem(part.getKey().name())));
        }
    }

    /**
     * Лист обхода, коды склада для сканера (из них заводится строка «вне
     * списка») и сведение расхождений.
     */
    @Test
    @DisplayName("Пересчёт несёт состояние в листе обхода, в кодах склада и в расхождениях")
    void inventoryCarriesTheCondition() throws Exception {
        Long warehouse = warehouse("Склад пересчёта");
        Map<PartCondition, Long> parts = fourParts("Фара для пересчёта", warehouse, null);
        MockHttpSession owner = login("vladelec");

        String opened = mvc.perform(post("/api/inventory/sessions").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(warehouse)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long sessionId = idOf(opened);

        ResultActions lines = mvc.perform(get("/api/inventory/sessions/%d/lines".formatted(sessionId))
                        .session(owner))
                .andExpect(status().isOk());
        ResultActions codes = mvc.perform(get("/api/inventory/sessions/%d/codes".formatted(sessionId))
                        .session(owner))
                .andExpect(status().isOk());
        for (var part : parts.entrySet()) {
            String condition = "$[?(@.partId == %d)].condition".formatted(part.getValue());
            lines.andExpect(jsonPath(condition).value(hasItem(part.getKey().name())));
            codes.andExpect(jsonPath(condition).value(hasItem(part.getKey().name())));
        }

        // Каждую позицию «не нашли» — недостача по всем четырём.
        for (Long partId : parts.values()) {
            mvc.perform(post("/api/inventory/sessions/%d/counts".formatted(sessionId)).with(csrf())
                            .session(owner)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"partId\":%d,\"qty\":0}".formatted(partId)))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/inventory/sessions/%d/finish".formatted(sessionId)).with(csrf())
                        .session(owner))
                .andExpect(status().isOk());

        ResultActions discrepancies = mvc.perform(
                        get("/api/inventory/sessions/%d/discrepancies".formatted(sessionId))
                                .session(owner))
                .andExpect(status().isOk());
        for (var part : parts.entrySet()) {
            discrepancies.andExpect(jsonPath("$[?(@.partId == %d)].condition"
                            .formatted(part.getValue()))
                    .value(hasItem(part.getKey().name())));
        }
    }

    /** Состав перевозки — документ перемещения между складами. */
    @Test
    @DisplayName("Состав перевозки несёт состояние каждой увезённой позиции")
    void moveLinesCarryTheCondition() throws Exception {
        Long from = warehouse("Склад отправки");
        Long to = warehouse("Склад приёма");
        Map<PartCondition, Long> parts = fourParts("Фара для перевозки", from, null);
        MockHttpSession owner = login("vladelec");

        StringBuilder items = new StringBuilder();
        for (Long partId : parts.values()) {
            items.append(items.isEmpty() ? "" : ",")
                    .append("{\"partId\":%d,\"quantity\":1}".formatted(partId));
        }
        mvc.perform(post("/api/stock/moves").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromWarehouseId":%d,"toWarehouseId":%d,"items":[%s]}"""
                                .formatted(from, to, items)))
                .andExpect(status().isCreated());
        Long documentId = inTenant(() -> jdbc.queryForObject("""
                SELECT max(l.document_id) FROM stock_document_line l WHERE l.part_id = ?""",
                Long.class, parts.get(PartCondition.NEW)));

        ResultActions lines = mvc.perform(get("/api/stock/moves/%d/lines".formatted(documentId))
                        .session(owner))
                .andExpect(status().isOk());
        for (var part : parts.entrySet()) {
            lines.andExpect(jsonPath("$[?(@.partId == %d)].condition".formatted(part.getValue()))
                    .value(hasItem(part.getKey().name())));
        }
    }

    /** Разрез по поставке («Поступило»): строки отчёта владельца. */
    @Test
    @DisplayName("Разрез по поставке несёт состояние строки")
    void originReportCarriesTheCondition() throws Exception {
        Long warehouse = warehouse("Склад разреза");
        Long supply = supply("Р-318", "ARRIVED");
        Map<PartCondition, Long> parts = fourParts("Фара для разреза", warehouse, supply);

        ResultActions items = mvc.perform(get("/api/reports/supplies/items")
                        .param("supplyId", String.valueOf(supply))
                        .param("tab", "received")
                        .session(login("vladelec")))
                .andExpect(status().isOk());
        for (var part : parts.entrySet()) {
            items.andExpect(jsonPath("$.rows[?(@.partId == %d)].condition"
                            .formatted(part.getValue()))
                    .value(hasItem(part.getKey().name())));
        }
    }

    /**
     * Ожидаемое по поставке: у владельца (карточка поставки) и у приёмщика
     * (справочник приёмки, «Ожидалось по этой поставке»).
     */
    @Test
    @DisplayName("Ожидаемые позиции несут состояние у владельца и в справочнике приёмки")
    void expectedPartsCarryTheCondition() throws Exception {
        Long supply = supply("О-318", "EXPECTED");
        Map<PartCondition, Long> parts = new LinkedHashMap<>();
        for (PartCondition condition : PartCondition.values()) {
            parts.put(condition, inTenant(() -> jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, condition, quantity,
                                      supply_id, expected_origin)
                    VALUES (1, ?, 5000, ?, 2, ?, true) RETURNING id""", Long.class,
                    titleOf("Фара ожидаемая", condition), condition.name(), supply)));
        }
        MockHttpSession owner = login("vladelec");

        ResultActions ofSupply = mvc.perform(
                        get("/api/intake/supplies/%d/expected-parts".formatted(supply)).session(owner))
                .andExpect(status().isOk());
        ResultActions reference = mvc.perform(get("/api/intake/reference").session(owner))
                .andExpect(status().isOk());
        for (var part : parts.entrySet()) {
            ofSupply.andExpect(jsonPath("$[?(@.id == %d)].condition".formatted(part.getValue()))
                    .value(hasItem(part.getKey().name())));
            reference.andExpect(jsonPath("$.expectedParts[?(@.id == %d)].condition"
                            .formatted(part.getValue()))
                    .value(hasItem(part.getKey().name())));
        }
    }

    /**
     * Отказ приёмки называет ожидаемую позицию со словом состояния: приёмщик
     * читает его в «требует внимания» и должен понять, о какой из двух фар речь.
     */
    @Test
    @DisplayName("Отказ приёмки ожидаемой позиции называет её состояние")
    void intakeRefusalNamesTheCondition() throws Exception {
        Long warehouse = warehouse("Склад приёмки");
        Long promised = supply("П-318", "EXPECTED");
        Long other = supply("Д-318", "ARRIVED");
        Long partId = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, condition, quantity,
                                  supply_id, expected_origin)
                VALUES (1, 'Фара чужой поставки', 5000, 'CONTRACT', 1, ?, true) RETURNING id""",
                Long.class, promised));

        mvc.perform(post("/api/intake/receipts").with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","warehouseId":%d,"supplyId":%d,
                                 "items":[{"partId":%d,"rawName":"фара","quantity":1,"price":5000}]}"""
                                .formatted(UUID.randomUUID(), warehouse, other, partId)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.message").value(containsString(
                        "«Фара чужой поставки · контрактная» ожидается по другой поставке")));
    }

    // ---------- фикстура ----------

    /** Четыре позиции, по одной на каждое состояние, без пометки в наименовании. */
    private Map<PartCondition, Long> fourParts(String title, Long warehouse, Long supply) {
        Map<PartCondition, Long> parts = new LinkedHashMap<>();
        for (PartCondition condition : PartCondition.values()) {
            parts.put(condition, inTenant(() -> {
                Long partId = jdbc.queryForObject("""
                        INSERT INTO part (category_id, title, price, cost_price, condition, supply_id)
                        VALUES (1, ?, 5000, 2000, ?, ?) RETURNING id""", Long.class,
                        titleOf(title, condition), condition.name(), supply);
                ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
                return partId;
            }));
        }
        return parts;
    }

    /**
     * Наименование, различающее позиции в выдаче, но без слова состояния:
     * «Фара для ссылки 1». Слово в наименовании сделало бы проверку
     * бессмысленной — оно и есть та пометка, которая уходит.
     */
    private static String titleOf(String title, PartCondition condition) {
        return title + " " + (condition.ordinal() + 1);
    }

    private long createDeal(Map<PartCondition, Long> parts, Long warehouse, boolean withService)
            throws Exception {
        List<String> items = new ArrayList<>();
        for (Long partId : parts.values()) {
            items.add("{\"partId\":%d,\"quantity\":1,\"warehouseId\":%d}".formatted(partId, warehouse));
        }
        String services = "";
        if (withService) {
            Long delivery = inTenant(() -> {
                Long found = soleId("SELECT id FROM service WHERE name = 'Доставка для ссылки'");
                return found != null ? found : jdbc.queryForObject(
                        "INSERT INTO service (name, price) VALUES ('Доставка для ссылки', 500)"
                                + " RETURNING id", Long.class);
            });
            services = ",\"services\":[{\"serviceId\":%d,\"quantity\":1,\"price\":500}]"
                    .formatted(delivery);
        }
        String body = mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":%d,\"items\":[%s]%s}"
                                .formatted(customer, String.join(",", items), services)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private Long warehouse(String name) {
        return inTenant(() -> {
            Long found = soleId("SELECT id FROM warehouse WHERE name = '" + name + "'");
            return found != null ? found : jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, ?) RETURNING id",
                    Long.class, branch, name);
        });
    }

    private Long supply(String number, String status) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO supply (kind, number, status) VALUES ('CONTAINER', ?, ?)
                RETURNING id""", Long.class, number + "-" + UUID.randomUUID(), status));
    }

    /** Идентификатор из ответа — поиском, а не срезом строки (U+0085 в MockMvc). */
    private static long idOf(String body) {
        var found = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (!found.find()) {
            throw new AssertionError("В ответе нет идентификатора: " + body);
        }
        return Long.parseLong(found.group(1));
    }

    private Long soleId(String query) {
        var found = jdbc.queryForList(query, Long.class);
        return found.isEmpty() ? null : found.get(0);
    }

    private void member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            // Фикстура пишет то, что называет, а не принимает найденное молча.
            jdbc.update("UPDATE tenant_member SET display_name = ?, role = ? WHERE id = ?",
                    displayName, role, found.get(0));
            return;
        }
        jdbc.update("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?)""",
                displayName, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"%s","password":"пароль"}"""
                                .formatted(COMPANY, login)))
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

    private void inTenant(Runnable body) {
        inTenant(() -> {
            body.run();
            return null;
        });
    }
}
