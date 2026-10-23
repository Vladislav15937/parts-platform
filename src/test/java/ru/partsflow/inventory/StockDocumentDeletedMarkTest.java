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

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Отметка «Удалён» у проведённого складского документа (задача 0367,
 * решения 1–5 владельца от 23.10.2026; {@code tenant/098}).
 *
 * <p>Форма данных, а не поведение: удаление ещё не пишет никто. Проверяется,
 * что момент и автор заполняются только вместе, что отметка не трогает
 * статус (документ остаётся «Выполнен») и что путь «Отменить проводку»
 * (0360, выполненный → черновик) прежними ограничениями не закрыт.
 */
@SpringBootTest
class StockDocumentDeletedMarkTest extends PostgresTestBase {

    private static final String TENANT = "t_000592";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @Test
    @DisplayName("Удалённый документ остаётся выполненным, с моментом и автором")
    void deletedDocumentStaysDoneWithAuthor() {
        long member = inTenant(this::member);
        long doc = inTenant(this::doneDocument);

        inTenant(() -> jdbc.update(
                "UPDATE stock_document SET deleted_at = now(), deleted_by = ? WHERE id = ?",
                member, doc));

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM stock_document WHERE id = ?", String.class, doc)))
                .as("отметка не меняет статус: «Выполнен (Удалён)»").isEqualTo("DONE");
    }

    @Test
    @DisplayName("Момент без автора и автор без момента отбиваются")
    void momentAndAuthorGoTogether() {
        long member = inTenant(this::member);
        long doc = inTenant(this::doneDocument);

        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "UPDATE stock_document SET deleted_at = now() WHERE id = ?", doc)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("stock_document_deleted_ck");
        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "UPDATE stock_document SET deleted_by = ? WHERE id = ?", member, doc)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("stock_document_deleted_ck");
    }

    @Test
    @DisplayName("«Отменить проводку» (0360) ложится в прежние ограничения без нового changeset'а")
    void unpostingStaysOpen() {
        long doc = inTenant(this::doneDocument);

        inTenant(() -> jdbc.update(
                "UPDATE stock_document SET status = 'DRAFT', completed_at = NULL WHERE id = ?", doc));

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM stock_document WHERE id = ?", String.class, doc)))
                .isEqualTo("DRAFT");
    }

    // ---------- вспомогательное ----------

    private long member() {
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role) VALUES ('Владелец склада', 'OWNER')
                RETURNING id""", Long.class);
    }

    private long doneDocument() {
        Long branch = jdbc.queryForObject(
                "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
        Long warehouse = jdbc.queryForObject(
                "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                Long.class, branch);
        return jdbc.queryForObject("""
                INSERT INTO stock_document (doc_type, status, warehouse_id, completed_at)
                VALUES ('INTAKE', 'DONE', ?, now()) RETURNING id""", Long.class, warehouse);
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
