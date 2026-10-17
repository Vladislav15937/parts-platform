package ru.partsflow.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.catalog.OemNumbers;
import ru.partsflow.inventory.OemRecount.Note;
import ru.partsflow.inventory.OemRecount.PartPlan;
import ru.partsflow.inventory.OemRecount.Row;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * План пересчёта одной позиции (задача 0317) — по строкам таблицы случаев задачи,
 * без базы.
 *
 * <p>Русские буквы — escape-последовательностями, как в {@code OemNumbersTest}:
 * в исходнике они неотличимы от латинских.
 */
class OemRecountPlanTest {

    /** «8518100С» с русской «С», как его хранили до 0309. */
    private static final String RU_8518100C = "8518100С";
    /** «АНАЛОГ». */
    private static final String ANALOG = "АНАЛОГ";
    /** Русская «С». */
    private static final String RU_C = "С";

    @Test
    @DisplayName("«8518100С» с русской «С» и приведением «8518100» — латиницей и «8518100C»")
    void doubleBecomesLatin() {
        PartPlan plan = OemRecount.plan(List.of(row(1, RU_8518100C, "8518100", true)));
        assertThat(plan.updates()).singleElement().satisfies(change -> {
            assertThat(change.raw()).isEqualTo("8518100C");
            assertThat(change.normalized()).isEqualTo("8518100C");
        });
        assertThat(plan.merges()).isEmpty();
        assertThat(plan.visible()).as("текст номера изменился — для площадки это правка").isTrue();
        assertThat(plan.oemWas()).isEqualTo(RU_8518100C);
        assertThat(plan.oemNow()).isEqualTo("8518100C");
    }

    @Test
    @DisplayName("Неразрывный пробел, нулевая ширина и табуляция по краям срезаются, приведённый не меняется")
    void invisibleEdgesAreStripped() {
        PartPlan plan = OemRecount.plan(List.of(
                row(1, " 81170-33670​", "8117033670", true),
                row(2, "\t52119-33660 ", "5211933660", false)));
        assertThat(plan.updates()).extracting(OemRecount.Change::raw)
                .containsExactly("81170-33670", "52119-33660");
        assertThat(plan.updates()).extracting(OemRecount.Change::normalized)
                .containsExactly("8117033670", "5211933660");
    }

    @Test
    @DisplayName("Два написания одного номера: основная остаётся и получает латиницу, вторая слита")
    void twoSpellingsMergeIntoPrimary() {
        Row primary = row(1, RU_8518100C, "8518100", true);
        Row latin = row(2, "8518100C", "8518100C", false);
        Row other = row(3, "B-1", "B1", false);
        PartPlan plan = OemRecount.plan(List.of(primary, latin, other));

        assertThat(plan.merges()).singleElement().satisfies(merge -> {
            assertThat(merge.was()).isEqualTo(latin);
            assertThat(merge.into()).isEqualTo(primary);
        });
        assertThat(plan.updates()).singleElement().satisfies(change -> {
            assertThat(change.was()).isEqualTo(primary);
            assertThat(change.raw()).isEqualTo("8518100C");
        });
        assertThat(plan.crossesWas()).isEqualTo("8518100C, B-1");
        assertThat(plan.crossesNow()).as("порядок оставшихся кросс-номеров").isEqualTo("B-1");
    }

    @Test
    @DisplayName("Основная выигрывает, даже если кросс-номер заведён раньше")
    void primaryWinsOverOlderCross() {
        Row cross = row(1, RU_8518100C, "8518100", false);
        Row primary = row(5, "8518100C", "8518100C", true);
        PartPlan plan = OemRecount.plan(List.of(cross, primary));
        assertThat(plan.merges()).singleElement()
                .satisfies(merge -> assertThat(merge.into()).isEqualTo(primary));
        assertThat(plan.updates()).as("основная уже латиницей — править нечего").isEmpty();
        assertThat(plan.oemNow()).isEqualTo("8518100C");
    }

    @Test
    @DisplayName("Два кросс-номера стали одним — остаётся меньший номер строки")
    void twoCrossesKeepTheOlder() {
        Row older = row(2, "8518100C", "8518100C", false);
        Row newer = row(7, RU_8518100C, "8518100", false);
        PartPlan plan = OemRecount.plan(List.of(row(1, "A-1", "A1", true), older, newer));
        assertThat(plan.merges()).singleElement().satisfies(merge -> {
            assertThat(merge.was()).isEqualTo(newer);
            assertThat(merge.into()).isEqualTo(older);
        });
    }

