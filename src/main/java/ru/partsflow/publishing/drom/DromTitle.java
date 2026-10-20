package ru.partsflow.publishing.drom;

import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Название позиции в файле Дрома — по формуле Bazon (задача 0335).
 *
 * <p>{@code Вид Марка Модель [Год] НОМЕР Кузов Двигатель, положение}.
 * Решение владельца продукта 19.10.2026 («на 100% соответствовать … файлов
 * выгрузки в базон», «Всё да, всё как в базон»): переехавший клиент не должен
 * видеть, что заголовки объявлений поменялись, а покупатель, нашедший деталь
 * по прежнему названию, находит её дальше.
 *
 * <p>Название в файле собирается из <b>полей позиции</b>, а не из хранимого
 * {@code part.title}: у Bazon это две формулы (название склада — задача 0336).
 * Поэтому оно не следует за правкой названия в карточке — ровно как у Bazon.
 *
 * <p>Правила, снятые с 35 201 позиции четырёх файлов Bazon:
 * <ul>
 *   <li><b>Вид</b> — как ввёл клиент, первая буква заглавная, пробелы
 *       схлопнуты; остальные буквы не трогаем («Клапан egr»).</li>
 *   <li><b>Марка, модель, кузов, двигатель</b> — первое значение списка
 *       («Lincoln,Ford» → «Lincoln»).</li>
 *   <li><b>Год</b> — текст «Год выпуска» целиком ({@code 2010}, {@code 03-08});
 *       нет текста — год числом (свой, иначе донора); нет года — слова нет.
 *       Стоит сразу после модели, раньше номера.</li>
 *   <li><b>Номер</b> — основной номер как вписан, без всего, кроме букв
 *       и цифр, заглавными. {@code OemNumbers.normalize} здесь не годится:
 *       он ставит русские двойники латиницей. Нет номера — слова нет.</li>
 *   <li><b>Положение</b> — после запятой, словами, в роде и числе первого
 *       слова вида: сначала «перед/зад», потом «лев/прав».</li>
 * </ul>
 */
final class DromTitle {

    private DromTitle() {
    }

    private enum Gender { MASCULINE, FEMININE, NEUTER, PLURAL }

    /**
     * Исключения из рода по окончанию — данные Bazon, один словарь на проект.
     * Слова на -ь по окончанию не определяются вовсе, поэтому им место здесь.
     */
    private static final Map<String, Gender> EXCEPTIONS = exceptions();

    private static Map<String, Gender> exceptions() {
        Map<String, Gender> m = new java.util.HashMap<>();
        for (String w : List.of("дверь", "педаль")) {
            m.put(w, Gender.FEMININE);
        }
        for (String w : List.of("консоль", "панель", "полуось", "фальшпанель", "фонарь",
                "ограничитель", "усилитель", "глушитель")) {
            m.put(w, Gender.MASCULINE);
        }
        for (String w : List.of("реле", "табло", "торпедо", "сиденье", "крыло", "кольцо")) {
            m.put(w, Gender.NEUTER);
        }
        for (String w : List.of("колодки", "коврики", "часы", "брызговики", "ветровики",
                "накладки", "противотуманки", "чехлы")) {
            m.put(w, Gender.PLURAL);
        }
        return Map.copyOf(m);
    }

    private static final Set<Character> VOWEL_FEM = Set.of('а', 'я');

    /** Название позиции в файле; {@code null}, если собрать нечего (нет вида). */
    static String of(DromOffer offer) {
        String kind = kind(offer.partName(), offer.partKind());
        if (kind == null) {
            return null;
        }

        List<String> words = new ArrayList<>();
        words.add(kind);
        add(words, first(offer.titleBrand()));
        add(words, first(offer.titleModel()));
        add(words, year(offer));
        add(words, number(offer.oemNumber()));
        add(words, first(offer.bodyCode()));
        add(words, first(offer.engineCode()));
        String title = String.join(" ", words);

        String position = position(kind, offer.longitudinalSide(), offer.lateralSide());
        return position.isEmpty() ? title : title + ", " + position;
    }

    /**
     * Вид детали с заглавной буквы: наименование, как его ввёл клиент, иначе вид из справочника.
     * Тот же «Вид», что открывает название и заголовок «&lt;Вид&gt; подходит:» (задача 0342).
     */
    static String kind(String partName, String partKind) {
        String kind = collapse(firstNonBlank(partName, partKind));
        return kind == null ? null : Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    }

    /** Номер без всех знаков, кроме букв и цифр, заглавными. */
    static String number(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        raw.codePoints().filter(Character::isLetterOrDigit).forEach(out::appendCodePoint);
        return out.isEmpty() ? null : out.toString().toUpperCase(Locale.ROOT);
    }

    /** Первое значение списка через запятую. */
    static String first(String list) {
        if (list == null) {
            return null;
        }
        for (String part : list.split(",")) {
            String value = collapse(part);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static String year(DromOffer offer) {
        String text = collapse(offer.yearText());
        if (text != null) {
            return text;
        }
        return offer.year() == null || offer.year() <= 0 ? null : String.valueOf(offer.year());
    }

    /** «перед/зад», затем «лев/прав»; «обе стороны» слова не даёт. */
    static String position(String kind, LongitudinalSide longitudinal, LateralSide lateral) {
        Gender gender = genderOf(kind);
        List<String> words = new ArrayList<>();
        if (longitudinal == LongitudinalSide.FRONT) {
            words.add(forms(gender, "передний", "передняя", "переднее", "передние"));
        } else if (longitudinal == LongitudinalSide.REAR) {
            words.add(forms(gender, "задний", "задняя", "заднее", "задние"));
        }
        if (lateral == LateralSide.LEFT) {
            words.add(forms(gender, "левый", "левая", "левое", "левые"));
        } else if (lateral == LateralSide.RIGHT) {
            words.add(forms(gender, "правый", "правая", "правое", "правые"));
        }
        return String.join(" ", words);
    }

    private static String forms(Gender g, String masculine, String feminine, String neuter,
                                String plural) {
        return switch (g) {
            case MASCULINE -> masculine;
            case FEMININE -> feminine;
            case NEUTER -> neuter;
            case PLURAL -> plural;
        };
    }

    /** Род и число по первому слову вида: исключения, затем окончание. */
    static Gender genderOf(String kind) {
        String word = kind.strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
        Gender exception = EXCEPTIONS.get(word);
        if (exception != null) {
            return exception;
        }
        char last = word.charAt(word.length() - 1);
        if (VOWEL_FEM.contains(last)) {
            return Gender.FEMININE;
        }
        if (last == 'о' || last == 'е') {
            return Gender.NEUTER;
        }
        if (last == 'ы' || last == 'и') {
            return Gender.PLURAL;
        }
        // -ый/-ий/-ой, согласная и необъяснённое -ь — мужской.
        return Gender.MASCULINE;
    }

    private static void add(List<String> words, String word) {
        if (word != null && !word.isBlank()) {
            words.add(word);
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** Пробелы схлопнуты, края срезаны; пусто — {@code null}. */
    private static String collapse(String s) {
        if (s == null) {
            return null;
        }
        String c = s.strip().replaceAll("\\s+", " ");
        return c.isEmpty() ? null : c;
    }
}
