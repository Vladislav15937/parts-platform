package ru.partsflow.migration.bazon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BazonCsvReaderTest {

    /** Собирает файл в той же кодировке, в которой приходит настоящая выгрузка. */
    private static BazonCsvReader readerOf(String content) {
        return new BazonCsvReader(new ByteArrayInputStream(content.getBytes(BazonCsvReader.CHARSET)));
    }

    private static List<BazonCsvReader.Row> rowsOf(String content) {
        List<BazonCsvReader.Row> rows = new ArrayList<>();
        try (BazonCsvReader reader = readerOf(content)) {
            reader.forEachRow(rows::add, (line, values) -> {
                throw new AssertionError("неожиданно битая строка " + line + ": " + values);
            });
        }
        return rows;
    }

    @Test
    @DisplayName("Заголовок и значения по имени колонки")
    void readsHeaderAndValues() {
        var rows = rowsOf("""
                "Номер товара";"Запчасть";"Цена"
                "112322";"Блок подрулевых переключателей";"2100"
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("Номер товара")).isEqualTo("112322");
        assertThat(rows.get(0).get("Запчасть")).isEqualTo("Блок подрулевых переключателей");
        assertThat(rows.get(0).get("Цена")).isEqualTo("2100");
    }

    @Test
    @DisplayName("Перевод строки внутри поля не разрывает запись")
    void handlesNewlineInsideField() {
        // Именно так выглядит описание товара в настоящей выгрузке: 35 841
        // запись занимает 44 509 физических строк.
        var rows = rowsOf("""
                "Номер товара";"Комментарий";"Цена"
                "112322";"Контракт.
                Без пробега по РФ";"2100"
                "112321";"Обычный";"1400"
                """);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("Комментарий")).isEqualTo("Контракт.\nБез пробега по РФ");
        assertThat(rows.get(0).get("Цена")).isEqualTo("2100");
        // Следующая запись не съехала — самая частая поломка такого разбора.
        assertThat(rows.get(1).get("Номер товара")).isEqualTo("112321");
    }

    @Test
    @DisplayName("Точка с запятой внутри кавычек — часть значения, а не разделитель")
    void handlesDelimiterInsideQuotes() {
        var rows = rowsOf("""
                "Номер";"Заметка"
                "1";"снять фару; проверить крепление"
                """);

        assertThat(rows.get(0).get("Заметка")).isEqualTo("снять фару; проверить крепление");
    }

    @Test
    @DisplayName("Удвоенная кавычка — это одна кавычка в значении")
    void handlesEscapedQuotes() {
        var rows = rowsOf("""
                "Номер";"Заметка"
                "1";"деталь ""как новая"" по словам продавца"
                """);

        assertThat(rows.get(0).get("Заметка")).isEqualTo("деталь \"как новая\" по словам продавца");
    }

    @Test
    @DisplayName("Пустое значение отдаётся как null, а не как пустая строка")
    void blankIsNull() {
        var rows = rowsOf("""
                "Номер";"Цвет"
                "1";""
                """);

        assertThat(rows.get(0).get("Цвет")).isNull();
    }

    @Test
    @DisplayName("Неизвестная колонка — null, а не исключение: состав выгрузки у клиентов разный")
    void unknownColumnIsNull() {
        var rows = rowsOf("""
                "Номер"
                "1"
                """);

        assertThat(rows.get(0).get("Такой колонки нет")).isNull();
        assertThat(rows.get(0).hasColumn("Номер")).isTrue();
    }

    @Test
    @DisplayName("Битая строка не пропускается молча, а уходит обработчику")
    void reportsMalformedRows() {
        List<Long> bad = new ArrayList<>();
        List<BazonCsvReader.Row> good = new ArrayList<>();

        try (BazonCsvReader reader = readerOf("""
                "Номер";"Цена"
                "1";"100"
                "2";"200";"лишняя колонка"
                "3";"300"
                """)) {
            reader.forEachRow(good::add, (line, values) -> bad.add(line));
        }

        assertThat(good).hasSize(2);
        assertThat(bad).containsExactly(3L);
    }

    @Test
    @DisplayName("CRLF не попадает в значения")
    void stripsCarriageReturn() {
        var rows = rowsOf("\"Номер\";\"Цена\"\r\n\"1\";\"100\"\r\n");

        assertThat(rows.get(0).get("Цена")).isEqualTo("100");
    }

    @Test
    @DisplayName("Номер строки сохраняется — без него ошибку импорта не найти в файле")
    void keepsLineNumber() {
        var rows = rowsOf("""
                "Номер"
                "1"
                "2"
                """);

        assertThat(rows.get(0).lineNumber()).isEqualTo(2);
        assertThat(rows.get(1).lineNumber()).isEqualTo(3);
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> readerOf(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пуста");
    }
}
