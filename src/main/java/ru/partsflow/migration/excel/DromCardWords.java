package ru.partsflow.migration.excel;

import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.PartCondition;
import ru.partsflow.inventory.VerticalSide;
import ru.partsflow.migration.bazon.BazonValueParser;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Слова карточки Дрома — состояние и три стороны — в значения позиции
 * (задача 0277).
 *
 * <p><b>Пусто и «не понял» различаются.</b> Пустая ячейка — {@code null}:
 * поля на карточке нет, и позиция заводится без него (состояние — умолчанием
 * «б/у», как у любой позиции без слова). Непустое слово, которого здесь нет,
 * — {@link Unknown}: импорт пропускает строку и называет слово дословно,
 * как «не в наличии: «Под заказ»». Молча подставленная сторона или «б/у»
 * вместо неизвестного слова — это объявление клиента, переехавшее неправдой,
 * а узнать об этом можно было бы только на Дроме у покупателя.
 *
 * <p><b>Состояние разбирает тот же разборщик, что у переноса из прежней
 * системы</b> ({@link BazonValueParser#parseCondition}): два разборщика одних
 * слов разошлись бы на первом же новом значении, и «Контракт» нашёлся бы
 * в одном переносе и не нашёлся в другом.
 */
final class DromCardWords {

    /** Непустое слово, которого разборщик не знает. */
    static final class Unknown extends RuntimeException {

        /** @param what что не распознано, уже с родом: «сторона не распознана» */
        Unknown(String what, String word) {
            super(what + ": «" + word + "»");
        }
    }

    private DromCardWords() {
    }

    static PartCondition condition(String raw) {
        String word = blankToNull(raw);
        if (word == null) {
            return null;
        }
        PartCondition parsed = BazonValueParser.parseCondition(word);
        if (parsed == null) {
            throw new Unknown("состояние не распознано", word);
        }
        return parsed;
    }

    /**
     * «Левая», «Правая» или обе через запятую — так Дром пишет на карточке
     * («Левая, Правая» у амортизатора № 135618913, «цена за пару»).
     */
    static LateralSide lateral(String raw) {
        return sides(raw, "сторона не распознана", LateralSide.class, LateralSide.BOTH, part ->
                switch (part) {
                    case "левая", "левый", "левое", "лево", "лев.", "лев" -> LateralSide.LEFT;
                    case "правая", "правый", "правое", "право", "прав.", "прав" -> LateralSide.RIGHT;
                    default -> null;
                });
    }

    /** «Переднее», «Заднее» — средний род на карточке Дрома; обе через запятую — {@code BOTH} (задача 0283). */
    static LongitudinalSide longitudinal(String raw) {
        return sides(raw, "положение не распознано", LongitudinalSide.class, LongitudinalSide.BOTH, part ->
                switch (part) {
                    case "переднее", "передний", "передняя", "перед", "перед." -> LongitudinalSide.FRONT;
                    case "заднее", "задний", "задняя", "зад", "задн." -> LongitudinalSide.REAR;
                    default -> null;
                });
    }

    /**
     * «Верхнее», «Нижнее». На образце поле не встретилось ни разу
     * (docs/drom-transfer-file.md: выведено по паре осей), поэтому слова
     * взяты по образцу «Переднее»; другое слово Дрома не потеряется молча,
     * а остановит строку со словами.
     */
    static VerticalSide vertical(String raw) {
        return sides(raw, "верх/низ не распознан", VerticalSide.class, VerticalSide.BOTH, part ->
                switch (part) {
                    case "верхнее", "верхний", "верхняя", "верх", "верх." -> VerticalSide.UPPER;
                    case "нижнее", "нижний", "нижняя", "низ", "ниж." -> VerticalSide.LOWER;
                    default -> null;
                });
    }

    /**
     * Разбор списка сторон одной оси — один на все три, а не три копии цикла.
     *
     * <p>Слова через {@code , ; /} в любом порядке; две разные стороны дают
     * {@code both}, повтор одного слова — эту одну сторону. Слово другой оси
     * или неизвестное останавливает строку со всем значением дословно: сторона,
     * подставленная вместо непонятной, — это объявление, переехавшее неправдой.
     * Пустое значение — {@code null}; значение из одних разделителей — не слово.
     */
    private static <S extends Enum<S>> S sides(String raw, String what, Class<S> axis, S both,
                                               java.util.function.Function<String, S> parse) {
        String word = blankToNull(raw);
        if (word == null) {
            return null;
        }
        Set<S> found = EnumSet.noneOf(axis);
        for (String part : word.split("[,;/]")) {
            if (part.isBlank()) {
                continue;
            }
            S side = parse.apply(normalize(part));
            if (side == null) {
                throw new Unknown(what, word);
            }
            found.add(side);
        }
        if (found.isEmpty()) {
            throw new Unknown(what, word);
        }
        return found.size() == 2 ? both : found.iterator().next();
    }

    /**
     * Код стороны словом карточки Дрома — для отчёта повторной загрузки
     * (задача 0282): «у позиции LEFT» — это внутреннее представление, а не
     * то, что владелец видел у себя в объявлении. {@code BOTH} у каждой из
     * трёх осей называется своей парой, поэтому нужна колонка ({@code side_lr},
     * {@code side_fr}, {@code side_ud}). Не сторона — {@code null}.
     */
    static String word(String column, String code) {
        return switch (column) {
            case "side_lr" -> switch (code) {
                case "LEFT" -> "Левая";
                case "RIGHT" -> "Правая";
                case "BOTH" -> "Левая, Правая";
                default -> null;
            };
            case "side_fr" -> switch (code) {
                case "FRONT" -> "Переднее";
                case "REAR" -> "Заднее";
                case "BOTH" -> "Переднее, Заднее";
                default -> null;
            };
            case "side_ud" -> switch (code) {
                case "UPPER" -> "Верхнее";
                case "LOWER" -> "Нижнее";
                case "BOTH" -> "Верхнее, Нижнее";
                default -> null;
            };
            default -> null;
        };
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.strip();
    }
}
