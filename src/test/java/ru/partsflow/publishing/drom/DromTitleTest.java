package ru.partsflow.publishing.drom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.PartCondition;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Название позиции в файле Дрома — формула Bazon (задача 0335). Образцы —
 * строки реальных файлов Bazon, сверка «до знака».
 */
class DromTitleTest {

    private static final LongitudinalSide F = LongitudinalSide.FRONT;
    private static final LongitudinalSide R = LongitudinalSide.REAR;
    private static final LateralSide L = LateralSide.LEFT;

    private static DromOffer offer(String kind, String brand, String model, String yearText,
                                   Integer year, String oem, String body, String engine,
                                   LongitudinalSide fr, LateralSide lr) {
        return new DromOffer("1", "хранимое название склада", null, null, BigDecimal.TEN,
                BigDecimal.ONE, PartCondition.USED, null, oem, List.of(), lr, fr, null,
                null, null, null, List.of(), "ВСЯ, СПИСКОМ", "ВСЕ, СПИСКОМ", body, engine,
                year, true, null, null, null, null, null, false,
                kind, yearText, brand, model);
    }

    @Test
    @DisplayName("Четырнадцать позиций Bazon собираются до знака")
    void fourteenSamplesFromBazon() {
        assertTitle("Кольца форсунок Isuzu ELF 1234567890 NKR81E 4JJ1",
                offer("кольца форсунок", "Isuzu", "ELF", null, null, "1234567890", "NKR81E", "4JJ1", null, null));
        assertTitle("Пыльник рулевой колонки Nissan X-Trail 123456A100 NT30 QR20DE",
                offer("пыльник рулевой колонки", "Nissan", "X-Trail", null, null, "12345-6A100", "NT30", "QR20DE", null, null));
        assertTitle("Воздухозаборник Nissan X-Trail 234567B200A TNT31 QR25DE, передний",
                offer("воздухозаборник", "Nissan", "X-Trail", null, null, "23456-7B200A", "TNT31", "QR25DE", F, null));
        assertTitle("Воздухозаборник Porsche Cayenne 8K1234567 957 M 48.01, передний левый",
                offer("воздухозаборник", "Porsche", "Cayenne", null, null, "8k1234567", "957", "M 48.01", F, L));
        assertTitle("Клапан egr Mercedes-Benz C-class C240 A2345678901 S203 M 112.912, передний",
                offer("клапан egr", "Mercedes-Benz", "C-class C240", null, null, "A2345678901", "S203", "M 112.912", F, null));
        assertTitle("Дефлектор Honda CR-V RE4 K24A, передний левый",
                offer("дефлектор", "Honda", "CR-V", null, null, null, "RE4", "K24A", F, L));
        assertTitle("Блок круиз-контроля Lincoln Navigator F12F3C456AC XY100 1997-2002 Г.В. ENG54L",
                offer("блок круиз-контроля", "Lincoln,Ford", "Navigator", null, null, "F12F3C456AC", "XY100", "1997-2002 Г.В. ENG54L", null, null));
        assertTitle("Карман Mazda Millenia TA1234567C",
                offer("карман", "Mazda", "Millenia", null, null, "TA1234567C", null, null, null, null));
        assertTitle("Шайба маховика Toyota Gaia 1998 3456789012 SXM10 3SFE",
                offer("шайба маховика", "Toyota", "Gaia", "1998", null, "3456789012", "SXM10", "3SFE", null, null));
        assertTitle("Кнопка стеклоподъемника Toyota Harrier 2010 4567890123 ACU30 2AZFE, задняя левая",
                offer("кнопка стеклоподъемника", "Toyota", "Harrier", "2010", null, "4567890123", "ACU30", "2AZFE", R, L));
        assertTitle("Знак аварийной остановки F123",
                offer("знак аварийной остановки", null, null, null, null, "f-12-3", null, null, null, null));
        assertTitle("Ремень поликлиновой 5PK123",
                offer("ремень поликлиновой", null, null, null, null, "5PK123", null, null, null, null));
        assertTitle("Бампер Lexus RX300 03-08 5678901234 MCU31W 1MZFE, передний",
                offer("бампер", "Lexus", "RX300", "03-08", null, "5678901234", "MCU31W", "1MZFE", F, null));
        assertTitle("Фара Mitsubishi Pajero Sport 04-09 6789A012XA K94W 4D56, передняя левая",
                offer("фара", "Mitsubishi", "Pajero Sport", "04-09", null, "6789A012XA", "K94W", "4D56", F, L));
    }

