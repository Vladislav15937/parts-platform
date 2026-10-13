package ru.partsflow.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор блока «Подходит» карточки Дрома в справочную применимость (задача 0289).
 *
 * <p>Текст лежит у позиции дословно ({@code part_drom_card.applicability}, 0287)
 * и этим разбором не меняется: разложение — улучшение поверх текста, а не замена.
 *
 * <p><b>Два написания одного блока.</b> Договор файла (`docs/drom-transfer-file.md`,
 * колонка 17) — строка на пункт, части через « | »:
 * {@code Toyota 4Runner | 5 поколение (2009–2013) | TRN285, GRN285}. А файл,
 * которым живой клиент переехал 13 октября 2026, собран парсером раньше договора
 * и пишет строку на машину: {@code Toyota 4Runner — 5 поколение, рестайлинг
 * (2013–2024): GRN285; 5 поколение (2009–2013): TRN285, GRN285; Другие кузова: …}.
 * У клиента лежит второе, у образца договора — первое; разбираются оба, иначе
 * кнопка на складе клиента не нашла бы ни одного пункта.
 *
 * <p><b>Только точное совпадение со справочником</b>, как у {@link TitleApplicability}:
 * марка — целым словом в начале заголовка блока, модель — остаток заголовка
 * целиком и только среди моделей этой марки. Поколение — по году начала:
 * справочник собран с Дрома, и год начала пункта совпадает с годом начала
 * поколения у 39 429 пунктов из 39 430 разобранных на складе клиента, а год
 * конца расходится постоянно (у Дрома в карточке «2013–2024», в справочнике
 * «2013–2023»). Два поколения с одним годом начала различаются годом конца;
 * не различились — поколение не ставится, а пункт называется.
 *
 * <p><b>Кузова и двигатели.</b> Кузова пункта поколения (то, что после «:») идут
 * в {@code note} его строки: у {@code part_applicability} нет ни кузова, ни
 * двигателя, а {@code modification_id} — ссылка на справочник модификаций,
 * в котором нет ни одной записи. «Другие кузова» к поколению не привязаны,
 * «Для двигателей» — про объявление, а не про машину: обе строки остаются
 * только в тексте.
 */
public final class DromApplicability {

    private DromApplicability() {
    }

    public record Brand(long id, String name) {
    }

    public record Model(long id, long brandId, String name) {
    }

    public record Generation(long id, long modelId, Integer yearFrom, Integer yearTo) {
    }

    /** Почему пункт блока не лёг в справочник целиком. Слова — для отчёта прохода. */
    public enum Reason {
        NO_BRAND("марки нет в справочнике"),
        NO_MODEL("модель не найдена у марки"),
        NO_GENERATION("поколение не найдено"),
        NO_YEARS("годы не разобраны");

        private final String words;

        Reason(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }
    }

    /** Пункт, не легший целиком, и название из блока клиента, по которому его узнать. */
    public record Miss(Reason reason, String name) {
    }

    /**
     * Строка справочной применимости, которую даёт блок.
     *
     * @param yearFrom {@code null} вместе с {@code yearTo} — годов в пункте нет
     * @param yearTo   {@code null} при заданном {@code yearFrom} — «н.в.»
     * @param note     кузова пункта поколения, через «, »; {@code null} — не названы
     */
    public record Target(long brandId, Long modelId, Long generationId,
                         Integer yearFrom, Integer yearTo, String note) {

        boolean hasYears() {
            return yearFrom != null || yearTo != null;
        }

        Key key() {
            return new Key(brandId, modelId, generationId);
        }
    }

    /** Машина без годов: ключ уникальности {@code part_applicability} без модификации. */
    record Key(long brandId, Long modelId, Long generationId) {

        /**
         * Та же машина, названная точнее: та же марка, и каждое поле этой
         * строки либо пусто, либо совпадает, а у другой заполнено хоть одно,
         * пустое здесь.
         */
        boolean coarserThan(Key other) {
            if (brandId != other.brandId) {
                return false;
            }
            if (modelId != null && !modelId.equals(other.modelId)) {
                return false;
            }
            if (generationId != null && !generationId.equals(other.generationId)) {
                return false;
            }
            return (modelId == null && other.modelId != null)
                    || (generationId == null && other.generationId != null);
        }
    }

    public record Parsed(List<Target> targets, List<Miss> misses) {
    }

    /** Справочник машин, подготовленный один раз на проход. */
    public static final class Dictionary {

        private final List<Brand> brands;
        private final Map<Long, Map<String, Model>> modelsByBrand = new HashMap<>();
        private final Map<Long, List<Generation>> generationsByModel = new HashMap<>();

