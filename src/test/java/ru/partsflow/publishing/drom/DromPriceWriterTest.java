package ru.partsflow.publishing.drom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.PartCondition;
import ru.partsflow.inventory.VerticalSide;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Писатель прайса запчастей Дрома — формат файла Bazon (задача 0333).
 *
 * <p>Образец — файлы выгрузки Bazon, сохранённые владельцем 19.10.2026
 * ({@code docs/bazon-parity.md}, «Обход 19.10.2026»). Сверяется
 * <b>структура</b>: корень, порядок тегов, пустые элементы, переводы строк,
 * конец файла. Значения, которые решают соседние задачи (номер позиции — 0334,
 * название — 0335, вид детали — 0336, описание — 0337, номера и производитель
 * — 0338), и ссылки на снимки зависят от клиента и сверяются маской.
 */
class DromPriceWriterTest {

    private final DromPriceWriter writer = new DromPriceWriter();

    @Nested
    @DisplayName("Файл повторяет файл Bazon")
    class AsBazon {

        /**
         * Девять позиций из обхода 19.10.2026 — те же состояния, стороны,
         * набор необязательных тегов и число снимков, что у них в файле
         * Bazon. Образец {@code drom/bazon-parts-nine.xml} снят с
         * {@code drom-yardt-parts.xml} и {@code drom-new-parts.xml}: значения
         * клиента в нём заменены маской, слова состояния, наличия и сторон
         * оставлены как есть — их и решает эта задача.
         */
        @Test
        @DisplayName("Девять позиций обхода совпадают с образцом построчно")
        void nineObservedPositionsMatchTheSample() throws Exception {
            String ours = masked(write(
                    part("Кнопка стеклоподъемника Toyota Harrier ACU30 2AZFE 2010 задн. лев. (б/у) 7428048120",
                            "Кнопка стеклоподъемника", PartCondition.CONTRACT,
                            LateralSide.LEFT, LongitudinalSide.REAR, null, null, null,
                            "7428048120", List.of("8403048080"), 9),
                    part("Клапан egr Mercedes-Benz C-class S203 M 112.912 перед. A1121400460",
                            "Клапан egr", PartCondition.CONTRACT,
                            null, LongitudinalSide.FRONT, null, null, "Mercedes",
                            "A1121400460", List.of("555148", "555148B", "1121400060"), 2),
                    part("Шайба маховика Toyota Gaia SXM10 3SFE 1998 3211632011",
                            "Шайба маховика", PartCondition.USED,
                            null, null, null, null, "Toyota", "3211632011", List.of(), 6),
                    part("Дефлектор Honda CR-V RE4 K24A перед. лев.",
                            "Дефлектор", PartCondition.CONTRACT,
                            LateralSide.LEFT, LongitudinalSide.FRONT, "Черный", null, "Honda",
                            null, List.of(), 3),
                    part("Ремень поликлиновой 4PK905",
                            "Ремень поликлиновой", PartCondition.CONTRACT,
                            null, null, null, null, "Pitwork", "4PK905",
                            List.of("38920-PH3-003", "38920-PH1-003"), 4),
                    part("Знак аварийной остановки f-79-8",
                            "Знак аварийной остановки", PartCondition.CONTRACT,
                            null, null, null, null, null, "f-79-8", List.of("oew-1468"), 3),
                    part("Бампер Lexus RX300 MCU31W 1MZFE 03 перед. 52119-48910",
                            "Бампер", PartCondition.NEW,
                            null, LongitudinalSide.FRONT, null, "TY04238BA", "TYG",
                            "52119-48910", List.of("52119-48913", "5211948061"), 15),
                    part("Воздухозаборник Nissan X-Trail TNT31 QR25DE перед. 16554JG30A",
                            "Воздухозаборник", PartCondition.CONTRACT,
                            null, LongitudinalSide.FRONT, null, "16554-JG30A", "Nissan",
                            "16554JG30A", List.of("16554-JG30A"), 9),
                    part("Воздухозаборник Porsche Cayenne 957 M 48.01 перед. лев. 7L5128752",
                            "Воздухозаборник", PartCondition.CONTRACT,
                            LateralSide.LEFT, LongitudinalSide.FRONT, null, null, "VAG",
                            "7L5128752", List.of(), 3)));

            String sample = sample("drom/bazon-parts-nine.xml");

            // Построчно, а не одной строкой: расхождение называет строку,
            // а не «строки не равны» на двух экранах текста.
            assertThat(ours.split("\n", -1))
                    .as("прайс разошёлся с файлом Bazon по структуре")
                    .containsExactly(sample.split("\n", -1));
        }

