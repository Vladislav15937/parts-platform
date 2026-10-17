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
 *   <li>«с разделителем» — в одной строке табуляция, запятая или перевод строки,
 *       то есть по нынешнему правилу записи ({@link OemNumbers#list}) это были бы
 *       несколько номеров. Делить строку пересчёт не берётся: это новые строки
 *       в конце набора, то есть другой порядок кросс-номеров, а он виден в прайсе
 *       и в карточке, — вопрос владельцу.</li>
 * </ul>
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

    /** Почему строка не тронута, хотя по правилу записи должна была бы. */
    public enum Note {
        NOT_A_NUMBER("не номер — после приведения ничего не осталось"),
        TOO_LONG("латиницей стал бы длиннее " + OemNumbers.MAX_NORMALIZED_LENGTH + " знаков"),
        HAS_SEPARATOR("в строке разделитель кросс-номеров — по правилу записи это были бы несколько номеров");

        private final String words;

        Note(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }
    }

    /** Строка, которую пересчёт изменит: было — {@code was}, станет — два значения. */
    public record Change(Row was, String raw, String normalized) {
    }

    /** Строка, слитая с другой строкой той же позиции и удаляемая. */
    public record Merge(Row was, Row into) {
    }

    /** Отмеченная строка. */
    public record Noted(Row row, Note note) {
    }

    /**
     * Что пересчёт сделает с одной позицией.
     *
     * @param updates изменения в том порядке, в каком их можно выполнить, не столкнувшись
     *                на {@code part_oem_uk}
     * @param cycle   изменения не упорядочиваются (две строки меняются ключами друг
     *                с другом): позиция не трогается и называется в отчёте
     */
    public record PartPlan(long partId, List<Change> updates, List<Merge> merges,
                           List<Noted> notes, boolean cycle, String oemWas, String oemNow,
                           String crossesWas, String crossesNow) {

        public boolean writes() {
            return !cycle && (!updates.isEmpty() || !merges.isEmpty());
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
     * @param rows строки позиции в порядке {@code id}
     */
    public static PartPlan plan(List<Row> rows) {
        long partId = rows.get(0).partId();
        List<Noted> notes = new ArrayList<>();
        Map<Long, String[]> target = new LinkedHashMap<>();
        for (Row row : rows) {
            String raw = row.raw();
            String norm = row.normalized();
            // Строка с разделителем внутри — по правилу записи это несколько номеров,
            // и делить её пересчёт не берётся (порядок кросс-номеров). Не трогается
            // целиком, даже если в ней есть и другое отличие от правила (русская «А»):
            // поправленная наполовину, она выглядела бы пересчитанной и ушла бы
            // из отчёта о том, что с ней надо решать.
            if (OemNumbers.list(row.raw()).size() > 1) {
                notes.add(new Noted(row, Note.HAS_SEPARATOR));
                target.put(row.id(), new String[]{raw, norm});
                continue;
            }
            String cleaned = OemNumbers.clean(row.raw());
            String normalized = OemNumbers.normalize(cleaned);
            boolean differs = !cleaned.equals(row.raw()) || !Objects.equals(normalized, row.normalized());
            if (differs) {
                if (normalized == null) {
                    notes.add(new Noted(row, Note.NOT_A_NUMBER));
                } else if (OemNumbers.tooLong(cleaned, !row.primary()) != null) {
                    notes.add(new Noted(row, Note.TOO_LONG));
                } else {
                    raw = cleaned;
                    norm = normalized;
                }
            }
            target.put(row.id(), new String[]{raw, norm});
        }

        // Строки, ставшие одним номером: остаётся основная, иначе меньший номер строки.
        Map<String, List<Row>> byKey = new LinkedHashMap<>();
        for (Row row : rows) {
            byKey.computeIfAbsent(target.get(row.id())[1], k -> new ArrayList<>()).add(row);
        }
        List<Merge> merges = new ArrayList<>();
        Set<Long> gone = new LinkedHashSet<>();
        for (List<Row> same : byKey.values()) {
            if (same.size() < 2) {
                continue;
            }
            Row keeper = same.stream().filter(Row::primary).findFirst().orElse(same.get(0));
            for (Row row : same) {
                if (row != keeper) {
                    merges.add(new Merge(row, keeper));
                    gone.add(row.id());
                }
            }
        }

        List<Change> pending = new ArrayList<>();
        for (Row row : rows) {
            String[] t = target.get(row.id());
            if (!gone.contains(row.id()) && (!t[0].equals(row.raw()) || !t[1].equals(row.normalized()))) {
                pending.add(new Change(row, t[0], t[1]));
            }
        }
        // Порядок: строку можно менять, когда её новый ключ не держит другая
        // ещё не изменённая строка. Удалённые к этому моменту уже удалены.
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

        List<Row> after = new ArrayList<>();
        for (Row row : rows) {
            if (!gone.contains(row.id())) {
                String[] t = target.get(row.id());
                after.add(new Row(row.id(), row.partId(), t[0], t[1], row.primary()));
            }
        }
        return new PartPlan(partId, List.copyOf(ordered), List.copyOf(merges), List.copyOf(notes),
                cycle, primaryOf(rows), primaryOf(after), crossesOf(rows), crossesOf(after));
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
        public final Map<Note, Long> notes = new LinkedHashMap<>();
        /** Готовые инструкции возврата: сначала правки, затем вставки слитых. */
        public final List<String> restoreUpdates = new ArrayList<>();
        public final List<String> restoreInserts = new ArrayList<>();
        /** Что именно изменено и слито — строками для человека. */
        public final List<String> lines = new ArrayList<>();

        public List<String> restore() {
            List<String> all = new ArrayList<>(restoreUpdates);
            all.addAll(restoreInserts);
            return all;
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
            describe(schema, plan, tally);
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
                    + " «" + visibleText(noted.row().raw()) + "»: " + noted.note().words());
        }
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
            for (Merge merge : plan.merges()) {
                Row was = merge.was();
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

    private static void describe(String schema, PartPlan plan, Tally tally) {
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
                    + visibleText(was.raw()) + "» слита со строкой " + merge.into().id()
                    + " — тот же номер, удалена");
            tally.restoreInserts.add("INSERT INTO " + schema + ".part_oem"
                    + " (id, part_id, raw_number, normalized, is_primary) OVERRIDING SYSTEM VALUE VALUES ("
                    + was.id() + ", " + was.partId() + ", " + literal(was.raw()) + ", "
                    + literal(was.normalized()) + ", " + was.primary() + ");");
        }
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
            for (String sql : t.restore()) {
                log.info("restore-sql: {}", sql);
            }
            log.info("{}: строк {}, позиций {}; {} позиций {} (строк изменено {}, слито {}); "
                            + "для площадки {}; не тронуто: не номер {}, длиннее предела {}, "
                            + "с разделителем {}; правлены во время запуска {}; меняются местами {}",
                    outcome.schema(), t.rows, t.parts, t.partsChanged,
                    options.apply() ? "пересчитано" : "будет пересчитано",
                    t.rowsUpdated, t.rowsMerged, t.feedMarks,
                    t.noted(Note.NOT_A_NUMBER), t.noted(Note.TOO_LONG), t.noted(Note.HAS_SEPARATOR),
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
