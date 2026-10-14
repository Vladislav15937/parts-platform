package ru.partsflow.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.DromApplicability.Key;
import ru.partsflow.inventory.DromApplicability.Target;
import ru.partsflow.platform.tenant.TenantContext;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Раскладывает блок «Подходит» карточки Дрома в справочную применимость
 * позиции (задача 0289): кнопкой по всему складу и сама — при загрузке файла.
 *
 * <p><b>Машиной, не человеком.</b> Все строки — {@code is_verified = false},
 * как у разбора наименований. Подтверждённое человеком в карточке проход
 * не трогает никак: не дополняет, не заменяет, не снимает.
 *
 * <p><b>Уточнение, а не дубль.</b> Строка машины без годов уступает более
 * точной строке той же машины у той же позиции. Ключ тот же («Toyota / Land
 * Cruiser Prado» из наименования и та же пара из блока без поколения) —
 * строка дополняется годами и кузовами, номер у неё прежний. Блок даёт
 * поколение — строка без годов снимается, остаются строки поколений.
 * Строка с годами не снимается никогда: годы — то, чего у точной строки
 * может не быть.
 *
 * <p><b>Текст не меняется.</b> {@code part_drom_card.applicability} проход
 * только читает: первично хранится дословный текст (0287).
 *
 * <p><b>Отметку «позиция изменилась» не ставит.</b> Массовая операция, как
 * перенос и разбор наименований: тысячи позиций разом означали бы пачки
 * дельт сразу после переезда. Марки и модели применимости уходят в прайс
 * Дрома тегами {@code brandcars} и {@code model} — их площадка узнает
 * полным прайсом, который собирается на каждый забор.
 *
 * <p><b>Порциями.</b> Каждая пачка — своя транзакция: обрыв посреди прохода
 * оставляет разобранные позиции разобранными, а позицию — целиком до или
 * целиком после. Повторный запуск идёт заново и добавляет только недостающее.
 * Пачки одного арендатора идут под рекомендательной блокировкой: два
 * одновременных нажатия разбирают склад по очереди, и второе видит
 * сделанное первым.
 */
@Service
public class DromApplicabilityService {

    private static final Logger log = LoggerFactory.getLogger(DromApplicabilityService.class);

    /** Позиций в пачке: транзакция короткая, а обращений к базе на пачку — единицы. */
    static final int BATCH = 200;

    /** Сколько названий на причину отдаётся экрану; число — полное. */
    static final int NAMES_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DromApplicabilityService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Итог прохода.
     *
     * @param parts   позиций с блоком «Подходит», которые прошёл разбор
     * @param added   строк применимости заведено
     * @param refined строк дополнено годами и кузовами (та же машина, что была)
     * @param removed строк без годов снято, потому что та же машина легла точнее
     * @param misses  пункты, не легшие целиком, — по причинам
     * @param byBody  позиций, получивших поколение своей машины по коду кузова (0295)
     * @param bodyMisses позиции, у которых блок поколения не называет и по кузову
     *                   оно не определилось, — по причинам; названия — наименование
     *                   позиции и пояснение
     */
    public record Report(int parts, int added, int refined, int removed, List<Miss> misses,
                         int byBody, List<Miss> bodyMisses) {
    }

    /**
     * @param items  пунктов с этой причиной
     * @param names  уникальные названия из блока клиента, не больше {@link #NAMES_LIMIT}
     * @param unique сколько уникальных названий всего
     */
    public record Miss(String reason, int items, List<String> names, int unique) {
    }

    /** Накопитель итога: меняется по ходу, наружу уходит {@link Report}. */
    public static final class Tally {

        private int parts;
        private int added;
        private int refined;
        private int removed;
        private final Map<DromApplicability.Reason, Integer> items =
                new EnumMap<>(DromApplicability.Reason.class);
        private final Map<DromApplicability.Reason, Set<String>> names =
                new EnumMap<>(DromApplicability.Reason.class);
        private int byBody;
        private final Map<BodyGeneration.Reason, Integer> bodyItems =
                new EnumMap<>(BodyGeneration.Reason.class);
        private final Map<BodyGeneration.Reason, Set<String>> bodyNames =
                new EnumMap<>(BodyGeneration.Reason.class);
        /** Позиции, которым загрузка разложила текст: по ним идёт проход по кузову (0295). */
        private final Set<Long> touched = new LinkedHashSet<>();

