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

    /**
     * Что мы умеем распознавать. Остальные колонки импорт не трогает.
     *
     * <p><b>Распознать — не значит прочитать.</b> Шесть полей из тринадцати
     * ({@code imported = false}) заголовок узнаёт, а {@code ExcelWarehouseImporter}
     * их не пишет никуда. До задачи 0272 разбор отдавал их экрану среди
     * сопоставленных — то есть обещал перенос того, что молча отбрасывалось.
     * Теперь наружу ({@link #importedFields()}) уходят только читаемые;
     * научили импорт новому полю — переставьте флаг, иначе экран его не увидит.
     */
    public enum Field {
        NAME(true, "наименование", "название", "товар", "запчасть", "деталь", "номенклатура"),
        PRICE(true, "цена", "стоимость", "цена продажи", "розница", "цена, руб", "цена руб"),
        QUANTITY(true, "кол-во", "количество", "остаток", "шт", "штук", "кол"),
        CELL(true, "ячейка", "место", "полка", "место хранения", "адрес", "стеллаж"),
        WAREHOUSE(false, "склад", "склад хранения"),
        BRAND(false, "марка", "производитель авто", "бренд авто", "марка авто"),
        MODEL(false, "модель", "модель авто"),
        YEAR(false, "год", "год выпуска", "г.в.", "гв"),
        VIN(false, "vin", "вин", "vin-код", "вин-код"),
        OEM(true, "оем", "oem", "артикул", "номер детали", "каталожный номер", "номер"),
        CONDITION(false, "состояние", "качество"),
        NOTE(true, "примечание", "комментарий", "описание", "заметка"),
        /**
         * «Наличие» файла переноса с Дрома: «В наличии» заводит позицию,
         * иное значение пропускает строку (задача 0279).
         *
         * <p>Синонимов нет намеренно: правило «В наличии» дословно — дромовское,
         * а в чужой таблице «Наличие» бывает и «да», и числом, и угаданная
         * колонка пропустила бы весь склад как «не в наличии». Сопоставляется
         * поле только договором {@link DromTransferFile}.
         */
        AVAILABILITY(true),
        /**
         * Ссылки на снимки, по одной на строку ячейки, главный первым.
         *
         * <p>Без синонимов намеренно: догадка по заголовку чужой таблицы
         * здесь означала бы обращения к чужим адресам по колонке, которую
         * никто не подтверждал («Фото: есть/нет» в самодельной таблице).
         * Сопоставляется договором файла переноса с Дрома или руками на экране.
         */
        PHOTOS(true),
        // Без синонимов: колонку узнаёт только договор файла переноса с Дрома.
        // Догадкой «номер» её не найти — «Номер» это OEM, а не объявление.
        DROM_AD_NUMBER(true);

        private final boolean imported;
        private final List<String> synonyms;

        Field(boolean imported, String... synonyms) {
            this.imported = imported;
            this.synonyms = List.of(synonyms);
        }

        public List<String> synonyms() {
            return synonyms;
        }

        /** Пишет ли импорт это поле куда-нибудь. */
        public boolean imported() {
            return imported;
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
        if (DromTransferFile.recognizes(header)) {
            // Файл переноса с Дрома сопоставляется договором, а не догадкой:
            // см. DromTransferFile, почему синонимы тут вредны.
            return new ColumnMapping(DromTransferFile.fields(header), List.copyOf(header));
        }
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
     * Сопоставленное из того, что импорт читает, — то, что видит человек.
     *
     * <p>Узнанная, но не читаемая колонка сюда не попадает: на экране она
     * должна стоять «не переносится», а не «сопоставлено».
     */
    public Map<Field, Integer> importedFields() {
        Map<Field, Integer> imported = new EnumMap<>(Field.class);
        byField.forEach((field, index) -> {
            if (field.imported()) {
                imported.put(field, index);
            }
        });
        return imported;
    }

    /** Узнан ли файл переноса с Дрома — тогда сопоставление взято из договора. */
    public boolean isDromTransfer() {
        return DromTransferFile.recognizes(header);
    }

    /**
     * Чего не хватает для импорта.
     *
     * <p>Обязательно одно наименование: без него нечего заводить. Цена
     * необязательна — её проставляют потом, и склад без цен всё равно лучше,
     * чем склад в тетради.
     *
     * <p><b>Количество необязательно с задачи 0279.</b> На карточке Дрома его
     * нет вовсе, и файл переноса с Дрома без него не загружался бы никогда.
     * Без колонки каждая позиция заводится по одной штуке — и экран говорит
     * об этом до загрузки, а отчёт после: догадка показывается человеку.
     */
    public List<Field> missingRequired() {
        List<Field> missing = new ArrayList<>();
        for (Field field : List.of(Field.NAME)) {
            if (!byField.containsKey(field)) {
                missing.add(field);
            }
        }
        return missing;
    }
}
