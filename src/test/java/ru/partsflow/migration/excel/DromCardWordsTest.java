package ru.partsflow.migration.excel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.VerticalSide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Разбор слов сторон карточки Дрома, все три оси (задача 0283). */
class DromCardWordsTest {

    @Test
    @DisplayName("«Переднее, Заднее» в любом порядке и с любым разделителем — обе стороны")
    void longitudinalBoth() {
        assertThat(DromCardWords.longitudinal("Переднее, Заднее")).isEqualTo(LongitudinalSide.BOTH);
        assertThat(DromCardWords.longitudinal("Заднее, Переднее")).isEqualTo(LongitudinalSide.BOTH);
        assertThat(DromCardWords.longitudinal("перед. / зад")).isEqualTo(LongitudinalSide.BOTH);
        assertThat(DromCardWords.longitudinal("Заднее; Передний")).isEqualTo(LongitudinalSide.BOTH);
        assertThat(DromCardWords.longitudinal("Переднее")).isEqualTo(LongitudinalSide.FRONT);
        assertThat(DromCardWords.longitudinal("Заднее")).isEqualTo(LongitudinalSide.REAR);
        assertThat(DromCardWords.longitudinal("Переднее, Переднее")).isEqualTo(LongitudinalSide.FRONT);
        assertThat(DromCardWords.longitudinal("  ")).isNull();
    }

    @Test
    @DisplayName("«Верхнее, Нижнее» в любом порядке — обе стороны")
    void verticalBoth() {
        assertThat(DromCardWords.vertical("Верхнее, Нижнее")).isEqualTo(VerticalSide.BOTH);
        assertThat(DromCardWords.vertical("Нижнее, Верхнее")).isEqualTo(VerticalSide.BOTH);
        assertThat(DromCardWords.vertical("Верхнее")).isEqualTo(VerticalSide.UPPER);
        assertThat(DromCardWords.vertical("Нижнее")).isEqualTo(VerticalSide.LOWER);
        assertThat(DromCardWords.vertical("Нижнее, Нижнее")).isEqualTo(VerticalSide.LOWER);
        assertThat(DromCardWords.vertical(null)).isNull();
    }

    @Test
    @DisplayName("Слово чужой оси или неизвестное останавливает строку, слово дословно")
    void unknownWordStopsTheRow() {
        assertThatThrownBy(() -> DromCardWords.longitudinal("Переднее, Левая"))
                .isInstanceOf(DromCardWords.Unknown.class)
                .hasMessage("положение не распознано: «Переднее, Левая»");
        assertThatThrownBy(() -> DromCardWords.vertical("Верхнее, Левая"))
                .isInstanceOf(DromCardWords.Unknown.class)
                .hasMessage("верх/низ не распознан: «Верхнее, Левая»");
        assertThatThrownBy(() -> DromCardWords.lateral("Левая, Переднее"))
                .isInstanceOf(DromCardWords.Unknown.class)
                .hasMessage("сторона не распознана: «Левая, Переднее»");
        assertThatThrownBy(() -> DromCardWords.longitudinal(", ;"))
                .isInstanceOf(DromCardWords.Unknown.class);
    }

    @Test
    @DisplayName("Слово отчёта зависит от оси: BOTH у каждой оси называется своей парой")
    void wordDependsOnAxis() {
        assertThat(DromCardWords.word("side_lr", "BOTH")).isEqualTo("Левая, Правая");
        assertThat(DromCardWords.word("side_fr", "BOTH")).isEqualTo("Переднее, Заднее");
        assertThat(DromCardWords.word("side_ud", "BOTH")).isEqualTo("Верхнее, Нижнее");
        assertThat(DromCardWords.word("side_fr", "FRONT")).isEqualTo("Переднее");
        assertThat(DromCardWords.word("side_ud", "LOWER")).isEqualTo("Нижнее");
        assertThat(DromCardWords.word("side_lr", "LEFT")).isEqualTo("Левая");
        assertThat(DromCardWords.word("condition", "USED")).isNull();
    }
}