        void miss(DromApplicability.Miss miss) {
            items.merge(miss.reason(), 1, Integer::sum);
            names.computeIfAbsent(miss.reason(), r -> new LinkedHashSet<>()).add(miss.name());
        }

        void bodyMiss(BodyGeneration.Reason reason, String name) {
            bodyItems.merge(reason, 1, Integer::sum);
            bodyNames.computeIfAbsent(reason, r -> new LinkedHashSet<>()).add(name);
        }

        public Report toReport() {
            List<Miss> misses = new ArrayList<>();
            for (DromApplicability.Reason reason : DromApplicability.Reason.values()) {
                Integer count = items.get(reason);
                if (count == null) {
                    continue;
                }
                Set<String> all = names.get(reason);
                misses.add(new Miss(reason.words(), count,
                        all.stream().limit(NAMES_LIMIT).toList(), all.size()));
            }
            List<Miss> bodyMisses = new ArrayList<>();
            for (BodyGeneration.Reason reason : BodyGeneration.Reason.values()) {
                Integer count = bodyItems.get(reason);
                if (count == null) {
                    continue;
                }
                Set<String> all = bodyNames.get(reason);
                bodyMisses.add(new Miss(reason.words(), count,
                        all.stream().limit(NAMES_LIMIT).toList(), all.size()));
            }
            return new Report(parts, added, refined, removed, List.copyOf(misses),
                    byBody, List.copyOf(bodyMisses));
        }
    }

    /**
     * Справочник машин на проход — общая схема {@code catalog}, читается один раз.
     *
     * <p>Без {@code @Transactional} намеренно: из {@link #applyAll} он зовётся
     * самовызовом, мимо прокси, и аннотация там не действовала бы — а читать
     * её как «здесь транзакция» значило бы ошибиться. Транзакция и не нужна:
     * все три запроса идут в {@code catalog.*} с явной схемой, а не в схему
     * арендатора, и {@code search_path} им безразличен. У загрузки файла он
     * зовётся внутри её транзакции.
     */
    public DromApplicability.Dictionary dictionary() {
        return new DromApplicability.Dictionary(
                jdbc.query("SELECT id, name FROM catalog.brand",
                        (rs, i) -> new DromApplicability.Brand(rs.getLong(1), rs.getString(2))),
                jdbc.query("SELECT id, brand_id, name FROM catalog.model",
                        (rs, i) -> new DromApplicability.Model(
                                rs.getLong(1), rs.getLong(2), rs.getString(3))),
                jdbc.query("SELECT id, model_id, year_from, year_to FROM catalog.generation",
                        (rs, i) -> new DromApplicability.Generation(rs.getLong(1), rs.getLong(2),
                                (Integer) rs.getObject(3), (Integer) rs.getObject(4))));
    }

    /**
     * Весь склад: каждая позиция с блоком «Подходит», пачками по {@link #BATCH}.
     *
     * <p>Без {@code @Transactional} намеренно — транзакция у каждой пачки своя
     * ({@link TransactionTemplate}), иначе склад в пятьдесят тысяч позиций шёл бы
     * одной транзакцией, а обрыв откатывал бы всё сделанное.
     */
    public Report applyAll() {
        TenantContext.require();
        long started = System.nanoTime();
        DromApplicability.Dictionary dictionary = dictionary();
        Tally tally = new Tally();
        long after = 0;
        while (true) {
            long cursor = after;
            Long last = tx.execute(status -> applyBatch(cursor, dictionary, tally));
            if (last == null) {
                break;
            }
            after = last;
        }
        // Второй проход — поколение по коду кузова (0295): сопоставление
        // собирается со всего склада, поэтому после первого, а не внутри него.
        long byBodyStarted = System.nanoTime();
        BodyGeneration.Index index = tx.execute(status -> bodyIndex(dictionary));
        after = 0;
        while (true) {
            long cursor = after;
            Long last = tx.execute(status -> byBodyBatch(cursor, dictionary, index, tally));
            if (last == null) {
                break;
            }
            after = last;
        }
        Report report = tally.toReport();
        log.info("Применимость из карточек Дрома: позиций {}, заведено {}, дополнено {}, снято {}, "
                        + "не легло пунктов {}, поколение по коду кузова {} — за {} мс (по кузову {} мс)",
                report.parts(), report.added(), report.refined(), report.removed(),
                report.misses().stream().mapToInt(Miss::items).sum(), report.byBody(),
                (System.nanoTime() - started) / 1_000_000,
                (System.nanoTime() - byBodyStarted) / 1_000_000);
        return report;
    }

