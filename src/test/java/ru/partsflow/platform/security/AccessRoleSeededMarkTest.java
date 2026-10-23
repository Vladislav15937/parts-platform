package ru.partsflow.platform.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Метка «роль переноса заводилась» (задача 0044, {@code tenant/102}).
 *
 * <p>Ответ владельца 24.10.2026 (tasks/0044, «после разбора PR 506, 508»,
 * пункт 1б): удалённый клиентом «Ревизор» — отказ, журнал сотруднику
 * {@code AUDITOR} открыт, только пока роль не заводили. По {@code access_role}
 * эти два случая не различить — строки нет в обоих; различает
 * {@code access_role_seeded}.
 *
 * <p><b>Заполнение проверяется проигрыванием по непустой схеме</b> (приём
 * {@code AccessRolesSeedTest} и {@code MemberNumberTest}): на пустой схеме
 * ставить метку некому, и {@code db/verify.sh} проходит при любом
 * {@code INSERT}. Changeset'ы снимаются своими же откатами вместе с отметкой
 * о накате и проигрываются заново тем путём, что провижининг.
 *
 * <p>{@link #RULE} — правило чтения, которое код обязан повторить; держится
 * оно здесь затем, чтобы схема и правило проверялись одним запросом.
 */
@SpringBootTest
class AccessRoleSeededMarkTest extends PostgresTestBase {

    /** Ревизор был на накате 101 — «Ревизор» заведён. */
    private static final String SEEDED = "t_000617";

    /** Ревизора на накате 101 не было, сотрудника завели после. */
    private static final String LATER = "t_000618";

    /**
     * Правило чтения для роли переноса {@code ?}: есть роль с этим
     * {@code seeded_from} — «есть»; нет роли, есть метка — клиент удалил;
     * нет ни того, ни другого — не заводили.
     */
    static final String RULE = """
            SELECT CASE
                     WHEN EXISTS (SELECT 1 FROM access_role WHERE seeded_from = ?) THEN 'есть'
                     WHEN EXISTS (SELECT 1 FROM access_role_seeded WHERE seeded_from = ?) THEN 'удалена'
                     ELSE 'не заводили'
                   END""";

    private static final OffsetDateTime SEED_DAY =
            OffsetDateTime.of(2026, 10, 23, 18, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(SEEDED, LATER);
    }

    @Test
    @DisplayName("Заведённый и удалённый клиентом «Ревизор» — «удалена», а не «не заводили»; удаление по-прежнему проходит")
    void deletedAuditorRoleIsTold() {
        inTenant(SEEDED, () -> {
            jdbc.update("DELETE FROM tenant_member");
            member("vladelec", "OWNER");
            member("revizor", "AUDITOR");
            return null;
        });
        replaySeedAndMark(SEEDED);

        assertThat(state(SEEDED, "AUDITOR")).as("«Ревизор» заведён накатом 101").isEqualTo("есть");
        assertThat(marks(SEEDED)).as("метка у всех шести ролей переноса")
                .containsExactlyInAnyOrder("OWNER", "MANAGER", "STOREKEEPER", "SELLER", "VIEWER", "AUDITOR");

        // Так удаляет роль клиент: назначенным снимают роль, роль удаляется
        // обычным DELETE. Метка на это не отвечает ничем — ни запретом,
        // ни исчезновением.
        int deleted = inTenant(SEEDED, () -> {
            jdbc.update("""
                    UPDATE tenant_member SET access_role_id = NULL
                     WHERE access_role_id = (SELECT id FROM access_role WHERE seeded_from = 'AUDITOR')""");
            return jdbc.update("DELETE FROM access_role WHERE seeded_from = 'AUDITOR'");
        });
        assertThat(deleted).as("удалить роль можно, как и до метки").isEqualTo(1);

        assertThat(state(SEEDED, "AUDITOR")).as("удалённая клиентом роль — отказ, не «не заводили»")
                .isEqualTo("удалена");
    }

    @Test
    @DisplayName("Сотрудник AUDITOR, заведённый после 101 там, где «Ревизора» не было, — «не заводили»")
    void auditorAddedAfterSeedIsNeverCreated() {
        inTenant(LATER, () -> {
            jdbc.update("DELETE FROM tenant_member");
            member("vladelec", "OWNER");
            member("prodavec", "SELLER");
            return null;
        });
        replaySeedAndMark(LATER);

        // Так ревизора заводит образ из main: пишет только колонку role.
        inTenant(LATER, () -> member("revizor", "AUDITOR"));

        assertThat(state(LATER, "AUDITOR")).as("роль не заводили — журнал открыт").isEqualTo("не заводили");

        // И то же после повторного наката самой метки по схеме, где ревизор
        // уже есть, а роли нет: заполнение ставит метку по ролям, а не по
        // сотрудникам.
        replayMark(LATER);
        assertThat(state(LATER, "AUDITOR")).as("заполнение 102 метку ревизору без роли не ставит")
                .isEqualTo("не заводили");
        assertThat(marks(LATER)).as("метка у пяти ролей, заведённых всем")
                .containsExactlyInAnyOrder("OWNER", "MANAGER", "STOREKEEPER", "SELLER", "VIEWER");
    }

    @Test
    @DisplayName("Момент метки — когда роль завёл перенос, а не день наката 102")
    void markKeepsSeedMoment() {
        inTenant(SEEDED, () -> {
            jdbc.update("UPDATE access_role SET created_at = ? WHERE seeded_from IS NOT NULL", SEED_DAY);
            return null;
        });
        replayMark(SEEDED);

        List<OffsetDateTime> moments = inTenant(SEEDED, () -> jdbc.queryForList(
                "SELECT seeded_at FROM access_role_seeded", OffsetDateTime.class));
        assertThat(moments).isNotEmpty()
                .allSatisfy(at -> assertThat(at.toInstant()).as("seeded_at = access_role.created_at")
                        .isEqualTo(SEED_DAY.toInstant()));
    }

    // ---------- вспомогательное ----------

    private String state(String schema, String seededFrom) {
        return inTenant(schema, () -> jdbc.queryForObject(RULE, String.class, seededFrom, seededFrom));
    }

    private List<String> marks(String schema) {
        return inTenant(schema, () -> jdbc.queryForList(
                "SELECT seeded_from FROM access_role_seeded", String.class));
    }

    private Void member(String login, String role) {
        jdbc.update("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, 'не-пароль')""", login, role, login);
        return null;
    }

    /**
     * Снимает {@code tenant/102} и {@code tenant/101} их откатами в порядке
     * Liquibase (сначала младший) и накатывает оба заново.
     */
    private void replaySeedAndMark(String schema) {
        inTenant(schema, () -> {
            jdbc.execute("DROP TABLE access_role_seeded");
            jdbc.update("""
                    UPDATE tenant_member SET access_role_id = NULL
                    WHERE access_role_id IN (SELECT id FROM access_role WHERE seeded_from IS NOT NULL)""");
            jdbc.update("DELETE FROM access_role WHERE seeded_from IS NOT NULL");
            jdbc.update("DELETE FROM " + schema + ".databasechangelog WHERE id IN (?, ?)",
                    "tenant-101-access-roles-seed", "tenant-102-access-role-seeded-mark");
            return null;
        });
        provision(schema);
    }

    /** Снимает только {@code tenant/102} его откатом и накатывает заново. */
    private void replayMark(String schema) {
        inTenant(schema, () -> {
            jdbc.execute("DROP TABLE access_role_seeded");
            jdbc.update("DELETE FROM " + schema + ".databasechangelog WHERE id = ?",
                    "tenant-102-access-role-seeded-mark");
            return null;
        });
        provision(schema);
    }

    private static void provision(String schema) {
        // Константой, а не переменной: сторож схем (tools/test-schema-guard.py)
        // видит хозяина только по имени.
        if (SEEDED.equals(schema)) {
            provisionTenants(SEEDED);
        } else {
            provisionTenants(LATER);
        }
    }

    private <T> T inTenant(String schema, Supplier<T> body) {
        TenantContext.set(schema);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
