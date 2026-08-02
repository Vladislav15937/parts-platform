package ru.partsflow.inventory;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор размера из строки поиска.
 *
 * <p><b>Зачем.</b> Покупатель звонит и называет размер, а не номер товара:
 * «есть двести двадцать пять пятьдесят пять на восемнадцать» или «нужны диски
 * пять на сто четырнадцать и три». Поиск по тексту заголовка это ловит только
 * при точном совпадении написания: в заголовке стоит «225/55 R18», и запрос
 * «225 55 18» не находит ничего, хотя товар лежит на полке.
 *
 * <p><b>Что разбирается.</b> Размер шины («225/55 R18», «225/55R18»,
 * «225 55 18»), размер диска («7x18», «18x7»), сверловка («5x114.3») и вылет
 * («ET38»). Всё, что не разобрано, остаётся текстом и ищется по номеру
 * и заголовку, как раньше: «Dunlop зимняя» так и должно искаться словами.
 *
 * <p><b>Сверловка и размер диска пишутся одинаково</b> — «5x114.3» и «7x18», —
 * и различаются числами. У сверловки второе число это диаметр окружности
 * в миллиметрах, он от 98 и выше; у диска второе число это посадочный диаметр
 * в дюймах, от 12 до 24. Пересечения нет, и это не совпадение: дюймы
 * и миллиметры разной величины.
 *
 * <p><b>Раскладка приводится к латинице.</b> «5х114.3» с кириллической «х»
 * набирают, не переключаясь, и это тот же размер. Та же беда, что с кодами
 * ячеек на этикетках.
 */
public record WheelSizeQuery(Integer tyreWidth,
                             Integer tyreHeight,
                             BigDecimal diameter,
                             BigDecimal discWidth,
                             String boltPattern,
                             Integer offsetMm,
                             String text) {

    /** Размер шины: три числа через «/», пробел или без разделителя вовсе. */
    private static final Pattern TYRE =
            Pattern.compile("\\b(\\d{3})\\s*[/ ]\\s*(\\d{2})\\s*(?:R\\s*)?(\\d{2})\\b",
                    Pattern.CASE_INSENSITIVE);

    /** Половина размера: «225/55» без диаметра — тоже осмысленный запрос. */
    private static final Pattern TYRE_PROFILE =
            Pattern.compile("\\b(\\d{3})\\s*/\\s*(\\d{2})\\b");

    /** Посадочный диаметр отдельно: «R18» или «18\"». */
    private static final Pattern RIM =
            Pattern.compile("\\bR\\s*(\\d{2})\\b|\\b(\\d{2})\"", Pattern.CASE_INSENSITIVE);

    private static final Pattern BOLT =
            Pattern.compile("\\b(\\d)\\s*x\\s*(\\d{2,3}(?:[.,]\\d)?)\\b", Pattern.CASE_INSENSITIVE);

    // Оба числа короткие: «7x18» из объявления и «18x7» с самого диска.
    // Сверловку это не заденет — её вторая часть трёхзначная и разобрана выше.
    private static final Pattern DISC =
            Pattern.compile("\\b(\\d{1,2}(?:[.,]\\d)?)\\s*x\\s*(\\d{1,2}(?:[.,]\\d)?)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern OFFSET =
            Pattern.compile("\\bET\\s*(-?\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    /** Ниже этого второе число — дюймы диска, выше — миллиметры сверловки. */
    private static final BigDecimal PCD_FROM = new BigDecimal("90");

    public static WheelSizeQuery parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new WheelSizeQuery(null, null, null, null, null, null, null);
        }
        // Кириллическая «х» и знак умножения — то же самое, что латинская «x»:
        // их набирают, не переключая раскладку.
        String rest = raw.strip().replace('х', 'x').replace('Х', 'x')
                .replace('×', 'x').replace(',', '.');

        Integer tyreWidth = null;
        Integer tyreHeight = null;
        BigDecimal diameter = null;
        BigDecimal discWidth = null;
        String bolt = null;
        Integer offset = null;

        Matcher m = TYRE.matcher(rest);
        if (m.find()) {
            tyreWidth = Integer.valueOf(m.group(1));
            tyreHeight = Integer.valueOf(m.group(2));
            diameter = new BigDecimal(m.group(3));
            rest = cut(rest, m);
        } else {
            m = TYRE_PROFILE.matcher(rest);
            if (m.find()) {
                tyreWidth = Integer.valueOf(m.group(1));
                tyreHeight = Integer.valueOf(m.group(2));
                rest = cut(rest, m);
            }
        }

        m = OFFSET.matcher(rest);
        if (m.find()) {
            offset = Integer.valueOf(m.group(1));
            rest = cut(rest, m);
        }

        m = BOLT.matcher(rest);
        if (m.find() && new BigDecimal(m.group(2)).compareTo(PCD_FROM) >= 0) {
            bolt = m.group(1) + "x" + trim(new BigDecimal(m.group(2)));
            rest = cut(rest, m);
        }

        m = DISC.matcher(rest);
        if (m.find()) {
            BigDecimal first = new BigDecimal(m.group(1));
            BigDecimal second = new BigDecimal(m.group(2));
            // «18x7» пишут на самом диске, «7x18» — в объявлении. Различаем
            // по величине: посадочный диаметр всегда больше ширины.
            BigDecimal width = first.compareTo(second) <= 0 ? first : second;
            BigDecimal rim = first.compareTo(second) <= 0 ? second : first;
            discWidth = width;
            if (diameter == null) {
                diameter = rim;
            }
            rest = cut(rest, m);
        }

        if (diameter == null) {
            m = RIM.matcher(rest);
            if (m.find()) {
                diameter = new BigDecimal(m.group(1) != null ? m.group(1) : m.group(2));
                rest = cut(rest, m);
            }
        }

        String text = rest.replaceAll("\\s+", " ").strip();
        return new WheelSizeQuery(tyreWidth, tyreHeight, diameter, discWidth, bolt,
                offset, text.isEmpty() ? null : text);
    }

    /** Разобран ли хоть один размер: если нет, поиск остаётся текстовым. */
    public boolean hasSize() {
        return tyreWidth != null || diameter != null || discWidth != null
                || boltPattern != null || offsetMm != null;
    }

    private static String cut(String source, Matcher found) {
        return source.substring(0, found.start()) + " " + source.substring(found.end());
    }

    private static String trim(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
