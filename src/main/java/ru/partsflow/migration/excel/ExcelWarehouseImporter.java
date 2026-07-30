package ru.partsflow.migration.excel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.catalog.PartName;
import ru.partsflow.catalog.PartNameService;
import ru.partsflow.migration.excel.ColumnMapping.Field;

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

    public ExcelWarehouseImporter(JdbcTemplate jdbc, PartNameService partNames) {
        this.jdbc = jdbc;
        this.partNames = partNames;
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
     * @param warehouseId куда класть остаток. Один на всю таблицу: колонка
     *                    склада в чужих выгрузках встречается редко, а
     *                    раскладывать по несуществующим складам нечем
     */
    @Transactional
    public Report importInto(InputStream in, ColumnMapping mapping, long warehouseId)
            throws Exception {

        List<Field> missing = mapping.missingRequired();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Не сопоставлены обязательные колонки: " + missing);
        }

        Map<String, Long> cells = existingCells(warehouseId);
        // Наименования кэшируются: строк пятьдесят тысяч, а различных названий
        // среди них — тысячи. Ходить в справочник на каждую строку значит
        // потратить импорт на повторные запросы об одном и том же.
        Map<String, PartName> names = new HashMap<>();
        Report report = new Report();
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

        log.info("Импорт из таблицы: заведено {} позиций, пропущено {}",
                report.imported, report.skipped.size());
        return report;
    }

    private Row parse(ExcelSheetReader.Row row, ColumnMapping mapping, Report report) {
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
                       Map<String, PartName> names, Report report) {
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

    /** Итог импорта. Пропущенные строки — с номером, как их видно в Excel. */
    public static final class Report {

        private int imported;
        private final List<Skipped> skipped = new ArrayList<>();

        void skip(int rowNumber, String reason) {
            // Список пропущенных не бесконечен: файл с неверным сопоставлением
            // даст пятьдесят тысяч одинаковых строк, и ответ раздуется.
            if (skipped.size() < 200) {
                skipped.add(new Skipped(rowNumber, reason));
            }
        }

        public int imported() {
            return imported;
        }

        public List<Skipped> skipped() {
            return List.copyOf(skipped);
        }

        public record Skipped(int row, String reason) {
        }
    }
}
