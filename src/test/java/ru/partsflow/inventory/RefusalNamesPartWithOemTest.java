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
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Отказ, который собирает сервер, называет позицию наименованием, словом
 * состояния и основным номером производителя (задача 0320, решение владельца
 * продукта 18.10.2026): «Фара · контрактная · 84140-12530».
 *
 * <p><b>Как это выглядело для человека.</b> «Фара левая Toyota Camry · б/у —
 * нужно 2, свободно 1»: таких фар на складе несколько, и продавец снова ищет,
 * какая кончилась. Запчасть опознают по номеру производителя.
 *
 * <p>Номер у позиций здесь заведён <b>в виде хранения</b> («84140-12530»),
 * а не приведённым («8414012530»): подмена {@code raw_number} на
 * {@code normalized} прошла бы проверку номера без разделителей. У второй
 * позиции есть только кросс-номер — он не должен встать на место основного.
 *
 * <p>Через HTTP, место за местом: отказы идут из пяти путей кода (нехватка под
 * заказ с площадки, два отказа возврата, предзаказ — свой запрос под
 * блокировкой, частичный приход, приёмка), и номер снимается у каждого
 * по отдельности. Своя схема ({@code t_000962}, тот же номер в реестре).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class RefusalNamesPartWithOemTest extends PostgresTestBase {

    private static final String TENANT = "t_000962";

    private static final long TENANT_ID = 962;

    private static final String COMPANY = "nomer0320";

    private static final String RAW_OEM = "84140-12530";

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
            customer = soleId("SELECT id FROM customer WHERE name = 'Покупатель номера'");
            if (customer == null) {
                customer = jdbc.queryForObject(
                        "INSERT INTO customer (name) VALUES ('Покупатель номера') RETURNING id",
                        Long.class);
            }
        });
    }

    // ---------- формат ----------

    @Test
    @DisplayName("Пустое звено пропадает вместе со своим разделителем")
    void emptyPartDropsWithItsSeparator() {
        assertThat(PartNaming.format("Фара", PartCondition.USED, "8414012530"))
                .isEqualTo("Фара · б/у · 8414012530");
        assertThat(PartNaming.format("Фара", PartCondition.USED, null))
                .isEqualTo("Фара · б/у");
        assertThat(PartNaming.format("Фара", PartCondition.USED, "  "))
                .isEqualTo("Фара · б/у");
        assertThat(PartNaming.format("Фара", null, "8414012530"))
                .isEqualTo("Фара · 8414012530");
        assertThat(PartNaming.format("Фара", null, null)).isEqualTo("Фара");
    }

    // ---------- места ----------

    /**
     * Нехватка под заказ с площадки — отказ с несколькими позициями сразу:
     * у одной основной номер, у другой только кросс-номер.
     */
    @Test
    @DisplayName("Нехватка под заказ называет основной номер и не подставляет кросс-номер")
    void marketplaceShortageNamesThePrimaryOem() throws Exception {
        Long warehouse = warehouse("Склад заказа");
        Long withOem = stockPart("Фара заказа", PartCondition.CONTRACT, warehouse);
        oem(withOem, RAW_OEM, true);
        Long crossOnly = stockPart("Фонарь заказа", PartCondition.NEW, warehouse);
        oem(crossOnly, "81550-0X100", false);

        mvc.perform(post("/api/deals/orders").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","orderNo":"%s","items":[
                                 {"partId":%d,"quantity":2,"warehouseId":%d},
                                 {"partId":%d,"quantity":2,"warehouseId":%d}]}"""
                                .formatted(UUID.randomUUID(), withOem, warehouse,
                                        crossOnly, warehouse)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.missing").value(hasItem(
                        "Фара заказа · контрактная · " + RAW_OEM + " — нужно 2, свободно 1")))
                .andExpect(jsonPath("$.missing").value(hasItem(
                        "Фонарь заказа · новая — нужно 2, свободно 1")));
    }

    @Test
    @DisplayName("Отказы возврата называют номер производителя")
    void returnRefusalsNameTheOem() throws Exception {
        Long warehouse = warehouse("Склад возврата");
        Long partId = stockPart("Фара возврата", PartCondition.USED, warehouse);
        oem(partId, RAW_OEM, true);
        long dealId = createDeal(partId, warehouse);
        Long itemId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM deal_item WHERE deal_id = ?", Long.class, dealId));
        MockHttpSession seller = login("prodavec");
        String refund = """
                {"warehouseId":%d,"items":[{"dealItemId":%d,"quantity":%d,"restocked":true}]}""";

        mvc.perform(post("/api/deals/%d/returns".formatted(dealId)).with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refund.formatted(warehouse, itemId, 1)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.message").value(containsString(
                        "Вернуть можно только выданное, а «Фара возврата · б/у · "
                                + RAW_OEM + "»")));

        mvc.perform(post("/api/deals/%d/issue".formatted(dealId)).with(csrf()).session(seller))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deals/%d/returns".formatted(dealId)).with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refund.formatted(warehouse, itemId, 2)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.message").value(containsString(
                        "Возвращают больше, чем выдали: «Фара возврата · б/у · " + RAW_OEM + "»")));
    }

    /** Предзаказ: позиция читается своим запросом под блокировкой — номер тем же запросом. */
    @Test
    @DisplayName("Отказы предзаказа называют номер производителя")
    void preorderRefusalsNameTheOem() throws Exception {
        Long warehouse = warehouse("Склад предзаказа");
        Long partId = expectedPart("Фара в пути", PartCondition.NEW, 1, supply("EXPECTED"));
        oem(partId, RAW_OEM, true);
        String until = Instant.now().plus(Duration.ofDays(30)).toString();

        mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"reservedUntil":"%s",
                                 "items":[{"partId":%d,"quantity":2,"warehouseId":%d}]}"""
                                .formatted(customer, until, partId, warehouse)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.message").value(containsString(
                        "Ожидаемую деталь «Фара в пути · новая · " + RAW_OEM + "» уже отложили")));

        mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"reservedUntil":"%s",
                                 "items":[{"partId":%d,"quantity":1}]}"""
                                .formatted(customer, until, partId)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.message").value(containsString(
                        "Укажите склад, на который придёт деталь «Фара в пути · новая · "
                                + RAW_OEM + "»")));
    }

    /** Частичный приход: пришедшее обещано предзаказу, обычной продаже отказ. */
    @Test
    @DisplayName("Отказ при частичном приходе называет номер производителя")
    void partialArrivalRefusalNamesTheOem() throws Exception {
        Long warehouse = warehouse("Склад прихода");
        Long supply = supply("EXPECTED");
        Long partId = expectedPart("Дверь в пути", PartCondition.REFURBISHED, 3, supply);
        oem(partId, RAW_OEM, true);
        mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"reservedUntil":"%s",
                                 "items":[{"partId":%d,"quantity":2,"warehouseId":%d}]}"""
                                .formatted(customer, Instant.now().plus(Duration.ofDays(30)),
                                        partId, warehouse)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/intake/receipts").with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","warehouseId":%d,"supplyId":%d,
                                 "items":[{"partId":%d,"rawName":"дверь","quantity":1,"price":1}]}"""
                                .formatted(UUID.randomUUID(), warehouse, supply, partId)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customer, partId, warehouse)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.message").value(containsString(
                        "Пришла только часть поставки: «Дверь в пути · восстановленная · "
                                + RAW_OEM + "»")));
    }

    @Test
    @DisplayName("Отказы приёмки ожидаемой позиции называют номер производителя")
    void intakeRefusalsNameTheOem() throws Exception {
        Long warehouse = warehouse("Склад приёмки");
        Long promised = supply("EXPECTED");
        Long other = supply("ARRIVED");
        Long expected = expectedPart("Фара чужой поставки", PartCondition.CONTRACT, 1, promised);
        oem(expected, RAW_OEM, true);
        Long ordinary = stockPart("Фара обычная", PartCondition.USED, warehouse);
        oem(ordinary, RAW_OEM, true);

        mvc.perform(receipt(warehouse, other, expected))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.message").value(containsString(
                        "«Фара чужой поставки · контрактная · " + RAW_OEM
                                + "» ожидается по другой поставке")));
        mvc.perform(receipt(warehouse, other, ordinary))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.message").value(containsString(
                        "«Фара обычная · б/у · " + RAW_OEM + "» не заводили как ожидаемую")))
                .andExpect(jsonPath("$.message").value(not(containsString("8414012530"))));
    }

    // ---------- перебор ----------

    /**
     * {@link PartCondition#named} зовёт только {@link PartNaming}, и позицию
     * в отказе никто не собирает своим запросом «title, condition»: новое место,
     * назвавшее позицию по-своему, осталось бы без номера.
     */
    @Test
    @DisplayName("Позицию в отказе называет одно место")
    void namedOnlyHere() throws IOException {
        Pattern ownQuery = Pattern.compile("SELECT\\s+(p\\.)?title,\\s*(p\\.)?condition\\b");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.endsWith("PartNaming.java") || file.endsWith("PartCondition.java")) {
                    continue;
                }
                String text = Files.readString(file);
                if (text.contains("PartCondition.named(") || text.contains("PartCondition::named")) {
                    found.add(file + ": PartCondition.named — зовите PartNaming");
                }
                // Запрос, который читает позицию сам (под блокировкой), законен,
                // если номер берёт тем же запросом — PartNaming.primaryOemSql.
                var query = ownQuery.matcher(text);
                while (query.find()) {
                    String statement = text.substring(query.start(),
                            Math.min(text.length(), query.end() + 200));
                    if (!statement.contains("PartNaming.primaryOemSql(")) {
                        found.add(file + ": свой запрос «title, condition» без номера"
                                + " производителя — зовите PartNaming");
                    }
                }
                if (Pattern.compile("\" · \"\\s*\\+\\s*[\\w.()]*[cC]ondition[\\w.()]*\\.title\\(\\)")
                        .matcher(text).find()) {
                    found.add(file + ": слово состояния приклеено « · » вручную — зовите PartNaming");
                }
            }
        }
        assertThat(found).as("позицию в отказе называют мимо PartNaming").isEmpty();
    }

    // ---------- фикстура ----------

    private Long stockPart(String title, PartCondition condition, Long warehouse) {
        return inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, condition)
                    VALUES (1, ?, 5000, 2000, ?) RETURNING id""", Long.class,
                    title, condition.name());
            ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouse, null));
            return id;
        });
    }

    private Long expectedPart(String title, PartCondition condition, int quantity, Long supply) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, condition, quantity, status,
                                  supply_id, expected_origin)
                VALUES (1, ?, 5000, ?, ?, 'DRAFT', ?, true) RETURNING id""", Long.class,
                title, condition.name(), quantity, supply));
    }

    private void oem(Long partId, String raw, boolean primary) {
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
                    VALUES (?, ?, ?, ?)""", partId, raw,
                    ru.partsflow.catalog.OemNumbers.normalize(raw), primary);
        });
    }

    private long createDeal(Long partId, Long warehouse) throws Exception {
        String body = mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":%d,\"items\":[{\"partId\":%d,\"quantity\":1,\"warehouseId\":%d}]}"
                                .formatted(customer, partId, warehouse)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var found = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (!found.find()) {
            throw new AssertionError("В ответе нет идентификатора: " + body);
        }
        return Long.parseLong(found.group(1));
    }

    private org.springframework.test.web.servlet.RequestBuilder receipt(
            Long warehouse, Long supply, Long partId) throws Exception {
        return post("/api/intake/receipts").with(csrf()).session(login("vladelec"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","warehouseId":%d,"supplyId":%d,
                         "items":[{"partId":%d,"rawName":"фара","quantity":1,"price":5000}]}"""
                        .formatted(UUID.randomUUID(), warehouse, supply, partId));
    }

    private Long warehouse(String name) {
        return inTenant(() -> {
            Long found = soleId("SELECT id FROM warehouse WHERE name = '" + name + "'");
            return found != null ? found : jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, ?) RETURNING id",
                    Long.class, branch, name);
        });
    }

    private Long supply(String status) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO supply (kind, number, status) VALUES ('CONTAINER', ?, ?)
                RETURNING id""", Long.class, "Н-320-" + UUID.randomUUID(), status));
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
