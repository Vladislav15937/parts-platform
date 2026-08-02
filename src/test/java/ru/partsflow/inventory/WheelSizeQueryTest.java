package ru.partsflow.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор размера из строки поиска.
 *
 * <p>Покупатель звонит и называет размер, а не номер товара. Поиск по тексту
 * заголовка ловит это только при точном совпадении написания: в заголовке
 * «225/55 R18», а сказали «225 55 18» — и товар, лежащий на полке,
 * не находится.
 *
 * <p>Ошибка тут тихая: продавец отвечает «нет такого», и проверить его
 * некому.
 */
class WheelSizeQueryTest {

    @Test
    @DisplayName("Размер шины читается во всех написаниях, которыми его называют")
    void tyreSizeIsParsed() {
        for (String spelling : new String[]{"225/55 R18", "225/55R18", "225 55 18",
                "225/55 18"}) {
            var size = WheelSizeQuery.parse(spelling);
            assertThat(size.tyreWidth()).as(spelling).isEqualTo(225);
            assertThat(size.tyreHeight()).as(spelling).isEqualTo(55);
            assertThat(size.diameter()).as(spelling).isEqualByComparingTo("18");
        }
    }

    @Test
    @DisplayName("Половина размера — тоже запрос")
    void profileWithoutRimIsParsed() {
        // «Что есть в 225/55?» — обычный вопрос: диаметр покупатель назовёт,
        // когда увидит, что вообще есть.
        var size = WheelSizeQuery.parse("225/55");
        assertThat(size.tyreWidth()).isEqualTo(225);
        assertThat(size.tyreHeight()).isEqualTo(55);
        assertThat(size.diameter()).isNull();
    }

    @Test
    @DisplayName("Сверловка и размер диска пишутся одинаково, но различаются числами")
    void boltPatternIsToldFromDiscSize() {
        // У сверловки второе число — миллиметры окружности, от 98 и выше;
        // у диска — дюймы посадочного диаметра, от 12 до 24.
        var bolt = WheelSizeQuery.parse("5x114.3");
        assertThat(bolt.boltPattern()).isEqualTo("5x114.3");
        assertThat(bolt.discWidth()).isNull();

        var disc = WheelSizeQuery.parse("7x18");
        assertThat(disc.discWidth()).isEqualByComparingTo("7");
        assertThat(disc.diameter()).isEqualByComparingTo("18");
        assertThat(disc.boltPattern()).isNull();
    }

    @Test
    @DisplayName("«18x7» с диска читается так же, как «7x18» из объявления")
    void discSizeIsParsedInBothOrders() {
        // На самом диске отлито «18x7J», в объявлении пишут «7x18».
        // Посадочный диаметр всегда больше ширины — этим и различаем.
        var size = WheelSizeQuery.parse("18x7");
        assertThat(size.discWidth()).isEqualByComparingTo("7");
        assertThat(size.diameter()).isEqualByComparingTo("18");
    }

    @Test
    @DisplayName("Кириллическая «х» — тот же размер")
    void cyrillicLetterIsTheSameSize() {
        // Её набирают, не переключая раскладку. Та же беда, что с кодами
        // ячеек на этикетках.
        assertThat(WheelSizeQuery.parse("5х114,3").boltPattern()).isEqualTo("5x114.3");
        assertThat(WheelSizeQuery.parse("7х18").discWidth()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("Вылет и диаметр читаются по отдельности")
    void offsetAndRimAreParsed() {
        var size = WheelSizeQuery.parse("5x114.3 ET38 R18");
        assertThat(size.boltPattern()).isEqualTo("5x114.3");
        assertThat(size.offsetMm()).isEqualTo(38);
        assertThat(size.diameter()).isEqualByComparingTo("18");
        assertThat(size.text()).isNull();
    }

    @Test
    @DisplayName("Отрицательный вылет не теряет знак")
    void negativeOffsetKeepsItsSign() {
        // ET-20 бывает у широких дисков, и «20» вместо «−20» — это другой
        // диск, который не встанет.
        assertThat(WheelSizeQuery.parse("ET-20").offsetMm()).isEqualTo(-20);
    }

    @Test
    @DisplayName("Неразобранное остаётся текстом")
    void whatIsNotASizeStaysText() {
        // «Dunlop зимняя» так и должно искаться словами — по заголовку.
        var size = WheelSizeQuery.parse("Dunlop зимняя 225/55 R18");
        assertThat(size.tyreWidth()).isEqualTo(225);
        assertThat(size.text()).isEqualTo("Dunlop зимняя");

        var words = WheelSizeQuery.parse("Nokian");
        assertThat(words.hasSize()).isFalse();
        assertThat(words.text()).isEqualTo("Nokian");
    }

    @Test
    @DisplayName("Номер товара размером не считается")
    void productCodeIsNotASize() {
        // Иначе поиск по номеру перестаёт работать: в шестнадцатеричном коде
        // найдётся что угодно.
        var size = WheelSizeQuery.parse("FC7D72FD9432");
        assertThat(size.hasSize()).isFalse();
        assertThat(size.text()).isEqualTo("FC7D72FD9432");
    }

    @Test
    @DisplayName("Пустой запрос ничего не разбирает")
    void emptyQueryParsesToNothing() {
        assertThat(WheelSizeQuery.parse("   ").hasSize()).isFalse();
        assertThat(WheelSizeQuery.parse(null).text()).isNull();
    }
}
