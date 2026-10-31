package ru.partsflow.intake;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Номер донора и список статусов машины (задача 0364, вторая часть;
 * {@code tenant/108}, {@code tenant/110}).
 *
 * <p><b>Раздачу уже лежащим машинам чистая база не судит</b> (db/CLAUDE.md,
 * запись про {@code tenant/064}): на пустой схеме раздавать нечего,
 * и {@code db/verify.sh} проходит при любом порядке. Поэтому машины
 * заводятся, всё, что завёл changeset, снимается вместе с отметкой о накате,
 * и он проигрывается заново — приём {@code MemberNumberTest}.
 */
@SpringBootTest
class DonorSchemaBackfillTest extends PostgresTestBase {

    private static final String REPLAY = "t_003640";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(REPLAY);
    }

    @Test
    @DisplayName("Перенесённым — номер Bazon, своим — следующие за наибольшим, новая машина продолжает ряд")
    void numberKeepsBazonAndContinuesAfterMax() {
        List<Long> ids = inTenant(() -> {
            jdbc.update("DELETE FROM donor");
            return List.of(
                    donor("511", "PURCHASED"),   // номер Bazon
                    donor(null, "PURCHASED"),    // своя
                    donor(" 077", "PURCHASED"),  // номер Bazon с нулём и пробелом
                    donor("77", "PURCHASED"),    // то же число — номер уже занят
                    donor("A12", "PURCHASED"),   // не число
                    donor(null, "PURCHASED"),    // своя
                    donor("0", "PURCHASED"));    // ноль номером не бывает
        });

        replay("tenant-108-donor-number", """
                DROP INDEX donor_number_uk;
                ALTER TABLE donor DROP CONSTRAINT donor_number_ck;
                ALTER TABLE donor DROP COLUMN number;
                DROP SEQUENCE donor_number_seq""");

        List<Long> numbers = inTenant(() -> jdbc.queryForList(
                "SELECT number FROM donor WHERE id = ANY (?) ORDER BY id",
                Long.class, (Object) ids.toArray(Long[]::new)));
        assertThat(numbers)
                .as("511 и 77 — номера Bazon; остальные по порядку заведения за наибольшим")
                .containsExactly(511L, 512L, 77L, 513L, 514L, 515L, 516L);

        long next = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO donor (brand_id) VALUES (?) RETURNING number", Long.class, brand()));
        assertThat(next).as("последовательность подвинута за наибольший").isEqualTo(517L);

        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "INSERT INTO donor (brand_id, number) VALUES (?, 511)", brand())))
                .as("номер уникален в организации")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("Прежний перечень — первые значения списка, у машины выбрано то, что стояло")
    void statusListStartsWithFormerStages() {
        List<Long> ids = inTenant(() -> {
            jdbc.update("DELETE FROM donor");
            return List.of(
                    donor(null, "WRITTEN_OFF"),
                    donor(null, "PURCHASED"),
                    donor(null, "DISMANTLED"),
                    donor(null, "DISMANTLING"));
        });

        replay("tenant-110-donor-status-list", """
                ALTER TABLE donor DROP COLUMN status_id;
                DROP TABLE donor_status""");

        assertThat(inTenant(() -> jdbc.queryForList(
                "SELECT name FROM donor_status ORDER BY sort_order", String.class)))
                .containsExactly("Куплена", "В разборе", "Разобрана", "Списана");

        assertThat(inTenant(() -> jdbc.queryForList("""
                SELECT s.name FROM donor d JOIN donor_status s ON s.id = d.status_id
                 WHERE d.id = ANY (?) ORDER BY d.id""",
                String.class, (Object) ids.toArray(Long[]::new))))
                .as("данные не теряются: статус машины переехал в список")
                .containsExactly("Списана", "Куплена", "Разобрана", "В разборе");

        assertThat(inTenant(() -> jdbc.queryForList(
                "SELECT status FROM donor WHERE id = ANY (?) ORDER BY id",
                String.class, (Object) ids.toArray(Long[]::new))))
                .as("состояние приёмки не тронуто")
                .containsExactly("WRITTEN_OFF", "PURCHASED", "DISMANTLED", "DISMANTLING");

        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "INSERT INTO donor_status (name) VALUES ('  разобрана ')")))
                .as("имя уникально без учёта регистра и пробелов по краям")
                .isInstanceOf(DuplicateKeyException.class);

        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "DELETE FROM donor_status WHERE name = 'Куплена'")))
                .as("назначенный машине статус база удалить не даёт")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- вспомогательное ----------

    private long brand() {
        return jdbc.queryForObject("SELECT min(id) FROM catalog.brand", Long.class);
    }

    private long donor(String legacyCode, String status) {
        return jdbc.queryForObject("""
                INSERT INTO donor (brand_id, legacy_code, status) VALUES (?, ?, ?) RETURNING id""",
                Long.class, brand(), legacyCode, status);
    }

    private void replay(String changesetId, String undo) {
        inTenant(() -> {
            for (String statement : undo.split(";")) {
                jdbc.execute(statement);
            }
            jdbc.update("DELETE FROM " + REPLAY + ".databasechangelog WHERE id = ?", changesetId);
            return null;
        });
        provisionTenants(REPLAY);
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(REPLAY);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