    @Test
    @DisplayName("«АНАЛОГ» — не номер: не меняется, не удаляется, назван")
    void analogIsNotANumber() {
        Row analog = row(2, ANALOG, "", false);
        PartPlan plan = OemRecount.plan(List.of(row(1, "A-1", "A1", true), analog));
        assertThat(plan.writes()).isFalse();
        assertThat(plan.notes()).singleElement().satisfies(noted -> {
            assertThat(noted.row()).isEqualTo(analog);
            assertThat(noted.note()).isEqualTo(Note.NOT_A_NUMBER);
        });
    }

    @Test
    @DisplayName("Предел 0312: латиницей ровно 1000 знаков — пересчитан, 1001 — оставлен и назван")
    void lengthLimitAtTheBoundary() {
        int limit = OemNumbers.MAX_NORMALIZED_LENGTH;
        String fits = "1" + RU_C.repeat(limit - 1);
        String over = "1" + RU_C.repeat(limit);
        PartPlan atLimit = OemRecount.plan(List.of(row(1, fits, "1", true)));
        assertThat(atLimit.updates()).singleElement()
                .satisfies(change -> assertThat(change.normalized()).hasSize(limit));

        PartPlan beyond = OemRecount.plan(List.of(row(1, over, "1", true)));
        assertThat(beyond.writes()).isFalse();
        assertThat(beyond.notes()).extracting(OemRecount.Noted::note).containsExactly(Note.TOO_LONG);
    }

    @Test
    @DisplayName("Разделитель внутри строки — не делится, а называется")
    void separatorInsideIsNamed() {
        PartPlan plan = OemRecount.plan(List.of(row(1, "111\t222", "111222", true)));
        assertThat(plan.writes()).isFalse();
        assertThat(plan.notes()).extracting(OemRecount.Noted::note).containsExactly(Note.HAS_SEPARATOR);
    }

    @Test
    @DisplayName("Новый ключ строки держит другая меняющаяся строка — та меняется первой")
    void chainIsOrdered() {
        // Строка 1 займёт ключ «X», который сейчас у строки 2; строка 2 уходит на «Y».
        Row first = row(1, "X", "OLD1", true);
        Row second = row(2, "Y", "X", false);
        PartPlan plan = OemRecount.plan(List.of(first, second));
        assertThat(plan.cycle()).isFalse();
        assertThat(plan.updates()).extracting(c -> c.was().id()).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("Две строки меняются ключами друг с другом — позиция не трогается")
    void swapIsACycle() {
        PartPlan plan = OemRecount.plan(List.of(row(1, "Y", "X", true), row(2, "X", "Y", false)));
        assertThat(plan.cycle()).isTrue();
        assertThat(plan.writes()).isFalse();
    }

    @Test
    @DisplayName("Пересчитанное пересчитывать нечего: второй план пуст")
    void planIsIdempotent() {
        List<Row> rows = List.of(row(1, RU_8518100C, "8518100", true), row(2, "8518100C", "8518100C", false),
                row(3, " " + "АВ-1", "B1", false), row(4, ANALOG, "", false));
        PartPlan plan = OemRecount.plan(rows);
        List<Row> after = new ArrayList<>();
        for (Row row : rows) {
            if (plan.merges().stream().anyMatch(m -> m.was().id() == row.id())) {
                continue;
            }
            Row now = plan.updates().stream().filter(c -> c.was().id() == row.id())
                    .map(c -> new Row(row.id(), row.partId(), c.raw(), c.normalized(), row.primary()))
                    .findFirst().orElse(row);
            after.add(now);
        }
        PartPlan again = OemRecount.plan(after);
        assertThat(again.writes()).isFalse();
        assertThat(again.notes()).extracting(OemRecount.Noted::note).containsExactly(Note.NOT_A_NUMBER);
    }

    @Test
    @DisplayName("Литерал возврата переживает строку лога: табуляция и неразрывный пробел — экранами")
    void restoreLiteralIsOneLine() {
        assertThat(OemRecount.literal("a\tb c'd\\e")).isEqualTo("E'a\\u0009b\\u00A0c\\'d\\\\e'");
        assertThat(OemRecount.literal(RU_8518100C)).isEqualTo("E'" + RU_8518100C + "'");
    }

    private static Row row(long id, String raw, String normalized, boolean primary) {
        return new Row(id, 10, raw, normalized, primary);
    }
}
