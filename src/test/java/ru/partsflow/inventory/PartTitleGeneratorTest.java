package ru.partsflow.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.inventory.PartTitleGenerator.Sides;
import ru.partsflow.inventory.PartTitleGenerator.VehicleTitlePart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Заголовок собирается автоматически ради однородности склада: руками его пишут
 * каждый раз по-своему, и по такому складу нельзя ни искать, ни выгружаться.
 * Поэтому порядок частей — это инвариант, а не деталь оформления.
 */
class PartTitleGeneratorTest {

    private final PartTitleGenerator generator = new PartTitleGenerator();

    @Test
    @DisplayName("Полный набор атрибутов собирается в привычный продавцу порядок")
    void buildsFullTitle() {
        String title = generator.generate(
                "Блок подрулевых переключателей",
                new VehicleTitlePart("Toyota", "Wish", "ZNE10", "1ZZFE", "2006"),
                null, null,
                "8414012530");

        assertThat(title)
                .isEqualTo("Блок подрулевых переключателей Toyota Wish ZNE10 1ZZFE 2006 8414012530");
    }

    @Test
    @DisplayName("Стороны идут в порядке «перед — лево — низ», с заглавной (решение владельца 24.10.2026)")
    void putsSidesInSpokenOrder() {
        String title = generator.generate(
                "Стойка",
                new VehicleTitlePart("Honda", "Airwave", "GJ1", null, "2002"),
                new Sides(LongitudinalSide.FRONT, LateralSide.LEFT, VerticalSide.LOWER), null,
                null);

        assertThat(title).isEqualTo("Стойка Honda Airwave GJ1 2002 Перед. Лев. Ниж.");
    }

    @Test
    @DisplayName("Сторона «обе» — двумя словами на месте одного (задача 0283)")
    void bothSidesAreTwoWords() {
        String title = generator.generate(
                "Стойка",
                new VehicleTitlePart("Honda", "Airwave", "GJ1", null, "2002"),
                new Sides(LongitudinalSide.BOTH, LateralSide.BOTH, VerticalSide.BOTH), null,
                null);

        assertThat(title).isEqualTo(
                "Стойка Honda Airwave GJ1 2002 Перед. Задн. Лев. Прав. Верх. Ниж.");
    }

    @Test
    @DisplayName("Заданные стороны попадают в заголовок, незаданные — пропускаются")
    void skipsUnsetAxes() {
        String title = generator.generate(
                "Датчик ABS",
                new VehicleTitlePart("Toyota", "Duet", "M100A", "EJDE", null),
                new Sides(LongitudinalSide.FRONT, LateralSide.RIGHT, null), null,
                "89544-97401");

        assertThat(title).isEqualTo("Датчик ABS Toyota Duet M100A EJDE Перед. Прав. 89544-97401");
    }

    @Test
    @DisplayName("Контрактная запчасть без донора: заголовок остаётся осмысленным")
    void worksWithoutVehicle() {
        String title = generator.generate("Амортизатор", null, null, null, "334388");

        assertThat(title).isEqualTo("Амортизатор 334388");
    }

    /**
     * Пометка состояния — как у Bazon (задача 0336, решение владельца продукта
     * 19.10.2026; отменяет 0315): «(б/у)» у б/у и контрактной, у новой нет.
     * У восстановленной пометки пока нет — у Bazon она не видена (решение 5).
     * Стоит после положения и перед номером.
     */
    @Test
    @DisplayName("«(б/у)» у б/у и контрактной, нет у новой, восстановленной и без состояния")
    void conditionMarkLikeBazon() {
        Sides front = new Sides(LongitudinalSide.FRONT, LateralSide.LEFT, null);
        assertThat(generator.generate("Фара", null, front, PartCondition.USED, "8301A315XA"))
                .isEqualTo("Фара Перед. Лев. (б/у) 8301A315XA");
        assertThat(generator.generate("Фара", null, front, PartCondition.CONTRACT, "8301A315XA"))
                .isEqualTo("Фара Перед. Лев. (б/у) 8301A315XA");
        for (PartCondition none : new PartCondition[] {PartCondition.NEW, PartCondition.REFURBISHED, null}) {
            assertThat(generator.generate("Фара", null, front, none, "8301A315XA"))
                    .as("состояние %s", none)
                    .isEqualTo("Фара Перед. Лев. 8301A315XA");
        }
    }

    private record Bazon(String no, String kind, VehicleTitlePart vehicle, Sides sides,
                         PartCondition condition, String oem, String title) {
    }

