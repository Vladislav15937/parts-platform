package ru.partsflow.migration.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.migration.excel.ColumnMapping.Field;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Перенос склада из таблицы клиента.
 *
 * <p>Проверяется то, что ломает переход клиента молча: распознавание колонок
 * с чужими заголовками, разбор чисел, записанных руками, и запись остатка
 * журналом, а не полем.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class ExcelImportTest extends PostgresTestBase {

    private static final String TENANT = "t_000069";

    @Autowired
    private ExcelWarehouseImporter importer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Колонки узнаются по чужим заголовкам")
    void detectsForeignHeaders() {
        // Заголовки взяты из того, как их пишут в живых таблицах.
        ColumnMapping mapping = ColumnMapping.detect(List.of(
                "№", "Название", "Кол-во", "Цена, руб", "Место хранения", "Комментарий"));

        assertThat(mapping.indexOf(Field.NAME)).isEqualTo(1);
        assertThat(mapping.indexOf(Field.QUANTITY)).isEqualTo(2);
        assertThat(mapping.indexOf(Field.PRICE)).isEqualTo(3);
        assertThat(mapping.indexOf(Field.CELL)).isEqualTo(4);
        assertThat(mapping.missingRequired()).isEmpty();
    }

    @Test
    @DisplayName("Точное совпадение выигрывает у похожего")
    void exactHeaderWins() {
        ColumnMapping mapping = ColumnMapping.detect(List.of("Цена закупки", "Цена", "Кол-во"));

        // «Цена закупки» — не цена продажи, и подставить её значит продавать
        // склад по себестоимости.
        assertThat(mapping.indexOf(Field.PRICE)).isEqualTo(1);
    }

    @Test
    @DisplayName("Слово внутри другого слова колонкой не считается")
    void substringIsNotAMatch() {
        // «Штрихкод» содержит «шт», «Городской» содержит «год». Подстрочное
        // совпадение уложило бы в количество штрихкоды.
        ColumnMapping mapping = ColumnMapping.detect(
                List.of("Наименование", "Штрихкод", "Городской округ"));

        assertThat(mapping.has(Field.QUANTITY)).isFalse();
        assertThat(mapping.has(Field.YEAR)).isFalse();
        assertThat(mapping.missingRequired()).containsExactly(Field.QUANTITY);
    }

    @Test
    @DisplayName("Числа, записанные руками, разбираются")
    void parsesHandWrittenNumbers() {
        assertThat(ExcelWarehouseImporter.number("12 500,50"))
                .isEqualByComparingTo("12500.50");
        assertThat(ExcelWarehouseImporter.number("3 000 ₽")).isEqualByComparingTo("3000");
        assertThat(ExcelWarehouseImporter.number("1500 руб.")).isEqualByComparingTo("1500");
        // Не разобралось — значит значения нет. Ноль вместо этого означал бы
        // деталь по нулевой цене, то есть отданную даром.
        assertThat(ExcelWarehouseImporter.number("договорная")).isNull();
        assertThat(ExcelWarehouseImporter.number("")).isNull();
        assertThat(ExcelWarehouseImporter.number("—")).isNull();
    }

    @Test
    @DisplayName("Пропущенная ячейка не сдвигает строку")
    void gapsDoNotShiftValues() throws Exception {
        byte[] book = workbook(
                List.of("Наименование", "Цена", "Кол-во"),
                // У второй строки цена пустая: POI не вызывает обработчик
                // на пустой ячейке, и наивная сборка положила бы количество
                // в колонку цены.
                List.of(List.of("Фара левая", "5000", "1"),
                        List.of("Бампер", "", "2")));

        ExcelWarehouseImporter.Preview preview =
                importer.preview(new ByteArrayInputStream(book));

        assertThat(preview.rows().get(1)).containsExactly("Бампер", "", "2");
    }

    @Test
    @DisplayName("Склад заливается движениями, остаток считает триггер")
    void importWritesMovements() throws Exception {
        byte[] book = workbook(
                List.of("Наименование", "Цена", "Кол-во", "Ячейка"),
                List.of(List.of("Фара левая Camry", "5000", "2", "А-01-1"),
                        List.of("Бампер передний", "8000", "1", "А-01-2")));

        ExcelWarehouseImporter.Report report = inTenant(() -> {
            try {
                return importer.importInto(new ByteArrayInputStream(book),
                        ColumnMapping.of(List.of(), Map.of(
                                Field.NAME, 0, Field.PRICE, 1,
                                Field.QUANTITY, 2, Field.CELL, 3)),
                        warehouse, java.util.UUID.randomUUID().toString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(report.imported()).isEqualTo(2);

        // Остаток — агрегат журнала. Проставь импорт qty_on_hand напрямую,
        // он разъехался бы с part_stock на первой же продаже.
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT sum(qty) FROM part_stock WHERE warehouse_id = ?""",
                BigDecimal.class, warehouse))).isEqualByComparingTo("3");

        // Счёт по своему складу: тесты делят схему, а журнал движений
        // неизменяем — чистить его между ними нечем.
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM stock_movement
                 WHERE movement_type = 'INTAKE' AND to_warehouse_id = ?""",
                Integer.class, warehouse))).isEqualTo(2);
    }

    @Test
    @DisplayName("Номер детали доезжает до склада, а слово вместо номера не валит строку")
    void oemColumnIsStored() throws Exception {
        byte[] book = workbook(
                List.of("Наименование", "Цена", "Кол-во", "Артикул"),
                List.of(List.of("Стартер Corolla", "4000", "1", "28100-0D030"),
                        List.of("Фара правая Corolla", "6000", "1", "б/н")));

        // Колонка с номером была написана в чужие имена — part_oem (number,
        // is_original) при колонках raw_number и is_primary. То есть импорт
        // с номером не работал никогда, а «б/н» валило бы строку и после
        // починки имён: приведение такого номера даёт пустоту, а колонка
        // объявлена NOT NULL.
        assertThat(inTenant(() -> load(book, Map.of(Field.NAME, 0, Field.PRICE, 1,
                Field.QUANTITY, 2, Field.OEM, 3))).imported()).isEqualTo(2);

        assertThat(inTenant(() -> jdbc.queryForList("""
                SELECT normalized FROM part_oem o JOIN part p ON p.id = o.part_id
                 WHERE p.title LIKE '%Corolla%'""", String.class)))
                .containsExactly("281000D030");
    }

    @Test
    @DisplayName("Повтор с тем же ключом не заводит второй склад")
    void repeatWithSameKeyDoesNotDouble() throws Exception {
        byte[] book = workbook(
                List.of("Наименование", "Цена", "Кол-во"),
                List.of(List.of("Фара левая", "9500", "2"),
                        List.of("Бампер", "14000", "1")));
        String key = java.util.UUID.randomUUID().toString();

        ExcelWarehouseImporter.Report first = inTenant(() -> load(book, key,
                Map.of(Field.NAME, 0, Field.PRICE, 1, Field.QUANTITY, 2)));

        // Именно так это и случилось вживую: запись прошла, ответ упал
        // на сериализации, владелец увидел ошибку и нажал ещё раз.
        ExcelWarehouseImporter.Report again = inTenant(() -> load(book, key,
                Map.of(Field.NAME, 0, Field.PRICE, 1, Field.QUANTITY, 2)));

        assertThat(first.imported()).isEqualTo(2);
        assertThat(again.imported())
                .as("повтор отдал другой итог — значит импортировал заново")
                .isEqualTo(2);

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT COALESCE(sum(qty), 0) FROM part_stock WHERE warehouse_id = ?",
                BigDecimal.class, warehouse)))
                .as("склад удвоился: у повтора не сработал ключ")
                .isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("Другой ключ — другая загрузка")
    void differentKeyImportsAgain() throws Exception {
        byte[] book = workbook(
                List.of("Наименование", "Кол-во"), List.of(List.of("Капот", "1")));

        inTenant(() -> load(book, java.util.UUID.randomUUID().toString(),
                Map.of(Field.NAME, 0, Field.QUANTITY, 1)));
        inTenant(() -> load(book, java.util.UUID.randomUUID().toString(),
                Map.of(Field.NAME, 0, Field.QUANTITY, 1)));

        // Тот же файл, загруженный намеренно дважды, — это две партии.
        // Отличать их от повтора нечем, кроме ключа, и решает клиент.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT COALESCE(sum(qty), 0) FROM part_stock WHERE warehouse_id = ?",
                BigDecimal.class, warehouse))).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("Ячейки из таблицы заводятся, а существующие переиспользуются")
    void cellsAreCreatedOnce() throws Exception {
        inTenant(() -> jdbc.update(
                "INSERT INTO storage_cell (warehouse_id, code) VALUES (?, 'А-01-1')", warehouse));

        byte[] book = workbook(
                List.of("Наименование", "Кол-во", "Ячейка"),
                List.of(List.of("Фара", "1", "А-01-1"),
                        List.of("Бампер", "1", "А-01-1"),
                        List.of("Дверь", "1", "Б-02-2")));

        inTenant(() -> load(book, Map.of(Field.NAME, 0, Field.QUANTITY, 1, Field.CELL, 2)));

        // Две новых ячейки из трёх строк было бы дублем существующей.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM storage_cell WHERE warehouse_id = ?",
                Integer.class, warehouse))).isEqualTo(2);
    }

    @Test
    @DisplayName("Строки без количества пропускаются с указанием номера")
    void rowsWithoutQuantityAreSkipped() throws Exception {
        byte[] book = workbook(
                List.of("Наименование", "Кол-во"),
                List.of(List.of("Фара", "1"),
                        List.of("Бампер", "0"),
                        List.of("", "5"),
                        List.of("Дверь", "нет")));

        ExcelWarehouseImporter.Report report =
                inTenant(() -> load(book, Map.of(Field.NAME, 0, Field.QUANTITY, 1)));

        assertThat(report.imported()).isEqualTo(1);
        // Номера строк — как их видит человек в Excel: искать он будет там.
        assertThat(report.skipped()).extracting(
                        ExcelWarehouseImporter.Report.Skipped::row)
                .containsExactly(3, 4, 5);
    }

    @Test
    @DisplayName("Наименования попадают в тот же справочник, что и у приёмки")
    void namesGoThroughTheSameDictionary() throws Exception {
        byte[] book = workbook(
                List.of("Наименование", "Кол-во"),
                List.of(List.of("Фара левая", "1"),
                        List.of("Фара левая", "1"),
                        List.of("Бампер", "1")));

        inTenant(() -> load(book, Map.of(Field.NAME, 0, Field.QUANTITY, 1)));

        // Два одинаковых названия дают одну запись справочника: иначе экран
        // нераспознанных завалит повторами одного и того же.
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM part_name WHERE name IN ('Фара левая', 'Бампер')""",
                Integer.class))).isEqualTo(2);
    }

    @Test
    @DisplayName("Импорт без обязательных колонок не начинается")
    void refusesWithoutRequiredColumns() {
        byte[] book;
        try {
            book = workbook(List.of("Наименование"), List.of(List.of("Фара")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThatThrownBy(() -> inTenant(() -> load(book, Map.of(Field.NAME, 0))))
                .hasMessageContaining("QUANTITY");
    }

    private ExcelWarehouseImporter.Report load(byte[] book, Map<Field, Integer> columns) {
        return load(book, java.util.UUID.randomUUID().toString(), columns);
    }

    private ExcelWarehouseImporter.Report load(byte[] book, String key,
                                               Map<Field, Integer> columns) {
        try {
            return importer.importInto(new ByteArrayInputStream(book),
                    ColumnMapping.of(List.of(), columns), warehouse, key);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Собирает настоящий xlsx: разбор формата и есть то, что проверяется. */
    private static byte[] workbook(List<String> header, List<List<String>> rows) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = book.createSheet("Склад");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < header.size(); i++) {
                headerRow.createCell(i).setCellValue(header.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    String value = values.get(c);
                    // Пустое значение оставляем несозданной ячейкой — именно так
                    // выглядит дырка в настоящей таблице.
                    if (!value.isEmpty()) {
                        Cell cell = row.createCell(c);
                        cell.setCellValue(value);
                    }
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }

    private void inTenant(Runnable body) {
        inTenant(() -> {
            body.run();
            return null;
        });
    }
}
