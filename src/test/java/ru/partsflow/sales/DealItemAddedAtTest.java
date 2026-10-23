package ru.partsflow.sales;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Момент, когда позиция попала в сделку (задача 0367, решение 8 владельца
 * от 23.10.2026; {@code tenant/099}).
 *
 * <p><b>Раздача лежащим строкам проверяется проигрыванием по непустой схеме</b>
 * (приём {@code PartNumberTest}, {@code MemberNumberTest}): на пустой схеме
 * раздавать нечего, и {@code db/verify.sh} проходит при любом выражении.
 * Поэтому строки заводятся, колонка снимается вместе с отметкой о накате —
 * и changeset проигрывается заново.
 */
@SpringBootTest
class DealItemAddedAtTest extends PostgresTestBase {

    private static final String TENANT = "t_000591";

    private static final Instant DEAL_CREATED = Instant.parse("2026-10-01T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @Test
    @DisplayName("Лежащим строкам — вставка из журнала изменений, без записи — заведение сделки, не раньше сделки")
    void backfillTakesInsertFromJournalAndNeverPrecedesTheDeal() {
        long[] items = inTenant(() -> {
            long warehouse = warehouse();
            long part = part();
            long deal = jdbc.queryForObject("""
                    INSERT INTO deal (status, created_at) VALUES ('RESERVED', ?::timestamptz)
                    RETURNING id""", Long.class, DEAL_CREATED.toString());
            long journaled = item(deal, part, warehouse);
            long silent = item(deal, part, warehouse);
            long transferred = item(deal, part, warehouse);
            // Вставка через приложение оставляет запись INSERT; у второй её
            // нет вовсе (перенос из Bazon пишет мимо Hibernate); третья
            // вставлена в прежнюю сделку раньше заведения этой — так выглядит
            // перенос позиций в новую сделку.
            insertJournal(journaled, "2026-10-03T12:30:00Z");
            insertJournal(journaled, "2026-10-05T09:00:00Z"); // поздняя запись — не она
            updateJournal(silent, "2026-10-04T08:00:00Z");     // не вставка
            insertJournal(transferred, "2026-09-20T15:00:00Z");
            return new long[] {journaled, silent, transferred};
        });

        replayChangeset();

        assertThat(addedAt(items[0])).as("первая запись INSERT журнала")
                .isEqualTo(Instant.parse("2026-10-03T12:30:00Z"));
        assertThat(addedAt(items[1])).as("без записи вставки — заведение сделки, а не день наката")
                .isEqualTo(DEAL_CREATED);
        assertThat(addedAt(items[2])).as("попасть в сделку раньше её заведения нельзя")
                .isEqualTo(DEAL_CREATED);
    }

    @Test
    @DisplayName("Новая строка без колонки получает момент вставки, пустой отметки не бывает")
    void newRowGetsInsertMomentAndNullIsRejected() {
        // Момент берётся у часов базы, а не JVM: умолчание now() ставит
        // Postgres, и сверять его с Instant.now() значит сверять двое часов.
        // Задача CI «Часы вперёд» сдвигает часы JVM на 31 день, а база в
        // контейнере живёт по настоящим, — сравнение с Instant.now() там
        // краснело на исправной схеме.
        Instant before = inTenant(() -> jdbc.queryForObject(
                "SELECT now()", java.sql.Timestamp.class).toInstant());
        long item = inTenant(() -> {
            long deal = jdbc.queryForObject("""
                    INSERT INTO deal (status, created_at) VALUES ('RESERVED', ?::timestamptz)
                    RETURNING id""", Long.class, DEAL_CREATED.toString());
            // Колонку не называет ни прежний образ, ни нынешний DealItem —
            // момент даёт умолчание.
            return item(deal, part(), warehouse());
        });
        assertThat(addedAt(item)).as("момент вставки по часам базы, а не заведение сделки")
                .isAfterOrEqualTo(before)
                .isAfter(DEAL_CREATED);

        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "UPDATE deal_item SET added_at = NULL WHERE id = ?", item)))
                .as("NOT NULL после раздачи").hasMessageContaining("added_at");
    }

    // ---------- вспомогательное ----------

    private long warehouse() {
        Long branch = jdbc.queryForObject(
                "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
        return jdbc.queryForObject(
                "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                Long.class, branch);
    }

    private long part() {
        return jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price) VALUES (1, 'Фара для отметки строки', 1000)
                RETURNING id""", Long.class);
    }

    private long item(long deal, long part, long warehouse) {
        return jdbc.queryForObject("""
                INSERT INTO deal_item (deal_id, part_id, quantity, price, status, warehouse_id)
                VALUES (?, ?, 1, 4500, 'RESERVED', ?) RETURNING id""",
                Long.class, deal, part, warehouse);
    }

    private void insertJournal(long item, String at) {
        journal(item, "INSERT", at);
    }

    private void updateJournal(long item, String at) {
        journal(item, "UPDATE", at);
    }

    private void journal(long item, String operation, String at) {
        jdbc.update("""
                INSERT INTO audit_log (table_name, record_id, operation, changed_at)
                VALUES ('deal_item', ?, ?, ?::timestamptz)""", item, operation, at);
    }

    private Instant addedAt(long item) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT added_at FROM deal_item WHERE id = ?", java.sql.Timestamp.class, item)
                .toInstant());
    }

    private void replayChangeset() {
        inTenant(() -> {
            jdbc.execute("ALTER TABLE deal_item DROP COLUMN added_at");
            jdbc.update("DELETE FROM " + TENANT + ".databasechangelog WHERE id = ?",
                    "tenant-099-deal-item-added-at");
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
