package ru.partsflow.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Разбор машин из заголовка позиции.
 *
 * <p><b>Зачем.</b> У переехавшего клиента четверть склада — 9 417 позиций
 * из 35 841 — без донора: это детали, подходящие к нескольким машинам сразу,
 * и машины перечислены прямо в наименовании, потому что записать их было
 * больше некуда. Подбор по машине их не находит вовсе: марки у них нет,
 * она только в тексте.
 *
 * <p>Заголовок устроен так:
 * {@code <наименование> <Марка[,Марка…]> <Модель[,Модель…]> <кузова…> <двигатели…>}.
 * Разбираются марки и модели; кузова и двигатели — нет, они идут без разделителя
 * и путаются с номерами.
 *
 * <p><b>Только точное совпадение со справочником.</b> Похожесть сюда не идёт
 * по той же причине, что и в справочник наименований: правдоподобная пара
 * уводит деталь к чужой машине, а замечают это, когда покупателю прислали
 * не то. Модель ищется только среди моделей найденных марок — иначе «A3»
 * совпадёт и с Audi, и с «Амберавто».
 */
public final class TitleApplicability {

    private TitleApplicability() {
    }

    /** Марка и модель из справочника. */
    public record Vehicle(long brandId, long modelId) {
    }

    public record Brand(long id, String name) {
    }

    public record Model(long id, long brandId, String name) {
    }

    /**
     * Справочник для разбора: марки и модели, подготовленные один раз на прогон.
     *
     * <p>Имена сортируются по длине убыванно: иначе «Land Cruiser» съест
     * «Land Cruiser Prado», и деталь уедет к другой машине.
     */
    public static final class Dictionary {

        private final List<Brand> brands;
        private final Map<Long, List<Model>> modelsByBrand;

        public Dictionary(List<Brand> brands, List<Model> models) {
            this.brands = brands.stream()
                    .sorted(Comparator.comparingInt((Brand b) -> b.name().length()).reversed())
                    .toList();
            this.modelsByBrand = new java.util.HashMap<>();
            for (Model model : models) {
                modelsByBrand.computeIfAbsent(model.brandId(), k -> new ArrayList<>()).add(model);
            }
            modelsByBrand.replaceAll((brand, list) -> list.stream()
                    .sorted(Comparator.comparingInt((Model m) -> m.name().length()).reversed())
                    .toList());
        }

        List<Brand> brands() {
            return brands;
        }

        List<Model> modelsOf(long brandId) {
            return modelsByBrand.getOrDefault(brandId, List.of());
        }
    }

    /**
     * Машины, названные в заголовке. Пустой список — разобрать не удалось,
     * и это нормальный исход: «Теплообменник (б/у) CCLNS004» машин не называет.
     */
    public static List<Vehicle> parse(String title, Dictionary dictionary) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        String lower = title.toLowerCase(Locale.ROOT);

        Found first = firstBrand(lower, dictionary);
        if (first == null) {
            return List.of();
        }

        // Марок может быть несколько через запятую: «Audi,Volkswagen A3,Golf V».
        List<Brand> brands = new ArrayList<>();
        brands.add(first.brand());
        int at = first.end();
        while (true) {
            int next = skipSeparator(lower, at);
            if (next < 0) {
                break;
            }
            Found more = brandAt(lower, next, dictionary);
            if (more == null) {
                break;
            }
            brands.add(more.brand());
            at = more.end();
        }

        return models(title.substring(at), brands, dictionary);
    }

    private static List<Vehicle> models(String tail, List<Brand> brands, Dictionary dictionary) {
        List<Vehicle> found = new ArrayList<>();
        for (String piece : tail.split(",")) {
            String token = piece.strip();
            if (token.isEmpty()) {
                continue;
            }
            Model model = modelAt(token, brands, dictionary);
            if (model == null) {
                // Первый неразобранный кусок — это уже кузова или двигатели:
                // дальше идти нечего. «Golf V, Jetta, 8P1» кончается на 8P1.
                break;
            }
            found.add(new Vehicle(model.brandId(), model.id()));
        }
        return List.copyOf(found);
    }

    /**
     * Модель по началу куска: в заголовке пишут «Golf V» и «Passat Variant»,
     * а в справочнике модель называется «Golf» и «Passat» — поколение и кузов
     * там отдельными полями.
     */
    private static Model modelAt(String token, List<Brand> brands, Dictionary dictionary) {
        String lower = token.toLowerCase(Locale.ROOT);
        for (Brand brand : brands) {
            for (Model model : dictionary.modelsOf(brand.id())) {
                String name = model.name().toLowerCase(Locale.ROOT);
                if (lower.equals(name)
                        || (lower.startsWith(name) && !isNameChar(lower.charAt(name.length())))) {
                    return model;
                }
            }
        }
        return null;
    }

    private static Found firstBrand(String lower, Dictionary dictionary) {
        Found best = null;
        for (Brand brand : dictionary.brands()) {
            String name = brand.name().toLowerCase(Locale.ROOT);
            int from = 0;
            while (true) {
                int at = lower.indexOf(name, from);
                if (at < 0) {
                    break;
                }
                if (isWholeWord(lower, at, name.length())
                        && (best == null || at < best.start())) {
                    best = new Found(brand, at, at + name.length());
                }
                from = at + 1;
            }
        }
        return best;
    }

    private static Found brandAt(String lower, int at, Dictionary dictionary) {
        for (Brand brand : dictionary.brands()) {
            String name = brand.name().toLowerCase(Locale.ROOT);
            if (lower.startsWith(name, at) && isWholeWord(lower, at, name.length())) {
                return new Found(brand, at, at + name.length());
            }
        }
        return null;
    }

    /** Позиция после запятой и пробелов, или −1, если там не запятая. */
    private static int skipSeparator(String lower, int at) {
        int i = at;
        while (i < lower.length() && lower.charAt(i) == ' ') {
            i++;
        }
        if (i >= lower.length() || lower.charAt(i) != ',') {
            return -1;
        }
        i++;
        while (i < lower.length() && lower.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /**
     * Совпадение целым словом. Без этого «Kia» находится внутри «Kiabi»,
     * а «Seat» — внутри «Seat cover», и деталь уезжает к чужой марке.
     */
    private static boolean isWholeWord(String text, int at, int length) {
        boolean leftOk = at == 0 || !isNameChar(text.charAt(at - 1));
        int after = at + length;
        boolean rightOk = after >= text.length() || !isNameChar(text.charAt(after));
        return leftOk && rightOk;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    private record Found(Brand brand, int start, int end) {
    }
}
