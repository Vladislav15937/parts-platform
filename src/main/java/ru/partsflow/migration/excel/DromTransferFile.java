package ru.partsflow.migration.excel;

import ru.partsflow.migration.excel.ColumnMapping.Field;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Файл переноса с Дрома: договор колонок между внешним парсером и импортом.
 *
 * <p>Полностью — с форматом, якорями страницы и примером — договор записан
 * в {@code docs/drom-transfer-file.md}; здесь та его часть, которую исполняет
 * код: какие колонки в файле есть и какую из них во что читает импорт.
 * Расходиться им нельзя, и сверяет их {@code DromTransferFileTest}.
 *
 * <p><b>Файл узнаётся по колонке «Номер объявления Дрома», и дальше колонки
 * берутся по договору, а не угадываются.</b> Синонимы здесь вредны: «Описание»
 * по общему правилу ложится в примечание — внутренний комментарий, которого
 * покупатель не увидит, — а у этого файла описание и есть текст объявления
 * (задача 0275). Договор один, и угадывать в нём нечего.
 *
 * <p><b>Колонка, которую импорт пока не читает, остаётся без поля</b>
 * ({@code reads == null}). Такая колонка не сопоставляется и на экране
 * «Импорт» помечена «не переносится»: показать её сопоставленной значило бы
 * пообещать перенос того, что молча отбрасывается. Какая задача её прочтёт —
 * записано в документе договора и в проверке, а не здесь: номер задачи
 * человеку на экране не нужен.
 */
public final class DromTransferFile {

    /** По этой колонке файл переноса отличается от прочих таблиц. */
    public static final String ANCHOR = "Номер объявления Дрома";

    /**
     * @param title    заголовок колонки в файле — дословно
     * @param required значение обязано быть в каждой строке; сама колонка
     *                 в заголовке есть всегда, даже пустая
     * @param reads    поле, в которое импорт её читает; {@code null} — пока
     *                 не читает
     */
    public record Column(String title, boolean required, Field reads) {
    }

    /** Порядок — тот, в котором парсер кладёт колонки. Импорт ищет по заголовку. */
    public static final List<Column> COLUMNS = List.of(
            new Column(ANCHOR, true, Field.DROM_AD_NUMBER),
            new Column("Ссылка", true, null),
            new Column("Наименование", true, Field.NAME),
            new Column("Вид детали", true, null),
            new Column("Раздел", true, null),
            new Column("Цена", true, Field.PRICE),
            new Column("Количество", false, Field.QUANTITY),
            new Column("Наличие", true, Field.AVAILABILITY),
            new Column("Состояние", true, Field.CONDITION),
            new Column("Оригинальность", false, Field.AUTHENTICITY),
            new Column("Производитель", false, Field.MANUFACTURER),
            new Column("Номер", false, Field.OEM),
            new Column("Номера замен", false, Field.OEM_SUBSTITUTES),
            new Column("Сторона", false, Field.SIDE_LR),
            new Column("Положение", false, Field.SIDE_FR),
            new Column("Верх/низ", false, Field.SIDE_UD),
            new Column("Применимость", false, null),
            new Column("Описание", false, Field.DESCRIPTION),
            new Column("Гарантия", false, Field.WARRANTY),
            new Column("Самовывоз", false, Field.PICKUP),
            new Column("Доставка и оплата", false, Field.DELIVERY),
            new Column("Фото", false, Field.PHOTOS));

    private DromTransferFile() {
    }

    /** Файл ли это переноса с Дрома. */
    public static boolean recognizes(List<String> header) {
        return indexOf(header, ANCHOR) >= 0;
    }

    /** Поле → номер колонки, строго по договору. */
    static Map<Field, Integer> fields(List<String> header) {
        Map<Field, Integer> found = new EnumMap<>(Field.class);
        for (Column column : COLUMNS) {
            if (column.reads() == null) {
                continue;
            }
            int index = indexOf(header, column.title());
            if (index >= 0) {
                found.put(column.reads(), index);
            }
        }
        return found;
    }

    private static int indexOf(List<String> header, String title) {
        String wanted = normalize(title);
        for (int i = 0; i < header.size(); i++) {
            if (normalize(header.get(i)).equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    /** Сравнение названий колонок: края, регистр и «ё» не различаются. */
    static String normalize(String title) {
        return title == null ? "" : title.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
