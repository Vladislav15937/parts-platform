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
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Основной номер производителя там, куда 0327 его не донесла (задача 0329,
 * ответ владельца продукта 18.10.2026 «Всё да»): страница сделки по ссылке,
 * печать сделки, лист обхода, коды склада для строки «вне списка», сведение
 * пересчёта, состав перевозки, разрез по машине и поставке, «Проданные
 * позиции» и их файл.
 *
 * <p><b>Как это выглядело для человека.</b> Продавец в выдаче видел «Фара ·
 * контрактная · 84140-12530» (0327), а покупатель в чеке и по ссылке, кладовщик
 * в листе обхода, владелец в отчёте — ту же позицию одним наименованием
 * и состоянием: две «Фары левые Toyota Camry · б/у» в чеке не различить.
 *
 * <p>Правило то же, что в 0320 и 0327: только основной номер, как хранится
 * («84140-12530», чтобы подмена {@code raw_number} на {@code normalized} была
 * видна); основного нет — пусто, кросс-номер не подставляется. Поэтому
 * на каждом ответе три позиции: с основным номером (и кросс-номером рядом),
 * только с кросс-номером и без номеров. Через HTTP: ответ наружу — record.
 *
 * <p>Своя схема ({@code t_000975}, тот же номер в реестре).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class OemInDocumentsTest extends PostgresTestBase {

    private static final String TENANT = "t_000975";

    private static final long TENANT_ID = 975;

    private static final String COMPANY = "nomer0329";

    private static final String RAW_OEM = "84140-12530";

    private static final String CROSS_OF_PRIMARY = "81550-0X100";

    private static final String CROSS_ONLY = "81550-0X200";

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
            customer = soleId("SELECT id FROM customer WHERE name = 'Покупатель 0329'");
            if (customer == null) {
                customer = jdbc.queryForObject(
                        "INSERT INTO customer (name) VALUES ('Покупатель 0329') RETURNING id",
                        Long.class);
            }
        });
    }

    /** Три позиции под общим словом: с основным номером, только с кросс-номером, без номеров. */
    private record Trio(Long withOem, Long crossOnly, Long none, Long warehouse) {
        Long[] all() {
            return new Long[] {withOem, crossOnly, none};
        }
    }

    private Trio trio(String word, Long supply) {
        Long warehouse = warehouse("Склад " + word);
        Long withOem = stockPart(word + " с номером", PartCondition.CONTRACT, warehouse, supply);
        oem(withOem, RAW_OEM, true);
        oem(withOem, CROSS_OF_PRIMARY, false);
        Long crossOnly = stockPart(word + " с кроссом", PartCondition.NEW, warehouse, supply);
        oem(crossOnly, CROSS_ONLY, false);
        Long none = stockPart(word + " без номера", PartCondition.USED, warehouse, supply);
        return new Trio(withOem, crossOnly, none, warehouse);
    }

    /** У позиции с основным номером — он, у двух других — пусто. */
    private static void expectTrio(ResultActions answer, String rows, String key, Trio trio)
            throws Exception {
        answer.andExpect(jsonPath("%s[?(@.%s == %d)].primaryOem".formatted(rows, key, trio.withOem()))
                        .value(contains(RAW_OEM)))
                .andExpect(jsonPath("%s[?(@.%s == %d)].primaryOem"
                                .formatted(rows, key, trio.crossOnly()))
                        .value(contains(nullValue())))
                .andExpect(jsonPath("%s[?(@.%s == %d)].primaryOem".formatted(rows, key, trio.none()))
                        .value(contains(nullValue())));
    }

    /** То же по наименованию — там, где строка не несёт номера позиции. */
    private static void expectTrioByTitle(ResultActions answer, String rows, String word)
            throws Exception {
        answer.andExpect(jsonPath(rows + "[?(@.title == '" + word + " с номером')].primaryOem")
                        .value(contains(RAW_OEM)))
                .andExpect(jsonPath(rows + "[?(@.title == '" + word + " с кроссом')].primaryOem")
                        .value(contains(nullValue())))
                .andExpect(jsonPath(rows + "[?(@.title == '" + word + " без номера')].primaryOem")
                        .value(contains(nullValue())));
    }

    @Test
    @DisplayName("Страница сделки по ссылке несёт основной номер, у услуги его нет")
    void sharedPageCarriesThePrimaryOem() throws Exception {
        Trio trio = trio("Фарссылка", null);
        long dealId = createDeal(trio, true);
        String path = mvc.perform(post("/api/deals/" + dealId + "/share").with(csrf())
                        .session(login("prodavec")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher token = Pattern.compile("/s/[^/]+/([^\"]+)").matcher(path);
        if (!token.find()) {
            throw new AssertionError("В ответе нет ссылки: " + path);
        }

        ResultActions shared = mvc.perform(get("/api/shared/%s/%s".formatted(COMPANY, token.group(1))))
                .andExpect(status().isOk());
        expectTrioByTitle(shared, "$.items", "Фарссылка");
        shared.andExpect(jsonPath("$.items[?(@.title == 'Доставка 0329')].primaryOem")
                .value(contains(nullValue())));
    }

    @Test
    @DisplayName("Печать сделки несёт основной номер в строке, у услуги его нет")
    void printCarriesThePrimaryOem() throws Exception {
        Trio trio = trio("Фарпечать", null);
        long dealId = createDeal(trio, true);

        ResultActions print = mvc.perform(get("/api/deals/{id}/print", dealId)
                        .session(login("prodavec")))
                .andExpect(status().isOk());
        expectTrioByTitle(print, "$.lines", "Фарпечать");
        print.andExpect(jsonPath("$.lines[?(@.title == 'Доставка 0329')].primaryOem")
                .value(contains(nullValue())));
    }

    @Test
    @DisplayName("Пересчёт несёт основной номер в листе обхода, в кодах склада и в расхождениях")
    void inventoryCarriesThePrimaryOem() throws Exception {
        Trio trio = trio("Фарпересчёт", null);
        MockHttpSession owner = login("vladelec");

        long sessionId = idOf(mvc.perform(post("/api/inventory/sessions").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(trio.warehouse())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        expectTrio(mvc.perform(get("/api/inventory/sessions/%d/lines".formatted(sessionId))
                        .session(owner)).andExpect(status().isOk()), "$", "partId", trio);
        // Из кодов склада заводится строка «вне списка» — она обязана нести
        // номер так же, как строки, пришедшие листом обхода.
        expectTrio(mvc.perform(get("/api/inventory/sessions/%d/codes".formatted(sessionId))
                        .session(owner)).andExpect(status().isOk()), "$", "partId", trio);

        for (Long partId : trio.all()) {
            mvc.perform(post("/api/inventory/sessions/%d/counts".formatted(sessionId)).with(csrf())
                            .session(owner)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"partId\":%d,\"qty\":0}".formatted(partId)))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/inventory/sessions/%d/finish".formatted(sessionId)).with(csrf())
                        .session(owner))
                .andExpect(status().isOk());
        expectTrio(mvc.perform(get("/api/inventory/sessions/%d/discrepancies".formatted(sessionId))
                        .session(owner)).andExpect(status().isOk()), "$", "partId", trio);
    }

    @Test
    @DisplayName("Состав перевозки несёт основной номер")
    void moveLinesCarryThePrimaryOem() throws Exception {
        Trio trio = trio("Фарперевозка", null);
        Long to = warehouse("Склад приёма 0329");
        MockHttpSession owner = login("vladelec");

        StringBuilder items = new StringBuilder();
        for (Long partId : trio.all()) {
            items.append(items.isEmpty() ? "" : ",")
                    .append("{\"partId\":%d,\"quantity\":1}".formatted(partId));
        }
        mvc.perform(post("/api/stock/moves").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromWarehouseId":%d,"toWarehouseId":%d,"items":[%s]}"""
                                .formatted(trio.warehouse(), to, items)))
                .andExpect(status().isCreated());
        Long documentId = inTenant(() -> jdbc.queryForObject(
                "SELECT max(document_id) FROM stock_document_line WHERE part_id = ?",
                Long.class, trio.withOem()));

        expectTrio(mvc.perform(get("/api/stock/moves/%d/lines".formatted(documentId))
                        .session(owner)).andExpect(status().isOk()), "$", "partId", trio);
    }

    @Test
    @DisplayName("Разрез по поставке несёт основной номер в строке")
    void originReportCarriesThePrimaryOem() throws Exception {
        Long supply = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO supply (kind, number, status) VALUES ('CONTAINER', ?, 'ARRIVED')
                RETURNING id""", Long.class, "Р-329-" + UUID.randomUUID()));
        Trio trio = trio("Фарразрез", supply);

        expectTrio(mvc.perform(get("/api/reports/supplies/items")
                        .param("supplyId", String.valueOf(supply))
                        .param("tab", "received")
                        .session(login("vladelec")))
                .andExpect(status().isOk()), "$.rows", "partId", trio);
    }

    @Test
    @DisplayName("Проданные позиции несут основной номер на экране и своей колонкой в файле")
    void soldItemsCarryThePrimaryOem() throws Exception {
        Trio trio = trio("Фарпродано", null);
        long dealId = createDeal(trio, false);
        mvc.perform(post("/api/deals/%d/issue".formatted(dealId)).with(csrf())
                        .session(login("prodavec")))
                .andExpect(status().isOk());
        MockHttpSession owner = login("vladelec");

        expectTrio(mvc.perform(get("/api/reports/sold-items").session(owner))
                .andExpect(status().isOk()), "$.rows", "partId", trio);

        String file = new String(mvc.perform(get("/api/reports/sold-items/export").session(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String header = file.lines().findFirst().orElseThrow();
        assertThat(header).as("в файле нет колонки номера производителя")
                .contains("\"Номер производителя\"");
        int column = java.util.Arrays.asList(header.replace("﻿", "").split(";"))
                .indexOf("\"Номер производителя\"");
        assertThat(cellOf(file, "Фарпродано с номером", column)).isEqualTo(RAW_OEM);
        assertThat(cellOf(file, "Фарпродано с кроссом", column))
                .as("кросс-номер подставлен вместо основного").isEmpty();
        assertThat(cellOf(file, "Фарпродано без номера", column)).isEmpty();
    }

    // ---------- фикстура ----------

    /** Клетка файла в строке с этим наименованием. В ячейках нет «;» — фикстура своя. */
    private static String cellOf(String file, String title, int column) {
        String row = file.lines().filter(line -> line.contains("\"" + title + "\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("В файле нет строки «" + title + "»"));
        String cell = row.split(";")[column];
        return cell.substring(1, cell.length() - 1);
    }

    private long createDeal(Trio trio, boolean withService) throws Exception {
        StringBuilder items = new StringBuilder();
        for (Long partId : trio.all()) {
            items.append(items.isEmpty() ? "" : ",").append(
                    "{\"partId\":%d,\"quantity\":1,\"warehouseId\":%d}"
                            .formatted(partId, trio.warehouse()));
        }
        String services = "";
        if (withService) {
            Long delivery = inTenant(() -> {
                Long found = soleId("SELECT id FROM service WHERE name = 'Доставка 0329'");
                return found != null ? found : jdbc.queryForObject(
                        "INSERT INTO service (name, price) VALUES ('Доставка 0329', 500)"
                                + " RETURNING id", Long.class);
            });
            services = ",\"services\":[{\"serviceId\":%d,\"quantity\":1,\"price\":500}]"
                    .formatted(delivery);
        }
        return idOf(mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":%d,\"items\":[%s]%s}"
                                .formatted(customer, items, services)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private static long idOf(String body) {
        Matcher found = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (!found.find()) {
            throw new AssertionError("В ответе нет идентификатора: " + body);
        }
        return Long.parseLong(found.group(1));
    }

    private Long stockPart(String title, PartCondition condition, Long warehouse, Long supply) {
        return inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, condition, supply_id)
                    VALUES (1, ?, 5000, 2000, ?, ?) RETURNING id""", Long.class,
                    title, condition.name(), supply);
            ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouse, null));
            return id;
        });
    }

    private void oem(Long partId, String raw, boolean primary) {
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
                    VALUES (?, ?, ?, ?)""", partId, raw,
                    ru.partsflow.catalog.OemNumbers.normalize(raw), primary);
        });
    }

    private Long warehouse(String name) {
        return inTenant(() -> {
            Long found = soleId("SELECT id FROM warehouse WHERE name = '" + name + "'");
            return found != null ? found : jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, ?) RETURNING id",
                    Long.class, branch, name);
        });
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
