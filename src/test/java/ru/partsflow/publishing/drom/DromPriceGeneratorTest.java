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
 * по складам и уменьшается резервом, а проданное, отложенное целиком
 * и невыгружаемое не попадает вовсе (задача 0339).
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

    /** Резерв выгружает только переключатель «Выгружать товары в резерве» (0339), по умолчанию выключен. */
    @Test
    @DisplayName("Отложенная целиком в прайс не идёт: обещанное другому не рекламируем")
    void reservationLeavesThePriceFile() {
        String name = "Прайс: стартер 1NZ-FE";
        Long partId = part(name, new BigDecimal("5000"), true);
        intake(partId, warehouse, 1);
        inTenant(() -> {
            reservations.reserve(partId, warehouse, java.math.BigDecimal.ONE);
            return null;
        });

        assertThat(price())
                .as("зарезервированная деталь ушла в прайс при выключенном переключателе")
                .doesNotContain(name);
    }

    @Test
    @DisplayName("Проданного в прайсе нет — как у Bazon (задача 0339)")
    void soldLeavesThePriceFile() {
        String name = "Прайс: генератор 2AZ-FE";
        Long partId = part(name, new BigDecimal("7000"), true);
        intake(partId, warehouse, 1);
        sale(partId, warehouse, 1);

        // Решение владельца 19.10.2026 «всё как в базон»: у Bazon проданного
        // в файле нет, объявление снимает следующий полный забор.
        assertThat(price()).doesNotContain(name);
        assertThat(delta(partId)).doesNotContain(name);
    }

    @Test
    @DisplayName("Списанного нет ни в прайсе, ни в дельте")
    void writtenOffIsExcludedFromPriceAndDelta() {
        String name = "Прайс: радиатор кондиционера";
        Long partId = part(name, new BigDecimal("2000"), true);
        intake(partId, warehouse, 1);
        inTenant(() -> ledger.record(StockMovement.writeOff(partId, java.math.BigDecimal.ONE, warehouse)));

        // Из полного прайса пропало — так площадка и узнаёт об удалении:
        // «проверяем, какие товары пропали, и убираем их с сайта».
        assertThat(price()).doesNotContain(name);

        // Дельта несёт тот же набор (задача 0339): «недоступна» в формате
        // Bazon сказать нечем, а «В наличии» о списанной — неправда.
        assertThat(delta(partId)).doesNotContain(name);
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

    /**
     * Название в файле — формула Bazon из полей позиции, а не хранимое название
     * (задача 0335): вид как ввёл клиент, текст года из переноса сильнее своего
     * числа, номер без знаков, первая марка применимости — по порядку строк,
     * положение словами. Дельта пишет то же.
     */
    @Test
    @DisplayName("Название в файле собрано по формуле Bazon: и в полном прайсе, и в дельте")
    void titleFollowsTheBazonFormula() {
        Long partId = part("Хранимое название склада, в файл не идёт", new BigDecimal("2500"), true);
        intake(partId, warehouse, 1);
        inTenant(() -> {
            Long nameId = jdbc.queryForObject(
                    "INSERT INTO part_name (name) VALUES ('воздухозаборник  боковой') RETURNING id",
                    Long.class);
            Long toyota = jdbc.queryForObject(
                    "SELECT id FROM catalog.brand WHERE name = 'Toyota'", Long.class);
            Long lexus = jdbc.queryForObject(
                    "SELECT id FROM catalog.brand WHERE name = 'Lexus'", Long.class);
            jdbc.update("""
                    UPDATE part SET part_name_id = ?, year_text = '03-08', manual_year = 2005,
                                    side_lr = 'LEFT', side_fr = 'FRONT'
                     WHERE id = ?""", nameId, partId);
            jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary) "
                    + "VALUES (?, '12345-6a100', '123456A100', true)", partId);
            // Toyota заведена раньше Lexus: «первая» — по порядку строк, а по алфавиту
            // первой была бы Lexus.
            jdbc.update("INSERT INTO part_applicability (part_id, brand_id) VALUES (?, ?)", partId, toyota);
            jdbc.update("INSERT INTO part_applicability (part_id, brand_id) VALUES (?, ?)", partId, lexus);
            return null;
        });
        String expected = "Воздухозаборник боковой Toyota 03-08 123456A100, передний левый";

        assertThat(price())
                .as("название в полном прайсе не по формуле Bazon")
                .contains("<title>" + expected + "</title>")
                .doesNotContain("Хранимое название склада");
        assertThat(delta(partId))
                .as("дельта назвала позицию иначе, чем полный прайс")
                .contains("<title>" + expected + "</title>");

        // Текст года пуст — в название идёт свой год.
        inTenant(() -> jdbc.update("UPDATE part SET year_text = NULL WHERE id = ?", partId));
        assertThat(price()).contains("<title>Воздухозаборник боковой Toyota 2005 123456A100, передний левый</title>");
    }

    /**
     * Машина из общего справочника (задача 0377) доезжает до {@code <title>} файла.
     *
     * <p>Обе позиции одинаковы во всём, кроме ссылки: вид, своя машина, вписанная
     * руками ({@code manual_*}), остаток. У позиции со ссылкой слова — справочника
     * (первое значение списка, формула 0335); у контрольной без ссылки — вписанные,
     * байт в байт как до 0377. Разбор PR 497: откат ссылки в {@code PartVehicle}
     * оставлял весь прайс зелёным.
     */
    @Test
    @DisplayName("Ссылка на справочник машин даёт в названии файла его слова, позиция без ссылки"
            + " называется как раньше: в полном прайсе и в дельте")
    void bazonVehicleWordsReachTheTitle() {
        Long linked = part("Прайс 0377: со ссылкой", new BigDecimal("3100"), true);
        Long plain = part("Прайс 0377: без ссылки", new BigDecimal("3100"), true);
        intake(linked, warehouse, 1);
        intake(plain, warehouse, 1);

        long mark = jdbc.queryForObject("SELECT id FROM catalog.bazon_mark WHERE name = 'Toyota'", Long.class);
        long model = jdbc.queryForObject("SELECT id FROM catalog.bazon_model WHERE bazon_mark_id = ?"
                + " AND name = 'Camry'", Long.class, mark);
        long body = jdbc.queryForObject("""
                SELECT b.id FROM catalog.bazon_body b
                  JOIN catalog.bazon_generation g ON g.id = b.bazon_generation_id
                 WHERE g.bazon_model_id = ? AND b.name IS NOT NULL ORDER BY b.id LIMIT 1""",
                Long.class, model);
        long motor = jdbc.queryForObject("SELECT id FROM catalog.bazon_motor WHERE bazon_mark_id = ?"
                + " ORDER BY id LIMIT 1", Long.class, mark);
        String words = String.join(" ", firstOf("bazon_mark", mark), firstOf("bazon_model", model),
                firstOf("bazon_body", body), firstOf("bazon_motor", motor));

        inTenant(() -> {
            Long nameId = jdbc.queryForObject("""
                    INSERT INTO part_name (name) VALUES ('бампер 0377')
                    ON CONFLICT (lower(btrim(name))) DO UPDATE SET name = excluded.name
                    RETURNING id""", Long.class);
            jdbc.update("""
                    UPDATE part SET part_name_id = ?, manual_brand = 'Nissan', manual_model = 'Primera',
                                    manual_body = 'P12', manual_engine = 'QG18DE'
                     WHERE id IN (?, ?)""", nameId, linked, plain);
            jdbc.update("""
                    UPDATE part SET bazon_mark_id = ?, bazon_model_id = ?, bazon_body_id = ?,
                                    bazon_motor_id = ?
                     WHERE id = ?""", mark, model, body, motor, linked);
            return null;
        });

        String linkedTitle = "<title>Бампер 0377 " + words + "</title>";
        String plainTitle = "<title>Бампер 0377 Nissan Primera P12 QG18DE</title>";
        String xml = price();
        assertThat(offerById(xml, linked))
                .as("слова справочника не дошли до названия в файле")
                .contains(linkedTitle);
        assertThat(offerById(xml, plain))
                .as("позиция без ссылки поменяла название в файле")
                .contains(plainTitle);
        assertThat(offerById(delta(linked, plain), linked)).contains(linkedTitle);
        assertThat(offerById(delta(linked, plain), plain)).contains(plainTitle);
    }

    /** Первое значение списка из записи справочника — как его берёт формула файла. */
    private String firstOf(String table, long id) {
        String list = jdbc.queryForObject("SELECT name FROM catalog." + table + " WHERE id = ?",
                String.class, id);
        return list.split(",")[0].strip();
    }

    /** Вырезает {@code <part>} позиции: в {@code <id>} файла — её номер ({@code part.number}). */
    private String offerById(String xml, Long partId) {
        Long number = inTenant(() -> jdbc.queryForObject(
                "SELECT number FROM part WHERE id = ?", Long.class, partId));
        int at = xml.indexOf("<id>" + number + "</id>");
        assertThat(at).as("позиции №%s нет в файле", number).isNotNegative();
        return xml.substring(xml.lastIndexOf("<part>", at), xml.indexOf("</part>", at));
    }

    @Test
    @DisplayName("Невыгружаемая позиция в прайс не попадает")
    void unpublishedIsExcluded() {
        String name = "Прайс: не для площадок";
        Long partId = part(name, new BigDecimal("100"), false);
        intake(partId, warehouse, 1);

        assertThat(price()).doesNotContain(name);
    }

    /**
     * Незаполненная цена — то же, что ноль: у Bazon незаполненное поле
     * в файле стоит «0», и пустой {@code <price>} площадка прочла бы
     * позицией без цены, а не ценой «0» (задача 0339, толкование исполнителя).
     */
    @Test
    @DisplayName("Позиция без цены уходит ценой 0, как у Bazon")
    void withoutPriceGoesAsZero() {
        String name = "Прайс: без цены";
        Long partId = part(name, null, true);
        intake(partId, warehouse, 1);

        assertThat(offerOf(name)).contains("<price>0</price>");
    }

    @Test
    @DisplayName("Нулевая цена в прайс идёт — как у Bazon (задача 0339)")
    void zeroPriceIsInTheFile() {
        String name = "Прайс: цена ноль";
        Long partId = part(name, BigDecimal.ZERO, true);
        intake(partId, warehouse, 1);

        // Решение владельца 19.10.2026: «0» у Bazon попадает в файл (4 позиции
        // из ≈20 тысяч), а экран «Выгрузки» называет такие позиции красной
        // строкой рядом со счётчиком.
        assertThat(offerOf(name)).contains("<price>0</price>");
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

    /**
     * Задача 0338: номера, производитель, маркировка и цвет доходят от карточки
     * до файла тем видом, что у Bazon, — и в полном прайсе, и в дельте.
     * Значения обезличены (вид тот же, что у позиций обхода: номер с дефисами
     * и строчной, производитель не марка машины, кросс-номер рядом
     * с маркировкой), список кросс-номеров длиннее предела.
     */
    @Test
    @DisplayName("Номера, производитель, маркировка и цвет — как у Bazon, в прайсе и в дельте")
    void numbersAndManufacturerAsBazonInPriceAndDelta() {
        String name = "Прайс: воздухозаборник с номерами Bazon";
        Long partId = part(name, new BigDecimal("1500"), true);
        intake(partId, warehouse, 1);
        java.util.List<String> crosses = new java.util.ArrayList<>(
                java.util.List.of("555000", "555000B", "12345AB000"));
        for (int i = 1; crosses.size() < 130; i++) {
            crosses.add(String.format("%07d", i));
        }
        inTenant(() -> {
            jdbc.update("""
                    UPDATE part SET manufacturer = 'VAG', marking = '12345-AB000',
                                    color = 'Серебро (199)'
                     WHERE id = ?""", partId);
            jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary) "
                    + "VALUES (?, '12345-6h789', '123456H789', true)", partId);
            for (String cross : crosses) {
                jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized) VALUES (?, ?, ?)",
                        partId, cross, ru.partsflow.catalog.OemNumbers.normalize(cross));
            }
            return null;
        });
        String joined = String.join(", ", crosses);
        assertThat(joined.length()).as("контроль: список длиннее предела").isGreaterThan(1024);

        String expected = "<color>Серебро (199)</color>\n"
                + "<marking>12345-AB000</marking>\n"
                + "<manufacturer>VAG</manufacturer>\n"
                + "<manufactureNumber>12345-6h789</manufactureNumber>\n"
                + "<analogsNumbers>" + joined.substring(0, 1024) + "</analogsNumbers>\n";
        assertThat(offerOf(name))
                .as("полный прайс отдал номера не так, как Bazon")
                .contains("<frontRear></frontRear>\n" + expected + "<photoUrls>");
        assertThat(offerIn(delta(partId), name))
                .as("дельта отдала номера не так, как полный прайс")
                .contains("<frontRear></frontRear>\n" + expected + "<photoUrls>");
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
                .doesNotContain("Расположение:")
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
     * <p>С задачи 0339 позиции, которой на складах выгрузки нет, в файле нет
     * вовсе — как у Bazon с режимом склада «В наличии».
     *
     * <p>Правило было записано, но ничем не закрыто: тестов на отбор
     * по складу не было вовсе, и проверить его можно было только глазами
     * на живой выгрузке.
     */
    @Test
    @DisplayName("Отбор по складу меняет состав: прайс филиала несёт только своё")
    void warehouseFilterChangesComposition() {
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
        assertThat(priceWith(alien))
                .as("прайс филиала обещает деталь, которой в этом филиале нет")
                .doesNotContain(name);
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
                // Внутреннее: на площадку не идёт. Ссылка на видео — тоже: у Bazon
                // её в описании нет (0337).
                new Field("video_url", "https://example.org/video", false),
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
    @DisplayName("Текстовый блок уходит в описание, ссылка на видео — нет (0337)")
    void textBlockReachesTheDescriptionVideoDoesNot() {
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
                // У Bazon в описании ссылки на видео нет (решение 0337).
                .as("ссылка на видео уехала в описание")
                .doesNotContain("example.org/v/17")
                .doesNotContain("Видео:");
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
                .contains("<description><![CDATA[\nТолько описание.]]></description>")
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
                .startsWith("\nОжидается поступление, срок — три недели");

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
                .startsWith("\nОжидается поступление");
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
                .startsWith("\nОжидается поступление");

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
     * Переключатель без текста приставки выгружает товар без неё (задача 0339).
     *
     * <p>До неё — «не выгружает ничего» (решение 0007). У Bazon поле
     * приставки пустое у всех пяти выгрузок клиента, а переключатель включён:
     * «как в базон» — значит пустая приставка не останавливает товар.
     */
    @Test
    @DisplayName("Включённый переключатель без текста выгружает товар в пути без приставки")
    void switchWithoutNoteShipsWithoutPrefix() {
        String name = "Прайс: радиатор из контейнера";
        expectedPart(name, new BigDecimal("5500"), "IN_TRANSIT");

        String xml = priceWith(new ru.partsflow.publishing.FeedSettings(
                null, null, null, null, null, true, "   "));

        assertThat(xml).as("товар в пути не ушёл без приставки").contains(name);
        assertThat(descriptionIn(offerIn(xml, name))).doesNotContain("   ");
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
        // С задачи 0339 дельта несёт тот же набор, что прайс: «недоступна»
        // в формате Bazon сказать нечем, и полностью предзаказанной позиции
        // нет и в дельте. Решение 0259 «снимать сразу» этим отменено: снимает
        // полный забор, как у Bazon (названо в PR задачи 0339).
        assertThat(delta)
                .as("дельта несёт позицию, которую нельзя купить")
                .doesNotContain(promised);

        // Деталь пришла, предзаказ стал обычным резервом: целиком отложенная,
        // она в прайс не идёт, пока выгрузка резерв не выгружает (0339).
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
        assertThat(priceWith(expects("Ожидается поступление")))
                .as("после прихода отложенная целиком ушла в прайс при выключенном резерве")
                .doesNotContain(promised);
    }

    /**
     * Предзаказ на часть ожидаемой партии уменьшает количество, а не снимает
     * объявление (задача 0262, решение владельца 9 октября 2026).
     *
     * <p>Партия 3: предзаказ на 1 — Дром видит 2 и доступное; ещё на 2 (двумя
     * строками 1+2 — считается сумма количеств, не число строк) — строки в
     * прайсе нет, и в дельте тоже (задача 0339). Счётчик сверяется с
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
                .as("дельта несёт позицию, все штуки которой под предзаказом")
                .doesNotContain(name);
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
        // тем, что позиции нет вовсе, а не доступной с отрицательным (0339).
        assertThat(delta(partId, expects("Ожидается поступление")))
                .doesNotContain(name);
        assertThat(priceWith(expects("Ожидается поступление"))).doesNotContain(name);
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
        if (at < 0 && offer.contains("<description/>")) {
            return "";
        }
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
