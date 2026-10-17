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
                "8414012530");

        assertThat(title)
                .isEqualTo("Блок подрулевых переключателей Toyota Wish ZNE10 1ZZFE 2006 8414012530");
    }

    @Test
    @DisplayName("Стороны идут в порядке «перед — лево — низ»")
    void putsSidesInSpokenOrder() {
        String title = generator.generate(
                "Стойка",
                new VehicleTitlePart("Honda", "Airwave", "GJ1", null, 2002),
                new Sides(LongitudinalSide.FRONT, LateralSide.LEFT, VerticalSide.LOWER),
                null);

        assertThat(title).isEqualTo("Стойка Honda Airwave GJ1 2002 перед. лев. ниж.");
    }

    @Test
    @DisplayName("Сторона «обе» — двумя словами на месте одного (задача 0283)")
    void bothSidesAreTwoWords() {
        String title = generator.generate(
                "Стойка",
                new VehicleTitlePart("Honda", "Airwave", "GJ1", null, 2002),
                new Sides(LongitudinalSide.BOTH, LateralSide.BOTH, VerticalSide.BOTH),
                null);

        assertThat(title).isEqualTo(
                "Стойка Honda Airwave GJ1 2002 перед. задн. лев. прав. верх. ниж.");
    }

    @Test
    @DisplayName("Заданные стороны попадают в заголовок, незаданные — пропускаются")
    void skipsUnsetAxes() {
        String title = generator.generate(
                "Датчик ABS",
                new VehicleTitlePart("Toyota", "Duet", "M100A", "EJDE", null),
                new Sides(LongitudinalSide.FRONT, LateralSide.RIGHT, null),
                "89544-97401");

        assertThat(title).isEqualTo("Датчик ABS Toyota Duet M100A EJDE перед. прав. 89544-97401");
    }

    @Test
    @DisplayName("Контрактная запчасть без донора: заголовок остаётся осмысленным")
    void worksWithoutVehicle() {
        String title = generator.generate("Амортизатор", null, null, "334388");

        assertThat(title).isEqualTo("Амортизатор 334388");
    }

    /**
     * Состояния в заголовке нет (задача 0315, решение владельца 17.10.2026).
     *
     * <p>Пометка «(б/у)» была второй копией поля {@code condition}, и копия
     * врала: с 0304 наименование меняет только человек, поэтому смена
     * состояния пометку не обновляла. Состояние показывается рядом словом.
     * Генератор состояния не принимает вовсе — вернуть его можно только
     * правкой подписи, а не забытым аргументом.
     */
    @Test
    @DisplayName("Пометки состояния в заголовке нет: ни «(б/у)», ни остальных")
    void noConditionMark() {
        String title = generator.generate("Фара", null, null, null);

        assertThat(title).isEqualTo("Фара");
        for (String mark : PartCondition.titleMarks()) {
            assertThat(title).doesNotContain(mark);
        }
    }

    @Test
    @DisplayName("Пустые атрибуты не оставляют двойных пробелов")
    void doesNotLeaveDoubleSpaces() {
        String title = generator.generate(
                "  Радиатор  ",
                new VehicleTitlePart("Nissan", "  ", null, "", 1999),
                new Sides(null, null, null),
                "  ");

        assertThat(title).isEqualTo("Радиатор Nissan 1999");
        assertThat(title).doesNotContain("  ");
    }

    @Test
    @DisplayName("Без вида детали заголовок не собирается: это не «пустое поле», а ошибка ввода")
    void requiresPartName() {
        assertThatThrownBy(() -> generator.generate(" ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Вид детали");
    }
}