        @Test
        @DisplayName("Заголовок с пробелом перед «?>», корень parts, в конце </parts> без перевода строки")
        void fileFrameIsBazons() throws Exception {
            String xml = write(offer());

            assertThat(xml)
                    .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<parts>\n<part>\n<id>")
                    .endsWith("</part>\n</parts>");
        }

        @Test
        @DisplayName("Пустой прайс — тот же каркас без позиций")
        void emptyPriceKeepsTheFrame() throws Exception {
            assertThat(write()).isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<parts>\n</parts>");
        }

        @Test
        @DisplayName("Наших тегов, которых у Bazon нет, в файле нет")
        void noTagsBazonDoesNotHave() throws Exception {
            // Решение 1 задачи 0333: формат заменён целиком. Склад, срок,
            // применимость отдельными тегами, вертикальная ось, оригинальность,
            // количество и флаг наличия — всё это у нас считается, но в файл
            // Bazon-вида не пишется.
            DromOffer everything = new DromOffer("P-1", "Стойка Honda Airwave перед. лев.", "Стойка",
                    null, new BigDecimal("100"), BigDecimal.ONE, PartCondition.USED, "KYB",
                    "334388", List.of("DS2130GS"), LateralSide.LEFT, LongitudinalSide.FRONT,
                    VerticalSide.LOWER, "Чёрный", "AM334388K",
                    new DromOffer.Placement("Ткацкая", "в наличии", 2, 4), List.of(),
                    "Honda", "Airwave", "GJ1", "L15A", 2007, true, null, null, null, null,
                    "Оригинал", false);

            String xml = write(everything);

            for (String gone : List.of("offers", "offer", "ordercode", "name", "quantity",
                    "available", "authenticity", "oem_number", "analog_numbers", "lr", "fr", "ud",
                    "brandcars", "modelcars", "bodycars", "engine", "year", "supplier_art",
                    "sklad", "nalichie", "srok_zakaza_ot", "srok_zakaza_do", "photo")) {
                assertThat(xml).as("тег <%s> остался в файле", gone).doesNotContain("<" + gone + ">");
            }
        }
    }

    @Nested
    @DisplayName("Пустые и необязательные элементы")
    class EmptyElements {

        @Test
        @DisplayName("Пустое пишется пустым элементом, а цвет, маркировка и производитель — не пишутся вовсе")
        void emptyIsAnEmptyElementExceptThreeOptional() throws Exception {
            DromOffer bare = new DromOffer("P-1", "Деталь", null, null, new BigDecimal("100"),
                    BigDecimal.ONE, PartCondition.USED, null, null, List.of(),
                    null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null);

            String xml = write(bare);

            assertThat(xml)
                    .contains("<deliveryCost></deliveryCost>\n")
                    .contains("<partname></partname>\n")
                    .contains("<leftRight></leftRight>\n<frontRear></frontRear>\n<manufactureNumber>")
                    .contains("<manufactureNumber></manufactureNumber>\n")
                    .contains("<analogsNumbers></analogsNumbers>\n")
                    .doesNotContain("<color")
                    .doesNotContain("<marking")
                    .doesNotContain("<manufacturer>")
                    .doesNotContain("/>\n");
        }

        @Test
        @DisplayName("Позиция без снимков даёт <photoUrls> и </photoUrls> на соседних строках")
        void noPhotosKeepsTheContainer() throws Exception {
            DromOffer noPhotos = new DromOffer("P-6", "Фара", "Фара", null, new BigDecimal("100"),
                    BigDecimal.ONE, PartCondition.USED, null, null, List.of(),
                    null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null);

            assertThat(write(noPhotos))
                    .contains("<analogsNumbers></analogsNumbers>\n<photoUrls>\n</photoUrls>\n<description/>");
        }

        @Test
        @DisplayName("Снимки — по <photoUrl> на строку внутри <photoUrls>, главный первым")
        void photosAreListedInOrder() throws Exception {
            assertThat(write(offer())).contains("""
                    <photoUrls>
                    <photoUrl>https://parts.example.ru/feeds/drom/yardt/tok/photo/11.jpg</photoUrl>
                    <photoUrl>https://parts.example.ru/feeds/drom/yardt/tok/photo/12.jpg</photoUrl>
                    </photoUrls>
                    """);
        }

