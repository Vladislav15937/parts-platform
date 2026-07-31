package ru.partsflow.migration.bazon;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Склады, вытащенные из заголовка выгрузки.
 *
 * <p>Имена складов заранее неизвестны и у каждого клиента свои: в заголовке
 * они появляются как {@code Ткацкая (свободно)}, {@code Ткацкая (резерв)},
 * {@code Ткацкая (ожидается)}. Захардкодить их нельзя, а угадать по позиции
 * колонки — тем более: складов бывает и один, и пять, и они переставляются.
 *
 * <p>Три величины на склад — не прихоть формата. «Свободно» можно продать
 * прямо сейчас, «резерв» обещан другому клиенту, «ожидается» ещё едет
 * контейнером. Для продавца это три разных ответа в трубку, и схлопывать
 * их в одно число нельзя.
 */
public final class BazonWarehouseColumns {

    private static final Pattern COLUMN = Pattern.compile(
            "^(?<warehouse>.+?)\\s*\\((?<kind>свободно|резерв|ожидается)\\)$");

    private BazonWarehouseColumns() {
    }

    /**
     * Разбирает заголовок и возвращает склады в порядке появления.
     *
     * <p>Склад попадает в результат, даже если у него нашлась не вся тройка
     * колонок: недостающие величины считаются нулевыми. Отбрасывать склад
     * целиком из-за отсутствия «ожидается» значило бы потерять его остаток.
     */
    public static List<Warehouse> discover(List<String> header) {
        Map<String, String[]> byWarehouse = new LinkedHashMap<>();

        for (String column : header) {
            Matcher m = COLUMN.matcher(column.trim());
            if (!m.matches()) {
                continue;
            }
            String name = m.group("warehouse").trim();
            String[] columns = byWarehouse.computeIfAbsent(name, k -> new String[3]);
            switch (m.group("kind")) {
                case "свободно" -> columns[0] = column;
                case "резерв" -> columns[1] = column;
                case "ожидается" -> columns[2] = column;
                default -> {
                }
            }
        }

        List<Warehouse> result = new ArrayList<>();
        byWarehouse.forEach((name, c) -> result.add(new Warehouse(name, c[0], c[1], c[2])));
        return List.copyOf(result);
    }

    /**
     * @param freeColumn     колонка «свободно», может отсутствовать
     * @param reservedColumn колонка «резерв», может отсутствовать
     * @param expectedColumn колонка «ожидается», может отсутствовать
     */
    public record Warehouse(String name, String freeColumn,
                            String reservedColumn, String expectedColumn) {

        public BigDecimal free(BazonCsvReader.Row row) {
            return amount(row, freeColumn);
        }

        public BigDecimal reserved(BazonCsvReader.Row row) {
            return amount(row, reservedColumn);
        }

        public BigDecimal expected(BazonCsvReader.Row row) {
            return amount(row, expectedColumn);
        }

        /** Есть ли на этом складе хоть что-то: пустые склады не создаём. */
        public boolean hasAnything(BazonCsvReader.Row row) {
            return free(row).signum() != 0
                    || reserved(row).signum() != 0
                    || expected(row).signum() != 0;
        }

        private static BigDecimal amount(BazonCsvReader.Row row, String column) {
            if (column == null) {
                return BigDecimal.ZERO;
            }
            BigDecimal value = BazonValueParser.parseAmount(row.get(column));
            return value == null ? BigDecimal.ZERO : value;
        }
    }
}
