package ru.partsflow.inventory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Накладные расходы в строке складского документа — схема под 0361
 * ({@code tenant/112}, задача 0386).
 *
 * <p>Кода, читающего колонку, ещё нет (это 0361), поэтому здесь стережётся
 * форма данных и то, что получат уже лежащие строки. Второе на чистой базе
 * не проверить: строки документа заводятся, колонка и отметка о накате
 * снимаются, и changeset проигрывается заново (приём {@code PartNumberTest},
 * {@code WheelWearPercentTest}).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class StockDocumentLineOverheadSchemaTest extends PostgresTestBase {

    private static final String TENANT = "t_000586";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @Test
    @DisplayName("Строки, лежавшие до наката, получают накладные 0; новые без колонки — тоже 0")
    void existingAndOldStyleLinesGetZero() {
        Long document = document();
        Long first = line(document, part("Фара левая"));
        Long second = line(document, part("Фара правая"));

        replayChangeset();

        Map<String, Object> column = inTenant(() -> jdbc.queryForMap("""
                SELECT data_type, numeric_precision, numeric_scale, is_nullable, column_default
                  FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND table_name = 'stock_document_line' AND column_name = 'overhead'"""));
        assertThat(column).as("колонка stock_document_line.overhead")
                .containsEntry("data_type", "numeric")
                .containsEntry("numeric_precision", 14)
                .containsEntry("numeric_scale", 2)
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", "0");

        List<BigDecimal> lying = inTenant(() -> jdbc.queryForList(
                "SELECT overhead FROM stock_document_line WHERE id IN (?, ?) ORDER BY id",
                BigDecimal.class, first, second));
        assertThat(lying).as("накладные у строк, лежавших до наката")
                .containsExactly(new BigDecimal("0.00"), new BigDecimal("0.00"));

        // Прежний образ колонки не знает и вставляет строку без неё.
        Long oldStyle = line(document, part("Капот"));
        assertThat(overheadOf(oldStyle)).as("вставка прежним образом")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Накладные — сумма на строку; отрицательные и пустые отбиваются схемой")
    void negativeAndNullAreRejected() {
        Long document = document();
        Long id = line(document, part("Дверь"));

        inTenant(() -> jdbc.update("UPDATE stock_document_line SET overhead = 100 WHERE id = ?", id));
        assertThat(overheadOf(id)).isEqualByComparingTo("100");

        // Умолчание задачи при неотвеченном вопросе владельцу: отрицательных нет.
        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "UPDATE stock_document_line SET overhead = -1 WHERE id = ?", id)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("stock_document_line_overhead_ck");
        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "UPDATE stock_document_line SET overhead = NULL WHERE id = ?", id)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long document() {
        return inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал накладных') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад накладных') RETURNING id",
                    Long.class, branch);
            return jdbc.queryForObject("""
                    INSERT INTO stock_document (doc_type, warehouse_id)
                    VALUES ('INTAKE', ?) RETURNING id""", Long.class, warehouse);
        });
    }

    private Long part(String title) {
        return inTenant(() -> jdbc.queryForObject(
                "INSERT INTO part (category_id, title, price) VALUES (1, ?, 1000) RETURNING id",
                Long.class, title));
    }

    /** Вставка так, как её делает прежний образ: без колонки накладных. */
    private Long line(Long document, Long part) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO stock_document_line (document_id, part_id, qty, price)
                VALUES (?, ?, 2, 1750) RETURNING id""", Long.class, document, part));
    }

    private BigDecimal overheadOf(Long line) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT overhead FROM stock_document_line WHERE id = ?", BigDecimal.class, line));
    }

    private void replayChangeset() {
        inTenant(() -> {
            jdbc.execute("ALTER TABLE stock_document_line DROP COLUMN overhead");
            jdbc.update("DELETE FROM " + TENANT + ".databasechangelog WHERE id = ?",
                    "tenant-112-stock-document-line-overhead");
            return null;
        });
        provisionTenants(TENANT);
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