        @Test
        @DisplayName("Цвет, маркировка, производитель стоят между сторонами и номером, в этом порядке")
        void optionalTagsAreInBazonOrder() throws Exception {
            assertThat(write(offer())).contains("""
                    <frontRear>front</frontRear>
                    <color>Чёрный</color>
                    <marking>AM334388K</marking>
                    <manufacturer>KYB</manufacturer>
                    <manufactureNumber>334388</manufactureNumber>
                    """);
        }

        @Test
        @DisplayName("Описание пустое — <description/> и </part> на одной строке, как у Bazon")
        void emptyDescriptionIsSelfClosed() throws Exception {
            // Так у Bazon в drom-new-parts.xml (2 позиции из 1 724). У нас
            // пустым описание бывает только у перенесённой с Дрома позиции
            // без текста клиента (задача 0275).
            assertThat(write(vehicle(PartCondition.USED, null, null, null, null, null, null)))
                    .contains("</photoUrls>\n<description/></part>\n</parts>");
        }
    }

    @Nested
    @DisplayName("Значения, которые решает формат")
    class Values {

        @Test
        @DisplayName("Цена — целое; копейки округляются вверх")
        void priceIsWholeRoundedUp() throws Exception {
            // Решение 2 задачи 0333 (19.10.2026): до обхода «Округлять до»
            // у Bazon — вверх до целого. Вниз деталь однажды уйдёт дешевле,
            // чем владелец задал.
            assertThat(value(write(priced("8500.00")), "price")).isEqualTo("8500");
            assertThat(value(write(priced("99.50")), "price")).isEqualTo("100");
            assertThat(value(write(priced("99.01")), "price")).isEqualTo("100");
            assertThat(value(write(priced("297")), "price")).isEqualTo("297");
        }

        @Test
        @DisplayName("Состояние — словами Bazon: контрактный, б/у, новый")
        void conditionWords() throws Exception {
            assertThat(value(write(conditioned(PartCondition.CONTRACT)), "condition"))
                    .as("контрактная ушла не своим словом")
                    .isEqualTo("контрактный");
            assertThat(value(write(conditioned(PartCondition.USED)), "condition")).isEqualTo("б/у");
            assertThat(value(write(conditioned(PartCondition.NEW)), "condition")).isEqualTo("новый");
            // Восстановленной в файлах Bazon не видели; у Дрома поле
            // двузначное по смыслу, и прежде она уходила «Б/у».
            assertThat(value(write(conditioned(PartCondition.REFURBISHED)), "condition"))
                    .isEqualTo("б/у");
        }

        /**
         * Отбор такую позицию не пускает ({@code HAS_CONDITION}, задача 0322), но
         * если она всё же доедет — запись не падает посреди потока: падение здесь
         * — 200 с обрывком файла, то есть «снять все объявления».
         */
        @Test
        @DisplayName("Позиция без состояния пишется с пустым <condition> и без строки о состоянии")
        void offerWithoutConditionIsWrittenWithoutIt() throws Exception {
            String xml = write(conditioned(null), offer());

            assertThat(xml).endsWith("</parts>");
            String first = xml.substring(0, xml.indexOf("</part>"));
            assertThat(first).contains("<condition></condition>").doesNotContain("Состояние:");
        }

        /**
         * {@code state} у всех строк — «В наличии», как у Bazon (задача 0339):
         * что нельзя купить, в файл не пускает отбор генератора, а отложенное,
         * которое выгрузка выгружает, и товар в пути уходят тем же словом.
         */
        @Test
        @DisplayName("Наличие одним словом Bazon у всех строк — «В наличии»")
        void stateIsAlwaysInStock() throws Exception {
            assertThat(value(write(offer()), "state")).isEqualTo("В наличии");
            assertThat(value(write(stocked(BigDecimal.ZERO)), "state"))
                    .as("писатель выбрал слово по остатку: в файле Bazon оно одно")
                    .isEqualTo("В наличии");
            assertThat(value(write(stocked(null)), "state")).isEqualTo("В наличии");
        }

