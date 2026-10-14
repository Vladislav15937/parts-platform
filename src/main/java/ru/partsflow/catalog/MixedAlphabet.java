package ru.partsflow.catalog;

/**
 * Слова, набранные смесью алфавитов: «Сruiser» с кириллической «С» — латинское слово
 * с одной подменённой буквой (задача 0298).
 *
 * <p><b>Зачем.</b> Наименования набирают с телефона и копируют из чужих объявлений, и
 * кириллическая «С» в середине латинского слова выглядит как латинская. Сравнение со
 * справочником идёт по знакам, поэтому у таких позиций марка находилась, а модели не было,
 * и номер детали с кириллической «С» на конце терял эту букву при приведении.
 *
 * <p><b>Только внутри смешанных слов.</b> Слово — подряд идущие буквы и цифры. Если в нём
 * есть и латинская буква, и кириллическая, кириллические двойники читаются как латинские.
 * Чисто русское слово остаётся как есть: «сорт» не должно стать «copt» и совпасть с моделью,
 * «Хонда» остаётся «Хонда». Цифры алфавита не несут: «8518100С» из одних цифр и «С» не
 * смешанное слово, и «С» там остаётся кириллической (ровно как до правки).
 *
 * <p><b>Длина сохраняется.</b> Каждый знак заменяется одним знаком, поэтому позиции в
 * исходной строке и в приведённой совпадают — на этом держится разбор наименования.
 */
public final class MixedAlphabet {

    /** Кириллические двойники латиницы и их латинские пары; один и тот же порядок. */
    private static final String CYRILLIC = "АВЕКМНОРСТХаеорсух";
    private static final String LATIN = "ABEKMHOPCTXaeopcyx";

    /**
     * Те же двойники для {@code translate} в SQL — единственное место, откуда
     * запрос берёт список, чтобы он не разошёлся с Java.
     */
    public static final String SQL_CYRILLIC = CYRILLIC;
    public static final String SQL_LATIN = LATIN;

    private MixedAlphabet() {
    }

    /** Строка, в которой смешанные слова переведены в латиницу; прочее — без изменений. */
    public static String latinize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = null;
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (!isWordChar(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            boolean latin = false;
            boolean cyrillic = false;
            while (i < n && isWordChar(text.charAt(i))) {
                char c = text.charAt(i);
                if (isLatin(c)) {
                    latin = true;
                } else if (isCyrillic(c)) {
                    cyrillic = true;
                }
                i++;
            }
            if (latin && cyrillic) {
                for (int k = start; k < i; k++) {
                    int at = CYRILLIC.indexOf(text.charAt(k));
                    if (at >= 0) {
                        if (out == null) {
                            out = new StringBuilder(text);
                        }
                        out.setCharAt(k, LATIN.charAt(at));
                    }
                }
            }
        }
        return out == null ? text : out.toString();
    }

    private static boolean isLatin(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isCyrillic(char c) {
        return (c >= 'А' && c <= 'я') || c == 'Ё' || c == 'ё';
    }

    private static boolean isWordChar(char c) {
        return isLatin(c) || isCyrillic(c) || (c >= '0' && c <= '9');
    }
}
