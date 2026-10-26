package ru.partsflow.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.partsflow.platform.audit.AuditJournal;
import ru.partsflow.platform.tenant.TenantSweep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Разовый пересчёт хранимых названий позиций по формуле Bazon (задача 0336,
 * пункт 4; решение владельца продукта 19.10.2026 «Всё да, всё как в базон»,
 * «да» на разовый пересчёт 20.10.2026).
 *
 * <p><b>Зачем.</b> С 0336 название — производное: правка поля формулы собирает
 * его заново ({@link PartTitles}). Хранимое до этого лежит по прежним правилам:
 * без «(б/у)» (0315), с видом из эталона справочника, с годом числом. Склад
 * двух видов — «Фара … 8414012530» рядом с «Фара … (б/у) 8414012530» — и первое
 * же касание позиции меняет её название само.
 *
 * <p><b>Правило одно с правкой карточки, второй копии нет.</b> Поля формулы
 * читает {@link PartTitles#fieldsSql}, собирает {@link PartTitles#compose} —
 * ровно то, что зовёт {@link PartTitles#retitle}. Поэтому повтор после
 * {@code --apply} ничего не меняет, и следующая правка позиции её название
 * не сдвинет. <b>Марка и модель — названия справочника</b> (донора или своей
 * машины), а не текст Bazon («C-Class», а не «C-class C240»): текст Bazon перенос
 * не хранит, и до колонок под него (задача 0336, ответ владельца 22.10.2026, п. 1)
 * запуск на ПРОМ запрещён. Год — {@code year_text}, иначе число; вид — как ввёл клиент;
 * номер — основной, как вписан; позиции, перенесённые с Дрома, — тоже
 * по формуле (решения владельца, там же).
 *
 * <p><b>Что не трогается и называется в отчёте</b>, а не решается молча:
 * <ul>
 *   <li>«нет вида» — у позиции нет написания ({@code part_name_id} пуст),
 *       собирать не из чего;</li>
 *   <li>«машина только в названии» — перенесена из Bazon ({@code legacy_code}),
 *       ни донора, ни своей машины: списки марок, моделей, кузовов лежат
 *       только в названии, и формула без них стёрла бы их. Называются только
 *       те, у кого формула без машины дала бы другое название: у остальных
 *       («Ремень поликлиновой (б/у) 4PK905») оно уже по формуле;</li>
 *   <li>колёса ({@code product_line <> 'PART'}) — у них своё название
 *       из размеров ({@code WheelService.titleOf}), только числом.</li>
 * </ul>
 *
 * <p><b>Одновременная правка не перезаписывается.</b> Каждое изменение —
 * с условием «название всё ещё то, что прочитали», а строка берётся
 * {@code FOR NO KEY UPDATE SKIP LOCKED} — той же силы, что у правки карточки:
 * занятая не ждётся, а пропускается и считается «правлена во время запуска».
 *
 * <p><b>Восстановимо построчно.</b> Каждое записанное изменение печатается
 * в отчёт {@code --apply} готовой инструкцией возврата ({@code restore-sql:};
 * проверка их не печатает — возвращать нечего) — прежнее название
 * по номеру строки, с условием «название всё ещё пересчитанное», — и пишется
 * в журнал изменений позиции той же записью, что правка карточки
 * («Наименование: … → …»), без автора: так журнал называет фоновую работу.
 * <b>Возврат журнала не откатывает:</b> запись «Наименование: … → …» остаётся,
 * а возврат прямым SQL своей записи не оставляет — история позиции после него
 * показывает пересчитанное название, хотя лежит прежнее.
 * {@code updated_at} не трогается: «дата правки» у 36 тысяч позиций в один
 * день сказала бы, что их правили люди.
 *
 * <p><b>Отметка для площадки</b> ({@link PartChangeLog}) по умолчанию
 * не ставится, включает её {@code --feed-marks}. Хранимое название в файл
 * Дрома не идёт — там своя формула (0335, {@code DromPriceWriter.nameOf}),
 * а оно уходит только у позиции без вида, которую пересчёт не трогает.
 */
@Component
public class TitleRecount {

    private static final Logger log = LoggerFactory.getLogger(TitleRecount.class);

    /** Имя разового запуска: им же берётся замок по арендатору. */
    public static final String JOB = "title-recount";

    /** Сколько строк читать из курсора и писать пачкой за раз. */
    private static final int CHUNK = 1000;

    private final TenantSweep sweep;

    public TitleRecount(TenantSweep sweep) {
        this.sweep = sweep;
    }

    /** Ключи запуска. */
    public record Options(boolean apply, String onlySchema, boolean feedMarks) {
    }

    /** Почему название не тронуто, хотя формула могла бы его сменить. */
    public enum Note {
        NO_KIND("нет вида детали — собрать название не из чего"),
        VEHICLE_ONLY_IN_TITLE("перенесена из Bazon без донора и своей машины — машины лежат"
                + " только в названии, формула без них их стёрла бы");

        private final String words;

        Note(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }
    }

    /** Позиция, как её видит пересчёт: кто она для человека и что у неё в названии. */
    public record Position(long id, long number, String code, String title) {
    }

    /** Изменение названия: было — {@code position.title()}, станет — {@code now}. */
    public record Change(Position position, String now) {
    }

    /** Позиция, которую пересчёт не тронул, и что с ней сделала бы формула. */
    public record Skipped(Position position, Note note, String formula) {
    }

    /** Итог по одному арендатору. */
    public static final class Tally {
        /** Позиций-запчастей прочитано. */
        public long parts;
        /** Колёс и прочего не по формуле — не читаются. */
        public long wheels;
        public long unchanged;
        public long changed;
        public long busy;
        public long feedMarks;
        public final Map<Note, Long> notes = new LinkedHashMap<>();
        /** «было → стало» и пропуски — строками для человека. */
        public final List<String> lines = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        /** Готовые инструкции возврата. */
        public final List<String> restore = new ArrayList<>();

        public long noted(Note note) {
            return notes.getOrDefault(note, 0L);
        }

        /** Нечего пересчитывать: повтор после {@code --apply} обязан дать это. */
        public boolean nothingToDo() {
            return changed == 0 && busy == 0;
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
        JdbcTemplate jdbc = new JdbcTemplate(Objects.requireNonNull(shared.getDataSource()));
        jdbc.setFetchSize(CHUNK);
        // Генератор без состояния; PartTitles — ради compose, базы ему здесь не нужно.
        PartTitles titles = new PartTitles(jdbc, new PartTitleGenerator());
        Tally tally = new Tally();
        Long wheels = jdbc.queryForObject("SELECT count(*) FROM part WHERE product_line <> 'PART'", Long.class);
        tally.wheels = wheels == null ? 0 : wheels;

        List<Change> changes = new ArrayList<>();
        // Курсором: на складе в 50 000 позиций в памяти остаются только изменения.
        jdbc.query(PartTitles.fieldsSql("pn.id = p.part_name_id", "d.id = p.donor_id")
                        .replaceFirst("SELECT ", "SELECT p.id AS part_key, p.number, p.public_code, p.title,"
                                + " p.legacy_code, p.donor_id AS donor_key, p.side_fr, p.side_lr, p.side_ud,"
                                + " p.condition, ")
                        + " WHERE p.product_line = 'PART' ORDER BY p.number",
                rs -> {
                    tally.parts++;
                    Position position = new Position(rs.getLong("part_key"), rs.getLong("number"),
                            rs.getString("public_code"), rs.getString("title"));
                    PartTitles.Fields fields = PartTitles.fieldsOf(rs);
                    String condition = rs.getString("condition");
                    PartTitleGenerator.Sides sides = new PartTitleGenerator.Sides(
                            enumOf(LongitudinalSide.class, rs.getString("side_fr")),
                            enumOf(LateralSide.class, rs.getString("side_lr")),
                            enumOf(VerticalSide.class, rs.getString("side_ud")));
                    if (fields.kind() == null) {
                        skip(tally, schema, new Skipped(position, Note.NO_KIND, null));
                        return;
                    }
                    String now = titles.compose(fields, sides,
                            condition == null ? null : PartCondition.valueOf(condition));
                    if (now.equals(position.title())) {
                        tally.unchanged++;
                        return;
                    }
                    Long donor = (Long) rs.getObject("donor_key");
                    if (PartTitles.vehicleOnlyInTitle(rs.getString("legacy_code"), donor, fields.ownVehicle())) {
                        skip(tally, schema, new Skipped(position, Note.VEHICLE_ONLY_IN_TITLE, now));
                        return;
                    }
                    changes.add(new Change(position, now));
                });

        List<Change> written = changes;
        if (options.apply()) {
            List<Change> busy = new ArrayList<>();
            written = write(jdbc, changes, busy);
            tally.busy = busy.size();
            for (Change change : busy) {
                tally.lines.add(name(change.position()) + ": правлена во время запуска — не тронута,"
                        + " повторите запуск");
            }
        }
        if (options.apply() && !written.isEmpty()) {
            // Кузова своей машины хранятся (0296) и зависят от наименования.
            OwnVehicleCodes codes = new OwnVehicleCodes(jdbc);
            codes.refresh(written.stream().map(c -> c.position().id()).toList());
        }
        List<Long> marked = new ArrayList<>();
        for (Change change : written) {
            tally.changed++;
            Position position = change.position();
            tally.lines.add(name(position) + ": «" + position.title() + "» → «" + change.now() + "»");
            // Возврат печатается только тем, что записано: у проверки возвращать нечего,
            // и файл возврата, собранный из её лога, выглядел бы готовым к делу.
            if (options.apply()) {
                tally.restore.add("UPDATE " + schema + ".part SET title = "
                        + OemRecount.literal(position.title()) + " WHERE id = " + position.id()
                        + " AND title = " + OemRecount.literal(change.now()) + ";");
            }
            marked.add(position.id());
        }
        // Число отметок — только когда их ставят: без --feed-marks «для площадки N»
        // читалось бы как «N дельт уйдёт на Дром».
        tally.feedMarks = options.feedMarks() ? marked.size() : 0;
        if (options.apply() && options.feedMarks()) {
            PartChangeLog marks = new PartChangeLog(jdbc);
            for (int from = 0; from < marked.size(); from += CHUNK) {
                marks.changed(marked.subList(from, Math.min(marked.size(), from + CHUNK)));
            }
        }
        return tally;
    }

    /**
     * Пишет названия пачками и каждую записанную — в журнал изменений позиции
     * той же записью, что правка карточки. Строка берётся под блокировку
     * без ожидания и только если название всё ещё прочитанное: ноль строк —
     * её правили или держат, и она не перезаписывается.
     *
     * @param busy сюда — изменения, которые не записались
     * @return изменения, которые записались
     */
    private static List<Change> write(JdbcTemplate jdbc, List<Change> changes, List<Change> busy) {
        List<Change> written = new ArrayList<>();
        AuditJournal journal = new AuditJournal(jdbc);
        for (int from = 0; from < changes.size(); from += CHUNK) {
            List<Change> chunk = changes.subList(from, Math.min(changes.size(), from + CHUNK));
            int[] counts = jdbc.batchUpdate("""
                    UPDATE part SET title = ?
                     WHERE id = (SELECT id FROM part WHERE id = ? AND title = ?
                                 FOR NO KEY UPDATE SKIP LOCKED)""",
                    chunk.stream().map(c -> new Object[]{c.now(), c.position().id(), c.position().title()})
                            .toList());
            for (int i = 0; i < counts.length; i++) {
                (counts[i] == 1 ? written : busy).add(chunk.get(i));
            }
            Map<Long, AuditJournal.Change> entries = new LinkedHashMap<>();
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] == 1) {
                    Change change = chunk.get(i);
                    entries.put(change.position().id(), new AuditJournal.Change(
                            Map.of("title", change.position().title()), Map.of("title", change.now())));
                }
            }
            journal.changedEach("part", entries);
        }
        return written;
    }

    private static void skip(Tally tally, String schema, Skipped skipped) {
        tally.notes.merge(skipped.note(), 1L, Long::sum);
        tally.skipped.add(schema + " " + name(skipped.position()) + " «" + skipped.position().title() + "»: "
                + skipped.note().words()
                + (skipped.formula() == null ? "" : "; формула дала бы «" + skipped.formula() + "»"));
    }

    /** «№ позиции и код» — по ним позицию находят на витрине; номер строки базы не годится. */
    private static String name(Position position) {
        return "№ позиции " + position.number() + ", код " + position.code();
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static void report(List<TenantSweep.Outcome<Tally>> outcomes, Options options) {
        String mode = options.apply() ? "ПЕРЕСЧЁТ (--apply)" : "ПРОВЕРКА (--dry-run, ничего не пишется)";
        log.info("Пересчёт названий позиций: {}", mode);
        long changed = 0;
        long skipped = 0;
        for (TenantSweep.Outcome<Tally> outcome : outcomes) {
            if (outcome.problem() != null) {
                log.error("{}: не выполнено — {}", outcome.schema(), outcome.problem());
                continue;
            }
            Tally t = outcome.result();
            changed += t.changed;
            skipped += t.noted(Note.NO_KIND) + t.noted(Note.VEHICLE_ONLY_IN_TITLE);
            // Каждое «было → стало» и каждый пропуск — своей строкой лога с меткой:
            // команда для ПРОМ складывает их в файл, и человек просматривает его
            // до --apply (а владелец отвечает «да» на числа и образцы).
            for (String line : t.lines) {
                log.info("title-row: {} {}", outcome.schema(), line);
            }
            for (String line : t.skipped) {
                log.info("title-skip: {}", line);
            }
            for (String sql : t.restore) {
                log.info("restore-sql: {}", sql);
            }
            log.info("{}: позиций {} (колёс, у них название не по формуле, {}); {} {}; уже по формуле {};"
                            + " не тронуто: нет вида {}, машина только в названии {};"
                            + " правлены во время запуска {}; {}",
                    outcome.schema(), t.parts, t.wheels,
                    options.apply() ? "пересчитано" : "будет пересчитано", t.changed, t.unchanged,
                    t.noted(Note.NO_KIND), t.noted(Note.VEHICLE_ONLY_IN_TITLE), t.busy,
                    !options.feedMarks() ? "отметки для площадки не ставятся"
                            : (options.apply() ? "отметок для площадки поставлено " : "отметок для площадки будет поставлено ")
                            + t.feedMarks);
        }
        log.info("Марка и модель в названиях — из справочника, а не как в Bazon"
                + " (задача 0336, п. 1 ответа 22.10.2026 не сделан)");
        log.info("Итого: названий {} {}; не тронуто {} — {}", changed,
                options.apply() ? "пересчитано" : "будет пересчитано", skipped,
                options.feedMarks() ? "отметки для площадки ставятся (--feed-marks)"
                        : "отметки для площадки НЕ ставятся (по умолчанию; хранимое название в файл Дрома"
                        + " не идёт, включить — --feed-marks)");
    }
}
