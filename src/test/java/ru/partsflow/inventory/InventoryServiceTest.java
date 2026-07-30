package ru.partsflow.inventory;

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
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Инвентаризация против настоящего склада.
 *
 * <p>Центральная проверка — {@link #saleDuringCountingIsNotShortage()}: пересчёт
 * не останавливает продажи, и наивная разница «посчитали минус сняли при
 * открытии» списала бы каждую проданную за время пересчёта деталь как
 * недостачу.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class InventoryServiceTest extends PostgresTestBase {

    private static final String TENANT = "t_000051";

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouse;
    private Long otherWarehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            // Сессии между тестами не мешают: каждый тест берёт свой склад.
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            otherWarehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, '54 YARD') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Открытие снимает учётный остаток склада")
    void openSnapshotsStock() {
        Long partId = partWithStock("Фара левая", 3);

        InventorySession session = inTenant(() -> inventory.open(warehouse, null));

        assertThat(session.getStatus()).isEqualTo(InventorySession.SessionStatus.OPEN);
        assertThat(session.getStartedAt()).as("момент открытия не вычитан из БД").isNotNull();
        assertThat(session.getLines()).singleElement().satisfies(line -> {
            assertThat(line.getPartId()).isEqualTo(partId);
            assertThat(line.getQtyExpected()).isEqualByComparingTo("3");
            assertThat(line.isCounted()).as("строка посчитана до пересчёта").isFalse();
        });
    }

    @Test
    @DisplayName("Сошедшаяся позиция движений не порождает")
    void matchingCountProducesNoMovement() {
        Long partId = partWithStock("Бампер передний", 2);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("2"), null));
        inTenant(() -> inventory.finishCounting(sessionId));

        assertThat(inTenant(() -> inventory.discrepancies(sessionId))).isEmpty();
        assertThat(inTenant(() -> inventory.apply(sessionId))).isZero();
        assertThat(adjustments(partId)).isZero();
        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("Недостача списывается корректировкой")
    void shortageIsWrittenOff() {
        Long partId = partWithStock("Стартер 1NZ-FE", 5);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("3"), null));
        inTenant(() -> inventory.finishCounting(sessionId));

        assertThat(inTenant(() -> inventory.discrepancies(sessionId))).singleElement()
                .satisfies(d -> {
                    assertThat(d.delta()).isEqualByComparingTo("-2");
                    assertThat(d.isShortage()).isTrue();
                });

        assertThat(inTenant(() -> inventory.apply(sessionId))).isEqualTo(1);
        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("Излишек приходуется корректировкой")
    void surplusIsAdded() {
        Long partId = partWithStock("Генератор 2AZ-FE", 1);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("4"), null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("Продажа во время пересчёта не превращается в недостачу")
    void saleDuringCountingIsNotShortage() {
        Long partId = partWithStock("Капот Camry V40", 10);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        // Кладовщик считает полку и находит все десять — учёт с фактом сошёлся.
        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("10"), null));

        // Потом продавец продаёт две. Склад работает, пересчёт его не морозит.
        sale(partId, warehouse, 2);

        inTenant(() -> inventory.finishCounting(sessionId));

        // Наивная разница «10 посчитали минус 10 сняли при открытии» дала бы
        // ноль корректировки — и это верно. Но если сравнивать с остатком
        // на момент проведения (8), получится излишек +2, и склад раздуется.
        assertThat(inTenant(() -> inventory.discrepancies(sessionId)))
                .as("продажа во время пересчёта попала в расхождения")
                .isEmpty();
        assertThat(inTenant(() -> inventory.apply(sessionId))).isZero();
        assertThat(qtyOf(partId, warehouse))
                .as("корректировка затёрла продажу").isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("Продажа до подсчёта уже учтена: недостачи нет")
    void saleBeforeCountingIsAccountedFor() {
        Long partId = partWithStock("Радиатор кондиционера", 10);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        // Сначала продали две, потом кладовщик дошёл до полки и нашёл восемь.
        sale(partId, warehouse, 2);
        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("8"), null));
        inTenant(() -> inventory.finishCounting(sessionId));

        // Против снимка при открытии это выглядело бы недостачей в две штуки.
        // Против учёта на момент подсчёта — всё сошлось.
        assertThat(inTenant(() -> inventory.discrepancies(sessionId)))
                .as("проданное до подсчёта посчитали недостачей")
                .isEmpty();
        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("Перемещение на другой склад не считается недостачей дважды")
    void moveIsAccountedPerWarehouse() {
        Long partId = partWithStock("Дверь задняя левая", 4);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        // Две уехали на второй склад до подсчёта.
        inTenant(() -> jdbc.update("""
                INSERT INTO stock_movement (part_id, movement_type, qty_delta,
                                            from_warehouse_id, to_warehouse_id)
                VALUES (?, 'MOVE', 2, ?, ?)""", partId, warehouse, otherWarehouse));

        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("2"), null));
        inTenant(() -> inventory.finishCounting(sessionId));

        // У перемещения qty_delta положительный, но для склада-источника это
        // расход: смотреть на знак нельзя, только на пару складов.
        assertThat(inTenant(() -> inventory.discrepancies(sessionId))).isEmpty();
        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("2");
        assertThat(qtyOf(partId, otherWarehouse)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("Непосчитанная строка не списывается")
    void uncountedLineIsNotWrittenOff() {
        Long counted = partWithStock("Крыло переднее правое", 2);
        Long skipped = partWithStock("Поддомкратник", 7);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, counted, new BigDecimal("2"), null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        // «Не дошли до полки» и «не нашли» — разные вещи. Подмена одного другим
        // списала бы полсклада.
        assertThat(qtyOf(skipped, warehouse))
                .as("непосчитанную позицию списали").isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("Посчитанный ноль — это недостача, а не пропуск")
    void countedZeroIsShortage() {
        Long partId = partWithStock("Ступица передняя", 3);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, partId, BigDecimal.ZERO, null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("0");
        assertThat(statusOf(partId))
                .as("обнулённая инвентаризацией деталь не должна выглядеть проданной")
                .isNotEqualTo("SOLD");
    }

    @Test
    @DisplayName("Найденная лишняя позиция добавляется строкой с нулевым учётом")
    void unknownPartBecomesSurplusLine() {
        partWithStock("Фара правая", 1);
        Long unknown = partWithStock("Найдена на полке", 0);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, unknown, new BigDecimal("2"), null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        assertThat(qtyOf(unknown, warehouse)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("Вторая инвентаризация того же склада не открывается")
    void secondSessionOnSameWarehouseIsRejected() {
        partWithStock("Амортизатор", 1);
        inTenant(() -> inventory.open(warehouse, null));

        // Две сессии дадут двойную корректировку на одно расхождение.
        assertThatThrownBy(() -> inTenant(() -> inventory.open(warehouse, null)))
                .hasMessageContaining("уже идёт инвентаризация");
    }

    @Test
    @DisplayName("Незавершённый пересчёт не проводится")
    void openSessionCannotBeApplied() {
        partWithStock("Фильтр воздушный", 1);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        assertThatThrownBy(() -> inTenant(() -> inventory.apply(sessionId)))
                .hasMessageContaining("состоянии OPEN");
    }

    @Test
    @DisplayName("Проведённую инвентаризацию не отменяют")
    void appliedSessionCannotBeCancelled() {
        Long partId = partWithStock("Тормозной диск", 2);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());
        inTenant(() -> inventory.count(sessionId, partId, BigDecimal.ONE, null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        assertThatThrownBy(() -> inTenant(() -> inventory.cancel(sessionId)))
                .hasMessageContaining("не отменяют");
    }

    @Test
    @DisplayName("Расхождения считаются одинаково до и после проведения")
    void discrepanciesAreStableAfterApply() {
        Long partId = partWithStock("Насос омывателя", 5);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());
        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("4"), null));
        inTenant(() -> inventory.finishCounting(sessionId));

        List<InventoryService.Discrepancy> before = inTenant(() -> inventory.discrepancies(sessionId));
        inTenant(() -> inventory.apply(sessionId));
        List<InventoryService.Discrepancy> after = inTenant(() -> inventory.discrepancies(sessionId));

        // Считается по неизменяемому журналу, поэтому ответ не зависит от того,
        // когда спросили: кладовщику можно показать итог и после проведения.
        assertThat(after).hasSameSizeAs(before);
        assertThat(after.get(0).delta()).isEqualByComparingTo(before.get(0).delta());
    }

    // ---------- фикстуры ----------

    private Long partWithStock(String title, int qty) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price)
                    VALUES (NULL, ?, 5000, 2000) RETURNING id""", Long.class, title);
            if (qty > 0) {
                jdbc.update("""
                        INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id)
                        VALUES (?, 'INTAKE', ?, ?)""", partId, qty, warehouse);
            }
            return partId;
        });
    }

    private void sale(Long partId, Long warehouseId, int qty) {
        inTenant(() -> jdbc.update("""
                INSERT INTO stock_movement (part_id, movement_type, qty_delta, from_warehouse_id)
                VALUES (?, 'SALE', ?, ?)""", partId, -qty, warehouseId));
    }

    private BigDecimal qtyOf(Long partId, Long warehouseId) {
        return inTenant(() -> {
            List<BigDecimal> found = jdbc.queryForList(
                    "SELECT qty FROM part_stock WHERE part_id = ? AND warehouse_id = ?",
                    BigDecimal.class, partId, warehouseId);
            return found.isEmpty() ? BigDecimal.ZERO : found.get(0);
        });
    }

    private int adjustments(Long partId) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM stock_movement
                 WHERE part_id = ? AND movement_type = 'INVENTORY_ADJUST'""",
                Integer.class, partId));
    }

    private String statusOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM part WHERE id = ?", String.class, partId));
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
