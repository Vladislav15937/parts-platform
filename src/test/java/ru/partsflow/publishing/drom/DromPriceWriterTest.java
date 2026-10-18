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
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DromPriceWriterTest {

    private final DromPriceWriter writer = new DromPriceWriter();

    @Nested
    @DisplayName("Структура прайса")
    class Structure {

        @Test
        @DisplayName("Позиция пишется каноническими элементами Дрома")
        void writesCanonicalElements() throws Exception {
            String xml = write(offer());

            assertThat(xml)
                    .contains("<offers>")
                    .contains("<ordercode>P-100500</ordercode>")
                    .contains("<name>Амортизатор передний левый</name>")
                    .contains("<partname>Амортизатор</partname>")
                    .contains("<price>8500</price>")
                    .contains("<condition>Б/у</condition>")
                    .contains("<manufacturer>KYB</manufacturer>")
                    .contains("<oem_number>334388</oem_number>")
                    .contains("</offers>");
        }

        @Test
        @DisplayName("Три оси стороны пишутся отдельными полями")
        void writesThreeSideAxes() throws Exception {
            String xml = write(offer());

            assertThat(xml)
                    .contains("<lr>лево</lr>")
                    .contains("<fr>перед</fr>")
                    .contains("<ud>низ</ud>");
        }

        @Test
        @DisplayName("Сторона «обе» по каждой оси пишется парой слов и в описании «и»-словами (задача 0283)")
        void writesBothSidesOnEveryAxis() throws Exception {
            String xml = write(offerSides(LateralSide.BOTH, LongitudinalSide.BOTH, VerticalSide.BOTH));

            assertThat(xml)
                    .contains("<lr>лево,право</lr>")
                    .contains("<fr>перед,зад</fr>")
                    .contains("<ud>верх,низ</ud>")
                    .contains("левая и правая")
                    .contains("передняя и задняя")
                    .contains("верхняя и нижняя");
        }

        @Test
        @DisplayName("Применимость едет отдельными тегами, а не в наименовании")
        void writesApplicability() throws Exception {
            // Эталон Дрома для «Автозапчастей» просит это первым: по марке
            // и модели покупатель фильтрует, а из заголовка площадка их
            // не разбирает.
            String xml = write(offer());

            assertThat(xml)
                    .contains("<brandcars>Honda</brandcars>")
                    .contains("<modelcars>Airwave</modelcars>")
                    .contains("<bodycars>GJ1</bodycars>")
                    .contains("<engine>L15A</engine>")
                    .contains("<year>2007</year>");
        }

        @Test
        @DisplayName("У контрактной детали года нет, и ноль вместо него не пишется")
        void contractPartHasNoYear() throws Exception {
            // Машины у неё нет вовсе, а подходит она к нескольким: марки
            // и модели едут списком, кузов с двигателем — никак. «Год 0»
            // на площадке означал бы машину, которой не существует.
            DromOffer contract = new DromOffer("P-7", "Стартер", "Стартер", null, new BigDecimal("100"),
                    BigDecimal.ONE, PartCondition.USED, null, null, List.of(),
                    null, null, null, null, null, null, List.of(),
                    "Toyota,Lexus", "Camry,Windom", null, null, null, false, null, null, null, null);

            String xml = write(contract);

            assertThat(xml)
                    .contains("<brandcars>Toyota,Lexus</brandcars>")
                    .contains("<modelcars>Camry,Windom</modelcars>")
                    .doesNotContain("<year>")
                    .doesNotContain("<bodycars>")
                    .doesNotContain("<engine>");
        }

        @Test
        @DisplayName("Аналоги идут одной строкой через запятую")
        void joinsAnalogNumbers() throws Exception {
            String xml = write(offer());

            assertThat(xml).contains("<analog_numbers>4853033281,DS2130GS</analog_numbers>");
        }

        @Test
        @DisplayName("Пустые поля не пишутся вовсе")
        void skipsEmptyFields() throws Exception {
            DromOffer bare = new DromOffer("P-1", "Деталь", "Деталь", null, new BigDecimal("100"),
                    BigDecimal.ONE, PartCondition.USED, null, null, List.of(),
                    null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null);

            String xml = write(bare);

            assertThat(xml)
                    .doesNotContain("<manufacturer>")
                    .doesNotContain("<oem_number>")
                    .doesNotContain("<analog_numbers>")
                    .doesNotContain("<lr>")
                    .doesNotContain("<color>")
                    .doesNotContain("<brandcars>");

            // Описание — исключение из правила «пустое не пишем»: их
            // минимальный формат требует его, а у детали с разборки своего
            // описания обычно нет. Собирается из того, что мы знаем, —
            // здесь известно только состояние.
            assertThat(xml).contains("<description>Состояние: б/у.</description>");
        }

        /**
         * Отбор такую позицию не пускает ({@code HAS_CONDITION}, задача 0322), но
         * если она всё же доедет — запись не падает посреди потока: падение здесь
         * — 200 с обрывком файла, то есть «снять все объявления».
         */
        @Test
        @DisplayName("Позиция без состояния дописывается без элемента и без строки о состоянии")
        void offerWithoutConditionIsWrittenWithoutIt() throws Exception {
            DromOffer unknown = new DromOffer("P-2", "Деталь", "Деталь", null, new BigDecimal("100"),
                    BigDecimal.ONE, null, null, null, List.of(),
                    null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null);

            String xml = write(unknown, offer());

            assertThat(xml)
                    .contains("<ordercode>P-2</ordercode>")
                    .contains("<ordercode>P-100500</ordercode>")
                    .contains("</offers>");
            String first = xml.substring(xml.indexOf("<ordercode>P-2"), xml.indexOf("<ordercode>P-100500"));
            assertThat(first).doesNotContain("<condition>").doesNotContain("Состояние:");
        }

        @Test
        @DisplayName("Вид детали уходит отдельным полем")
        void writesPartKind() throws Exception {
            // По нему площадка кладёт товар в раздел; заголовок она разбирает,
            // а не читает, и угадывает не всегда.
            assertThat(write(offer())).contains("<partname>Амортизатор</partname>");
        }

        @Test
        @DisplayName("Склад уходит отдельным полем")
        void writesWarehouse() throws Exception {
            // Покупателю это «куда ехать»: у клиента филиалы на разных концах
            // города, и без поля он узнаёт адрес только звонком.
            assertThat(write(offer())).contains("<sklad>Ткацкая</sklad>");
        }

        @Test
        @DisplayName("Позиция без склада не получает пустого элемента")
        void skipsWarehouseWhenUnknown() throws Exception {
            // Проданная позиция остаётся в прайсе недоступной, но лежать ей
            // уже негде: пустой <sklad> означал бы склад с пустым именем.
            DromOffer soldOut = new DromOffer("P-12", "Стартер", "Стартер", null,
                    new BigDecimal("100"), BigDecimal.ZERO, PartCondition.USED,
                    null, null, List.of(), null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null);

            assertThat(write(soldOut)).doesNotContain("<sklad>");
        }

        @Test
        @DisplayName("Текст наличия и вилка дней уходят отдельными тегами")
        void writesAvailabilityAndLeadTime() throws Exception {
            // Отдельными тегами, а не фразой в описании: эталон площадки
            // просит этого прямо, и разбирает она именно теги.
            String xml = write(offerAt(
                    new DromOffer.Placement("54 YARD", "под заказ", 2, 4)));

            assertThat(xml)
                    .contains("<sklad>54 YARD</sklad>")
                    .contains("<nalichie>под заказ</nalichie>")
                    .contains("<srok_zakaza_ot>2</srok_zakaza_ot>")
                    .contains("<srok_zakaza_do>4</srok_zakaza_do>");
        }

        @Test
        @DisplayName("Ноль дней не пишется, а в вилке «0–4» нижняя граница остаётся")
        void zeroDaysAreNotNoise() throws Exception {
            // Для покупателя «ноль дней» и «дней не назвали» — одно и то же:
            // ждать не надо. А вот у вилки «0–4» значима и нижняя граница —
            // она говорит «может быть, и сегодня».
            assertThat(write(offerAt(new DromOffer.Placement("Ткацкая", "в наличии", 0, 0))))
                    .contains("<nalichie>в наличии</nalichie>")
                    .doesNotContain("<srok_zakaza_ot>")
                    .doesNotContain("<srok_zakaza_do>");

            assertThat(write(offerAt(new DromOffer.Placement("Ткацкая", "в наличии", 0, 4))))
                    .contains("<srok_zakaza_ot>0</srok_zakaza_ot>")
                    .contains("<srok_zakaza_do>4</srok_zakaza_do>");
        }

        @Test
        @DisplayName("Склад без заданного наличия не получает пустого элемента")
        void skipsAvailabilityWhenUnset() throws Exception {
            // Пустой <nalichie> площадка читает как заполненный пустым
            // значением, а выдуманное нами «в наличии» уехало бы покупателю
            // от имени разборки, которая этого не обещала.
            assertThat(write(offerAt(new DromOffer.Placement("Ткацкая", null, null, null))))
                    .contains("<sklad>Ткацкая</sklad>")
                    .doesNotContain("<nalichie>")
                    .doesNotContain("<srok_zakaza_");
        }

        @Test
        @DisplayName("В наименовании выгрузки нет сокращений и повторов")
        void nameIsPlain() throws Exception {
            DromOffer abbreviated = new DromOffer("P-8",
                    "Фара Toyota Camry 2007 перед. лев. (б/у) 81170-33670", "Фара", null,
                    new BigDecimal("100"), BigDecimal.ONE, PartCondition.USED,
                    null, "81170-33670", List.of(),
                    LateralSide.LEFT, LongitudinalSide.FRONT, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null);

            // «Названия максимально простые и понятные, без сокращений» —
            // требование площадки, от него зависит раздел. Сторона
            // и состояние при этом не теряются: они уехали своими полями.
            // С задачи 0331 то же и с номером: он уехал в oem_number.
            String xml = write(abbreviated);
            assertThat(xml)
                    .contains("<name>Фара Toyota Camry 2007</name>")
                    .contains("<oem_number>81170-33670</oem_number>")
                    .contains("<lr>лево</lr>")
                    .contains("<condition>Б/у</condition>");
        }

        @Test
        @DisplayName("Собранное описание называет машину, сторону и номер")
        void assemblesDescriptionFromWhatWeKnow() throws Exception {
            // Своего описания у детали с разборки обычно нет: приёмщику
            // некогда его писать. Тогда оно собирается из известного —
            // и только из него.
            DromOffer bare = new DromOffer("P-10", "Амортизатор", "Амортизатор", null,
                    new BigDecimal("8500"), BigDecimal.ONE, PartCondition.USED,
                    "KYB", "334388", List.of(),
                    LateralSide.LEFT, LongitudinalSide.FRONT, VerticalSide.LOWER,
                    null, null, null, List.of(),
                    "Honda", "Airwave", "GJ1", "L15A", 2007, true, null, null, null, null);

            assertThat(write(bare))
                    .contains("Снято с: Honda Airwave GJ1 L15A 2007.")
                    .contains("Расположение: передняя, левая, нижняя.")
                    .contains("Номер производителя: 334388.")
                    .contains("Состояние: б/у.");

            // У контрактной формулировка другая: её никто ни с чего не снимал,
            // марка у неё из применимости, и «снято с Toyota Camry» было бы
            // неправдой — заметил бы покупатель, а не мы.
            DromOffer contract = new DromOffer("P-11", "Стартер", "Стартер", null,
                    new BigDecimal("100"), BigDecimal.ONE, PartCondition.USED,
                    null, null, List.of(), null, null, null, null, null, null, List.of(),
                    "Toyota,Lexus", "Camry,Windom", null, null, null, false, null, null, null, null);
            assertThat(write(contract))
                    .contains("Подходит на: Toyota,Lexus Camry,Windom.")
                    .doesNotContain("Снято с:");

            // Своё описание, если оно есть, не подменяется собранным.
            assertThat(write(offer())).contains("Снят с Honda Airwave, пробег 80 000");
        }

        @Test
        @DisplayName("«Ожидается поступление» стоит первой строкой описания")
        void expectedNoteComesFirst() throws Exception {
            // Все остальные приписки рассказывают про деталь и дописываются
            // в конец. Эта — про то, когда деталь можно забрать, и ниже
            // описания её прочитают последней или не прочитают вовсе:
            // покупатель приедет за товаром, который ещё в контейнере.
            DromOffer awaited = new DromOffer("P-13", "Бампер", "Бампер",
                    "Оригинал, без сколов.", new BigDecimal("12000"), BigDecimal.ONE,
                    PartCondition.USED, null, null, List.of(),
                    null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true,
                    "Отправляем транспортной компанией.",
                    "https://example.org/v/1",
                    "Стоимость установки: 1000 р.",
                    "Ожидается поступление");

            String description = write(awaited)
                    .replaceAll("(?s).*<description>", "")
                    .replaceAll("(?s)</description>.*", "");

            assertThat(description)
                    .as("приписка про ожидание встала не первой строкой описания")
                    .startsWith("Ожидается поступление")
                    // Порядок остального не меняется: описание владельца,
                    // текстовый блок, установка и ссылка на ролик последней.
                    .isEqualTo("""
                            Ожидается поступление
                            Оригинал, без сколов.
                            Отправляем транспортной компанией.
                            Стоимость установки: 1000 р.
                            Видео: https://example.org/v/1""");
        }

        @Test
        @DisplayName("Остаток уходит числом: ноль — это удаление по их же документации")
        void writesQuantity() throws Exception {
            assertThat(write(offer())).contains("<quantity>1</quantity>");

            DromOffer soldOut = new DromOffer("P-9", "Стартер", "Стартер", null,
                    new BigDecimal("100"), BigDecimal.ZERO, PartCondition.USED,
                    null, null, List.of(), null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null);
            assertThat(write(soldOut)).contains("<quantity>0</quantity>");
        }

        @Test
        @DisplayName("Опасные символы экранируются, а не ломают документ")
        void escapesSpecialCharacters() throws Exception {
            DromOffer tricky = new DromOffer("P-2", "Кронштейн <передний> & правый", "Кронштейн", null,
                    new BigDecimal("100"), BigDecimal.ONE, PartCondition.USED,
                    null, null, List.of(), null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null);

            String xml = write(tricky);

            assertThat(xml).contains("Кронштейн &lt;передний&gt; &amp; правый");
        }

        @Test
        @DisplayName("Снимки уходят ссылками, главный первым")
        void writesPhotoLinks() throws Exception {
            String xml = write(offer());

            // Повторяющимся элементом, а не строкой через запятую: разбор
            // по разделителю ломается на первой же ссылке с запятой внутри.
            assertThat(xml)
                    .contains("<photo>https://parts.example.ru/feeds/drom/yardt/tok/photo/11.jpg</photo>")
                    .contains("<photo>https://parts.example.ru/feeds/drom/yardt/tok/photo/12.jpg</photo>");
            // Порядок — это обложка объявления: площадка берёт первую ссылку.
            assertThat(xml.indexOf("photo/11.jpg")).isLessThan(xml.indexOf("photo/12.jpg"));
        }

        @Test
        @DisplayName("Позиция без снимков не получает пустого элемента")
        void skipsPhotosWhenThereAreNone() throws Exception {
            DromOffer noPhotos = new DromOffer("P-6", "Фара", "Фара", null, new BigDecimal("100"),
                    BigDecimal.ONE, PartCondition.USED, null, null, List.of(),
                    null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null);

            assertThat(write(noPhotos)).doesNotContain("<photo>");
        }

        @Test
        @DisplayName("Новая запчасть уходит как новая")
        void mapsNewCondition() throws Exception {
            DromOffer brandNew = new DromOffer("P-3", "Фильтр", "Фильтр", null, new BigDecimal("500"),
                    BigDecimal.ONE, PartCondition.NEW, null, null, List.of(),
                    null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null);

            assertThat(write(brandNew)).contains("<condition>Новое</condition>");
        }
    }

    @Nested
    @DisplayName("Наличие")
    class Availability {

        @Test
        @DisplayName("Свободный остаток даёт available = true")
        void freeStockIsAvailable() throws Exception {
            assertThat(write(offer())).contains("<available>true</available>");
        }

        @Test
        @DisplayName("Без свободного остатка позиция остаётся в прайсе, но недоступной")
        void soldOutStaysInPriceAsUnavailable() throws Exception {
            DromOffer soldOut = new DromOffer("P-4", "Стартер", "Стартер", null, new BigDecimal("5000"),
                    BigDecimal.ZERO, PartCondition.USED, null, null, List.of(),
                    null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null);

            String xml = write(soldOut);

            // Позицию нельзя просто выкинуть из прайса: у Дрома объявление
            // тогда исчезнет вместе с накопленными просмотрами.
            assertThat(xml).contains("<ordercode>P-4</ordercode>");
            assertThat(xml).contains("<available>false</available>");
        }

        @Test
        @DisplayName("Неизвестный остаток считается отсутствием, а не наличием")
        void unknownStockIsNotAvailable() throws Exception {
            DromOffer unknown = new DromOffer("P-5", "Бампер", "Бампер", null, new BigDecimal("100"),
                    null, PartCondition.USED, null, null, List.of(),
                    null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null, null, null);

            assertThat(write(unknown)).contains("<available>false</available>");
        }
    }

    /**
     * Позиция, перенесённая с объявления клиента на Дроме (задача 0275):
     * заголовок и описание — его, и уходят они дословно.
     *
     * <p>Значения сверяются разбором XML, а не поиском подстроки: «дословно»
     * значит и перевод строки, и пробел после скобки, а подстрока молчит
     * о том, что стоит вокруг неё.
     */
    @Nested
    @DisplayName("Текст клиента, перенесённый с Дрома")
    class ClientText {

        @Test
        @DisplayName("Заголовок клиента без номера уходит побайтно — без чистки «(б/у)» и сокращений")
        void nameIsVerbatim() throws Exception {
            // Первые два — заголовки из образца задачи 0272: слитная скобка
            // и пробел после открывающей. Третий — то, что чистка вырезала бы:
            // пометка состояния и «лев.»/«перед.» в заголовке клиента.
            for (String title : List.of(
                    "Карданный вал передний (Пробег 130т. км)Toyota Land Cruiser Prado 120",
                    "Рулевая рейка ( правый руль 110т. км) Toyota Land Cruiser Prado 120",
                    "Фара (б/у) перед. лев. Toyota Camry  (контракт)")) {
                assertThat(only(write(transferred(title, null)), "name"))
                        .as("заголовок клиента ушёл не дословно")
                        .isEqualTo(title);
            }
        }

        @Test
        @DisplayName("Из заголовка клиента срезан только номер позиции, прочее байт в байт (0331)")
        void onlyTheNumberIsCutFromTheClientTitle() throws Exception {
            // Ответ владельца 18.10.2026 «Всё да», вопрос 1: номер у перенесённых
            // тоже срезается, остальное по 0275 — как у клиента: «(б/у)»,
            // «перед. лев.» и двойной пробел остаются.
            String title = "Фара (б/у) перед. лев. Toyota  Camry 3714060380";
            assertThat(only(write(transferred(title, null)), "name"))
                    .as("из заголовка клиента срезано не только номер")
                    .isEqualTo("Фара (б/у) перед. лев. Toyota  Camry");

            assertThat(only(write(withNumbers("Фара (контракт) 8414012530, 8414012531",
                    "8414012530", List.of("8414012531"), true)), "name"))
                    .isEqualTo("Фара (контракт)");
        }

        @Test
        @DisplayName("Описание клиента уходит как есть, с переводами строк и без приписок")
        void descriptionIsVerbatim() throws Exception {
            String text = "Подходит на автомобиль с левым и правым рулем.\nХорошее состоянии.";

            assertThat(only(write(transferred("Дверь", text)), "description"))
                    .as("описание клиента дописано или поправлено")
                    .isEqualTo(text);
        }

        @Test
        @DisplayName("Нет описания у клиента — нет его и в прайсе, ничего не собирается")
        void noDescriptionStaysEmpty() throws Exception {
            // У клиента 2 из 7 просмотренных карточек без описания: собранное
            // «Снято с … Состояние: б/у.» было бы текстом, которого у него нет.
            assertThat(write(transferred("Расширитель крыла", null)))
                    .doesNotContain("<description>")
                    .doesNotContain("Состояние:");
        }

        @Test
        @DisplayName("Длинное описание не режется")
        void longDescriptionIsNotCut() throws Exception {
            String text = "Ж".repeat(4_999) + "\n" + "к".repeat(1_000);

            assertThat(only(write(transferred("Двигатель", text)), "description"))
                    .isEqualTo(text);
        }

        @Test
        @DisplayName("Прочим позициям чистка заголовка и сборка описания остаются")
        void ownPositionsAreUnchanged() throws Exception {
            // Контроль: тот же заголовок без признака «текст клиента» чистится,
            // а пустое описание собирается — иначе проверки выше зеленели бы
            // на писателе, который перестал чистить всем.
            DromOffer ours = new DromOffer("P-1", "Фара (б/у) перед. лев. Toyota Camry",
                    "Фара", null, new BigDecimal("100"), BigDecimal.ONE, PartCondition.USED,
                    null, null, List.of(), null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, "Текстовый блок", null, null, null);

            String xml = write(ours);
            assertThat(only(xml, "name")).isEqualTo("Фара Toyota Camry");
            assertThat(only(xml, "description")).isEqualTo("Состояние: б/у.\nТекстовый блок");
        }

        /**
         * Перенесённая позиция со всем, что прайс умеет дописывать: текстовым
         * блоком, видео, установкой, «ожидается», машиной и стороной. Ни одно
         * из них в её текст попасть не должно.
         */
        private DromOffer transferred(String title, String description) {
            return new DromOffer("P-777", title, "Фара", description,
                    new BigDecimal("1500"), BigDecimal.ONE, PartCondition.USED,
                    null, "3714060380", List.of(), LateralSide.LEFT, LongitudinalSide.FRONT,
                    null, null, null, null, List.of(),
                    "Toyota", "Land Cruiser Prado", null, null, 2008, true,
                    "Текстовый блок", "https://video.example/1",
                    "Стоимость установки: 500 р.", "Ожидается поступление", null, true);
        }

        /** Текст единственного элемента с этим именем — как его прочтёт площадка. */
        private String only(String xml, String element) throws Exception {
            org.w3c.dom.NodeList found = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getElementsByTagName(element);
            assertThat(found.getLength()).as("элемент <%s> в прайсе", element).isEqualTo(1);
            return found.item(0).getTextContent();
        }
    }

    @Nested
    @DisplayName("Номера в наименовании (задача 0331)")
    class NumbersInName {

        @Test
        @DisplayName("Номер позиции срезается с конца названия, год, модель и кузов остаются")
        void numbersOfThePositionAreCut() throws Exception {
            // Таблица примеров из задачи 0331, строка в строку.
            assertName("Фара левая Toyota Camry 8414012530", "8414012530", List.of())
                    .isEqualTo("Фара левая Toyota Camry");
            assertName("Фара левая Toyota Camry 8414012530, 8414012531",
                    "8414012530", List.of("8414012531"))
                    .isEqualTo("Фара левая Toyota Camry");
            assertName("Бампер передний ВАЗ 2106 5201", "5201", List.of())
                    .isEqualTo("Бампер передний ВАЗ 2106");
            assertName("Фара BMW E46 1999 63126904", "63126904", List.of())
                    .isEqualTo("Фара BMW E46 1999");
            assertName("Бампер Mazda 3 BK 2006", null, List.of())
                    .isEqualTo("Бампер Mazda 3 BK 2006");
        }

        @Test
        @DisplayName("Номер узнаётся в написании заголовка: дефис, пробел, русская «С», регистр")
        void numberIsRecognisedAsWritten() throws Exception {
            assertName("Фара Toyota Camry 2007 81170-33670", "8117033670", List.of())
                    .isEqualTo("Фара Toyota Camry 2007");
            assertName("Фара Toyota Camry 81170 33670", "81170-33670", List.of())
                    .isEqualTo("Фара Toyota Camry");
            assertName("Вал Nissan 8518100С", "8518100C", List.of())
                    .isEqualTo("Вал Nissan");
            assertName("Фара Honda fp-31", "FP-31", List.of())
                    .isEqualTo("Фара Honda");
            // Основной, сохранённый до 0323 одной строкой с запятой.
            assertName("Фара левая 8414012530; 8414012531", "8414012530, 8414012531", List.of())
                    .isEqualTo("Фара левая");
        }

        @Test
        @DisplayName("Запятая и пробелы перед номером уходят вместе с ним")
        void separatorBeforeTheNumberGoes() throws Exception {
            assertName("Фара левая, 8414012530", "8414012530", List.of())
                    .isEqualTo("Фара левая");
            assertName("Фара левая 8414012530 ,", "8414012530", List.of())
                    .isEqualTo("Фара левая");
        }

        @Test
        @DisplayName("Срезается только хвост и каждый номер один раз")
        void onlyTheTailAndOnce() throws Exception {
            // Номер в середине — не тот, что дописал генератор: остаётся.
            assertName("Блок 8414012530 Toyota Camry", "8414012530", List.of())
                    .isEqualTo("Блок 8414012530 Toyota Camry");
            // Номер совпал с годом: срезан последний, год в середине цел.
            assertName("Дверь Kia Rio 2012 2012", "2012", List.of())
                    .isEqualTo("Дверь Kia Rio 2012");
            // «2106 перед» — не номер, хотя без русских букв привёлся бы к «2106».
            assertName("Бампер ВАЗ 2106 перед", "2106", List.of())
                    .isEqualTo("Бампер ВАЗ 2106 перед");
            // Чужой номер, похожий на номер, не срезается.
            assertName("Фара Toyota 99999", "8414012530", List.of())
                    .isEqualTo("Фара Toyota 99999");
        }

        @Test
        @DisplayName("Название из одного номера не становится пустым")
        void nameNeverBecomesEmpty() throws Exception {
            assertName("8414012530", "8414012530", List.of()).isEqualTo("8414012530");
        }

        @Test
        @DisplayName("Номера уходят своими тегами, описание с «Номер производителя» не меняется")
        void numbersStayInTheirFields() throws Exception {
            String xml = write(withNumbers("Фара левая Toyota Camry 8414012530, 8414012531",
                    "8414012530", List.of("8414012531"), false));

            assertThat(xml)
                    .contains("<name>Фара левая Toyota Camry</name>")
                    .contains("<oem_number>8414012530</oem_number>")
                    .contains("<analog_numbers>8414012531</analog_numbers>")
                    .contains("Номер производителя: 8414012530.");
        }

        private org.assertj.core.api.AbstractStringAssert<?> assertName(
                String title, String oem, List<String> analogs) throws Exception {
            String xml = write(withNumbers(title, oem, analogs, false));
            int from = xml.indexOf("<name>") + "<name>".length();
            return assertThat(xml.substring(from, xml.indexOf("</name>")))
                    .as("наименование в прайсе для «%s»", title);
        }
    }

    /** Позиция с заданным заголовком и номерами; {@code clientText} — перенос с Дрома. */
    static DromOffer withNumbers(String title, String oem, List<String> analogs,
                                 boolean clientText) {
        return new DromOffer("P-331", title, "Фара", null, new BigDecimal("1000"),
                BigDecimal.ONE, PartCondition.USED, null, oem, analogs, null, null, null,
                null, null, null, List.of(), null, null, null, null, null, true,
                null, null, null, null, null, clientText);
    }

    @Nested
    @DisplayName("Потоковость")
    class Streaming {

        @Test
        @DisplayName("Байты уходят в поток до того, как кончились позиции")
        void writesBeforeIteratorIsExhausted() throws Exception {
            // Сторожевой тест инварианта из CLAUDE.md: прайс пишется потоково.
            // Если кто-то заменит StAX на шаблонизатор или сборку строки,
            // к моменту третьей позиции в потоке всё ещё будет пусто.
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

        @Test
        @DisplayName("Пустой прайс — валидный документ, а не пустой файл")
        void emptyPriceIsStillValidXml() throws Exception {
            String xml = write();

            assertThat(xml).contains("<offers>").contains("</offers>");
        }
    }

    // ---------- вспомогательное ----------

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

    /**
     * Та же позиция, но с другим размещением.
     *
     * <p>Поля перечислены руками, а не скопированы «как-нибудь»: у записи
     * нет копирующего конструктора, и компилятор здесь единственный, кто
     * заметит перепутанные местами соседи одного типа.
     */
    private static DromOffer offerAt(DromOffer.Placement placement) {
        DromOffer base = offer();
        return new DromOffer(
                base.orderCode(), base.name(), base.partKind(), base.description(),
                base.price(), base.availableQty(), base.condition(), base.manufacturer(),
                base.oemNumber(), base.analogNumbers(), base.lateralSide(),
                base.longitudinalSide(), base.verticalSide(), base.color(), base.marking(),
                placement, base.photos(), base.carBrand(), base.carModel(), base.bodyCode(),
                base.engineCode(), base.year(), base.fromDonor(), base.textBlock(),
                base.videoUrl(), base.installationNote(), base.expectedNote());
    }

    private static DromOffer offerSides(LateralSide lr, LongitudinalSide fr, VerticalSide ud) {
        DromOffer base = offer();
        return new DromOffer(
                base.orderCode(), base.name(), base.partKind(), null,
                base.price(), base.availableQty(), base.condition(), base.manufacturer(),
                base.oemNumber(), base.analogNumbers(), lr, fr, ud, base.color(),
                base.marking(), base.placement(), base.photos(), base.carBrand(),
                base.carModel(), base.bodyCode(), base.engineCode(), base.year(),
                base.fromDonor(), base.textBlock(), base.videoUrl(), base.installationNote(),
                base.expectedNote());
    }

    private String write(DromOffer... offers) throws XMLStreamException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out, List.of(offers).iterator());
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
