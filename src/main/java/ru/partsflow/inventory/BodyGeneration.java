package ru.partsflow.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Поколение своей машины по коду кузова, когда блок «Подходит» поколения
 * не называет (задача 0295).
 *
 * <p>У позиции «Капот Toyota Land Cruiser Prado 120» блок — {@code Toyota Land
 * Cruiser Prado — GRJ120, GRJ121W, KDJ120…}: кузова без поколения. А в других
 * карточках того же клиента те же коды стоят уже при поколении:
 * {@code 3 поколение (2002–2009): GRJ121W, KDJ120…}. Эти пары и есть
 * сопоставление «код → поколение» — собранное из текстов «Подходит» всего
 * склада арендатора на проход, а не из общего справочника {@code catalog}:
 * там кузовов у поколений нет, и правка общего справочника из файла одного
 * клиента — решение уровня выше задачи.
 *
 * <p><b>Только при уверенности</b> (решение владельца 14.10.2026,
 * {@code tasks/0294}, «Следствие»). Поколение ставится, если все коды,
 * для которых пара нашлась, ведут в одно поколение Дрома и каждая его строка
 * нашлась в справочнике. Код без пары ничего не даёт — ни «за», ни «против»
 * (ответ владельца 14.10.2026, {@code tasks/0295}: «да»); разные поколения,
 * ни одного найденного кода — пусто, и позиция названа в отчёте причиной.
 *
 * <p><b>Рестайлинги — поколение Дрома целиком</b> (ответ владельца 14.10.2026,
 * {@code tasks/0295}, п. 2; как задача 0297). Строк у поколения в карточках
 * склада несколько («4 поколение (2009–2013)», «4 поколение, рестайлинг
 * (2013–2017)») — позиция получает по строке применимости на каждую, все
 * с меткой «4 поколение»: витрина собирает их в одно поколение тем же правилом,
 * что строки, названные клиентом в самом блоке.
 *
 * <p><b>Номер кузова в наименовании сужает коды до поиска</b> — тем же
 * правилом ряда, что у сверки колонки (0294): «Prado 120» — ряд «12», код
 * GRJ120W его, LJ150 нет. Иначе 120-я деталь, чей блок называет и 120,
 * и 150, не определилась бы — а определившись без сужения, получила бы чужое.
 */
final class BodyGeneration {

    private BodyGeneration() {
    }

    /** Начало примечания с кузовами пункта — его пишет разбор блока (0289). */
    static final String BODIES = "Кузова: ";

    /**
     * Пометка строки, поколение которой выведено нами, а не названо клиентом.
     * Стоит в {@code note} после кузовов отдельной частью — как «; » у слитых
     * примечаний разбора, — и несёт коды, по которым поколение определено.
     */
    static final String MARK = "Поколение по коду кузова: ";

    /** Пара из пункта блока с поколением. {@code generationId} пуст — поколения нет в справочнике. */
    record Pair(long modelId, String code, String label, int yearFrom, Integer yearTo,
                Long generationId) {
    }

    /** Почему поколение по кузову не поставлено. Слова — для отчёта прохода. */
    enum Reason {
        NO_BODIES("блок не называет кузова своей машины"),
        OTHER_SERIES("кузова блока другого ряда, чем номер в наименовании"),
        UNKNOWN_BODY("кузова не встречаются в карточках с поколением"),
        TWO_GENERATIONS("кузова встречаются в разных поколениях"),
        RESTYLING("у поколения несколько строк, а номера поколения Дрома в тексте нет — "
                + "собрать их в одно не по чему"),
        NOT_IN_CATALOG("поколение не найдено в справочнике");

        private final String words;

        Reason(String words) {
            this.words = words;
        }

        String words() {
            return words;
        }
    }

    /** Строка справочника поколения с годами клиента — одна строка применимости. */
    record Span(long generationId, int yearFrom, Integer yearTo) {
    }

    /**
     * Итог для одной позиции: либо строки поколения (одна, или по строке
     * на рестайлинг), метка поколения Дрома («3 поколение», {@code null} — номера
     * в тексте нет) и коды, по которым оно определено, — либо причина
     * и пояснение для отчёта.
     */
    record Outcome(List<Span> spans, String label, List<String> decisive,
                   Reason reason, String detail) {

        static Outcome skip(Reason reason, String detail) {
            return new Outcome(List.of(), null, List.of(), reason, detail);
        }

        boolean found() {
            return reason == null;
        }
    }

    private record CodeKey(long modelId, String code) {
    }

    private record GroupKey(long modelId, String number) {
    }

    /** Строка поколения, как её пишет клиент: «3 поколение» и годы. */
    private record Line(String label, int yearFrom, Integer yearTo, Long generationId) {

