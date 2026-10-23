package ru.partsflow.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Роли клиента и права сотрудника через экранные пути (задача 0044, второй шаг).
 *
 * <p>Через HTTP: отказ здесь — это запрет на пути (403) или правило сервиса
 * (409 словами), а закрытие сессий видно только следующим запросом той же
 * cookie. Схема своя — {@code t_000442}.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class AccessRoleHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000442";
    private static final String PASSWORD = "пароль-длинный";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper json;

    private long ownerRole;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 442");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (442, ?, 'Роли клиента', 'roli442')""", TENANT);
        inTenant(() -> {
            jdbc.update("DELETE FROM member_permission_override");
            jdbc.update("UPDATE tenant_member SET access_role_id = NULL");
            // Владелец в схеме один, пока метод не заведёт второго сам: иначе
            // второй владелец соседнего метода разрешил бы снять первого.
            jdbc.update("UPDATE tenant_member SET role = 'VIEWER' WHERE login <> 'vladelec'");
            jdbc.update("DELETE FROM access_role WHERE seeded_from IS NULL");
            jdbc.update("DELETE FROM access_role_permission WHERE role_id = "
                    + "(SELECT id FROM access_role WHERE is_owner)");
            return null;
        });
        member("vladelec", "OWNER");
        ownerRole = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM access_role WHERE is_owner", Long.class));
    }

    // ------------------------------------------------------- владелец — всё всегда

    @Test
    @DisplayName("Роль «Владелец» не переименовать, набора ей не дать, не удалить; права поверх неё не снять — и второй владелец может всё")
    void ownerRoleCannotBeNarrowed() throws Exception {
        long second = member("vtoroy", "VIEWER");
        MockHttpSession owner = login("vladelec");

        // Второй владелец — назначением, как его назначит экран.
        assign(owner, second, ownerRole, "{}").andExpect(status().isOk());
        assertThat(legacyRole(second)).as("прежняя колонка у владельца").isEqualTo("OWNER");

        String message = text(mvc.perform(put("/api/access/roles/" + ownerRole).with(csrf())
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Главный\",\"permissions\":[\"deal.sell\"]}"))
                .andExpect(status().isConflict()));
        assertThat(message).contains("Владелец");
        mvc.perform(delete("/api/access/roles/" + ownerRole).with(csrf()).session(owner))
                .andExpect(status().isConflict());
        assign(owner, second, ownerRole, "{\"journal.view\":false}")
                .andExpect(status().isConflict());

        inTenant(() -> {
            assertThat(jdbc.queryForObject("SELECT name FROM access_role WHERE is_owner", String.class))
                    .isEqualTo("Владелец");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM access_role_permission
                     WHERE role_id = (SELECT id FROM access_role WHERE is_owner)""", Integer.class))
                    .as("роли «Владелец» записали набор").isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM member_permission_override WHERE member_id = ?",
                    Integer.class, second)).as("владельцу сняли право поверх роли").isZero();
            return null;
        });

        for (String login : List.of("vladelec", "vtoroy")) {
            MockHttpSession session = login(login);
            JsonNode me = me(session);
            assertThat(me.path("owner").asBoolean()).as(login).isTrue();
            assertThat(keys(me)).as(login + ": весь справочник")
                    .hasSize(Permission.values().length);
            mvc.perform(get("/api/organization/audit").session(session)).andExpect(status().isOk());
            mvc.perform(get("/api/access/roles").session(session)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Последнего владельца снять нельзя; второго — можно")
    void lastOwnerStays() throws Exception {
        long vladelec = id("vladelec");
        long viewerRole = seeded("VIEWER");
        MockHttpSession owner = login("vladelec");

        String message = text(assign(owner, vladelec, viewerRole, "{}")
                .andExpect(status().isConflict()));
        assertThat(message).contains("единственный владелец");

        long second = member("vtoroy", "OWNER");
        assign(owner, second, viewerRole, "{}").andExpect(status().isOk());
        assertThat(legacyRole(second)).isEqualTo("VIEWER");
    }

    @Test
    @DisplayName("Роль «Владелец» назначает и снимает только владелец, а не тот, кому дали «Сотрудники и роли»")
    void onlyOwnerGrantsOwnership() throws Exception {
        long kadrovik = member("kadrovik", "VIEWER");
        long role = role("Кадровик", "members.manage");
        assignSql(kadrovik, role);
        long target = member("prodavec", "SELLER");
        MockHttpSession session = login("kadrovik");

        assign(session, target, ownerRole, "{}").andExpect(status().isForbidden());
        assign(session, kadrovik, ownerRole, "{}").andExpect(status().isForbidden());
        mvc.perform(post("/api/members").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"novyy\",\"password\":\"%s\",\"role\":\"OWNER\"}"
                                .formatted(PASSWORD)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/members").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"novyy\",\"password\":\"%s\",\"roleId\":%d}"
                                .formatted(PASSWORD, ownerRole)))
                .andExpect(status().isForbidden());
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM tenant_member WHERE login = 'novyy'", Integer.class)))
                .as("отказ назначения откатил и заведение").isZero();

        // Обычную роль он назначает.
        assign(session, target, seeded("STOREKEEPER"), "{}").andExpect(status().isOk());
    }

    // --------------------------------------------------------- роли и назначение

    @Test
    @DisplayName("Роль заводится, назначается, поверх неё добавляется и снимается право; прежняя колонка пишется вниз")
    void createAssignOverride() throws Exception {
        MockHttpSession owner = login("vladelec");
        JsonNode created = json.readTree(text(mvc.perform(post("/api/access/roles").with(csrf())
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Приёмщик\",\"permissions\":[\"intake.receive\",\"photos.manage\"]}"))
                .andExpect(status().isCreated())));
        long role = created.path("id").asLong();

        mvc.perform(post("/api/access/roles").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" приёмщик \",\"permissions\":[]}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/access/roles").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Чужой\",\"permissions\":[\"deal.steal\"]}"))
                .andExpect(status().isBadRequest());

        long ivan = member("ivan", "VIEWER");
        JsonNode view = json.readTree(text(assign(owner, ivan, role,
                "{\"deal.sell\":true,\"photos.manage\":false,\"intake.receive\":true}")
                .andExpect(status().isOk())));
        assertThat(view.path("roleName").asText()).isEqualTo("Приёмщик");
        assertThat(list(view.path("added"))).containsExactly("deal.sell");
        assertThat(list(view.path("removed"))).containsExactly("photos.manage");
        assertThat(list(view.path("effective"))).containsExactlyInAnyOrder("intake.receive", "deal.sell");
        assertThat(legacyRole(ivan)).as("своя роль клиента — «Просмотр» в прежней колонке")
                .isEqualTo("VIEWER");

        MockHttpSession session = login("ivan");
        mvc.perform(get("/api/deals/board").session(session)).andExpect(status().isOk());
        mvc.perform(delete("/api/parts/1/photos/1").with(csrf()).session(session))
                .andExpect(status().isForbidden());

        // Роль переноса — её прежняя жёсткая роль в колонке.
        assign(owner, ivan, seeded("STOREKEEPER"), "{}").andExpect(status().isOk());
        assertThat(legacyRole(ivan)).isEqualTo("STOREKEEPER");
    }

    @Test
    @DisplayName("Назначенную роль удалить нельзя — словами и числом; свободную — можно")
    void deleteAssignedRole() throws Exception {
        MockHttpSession owner = login("vladelec");
        long role = role("Временная", "deal.sell");
        long ivan = member("ivan", "VIEWER");
        assignSql(ivan, role);

        String message = text(mvc.perform(delete("/api/access/roles/" + role).with(csrf()).session(owner))
                .andExpect(status().isConflict()));
        assertThat(message).contains("Временная").contains("1 сотруднику");

        assign(owner, ivan, seeded("VIEWER"), "{}").andExpect(status().isOk());
        mvc.perform(delete("/api/access/roles/" + role).with(csrf()).session(owner))
                .andExpect(status().isNoContent());
    }

    // --------------------------------------------------------------- сессии

    @Test
    @DisplayName("Правка набора роли закрывает сессии всех, кому она назначена; назначение — сессии сотрудника")
    void rightsChangeClosesSessions() throws Exception {
        MockHttpSession owner = login("vladelec");
        long role = role("Отчётник", "reports.view");
        long first = member("otchet1", "VIEWER");
        long second = member("otchet2", "VIEWER");
        long other = member("drugoy", "VIEWER");
        assignSql(first, role);
        assignSql(second, role);
        MockHttpSession one = login("otchet1");
        MockHttpSession two = login("otchet2");
        MockHttpSession three = login("drugoy");
        mvc.perform(get("/api/reports/managers").session(one)).andExpect(status().isOk());

        mvc.perform(put("/api/access/roles/" + role).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Отчётник\",\"permissions\":[]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/me").session(one)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(two)).andExpect(status().isUnauthorized());
        // Сотрудник другой роли остался работать.
        mvc.perform(get("/api/auth/me").session(three)).andExpect(status().isOk());
        mvc.perform(get("/api/reports/managers").session(login("otchet1")))
                .andExpect(status().isForbidden());

        MockHttpSession again = login("drugoy");
        assign(owner, other, role, "{\"reports.view\":true}").andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(again)).andExpect(status().isUnauthorized());
        // Владелец, назначивший права, остался работать.
        mvc.perform(get("/api/auth/me").session(owner)).andExpect(status().isOk());

        String reason = inTenant(() -> jdbc.queryForObject("""
                SELECT end_reason || ': ' || end_detail FROM login_session s
                  JOIN tenant_member m ON m.id = s.member_id
                 WHERE m.login = 'otchet2' ORDER BY s.id LIMIT 1""", String.class));
        assertThat(reason).as("во «Входах» сказано, почему закрыли")
                .isEqualTo("REVOKED: права роли изменены");
    }

    @Test
    @DisplayName("Правка роли и назначение — в журнале действий словами")
    void journalRecordsRoleAndAssignment() throws Exception {
        MockHttpSession owner = login("vladelec");
        long role = role("Сборщик", "stock.move");
        mvc.perform(put("/api/access/roles/" + role).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Сборщик\",\"permissions\":[\"stock.move\",\"part.cell\"]}"))
                .andExpect(status().isOk());
        long ivan = member("ivan", "VIEWER");
        assign(owner, ivan, role, "{\"deal.sell\":true}").andExpect(status().isOk());

        String page = text(mvc.perform(get("/api/organization/audit").session(owner))
                .andExpect(status().isOk()));
        assertThat(page).contains("Раскладывать по полкам").contains("Сборщик").contains("Продавать");
    }

    // ------------------------------------------------------------ вспомогательное

    private ResultActions assign(MockHttpSession session, long member, long role, String overrides)
            throws Exception {
        return mvc.perform(put("/api/access/members/" + member).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":%d,\"overrides\":%s}".formatted(role, overrides)));
    }

    private long member(String login, String role) {
        return inTenant(() -> {
            List<Long> found = jdbc.queryForList(
                    "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
            if (!found.isEmpty()) {
                // Фикстура пишет то, что называет (корневой CLAUDE.md, «Тесты»).
                jdbc.update("""
                        UPDATE tenant_member SET role = ?, access_role_id = NULL, is_active = true
                         WHERE id = ?""", role, found.get(0));
                return found.get(0);
            }
            return jdbc.queryForObject("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES (?, ?, ?, ?) RETURNING id""", Long.class,
                    login, role, login, passwordEncoder.encode(PASSWORD));
        });
    }

    private long role(String name, String... permissions) {
        return inTenant(() -> {
            long id = jdbc.queryForObject(
                    "INSERT INTO access_role (name) VALUES (?) RETURNING id", Long.class, name);
            for (String permission : permissions) {
                jdbc.update("INSERT INTO access_role_permission (role_id, permission) VALUES (?, ?)",
                        id, permission);
            }
            return id;
        });
    }

    private void assignSql(long member, long role) {
        inTenant(() -> jdbc.update(
                "UPDATE tenant_member SET access_role_id = ? WHERE id = ?", role, member));
    }

    private long seeded(String from) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM access_role WHERE seeded_from = ?", Long.class, from));
    }

    private long id(String login) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login));
    }

    private String legacyRole(long member) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT role FROM tenant_member WHERE id = ?", String.class, member));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"roli442","login":"%s","password":"%s"}"""
                                .formatted(login, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private JsonNode me(MockHttpSession session) throws Exception {
        return json.readTree(text(mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())));
    }

    private static String text(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> keys(JsonNode me) {
        return list(me.path("permissions"));
    }

    private static List<String> list(JsonNode array) {
        List<String> keys = new ArrayList<>();
        array.forEach(key -> keys.add(key.asText()));
        return keys;
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(s -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
