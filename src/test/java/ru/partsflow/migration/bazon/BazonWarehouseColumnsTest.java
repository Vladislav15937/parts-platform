package ru.partsflow.migration.bazon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BazonWarehouseColumnsTest {

    /** Заголовок настоящей выгрузки: два склада по три величины. */
    private static final List<String> REAL_HEADER = List.of(
            "Номер товара", "Запчасть", "Цена", "Секция",
            "Ткацкая (свободно)", "Ткацкая (резерв)", "Ткацкая (ожидается)",
            "54 YARD (свободно)", "54 YARD (резерв)", "54 YARD (ожидается)");

    @Test
    @DisplayName("Склады находятся по заголовку и в порядке появления")
    void discoversWarehouses() {
        var warehouses = BazonWarehouseColumns.discover(REAL_HEADER);

        assertThat(warehouses).extracting(BazonWarehouseColumns.Warehouse::name)
                .containsExactly("Ткацкая", "54 YARD");
    }

    @Test
    @DisplayName("Обычные колонки со скобками не принимаются за склад")
    void ignoresUnrelatedColumns() {
        var warehouses = BazonWarehouseColumns.discover(
                List.of("Цвет", "Габариты (упаковка)", "Оценка состояния"));

        assertThat(warehouses).isEmpty();
    }

    @Test
    @DisplayName("Склад с неполной тройкой колонок не теряется: недостающее — ноль")
    void keepsWarehouseWithMissingColumns() {
        var warehouses = BazonWarehouseColumns.discover(
                List.of("Номер", "Основной (свободно)"));

        assertThat(warehouses).hasSize(1);
        var w = warehouses.get(0);
        assertThat(w.freeColumn()).isEqualTo("Основной (свободно)");
        assertThat(w.reservedColumn()).isNull();
        assertThat(w.expectedColumn()).isNull();
    }

    @Test
    @DisplayName("Три величины читаются из строки по отдельности")
    void readsThreeQuantities() {
        var row = firstRow("""
                "Номер товара";"Ткацкая (свободно)";"Ткацкая (резерв)";"Ткацкая (ожидается)"
                "112322";"2";"1";"5"
                """);
        var w = BazonWarehouseColumns.discover(
                List.of("Номер товара", "Ткацкая (свободно)", "Ткацкая (резерв)", "Ткацкая (ожидается)")).get(0);

        assertThat(w.free(row)).isEqualByComparingTo("2");
        assertThat(w.reserved(row)).isEqualByComparingTo("1");
        assertThat(w.expected(row)).isEqualByComparingTo("5");
        assertThat(w.hasAnything(row)).isTrue();
    }

    @Test
    @DisplayName("Пустой склад распознаётся: заводить его для этой позиции незачем")
    void detectsEmptyWarehouse() {
        var header = List.of("Номер товара", "54 YARD (свободно)", "54 YARD (резерв)", "54 YARD (ожидается)");
        var row = firstRow("""
                "Номер товара";"54 YARD (свободно)";"54 YARD (резерв)";"54 YARD (ожидается)"
                "112322";"0";"0";"0"
                """);

        assertThat(BazonWarehouseColumns.discover(header).get(0).hasAnything(row)).isFalse();
    }

    @Test
    @DisplayName("Отсутствующая колонка читается как ноль, а не падает")
    void missingColumnReadsAsZero() {
        var header = List.of("Номер товара", "Основной (свободно)");
        var row = firstRow("""
                "Номер товара";"Основной (свободно)"
                "1";"3"
                """);
        var w = BazonWarehouseColumns.discover(header).get(0);

        assertThat(w.free(row)).isEqualByComparingTo("3");
        assertThat(w.expected(row)).isEqualByComparingTo("0");
    }

    private static BazonCsvReader.Row firstRow(String content) {
        List<BazonCsvReader.Row> rows = new ArrayList<>();
        try (var reader = new BazonCsvReader(
                new ByteArrayInputStream(content.getBytes(BazonCsvReader.CHARSET)))) {
            reader.forEachRow(rows::add, (line, values) -> {
            });
        }
        return rows.get(0);
    }
}
