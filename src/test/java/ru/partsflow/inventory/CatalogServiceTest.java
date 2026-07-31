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

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Витрина склада: таблица товаров для владельца.
 *
 * <p>Проверяется то, что ломается тихо: подстановка сортировки, фильтр
 * «отсутствующие» и колонки складов. Ошибка в первой — это внедрение SQL,
 * во второй — склад, который выглядит вдвое больше, чем есть.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class CatalogServiceTest extends PostgresTestBase {

    private static final String TENANT = "t_000089";

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JdbcTemplate jdbc;

    /** Марка из поставляемого справочника: свою заводить нельзя, дерево из миграции. */
    private Long BRAND;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        BRAND = jdbc.queryForObject(
                "SELECT id FROM catalog.brand WHERE name = 'Toyota'", Long.class);
        // Журнал движений неизменяем — его нельзя чистить между тестами,
        // и это правильно: удаление движения означало бы остаток, взявшийся
        // ниоткуда. Поэтому каждый тест заводит свои позиции с уникальными
        // названиями и смотрит только на них.
        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Проданное по умолчанию не показывается, но по просьбе — да")
    void soldIsHiddenUnlessAsked() {
        Long inStock = part("Фара на складе", 1);
        Long sold = part("Фара проданная", 0);

        List<String> visible = titles(catalog(false, false));
        assertThat(visible).contains("Фара на складе");
        // Склад — это то, что лежит: проданное в общем списке делает его
        // вдвое длиннее и путает пересчёт.
        assertThat(visible)
                .as("проданное показано в складе по умолчанию")
                .doesNotContain("Фара проданная");

        assertThat(titles(catalog(false, true))).contains("Фара проданная");
        assertThat(inStock).isNotNull();
        assertThat(sold).isNotNull();
    }

    @Test
    @DisplayName("Остаток приходит по складам, а не одним числом")
    void stockIsPerWarehouse() {
        Long partId = part("Бампер", 3);

        var row = catalog(true, false).rows().stream()
                .filter(r -> r.id().equals(partId)).findFirst().orElseThrow();

        // У клиента складов несколько, и колонка на каждый — это то, как
        // на складе ищут: «на Ткацкой две, на дальнем ноль».
        assertThat(row.stock()).containsEntry(warehouseId, new java.math.BigDecimal("3.000"));
    }

    @Test
    @DisplayName("Неизвестная сортировка не уходит в SQL")
    void unknownSortIsIgnored() {
        part("Дверь", 1);

        // ORDER BY не принимает параметр, и подстановка пришедшего текста —
        // это внедрение. Неизвестное имя обязано молча стать сортировкой
        // по умолчанию, а не попасть в запрос.
        long before = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part", Long.class));

        var page = inTenant(() -> catalog.list(null, true, false, List.of(), null,
                "id; DROP TABLE part", false, 0, 50));

        // Запрос отработал, таблица на месте: неизвестное имя стало
        // сортировкой по умолчанию, а не уехало в SQL.
        assertThat(page.total()).isGreaterThan(0);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part", Long.class))).isEqualTo(before);
    }

    @Test
    @DisplayName("Выгрузка отдаёт то же, что видно на экране, и словами")
    void exportMatchesTheScreen() {
        part("Фара выгружаемая", 2);

        List<List<String>> rows = new java.util.ArrayList<>();
        inTenant(() -> {
            catalog.export(null, true, false, List.of(), null, "code", true,
                    catalog.warehouses(), rows::add);
            return null;
        });

        var header = CatalogService.exportHeader(inTenant(() -> catalog.warehouses()));
        // Столько же ячеек, сколько заголовков: разъехавшись, файл открывается
        // со сдвигом, и цена оказывается в колонке количества.
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0))
                .as("ячеек в строке не столько же, сколько заголовков — "
                        + "файл откроется со сдвигом колонок")
                .hasSameSizeAs(header);

        // Файл читают глазами в Excel: «REAR» там — утечка внутреннего
        // представления. В выгрузке прежней системы стоит «Задн.».
        assertThat(header).contains("Номер товара", "Запчасть", "Секция");
        assertThat(String.join(";", rows.get(0))).doesNotContain("REAR").doesNotContain("LEFT");
    }

    private CatalogService.Page catalog(boolean reserved, boolean missing) {
        return inTenant(() -> catalog.list(null, reserved, missing, List.of(), null, "code", true, 0, 50));
    }

    @Test
    void vehicleFilterFindsPartsByTheirDonor() {
        Long prius = donor("Prius", "NHW20", "1NZ-FXE");
        Long corolla = donor("Corolla", "ZZE120", "3ZZ-FE");
        onDonor("Фара Prius", prius);
        onDonor("Фара Corolla", corolla);

        assertThat(titles(byVehicle(BRAND, model("Prius"), null, null)))
                .containsExactly("Фара Prius");
    }

    /**
     * Переехавшему клиенту применимость никто не заполнял: у него на складе
     * двадцать шесть тысяч деталей с машиной и ноль строк применимости.
     * Подбор, смотрящий только в применимость, показал бы ему пустоту.
     */
    @Test
    void vehicleFilterAlsoUsesDeclaredApplicability() {
        Long corolla = donor("Corolla", "ZZE120", "3ZZ-FE");
        Long universal = onDonor("Стартер подходящий и к Vitz", corolla);
        Long vitz = model("Vitz");
        inTenant(() -> jdbc.update(
                "INSERT INTO part_applicability (part_id, brand_id, model_id) VALUES (?, ?, ?)",
                universal, BRAND, vitz));

        assertThat(titles(byVehicle(BRAND, vitz, null, null)))
                .containsExactly("Стартер подходящий и к Vitz");
    }

    /**
     * Кузов сравнивается по вхождению: у одной машины в поле «ACV40»,
     * у другой той же модели — «ACV40L», и точное равенство отсекло бы вторую.
     */
    @Test
    void bodyAndEngineNarrowByPart() {
        Long first = donor("Mark II", "GX100", "1G-FE");
        Long second = donor("Mark II", "JZX110 Tourer V", "1JZ-GTE");
        onDonor("Фара GX100", first);
        onDonor("Фара JZX110", second);
        Long markTwo = model("Mark II");

        assertThat(titles(byVehicle(BRAND, markTwo, "GX100", null)))
                .containsExactly("Фара GX100");
        assertThat(titles(byVehicle(BRAND, markTwo, null, "1JZ")))
                .containsExactly("Фара JZX110");
        // В поле у клиента «JZX110 Tourer V», а ищут по «JZX110»: точное
        // равенство не нашло бы ничего, а деталь подходит.
        assertThat(titles(byVehicle(BRAND, markTwo, "JZX110", null)))
                .containsExactly("Фара JZX110");
    }

    @Test
    void vehicleListCountsPartsOnTheShelf() {
        Long rav4 = donor("RAV4", "ACA31", "2AZ-FE");
        onDonor("Фара RAV4", rav4);
        onDonor("Бампер RAV4", rav4);

        assertThat(inTenant(catalog::vehicles))
                .filteredOn(option -> "ACA31".equals(option.body()))
                .singleElement()
                .satisfies(option -> {
                    assertThat(option.model()).isEqualTo("RAV4");
                    assertThat(option.parts()).isEqualTo(2);
                });
    }

    private CatalogService.Page byVehicle(Long brandId, Long modelId, String body, String engine) {
        return inTenant(() -> catalog.list(null, true, false, List.of(),
                new CatalogService.Vehicle(brandId, modelId, body, engine), "code", true, 0, 50));
    }

    private Long model(String name) {
        return jdbc.queryForObject(
                "SELECT id FROM catalog.model WHERE brand_id = ? AND name = ? LIMIT 1",
                Long.class, BRAND, name);
    }

    private Long donor(String modelName, String body, String engine) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO donor (brand_id, model_id, body_code, engine_code, status)
                VALUES (?, ?, ?, ?, 'DISMANTLING') RETURNING id""",
                Long.class, BRAND, model(modelName), body, engine));
    }

    private Long onDonor(String title, Long donorId) {
        Long id = part(title, 1);
        inTenant(() -> jdbc.update("UPDATE part SET donor_id = ? WHERE id = ?", donorId, id));
        return id;
    }

    private List<String> titles(CatalogService.Page page) {
        return page.rows().stream().map(CatalogService.Row::title).toList();
    }

    private Long part(String title, int qty) {
        return inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, ?, 1000)
                    RETURNING id""", Long.class, title);
            if (qty > 0) {
                jdbc.update("""
                        INSERT INTO stock_movement (part_id, movement_type, qty_delta,
                                                    to_warehouse_id)
                        VALUES (?, 'INTAKE', ?, ?)""", id, qty, warehouseId);
            }
            return id;
        });
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
