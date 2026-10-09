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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ожидаемые позиции в поиске продавца: ветвь запроса, которую стережёт
 * частичный индекс {@code part_expected_ix} (задача 0261, {@code tenant/074}).
 *
 * <p>Поиск собран двумя ветвями {@code UNION ALL}: остаток со склада и
 * ожидаемые по поставке позиции. Вторая идёт по частичному индексу, и у неё
 * три допущения, каждое из которых молча ломается правкой в другом месте:
 * <ul>
 *   <li>ожидаемая позиция — это деталь <i>без раскладки с остатком</i>
 *       (проверка {@code NOT EXISTS} в ветви), иначе деталь с остатком
 *       показалась бы дважды;</li>
 *   <li>каждый способ спросить — код, слово в другом падеже, кросс-номер,
 *       «№ позиции» — доходит и до ожидаемой ветви: условие поиска строится
 *       для неё отдельно (сужено до ожидаемых в каждой ветке {@code UNION}),
 *       и забытая ветка не найдёт позицию, которую найдёт витрина;</li>
 *   <li>условие запроса влечёт предикат индекса: планировщик берёт частичный
 *       индекс, только если запрос повторяет его условие, а не взяв —
 *       читает {@code part} молча, и узнают об этом по времени ответа.</li>
 * </ul>
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class StockSearchExpectedTest extends PostgresTestBase {

    // Своя схема: номер записи в tenant_registry равен номеру схемы (см. корневой
    // CLAUDE.md), и разделить её с другим классом значит зависеть от порядка.
    private static final String TENANT = "t_000264";

    @Autowired
    private PartService parts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouseId;
    private Long supplyId;
    private Long expectedId;
    private String expectedCode;
    private long expectedNumber;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            jdbc.update("DELETE FROM part_wheel");
            jdbc.update("DELETE FROM part_oem");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM deal_item");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM part");
            jdbc.update("DELETE FROM supply");
            jdbc.update("DELETE FROM donor");
            jdbc.update("DELETE FROM warehouse");
            jdbc.update("DELETE FROM branch");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            supplyId = jdbc.queryForObject("""
                    INSERT INTO supply (kind, number, status, expected_on)
                    VALUES ('CONTAINER', 'EXP-1', 'EXPECTED', current_date + 10) RETURNING id""",
                    Long.class);
            expectedId = jdbc.queryForObject("""
                    INSERT INTO part (title, price, status, expected_origin, supply_id,
                                      quantity, category_id, is_published)
                    VALUES ('Фара ожидаемая Lexus RX 2010 прав.', 9000, 'DRAFT', true, ?, 3, 1, true)
                    RETURNING id""", Long.class, supplyId);
            jdbc.update("""
                    INSERT INTO part_oem (part_id, raw_number, normalized)
                    VALUES (?, '8114048A90', '8114048A90')""", expectedId);
            expectedCode = jdbc.queryForObject(
                    "SELECT public_code FROM part WHERE id = ?", String.class, expectedId);
            expectedNumber = jdbc.queryForObject(
                    "SELECT number FROM part WHERE id = ?", Long.class, expectedId);
            return null;
        });
    }

    @Test
    @DisplayName("Ожидаемая позиция отдаётся строкой «ожидается» с тем, что ещё можно отложить")
    void expectedPartIsOneExpectedRow() {
        PartService.StockSearch found = search("фара", 50);

        assertThat(found.rows()).hasSize(1);
        PartService.StockRow row = found.rows().get(0);
        assertThat(row.partId()).isEqualTo(expectedId);
        assertThat(row.expected()).isTrue();
        assertThat(row.qtyAvailable()).isEqualByComparingTo("3");
        assertThat(row.warehouseId()).isEqualTo(warehouseId);
        assertThat(found.total()).isEqualTo(1);
    }

    /**
     * Допущение «ожидаемая = без раскладки», сказанное тестом. Деталь по
     * ожидаемой поставке, у которой всё же есть строка раскладки с остатком, —
     * это деталь со склада: показать её нужно один раз, остатком, а не двумя
     * строками (прежний {@code UNION ALL} отдал бы обе). Система такого
     * состояния не создаёт — при приходе статус уже {@code IN_STOCK}, —
     * но запрос обязан ответить на него определённо, а не тем, что вышло.
     */
    @Test
    @DisplayName("Позиция из поставки с остатком на складе показывается один раз, остатком")
    void expectedWithShelfRowIsStockOnly() {
        inTenant(() -> jdbc.update("""
                INSERT INTO part_stock (part_id, warehouse_id, qty, qty_reserved)
                VALUES (?, ?, 2, 0)""", expectedId, warehouseId));

        assertThat(search("фара", 50).rows())
                .as("позиция показана дважды — остатком и как ожидаемая")
                .hasSize(1);
        PartService.StockSearch found = search("фара", 1);

        assertThat(found.rows()).hasSize(1);
        assertThat(found.rows().get(0).expected())
                .as("позиция с остатком показана как ожидаемая: склад выдан за поставку")
                .isFalse();
        assertThat(found.rows().get(0).qty()).isEqualByComparingTo("2");
        assertThat(found.total())
                .as("счёт видит позицию дважды — обе ветви считают её")
                .isEqualTo(1);
    }

    /**
     * Строка раскладки с нулём остаётся на складе после того, как увезли всё
     * (пустая строка не удаляется) — такая позиция остатка не даёт и,
     * если она ожидается, обязана показываться ожидаемой.
     */
    @Test
    @DisplayName("Пустая строка раскладки не делает ожидаемую позицию складской")
    void emptyShelfRowKeepsPartExpected() {
        inTenant(() -> jdbc.update("""
                INSERT INTO part_stock (part_id, warehouse_id, qty, qty_reserved)
                VALUES (?, ?, 0, 0)""", expectedId, warehouseId));

        PartService.StockSearch found = search("фара", 1);

        assertThat(found.rows()).hasSize(1);
        assertThat(found.rows().get(0).expected()).isTrue();
        assertThat(found.total()).isEqualTo(1);
    }

    /** Поставка, которая уже приехала или закрыта, ничего не обещает. */
    @Test
    @DisplayName("Приехавшая поставка не даёт ожидаемых строк")
    void arrivedSupplyGivesNothing() {
        inTenant(() -> jdbc.update("UPDATE supply SET status = 'ARRIVED' WHERE id = ?", supplyId));

        assertThat(search("фара", 50).rows()).isEmpty();
        assertThat(search("фара", 1).total()).isZero();
    }

    /**
     * Каждый способ спросить доходит до ожидаемой ветви и в выдаче, и в счёте.
     * Предел в одну строку заставляет считать число найденного отдельным
     * запросом: при {@code rows.size() < limit} счёт не делается вовсе.
     */
    @Test
    @DisplayName("Код, слово в другом падеже, кросс-номер и «№ позиции» находят ожидаемую")
    void everyWayOfAskingReachesTheExpectedBranch() {
        for (String query : List.of(
                expectedCode,                       // код с этикетки — ветка public_code
                expectedCode.substring(2, 8),       // кусок кода — та же ветка, подстрокой
                "фары",                             // морфология — ветка tsvector
                "ожидаемая",                        // подстрока заголовка
                "8114048A90",                       // кросс-номер — ветка part_oem
                "№ " + expectedNumber,              // номер позиции — своя ветка UNION
                "#" + expectedNumber)) {
            PartService.StockSearch found = search(query, 1);
            assertThat(found.rows())
                    .as("«%s» не находит ожидаемую позицию", query)
                    .extracting(PartService.StockRow::partId).containsExactly(expectedId);
            assertThat(found.total())
                    .as("счёт по «%s» считает не тем условием, что выдача", query)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Чужой запрос ожидаемую позицию не находит")
    void foreignQueryDoesNotReachExpected() {
        for (String query : List.of("бампер", "1A2B3C4D5E", "9999999999")) {
            assertThat(search(query, 50).rows())
                    .as("«%s» нашло лишнее", query).isEmpty();
            assertThat(search(query, 1).total()).as("счёт по «%s»", query).isZero();
        }
    }

    /** Размер колеса отбирает по полям, а не словами; ожидаемое колесо тоже. */
    @Test
    @DisplayName("Ожидаемое колесо находится по размеру, названному словами")
    void expectedWheelIsFoundBySize() {
        Long wheel = inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (title, price, status, expected_origin, supply_id,
                                      quantity, category_id, is_published, product_line)
                    VALUES ('Шина 225/55 R18 Bridgestone Blizzak', 9000, 'DRAFT', true, ?, 4, 1,
                            true, 'WHEEL') RETURNING id""", Long.class, supplyId);
            jdbc.update("""
                    INSERT INTO part_wheel (part_id, kind, tyre_width, tyre_height, diameter)
                    VALUES (?, 'TYRE', 225, 55, 18)""", id);
            return id;
        });

        PartService.StockSearch found = search("225 55 18", 1);

        assertThat(found.rows()).extracting(PartService.StockRow::partId).containsExactly(wheel);
        assertThat(found.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("Машина ожидаемой позиции попадает в списки значений отбора")
    void facetsCarryTheExpectedVehicle() {
        inTenant(() -> {
            Long brand = jdbc.queryForObject(
                    "SELECT id FROM catalog.brand WHERE slug = 'toyota'", Long.class);
            Long model = jdbc.queryForObject(
                    "SELECT id FROM catalog.model WHERE brand_id = ? ORDER BY id LIMIT 1",
                    Long.class, brand);
            Long donor = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, model_id, year, status)
                    VALUES (?, ?, 2010, 'DISMANTLING') RETURNING id""", Long.class, brand, model);
            return jdbc.update(
                    "UPDATE part SET donor_id = ?, quality_grade = 'NO_DEFECTS' WHERE id = ?",
                    donor, expectedId);
        });

        PartService.Facets facets = search("фара", 50).facets();

        assertThat(facets.vehicles())
                .as("марка ожидаемой позиции не попала в список — отобрать по ней нельзя")
                .extracting(PartService.VehicleOption::brand).contains("Toyota");
        assertThat(facets.grades()).contains("Без дефектов");
    }

    // ------------------------------------------------------- частичный индекс

    /**
     * Предикат запроса повторяет предикат индекса дословно — и в файле
     * changeset'а, и в каждой ветке условия поиска. Планировщик берёт
     * частичный индекс, только если условие запроса его влечёт; сменит код
     * условие — индекс перестанет использоваться молча.
     */
    @Test
    @DisplayName("Предикат запроса совпадает с предикатом индекса из changeset'а")
    void predicateMatchesTheChangeset() throws IOException {
        String sql = Files.readString(Path.of("db/changelog/tenant/074-part-expected-ix.sql"));
        Matcher m = Pattern.compile("CREATE INDEX part_expected_ix\\s+ON \\$\\{tenant.schema}.part"
                + " \\(id\\)\\s+WHERE ([^;]+);").matcher(sql);
        assertThat(m.find()).as("в changeset'е 074 не нашёл определение индекса").isTrue();

        assertThat(PartService.EXPECTED_PREDICATE)
                .as("предикат запроса разошёлся с предикатом индекса part_expected_ix")
                .isEqualTo(m.group(1).strip());

        // Условие самой ветви ожидаемых — то же, с псевдонимом таблицы.
        assertThat(PartService.EXPECTED_WHERE)
                .as("условие ветви ожидаемых разошлось с предикатом индекса")
                .contains(PartService.EXPECTED_PREDICATE
                        .replace("expected_origin", "p.expected_origin")
                        .replace("status", "p.status"));

        // Каждая ветка условия поиска по part сужена предикатом индекса (их
        // четыре: код, заголовок, морфология, номер позиции); ветка кросс-номеров
        // сужает через part и пишет то же условие со своим псевдонимом.
        String narrowed = PartService.textMatch(true, true);
        assertThat(narrowed.split(Pattern.quote(" AND " + PartService.EXPECTED_PREDICATE), -1).length - 1)
                .as("не каждая ветка поиска по part сужена предикатом индекса")
                .isEqualTo(4);
        assertThat(narrowed).contains("e.expected_origin AND e.status = 'DRAFT'");
    }

    /**
     * Индекс действительно берётся: план условия ожидаемой ветви называет
     * {@code part_expected_ix}. Обычный индекс по {@code status} снят внутри
     * транзакции, которая откатывается, — на пустой схеме планировщик иначе
     * вправе выбрать любой из равных по цене. Контроль чувствительности:
     * условие без {@code status} индекс не влечёт и его не берёт, то есть
     * тест умеет краснеть.
     */
    @Test
    @DisplayName("План ожидаемой ветви идёт по part_expected_ix")
    void expectedBranchUsesThePartialIndex() {
        String planned = plan("SELECT p.id FROM part p WHERE " + PartService.EXPECTED_WHERE);
        assertThat(planned)
                .as("ветвь ожидаемых не берёт частичный индекс — читает part целиком:\n" + planned)
                .contains("part_expected_ix");

        String control = plan("SELECT p.id FROM part p WHERE p.expected_origin");
        assertThat(control)
                .as("условие без status индекс не влечёт — тест не отличает взятый индекс от невзятого")
                .doesNotContain("part_expected_ix");
    }

    /** План запроса при запрещённом последовательном чтении; схема остаётся целой. */
    private String plan(String sql) {
        try {
            TenantContext.set(TENANT);
            return transactionTemplate.execute(status -> {
                jdbc.execute("SET LOCAL enable_seqscan = off");
                jdbc.execute("SET LOCAL enable_bitmapscan = off");
                jdbc.execute("DROP INDEX part_status_ix");
                String text = String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
                status.setRollbackOnly();
                return text;
            });
        } finally {
            TenantContext.clear();
        }
    }

    private PartService.StockSearch search(String query, int limit) {
        return inTenant(() -> parts.searchAvailable(query, limit));
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
