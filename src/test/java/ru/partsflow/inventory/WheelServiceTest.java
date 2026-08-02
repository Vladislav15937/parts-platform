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
import java.util.Map;
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

        var row = inTenant(() -> wheels.list(null, null, false, Map.of(), Map.of(), "set", true, 50)).stream()
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
        var found = inTenant(() -> wheels.list("195/65", null, false, Map.of(), Map.of(), "set", true, 500));
        assertThat(ids(found)).contains(tyreId).doesNotContain(discId);

        // «Покажи только диски» — первое, что делает кладовщик, когда ищет
        // комплект железа: половина колонок у второго вида пуста.
        var discs = inTenant(() -> wheels.list(null, "DISC", false, Map.of(), Map.of(), "set", true, 500));
        assertThat(ids(discs)).contains(discId).doesNotContain(tyreId);
        assertThat(discs).allSatisfy(row -> assertThat(row.kind()).isEqualTo("DISC"));

        var all = inTenant(() -> wheels.list(null, null, false, Map.of(), Map.of(), "set", true, 500));
        assertThat(ids(all)).contains(tyreId, discId);
    }

    @Test
    @DisplayName("Неизвестная сортировка не ломает выдачу и не подставляется в SQL")
    void unknownSortFallsBackToDefault() {
        var created = inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));

        // ORDER BY не принимает параметр, и подстановка пришедшего текста —
        // это внедрение SQL. Неизвестное имя молча становится умолчанием.
        assertThat(ids(inTenant(() -> wheels.list(
                null, null, false, Map.of(), Map.of(), "p.id; DROP TABLE part", true, 500))))
                .contains(created.partIds().get(0));
    }

    @Test
    @DisplayName("Неизвестный вид товара отвергается, а не ищется")
    void unknownKindIsRejected() {
        assertThatThrownBy(() -> inTenant(() ->
                wheels.list(null, "КОЛЕСО", false, Map.of(), Map.of(), "set", true, 50)))
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
            wheels.export(null, "TYRE", false, Map.of(), Map.of(), "set", true, found, rows::add);
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
                null, null, "Toyota", null,
                null, null, null, null, null, null,
                new BigDecimal("6750"), null, null);

        assertThat(inTenant(() -> wheels.createSet(disc, 4, warehouseId, null)).title())
                .isEqualTo("Диск Литой 6x15 5x100 ET45 Toyota");
    }

    /**
     * Отбор колонками, как в кабинете: значение выбирается из списка того,
     * что есть на складе, а не набирается руками.
     */
    @Test
    @DisplayName("Колонка отбирается по значению, показанному на экране")
    void columnsAreFilteredByShownValue() {
        var tyre = inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));
        var disc = inTenant(() -> wheels.createSet(disc(), 1, warehouseId, null));

        // «Шина», а не TYRE, и «летняя», а не SUMMER: владелец выбирает
        // из списка то, что видит в таблице, и отбор обязан понимать ровно это.
        assertThat(ids(inTenant(() -> wheels.list(null, null, false,
                Map.of("kind", "Шина"), Map.of(), "set", true, 500))))
                .contains(tyre.partIds().get(0)).doesNotContain(disc.partIds().get(0));
        assertThat(ids(inTenant(() -> wheels.list(null, null, false,
                Map.of("season", "летняя"), Map.of(), "set", true, 500))))
                .contains(tyre.partIds().get(0));
    }

    @Test
    @DisplayName("Число в отборе сравнивается так же, как показано: 15, а не 15.0")
    void numbersAreComparedAsShown() {
        var created = inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));

        // В базе диаметр лежит как 15.0, в таблице стоит «15», и выбранное
        // из списка «15» обязано находить эту шину.
        assertThat(ids(inTenant(() -> wheels.list(null, null, false,
                Map.of("diameter", "15"), Map.of(), "set", true, 500))))
                .contains(created.partIds().get(0));
    }

    @Test
    @DisplayName("Список значений колонки — то, что есть на складе, и в том же виде")
    void valuesComeFromTheStock() {
        inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));
        inTenant(() -> wheels.createSet(disc(), 1, warehouseId, null));

        assertThat(inTenant(() -> wheels.values("kind"))).contains("Шина", "Диск");
        assertThat(inTenant(() -> wheels.values("diameter"))).contains("15");
        assertThat(inTenant(() -> wheels.values("season"))).contains("летняя");
        // Пустых значений в списке нет: «— пусто —» это отдельный пункт,
        // а пустая строка среди марок выглядела бы промахом мыши.
        assertThat(inTenant(() -> wheels.values("tyreBrand"))).doesNotContain("", (String) null);
    }

    @Test
    @DisplayName("«Пусто» и «не пусто» — тоже отбор")
    void emptinessIsAFilter() {
        var tyre = inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));
        var disc = inTenant(() -> wheels.createSet(disc(), 1, warehouseId, null));

        // У диска сезона нет вовсе, у шины он летний. Вопрос «где не заполнено»
        // задают, когда разгребают склад после переезда.
        assertThat(ids(inTenant(() -> wheels.list(null, null, false,
                Map.of("season", WheelService.EMPTY), Map.of(), "set", true, 500))))
                .contains(disc.partIds().get(0)).doesNotContain(tyre.partIds().get(0));
        assertThat(ids(inTenant(() -> wheels.list(null, null, false,
                Map.of("season", WheelService.PRESENT), Map.of(), "set", true, 500))))
                .contains(tyre.partIds().get(0)).doesNotContain(disc.partIds().get(0));
    }

    @Test
    @DisplayName("Несколько фильтров складываются, а не заменяют друг друга")
    void filtersAddUp() {
        var tyre = inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));
        var disc = inTenant(() -> wheels.createSet(disc(), 1, warehouseId, null));

        // Диаметр у обоих пятнадцатый, вид разный: вместе они обязаны
        // оставить одну строку.
        var found = ids(inTenant(() -> wheels.list(null, null, false,
                Map.of("diameter", "15", "kind", "Диск"), Map.of(), "set", true, 500)));
        assertThat(found).contains(disc.partIds().get(0))
                .doesNotContain(tyre.partIds().get(0));
    }

    @Test
    @DisplayName("Неизвестная колонка отвергается, а не подставляется в SQL")
    void unknownColumnIsRejected() {
        assertThatThrownBy(() -> inTenant(() -> wheels.list(null, null, false,
                Map.of("p.price = 0 OR true", "1"), Map.of(), "set", true, 50)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTenant(() -> wheels.values("p.price; DROP TABLE part")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Отбор колонками действует и на выгрузку")
    void exportUsesTheSameColumns() {
        var tyres = inTenant(() -> wheels.createSet(tyre(), 2, warehouseId, null));
        var disc = inTenant(() -> wheels.createSet(disc(), 1, warehouseId, null));

        var found = inTenant(() -> catalog.warehouses());
        List<List<String>> rows = new java.util.ArrayList<>();
        inTenant(() -> {
            wheels.export(null, null, false, Map.of("kind", "Шина"), Map.of(), "set", true, found, rows::add);
            return null;
        });

        // Скачанный файл обязан совпасть с экраном: диск под отбор шины
        // не подходит и в файл не попал.
        var codes = rows.stream().map(row -> row.get(0)).toList();
        assertThat(codes).containsAll(codesOf(tyres.partIds()))
                .doesNotContainAnyElementsOf(codesOf(disc.partIds()));
        assertThat(rows).allSatisfy(row -> assertThat(row.get(2)).isEqualTo("Шина"));
    }

    /**
     * Колесо в сборе — третий вид товара: шина, уже надетая на диск.
     *
     * <p>Снято с кабинета клиента: комплект №181, четыре колеса 225/55 R18
     * Dunlop на дисках Rays. Продают их так же поштучно, и свойства заполнены
     * оба набора сразу.
     */
    @Test
    @DisplayName("Колесо в сборе несёт свойства и шины, и диска")
    void assemblyCarriesBothSides() {
        var created = inTenant(() -> wheels.createSet(assembly(), 4, warehouseId, null));

        var row = inTenant(() -> wheels.list(null, null, false, Map.of(), Map.of(), "set", true, 500))
                .stream().filter(w -> created.partIds().contains(w.id())).findFirst().orElseThrow();

        assertThat(row.kind()).isEqualTo("ASSEMBLY");
        // Производители разные, и это главное: одним полем такое не записать.
        assertThat(row.brand()).isEqualTo("Dunlop");
        assertThat(row.discBrand()).isEqualTo("Mitsubishi");
        assertThat(row.model()).isEqualTo("Winter Maxx SJ8");
        assertThat(row.discModel()).isEqualTo("Rays");
        assertThat(row.tyreWidth()).isEqualTo(225);
        assertThat(row.boltPattern()).isEqualTo("5x114.3");
    }

    @Test
    @DisplayName("Заголовок сборки называет и шину, и диск")
    void assemblyTitleNamesBothSides() {
        // Покупатель ищет колесо и по размеру шины, и по сверловке:
        // «225/55 R18 на 5x114.3».
        assertThat(WheelService.titleOf(assembly()))
                .isEqualTo("Колесо 225/55 R18 Dunlop Winter Maxx SJ8 зимняя (липучка),"
                        + " диск Литой 7x18 5x114.3 ET38 Mitsubishi Rays");
    }

    @Test
    @DisplayName("Зимняя различает шипы и липучку")
    void winterKindIsNamed() {
        // Разница не косметическая: шипы и липучка ездят по-разному и стоят
        // по-разному, а покупатель спрашивает именно это.
        assertThat(WheelService.seasonName("WINTER_STUDDED")).isEqualTo("зимняя (шипы)");
        assertThat(WheelService.seasonName("WINTER_FRICTION")).isEqualTo("зимняя (липучка)");
        // Прежнее «зимняя» остаётся: у заведённых раньше шин неизвестно,
        // какие они, и додумывать за приёмщика нельзя.
        assertThat(WheelService.seasonName("WINTER")).isEqualTo("зимняя");
    }

    @Test
    @DisplayName("Сборка отбирается и попадает в список видов товара")
    void assemblyIsFilterable() {
        var assembly = inTenant(() -> wheels.createSet(assembly(), 1, warehouseId, null));
        var tyre = inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));

        assertThat(inTenant(() -> wheels.values("kind"))).contains("Колесо");
        assertThat(ids(inTenant(() -> wheels.list(null, null, false,
                Map.of("kind", "Колесо"), Map.of(), "set", true, 500))))
                .contains(assembly.partIds().get(0))
                .doesNotContain(tyre.partIds().get(0));

        // Отбор по производителю диска обязан смотреть в дисковое поле:
        // у сборки шина Dunlop, а диск Mitsubishi, и перепутав их, отбор
        // «диски Mitsubishi» не найдёт ни одного.
        assertThat(inTenant(() -> wheels.values("discBrand"))).contains("Mitsubishi");
        assertThat(ids(inTenant(() -> wheels.list(null, null, false,
                Map.of("discBrand", "Mitsubishi"), Map.of(), "set", true, 500))))
                .contains(assembly.partIds().get(0));
        assertThat(ids(inTenant(() -> wheels.list(null, null, false,
                Map.of("discBrand", "Dunlop"), Map.of(), "set", true, 500))))
                .doesNotContain(assembly.partIds().get(0));
    }

    /**
     * Производитель шины и производитель диска — разные колонки при разных
     * полях: у сборки в них стоят разные значения, и перепутать их значит
     * отдать покупателю не то, что он подбирал.
     */
    @Test
    @DisplayName("В выгрузке производители шины и диска стоят каждый в своей колонке")
    void exportKeepsBrandsApart() {
        inTenant(() -> wheels.createSet(assembly(), 1, warehouseId, null));

        var found = inTenant(() -> catalog.warehouses());
        List<List<String>> rows = new java.util.ArrayList<>();
        inTenant(() -> {
            wheels.export(null, "ASSEMBLY", false, Map.of(), Map.of(), "set", true, found, rows::add);
            return null;
        });

        var header = WheelService.exportHeader(found);
        var row = rows.get(0);
        assertThat(row.get(header.indexOf("Производитель шины"))).isEqualTo("Dunlop");
        assertThat(row.get(header.indexOf("Производитель диска"))).isEqualTo("Mitsubishi");
        assertThat(row.get(header.indexOf("Товар"))).isEqualTo("Колесо");
    }

    private WheelService.WheelRequest assembly() {
        return new WheelService.WheelRequest("ASSEMBLY", new BigDecimal("18"),
                225, 55, "R", "Легковая", "WINTER_FRICTION", new BigDecimal("4"), 2014,
                "Литой", new BigDecimal("7.0"), 38, "5x114.3", new BigDecimal("66"),
                "Dunlop", "Winter Maxx SJ8", "Mitsubishi", "Rays",
                "METRIC", "STANDARD", false, false, "Q", 98,
                new BigDecimal("10000"), null, null);
    }

    /**
     * Поиск по размеру — то, чем колесо ищут на самом деле: покупатель звонит
     * и называет размер, а не номер товара.
     */
    @Test
    @DisplayName("Шина находится по размеру, как его называют вслух")
    void tyreIsFoundBySize() {
        var tyre = inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));
        Long id = tyre.partIds().get(0);

        // В заголовке стоит «195/65 R15», а говорят по-разному — и все
        // написания обязаны находить одну и ту же шину.
        for (String asked : new String[]{"195/65 R15", "195 65 15", "195/65R15"}) {
            assertThat(ids(inTenant(() -> wheels.list(asked, null, false, Map.of(), Map.of(),
                    "set", true, 500)))).as(asked).contains(id);
        }
        // А чужой размер её находить не должен.
        assertThat(ids(inTenant(() -> wheels.list("205/55 R16", null, false, Map.of(), Map.of(),
                "set", true, 500)))).doesNotContain(id);
    }

    @Test
    @DisplayName("Диск находится по сверловке и по своему размеру")
    void discIsFoundBySize() {
        var disc = inTenant(() -> wheels.createSet(disc(), 1, warehouseId, null));
        var other = inTenant(() -> wheels.createSet(disc("5x114.3"), 1, warehouseId, null));
        Long id = disc.partIds().get(0);

        // «Нужны диски пять на сто» — и в русской раскладке тоже.
        assertThat(ids(inTenant(() -> wheels.list("5x100", null, false, Map.of(), Map.of(),
                "set", true, 500)))).contains(id).doesNotContain(other.partIds().get(0));
        assertThat(ids(inTenant(() -> wheels.list("5х100", null, false, Map.of(), Map.of(),
                "set", true, 500)))).contains(id);
        // Размер самого диска: «6x15» из объявления и «15x6» с диска.
        assertThat(ids(inTenant(() -> wheels.list("6x15", null, false, Map.of(), Map.of(),
                "set", true, 500)))).contains(id);
        assertThat(ids(inTenant(() -> wheels.list("15x6", null, false, Map.of(), Map.of(),
                "set", true, 500)))).contains(id);
    }

    @Test
    @DisplayName("Колесо в сборе находится и по резине, и по железу")
    void assemblyIsFoundByBothSizes() {
        var assembly = inTenant(() -> wheels.createSet(assembly(), 1, warehouseId, null));
        Long id = assembly.partIds().get(0);

        assertThat(ids(inTenant(() -> wheels.list("225/55 R18", null, false, Map.of(), Map.of(),
                "set", true, 500)))).contains(id);
        assertThat(ids(inTenant(() -> wheels.list("5x114.3", null, false, Map.of(), Map.of(),
                "set", true, 500)))).contains(id);
        // И по тому и другому разом — это уточнение, а не два разных запроса.
        assertThat(ids(inTenant(() -> wheels.list("225/55 R18 5x114.3", null, false, Map.of(), Map.of(),
                "set", true, 500)))).contains(id);
    }

    @Test
    @DisplayName("Слова ищутся словами, а номер — номером")
    void wordsAndCodesStillWork() {
        var tyre = inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));
        Long id = tyre.partIds().get(0);

        assertThat(ids(inTenant(() -> wheels.list("Goodyear", null, false, Map.of(), Map.of(),
                "set", true, 500)))).contains(id);
        // Размер и слово вместе: сначала отбор по полям, потом текст.
        assertThat(ids(inTenant(() -> wheels.list("195/65 R15 Goodyear", null, false, Map.of(), Map.of(),
                "set", true, 500)))).contains(id);
        assertThat(ids(inTenant(() -> wheels.list("195/65 R15 Nokian", null, false, Map.of(), Map.of(),
                "set", true, 500)))).doesNotContain(id);
        assertThat(ids(inTenant(() -> wheels.list(codesOf(List.of(id)).get(0), null, false,
                Map.of(), Map.of(), "set", true, 500)))).contains(id);
    }

    /**
     * Вбитое в колонку слово ищется вхождением, а не точным равенством.
     *
     * <p>Владелец набирает «Nok», а не выбирает «Nokian» из списка: список
     * нужен, когда значений десяток, а когда их сотня — быстрее напечатать
     * три буквы. Точным равенством такое не нашло бы ничего.
     */
    @Test
    @DisplayName("Слово, вбитое в колонку, ищется вхождением")
    void typedWordIsSearchedByPart() {
        var tyre = inTenant(() -> wheels.createSet(tyre(), 1, warehouseId, null));
        var disc = inTenant(() -> wheels.createSet(disc(), 1, warehouseId, null));
        Long id = tyre.partIds().get(0);

        assertThat(ids(inTenant(() -> wheels.list(null, null, false, Map.of(),
                Map.of("tyreBrand", "good"), "set", true, 500))))
                .as("регистр не должен мешать: набирают как придётся")
                .contains(id).doesNotContain(disc.partIds().get(0));

        // Выбор из списка остаётся точным: «Goodyear» — это именно он,
        // а не всё, что на него похоже.
        assertThat(ids(inTenant(() -> wheels.list(null, null, false,
                Map.of("tyreBrand", "Good"), Map.of(), "set", true, 500))))
                .doesNotContain(id);
    }

    @Test
    @DisplayName("Вбитое слово по неизвестной колонке отвергается")
    void typedWordChecksTheColumn() {
        assertThatThrownBy(() -> inTenant(() -> wheels.list(null, null, false, Map.of(),
                Map.of("p.price = 0 OR true", "1"), "set", true, 50)))
                .isInstanceOf(IllegalArgumentException.class);
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
        return disc("5x100");
    }

    private WheelService.WheelRequest disc(String boltPattern) {
        return new WheelService.WheelRequest("DISC", new BigDecimal("15"),
                null, null, null, null, null, null, null,
                "Литой", new BigDecimal("6.0"), 45, boltPattern, new BigDecimal("54.1"),
                null, null, "Enkei", "RPF1",
                null, null, null, null, null, null,
                new BigDecimal("6750"), null, null);
    }

    private WheelService.WheelRequest tyre() {
        return new WheelService.WheelRequest("TYRE", new BigDecimal("15"),
                195, 65, "R", "Легковая", "SUMMER", new BigDecimal("5"), 2022,
                null, null, null, null, null,
                "Goodyear", "EfficientGrip", null, null,
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
