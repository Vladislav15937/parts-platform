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
 * Поиск продавца находит то же, что и витрина владельца.
 *
 * <p><b>Что было.</b> Продавец искал только по морфологии, а витрина —
 * и подстрокой тоже. Числам морфология не годится: покупатель называет номер
 * куском («1150-33»), а продавец читает с этикетки на детали код товара.
 * Замерено на живом складе: «140125» находило у владельца три позиции
 * и ни одной у продавца, код товара «7584A8FEAE3D» — одну у владельца
 * и ноль у продавца. Деталь лежит на полке, её номер напечатан на ней же,
 * и продавец отвечает «нет такого».
 *
 * <p>Кросс-номера не искались вовсе: {@code part_oem} в запросе
 * не участвовал, — а по ним и звонят, когда своего номера нет.
 *
 * <p>Ровно эта расходимость уже чинилась с другой стороны, когда витрина
 * искала только подстрокой и показывала 521 позицию против 739 у продавца.
 * Два поиска по одному складу отвечают по-разному, и неправ тот, о ком
 * не спрашивали.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class SellerSearchTest extends PostgresTestBase {

    // Своя схема, а не общая: в t_000106 живёт перенос колёс, и заведённая
    // здесь шина ломала ему счёт остатка — поймано полным прогоном, когда
    // в этом тесте появилось колесо.
    private static final String TENANT = "t_000112";

    @Autowired
    private PartService parts;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouseId;
    private Long partId;
    private String code;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            jdbc.update("DELETE FROM part_oem");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM part");
            // Машины заводят тесты отбора, и своя на каждый прогон: иначе
            // «Найдено 1» превращается в «найдено сколько-то» от прогона
            // к прогону.
            jdbc.update("DELETE FROM donor");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);

            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, is_published)
                    VALUES (1, 'Фара Toyota Camry 2007 лев. (б/у) 8414012530', 8500, 4000, true)
                    RETURNING id""", Long.class);
            // Номер производителя и кросс-номер: по второму звонят, когда
            // своего номера у покупателя нет.
            jdbc.update("""
                    INSERT INTO part_oem (part_id, raw_number, normalized)
                    VALUES (?, '8414012530', '8414012530'), (?, '8465242100', '8465242100')""",
                    partId, partId);
            code = jdbc.queryForObject(
                    "SELECT public_code FROM part WHERE id = ?", String.class, partId);
            return null;
        });
        inTenant(() -> ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouseId, null)));
    }

    /**
     * Код товара напечатан на этикетке, приклеенной к самой детали.
     * Не найти по нему — значит не найти деталь, держа её в руках.
     */
    @Test
    @DisplayName("Продавец находит деталь по коду товара с этикетки")
    void findsByItemCode() {
        assertThat(found(code))
                .as("код товара с этикетки не находит собственную деталь")
                .contains(partId);
    }

    /**
     * Размер шины покупатель называет словами, и продавец набирает как слышит.
     *
     * <p>Вкладка «Шины и диски» разбирает «225 55 18» в поля с самого начала,
     * а поиск продавца — нет: он искал подстрокой, а в заголовке стоит
     * «225/55 R18», и по буквам это не совпадает ни с чем. Замерено на живом
     * складе: вкладка отдавала 22 позиции, продавец — ноль. Двадцать два
     * колеса лежат, а продавец отвечает «нет такого».
     *
     * <p>Правило общее и чинилось уже дважды: два поиска по одному складу
     * обязаны находить одно и то же.
     */
    @Test
    @DisplayName("Продавец находит шину по размеру, названному словами")
    void findsWheelBySpokenSize() {
        Long wheel = wheelWithSize();

        assertThat(found("225 55 18"))
                .as("размер словами не находит шину — продавец ответит «нет такого»")
                .contains(wheel);
        assertThat(found("225/55 R18"))
                .as("размер в привычной записи тоже обязан находить")
                .contains(wheel);
    }

    /** Нераспознанное остаётся текстом: «Bridgestone» ищется словами. */
    @Test
    @DisplayName("Марка вместе с размером сужает, а не теряет позицию")
    void findsWheelByBrandAndSize() {
        Long wheel = wheelWithSize();

        assertThat(found("бриджстоун 225 55 18"))
                .as("марка по-русски вместе с размером не нашла шину")
                .contains(wheel);
        assertThat(found("данлоп 225 55 18"))
                .as("чужая марка обязана сузить выдачу до пустоты")
                .doesNotContain(wheel);
    }

    private Long wheelWithSize() {
        return inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published, product_line)
                    VALUES (1, 'Шина 225/55 R18 Bridgestone Blizzak зимняя (шипы)',
                            9000, true, 'WHEEL')
                    RETURNING id""", Long.class);
            jdbc.update("""
                    INSERT INTO part_wheel (part_id, kind, tyre_width, tyre_height, diameter)
                    VALUES (?, 'TYRE', 225, 55, 18)""", id);
            ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouseId, null));
            return id;
        });
    }

    /** Номер называют куском: «есть 1150-33?» */
    @Test
    @DisplayName("Продавец находит по обрывку номера производителя")
    void findsByNumberFragment() {
        assertThat(found("140125"))
                .as("номер, названный куском, не находит деталь")
                .contains(partId);
    }

    /** По кросс-номеру звонят, когда своего номера у покупателя нет. */
    @Test
    @DisplayName("Продавец находит по кросс-номеру")
    void findsByCrossNumber() {
        assertThat(found("8465242100"))
                .as("кросс-номер не ищется вовсе")
                .contains(partId);
    }

    /**
     * Морфология обязана остаться: «фары» и «фару» спрашивают чаще, чем
     * «фара», и подстрока их не свяжет.
     */
    @Test
    @DisplayName("Слово в другом падеже по-прежнему находится")
    void findsByMorphology() {
        assertThat(found("фары")).contains(partId);
    }

    /** Русское написание машины — то, как покупатель её называет. */
    @Test
    @DisplayName("Машина по-русски по-прежнему находится")
    void findsByRussianVehicle() {
        assertThat(found("камри")).contains(partId);
    }

    /**
     * Расширение поиска не должно превращать его в «находит всё»: продавец
     * читает выдачу глазами, и лишняя деталь в ней хуже отсутствующей.
     */
    @Test
    @DisplayName("Чужой номер деталь не находит")
    void doesNotFindForeignNumber() {
        assertThat(found("9999999999")).isEmpty();
    }

    /**
     * Обрезанный список обязан называть, сколько нашлось всего.
     *
     * <p>Продавец видел пятьдесят строк из семисот сорока одной и не знал
     * об этом ничего: ответить покупателю «нет такого», глядя на обрезанный
     * список, — то же, что ответить так на пустой, только тут он ещё
     * и уверен, что посмотрел всё.
     */
    @Test
    @DisplayName("Обрезанный список называет число найденного")
    void truncatedListTellsTheTotal() {
        inTenant(() -> {
            for (int i = 0; i < 5; i++) {
                Long id = jdbc.queryForObject("""
                        INSERT INTO part (category_id, title, price, is_published)
                        VALUES (1, ?, 100, true) RETURNING id""",
                        Long.class, "Фара запасная " + i);
                jdbc.update("""
                        INSERT INTO part_stock (part_id, warehouse_id, qty, qty_reserved)
                        VALUES (?, ?, 1, 0)""", id, warehouseId);
            }
            return null;
        });

        PartService.StockSearch cut = inTenant(() -> parts.searchAvailable("фара", 2));

        assertThat(cut.rows()).hasSize(2);
        assertThat(cut.total())
                .as("список обрезан, а число найденного молчит: продавец решит, "
                        + "что посмотрел всё")
                .isEqualTo(6);

        // Когда всё влезло, лишний запрос не нужен и число равно длине.
        PartService.StockSearch whole = inTenant(() -> parts.searchAvailable("фара", 50));
        assertThat(whole.total()).isEqualTo(whole.rows().size());
    }

    /**
     * Главная проверка отбора: он применяется к запросу в базу, а не к тем
     * строкам, что уже показаны.
     *
     * <p>Список обрезан пятьюдесятью строками, а «фара» на живом складе
     * находит 181. Сузив показанное, продавец, которому сказали «фара
     * на Ниссан», получил бы пустоту при полке, полной ниссановских фар:
     * они остались за списком до того, как он успел назвать марку.
     *
     * <p>Поэтому ниссановская фара здесь заведена третьей, а предел
     * поставлен в две строки: без отбора в базе она не показывается вовсе.
     */
    @Test
    @DisplayName("Марка, которой нет в показанных строках, находится отбором")
    void filtersInTheDatabaseAndNotInTheShownRows() {
        // Ниссановская заводится последней: выдача идёт по совпадению,
        // а при равном совпадении — по номеру позиции, и в две показанные
        // строки попадают тойотовские. Это и есть живой случай — 50 из 181.
        partOfVehicle("Фара Toyota Camry 2010 лев.",
                "toyota", "Camry", 2010, "LEFT", "FRONT", 7000);
        Long nissan = partOfVehicle("Фара Nissan Almera 2011 прав.",
                "nissan", "Almera", 2011, "RIGHT", "FRONT", 5000);

        List<Long> shown = inTenant(() -> parts.searchAvailable("фара", 2)).rows().stream()
                .map(PartService.StockRow::partId).toList();
        assertThat(shown)
                .as("проверка бессмысленна, если искомое и так показано")
                .doesNotContain(nissan);

        PartService.StockSearch narrowed = inTenant(() -> parts.searchAvailable(
                "фара", 2, filter().brand("Nissan").build()));

        assertThat(ids(narrowed))
                .as("отбор по марке не дошёл до запроса: ниссановская фара "
                        + "осталась за списком, и продавец ответит «нет такого»")
                .containsExactly(nissan);
        assertThat(narrowed.total())
                .as("счётчик считает не тем условием, каким собрана выдача")
                .isEqualTo(1);
    }

    /** «Фара левая» — это половина разговора по телефону. */
    @Test
    @DisplayName("Сторона сужает выдачу, а снятая — возвращает всё")
    void filtersBySide() {
        Long left = partOfVehicle("Фара Toyota Camry 2010 лев.",
                "toyota", "Camry", 2010, "LEFT", "FRONT", 7000);
        Long right = partOfVehicle("Фара Toyota Camry 2010 прав.",
                "toyota", "Camry", 2010, "RIGHT", "FRONT", 7500);

        assertThat(ids(inTenant(() -> parts.searchAvailable(
                "фара", 50, filter().side("LEFT").build()))))
                .as("отбор по стороне не сузил выдачу")
                .containsExactly(left);
        assertThat(found("фара")).contains(left, right);
    }

    /** Два отбора сужают вместе, а не заменяют друг друга. */
    @Test
    @DisplayName("Марка и год работают вместе")
    void filtersNarrowTogether() {
        Long old = partOfVehicle("Фара Toyota Camry 2007 лев.",
                "toyota", "Camry", 2007, "LEFT", "FRONT", 6000);
        Long fresh = partOfVehicle("Фара Toyota Camry 2012 лев.",
                "toyota", "Camry", 2012, "LEFT", "FRONT", 9000);
        partOfVehicle("Фара Nissan Almera 2012 лев.",
                "nissan", "Almera", 2012, "LEFT", "FRONT", 4000);

        assertThat(ids(inTenant(() -> parts.searchAvailable(
                "фара", 50, filter().brand("Toyota").yearFrom(2010).build()))))
                .as("второй отбор заменил первый вместо того, чтобы сузить")
                .containsExactly(fresh);
        assertThat(old).isNotNull();
    }

    /** Отбор, под который ничего не подходит, отвечает пустотой, а не всем складом. */
    @Test
    @DisplayName("Отбор без подходящего отдаёт пусто")
    void filterWithoutMatchesIsEmpty() {
        partOfVehicle("Фара Toyota Camry 2010 лев.",
                "toyota", "Camry", 2010, "LEFT", "FRONT", 7000);

        PartService.StockSearch nothing = inTenant(() -> parts.searchAvailable(
                "фара", 50, filter().brand("Nissan").build()));

        assertThat(nothing.rows()).isEmpty();
        assertThat(nothing.total()).isZero();
    }

    /** «Что подешевле» — вопрос, которым кончается половина разговоров. */
    @Test
    @DisplayName("Сортировка по цене работает в обе стороны и вместе с отбором")
    void sortsByPrice() {
        Long cheap = partOfVehicle("Фара Toyota Camry 2010 лев.",
                "toyota", "Camry", 2010, "LEFT", "FRONT", 3000);
        Long dear = partOfVehicle("Фара Toyota Camry 2011 прав.",
                "toyota", "Camry", 2011, "RIGHT", "FRONT", 9000);

        assertThat(ids(inTenant(() -> parts.searchAvailable(
                "фара", 50, filter().brand("Toyota").sort("price", false).build()))))
                .as("по возрастанию цены первой обязана идти дешёвая")
                .containsExactly(cheap, dear);
        assertThat(ids(inTenant(() -> parts.searchAvailable(
                "фара", 50, filter().brand("Toyota").sort("price", true).build()))))
                .as("по убыванию цены порядок обязан быть обратным")
                .containsExactly(dear, cheap);
    }

    /**
     * Выбирать продавцу предлагается то, что в найденном есть.
     *
     * <p>Предложить все полторы сотни марок склада — значит предложить
     * выбрать то, чего в выдаче нет; список при этом считается по одному
     * запросу, без уже поставленного отбора, иначе с Toyota нельзя
     * переключиться на Nissan.
     */
    @Test
    @DisplayName("Марки для отбора берутся из найденного, а не из первых строк")
    void offersValuesFoundBeyondTheShownRows() {
        partOfVehicle("Фара Nissan Almera 2011 прав.",
                "nissan", "Almera", 2011, "RIGHT", "FRONT", 5000);
        partOfVehicle("Фара Toyota Camry 2010 лев.",
                "toyota", "Camry", 2010, "LEFT", "FRONT", 7000);

        PartService.StockSearch cut = inTenant(() -> parts.searchAvailable("фара", 1));

        assertThat(cut.rows()).hasSize(1);
        assertThat(cut.facets().vehicles())
                .as("список марок собран по показанной строке, а не по найденному")
                .contains(new PartService.VehicleOption("Nissan", "Almera"),
                        new PartService.VehicleOption("Toyota", "Camry"));
        assertThat(cut.facets().grades())
                .as("оценка состояния названа не теми словами, какими её "
                        + "показывает витрина")
                .contains("б/у");

        // Отбор поставлен — список значений обязан остаться прежним, иначе
        // с одной марки не переключиться на другую.
        PartService.StockSearch narrowed = inTenant(() -> parts.searchAvailable(
                "фара", 50, filter().brand("Toyota").build()));
        assertThat(narrowed.facets().vehicles())
                .contains(new PartService.VehicleOption("Nissan", "Almera"));
    }

    /**
     * Неизвестная сторона — это 4xx со словами, а не пустая выдача.
     *
     * <p>Пустая читалась бы как «нет такого»: продавец ответил бы покупателю
     * по сломанному отбору, ничего не заподозрив.
     */
    @Test
    @DisplayName("Неизвестная сторона отвергается словами")
    void unknownSideIsRejected() {
        assertThatThrownBy(() -> inTenant(() -> parts.searchAvailable(
                "фара", 50, filter().side("СЛЕВА").build())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("левой");
    }

    /**
     * Та же проверка для «переднего и заднего» — класс закрывается целиком.
     *
     * <p>Код у обоих разборов один, и это как раз повод проверить оба:
     * одинаковый код расходится при первой же правке, а отбор, молча
     * отдавший пустоту, продавец читает как «нет такого» и отвечает так
     * покупателю. Пробел назван разбором PR #111 и закрыт тем же приёмом,
     * что и сторона.
     */
    @Test
    @DisplayName("Неизвестное «перед/зад» отвергается словами")
    void unknownPositionIsRejected() {
        assertThatThrownBy(() -> inTenant(() -> parts.searchAvailable(
                "фара", 50, filter().position("СПЕРЕДИ").build())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("передним");
    }

    private List<Long> ids(PartService.StockSearch search) {
        return search.rows().stream().map(PartService.StockRow::partId).toList();
    }

    /** Позиция со своей машиной: марка, модель и год — это отбор продавца. */
    private Long partOfVehicle(String title, String brandSlug, String model, int year,
                               String sideLr, String sideFr, int price) {
        return inTenant(() -> {
            Long brand = jdbc.queryForObject(
                    "SELECT id FROM catalog.brand WHERE slug = ?", Long.class, brandSlug);
            Long modelId = jdbc.queryForObject(
                    "SELECT id FROM catalog.model WHERE brand_id = ? AND name = ?"
                            + " ORDER BY id LIMIT 1", Long.class, brand, model);
            Long donor = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, model_id, year, status)
                    VALUES (?, ?, ?, 'DISMANTLING') RETURNING id""",
                    Long.class, brand, modelId, year);
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published,
                                      donor_id, side_lr, side_fr)
                    VALUES (1, ?, ?, true, ?, ?, ?) RETURNING id""",
                    Long.class, title, price, donor, sideLr, sideFr);
            ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouseId, null));
            return id;
        });
    }

    private static FilterBuilder filter() {
        return new FilterBuilder();
    }

    /** Отбор в тесте набирается по одному полю: их двенадцать. */
    private static final class FilterBuilder {
        private String brand;
        private Integer yearFrom;
        private String side;
        private String position;
        private String sort;
        private boolean descending;

        FilterBuilder brand(String value) {
            this.brand = value;
            return this;
        }

        FilterBuilder yearFrom(Integer value) {
            this.yearFrom = value;
            return this;
        }

        FilterBuilder side(String value) {
            this.side = value;
            return this;
        }

        FilterBuilder position(String value) {
            this.position = value;
            return this;
        }

        FilterBuilder sort(String value, boolean desc) {
            this.sort = value;
            this.descending = desc;
            return this;
        }

        PartService.StockFilter build() {
            return new PartService.StockFilter(brand, null, yearFrom, null, side, position,
                    null, null, null, null, sort, descending);
        }
    }

    private List<Long> found(String query) {
        return inTenant(() -> parts.searchAvailable(query, 50)).rows().stream()
                .map(PartService.StockRow::partId)
                .toList();
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
