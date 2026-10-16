package ru.partsflow.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Каким номер производителя хранить и как делить кросс-номера (задача 0309,
 * решение владельца продукта 16.10.2026: «похожие русские заменять латиницей,
 * срезать неразрывный пробел, считать табуляцию разделителем»).
 *
 * <p>Русские буквы здесь записаны escape-последовательностями: в исходнике
 * они неотличимы от латинских, и тест, где «А» случайно латинская, проверял
 * бы не то, что написано в его имени.
 */
class OemNumbersTest {

    /** «АВ-123С»: русские А, В и С. */
    private static final String RUSSIAN_AB_123C = "\u0410\u0412-123\u0421";
    /** «ас-1»: строчные русские а и с — двойники. */
    private static final String RUSSIAN_SMALL_AC_1 = "\u0430\u0441-1";
    /** «ав-1»: строчная «в» от «b» глазом отличается — не двойник. */
    private static final String RUSSIAN_SMALL_AV_1 = "\u0430\u0432-1";
    /** «б/н»: строчная «н» — не «h», это «без номера». */
    private static final String NO_NUMBER = "\u0431/\u043d";
    /** «АНАЛОГ»: Л и Г двойников не имеют. */
    private static final String ANALOG = "\u0410\u041d\u0410\u041b\u041e\u0413";
    /** «4520Б»: Б двойника не имеет. */
    private static final String WITH_BE = "4520\u0411";
    private static final String NBSP = "\u00A0";

    @Test
    @DisplayName("Русские двойники в номере — латиница, регистр сохраняется")
    void doublesBecomeLatin() {
        assertThat(OemNumbers.clean(RUSSIAN_AB_123C)).isEqualTo("AB-123C");
        assertThat(OemNumbers.clean(RUSSIAN_SMALL_AC_1)).isEqualTo("ac-1");
        assertThat(OemNumbers.clean("8518100\u0421")).isEqualTo("8518100C");
        // Все одиннадцать прописных и шесть строчных двойников.
        assertThat(OemNumbers.clean("\u0410\u0412\u0415\u041a\u041c\u041d\u041e\u0420\u0421\u0422\u0425"))
                .isEqualTo("ABEKMHOPCTX");
        assertThat(OemNumbers.clean("\u0430\u0435\u043e\u0440\u0441\u0445"))
                .isEqualTo("aeopcx");
        // Строчные «в», «к», «м», «н», «т» от «b», «k», «m», «h», «t» глазом
        // отличаются: слово с ними не трогается, как любое русское.
        assertThat(OemNumbers.clean(RUSSIAN_SMALL_AV_1)).isEqualTo(RUSSIAN_SMALL_AV_1);
        assertThat(OemNumbers.clean(NO_NUMBER)).isEqualTo(NO_NUMBER);
    }

    @Test
    @DisplayName("Слово с русской буквой вне двойников не меняется: «АНАЛОГ» не становится мусором")
    void wordWithOtherRussianLetterStays() {
        assertThat(OemNumbers.clean(ANALOG)).isEqualTo(ANALOG);
        assertThat(OemNumbers.clean(WITH_BE)).isEqualTo(WITH_BE);
        // Слова делятся разделителями: соседнее слово из двойников приводится.
        assertThat(OemNumbers.clean(RUSSIAN_AB_123C + " " + ANALOG))
                .isEqualTo("AB-123C " + ANALOG);
        assertThat(OemNumbers.clean("81170-33670")).isEqualTo("81170-33670");
    }

    @Test
    @DisplayName("Неразрывный, узкие и нулевой ширины пробелы по краям срезаются; внутри — разделитель приведения")
    void unicodeSpacesAtEdges() {
        assertThat(OemNumbers.clean(NBSP + "81170-33670" + NBSP)).isEqualTo("81170-33670");
        assertThat(OemNumbers.clean("\u2009\u202F81170\u2007\u200B\uFEFF")).isEqualTo("81170");
        assertThat(OemNumbers.clean(" \t" + NBSP)).isEmpty();
        assertThat(OemNumbers.clean("81170" + NBSP + "33670")).isEqualTo("81170" + NBSP + "33670");
        assertThat(OemNumbers.normalize("81170" + NBSP + "33670")).isEqualTo("8117033670");
    }

    @Test
    @DisplayName("Табуляция делит кросс-номера наравне с запятой, точкой с запятой и переводом строки")
    void tabSeparatesCrosses() {
        assertThat(OemNumbers.list("aa111\tBB222, dd-444;ee555\nff666\r\ngg777"))
                .containsExactly("aa111", "BB222", "dd-444", "ee555", "ff666", "gg777");
        // Пробел — не разделитель: «81170 33670» — один номер.
        assertThat(OemNumbers.list("81170 33670")).containsExactly("81170 33670");
        // Каждый кусок чистится тем же правилом.
        assertThat(OemNumbers.list(NBSP + RUSSIAN_AB_123C + NBSP + "\t, ,"))
                .containsExactly("AB-123C");
        assertThat(OemNumbers.list(null)).isEmpty();
    }

    @Test
    @DisplayName("Приведение: русские двойники латиницей, хранимое и набранное приводятся к одному")
    void normalizeAgreesWithClean() {
        assertThat(OemNumbers.normalize(RUSSIAN_AB_123C)).isEqualTo("AB123C");
        assertThat(OemNumbers.normalize(RUSSIAN_SMALL_AC_1)).isEqualTo("AC1");
        assertThat(OemNumbers.normalize(ANALOG)).isNull();
        assertThat(OemNumbers.normalize(NO_NUMBER)).as("«б/н» стало номером").isNull();
        for (String typed : List.of(RUSSIAN_AB_123C, RUSSIAN_SMALL_AC_1, RUSSIAN_SMALL_AV_1, NO_NUMBER, ANALOG, WITH_BE,
                "FP8518100\u0421", "fp8518100\u0441", "A\u04321", NBSP + "81170 33670",
                "ab-123 456x", "\u0443123", RUSSIAN_AB_123C + " " + ANALOG)) {
            assertThat(OemNumbers.normalize(OemNumbers.clean(typed)))
                    .as("хранимое «%s» приводится не так, как набранное", typed)
                    .isEqualTo(OemNumbers.normalize(typed));
        }
    }

    @Test
    @DisplayName("Повторное приведение ничего не меняет")
    void cleanIsIdempotent() {
        for (String typed : List.of(RUSSIAN_AB_123C, ANALOG, NBSP + "81170 33670" + NBSP)) {
            String once = OemNumbers.clean(typed);
            assertThat(OemNumbers.clean(once)).isEqualTo(once);
        }
    }
}
