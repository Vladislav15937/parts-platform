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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Деление права {@code members.manage} на четыре (задача 0044, {@code tenant/103}).
 *
 * <p>Ответ владельца 24.10.2026 (tasks/0044, «развёрнутые вопросы», пункт 2а):
 * у кого право было — тот получает все четыре. Ключи — контракт с кодом:
 * {@code members.accounts}, {@code members.assign_roles}, {@code members.roles},
 * {@code members.grant_overrides}.
 *
 * <p><b>Раздача проверяется проигрыванием по непустой схеме</b> (приём
 * {@code AccessRolesSeedTest}): на пустой схеме делить нечего, и
 * {@code db/verify.sh} проходит при любом {@code INSERT}. Откат проигрывается
 * строками {@code --rollback} самого changeset'а, прочитанными из classpath, —
 * тем же текстом, что выполнит Liquibase, а не пересказом.
 */
@SpringBootTest
class MembersManageSplitTest extends PostgresTestBase {

    /** Прямой ход и его повтор. */
    private static final String FORWARD = "t_000619";

    /** Откат и накат обратно. */
    private static final String ROLLBACK = "t_000620";

    private static final String CHANGESET = "tenant-103-members-manage-split";
    private static final String FILE = "db/changelog/tenant/103-members-manage-split.sql";

    private static final List<String> FOUR = List.of(
            "members.accounts", "members.assign_roles", "members.roles", "members.grant_overrides");

