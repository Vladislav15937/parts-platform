package ru.partsflow.migration.excel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.VerticalSide;
import ru.partsflow.migration.bazon.BazonValueParser;
import ru.partsflow.shared.ValueWords;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор переноса читает слова-значения без регистра — и наши новые слова тоже.
 *
 * <p>С 24.10.2026 слова с заглавной («Левый руль», «Передний», «Лев.»,
 * {@code shared/ValueWords}). Перенос из Bazon и файл с Дрома сравнивают слово
 * файла после {@code toLowerCase}: проверка держит, что так и осталось, —
 * и в нашем регистре, и в строчном, и заглавными. Перебором по словарю, а не
 * по списку: новое слово попадает в проверку в тот день, когда его дописали.
 *
 * <p>{@code DCT} («Робот DSG») разбор не читает намеренно: в файлах Bazon такого
 * слова не встречалось, «Робот» там — {@code AMT} ({@code BazonValueParser}).
 */
class ValueWordsReadBackTest {

    @Test
    @DisplayName("Руль, привод и коробка из словаря читаются переносом Bazon в любом регистре")
    void bazonReadsOurWordsInAnyCase() {
        ValueWords.steerings().forEach((code, word) -> {
            for (String spelled : spellings(word)) {
                assertThat(BazonValueParser.parseSteering(spelled)).as("руль «%s»", spelled).isEqualTo(code);
            }
        });
        ValueWords.drives().forEach((code, word) -> {
            for (String spelled : spellings(word)) {
                assertThat(BazonValueParser.parseDriveType(spelled)).as("привод «%s»", spelled).isEqualTo(code);
            }
        });
        ValueWords.transmissions().forEach((code, word) -> {
            if (code.equals("DCT")) {
                return;
            }
            for (String spelled : spellings(word)) {
                assertThat(BazonValueParser.parseTransmissionType(spelled))
                        .as("коробка «%s»", spelled).isEqualTo(code);
            }
        });
    }

    @Test
    @DisplayName("Стороны из словаря читаются переносом Bazon и файлом с Дрома в любом регистре")
    void sidesAreReadInAnyCase() {
        for (LateralSide side : new LateralSide[] {LateralSide.LEFT, LateralSide.RIGHT}) {
            String word = ValueWords.side(ValueWords.Axis.LEFT_RIGHT, side.name(), ValueWords.CELL_SEPARATOR);
            for (String spelled : spellings(word)) {
                assertThat(BazonValueParser.parseLateralSide(spelled)).as("Bazon «%s»", spelled).isEqualTo(side);
                assertThat(DromCardWords.lateral(spelled)).as("Дром «%s»", spelled).isEqualTo(side);
            }
        }
        for (LongitudinalSide side : new LongitudinalSide[] {LongitudinalSide.FRONT, LongitudinalSide.REAR}) {
            String word = ValueWords.side(ValueWords.Axis.FRONT_REAR, side.name(), ValueWords.CELL_SEPARATOR);
            for (String spelled : spellings(word)) {
                assertThat(BazonValueParser.parseLongitudinalSide(spelled)).as("Bazon «%s»", spelled).isEqualTo(side);
                assertThat(DromCardWords.longitudinal(spelled)).as("Дром «%s»", spelled).isEqualTo(side);
            }
        }
        for (VerticalSide side : new VerticalSide[] {VerticalSide.UPPER, VerticalSide.LOWER}) {
            String word = ValueWords.side(ValueWords.Axis.UPPER_LOWER, side.name(), ValueWords.CELL_SEPARATOR);
            for (String spelled : spellings(word)) {
                assertThat(DromCardWords.vertical(spelled)).as("Дром «%s»", spelled).isEqualTo(side);
            }
        }
        // «Обе» через запятую — словом клетки, как его пишет файл склада.
        assertThat(DromCardWords.lateral(ValueWords.side(ValueWords.Axis.LEFT_RIGHT, "BOTH", ", ")))
                .isEqualTo(LateralSide.BOTH);
        assertThat(DromCardWords.longitudinal(ValueWords.side(ValueWords.Axis.FRONT_REAR, "BOTH", ", ")))
                .isEqualTo(LongitudinalSide.BOTH);
    }

    private static String[] spellings(String word) {
        return new String[] {word, word.toLowerCase(Locale.ROOT), word.toUpperCase(Locale.ROOT)};
    }
}
