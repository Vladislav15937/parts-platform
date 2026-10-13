package ru.partsflow.migration.excel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.catalog.PartName;
import ru.partsflow.catalog.PartNameService;
import ru.partsflow.migration.excel.ColumnMapping.Field;
import ru.partsflow.migration.excel.ExcelWarehouseImporter.Report.Skipped;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Перенос склада из таблицы клиента.
 *
 * <p>Без этого ни один клиент не переходит: склад в три тысячи позиций
 * не перезаносят руками, а в пятьдесят — тем более.
 *
 * <p><b>Пишется движениями, а не остатком.</b> Соблазн проставить
 * {@code part.qty_on_hand} прямо велик — так быстрее, — но остаток здесь
 * агрегат журнала, и запись мимо него разъедется с {@code part_stock}
 * на первой же продаже. Каждая строка таблицы даёт приходное движение,
 * а остаток считает триггер.
 *
 * <p><b>Наименование идёт через тот же справочник, что и приёмка.</b>
 * {@code PartNameService.resolve} заводит его, если такого не было, и пытается
 * сопоставить с эталоном точным совпадением. Не сопоставилось — позиция
 * попадает на экран нераспознанных вместе с теми, что завёл приёмщик.
 * Отдельный путь «импортированные наименования» означал бы второй справочник,
 * который расходится с первым.
 *
 * <p><b>Категория берётся от эталона или не берётся вовсе.</b> Придумать её
 * при импорте нельзя: категория — свойство вида детали, а не строки чужой
 * таблицы. Позиция без категории не уедет на площадку, и это правильное
 * следствие: пока наименование не разобрано, публиковать нечего.
 */
@Service
public class ExcelWarehouseImporter {

    private static final Logger log = LoggerFactory.getLogger(ExcelWarehouseImporter.class);

    /** Сколько строк показываем в предпросмотре. Больше человеку не нужно. */
    private static final int PREVIEW_ROWS = 15;

    private static final int BATCH_SIZE = 500;

    /** Значение «Наличия» с карточки Дрома, при котором позиция заводится. */
    static final String IN_STOCK = "В наличии";

    private final JdbcTemplate jdbc;
    private final PartNameService partNames;

    private final ru.partsflow.inventory.StockLedger ledger;

    public ExcelWarehouseImporter(JdbcTemplate jdbc, PartNameService partNames,
                                  ru.partsflow.inventory.StockLedger ledger) {
        this.jdbc = jdbc;
        this.partNames = partNames;
        this.ledger = ledger;
    }

    /**
     * Разбирает файл, ничего не записывая.
     *
     * <p>Обязательный шаг: сопоставление колонок — догадка, а ошибка в ней
     * тихая. Перепутанные цена и количество дают склад, где всё по три рубля,
     * и замечают это на первой продаже.
     */
    public Preview preview(InputStream in) throws Exception {
        List<String> header = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();

        ExcelSheetReader.read(in, row -> {
            if (header.isEmpty()) {
                header.addAll(row.cells());
                return true;
            }
            if (!row.isEmpty()) {
                rows.add(row.cells());
            }
            return rows.size() < PREVIEW_ROWS;
        });

        if (header.isEmpty()) {
            throw new IllegalArgumentException("В файле нет ни одной строки");
        }
        ColumnMapping mapping = ColumnMapping.detect(header);
        return new Preview(header, mapping.importedFields(), mapping.missingRequired(), rows,
                mapping.isDromTransfer() ? Preview.DROM_TRANSFER : null);
    }

    /**
     * Заливает склад.
     *
     * <p><b>Повтор с тем же ключом не заводит второй склад.</b> Загрузка —
     * самая разрушительная операция в системе: тысячи позиций, и отменить её
     * можно только восстановлением из бэкапа. Первая же проверка это
     * и показала: запись прошла, ответ упал на сериализации, владелец увидел
     * ошибку и нажал ещё раз — склад стал двойным.
     *
     * <p>Ключ занимается вставкой в начале той же транзакции. Второй
     * одновременный запрос упрётся в уникальный индекс, а не в проверку
     * «нет ли уже такого», которая его пропустила бы. Сорвавшийся импорт
     * откатит и ключ, поэтому повторить его можно.
     *
     * @param warehouseId куда класть остаток. Один на всю таблицу: колонка
     *                    склада в чужих выгрузках встречается редко, а
     *                    раскладывать по несуществующим складам нечем
     * @param requestId   ключ клиента: генерируется при выборе файла
     *                    и не меняется при повторах
     */
    @Transactional
    public Report importInto(InputStream in, ColumnMapping mapping, long warehouseId,
                             String requestId) throws Exception {

        List<Field> missing = mapping.missingRequired();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Не сопоставлены обязательные колонки: " + missing);
        }

