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
                new VehicleTitlePart("Toyota", "Wish", "ZNE10", "1ZZFE", 2006),
                null,
                PartCondition.USED,
                "8414012530");

        assertThat(title)
                .isEqualTo("Блок подрулевых переключателей Toyota Wish ZNE10 1ZZFE 2006 (б/у) 8414012530");
    }

    @Test
    @DisplayName("Стороны идут в порядке «перед — лево — низ»")
    void putsSidesInSpokenOrder() {
        String title = generator.generate(
                "Стойка",
                new VehicleTitlePart("Honda", "Airwave", "GJ1", null, 2002),
                new Sides(LongitudinalSide.FRONT, LateralSide.LEFT, VerticalSide.LOWER),
                PartCondition.USED,
                null);

        assertThat(title).isEqualTo("Стойка Honda Airwave GJ1 2002 перед. лев. ниж. (б/у)");
    }

    @Test
    @DisplayName("Заданные стороны попадают в заголовок, незаданные — пропускаются")
    void skipsUnsetAxes() {
        String title = generator.generate(
                "Датчик ABS",
                new VehicleTitlePart("Toyota", "Duet", "M100A", "EJDE", null),
                new Sides(LongitudinalSide.FRONT, LateralSide.RIGHT, null),
                PartCondition.USED,
                "89544-97401");

        assertThat(title).isEqualTo("Датчик ABS Toyota Duet M100A EJDE перед. прав. (б/у) 89544-97401");
    }

    @Test
    @DisplayName("Контрактная запчасть без донора: заголовок остаётся осмысленным")
    void worksWithoutVehicle() {
        String title = generator.generate("Амортизатор", null, null, PartCondition.NEW, "334388");

        assertThat(title).isEqualTo("Амортизатор (новая) 334388");
    }

    @Test
    @DisplayName("Состояние подставляется всегда, по умолчанию — б/у")
    void alwaysMarksCondition() {
        assertThat(generator.generate("Фара", null, null, null, null)).isEqualTo("Фара (б/у)");
        assertThat(generator.generate("Фара", null, null, PartCondition.REFURBISHED, null))
                .isEqualTo("Фара (восст.)");
    }

    /**
     * Контрактная деталь помечается своим словом, а не «б/у».
     *
     * <p>Контрактная снята с целой машины и не ремонтировалась, б/у —
     * с разборки: на разборке это разные товары и разная цена. Пока
     * значения не было, все девять с лишним тысяч контрактных позиций
     * переехавшего клиента назывались в заголовке «(б/у)».
     */
    @Test
    @DisplayName("Контрактная деталь помечается своим словом")
    void marksContract() {
        assertThat(generator.generate("Фара", null, null, PartCondition.CONTRACT, null))
                .isEqualTo("Фара (контракт)");
    }

    @Test
    @DisplayName("Правка состояния двигает пометку, не тронув остального заголовка")
    void remarkReplacesOnlyTheMark() {
        String title = generator.generate(
                "Фара",
                new VehicleTitlePart("Toyota", "Camry", null, null, 2007),
                new Sides(LongitudinalSide.FRONT, LateralSide.LEFT, null),
                PartCondition.USED,
                "81150-33670");

        assertThat(generator.remark(title, PartCondition.NEW))
                .isEqualTo("Фара Toyota Camry 2007 перед. лев. (новая) 81150-33670");
    }

    /**
     * Самое дорогое утверждение этого класса.
     *
     * <p>У перенесённой контрактной позиции в заголовке стоит **список
     * машин** — донора у неё нет, и применимость перечислена прямо
     * в названии ({@code BazonImporter}), а {@code TitleApplicability}
     * эти машины оттуда же и разбирает. Пересборка заголовка из вида
     * детали, машины и сторон стёрла бы их молча — и вместе с ними
     * применимость, по которой деталь находят. У переехавшего клиента
     * таких позиций 9 417 из 35 841.
     */
    @Test
    @DisplayName("Список машин в заголовке переживает правку состояния")
    void remarkKeepsWhatItDidNotWrite() {
        String imported = "Фара Toyota, Camry, Allion, Premio (б/у) 81150-33670";

        assertThat(generator.remark(imported, PartCondition.CONTRACT))
                .isEqualTo("Фара Toyota, Camry, Allion, Premio (контракт) 81150-33670");
    }

    @Test
    @DisplayName("Заголовок без пометки её получает, а не остаётся без состояния")
    void remarkAppendsWhenThereIsNoMark() {
        // «Без наименования» собирает не этот класс, а битая строка переноса:
        // пометки в таком заголовке нет вовсе.
        assertThat(generator.remark("Без наименования", PartCondition.CONTRACT))
                .isEqualTo("Без наименования (контракт)");
    }

    @Test
    @DisplayName("Пустые атрибуты не оставляют двойных пробелов")
    void doesNotLeaveDoubleSpaces() {
        String title = generator.generate(
                "  Радиатор  ",
                new VehicleTitlePart("Nissan", "  ", null, "", 1999),
                new Sides(null, null, null),
                PartCondition.USED,
                "  ");

        assertThat(title).isEqualTo("Радиатор Nissan 1999 (б/у)");
        assertThat(title).doesNotContain("  ");
    }

    @Test
    @DisplayName("Без вида детали заголовок не собирается: это не «пустое поле», а ошибка ввода")
    void requiresPartName() {
        assertThatThrownBy(() -> generator.generate(" ", null, null, PartCondition.USED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Вид детали");
    }
}