        @Test
        @DisplayName("Стороны — английскими словами Bazon, «обе» — парой через запятую")
        void sidesInEnglish() throws Exception {
            String xml = write(sided(LateralSide.RIGHT, LongitudinalSide.REAR));
            assertThat(value(xml, "leftRight")).isEqualTo("right");
            assertThat(value(xml, "frontRear")).isEqualTo("rear");

            String both = write(sided(LateralSide.BOTH, LongitudinalSide.BOTH));
            assertThat(value(both, "leftRight")).isEqualTo("left,right");
            assertThat(value(both, "frontRear")).isEqualTo("front,rear");
            // Расположения словами в описании больше нет (0337).
            assertThat(value(both, "description")).doesNotContain("левая");
        }

        @Test
        @DisplayName("Опасные символы экранируются, описание уходит в CDATA и «]]>» его не рвёт")
        void escapesAndCdata() throws Exception {
            // Название в файле собирается из вида клиента (0335): опасные знаки — в нём.
            DromOffer tricky = new DromOffer("P-2", "Кронштейн", "Кронштейн",
                    "Размер ]]> & <б/у>", new BigDecimal("100"), BigDecimal.ONE, PartCondition.USED,
                    null, null, List.of(), null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null, null, false,
                    "кронштейн <передний> & правый", null, null, null);

            String xml = write(tricky);

            assertThat(xml)
                    .contains("<title>Кронштейн &lt;передний&gt; &amp; правый</title>")
                    .contains("<description><![CDATA[");
            assertThat(value(xml, "description")).isEqualTo("\nРазмер ]]> & <б/у>");
        }

        @Test
        @DisplayName("Название выгрузки собрано по формуле Bazon из полей позиции, вид детали уходит полем partname")
        void titleAndPartname() throws Exception {
            DromOffer abbreviated = new DromOffer("P-8",
                    "Фара Toyota Camry 2007 перед. лев. (б/у) 81170-33670", "Фара", null,
                    new BigDecimal("100"), BigDecimal.ONE, PartCondition.USED,
                    null, "81170-33670", List.of(),
                    LateralSide.LEFT, LongitudinalSide.FRONT, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null, null, false,
                    "фара", null, "Toyota", "Camry");

            String xml = write(abbreviated);
            // Год не задан ни текстом, ни числом — слова нет.
            assertThat(value(xml, "title")).isEqualTo("Фара Toyota Camry 8117033670, передняя левая");
            assertThat(value(xml, "partname")).isEqualTo("Фара");
            assertThat(value(xml, "id")).isEqualTo("P-8");
        }

    }

    /**
     * Номера, производитель, маркировка и цвет — как у Bazon (задача 0338).
     * Значения обезличены: вид тот же, что в файлах обхода 19.10.2026,
     * цифры не клиента.
     */
    @Nested
    @DisplayName("Номера и производитель — как у Bazon (0338)")
    class Numbers {

        @Test
        @DisplayName("Кросс-номера — через запятую с пробелом, в порядке карточки, варианты написания не склеены")
        void analogsAreJoinedWithCommaAndSpace() throws Exception {
            DromOffer numbered = numbered("12345-6h789",
                    List.of("555000", "555000B", "12345AB000", "12345-AB000"));

            assertThat(value(write(numbered), "analogsNumbers"))
                    .isEqualTo("555000, 555000B, 12345AB000, 12345-AB000");
            assertThat(value(write(numbered), "manufactureNumber"))
                    .as("номер производителя ушёл не так, как вписан")
                    .isEqualTo("12345-6h789");
        }

        /**
         * Решение владельца 19.10.2026 («Всё как у bazon», второй раздел
         * «Ответ владельца» задачи 0338): ввод не ограничен, файл режет
         * по знакам ровно до 1 024 — как Bazon, вместе с обрывом посреди номера.
         *
         * <p>Сто тридцать номеров дают 1 168 знаков — столько же, сколько
         * черновик обхода, — и знак 1 024 приходится на середину номера:
         * «резать по целой записи» дало бы 1 018 знаков и тоже краснит.
         */
        @Test
        @DisplayName("Список длиннее 1 024 знаков режется по знакам ровно до 1 024, посреди номера")
        void longAnalogsAreCutAtExactly1024Chars() throws Exception {
            List<String> many = longAnalogs();
            String joined = String.join(", ", many);
            assertThat(joined).as("контроль: длина набора").hasSize(1168);
            assertThat(joined.substring(1018, 1030))
                    .as("контроль: знак 1 024 — посреди номера, а не на границе записи")
                    .isEqualTo(", 0000113, 0");

            String written = value(write(numbered("A-1", many)), "analogsNumbers");

            assertThat(written)
                    .as("строка кросс-номеров ушла не ровно 1 024 знаками")
                    .hasSize(DromPriceWriter.ANALOGS_LIMIT)
                    .isEqualTo(joined.substring(0, 1024))
                    .endsWith(", 0000112, 0000");
        }

