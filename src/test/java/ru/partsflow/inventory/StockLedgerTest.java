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
 * Сторож журнала склада: каждый вид движения и что он делает с остатком.
 *
 * <p>До 3 августа 2026 это делал триггер {@code stock_movement_apply}, и его
 * нельзя было обойти. Теперь применяет {@link StockLedger}, и цена этого —
 * возможность написать движение мимо него. Здесь проверяется, что перенос
 * ничего не потерял: каждый вид движения, порядок шагов у перемещения,
 * условия статуса и главное — что кэш сходится с журналом, то есть
 * {@code v_stock_discrepancy} пуста.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class StockLedgerTest extends PostgresTestBase {

    private static final String TENANT = "t_000095";

    @Autowired
    private StockLedger ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouse;
    private Long otherWarehouse;
    private Long cell;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            otherWarehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, '54 YARD') RETURNING id",
                    Long.class, branch);
            cell = jdbc.queryForObject(
                    "INSERT INTO storage_cell (warehouse_id, code) VALUES (?, 'А-01-1') RETURNING id",
                    Long.class, warehouse);
            return null;
        });
    }

    @Test
    @DisplayName("Приход кладёт остаток на склад и в ячейку, статус — «в наличии»")
    void intakePutsStockOnTheShelf() {
        Long partId = part("Фара левая");

        inTenant(() -> ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, cell)));

        assertThat(qtyAt(partId, warehouse)).isEqualByComparingTo("1");
        assertThat(onHand(partId)).isEqualByComparingTo("1");
        assertThat(status(partId)).isEqualTo("IN_STOCK");
        assertThat(cellOf(partId)).isEqualTo(cell);
    }

    @Test
    @DisplayName("Продажа обнуляет остаток и переводит карточку в «продано»")
    void saleMarksThePartSold() {
        Long partId = part("Стартер");
        intake(partId, warehouse, "1");

        inTenant(() -> ledger.record(StockMovement.sale(partId, BigDecimal.ONE, warehouse, null)));

        assertThat(onHand(partId)).isEqualByComparingTo("0");
        assertThat(status(partId)).isEqualTo("SOLD");
    }

    @Test
    @DisplayName("Списание переводит в «списано», а не в «продано»")
    void writeOffMarksThePartWrittenOff() {
        Long partId = part("Бампер битый");
        intake(partId, warehouse, "1");

        inTenant(() -> ledger.record(StockMovement.writeOff(partId, BigDecimal.ONE, warehouse)));

        assertThat(status(partId)).isEqualTo("WRITTEN_OFF");
    }

    @Test
    @DisplayName("Возврат оживляет карточку")
    void returnPutsThePartBack() {
        Long partId = part("Дверь");
        intake(partId, warehouse, "1");
        inTenant(() -> ledger.record(StockMovement.sale(partId, BigDecimal.ONE, warehouse, null)));

        inTenant(() -> ledger.record(
                StockMovement.returned(partId, BigDecimal.ONE, warehouse, null)));

        assertThat(onHand(partId)).isEqualByComparingTo("1");
        assertThat(status(partId)).isEqualTo("IN_STOCK");
    }

    @Test
    @DisplayName("Недостача списывает, излишек статус не трогает")
    void adjustmentDependsOnDirection() {
        Long shortage = part("Пропавшая фара");
        intake(shortage, warehouse, "1");
        inTenant(() -> ledger.record(
                StockMovement.inventoryAdjust(shortage, new BigDecimal("-1"), warehouse, null)));
        assertThat(status(shortage)).isEqualTo("WRITTEN_OFF");

        Long surplus = part("Нашедшийся стартер");
        intake(surplus, warehouse, "1");
        inTenant(() -> ledger.record(
                StockMovement.inventoryAdjust(surplus, BigDecimal.ONE, warehouse, null)));

        // Излишек не говорит, куда делась деталь: статус остаётся прежним.
        assertThat(status(surplus)).isEqualTo("IN_STOCK");
        assertThat(onHand(surplus)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("Перемещение переносит остаток между складами, общий не меняя")
    void moveKeepsTheTotal() {
        Long partId = part("Капот");
        intake(partId, warehouse, "3");

        inTenant(() -> ledger.record(
                StockMovement.move(partId, new BigDecimal("2"), warehouse, otherWarehouse, null)));

        assertThat(qtyAt(partId, warehouse)).isEqualByComparingTo("1");
        assertThat(qtyAt(partId, otherWarehouse)).isEqualByComparingTo("2");
        // Общий остаток — сумма по складам, и перемещение его не трогает.
        assertThat(onHand(partId)).isEqualByComparingTo("3");
        assertThat(status(partId)).isEqualTo("IN_STOCK");
    }

    @Test
    @DisplayName("Унести со склада, где ничего нет, нельзя")
    void cannotTakeFromAnEmptyWarehouse() {
        Long partId = part("Радиатор");
        intake(partId, warehouse, "1");

        // Молча пропустив, мы получили бы журнал, по которому со склада
        // уносили то, чего там нет.
        assertThatThrownBy(() -> inTenant(() -> ledger.record(
                StockMovement.sale(partId, BigDecimal.ONE, otherWarehouse, null))))
                .hasMessageContaining("списывать нечего");
    }

    @Test
    @DisplayName("Кэш сходится с журналом: сверка остатка пуста")
    void cacheAgreesWithTheJournal() {
        Long first = part("Генератор");
        intake(first, warehouse, "5");
        inTenant(() -> ledger.record(
                StockMovement.sale(first, new BigDecimal("2"), warehouse, null)));
        inTenant(() -> ledger.record(
                StockMovement.move(first, BigDecimal.ONE, warehouse, otherWarehouse, null)));

        Long second = part("Зеркало");
        intake(second, otherWarehouse, "1");
        inTenant(() -> ledger.record(StockMovement.writeOff(second, BigDecimal.ONE, otherWarehouse)));

        // Инвариант, ради которого сверка и заведена: остаток — агрегат
        // журнала, и кэш обязан ему соответствовать. Пока применял триггер,
        // разойтись было нельзя; теперь применяет код, и это надо проверять.
        assertThat(discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("Гонка за последней деталью: вторая попытка отказывает словами")
    void concurrentWriteOffsDoNotDriveTheStockNegative() throws Exception {
        Long partId = part("Стартер");
        intake(partId, warehouse, "1");

        // Двое кладовщиков списывают одну и ту же единицу. Предпроверка
        // остатка их не разводит — она читает то, чего через миллисекунду
        // уже нет, — поэтому сторожем обязана быть сама инструкция.
        java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);
        java.util.List<Throwable> failures = java.util.Collections.synchronizedList(
                new java.util.ArrayList<>());
        Runnable writeOff = () -> {
            try {
                together.await();
                inTenant(() -> ledger.record(
                        StockMovement.writeOff(partId, BigDecimal.ONE, warehouse)));
            } catch (Throwable e) {
                failures.add(e);
            }
        };

        Thread first = new Thread(writeOff);
        Thread second = new Thread(writeOff);
        first.start();
        second.start();
        first.join();
        second.join();

        assertThat(failures).as("списали дважды одну и ту же единицу").hasSize(1);
        // Схема отбила бы уход в минус и без этой проверки, но нарушением
        // CHECK — то есть пятисоткой, которую офлайн-очередь повторяет вечно.
        // Проигравший обязан получить объяснение, а не «внутреннюю ошибку».
        assertThat(failures.get(0))
                .as("гонка ответила поломкой сервера вместо отказа по правилу")
                .isInstanceOf(StockReservationRepository.InsufficientStockException.class)
                .hasMessageContaining("свободно 0");

        assertThat(qtyAt(partId, warehouse)).isEqualByComparingTo("0");
        // Движение проигравшего обязано откатиться вместе с его транзакцией:
        // иначе журнал скажет, что унесли две штуки, а раскладка — что одну.
        assertThat(discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("Пачка после массовой записи считает то же самое")
    void recomputeMatchesStepByStep() {
        Long partId = part("Рейка рулевая");
        intake(partId, warehouse, "4");
        inTenant(() -> ledger.record(
                StockMovement.sale(partId, BigDecimal.ONE, warehouse, null)));

        // Портим кэш, как это выглядело бы после переноса, писавшего движения
        // мимо приложения, и просим пересчитать.
        inTenant(() -> {
            jdbc.update("UPDATE part_stock SET qty = 0 WHERE part_id = ?", partId);
            jdbc.update("UPDATE part SET qty_on_hand = 0 WHERE id = ?", partId);
            return null;
        });

        inTenant(() -> ledger.recomputeAll());

        assertThat(qtyAt(partId, warehouse)).isEqualByComparingTo("3");
        assertThat(onHand(partId)).isEqualByComparingTo("3");
        assertThat(discrepancies()).isEmpty();
    }

    // ---------- фикстуры ----------

    private Long part(String title) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price) VALUES (1, ?, 5000)
                RETURNING id""", Long.class, title));
    }

    private void intake(Long partId, Long warehouseId, String qty) {
        inTenant(() -> ledger.record(
                StockMovement.intake(partId, new BigDecimal(qty), warehouseId, null)));
    }

    private BigDecimal qtyAt(Long partId, Long warehouseId) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT COALESCE(sum(qty), 0) FROM part_stock
                 WHERE part_id = ? AND warehouse_id = ?""",
                BigDecimal.class, partId, warehouseId));
    }

    private BigDecimal onHand(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT qty_on_hand FROM part WHERE id = ?", BigDecimal.class, partId));
    }

    private String status(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM part WHERE id = ?", String.class, partId));
    }

    private Long cellOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT storage_cell_id FROM part WHERE id = ?", Long.class, partId));
    }

    private List<java.util.Map<String, Object>> discrepancies() {
        return inTenant(() -> jdbc.queryForList("SELECT * FROM v_stock_discrepancy"));
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
