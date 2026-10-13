package ru.partsflow.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Слова журнала для стороны «обе» зависят от оси (задача 0283). */
class AuditedColumnsSidesTest {

    @Test
    @DisplayName("BOTH в журнале называется парой своей оси, а не «левый и правый» на все три")
    void bothIsNamedByAxis() {
        assertThat(AuditedColumns.display("side_lr", "BOTH", Map.of())).isEqualTo("Левый и правый");
        assertThat(AuditedColumns.display("side_fr", "BOTH", Map.of())).isEqualTo("Передний и задний");
        assertThat(AuditedColumns.display("side_ud", "BOTH", Map.of())).isEqualTo("Верхний и нижний");
        assertThat(AuditedColumns.display("side_fr", "REAR", Map.of())).isEqualTo("Задний");
    }
}