        @Test
        @DisplayName("Список короче предела и ровно в предел уходит целиком")
        void shortAnalogsAreKept() throws Exception {
            List<String> exactly = new ArrayList<>(longAnalogs().subList(0, 113));
            String head = String.join(", ", exactly);
            // Добиваем до ровно 1 024 знаков последним номером.
            exactly.add("X".repeat(1024 - head.length() - 2));
            assertThat(String.join(", ", exactly)).hasSize(1024);

            assertThat(value(write(numbered("A-1", exactly)), "analogsNumbers"))
                    .isEqualTo(String.join(", ", exactly));
            assertThat(value(write(numbered("A-1", List.of("555000"))), "analogsNumbers"))
                    .isEqualTo("555000");
        }

        @Test
        @DisplayName("Производитель, маркировка и цвет — своими тегами, как вписаны")
        void manufacturerMarkingAndColorAsTyped() throws Exception {
            DromOffer porsche = new DromOffer("P-9", "Воздухозаборник", "Воздухозаборник", null,
                    new BigDecimal("100"), BigDecimal.ONE, PartCondition.CONTRACT,
                    "VAG", "GE4T-00-000A 92", List.of("12345AB000"), null, null, null,
                    "Серебро (199)", "12345-AB000", null, List.of(),
                    "Porsche", "Cayenne", null, null, null, true, null, null, null, null);

            assertThat(write(porsche)).contains("""
                    <frontRear></frontRear>
                    <color>Серебро (199)</color>
                    <marking>12345-AB000</marking>
                    <manufacturer>VAG</manufacturer>
                    <manufactureNumber>GE4T-00-000A 92</manufactureNumber>
                    <analogsNumbers>12345AB000</analogsNumbers>
                    <photoUrls>
                    """);
        }
    }

    /** 1 168 знаков через «, »: первый номер длиннее, три последних короче. */
    private static List<String> longAnalogs() {
        List<String> many = new ArrayList<>();
        many.add("HEAD-00000");
        for (int i = 1; i < 130; i++) {
            many.add(String.format(i >= 127 ? "%06d" : "%07d", i));
        }
        return many;
    }

    private static DromOffer numbered(String oem, List<String> analogs) {
        return new DromOffer("P-30", "Ремень", "Ремень", null, new BigDecimal("100"),
                BigDecimal.ONE, PartCondition.USED, null, oem, analogs,
                null, null, null, null, null, null, List.of(),
                null, null, null, null, null, true, null, null, null, null);
    }

    /**
     * Состав описания — задача 0337; здесь проверяется, что прежняя сборка
     * доезжает в новый элемент без потерь.
     */
    @Nested
    @DisplayName("Описание — устройство Bazon (0337)")
    class Description {

        @Test
        @DisplayName("Четыре блока: перевод строки в начале, пустая строка между, конца нет")
        void blocksInTheOrderOfTheSample() throws Exception {
            assertThat(value(write(withText("С накладкой. Контракт.", "ОПЛАТА НАЛИЧНЫМИ")), "description"))
                    .isEqualTo("\nС накладкой. Контракт.\n\n"
                            + "Запчасть снята с: Nissan Teana 2008 J32 VQ25DE\n\n"
                            + "ОПЛАТА НАЛИЧНЫМИ");
        }

        @Test
        @DisplayName("Без комментария и текстового блока — только «снята с», без лишней пустой строки")
        void emptyBlocksTakeTheirSeparatorsWithThem() throws Exception {
            assertThat(value(write(withText(null, null)), "description"))
                    .isEqualTo("\nЗапчасть снята с: Nissan Teana 2008 J32 VQ25DE");
            assertThat(value(write(withText("  ", "")), "description"))
                    .isEqualTo("\nЗапчасть снята с: Nissan Teana 2008 J32 VQ25DE");
        }

        @Test
        @DisplayName("Комментарий и текстовый блок — как набраны, переводы строк внутри целы")
        void textsAreVerbatim() throws Exception {
            // Разбор XML сам сводит CRLF к LF, поэтому «как набран» проверяется по байтам.
            String xml = write(withText("Первая.\r\nВторая.", "Блок\nв две строки"));
            assertThat(xml).contains("<![CDATA[\nПервая.\r\nВторая.\n\n")
                    .contains("\n\nБлок\nв две строки]]>");
        }

