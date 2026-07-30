package ru.partsflow.intake;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.PartCondition;
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
                List.of(item("фара левая", "8500"),
                        item("бампер передний", "12000")),
                null, uniqueRequestId()));

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
    @DisplayName("Заголовок собирается сам: вид детали, машина, стороны, состояние")
    void titleIsAssembledNotTyped() {
        Supply supply = arrivedSupply("30");
        Donor donor = inTenant(() -> intake.registerDonor(camry(), supply.getId(), null));

        IntakeService.Receipt receipt = inTenant(() -> intake.receive(
                warehouse, supply.getId(), donor.getId(),
                List.of(item("фара левая", "8500")), null, uniqueRequestId()));

        // Приёмщик заголовок не писал — он выбрал вид детали, машину и стороны.
        assertThat(receipt.parts().get(0).getTitle())
                .contains("фара левая")
                .contains("Toyota")
                .contains("перед. лев.")
                .contains("(б/у)");
    }

    @Test
    @DisplayName("Сопоставленное наименование даёт эталонный заголовок, а не местное написание")
    void matchedNameGivesCanonicalTitle() {
        // Эталон с синонимом: местное «запаска» должно превратиться
        // в «Запасное колесо», иначе однородности склада не будет.
        Long category = jdbc.queryForObject("""
                INSERT INTO catalog.part_category (name, slug, path)
                VALUES ('Тест заголовка', 'title-test', 'title_test')
                ON CONFLICT (path) DO UPDATE SET name = excluded.name
                RETURNING id""", Long.class);
        jdbc.update("""
                DELETE FROM catalog.part_kind WHERE category_id = ? AND name = 'Запасное колесо'""",
                category);
        jdbc.update("""
                INSERT INTO catalog.part_kind (category_id, name, synonyms)
                VALUES (?, 'Запасное колесо', ARRAY['запаска'])""", category);

        Supply supply = arrivedSupply("31");
        IntakeService.Receipt receipt = inTenant(() -> intake.receive(
                warehouse, supply.getId(), null,
                List.of(item("запаска", "2000")), null, uniqueRequestId()));

        assertThat(receipt.parts().get(0).getTitle())
                .as("в заголовок ушло местное написание вместо эталонного")
                .contains("Запасное колесо")
                .doesNotContain("запаска");
    }

    @Test
    @DisplayName("Номер производителя кладётся основным")
    void oemNumberIsStoredAsPrimary() {
        Supply supply = arrivedSupply("32");
        IntakeService.Receipt receipt = inTenant(() -> intake.receive(
                warehouse, supply.getId(), null,
                List.of(new IntakeService.ItemRequest("амортизатор", BigDecimal.ONE,
                        new BigDecimal("8500"), null, cell, null, null, null,
                        PartCondition.USED, null, "KYB", "334388", null, null)),
                null, uniqueRequestId()));

        Long partId = receipt.parts().get(0).getId();
        assertThat(inTenant(() -> jdbc.queryForMap(
                "SELECT raw_number, is_primary FROM part_oem WHERE part_id = ?", partId)))
                .containsEntry("raw_number", "334388")
                .containsEntry("is_primary", true);
    }

    @Test
    @DisplayName("Карточки возвращаются в порядке позиций запроса")
    void partsFollowRequestOrder() {
        Supply supply = arrivedSupply("42");

        IntakeService.Receipt receipt = inTenant(() -> intake.receive(
                warehouse, supply.getId(), null,
                List.of(item("фара левая", "8500"),
                        item("бампер передний", "12000"),
                        item("капот", "9000")),
                null, uniqueRequestId()));

        // Телефон привязывает снятые фотографии к деталям по номеру позиции —
        // другого способа у него нет, идентификаторы выдаёт сервер.
        // Перестановка означает снимки, уехавшие к чужим деталям.
        List<String> titles = receipt.parts().stream().map(ru.partsflow.inventory.Part::getTitle).toList();
        assertThat(titles.get(0)).startsWith("фара левая");
        assertThat(titles.get(1)).startsWith("бампер передний");
        assertThat(titles.get(2)).startsWith("капот");
    }

    @Test
    @DisplayName("Контрактная деталь приходит поставкой без донора")
    void contractPartHasSupplyButNoDonor() {
        Supply supply = inTenant(() -> intake.registerSupply(
                Supply.SupplyKind.CONTAINER, "18", "Onteco 6", null));
        inTenant(() -> intake.markSupplyArrived(supply.getId(), LocalDate.now()));

        IntakeService.Receipt receipt = inTenant(() -> intake.receive(
                warehouse, supply.getId(), null,
                List.of(item("амортизатор", "4000")), null, uniqueRequestId()));

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
                List.of(item("телевизор", "3000")), null, uniqueRequestId()));

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
                List.of(item("крыло", "5000")), null, uniqueRequestId()));
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
                List.of(item("капот", "9000")), null, uniqueRequestId()));

        assertThatThrownBy(() -> inTenant(() -> documents.cancel(receipt.document().getId())))
                .hasMessageContaining("журнал движений неизменяем");
    }

    @Test
    @DisplayName("Повтор с тем же ключом не создаёт вторую партию")
    void repeatWithSameRequestIdIsIdempotent() {
        Supply supply = arrivedSupply("40");
        String requestId = uniqueRequestId();

        IntakeService.Receipt first = inTenant(() -> intake.receive(
                warehouse, supply.getId(), null,
                List.of(item("фара левая", "8500")), null, requestId));

        // Телефон отправил партию, соединение оборвалось до ответа, очередь
        // повторила. Без ключа здесь появилась бы вторая деталь на складе.
        IntakeService.Receipt again = inTenant(() -> intake.receive(
                warehouse, supply.getId(), null,
                List.of(item("фара левая", "8500")), null, requestId));

        assertThat(again.document().getId()).isEqualTo(first.document().getId());
        assertThat(again.parts()).extracting(ru.partsflow.inventory.Part::getId)
                .containsExactlyElementsOf(
                        first.parts().stream().map(ru.partsflow.inventory.Part::getId).toList());

        Long partId = first.parts().get(0).getId();
        assertThat(qtyOf(partId)).as("повтор удвоил остаток").isEqualByComparingTo("1");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM stock_document WHERE client_request_id = ?",
                Integer.class, requestId))).isEqualTo(1);
    }

    @Test
    @DisplayName("Другой ключ — другая партия")
    void differentRequestIdCreatesSecondReceipt() {
        Supply supply = arrivedSupply("41");

        IntakeService.Receipt first = inTenant(() -> intake.receive(
                warehouse, supply.getId(), null,
                List.of(item("бампер передний", "12000")), null, uniqueRequestId()));
        IntakeService.Receipt second = inTenant(() -> intake.receive(
                warehouse, supply.getId(), null,
                List.of(item("бампер передний", "12000")), null, uniqueRequestId()));

        // Приёмщик действительно принял две одинаковые детали — это не повтор.
        assertThat(second.document().getId()).isNotEqualTo(first.document().getId());
        assertThat(second.parts().get(0).getId()).isNotEqualTo(first.parts().get(0).getId());
    }

    @Test
    @DisplayName("Приёмка без позиций отбивается")
    void emptyIntakeIsRejected() {
        Supply supply = arrivedSupply("23");

        assertThatThrownBy(() -> inTenant(() ->
                intake.receive(warehouse, supply.getId(), null, List.of(), null, uniqueRequestId())))
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

    /** Каждый вызов — свой ключ запроса: повторы проверяются отдельным тестом. */
    private String uniqueRequestId() {
        return java.util.UUID.randomUUID().toString();
    }

    /** Наименование карточки не передаём: его собирает PartTitleGenerator. */
    private IntakeService.ItemRequest item(String rawName, String price) {
        return new IntakeService.ItemRequest(rawName, BigDecimal.ONE,
                new BigDecimal(price), new BigDecimal(price).multiply(new BigDecimal("0.4")),
                cell, LateralSide.LEFT, LongitudinalSide.FRONT, null,
                PartCondition.USED, null, "KYB", null, null, null);
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
