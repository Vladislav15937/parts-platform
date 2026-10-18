package ru.partsflow.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.partsflow.catalog.OemNumbers;
import ru.partsflow.platform.audit.AuditJournal;
import ru.partsflow.platform.tenant.TenantSweep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Пересчёт уже сохранённых номеров производителя тем же правилом, что и запись
 * (задача 0317, решение владельца продукта 17.10.2026 «4б - б»).
 *
 * <p><b>Зачем.</b> С задачи 0309 номер хранится {@link OemNumbers#clean} (русские
 * двойники латиницей, невидимые пробелы по краям срезаны), а приводится
 * {@link OemNumbers#normalize}, который двойники читает латиницей. Сохранённое
 * раньше лежит по-старому: «8518100С» с русской «С» и {@code normalized = 8518100}.
 * Поиск по приведённому виду (0306) такую позицию по «8518100C» не находит, а повтор
 * файла переноса (0282) называет её номер расхождением. На одном складе два правила.
 *
 * <p><b>Правило одно с записью, и второй его копии нет.</b> Для каждой строки
 * {@code part_oem}: {@code raw = clean(raw_number)}, {@code normalized = normalize(raw)}.
 * Повторить это в SQL значило бы завести вторую копию списка двойников и правила
 * смешанных слов, и первая же правка {@code OemNumbers} их развела бы. Поэтому
 * пересчёт — Java, разовым запуском по реестру ({@link TenantSweep}), а не changeset.
 *
 * <p><b>Что не меняется и называется в отчёте</b>, а не решается молча:
 * <ul>
 *   <li>«не номер» — после приведения ничего не осталось («АНАЛОГ»): удалять строку
 *       нельзя (решение владельца — правка, а не чистка), а {@code normalized}
 *       объявлен {@code NOT NULL};</li>
 *   <li>«длиннее предела» — латиницей номер стал бы длиннее
 *       {@link OemNumbers#MAX_NORMALIZED_LENGTH} (0312): старое приведение вырезало
 *       русские буквы, новое их сохраняет, и строка, прежде короткая, перестала бы
 *       помещаться в индекс;</li>
 *   <li>«основной с разделителем» — основной номер, в котором {@link OemNumbers#list}
 *       видит несколько номеров: ввод основной номер не делит нигде (карточка, приёмка,
 *       оба переноса), и пересчёт не делит тоже — иначе следующий ввод того же текста
 *       и повтор файла (0282) разошлись бы с пересчитанным.</li>
 * </ul>
 *
 * <p><b>Строка кросс-номеров с разделителем делится</b> (задача 0319, решение владельца
 * продукта 18.10.2026, «1 - б»): «8414012530, 8414012531» одной строкой становится двумя
 * кросс-номерами — тем же {@link OemNumbers#list}, что и ввод, своих разделителей нет.
 * Риск, названный владельцу: запятая внутри одного номера развалит его на два; поэтому
 * каждая разделяемая строка печатается в отчёт проверки отдельной строкой
 * ({@code split-row:}) — человек просматривает их до {@code --apply}. Как сохраняется
 * порядок и что попадает в возврат — у {@link #plan}.
 *
 * <p><b>Два написания одного номера у позиции сливаются в одну строку.</b>
 * «8518100С» и «8518100C» после пересчёта — один номер, а {@code part_oem_uk
 * (part_id, normalized)} второй такой строки не пустит. Остаётся основная, иначе
 * строка с меньшим номером; вторая удаляется. Основная выигрывает всегда, поэтому
 * {@code is_primary} пересчёт не меняет ни у одной строки — и {@code part_oem_primary_uk}
 * не трогается. Номера строк ({@code id}) не перевыдаются: порядок кросс-номеров
 * ({@code ORDER BY id}) у прайса и карточки прежний, слитая строка просто пропадает.
 *
 * <p><b>Одновременная правка не перезаписывается.</b> Позиция берётся под блокировку
 * {@code FOR NO KEY UPDATE SKIP LOCKED} — той же силы, что у правки карточки
 * ({@code findByIdForUpdate}), — и занятая не ждётся, а пропускается. Каждое
 * изменение — с условием «строка всё ещё та, что прочитали»; ноль строк — значит,
 * её правили между чтением и записью, и вся позиция откатывается к точке сохранения
 * и считается «правлена во время запуска».
 *
 * <p><b>Восстановимо построчно.</b> Каждая изменённая и каждая слитая строка
 * печатается в отчёт готовой инструкцией возврата ({@code restore-sql:}), с прежними
 * {@code id}, {@code part_id}, {@code raw_number}, {@code normalized}, {@code is_primary}.
 * Плюс запись в журнал изменений позиции — тем же видом, каким пишет правку карточка
 * ({@code PartIdentity}: «Номер производителя», «Кросс-номера»), без автора: так журнал
 * называет фоновую работу.
 *
 * <p><b>Отметка для площадки</b> ({@link PartChangeLog}) ставится позиции, у которой
 * изменился текст номера: прайс Дрома несёт {@code raw_number}, а не приведённый.
 * Сколько таких — отдельное число отчёта. <b>По умолчанию отметки не ставятся</b>
 * (решение координатора до слова владельца: тысячи дельт Дрому молча не слать);
 * включает их ключ {@code --feed-marks}. Без отметок номер уедет полным прайсом
 * при следующем заборе.
 */
@Component
public class OemRecount {

    private static final Logger log = LoggerFactory.getLogger(OemRecount.class);

    /** Имя разового запуска: им же берётся замок по арендатору. */
    public static final String JOB = "oem-recount";

    /** Сколько строк читать из курсора за раз. */
    private static final int FETCH = 1000;

    private final TenantSweep sweep;

    public OemRecount(TenantSweep sweep) {
        this.sweep = sweep;
    }

    /** Строка {@code part_oem}, как лежит. */
    public record Row(long id, long partId, String raw, String normalized, boolean primary) {
    }

    /** Почему строка (или часть строки) не тронута, хотя по правилу записи должна была бы. */
    public enum Note {
        NOT_A_NUMBER("не номер — после приведения ничего не осталось"),
        TOO_LONG("латиницей стал бы длиннее " + OemNumbers.MAX_NORMALIZED_LENGTH + " знаков"),
        PRIMARY_HAS_SEPARATOR("основной номер с разделителем кросс-номеров — не делится: ввод"
                + " (карточка, приёмка, оба переноса) основной номер не делит, делит только кросс-номера"),
        SPLIT_NOT_A_NUMBER("часть «не номер» при делении отброшена — строки не порождает"),
        SPLIT_EMPTY("пустая часть при делении — строки не порождает");

        private final String words;

        Note(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }
    }

    /** Строка, которую пересчёт изменит на месте: было — {@code was}, станет — два значения. */
    public record Change(Row was, String raw, String normalized) {
    }

    /**
     * Строка, слитая с другой строкой той же позиции и удаляемая. {@code into} с номером
     * {@code 0} — строка, которую пересчёт вставит (часть разделённой строки).
     */
    public record Merge(Row was, Row into) {
    }

    /**
     * Вставляемая строка (кросс-номер): часть разделённой строки ({@code moved == null})
     * или строка, переставленная за неё ради порядка ({@code moved} — какой она была).
     */
    public record Insert(String raw, String normalized, Row moved) {
    }

    /**
     * Строка кросс-номеров, разделённая на номера (задача 0319).
     *
     * @param numbers части-номера в порядке строки
     * @param kept    из них породили строку (первая — на месте исходной); остальные слиты
     *                с номером, который у позиции уже есть
     */
    public record Split(Row was, List<String> numbers, List<String> kept) {

        public int merged() {
            return numbers.size() - kept.size();
        }
    }

    /** Отмеченная строка; {@code piece} — часть, о которой речь, если речь о части. */
    public record Noted(Row row, Note note, String piece) {
    }

    /**
     * Что пересчёт сделает с одной позицией.
     *
     * @param updates изменения на месте в том порядке, в каком их можно выполнить,
     *                не столкнувшись на {@code part_oem_uk}
     * @param inserts вставки в порядке будущих кросс-номеров — после удалений и правок
     * @param cycle   изменения не упорядочиваются (две строки меняются ключами друг
     *                с другом): позиция не трогается и называется в отчёте
     */
    public record PartPlan(long partId, List<Change> updates, List<Merge> merges, List<Insert> inserts,
                           List<Split> splits, List<Noted> notes, boolean cycle, String oemWas,
                           String oemNow, String crossesWas, String crossesNow) {

        public boolean writes() {
            return !cycle && (!updates.isEmpty() || !merges.isEmpty() || !inserts.isEmpty());
        }

        /** Изменился текст, который видит площадка, — номер или набор кросс-номеров. */
        public boolean visible() {
            return writes() && (!Objects.equals(oemWas, oemNow)
                    || !Objects.equals(crossesWas, crossesNow));
        }
    }

    /**
     * План по строкам одной позиции. Чистая функция: базы не знает и проверяется
     * без неё (`OemRecountPlanTest`).
     *
     * <p><b>Деление (задача 0319).</b> Строка кросс-номеров, в которой {@link OemNumbers#list}
     * видит несколько номеров, становится несколькими строками — тем же правилом, что ввод
     * кросс-номеров ({@code PartIdentity}): части {@code list}, каждая уже {@code clean},
     * «не номер» отброшен, повтор номера не заводится (первый выигрывает, основной —
     * всегда). Первая уцелевшая часть остаётся в исходной строке (тот же {@code id}),
     * остальные вставляются. <b>Порядок кросс-номеров сохраняется точно:</b> новые
     * {@code id} больше всех прежних, поэтому строки, стоявшие за разделённой, удаляются
     * и вставляются заново за её частями — тем же текстом, с новым {@code id}. На
     * {@code part_oem.id} не ссылается ничто: правка карточки переписывает набор целиком
     * точно так же.
     *
     * @param rows строки позиции в порядке {@code id}
     */
    public static PartPlan plan(List<Row> rows) {
        long partId = rows.get(0).partId();
        List<Noted> notes = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        Map<Row, List<String>> numbersOf = new LinkedHashMap<>();
        for (Row row : rows) {
            List<String> pieces = OemNumbers.list(row.raw());
            if (pieces.size() > 1) {
                if (row.primary()) {
                    // Основной номер ввод не делит ни в одном месте записи (карточка —
                    // cleanOem, приёмка, оба переноса — clean): разделённый, он разошёлся бы
                    // с тем, что запишет следующий ввод того же текста, а повтор файла
                    // (0282) назвал бы номер расхождением. Не трогается целиком — даже
                    // если в нём есть русская «А»: поправленный наполовину, он ушёл бы
                    // из отчёта о том, что с ним надо решать.
                    notes.add(new Noted(row, Note.PRIMARY_HAS_SEPARATOR, null));
                    entries.add(new Entry(row, row.raw(), row.normalized()));
                    continue;
                }
                List<String> numbers = new ArrayList<>();
                List<String> notNumbers = new ArrayList<>();
                String tooLong = null;
                for (String piece : pieces) {
                    if (OemNumbers.normalize(piece) == null) {
                        notNumbers.add(piece);
                    } else if (OemNumbers.tooLong(piece, true) != null) {
                        tooLong = piece;
                    } else {
                        numbers.add(piece);
                    }
                }
                // Часть, которую нельзя сохранить, — строка остаётся целиком как есть:
                // отбросить её значило бы потерять номер, а не «не номер».
                if (tooLong != null || numbers.isEmpty()) {
                    notes.add(new Noted(row, tooLong != null ? Note.TOO_LONG : Note.NOT_A_NUMBER, tooLong));
                    entries.add(new Entry(row, row.raw(), row.normalized()));
                    continue;
                }
                for (String piece : notNumbers) {
                    notes.add(new Noted(row, Note.SPLIT_NOT_A_NUMBER, piece));
                }
                for (int i = OemNumbers.emptyPieces(row.raw()); i > 0; i--) {
                    notes.add(new Noted(row, Note.SPLIT_EMPTY, ""));
                }
                numbersOf.put(row, numbers);
                for (String number : numbers) {
                    entries.add(new Entry(row, number, OemNumbers.normalize(number)));
                }
                continue;
            }
            String raw = row.raw();
            String norm = row.normalized();
            String cleaned = OemNumbers.clean(row.raw());
            String normalized = OemNumbers.normalize(cleaned);
            boolean differs = !cleaned.equals(row.raw()) || !Objects.equals(normalized, row.normalized());
            if (differs) {
                if (normalized == null) {
                    notes.add(new Noted(row, Note.NOT_A_NUMBER, null));
                } else if (OemNumbers.tooLong(cleaned, !row.primary()) != null) {
                    notes.add(new Noted(row, Note.TOO_LONG, null));
                } else {
                    raw = cleaned;
                    norm = normalized;
                }
            }
            entries.add(new Entry(row, raw, norm));
        }

        // Одинаковые номера: основная выигрывает всегда, иначе первая по порядку —
        // как при вводе (основной, затем кросс-номера по порядку, повтор отброшен).
        // Для строк без деления «первая по порядку» — меньший номер строки, как в 0317.
        Map<String, Entry> keeper = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e.origin.primary()) {
                keeper.putIfAbsent(e.normalized, e);
            }
        }
        for (Entry e : entries) {
            keeper.putIfAbsent(e.normalized, e);
        }
        List<Entry> kept = new ArrayList<>();
        Set<Row> placed = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (keeper.get(e.normalized) == e) {
                // Первая уцелевшая часть строки занимает её место, остальные — новые.
                e.inPlace = placed.add(e.origin);
                kept.add(e);
            }
        }
        // Кросс-номера после первой новой строки переставляются за неё: новый id
        // больше любого прежнего, и без перестановки порядок ORDER BY id сменился бы.
        // Основной номер порядка не несёт и не переставляется.
        boolean tail = false;
        for (Entry e : kept) {
            if (e.origin.primary()) {
                continue;
            }
            if (!e.inPlace) {
                tail = true;
            } else if (tail) {
                e.moved = true;
            }
        }

        List<Merge> merges = new ArrayList<>();
        for (Row row : rows) {
            if (!placed.contains(row)) {
                Entry first = entries.stream().filter(e -> e.origin == row).findFirst().orElseThrow();
                merges.add(new Merge(row, keeper.get(first.normalized).slot()));
            }
        }
        List<Split> splits = new ArrayList<>();
        numbersOf.forEach((row, numbers) -> splits.add(new Split(row, List.copyOf(numbers),
                kept.stream().filter(e -> e.origin == row).map(e -> e.raw).toList())));

        List<Change> pending = new ArrayList<>();
        List<Insert> inserts = new ArrayList<>();
        for (Entry e : kept) {
            if (e.inPlace && !e.moved) {
                if (!e.raw.equals(e.origin.raw()) || !e.normalized.equals(e.origin.normalized())) {
                    pending.add(new Change(e.origin, e.raw, e.normalized));
                }
            } else {
                inserts.add(new Insert(e.raw, e.normalized, e.moved ? e.origin : null));
            }
        }
        // Порядок: строку можно менять, когда её новый ключ не держит другая
        // ещё не изменённая строка. Удалённые и переставляемые к этому моменту
        // уже удалены, вставки идут после всех правок.
        List<Change> ordered = new ArrayList<>();
        boolean cycle = false;
        while (!pending.isEmpty()) {
            Change next = null;
            for (Change candidate : pending) {
                boolean blocked = false;
                for (Change other : pending) {
                    if (other != candidate && other.was().normalized().equals(candidate.normalized())) {
                        blocked = true;
                        break;
                    }
                }
                if (!blocked) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                cycle = true;
                ordered.addAll(pending);
                break;
            }
            ordered.add(next);
            pending.remove(next);
        }

        List<Row> after = kept.stream().map(Entry::asRow).toList();
        return new PartPlan(partId, List.copyOf(ordered), List.copyOf(merges), List.copyOf(inserts),
                List.copyOf(splits), List.copyOf(notes), cycle, primaryOf(rows), primaryOf(after),
                crossesOf(rows), crossesOf(after));
    }

    /** Будущая строка позиции: из какой строки взялась и с какими значениями. */
    private static final class Entry {
        final Row origin;
        final String raw;
        final String normalized;
        /** Стоит на месте строки {@code origin} (тот же {@code id}); иначе — новая. */
        boolean inPlace;
        /** Была на месте, но стоит за новой строкой: удаляется и вставляется заново. */
        boolean moved;

        Entry(Row origin, String raw, String normalized) {
            this.origin = origin;
            this.raw = raw;
            this.normalized = normalized;
        }

        /** Строка, какой она станет: прежний {@code id} у стоящей на месте, {@code 0} у вставленной. */
        Row asRow() {
            boolean same = inPlace && !moved;
            if (same && raw.equals(origin.raw()) && normalized.equals(origin.normalized())) {
                return origin;
            }
            return new Row(same ? origin.id() : 0, origin.partId(), raw, normalized, origin.primary());
        }

        /** С какой строкой сливаются: прежняя на месте — она как была, иначе вставляемая. */
        Row slot() {
            return inPlace && !moved ? origin : new Row(0, origin.partId(), raw, normalized, origin.primary());
        }
    }

    /** Номер производителя позиции в том виде, в каком его пишет журнал карточки. */
    private static String primaryOf(List<Row> rows) {
        return rows.stream().filter(Row::primary).map(Row::raw).findFirst().orElse(null);
    }

    /** Кросс-номера через «, » в порядке строк — как {@code PartIdentity.of}. */
    private static String crossesOf(List<Row> rows) {
        List<String> crosses = rows.stream().filter(r -> !r.primary()).map(Row::raw).toList();
        return crosses.isEmpty() ? null : String.join(", ", crosses);
    }

    // ------------------------------------------------------------------ запуск

    /** Ключи запуска. */
    public record Options(boolean apply, String onlySchema, boolean feedMarks) {
    }

    /** Итог по одному арендатору. */
    public static final class Tally {
        public long rows;
        public long parts;
        public long partsChanged;
        public long rowsUpdated;
        public long rowsMerged;
        public long feedMarks;
        public long busy;
        public long cycles;
        /** Деление (0319): разделённых строк, полученных из них номеров, частей, слитых с имеющимися. */
        public long splitRows;
        public long splitNumbers;
        public long splitMerged;
        /** Вставлено строк: части разделённых и переставленные за них ради порядка. */
        public long rowsInserted;
        public long rowsMoved;
        public final Map<Note, Long> notes = new LinkedHashMap<>();
        /**
         * Готовые инструкции возврата: сначала удаление вставленных, затем правки
         * в обратном порядке, затем вставки слитых и переставленных с прежним {@code id}.
         */
        public final List<String> restoreDeletes = new ArrayList<>();
        public final List<String> restoreUpdates = new ArrayList<>();
        public final List<String> restoreInserts = new ArrayList<>();
        /** Что именно изменено и слито — строками для человека. */
        public final List<String> lines = new ArrayList<>();
        /** Каждая строка, которая будет разделена, — для просмотра человеком до запуска. */
        public final List<String> splitLines = new ArrayList<>();

        public List<String> restore() {
            List<String> all = new ArrayList<>(restoreDeletes);
            all.addAll(restoreUpdates);
            all.addAll(restoreInserts);
            return all;
        }

        /** Частей, не породивших строк: пустые и «не номер». */
        public long splitSkipped() {
            return noted(Note.SPLIT_EMPTY) + noted(Note.SPLIT_NOT_A_NUMBER);
        }

        public long noted(Note note) {
            return notes.getOrDefault(note, 0L);
        }

        /** Нечего пересчитывать: повторный запуск после {@code --apply} обязан дать это. */
        public boolean nothingToDo() {
            return partsChanged == 0 && busy == 0 && cycles == 0;
        }
    }

    /**
     * Пересчитать (или посчитать, без {@code apply}) у всех арендаторов реестра.
     *
     * @return итоги по схемам; отказ арендатора — в {@link TenantSweep.Outcome#problem()}
     */
    public List<TenantSweep.Outcome<Tally>> run(Options options) {
        List<TenantSweep.Outcome<Tally>> outcomes = sweep.run(JOB, options.apply(), options.onlySchema(),
                (jdbc, tenant) -> recount(jdbc, tenant.schema(), options));
        report(outcomes, options);
        return outcomes;
    }

    private Tally recount(JdbcTemplate shared, String schema, Options options) {
        // Свой экземпляр — ради размера выборки курсора; транзакция та же:
        // соединение привязано к источнику, а не к экземпляру шаблона.
        JdbcTemplate jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(shared.getDataSource()));
        jdbc.setFetchSize(FETCH);
        Tally tally = new Tally();
        List<PartPlan> plans = new ArrayList<>();
        List<Row> group = new ArrayList<>();
        // Курсором и по позиции целиком: на складе в 50 000 позиций это ~125 000
        // строк, а в памяти остаются только планы позиций, которые что-то меняют.
        jdbc.query("""
                SELECT id, part_id, raw_number, normalized, is_primary
                  FROM part_oem ORDER BY part_id, id""", rs -> {
            Row row = new Row(rs.getLong("id"), rs.getLong("part_id"), rs.getString("raw_number"),
                    rs.getString("normalized"), rs.getBoolean("is_primary"));
            tally.rows++;
            if (!group.isEmpty() && group.get(0).partId() != row.partId()) {
                keep(plan(group), plans, tally);
                group.clear();
            }
            group.add(row);
        });
        if (!group.isEmpty()) {
            keep(plan(group), plans, tally);
        }

        PartChangeLog marks = new PartChangeLog(jdbc);
        AuditJournal journal = new AuditJournal(jdbc);
        List<Long> visible = new ArrayList<>();
        Map<Long, String> names = names(jdbc, plans);
        for (PartPlan plan : plans) {
            if (plan.cycle()) {
                tally.cycles++;
                tally.lines.add(schema + " позиция " + plan.partId()
                        + ": номера меняются местами друг с другом — не тронута, разобрать руками");
                continue;
            }
            if (!plan.writes()) {
                continue;
            }
            if (options.apply() && !apply(jdbc, plan)) {
                tally.busy++;
                tally.lines.add(schema + " позиция " + plan.partId()
                        + ": правлена во время запуска — не тронута, повторите запуск");
                continue;
            }
            tally.partsChanged++;
            tally.rowsUpdated += plan.updates().size();
            tally.rowsMerged += plan.merges().size();
            for (Insert insert : plan.inserts()) {
                if (insert.moved() == null) {
                    tally.rowsInserted++;
                } else {
                    tally.rowsMoved++;
                }
            }
            for (Split split : plan.splits()) {
                tally.splitRows++;
                tally.splitNumbers += split.kept().size();
                tally.splitMerged += split.merged();
            }
            describe(schema, plan, tally, names.get(plan.partId()));
            if (plan.visible()) {
                tally.feedMarks++;
                visible.add(plan.partId());
                if (options.apply()) {
                    journal(journal, plan);
                }
            }
        }
        if (options.apply() && options.feedMarks() && !visible.isEmpty()) {
            marks.changed(visible);
        }
        return tally;
    }

    private static void keep(PartPlan plan, List<PartPlan> plans, Tally tally) {
        tally.parts++;
        for (Noted noted : plan.notes()) {
            tally.notes.merge(noted.note(), 1L, Long::sum);
        }
        if (plan.writes() || plan.cycle()) {
            plans.add(plan);
        }
        for (Noted noted : plan.notes()) {
            tally.lines.add("позиция " + plan.partId() + ", строка " + noted.row().id()
                    + " «" + visibleText(noted.row().raw()) + "»: " + noted.note().words()
                    + (noted.piece() == null || noted.piece().isEmpty() ? ""
                    : " — «" + visibleText(noted.piece()) + "»"));
        }
    }

    /**
     * «№ позиции и код» позиций с разделёнными строками — для списка, который человек
     * просматривает до запуска: по номеру строки базы позицию на витрине не найти.
     */
    private static Map<Long, String> names(JdbcTemplate jdbc, List<PartPlan> plans) {
        List<Long> ids = plans.stream().filter(p -> !p.splits().isEmpty() && !p.cycle())
                .map(PartPlan::partId).toList();
        Map<Long, String> names = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += FETCH) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + FETCH));
            jdbc.query("SELECT id, number, public_code FROM part WHERE id = ANY (?)",
                    rs -> {
                        names.put(rs.getLong("id"), "№ позиции " + rs.getLong("number")
                                + ", код " + rs.getString("public_code"));
                    },
                    (Object) chunk.toArray(Long[]::new));
        }
        return names;
    }

    /**
     * Одна позиция: блокировка, удаление слитых, правки в безопасном порядке.
     *
     * @return {@code false} — позицию держит другая правка или строки уже не те,
     *         что прочитаны; всё сделанное по ней откачено
     */
    private boolean apply(JdbcTemplate jdbc, PartPlan plan) {
        List<Long> locked = jdbc.queryForList(
                "SELECT id FROM part WHERE id = ? FOR NO KEY UPDATE SKIP LOCKED", Long.class, plan.partId());
        if (locked.isEmpty()) {
            return false;
        }
        jdbc.execute("SAVEPOINT oem_recount_part");
        try {
            List<Row> deleted = new ArrayList<>();
            plan.merges().forEach(merge -> deleted.add(merge.was()));
            plan.inserts().stream().map(Insert::moved).filter(Objects::nonNull).forEach(deleted::add);
            for (Row was : deleted) {
                if (jdbc.update("""
                        DELETE FROM part_oem
                         WHERE id = ? AND part_id = ? AND raw_number = ? AND normalized = ?
                           AND is_primary = ?""",
                        was.id(), was.partId(), was.raw(), was.normalized(), was.primary()) != 1) {
                    jdbc.execute("ROLLBACK TO SAVEPOINT oem_recount_part");
                    return false;
                }
            }
            for (Change change : plan.updates()) {
                Row was = change.was();
                if (jdbc.update("""
                        UPDATE part_oem SET raw_number = ?, normalized = ?
                         WHERE id = ? AND part_id = ? AND raw_number = ? AND normalized = ?
                           AND is_primary = ?""",
                        change.raw(), change.normalized(),
                        was.id(), was.partId(), was.raw(), was.normalized(), was.primary()) != 1) {
                    jdbc.execute("ROLLBACK TO SAVEPOINT oem_recount_part");
                    return false;
                }
            }
            // Вставки — в порядке будущих кросс-номеров: id раздаёт последовательность
            // по возрастанию, и порядок вставки становится порядком ORDER BY id.
            for (Insert insert : plan.inserts()) {
                jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)"
                        + " VALUES (?, ?, ?, false)", plan.partId(), insert.raw(), insert.normalized());
            }
        } catch (DataAccessException e) {
            // Уникальность отбила строку, которую между чтением и записью завёл
            // кто-то ещё (дозаполнение повтором файла, правка карточки мимо блокировки).
            // Позиция откатывается целиком, остальной склад пересчитывается дальше.
            log.warn("Пересчёт номеров: позиция {} не пересчитана: {}", plan.partId(), e.getMessage());
            jdbc.execute("ROLLBACK TO SAVEPOINT oem_recount_part");
            return false;
        }
        jdbc.execute("RELEASE SAVEPOINT oem_recount_part");
        return true;
    }

    private static void journal(AuditJournal journal, PartPlan plan) {
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        if (!Objects.equals(plan.oemWas(), plan.oemNow())) {
            was.put("oem", plan.oemWas());
            now.put("oem", plan.oemNow());
        }
        if (!Objects.equals(plan.crossesWas(), plan.crossesNow())) {
            was.put("crosses", plan.crossesWas());
            now.put("crosses", plan.crossesNow());
        }
        journal.changed("part", plan.partId(), was, now);
    }

    private static void describe(String schema, PartPlan plan, Tally tally, String name) {
        for (Split split : plan.splits()) {
            Row was = split.was();
            List<String> merged = new ArrayList<>(split.numbers());
            split.kept().forEach(merged::remove);
            List<String> dropped = plan.notes().stream()
                    .filter(n -> n.row() == was && n.note() == Note.SPLIT_NOT_A_NUMBER)
                    .map(n -> "«" + visibleText(n.piece()) + "»").toList();
            long empty = plan.notes().stream()
                    .filter(n -> n.row() == was && n.note() == Note.SPLIT_EMPTY).count();
            tally.splitLines.add((name == null ? "" : name + ", ") + "позиция " + plan.partId()
                    + ", строка " + was.id() + ": «" + visibleText(was.raw()) + "» → "
                    + String.join(" | ", split.kept().stream().map(k -> "«" + visibleText(k) + "»").toList())
                    + (merged.isEmpty() ? "" : "; уже есть у позиции: "
                    + String.join(", ", merged.stream().map(m -> "«" + visibleText(m) + "»").toList()))
                    + (dropped.isEmpty() ? "" : "; не номер, отброшено: " + String.join(", ", dropped))
                    + (empty == 0 ? "" : "; пустых частей " + empty));
        }
        for (Insert insert : plan.inserts()) {
            Row moved = insert.moved();
            tally.restoreDeletes.add("DELETE FROM " + schema + ".part_oem WHERE part_id = " + plan.partId()
                    + " AND normalized = " + literal(insert.normalized()) + ";");
            if (moved != null) {
                tally.lines.add("позиция " + plan.partId() + ", строка " + moved.id() + " «"
                        + visibleText(moved.raw()) + "» переставлена за разделённую — тот же номер,"
                        + " новый номер строки, порядок кросс-номеров прежний");
                tally.restoreInserts.add(restoreInsert(schema, moved));
            }
        }
        for (Change change : plan.updates()) {
            Row was = change.was();
            tally.lines.add("позиция " + plan.partId() + ", строка " + was.id() + ": «"
                    + visibleText(was.raw()) + "» (" + was.normalized() + ") → «"
                    + visibleText(change.raw()) + "» (" + change.normalized() + ")");
            tally.restoreUpdates.add(0, "UPDATE " + schema + ".part_oem SET raw_number = "
                    + literal(was.raw()) + ", normalized = " + literal(was.normalized())
                    + " WHERE id = " + was.id() + ";");
        }
        for (Merge merge : plan.merges()) {
            Row was = merge.was();
            tally.lines.add("позиция " + plan.partId() + ", строка " + was.id() + " «"
                    + visibleText(was.raw()) + "» слита "
                    + (merge.into().id() == 0 ? "с частью разделённой строки «" + visibleText(merge.into().raw()) + "»"
                    : "со строкой " + merge.into().id())
                    + " — тот же номер, удалена");
            tally.restoreInserts.add(restoreInsert(schema, was));
        }
    }

    private static String restoreInsert(String schema, Row was) {
        return "INSERT INTO " + schema + ".part_oem"
                + " (id, part_id, raw_number, normalized, is_primary) OVERRIDING SYSTEM VALUE VALUES ("
                + was.id() + ", " + was.partId() + ", " + literal(was.raw()) + ", "
                + literal(was.normalized()) + ", " + was.primary() + ");";
    }

    private static void report(List<TenantSweep.Outcome<Tally>> outcomes, Options options) {
        String mode = options.apply() ? "ПЕРЕСЧЁТ (--apply)" : "ПРОВЕРКА (--dry-run, ничего не пишется)";
        log.info("Пересчёт номеров производителя: {}", mode);
        long changed = 0;
        long marks = 0;
        for (TenantSweep.Outcome<Tally> outcome : outcomes) {
            if (outcome.problem() != null) {
                log.error("{}: не выполнено — {}", outcome.schema(), outcome.problem());
                continue;
            }
            Tally t = outcome.result();
            changed += t.partsChanged;
            marks += t.feedMarks;
            for (String line : t.lines) {
                log.info("{}: {}", outcome.schema(), line);
            }
            // Каждая разделяемая строка — своей строкой лога с меткой: команда для ПРОМ
            // складывает их в отдельный файл, и человек просматривает его до --apply
            // (риск, названный владельцу: запятая внутри одного номера развалит его).
            for (String line : t.splitLines) {
                log.info("split-row: {} {}", outcome.schema(), line);
            }
            for (String sql : t.restore()) {
                log.info("restore-sql: {}", sql);
            }
            log.info("{}: строк {}, позиций {}; {} позиций {} (строк изменено {}, слито {}, вставлено {},"
                            + " переставлено ради порядка {}); деление: строк {}, получено номеров {},"
                            + " частей слито с имеющимися {}, частей пропущено {} (пустых {}, не номер {});"
                            + " для площадки {}; не тронуто: не номер {}, длиннее предела {},"
                            + " основной с разделителем {}; правлены во время запуска {}; меняются местами {}",
                    outcome.schema(), t.rows, t.parts, t.partsChanged,
                    options.apply() ? "пересчитано" : "будет пересчитано",
                    t.rowsUpdated, t.rowsMerged, t.rowsInserted, t.rowsMoved,
                    t.splitRows, t.splitNumbers, t.splitMerged, t.splitSkipped(),
                    t.noted(Note.SPLIT_EMPTY), t.noted(Note.SPLIT_NOT_A_NUMBER), t.feedMarks,
                    t.noted(Note.NOT_A_NUMBER), t.noted(Note.TOO_LONG), t.noted(Note.PRIMARY_HAS_SEPARATOR),
                    t.busy, t.cycles);
        }
        log.info("Итого: позиций {} {}; позиций, изменившихся для площадки, {} — {}", changed,
                options.apply() ? "пересчитано" : "будет пересчитано", marks,
                options.feedMarks() ? "отметки ставятся (--feed-marks)"
                        : "отметки НЕ ставятся (по умолчанию; номера уедут полным прайсом"
                        + " при следующем заборе, включить — --feed-marks)");
        if (options.feedMarks() && marks > DELTA_PACKET) {
            log.warn("Отметок больше пакета дельты ({}): Дром получит их {} пакетами подряд",
                    DELTA_PACKET, (marks + DELTA_PACKET - 1) / DELTA_PACKET);
        }
    }

    /** Пакет дельты релея ({@code FeedDeltaRelay.BATCH_SIZE}) — для слов отчёта. */
    static final int DELTA_PACKET = 500;

    /**
     * Строковый литерал Postgres, который переживает строку лога: управляющие
     * и невидимые знаки — экранами {@code \}{@code uXXXX}, а не как есть.
     * Номер с табуляцией внутри иначе разорвал бы строку отчёта.
     */
    static String literal(String value) {
        if (value == null) {
            return "NULL";
        }
        StringBuilder out = new StringBuilder("E'");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\'') {
                out.append("\\'");
            } else if (c < 0x20 || c == 0x7F || Character.isSpaceChar(c) && c != ' '
                    || Character.getType(c) == Character.FORMAT) {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('\'').toString();
    }

    /** Номер для строки отчёта: невидимые знаки — кодом, чтобы «пробел по краю» было видно. */
    static String visibleText(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F || Character.isSpaceChar(c) && c != ' '
                    || Character.getType(c) == Character.FORMAT) {
                out.append(String.format("<U+%04X>", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