        @Test
        @DisplayName("Нет в описании ни номера, ни состояния, ни расположения, ни видео")
        void nothingWeInvented() throws Exception {
            assertThat(value(write(withText(null, "Блок")), "description"))
                    .doesNotContain("Номер производителя")
                    .doesNotContain("Состояние")
                    .doesNotContain("Расположение")
                    .doesNotContain("Видео")
                    .doesNotContain("Подходит на");
        }

        @Test
        @DisplayName("Нет блока «снята с» у новой детали, у позиции без марки или модели")
        void noVehicleBlock() throws Exception {
            DromOffer fresh = vehicle(PartCondition.NEW, "Nissan", "Teana", 2008, null, null, null);
            assertThat(value(write(fresh), "description")).isEmpty();

            assertThat(value(write(vehicle(PartCondition.USED, null, "Teana", null, null, null, null)),
                    "description")).isEmpty();
            assertThat(value(write(vehicle(PartCondition.USED, "Nissan", null, null, null, null, null)),
                    "description")).isEmpty();
        }

        @Test
        @DisplayName("«Снята с»: первые значения списков, год текстом, без года и кузова — без слов")
        void vehicleWords() throws Exception {
            DromOffer lists = vehicle(PartCondition.CONTRACT, "Lincoln,Ford", "Town Car,F150",
                    null, "03-08", "J32, J33", "VQ25DE,VQ35DE");
            assertThat(value(write(lists), "description"))
                    .isEqualTo("\nЗапчасть снята с: Lincoln Town Car 03-08 J32 VQ25DE");

            assertThat(value(write(vehicle(PartCondition.USED, "Nissan", "Teana", null, null, null, null)),
                    "description")).isEqualTo("\nЗапчасть снята с: Nissan Teana");
        }

        @Test
        @DisplayName("Приписки про ожидание и установку — только когда выгрузка их включила")
        void switchedNotesOnly() throws Exception {
            DromOffer awaited = new DromOffer("P-13", "Бампер", "Бампер",
                    "Оригинал, без сколов.", new BigDecimal("12000"), BigDecimal.ONE,
                    PartCondition.USED, null, null, List.of(),
                    null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true,
                    "Блок", "https://example.org/v/1",
                    "Стоимость установки: 1000 р.",
                    "Ожидается поступление");

            assertThat(value(write(awaited), "description"))
                    .isEqualTo("\nОжидается поступление\n\nОригинал, без сколов.\n\n"
                            + "Блок\n\nСтоимость установки: 1000 р.");
            assertThat(value(write(withText("К", null)), "description")).doesNotContain("Ожидается");
        }

        @Test
        @DisplayName("Позиция без единого блока — пустой элемент")
        void emptyDescription() throws Exception {
            DromOffer bare = vehicle(PartCondition.USED, null, null, null, null, null, null);
            assertThat(write(bare)).contains("<description/>");
        }
    }

    /**
     * Позиция, перенесённая с объявления клиента на Дроме (задача 0275):
     * заголовок и описание — его, и уходят они дословно.
     */
    @Nested
    @DisplayName("Текст клиента, перенесённый с Дрома")
    class ClientText {

        @Test
        @DisplayName("Заголовок клиента в файл не идёт — название собрано по формуле Bazon (0335)")
        void nameIsTheFormulaNotTheClientsTitle() throws Exception {
            for (String title : List.of(
                    "Карданный вал передний (Пробег 130т. км)Toyota Land Cruiser Prado 120",
                    "Рулевая рейка ( правый руль 110т. км) Toyota Land Cruiser Prado 120",
                    "Фара (б/у) перед. лев. Toyota Camry  (контракт)")) {
                assertThat(value(write(transferred(title, null)), "title"))
                        .as("заголовок клиента ушёл в файл, а не формула")
                        .isEqualTo("Фара Toyota Land Cruiser Prado 2008 3714060380, передняя левая");
            }
        }

        @Test
        @DisplayName("Описание клиента — «Комментарий»: как есть, с переводами строк, без чужих строк")
        void descriptionIsVerbatim() throws Exception {
            String text = "Подходит на автомобиль с левым и правым рулем.\nХорошее состоянии.";

            assertThat(value(write(transferred("Дверь", text)), "description"))
                    .as("комментарий клиента поправлен или разбавлен чужими строками")
                    .isEqualTo("\n" + text + "\n\n"
                            + "Запчасть снята с: Toyota Land Cruiser Prado 2008\n\n"
                            + "Текстовый блок");
        }

