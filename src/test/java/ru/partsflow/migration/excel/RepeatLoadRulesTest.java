package ru.partsflow.migration.excel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.migration.excel.ColumnMapping.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сторож новых колонок для повторной загрузки (задача 0282, пункт 9).
 *
 * <p>Каждое поле, которое импорт читает, обязано иметь одно из двух: правило
 * дозаполнения ({@link ExcelWarehouseImporter#REFILLED}) либо запись «не
 * дозаполняется, потому что …» ({@link ExcelWarehouseImporter#NOT_REFILLED}).
 * Иначе следующая задача (0276, 0278, …) научит импорт колонке, а повтор файла
 * будет молча её пропускать у уже перенесённых позиций — ровно тот пробел,
 * ради которого задача заведена. Что правило из списка действительно работает,
 * доказывает перебор {@code DromRepeatLoadTest}.
 */
class RepeatLoadRulesTest {

    @Test
    @DisplayName("Каждое читаемое поле либо дозаполняется повтором, либо названо, почему нет")
    void everyReadFieldHasARepeatRule() {
        for (Field field : Field.values()) {
            boolean refilled = ExcelWarehouseImporter.REFILLED.containsKey(field);
            boolean explained = ExcelWarehouseImporter.NOT_REFILLED.containsKey(field);
            if (field.imported()) {
                assertThat(refilled || explained)
                        .as("импорт читает «%s», а повтор файла не знает, что с ним делать: "
                                + "добавьте правило в REFILLED или причину в NOT_REFILLED", field)
                        .isTrue();
                assertThat(refilled && explained)
                        .as("«%s» и дозаполняется, и названо недозаполняемым", field)
                        .isFalse();
            } else {
                assertThat(refilled || explained)
                        .as("«%s» импорт не читает — правило повтора про него лишнее", field)
                        .isFalse();
            }
        }
        ExcelWarehouseImporter.NOT_REFILLED.forEach((field, reason) ->
                assertThat(reason).as("причина у «%s» пустая", field).isNotBlank());
    }
}
