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
import ru.partsflow.inventory.StockMovement;
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
    private ru.partsflow.inventory.StockLedger ledger;

    @Autowired
    private DromPriceGenerator generator;

    @Autowired
    private ru.partsflow.publishing.MarketplaceAccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ru.partsflow.inventory.StockReservationRepository reservations;

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
        inTenant(() -> {
            reservations.reserve(partId, warehouse, java.math.BigDecimal.ONE);
            return null;
        });

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
    @DisplayName("Списанное в прайс не попадает, а в дельту попадает недоступным")
    void writtenOffIsExcludedFromPriceButSentInDelta() {
        String name = "Прайс: радиатор кондиционера";
        Long partId = part(name, new BigDecimal("2000"), true);
        intake(partId, warehouse, 1);
        inTenant(() -> ledger.record(StockMovement.writeOff(partId, java.math.BigDecimal.ONE, warehouse)));

        // Из полного прайса пропало — так площадка и узнаёт об удалении:
        // «проверяем, какие товары пропали, и убираем их с сайта».
        assertThat(price()).doesNotContain(name);

        // А дельта об исчезновении сообщить не умеет: сказать можно только
        // о том, что в неё попало. Отброшенное здесь висело бы на сайте
        // доступным до следующего полного забора, то есть до суток.
        assertThat(delta(partId))
                .contains(name)
                .contains("<available>false</available>");
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
    @DisplayName("Нулевая цена в прайс не идёт — это незаполненное поле")
    void zeroPriceIsExcluded() {
        String name = "Прайс: цена ноль";
        Long partId = part(name, BigDecimal.ZERO, true);
        intake(partId, warehouse, 1);

        // В выгрузке прежней системы ноль стоит там, где поле не заполняли:
        // у переехавшего клиента таких десять позиций из тридцати шести тысяч,
        // и уезжали они молча. А «0 ₽» в объявлении — публичное обещание
        // отдать деталь даром, за которым идут звонки, а по правилам площадки
        // и снятие всех объявлений разом.
        assertThat(price()).doesNotContain(name);
    }

    @Test
    @DisplayName("Основной номер отделён от аналогов")
    void splitsPrimaryOemAndAnalogs() {
        String name = "Прайс: амортизатор с номерами";
        Long partId = part(name, new BigDecimal("8500"), true);
        intake(partId, warehouse, 1);
        inTenant(() -> {
            jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary) "
                    + "VALUES (?, '334388', '334388', true)", partId);
            jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized) VALUES (?, '4853033281', '4853033281')", partId);
            jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized) VALUES (?, 'DS2130GS', 'DS2130GS')", partId);
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

    /**
     * Счётчик обещает ровно то, что уедет.
     *
     * <p>Он для того и заведён: владелец видит число до сохранения, и ноль
     * показывается ошибкой. Но считал он колёса, которых в прайсе запчастей
     * нет, — то есть врал в ту самую сторону, ради которой существует:
     * успокаивал числом. Поймано прогоном на арендаторе с комплектом резины.
     */
    @Test
    @DisplayName("Счётчик выгрузки считает то же, что уезжает в прайс")
    void counterMatchesTheFeed() {
        Long part = part("Прайс: запчасть для счётчика", new BigDecimal("2000"), true);
        intake(part, warehouse, 1);
        Long wheel = part("Прайс: шина для счётчика", new BigDecimal("3000"), true);
        inTenant(() -> jdbc.update("UPDATE part SET product_line = 'WHEEL' WHERE id = ?", wheel));
        intake(wheel, warehouse, 1);

        long counted = inTenant(() -> accounts.countMatching(
                null, null, null, null, null, false, null, false));
        long offers = price().split("<offer>", -1).length - 1;

        assertThat(counted)
                .as("счётчик обещает не то число, которое уедет площадке")
                .isEqualTo(offers);
    }

    // ---------- фикстуры ----------

    private Long part(String title, BigDecimal price, boolean published) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, cost_price, is_published)
                VALUES (1, ?, ?, 1000, ?) RETURNING id""",
                Long.class, title, price, published));
    }

    private void intake(Long partId, Long warehouseId, int qty) {
        inTenant(() -> ledger.record(StockMovement.intake(partId, java.math.BigDecimal.valueOf(qty), warehouseId, null)));
    }

    private void sale(Long partId, Long warehouseId, int qty) {
        inTenant(() -> ledger.record(StockMovement.sale(partId, java.math.BigDecimal.valueOf(qty), warehouseId, null)));
    }

    private String price() {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out);
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    private String delta(Long... partIds) {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeDelta(out, java.util.List.of(partIds));
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