        public Dictionary(List<Brand> brands, List<Model> models, List<Generation> generations) {
            // Длинные названия первыми: «Land Rover» не должен уступить «Land».
            this.brands = brands.stream()
                    .sorted(Comparator.comparingInt((Brand b) -> b.name().length()).reversed())
                    .toList();
            for (Model model : models) {
                modelsByBrand.computeIfAbsent(model.brandId(), k -> new HashMap<>())
                        .putIfAbsent(model.name().toLowerCase(Locale.ROOT), model);
            }
            for (Generation generation : generations) {
                generationsByModel.computeIfAbsent(generation.modelId(), k -> new ArrayList<>())
                        .add(generation);
            }
        }

        Brand brandOf(String heading) {
            String lower = heading.toLowerCase(Locale.ROOT);
            for (Brand brand : brands) {
                String name = brand.name().toLowerCase(Locale.ROOT);
                if (lower.equals(name) || lower.startsWith(name + " ")) {
                    return brand;
                }
            }
            return null;
        }

        Model modelOf(long brandId, String name) {
            return modelsByBrand.getOrDefault(brandId, Map.of()).get(name.toLowerCase(Locale.ROOT));
        }

        Generation generationOf(long modelId, int yearFrom, Integer yearTo) {
            List<Generation> starting = generationsByModel.getOrDefault(modelId, List.of()).stream()
                    .filter(g -> g.yearFrom() != null && g.yearFrom() == yearFrom)
                    .toList();
            if (starting.size() == 1) {
                return starting.get(0);
            }
            List<Generation> exact = starting.stream()
                    .filter(g -> Objects.equals(g.yearTo(), yearTo))
                    .toList();
            return exact.size() == 1 ? exact.get(0) : null;
        }
    }

    /** Знак, который парсер Дрома ставит вокруг тире в годах поколения. */
    private static final String WORD_JOINER = "⁠";

    private static final Pattern YEARS = Pattern.compile(
            "\\((\\d{4})\\s*[–—-]\\s*(\\d{4}|н\\.\\s*в\\.)\\s*\\)");

    private static final String GENERATION_WORD = "поколение";
    private static final String OTHER_BODIES = "Другие кузова";
    private static final String ENGINES = "Для двигател";

    /** Пункт блока: заголовок (марка и модель), текст пункта, кузова. */
    private record Item(String heading, String generation, String bodies) {
    }

    /** Строки применимости, которые даёт текст, и пункты, не легшие целиком. */
    public static Parsed parse(String text, Dictionary dictionary) {
        if (text == null || text.isBlank()) {
            return new Parsed(List.of(), List.of());
        }
        List<Target> found = new ArrayList<>();
        List<Miss> misses = new ArrayList<>();
        for (Item item : items(text)) {
            resolve(item, dictionary, found, misses);
        }
        return new Parsed(reduce(found), List.copyOf(misses));
    }

    private static void resolve(Item item, Dictionary dictionary, List<Target> found,
                                List<Miss> misses) {
        Brand brand = dictionary.brandOf(item.heading());
        if (brand == null) {
            misses.add(new Miss(Reason.NO_BRAND, item.heading()));
            return;
        }
        String rest = item.heading().substring(brand.name().length()).strip();
        if (rest.isEmpty()) {
            // «Toyota — »: клиент назвал одну марку.
            found.add(new Target(brand.id(), null, null, null, null, null));
            return;
        }
        Model model = dictionary.modelOf(brand.id(), rest);
        if (model == null) {
            misses.add(new Miss(Reason.NO_MODEL, item.heading()));
            return;
        }
        String note = item.bodies() == null ? null : "Кузова: " + item.bodies();
        if (item.generation() == null) {
            found.add(new Target(brand.id(), model.id(), null, null, null, note));
            return;
        }
        String named = item.heading() + " — " + item.generation();
        Matcher years = YEARS.matcher(item.generation());
        Integer from = null;
        Integer to = null;
        if (years.find()) {
            from = Integer.parseInt(years.group(1));
            to = years.group(2).startsWith("н") ? null : Integer.valueOf(years.group(2));
        }
        if (from == null || (to != null && to < from)) {
            misses.add(new Miss(Reason.NO_YEARS, named));
            found.add(new Target(brand.id(), model.id(), null, null, null, note));
            return;
        }
        Generation generation = dictionary.generationOf(model.id(), from, to);
        if (generation == null) {
            // Марка, модель и годы клиента верны и ложатся; поколение не угадывается.
            misses.add(new Miss(Reason.NO_GENERATION, named));
            found.add(new Target(brand.id(), model.id(), null, from, to, note));
            return;
        }
        found.add(new Target(brand.id(), model.id(), generation.id(), from, to, note));
    }

