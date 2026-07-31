package ru.partsflow.migration.excel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Сопоставление колонок чужой таблицы с нашими полями.
 *
 * <p>Единственная причина, по которой импорт вообще сложен: таблица у каждого
 * клиента своя. «Наименование» бывает «Название», «Товар», «Запчасть»
 * и «Деталь»; «Кол-во» — «Количество», «Остаток», «Шт». Порядок колонок
 * произвольный, лишних хватает.
 *
 * <p><b>Догадка показывается человеку, а не применяется молча.</b> Ошибка
 * сопоставления тихая и дорогая: перепутанные местами цена и количество дают
 * склад, где всё стоит по три рубля, и заметят это на первой продаже.
 * Поэтому распознавание возвращает разбор, его подтверждают, и только потом
 * идёт импорт.
 *
 * <p><b>Совпадение точное, а не по похожести.</b> Триграммы дали бы
 * «Цена закупки» вместо «Цена» и «Год выпуска» вместо «Год» — правдоподобно
 * и неверно. Тот же выбор, что в сопоставлении наименований деталей.
 */
public final class ColumnMapping {

    /** Что мы умеем распознавать. Остальные колонки импорт не трогает. */
    public enum Field {
        NAME("наименование", "название", "товар", "запчасть", "деталь", "номенклатура"),
        PRICE("цена", "стоимость", "цена продажи", "розница", "цена, руб", "цена руб"),
        QUANTITY("кол-во", "количество", "остаток", "шт", "штук", "кол"),
        CELL("ячейка", "место", "полка", "место хранения", "адрес", "стеллаж"),
        WAREHOUSE("склад", "склад хранения"),
        BRAND("марка", "производитель авто", "бренд авто", "марка авто"),
        MODEL("модель", "модель авто"),
        YEAR("год", "год выпуска", "г.в.", "гв"),
        VIN("vin", "вин", "vin-код", "вин-код"),
        OEM("оем", "oem", "артикул", "номер детали", "каталожный номер", "номер"),
        CONDITION("состояние", "качество"),
        NOTE("примечание", "комментарий", "описание", "заметка");

        private final List<String> synonyms;

        Field(String... synonyms) {
            this.synonyms = List.of(synonyms);
        }

        public List<String> synonyms() {
            return synonyms;
        }
    }

    private final Map<Field, Integer> byField;
    private final List<String> header;

    private ColumnMapping(Map<Field, Integer> byField, List<String> header) {
        this.byField = byField;
        this.header = header;
    }

    /**
     * Разбирает заголовок.
     *
     * <p>Первая подошедшая колонка выигрывает: в таблицах встречается и «Цена»,
     * и «Цена закупки», и брать надо ту, что совпала точно. Поэтому сначала
     * ищется точное равенство по всем колонкам, и только потом — вхождение.
     */
    public static ColumnMapping detect(List<String> header) {
        Map<Field, Integer> found = new EnumMap<>(Field.class);

        for (Field field : Field.values()) {
            int index = indexOfExact(header, field);
            if (index < 0) {
                index = indexOfContaining(header, field);
            }
            if (index >= 0) {
                found.put(field, index);
            }
        }
        return new ColumnMapping(found, List.copyOf(header));
    }

    /** Сопоставление, заданное человеком: поле → номер колонки. */
    public static ColumnMapping of(List<String> header, Map<Field, Integer> byField) {
        return new ColumnMapping(new EnumMap<>(byField), List.copyOf(header));
    }

    private static int indexOfExact(List<String> header, Field field) {
        for (int i = 0; i < header.size(); i++) {
            String column = normalize(header.get(i));
            if (field.synonyms().contains(column)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Вхождение целым словом.
     *
     * <p>Не подстрокой: «год» входит в «городской», а «шт» — в «штрихкод».
     * Ошибка при этом молчаливая, а импорт после неё придётся откатывать
     * целым складом.
     */
    private static int indexOfContaining(List<String> header, Field field) {
        for (int i = 0; i < header.size(); i++) {
            List<String> words = List.of(normalize(header.get(i)).split("[\\s,./()]+"));
            for (String synonym : field.synonyms()) {
                if (words.contains(synonym)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String normalize(String column) {
        return column == null ? "" : column.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    public Integer indexOf(Field field) {
        return byField.get(field);
    }

    public boolean has(Field field) {
        return byField.containsKey(field);
    }

    public String value(ExcelSheetReader.Row row, Field field) {
        Integer index = byField.get(field);
        return index == null ? "" : row.at(index);
    }

    public List<String> header() {
        return header;
    }

    public Map<Field, Integer> fields() {
        return Map.copyOf(byField);
    }

    /**
     * Чего не хватает для импорта.
     *
     * <p>Обязательны наименование и количество: без первого нечего заводить,
     * без второго непонятно, сколько. Цена необязательна — её проставляют
     * потом, и склад без цен всё равно лучше, чем склад в тетради.
     */
    public List<Field> missingRequired() {
        List<Field> missing = new ArrayList<>();
        for (Field field : List.of(Field.NAME, Field.QUANTITY)) {
            if (!byField.containsKey(field)) {
                missing.add(field);
            }
        }
        return missing;
    }
}