    /**
     * Поколение по коду кузова у позиций, которым загрузка разложила текст, —
     * после всех строк файла, в транзакции загрузки (задача 0295). Не по ходу
     * строк: пары «код → поколение» новой позиции дают карточки, стоящие в файле
     * ниже неё, а на складе их ещё нет.
     */
    @Transactional
    public void applyByBodyTo(DromApplicability.Dictionary dictionary, Tally tally) {
        if (tally.touched.isEmpty()) {
            return;
        }
        lock();
        BodyGeneration.Index index = bodyIndex(dictionary);
        List<Long> touched = new ArrayList<>(tally.touched);
        for (int from = 0; from < touched.size(); from += BATCH) {
            List<Long> chunk = touched.subList(from, Math.min(touched.size(), from + BATCH));
            Map<Long, PartText> parts = new java.util.LinkedHashMap<>();
            jdbc.query("""
                    SELECT c.part_id, p.title, c.applicability
                      FROM part_drom_card c JOIN part p ON p.id = c.part_id
                     WHERE c.part_id = ANY (?) AND c.applicability IS NOT NULL
                     ORDER BY c.part_id""", rs -> {
                parts.put(rs.getLong(1), new PartText(rs.getString(2), rs.getString(3)));
            }, (Object) chunk.toArray(Long[]::new));
            byBody(parts, dictionary, index, tally);
        }
    }

    /**
     * Сопоставление «код кузова → поколение» по текстам «Подходит» всего склада
     * арендатора. Держится в памяти на проход: таблицы под него нет намеренно
     * (схема не меняется, а пары — производное от текста, который и так лежит).
     */
    private BodyGeneration.Index bodyIndex(DromApplicability.Dictionary dictionary) {
        BodyGeneration.Index index = new BodyGeneration.Index();
        // Порядок — чтобы отчёт называл строки поколения одинаково от прохода к проходу.
        jdbc.query("SELECT applicability FROM part_drom_card WHERE applicability IS NOT NULL"
                        + " ORDER BY part_id",
                rs -> {
                    DromApplicability.bodyPairs(rs.getString(1), dictionary).forEach(index::add);
                });
        return index;
    }

    private Long byBodyBatch(long after, DromApplicability.Dictionary dictionary,
                             BodyGeneration.Index index, Tally tally) {
        lock();
        Map<Long, PartText> parts = new java.util.LinkedHashMap<>();
        jdbc.query("""
                SELECT c.part_id, p.title, c.applicability
                  FROM part_drom_card c JOIN part p ON p.id = c.part_id
                 WHERE c.applicability IS NOT NULL AND c.part_id > ?
                 ORDER BY c.part_id
                 LIMIT ?""", rs -> {
            parts.put(rs.getLong(1), new PartText(rs.getString(2), rs.getString(3)));
        }, after, BATCH);
        if (parts.isEmpty()) {
            return null;
        }
        byBody(parts, dictionary, index, tally);
        return parts.keySet().stream().reduce((a, b) -> b).orElseThrow();
    }

    /**
     * Своя машина без поколения и с кузовами — поколение по сопоставлению
     * (задача 0295). Ставится строкой поколения с годами клиента и пометкой
     * в {@code note}; грубая строка своей машины уступает ей по правилу
     * уточнения 0289 (подтверждённая человеком — нет). У своей машины уже
     * есть поколение или годы — блок их назвал, или это сделал прошлый проход:
     * ничего не делается, и повтор поэтому — «ничего нового».
     */
    private record PartText(String title, String applicability) {
    }

