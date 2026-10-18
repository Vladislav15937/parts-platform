package ru.partsflow.migration.excel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.inventory.WheelService.WheelRequest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор колёсной строки файла переноса (задача 0291) — на ячейках «Прочих полей»
 * из файла клиента Strikexz дословно (лист «Объявления»), с проверкой каждого поля
 * из таблицы критерия 3.
 */
class DromWheelCardTest {

    /** Строка 349: шина, в наименовании другой размер, чем в карточке Дрома. */
    static final String TYRE_349 = """
            model~tireFirmAndModel [model~tireFirmAndModel]: Dunlop Grandtrek PT3
            Количество шин в неделимом комплекте [inSetQuantity]: 4 шт
            Всего комплектов [quantity]: 1 шт
            Год выпуска шин [year]: 2023
            Протектор [wheelSeason]: Летние
            Износ [wheelTireWear]: До 5 %
            marking [marking]: 265/65 R19
            Посадочный диаметр [marking]: 19"
            Ширина профиля [marking]: 265 мм
            Высота профиля [marking]: 65 %
            Каркас [marking]: радиальный
            Тип шины [predestination]: Обычная""";

    /** Строка 466: колёса в сборе — шинные и дисковые свойства разом. */
    static final String ASSEMBLY_466 = """
            model~tireFirmAndModel [model~tireFirmAndModel]: Bridgestone Blizzak DM-Z3
            Количество колёс в комплекте [inSetQuantity]: 4 шт
            Количество комплектов [quantity]: 1 шт
            Год выпуска шин [year]: 2006
            Протектор [wheelSeason]: Зимние
            Износ [wheelTireWear]: 10 %
            marking [marking]: 265/65 R17
            Диаметр [marking]: 17"
            Ширина профиля [marking]: 265 мм
            Высота профиля [marking]: 65 %
            Каркас [marking]: радиальный
            Тип [diskType]: Литой
            Модель шины [tireFirmAndModel]: Bridgestone Blizzak DM-Z3
            Тип шины [predestination]: Обычная
            Модель диска [discFirmAndModel]: noname""";

    /** Строка 576: шина с индексами в маркировке и без пробела перед R. */
    static final String TYRE_576 = """
            model~tireFirmAndModel [model~tireFirmAndModel]: Maxxis Bravo HT-770
            Количество шин в неделимом комплекте [inSetQuantity]: 4 шт
            Всего комплектов [quantity]: 1 шт
            Год выпуска шин [year]: 2024
            Протектор [wheelSeason]: Всесезонные
            Износ [wheelTireWear]: 5 %
            marking [marking]: 265/60R18 114H
            Посадочный диаметр [marking]: 18"
            Ширина профиля [marking]: 265 мм
            Высота профиля [marking]: 60 %
            Каркас [marking]: радиальный
            Тип шины [predestination]: Обычная""";

    /** Строка 946: диски; вылет дробный, ширина — ещё и строкой без ключа. */
    static final String DISC_946 = """
            model~tireFirmAndModel [model~tireFirmAndModel]: Toyota
            Количество дисков в комплекте [inSetQuantity]: 4 шт
            Количество комплектов [quantity]: 1 шт
            Диаметр [wheelDiameter]: 17"
            Ширина диска [discParameters]: Ширина диска 7.5" Вылет (ET) 37.5 мм.
            Сверловка (PCD) [wheelPcd]: 6x139.7
            Тип [diskType]: Литой
            Ширина диска: 7.5\"""";

    /** Строка 993: маркировка через обратную косую. */
    static final String TYRE_993 = """
            model~tireFirmAndModel [model~tireFirmAndModel]: Bridgestone Dueler H/L
            Количество шин в неделимом комплекте [inSetQuantity]: 4 шт
            Всего комплектов [quantity]: 1 шт
            Год выпуска шин [year]: 2013
            Протектор [wheelSeason]: Летние
            Износ [wheelTireWear]: 50 %
            marking [marking]: 265\\65 R17
            Посадочный диаметр [marking]: 17"
            Ширина профиля [marking]: 265 мм
            Высота профиля [marking]: 65 %
            Каркас [marking]: радиальный
            Тип шины [predestination]: Обычная""";

    static final String SECTION_TYRES = "Шины и диски → Шины → Шины Dunlop Grandtrek → Dunlop Grandtrek PT3";

