package ru.partsflow.migration.excel;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Потоковое чтение листа xlsx.
 *
 * <p><b>Через SAX, а не через {@code XSSFWorkbook}.</b> Объектная модель POI
 * поднимает файл в память целиком: выгрузка склада на пятьдесят тысяч позиций
 * с описаниями — это гигабайты heap, и приложение уходит в своп на одном
 * клиенте. Та же причина, по которой фиды площадок пишутся через StAX.
 *
 * <p><b>Значения читаются как текст, форматированный как в таблице.</b>
 * {@link DataFormatter} отдаёт то, что видит человек: артикул
 * {@code 0123456} не превращается в {@code 123456}, а дата остаётся датой,
 * а не числом 45292. Разбирать типы — работа импортёра, который знает,
 * какая колонка чем является.
 *
 * <p><b>Пропущенные ячейки не сдвигают строку.</b> POI при пустых ячейках
 * не вызывает обработчик, и наивная сборка списка сместила бы все значения
 * влево — цена оказалась бы в колонке количества. Позиция берётся из адреса
 * ячейки.
 */
public final class ExcelSheetReader {

    private ExcelSheetReader() {
    }

    /** Что делать с каждой строкой. Возврат {@code false} прекращает чтение. */
    public interface RowHandler extends Predicate<Row> {
    }

    /**
     * @param number номер строки в файле, с единицы — как его видит человек
     *               в Excel; в сообщениях об ошибках он и нужен
     */
    public record Row(int number, List<String> cells) {

        public String at(int index) {
            return index >= 0 && index < cells.size() ? cells.get(index) : "";
        }

        public boolean isEmpty() {
            return cells.stream().allMatch(c -> c == null || c.isBlank());
        }
    }

    /** Читает первый лист книги. */
    public static void read(InputStream in, RowHandler handler) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(in)) {
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();

            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            if (!sheets.hasNext()) {
                throw new IllegalArgumentException("В книге нет ни одного листа");
            }
            try (InputStream sheet = sheets.next()) {
                XMLReader parser = org.apache.poi.util.XMLHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(
                        styles, strings, new Collector(handler), new DataFormatter(), false));
                parser.parse(new InputSource(sheet));
            } catch (StopReading stop) {
                // Обработчик сказал «хватит»: предпросмотр читает первые строки
                // и не должен тянуть весь файл.
            }
        }
    }

    /** Собирает ячейки строки, держа их на своих местах. */
    private static final class Collector implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final RowHandler handler;
        private final List<String> cells = new ArrayList<>();
        private int rowNumber;

        Collector(RowHandler handler) {
            this.handler = handler;
        }

        @Override
        public void startRow(int rowIndex) {
            cells.clear();
            rowNumber = rowIndex + 1;
        }

        @Override
        public void cell(String reference, String formattedValue, org.apache.poi.xssf.usermodel.XSSFComment comment) {
            int column = columnOf(reference);
            // Дырки заполняем пустыми: иначе значения съезжают влево, и цена
            // приезжает в колонку количества.
            while (cells.size() < column) {
                cells.add("");
            }
            cells.add(formattedValue == null ? "" : formattedValue.trim());
        }

        @Override
        public void endRow(int rowIndex) {
            if (!handler.test(new Row(rowNumber, List.copyOf(cells)))) {
                throw new StopReading();
            }
        }

        /** {@code BC12} → 54. Буквенная часть адреса — это число в 26-ричной. */
        private static int columnOf(String reference) {
            int column = 0;
            for (int i = 0; i < reference.length(); i++) {
                char c = reference.charAt(i);
                if (c < 'A' || c > 'Z') {
                    break;
                }
                column = column * 26 + (c - 'A' + 1);
            }
            return column - 1;
        }
    }

    /** Прекращение чтения по требованию обработчика, а не ошибка. */
    private static final class StopReading extends RuntimeException {

        StopReading() {
            super(null, null, false, false);
        }
    }
}
