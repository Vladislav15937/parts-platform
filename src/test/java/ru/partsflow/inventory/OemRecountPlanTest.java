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

    // ------------------------------------------------------------ деление основного (0324)

    @Test
    @DisplayName("0324: основной «8414012530, 8414012531» — первый остаётся основным на месте, второй вставляется кросс-номером")
    void primaryWithSeparatorIsSplit() {
        Row primary = row(1, "8414012530, 8414012531", "84140125308414012531", true);
        PartPlan plan = OemRecount.plan(List.of(primary));

        assertThat(plan.updates()).singleElement().satisfies(change -> {
            assertThat(change.was()).isEqualTo(primary);
            assertThat(change.raw()).isEqualTo("8414012530");
            assertThat(change.normalized()).isEqualTo("8414012530");
        });
        assertThat(plan.inserts()).singleElement().satisfies(insert -> {
            assertThat(insert.raw()).isEqualTo("8414012531");
            assertThat(insert.moved()).isNull();
        });
        assertThat(plan.oemNow()).isEqualTo("8414012530");
        assertThat(plan.crossesNow()).isEqualTo("8414012531");
        assertThat(plan.splits()).singleElement().satisfies(split -> {
            assertThat(split.was().primary()).isTrue();
            assertThat(split.kept()).containsExactly("8414012530", "8414012531");
        });
        assertThat(plan.notes()).isEmpty();
    }

    @Test
    @DisplayName("0324: лишние номера основного встают первыми, имеющиеся кросс-номера переставляются за них")
    void primaryExtrasGoFirst() {
        Row primary = row(1, "A-1; B-2", "A1B2", true);
        Row cross = row(2, "C-3", "C3", false);
        PartPlan plan = OemRecount.plan(List.of(primary, cross));

        assertThat(plan.inserts()).extracting(OemRecount.Insert::raw).containsExactly("B-2", "C-3");
        assertThat(plan.inserts()).extracting(OemRecount.Insert::moved).containsExactly(null, cross);
        assertThat(plan.crossesNow()).isEqualTo("B-2, C-3");
        assertThat(plan.oemNow()).isEqualTo("A-1");
    }

    @Test
    @DisplayName("0324: лишние первыми и тогда, когда строка основного заведена позже кросс-номера")
    void primaryExtrasGoFirstWhenPrimaryRowIsLater() {
        Row cross = row(1, "C-3", "C3", false);
        Row primary = row(2, "A-1; B-2", "A1B2", true);
        PartPlan plan = OemRecount.plan(List.of(cross, primary));

        assertThat(plan.crossesNow()).as("лишний номер основного встал за прежним кросс-номером")
                .isEqualTo("B-2, C-3");
        assertThat(plan.inserts()).extracting(OemRecount.Insert::moved).containsExactly(null, cross);
    }

    @Test
    @DisplayName("0324: часть основного, совпавшая с имеющимся кросс-номером, сливается — номер один, на месте лишнего")
    void primaryPartMergesWithCross() {
        Row primary = row(1, "A-1, B-2", "A1B2", true);
        Row same = row(2, "b2", "B2", false);
        Row other = row(3, "C-3", "C3", false);
        PartPlan plan = OemRecount.plan(List.of(primary, same, other));

        assertThat(plan.merges()).singleElement().satisfies(merge -> {
            assertThat(merge.was()).isEqualTo(same);
            assertThat(merge.into().id()).as("слита с частью основного, которую вставят").isZero();
        });
        assertThat(plan.crossesNow()).isEqualTo("B-2, C-3");
        assertThat(plan.oemNow()).as("основной не теряется").isEqualTo("A-1");
    }

    @Test
    @DisplayName("0324: часть основного, равная другой его части, строки не даёт; «АНАЛОГ» и пустые — называются")
    void primaryPartsRepeatAndNotANumber() {
        Row primary = row(1, "A-1, a1,, " + ANALOG + ", B-2", "A1A1B2", true);
        PartPlan plan = OemRecount.plan(List.of(primary));
        assertThat(plan.oemNow()).isEqualTo("A-1");
        assertThat(plan.crossesNow()).isEqualTo("B-2");
        assertThat(plan.notes()).extracting(OemRecount.Noted::note)
                .containsExactlyInAnyOrder(Note.SPLIT_NOT_A_NUMBER, Note.SPLIT_EMPTY);
        assertThat(plan.splits()).singleElement().satisfies(split -> assertThat(split.merged()).isEqualTo(1));
    }

    @Test
    @DisplayName("0324: первая часть основного «не номер» — строка целиком, основной не угадывается, причина названа")
    void primaryFirstNotANumberIsLeftWhole() {
        PartPlan plan = OemRecount.plan(List.of(row(1, ANALOG + ", FP-3", "FP3", true)));
        assertThat(plan.writes()).isFalse();
        assertThat(plan.notes()).extracting(OemRecount.Noted::note).containsExactly(Note.PRIMARY_FIRST_NOT_A_NUMBER);
    }

    @Test
    @DisplayName("0324: часть основного длиннее предела — строка целиком, причина названа")
    void primaryPartTooLongIsLeftWhole() {
        String longPart = "Z".repeat(OemNumbers.MAX_NORMALIZED_LENGTH + 1);
        PartPlan plan = OemRecount.plan(List.of(row(1, "A-1, " + longPart, "A1" + longPart, true)));
        assertThat(plan.writes()).isFalse();
        assertThat(plan.notes()).extracting(OemRecount.Noted::note).containsExactly(Note.PRIMARY_TOO_LONG);
    }

    @Test
    @DisplayName("0324: основной с разделителем и русской «А» — делится, и каждая часть чистится")
    void primaryWithSeparatorAndAnotherDefectIsSplitAndCleaned() {
        PartPlan plan = OemRecount.plan(List.of(row(1, "\u0410-1\t222", "1222", true)));
        assertThat(plan.oemNow()).isEqualTo("A-1");
        assertThat(plan.crossesNow()).isEqualTo("222");
    }

    @Test
    @DisplayName("0324: результат плана равен правилу ввода OemNumbers.primary на тех же входах")
    void primaryPlanEqualsTheInputRule() {
        for (String input : List.of("8414012530, 8414012531", "84140-12530;84140-12531", "KR-1\tKR-2",
                "KR-3\nKR-4\r\nKR-5", "FP-1, " + ANALOG + ", FP-2", ANALOG + ", FP-3", "DS-1, ds1, DS 1",
                "81170 33670")) {
            PartPlan plan = OemRecount.plan(List.of(row(1, input, OemNumbers.normalize(input), true)));
            OemNumbers.Primary rule = OemNumbers.primary(input);
            String oem = plan.writes() ? plan.oemNow() : input;
            assertThat(oem).as("основной из «%s»", input).isEqualTo(rule.primary());
            assertThat(plan.writes() ? plan.crossesNow() : null).as("кросс-номера из «%s»", input)
                    .isEqualTo(rule.crosses().isEmpty() ? null : String.join(", ", rule.crosses()));
        }
    }

    @Test
    @DisplayName("0324: разделённый основной второй раз не делится — план пуст")
    void primarySplitIsIdempotent() {
        Row primary = row(1, "A-1, B-2", "A1B2", true);
        Row cross = row(2, "C-3", "C3", false);
        PartPlan plan = OemRecount.plan(List.of(primary, cross));
        List<Row> after = List.of(row(1, plan.oemNow(), "A1", true), row(3, "B-2", "B2", false),
                row(4, "C-3", "C3", false));
        assertThat(OemRecount.plan(after).writes()).isFalse();
    }

    // ------------------------------------------------------------ деление (0319)

    @Test
    @DisplayName("0319: «8414012530, 8414012531» — первая часть на месте строки, вторая вставляется")
    void crossesInOneRowAreSplit() {
        Row cross = row(2, "8414012530, 8414012531", "84140125308414012531", false);
        PartPlan plan = OemRecount.plan(List.of(row(1, "A-1", "A1", true), cross));

        assertThat(plan.updates()).singleElement().satisfies(change -> {
            assertThat(change.was()).isEqualTo(cross);
            assertThat(change.raw()).isEqualTo("8414012530");
            assertThat(change.normalized()).isEqualTo("8414012530");
        });
        assertThat(plan.inserts()).singleElement().satisfies(insert -> {
            assertThat(insert.raw()).isEqualTo("8414012531");
            assertThat(insert.normalized()).isEqualTo("8414012531");
            assertThat(insert.moved()).isNull();
        });
        assertThat(plan.splits()).singleElement().satisfies(split -> {
            assertThat(split.kept()).containsExactly("8414012530", "8414012531");
            assertThat(split.merged()).isZero();
        });
        assertThat(plan.crossesNow()).isEqualTo("8414012530, 8414012531");
        assertThat(plan.oemNow()).as("основной не тронут").isEqualTo("A-1");
        assertThat(plan.notes()).isEmpty();
    }

    @Test
    @DisplayName("0319: строки за разделённой переставляются за её части — порядок кросс-номеров точный")
    void rowsAfterTheSplitKeepTheirPlace() {
        Row split = row(2, "X1; " + RU_C + "2", "X1", false);
        Row after = row(3, "Y1", "Y1", false);
        Row analog = row(4, ANALOG, "", false);
        PartPlan plan = OemRecount.plan(List.of(row(1, "A-1", "A1", true), split, after, analog));

        assertThat(plan.updates()).extracting(OemRecount.Change::raw).containsExactly("X1");
        assertThat(plan.inserts()).extracting(OemRecount.Insert::raw).containsExactly("C2", "Y1", ANALOG);
        assertThat(plan.inserts()).extracting(OemRecount.Insert::moved).containsExactly(null, after, analog);
        assertThat(plan.crossesWas()).isEqualTo("X1; " + RU_C + "2, Y1, " + ANALOG);
        assertThat(plan.crossesNow()).isEqualTo("X1, C2, Y1, " + ANALOG);
        assertThat(plan.merges()).isEmpty();
    }

    @Test
    @DisplayName("0319: часть, уже имеющаяся у позиции, дубля не заводит; основной не теряется")
    void partAlreadyPresentIsMerged() {
        Row primary = row(1, "B-1", "B1", true);
        Row split = row(2, "A-1, b1", "A1B1", false);
        Row later = row(3, "a1", "A1", false);
        PartPlan plan = OemRecount.plan(List.of(primary, split, later));

        assertThat(plan.updates()).singleElement().satisfies(change -> {
            assertThat(change.was()).isEqualTo(split);
            assertThat(change.raw()).isEqualTo("A-1");
        });
        assertThat(plan.inserts()).as("«b1» — уже основной номер").isEmpty();
        assertThat(plan.merges()).singleElement().satisfies(merge -> {
            assertThat(merge.was()).isEqualTo(later);
            assertThat(merge.into()).isEqualTo(split);
        });
        assertThat(plan.splits()).singleElement().satisfies(s -> assertThat(s.merged()).isEqualTo(1));
        assertThat(plan.oemNow()).isEqualTo("B-1");
        assertThat(plan.crossesNow()).isEqualTo("A-1");
    }

    @Test
    @DisplayName("0319: первая часть — повтор основного: место строки занимает первая уцелевшая")
    void firstSurvivingPartTakesThePlace() {
        PartPlan plan = OemRecount.plan(List.of(row(1, "A1", "A1", true), row(2, "A1, B2", "A1B2", false)));
        assertThat(plan.updates()).singleElement().satisfies(change -> assertThat(change.raw()).isEqualTo("B2"));
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.merges()).isEmpty();
    }

    @Test
    @DisplayName("0319: все части — уже имеющиеся номера: строка слита и удалена")
    void allPartsPresentMergeTheRow() {
        Row primary = row(1, "A1", "A1", true);
        Row split = row(2, "a-1, A 1", "A1A1", false);
        PartPlan plan = OemRecount.plan(List.of(primary, split));
        assertThat(plan.merges()).singleElement().satisfies(merge -> {
            assertThat(merge.was()).isEqualTo(split);
            assertThat(merge.into()).isEqualTo(primary);
        });
        assertThat(plan.updates()).isEmpty();
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.crossesNow()).isNull();
    }

    @Test
    @DisplayName("0319: пустые части и «не номер» строк не порождают и называются")
    void emptyAndNotANumberPartsAreNamed() {
        PartPlan plan = OemRecount.plan(List.of(row(2, "A1,,  " + ANALOG + "; ,B2", "A1B2", false)));
        assertThat(plan.updates()).extracting(OemRecount.Change::raw).containsExactly("A1");
        assertThat(plan.inserts()).extracting(OemRecount.Insert::raw).containsExactly("B2");
        assertThat(plan.notes()).extracting(OemRecount.Noted::note)
                .containsExactlyInAnyOrder(Note.SPLIT_NOT_A_NUMBER, Note.SPLIT_EMPTY, Note.SPLIT_EMPTY);
        assertThat(plan.notes()).filteredOn(n -> n.note() == Note.SPLIT_NOT_A_NUMBER)
                .extracting(OemRecount.Noted::piece).containsExactly(ANALOG);
    }

    @Test
    @DisplayName("0319: часть длиннее предела 0312 — строка не делится и называется целиком")
    void tooLongPartLeavesTheRowWhole() {
        String over = "1".repeat(OemNumbers.MAX_NORMALIZED_LENGTH + 1);
        PartPlan plan = OemRecount.plan(List.of(row(2, "A1, " + over, "A1" + over, false)));
        assertThat(plan.writes()).isFalse();
        assertThat(plan.notes()).extracting(OemRecount.Noted::note).containsExactly(Note.TOO_LONG);
    }

    @Test
    @DisplayName("0319: часть ровно в предел делится")
    void partAtTheLimitIsSplit() {
        String fits = "1".repeat(OemNumbers.MAX_NORMALIZED_LENGTH);
        PartPlan plan = OemRecount.plan(List.of(row(2, "A1, " + fits, "A1" + fits, false)));
        assertThat(plan.inserts()).extracting(OemRecount.Insert::raw).containsExactly(fits);
    }

    @Test
    @DisplayName("0319: делится только тем, чем делит ввод — пробел, косая и дефис номер не режут")
    void onlyInputSeparatorsSplit() {
        PartPlan whole = OemRecount.plan(List.of(row(2, "81170 33670 / 81170-33671", "81170336708117033671", false)));
        assertThat(whole.splits()).isEmpty();
        for (String separator : List.of(",", ";", "\n", "\r\n", "\t")) {
            PartPlan split = OemRecount.plan(List.of(row(2, "A1" + separator + "B2", "A1B2", false)));
            assertThat(split.inserts()).as("разделитель %s", OemRecount.visibleText(separator))
                    .extracting(OemRecount.Insert::raw).containsExactly("B2");
            assertThat(split.notes()).as("«\\r\\n» — один перевод строки, а не пустая часть").isEmpty();
        }
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
