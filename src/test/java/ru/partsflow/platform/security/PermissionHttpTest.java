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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Права по ролям клиента — через HTTP, то есть через перехватчик, сессию и вход
 * (задача 0044).
 *
 * <p>Матрица всех путей — {@link PermissionGuardTest}; здесь то, что видно
 * только снаружи: права читаются при входе из новых таблиц, права поверх роли
 * добавляют и снимают, у владельца — всё всегда, «роли нет» решено по записи
 * схемы, прежняя сессия дочитывает права.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PermissionHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000441";

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

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessions;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 441");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (441, ?, 'Права по действиям', 'prava441')""", TENANT);
        inTenant(() -> {
            jdbc.update("DELETE FROM member_permission_override");
            jdbc.update("UPDATE tenant_member SET access_role_id = NULL");
            jdbc.update("DELETE FROM access_role WHERE seeded_from IS NULL");
            jdbc.update("""
                    DELETE FROM access_role_permission
                     WHERE role_id = (SELECT id FROM access_role WHERE is_owner)""");
            return null;
        });
    }

    // ----------------------------------------------------- владелец — всё всегда

    @Test
    @DisplayName("Владелец — всё всегда: ни набор его роли, ни снятые поверх роли права, ни второй владелец этого не сужают")
    void ownerHasEverythingAlways() throws Exception {
        long first = member("vladelec", "OWNER");
        long second = member("vtoroy", "OWNER");
        inTenant(() -> {
            long ownerRole = jdbc.queryForObject("SELECT id FROM access_role WHERE is_owner", Long.class);
            // Так выглядела бы «правка роли Владелец» прямым SQL: набор из одного права.
            jdbc.update("INSERT INTO access_role_permission (role_id, permission) VALUES (?, 'deal.sell')",
                    ownerRole);
            // Второй владелец назначен ссылкой, первый — прежней колонкой role.
            jdbc.update("UPDATE tenant_member SET access_role_id = ? WHERE id = ?", ownerRole, second);
            for (long owner : List.of(first, second)) {
                for (String key : List.of("journal.view", "members.manage",
                        "stock_document.delete_done", "settings.company", "deal.sell")) {
                    jdbc.update("""
                            INSERT INTO member_permission_override (member_id, permission, granted)
                            VALUES (?, ?, false)""", owner, key);
                }
            }
            return null;
        });

        for (String login : List.of("vladelec", "vtoroy")) {
            MockHttpSession session = login(login);
            for (String path : List.of("/api/organization/audit", "/api/members",
                    "/api/company/settings", "/api/deals/board", "/api/organization/sessions")) {
                mvc.perform(get(path).session(session))
                        .andExpect(status().isOk());
            }
            mvc.perform(delete("/api/stock/documents/987654").with(csrf()).session(session))
                    .andExpect(status().isBadRequest());

            JsonNode me = me(session);
            assertThat(me.path("owner").asBoolean()).as(login + ": владелец").isTrue();
            assertThat(keys(me)).as(login + ": весь справочник, а не набор роли")
                    .containsExactlyInAnyOrderElementsOf(
                            Arrays.stream(Permission.values()).map(Permission::key).toList());
        }
    }

    // ------------------------------------------------------- роль клиента и поверх

    @Test
    @DisplayName("Права роли клиента: есть право — 200, нет — 403")
    void customRoleGivesExactlyItsSet() throws Exception {
        long role = role("Продающий кладовщик", "deal.sell", "stock.move");
        long kladovshchik = member("kladovshchik", "VIEWER");
        assign(kladovshchik, role);

        MockHttpSession session = login("kladovshchik");
        mvc.perform(get("/api/deals/board").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/stock/moves").session(session)).andExpect(status().isOk());
        // Роль в прежней колонке — «Просмотр», но права решает роль клиента.
        mvc.perform(get("/api/inventory/sessions").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/organization/audit").session(session)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/stock/documents/987654").with(csrf()).session(session))
                .andExpect(status().isForbidden());

        JsonNode me = me(session);
        assertThat(me.path("owner").asBoolean()).isFalse();
        assertThat(keys(me)).containsExactlyInAnyOrder("deal.sell", "stock.move");
    }

    @Test
    @DisplayName("Поверх роли: добавленное право открывает, снятое закрывает")
    void overridesAddAndRemove() throws Exception {
        long role = role("Продавец-2", "deal.sell", "deal.return");
        long prodavec = member("prodavec2", "VIEWER");
        assign(prodavec, role);
        override(prodavec, "stock_document.delete_done", true);
        override(prodavec, "deal.sell", false);

        MockHttpSession session = login("prodavec2");
        mvc.perform(delete("/api/stock/documents/987654").with(csrf()).session(session))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/deals/board").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/deals/returns").session(session)).andExpect(status().isOk());
        assertThat(keys(me(session)))
                .containsExactlyInAnyOrder("deal.return", "stock_document.delete_done");
    }

    @Test
    @DisplayName("Живая связь: набор роли правится — новый вход видит новые права")
    void roleEditReachesEveryoneAssigned() throws Exception {
        long role = role("Отчётник");
        long first = member("otchet1", "VIEWER");
        long second = member("otchet2", "VIEWER");
        assign(first, role);
        assign(second, role);
        for (String login : List.of("otchet1", "otchet2")) {
            mvc.perform(get("/api/payments").session(login(login))).andExpect(status().isForbidden());
        }

        inTenant(() -> jdbc.update("""
                INSERT INTO access_role_permission (role_id, permission)
                VALUES (?, 'payments.registry.view')""", role));

        for (String login : List.of("otchet1", "otchet2")) {
            mvc.perform(get("/api/payments").session(login(login))).andExpect(status().isOk());
        }
    }

    // ---------------------------------------------------------------- роли нет

    @Test
    @DisplayName("Ревизор там, где «Ревизора» нет, — журнал; удалённая клиентом прежняя роль — ничего")
    void noRoleCases() throws Exception {
        inTenant(() -> {
            jdbc.update("DELETE FROM access_role WHERE seeded_from = 'AUDITOR'");
            return null;
        });
        member("revizor", "AUDITOR");
        MockHttpSession auditor = login("revizor");
        mvc.perform(get("/api/organization/audit").session(auditor)).andExpect(status().isOk());
        mvc.perform(get("/api/deals/board").session(auditor)).andExpect(status().isForbidden());
        assertThat(keys(me(auditor))).containsExactly("journal.view");

        // Клиент удалил «Менеджер» и завёл свою; прежний образ (откат приложения)
        // заводит сотрудника строкой role = MANAGER без ссылки.
        long removed = inTenant(() -> {
            long id = jdbc.queryForObject(
                    "SELECT id FROM access_role WHERE seeded_from = 'MANAGER'", Long.class);
            jdbc.update("UPDATE tenant_member SET access_role_id = NULL WHERE access_role_id = ?", id);
            jdbc.update("DELETE FROM access_role WHERE id = ?", id);
            return id;
        });
        member("staryy", "MANAGER");
        MockHttpSession manager = login("staryy");
        mvc.perform(get("/api/reports/managers").session(manager)).andExpect(status().isForbidden());
        assertThat(keys(me(manager))).as("набор удалённой роли %d не вернулся сам", removed).isEmpty();

        // Вернуть роль на место, иначе соседние методы не найдут «Менеджера».
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO access_role (name, seeded_from) VALUES ('Менеджер', 'MANAGER')""");
            return null;
        });
    }

    // ------------------------------------------------------- сессия до задачи 0044

    @Test
    @DisplayName("Сессия, заведённая до 0044, дочитывает права и не получает 403 на первом нажатии")
    void legacySessionReadsPermissions() throws Exception {
        long prodavec = member("prodavec", "SELLER");
        MockHttpSession session = login("prodavec");

        // Принципал прежнего вида: набора прав в нём нет, как у сессии,
        // пережившей выкладку в общей схеме ячейки.
        int replaced = 0;
        for (Session stored : sessions.findByPrincipalName(TENANT + "#" + prodavec).values()) {
            SecurityContext context = stored.getAttribute("SPRING_SECURITY_CONTEXT");
            TenantPrincipal current = (TenantPrincipal) context.getAuthentication().getPrincipal();
            TenantPrincipal legacy = current.withAccess(false, null);
            SecurityContext old = SecurityContextHolder.createEmptyContext();
            old.setAuthentication(new TenantAuthenticationToken(legacy, legacy.getAuthorities()));
            stored.setAttribute("SPRING_SECURITY_CONTEXT", old);
            save(stored);
            replaced++;
        }
        assertThat(replaced).as("сессия продавца найдена в хранилище").isEqualTo(1);

        mvc.perform(get("/api/deals/board").session(session)).andExpect(status().isOk());
        assertThat(keys(me(session))).contains("deal.sell", "deal.return", "customer.view");
    }

    // ------------------------------------------------------------ вспомогательное

    @SuppressWarnings("unchecked")
    private <S extends Session> void save(Session session) {
        ((FindByIndexNameSessionRepository<S>) sessions).save((S) session);
    }

    private long member(String login, String role) {
        return inTenant(() -> {
            List<Long> found = jdbc.queryForList(
                    "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
            if (!found.isEmpty()) {
                // Фикстура пишет то, что называет (корневой CLAUDE.md, «Тесты»).
                jdbc.update("UPDATE tenant_member SET role = ?, access_role_id = NULL, is_active = true WHERE id = ?",
                        role, found.get(0));
                return found.get(0);
            }
            return jdbc.queryForObject("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES (?, ?, ?, ?) RETURNING id""", Long.class,
                    login, role, login, passwordEncoder.encode("пароль-длинный"));
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

    private void assign(long member, long role) {
        inTenant(() -> jdbc.update(
                "UPDATE tenant_member SET access_role_id = ? WHERE id = ?", role, member));
    }

    private void override(long member, String permission, boolean granted) {
        inTenant(() -> jdbc.update("""
                INSERT INTO member_permission_override (member_id, permission, granted)
                VALUES (?, ?, ?)""", member, permission, granted));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"prava441","login":"%s","password":"пароль-длинный"}"""
                                .formatted(login)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private JsonNode me(MockHttpSession session) throws Exception {
        return json.readTree(mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> keys(JsonNode me) {
        List<String> keys = new ArrayList<>();
        me.path("permissions").forEach(key -> keys.add(key.asText()));
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