    @Test
    @DisplayName("Номер: всё, кроме букв и цифр, вон; заглавными; русские буквы остаются")
    void numberIsStrippedAndUppercased() {
        assertThat(DromTitle.number("12345-6A100")).isEqualTo("123456A100");
        assertThat(DromTitle.number("f-12-3")).isEqualTo("F123");
        assertThat(DromTitle.number("0516.L8")).isEqualTo("0516L8");
        assertThat(DromTitle.number("1 474 443")).isEqualTo("1474443");
        assertThat(DromTitle.number("7l0820743a")).isEqualTo("7L0820743A");
        assertThat(DromTitle.number("ав-12")).isEqualTo("АВ12");
        assertThat(DromTitle.number(" - ")).isNull();
        assertThat(DromTitle.number(null)).isNull();
    }

    @Test
    @DisplayName("Год: текст из переноса сильнее своего числа; нет текста — число; нет вовсе — слова нет")
    void yearTextBeatsManualYear() {
        assertTitle("Бампер Lexus RX300 03-08 5678901234",
                offer("бампер", "Lexus", "RX300", "03-08", 2005, "5678901234", null, null, null, null));
        assertTitle("Бампер Lexus RX300 2005 5678901234",
                offer("бампер", "Lexus", "RX300", null, 2005, "5678901234", null, null, null, null));
        assertTitle("Бампер Lexus RX300 5678901234",
                offer("бампер", "Lexus", "RX300", null, null, "5678901234", null, null, null, null));
    }

    @Test
    @DisplayName("Вид: как ввёл клиент, первая буква заглавная, пробелы схлопнуты; нет клиентского — справочный")
    void kindIsAsTheClientTyped() {
        assertTitle("Блок коррекции фар X",
                offer("блок коррекции  фар", "X", null, null, null, null, null, null, null, null));
        DromOffer fromCatalogue = new DromOffer("1", "t", "Фара", null, BigDecimal.TEN,
                BigDecimal.ONE, PartCondition.USED, null, null, List.of(), null, null, null,
                null, null, null, List.of(), null, null, null, null, null, true, null, null,
                null, null, null, false, null, null, null, null);
        assertThat(DromTitle.of(fromCatalogue)).isEqualTo("Фара");
    }

    @Test
    @DisplayName("Род и число положения: окончание и исключения Bazon")
    void positionAgreesWithTheFirstWord() {
        assertThat(pos("Воздухозаборник")).isEqualTo("передний левый");
        assertThat(pos("Фара")).isEqualTo("передняя левая");
        assertThat(pos("Крыло")).isEqualTo("переднее левое");
        assertThat(pos("Колодки тормозные")).isEqualTo("передние левые");
        // исключения словаря сильнее окончания
        assertThat(pos("Дверь")).isEqualTo("передняя левая");
        assertThat(pos("Педаль")).isEqualTo("передняя левая");
        for (String w : List.of("Консоль", "Панель", "Полуось", "Фальшпанель", "Фонарь",
                "Ограничитель", "Усилитель", "Глушитель")) {
            assertThat(pos(w)).as(w).isEqualTo("передний левый");
        }
        for (String w : List.of("Реле", "Табло", "Торпедо", "Сиденье", "Кольцо")) {
            assertThat(pos(w)).as(w).isEqualTo("переднее левое");
        }
        for (String w : List.of("Коврики", "Часы", "Брызговики", "Ветровики", "Накладки",
                "Противотуманки", "Чехлы")) {
            assertThat(pos(w)).as(w).isEqualTo("передние левые");
        }
        assertThat(DromTitle.position("Фара", R, LateralSide.RIGHT)).isEqualTo("задняя правая");
        // обе стороны и нет стороны — слова нет
        assertThat(DromTitle.position("Фара", LongitudinalSide.BOTH, LateralSide.BOTH)).isEmpty();
        assertThat(DromTitle.position("Фара", null, null)).isEmpty();
    }

    @Test
    @DisplayName("Первое значение списка: «Lincoln,Ford» — Lincoln")
    void firstOfTheList() {
        assertThat(DromTitle.first("Lincoln,Ford")).isEqualTo("Lincoln");
        assertThat(DromTitle.first(" , Ford")).isEqualTo("Ford");
        assertThat(DromTitle.first(" ")).isNull();
    }

    @Test
    @DisplayName("Список машин применимости в название не попадает — только поля названия")
    void applicabilityListDoesNotLeak() {
        assertThat(DromTitle.of(offer("фара", "Toyota", "Camry", null, null, null, null, null, null, null)))
                .doesNotContain("СПИСКОМ");
    }

    private static String pos(String kind) {
        return DromTitle.position(kind, F, L);
    }

    private static void assertTitle(String expected, DromOffer offer) {
        assertThat(DromTitle.of(offer)).isEqualTo(expected);
    }
}
