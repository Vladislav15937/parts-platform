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
                    "Toyota,Lexus", "Camry,Windom", null, null, null, false, null, null);

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
                    null, null, null, null, null, true, null, null);

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
                    null, null, null, null, null, true, null, null);

            assertThat(write(soldOut)).doesNotContain("<sklad>");
        }

        @Test
        @DisplayName("В наименовании выгрузки нет сокращений и повторов")
        void nameIsPlain() throws Exception {
            DromOffer abbreviated = new DromOffer("P-8",
                    "Фара Toyota Camry 2007 перед. лев. (б/у) 81170-33670", "Фара", null,
                    new BigDecimal("100"), BigDecimal.ONE, PartCondition.USED,
                    null, "81170-33670", List.of(),
                    LateralSide.LEFT, LongitudinalSide.FRONT, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null);

            // «Названия максимально простые и понятные, без сокращений» —
            // требование площадки, от него зависит раздел. Сторона
            // и состояние при этом не теряются: они уехали своими полями.
            String xml = write(abbreviated);
            assertThat(xml)
                    .contains("<name>Фара Toyota Camry 2007 81170-33670</name>")
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
                    "Honda", "Airwave", "GJ1", "L15A", 2007, true, null, null);

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
                    "Toyota,Lexus", "Camry,Windom", null, null, null, false, null, null);
            assertThat(write(contract))
                    .contains("Подходит на: Toyota,Lexus Camry,Windom.")
                    .doesNotContain("Снято с:");

            // Своё описание, если оно есть, не подменяется собранным.
            assertThat(write(offer())).contains("Снят с Honda Airwave, пробег 80 000");
        }

        @Test
        @DisplayName("Остаток уходит числом: ноль — это удаление по их же документации")
        void writesQuantity() throws Exception {
            assertThat(write(offer())).contains("<quantity>1</quantity>");

            DromOffer soldOut = new DromOffer("P-9", "Стартер", "Стартер", null,
                    new BigDecimal("100"), BigDecimal.ZERO, PartCondition.USED,
                    null, null, List.of(), null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null);
            assertThat(write(soldOut)).contains("<quantity>0</quantity>");
        }

        @Test
        @DisplayName("Опасные символы экранируются, а не ломают документ")
        void escapesSpecialCharacters() throws Exception {
            DromOffer tricky = new DromOffer("P-2", "Кронштейн <передний> & правый", "Кронштейн", null,
                    new BigDecimal("100"), BigDecimal.ONE, PartCondition.USED,
                    null, null, List.of(), null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null);

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
                    null, null, null, null, null, true, null, null);

            assertThat(write(noPhotos)).doesNotContain("<photo>");
        }

        @Test
        @DisplayName("Новая запчасть уходит как новая")
        void mapsNewCondition() throws Exception {
            DromOffer brandNew = new DromOffer("P-3", "Фильтр", "Фильтр", null, new BigDecimal("500"),
                    BigDecimal.ONE, PartCondition.NEW, null, null, List.of(),
                    null, null, null, null, null, null, List.of(),
                    null, null, null, null, null, true, null, null);

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
                    null, null, null, null, null, true, null, null);

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
                    null, null, null, null, null, true, null, null);

            assertThat(write(unknown)).contains("<available>false</available>");
        }
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
                "Ткацкая",
                List.of("https://parts.example.ru/feeds/drom/yardt/tok/photo/11.jpg",
                        "https://parts.example.ru/feeds/drom/yardt/tok/photo/12.jpg"),
                "Honda",
                "Airwave",
                "GJ1",
                "L15A",
                2007,
                true,
                null,
                null);
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