    /**
     * Тринадцать живых карточек Bazon (задача 0336, таблица «Что известно»):
     * по полям карточки название собирается до знака таким же. Вид — как
     * набран («стеклоподъемника» без «ё», «egr» строчными), марка, модель,
     * кузов и двигатель — первым значением списка, год — первым числом
     * («03-08» → «03»), номер как вписан и последним.
     *
     * <p>Одно отличие от Bazon — и оно решение владельца продукта 24.10.2026
     * ({@code tasks/0044}, пункт 4): стороны с заглавной («Перед. Лев.»),
     * у Bazon строчными («перед. лев.»). Карточки оставлены как у Bazon,
     * а отличие наложено одним правилом {@link #ownersSides}: тогда тест
     * по-прежнему говорит, что всё прочее совпадает до знака.
     */
    @Test
    @DisplayName("Название по полям карточки — до знака как у Bazon, тринадцать карточек")
    void bazonCards() {
        Sides none = null;
        Sides front = new Sides(LongitudinalSide.FRONT, null, null);
        Sides frontLeft = new Sides(LongitudinalSide.FRONT, LateralSide.LEFT, null);
        Sides rearLeft = new Sides(LongitudinalSide.REAR, LateralSide.LEFT, null);
        java.util.List<Bazon> cards = java.util.List.of(
                new Bazon("114", "Кольца форсунок",
                        new VehicleTitlePart("Isuzu", "ELF", "NKR81E", "4JJ1", null), none,
                        PartCondition.USED, "9095661110",
                        "Кольца форсунок Isuzu ELF NKR81E 4JJ1 (б/у) 9095661110"),
                new Bazon("637", "Бачок влагоудалителя",
                        new VehicleTitlePart("Nissan", "Murano", "PNZ50", "VQ35DE", null), none,
                        PartCondition.USED, "16585-CA000",
                        "Бачок влагоудалителя Nissan Murano PNZ50 VQ35DE (б/у) 16585-CA000"),
                new Bazon("688", "Воздухозаборник",
                        new VehicleTitlePart("Nissan", "X-Trail", "TNT31", "QR25DE", null), front,
                        PartCondition.USED, "16554JG30A",
                        "Воздухозаборник Nissan X-Trail TNT31 QR25DE перед. (б/у) 16554JG30A"),
                new Bazon("691", "Воздухозаборник",
                        new VehicleTitlePart("Porsche", "Cayenne", "957", "M 48.01", null), frontLeft,
                        PartCondition.USED, "7L5128752",
                        "Воздухозаборник Porsche Cayenne 957 M 48.01 перед. лев. (б/у) 7L5128752"),
                new Bazon("1 349", "Клапан egr",
                        new VehicleTitlePart("Mercedes-Benz", "C-class C240", "S203", "M 112.912", null),
                        front, PartCondition.USED, "A1121400460",
                        "Клапан egr Mercedes-Benz C-class C240 S203 M 112.912 перед. (б/у) A1121400460"),
                new Bazon("3 785", "Дефлектор",
                        new VehicleTitlePart("Honda", "CR-V", "RE4", "K24A", null), frontLeft,
                        PartCondition.USED, null,
                        "Дефлектор Honda CR-V RE4 K24A перед. лев. (б/у)"),
                new Bazon("9 701", "Блок круиз-контроля",
                        new VehicleTitlePart("Lincoln,Ford", "Navigator,Expedition",
                                "UN173 1997-2002 Г.В.,UN93", "TRITON54L", null), none,
                        PartCondition.CONTRACT, "F85F-9C734-AC",
                        "Блок круиз-контроля Lincoln Navigator UN173 1997-2002 Г.В. TRITON54L (б/у) F85F-9C734-AC"),
                new Bazon("65 383", "Шайба маховика",
                        new VehicleTitlePart("Toyota", "Gaia", "SXM10", "3SFE", "1998"), none,
                        PartCondition.USED, "3211632011",
                        "Шайба маховика Toyota Gaia SXM10 3SFE 1998 (б/у) 3211632011"),
                new Bazon("88 487", "Кнопка стеклоподъемника",
                        new VehicleTitlePart("Toyota", "Harrier", "ACU30", "2AZFE", "2010"), rearLeft,
                        PartCondition.USED, "7428048120",
                        "Кнопка стеклоподъемника Toyota Harrier ACU30 2AZFE 2010 задн. лев. (б/у) 7428048120"),
                new Bazon("1 640", "Ремень поликлиновой", null, none,
                        PartCondition.USED, "4PK905",
                        "Ремень поликлиновой (б/у) 4PK905"),
                new Bazon("91 447", "Знак аварийной остановки", null, none,
                        PartCondition.CONTRACT, "f-79-8",
                        "Знак аварийной остановки (б/у) f-79-8"),
                new Bazon("27 079", "Бампер",
                        new VehicleTitlePart("Lexus, Toyota", "RX300", "MCU31W", "1MZFE", "03-08"), front,
                        PartCondition.NEW, "52119-48910",
                        "Бампер Lexus RX300 MCU31W 1MZFE 03 перед. 52119-48910"),
                new Bazon("33 251", "Фара",
                        new VehicleTitlePart("Mitsubishi", "Pajero Sport", "K94W", "4D56", "04-09"),
                        frontLeft, PartCondition.NEW, "8301A315XA",
                        "Фара Mitsubishi Pajero Sport K94W 4D56 04 перед. лев. 8301A315XA"));
        for (Bazon card : cards) {
            assertThat(generator.generate(card.kind(), card.vehicle(), card.sides(),
                    card.condition(), card.oem()))
                    .as("позиция № %s", card.no())
                    .isEqualTo(ownersSides(card.title()));
        }
    }

