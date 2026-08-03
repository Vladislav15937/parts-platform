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
        return new Preview(header, mapping.fields(), mapping.missingRequired(), rows);
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
                result.imported(), skippedJson(result.skipped()), requestId);
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
        List<Report> found = jdbc.query(
                "SELECT imported, skipped FROM import_run WHERE client_request_id = ?",
                (rs, i) -> new Report(rs.getInt("imported"), parseSkipped(rs.getString("skipped"))),
                requestId);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Пропущенные строки в JSON.
     *
     * <p>Руками, без Jackson: список короткий и плоский, а тянуть сюда
     * сериализатор ради двух полей значит связать импортёр с представлением.
     * Кавычки в причине заменяются — причины пишем мы сами, и кавычек
     * в них нет, но полагаться на это нельзя.
     */
    private static String skippedJson(List<Report.Skipped> skipped) {
        return skipped.stream()
                .map(s -> "{\"row\":%d,\"reason\":\"%s\"}"
                        .formatted(s.row(), s.reason().replace('"', '\'')))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static List<Report.Skipped> parseSkipped(String json) {
        List<Report.Skipped> skipped = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\{\"row\":(\\d+),\"reason\":\"([^\"]*)\"}")
                .matcher(json == null ? "" : json);
        while (matcher.find()) {
            skipped.add(new Report.Skipped(
                    Integer.parseInt(matcher.group(1)), matcher.group(2)));
        }
        return skipped;
    }

    private Row parse(ExcelSheetReader.Row row, ColumnMapping mapping, Tally report) {
        String name = mapping.value(row, Field.NAME).strip();
        if (name.isEmpty()) {
            report.skip(row.number(), "пустое наименование");
            return null;
        }

        BigDecimal quantity = number(mapping.value(row, Field.QUANTITY));
        if (quantity == null || quantity.signum() <= 0) {
            // Ноль — это не остаток, а строка о том, что позиции нет.
            // Заводить карточку под неё значит наполнить склад несуществующим.
            report.skip(row.number(), "нет количества");
            return null;
        }

        return new Row(
                name,
                number(mapping.value(row, Field.PRICE)),
                quantity,
                blankToNull(mapping.value(row, Field.CELL)),
                blankToNull(mapping.value(row, Field.OEM)),
                blankToNull(mapping.value(row, Field.NOTE)));
    }

    private void flush(List<Row> batch, long warehouseId, Map<String, Long> cells,
                       Map<String, PartName> names, Tally report) {
        for (Row row : batch) {
            Long cellId = row.cell() == null ? null
                    : cells.computeIfAbsent(row.cell().toUpperCase(),
                            code -> createCell(warehouseId, row.cell()));

            PartName partName = names.computeIfAbsent(
                    row.name().toLowerCase(), key -> partNames.resolve(row.name(), null));

            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, part_name_id, title, price, note,
                                      storage_cell_id)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                    Long.class, partName.getCategoryId(), partName.getId(),
                    row.name(), row.price(), row.note(), cellId);

            if (row.oem() != null) {
                jdbc.update("""
                        INSERT INTO part_oem (part_id, number, normalized, is_original)
                        VALUES (?, ?, catalog.normalize_oem(?), false)
                        ON CONFLICT DO NOTHING""", partId, row.oem(), row.oem());
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

    private record Row(String name, BigDecimal price, BigDecimal quantity,
                       String cell, String oem, String note) {
    }

    /** Что распознали в файле. Показывается человеку до записи. */
    public record Preview(List<String> header, Map<Field, Integer> detected,
                          List<Field> missingRequired, List<List<String>> rows) {
    }

    /**
     * Итог импорта. Пропущенные строки — с номером, как их видно в Excel.
     *
     * <p>Именно record, а не класс с методами в стиле record: обычный класс
     * без getX() Jackson не сериализует, и ответ уходит пятисоткой
     * «No acceptable representation». Тесты этого не ловят — они зовут
     * импортёр напрямую, минуя HTTP.
     */
    public record Report(int imported, List<Skipped> skipped) {

        public record Skipped(int row, String reason) {
        }
    }

    /** Накопитель итога: во время разбора он меняется, наружу уходит record. */
    private static final class Tally {

        private int imported;
        private final List<Skipped> skipped = new ArrayList<>();

        void skip(int rowNumber, String reason) {
            // Список пропущенных не бесконечен: файл с неверным сопоставлением
            // даст пятьдесят тысяч одинаковых строк, и ответ раздуется.
            if (skipped.size() < 200) {
                skipped.add(new Skipped(rowNumber, reason));
            }
        }

        Report toReport() {
            return new Report(imported, List.copyOf(skipped));
        }
    }
}