        Report already = replayOf(requestId);
        if (already != null) {
            log.info("Повтор загрузки по ключу {}: отдаём прежний итог", requestId);
            return already;
        }
        // Занимаем ключ до работы: второй одновременный запрос упрётся сюда.
        jdbc.update("INSERT INTO import_run (client_request_id, warehouse_id) VALUES (?, ?)",
                requestId, warehouseId);

        Map<String, Long> cells = existingCells(warehouseId);
        // Наименования кэшируются: строк пятьдесят тысяч, а различных названий
        // среди них — тысячи. Ходить в справочник на каждую строку значит
        // потратить импорт на повторные запросы об одном и том же.
        Map<String, PartName> names = new HashMap<>();
        Tally report = new Tally();
        List<Row> batch = new ArrayList<>(BATCH_SIZE);
        boolean[] headerSeen = {false};

        ExcelSheetReader.read(in, row -> {
            if (!headerSeen[0]) {
                headerSeen[0] = true;
                return true;
            }
            if (row.isEmpty()) {
                return true;
            }
            Row parsed = parse(row, mapping, report);
            if (parsed != null) {
                batch.add(parsed);
                if (batch.size() >= BATCH_SIZE) {
                    flush(batch, warehouseId, cells, names, report);
                }
            }
            return true;
        });
        flush(batch, warehouseId, cells, names, report);

        // Движения записаны, применить их надо пачкой: тысяча строк — тысяча
        // обращений к базе, если делать это по одному. Остаток и статус
        // считаются по журналу, поэтому результат тот же.
        ledger.recomputeAll();

        log.info("Импорт из таблицы: заведено {} позиций, пропущено {}",
                report.imported, report.skipped.size());

