package ru.partsflow.inventory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Сторожевой тест резервирования остатка.
 *
 * <p>Проверяет самую дорогую ошибку продаж: одна деталь, продана двум клиентам.
 * Она не воспроизводится последовательными вызовами — нужна настоящая гонка
 * двух соединений за одну строку {@code part_stock}, поэтому тест работает
 * на голом JDBC, а не через Hibernate: важно, что именно происходит в БД,
 * когда вторая транзакция ждёт на блокировке строки.
 *
 * <p>Если этот тест покраснел — резерв где-то стали считать в приложении.
 * Это не флейк.
 */
class StockReservationTest extends PostgresTestBase {

    private static final String TENANT = "t_000042";

    private long warehouseId;
    private long otherWarehouseId;
    private long cellId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void createWarehouse() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO branch (name) VALUES ('Основной филиал')");
            warehouseId = insertId(statement,
                    "INSERT INTO warehouse (branch_id, name) "
                            + "SELECT id, 'Склад выдачи' FROM branch LIMIT 1 RETURNING id");
            otherWarehouseId = insertId(statement,
                    "INSERT INTO warehouse (branch_id, name) "
                            + "SELECT id, 'Дальний склад' FROM branch LIMIT 1 RETURNING id");
            cellId = insertId(statement,
                    "INSERT INTO storage_cell (warehouse_id, code) "
                            + "VALUES (" + warehouseId + ", 'А-01-1') RETURNING id");
        }
    }

    @Nested
    @DisplayName("Гонка за последней деталью")
    class Race {

        @Test
        @DisplayName("Второй продавец не получает деталь, которую занял первый")
        void secondSellerLosesRaceForLastPart() throws Exception {
            long partId = partWithStock("Фара левая Camry V50", 1);

            try (Connection first = connect(); Connection second = connect()) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);

                // Первый успевает и держит строку до коммита.
                reserve(first, partId, warehouseId, 1);

                AtomicReference<Exception> failure = new AtomicReference<>();
                Thread rival = new Thread(() -> {
                    try {
                        reserve(second, partId, warehouseId, 1);
                    } catch (Exception e) {
                        failure.set(e);
                    }
                });
                rival.start();

                // Ждём, пока второй действительно встанет на блокировке строки:
                // без этого тест выродится в последовательные вызовы и перестанет
                // проверять то, ради чего написан.
                awaitBlockedOnReserve();
                first.commit();

                rival.join(10_000);
                assertThat(rival.isAlive()).as("второй продавец так и не разблокировался").isFalse();

                assertThat(failure.get())
                        .as("вторая транзакция увидела устаревший снимок и тоже зарезервировала")
                        .isNotNull()
                        .hasMessageContaining("Недостаточно свободного остатка");

                second.rollback();
            }

            assertThat(reserved(partId, warehouseId))
                    .as("резерв удвоился — деталь обещана двум клиентам")
                    .isEqualByComparingTo("1");
            assertThat(available(partId, warehouseId)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Резерв нельзя поставить, когда свободного остатка не осталось")
        void reserveRejectsWhenFreeStockExhausted() throws Exception {
            long partId = partWithStock("Бампер передний X-Trail", 2);

            reserveCommitted(partId, warehouseId, 2);

            assertThatThrownBy(() -> reserveCommitted(partId, warehouseId, 1))
                    .hasMessageContaining("Недостаточно свободного остатка");
            assertThat(reserved(partId, warehouseId)).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("Резерв на одном складе не расходует остаток другого")
        void reserveIsPerWarehouse() throws Exception {
            long partId = partWithStock("Дверь задняя левая Corolla", 1);
            addStock(partId, otherWarehouseId, 1);

            reserveCommitted(partId, warehouseId, 1);

            assertThat(available(partId, warehouseId)).isEqualByComparingTo("0");
            assertThat(available(partId, otherWarehouseId))
                    .as("резерв протёк на соседний склад")
                    .isEqualByComparingTo("1");
        }
    }

    @Nested
    @DisplayName("Снятие резерва")
    class Release {

        @Test
        @DisplayName("Снятие несуществующего резерва — ошибка, а не тихий пропуск")
        void releaseRejectsWhatWasNotReserved() throws Exception {
            long partId = partWithStock("Крыло переднее правое Rav4", 1);

            assertThatThrownBy(() -> releaseCommitted(partId, warehouseId, 1))
                    .hasMessageContaining("Нечего снимать с резерва");
        }

        @Test
        @DisplayName("Снятие возвращает деталь в свободный остаток")
        void releaseFreesStock() throws Exception {
            long partId = partWithStock("Капот Camry V40", 1);

            reserveCommitted(partId, warehouseId, 1);
            releaseCommitted(partId, warehouseId, 1);

            assertThat(reserved(partId, warehouseId)).isEqualByComparingTo("0");
            assertThat(available(partId, warehouseId)).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("Частичное снятие оставляет остаток резерва на месте")
        void releaseIsPartial() throws Exception {
            long partId = partWithStock("Комплект тормозных колодок", 3);

            reserveCommitted(partId, warehouseId, 3);
            releaseCommitted(partId, warehouseId, 1);

            assertThat(reserved(partId, warehouseId)).isEqualByComparingTo("2");
            assertThat(available(partId, warehouseId)).isEqualByComparingTo("1");
        }
    }

    @Nested
    @DisplayName("Порядок операций при выдаче")
    class IssueOrder {

        @Test
        @DisplayName("Списание до снятия резерва отбивается с объяснением порядка")
        void writeOffBeforeReleaseIsRejected() throws Exception {
            long partId = partWithStock("Стартер 1NZ-FE", 1);
            reserveCommitted(partId, warehouseId, 1);

            // Ровно та ошибка, от которой стоит триггер: сначала списали,
            // резерв снять забыли.
            assertThatThrownBy(() -> sale(partId, warehouseId, 1))
                    .hasMessageContaining("При выдаче резерв снимают до списания");

            assertThat(qty(partId, warehouseId))
                    .as("списание прошло, несмотря на отбой").isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("Снятие резерва перед списанием проходит и обнуляет остаток")
        void releaseThenWriteOffSucceeds() throws Exception {
            long partId = partWithStock("Генератор 2AZ-FE", 1);

            reserveCommitted(partId, warehouseId, 1);
            releaseCommitted(partId, warehouseId, 1);
            sale(partId, warehouseId, 1);

            assertThat(qty(partId, warehouseId)).isEqualByComparingTo("0");
            assertThat(reserved(partId, warehouseId)).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("Сверка резервов")
    class Reconciliation {

        @Test
        @DisplayName("Резерв мимо функций попадает в v_reservation_discrepancy")
        void discrepancySeesReserveMadeOutsideFunctions() throws Exception {
            long partId = partWithStock("Радиатор кондиционера", 1);
            reserveCommitted(partId, warehouseId, 1);

            // Резерв есть на складе, но ни одной позиции сделки под него нет —
            // именно так выглядит резерв, поставленный в обход сделки.
            assertThat(discrepancyCount(partId))
                    .as("сверка не заметила резерв без позиции сделки")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("Без резервов сверка пуста")
        void discrepancyIsEmptyWithoutReserves() throws Exception {
            long partId = partWithStock("Фара правая Camry V50", 1);

            assertThat(discrepancyCount(partId)).isZero();
        }
    }

    // ---------- фикстуры и доступ к БД ----------

    private long partWithStock(String title, int quantity) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            long partId = insertId(statement, """
                    INSERT INTO part (category_id, title, price, cost_price, status)
                    VALUES (1, '%s', 8500, 4000, 'IN_STOCK') RETURNING id""".formatted(title));
            statement.execute("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta,
                                                to_warehouse_id, to_cell_id)
                    VALUES (%d, 'INTAKE', %d, %d, %d)"""
                    .formatted(partId, quantity, warehouseId, cellId));
            return partId;
        }
    }

    private void addStock(long partId, long warehouse, int quantity) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id)
                    VALUES (%d, 'INTAKE', %d, %d)""".formatted(partId, quantity, warehouse));
        }
    }

    private void sale(long partId, long warehouse, int quantity) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta, from_warehouse_id)
                    VALUES (%d, 'SALE', -%d, %d)""".formatted(partId, quantity, warehouse));
        }
    }

    private void reserve(Connection connection, long partId, long warehouse, int quantity)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT reserve_stock(%d, %d, %d)"
                    .formatted(partId, warehouse, quantity));
        }
    }

    private void reserveCommitted(long partId, long warehouse, int quantity) throws SQLException {
        try (Connection connection = connect()) {
            reserve(connection, partId, warehouse, quantity);
        }
    }

    private void releaseCommitted(long partId, long warehouse, int quantity) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT release_stock(%d, %d, %d)"
                    .formatted(partId, warehouse, quantity));
        }
    }

    /**
     * Ждёт, пока конкурент встанет на блокировке строки {@code part_stock}.
     * Без этой синхронизации гонки в тесте не возникает.
     */
    private void awaitBlockedOnReserve() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (scalarInt("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query LIKE '%reserve_stock%'""") > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException(
                "Конкурирующий резерв не заблокировался: строка part_stock не блокируется, "
                        + "и гонка в этом тесте не воспроизводится");
    }

    private BigDecimal qty(long partId, long warehouse) throws SQLException {
        return scalarDecimal("SELECT qty FROM part_stock WHERE part_id = %d AND warehouse_id = %d"
                .formatted(partId, warehouse));
    }

    private BigDecimal reserved(long partId, long warehouse) throws SQLException {
        return scalarDecimal("SELECT qty_reserved FROM part_stock WHERE part_id = %d AND warehouse_id = %d"
                .formatted(partId, warehouse));
    }

    private BigDecimal available(long partId, long warehouse) throws SQLException {
        return scalarDecimal("SELECT qty_available FROM part_stock WHERE part_id = %d AND warehouse_id = %d"
                .formatted(partId, warehouse));
    }

    /** Сверка считается по конкретной детали: тесты делят одну схему арендатора. */
    private int discrepancyCount(long partId) throws SQLException {
        return scalarInt("SELECT count(*) FROM v_reservation_discrepancy WHERE part_id = %d"
                .formatted(partId));
    }

    private BigDecimal scalarDecimal(String sql) throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as("строка part_stock не найдена: %s", sql).isTrue();
            return rs.getBigDecimal(1);
        }
    }

    private int scalarInt(String sql) throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static long insertId(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + TENANT + ", catalog, public");
        }
        return connection;
    }
}
