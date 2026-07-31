package ru.partsflow.sales;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Сквозной тест продаж: сделка — выдача — возврат.
 *
 * <p>Проверяет то, что нельзя проверить на объектах в памяти: остаток после
 * выдачи и возврата ведёт триггер по журналу движений, баланс клиента —
 * триггер по журналу лицевого счёта, а порядок «снять резерв, потом списать»
 * стережёт триггер {@code part_stock_reserved_guard}. Если сервис нарушит
 * любое из этих правил, узнать об этом можно только против настоящей БД.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class SalesServiceReturnTest extends PostgresTestBase {

    private static final String TENANT = "t_000044";

    @Autowired
    private SalesService salesService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long customerId;
    private Long managerId;
    private Long issueWarehouseId;
    private Long returnWarehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            Long branchId = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            issueWarehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад выдачи') RETURNING id",
                    Long.class, branchId);
            returnWarehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад возврата') RETURNING id",
                    Long.class, branchId);
            customerId = jdbc.queryForObject(
                    "INSERT INTO customer (name, phone) VALUES ('Автосервис на Ткацкой', '+79130000000') "
                            + "RETURNING id", Long.class);
            managerId = jdbc.queryForObject(
                    "INSERT INTO tenant_member (user_id, display_name, role) "
                            + "VALUES ((SELECT COALESCE(max(user_id), 0) + 1 FROM tenant_member), 'Пётр', 'SELLER') RETURNING id",
                    Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Возврат ставит деталь на склад возврата, а не на склад выдачи")
    void returnGoesToReturnWarehouse() {
        Long partId = partWithStock("Фара левая Camry V50", 1, new BigDecimal("8500"));

        Deal deal = inTenant(() -> issued(partId, new BigDecimal("8500")));
        Long itemId = deal.getItems().get(0).getId();

        assertThat(qty(partId, issueWarehouseId)).isEqualByComparingTo("0");

        DealReturn dealReturn = inTenant(() -> salesService.registerReturn(
                deal.getId(), returnWarehouseId, List.of(whole(itemId)),
                "Не подошёл", true, null, managerId));

        assertThat(dealReturn.getStatus()).isEqualTo(ReturnStatus.DONE);
        assertThat(dealReturn.getNumber()).as("документ возврата без номера").isNotNull();
        assertThat(dealReturn.getAmount()).isEqualByComparingTo("8500");

        assertThat(qty(partId, returnWarehouseId))
                .as("деталь не встала на склад возврата").isEqualByComparingTo("1");
        assertThat(qty(partId, issueWarehouseId))
                .as("деталь вернулась на склад выдачи, хотя принимали на другой")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Возврат целиком закрывает сделку как возвращённую")
    void fullReturnClosesDeal() {
        Long partId = partWithStock("Бампер передний X-Trail", 1, new BigDecimal("12000"));

        Deal deal = inTenant(() -> issued(partId, new BigDecimal("12000")));
        Long itemId = deal.getItems().get(0).getId();

        inTenant(() -> salesService.registerReturn(deal.getId(), returnWarehouseId,
                List.of(whole(itemId)), null, true, null, managerId));

        assertThat(dealStatus(deal.getId())).isEqualTo("RETURNED");
        assertThat(itemStatus(itemId)).isEqualTo("RETURNED");
    }

    @Test
    @DisplayName("Частичный возврат оставляет сделку выданной")
    void partialReturnKeepsDealIssued() {
        Long firstPart = partWithStock("Дверь задняя левая", 1, new BigDecimal("6000"));
        Long secondPart = partWithStock("Крыло переднее правое", 1, new BigDecimal("4000"));

        Deal deal = inTenant(() -> {
            Deal created = salesService.createReserved(customerId, managerId,
                    Instant.now().plus(2, ChronoUnit.DAYS),
                    List.of(new SalesService.ItemRequest(firstPart, BigDecimal.ONE,
                                    new BigDecimal("6000"), issueWarehouseId),
                            new SalesService.ItemRequest(secondPart, BigDecimal.ONE,
                                    new BigDecimal("4000"), issueWarehouseId)),
                    List.of());
            return salesService.issue(created.getId(), managerId);
        });

        Long returnedItem = deal.getItems().stream()
                .filter(i -> i.getPartId().equals(firstPart)).findFirst().orElseThrow().getId();

        inTenant(() -> salesService.registerReturn(deal.getId(), returnWarehouseId,
                List.of(whole(returnedItem)), null, true, null, managerId));

        assertThat(dealStatus(deal.getId()))
                .as("сделка закрылась, хотя одна позиция осталась у клиента")
                .isEqualTo("ISSUED");
        assertThat(qty(firstPart, returnWarehouseId)).isEqualByComparingTo("1");
        assertThat(qty(secondPart, returnWarehouseId)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Брак: деньги возвращают, на склад деталь не ставят")
    void defectiveReturnDoesNotRestock() {
        Long partId = partWithStock("Стартер 1NZ-FE", 1, new BigDecimal("5000"));

        Deal deal = inTenant(() -> issued(partId, new BigDecimal("5000")));
        Long itemId = deal.getItems().get(0).getId();

        DealReturn dealReturn = inTenant(() -> salesService.registerReturn(
                deal.getId(), returnWarehouseId,
                List.of(new SalesService.ReturnRequest(itemId, null, null, false)),
                "Не рабочий", true, null, managerId));

        assertThat(dealReturn.getAmount()).isEqualByComparingTo("5000");
        assertThat(stockRows(partId, returnWarehouseId))
                .as("бракованная деталь встала в остаток и снова продаётся").isZero();
        assertThat(balance()).as("деньги за брак клиенту не вернули")
                .isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("Возврат на лицевой счёт поднимает баланс клиента")
    void refundToAccountRaisesBalance() {
        Long partId = partWithStock("Генератор 2AZ-FE", 1, new BigDecimal("7000"));

        Deal deal = inTenant(() -> issued(partId, new BigDecimal("7000")));
        Long itemId = deal.getItems().get(0).getId();

        inTenant(() -> salesService.registerReturn(deal.getId(), returnWarehouseId,
                List.of(whole(itemId)), null, true, null, managerId));

        assertThat(balance()).isEqualByComparingTo("7000");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM customer_account_entry "
                        + "WHERE entry_type = 'DEAL_REFUND' AND customer_id = ?",
                Integer.class, customerId))).isEqualTo(1);
    }

    @Test
    @DisplayName("Возврат из кассы пишется расходным платежом, счёт не трогает")
    void refundFromCashRegisterCreatesOutgoingPayment() {
        Long partId = partWithStock("Радиатор кондиционера", 1, new BigDecimal("3000"));

        Deal deal = inTenant(() -> issued(partId, new BigDecimal("3000")));
        Long itemId = deal.getItems().get(0).getId();

        inTenant(() -> salesService.registerReturn(deal.getId(), returnWarehouseId,
                List.of(whole(itemId)), null, false, null, managerId));

        assertThat(balance()).as("возврат из кассы попал ещё и на лицевой счёт")
                .isEqualByComparingTo("0");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE direction = 'OUT' AND deal_id = ?",
                Integer.class, deal.getId()))).isEqualTo(1);
    }

    @Test
    @DisplayName("Невыданную позицию вернуть нельзя")
    void cannotReturnWhatWasNotIssued() {
        Long partId = partWithStock("Капот Camry V40", 1, new BigDecimal("9000"));

        Deal deal = inTenant(() -> salesService.createReserved(customerId, managerId,
                Instant.now().plus(1, ChronoUnit.DAYS),
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE,
                        new BigDecimal("9000"), issueWarehouseId)), List.of()));
        Long itemId = deal.getItems().get(0).getId();

        assertThatThrownBy(() -> inTenant(() -> salesService.registerReturn(
                deal.getId(), returnWarehouseId, List.of(whole(itemId)),
                null, true, null, managerId)))
                .hasMessageContaining("Вернуть можно только выданное");
    }

    @Test
    @DisplayName("История возврата и сделки пишется отдельными лентами")
    void historyIsWrittenForBothDocuments() {
        Long partId = partWithStock("Фара правая Camry V50", 1, new BigDecimal("8500"));

        Deal deal = inTenant(() -> issued(partId, new BigDecimal("8500")));
        Long itemId = deal.getItems().get(0).getId();

        DealReturn dealReturn = inTenant(() -> salesService.registerReturn(
                deal.getId(), returnWarehouseId, List.of(whole(itemId)),
                "Не подошёл", true, null, managerId));

        List<DocumentEvent> dealHistory = inTenant(() -> salesService.history(deal.getId()));
        assertThat(dealHistory).extracting(DocumentEvent::getEventType)
                .contains("CREATED", "ISSUED", "RETURNED");
        assertThat(dealHistory).filteredOn(e -> "RETURNED".equals(e.getEventType()))
                .singleElement()
                .satisfies(e -> assertThat(e.getMessage()).contains("Не подошёл"));

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM document_event WHERE document_type = 'RETURN' AND document_id = ?",
                Integer.class, dealReturn.getId()))).isEqualTo(1);
    }

    // ---------- фикстуры ----------

    private static SalesService.ReturnRequest whole(Long itemId) {
        return SalesService.ReturnRequest.whole(itemId);
    }

    /** Сделка, доведённая до выдачи: остаток списан, резерв снят. */
    private Deal issued(Long partId, BigDecimal price) {
        Deal created = salesService.createReserved(customerId, managerId,
                Instant.now().plus(1, ChronoUnit.DAYS),
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, price,
                        issueWarehouseId)),
                List.of());
        return salesService.issue(created.getId(), managerId);
    }

    private Long partWithStock(String title, int quantity, BigDecimal price) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, status)
                    VALUES (1, ?, ?, ?, 'IN_STOCK') RETURNING id""",
                    Long.class, title, price, price.multiply(new BigDecimal("0.4")));
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id)
                    VALUES (?, 'INTAKE', ?, ?)""", partId, quantity, issueWarehouseId);
            return partId;
        });
    }

    private BigDecimal qty(Long partId, Long warehouseId) {
        return inTenant(() -> {
            List<BigDecimal> found = jdbc.queryForList(
                    "SELECT qty FROM part_stock WHERE part_id = ? AND warehouse_id = ?",
                    BigDecimal.class, partId, warehouseId);
            return found.isEmpty() ? BigDecimal.ZERO : found.get(0);
        });
    }

    private int stockRows(Long partId, Long warehouseId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_stock WHERE part_id = ? AND warehouse_id = ?",
                Integer.class, partId, warehouseId));
    }

    private BigDecimal balance() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT balance FROM customer WHERE id = ?", BigDecimal.class, customerId));
    }

    private String dealStatus(Long dealId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM deal WHERE id = ?", String.class, dealId));
    }

    private String itemStatus(Long itemId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM deal_item WHERE id = ?", String.class, itemId));
    }

    /**
     * Выполняет действие в контексте арендатора и внутри транзакции.
     *
     * <p>Транзакция здесь обязательна: {@code search_path} на схему арендатора
     * выставляет провайдер соединений Hibernate, и {@code JdbcTemplate} видит
     * нужную схему только пока переиспользует соединение транзакции. Без неё
     * запрос уйдёт в {@code public}.
     */
    private <T> T inTenant(Supplier<T> action) {
        try {
            TenantContext.set(TENANT);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }
}