    @Test
    @DisplayName("Распознаются три вида из файла клиента; тормозной диск, запаска и секретки — нет")
    void recognition() {
        assertThat(DromWheelCard.recognizes(SECTION_TYRES, TYRE_349)).isTrue();
        assertThat(DromWheelCard.recognizes("Шины и диски", ASSEMBLY_466)).isTrue();
        assertThat(DromWheelCard.recognizes("Шины и диски → Диски", DISC_946)).isTrue();
        // По разделу без «Прочих полей» — тоже колесо: подраздел «Шины», «Диски» или нет его.
        assertThat(DromWheelCard.recognizes("Шины и диски → Шины → Шины Nokian", null)).isTrue();
        assertThat(DromWheelCard.recognizes("Шины и диски", null)).isTrue();
        // Служебная строка Дрома узнаёт колесо и без раздела — и под чужим разделом
        // (критерий 1: «model~tireFirmAndModel ИЛИ раздел»). Строки клиента выше
        // проходят и по разделу, поэтому одни они правило про служебную строку не держат.
        assertThat(DromWheelCard.recognizes(null, TYRE_349))
                .as("шина без раздела, со служебной строкой Дрома, не узнана").isTrue();
        assertThat(DromWheelCard.recognizes("Автозапчасти → Расходники и комплектующие", DISC_946))
                .as("диски со служебной строкой Дрома под чужим разделом не узнаны").isTrue();

        // Строка 970 — аксессуар в том же разделе.
        assertThat(DromWheelCard.recognizes("Шины и диски → Аксессуары для колес", null))
                .as("«Гайка на колесо (Секретки)» принята за колесо").isFalse();
        // «Диск тормозной …» и «Колесо запасное …» — запчасти, по наименованию не судим.
        assertThat(DromWheelCard.recognizes("Автозапчасти → Тормозная система", null))
                .as("«Диск тормозной» принят за колесо").isFalse();
        assertThat(DromWheelCard.recognizes("Автозапчасти → Расходники и комплектующие", null))
                .as("«Колесо запасное» принято за колесо").isFalse();
        assertThat(DromWheelCard.recognizes(null,
                "Модель магнитолы [model]: Toyota NHZT-W58\nРазмер [size]: 2 DIN — 178x100 мм"))
                .as("магнитола с «Прочими полями» принята за колесо").isFalse();
    }

    @Test
    @DisplayName("Строка 349: шина 265/65 R19 Dunlop Grandtrek PT3, расхождение с наименованием названо")
    void tyre349() {
        DromWheelCard.Parsed parsed = DromWheelCard.parse(
                "Автошина Dunlop Grandtrek PT5 235/55 R19", SECTION_TYRES, TYRE_349);
        WheelRequest r = parsed.request();
        assertThat(r.kind()).isEqualTo("TYRE");
        assertThat(r.tyreWidth()).isEqualTo(265);
        assertThat(r.tyreHeight()).isEqualTo(65);
        assertThat(r.diameter()).isEqualByComparingTo("19");
        assertThat(r.construction()).isEqualTo("R");
        assertThat(r.markingType()).isEqualTo("METRIC");
        assertThat(r.loadIndex()).isNull();
        assertThat(r.speedIndex()).isNull();
        assertThat(r.season()).isEqualTo("SUMMER");
        // «До 5 %» — 5 %: процентом, как у клиента (задача 0340).
        assertThat(r.wearPercent()).isEqualTo(5);
        assertThat(r.madeYear()).isEqualTo(2023);
        assertThat(r.brand()).isEqualTo("Dunlop");
        assertThat(r.model()).isEqualTo("Grandtrek PT3");
        assertThat(r.tyreType()).isEqualTo("Обычная");
        assertThat(r.discBrand()).isNull();
        assertThat(parsed.setPieces()).isEqualTo(4);
        assertThat(parsed.sets()).isEqualTo(1);
        assertThat(parsed.problems())
                .containsExactly("Размер: в наименовании 235/55 R19, в «Прочих полях» 265/65 R19"
                        + " — взят из «Прочих полей»");
    }

    @Test
    @DisplayName("Строка 466: колесо в сборе — шина Bridgestone на литом диске noname")
    void assembly466() {
        DromWheelCard.Parsed parsed = DromWheelCard.parse(
                "Колеса Bridgestone Blizzak DM-Z3 265/65 R17", "Шины и диски", ASSEMBLY_466);
        WheelRequest r = parsed.request();
        assertThat(r.kind()).isEqualTo("ASSEMBLY");
        assertThat(r.tyreWidth()).isEqualTo(265);
        assertThat(r.tyreHeight()).isEqualTo(65);
        assertThat(r.diameter()).isEqualByComparingTo("17");
        // «Зимние» без слова о шипах — просто зимняя: шипы не выдумываются.
        assertThat(r.season()).isEqualTo("WINTER");
        assertThat(r.wearPercent()).isEqualTo(10);
        assertThat(r.madeYear()).isEqualTo(2006);
        assertThat(r.brand()).isEqualTo("Bridgestone");
        assertThat(r.model()).isEqualTo("Blizzak DM-Z3");
        assertThat(r.discType()).isEqualTo("Литой");
        assertThat(r.discModel()).as("«noname» спрятан").isEqualTo("noname");
        assertThat(parsed.setPieces()).isEqualTo(4);
        assertThat(parsed.sets()).isEqualTo(1);
        assertThat(parsed.problems()).isEmpty();
    }

