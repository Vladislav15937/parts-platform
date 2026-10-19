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
import ru.partsflow.support.WheelCsv;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Файл шин и дисков в формате Bazon (задача 0340): CSV, 25 колонок.
 *
 * <p>Проверяется то, что различает разбор площадки: устройство файла
 * (кодировка, кавычки, CRLF, заголовок), ключ позиции («К» и номер),
 * слова сезона и состояния, износ процентом, цена как в карточке и отбор.
 * Файл читается {@link WheelCsv}, который отказывает на любом отличии
 * от образца.
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
    @DisplayName("Файл устроен как у Bazon: windows-1251, заголовок, все поля в кавычках, CRLF")
    void fileLayoutIsBazons() {
        tyre("Шина 225/55 R18 Dunlop зимняя", "8000", "WINTER_STUDDED", 225, 55, "18", 100, "Q", 30);

        byte[] bytes = priceBytes();

        // Заголовок «Артикул» в windows-1251 — C0 F0 F2 E8 EA F3 EB, в кавычках,
        // без BOM. UTF-8 дал бы D0 90 …, и площадка прочла бы мусор.
        assertThat(bytes[0]).as("файл начинается не с кавычки (BOM?)").isEqualTo((byte) '"');
        byte[] article = {(byte) 0xC0, (byte) 0xF0, (byte) 0xF2, (byte) 0xE8, (byte) 0xEA,
                (byte) 0xF3, (byte) 0xEB};
        for (int i = 0; i < article.length; i++) {
            assertThat(bytes[i + 1]).as("байт %d заголовка не из windows-1251", i).isEqualTo(article[i]);
        }
        assertThat(new String(bytes, WheelCsv.CHARSET))
                .startsWith(String.join(";", DromWheelWriter.HEADER.stream()
                        .map(h -> "\"" + h + "\"").toList()) + "\r\n");
        assertThat(DromWheelWriter.HEADER).hasSize(25);

        WheelCsv csv = WheelCsv.parse(bytes);
        assertThat(csv.header()).isEqualTo(DromWheelWriter.HEADER);
        // Разбор требует кавычек у каждого поля и 25 полей в каждой строке.
        assertThat(csv.rows()).isNotEmpty();
    }

    @Test
    @DisplayName("Артикул — русская «К» (U+041A) и номер позиции, а не публичный код")
    void articleIsRussianKAndThePartNumber() {
        Long partId = tyre("Шина 225/55 R18 Dunlop зимняя", "8000", "WINTER_STUDDED",
                225, 55, "18", 100, "Q", 30);

        String article = rowOf(partId).get("Артикул");

        // Проверка кодом знака, не глазом: латинская K выглядит так же.
        assertThat(article.codePointAt(0)).as("первая буква артикула не русская «К»")
                .isEqualTo(0x041A);
        assertThat(article).isEqualTo("К" + numberOf(partId));
        assertThat(article).doesNotContain(publicCodeOf(partId));
    }

    @Test
    @DisplayName("Колонки шины: размер, производитель, сезон словами Bazon, год")
    void tyreColumns() {
        Long partId = tyre("Шина 225/55 R18 Dunlop зимняя", "8000", "WINTER_FRICTION",
                225, 55, "18", 100, "Q", 30);
        inTenant(() -> jdbc.update(
                "UPDATE part_wheel SET brand = 'Dunlop', model = 'Winter Maxx SJ8' WHERE part_id = ?",
                partId));

        Map<String, String> row = rowOf(partId);

        assertThat(row).containsEntry("Тип (диск, шина, колесо)", "шина")
                .containsEntry("Количество в комплекте", "1")
                .containsEntry("Производитель шины", "Dunlop")
                .containsEntry("Модель шины", "Winter Maxx SJ8")
                .containsEntry("Ширина профиля шины", "225")
                .containsEntry("Высота профиля шины", "55")
                .containsEntry("Посадочный диаметр шины", "18")
                .containsEntry("Сезон шины (лето, зима, шипы)", "Зимняя (липучка)")
                .containsEntry("Дата производства", "2021")
                .containsEntry("Статус", "В наличии")
                // Колонки диска у шины пусты — но есть, и в кавычках.
                .containsEntry("Производитель диска", "")
                .containsEntry("PCD диска", "")
                .containsEntry("Номер производителя", "");
    }

    @Test
    @DisplayName("Слова сезона: лето, зима, липучка, шипы, всесезонная")
    void seasonWords() {
        assertThat(seasonOf("SUMMER")).isEqualTo("Летняя");
        assertThat(seasonOf("WINTER")).isEqualTo("Зимняя");
        assertThat(seasonOf("WINTER_FRICTION")).isEqualTo("Зимняя (липучка)");
        // Слова для шипов у Bazon не видели — «Шипы» до обхода (одно место: DromWheelWriter).
        assertThat(seasonOf("WINTER_STUDDED")).isEqualTo(DromWheelWriter.SEASON_STUDDED);
        assertThat(seasonOf("ALL_SEASON")).isEqualTo("Всесезонная");
    }

    @Test
    @DisplayName("Износ уходит тем процентом, что вписан, со знаком %")
    void wearGoesAsWritten() {
        Long partId = tyre("Шина 185/65 R15 с износом 12 %", "3000", "SUMMER",
                185, 65, "15", 88, "H", 12);

        // Двенадцать, а не тринадцать: через миллиметры (8 мм за новую)
        // 12 % и 13 % лежат одним числом 7,0 мм, и до задачи 0340 вписанное
        // «12%» уезжало «13%».
        assertThat(rowOf(partId)).containsEntry("Износ шин", "12%");
    }

    @Test
    @DisplayName("У новой шины износа нет вовсе, состояние — «Новое»")
    void newTyreHasNoWear() {
        Long partId = tyre("Шина 185/65 R15 новая", "6000", "SUMMER", 185, 65, "15", 88, "H", 40);
        inTenant(() -> jdbc.update("UPDATE part SET condition = 'NEW' WHERE id = ?", partId));

        assertThat(rowOf(partId)).containsEntry("Износ шин", "")
                .containsEntry("Новое/БУ", "Новое");
    }

    @Test
    @DisplayName("Состояние словами Bazon: БУ и Контракт")
    void conditionWords() {
        Long used = tyre("Шина состояние б/у", "3001", "SUMMER", 185, 65, "15", 88, "H", 20);
        Long contract = tyre("Шина состояние контракт", "3002", "SUMMER", 185, 65, "15", 88, "H", 20);
        inTenant(() -> jdbc.update("UPDATE part SET condition = 'CONTRACT' WHERE id = ?", contract));

        assertThat(rowOf(used)).containsEntry("Новое/БУ", "БУ");
        assertThat(rowOf(contract)).containsEntry("Новое/БУ", "Контракт");
    }

    @Test
    @DisplayName("Цена уходит как в карточке: комплект одной позицией — «4» и цена комплекта, не штуки")
    void priceIsTheCardsPriceAndSetSizeIsItsOwnColumn() {
        Long single = tyre("Шина 215/60 R17 одиночная", "4500", "SUMMER", 215, 60, "17", 96, "V", 10);
        Long set = tyre("Шина 235/55 R19 комплект", "45000", "SUMMER", 235, 55, "19", 105, "V", 10);
        inTenant(() -> jdbc.update("UPDATE part_wheel SET set_pieces = 4 WHERE part_id = ?", set));

        // Цена на штуку не делится никогда (решение 2 задачи 0340): 45 000
        // остаётся 45 000, а «за комплект из четырёх» говорит соседняя колонка.
        assertThat(rowOf(single)).containsEntry("Цена", "4500")
                .containsEntry("Количество в комплекте", "1");
        assertThat(rowOf(set)).containsEntry("Цена", "45000")
                .containsEntry("Количество в комплекте", "4");
    }

    @Test
    @DisplayName("Цена целым, копейки вверх")
    void priceIsWholeRoundedUp() {
        Long partId = tyre("Шина 195/65 R15 с копейками", "4999.50", "SUMMER",
                195, 65, "15", 91, "T", 10);

        assertThat(rowOf(partId)).containsEntry("Цена", "5000");
    }

    @Test
    @DisplayName("Комментарий — описание позиции как набрано: кавычки, «;» и одиночный LF сохраняются")
    void commentIsVerbatim() {
        Long partId = tyre("Шина 195/65 R15 с описанием", "4000", "SUMMER", 195, 65, "15", 91, "T", 10);
        String comment = "Шина #1: \"Контракт\"; без пробега по РФ\nЦена за комплект";
        inTenant(() -> jdbc.update("UPDATE part SET description = ? WHERE id = ?", comment, partId));

        byte[] bytes = priceBytes();
        assertThat(WheelCsv.parse(bytes).byArticle("К" + numberOf(partId)))
                .containsEntry("Комментарий", comment);
        // В самом файле: кавычка удвоена, LF внутри — одиночный, не CRLF.
        assertThat(new String(bytes, WheelCsv.CHARSET))
                .contains("\"Шина #1: \"\"Контракт\"\"; без пробега по РФ\nЦена за комплект\"");
    }

    @Test
    @DisplayName("Знак вне windows-1251 не роняет файл: уходит «?», строки на месте")
    void unmappableCharDoesNotBreakTheFile() {
        Long partId = tyre("Шина 195/65 R15 эмодзи", "4000", "SUMMER", 195, 65, "15", 91, "T", 10);
        inTenant(() -> jdbc.update("UPDATE part SET description = ? WHERE id = ?",
                "Хорошая 😀 шина", partId));

        assertThat(rowOf(partId).get("Комментарий")).startsWith("Хорошая ").endsWith(" шина")
                .contains("?");
    }

    @Test
    @DisplayName("Диск: свои колонки, у шины они пусты, сезона и износа нет")
    void discGetsItsOwnColumns() {
        Long partId = wheelPart("Диск R18 Rays", "12000");
        inTenant(() -> jdbc.update("""
                INSERT INTO part_wheel (part_id, kind, diameter, disc_width, offset_mm,
                                        bolt_pattern, disc_brand, disc_model, disc_type, hub_bore)
                VALUES (?, 'DISC', 18, 7.5, 38, '5x114.3', 'Rays', 'Volk', 'Литой', 73.1)""", partId));

        assertThat(rowOf(partId)).containsEntry("Тип (диск, шина, колесо)", "диск")
                .containsEntry("Производитель диска", "Rays")
                .containsEntry("Модель диска", "Volk")
                .containsEntry("Диаметр диска", "18")
                .containsEntry("Тип диска", "Литой")
                .containsEntry("Ширина диска", "7.5")
                .containsEntry("Вылет диска", "38")
                .containsEntry("PCD диска", "5x114.3")
                .containsEntry("Диаметр осевого отверстия диска", "73.1")
                .containsEntry("Производитель шины", "")
                .containsEntry("Ширина профиля шины", "")
                .containsEntry("Сезон шины (лето, зима, шипы)", "")
                .containsEntry("Износ шин", "");
    }

    @Test
    @DisplayName("Колесо в сборе заполняет обе половины — диск и шину")
    void assemblyFillsBothHalves() {
        Long partId = wheelPart("Колесо в сборе", "40000");
        inTenant(() -> jdbc.update("""
                INSERT INTO part_wheel (part_id, kind, diameter, tyre_width, tyre_height, season,
                                        wear_percent, made_year, disc_width, offset_mm, bolt_pattern,
                                        disc_brand, brand, model, set_pieces)
                VALUES (?, 'ASSEMBLY', 16, 205, 55, 'SUMMER', 30, 2019, 6.5, 45, '5x100',
                        'Toyota', 'Yokohama', 'BluEarth', 4)""", partId));

        assertThat(rowOf(partId)).containsEntry("Тип (диск, шина, колесо)", "колесо")
                .containsEntry("Производитель диска", "Toyota")
                .containsEntry("Диаметр диска", "16")
                .containsEntry("Производитель шины", "Yokohama")
                .containsEntry("Посадочный диаметр шины", "16")
                .containsEntry("Износ шин", "30%")
                .containsEntry("Цена", "40000")
                .containsEntry("Количество в комплекте", "4");
    }

    @Test
    @DisplayName("Запчасти в прайс колёс не попадают")
    void partsStayOutOfTheWheelFeed() {
        String title = "Фара, которой тут не место";
        Long partId = inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published)
                    VALUES (1, ?, 5000, true) RETURNING id""", Long.class, title);
            ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouse, null));
            return id;
        });

        assertThat(WheelCsv.parse(priceBytes()).byArticle("К" + numberOf(partId)))
                .as("запчасть уехала в файл шин и дисков").isNull();
    }

    @Test
    @DisplayName("Проданное колесо остаётся в файле, статус «Нет в наличии»")
    void soldWheelStaysUnavailable() {
        Long partId = tyre("Шина 175/70 R14 проданная", "2000", "SUMMER", 175, 70, "14", 84, "T", 40);
        inTenant(() -> {
            ledger.record(StockMovement.sale(partId, BigDecimal.ONE, warehouse, null));
            return null;
        });

        // То же соображение, что и в прайсе запчастей: убрать позицию —
        // значит потерять объявление вместе с накопленными просмотрами.
        assertThat(rowOf(partId)).containsEntry("Статус", "Нет в наличии");
    }

    /**
     * «Выгружать» решает и у колёс, и решает иначе, чем продажа.
     *
     * <p>Проданное колесо остаётся в файле недоступным, чтобы объявление
     * сохранило накопленные просмотры, а снятое с выгрузки исчезает совсем —
     * владелец не хочет его продавать, и объявления быть не должно.
     */
    @Test
    @DisplayName("Снятое с выгрузки колесо в файл не идёт вовсе")
    void unpublishedWheelDisappears() {
        Long partId = tyre("Шина 185/65 R15 снятая с выгрузки", "3000", "SUMMER",
                185, 65, "15", 88, "H", 25);
        assertThat(WheelCsv.parse(priceBytes()).byArticle("К" + numberOf(partId)))
                .as("колесо не попало в файл до снятия").isNotNull();

        inTenant(() -> jdbc.update("UPDATE part SET is_published = false WHERE id = ?", partId));

        assertThat(WheelCsv.parse(priceBytes()).byArticle("К" + numberOf(partId)))
                .as("снятое с выгрузки колесо всё равно уехало на площадку").isNull();
    }

    /**
     * Счётчик обязан считать тем же условием, что и генератор.
     *
     * <p>Сравнение идёт с числом строк в собранном файле, а не с ожидаемым
     * числом, — так расхождение поймается при любой будущей правке отбора.
     * Отбор по цене отсекает фикстуры соседних проверок: схема у класса
     * общая. Шин две, а запчасть одна — иначе счёт по чужой линии случайно
     * совпал бы с верным.
     */
    @Test
    @DisplayName("Счётчик выгрузки колёс обещает столько, сколько уедет")
    void counterMatchesTheWheelFeed() {
        BigDecimal from = new BigDecimal("987000");
        BigDecimal to = new BigDecimal("988000");

        tyre("Счётчик: шина первая", "987500", "SUMMER", 205, 55, "16", 91, "V", 25);
        tyre("Счётчик: шина вторая", "987600", "SUMMER", 215, 60, "17", 94, "H", 40);
        inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, is_published)
                    VALUES (1, 'Счётчик: фара', 987700, 1000, true) RETURNING id""", Long.class);
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
            return partId;
        });

        long counted = inTenant(() -> accounts.countMatching(
                from, to, null, null, null, false, null, false, "WHEEL",
                Map.of(), Map.of(), false));
        long rows = WheelCsv.parse(priceWithin(from, to)).rows().size();

        assertThat(rows).as("в файл колёс попали не обе шины").isEqualTo(2);
        assertThat(counted)
                .as("счётчик колёсной выгрузки обещает не то число, которое уедет")
                .isEqualTo(rows);
    }

    /**
     * Своё условие по колонке вкладки колёс сужает файл и счётчик.
     *
     * <p>Колонки берутся у самой вкладки колёс, а не повторяются здесь:
     * второй список разошёлся бы с ней на первой правке.
     */
    @Test
    @DisplayName("Условие по колонке колёс сужает файл и счётчик заодно")
    void ownColumnConditionNarrowsTheWheelFeed() {
        BigDecimal from = new BigDecimal("991000");
        BigDecimal to = new BigDecimal("992000");

        Long summer = tyre("Отбор: шина летняя", "991100", "SUMMER", 205, 55, "16", 91, "V", 25);
        Long winter = tyre("Отбор: шина зимняя", "991200", "WINTER_STUDDED", 215, 60, "17", 94, "H", 40);

        DromPriceGenerator.FeedFilter summerOnly = new DromPriceGenerator.FeedFilter(
                from, to, List.of(), List.of(), List.of(), false, List.of(), false,
                Map.of("season", "летняя"), Map.of());

        WheelCsv csv = WheelCsv.parse(inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out, summerOnly, null);
            return out.toByteArray();
        }));

        assertThat(csv.byArticle("К" + numberOf(summer)))
                .as("летняя шина не попала в летний файл").isNotNull();
        assertThat(csv.byArticle("К" + numberOf(winter)))
                .as("в летний файл уехала зимняя шина").isNull();

        long counted = inTenant(() -> accounts.countMatching(
                from, to, null, null, null, false, null, false, "WHEEL",
                Map.of("season", "летняя"), Map.of(), false));
        assertThat(counted)
                .as("счётчик колёс считает не тем условием, каким собирается файл")
                .isEqualTo(csv.rows().size());
    }

    /**
     * Число снимков действует и на колёсный файл.
     *
     * <p>Настройка, доехавшая до прайса запчастей и не доехавшая сюда, даёт
     * два файла одного клиента, собранные по разным правилам.
     */
    @Test
    @DisplayName("Число снимков выгрузки действует и на файл колёс; фото — через «, »")
    void photoLimitAppliesToWheelsToo() {
        BigDecimal from = new BigDecimal("993000");
        BigDecimal to = new BigDecimal("994000");
        Long partId = tyre("Снимки: шина летняя", "993100", "SUMMER", 205, 55, "16", 91, "V", 25);
        addPhotos(partId, 5);

        DromPriceGenerator.FeedFilter mine = new DromPriceGenerator.FeedFilter(
                from, to, List.of(), List.of(), List.of(), false, List.of(), false);

        WheelCsv csv = WheelCsv.parse(inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out, mine, "https://example.org/photo/",
                    new ru.partsflow.publishing.FeedSettings(null, null, 2, null, null,
                            null, null));
            return out.toByteArray();
        }));

        String photos = csv.byArticle("К" + numberOf(partId)).get("Фото");
        assertThat(photos.split(", ", -1))
                .as("файл колёс не знает про число снимков — два файла "
                        + "одного клиента собраны по разным правилам")
                .hasSize(2);
        assertThat(photos).startsWith("https://example.org/photo/");
    }

    /**
     * Дельта колёс — тот же формат и та же строка, что в полном файле,
     * со снимками: пустая колонка «Фото» значила бы «снимков нет».
     */
    @Test
    @DisplayName("Дельта колёс пишет строку позиции так же, как полный файл, и со снимками")
    void deltaIsTheSameRowAsTheFullFile() {
        Long partId = tyre("Дельта: шина", "995100", "WINTER_FRICTION", 205, 55, "16", 91, "V", 25);
        addPhotos(partId, 3);
        DromPriceGenerator.FeedFilter everything = DromPriceGenerator.FeedFilter.everything();

        byte[] full = inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out, everything, "https://example.org/photo/");
            return out.toByteArray();
        });
        byte[] delta = inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeDelta(out, List.of(partId), everything,
                    ru.partsflow.publishing.FeedSettings.none(), "https://example.org/photo/");
            return out.toByteArray();
        });

        String article = "К" + numberOf(partId);
        WheelCsv deltaCsv = WheelCsv.parse(delta);
        assertThat(deltaCsv.header()).isEqualTo(DromWheelWriter.HEADER);
        assertThat(deltaCsv.rows()).hasSize(1);
        assertThat(deltaCsv.byArticle(article)).isEqualTo(WheelCsv.parse(full).byArticle(article));
        assertThat(deltaCsv.byArticle(article).get("Фото").split(", ")).hasSize(3);
    }

    // ---------- фикстуры ----------

    private void addPhotos(Long partId, int count) {
        inTenant(() -> {
            for (int i = 0; i < count; i++) {
                jdbc.update("""
                        INSERT INTO part_photo (part_id, s3_key, sort_order, is_main, status)
                        VALUES (?, ?, ?, ?, 'PROCESSED')""",
                        partId, "t_000096/parts/%d/snimok-%d.jpg".formatted(partId, i),
                        i, i == 0);
            }
            return null;
        });
    }

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
                      String diameter, Integer loadIndex, String speedIndex, Integer wearPercent) {
        Long partId = wheelPart(title, price);
        inTenant(() -> jdbc.update("""
                INSERT INTO part_wheel (part_id, kind, diameter, tyre_width, tyre_height,
                                        season, wear_percent, load_index, speed_index, made_year)
                VALUES (?, 'TYRE', ?::numeric, ?, ?, ?, ?, ?, ?, 2021)""",
                partId, diameter, width, height, season, wearPercent, loadIndex, speedIndex));
        return partId;
    }

    private byte[] priceWithin(BigDecimal from, BigDecimal to) {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out, new DromPriceGenerator.FeedFilter(
                    from, to, List.of(), List.of(), List.of(), false, List.of(), false), null);
            return out.toByteArray();
        });
    }

    private byte[] priceBytes() {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out, DromPriceGenerator.FeedFilter.everything(), null);
            return out.toByteArray();
        });
    }

    /** Строка файла этой позиции: ключ — «К» и номер. */
    private Map<String, String> rowOf(Long partId) {
        Map<String, String> row = WheelCsv.parse(priceBytes()).byArticle("К" + numberOf(partId));
        assertThat(row).as("позиции нет в файле колёс").isNotNull();
        return row;
    }

    private String seasonOf(String season) {
        Long partId = tyre("Сезон " + season, "3500", season, 195, 65, "15", 91, "T", 10);
        return rowOf(partId).get("Сезон шины (лето, зима, шипы)");
    }

    private long numberOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT number FROM part WHERE id = ?", Long.class, partId));
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
