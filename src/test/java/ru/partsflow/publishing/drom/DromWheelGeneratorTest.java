package ru.partsflow.publishing.drom;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.StockLedger;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Прайс шин и дисков.
 *
 * <p>Проверяется то, за что площадка снимает объявления или не показывает их
 * вовсе: порядок и слитность маркировки, сезон её словами, шиповка только
 * там, где она известна, износ в процентах и цена за то количество, которое
 * названо в комплекте.
 *
 * <p>Требования взяты с {@code farpost.ru/help/trebovaniya_k_price_listam_po_shinam}
 * и в комментариях к проверкам названы пунктами.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class DromWheelGeneratorTest extends PostgresTestBase {

    private static final String TENANT = "t_000096";

    @Autowired
    private DromWheelGenerator generator;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private ru.partsflow.publishing.MarketplaceAccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Маркировка собирается в порядке площадки и слитными индексами")
    void markingFollowsTheRequiredOrder() {
        String title = "Шина 225/55 R18 Dunlop зимняя";
        Long partId = tyre(title, "8000", "WINTER_STUDDED", 225, 55, "18", 100, "Q", "5.5");

        String offer = offerOf(title);

        // П. 3.3: типоразмер, потом индексы; п. 3.6.1 — нагрузка и скорость
        // слитно. «R18 225/55» площадка честно предупреждает, что распознает
        // неверно, и покупатель шину не найдёт.
        assertThat(offer).contains("<marking>225/55R18 100Q</marking>");
        assertThat(offer).contains("<ordercode>" + publicCodeOf(partId) + "</ordercode>");
    }

    @Test
    @DisplayName("Сезон и шиповка едут словами площадки")
    void seasonAndSpikeAreSpelledOut() {
        String studded = "Шина 195/65 R15 Nokian шипы";
        tyre(studded, "5000", "WINTER_STUDDED", 195, 65, "15", 91, "T", "7");
        assertThat(offerOf(studded))
                .contains("<season>Зимняя</season>")
                .contains("<spike>Шипованная</spike>");

        String velcro = "Шина 195/65 R15 Nokian липучка";
        tyre(velcro, "5000", "WINTER_FRICTION", 195, 65, "15", 91, "T", "7");
        assertThat(offerOf(velcro))
                .contains("<season>Зимняя</season>")
                .contains("<spike>Нешипуемая</spike>");
    }

    @Test
    @DisplayName("У просто «зимней» шиповка не выдумывается")
    void unknownSpikeIsNotInvented() {
        String title = "Шина 205/55 R16 неизвестной зимы";
        tyre(title, "4000", "WINTER", 205, 55, "16", 91, "H", "6");

        // Сказать «нешипуемая» там, где шипы неизвестны, значит обмануть
        // покупателя, который ищет именно шипы. У заведённых до появления
        // различия шин сезон так и записан — просто «зимняя».
        assertThat(offerOf(title)).doesNotContain("<spike>");
    }

    @Test
    @DisplayName("Износ уходит процентами: площадка меряет им, мы миллиметрами")
    void wearIsConvertedToPercent() {
        String title = "Шина 185/65 R15 с остатком 6 мм";
        tyre(title, "3000", "SUMMER", 185, 65, "15", 88, "H", "6");

        // Остаток 6 мм от восьми — износ 25 %. База пересчёта названа
        // в генераторе: своего значения «новая шина» у нас нет, а без поля
        // б/у шина выпадает из фильтра «износ до 20 %».
        assertThat(offerOf(title)).contains("<iznos>25</iznos>");
    }

    @Test
    @DisplayName("У новой шины износа нет вовсе")
    void newTyreHasNoWear() {
        String title = "Шина 185/65 R15 новая";
        Long partId = tyre(title, "6000", "SUMMER", 185, 65, "15", 88, "H", null);
        inTenant(() -> jdbc.update("UPDATE part SET condition = 'NEW' WHERE id = ?", partId));

        String offer = offerOf(title);
        assertThat(offer).contains("<condition>Новая</condition>");
        assertThat(offer).doesNotContain("<iznos>");
    }

    @Test
    @DisplayName("Цена названа за одну шину, и это же стоит в комплекте")
    void priceMatchesTheDeclaredSetSize() {
        String title = "Шина 215/60 R17 одиночная";
        tyre(title, "4500", "SUMMER", 215, 60, "17", 96, "V", "7");

        // П. 6.4.3: стоимость обязана соответствовать количеству шин
        // в комплекте. Мы продаём поштучно — значит единица и цена штуки,
        // а не набор из четырёх по цене одной.
        assertThat(offerOf(title))
                .contains("<inSet>1</inSet>")
                .contains("<price>4500")
                .contains("<quantity>1</quantity>");
    }

    @Test
    @DisplayName("Диск уходит своей маркировкой, а не шинной")
    void discGetsItsOwnMarking() {
        String title = "Диск R18 Rays";
        Long partId = wheelPart(title, "12000");
        inTenant(() -> jdbc.update("""
                INSERT INTO part_wheel (part_id, kind, diameter, disc_width, offset_mm,
                                        bolt_pattern, disc_brand, disc_model)
                VALUES (?, 'DISC', 18, 7.5, 38, '5x114.3', 'Rays', 'Volk')""", partId));

        assertThat(offerOf(title))
                .contains("<marking>7.5x18 5x114.3 ET38</marking>")
                .contains("<model>Rays Volk</model>");
    }

    @Test
    @DisplayName("Запчасти в прайс колёс не попадают")
    void partsStayOutOfTheWheelFeed() {
        String title = "Фара, которой тут не место";
        inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published)
                    VALUES (1, ?, 5000, true) RETURNING id""", Long.class, title);
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
            return null;
        });

        assertThat(price()).doesNotContain(title);
    }

    @Test
    @DisplayName("Проданное колесо остаётся в прайсе недоступным")
    void soldWheelStaysUnavailable() {
        String title = "Шина 175/70 R14 проданная";
        Long partId = tyre(title, "2000", "SUMMER", 175, 70, "14", 84, "T", "5");
        inTenant(() -> {
            ledger.record(StockMovement.sale(partId, BigDecimal.ONE, warehouse, null));
            return null;
        });

        // То же соображение, что и в прайсе запчастей: убрать позицию —
        // значит потерять объявление вместе с накопленными просмотрами.
        assertThat(offerOf(title)).contains("<available>false</available>");
    }

    /**
     * «Выгружать» решает и у колёс, и решает иначе, чем продажа.
     *
     * <p>Колонка одна на оба вида товара — колесо это тот же {@code part},
     * только со своей линией, — и оба генератора её читают. Но следствия
     * у снятия и у продажи разные, и путать их нельзя: проданное колесо
     * остаётся в файле недоступным, чтобы объявление сохранило накопленные
     * просмотры, а снятое с выгрузки исчезает совсем — владелец не хочет
     * его продавать, и объявления быть не должно.
     *
     * <p>У прайса запчастей это закрыто списком решений по каждому полю,
     * у колёсного до сих пор не проверялось ничем.
     */
    @Test
    @DisplayName("Снятое с выгрузки колесо в прайс не идёт вовсе")
    void unpublishedWheelDisappears() {
        String title = "Шина 185/65 R15 снятая с выгрузки";
        Long partId = tyre(title, "3000", "SUMMER", 185, 65, "15", 88, "H", "6");
        inTenant(() -> {
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
            return null;
        });
        assertThat(price()).as("колесо не попало в прайс до снятия").contains(title);

        inTenant(() -> jdbc.update("UPDATE part SET is_published = false WHERE id = ?", partId));

        assertThat(price())
                .as("снятое с выгрузки колесо всё равно уехало на площадку")
                .doesNotContain(title);
    }

    /**
     * Счётчик обязан считать тем же условием, что и генератор.
     *
     * <p>Пока он знал только запчасти, у выгрузки колёс он показывал 35 835
     * позиций там, где уезжало 60: число, ради которого он и заведён, врало
     * в шестьсот раз и успокаивало вместо того, чтобы предупреждать.
     * Сравнение идёт с числом {@code <offer>} в собранном прайсе, а не
     * с ожидаемым числом, — так расхождение поймается при любой будущей
     * правке отбора.
     *
     * <p>Отбор по цене отсекает фикстуры соседних проверок: схема у класса
     * общая, и без него число зависело бы от порядка тестов. Шин при этом
     * две, а запчасть одна — иначе счёт по чужой линии случайно совпал бы
     * с верным, и проверка молчала бы при сломанном коде.
     */
    @Test
    @DisplayName("Счётчик выгрузки колёс обещает столько, сколько уедет")
    void counterMatchesTheWheelFeed() {
        BigDecimal from = new BigDecimal("987000");
        BigDecimal to = new BigDecimal("988000");

        tyre("Счётчик: шина первая", "987500", "SUMMER", 205, 55, "16", 91, "V", "6");
        tyre("Счётчик: шина вторая", "987600", "SUMMER", 215, 60, "17", 94, "H", "5");
        inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, is_published)
                    VALUES (1, 'Счётчик: фара', 987700, 1000, true) RETURNING id""", Long.class);
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
            return partId;
        });

        long counted = inTenant(() -> accounts.countMatching(
                from, to, null, null, null, false, null, false, "WHEEL"));
        long offers = priceWithin(from, to).split("<offer>", -1).length - 1;

        assertThat(offers).as("в прайс колёс попали не обе шины").isEqualTo(2);
        assertThat(counted)
                .as("счётчик колёсной выгрузки обещает не то число, которое уедет")
                .isEqualTo(offers);
    }

    // ---------- фикстуры ----------

    private Long wheelPart(String title, String price) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published,
                                      product_line, condition)
                    VALUES (1, ?, ?::numeric, true, 'WHEEL', 'USED') RETURNING id""",
                    Long.class, title, price);
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
            return partId;
        });
    }

    private Long tyre(String title, String price, String season, int width, int height,
                      String diameter, Integer loadIndex, String speedIndex, String wearMm) {
        Long partId = wheelPart(title, price);
        inTenant(() -> jdbc.update("""
                INSERT INTO part_wheel (part_id, kind, diameter, tyre_width, tyre_height,
                                        season, wear_mm, load_index, speed_index, made_year)
                VALUES (?, 'TYRE', ?::numeric, ?, ?, ?, ?::numeric, ?, ?, 2021)""",
                partId, diameter, width, height, season, wearMm, loadIndex, speedIndex));
        return partId;
    }

    private String priceWithin(BigDecimal from, BigDecimal to) {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out, new DromPriceGenerator.FeedFilter(
                    from, to, java.util.List.of(), java.util.List.of(),
                    java.util.List.of(), false, java.util.List.of(), false), null);
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    private String price() {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out, DromPriceGenerator.FeedFilter.everything(), null);
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    /** Вырезает один {@code <offer>} по названию позиции. */
    private String offerOf(String title) {
        String xml = price();
        int nameAt = xml.indexOf("<name>" + title + "</name>");
        assertThat(nameAt).as("позиции «%s» нет в прайсе колёс", title).isNotNegative();
        return xml.substring(xml.lastIndexOf("<offer>", nameAt), xml.indexOf("</offer>", nameAt));
    }

    private String publicCodeOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, partId));
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