        String number() {
            return dromNumber(label);
        }

        String words() {
            return label + " (" + yearFrom + "–" + (yearTo == null ? "н.в." : yearTo) + ")";
        }
    }

    private static final Pattern DROM_NUMBER = Pattern.compile("^(\\d+)\\s+поколение");

    /** «4 поколение, рестайлинг» → «4»: рестайлинги одного поколения Дрома — одно. */
    static String dromNumber(String label) {
        Matcher m = DROM_NUMBER.matcher(label);
        return m.find() ? m.group(1) : label;
    }

    /** Сопоставление «код → поколение», собранное с текстов склада на проход. */
    static final class Index {

        private final Map<CodeKey, Set<Line>> byCode = new HashMap<>();
        private final Map<GroupKey, Set<Line>> byNumber = new HashMap<>();

        void add(Pair pair) {
            Line line = new Line(pair.label(), pair.yearFrom(), pair.yearTo(), pair.generationId());
            byCode.computeIfAbsent(new CodeKey(pair.modelId(), pair.code()), k -> new LinkedHashSet<>())
                    .add(line);
            byNumber.computeIfAbsent(new GroupKey(pair.modelId(), line.number()),
                    k -> new LinkedHashSet<>()).add(line);
        }

        int size() {
            return byCode.size();
        }

        Outcome decide(long modelId, List<String> codes) {
            List<String> decisive = new ArrayList<>();
            Set<Line> lines = new LinkedHashSet<>();
            Map<String, Set<String>> numbersOf = new LinkedHashMap<>();
            for (String code : codes) {
                Set<Line> found = byCode.get(new CodeKey(modelId, code));
                if (found == null) {
                    continue;
                }
                decisive.add(code);
                lines.addAll(found);
                found.forEach(l -> numbersOf.computeIfAbsent(code, k -> new TreeSet<>())
                        .add(l.number()));
            }
            if (decisive.isEmpty()) {
                return Outcome.skip(Reason.UNKNOWN_BODY, listed(codes));
            }
            Set<String> numbers = new TreeSet<>();
            lines.forEach(l -> numbers.add(l.number()));
            if (numbers.size() > 1) {
                // Называем код, который сам в двух поколениях, если такой есть, —
                // по нему человек и проверит; иначе — какие поколения столкнулись.
                for (Map.Entry<String, Set<String>> e : numbersOf.entrySet()) {
                    if (e.getValue().size() > 1) {
                        return Outcome.skip(Reason.TWO_GENERATIONS,
                                "кузов " + e.getKey() + " есть в поколениях " + numbered(e.getValue()));
                    }
                }
                return Outcome.skip(Reason.TWO_GENERATIONS, "поколения " + numbered(numbers));
            }
            Set<Line> group = byNumber.get(new GroupKey(modelId, numbers.iterator().next()));
            // Метка — словами того же разбора, что пишет её строкам блока (0297):
            // витрина склеивает рестайлинги по равенству меток.
            String label = DromApplicability.dromGeneration(group.iterator().next().label());
            if (group.size() > 1 && label == null) {
                return Outcome.skip(Reason.RESTYLING, words(group));
            }
            for (Line line : group) {
                if (line.generationId() == null) {
                    return Outcome.skip(Reason.NOT_IN_CATALOG, line.words());
                }
            }
            return new Outcome(spans(group), label, List.copyOf(decisive), null, null);
        }

        /**
         * Строки поколения — по одной на строку справочника. Два написания
         * одной строки («2009–2013» и «2009–2012» у разных карточек, или
         * рестайлинг, легший годом начала в ту же строку справочника) дают одну:
         * уникальный ключ применимости — поколение справочника, и годы
         * берутся от раннего начала до позднего конца («н.в.» — шире всего).
         */
        private static List<Span> spans(Set<Line> group) {
            Map<Long, Span> byGeneration = new LinkedHashMap<>();
            group.stream().sorted(java.util.Comparator.comparingInt(Line::yearFrom)).forEach(line ->
                    byGeneration.merge(line.generationId(),
                            new Span(line.generationId(), line.yearFrom(), line.yearTo()),
                            (a, b) -> new Span(a.generationId(), Math.min(a.yearFrom(), b.yearFrom()),
                                    a.yearTo() == null || b.yearTo() == null ? null
                                            : Integer.valueOf(Math.max(a.yearTo(), b.yearTo())))));
            return List.copyOf(byGeneration.values());
        }
    }

    private static String words(Set<Line> group) {
        return group.stream().map(Line::words).reduce((a, b) -> a + "; " + b).orElseThrow();
    }

    private static String numbered(Set<String> numbers) {
        return String.join(" и ", numbers);
    }

