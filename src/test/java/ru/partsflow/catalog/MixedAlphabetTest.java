package ru.partsflow.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Слова, набранные смесью алфавитов (задача 0298): файл клиента Strikexz —
 * «Land Сruiser» (кириллическая «С») в 12 наименованиях и номер «FP8518100С».
 */
class MixedAlphabetTest {

    private static final String CYRILLIC_C = "С";
    private static final String CYRILLIC_SMALL_C = "с";
    private static final String CYRILLIC_K = "К";

    @Test
    @DisplayName("Кириллическая «С» внутри латинского слова читается латинской")
    void cyrillicLetterInLatinWord() {
        assertThat(MixedAlphabet.latinize("Toyota Land " + CYRILLIC_C + "ruiser Prado 120"))
                .isEqualTo("Toyota Land Cruiser Prado 120");
        assertThat(MixedAlphabet.latinize("Nexte" + CYRILLIC_SMALL_C)).isEqualTo("Nextec");
        assertThat(MixedAlphabet.latinize(CYRILLIC_K + "ZJ95")).isEqualTo("KZJ95");
    }

    @Test
    @DisplayName("Чисто русское слово остаётся как есть: «сорт» не становится «copt»")
    void pureCyrillicWordsStay() {
        assertThat(MixedAlphabet.latinize("сорт")).isEqualTo("сорт");
        assertThat(MixedAlphabet.latinize("Хонда Аккорд")).isEqualTo("Хонда Аккорд");
        assertThat(MixedAlphabet.latinize("Фара левая Toyota")).isEqualTo("Фара левая Toyota");
    }

    @Test
    @DisplayName("Только двойники из списка; другие кириллические буквы смесью не трогаются")
    void onlyLookalikes() {
        // «Б» и «Д» двойников не имеют: слово остаётся смешанным, но не меняется.
        assertThat(MixedAlphabet.latinize("ABБД")).isEqualTo("ABБД");
    }

    @Test
    @DisplayName("Длина не меняется: позиции в исходной и приведённой строках совпадают")
    void lengthIsPreserved() {
        String title = "Фара Land " + CYRILLIC_C + "ruiser, Prado";
        assertThat(MixedAlphabet.latinize(title)).hasSameSizeAs(title);
    }

    @Test
    @DisplayName("Номер с кириллической «С» на конце приводится так же, как латинский")
    void numberWithCyrillicLetterNormalizesLikeLatin() {
        assertThat(OemNumbers.normalize("FP8518100" + CYRILLIC_C)).isEqualTo("FP8518100C");
        assertThat(OemNumbers.normalize("FP8518100" + CYRILLIC_C))
                .isEqualTo(OemNumbers.normalize("FP8518100C"));
        assertThat(OemNumbers.normalize("fp8518100" + CYRILLIC_SMALL_C)).isEqualTo("FP8518100C");
    }

    @Test
    @DisplayName("Номера без смеси алфавитов не меняются ни на знак")
    void plainNumbersAreUntouched() {
        assertThat(OemNumbers.normalize("81170-33670")).isEqualTo("8117033670");
        assertThat(OemNumbers.normalize("81170 33670")).isEqualTo("8117033670");
        assertThat(OemNumbers.normalize("fp8518100c")).isEqualTo("FP8518100C");
        assertThat(OemNumbers.normalize("3714060380")).isEqualTo("3714060380");
        // Чисто русское «слово» из чужих кросс-номеров по-прежнему не номер.
        assertThat(OemNumbers.normalize("АНАЛОГ")).isNull();
        assertThat(OemNumbers.normalize("б/н")).isNull();
        assertThat(OemNumbers.normalize(null)).isNull();
        // Цифры алфавита не несут: «8518100С» без латинской буквы не смешанное слово.
        assertThat(OemNumbers.normalize("8518100" + CYRILLIC_C)).isEqualTo("8518100");
    }
}
