package ru.partsflow.migration.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Склейка листа «Продолжение» (задача 0281) — на настоящем xlsx, записанном
 * так же, как пишет его парсер, и прочитанном тем же {@link ExcelSheetReader},
 * что у импорта. Через файл, а не на строках в памяти: обрезанные края ячейки
 * теряются именно при чтении, и склейка на списке строк этого не увидела бы.
 *
 * <p>Сквозная проверка через {@code /api/import/excel} — в
 * {@code DromContinuationImportTest}: там значение доезжает до базы.
 */
class DromContinuationTest {

    /** Предел ячейки Excel и размер части — как в парсере ({@code DromShopWorkbook}). */
    static final int CELL_MAX = 32_767;
    static final int PART_MAX = 32_000;

    private static final String AD = "114388280";

    /**
     * Разрез парсера, переписанный дословно с {@code DromShopWorkbook.split}
     * (app-drom-price-parser, ветка {@code drom-migration-export}, коммит
     * 9402e36): режет по последнему переводу строки внутри куска и оставляет
     * его в конце куска, иначе по пределу, не разрывая суррогатную пару.
     * Склейка обязана быть его обратной — поэтому образец режется им, а не
     * своим представлением о стыке.
     */
    static List<String> split(String v, int first, int rest) {
        List<String> out = new ArrayList<>();
        int pos = 0;
        int limit = first;
        while (v.length() - pos > limit) {
            int end = pos + limit;
            int nl = v.lastIndexOf('\n', end - 1);
            if (nl >= pos) {
                end = nl + 1;
            } else if (Character.isHighSurrogate(v.charAt(end - 1))) {
                end--;
            }
            out.add(v.substring(pos, end));
            pos = end;
            limit = rest;
        }
        out.add(v.substring(pos));
        return out;
    }

