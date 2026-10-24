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
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.StockLedger;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Итоги поставки на её карточке (задача 0375): поступления по статусам и на какую
 * сумму, товары штуками, машины и их затраты.
 *
 * <p>Через HTTP: ответ — record, и обычный класс уехал бы пятисоткой, которой
 * вызов сервиса не видит. Документы проведены настоящей приёмкой, продажа —
 * настоящей сделкой; черновик и отменённый документ заведены SQL, потому что
 * завести их с экрана пока нечем (0360).
 *
 * <p>Каждое число сверяется не только с записанным в тест, но и с независимым
 * счётом по документам и журналу — критерий задачи требует ровно этого.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class SupplySummaryTest extends PostgresTestBase {

    private static final String TENANT = "t_000375";

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
    private Long customer;
    private MockHttpSession owner;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 375");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (375, ?, 'Итоги поставки', 'supplysum')""", TENANT);
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    SELECT 'itogi-vladelets', 'Владелец итогов', ?, 'OWNER'
                     WHERE NOT EXISTS (SELECT 1 FROM tenant_member WHERE login = 'itogi-vladelets')""",
                    passwordEncoder.encode("пароль-подлиннее"));
            jdbc.update("UPDATE tenant_member SET display_name = 'Владелец итогов', role = 'OWNER' "
                    + "WHERE login = 'itogi-vladelets'");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            customer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id", Long.class);
            return null;
        });
        owner = login();
    }

    @Test
    @DisplayName("Контейнер с 3 машинами и 2 проведёнными поступлениями: итоги сходятся с документами и журналом")
    void summaryAgreesWithDocumentsAndJournal() throws Exception {
        long supply = arrivedSupply();
        for (int i = 1; i <= 3; i++) {
            long car = donor(supply, "ИТОГ-" + i);
            cost(car, 100_000 * i);                      // 100 000 + 200 000 + 300 000
        }

        receive(supply, """
                {"rawName":"Фара","quantity":1,"price":5000,"costPrice":700},
                {"rawName":"Дверь","quantity":2,"price":8000,"costPrice":1500}""");
        receive(supply, """
                {"rawName":"Бампер","quantity":1,"price":9000,"costPrice":2000}""");
        // Третье поступление проведено и удалено: товар снят встречным движением,
        // и «Выполнено — 3» обещало бы приход, которого нет.
        long removed = receive(supply, """
                {"rawName":"Капот","quantity":1,"price":3000,"costPrice":900}""");
        mvc.perform(delete("/api/stock/documents/" + removed).with(csrf()).session(owner))
                .andExpect(status().isOk());

        inTenant(() -> {
            otherDocument(supply, "DRAFT", 400);
            otherDocument(supply, "CANCELLED", 300);
            return null;
        });

        JsonNode summary = summary(supply);

        JsonNode receipts = summary.path("receipts");
        assertCount(receipts.path("drafts"), 1, "400");
        assertCount(receipts.path("done"), 2, "5700");          // 700 + 2×1500 + 2000
        assertCount(receipts.path("cancelled"), 1, "300");
        assertCount(receipts.path("total"), 4, "6400");

        // Независимо: сумма по строкам действующих документов поставки.
        BigDecimal byDocuments = inTenant(() -> jdbc.queryForObject("""
                SELECT sum(l.qty * COALESCE(l.price, p.cost_price, 0))
                  FROM stock_document d
                  JOIN stock_document_line l ON l.document_id = d.id
                  JOIN part p ON p.id = l.part_id
                 WHERE d.supply_id = ? AND d.doc_type = 'INTAKE' AND d.deleted_at IS NULL""",
                BigDecimal.class, supply));
        assertThat(receipts.path("total").path("amount").decimalValue())
                .as("«Всего» по поступлениям разошлось со строками документов")
                .isEqualByComparingTo(byDocuments);

        JsonNode goods = summary.path("goods");
        assertGoods(goods, 4, 0, 0, 4);
        assertThat(goods.path("total").decimalValue())
                .as("«Всего» товаров разошлось с приходом по журналу")
                .isEqualByComparingTo(intakeByJournal(supply));

        JsonNode donors = summary.path("donors");
        assertThat(donors.path("count").asLong()).as("машин в поставке").isEqualTo(3);
        assertThat(donors.path("cost").decimalValue())
                .as("затраты машин поставки").isEqualByComparingTo("600000");
    }

    @Test
    @DisplayName("Продажа одной двери из двух увеличивает «Продано» и уменьшает «Остаток»; списание — «Списано»")
    void saleAndWriteOffMoveTheGoods() throws Exception {
        long supply = arrivedSupply();
        long receipt = receive(supply, """
                {"rawName":"Дверь","quantity":2,"price":8000,"costPrice":1500},
                {"rawName":"Бампер","quantity":1,"price":9000,"costPrice":2000}""");
        long doors = partOf(receipt, 0);
        long bumper = partOf(receipt, 1);

        assertGoods(summary(supply).path("goods"), 3, 0, 0, 3);

        // Одна из двух: по статусу позиции (как вкладка «Продано» отчёта)
        // эта продажа не видна вовсе — позиция остаётся «в наличии».
        long deal = createDeal(doors);
        mvc.perform(post("/api/deals/" + deal + "/issue").with(csrf()).session(owner))
                .andExpect(status().isOk());
        assertGoods(summary(supply).path("goods"), 2, 1, 0, 3);

        inTenant(() -> {
            ledger.record(StockMovement.writeOff(bumper, BigDecimal.ONE, warehouse));
            return null;
        });
        JsonNode goods = summary(supply).path("goods");
        assertGoods(goods, 1, 1, 1, 3);
        assertThat(goods.path("total").decimalValue()).isEqualByComparingTo(intakeByJournal(supply));
    }

    @Test
    @DisplayName("Пустая поставка — нули, а не пустой ответ")
    void emptySupplyIsZeros() throws Exception {
        JsonNode summary = summary(arrivedSupply());
        assertCount(summary.path("receipts").path("total"), 0, "0");
        assertGoods(summary.path("goods"), 0, 0, 0, 0);
        assertThat(summary.path("donors").path("count").asLong()).isZero();
        assertThat(summary.path("donors").path("cost").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Незнакомая поставка — 400 словами, без номера строки базы")
    void unknownSupplyIsRefusedInWords() throws Exception {
        var result = mvc.perform(get("/api/intake/supplies/987654/summary").session(owner))
                .andExpect(status().isBadRequest())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("Поставка не найдена").doesNotContain("987654");
    }

    // ---------------------------------------------------------------- фикстуры

    private long arrivedSupply() {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO supply (kind, number, status, arrived_on)
                VALUES ('CONTAINER', 'ИТ-' || nextval('supply_id_seq'), 'ARRIVED', current_date)
                RETURNING id""", Long.class));
    }

    private long donor(long supply, String code) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO donor (public_code, brand_id, status, supply_id)
                VALUES (? || '-' || nextval('supply_id_seq'),
                        (SELECT id FROM catalog.brand ORDER BY id LIMIT 1), 'DISMANTLING', ?)
                RETURNING id""", Long.class, code, supply));
    }

    private void cost(long donor, int amount) throws Exception {
        mvc.perform(post("/api/intake/donors/" + donor + "/costs").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PURCHASE\",\"amount\":" + amount + "}"))
                .andExpect(status().is2xxSuccessful());
    }

    /** Проведённое поступление настоящей приёмкой; возвращает номер документа в базе. */
    private long receive(long supply, String items) throws Exception {
        var result = mvc.perform(post("/api/intake/receipts").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"sum-%d","warehouseId":%d,"supplyId":%d,"items":[%s]}"""
                                .formatted(System.nanoTime(), warehouse, supply, items)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsByteArray()).path("documentId").asLong();
    }

    private long partOf(long document, int index) {
        return inTenant(() -> jdbc.queryForList(
                "SELECT part_id FROM stock_document_line WHERE document_id = ? ORDER BY id",
                Long.class, document).get(index));
    }

    /** Черновик или отменённый документ поставки с одной позицией по закупке {@code cost}. */
    private void otherDocument(long supply, String status, int cost) {
        Long part = jdbc.queryForObject("""
                INSERT INTO part (title, price, cost_price, quantity, supply_id, product_line)
                VALUES ('Позиция документа', 1000, ?, 1, ?, 'PART') RETURNING id""",
                Long.class, cost, supply);
        Long document = jdbc.queryForObject("""
                INSERT INTO stock_document (doc_type, status, warehouse_id, supply_id)
                VALUES ('INTAKE', ?, ?, ?) RETURNING id""", Long.class, status, warehouse, supply);
        jdbc.update("INSERT INTO stock_document_line (document_id, part_id, qty) VALUES (?, ?, 1)",
                document, part);
    }

    private long createDeal(long part) throws Exception {
        var created = mvc.perform(post("/api/deals").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"items":[
                                  {"partId":%d,"quantity":1,"price":7000,"warehouseId":%d}]}"""
                                .formatted(customer, part, warehouse)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(created.getResponse().getContentAsByteArray()).path("id").asLong();
    }

    private BigDecimal intakeByJournal(long supply) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT COALESCE(sum(m.qty_delta), 0)
                  FROM stock_movement m JOIN part p ON p.id = m.part_id
                 WHERE p.supply_id = ? AND m.movement_type = 'INTAKE'""",
                BigDecimal.class, supply));
    }

    private JsonNode summary(long supply) throws Exception {
        var result = mvc.perform(get("/api/intake/supplies/" + supply + "/summary").session(owner))
                .andExpect(status().isOk())
                .andReturn();
        return json(result.getResponse().getContentAsByteArray());
    }

    private static void assertCount(JsonNode count, long expected, String amount) {
        assertThat(count.path("count").asLong()).as("документов").isEqualTo(expected);
        assertThat(count.path("amount").decimalValue()).as("на сумму").isEqualByComparingTo(amount);
    }

    private static void assertGoods(JsonNode goods, int remaining, int sold, int writtenOff,
                                    int total) {
        assertThat(goods.path("remaining").decimalValue()).as("Остаток")
                .isEqualByComparingTo(BigDecimal.valueOf(remaining));
        assertThat(goods.path("sold").decimalValue()).as("Продано")
                .isEqualByComparingTo(BigDecimal.valueOf(sold));
        assertThat(goods.path("writtenOff").decimalValue()).as("Списано")
                .isEqualByComparingTo(BigDecimal.valueOf(writtenOff));
        assertThat(goods.path("total").decimalValue()).as("Всего")
                .isEqualByComparingTo(BigDecimal.valueOf(total));
    }

    private static JsonNode json(byte[] body) throws Exception {
        return new ObjectMapper().readTree(body);
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"supplysum","login":"itogi-vladelets","password":"пароль-подлиннее"}"""))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(Supplier<T> action) {
        try {
            TenantContext.set(TENANT);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }
}
