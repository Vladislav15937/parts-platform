package ru.partsflow.intake;

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

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Справочники для офлайн-приёмки.
 *
 * <p>Проверяется состав выгрузки: что попадает, что нет и в каком порядке.
 * Ошибка здесь тихая — приёмщик уходит к стеллажам с неполным справочником
 * и обнаруживает это, когда деталь уже в руках.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class IntakeReferenceServiceTest extends PostgresTestBase {

    private static final String TENANT = "t_000054";

    @Autowired
    private IntakeReferenceService reference;

    @Autowired
    private IntakeService intake;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long brandId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        // Эталонная марка из справочника, а не своя копия: «Toyota» с уникальным
        // slug у каждого теста накапливались в общей схеме catalog и ломали
        // соседей — отбор по марке считал их несколько вместо одной. CLAUDE.md.
        brandId = jdbc.queryForObject(
                "SELECT id FROM catalog.brand WHERE slug = 'toyota'", Long.class);

        inTenant(() -> {
            jdbc.update("DELETE FROM part_name");
            jdbc.update("DELETE FROM donor");
            jdbc.update("DELETE FROM supply");
            jdbc.update("DELETE FROM storage_cell");
            jdbc.update("DELETE FROM warehouse");
            jdbc.update("DELETE FROM branch");
            return null;
        });
    }

    @Test
    @DisplayName("Склады приходят с ячейками, одной записью на склад")
    void warehousesCarryTheirCells() {
        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            jdbc.update("INSERT INTO storage_cell (warehouse_id, code, zone) VALUES (?, 'А-01-1', 'A')",
                    warehouse);
            jdbc.update("INSERT INTO storage_cell (warehouse_id, code) VALUES (?, 'А-01-2')", warehouse);
            return null;
        });

        var loaded = inTenant(() -> reference.load());

        assertThat(loaded.warehouses()).singleElement().satisfies(w -> {
            assertThat(w.name()).isEqualTo("Ткацкая");
            assertThat(w.cells()).extracting(IntakeReferenceService.Cell::code)
                    .containsExactly("А-01-1", "А-01-2");
        });
    }

    @Test
    @DisplayName("Склад без ячеек не теряется и не порождает пустую ячейку")
    void warehouseWithoutCellsIsStillListed() {
        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            jdbc.update("INSERT INTO warehouse (branch_id, name) VALUES (?, 'Без ячеек')", branch);
            return null;
        });

        var loaded = inTenant(() -> reference.load());

        assertThat(loaded.warehouses()).singleElement().satisfies(w -> {
            assertThat(w.name()).isEqualTo("Без ячеек");
            assertThat(w.cells()).isEmpty();
        });
    }

    @Test
    @DisplayName("Отключённые склады и ячейки не выгружаются")
    void inactiveWarehousesAndCellsAreSkipped() {
        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            Long live = jdbc.queryForObject("""
                    INSERT INTO warehouse (branch_id, name) VALUES (?, 'Рабочий') RETURNING id""",
                    Long.class, branch);
            jdbc.update("INSERT INTO warehouse (branch_id, name, is_active) VALUES (?, 'Закрытый', false)",
                    branch);
            jdbc.update("INSERT INTO storage_cell (warehouse_id, code, is_active) "
                    + "VALUES (?, 'Б-01-1', false)", live);
            return null;
        });

        var loaded = inTenant(() -> reference.load());

        assertThat(loaded.warehouses()).extracting(IntakeReferenceService.Warehouse::name)
                .containsExactly("Рабочий");
        assertThat(loaded.warehouses().get(0).cells()).isEmpty();
    }

    @Test
    @DisplayName("Ожидаемые поставки нужны так же, как прибывшие")
    void expectedSuppliesAreIncluded() {
        inTenant(() -> {
            intake.registerSupply(Supply.SupplyKind.CONTAINER, "17", "Onteco 6", null);
            var arrived = intake.registerSupply(Supply.SupplyKind.CONTAINER, "18", "Onteco 6", null);
            intake.markSupplyArrived(arrived.getId(), java.time.LocalDate.now());
            return null;
        });

        var loaded = inTenant(() -> reference.load());

        // Контейнер заводят заранее, и принимать из него могут до того, как
        // кто-то отметит поставку прибывшей.
        assertThat(loaded.supplies()).extracting(IntakeReferenceService.SupplyRef::number)
                .containsExactlyInAnyOrder("17", "18");
    }

    @Test
    @DisplayName("Закрытая поставка в справочник не идёт")
    void closedSupplyIsSkipped() {
        inTenant(() -> {
            var supply = intake.registerSupply(Supply.SupplyKind.CONTAINER, "19", null, null);
            intake.markSupplyArrived(supply.getId(), java.time.LocalDate.now());
            jdbc.update("UPDATE supply SET status = 'CLOSED' WHERE id = ?", supply.getId());
            return null;
        });

        assertThat(inTenant(() -> reference.load()).supplies()).isEmpty();
    }

    @Test
    @DisplayName("В список машин попадают и разобранные, а купленные — нет")
    void donorsInAndAfterDismantling() {
        inTenant(() -> {
            var purchased = intake.registerDonor(donor("JT1"), null, null);
            var dismantling = intake.registerDonor(donor("JT2"), null, null);
            intake.startDismantling(dismantling.getId());
            var done = intake.registerDonor(donor("JT3"), null, null);
            intake.startDismantling(done.getId());
            jdbc.update("UPDATE donor SET status = 'DISMANTLED' WHERE id = ?", done.getId());
            assertThat(purchased.getStatus()).isEqualTo(Donor.DonorStatus.PURCHASED);
            return null;
        });

        var loaded = inTenant(() -> reference.load());

        // Разобранные остаются: снять забытую деталь через неделю после закрытия
        // разбора — обычное дело, и без машины в списке приёмщик заведёт деталь
        // без донора.
        assertThat(loaded.donors()).extracting(IntakeReferenceService.DonorRef::vin)
                .containsExactlyInAnyOrder("JT2", "JT3");
    }

    @Test
    @DisplayName("У машины видно марку и модель, а не только идентификаторы")
    void donorCarriesReadableVehicle() {
        // Эталонная модель из справочника, а не своя копия: тесту нужно лишь
        // читаемое «Camry» у машины, а сидовая Camry именно так и называется
        // и уже имеет алиас. Своя «Camry» без алиаса ломала VehicleWordsTest.
        Long modelId = jdbc.queryForObject(
                "SELECT id FROM catalog.model WHERE brand_id = ? AND slug = 'camry'",
                Long.class, brandId);

        inTenant(() -> {
            Donor donor = donor("JT9");
            donor.setModelId(modelId);
            var saved = intake.registerDonor(donor, null, null);
            intake.startDismantling(saved.getId());
            return null;
        });

        assertThat(inTenant(() -> reference.load()).donors()).singleElement().satisfies(d -> {
            assertThat(d.brand()).isEqualTo("Toyota");
            assertThat(d.model()).isEqualTo("Camry");
            assertThat(d.code()).isNotBlank();
        });
    }

    /**
     * Выбрать машину из списка приёмщик должен уметь, а марки, модели и года
     * для этого мало: у переехавшего клиента 200 машин из 442 совпадают
     * по этой тройке. Различают их номер клиента и его же заметка — и то
     * и другое лежит в базе с самого переезда.
     */
    @Test
    @DisplayName("Машину в справочнике подписывают номер клиента и его заметка")
    void donorCarriesClientCodeAndNote() {
        inTenant(() -> {
            var saved = intake.registerDonor(donor("JT8"), null, null);
            intake.startDismantling(saved.getId());
            jdbc.update("UPDATE donor SET legacy_code = '229', note = 'Синий маркер!!!' "
                    + "WHERE id = ?", saved.getId());
            return null;
        });

        assertThat(inTenant(() -> reference.load()).donors()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo("229");
            assertThat(d.note()).isEqualTo("Синий маркер!!!");
        });
    }

    @Test
    @DisplayName("Наименования идут по частоте: сверху то, что пишут каждый день")
    void partNamesOrderedByUsage() {
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO part_name (name, usage_count) VALUES ('редкое', 1), ('частое', 250)""");
            return null;
        });

        var loaded = inTenant(() -> reference.load());

        assertThat(loaded.partNames()).extracting(IntakeReferenceService.PartNameRef::name)
                .containsExactly("частое", "редкое");
    }

    @Test
    @DisplayName("У наименования видно, распознано ли оно")
    void partNameCarriesMatchedFlag() {
        // Эталон берём из поставляемого справочника, а не заводим свою «Фару»:
        // общий каталог один на все тесты, и вторая запись с тем же именем
        // роняет соседний тест, который ищет эталон по имени. Здесь важно лишь
        // то, что наименование с эталоном показано распознанным.
        Long kind = jdbc.queryForObject(
                "SELECT id FROM catalog.part_kind WHERE name = 'Фара'", Long.class);
        Long category = jdbc.queryForObject(
                "SELECT category_id FROM catalog.part_kind WHERE id = ?", Long.class, kind);

        inTenant(() -> {
            jdbc.update("INSERT INTO part_name (name) VALUES ('телевизор')");
            jdbc.update("""
                    INSERT INTO part_name (name, part_kind_id, category_id, match_status)
                    VALUES ('фара', ?, ?, 'AUTO')""", kind, category);
            return null;
        });

        var loaded = inTenant(() -> reference.load());

        assertThat(loaded.partNames())
                .filteredOn(n -> n.name().equals("фара"))
                .singleElement()
                .satisfies(n -> assertThat(n.matched()).isTrue());
        assertThat(loaded.partNames())
                .filteredOn(n -> n.name().equals("телевизор"))
                .singleElement()
                .satisfies(n -> assertThat(n.matched()).isFalse());
    }

    @Test
    @DisplayName("Момент выгрузки отдаётся: приёмщику надо знать свежесть справочника")
    void loadedAtIsReported() {
        var loaded = inTenant(() -> reference.load());

        assertThat(loaded.loadedAt()).isNotNull();
    }

    // ---------- фикстуры ----------

    private Donor donor(String vin) {
        Donor donor = new Donor(brandId);
        donor.setVin(vin);
        donor.setYear((short) 2006);
        return donor;
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
