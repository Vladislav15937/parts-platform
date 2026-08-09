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
                .contains("<name>" + name + "</name>")
                // Цена — numeric(14,2), копейки сохраняются как есть.
                .contains("<price>8500.00</price>")
                .contains("<available>true</available>");
    }

    @Test
    @DisplayName("Свободный остаток складывается по всем складам")
    void availabilitySumsWarehouses() {
        String name = "Прайс: комплект колодок";
        Long partId = part(name, new BigDecimal("3000"), true);
        intake(partId, warehouse, 2);
        intake(partId, otherWarehouse, 3);

        assertThat(offerOf(name)).contains("<available>true</available>");
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
                .contains("<available>false</available>");
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
        assertThat(offerOf(name)).contains("<available>false</available>");
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
                .contains("<available>false</available>");
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
        assertThat(offer).contains("<oem_number>334388</oem_number>");
        assertThat(offer).containsPattern("<analog_numbers>[^<]*4853033281[^<]*</analog_numbers>");
        assertThat(offer).containsPattern("<analog_numbers>[^<]*DS2130GS[^<]*</analog_numbers>");
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
                .contains("<lr>лево</lr>")
                .contains("<fr>перед</fr>")
                .contains("<ud>низ</ud>")
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
                null, null, null, null, null, false, null, false, "PART"));
        long offers = price().split("<offer>", -1).length - 1;

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
                java.util.List.of(brandId), false, "PART"));
        assertThat(counted)
                .as("счётчик считает марку не так, как генератор")
                .isEqualTo(onlyToyota.split("<offer>", -1).length - 1);
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
                .contains("<available>true</available>")
                .contains("<quantity>1</quantity>");

        DromPriceGenerator.FeedFilter alien = new DromPriceGenerator.FeedFilter(
                null, null, java.util.List.of(), java.util.List.of(warehouse),
                java.util.List.of(), false, java.util.List.of(), false);
        String other = offerIn(priceWith(alien), name);
        assertThat(other)
                .as("прайс филиала обещает деталь, которой в этом филиале нет")
                .contains("<available>false</available>")
                .contains("<quantity>0</quantity>");
    }

    /** Вырезает {@code <offer>} по названию из готового прайса. */
    private String offerIn(String xml, String name) {
        int nameAt = xml.indexOf("<name>" + name + "</name>");
        assertThat(nameAt).as("позиции «%s» нет в прайсе вовсе — объявление исчезло", name)
                .isNotNegative();
        return xml.substring(xml.lastIndexOf("<offer>", nameAt), xml.indexOf("</offer>", nameAt));
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
                .contains("<description>Только описание.</description>")
                .doesNotContain("Видео:");
    }

    /** {@code null}, если позиции в прайсе нет вовсе: снятая с публикации исчезает. */
    private String offerOfOrNull(String name) {
        String xml = price();
        int nameAt = xml.indexOf("<name>" + name + "</name>");
        if (nameAt < 0) {
            return null;
        }
        return xml.substring(xml.lastIndexOf("<offer>", nameAt), xml.indexOf("</offer>", nameAt));
    }

    private String delta(Long... partIds) {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeDelta(out, java.util.List.of(partIds));
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    /** Вырезает из прайса один {@code <offer>} по названию позиции. */
    private String offerOf(String name) {
        String xml = price();
        int nameAt = xml.indexOf("<name>" + name + "</name>");
        assertThat(nameAt).as("позиции «%s» нет в прайсе", name).isNotNegative();

        int start = xml.lastIndexOf("<offer>", nameAt);
        int end = xml.indexOf("</offer>", nameAt);
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
