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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Перенос прежних ролей в роли клиента (задача 0044, {@code tenant/101}).
 *
 * <p>Ответ владельца 23.10.2026 (tasks/0044, «на оставшиеся три», пункт 3):
 * у каждого клиента заводятся обычные роли с прежними наборами, сотрудник
 * получает роль, равную прежней, доступ никто не теряет.
 *
 * <p><b>Раздача проверяется проигрыванием по непустой схеме</b> (приём
 * {@code MemberNumberTest}): на пустой схеме назначать некого, и
 * {@code db/verify.sh} проходит при любом {@code UPDATE}. Поэтому
 * сотрудники заводятся, changeset снимается своим же откатом вместе
 * с отметкой о накате — и проигрывается заново.
 */
@SpringBootTest
class AccessRolesSeedTest extends PostgresTestBase {

    /** Схема с сотрудниками всех прежних ролей, включая ревизора. */
    private static final String ALL = "t_000579";

    /** Схема без ревизора. */
    private static final String NO_AUDITOR = "t_000580";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(ALL, NO_AUDITOR);
    }

    @Test
    @DisplayName("Каждый сотрудник получает роль, равную прежней; ADMIN — без роли; наборы равны нынешним")
    void everyoneGetsTheirFormerRole() {
        inTenant(ALL, () -> {
            jdbc.update("DELETE FROM tenant_member");
            for (String role : List.of("OWNER", "MANAGER", "STOREKEEPER", "SELLER", "VIEWER", "AUDITOR")) {
                member(role.toLowerCase(), role);
            }
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, service_admin_id)
                    VALUES ('Администратор сервиса', 'ADMIN', 1)""");
            return null;
        });

        replaySeed(ALL);

        Map<String, String> assigned = inTenant(ALL, () -> {
            Map<String, String> rows = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT m.role, r.name FROM tenant_member m
                    LEFT JOIN access_role r ON r.id = m.access_role_id ORDER BY m.id""",
                    rs -> { rows.put(rs.getString(1), rs.getString(2)); });
            return rows;
        });
        assertThat(assigned).as("роль, равная прежней; администратор сервиса — без назначения")
                .containsExactly(
                        Map.entry("OWNER", "Владелец"),
                        Map.entry("MANAGER", "Менеджер"),
                        Map.entry("STOREKEEPER", "Кладовщик"),
                        Map.entry("SELLER", "Продавец"),
                        Map.entry("VIEWER", "Просмотр"),
                        Map.entry("AUDITOR", "Ревизор"),
                        entryWithNull("ADMIN"));

        assertThat(inTenant(ALL, () -> jdbc.queryForObject(
                "SELECT is_owner FROM access_role WHERE seeded_from = 'OWNER'", Boolean.class)))
                .as("«Владелец» — встроенная роль").isTrue();
        assertThat(permissions(ALL, "OWNER")).as("у владельца набора нет: все права всегда").isEmpty();
        assertThat(permissions(ALL, "MANAGER")).hasSize(31)
                .contains("part.edit", "part.money.view", "inventory.reconcile", "reports.view")
                .doesNotContain("journal.view", "stock_document.delete_done", "members.manage");
        assertThat(permissions(ALL, "STOREKEEPER")).containsExactlyInAnyOrder(
                "intake.receive", "photos.manage", "stock.move", "part.cell", "wheels.set",
                "inventory.count", "inventory.counter_note", "deal.issue");
        assertThat(permissions(ALL, "SELLER")).containsExactlyInAnyOrder(
                "intake.receive", "photos.manage", "inventory.count", "deal.sell", "deal.issue",
                "deal.return", "customer.view", "customer.create", "customer.account");
        assertThat(permissions(ALL, "VIEWER")).containsExactly("inventory.journal.view");
        assertThat(permissions(ALL, "AUDITOR")).containsExactly("journal.view");
    }

    @Test
    @DisplayName("«Ревизор» заводится только там, где есть сотрудник-ревизор")
    void auditorRoleOnlyWhereAuditorExists() {
        inTenant(NO_AUDITOR, () -> {
            jdbc.update("DELETE FROM tenant_member");
            member("vladelec", "OWNER");
            member("prodavec", "SELLER");
            return null;
        });

        replaySeed(NO_AUDITOR);

        List<String> roles = inTenant(NO_AUDITOR, () -> jdbc.queryForList(
                "SELECT name FROM access_role ORDER BY id", String.class));
        assertThat(roles).as("четыре роли из ответа владельца и встроенный владелец, ревизора нет")
                .containsExactly("Владелец", "Менеджер", "Кладовщик", "Продавец", "Просмотр");
    }

    @Test
    @DisplayName("Повторный накат не переназначает сотрудника, стоящего на своей роли клиента")
    void replayDoesNotReassignCustomRole() {
        inTenant(ALL, () -> {
            jdbc.update("DELETE FROM tenant_member");
            member("vladelec", "OWNER");
            member("svoy", "VIEWER");
            return null;
        });
        replaySeed(ALL);

        // Так это оставит новый код: своя роль клиента, а в прежней колонке
        // role — «Просмотр» (вниз, для прежнего образа).
        long roleId = inTenant(ALL, () -> {
            long id = jdbc.queryForObject(
                    "INSERT INTO access_role (name) VALUES ('Своя2') RETURNING id", Long.class);
            jdbc.update("INSERT INTO access_role_permission (role_id, permission) VALUES (?, 'deal.sell')", id);
            jdbc.update("UPDATE tenant_member SET access_role_id = ? WHERE login = 'svoy'", id);
            return id;
        });

        replaySeed(ALL);

        Long now = inTenant(ALL, () -> jdbc.queryForObject(
                "SELECT access_role_id FROM tenant_member WHERE login = 'svoy'", Long.class));
        assertThat(now).as("своя роль клиента пережила откат и повторный накат 101, «Просмотр» не подставлен")
                .isEqualTo(roleId);
        String owner = inTenant(ALL, () -> jdbc.queryForObject("""
                SELECT r.name FROM tenant_member m JOIN access_role r ON r.id = m.access_role_id
                WHERE m.login = 'vladelec'""", String.class));
        assertThat(owner).as("снятое откатом назначение раздано заново").isEqualTo("Владелец");
    }

    // ---------- вспомогательное ----------

    private static Map.Entry<String, String> entryWithNull(String key) {
        Map<String, String> one = new LinkedHashMap<>();
        one.put(key, null);
        return one.entrySet().iterator().next();
    }

    private void member(String login, String role) {
        jdbc.update("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, 'не-пароль')""", login, role, login);
    }

    private List<String> permissions(String schema, String seededFrom) {
        return inTenant(schema, () -> jdbc.queryForList("""
                SELECT p.permission FROM access_role_permission p
                JOIN access_role r ON r.id = p.role_id WHERE r.seeded_from = ?""",
                String.class, seededFrom));
    }

    /**
     * Снимает {@code tenant/101} его же откатом и отметкой о накате,
     * затем накатывает заново тем же путём, что провижининг.
     */
    private void replaySeed(String schema) {
        inTenant(schema, () -> {
            jdbc.update("""
                    UPDATE tenant_member SET access_role_id = NULL
                    WHERE access_role_id IN (SELECT id FROM access_role WHERE seeded_from IS NOT NULL)""");
            jdbc.update("DELETE FROM access_role WHERE seeded_from IS NOT NULL");
            jdbc.update("DELETE FROM " + schema + ".databasechangelog WHERE id = ?",
                    "tenant-101-access-roles-seed");
            return null;
        });
        // Константой, а не переменной: сторож схем (tools/test-schema-guard.py)
        // видит хозяина только по имени.
        if (ALL.equals(schema)) {
            provisionTenants(ALL);
        } else {
            provisionTenants(NO_AUDITOR);
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