        @Test
        @DisplayName("Длинное описание не режется")
        void longDescriptionIsNotCut() throws Exception {
            String text = "Ж".repeat(4_999) + "\n" + "к".repeat(1_000);

            assertThat(value(write(transferred("Двигатель", text)), "description"))
                    .startsWith("\n" + text + "\n\n");
        }

        @Test
        @DisplayName("Позиция без комментария: текстовый блок, и никакого «Состояния»")
        void ownPositionsAreUnchanged() throws Exception {
            DromOffer ours = new DromOffer("P-1", "Фара (б/у) перед. лев. Toyota Camry",
                    "Фара", null, new BigDecimal("100"), BigDecimal.ONE, PartCondition.USED,
                    null, null, List.of(), null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, "Текстовый блок", null, null, null);

            String xml = write(ours);
            // Полей названия нет — уходит вид справочника; хранимое название не течёт.
            assertThat(value(xml, "title")).isEqualTo("Фара");
            assertThat(value(xml, "description")).isEqualTo("\nТекстовый блок");
        }
    }

    @Nested
    @DisplayName("Потоковость")
    class Streaming {

        @Test
        @DisplayName("Байты уходят в поток до того, как кончились позиции")
        void writesBeforeIteratorIsExhausted() throws Exception {
            // Сторожевой тест инварианта из CLAUDE.md: прайс пишется потоково.
            CountingStream out = new CountingStream();
            long[] writtenWhenThirdRequested = {-1};

            Iterator<DromOffer> lazy = new Iterator<>() {
                private int produced;

                @Override
                public boolean hasNext() {
                    return produced < 2_000;
                }

                @Override
                public DromOffer next() {
                    if (produced == 3) {
                        writtenWhenThirdRequested[0] = out.count;
                    }
                    produced++;
                    return offer();
                }
            };

            int written = writer.write(out, lazy);

            assertThat(written).isEqualTo(2_000);
            assertThat(writtenWhenThirdRequested[0])
                    .as("к четвёртой позиции в поток не ушло ни байта — прайс собирается в памяти")
                    .isGreaterThan(0);
        }
    }

    // ---------- вспомогательное ----------

    /** Позиция с набором полей одной из девяти позиций обхода. */
    private static DromOffer part(String title, String kind, PartCondition condition,
                                  LateralSide lr, LongitudinalSide fr, String color,
                                  String marking, String manufacturer, String oem,
                                  List<String> analogs, int photos) {
        List<String> links = IntStream.rangeClosed(1, photos)
                .mapToObj(i -> "https://parts.example.ru/feeds/drom/yardt/tok/photo/" + i + ".jpg")
                .toList();
        return new DromOffer("P-" + photos, title, kind, null, new BigDecimal("500.00"),
                BigDecimal.ONE, condition, manufacturer, oem, analogs, lr, fr, null,
                color, marking, new DromOffer.Placement("Ткацкая", null, null, null), links,
                "Toyota", "Harrier", null, null, null, true, "ОПЛАТА НАЛИЧНЫМИ", null, null, null);
    }

    /**
     * Маска значений, которые эта задача не решает: номер позиции, название,
     * вид детали, номера, производитель, маркировка, цвет, ссылки и описание.
     * Цена — «ЦЕЛОЕ», только если она целое число: «500.00» маской не станет.
     */
    private static String masked(String xml) {
        String out = xml.replaceAll("(?s)<description><!\\[CDATA\\[.*?]]></description>",
                "<description>…</description>");
        for (String tag : List.of("id", "title", "partname", "color", "marking", "manufacturer",
                "manufactureNumber", "analogsNumbers", "photoUrl")) {
            out = out.replaceAll("<" + tag + ">[^<]+</" + tag + ">", "<" + tag + ">…</" + tag + ">");
        }
        return out.replaceAll("<price>\\d+</price>", "<price>ЦЕЛОЕ</price>");
    }

