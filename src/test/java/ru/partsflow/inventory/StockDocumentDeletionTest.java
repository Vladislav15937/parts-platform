package ru.partsflow.inventory;

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
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Удаление выполненного поступления, списания и перемещения (задача 0367,
 * «Ответ владельца — 23 октября 2026», пп. 1–5).
 *
 * <p>Через HTTP: право проверяется в сервисе ({@code PostedDocumentDeletion}),
 * ответ — record, и признак кнопки ставит сервер в ленте карточки и журнале
 * перевозок; всё это видно только снаружи.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class StockDocumentDeletionTest extends PostgresTestBase {

    private static final String TENANT = "t_000367";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockDocumentService documents;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private StockReservationRepository reservations;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper json;

    private Long warehouse;
    private Long farWarehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 367");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (367, ?, 'Удаление документов', 'docdelete')""", TENANT);

        inTenant(() -> {
            member("vladelec", "Владелец Удаляющий", "OWNER");
            member("menedzher", "Менеджер", "MANAGER");
            member("kladovshchik", "Кладовщик", "STOREKEEPER");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            farWarehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Дальний') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    // ------------------------------------------------------------ поступление

    @Test
    @DisplayName("Поступление: снимается только свободное, отложенное остаётся и названо, исходное движение на месте")
    void intakeTakesBackOnlyTheFree() throws Exception {
        long part = part("Фара левая");
        long doc = intake(part, "2");
        inTenant(() -> {
            reservations.reserve(part, warehouse, new BigDecimal("1"));
            return null;
        });

        JsonNode outcome = deleteOk(doc, login("vladelec"));

        assertThat(outcome.path("alreadyDeleted").asBoolean()).isFalse();
        JsonNode notTaken = outcome.path("notTaken");
        assertThat(notTaken).as("отложенное не названо человеку").hasSize(1);
        assertThat(notTaken.get(0).path("title").asText()).isEqualTo("Фара левая");
        assertThat(notTaken.get(0).path("taken").decimalValue()).isEqualByComparingTo("1");
        assertThat(notTaken.get(0).path("wanted").decimalValue()).isEqualByComparingTo("2");

        assertThat(stock(part, warehouse)).as("свободное снято, отложенное осталось")
                .isEqualByComparingTo("1");
        assertThat(reserved(part, warehouse)).isEqualByComparingTo("1");
        assertThat(movements(part)).as("исходное движение и встречное — оба в журнале")
                .containsExactly("INTAKE 2.000", "INTAKE -1.000");
        assertThat(docState(doc)).as("статус документа остаётся DONE, отметка — отдельно")
                .isEqualTo("DONE deleted by Владелец Удаляющий");
        assertThat(discrepancies()).as("остаток и журнал разошлись").isZero();
        assertThat(partStatus(part)).isEqualTo("IN_STOCK");
    }

    /**
     * Вопрос владельцу, названный в PR: какой статус у позиции, когда встречное
     * движение обнулило остаток поступления. Сделано «как будто документа не
     * было»: не было ухода со склада — «черновик», была продажа — «продано».
     * Общее правило StockLedger дало бы по виду INTAKE «в наличии» при нуле.
     */
    @Test
    @DisplayName("Поступление снято целиком: позиция не «в наличии» и не «списано», а как без документа")
    void fullyTakenBackIntakeStatusIsAsWithoutTheDocument() throws Exception {
        long only = part("Бампер");
        long onlyDoc = intake(only, "1");
        deleteOk(onlyDoc, login("vladelec"));
        assertThat(stock(only, warehouse)).isEqualByComparingTo("0");
        assertThat(partStatus(only)).as("не приходовалась — черновик").isEqualTo("DRAFT");

        long sold = part("Капот");
        intake(sold, "1");
        inTenant(() -> ledger.record(StockMovement.sale(sold, BigDecimal.ONE, warehouse, null)));
        long second = intake(sold, "1");
        deleteOk(second, login("vladelec"));
        assertThat(partStatus(sold)).as("второе поступление удалено — проданная остаётся проданной")
                .isEqualTo("SOLD");
    }

    // ---------------------------------------------------------------- списание

    @Test
    @DisplayName("Списание: возвращается целиком, позиция снова в наличии")
    void writeOffComesBack() throws Exception {
        long part = part("Дверь");
        intake(part, "2");
        long doc = writeOff(part, "2");
        assertThat(partStatus(part)).isEqualTo("WRITTEN_OFF");

        JsonNode outcome = deleteOk(doc, login("vladelec"));

        assertThat(outcome.path("notTaken")).isEmpty();
        assertThat(stock(part, warehouse)).isEqualByComparingTo("2");
        assertThat(partStatus(part)).isEqualTo("IN_STOCK");
        assertThat(discrepancies()).isZero();
    }

    // ------------------------------------------------------------ перемещение

    @Test
    @DisplayName("Перемещение: обратно едет только свободное на складе-получателе")
    void moveTakesBackOnlyTheFreeOnTheDestination() throws Exception {
        long part = part("Стартер");
        intake(part, "2");
        long doc = move(part, "2");
        inTenant(() -> {
            reservations.reserve(part, farWarehouse, new BigDecimal("1"));
            return null;
        });

        JsonNode outcome = deleteOk(doc, login("vladelec"));

        assertThat(outcome.path("notTaken").get(0).path("taken").decimalValue())
                .isEqualByComparingTo("1");
        assertThat(stock(part, farWarehouse)).as("отложенное на получателе осталось")
                .isEqualByComparingTo("1");
        assertThat(stock(part, warehouse)).as("свободное вернулось на склад-источник")
                .isEqualByComparingTo("1");
        assertThat(discrepancies()).isZero();
    }

    // ------------------------------------------------- повтор и одновременность

    @Test
    @DisplayName("Повтор удаления — не второе встречное движение и не отказ: тот же итог с alreadyDeleted")
    void repeatIsTheSameOutcome() throws Exception {
        long part = part("Генератор");
        long doc = intake(part, "2");
        inTenant(() -> {
            reservations.reserve(part, warehouse, new BigDecimal("1"));
            return null;
        });
        MockHttpSession owner = login("vladelec");
        JsonNode first = deleteOk(doc, owner);
        JsonNode second = deleteOk(doc, owner);

        assertThat(second.path("alreadyDeleted").asBoolean()).isTrue();
        assertThat(second.path("notTaken")).isEqualTo(first.path("notTaken"));
        assertThat(second.path("deletedAt").asText()).isEqualTo(first.path("deletedAt").asText());
        assertThat(movements(part)).containsExactly("INTAKE 2.000", "INTAKE -1.000");
    }

    /**
     * Списание, а не поступление, — потому что у него снимать нечего: без
     * блокировки строки документа два одновременных удаления оба вернули бы
     * товар на склад, и остаток удвоился бы. У поступления второе встречное
     * движение отбило бы условие «есть свободное», и гонка спряталась бы.
     *
     * <p>Повторяется направленно (разбор PR 505): гонка ловится повтором рядом
     * с ней, а не объёмом полного прогона.
     */
    @org.junit.jupiter.api.RepeatedTest(10)
    @DisplayName("Два одновременных удаления: одно встречное движение, оба ответа — успех")
    void concurrentDeletesWriteOneCounterMovement() throws Exception {
        MockHttpSession first = login("vladelec");
        MockHttpSession second = login("vladelec");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                long part = part("Радиатор " + round);
                intake(part, "1");
                long doc = writeOff(part, "1");
                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<Integer>> answers = new ArrayList<>();
                for (MockHttpSession session : List.of(first, second)) {
                    answers.add(pool.submit(() -> {
                        start.await();
                        return mvc.perform(delete("/api/stock/documents/" + doc).with(csrf())
                                        .session(session))
                                .andReturn().getResponse().getStatus();
                    }));
                }
                for (Future<Integer> answer : answers) {
                    assertThat(answer.get()).as("одновременный повтор получил отказ").isEqualTo(200);
                }
                assertThat(stock(part, warehouse)).as("списанное вернулось дважды").isEqualByComparingTo("1");
                assertThat(movements(part)).containsExactly(
                        "INTAKE 1.000", "WRITE_OFF -1.000", "WRITE_OFF 1.000");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ право

    @Test
    @DisplayName("Удаляет только владелец: менеджеру и кладовщику — 403, кнопки у них нет")
    void onlyTheOwnerDeletes() throws Exception {
        long part = part("Зеркало");
        long doc = intake(part, "1");

        for (String login : List.of("menedzher", "kladovshchik")) {
            mvc.perform(delete("/api/stock/documents/" + doc).with(csrf()).session(login(login)))
                    .andExpect(status().isForbidden());
            JsonNode row = movementRows(part, login("menedzher")).get(0);
            assertThat(row.path("deletableDocumentId").isNull())
                    .as("без права сервер не отметил строку удаляемой").isTrue();
        }
        assertThat(docState(doc)).isEqualTo("DONE");

        JsonNode row = movementRows(part, login("vladelec")).get(0);
        assertThat(row.path("deletableDocumentId").asLong()).isEqualTo(doc);
    }

    @Test
    @DisplayName("Черновик не удаляется, а отменяется: 409 словами")
    void draftIsRefusedWithWords() throws Exception {
        long part = part("Молдинг");
        long draft = inTenant(() -> {
            StockDocument document = StockDocument.intake(warehouse, null);
            document.addLine(part, BigDecimal.ONE, null);
            return documents.save(document).getId();
        });

        String message = mvc.perform(delete("/api/stock/documents/" + draft).with(csrf())
                        .session(login("vladelec")))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(message).contains("ещё не выполнен");
    }

    // ---------------------------------------------------- кто показывал удалённое

    @Test
    @DisplayName("«Движение товара»: обе строки удалённого документа — «Выполнен (Удалён)», кнопки больше нет")
    void historyShowsDeleted() throws Exception {
        long part = part("Крыло");
        long doc = intake(part, "1");
        MockHttpSession owner = login("vladelec");
        deleteOk(doc, owner);

        JsonNode rows = movementRows(part, owner);
        assertThat(rows).hasSize(2);
        for (JsonNode row : rows) {
            assertThat(row.path("status").asText()).isEqualTo("Выполнен (Удалён)");
            assertThat(row.path("deleted").asBoolean()).isTrue();
            assertThat(row.path("deletableDocumentId").isNull()).isTrue();
            assertThat(row.path("document").asText()).startsWith("Поступление №");
        }
        assertThat(rows.get(0).path("reason").asText()).isEqualTo("Удаление документа");
        assertThat(rows.get(0).path("qty").decimalValue()).isEqualByComparingTo("-1");
        assertThat(history(part, owner).path("intake").isNull())
                .as("удалённое поступление не называется тем, которым позиция пришла").isTrue();
    }

    /**
     * Правило «что удаляется» одно (`StockDocument.refusalOf`), и его читают три
     * поверхности. Мягкие утверждения — чтобы подделка правила показала, что
     * краснеют все три, а не только первая.
     */
    @Test
    @DisplayName("Одно правило удаляемости: лента карточки, журнал перевозок и сервис отвечают одинаково")
    void oneRuleForLaneJournalAndService() throws Exception {
        long part = part("Рулевая тяга");
        intake(part, "1");
        long doc = move(part, "1");
        MockHttpSession owner = login("vladelec");

        org.assertj.core.api.SoftAssertions soft = new org.assertj.core.api.SoftAssertions();
        JsonNode moveRow = null;
        for (JsonNode row : movementRows(part, owner)) {
            if (row.path("document").asText().startsWith("Перемещение")) {
                moveRow = row;
            }
        }
        soft.assertThat(moveRow == null ? null : moveRow.path("deletableDocumentId").asLong())
                .as("лента: перемещение не отмечено удаляемым").isEqualTo(doc);
        soft.assertThat(journalRow(doc, owner).path("deletable").asBoolean())
                .as("журнал перевозок: перемещение не отмечено удаляемым").isTrue();
        soft.assertThat(mvc.perform(delete("/api/stock/documents/" + doc).with(csrf()).session(owner))
                        .andReturn().getResponse().getStatus())
                .as("сервис отказал удалить перемещение").isEqualTo(200);
        soft.assertAll();
    }

    @Test
    @DisplayName("Журнал перевозок: удалённое перемещение остаётся с отметкой и без кнопки")
    void moveJournalMarksDeleted() throws Exception {
        long part = part("Колпак");
        intake(part, "1");
        long doc = move(part, "1");
        MockHttpSession owner = login("vladelec");

        assertThat(journalRow(doc, owner).path("deletable").asBoolean()).isTrue();
        assertThat(journalRow(doc, login("menedzher")).path("deletable").asBoolean()).isFalse();

        deleteOk(doc, owner);
        JsonNode row = journalRow(doc, owner);
        assertThat(row.path("deletedAt").isNull()).as("удалённое показано обычным").isFalse();
        assertThat(row.path("deletedBy").asText()).startsWith("Владелец Удаляющий (");
        assertThat(row.path("deletable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Повтор приёмки по ключу после удаления поступления: ответ первого раза, второго прихода нет")
    void intakeReplayDoesNotResurrect() throws Exception {
        String requestId = "udalenie-" + System.nanoTime();
        String body = """
                {"requestId":"%s","warehouseId":%d,"items":[{"rawName":"Фонарь","price":100,"quantity":1}]}"""
                .formatted(requestId, warehouse);
        MockHttpSession owner = login("vladelec");
        JsonNode first = json.readTree(mvc.perform(post("/api/intake/receipts").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        long doc = first.path("documentId").asLong();
        long part = first.path("parts").get(0).path("id").asLong();
        assertThat(first.path("deleted").asBoolean()).isFalse();

        deleteOk(doc, owner);

        JsonNode replay = json.readTree(mvc.perform(post("/api/intake/receipts").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(replay.path("documentId").asLong()).isEqualTo(doc);
        assertThat(replay.path("deleted").asBoolean()).isTrue();
        assertThat(stock(part, warehouse)).as("повтор вернул удалённый приход").isEqualByComparingTo("0");
    }

    /**
     * Встречное движение того же вида с минусом: модуль в «Поступило» посчитал
     * бы удалённое поступление вторым приходом.
     */
    @Test
    @DisplayName("«Поступило» в разрезе по поставке не считает удалённое поступление")
    void originReportDoesNotCountTheDeletedIntake() throws Exception {
        long part = part("Суппорт");
        long supply = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO supply (kind, number, status) VALUES ('CONTAINER', 'Р-367', 'ARRIVED')
                RETURNING id""", Long.class));
        inTenant(() -> jdbc.update("UPDATE part SET supply_id = ? WHERE id = ?", supply, part));
        intake(part, "1");
        long second = intake(part, "2");
        MockHttpSession owner = login("vladelec");
        deleteOk(second, owner);

        JsonNode page = json.readTree(mvc.perform(get("/api/reports/supplies/items")
                        .param("supplyId", String.valueOf(supply)).param("tab", "received")
                        .session(owner))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(page.path("rows").get(0).path("quantity").decimalValue())
                .as("удалённое поступление посчитано приходом").isEqualByComparingTo("1");
    }

    /**
     * Остальные три суммы отчёта (разбор PR 505): «Списано» и оба графика прихода.
     * Модуль вместо суммы со знаком посчитал бы встречные движения вторым
     * приходом и вторым списанием.
     */
    @Test
    @DisplayName("«Списано» и графики по поставке не считают удалённые списание и поступление")
    void originReportWriteOffAndChartsSkipDeleted() throws Exception {
        long part = part("Тяга");
        long supply = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO supply (kind, number, status) VALUES ('CONTAINER', 'Р-367-2', 'ARRIVED')
                RETURNING id""", Long.class));
        inTenant(() -> jdbc.update(
                "UPDATE part SET supply_id = ?, cost_price = 300 WHERE id = ?", supply, part));
        intake(part, "2");
        long extra = intake(part, "3");
        MockHttpSession owner = login("vladelec");
        deleteOk(extra, owner);
        long firstOff = writeOff(part, "1");
        deleteOk(firstOff, owner);
        writeOff(part, "2");
        assertThat(partStatus(part)).isEqualTo("WRITTEN_OFF");

        JsonNode off = json.readTree(mvc.perform(get("/api/reports/supplies/items")
                        .param("supplyId", String.valueOf(supply)).param("tab", "written-off")
                        .session(owner))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(off.path("rows").get(0).path("quantity").decimalValue())
                .as("удалённое списание посчитано списанием").isEqualByComparingTo("2");

        JsonNode chart = json.readTree(mvc.perform(get("/api/reports/supplies/chart")
                        .param("supplyId", String.valueOf(supply)).session(owner))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        JsonNode points = chart.path("points");
        JsonNode last = points.get(points.size() - 1);
        assertThat(last.path("planned").decimalValue())
                .as("планируемая сумма считает удалённое поступление (цена 1000 × 2)")
                .isEqualByComparingTo("2000");
        assertThat(last.path("totalCost").decimalValue())
                .as("себестоимость всех считает удалённое поступление (300 × 2)")
                .isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("Время строки сделки в «Движении товара» — момент попадания позиции в сделку")
    void heldRowTakesItemMoment() throws Exception {
        long part = part("Решётка");
        intake(part, "1");
        Instant added = Instant.parse("2026-10-05T08:15:00Z");
        inTenant(() -> {
            Long deal = jdbc.queryForObject("""
                    INSERT INTO deal (status, created_at) VALUES ('RESERVED', '2026-10-01T10:00:00Z')
                    RETURNING id""", Long.class);
            jdbc.update("""
                    INSERT INTO deal_item (deal_id, part_id, quantity, price, status, warehouse_id, added_at)
                    VALUES (?, ?, 1, 4500, 'RESERVED', ?, ?::timestamptz)""",
                    deal, part, warehouse, added.toString());
            return null;
        });

        JsonNode held = null;
        for (JsonNode row : movementRows(part, login("vladelec"))) {
            if (row.path("held").asBoolean()) {
                held = row;
            }
        }
        assertThat(held).isNotNull();
        assertThat(Instant.parse(held.path("at").asText())).as("взято заведение сделки")
                .isEqualTo(added);
        assertThat(held.path("status").asText()).isEqualTo("Резерв");
        assertThat(held.path("price").decimalValue()).isEqualByComparingTo("4500");
    }

    // -------------------------------------------------------------- помощники

    private long part(String title) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price) VALUES (1, ?, 1000) RETURNING id""",
                Long.class, title));
    }

    private long intake(long part, String qty) {
        return inTenant(() -> {
            StockDocument document = StockDocument.intake(warehouse, null);
            document.addLine(part, new BigDecimal(qty), null);
            return documents.complete(documents.save(document).getId()).getId();
        });
    }

    private long writeOff(long part, String qty) {
        return inTenant(() -> {
            StockDocument document = StockDocument.writeOff(warehouse);
            document.setNote("разбита");
            document.addLine(part, new BigDecimal(qty), null);
            return documents.complete(documents.save(document).getId()).getId();
        });
    }

    private long move(long part, String qty) {
        return inTenant(() -> {
            StockDocument document = StockDocument.move(warehouse, farWarehouse);
            document.addLine(part, new BigDecimal(qty), null);
            return documents.complete(documents.save(document).getId()).getId();
        });
    }

    private JsonNode deleteOk(long doc, MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(delete("/api/stock/documents/" + doc).with(csrf()).session(session))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode history(long part, MockHttpSession session) throws Exception {
        return json.readTree(mvc.perform(get("/api/parts/" + part + "/history").session(session))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode movementRows(long part, MockHttpSession session) throws Exception {
        return history(part, session).path("movements");
    }

    private JsonNode journalRow(long doc, MockHttpSession session) throws Exception {
        JsonNode list = json.readTree(mvc.perform(get("/api/stock/moves").session(session))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        for (JsonNode row : list) {
            if (row.path("id").asLong() == doc) {
                return row;
            }
        }
        throw new AssertionError("перевозки нет в журнале");
    }

    private List<String> movements(long part) {
        return inTenant(() -> jdbc.queryForList("""
                SELECT movement_type || ' ' || qty_delta FROM stock_movement
                 WHERE part_id = ? ORDER BY id""", String.class, part));
    }

    private BigDecimal stock(long part, long wh) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT coalesce(sum(qty), 0) FROM part_stock WHERE part_id = ? AND warehouse_id = ?",
                BigDecimal.class, part, wh));
    }

    private BigDecimal reserved(long part, long wh) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT qty_reserved FROM part_stock WHERE part_id = ? AND warehouse_id = ?",
                BigDecimal.class, part, wh));
    }

    private String partStatus(long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM part WHERE id = ?", String.class, part));
    }

    private String docState(long doc) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT d.status || CASE WHEN d.deleted_at IS NULL THEN ''
                                        ELSE ' deleted by ' || m.display_name END
                  FROM stock_document d LEFT JOIN tenant_member m ON m.id = d.deleted_by
                 WHERE d.id = ?""", String.class, doc));
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
                                {"company":"docdelete","login":"%s","password":"пароль"}"""
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