    private static final OffsetDateTime DECIDED =
            OffsetDateTime.of(2026, 10, 20, 9, 30, 0, 0, ZoneOffset.UTC);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(FORWARD, ROLLBACK);
    }

    @Test
    @DisplayName("У кого было members.manage — все четыре; снятое поверх роли — четыре снятых; уже поделённое не воскресает; повтор ничего не меняет")
    void splitKeepsAccessAndDoesNotResurrect() {
        long owner = inTenant(FORWARD, () -> {
            jdbc.update("DELETE FROM member_permission_override");
            jdbc.update("UPDATE tenant_member SET access_role_id = NULL");
            jdbc.update("DELETE FROM access_role WHERE seeded_from IS NULL");
            jdbc.update("DELETE FROM tenant_member");
            return member("vladelec");
        });
        inTenant(FORWARD, () -> {
            role("Кадровик", "members.manage", "deal.sell");
            role("Продажи", "deal.sell");
            // Деление уже было, клиент снял три из четырёх, старый ключ остался.
            role("Поделённая", "members.manage", "members.accounts");
            // Все четыре уже стоят.
            role("Полная", "members.manage", "members.accounts", "members.assign_roles",
                    "members.roles", "members.grant_overrides");

            override(member("dobavlen"), "members.manage", true, DECIDED, owner);
            override(member("snyat"), "members.manage", false, DECIDED, owner);
            override(member("chuzhoe"), "deal.sell", true, DECIDED, owner);
            long partly = member("chastichno");
            override(partly, "members.manage", true, DECIDED, owner);
            override(partly, "members.roles", true, DECIDED, owner);
            return null;
        });

        replayForward(FORWARD);
        assertSplit(owner);

        replayForward(FORWARD);
        assertSplit(owner);
    }

    private void assertSplit(long owner) {
        Map<String, List<String>> roles = rolePermissions(FORWARD);
        assertThat(roles.get("Кадровик")).as("роль с members.manage получает все четыре и старый ключ сохраняет")
                .containsExactlyInAnyOrder("deal.sell", "members.manage", "members.accounts",
                        "members.assign_roles", "members.roles", "members.grant_overrides");
        assertThat(roles.get("Продажи")).as("роль без members.manage не тронута")
                .containsExactly("deal.sell");
        assertThat(roles.get("Поделённая")).as("снятое у уже поделённой роли не воскресает")
                .containsExactlyInAnyOrder("members.manage", "members.accounts");
        assertThat(roles.get("Полная")).as("уже выданные четыре — без дублей и без отказа")
                .containsExactlyInAnyOrder("members.manage", "members.accounts", "members.assign_roles",
                        "members.roles", "members.grant_overrides");

        Map<String, Map<String, Boolean>> overrides = overrides(FORWARD);
        assertThat(overrides.get("dobavlen")).as("добавленное поверх роли — четыре добавленных")
                .containsOnlyKeys(withManage())
                .allSatisfy((k, v) -> assertThat(v).as(k).isTrue());
        assertThat(overrides.get("snyat")).as("снятое поверх роли — четыре снятых")
                .containsOnlyKeys(withManage())
                .allSatisfy((k, v) -> assertThat(v).as(k).isFalse());
        assertThat(overrides.get("chuzhoe")).as("чужое право не тронуто")
                .containsExactly(Map.entry("deal.sell", true));
        assertThat(overrides.get("chastichno")).as("снятое у уже поделённого сотрудника не воскресает")
                .containsOnlyKeys("members.manage", "members.roles");

        List<Map<String, Object>> who = inTenant(FORWARD, () -> jdbc.queryForList("""
                SELECT o.set_at, o.set_by FROM member_permission_override o
                  JOIN tenant_member m ON m.id = o.member_id
                 WHERE m.login = 'dobavlen' AND o.permission IN ('members.accounts', 'members.assign_roles',
                       'members.roles', 'members.grant_overrides')"""));
        assertThat(who).hasSize(4).allSatisfy(row -> {
            assertThat(((java.sql.Timestamp) row.get("set_at")).toInstant())
                    .as("момент решения переносится с исходной строки").isEqualTo(DECIDED.toInstant());
            assertThat(((Number) row.get("set_by")).longValue()).as("автор решения тот же").isEqualTo(owner);
        });
    }

    @Test
    @DisplayName("Откат сводит четыре права вниз в одно и не шире нового итога; накат обратно не воскрешает снятого")
    void rollbackFoldsDownAndReplayDoesNotResurrect() {
        long owner = inTenant(ROLLBACK, () -> {
            jdbc.update("DELETE FROM member_permission_override");
            jdbc.update("UPDATE tenant_member SET access_role_id = NULL");
            jdbc.update("DELETE FROM access_role WHERE seeded_from IS NULL");
            jdbc.update("DELETE FROM tenant_member");
            return member("vladelec");
        });
        // Состояние после наката и работы нового кода.
        inTenant(ROLLBACK, () -> {
            role("Собрана новым кодом", "deal.sell", "members.accounts", "members.assign_roles",
                    "members.roles", "members.grant_overrides");
            role("Сняли одно", "members.manage", "members.accounts", "members.assign_roles", "members.roles");
            role("Все четыре и старый", "members.manage", "members.accounts", "members.assign_roles",
                    "members.roles", "members.grant_overrides");
            role("Сняли все четыре", "members.manage", "deal.sell");

            long all = member("vse_chetyre");
            for (String k : FOUR) {
                override(all, k, true, DECIDED, owner);
            }
            long oneOff = member("odno_snyato");
            override(oneOff, "members.manage", true, DECIDED, owner);
            override(oneOff, "members.accounts", true, DECIDED, owner);
            override(oneOff, "members.roles", false, DECIDED, owner);
            long two = member("dva_dobavleno");
            override(two, "members.manage", true, DECIDED, owner);
            override(two, "members.accounts", true, DECIDED, owner);
            override(two, "members.roles", true, DECIDED, owner);
            override(member("stary_snyaty"), "members.manage", false, DECIDED, owner);
            override(member("chuzhoe"), "deal.sell", true, DECIDED, owner);
            return null;
        });

        rollback(ROLLBACK);

        Map<String, List<String>> roles = rolePermissions(ROLLBACK);
        assertThat(roles.get("Собрана новым кодом")).as("роль со всеми четырьмя получает старое право")
                .containsExactlyInAnyOrder("deal.sell", "members.manage");
        assertThat(roles).as("роль без всех четырёх старое право теряет — вниз, а не вверх")
                .doesNotContainKey("Сняли одно");
        assertThat(roles.get("Все четыре и старый")).containsExactly("members.manage");
        assertThat(roles.get("Сняли все четыре")).as("снявшая все четыре теряет и старое")
                .containsExactly("deal.sell");

        Map<String, Map<String, Boolean>> overrides = overrides(ROLLBACK);
        assertThat(overrides.get("vse_chetyre")).as("все четыре добавлены — добавлено старое")
                .containsExactly(Map.entry("members.manage", true));
        assertThat(overrides.get("odno_snyato")).as("хоть одно снято — снято старое")
                .containsExactly(Map.entry("members.manage", false));
        assertThat(overrides).as("добавлены не все — старое добавленное снимается, решает роль")
                .doesNotContainKeys("dva_dobavleno", "stary_snyaty");
        assertThat(overrides.get("chuzhoe")).containsExactly(Map.entry("deal.sell", true));

        replayForward(ROLLBACK);

        Map<String, List<String>> again = rolePermissions(ROLLBACK);
        assertThat(again.get("Собрана новым кодом")).as("накат обратно возвращает четвёрку из старого права")
                .containsExactlyInAnyOrder("deal.sell", "members.manage", "members.accounts",
                        "members.assign_roles", "members.roles", "members.grant_overrides");
        assertThat(again).as("снятое не воскресает").doesNotContainKey("Сняли одно");
        assertThat(again.get("Сняли все четыре")).containsExactly("deal.sell");

        Map<String, Map<String, Boolean>> overridesAgain = overrides(ROLLBACK);
        assertThat(overridesAgain.get("odno_snyato")).as("снятое — четыре снятых")
                .containsOnlyKeys(withManage())
                .allSatisfy((k, v) -> assertThat(v).as(k).isFalse());
        assertThat(overridesAgain).doesNotContainKeys("dva_dobavleno", "stary_snyaty");
    }

    // ---------- вспомогательное ----------

    private static String[] withManage() {
        return new String[] {"members.manage", "members.accounts", "members.assign_roles",
                "members.roles", "members.grant_overrides"};
    }

    private long member(String login) {
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, 'VIEWER', ?, 'не-пароль') RETURNING id""", Long.class, login, login);
    }

    private void role(String name, String... permissions) {
        long id = jdbc.queryForObject(
                "INSERT INTO access_role (name) VALUES (?) RETURNING id", Long.class, name);
        for (String p : permissions) {
            jdbc.update("INSERT INTO access_role_permission (role_id, permission) VALUES (?, ?)", id, p);
        }
    }

    private void override(long member, String permission, boolean granted, OffsetDateTime at, long by) {
        jdbc.update("""
                INSERT INTO member_permission_override (member_id, permission, granted, set_at, set_by)
                VALUES (?, ?, ?, ?, ?)""", member, permission, granted, at, by);
    }

    private Map<String, List<String>> rolePermissions(String schema) {
        return inTenant(schema, () -> {
            Map<String, List<String>> rows = new TreeMap<>();
            jdbc.query("""
                    SELECT r.name, p.permission FROM access_role r
                      JOIN access_role_permission p ON p.role_id = r.id
                     WHERE r.seeded_from IS NULL""",
                    rs -> {
                        rows.computeIfAbsent(rs.getString(1), k -> new java.util.ArrayList<>()).add(rs.getString(2));
                    });
            return rows;
        });
    }

    private Map<String, Map<String, Boolean>> overrides(String schema) {
        return inTenant(schema, () -> {
            Map<String, Map<String, Boolean>> rows = new TreeMap<>();
            jdbc.query("""
                    SELECT m.login, o.permission, o.granted FROM member_permission_override o
                      JOIN tenant_member m ON m.id = o.member_id""",
                    rs -> {
                        rows.computeIfAbsent(rs.getString(1), k -> new TreeMap<>())
                                .put(rs.getString(2), rs.getBoolean(3));
                    });
            return rows;
        });
    }

    /** Снимает отметку о накате 103 и накатывает его заново тем путём, что провижининг. */
    private void replayForward(String schema) {
        inTenant(schema, () -> jdbc.update(
                "DELETE FROM " + schema + ".databasechangelog WHERE id = ?", CHANGESET));
        provision(schema);
    }

    /** Выполняет строки {@code --rollback} changeset'а в его порядке, как Liquibase. */
    private void rollback(String schema) {
        List<String> statements = rollbackStatements();
        assertThat(statements).as("строки отката прочитаны").isNotEmpty();
        inTenant(schema, () -> {
            for (String sql : statements) {
                jdbc.execute(sql.replace("${tenant.schema}", schema));
            }
            return null;
        });
    }

    private static List<String> rollbackStatements() {
        try (InputStream in = MembersManageSplitTest.class.getClassLoader().getResourceAsStream(FILE)) {
            assertThat(in).as(FILE + " в classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(l -> l.startsWith("--rollback "))
                    .map(l -> l.substring("--rollback ".length()).trim())
                    .map(l -> l.endsWith(";") ? l.substring(0, l.length() - 1) : l)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void provision(String schema) {
        // Константой, а не переменной: сторож схем (tools/test-schema-guard.py)
        // видит хозяина только по имени.
        if (FORWARD.equals(schema)) {
            provisionTenants(FORWARD);
        } else {
            provisionTenants(ROLLBACK);
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