        Report result = report.toReport();
        jdbc.update("UPDATE import_run SET imported = ?, skipped = ?::jsonb "
                        + "WHERE client_request_id = ?",
                result.imported(), skippedJson(result), requestId);
        return result;
    }

    /**
     * Итог прошлой загрузки с тем же ключом.
     *
     * <p>Повтор получает ответ, а не отказ: клиент не отличает «получилось
     * только что» от «получилось в прошлый раз», а увидев ошибку — нажмёт
     * ещё раз.
     */
    private Report replayOf(String requestId) {
        List<Integer> imported = jdbc.queryForList(
                "SELECT imported FROM import_run WHERE client_request_id = ?",
                Integer.class, requestId);
        if (imported.isEmpty()) {
            return null;
        }
        return new Report(imported.get(0), skippedOf(requestId), quantityDefaultedOf(requestId));
    }

    /**
     * Сколько позиций прошлый прогон завёл по одной штуке.
     *
     * <p>Число лежит отдельным элементом в том же {@code skipped}: своей
     * колонки у {@code import_run} нет, а заводить её ради одного числа
     * значит changeset и отдельную ветку. Потерять его на повторе нельзя —
     * повтор смотрят тогда, когда первого ответа не видели, и «заведено
     * 2 897» без «по одной штуке» читается как точный склад.
     */
    private int quantityDefaultedOf(String requestId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COALESCE(sum((e->>'quantityDefaulted')::int), 0)::int
                          FROM import_run r
                         CROSS JOIN LATERAL jsonb_array_elements(r.skipped) e
                         WHERE r.client_request_id = ?
                           AND e->>'quantityDefaulted' IS NOT NULL""",
                Integer.class, requestId);
        return count == null ? 0 : count;
    }

    /**
     * Пропущенные строки прошлого прогона — разбирает их Postgres, а не мы.
     *
     * <p>Колонка объявлена {@code jsonb}, и база хранит не тот текст, что мы
     * записали, а свой: пробел после двоеточия, свой порядок ключей. Разбор
     * регуляркой по нашему же написанию не совпадал с этим ни разу, и повтор
     * загрузки отвечал «пропущено 0» при непустом списке — то есть терял
     * ровно тот перечень строк, ради которого повтор и смотрят. «Пропущено
     * 0» читается как «всё загрузилось», и битые строки владелец
     * не исправляет никогда.
     *
     * <p>Записываем по-прежнему руками: список короткий и плоский, а тянуть
     * сериализатор ради двух полей значит связать импортёр с представлением.
     * Читать так же нельзя — на чтении собеседник не мы, а база.
     */
    private List<Report.Skipped> skippedOf(String requestId) {
        return jdbc.query("""
                        SELECT (e->>'row')::int AS row_number, e->>'reason' AS reason
                          FROM import_run r
                         CROSS JOIN LATERAL jsonb_array_elements(r.skipped) e
                         WHERE r.client_request_id = ?
                           AND e->>'reason' IS NOT NULL""",
                (rs, i) -> new Report.Skipped(rs.getInt("row_number"), rs.getString("reason")),
                requestId);
    }

    /**
     * Пропущенные строки в JSON.
     *
     * <p>Руками, без Jackson: список короткий и плоский, а тянуть сюда
     * сериализатор ради двух полей значит связать импортёр с представлением.
     * Кавычки в причине заменяются — причины пишем мы сами, и кавычек
     * в них нет, но полагаться на это нельзя.
     *
     * <p>Число позиций, заведённых по одной штуке, дописывается в конец
     * отдельным элементом без {@code row} — почему здесь, а не своей
     * колонкой, сказано у {@link #quantityDefaultedOf}.
     */
    private static String skippedJson(Report report) {
        java.util.stream.Stream<String> skipped = report.skipped().stream()
                .map(s -> "{\"row\":%d,\"reason\":\"%s\"}"
                        .formatted(s.row(), s.reason().replace('"', '\'')));
        java.util.stream.Stream<String> defaulted = report.quantityDefaulted() == 0
                ? java.util.stream.Stream.empty()
                : java.util.stream.Stream.of(
                        "{\"quantityDefaulted\":%d}".formatted(report.quantityDefaulted()));
        return java.util.stream.Stream.concat(skipped, defaulted)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }


    private Row parse(ExcelSheetReader.Row row, ColumnMapping mapping, Tally report) {
        String name = mapping.value(row, Field.NAME).strip();
        if (name.isEmpty()) {
            report.skip(row.number(), "пустое наименование");
            return null;
        }

        if (mapping.has(Field.AVAILABILITY)) {
            // Дром пишет на карточке только «В наличии» — это и есть правда
            // о том, что позиция лежит. Иное значение называется дословно:
            // по нему видно, что писал Дром, и правило можно расширить.
            String availability = mapping.value(row, Field.AVAILABILITY).strip();
            if (!IN_STOCK.equals(availability)) {
                report.skip(row.number(), availability.isEmpty()
                        ? "не в наличии: значение пустое"
                        : "не в наличии: «" + availability + "»");
                return null;
            }
        }

        BigDecimal quantity;
        String rawQuantity = mapping.value(row, Field.QUANTITY);
        if (rawQuantity.isBlank()) {
            // Количества нет — ни колонки, ни значения в строке. У Дрома его
            // нет на странице вовсе (задача 0279), и пропуск здесь значил бы
            // «пропущено 2 897: нет количества» на весь перенос. Одна штука —
            // догадка, поэтому её число уходит в отчёт, а экран говорит
            // о ней до загрузки.
            quantity = BigDecimal.ONE;
            report.quantityDefaulted++;
        } else {
            quantity = number(rawQuantity);
            if (quantity == null || quantity.signum() <= 0) {
                // Ноль — это не остаток, а строка о том, что позиции нет.
                // Заводить карточку под неё значит наполнить склад несуществующим.
                // То же со словом вместо числа: «нет» в колонке количества
                // одной штукой не читается.
                report.skip(row.number(), "нет количества");
                return null;
            }
        }

        String adNumber = dromAdNumber(mapping.value(row, Field.DROM_AD_NUMBER));
        if (adNumber != null && !adNumber.matches("[0-9]+")) {
            // Число в Excel («1,26E+08») или слово вместо номера: заводить позицию
            // с номером объявления, который нельзя сверить с кабинетом, значит
            // пообещать связь, которой нет. Строку пропускаем и называем, что в ней.
            report.skip(row.number(), "номер объявления Дрома не из цифр: «"
                    + shortened(adNumber) + "»");
            return null;
        }

        return new Row(
                row.number(),
                name,
                number(mapping.value(row, Field.PRICE)),
                quantity,
                blankToNull(mapping.value(row, Field.CELL)),
                blankToNull(mapping.value(row, Field.OEM)),
                blankToNull(mapping.value(row, Field.NOTE)),
                photoLinks(mapping.value(row, Field.PHOTOS)),
                adNumber);
    }

    /**
     * Ссылки на снимки из ячейки — в том порядке, в каком они там стоят.
     *
     * <p><b>Порядок и есть смысл колонки.</b> Первая ссылка — обложка
     * объявления у клиента, и она обязана остаться главной у нас: перенос
     * отмечает главным снимок с нулевым порядком. Повтор одной ссылки
     * в ячейке оставляет её на первом месте, где она встретилась, а не
     * дырой в нумерации.
     *
     * <p>Делится по любому пробельному знаку, а не только по переводу
     * строки: в адресе пробела не бывает, а таблица, прошедшая через Excel,
     * приносит {@code \r\n} и пробелы по краям. Что не похоже на адрес,
     * не отбрасывается молча — уходит в очередь и отказывает там с причиной
     * словами, как любая битая ссылка: молча потерянный снимок не найдёт
     * никто.
     */
    static List<String> photoLinks(String cell) {
        if (cell == null || cell.isBlank()) {
            return List.of();
        }
        return List.copyOf(new java.util.LinkedHashSet<>(
                List.of(cell.strip().split("\\s+"))));
    }


    /**
     * Номер объявления из ячейки: края обрезаны, знак «№» перед числом
     * снят (на карточке Дрома он стоит именно так), пусто — {@code null}.
     * Пустая строка в базу не уйдёт никогда: колонка {@code NULL} либо цифры.
     */
    private static String dromAdNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.startsWith("№")) {
            value = value.substring(1).strip();
        }
        return value.isEmpty() ? null : value;
    }

    private static String shortened(String value) {
        return value.length() <= 30 ? value : value.substring(0, 30) + "…";
    }

    private void flush(List<Row> batch, long warehouseId, Map<String, Long> cells,
                       Map<String, PartName> names, Tally report) {
        for (Row row : batch) {
            // Объявление, которое у нас уже есть, второй позицией не заводится:
            // повтор файла переноса не должен удваивать склад клиента. Ответ
            // называет позицию публичным кодом — номер базы человеку ничего не даёт.
            // Проверка стоит до работы со справочником и ячейками, чтобы
            // пропущенная строка не оставляла за собой побочных заведений.
            if (row.dromAd() != null && skipIfAdTaken(row, report)) {
                continue;
            }
            Long cellId = row.cell() == null ? null
                    : cells.computeIfAbsent(row.cell().toUpperCase(),
                            code -> createCell(warehouseId, row.cell()));

            PartName partName = names.computeIfAbsent(
                    row.name().toLowerCase(), key -> partNames.resolve(row.name(), null));

            // ON CONFLICT — на одновременный повтор: проверка выше читает, а между
            // чтением и вставкой встанет вторая загрузка. Индекс дубль не пустит
            // в любом случае, но без этого человек получил бы «Операция нарушает
            // целостность данных» на весь файл вместо названной позиции.
            List<Long> inserted = jdbc.queryForList("""
                    INSERT INTO part (category_id, part_name_id, title, price, note,
                                      storage_cell_id, drom_ad_number)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (drom_ad_number) WHERE drom_ad_number IS NOT NULL
                    DO NOTHING
                    RETURNING id""",
                    Long.class, partName.getCategoryId(), partName.getId(),
                    row.name(), row.price(), row.note(), cellId, row.dromAd());
            if (inserted.isEmpty()) {
                skipIfAdTaken(row, report);
                continue;
            }
            Long partId = inserted.get(0);

            // Номер, от которого после приведения ничего не осталось, — не номер:
            // колонка normalized объявлена NOT NULL, и такая строка отвечает
            // отказом базы вместо загруженной позиции.
            String normalizedOem = ru.partsflow.catalog.OemNumbers.normalize(row.oem());
            if (normalizedOem != null) {
                jdbc.update("""
                        INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
                        VALUES (?, ?, ?, true)
                        ON CONFLICT DO NOTHING""",
                        partId, row.oem(), normalizedOem);
            }

            // Снимки только ставятся в очередь: качает их отдельный проход
            // (PhotoMigration), который можно прервать и продолжить. Внутри
            // запроса импорта это часы — у клиента с Дрома 2 897 карточек
            // по 14–16 снимков. Повтор той же ссылки той же позиции отбивает
            // part_photo_import_uk, а не проверка «нет ли уже такого».
            List<Object[]> photos = new ArrayList<>(row.photos().size());
            for (int order = 0; order < row.photos().size(); order++) {
                photos.add(new Object[] {partId, row.photos().get(order), order});
            }
            if (!photos.isEmpty()) {
                jdbc.batchUpdate("""
                        INSERT INTO part_photo_import (part_id, url, sort_order)
                        VALUES (?, ?, ?)
                        ON CONFLICT (part_id, url) DO NOTHING""", photos);
            }

            // Остаток появляется движением: писать qty_on_hand напрямую значит
            // разъехаться с part_stock на первой же продаже.
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta,
                                                to_warehouse_id, to_cell_id, reason)
                    VALUES (?, 'INTAKE', ?, ?, ?, 'Импорт из таблицы')""",
                    partId, row.quantity(), warehouseId, cellId);
            // Применяется это пачкой, после всех строк — см. recomputeAll
            // в конце importInto.

            report.imported++;
        }
        batch.clear();
    }

    /**
     * Занят ли номер объявления; если да — строка пропущена и названа.
     *
     * @return {@code true}, если позиция с этим номером уже есть
     */
    private boolean skipIfAdTaken(Row row, Tally report) {
        List<String> holder = jdbc.queryForList(
                "SELECT public_code FROM part WHERE drom_ad_number = ?",
                String.class, row.dromAd());
        if (holder.isEmpty()) {
            return false;
        }
        report.skip(row.number(), "объявление № " + row.dromAd()
                + " уже у позиции " + holder.get(0));
        return true;
    }

    private Long createCell(long warehouseId, String code) {
        return jdbc.queryForObject("""
                INSERT INTO storage_cell (warehouse_id, code) VALUES (?, ?)
                ON CONFLICT (warehouse_id, code) DO UPDATE SET code = excluded.code
                RETURNING id""", Long.class, warehouseId, code);
    }

    private Map<String, Long> existingCells(long warehouseId) {
        Map<String, Long> cells = new HashMap<>();
        jdbc.query("SELECT id, code FROM storage_cell WHERE warehouse_id = ?",
                rs -> {
                    cells.put(rs.getString("code").toUpperCase(), rs.getLong("id"));
                }, warehouseId);
        return cells;
    }

    /**
     * Число из ячейки таблицы.
     *
     * <p>Запятая как разделитель дробной части, пробелы между разрядами,
     * «₽» и «руб» в той же ячейке — обычное дело в таблицах, которые вели
     * руками. Не разобралось — считаем, что значения нет: подставлять ноль
     * значит завести деталь по нулевой цене и продать её даром.
     */
    static BigDecimal number(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replace(' ', ' ')
                .replaceAll("[^0-9,.-]", "")
                .replace(" ", "")
                .replace(',', '.');
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record Row(int number, String name, BigDecimal price, BigDecimal quantity,
                       String cell, String oem, String note, List<String> photos,
                       String dromAd) {
    }

    /**
     * Что распознали в файле. Показывается человеку до записи.
     *
     * @param detected только поля, которые импорт читает: узнанная, но
     *                 не читаемая колонка показывается «не переносится»
     * @param format   {@link #DROM_TRANSFER}, если это файл переноса с Дрома
     *                 (сопоставлен договором, а не догадкой); иначе {@code null}
     */
    public record Preview(List<String> header, Map<Field, Integer> detected,
                          List<Field> missingRequired, List<List<String>> rows,
                          String format) {

        public static final String DROM_TRANSFER = "DROM_TRANSFER";
    }

    /**
     * Итог импорта. Пропущенные строки — с номером, как их видно в Excel.
     *
     * <p>Именно record, а не класс с методами в стиле record: обычный класс
     * без getX() Jackson не сериализует, и ответ уходит пятисоткой
     * «No acceptable representation». Тесты этого не ловят — они зовут
     * импортёр напрямую, минуя HTTP.
     */
    public record Report(int imported, List<Skipped> skipped, int quantityDefaulted) {

        public record Skipped(int row, String reason) {
        }
    }

    /** Накопитель итога: во время разбора он меняется, наружу уходит record. */
    private static final class Tally {

        private int imported;
        /** Строк, заведённых по одной штуке, потому что количества в файле нет. */
        private int quantityDefaulted;
        private final List<Skipped> skipped = new ArrayList<>();

        void skip(int rowNumber, String reason) {
            // Список пропущенных не бесконечен: файл с неверным сопоставлением
            // даст пятьдесят тысяч одинаковых строк, и ответ раздуется.
            if (skipped.size() < 200) {
                skipped.add(new Skipped(rowNumber, reason));
            }
        }

        Report toReport() {
            return new Report(imported, List.copyOf(skipped), quantityDefaulted);
        }
    }
}
