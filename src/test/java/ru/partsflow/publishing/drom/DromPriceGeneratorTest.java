package ru.partsflow.publishing.drom;

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

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сборка прайса Дрома из настоящей схемы арендатора.
 *
 * <p>Проверяет то, чего не видно на объектах: свободный остаток суммируется
 * по складам и уменьшается резервом, проданное остаётся в прайсе недоступным,
 * а невыгружаемое не попадает вовсе.
 *
 * <p>Тесты делят одну схему и ничего не удаляют: журнал движений неизменяем
 * на уровне БД. Поэтому каждая проверка работает со своей позицией и ищет
 * её в прайсе по названию.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class DromPriceGeneratorTest extends PostgresTestBase {

    private static final String TENANT = "t_000046";

    @Autowired
    private DromPriceGenerator generator;

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
    void warehouses() {
        inTenant(() -> {
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
    @DisplayName("Позиция с остатком уходит в прайс доступной")
    void inStockPartIsAvailable() {
        String name = "Прайс: амортизатор передний левый";
        Long partId = part(name, new BigDecimal("8500"), true);
        intake(partId, warehouse, 1);

        assertThat(offerOf(name))
                .contains("<name>" + name + "</name>")
                // Цена — numeric(14,2), копейки сохраняются как есть.
                .contains("<price>8500.00</price>")
                .contains("<available>true</available>");
    }

    @Test
    @DisplayName("Свободный остаток складывается по всем складам")
    void availabilitySumsWarehouses() {
        String name = "Прайс: комплект колодок";
        Long partId = part(name, new BigDecimal("3000"), true);
        intake(partId, warehouse, 2);
        intake(partId, otherWarehouse, 3);

        assertThat(offerOf(name)).contains("<available>true</available>");
    }

    @Test
    @DisplayName("Резерв делает позицию недоступной: обещанное другому не рекламируем")
    void reservationMakesUnavailable() {
        String name = "Прайс: стартер 1NZ-FE";
        Long partId = part(name, new BigDecimal("5000"), true);
        intake(partId, warehouse, 1);
        inTenant(() -> jdbc.queryForObject("SELECT reserve_stock(?, ?, 1)",
                Object.class, partId, warehouse));

        assertThat(offerOf(name))
                .as("зарезервированная деталь ушла в прайс как доступная")
                .contains("<available>false</available>");
    }

    @Test
    @DisplayName("Проданное остаётся в прайсе, но недоступным")
    void soldStaysUnavailable() {
        String name = "Прайс: генератор 2AZ-FE";
        Long partId = part(name, new BigDecimal("7000"), true);
        intake(partId, warehouse, 1);
        sale(partId, warehouse, 1);

        // Убрать позицию из прайса нельзя: объявление у Дрома исчезнет
        // вместе с накопленными просмотрами.
        assertThat(offerOf(name)).contains("<available>false</available>");
    }

    @Test
    @DisplayName("Списанное в прайс не попадает")
    void writtenOffIsExcluded() {
        String name = "Прайс: радиатор кондиционера";
        Long partId = part(name, new BigDecimal("2000"), true);
        intake(partId, warehouse, 1);
        inTenant(() -> jdbc.update("""
                INSERT INTO stock_movement (part_id, movement_type, qty_delta, from_warehouse_id)
                VALUES (?, 'WRITE_OFF', -1, ?)""", partId, warehouse));

        assertThat(price()).doesNotContain(name);
    }

    @Test
    @DisplayName("Невыгружаемая позиция в прайс не попадает")
    void unpublishedIsExcluded() {
        String name = "Прайс: не для площадок";
        Long partId = part(name, new BigDecimal("100"), false);
        intake(partId, warehouse, 1);

        assertThat(price()).doesNotContain(name);
    }

    @Test
    @DisplayName("Позиция без цены в прайс не попадает")
    void withoutPriceIsExcluded() {
        String name = "Прайс: без цены";
        Long partId = part(name, null, true);
        intake(partId, warehouse, 1);

        assertThat(price()).doesNotContain(name);
    }

    @Test
    @DisplayName("Основной номер отделён от аналогов")
    void splitsPrimaryOemAndAnalogs() {
        String name = "Прайс: амортизатор с номерами";
        Long partId = part(name, new BigDecimal("8500"), true);
        intake(partId, warehouse, 1);
        inTenant(() -> {
            jdbc.update("INSERT INTO part_oem (part_id, raw_number, is_primary) "
                    + "VALUES (?, '334388', true)", partId);
            jdbc.update("INSERT INTO part_oem (part_id, raw_number) VALUES (?, '4853033281')", partId);
            jdbc.update("INSERT INTO part_oem (part_id, raw_number) VALUES (?, 'DS2130GS')", partId);
            return null;
        });

        String offer = offerOf(name);
        assertThat(offer).contains("<oem_number>334388</oem_number>");
        assertThat(offer).containsPattern("<analog_numbers>[^<]*4853033281[^<]*</analog_numbers>");
        assertThat(offer).containsPattern("<analog_numbers>[^<]*DS2130GS[^<]*</analog_numbers>");
    }

    @Test
    @DisplayName("Три оси стороны доходят до прайса")
    void writesThreeSideAxes() {
        String name = "Прайс: стойка передняя левая нижняя";
        Long partId = part(name, new BigDecimal("4000"), true);
        intake(partId, warehouse, 1);
        inTenant(() -> jdbc.update("""
                UPDATE part SET side_lr = 'LEFT', side_fr = 'FRONT', side_ud = 'LOWER',
                                manufacturer = 'KYB'
                 WHERE id = ?""", partId));

        assertThat(offerOf(name))
                .contains("<lr>лево</lr>")
                .contains("<fr>перед</fr>")
                .contains("<ud>низ</ud>")
                .contains("<manufacturer>KYB</manufacturer>");
    }

    @Test
    @DisplayName("Прайс собирается по своему арендатору, а не по public")
    void readsTenantSchema() {
        String name = "Прайс: проверка арендатора";
        Long partId = part(name, new BigDecimal("1000"), true);
        intake(partId, warehouse, 1);

        // Соединение берётся из сессии Hibernate: взятое напрямую из пула
        // смотрело бы в public, и прайс собрался бы пустым или не тем.
        assertThat(price()).contains(name).startsWith("<?xml");
    }

    // ---------- фикстуры ----------

    private Long part(String title, BigDecimal price, boolean published) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, cost_price, is_published)
                VALUES (1, ?, ?, 1000, ?) RETURNING id""",
                Long.class, title, price, published));
    }

    private void intake(Long partId, Long warehouseId, int qty) {
        inTenant(() -> jdbc.update("""
                INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id)
                VALUES (?, 'INTAKE', ?, ?)""", partId, qty, warehouseId));
    }

    private void sale(Long partId, Long warehouseId, int qty) {
        inTenant(() -> jdbc.update("""
                INSERT INTO stock_movement (part_id, movement_type, qty_delta, from_warehouse_id)
                VALUES (?, 'SALE', ?, ?)""", partId, -qty, warehouseId));
    }

    private String price() {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out);
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    /** Вырезает из прайса один {@code <offer>} по названию позиции. */
    private String offerOf(String name) {
        String xml = price();
        int nameAt = xml.indexOf("<name>" + name + "</name>");
        assertThat(nameAt).as("позиции «%s» нет в прайсе", name).isNotNegative();

        int start = xml.lastIndexOf("<offer>", nameAt);
        int end = xml.indexOf("</offer>", nameAt);
        return xml.substring(start, end);
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
