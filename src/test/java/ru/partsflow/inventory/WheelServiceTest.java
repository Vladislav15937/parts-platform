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
 * Шины и диски.
 *
 * <p>Устройство снято с живого кабинета Bazon: это не отдельная сущность,
 * а товар с типом «Шина» или «Диск», своим набором свойств и номером
 * комплекта — четыре шины под одним номером. Склад у них общий с запчастями.
 *
 * <p>Проверяется то, на чём это ломается тихо: комплект заводится одним
 * действием, но остаётся четырьмя карточками со своим остатком, а заголовок
 * собирается из свойств — написание руками даёт «195/65R15» и «195 65 15»
 * на соседних строках.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class WheelServiceTest extends PostgresTestBase {

    private static final String TENANT = "t_000088";

    @Autowired
    private WheelService wheels;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Комплект заводится одним действием, но остаётся четырьмя карточками")
    void setIsCreatedAsSeparateCards() {
        var created = inTenant(() -> wheels.createSet(tyre(), 4, warehouseId, null));

        // Заводят комплектом — повторять двенадцать полей четырежды никто
        // не станет. Но покупатель берёт и одну запаску, поэтому каждое
        // колесо остаётся карточкой со своим остатком.
        assertThat(created.partIds()).hasSize(4);
        assertThat(created.setNo()).isNotNull();

        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(DISTINCT part_id) FROM stock_movement
                 WHERE part_id = ANY (?)""",
                Long.class, (Object) created.partIds().toArray(Long[]::new))))
                .as("остаток появился не у каждого колеса — продать поштучно не выйдет")
                .isEqualTo(4);

        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM part_wheel WHERE set_no = ?""",
                Long.class, created.setNo())))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("Заголовок собирается из свойств, а не пишется руками")
    void titleIsComposed() {
        var created = inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));

        // Иначе у одного клиента появятся «195/65R15» и «195 65 15»
        // на соседних строках, и ни поиск, ни выгрузка их не свяжут.
        assertThat(created.title()).isEqualTo("Шина 195/65 R15 Goodyear EfficientGrip летняя");
    }

    /**
     * Свойства колеса доезжают до витрины вместе с остатком по складам.
     *
     * <p>Пока строка витрины несла шесть полей, сорок свойств лежали в базе
     * и увидеть их было негде — та же порода ошибки, что с витриной
     * запчастей: данные есть, колонки нет.
     */
    @Test
    @DisplayName("Витрина отдаёт свойства шины и остаток по складам")
    void listCarriesEveryProperty() {
        var created = inTenant(() -> wheels.createSet(tyre(), 2, warehouseId, null));

        var row = inTenant(() -> wheels.list(null, null, false, "set", true, 50)).stream()
                .filter(w -> created.partIds().contains(w.id()))
                .findFirst()
                .orElseThrow();

        assertThat(row.markingType()).isEqualTo("METRIC");
        assertThat(row.treadType()).isEqualTo("DIRECTIONAL");
        assertThat(row.speedIndex()).isEqualTo("H");
        assertThat(row.loadIndex()).isEqualTo(91);
        assertThat(row.runFlat()).isFalse();
        assertThat(row.lightTruck()).isFalse();
        assertThat(row.wearMm()).isEqualByComparingTo("5");
        assertThat(row.season()).isEqualTo("SUMMER");
        // Колонки складов не фиксированы: у одного клиента их два,
        // у другого пять, — поэтому остаток едет картой, а не числом.
        assertThat(row.stock()).containsEntry(warehouseId, new java.math.BigDecimal("1.000"));
        assertThat(row.published()).isTrue();
    }

    /**
     * Поиск, отбор и сортировка — то, без чего таблица на две сотни колёс
     * читается только глазами.
     */
    @Test
    @DisplayName("Поиск идёт по номеру и заголовку, отбор — по виду товара")
    void listIsSearchableAndFiltered() {
        var tyre = inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));
        var disc = inTenant(() -> wheels.createSet(disc(), 1, warehouseId, null));
        Long tyreId = tyre.partIds().get(0);
        Long discId = disc.partIds().get(0);

        // Размер попадает в поиск сам: он собран в заголовок, а покупатель
        // называет именно его.
        var found = inTenant(() -> wheels.list("195/65", null, false, "set", true, 500));
        assertThat(ids(found)).contains(tyreId).doesNotContain(discId);

        // «Покажи только диски» — первое, что делает кладовщик, когда ищет
        // комплект железа: половина колонок у второго вида пуста.
        var discs = inTenant(() -> wheels.list(null, "DISC", false, "set", true, 500));
        assertThat(ids(discs)).contains(discId).doesNotContain(tyreId);
        assertThat(discs).allSatisfy(row -> assertThat(row.kind()).isEqualTo("DISC"));

        var all = inTenant(() -> wheels.list(null, null, false, "set", true, 500));
        assertThat(ids(all)).contains(tyreId, discId);
    }

    @Test
    @DisplayName("Неизвестная сортировка не ломает выдачу и не подставляется в SQL")
    void unknownSortFallsBackToDefault() {
        var created = inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));

        // ORDER BY не принимает параметр, и подстановка пришедшего текста —
        // это внедрение SQL. Неизвестное имя молча становится умолчанием.
        assertThat(ids(inTenant(() -> wheels.list(
                null, null, false, "p.id; DROP TABLE part", true, 500))))
                .contains(created.partIds().get(0));
    }

    @Test
    @DisplayName("Неизвестный вид товара отвергается, а не ищется")
    void unknownKindIsRejected() {
        assertThatThrownBy(() -> inTenant(() ->
                wheels.list(null, "КОЛЕСО", false, "set", true, 50)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Выгрузка обязана совпасть с экраном: ради этой сверки её и качают.
     */
    @Test
    @DisplayName("Выгрузка отдаёт те же строки, что и страница, и столько же колонок")
    void exportMatchesTheScreen() {
        var tyres = inTenant(() -> wheels.createSet(tyre(), 2, warehouseId, null));
        var disc = inTenant(() -> wheels.createSet(disc(), 1, warehouseId, null));

        var found = inTenant(() -> catalog.warehouses());
        List<List<String>> rows = new java.util.ArrayList<>();
        inTenant(() -> {
            wheels.export(null, "TYRE", false, "set", true, found, rows::add);
            return null;
        });

        // Отбор у выгрузки и у страницы общий: диск в файл не попал,
        // а обе шины попали.
        var codes = rows.stream().map(row -> row.get(0)).toList();
        assertThat(codes).containsAll(codesOf(tyres.partIds()))
                .doesNotContainAnyElementsOf(codesOf(disc.partIds()));
        // Заголовок и строка обязаны быть одной длины: разъехавшись, файл
        // сдвигает значения на колонку, и цена приезжает в количество.
        assertThat(rows.get(0)).hasSameSizeAs(WheelService.exportHeader(found));
        assertThat(rows.get(0)).contains("Шина", "летняя", "Метрическая", "Направленный");
    }

    @Test
    @DisplayName("Одиночное колесо номера комплекта не получает")
    void singleWheelHasNoSetNumber() {
        // Запаска — это не комплект из одного, и номер у неё сбивал бы
        // с толку: по нему ищут остальные три.
        assertThat(inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null)).setNo())
                .isNull();
    }

    @Test
    @DisplayName("Комплект из сорока колёс — это опечатка, а не приёмка")
    void absurdSetIsRejected() {
        assertThatThrownBy(() -> inTenant(() -> wheels.createSet(tyre(), 40, warehouseId, null)))
                .hasMessageContaining("от одного до восьми");
    }

    @Test
    @DisplayName("Диск получает свой заголовок, а не шинный")
    void discTitleDiffers() {
        var disc = new WheelService.WheelRequest("DISC", new BigDecimal("15"),
                null, null, null, null, null, null, null,
                "Литой", new BigDecimal("6.0"), 45, "5x100", new BigDecimal("54.1"),
                "Toyota", null,
                null, null, null, null, null, null,
                new BigDecimal("6750"), null, null);

        assertThat(inTenant(() -> wheels.createSet(disc, 4, warehouseId, null)).title())
                .isEqualTo("Диск Литой 6x15 5x100 ET45 Toyota");
    }

    private static List<Long> ids(List<WheelService.WheelRow> rows) {
        return rows.stream().map(WheelService.WheelRow::id).toList();
    }

    private List<String> codesOf(List<Long> partIds) {
        return inTenant(() -> jdbc.queryForList(
                "SELECT public_code FROM part WHERE id = ANY (?)",
                String.class, (Object) partIds.toArray(Long[]::new)));
    }

    private WheelService.WheelRequest disc() {
        return new WheelService.WheelRequest("DISC", new BigDecimal("15"),
                null, null, null, null, null, null, null,
                "Литой", new BigDecimal("6.0"), 45, "5x100", new BigDecimal("54.1"),
                "Enkei", null,
                null, null, null, null, null, null,
                new BigDecimal("6750"), null, null);
    }

    private WheelService.WheelRequest tyre() {
        return new WheelService.WheelRequest("TYRE", new BigDecimal("15"),
                195, 65, "R", "Легковая", "SUMMER", new BigDecimal("5"), 2022,
                null, null, null, null, null,
                "Goodyear", "EfficientGrip",
                "METRIC", "DIRECTIONAL", false, false, "H", 91,
                new BigDecimal("3500"), null, null);
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
