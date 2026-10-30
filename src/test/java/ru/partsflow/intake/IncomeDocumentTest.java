package ru.partsflow.intake;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.StockLedger;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Документ поступления с черновиком (задача 0360, решение владельца 19.10.2026
 * «Всё как у bazon»): «Создать поступление» → позиции без остатка → «Провести
 * документ» → «Отменить проводку» → «Отменить поступление»; список по вкладкам
 * и история документа.
 *
 * <p>Через HTTP: ответы — record'ы, право стоит на путях, и всё это видно
 * только снаружи.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class IncomeDocumentTest extends PostgresTestBase {

    private static final String TENANT = "t_000360";

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
    private ObjectMapper json;

    private Long warehouse;
    private MockHttpSession owner;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 360");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (360, ?, 'Поступления', 'incomes')""", TENANT);
        inTenant(() -> {
            member("vladelec", "Владелец Принимающий", "OWNER");
            member("prosmotr", "Просмотр", "VIEWER");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
        owner = login("vladelec");
    }

    @Test
    @DisplayName("Черновик: позиции заведены с номером, но остатка нет; проведение кладёт на склад, отмена проводки снимает")
    void draftPostUnpostCancel() throws Exception {
        long supply = supply("Иванов | Двери");
        JsonNode draft = create(supply, "Иванов | Двери");
        long doc = draft.path("id").asLong();
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("supply").asText()).isEqualTo("Поставка №Иванов | Двери");
        assertThat(draft.path("warehouse").asText()).isEqualTo("Ткацкая");

        addItem(doc, "дверь передняя левая", "0", "Снимаем только обшивку");
        JsonNode two = addItem(doc, "дверь задняя правая", "1500", null);
        JsonNode lines = two.path("lines");
        assertThat(lines).hasSize(2);
        long first = lines.get(0).path("partId").asLong();
        long second = lines.get(1).path("partId").asLong();
        assertThat(lines.get(0).path("number").asLong()).as("у позиции черновика есть номер").isPositive();

        // Критерий 6: до «Провести документ» остатка нет — это и есть черновик.
        assertThat(onHand(first)).as("черновик двинул остаток").isEqualByComparingTo("0");
        assertThat(onHand(second)).isEqualByComparingTo("0");
        assertThat(partStatus(first)).isEqualTo("DRAFT");
        assertThat(movements(first)).as("черновик написал движение").isEmpty();
        assertThat(catalogFinds("дверь передняя", false)).as("в списке склада её нет").isFalse();
        assertThat(catalogFinds("дверь передняя", true))
                .as("с «Отображать отсутствующие» она есть").isTrue();

        JsonNode posted = act(doc, "post", status().isOk());
        assertThat(posted.path("status").asText()).isEqualTo("DONE");
        assertThat(posted.path("completedAt").isNull()).isFalse();
        assertThat(stock(first)).as("проведение не положило на склад поступления").isEqualByComparingTo("1");
        assertThat(stock(second)).isEqualByComparingTo("1");
        assertThat(partStatus(first)).isEqualTo("IN_STOCK");
        assertThat(texts(posted)).containsExactly("Создано поступление",
                "Добавлена позиция " + lines.get(0).path("number").asLong(),
                "Добавлена позиция " + lines.get(1).path("number").asLong(),
                "Поступление проведено");
        assertThat(posted.path("history").get(0).path("author").asText())
                .startsWith("Владелец Принимающий");

        JsonNode lane = history(first).path("movements").get(0);
        assertThat(lane.path("document").asText()).isEqualTo("Поступление №" + posted.path("number").asLong());
        assertThat(lane.path("status").asText()).isEqualTo("Выполнен");

        // Повтор «Провести документ» — не второй приход.
        act(doc, "post", status().isOk());
        assertThat(stock(first)).as("повтор проведения положил второй раз").isEqualByComparingTo("1");

        JsonNode unposted = act(doc, "unpost", status().isOk());
        assertThat(unposted.path("status").asText()).isEqualTo("DRAFT");
        assertThat(stock(first)).as("отмена проводки не сняла остаток").isEqualByComparingTo("0");
        assertThat(stock(second)).isEqualByComparingTo("0");
        assertThat(partStatus(first)).isEqualTo("DRAFT");
        assertThat(movements(first)).as("исходное движение остаётся, встречное — рядом")
                .containsExactly("INTAKE 1.000 null", "INTAKE -1.000 DOCUMENT_UNPOSTED");
        assertThat(texts(unposted)).last().isEqualTo("Проводка отменена");
        assertThat(discrepancies()).as("остаток и журнал разошлись").isZero();

        // Повтор «Отменить проводку» — тот же черновик, а не отказ и не второе снятие.
        act(doc, "unpost", status().isOk());
        assertThat(movements(first)).hasSize(2);

        JsonNode cancelled = act(doc, "cancel", status().isOk());
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(texts(cancelled)).last().isEqualTo("Отменено");
        JsonNode tab = list("cancelled");
        assertThat(tab.path("rows").findValuesAsText("number"))
                .contains(String.valueOf(cancelled.path("number").asLong()));
    }

    @Test
    @DisplayName("Повторное проведение после отмены проводки кладёт товар снова")
    void repostAfterUnpost() throws Exception {
        long doc = create(null, null).path("id").asLong();
        long part = addItem(doc, "капот", "5000", null).path("lines").get(0).path("partId").asLong();
        act(doc, "post", status().isOk());
        act(doc, "unpost", status().isOk());
        act(doc, "post", status().isOk());
        assertThat(stock(part)).isEqualByComparingTo("1");
        assertThat(discrepancies()).isZero();
    }

    @Test
    @DisplayName("Проданную позицию проводка не теряет: отказ словами, остаток и документ не тронуты")
    void soldRefusesUnpost() throws Exception {
        long doc = create(null, null).path("id").asLong();
        long part = addItem(doc, "фара левая", "8000", null).path("lines").get(0).path("partId").asLong();
        act(doc, "post", status().isOk());
        inTenant(() -> ledger.record(StockMovement.sale(part, BigDecimal.ONE, warehouse, null)));

        JsonNode refused = act(doc, "unpost", status().isConflict());
        assertThat(refused.path("message").asText())
                .contains("фара левая")
                .contains("позиция уже продана — отмена проводки невозможна");
        assertThat(docStatus(doc)).isEqualTo("DONE");
        assertThat(movements(part)).as("отказ оставил встречное движение")
                .containsExactly("INTAKE 1.000 null", "SALE -1.000 DEAL");
        assertThat(discrepancies()).isZero();
    }

    @Test
    @DisplayName("Выполненный документ сразу не отменяют — отказ называет «Отменить проводку»")
    void doneIsNotCancelledDirectly() throws Exception {
        long doc = create(null, null).path("id").asLong();
        addItem(doc, "зеркало", "900", null);
        act(doc, "post", status().isOk());
        JsonNode refused = act(doc, "cancel", status().isConflict());
        assertThat(refused.path("message").asText()).contains("Выполненный").contains("Отменить проводку");
        assertThat(docStatus(doc)).isEqualTo("DONE");
    }

    @Test
    @DisplayName("Без склада поступление не создаётся — словами; двойное «Создать» даёт один черновик")
    void createNeedsWarehouseAndIsIdempotent() throws Exception {
        MvcResult noWarehouse = mvc.perform(post("/api/intake/incomes").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(body(noWarehouse).path("message").asText()).contains("Выберите склад оприходования");

        long before = list("drafts").path("counts").path("drafts").asLong();
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CyclicBarrier start = new CyclicBarrier(4);
        List<Future<Integer>> answers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            answers.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/intake/incomes").with(csrf()).session(owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"warehouseId\":%d,\"requestId\":\"%s\"}"
                                        .formatted(warehouse, key)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> answer : answers) {
            codes.add(answer.get());
        }
        pool.shutdown();
        assertThat(codes).as("одновременный повтор получил отказ").containsOnly(201);
        assertThat(list("drafts").path("counts").path("drafts").asLong())
                .as("двойное «Создать поступление» завело два черновика").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("Партия с телефона по-прежнему проводится сразу и пишет ту же историю")
    void phoneBatchIsPostedAtOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String payload = """
                {"warehouseId":%d,"requestId":"%s","items":[{"rawName":"стартер","price":3000}]}"""
                .formatted(warehouse, key);
        JsonNode first = body(mvc.perform(post("/api/intake/receipts").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andReturn());
        JsonNode again = body(mvc.perform(post("/api/intake/receipts").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andReturn());
        long doc = first.path("documentId").asLong();
        assertThat(again.path("documentId").asLong()).isEqualTo(doc);
        long part = first.path("parts").get(0).path("id").asLong();
        assertThat(stock(part)).isEqualByComparingTo("1");

        JsonNode income = body(mvc.perform(get("/api/intake/incomes/" + doc).session(owner))
                .andExpect(status().isOk()).andReturn());
        assertThat(income.path("status").asText()).isEqualTo("DONE");
        assertThat(texts(income)).containsExactly("Создано поступление",
                "Добавлена позиция " + income.path("lines").get(0).path("number").asLong(),
                "Поступление проведено");
    }

    @Test
    @DisplayName("Без права «Принимать товар» поступление не заводят")
    void sellerCannotWrite() throws Exception {
        MockHttpSession seller = login("prosmotr");
        mvc.perform(post("/api/intake/incomes").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(warehouse)))
                .andExpect(status().isForbidden());
    }

    // ---------- вспомогательное ----------

    private long supply(String name) throws Exception {
        return body(mvc.perform(post("/api/intake/supplies").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"OTHER\",\"number\":\"%s\",\"note\":\"описание\"}".formatted(name)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private JsonNode create(Long supply, String note) throws Exception {
        String content = "{\"warehouseId\":%d,\"supplyId\":%s,\"note\":%s,\"requestId\":\"%s\"}"
                .formatted(warehouse, supply, note == null ? "null" : json.writeValueAsString(note),
                        UUID.randomUUID());
        return body(mvc.perform(post("/api/intake/incomes").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode addItem(long doc, String name, String price, String note) throws Exception {
        String content = "{\"rawName\":%s,\"quantity\":1,\"price\":%s,\"note\":%s,\"requestId\":\"%s\"}"
                .formatted(json.writeValueAsString(name), price,
                        note == null ? "null" : json.writeValueAsString(note), UUID.randomUUID());
        return body(mvc.perform(post("/api/intake/incomes/" + doc + "/items").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode act(long doc, String action, ResultMatcher expected) throws Exception {
        return body(mvc.perform(post("/api/intake/incomes/" + doc + "/" + action).with(csrf()).session(owner))
                .andExpect(expected).andReturn());
    }

    private JsonNode list(String tab) throws Exception {
        return body(mvc.perform(get("/api/intake/incomes").param("tab", tab).session(owner))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode history(long part) throws Exception {
        return body(mvc.perform(get("/api/parts/" + part + "/history").session(owner))
                .andExpect(status().isOk()).andReturn());
    }

    private boolean catalogFinds(String query, boolean missing) throws Exception {
        JsonNode view = body(mvc.perform(get("/api/parts/catalog").param("q", query)
                        .param("missing", String.valueOf(missing)).session(owner))
                .andExpect(status().isOk()).andReturn());
        return view.path("rows").size() > 0;
    }

    private static List<String> texts(JsonNode income) {
        List<String> out = new ArrayList<>();
        income.path("history").forEach(e -> out.add(e.path("text").asText()));
        return out;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private BigDecimal stock(long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT coalesce(sum(qty), 0) FROM part_stock WHERE part_id = ? AND warehouse_id = ?",
                BigDecimal.class, part, warehouse));
    }

    private BigDecimal onHand(long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT qty_on_hand FROM part WHERE id = ?", BigDecimal.class, part));
    }

    private String partStatus(long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM part WHERE id = ?", String.class, part));
    }

    private String docStatus(long doc) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM stock_document WHERE id = ?", String.class, doc));
    }

    private List<String> movements(long part) {
        return inTenant(() -> jdbc.queryForList("""
                SELECT movement_type || ' ' || qty_delta || ' ' || coalesce(ref_type, 'null')
                  FROM stock_movement WHERE part_id = ? ORDER BY id""", String.class, part));
    }

    private int discrepancies() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM v_stock_discrepancy", Integer.class));
    }

    private void member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (found.isEmpty()) {
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES (?, ?, ?, ?)""",
                    displayName, role, login, passwordEncoder.encode("пароль"));
        } else {
            // Фикстура пишет то, что называет (корневой CLAUDE.md, «Тесты»).
            jdbc.update("UPDATE tenant_member SET display_name = ?, role = ? WHERE login = ?",
                    displayName, role, login);
        }
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"incomes","login":"%s","password":"пароль"}"""
                                .formatted(login)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(s -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