    /** Сокращения сторон с заглавной — единственное отличие от Bazon (24.10.2026). */
    private static String ownersSides(String bazonTitle) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<=\\s)(перед|задн|лев|прав|верх|ниж)\\.").matcher(bazonTitle);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String word = m.group(1);
            m.appendReplacement(out, Character.toUpperCase(word.charAt(0)) + word.substring(1) + ".");
        }
        return m.appendTail(out).toString();
    }

    @Test
    @DisplayName("Пустые атрибуты не оставляют двойных пробелов")
    void doesNotLeaveDoubleSpaces() {
        String title = generator.generate(
                "  Радиатор  ",
                new VehicleTitlePart("Nissan", "  ", null, "", "1999"),
                new Sides(null, null, null), null,
                "  ");

        assertThat(title).isEqualTo("Радиатор Nissan 1999");
        assertThat(title).doesNotContain("  ");
    }

    @Test
    @DisplayName("Без вида детали заголовок не собирается: это не «пустое поле», а ошибка ввода")
    void requiresPartName() {
        assertThatThrownBy(() -> generator.generate(" ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Вид детали");
    }

    /**
     * В заголовок идёт только основной номер (задача 0330, решение владельца
     * продукта 18.10.2026: «у детали один номер производителя, остальное —
     * кросс-номера»). Делит {@code OemNumbers.primary}; без разделителя и при
     * «не номере» в начале текст идёт как пришёл, байт в байт.
     */
    @Test
    @DisplayName("Номер производителя с разделителем: в заголовке только основной, кросс-номера нет")
    void onlyPrimaryNumberInTitle() {
        assertThat(generator.generate("Фара", null, null, null, "8414012530, 8414012531"))
                .isEqualTo("Фара 8414012530");
        assertThat(generator.generate("Фара", null, null, null, "84140-12530;84140-12531\t84140-12532"))
                .isEqualTo("Фара 84140-12530");
        assertThat(generator.generate("Фара", null, null, null, "KR-3\r\nKR-4"))
                .isEqualTo("Фара KR-3");
        assertThat(generator.generate("Фара", null, null, null, "FP-1, АНАЛОГ"))
                .as("«АНАЛОГ» номером не считается и в заголовок не идёт, как и в part_oem")
                .isEqualTo("Фара FP-1");
        assertThat(generator.generate("Фара", null, null, null, "8414012530,"))
                .isEqualTo("Фара 8414012530");
        assertThat(generator.generate("Фара", null, null, null, "АВ-123С; 9"))
                .as("основной — в виде хранения (raw_number), как его даёт primary")
                .isEqualTo("Фара AB-123C");
    }

    @Test
    @DisplayName("Без разделителя, «не номер» в начале и текст без номера — заголовок прежний, байт в байт")
    void numberWithoutSeparatorIsAsBefore() {
        for (String oem : java.util.List.of(
                "8414012530", "84140-12530", "81170 33670", "8518100С", "б/н", "АНАЛОГ",
                "АНАЛОГ, FP-3", "ab-123 456x")) {
            assertThat(generator.generate("Фара", null, null, null, oem))
                    .as("номер «%s»", oem)
                    .isEqualTo("Фара " + oem);
        }
    }
}