    private static String sample(String resource) throws IOException {
        try (InputStream in = DromPriceWriterTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("образец %s", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static DromOffer offer() {
        return new DromOffer(
                "P-100500",
                "Амортизатор передний левый",
                "Амортизатор",
                "Снят с Honda Airwave, пробег 80 000",
                new BigDecimal("8500"),
                BigDecimal.ONE,
                PartCondition.USED,
                "KYB",
                "334388",
                List.of("4853033281", "DS2130GS"),
                LateralSide.LEFT,
                LongitudinalSide.FRONT,
                VerticalSide.LOWER,
                "Чёрный",
                "AM334388K",
                new DromOffer.Placement("Ткацкая", null, null, null),
                List.of("https://parts.example.ru/feeds/drom/yardt/tok/photo/11.jpg",
                        "https://parts.example.ru/feeds/drom/yardt/tok/photo/12.jpg"),
                "Honda",
                "Airwave",
                "GJ1",
                "L15A",
                2007,
                true,
                null,
                null,
                null,
                null);
    }

    private static DromOffer priced(String price) {
        return new DromOffer("P-20", "Стартер", "Стартер", null, new BigDecimal(price),
                BigDecimal.ONE, PartCondition.USED, null, null, List.of(),
                null, null, null, null, null, null, List.of(),
                null, null, null, null, null, true, null, null, null, null);
    }

    private static DromOffer conditioned(PartCondition condition) {
        return new DromOffer("P-21", "Стартер", "Стартер", null, new BigDecimal("100"),
                BigDecimal.ONE, condition, null, null, List.of(),
                null, null, null, null, null, null, List.of(),
                null, null, null, null, null, true, null, null, null, null);
    }

    private static DromOffer stocked(BigDecimal free) {
        return new DromOffer("P-22", "Стартер", "Стартер", null, new BigDecimal("100"),
                free, PartCondition.USED, null, null, List.of(),
                null, null, null, null, null, null, List.of(),
                null, null, null, null, null, true, null, null, null, null);
    }

    private static DromOffer sided(LateralSide lr, LongitudinalSide fr) {
        return new DromOffer("P-23", "Фара", "Фара", null, new BigDecimal("100"),
                BigDecimal.ONE, PartCondition.USED, null, null, List.of(),
                lr, fr, null, null, null, null, List.of(),
                null, null, null, null, null, true, null, null, null, null);
    }

    /**
     * Перенесённая позиция со всем, что прайс умеет дописывать: текстовым
     * блоком, видео, установкой, «ожидается», машиной и стороной. Ни одно
     * из них в её текст попасть не должно.
     */
    private static DromOffer transferred(String title, String description) {
        return new DromOffer("P-777", title, "Фара", description,
                new BigDecimal("1500"), BigDecimal.ONE, PartCondition.USED,
                null, "3714060380", List.of(), LateralSide.LEFT, LongitudinalSide.FRONT,
                null, null, null, null, List.of(),
                "Toyota", "Land Cruiser Prado", null, null, 2008, true,
                "Текстовый блок", "https://video.example/1",
                null, null, null, true,
                "фара", null, "Toyota", "Land Cruiser Prado");
    }

    private static DromOffer vehicle(PartCondition condition, String brand, String model,
                                     Integer year, String yearText, String body, String engine) {
        return new DromOffer("P-1", "Фара", "Фара", null,
                new BigDecimal("100"), BigDecimal.ONE, condition,
                null, null, List.of(), null, null, null, null, null, null, List.of(),
                brand, model, body, engine, year, true, null, null, null, null,
                null, false, "Фара", yearText, brand, model);
    }

    private static DromOffer withText(String comment, String textBlock) {
        return new DromOffer("P-2", "Фара", "Фара", comment,
                new BigDecimal("100"), BigDecimal.ONE, PartCondition.CONTRACT,
                null, null, List.of(), null, null, null, null, null, null, List.of(),
                "Nissan", "Teana", "J32", "VQ25DE", 2008, true, textBlock, null, null, null,
                null, false, "Фара", null, "Nissan", "Teana");
    }

    /** Текст единственного элемента с этим именем — как его прочтёт площадка. */
    private static String value(String xml, String element) throws Exception {
        org.w3c.dom.NodeList found = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getElementsByTagName(element);
        assertThat(found.getLength()).as("элемент <%s> в прайсе", element).isEqualTo(1);
        return found.item(0).getTextContent();
    }

    private String write(DromOffer... offers) throws XMLStreamException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out, new ArrayList<>(List.of(offers)).iterator());
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Считает, сколько байт реально ушло в поток к каждому моменту. */
    private static class CountingStream extends OutputStream {

        private long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            count += len;
        }
    }
}