    private void byBody(Map<Long, PartText> parts, DromApplicability.Dictionary dictionary,
                        BodyGeneration.Index index, Tally tally) {
        Map<Long, List<Row>> existing = existing(parts.keySet());
        for (Map.Entry<Long, PartText> entry : parts.entrySet()) {
            long partId = entry.getKey();
            String title = entry.getValue().title();
            List<Row> rows = existing.getOrDefault(partId, List.of());
            BodyGeneration.Own own = BodyGeneration.own(title, rows.stream()
                    .map(r -> new BodyGeneration.Fit(r.key().brandId(), r.key().modelId()))
                    .toList(), dictionary);
            if (own == null) {
                continue;
            }
            List<Row> ownRows = rows.stream()
                    .filter(r -> BodyGeneration.same(own, r.key().brandId(), r.key().modelId()))
                    .toList();
            if (ownRows.stream().anyMatch(r -> r.key().generationId() != null || r.hasYears())) {
                continue;
            }
            // Кузова — из текста блока, а не из примечаний строк: подтверждённая
            // человеком строка того же ключа не дополняется кузовами (0289),
            // а блок их всё равно называет.
            List<Target> ownTargets = DromApplicability.parse(entry.getValue().applicability(),
                            dictionary).targets().stream()
                    .filter(t -> BodyGeneration.same(own, t.brandId(), t.modelId()))
                    .toList();
            if (ownTargets.stream().anyMatch(t -> t.generationId() != null || t.hasYears())) {
                continue;
            }
            List<String> codes = ownTargets.stream()
                    .flatMap(t -> BodyGeneration.codesOf(t.note()).stream())
                    .distinct().toList();
            if (codes.isEmpty()) {
                tally.bodyMiss(BodyGeneration.Reason.NO_BODIES, title);
                continue;
            }
            String titleBody = BodyGeneration.titleBody(title, own.model());
            if (titleBody != null) {
                codes = codes.stream().filter(c -> BodyGeneration.sameSeries(c, titleBody)).toList();
                if (codes.isEmpty()) {
                    tally.bodyMiss(BodyGeneration.Reason.OTHER_SERIES, title);
                    continue;
                }
            }
            BodyGeneration.Outcome outcome = index.decide(own.modelId(), codes);
            if (!outcome.found()) {
                tally.bodyMiss(outcome.reason(), title + ": " + outcome.detail());
                continue;
            }
            int inserted = jdbc.update("""
                    INSERT INTO part_applicability (part_id, brand_id, model_id, generation_id,
                                                    year_from, year_to, note, is_verified)
                    VALUES (?, ?, ?, ?, ?, ?, ?, false)
                    ON CONFLICT (part_id, brand_id, model_id, generation_id, modification_id)
                    DO NOTHING""",
                    partId, own.brandId(), own.modelId(), outcome.generationId(),
                    outcome.yearFrom(), outcome.yearTo(),
                    BodyGeneration.note(codes, outcome.decisive()));
            tally.byBody += inserted;
            for (Row row : ownRows) {
                if (row.verified()) {
                    continue;
                }
                tally.removed += jdbc.update("""
                        DELETE FROM part_applicability
                         WHERE id = ? AND NOT is_verified AND generation_id IS NULL
                           AND year_from IS NULL AND year_to IS NULL""", row.id());
            }
        }
    }