    /** Первые три кода и сколько ещё — в отчёте не нужен список из тридцати. */
    static String listed(List<String> codes) {
        String head = String.join(", ", codes.subList(0, Math.min(3, codes.size())));
        return codes.size() > 3 ? head + " и ещё " + (codes.size() - 3) : head;
    }

    /**
     * Номер кузова сразу после модели в наименовании — то же правило, что
     * у сверки колонки ({@code CatalogService}, шаг 2, задача 0294): отдельный
     * номер из двух-трёх цифр после пробела, не вплотную к модели («RX350»)
     * и не через косую («120/150» — два ряда). Нет — {@code null}.
     */
    private static final Pattern TITLE_BODY =
            Pattern.compile("^ +([0-9]{2,3})(?:[^\\p{L}\\p{N}/]|$)");

    static String titleBody(String title, String model) {
        if (title == null || model == null) {
            return null;
        }
        int at = title.toLowerCase(Locale.ROOT).indexOf(model.toLowerCase(Locale.ROOT));
        if (at < 0) {
            return null;
        }
        Matcher m = TITLE_BODY.matcher(title.substring(at + model.length()));
        return m.find() ? m.group(1) : null;
    }

    private static final Pattern CODE_DIGITS = Pattern.compile("^[^0-9]*([0-9]+)");

    /**
     * Код того же ряда, что номер в наименовании: ряд — номер без последней
     * цифры (120 → «12», 95 → «9»), у кода после букв стоит ряд и ровно одна
     * цифра. Двузначный номер — сам себе номер, а не сокращение трёхзначного:
     * «Prado 12» ряд «1», а не 120-й (решение владельца, {@code tasks/0294},
     * «Prado 12» — «не знаю», значит не ставить).
     */
    static boolean sameSeries(String code, String titleBody) {
        Matcher m = CODE_DIGITS.matcher(code);
        if (!m.find()) {
            return false;
        }
        String digits = m.group(1);
        String series = titleBody.substring(0, titleBody.length() - 1);
        return digits.length() == series.length() + 1 && digits.startsWith(series);
    }

    /** Кузова из примечания строки: «Кузова: A, B; Кузова: C» → A, B, C. */
    static List<String> codesOf(String note) {
        Set<String> codes = new LinkedHashSet<>();
        if (note == null) {
            return List.of();
        }
        for (String part : note.split("; ")) {
            if (!part.startsWith(BODIES)) {
                continue;
            }
            for (String code : part.substring(BODIES.length()).split(",")) {
                String body = code.strip().toUpperCase(Locale.ROOT);
                if (!body.isEmpty()) {
                    codes.add(body);
                }
            }
        }
        return List.copyOf(codes);
    }

    /** Примечание выведенной строки: кузова своего ряда и пометка с решившими кодами. */
    static String note(List<String> codes, List<String> decisive) {
        return BODIES + String.join(", ", codes) + "; " + MARK + String.join(", ", decisive);
    }

    /** Своя машина позиции — марка и модель, названные в наименовании. */
    record Own(long brandId, long modelId, String model) {
    }

    /** Строка применимости, как её видит выбор своей машины. */
    record Fit(long brandId, Long modelId) {
    }

    /**
     * Та же своя машина, что у колонок склада ({@code CatalogService.OWN_VEHICLE_OF},
     * шаг 1, задача 0290): марка, названная в наименовании раньше других; при ней
     * модель, тоже названная там (раньше, а при одном месте — длиннейшая).
     * Марка названа, модели нет — своей модели нет, и поколение ставить не к чему.
     */
    static Own own(String title, List<Fit> fits, DromApplicability.Dictionary dictionary) {
        if (title == null) {
            return null;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        Own best = null;
        int[] bestRank = null;
        for (Fit fit : fits) {
            String brand = dictionary.brandName(fit.brandId());
            if (brand == null) {
                continue;
            }
            int brandAt = lower.indexOf(brand.toLowerCase(Locale.ROOT));
            if (brandAt < 0) {
                continue;
            }
            String model = fit.modelId() == null ? null : dictionary.modelName(fit.modelId());
            int modelAt = model == null ? -1 : lower.indexOf(model.toLowerCase(Locale.ROOT));
            boolean named = modelAt >= 0;
            int[] rank = {brandAt, named ? 0 : 1, named ? modelAt : Integer.MAX_VALUE,
                    named ? -model.length() : 0};
            if (bestRank == null || compare(rank, bestRank) < 0) {
                bestRank = rank;
                best = named ? new Own(fit.brandId(), fit.modelId(), model) : null;
            }
        }
        return best;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    static boolean same(Own own, long brandId, Long modelId) {
        return own.brandId() == brandId && Objects.equals(own.modelId(), modelId);
    }
}