    @Test
    @DisplayName("Строка 576: «265/60R18 114H» — нагрузка 114, скорость H")
    void tyre576() {
        DromWheelCard.Parsed parsed = DromWheelCard.parse(
                "Автошина Maxxis Bravo HT-770 265/60R18 114H", SECTION_TYRES, TYRE_576);
        WheelRequest r = parsed.request();
        assertThat(r.kind()).isEqualTo("TYRE");
        assertThat(r.tyreWidth()).isEqualTo(265);
        assertThat(r.tyreHeight()).isEqualTo(60);
        assertThat(r.diameter()).isEqualByComparingTo("18");
        assertThat(r.loadIndex()).isEqualTo(114);
        assertThat(r.speedIndex()).isEqualTo("H");
        assertThat(r.season()).isEqualTo("ALL_SEASON");
        assertThat(r.wearPercent()).isEqualTo(5);
        assertThat(r.madeYear()).isEqualTo(2024);
        assertThat(r.brand()).isEqualTo("Maxxis");
        assertThat(r.model()).isEqualTo("Bravo HT-770");
        assertThat(parsed.problems()).isEmpty();
    }

    @Test
    @DisplayName("Строка 946: диски 7.5×17 6x139.7, вылет 37.5 округлён до 38, производитель Toyota")
    void disc946() {
        DromWheelCard.Parsed parsed = DromWheelCard.parse(
                "Диски Toyota Land Cruiser Prado 150", "Шины и диски → Диски", DISC_946);
        WheelRequest r = parsed.request();
        assertThat(r.kind()).isEqualTo("DISC");
        assertThat(r.diameter()).isEqualByComparingTo("17");
        assertThat(r.discWidth()).isEqualByComparingTo(new BigDecimal("7.5"));
        assertThat(r.offsetMm()).isEqualTo(38);
        assertThat(r.boltPattern()).isEqualTo("6x139.7");
        assertThat(r.discType()).isEqualTo("Литой");
        assertThat(r.discBrand()).isEqualTo("Toyota");
        assertThat(r.discModel()).isNull();
        assertThat(r.brand()).as("производитель диска лёг в производителя шины").isNull();
        assertThat(r.tyreWidth()).isNull();
        assertThat(r.season()).isNull();
        assertThat(parsed.setPieces()).isEqualTo(4);
        assertThat(parsed.sets()).isEqualTo(1);
        // Округлённое — уже не слово клиента: владелец узнаёт об этом из отчёта загрузки.
        assertThat(parsed.problems()).containsExactly(
                "Вылет: 37.5 мм записан как 38 — у нас вылет целый, точное значение в «Характеристиках»");
    }

    @Test
    @DisplayName("Вылет целым числом записывается как есть и в отчёт не попадает")
    void wholeOffsetIsNotNamed() {
        DromWheelCard.Parsed parsed = DromWheelCard.parse("Диски", "Шины и диски → Диски",
                DISC_946.replace("37.5 мм", "45 мм"));
        assertThat(parsed.request().offsetMm()).isEqualTo(45);
        assertThat(parsed.problems()).isEmpty();
    }

    @Test
    @DisplayName("Строка 993: «265\\65 R17», износ 50 %")
    void tyre993() {
        DromWheelCard.Parsed parsed = DromWheelCard.parse(
                "Автошина Bridgestone Dueler H/L 265/65 R17", SECTION_TYRES, TYRE_993);
        WheelRequest r = parsed.request();
        assertThat(r.kind()).isEqualTo("TYRE");
        assertThat(r.tyreWidth()).isEqualTo(265);
        assertThat(r.tyreHeight()).isEqualTo(65);
        assertThat(r.diameter()).isEqualByComparingTo("17");
        assertThat(r.season()).isEqualTo("SUMMER");
        assertThat(r.wearPercent()).isEqualTo(50);
        assertThat(r.madeYear()).isEqualTo(2013);
        assertThat(r.brand()).isEqualTo("Bridgestone");
        assertThat(r.model()).isEqualTo("Dueler H/L");
        assertThat(parsed.problems()).isEmpty();
    }

    @Test
    @DisplayName("Неразобранное свойство пусто и названо полем, а не угадано")
    void unparsedIsNamed() {
        DromWheelCard.Parsed parsed = DromWheelCard.parse("Автошина", SECTION_TYRES, """
                model~tireFirmAndModel [model~tireFirmAndModel]: Nokian
                Протектор [wheelSeason]: Зимние шипованные
                Износ [wheelTireWear]: почти новые
                marking [marking]: 265/65 Р19
                Количество шин в неделимом комплекте [inSetQuantity]: четыре""");
        WheelRequest r = parsed.request();
        assertThat(r.season()).isNull();
        assertThat(r.wearPercent()).isNull();
        assertThat(r.tyreWidth()).isNull();
        assertThat(parsed.setPieces()).isNull();
        assertThat(parsed.problems()).containsExactlyInAnyOrder(
                "Размер шины: «265/65 Р19» не разобран",
                "Сезон: «Зимние шипованные» не разобран",
                "Износ: «почти новые» не разобран",
                "Штук в комплекте: «четыре» не разобрано");
    }
}
