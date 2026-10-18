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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Строки списков несут основной номер производителя рядом со словом состояния
 * (задача 0327, решение владельца продукта 18.10.2026 «7 - а»): выдача продавца
 * и корзина ({@code StockRow}), состав сделки и выбор на возврат, заказ
 * с площадки ({@code ItemView}).
 *
 * <p><b>Как это выглядело для человека.</b> Отказ (0320) называл «Фара · б/у ·
 * 84140-12530», а список, из которого продавец позицию выбирал, номера
 * не показывал: двух «Фара левая Toyota Camry · контрактная» в выдаче было
 * не различить.
 *
 * <p>На каждом ответе — три позиции: с основным номером (в виде хранения,
 * «84140-12530», чтобы подмена {@code raw_number} на {@code normalized} была
 * видна), только с кросс-номером (поле пусто — кросс-номер не подставляется)
 * и без номеров вовсе. Своя схема ({@code t_000973}, тот же номер в реестре).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class OemBesideConditionTest extends PostgresTestBase {

    private static final String TENANT = "t_000973";

    private static final long TENANT_ID = 973;

    private static final String COMPANY = "nomer0327";

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
            member("prodavec", "Продавец", "SELLER");
            branch = soleId("SELECT id FROM branch ORDER BY id LIMIT 1");
            if (branch == null) {
                branch = jdbc.queryForObject(
                        "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            }
            customer = soleId("SELECT id FROM customer WHERE name = 'Покупатель 0327'");
            if (customer == null) {
                customer = jdbc.queryForObject(
                        "INSERT INTO customer (name) VALUES ('Покупатель 0327') RETURNING id",
                        Long.class);
            }
        });
    }

    /** Три позиции под общим словом: с основным номером, только с кросс-номером, без номеров. */
    private record Trio(Long withOem, Long crossOnly, Long none, Long warehouse) {
    }

    private Trio trio(String word) {
        Long warehouse = warehouse("Склад " + word);
        Long withOem = stockPart(word + " с номером", PartCondition.CONTRACT, warehouse);
        oem(withOem, RAW_OEM, true);
        oem(withOem, "81550-0X100", false);
        Long crossOnly = stockPart(word + " с кроссом", PartCondition.NEW, warehouse);
        oem(crossOnly, "81550-0X200", false);
        Long none = stockPart(word + " без номера", PartCondition.USED, warehouse);
        return new Trio(withOem, crossOnly, none, warehouse);
    }

    @Test
    @DisplayName("Выдача продавца несёт основной номер и не подставляет кросс-номер")
    void stockSearchCarriesThePrimaryOem() throws Exception {
        trio("Фарвыдача");
        // Ожидаемая позиция идёт второй ветвью UNION ALL — номер обязан
        // приехать и у неё, и на своём месте.
        Long expected = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, condition, quantity, status,
                                  supply_id, expected_origin)
                VALUES (1, 'Фарвыдача ожидаемая', 5000, 'NEW', 2, 'DRAFT', ?, true)
                RETURNING id""", Long.class, supply()));
        oem(expected, "90919-02240", true);

        ResultActions answer = mvc.perform(get("/api/parts/stock").param("q", "Фарвыдача")
                        .session(login("prodavec")))
                .andExpect(status().isOk());
        answer.andExpect(jsonPath("$.rows[?(@.title == 'Фарвыдача с номером')].primaryOem")
                        .value(contains(RAW_OEM)))
                .andExpect(jsonPath("$.rows[?(@.title == 'Фарвыдача с кроссом')].primaryOem")
                        .value(contains(nullValue())))
                .andExpect(jsonPath("$.rows[?(@.title == 'Фарвыдача без номера')].primaryOem")
                        .value(contains(nullValue())))
                .andExpect(jsonPath("$.rows[?(@.title == 'Фарвыдача ожидаемая')].primaryOem")
                        .value(contains("90919-02240")))
                // Столбцы ветвей не сдвинулись: состояние на своём месте.
                .andExpect(jsonPath("$.rows[?(@.title == 'Фарвыдача ожидаемая')].condition")
                        .value(contains("NEW")))
                .andExpect(jsonPath("$.rows[?(@.title == 'Фарвыдача с номером')].condition")
                        .value(contains("CONTRACT")));
    }

    @Test
    @DisplayName("Состав сделки и выбор на возврат несут основной номер")
    void dealItemsCarryThePrimaryOem() throws Exception {
        Trio trio = trio("Фарсделка");
        MockHttpSession seller = login("prodavec");
        String body = mvc.perform(post("/api/deals").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"items":[
                                 {"partId":%d,"quantity":1,"warehouseId":%d},
                                 {"partId":%d,"quantity":1,"warehouseId":%d},
                                 {"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customer, trio.withOem(), trio.warehouse(),
                                        trio.crossOnly(), trio.warehouse(),
                                        trio.none(), trio.warehouse())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long dealId = idOf(body);

        expectTrio(mvc.perform(get("/api/deals/{id}", dealId).session(seller))
                .andExpect(status().isOk()), "$.items", "Фарсделка");

        // Выбор позиций на возврат — тот же состав, у выданной сделки.
        mvc.perform(post("/api/deals/%d/issue".formatted(dealId)).with(csrf()).session(seller))
                .andExpect(status().isOk());
        expectTrio(mvc.perform(get("/api/deals/{id}", dealId).session(seller))
                .andExpect(status().isOk()), "$.items", "Фарсделка");
    }

    @Test
    @DisplayName("Заказ с площадки несёт основной номер")
    void marketplaceOrderCarriesThePrimaryOem() throws Exception {
        Trio trio = trio("Фарзаказ");
        MockHttpSession seller = login("prodavec");
        expectTrio(mvc.perform(post("/api/deals/orders").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","orderNo":"%s","items":[
                                 {"partId":%d,"quantity":1,"warehouseId":%d},
                                 {"partId":%d,"quantity":1,"warehouseId":%d},
                                 {"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(UUID.randomUUID(), trio.withOem(), trio.warehouse(),
                                        trio.crossOnly(), trio.warehouse(),
                                        trio.none(), trio.warehouse())))
                .andExpect(status().isCreated()), "$.deal.items", "Фарзаказ");

        // Список «ждут ответа» — то, что рисует экран заказов.
        expectTrio(mvc.perform(get("/api/deals/orders/awaiting-reply").session(seller))
                .andExpect(status().isOk()), "$[*].items[*]", "Фарзаказ");
    }

    private static void expectTrio(ResultActions answer, String items, String word)
            throws Exception {
        answer.andExpect(jsonPath(items + "[?(@.title == '" + word + " с номером')].primaryOem")
                        .value(contains(RAW_OEM)))
                .andExpect(jsonPath(items + "[?(@.title == '" + word + " с кроссом')].primaryOem")
                        .value(contains(nullValue())))
                .andExpect(jsonPath(items + "[?(@.title == '" + word + " без номера')].primaryOem")
                        .value(contains(nullValue())));
    }

    // ---------- перебор ----------

    /**
     * Где на экране стоит слово состояния за наименованием, там стоит и номер
     * производителя — либо место названо здесь с причиной.
     *
     * <p><b>Зачем.</b> Слово состояния стоит в двенадцати местах (0318), номер
     * владелец попросил в пяти списках продажи (0327). Новое место продажи,
     * поставившее {@code ConditionWord} без {@code OemWord}, называло бы позицию
     * не так, как соседний список и отказ, — ровно то расхождение, ради которого
     * задача заведена. Номер ищется в той же строке разметки, что слово, или
     * в двух следующих: так они стоят во всех пяти местах.
     */
    private static final Map<String, String> CONDITION_WITHOUT_OEM = Map.ofEntries(
            Map.entry("frontend/src/screens/SharedDealScreen.tsx",
                    "страница сделки уходит покупателю — номер там не добавлять без слова"
                            + " владельца (задача 0327)"),
            Map.entry("frontend/src/screens/InventoryScreen.tsx",
                    "лист обхода пересчёта — владелец назвал списки продажи, не пересчёт (0327)"),
            Map.entry("frontend/src/screens/ExpectedGoods.tsx",
                    "ожидаемый товар у владельца — не список продажи (0327)"),
            Map.entry("frontend/src/screens/IntakeScreen.tsx",
                    "приёмка — номер там вписывают, а не читают; не список продажи (0327)"),
            Map.entry("frontend/src/screens/AuditJournalScreen.tsx",
                    "журнал действий — номер там не просили (0321)"),
            Map.entry("frontend/src/screens/InventoryReconcile.tsx",
                    "сведение пересчёта — таблица со столбцом, не список продажи (0327)"),
            Map.entry("frontend/src/screens/StockMovesScreen.tsx",
                    "состав перевозки — таблица со столбцом, не список продажи (0327)"),
            Map.entry("frontend/src/screens/ReportsScreen.tsx",
                    "разрез по машине — таблица отчёта, не список продажи (0327)"),
            Map.entry("frontend/src/inventory/inventory.ts",
                    "подпись очереди отправки на приёмке — не список продажи (0327)"),
            Map.entry("frontend/src/inventory/catalog.ts",
                    "сам словарь и таблица склада: у неё свой столбец «Номер производителя»"),
            Map.entry("frontend/src/labels/labels.ts",
                    "этикетка 58×40: состояние строкой с ценой, лишней строки нет (0314);"
                            + " владелец назвал списки продажи, не этикетку (0327)"),
            Map.entry("frontend/src/inventory/ConditionWord.tsx", "само слово"));

    @Test
    @DisplayName("Где стоит слово состояния, там стоит и номер производителя")
    void oemStandsWhereConditionWordStands() throws IOException {
        Pattern word = Pattern.compile("<ConditionWord\\b|\\bconditionTitle\\(|\\btitleWithCondition\\(");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("frontend/src"))) {
            for (Path file : files.filter(p -> p.toString().matches(".*\\.tsx?$"))
                    .filter(p -> !p.getFileName().toString().contains(".test."))
                    .toList()) {
                String name = file.toString().replace('\\', '/');
                if (CONDITION_WITHOUT_OEM.containsKey(name)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = word.matcher(lines.get(i));
                    if (!m.find() || lines.get(i).strip().startsWith("*")
                            || lines.get(i).strip().startsWith("//")
                            || lines.get(i).contains("import ")) {
                        continue;
                    }
                    String near = String.join("\n",
                            lines.subList(i, Math.min(lines.size(), i + 3)));
                    if (!near.contains("<OemWord")) {
                        found.add(name + ":" + (i + 1) + ": " + lines.get(i).strip());
                    }
                }
            }
        }
        assertThat(found)
                .as("слово состояния без номера производителя — поставьте `OemWord` рядом"
                        + " или назовите место в CONDITION_WITHOUT_OEM с причиной")
                .isEmpty();
    }

    // ---------- фикстура ----------

    private static long idOf(String body) {
        Matcher found = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (!found.find()) {
            throw new AssertionError("В ответе нет идентификатора: " + body);
        }
        return Long.parseLong(found.group(1));
    }

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

    private Long supply() {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO supply (kind, number, status) VALUES ('CONTAINER', ?, 'EXPECTED')
                RETURNING id""", Long.class, "Н-327-" + UUID.randomUUID()));
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
