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
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Отказы склада и журнал действий называют позицию со словом состояния
 * (задача 0321, решение владельца продукта 18.10.2026, «6-б»).
 *
 * <p><b>Как это выглядело для человека.</b> После 0315 пометки «(б/у)»
 * в наименовании нет, и «Фара Toyota Camry 2007 (55747F0F91CD)» в отказе склада
 * и «Фара Toyota Camry 2007» в журнале не говорили, новая это или б/у —
 * а журнал читают ровно тогда, когда надо знать, какая из двух деталей.
 *
 * <p><b>Состояние в журнале — на момент записи, а не нынешнее, и это
 * доказывается сменой состояния после действий.</b> Позиция заведена «б/у»,
 * с ней сделали две вещи (правка цены, сделка), потом состояние сменили на
 * «контрактная». Нынешнее у всех трёх записей одно; на момент записи — разное:
 * подмена восстановления нынешним состоянием валит именно эти проверки.
 *
 * <p>Своя схема ({@code t_000963}, тот же номер в реестре): журнал ищется
 * по наименованию, а чужие правки той же схемы сдвинули бы страницу.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class ConditionInJournalAndStockTest extends PostgresTestBase {

    private static final String TENANT = "t_000963";

    private static final long TENANT_ID = 963;

    private static final String COMPANY = "sostoyanie0321";

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

    @Autowired
    private StockNaming naming;

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
            customer = soleId("SELECT id FROM customer WHERE name = 'Покупатель журнала'");
            if (customer == null) {
                customer = jdbc.queryForObject(
                        "INSERT INTO customer (name) VALUES ('Покупатель журнала') RETURNING id",
                        Long.class);
            }
        });
    }

    // ---------- отказы склада ----------

    @Test
    @DisplayName("Отказ склада называет состояние словом между наименованием и кодом")
    void stockRefusalNamesTheCondition() throws Exception {
        Long warehouse = warehouse("Склад списания");
        Long partId = stockPart("Фара склада", PartCondition.CONTRACT, warehouse);
        String code = inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, partId));

        mvc.perform(post("/api/stock/write-offs").with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"разбита",
                                 "items":[{"partId":%d,"quantity":2}]}"""
                                .formatted(warehouse, partId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString(
                        "Фара склада · контрактная (" + code + ")")))
                .andExpect(jsonPath("$.message").value(not(containsString("CONTRACT"))));
    }

    /**
     * Пустое состояние (с 0322 колонка допускает {@code NULL}, {@code tenant/091})
     * — слова нет и разделителя тоже. Колонку делает пустой {@link #nullableCondition}.
     */
    @Test
    @DisplayName("Без состояния отказ склада не печатает ни слова, ни разделителя")
    void stockRefusalWithoutConditionPrintsNoWord() throws Exception {
        nullableCondition();
        Long warehouse = warehouse("Склад без состояния");
        Long partId = stockPart("Фара без состояния", null, warehouse);
        String code = inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, partId));

        assertThat(inTenant(() -> naming.part(partId)))
                .isEqualTo("Фара без состояния (" + code + ")");
        mvc.perform(post("/api/stock/write-offs").with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"разбита",
                                 "items":[{"partId":%d,"quantity":2}]}"""
                                .formatted(warehouse, partId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString(
                        "Фара без состояния (" + code + ")")))
                .andExpect(jsonPath("$.message").value(not(containsString("Фара без состояния ·"))));
    }

    // ---------- журнал действий ----------

    @Test
    @DisplayName("Журнал называет состояние на момент записи, а не нынешнее")
    void journalShowsTheConditionAtTheTimeOfTheEntry() throws Exception {
        String title = "Фара журнала " + UUID.randomUUID().toString().substring(0, 8);
        Long warehouse = warehouse("Склад журнала");
        Long partId = stockPart(title, PartCondition.USED, warehouse);
        MockHttpSession owner = login("vladelec");

        // 1. Правка цены, пока позиция б/у.
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":5500}"))
                .andExpect(status().isOk());
        // 2. Сделка, пока позиция б/у.
        mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":%d,\"items\":[{\"partId\":%d,\"quantity\":1,\"warehouseId\":%d}]}"
                                .formatted(customer, partId, warehouse)))
                .andExpect(status().isCreated());
        // 3. Состояние сменили — теперь позиция контрактная.
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"condition\":\"CONTRACT\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/organization/audit").param("q", title).session(owner))
                // Смена состояния: запись показывает состояние после неё
                // и старое с новым — словами, а не кодом.
                .andExpect(jsonPath("$.items[?(@.kind == 'Товар' && @.changes[0].label == 'Состояние')].subjectCondition")
                        .value(org.hamcrest.Matchers.contains("CONTRACT")))
                .andExpect(jsonPath("$.items[?(@.kind == 'Товар')].changes[?(@.label == 'Состояние')].before")
                        .value(org.hamcrest.Matchers.contains("Б/у")))
                .andExpect(jsonPath("$.items[?(@.kind == 'Товар')].changes[?(@.label == 'Состояние')].after")
                        .value(org.hamcrest.Matchers.contains("Контрактная")))
                // Правка цены — до смены: тогда позиция была б/у.
                .andExpect(jsonPath("$.items[?(@.kind == 'Товар' && @.changes[0].label == 'Цена')].subjectCondition")
                        .value(org.hamcrest.Matchers.contains("USED")))
                // Позиция сделки — тоже до смены; у самой строки сделки
                // состояния в снимке нет, оно берётся из снимков позиции.
                .andExpect(jsonPath("$.items[?(@.kind == 'Позиция сделки')].subjectCondition")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("USED"))))
                .andExpect(jsonPath("$.items[?(@.kind == 'Позиция сделки')]")
                        .value(org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThan(0))));
    }

    /**
     * Пустое состояние — значение, а не «нет сведений» (шов с 0322, найдено
     * разбором свода): позиция без состояния, правка цены и сделка, потом
     * состояние поставили. Прежние записи остаются без состояния — назови
     * их журнал «контрактная», он соврал бы о прошлом.
     */
    @Test
    @DisplayName("Записи, сделанные без состояния, после его установки остаются без состояния")
    void journalKeepsEmptyConditionOfOldEntries() throws Exception {
        String title = "Фара пустая " + UUID.randomUUID().toString().substring(0, 8);
        nullableCondition();
        Long warehouse = warehouse("Склад журнала 3");
        Long partId = stockPart(title, null, warehouse);
        MockHttpSession owner = login("vladelec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":5500}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":%d,\"items\":[{\"partId\":%d,\"quantity\":1,\"warehouseId\":%d}]}"
                                .formatted(customer, partId, warehouse)))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"condition\":\"CONTRACT\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/organization/audit").param("q", title).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.kind == 'Товар' && @.changes[0].label == 'Состояние')].subjectCondition")
                        .value(org.hamcrest.Matchers.contains("CONTRACT")))
                .andExpect(jsonPath("$.items[?(@.kind == 'Товар' && @.changes[0].label == 'Цена')].subjectCondition")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.items[?(@.kind == 'Позиция сделки')].subjectCondition")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.items[?(@.kind == 'Позиция сделки')]")
                        .value(org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThan(0))));
    }

    /**
     * Позиция заведена мимо Hibernate (переносом): снимков до действия нет,
     * и состояние берётся из «было» первой правки после — нынешнее тут
     * было бы неправдой о прошлом.
     */
    @Test
    @DisplayName("Без снимка до действия состояние берётся из «было» первой правки после")
    void journalReconstructsFromTheFirstLaterChange() throws Exception {
        String title = "Фонарь журнала " + UUID.randomUUID().toString().substring(0, 8);
        Long warehouse = warehouse("Склад журнала 2");
        Long partId = stockPart(title, PartCondition.NEW, warehouse);
        MockHttpSession owner = login("vladelec");

        mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":%d,\"items\":[{\"partId\":%d,\"quantity\":1,\"warehouseId\":%d}]}"
                                .formatted(customer, partId, warehouse)))
                .andExpect(status().isCreated());
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE table_name = 'part' AND record_id = ?",
                Long.class, partId)))
                .as("у позиции, заведённой мимо Hibernate, уже есть снимок — проверка ни о чём")
                .isZero();
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"condition\":\"REFURBISHED\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/organization/audit").param("q", title).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.kind == 'Позиция сделки')].subjectCondition")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("NEW"))))
                .andExpect(jsonPath("$.items[?(@.kind == 'Позиция сделки')]")
                        .value(org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThan(0))));
    }

    // ---------- перебор ----------

    /** Журнал и отказы склада не держат своего словаря состояний. */
    @Test
    @DisplayName("Журнал и отказы склада не строят слово состояния сами")
    void noOwnConditionWords() throws IOException {
        List<Path> files = List.of(
                Path.of("src/main/java/ru/partsflow/platform/audit/OrganizationAuditService.java"),
                Path.of("src/main/java/ru/partsflow/inventory/StockNaming.java"));
        Pattern ownWord = Pattern.compile("\"(новая|б/у|восстановленная|контрактная)\"",
                Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);
        for (Path file : files) {
            String text = Files.readString(file);
            assertThat(ownWord.matcher(text).find())
                    .as("%s держит своё слово состояния — словарь один, PartCondition", file)
                    .isFalse();
        }
    }

    // ---------- фикстура ----------

    /**
     * До слияния {@code tenant/091} (задача 0322, PR 460): в этой ветке колонка
     * ещё {@code NOT NULL}, и пустое состояние делается снятием ограничения
     * в своей схеме {@code t_000963}. <b>После слияния 091 этот метод и его
     * вызовы убрать</b> — колонка будет допускать пустоту сама.
     */
    private void nullableCondition() {
        inTenant(() -> {
            jdbc.execute("ALTER TABLE part ALTER COLUMN condition DROP NOT NULL");
        });
    }

    private Long stockPart(String title, PartCondition condition, Long warehouse) {
        return inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, condition)
                    VALUES (1, ?, 5000, 2000, ?) RETURNING id""", Long.class,
                    title, condition == null ? null : condition.name());
            ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouse, null));
            return id;
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
