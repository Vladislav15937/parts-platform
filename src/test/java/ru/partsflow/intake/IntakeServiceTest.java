package ru.partsflow.intake;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.StockDocument;
import ru.partsflow.inventory.StockDocumentService;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Приёмка целиком: контейнер — машина — деталь — полка.
 *
 * <p>Главное, что здесь проверяется: цепочка не рвётся на незнакомом
 * наименовании. Приёмщик стоит на складе с деталью в руках, и отказ означал бы
 * деталь без карточки — невидимую для продажи.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class IntakeServiceTest extends PostgresTestBase {

    private static final String TENANT = "t_000049";

    @Autowired
    private IntakeService intake;

    @Autowired
    private StockDocumentService documents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouse;
    private Long cell;
    private Long brandId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        // Марки живут в общей схеме catalog и между тестами не сбрасываются.
        brandId = jdbc.queryForObject("""
                INSERT INTO catalog.brand (name, slug) VALUES ('Toyota', 'toyota-intake-test')
                ON CONFLICT (slug) DO UPDATE SET name = excluded.name
                RETURNING id""", Long.class);

        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            cell = jdbc.queryForObject(
                    "INSERT INTO storage_cell (warehouse_id, code) VALUES (?, 'А-01-1') RETURNING id",
                    Long.class, warehouse);
            return null;
        });
    }

    @Test
    @DisplayName("Контейнер, машина, деталь: сквозной путь до остатка на складе")
    void containerToShelf() {
        Supply supply = inTenant(() -> intake.registerSupply(
                Supply.SupplyKind.CONTAINER, "17", "Onteco 6", null));
        inTenant(() -> intake.markSupplyArrived(supply.getId(), LocalDate.now()));

        Donor donor = inTenant(() -> intake.registerDonor(camry(), supply.getId(), null));
        inTenant(() -> intake.startDismantling(donor.getId()));

        IntakeService.Receipt receipt = inTenant(() -> intake.receive(
                warehouse, supply.getId(), donor.getId(),
                List.of(item("фара левая", "Фара левая Camry V50", "8500"),
                        item("бампер передний", "Бампер передний Camry V50", "12000")),
                null));

        assertThat(receipt.document().getStatus()).isEqualTo(StockDocument.DocumentStatus.DONE);
        assertThat(receipt.document().getNumber()).as("документ без номера").isNotNull();
        assertThat(receipt.parts()).hasSize(2);

        // Остаток ведёт триггер по журналу, статус карточки — тоже.
        for (var part : receipt.parts()) {
            assertThat(qtyOf(part.getId())).isEqualByComparingTo("1");
            assertThat(statusOf(part.getId())).isEqualTo("IN_STOCK");
            assertThat(donorOf(part.getId())).isEqualTo(donor.getId());
            assertThat(supplyOf(part.getId())).isEqualTo(supply.getId());
        }
    }

    @Test
    @DisplayName("Контрактная деталь приходит поставкой без донора")
    void contractPartHasSupplyButNoDonor() {
        Supply supply = inTenant(() -> intake.registerSupply(
                Supply.SupplyKind.CONTAINER, "18", "Onteco 6", null));
        inTenant(() -> intake.markSupplyArrived(supply.getId(), LocalDate.now()));

        IntakeService.Receipt receipt = inTenant(() -> intake.receive(
                warehouse, supply.getId(), null,
                List.of(item("амортизатор", "Амортизатор передний", "4000")), null));

        Long partId = receipt.parts().get(0).getId();
        assertThat(donorOf(partId)).as("контрактной детали приписали донора").isNull();
        assertThat(supplyOf(partId))
                .as("происхождение товара без поставки не восстановить")
                .isEqualTo(supply.getId());
    }

    @Test
    @DisplayName("Незнакомое наименование приёмку не останавливает")
    void unknownNameDoesNotBlockIntake() {
        Supply supply = arrivedSupply("19");

        IntakeService.Receipt receipt = inTenant(() -> intake.receive(
                warehouse, supply.getId(), null,
                List.of(item("телевизор", "Панель рамки радиатора", "3000")), null));

        Long partId = receipt.parts().get(0).getId();
        assertThat(qtyOf(partId)).isEqualByComparingTo("1");

        // Наименование заведено и ждёт разбора, а деталь уже продаётся.
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT match_status FROM part_name WHERE lower(btrim(name)) = 'телевизор'""",
                String.class))).isEqualTo("UNMATCHED");
    }

    @Test
    @DisplayName("Повторная регистрация контейнера не создаёт второй")
    void supplyRegistrationIsIdempotent() {
        Supply first = inTenant(() -> intake.registerSupply(
                Supply.SupplyKind.CONTAINER, "20", "Onteco 6", null));
        Supply again = inTenant(() -> intake.registerSupply(
                Supply.SupplyKind.CONTAINER, " 20 ", "Другой поставщик", null));

        assertThat(again.getId()).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("Локация машины меняется, стадия разбора — нет")
    void locationIsIndependentOfDismantlingStage() {
        Donor donor = inTenant(() -> intake.registerDonor(camry(), null, null));

        inTenant(() -> intake.moveDonor(donor.getId(), "В Барнаул"));

        assertThat(inTenant(() -> jdbc.queryForMap(
                "SELECT location, status FROM donor WHERE id = ?", donor.getId())))
                .containsEntry("location", "В Барнаул")
                .containsEntry("status", "PURCHASED");
    }

    @Test
    @DisplayName("Черновик документа остаток не двигает")
    void draftDocumentDoesNotTouchStock() {
        Supply supply = arrivedSupply("21");
        IntakeService.Receipt receipt = inTenant(() -> intake.receive(
                warehouse, supply.getId(), null,
                List.of(item("крыло", "Крыло переднее правое", "5000")), null));
        Long partId = receipt.parts().get(0).getId();

        // Второй документ на ту же деталь оставляем черновиком.
        Long draftId = inTenant(() -> {
            StockDocument draft = StockDocument.intake(warehouse, supply.getId());
            draft.addLine(partId, new BigDecimal("5"), cell);
            return documents.save(draft).getId();
        });

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM stock_document WHERE id = ?", String.class, draftId)))
                .isEqualTo("DRAFT");
        assertThat(qtyOf(partId))
                .as("черновик изменил остаток — значит движения пишутся до проведения")
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Проведённый документ не отменяют, а исправляют встречным")
    void completedDocumentCannotBeCancelled() {
        Supply supply = arrivedSupply("22");
        IntakeService.Receipt receipt = inTenant(() -> intake.receive(
                warehouse, supply.getId(), null,
                List.of(item("капот", "Капот Camry V40", "9000")), null));

        assertThatThrownBy(() -> inTenant(() -> documents.cancel(receipt.document().getId())))
                .hasMessageContaining("журнал движений неизменяем");
    }

    @Test
    @DisplayName("Приёмка без позиций отбивается")
    void emptyIntakeIsRejected() {
        Supply supply = arrivedSupply("23");

        assertThatThrownBy(() -> inTenant(() ->
                intake.receive(warehouse, supply.getId(), null, List.of(), null)))
                .hasMessageContaining("без позиций");
    }

    @Test
    @DisplayName("Разбор нельзя начать дважды")
    void dismantlingStartsOnce() {
        Donor donor = inTenant(() -> intake.registerDonor(camry(), null, null));
        inTenant(() -> intake.startDismantling(donor.getId()));

        assertThatThrownBy(() -> inTenant(() -> intake.startDismantling(donor.getId())))
                .hasMessageContaining("состоянии DISMANTLING");
    }

    // ---------- фикстуры ----------

    private Donor camry() {
        Donor donor = new Donor(brandId);
        donor.setYear((short) 2006);
        donor.setVin("JTNBE46K90335" + System.nanoTime() % 100000);
        donor.setSteering(Donor.Steering.RIGHT);
        donor.setDriveType(Donor.DriveType.FWD);
        donor.setTransmissionType(Donor.TransmissionType.AT);
        donor.setTransmissionModel("U341E-01A");
        donor.setColor("Серебро");
        donor.setColorCode("1C0");
        return donor;
    }

    private Supply arrivedSupply(String number) {
        Supply supply = inTenant(() -> intake.registerSupply(
                Supply.SupplyKind.CONTAINER, number, "Onteco 6", null));
        inTenant(() -> intake.markSupplyArrived(supply.getId(), LocalDate.now()));
        return supply;
    }

    private IntakeService.ItemRequest item(String rawName, String title, String price) {
        return new IntakeService.ItemRequest(rawName, title, BigDecimal.ONE,
                new BigDecimal(price), new BigDecimal(price).multiply(new BigDecimal("0.4")), cell);
    }

    private BigDecimal qtyOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT qty_on_hand FROM part WHERE id = ?", BigDecimal.class, partId));
    }

    private String statusOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM part WHERE id = ?", String.class, partId));
    }

    private Long donorOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT donor_id FROM part WHERE id = ?", Long.class, partId));
    }

    private Long supplyOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT supply_id FROM part WHERE id = ?", Long.class, partId));
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