    /** Одна пачка после позиции {@code after}; последняя разобранная или {@code null}, если пусто. */
    private Long applyBatch(long after, DromApplicability.Dictionary dictionary, Tally tally) {
        lock();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT part_id, applicability FROM part_drom_card
                 WHERE applicability IS NOT NULL AND part_id > ?
                 ORDER BY part_id
                 LIMIT ?""", after, BATCH);
        if (rows.isEmpty()) {
            return null;
        }
        Map<Long, String> texts = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            texts.put(((Number) row.get("part_id")).longValue(), (String) row.get("applicability"));
        }
        apply(texts, dictionary, tally);
        return texts.keySet().stream().reduce((a, b) -> b).orElseThrow();
    }

    /**
     * Одна позиция — из загрузки файла, в её транзакции: загрузка откатилась —
     * откатилось и разложение.
     */
    @Transactional
    public void applyTo(long partId, String text, DromApplicability.Dictionary dictionary,
                        Tally tally) {
        lock();
        apply(Map.of(partId, text), dictionary, tally);
    }

    /**
     * Два прохода по одному складу — по очереди: разбор читает строки позиции
     * и решает, что дополнить и что снять, а между чтением и записью второй
     * проход завёл бы то же самое или снял бы уже дополненное. Блокировка
     * транзакционная — сессионная протекла бы через PgBouncer, как
     * {@code SET search_path}.
     */
    private void lock() {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null,
                TenantContext.require() + "/drom-applicability");
    }

    private record Row(long id, Key key, Integer yearFrom, Integer yearTo, String note,
                       String generationLabel, boolean verified) {

        boolean hasYears() {
            return yearFrom != null || yearTo != null;
        }
    }

    private void apply(Map<Long, String> texts, DromApplicability.Dictionary dictionary,
                       Tally tally) {
        Map<Long, List<Row>> existing = existing(texts.keySet());
        for (Map.Entry<Long, String> entry : texts.entrySet()) {
            long partId = entry.getKey();
            DromApplicability.Parsed parsed = DromApplicability.parse(entry.getValue(), dictionary);
            parsed.misses().forEach(tally::miss);
            tally.parts++;
            tally.touched.add(partId);
            applyPart(partId, parsed.targets(), existing.getOrDefault(partId, List.of()), tally);
        }
    }

    private Map<Long, List<Row>> existing(Set<Long> parts) {
        Map<Long, List<Row>> byPart = new HashMap<>();
        jdbc.query("""
                SELECT id, part_id, brand_id, model_id, generation_id, modification_id,
                       year_from, year_to, note, generation_label, is_verified
                  FROM part_applicability
                 WHERE part_id = ANY (?)""", rs -> {
            Number modification = (Number) rs.getObject("modification_id");
            // Строка с модификацией — точнее всего, что даёт блок: её не трогаем
            // и ею не меряем (модификаций разбор не ставит).
            if (modification != null) {
                return;
            }
            Number model = (Number) rs.getObject("model_id");
            Number generation = (Number) rs.getObject("generation_id");
            Number from = (Number) rs.getObject("year_from");
            Number to = (Number) rs.getObject("year_to");
            byPart.computeIfAbsent(rs.getLong("part_id"), k -> new ArrayList<>()).add(new Row(
                    rs.getLong("id"),
                    new Key(rs.getLong("brand_id"),
                            model == null ? null : model.longValue(),
                            generation == null ? null : generation.longValue()),
                    from == null ? null : from.intValue(), to == null ? null : to.intValue(),
                    rs.getString("note"), rs.getString("generation_label"),
                    rs.getBoolean("is_verified")));
        }, (Object) parts.toArray(Long[]::new));
        return byPart;
    }

    private void applyPart(long partId, List<Target> targets, List<Row> rows, Tally tally) {
        Map<Key, Row> byKey = new HashMap<>();
        rows.forEach(r -> byKey.put(r.key(), r));
        Set<Key> present = new LinkedHashSet<>(byKey.keySet());

        for (Target target : targets) {
            Key key = target.key();
            Row row = byKey.get(key);
            if (row == null) {
                // Блок говорит грубее того, что уже есть, — добавлять нечего.
                if (!target.hasYears() && DromApplicability.dominated(key, present)) {
                    continue;
                }
                tally.added += jdbc.update("""
                        INSERT INTO part_applicability (part_id, brand_id, model_id, generation_id,
                                                        year_from, year_to, note, generation_label,
                                                        is_verified)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, false)
                        ON CONFLICT (part_id, brand_id, model_id, generation_id, modification_id)
                        DO NOTHING""",
                        partId, target.brandId(), target.modelId(), target.generationId(),
                        target.yearFrom(), target.yearTo(), target.note(), target.generationLabel());
                present.add(key);
                continue;
            }
            if (row.verified() || !target.hasYears() && target.note() == null) {
                continue;
            }
            // Метка поколения Дрома (0297) — тоже уточнение: строки, разобранные
            // до неё, получают её повторным проходом, годы и кузова при этом прежние.
            if (Objects.equals(row.yearFrom(), target.yearFrom())
                    && Objects.equals(row.yearTo(), target.yearTo())
                    && Objects.equals(row.note(), target.note())
                    && Objects.equals(row.generationLabel(), target.generationLabel())) {
                continue;
            }
            // Условие в WHERE, а не только проверка выше: человек мог подтвердить
            // строку в карточке между нашим чтением и записью.
            tally.refined += jdbc.update("""
                    UPDATE part_applicability
                       SET year_from = ?, year_to = ?, note = ?, generation_label = ?
                     WHERE id = ? AND NOT is_verified""",
                    target.yearFrom(), target.yearTo(), target.note(), target.generationLabel(),
                    row.id());
        }

        for (Row row : rows) {
            if (row.verified() || row.hasYears() || !DromApplicability.dominated(row.key(), present)) {
                continue;
            }
            tally.removed += jdbc.update("""
                    DELETE FROM part_applicability
                     WHERE id = ? AND NOT is_verified AND year_from IS NULL AND year_to IS NULL""",
                    row.id());
        }
    }
}
