package ru.partsflow.publishing.drom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запасное название в файле Дрома ({@link DromPriceWriter#plainName}) снимает
 * стороны в любом регистре.
 *
 * <p>С 24.10.2026 название позиции пишет стороны с заглавной («Перед. Лев.»,
 * {@code shared/ValueWords}), а собранные раньше — строчными. Сокращения
 * площадка просит не присылать («без сокращений и аббревиатур»), и сторона
 * уже уехала своими тегами: снятие только строчных оставило бы «Перед. Лев.»
 * в названии объявления (разбор PR 516).
 */
class DromPlainNameTest {

    @Test
    @DisplayName("Стороны с заглавной и строчными снимаются одинаково")
    void sidesAreStrippedInAnyCase() {
        assertThat(DromPriceWriter.plainName("Фара Toyota Camry 2007 Перед. Лев. (б/у) 81170-33670"))
                .isEqualTo("Фара Toyota Camry 2007 81170-33670");
        assertThat(DromPriceWriter.plainName("Фара Toyota Camry 2007 перед. лев. (б/у) 81170-33670"))
                .isEqualTo("Фара Toyota Camry 2007 81170-33670");
        assertThat(DromPriceWriter.plainName("Стойка Honda Airwave Перед. Задн. Лев. Прав. Верх. Ниж."))
                .isEqualTo("Стойка Honda Airwave");
    }

    @Test
    @DisplayName("Слово, только начинающееся со стороны, не трогается")
    void wordsThatMerelyStartLikeASideStay() {
        assertThat(DromPriceWriter.plainName("Левый поворотник Передний"))
                .isEqualTo("Левый поворотник Передний");
    }
}