    /**
     * Одна строка на машину: пункты с одним ключом сливаются (годы — общим
     * промежутком, кузова — списком), а строка без годов, у которой есть
     * та же машина точнее, не заводится — она ничего не добавляет.
     */
    private static List<Target> reduce(List<Target> found) {
        Map<Key, Target> byKey = new LinkedHashMap<>();
        for (Target target : found) {
            byKey.merge(target.key(), target, DromApplicability::merge);
        }
        List<Target> kept = new ArrayList<>();
        for (Target target : byKey.values()) {
            if (!target.hasYears() && dominated(target.key(), byKey.keySet())) {
                continue;
            }
            kept.add(target);
        }
        return List.copyOf(kept);
    }

    static boolean dominated(Key key, java.util.Collection<Key> others) {
        for (Key other : others) {
            if (key.coarserThan(other)) {
                return true;
            }
        }
        return false;
    }

    private static Target merge(Target a, Target b) {
        Integer from;
        Integer to;
        if (!a.hasYears()) {
            from = b.yearFrom();
            to = b.yearTo();
        } else if (!b.hasYears()) {
            from = a.yearFrom();
            to = a.yearTo();
        } else {
            from = Math.min(a.yearFrom(), b.yearFrom());
            to = a.yearTo() == null || b.yearTo() == null ? null : Math.max(a.yearTo(), b.yearTo());
        }
        Set<String> notes = new LinkedHashSet<>();
        if (a.note() != null) {
            notes.add(a.note());
        }
        if (b.note() != null) {
            notes.add(b.note());
        }
        return new Target(a.brandId(), a.modelId(), a.generationId(), from, to,
                notes.isEmpty() ? null : String.join("; ", notes));
    }

    /** Пункты блока в обоих написаниях; двигатели, «Другие кузова» и пустые строки — мимо. */
    private static List<Item> items(String text) {
        List<Item> items = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.replace(WORD_JOINER, "").replace(' ', ' ').strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.contains("|")) {
                piped(line, items);
            } else {
                dashed(line, items);
            }
        }
        return items;
    }

    /** {@code Toyota 4Runner | 5 поколение (2009–2013) | TRN285, GRN285}. */
    private static void piped(String line, List<Item> items) {
        String[] parts = line.split("\\|", -1);
        String heading = parts[0].strip();
        String item = parts.length > 1 ? parts[1].strip() : "";
        String small = parts.length > 2 ? parts[2].strip() : "";
        if (heading.isEmpty() || heading.startsWith(ENGINES) || item.equals(OTHER_BODIES)) {
            return;
        }
        if (item.contains(GENERATION_WORD)) {
            items.add(new Item(heading, item, blankToNull(small)));
        } else {
            // «Lexus GX400 | GRJ158 | » — поколения нет, в пункте кузов.
            String bodies = item.isEmpty() ? small : small.isEmpty() ? item : item + ", " + small;
            items.add(new Item(heading, null, blankToNull(bodies)));
        }
    }

    /** {@code Toyota 4Runner — 5 поколение (2009–2013): TRN285, GRN285; Другие кузова: …}. */
    private static void dashed(String line, List<Item> items) {
        String heading;
        String rest;
        int dash = line.indexOf(" — ");
        if (dash >= 0) {
            heading = line.substring(0, dash).strip();
            rest = line.substring(dash + 3).strip();
        } else if (line.endsWith("—")) {
            heading = line.substring(0, line.length() - 1).strip();
            rest = "";
        } else {
            heading = line;
            rest = "";
        }
        if (heading.isEmpty() || heading.equals("—") || heading.startsWith(ENGINES)) {
            return;
        }
        if (rest.isEmpty()) {
            items.add(new Item(heading, null, null));
            return;
        }
        for (String piece : rest.split(";")) {
            String item = piece.strip();
            if (item.isEmpty() || item.startsWith(OTHER_BODIES)) {
                continue;
            }
            if (item.contains(GENERATION_WORD)) {
                int colon = item.indexOf(':');
                String generation = colon < 0 ? item : item.substring(0, colon).strip();
                String bodies = colon < 0 ? null : item.substring(colon + 1).strip();
                items.add(new Item(heading, generation, blankToNull(bodies)));
            } else {
                items.add(new Item(heading, null, item));
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
