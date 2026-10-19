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
                .contains("<title>" + name + "</title>")
                // Цена целым, как у Bazon (задача 0333): numeric(14,2) без копеек.
                .contains("<price>8500</price>")
                .contains("<state>В наличии</state>");
    }

    @Test
    @DisplayName("Свободный остаток складывается по всем складам")
    void availabilitySumsWarehouses() {
        String name = "Прайс: комплект колодок";
        Long partId = part(name, new BigDecimal("3000"), true);
        intake(partId, warehouse, 2);
        intake(partId, otherWarehouse, 3);

        assertThat(offerOf(name)).contains("<state>В наличии</state>");
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
                .contains("<state>Нет в наличии</state>");
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
        assertThat(offerOf(name)).contains("<state>Нет в наличии</state>");
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
                .contains("<state>Нет в наличии</state>");
    }

    /**
     * Дельта и полный прайс пишут одну позицию одинаково (задача 0333, пункт 5).
     *
     * <p>Площадка разбирает дельту той же настройкой, что прайс, — под файл
     * Bazon. Сверяется вся строка {@code <part>}, вместе со снимками: в формате
     * Bazon пустой {@code <photoUrls>} значит «снимков нет», и дельта без
     * ссылок, какой она была до задачи, сообщала бы площадке именно это.
     */
    @Test
    @DisplayName("Дельта несёт позицию той же строкой, что полный прайс, со снимками")
    void deltaWritesThePartExactlyAsTheFullPrice() {
        String name = "Прайс: фара для сверки дельты";
        Long partId = part(name, new BigDecimal("4999.50"), true);
        intake(partId, warehouse, 1);
        inTenant(() -> {
            jdbc.update("""
                    UPDATE part SET side_lr = 'RIGHT', side_fr = 'FRONT', manufacturer = 'Koito',
                                    condition = 'CONTRACT'
                     WHERE id = ?""", partId);
            jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary) "
                    + "VALUES (?, '81110-33670', '8111033670', true)", partId);
            for (int i = 1; i <= 2; i++) {
                jdbc.update("""
                        INSERT INTO part_photo (part_id, s3_key, is_main, sort_order, status)
                        VALUES (?, ?, ?, ?, 'PROCESSED')""",
                        partId, TENANT + "/parts/" + partId + "/" + i + ".jpg", i == 1, i);
            }
            return null;
        });
        String base = "https://parts.example.ru/feeds/drom/proba/tok/photo/";
        String donorBase = "https://parts.example.ru/feeds/drom/proba/tok/donor-photo/";

        String full = inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out, DromPriceGenerator.FeedFilter.everything(), base, donorBase,
                    ru.partsflow.publishing.FeedSettings.none());
            return out.toString(StandardCharsets.UTF_8);
        });
        String delta = inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeDelta(out, java.util.List.of(partId),
                    DromPriceGenerator.FeedFilter.everything(),
                    ru.partsflow.publishing.FeedSettings.none(), base, donorBase);
            return out.toString(StandardCharsets.UTF_8);
        });

        String inFull = offerIn(full, name);
        assertThat(inFull)
                .as("контроль: позиция ушла со снимками и словами Bazon")
                .contains("<photoUrl>" + base)
                .contains("<price>5000</price>")
                .contains("<condition>контрактный</condition>")
                .contains("<leftRight>right</leftRight>");
        assertThat(offerIn(delta, name))
                .as("дельта пишет позицию не так, как полный прайс")
                .isEqualTo(inFull);
        assertThat(delta)
                .as("дельта — тот же каркас файла, что полный прайс")
                .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<parts>\n<part>\n")
                .endsWith("</part>\n</parts>");
    }

    /**
     * Ключ позиции — номер позиции в {@code <id>}, и в полном прайсе, и в дельте
     * (задачи 0334 и 0333 вместе: 0334 решила значение, 0333 — имя тега).
     * У перенесённой с Bazon позиции номер — её «Номер товара», по которому Дром
     * узнаёт прежние объявления; публичный код в {@code <id>} завёл бы их заново.
     * Номер сдвинут далеко от любых других чисел файла, чтобы совпадение
     * подстрокой не выдало себя за ключ.
     */
    @Test
    @DisplayName("В <id> — номер позиции, а не публичный код: в полном прайсе и в дельте")
    void idIsThePartNumberInPriceAndDelta() {
        String name = "Прайс: фара для сверки ключа";
        Long partId = part(name, new BigDecimal("3100"), true);
        intake(partId, warehouse, 1);
        String[] keys = inTenant(() -> {
            jdbc.update("UPDATE part SET number = 987654 WHERE id = ?", partId);
            return new String[] {
                    jdbc.queryForObject("SELECT number::text FROM part WHERE id = ?", String.class, partId),
                    jdbc.queryForObject("SELECT public_code FROM part WHERE id = ?", String.class, partId)};
        });

        String full = offerIn(priceWith(DromPriceGenerator.FeedFilter.everything()), name);
        String delta = offerIn(inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeDelta(out, java.util.List.of(partId),
                    DromPriceGenerator.FeedFilter.everything(),
                    ru.partsflow.publishing.FeedSettings.none(), null, null);
            return out.toString(StandardCharsets.UTF_8);
        }), name);

        for (String[] file : new String[][] {{"полный прайс", full}, {"дельта", delta}}) {
            assertThat(file[1])
                    .as("%s: ключ позиции не её номер", file[0])
                    .startsWith("<part>\n<id>" + keys[0] + "</id>\n")
                    .doesNotContain(keys[1]);
        }
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
        assertThat(offer).contains("<manufactureNumber>334388</manufactureNumber>");
        assertThat(offer).containsPattern("<analogsNumbers>[^<]*4853033281[^<]*</analogsNumbers>");
        assertThat(offer).containsPattern("<analogsNumbers>[^<]*DS2130GS[^<]*</analogsNumbers>");
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
                .contains("<leftRight>left</leftRight>")
                .contains("<frontRear>front</frontRear>")
                // Вертикальной оси у Bazon нет тегом — она остаётся
                // словом в собранном описании.
                .doesNotContain("<ud>")
                .contains("Расположение: передняя, левая, нижняя.")
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
                null, null, null, null, null, false, null, false, "PART",
                java.util.Map.of(), java.util.Map.of(), false));
        long offers = price().split("<part>", -1).length - 1;

        assertThat(counted)
                .as("счётчик обещает не то число, которое уедет площадке")
                .isEqualTo(offers);
    }

    // ---------- фикстуры ----------

    /**
     * Отбор по марке видит и применимость, а не только машину-донора.
     *
     * <p>У контрактной детали донора нет вовсе, а марка есть — она лежит
     * в {@code part_applicability}, и прайс публикует её тегом
     * {@code brandcars} именно оттуда. Пока отбор смотрел только на донора,
     * прайс-лист «только Toyota» отдавал 12 537 позиций живого склада там,
     * где витрина по той же марке показывала 16 529: четыре тысячи
     * контрактных Тойот не попадали в выгрузку, хотя сама выгрузка
     * объявляет их Тойотами.
     *
     * <p>Обратная сторона держится тем же тестом: «кроме Toyota» обязано
     * выкинуть и контрактную Тойоту — иначе исключение марки не исключает.
     */
    @Test
    @DisplayName("Отбор по марке берёт её и из применимости")
    void brandFilterSeesApplicability() {
        Long brandId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM catalog.brand WHERE name = 'Toyota'", Long.class));

        Long contract = part("Фара контрактная", new BigDecimal("5000"), true);
        intake(contract, warehouse, 1);
        inTenant(() -> jdbc.update(
                "INSERT INTO part_applicability (part_id, brand_id) VALUES (?, ?)",
                contract, brandId));

        Long other = part("Бампер без машины", new BigDecimal("5000"), true);
        intake(other, warehouse, 1);

        DromPriceGenerator.FeedFilter only = new DromPriceGenerator.FeedFilter(
                null, null, java.util.List.of(), java.util.List.of(), java.util.List.of(), false,
                java.util.List.of(brandId), false);
        String onlyToyota = priceWith(only);

        assertThat(onlyToyota)
                .as("контрактная Тойота не попала в прайс-лист «только Toyota»")
                .contains("Фара контрактная");
        assertThat(onlyToyota)
                .as("в «только Toyota» уехало то, что к Toyota не относится")
                .doesNotContain("Бампер без машины");

        DromPriceGenerator.FeedFilter except = new DromPriceGenerator.FeedFilter(
                null, null, java.util.List.of(), java.util.List.of(), java.util.List.of(), false,
                java.util.List.of(brandId), true);
        String withoutToyota = priceWith(except);

        assertThat(withoutToyota)
                .as("«кроме Toyota» оставило контрактную Тойоту")
                .doesNotContain("Фара контрактная");
        assertThat(withoutToyota)
                .as("«кроме Toyota» выкинуло позицию без машины — а она не Тойота")
                .contains("Бампер без машины");

        // Счётчик и генератор — два разных запроса с одним условием,
        // и правка одного мимо другого была бы обещанием не того числа,
        // которое уедет площадке. Проверяется отбором, а не пустым фильтром:
        // существующая сверка счётчика идёт без марок и эту ветку не трогает.
        long counted = inTenant(() -> accounts.countMatching(
                null, null, null, null, null, false,
                java.util.List.of(brandId), false, "PART",
                java.util.Map.of(), java.util.Map.of(), false));
        assertThat(counted)
                .as("счётчик считает марку не так, как генератор")
                .isEqualTo(onlyToyota.split("<part>", -1).length - 1);
    }

    /**
     * Выгрузка филиала показывает остаток этого филиала, а не всей компании.
     *
     * <p><b>Зачем.</b> Прайс с отбором по складу — это витрина конкретной
     * точки: покупатель читает «в наличии» и едет туда. Если остаток считать
     * по всем складам, он приедет за деталью, которая лежит на другом конце
     * города, и виноват будет магазин, а не он.
     *
     * <p>Из прайса позиция при этом не исчезает — уезжает недоступной:
     * убранное из файла объявление площадка снимает вместе с накопленными
     * просмотрами, за которые и платят.
     *
     * <p>Правило было записано, но ничем не закрыто: тестов на отбор
     * по складу не было вовсе, и проверить его можно было только глазами
     * на живой выгрузке.
     */
    @Test
    @DisplayName("Отбор по складу меняет остаток, а не только состав")
    void warehouseFilterChangesAvailability() {
        String name = "Прайс: лежит на дальнем складе";
        Long partId = part(name, new BigDecimal("7000"), true);
        intake(partId, otherWarehouse, 1);

        DromPriceGenerator.FeedFilter own = new DromPriceGenerator.FeedFilter(
                null, null, java.util.List.of(), java.util.List.of(otherWarehouse),
                java.util.List.of(), false, java.util.List.of(), false);
        assertThat(offerIn(priceWith(own), name))
                .as("на своём складе деталь есть, а прайс объявил её недоступной")
                .contains("<state>В наличии</state>");

        DromPriceGenerator.FeedFilter alien = new DromPriceGenerator.FeedFilter(
                null, null, java.util.List.of(), java.util.List.of(warehouse),
                java.util.List.of(), false, java.util.List.of(), false);
        String other = offerIn(priceWith(alien), name);
        assertThat(other)
                .as("прайс филиала обещает деталь, которой в этом филиале нет")
                .contains("<state>Нет в наличии</state>");
    }

    /**
     * Своё условие владельца сужает и прайс, и дельту, и счётчик.
     *
     * <p><b>Зачем.</b> Зашитых условий было шесть — цена, состояние, склады,
     * наименования, марки, — и каждое седьмое означало релиз: миграция,
     * генератор, счётчик, экран. Владелец при этом смотрит склад по двадцати
     * девяти колонкам и отбирает по любой; выгрузка брала из них ни одной.
     *
     * <p>Проверяются три поверхности разом, потому что разойтись они могут
     * только порознь. Счётчик, не знающий условия, обещает не то число,
     * которое уедет площадке, — это уже случалось дважды, с колёсами
     * и с марками из применимости. А дельта, не знающая условия, заводит
     * в чужом прайс-листе объявление, которого владелец не создавал: снять
     * его нечем до следующего полного забора, то есть до трёх суток.
     */
    @Test
    @DisplayName("Своё условие по колонке склада сужает прайс, дельту и счётчик")
    void ownColumnConditionNarrowsFeedDeltaAndCounter() {
        String mine = "Прайс: фара в своей секции";
        String alien = "Прайс: фара в чужой секции";
        Long ours = part(mine, new BigDecimal("4000"), true);
        Long theirs = part(alien, new BigDecimal("4000"), true);
        intake(ours, warehouse, 1);
        intake(theirs, warehouse, 1);
        inTenant(() -> jdbc.update("UPDATE part SET section = 'A-01' WHERE id = ?", ours));
        inTenant(() -> jdbc.update("UPDATE part SET section = 'B-02' WHERE id = ?", theirs));

        DromPriceGenerator.FeedFilter bySection = new DromPriceGenerator.FeedFilter(
                null, null, java.util.List.of(), java.util.List.of(), java.util.List.of(), false,
                java.util.List.of(), false,
                java.util.Map.of("section", "A-01"), java.util.Map.of());

        String xml = priceWith(bySection);
        assertThat(xml)
                .as("позиция своей секции не попала в прайс с условием по секции")
                .contains(mine);
        assertThat(xml)
                .as("условие по колонке не применилось: уехала чужая секция")
                .doesNotContain(alien);

        // Дельта — та же выгрузка, только выбранными позициями. Не знай она
        // условия, чужая секция завела бы объявление в этом прайс-листе.
        String delta = inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeDelta(out, java.util.List.of(ours, theirs), bySection);
            return out.toString(StandardCharsets.UTF_8);
        });
        assertThat(delta).as("своя позиция пропала из дельты").contains(mine);
        assertThat(delta)
                .as("дельта уносит в прайс-лист то, что его отбор не пускает")
                .doesNotContain(alien);

        long counted = inTenant(() -> accounts.countMatching(
                null, null, null, null, null, false, null, false, "PART",
                java.util.Map.of("section", "A-01"), java.util.Map.of(), false));
        assertThat(counted)
                .as("счётчик считает не тем условием, каким собирается прайс")
                .isEqualTo(xml.split("<part>", -1).length - 1);
    }

    /**
     * Вбитое руками ищется вхождением, как на витрине.
     *
     * <p>Это не придирка к способу набора: «Nok» обязано находить Nokian,
     * иначе владелец, знающий производителя приблизительно, получает пустой
     * прайс — а пустой прайс площадка примет молча, и объявления пропадут
     * вместе с накопленными просмотрами.
     */
    @Test
    @DisplayName("Условие «содержит» ищет куском, а не целым значением")
    void ownWordConditionMatchesBySubstring() {
        String name = "Прайс: стойка Tokico";
        String alien = "Прайс: стойка KYB";
        Long partId = part(name, new BigDecimal("4500"), true);
        Long other = part(alien, new BigDecimal("4500"), true);
        intake(partId, warehouse, 1);
        intake(other, warehouse, 1);
        inTenant(() -> jdbc.update(
                "UPDATE part SET manufacturer = 'Tokico Japan' WHERE id = ?", partId));
        inTenant(() -> jdbc.update("UPDATE part SET manufacturer = 'KYB' WHERE id = ?", other));

        String xml = priceWith(new DromPriceGenerator.FeedFilter(
                null, null, java.util.List.of(), java.util.List.of(), java.util.List.of(), false,
                java.util.List.of(), false,
                java.util.Map.of(), java.util.Map.of("manufacturer", "toki")));
        assertThat(xml)
                .as("«содержит» ищет целым значением — куском производителя не найти")
                .contains(name);
        // Вторая половина: условие обязано ещё и отсекать. Без неё тест
        // проходит на выгрузке, не знающей условий вовсе, — то есть стережёт
        // ровно ничего; проверено откатом правки.
        assertThat(xml)
                .as("условие «содержит» не отсекает: уехал чужой производитель")
                .doesNotContain(alien);
    }

    /** Вырезает {@code <part>} по названию из готового прайса. */
    private String offerIn(String xml, String name) {
        int nameAt = xml.indexOf("<title>" + name + "</title>");
        assertThat(nameAt).as("позиции «%s» нет в прайсе вовсе — объявление исчезло", name)
                .isNotNegative();
        return xml.substring(xml.lastIndexOf("<part>", nameAt), xml.indexOf("</part>", nameAt));
    }

    private String priceWith(DromPriceGenerator.FeedFilter filter) {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out, filter);
            return out.toString(StandardCharsets.UTF_8);
        });
    }

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

    /**
     * Каждое поле карточки решено: доезжает до прайса или нет.
     *
     * <p><b>Зачем.</b> Владелец правит цену — и ждёт, что покупатель увидит
     * новую; правит закупочную — и ждёт, что не увидит никто. Между этими
     * двумя ожиданиями нет ничего, кроме нашего решения по каждому полю,
     * а решение это нигде не записано: поле, добавленное в форму правки
     * завтра, молча не попадёт в файл, и заметить это можно будет только
     * по жалобе покупателя, который приехал не за тем.
     *
     * <p>Поэтому список закрытый и делится надвое. Слева — то, что видит
     * покупатель: правка обязана менять файл. Справа — внутреннее
     * и коммерческое: правка обязана файл <b>не</b> менять, иначе адрес
     * стеллажа или минимальная цена уедут на площадку.
     *
     * <p>Новое поле формы, не названное ни там, ни там, роняет тест —
     * и это единственный способ не забыть про него.
     */
    @Test
    @DisplayName("Правка поля меняет прайс тогда и только тогда, когда так решено")
    void everyEditedFieldIsDecidedAboutTheFeed() {
        String name = "Прайс: поля карточки";
        Long partId = part(name, new BigDecimal("5000"), true);
        intake(partId, warehouse, 1);

        record Field(String column, Object value, boolean reachesBuyer) { }
        java.util.List<Field> fields = java.util.List.of(
                // Видит покупатель.
                new Field("price", new BigDecimal("6000"), true),
                new Field("description", "Снято с целой машины", true),
                new Field("manufacturer", "KYB", true),
                new Field("marking", "АРТ-42", true),
                new Field("color", "Чёрный", true),
                new Field("is_published", false, true),
                // Владелец пишет их «для объявления» — значит покупатель
                // обязан их видеть.
                new Field("text_block", "Снята с целой машины, следов удара нет", true),
                new Field("video_url", "https://example.org/video", true),
                // Внутреннее: на площадку не идёт.
                new Field("min_price", new BigDecimal("100"), false),
                new Field("cost_price", new BigDecimal("200"), false),
                // Цена установки сама по себе внутренняя и остаётся ею:
                // наружу от неё уходит только приписка к описанию, и только
                // если владелец включил её у выгрузки. Прайс здесь собирается
                // без настроек — значит поле обязано молчать, как и раньше
                // (включённую приписку стережёт
                // DromFeedControllerTest.installationNoteBelongsToTheFeed).
                new Field("installation_price", new BigDecimal("300"), false),
                new Field("note", "лежит с краю", false),
                new Field("section", "01-02-03", false),
                new Field("barcode", "4600000000048", false),
                new Field("weight_kg", new BigDecimal("4.2"), false),
                new Field("length_mm", 120, false),
                new Field("width_mm", 80, false),
                new Field("height_mm", 45, false),
                new Field("package_weight_kg", new BigDecimal("5.1"), false));

        String clean = offerOf(name);
        for (Field field : fields) {
            inTenant(() -> jdbc.update(
                    "UPDATE part SET %s = ? WHERE id = ?".formatted(field.column()),
                    field.value(), partId));

            if (field.reachesBuyer()) {
                assertThat(offerOfOrNull(name))
                        .as("правка «%s» не доехала до прайса, а покупатель её ждёт",
                                field.column())
                        .isNotEqualTo(clean);
            } else {
                assertThat(offerOf(name))
                        .as("«%s» уехало на площадку, хотя это внутреннее поле",
                                field.column())
                        .isEqualTo(clean);
            }

            // Возвращаем как было: иначе следующее поле сравнивается
            // с изменённым состоянием и «меняется» покажет предыдущая правка.
            // У обязательных колонок NULL не годится — возвращаем значение.
            inTenant(() -> switch (field.column()) {
                case "price" -> jdbc.update("UPDATE part SET price = 5000 WHERE id = ?", partId);
                case "is_published" ->
                        jdbc.update("UPDATE part SET is_published = true WHERE id = ?", partId);
                default -> jdbc.update(
                        "UPDATE part SET %s = NULL WHERE id = ?".formatted(field.column()), partId);
            });
            assertThat(offerOf(name))
                    .as("после отката поля «%s» прайс не вернулся к прежнему", field.column())
                    .isEqualTo(clean);
        }
    }

    /**
     * Текст и видео из карточки доезжают до объявления, а не остаются внутри.
     *
     * <p>Владелец пишет их в полях «Текстовый блок» и «Видео» — это те же
     * колонки, что приезжают из прежней системы. До правки в прайс уходило
     * только описание, и написанное «для объявления» покупатель не видел
     * вовсе: заметить это можно было лишь сверив файл с карточкой руками.
     */
    @Test
    @DisplayName("Текстовый блок и видео дописываются к описанию")
    void textBlockAndVideoReachTheDescription() {
        String name = "Прайс: текст и видео";
        Long partId = part(name, new BigDecimal("4000"), true);
        intake(partId, warehouse, 1);
        inTenant(() -> jdbc.update("""
                UPDATE part SET description = 'Снято с целой машины.',
                                text_block = 'Резьба целая, крепления без трещин.',
                                video_url = 'https://example.org/v/17'
                 WHERE id = ?""", partId));

        String offer = offerOf(name);

        assertThat(offer)
                .as("описание владельца обязано остаться первым")
                .contains("Снято с целой машины.")
                .as("текстовый блок не доехал до покупателя")
                .contains("Резьба целая, крепления без трещин.")
                // Подпись обязательна: голый адрес посреди текста читается
                // как мусор, и по нему не понять, что там ролик о детали.
                .as("ссылка на видео ушла без подписи или не ушла вовсе")
                .contains("Видео: https://example.org/v/17");
    }

    /**
     * Пустые поля не дают ни строки, ни подписи.
     *
     * <p>Иначе у каждой второй позиции в описании висело бы «Видео:»
     * без ссылки — обещание, которого никто не давал.
     */
    @Test
    @DisplayName("Незаполненные текст и видео описание не портят")
    void emptyTextAndVideoAddNothing() {
        String name = "Прайс: без текста и видео";
        Long partId = part(name, new BigDecimal("4000"), true);
        intake(partId, warehouse, 1);
        inTenant(() -> jdbc.update(
                "UPDATE part SET description = 'Только описание.' WHERE id = ?", partId));

        assertThat(offerOf(name))
                .contains("<description><![CDATA[Только описание.]]></description>")
                .doesNotContain("Видео:");
    }

    /**
     * Товар, которого ещё нет на складе, уезжает только по решению выгрузки.
     *
     * <p>Решение владельца продукта от 5 сентября 2026: «выгружаем с припиской
     * "ожидается поступление"». Товар в пути никому не обещан — его можно
     * продавать, покупателю лишь надо сказать, что придётся подождать.
     * Поэтому проверяются обе стороны сразу: без переключателя такой позиции
     * в прайсе нет вовсе (появление настройки не меняет чужие прайсы молча),
     * а с переключателем она уезжает <b>доступной</b> и с припиской в начале
     * описания. Нулём её отдавать нельзя: ноль в колонке количества Дром
     * читает как «товар удалить», и объявление, ради которого всё
     * затевалось, не появилось бы вовсе.
     *
     * <p>Здесь же сверяется счётчик. Настройка живёт не в колонках отбора,
     * то есть счётчику она приезжает отдельно, — а счётчик, считающий не тем
     * условием, что генератор, в этом модуле врал уже дважды.
     */
    @Test
    @DisplayName("Товар в пути уезжает в прайс, только если выгрузка его выгружает")
    void goodsInTransitFollowTheFeedSetting() {
        String awaited = "Прайс: бампер из контейнера";
        String abandoned = "Прайс: черновик без поставки";
        Long partId = expectedPart(awaited, new BigDecimal("12000"), "EXPECTED");
        // Черновик, заведённый и брошенный: поставки в пути у него нет,
        // и переключатель не обязан выгружать заодно и его.
        part(abandoned, new BigDecimal("9000"), true);

        assertThat(price())
                .as("товар, которого ещё нет на складе, уехал в прайс без спроса")
                .doesNotContain(awaited);

        String xml = priceWith(expects("Ожидается поступление, срок — три недели"));

        assertThat(xml)
                .as("товар по ожидаемой поставке в прайс так и не попал")
                .contains("<title>" + awaited + "</title>")
                .as("вместе с ним уехал черновик, поставки в пути не имеющий")
                .doesNotContain(abandoned);

        String offer = offerIn(xml, awaited);
        assertThat(offer)
                .as("товар в пути уехал недоступным — объявление, ради которого всё затевалось, не появится")
                .contains("<state>В наличии</state>");
        assertThat(descriptionIn(offer))
                .as("приписка обязана стоять в начале описания, а не после него")
                .startsWith("Ожидается поступление, срок — три недели");

        long counted = inTenant(() -> accounts.countMatching(
                null, null, null, null, null, false, null, false, "PART",
                java.util.Map.of(), java.util.Map.of(), true));
        assertThat(counted)
                .as("счётчик обещает не то число, которое уедет площадке")
                .isEqualTo(xml.split("<part>", -1).length - 1);

        // Дельта собирается теми же настройками: уйдя без них, она завела бы
        // в прайс-листе объявление, которого владелец не заводил.
        assertThat(descriptionIn(offerIn(deltaWith(expects("Ожидается поступление")), awaited)))
                .as("дельта уносит товар в пути без приписки")
                .startsWith("Ожидается поступление");
    }

    /**
     * Пришедшая поставка снимает приписку — товар становится обычным.
     *
     * <p>Проверяется приходом, а не сменой статуса поставки в отрыве от него:
     * приписка обязана исчезнуть ровно тогда, когда деталь легла на полку.
     */
    @Test
    @DisplayName("Пришедшая поставка снимает приписку про ожидание")
    void arrivedSupplyDropsTheNote() {
        String name = "Прайс: фара из контейнера";
        Long partId = expectedPart(name, new BigDecimal("7000"), "EXPECTED");

        assertThat(descriptionIn(offerIn(priceWith(expects("Ожидается поступление")), name)))
                .startsWith("Ожидается поступление");

        inTenant(() -> jdbc.update(
                "UPDATE supply SET status = 'ARRIVED', arrived_on = current_date"
                        + " WHERE id = (SELECT supply_id FROM part WHERE id = ?)", partId));
        intake(partId, warehouse, 1);

        String offer = offerIn(priceWith(expects("Ожидается поступление")), name);
        assertThat(descriptionIn(offer))
                .as("приписка осталась на товаре, который уже лежит на складе")
                .doesNotContain("Ожидается поступление");
        assertThat(offer).contains("<state>В наличии</state>");
    }

    /**
     * Переключатель без текста приписки не выгружает ничего.
     *
     * <p>Пустой текст отбивается при сохранении, но настройки в базу может
     * положить и перенос, и запрос мимо экрана. Товар, уехавший без слов
     * о том, что его ещё нет, — это объявление, по которому покупатель
     * приедет за деталью, лежащей в контейнере.
     */
    @Test
    @DisplayName("Включённый переключатель без текста товар в пути не выгружает")
    void switchWithoutNoteShipsNothing() {
        String name = "Прайс: радиатор из контейнера";
        expectedPart(name, new BigDecimal("5500"), "IN_TRANSIT");

        String xml = priceWith(new ru.partsflow.publishing.FeedSettings(
                null, null, null, null, null, true, "   "));

        assertThat(xml)
                .as("товар в пути уехал без объяснения, почему его нет на складе")
                .doesNotContain(name);
    }

    /**
     * Принятая деталь — обычный товар, даже если поставку не отметили пришедшей.
     *
     * <p>Поймано живым прогоном, а не тестом: «прибыла» нажимают не всегда
     * и не сразу — контейнер разгрузили, товар приняли, а отметка осталась
     * на завтра. Пока «в пути» считалось по одной поставке, такая деталь
     * получала приписку «ожидается поступление», лёжа на полке, и уезжала
     * с количеством из карточки вместо настоящего остатка: покупателю
     * обещают ждать то, что можно забрать сегодня, а площадке называют
     * не тот остаток.
     */
    @Test
    @DisplayName("Принятый товар по неотмеченной поставке — обычный, без приписки")
    void receivedGoodsAreOrdinaryEvenIfSupplyNotMarked() {
        String name = "Прайс: стойка из неотмеченного контейнера";
        Long partId = expectedPart(name, new BigDecimal("4300"), "IN_TRANSIT");
        // Поставку прибывшей не отмечали — а товар уже приняли на склад.
        intake(partId, warehouse, 2);

        String offer = offerIn(priceWith(expects("Ожидается поступление")), name);

        assertThat(descriptionIn(offer))
                .as("деталь лежит на полке, а объявление обещает её ждать")
                .doesNotContain("Ожидается поступление");
        assertThat(offer)
                .as("принятая деталь уехала не доступной")
                .contains("<state>В наличии</state>");
    }

    /**
     * Отложенная под клиента предзаказом позиция в прайс не идёт вовсе
     * (ответ владельца 9 октября 2026, задача 0170).
     *
     * <p>Товара на руках нет, площадка его честно не продаст, а обещанное
     * покупателю не должно уйти ещё и второму. Строки просто нет — нулём она
     * не пишется (ноль Дром читает как «удалить»). Контроль в том же прайсе:
     * ожидаемая позиция без предзаказа по-прежнему уезжает, иначе тест
     * прошёл бы и там, где ожидаемое не выгружается вообще. Счётчик выгрузки
     * сверяется с числом {@code <part>}, и дельта тем же запросом.
     */
    @Test
    @DisplayName("Позиция под предзаказом в прайс не идёт, без предзаказа — уезжает; после прихода возвращается")
    void preorderedPositionIsLeftOutOfThePrice() {
        String promised = "Прайс: фара под предзаказом";
        String open = "Прайс: фара без предзаказа";
        Long promisedId = expectedPart(promised, new BigDecimal("9000"), "EXPECTED");
        expectedPart(open, new BigDecimal("9100"), "EXPECTED");
        Long dealId = inTenant(() -> {
            Long deal = jdbc.queryForObject("""
                    INSERT INTO deal (status, total_amount) VALUES ('RESERVED', 9000)
                    RETURNING id""", Long.class);
            jdbc.update("""
                    INSERT INTO deal_item (deal_id, part_id, quantity, price, warehouse_id, status)
                    VALUES (?, ?, 1, 9000, ?, 'PREORDER')""", deal, promisedId, warehouse);
            return deal;
        });

        String xml = priceWith(expects("Ожидается поступление"));
        assertThat(xml)
                .as("позиция, обещанная клиенту предзаказом, уехала на площадку")
                .doesNotContain(promised)
                .as("контроль: ожидаемая позиция без предзаказа должна уезжать")
                .contains("<title>" + open + "</title>");

        long counted = inTenant(() -> accounts.countMatching(
                null, null, null, null, null, false, null, false, "PART",
                java.util.Map.of(), java.util.Map.of(), true));
        assertThat(counted)
                .as("счётчик выгрузки обещает не то, что уедет площадке")
                .isEqualTo(xml.split("<part>", -1).length - 1);

        String delta = inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeDelta(out, java.util.List.of(promisedId),
                    DromPriceGenerator.FeedFilter.everything(),
                    expects("Ожидается поступление"));
            return out.toString(StandardCharsets.UTF_8);
        });
        // Дельта, в отличие от прайса, позицию несёт — недоступной: об исчезновении
        // строки она сообщить не умеет, а опубликованное объявление должно
        // сняться сразу, а не через полный забор (задача 0259).
        assertThat(delta)
                .as("дельта молчит про позицию под предзаказом: объявление осталось доступным")
                .contains("<state>Нет в наличии</state>");

        // Деталь пришла, предзаказ стал обычным резервом: строка возвращается
        // и вычитается из остатка, как любой резерв (недоступна, но объявление живо).
        inTenant(() -> {
            jdbc.update("UPDATE supply SET status = 'ARRIVED', arrived_on = current_date"
                    + " WHERE id = (SELECT supply_id FROM part WHERE id = ?)", promisedId);
            return null;
        });
        intake(promisedId, warehouse, 1);
        inTenant(() -> {
            reservations.reserve(promisedId, warehouse, java.math.BigDecimal.ONE);
            jdbc.update("UPDATE deal_item SET status = 'RESERVED' WHERE deal_id = ?", dealId);
            return null;
        });
        assertThat(offerIn(priceWith(expects("Ожидается поступление")), promised))
                .as("после прихода позиция обязана вернуться в прайс")
                .contains("<state>Нет в наличии</state>");
    }

    /**
     * Предзаказ на часть ожидаемой партии уменьшает количество, а не снимает
     * объявление (задача 0262, решение владельца 9 октября 2026).
     *
     * <p>Партия 3: предзаказ на 1 — Дром видит 2 и доступное; ещё на 2 (двумя
     * строками 1+2 — считается сумма количеств, не число строк) — строки в
     * прайсе нет, а дельта несёт недоступной с нулём. Счётчик сверяется с
     * числом {@code <part>} на каждом шаге.
     */
    @Test
    @DisplayName("Частичный предзаказ уменьшает количество на Дроме; полный снимает; счётчик сверен")
    void partialPreorderReducesTheQuantity() {
        String name = "Прайс: партия фар, часть под предзаказом";
        Long partId = expectedPart(name, new BigDecimal("9000"), "EXPECTED");
        inTenant(() -> jdbc.update("UPDATE part SET quantity = 3 WHERE id = ?", partId));
        Long dealId = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO deal (status, total_amount) VALUES ('RESERVED', 9000)
                RETURNING id""", Long.class));

        // Без предзаказа: в файле (контроль, что партия вообще в файле).
        assertThat(offerIn(priceWith(expects("Ожидается поступление")), name))
                .contains("<state>В наличии</state>");

        inTenant(() -> jdbc.update("""
                INSERT INTO deal_item (deal_id, part_id, quantity, price, warehouse_id, status)
                VALUES (?, ?, 1, 9000, ?, 'PREORDER')""", dealId, partId, warehouse));
        String full = priceWith(expects("Ожидается поступление"));
        assertThat(offerIn(full, name))
                .as("из трёх штук одна под предзаказом: остальные две обязаны остаться в продаже")
                .contains("<state>В наличии</state>");
        assertThat(offerIn(delta(partId, expects("Ожидается поступление")), name))
                .as("дельта несёт то же наличие, что и полный прайс")
                .contains("<state>В наличии</state>");
        assertThat(counted())
                .as("счётчик расходится с числом <part>: позиция с частичным предзаказом в файле")
                .isEqualTo(full.split("<part>", -1).length - 1);

        // Ещё две штуки отдельной строкой: всего 1 + 2 = 3, под предзаказом всё.
        inTenant(() -> jdbc.update("""
                INSERT INTO deal_item (deal_id, part_id, quantity, price, warehouse_id, status)
                VALUES (?, ?, 2, 9000, ?, 'PREORDER')""", dealId, partId, warehouse));
        String none = priceWith(expects("Ожидается поступление"));
        assertThat(none)
                .as("все штуки под предзаказом, а строка в прайсе осталась")
                .doesNotContain("<title>" + name + "</title>");
        assertThat(counted())
                .as("счётчик считает позицию, которой в файле нет")
                .isEqualTo(none.split("<part>", -1).length - 1);
        assertThat(delta(partId, expects("Ожидается поступление")))
                .as("дельта полностью предзаказанной позиции: недоступная")
                .contains("<state>Нет в наличии</state>");
    }

    /**
     * Количество не уходит в минус, даже если предзаказов записано больше,
     * чем штук в карточке (сервис такого не допускает, но файл не должен
     * зависеть от того, что кто-то проверил выше).
     */
    @Test
    @DisplayName("Предзаказов больше, чем штук: количество в файле не отрицательно")
    void quantityNeverGoesNegative() {
        String name = "Прайс: предзаказов больше партии";
        Long partId = expectedPart(name, new BigDecimal("9000"), "EXPECTED");
        inTenant(() -> {
            Long deal = jdbc.queryForObject("""
                    INSERT INTO deal (status, total_amount) VALUES ('RESERVED', 9000)
                    RETURNING id""", Long.class);
            jdbc.update("""
                    INSERT INTO deal_item (deal_id, part_id, quantity, price, warehouse_id, status)
                    VALUES (?, ?, 5, 9000, ?, 'PREORDER')""", deal, partId, warehouse);
            return null;
        });
        // Количества в файле Bazon-вида нет (задача 0333): «не в минус» видно
        // тем, что позиция уходит недоступной, а не доступной с отрицательным.
        assertThat(delta(partId, expects("Ожидается поступление")))
                .contains("<state>Нет в наличии</state>");
    }

    private long counted() {
        return inTenant(() -> accounts.countMatching(
                null, null, null, null, null, false, null, false, "PART",
                java.util.Map.of(), java.util.Map.of(), true));
    }

    private String delta(Long partId, ru.partsflow.publishing.FeedSettings settings) {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeDelta(out, java.util.List.of(partId),
                    DromPriceGenerator.FeedFilter.everything(), settings);
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    /** Настройки выгрузки, которая выгружает ожидаемый товар с этой припиской. */
    private static ru.partsflow.publishing.FeedSettings expects(String note) {
        return new ru.partsflow.publishing.FeedSettings(
                null, null, null, null, null, true, note);
    }

    /** Позиция без остатка, привязанная к поставке в пути. */
    private Long expectedPart(String title, BigDecimal price, String supplyStatus) {
        return inTenant(() -> {
            Long supplyId = jdbc.queryForObject("""
                    INSERT INTO supply (kind, number, status)
                    VALUES ('CONTAINER', ?, ?) RETURNING id""",
                    Long.class, "К-" + title.hashCode(), supplyStatus);
            return jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price,
                                      is_published, supply_id)
                    VALUES (1, ?, ?, 1000, true, ?) RETURNING id""",
                    Long.class, title, price, supplyId);
        });
    }

    private String priceWith(ru.partsflow.publishing.FeedSettings settings) {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out, DromPriceGenerator.FeedFilter.everything(), null, settings);
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    private String deltaWith(ru.partsflow.publishing.FeedSettings settings) {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeDelta(out, java.util.List.of(
                            jdbc.queryForObject("SELECT id FROM part WHERE title = ?",
                                    Long.class, "Прайс: бампер из контейнера")),
                    DromPriceGenerator.FeedFilter.everything(), settings);
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    /** Описание объявления как оно уедет площадке — без обёртки CDATA. */
    private static String descriptionIn(String offer) {
        String open = "<description><![CDATA[";
        int at = offer.indexOf(open);
        assertThat(at).as("описания в предложении нет вовсе").isNotNegative();
        return offer.substring(at + open.length(), offer.indexOf("]]></description>", at));
    }

    /** {@code null}, если позиции в прайсе нет вовсе: снятая с публикации исчезает. */
    private String offerOfOrNull(String name) {
        String xml = price();
        int nameAt = xml.indexOf("<title>" + name + "</title>");
        if (nameAt < 0) {
            return null;
        }
        return xml.substring(xml.lastIndexOf("<part>", nameAt), xml.indexOf("</part>", nameAt));
    }

    private String delta(Long... partIds) {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeDelta(out, java.util.List.of(partIds));
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    /** Вырезает из прайса один {@code <part>} по названию позиции. */
    private String offerOf(String name) {
        String xml = price();
        int nameAt = xml.indexOf("<title>" + name + "</title>");
        assertThat(nameAt).as("позиции «%s» нет в прайсе", name).isNotNegative();

        int start = xml.lastIndexOf("<part>", nameAt);
        int end = xml.indexOf("</part>", nameAt);
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
