package ru.partsflow.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Нормализация номера производителя.
 *
 * <p>Один и тот же номер пишут по-разному: «81170-33670», «81170 33670»,
 * «8117033670», в любом регистре. Чтобы любое написание находило одну и ту же
 * деталь, рядом с исходным номером хранится приведённый — только буквы
 * и цифры, в верхнем регистре.
 *
 * <p>До 4 августа 2026 приведение делала генерируемая колонка
 * {@code part_oem.normalized} через функцию {@code catalog.normalize_oem}.
 * Правило «вся логика в приложении» перенесло это сюда, и правило тут
 * не формальность: приведение — это решение о том, что считать одним номером,
 * а такие решения не должны жить в двух местах.
 *
 * <p><b>Писать и искать обязаны одним и тем же методом.</b> Разойдись они —
 * и деталь, заведённая с дефисом, перестанет находиться по номеру без него;
 * заметит это продавец, ответивший покупателю «нет такого», а проверить его
 * будет некому.
 *
 * <p><b>Здесь же — каким номер хранить ({@link #clean}) и как делить список
 * кросс-номеров ({@link #list})</b> (задача 0309). Записывают номер карточка,
 * приёмка и перенос из таблицы; правило «русские двойники — латиницей» в одном
 * из них и не в другом дало бы номер, который на Дроме ищется у одной позиции
 * и не ищется у соседней.
 */
public final class OemNumbers {

    /**
     * Русские буквы, неотличимые глазом от латинских, и их латинские пары —
     * в одном порядке. Строчных двойников меньше: «в», «к», «м», «н», «т»
     * от «b», «k», «m», «h», «t» глазом отличаются, и «н» в «б/н» — это «без
     * номера», а не латинская «h» (на этом поймался первый вариант правила).
     */
    private static final String CYRILLIC_DOUBLES = "АВЕКМНОРСТХаеорсх";
    private static final String LATIN_DOUBLES = "ABEKMHOPCTXaeopcx";

    /**
     * Чем делят кросс-номера — одно место на карточку и перенос. Пробел
     * разделителем не считается: «81170 33670» — один номер. Табуляция —
     * считается: её приносит копирование из таблицы (решение владельца
     * продукта 16.10.2026, «считать табуляцию разделителем»).
     */
    private static final String SEPARATORS = "[,;\\n\\r\\t]+";

    private OemNumbers() {
    }

    /**
     * Самый длинный номер, который можно сохранить, — в знаках приведённого вида
     * ({@link #normalize}): его и видит индекс {@code part_oem_uk (part_id, normalized)}
     * (задача 0312, решение владельца продукта 17.10.2026: «отказывай словами»).
     *
     * <p>Индекс btree не принимает строку длиннее 2 704 байт после сжатия, и номер
     * примерно из 2 700 несжимаемых знаков уходил наружу пятисоткой «Внутренняя
     * ошибка», а на приёмке — записью, которую офлайн-очередь повторяет вечно
     * (замер 0310). Предел — в 2,7 раза ниже предела индекса, чтобы не зависеть
     * от того, как Postgres сожмёт строку, и в 15 раз выше предупреждения экрана
     * (64 знака): настоящий номер сюда не дотягивается, дотягивается только
     * вставка не из того поля. Мерится каждый номер отдельно: кросс-номер —
     * своя строка {@code part_oem}, и суммарного предела у них нет.
     *
     * <p>Копия на экране — {@code frontend/src/inventory/oemLength.ts}
     * ({@code OEM_STORE_LIMIT}); сверяет их {@code OemLengthClientTest}.
     */
    public static final int MAX_NORMALIZED_LENGTH = 1000;

    /**
     * Почему номер нельзя сохранить, словами; {@code null} — можно.
     *
     * <p>Одно место на все поверхности записи {@code part_oem}: карточку, приёмку
     * и оба переноса. Проверка стоит до записи, а не ловит отбой индекса:
     * текст «exceeds btree» — не договор, а индекс остаётся второй линией.
     *
     * @param raw   номер как пришёл
     * @param cross кросс-номер — тогда в тексте его начало, чтобы человек узнал,
     *              какой из списка; самого номера в тысячи знаков в ответе нет
     */
    public static String tooLong(String raw, boolean cross) {
        String normalized = normalize(raw);
        if (normalized == null || normalized.length() <= MAX_NORMALIZED_LENGTH) {
            return null;
        }
        String tail = " длиннее " + MAX_NORMALIZED_LENGTH + " знаков: сохранить нельзя."
                + " Похоже, вставлено не то — проверьте номер";
        if (!cross) {
            return "Номер производителя" + tail;
        }
        String shown = stripSpaces(raw);
        return "Кросс-номер «" + (shown.length() > 20 ? shown.substring(0, 20) + "…" : shown)
                + "»" + tail;
    }

    /**
     * @return номер без разделителей в верхнем регистре; {@code null}, если
     *         номера в строке нет вовсе
     *
     * <p><b>{@code null} означает «это не номер», и строку в {@code part_oem}
     * заводить не надо.</b> Колонка {@code normalized} объявлена {@code NOT NULL},
     * и попытка записать туда пустоту — отказ базы: при переезде он уносит
     * карточку целиком вместе с остатком и снимками, при приёмке отвечает
     * пятисоткой, а её офлайн-очередь повторяет вечно. Приходит это не из
     * выдумки: в выгрузке живого клиента в кросс-номерах стоит слово «АНАЛОГ»,
     * от которого после приведения не остаётся ничего. Пока приведение считала
     * генерируемая колонка, из такого выходила пустая строка и вставка
     * проходила; перенос в Java поведение изменил, и заметил это только прогон.
     *
     * <p><b>Русские двойники читаются латиницей</b> (задача 0309): слово, все
     * русские буквы которого — двойники, приводится целиком («8518100С» —
     * «8518100C», «АВ123» — «AB123»), иначе последняя буква вырезалась бы
     * вместе с разделителями. Слово со смесью алфавитов приводится по правилу
     * 0298 ({@link MixedAlphabet}). Слово с русской буквой вне двойников
     * («АНАЛОГ») остаётся русским и номера не даёт. Всегда верно
     * {@code normalize(clean(x)) == normalize(x)}: хранимое и набранное
     * приводятся к одному.
     */
    public static String normalize(String rawNumber) {
        if (rawNumber == null) {
            return null;
        }
        // Двойники — до верхнего регистра: прописная «Н» двойник, строчная нет.
        String latin = latinizeDoubles(MixedAlphabet.latinize(rawNumber))
                .toUpperCase(Locale.ROOT);
        String normalized = latin.replaceAll("[^A-Z0-9]", "");
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Номер, каким его хранить ({@code part_oem.raw_number}) — задача 0309,
     * решение владельца продукта 16.10.2026: «похожие русские заменять
     * латиницей, срезать неразрывный пробел».
     *
     * <p>По краям срезаются любые пробелы, а не только обычный: неразрывный
     * (U+00A0), узкие и нулевой ширины приходят копированием из письма
     * и мессенджера, невидимы и оставили бы номер, которого не набрать.
     * Внутри номера они остаются: при приведении это разделитель, как пробел.
     *
     * <p>Русские двойники заменяются латиницей в каждом слове, где все русские
     * буквы — двойники («АВ-123С» — «AB-123C», регистр сохраняется). Слово
     * с другой русской буквой не трогается вовсе: «АНАЛОГ» с заменой стало бы
     * «AHAЛOГ» — мусором, который не читается ни так, ни так.
     *
     * @return {@code null} для {@code null}; пустая строка, если в строке одни пробелы
     */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        return latinizeDoubles(stripSpaces(raw));
    }

    /**
     * Список кросс-номеров из текста: делится {@link #SEPARATORS}, каждый
     * кусок — {@link #clean}, пустые отброшены. Порядок — как в тексте.
     */
    public static List<String> list(String text) {
        List<String> numbers = new ArrayList<>();
        if (text == null) {
            return numbers;
        }
        for (String piece : text.split(SEPARATORS)) {
            String cleaned = clean(piece);
            if (!cleaned.isEmpty()) {
                numbers.add(cleaned);
            }
        }
        return numbers;
    }

    /**
     * Сколько пустых кусков стоит <b>между</b> номерами («A,,B», «A, ,B»), — для отчёта
     * пересчёта 0319: {@link #list} их молча отбрасывает, а человеку, просматривающему
     * деление, надо видеть, что в строке было место без номера. Разделители — те же
     * {@link #SEPARATORS}, по одному; «\r\n» — один перевод строки, а не пустота
     * между двумя. Края не считаются: разделитель в конце строки — не пропуск.
     */
    public static int emptyPieces(String text) {
        if (text == null) {
            return 0;
        }
        String[] pieces = text.split("\\r\\n|" + SEPARATORS.substring(0, SEPARATORS.length() - 1), -1);
        int first = 0;
        int last = pieces.length - 1;
        while (first <= last && clean(pieces[first]).isEmpty()) {
            first++;
        }
        while (last >= first && clean(pieces[last]).isEmpty()) {
            last--;
        }
        int empty = 0;
        for (int i = first; i <= last; i++) {
            if (clean(pieces[i]).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    /**
     * Приведённый запрос для поиска по {@code part_oem.normalized} (задача 0306) —
     * тем же {@link #normalize}, что и запись: набравший «AB 123 456 X», «ab123456x»
     * или русскими «АВ123456Х» находит записанное «ab-123 456x».
     *
     * <p>{@code null} — запрос не похож на номер, и ветки по приведённому виду
     * быть не должно. Два случая. В запросе осталась русская буква, не ставшая
     * латиницей: «фара 1» привела бы к «1» и отдала бы пол-склада. Или после
     * приведения короче {@link #SEARCH_MIN_LENGTH} знаков: триграммный индекс
     * {@code part_oem_trgm} на двух знаках не работает, а одна-две цифры и так
     * находятся подстрокой набранного.
     */
    public static String searchKey(String query) {
        if (query == null) {
            return null;
        }
        String latin = latinizeDoubles(MixedAlphabet.latinize(query));
        for (int i = 0; i < latin.length(); i++) {
            char c = latin.charAt(i);
            if (Character.isLetter(c) && !((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                return null;
            }
        }
        String normalized = normalize(query);
        return normalized == null || normalized.length() < SEARCH_MIN_LENGTH ? null : normalized;
    }

    /** Короче поиск по приведённому виду не идёт ({@link #searchKey}). */
    private static final int SEARCH_MIN_LENGTH = 3;

    /** Края без любых пробелов — обычных, неразрывных, узких и нулевой ширины. */
    private static String stripSpaces(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c)
                || c == '​' || c == '⁠' || c == '﻿';
    }

    /**
     * Слова, все русские буквы которых — двойники, переводятся в латиницу;
     * длина и регистр сохраняются. Слово — подряд идущие буквы и цифры,
     * как у {@link MixedAlphabet}.
     */
    private static String latinizeDoubles(String text) {
        StringBuilder out = null;
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (!isWordChar(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            boolean onlyDoubles = true;
            boolean cyrillic = false;
            while (i < n && isWordChar(text.charAt(i))) {
                char c = text.charAt(i);
                if (isCyrillic(c)) {
                    cyrillic = true;
                    if (CYRILLIC_DOUBLES.indexOf(c) < 0) {
                        onlyDoubles = false;
                    }
                }
                i++;
            }
            if (cyrillic && onlyDoubles) {
                if (out == null) {
                    out = new StringBuilder(text);
                }
                for (int k = start; k < i; k++) {
                    char c = text.charAt(k);
                    if (isCyrillic(c)) {
                        out.setCharAt(k, LATIN_DOUBLES.charAt(CYRILLIC_DOUBLES.indexOf(c)));
                    }
                }
            }
        }
        return out == null ? text : out.toString();
    }

    private static boolean isCyrillic(char c) {
        return (c >= 'А' && c <= 'я') || c == 'Ё' || c == 'ё';
    }

    private static boolean isWordChar(char c) {
        return isCyrillic(c) || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9');
    }
}
