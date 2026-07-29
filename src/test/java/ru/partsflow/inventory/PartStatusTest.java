package ru.partsflow.inventory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.support.PostgresTestBase;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Статус карточки идёт за журналом движений.
 *
 * <p>До миграции {@code 013} статус выставлялся один раз при приёмке и больше
 * не менялся ничем. Проданная деталь оставалась {@code IN_STOCK} и продолжала
 * показываться продавцу, а отчёт по окупаемости донора считал проданные
 * по {@code status = 'SOLD'} и всегда возвращал ноль.
 */
class PartStatusTest extends PostgresTestBase {

    private static final String TENANT = "t_000045";

    private long warehouseId;
    private long otherWarehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void createWarehouses() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO branch (name) VALUES ('Филиал')");
            warehouseId = id(s, "INSERT INTO warehouse (branch_id, name) "
                    + "SELECT id, 'Основной' FROM branch LIMIT 1 RETURNING id");
            otherWarehouseId = id(s, "INSERT INTO warehouse (branch_id, name) "
                    + "SELECT id, 'Второй' FROM branch LIMIT 1 RETURNING id");
        }
    }

    @Test
    @DisplayName("Карточка без движений остаётся черновиком")
    void partWithoutMovementsStaysDraft() throws SQLException {
        long partId = part("Фара левая Camry V50");

        assertThat(status(partId)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("Приёмка переводит черновик в наличие")
    void intakeMakesPartInStock() throws SQLException {
        long partId = part("Бампер передний X-Trail");

        intake(partId, warehouseId, 1);

        assertThat(status(partId)).isEqualTo("IN_STOCK");
    }

    @Test
    @DisplayName("Продажа последней единицы переводит карточку в проданное")
    void saleOfLastUnitMarksSold() throws SQLException {
        long partId = part("Стартер 1NZ-FE");
        intake(partId, warehouseId, 1);

        sale(partId, warehouseId, 1);

        assertThat(status(partId)).isEqualTo("SOLD");
    }

    @Test
    @DisplayName("Продажа части остатка наличие не снимает")
    void partialSaleKeepsPartInStock() throws SQLException {
        long partId = part("Комплект тормозных колодок");
        intake(partId, warehouseId, 3);

        sale(partId, warehouseId, 1);

        assertThat(status(partId)).isEqualTo("IN_STOCK");
    }

    @Test
    @DisplayName("Возврат от клиента возвращает проданное в продажу")
    void returnBringsSoldPartBack() throws SQLException {
        long partId = part("Генератор 2AZ-FE");
        intake(partId, warehouseId, 1);
        sale(partId, warehouseId, 1);
        assertThat(status(partId)).isEqualTo("SOLD");

        returned(partId, otherWarehouseId, 1);

        assertThat(status(partId))
                .as("вернувшаяся деталь осталась проданной и не продаётся снова")
                .isEqualTo("IN_STOCK");
    }

    @Test
    @DisplayName("Списание отличается от продажи")
    void writeOffIsNotSale() throws SQLException {
        long partId = part("Радиатор кондиционера");
        intake(partId, warehouseId, 1);

        writeOff(partId, warehouseId, 1);

        assertThat(status(partId)).isEqualTo("WRITTEN_OFF");
    }

    @Test
    @DisplayName("Перемещение между складами статус не меняет")
    void moveKeepsStatus() throws SQLException {
        long partId = part("Дверь задняя левая");
        intake(partId, warehouseId, 1);

        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta,
                                                from_warehouse_id, to_warehouse_id)
                    VALUES (%d, 'MOVE', 1, %d, %d)"""
                    .formatted(partId, warehouseId, otherWarehouseId));
        }

        assertThat(status(partId)).isEqualTo("IN_STOCK");
    }

    @Test
    @DisplayName("Резерв статус карточки не трогает")
    void reservationDoesNotChangeStatus() throws SQLException {
        long partId = part("Капот Camry V40");
        intake(partId, warehouseId, 1);

        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("SELECT reserve_stock(%d, %d, 1)".formatted(partId, warehouseId));
        }

        // Зарезервированная деталь физически на складе, и статус карточки
        // про наличие, а не про обещания: резерв виден в part_stock.
        assertThat(status(partId)).isEqualTo("IN_STOCK");
        assertThat(reserved(partId)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("RESERVED больше не допустимый статус карточки")
    void reservedStatusIsRejected() throws SQLException {
        long partId = part("Ступица передняя");

        assertThatThrownBy(() -> {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                s.execute("UPDATE part SET status = 'RESERVED' WHERE id = " + partId);
            }
        }).hasMessageContaining("part_status_ck");
    }

    @Test
    @DisplayName("Проданное уходит с экрана продавца, зарезервированное тоже")
    void sellerScreenShowsOnlyFreeStock() throws SQLException {
        long sold = part("Фара правая Camry V50");
        long reserved = part("Крыло переднее правое");
        long free = part("Поддомкратник");
        for (long partId : new long[]{sold, reserved, free}) {
            intake(partId, warehouseId, 1);
            applicableToBrand(partId, 1);
        }
        sale(sold, warehouseId, 1);
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("SELECT reserve_stock(%d, %d, 1)".formatted(reserved, warehouseId));
        }

        assertThat(applicableParts(1))
                .as("экран продавца показывает не только свободное")
                .containsExactly(free);
    }

    // ---------- фикстуры ----------

    private long part(String title) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            return id(s, """
                    INSERT INTO part (category_id, title, price, cost_price)
                    VALUES (1, '%s', 8500, 4000) RETURNING id""".formatted(title));
        }
    }

    private void applicableToBrand(long partId, long brandId) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO part_applicability (part_id, brand_id) VALUES (%d, %d)"
                    .formatted(partId, brandId));
        }
    }

    /** Тот же запрос, что в {@code PartRepository.findApplicableTo}. */
    private java.util.List<Long> applicableParts(long brandId) throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT DISTINCT p.id FROM part p
                     JOIN part_applicability a ON a.part_id = p.id
                     JOIN part_stock ps ON ps.part_id = p.id AND ps.qty_available > 0
                     WHERE a.brand_id = %d
                     ORDER BY p.id""".formatted(brandId))) {
            java.util.List<Long> found = new java.util.ArrayList<>();
            while (rs.next()) {
                found.add(rs.getLong(1));
            }
            return found;
        }
    }

    private void intake(long partId, long warehouse, int qty) throws SQLException {
        movement(partId, "INTAKE", qty, null, warehouse);
    }

    private void sale(long partId, long warehouse, int qty) throws SQLException {
        movement(partId, "SALE", -qty, warehouse, null);
    }

    private void writeOff(long partId, long warehouse, int qty) throws SQLException {
        movement(partId, "WRITE_OFF", -qty, warehouse, null);
    }

    private void returned(long partId, long warehouse, int qty) throws SQLException {
        movement(partId, "RETURN", qty, null, warehouse);
    }

    private void movement(long partId, String type, int delta, Long from, Long to)
            throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta,
                                                from_warehouse_id, to_warehouse_id)
                    VALUES (%d, '%s', %d, %s, %s)"""
                    .formatted(partId, type, delta,
                            from == null ? "NULL" : from, to == null ? "NULL" : to));
        }
    }

    private String status(long partId) throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT status FROM part WHERE id = " + partId)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private java.math.BigDecimal reserved(long partId) throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT sum(qty_reserved) FROM part_stock WHERE part_id = " + partId)) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    private static long id(Statement statement, String sql) throws SQLException {
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