    /**
     * Применимость на 1 000 строк марок: больше двух пределов, то есть три
     * куска — ячейка и две части. Строки разной длины и пустая посередине,
     * чтобы стык пришёлся на перевод строки, а не на ровный шаг.
     */
    static String longApplicability() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            if (i > 0) {
                value.append('\n');
            }
            if (i == 500) {
                value.append('\n'); // пустая строка у клиента — «\n\n», её не терять
            }
            value.append("Toyota Corolla | ").append(i % 12 + 1).append(" поколение (")
                    .append(1990 + i % 30).append("–").append(1995 + i % 30)
                    .append(") | AE").append(100 + i).append(", ZZE").append(120 + i)
                    .append(" ".repeat(i % 7)).append("Седан, хэтчбек, универсал ").append(i);
        }
        return value.toString();
    }

    /** Значение без единого перевода строки: парсер режет его по пределу, посреди слова. */
    static String longOneLine() {
        StringBuilder value = new StringBuilder();
        int i = 0;
        while (value.length() < 70_000) {
            value.append("слово").append(i++).append(' ');
        }
        value.append("конец");
        return value.toString();
    }

    @Test
    @DisplayName("Значение длиннее 32 767 знаков, разрезанное парсером на три куска, склеивается побайтно")
    void longValueIsStitchedExactly() throws Exception {
        String applicability = longApplicability();
        String oneLine = longOneLine();
        List<String> a = split(applicability, CELL_MAX, PART_MAX);
        List<String> d = split(oneLine, CELL_MAX, PART_MAX);
        assertThat(applicability.length()).isGreaterThan(CELL_MAX + PART_MAX);
        assertThat(a).as("образец обязан резаться на ячейку и две части или больше").hasSizeGreaterThan(2);
        assertThat(d).hasSizeGreaterThan(2);
        assertThat(a.get(0)).as("стык по переводу строки — он в конце куска").endsWith("\n");

        // Части на листе в обратном порядке: склейка идёт по номеру, а не по листу.
        List<String[]> parts = new ArrayList<>();
        parts.addAll(partsOf(AD, "Описание", d));
        parts.addAll(partsOf(AD, "Применимость", a));
        Collections.reverse(parts);

        byte[] file = workbook(List.of(
                row(AD, "Фара", a.get(0), d.get(0)),
                row("999", "Фара короткая", "Toyota", "коротко")), parts);

        Read read = read(file);
        assertThat(read.problems).as("исправный лист назван повреждённым").isEmpty();
        ExcelSheetReader.Row first = read.rows.get(0);
        assertThat(first.rawAt(2)).as("применимость склеена не побайтно").isEqualTo(applicability);
        assertThat(first.at(2)).isEqualTo(applicability);
        assertThat(first.rawAt(3)).as("строка без переводов склеена не побайтно").isEqualTo(oneLine);
        assertThat(read.rows.get(1).at(2)).as("строку без частей склейка тронула").isEqualTo("Toyota");
    }

    @Test
    @DisplayName("Часть без строки на первом листе — названа номером объявления, колонкой и листом")
    void orphanPartIsNamed() throws Exception {
        byte[] file = workbook(List.of(row(AD, "Фара", "начало\n", "")),
                List.<String[]>of(new String[] {"555", "Применимость", "1", "хвост"}));

        Read read = read(file);

        assertThat(read.rows.get(0).rawAt(2)).isEqualTo("начало\n");
        assertThat(read.problems).extracting(DromContinuation.Problem::reason).containsExactly(
                "Лист «Продолжение»: часть для объявления № 555 (колонка «Применимость») "
                        + "не имеет строки на листе «Объявления»; пропущена");
        assertThat(read.problems.get(0).row()).as("строка — на листе «Продолжение»").isEqualTo(2);
    }

    @Test
    @DisplayName("Разрыв нумерации — значение до разрыва, и сказано, какой части нет")
    void gapIsNamedAndValueStopsBeforeIt() throws Exception {
        byte[] file = workbook(List.of(row(AD, "Фара", "голова\n", "")), List.of(
                new String[] {AD, "Применимость", "1", "первая\n"},
                new String[] {AD, "Применимость", "3", "третья"}));

        Read read = read(file);

        assertThat(read.rows.get(0).rawAt(2)).isEqualTo("голова\nпервая\n");
        assertThat(read.problems).extracting(DromContinuation.Problem::reason).containsExactly(
                "Лист «Продолжение»: у объявления № " + AD + " колонка «Применимость» — "
                        + "нет части 2 из 3; значение взято до разрыва");
    }

    @Test
    @DisplayName("Колонка, которой нет на первом листе, названа, а не проглочена")
    void unknownColumnIsNamed() throws Exception {
        byte[] file = workbook(List.of(row(AD, "Фара", "", "")),
                List.<String[]>of(new String[] {AD, "Совместимость", "1", "хвост"}));

        Read read = read(file);

        assertThat(read.problems).extracting(DromContinuation.Problem::reason).containsExactly(
                "Лист «Продолжение»: у объявления № " + AD + " колонки «Совместимость» "
                        + "на листе «Объявления» нет; часть пропущена");
    }

    @Test
    @DisplayName("Повтор номера части и номер не числом — названы, склеено остальное")
    void duplicateAndBadNumbersAreNamed() throws Exception {
        byte[] file = workbook(List.of(row(AD, "Фара", "а\n", "")), List.of(
                new String[] {AD, "Применимость", "1", "б\n"},
                new String[] {AD, "Применимость", "1", "лишнее"},
                new String[] {AD, "Применимость", "вторая", "лишнее"},
                new String[] {AD, "Применимость", "2", "в"}));

        Read read = read(file);

        assertThat(read.rows.get(0).rawAt(2)).isEqualTo("а\nб\nв");
        assertThat(read.problems).extracting(DromContinuation.Problem::reason).containsExactlyInAnyOrder(
                "Лист «Продолжение»: у объявления № " + AD + " колонка «Применимость» — "
                        + "часть «вторая» не номер; пропущена",
                "Лист «Продолжение»: у объявления № " + AD + " колонка «Применимость» — "
                        + "часть 1 повторяется; взята первая");
    }

    @Test
    @DisplayName("Нет листа, пустой лист, лист без заголовка — файл читается как раньше, молча")
    void missingEmptyOrHeaderlessSheetIsIgnored() throws Exception {
        List<List<String>> main = List.of(row(AD, "Фара", "начало\n", ""));

        Read none = read(workbook(main, null));
        Read empty = read(workbookWith(main, sheet -> { }));
        Read headerless = read(workbookWith(main, sheet -> {
            Row r = sheet.createRow(0);
            String[] values = {AD, "Применимость", "1", "хвост"};
            for (int i = 0; i < values.length; i++) {
                r.createCell(i).setCellValue(values[i]);
            }
        }));

        for (Read read : List.of(none, empty, headerless)) {
            assertThat(read.problems).isEmpty();
            assertThat(read.rows.get(0).rawAt(2)).isEqualTo("начало\n");
            assertThat(read.rows.get(0).at(2)).as("края по-прежнему обрезаны").isEqualTo("начало");
        }
    }

    // --- файл, как его пишет парсер ---

    private static List<String> row(String ad, String name, String applicability, String description) {
        return List.of(ad, name, applicability, description);
    }

    static List<String[]> partsOf(String ad, String column, List<String> chunks) {
        List<String[]> rows = new ArrayList<>();
        for (int k = 1; k < chunks.size(); k++) {
            rows.add(new String[] {ad, column, String.valueOf(k), chunks.get(k)});
        }
        return rows;
    }

    private static byte[] workbook(List<List<String>> main, List<String[]> parts) throws Exception {
        if (parts == null) {
            return workbookWith(main, null);
        }
        return workbookWith(main, sheet -> {
            Row header = sheet.createRow(0);
            for (int i = 0; i < DromContinuation.HEADER.size(); i++) {
                header.createCell(i).setCellValue(DromContinuation.HEADER.get(i));
            }
            int r = 1;
            for (String[] part : parts) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < part.length; i++) {
                    row.createCell(i).setCellValue(part[i]);
                }
            }
        });
    }

    private static byte[] workbookWith(List<List<String>> main,
                                       java.util.function.Consumer<Sheet> continuation)
            throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle text = book.createCellStyle();
            text.setWrapText(true);
            text.setDataFormat(book.createDataFormat().getFormat("@"));
            Sheet sheet = book.createSheet("Объявления");
            List<String> header = List.of(DromTransferFile.ANCHOR, "Наименование", "Применимость", "Описание");
            Row h = sheet.createRow(0);
            for (int i = 0; i < header.size(); i++) {
                h.createCell(i).setCellValue(header.get(i));
            }
            for (int r = 0; r < main.size(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < main.get(r).size(); c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellStyle(text);
                    cell.setCellValue(main.get(r).get(c));
                }
            }
            if (continuation != null) {
                continuation.accept(book.createSheet(DromContinuation.SHEET));
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    // --- чтение, как у импорта ---

    private record Read(List<ExcelSheetReader.Row> rows, List<DromContinuation.Problem> problems) {
    }

    private static Read read(byte[] file) throws Exception {
        DromContinuation continuation = new DromContinuation();
        List<ExcelSheetReader.Row> rows = new ArrayList<>();
        boolean[] header = {false};
        String sheet = ExcelSheetReader.read(new ByteArrayInputStream(file), DromContinuation.SHEET,
                side -> {
                    continuation.accept(side);
                    return true;
                },
                row -> {
                    if (!header[0]) {
                        header[0] = true;
                        continuation.header(row.cells());
                        return true;
                    }
                    rows.add(continuation.stitch(row));
                    return true;
                });
        assertThat(sheet).isEqualTo("Объявления");
        return new Read(rows, continuation.finish(sheet));
    }
}
