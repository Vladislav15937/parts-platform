package ru.partsflow.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    /** Слова отказа «последнего владельца» — решение владельца 24.10.2026, дословно. */
    private static final String LAST_OWNER_WORDS = "в компании должен остаться хотя бы один владелец";

    /**
     * Повторов гонки. Окно между счётом и записью — миллисекунды, и попадает
     * в него не каждый заход: число взято замером подделки «прочитали —
     * проверили — записали» (сколько красных из скольких — в PR и памятке).
     */
    private static final int RACE_ROUNDS = 20;

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
    private MemberService members;

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
        assertThat(message).contains(LAST_OWNER_WORDS);

        long second = member("vtoroy", "OWNER");
        assign(owner, second, viewerRole, "{}").andExpect(status().isOk());
        assertThat(legacyRole(second)).isEqualTo("VIEWER");
    }

    // ------------------------------------------- владельцев не меньше одного

    @Test
    @DisplayName("Последнему владельцу не сменить роль на другую — и владельцу ссылкой, и владельцу по колонке")
    void lastOwnerKeepsOwnerRole() throws Exception {
        long vladelec = id("vladelec");
        MockHttpSession owner = login("vladelec");
        // Владелец прежней колонкой (без ссылки) — роль переноса «Продавец».
        String message = text(assign(owner, vladelec, seeded("SELLER"), "{}")
                .andExpect(status().isConflict()));
        assertThat(message).contains(LAST_OWNER_WORDS);

        // Владелец ссылкой на роль «Владелец» — своя роль клиента.
        inTenant(() -> jdbc.update("UPDATE tenant_member SET access_role_id = ? WHERE id = ?",
                ownerRole, vladelec));
        long custom = role("Своя", "deal.sell");
        message = text(assign(login("vladelec"), vladelec, custom, "{}")
                .andExpect(status().isConflict()));
        assertThat(message).contains(LAST_OWNER_WORDS);
        assertThat(ownersLeft()).isEqualTo(1);
    }

    @Test
    @DisplayName("Последнего владельца не выключить: через HTTP до этого не дойти (себя — нельзя, чужого владельца — только владелец), и потому проверка стоит в самой операции")
    void lastOwnerCannotBeDisabled() {
        long vladelec = id("vladelec");
        assertThatThrownBy(() -> inTenant(() -> {
            members.setActive(vladelec, false);
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining(LAST_OWNER_WORDS);
        assertThat(ownersLeft()).isEqualTo(1);
    }

    /**
     * Два последних владельца одновременно выключают друг друга: выжить
     * обязан один, а не ноль. Проверка чтением пропустила бы обоих.
     */
    @RepeatedTest(RACE_ROUNDS)
    @DisplayName("Гонка: два последних владельца выключают друг друга одновременно — остаётся один")
    void twoOwnersDisableEachOther() throws Exception {
        long first = id("vladelec");
        long second = member("vtoroy", "OWNER");
        MockHttpSession one = login("vladelec");
        MockHttpSession two = login("vtoroy");

        List<Integer> statuses = race(
                () -> post("/api/members/" + second + "/disable").with(csrf()).session(one),
                () -> post("/api/members/" + first + "/disable").with(csrf()).session(two));

        assertThat(ownersLeft()).as("действующих владельцев после гонки, ответы %s", statuses)
                .isEqualTo(1);
        assertThat(statuses).as("ровно один прошёл").containsOnlyOnce(204);
    }

    @RepeatedTest(RACE_ROUNDS)
    @DisplayName("Гонка: два последних владельца снимают друг с друга «Владельца» одновременно — остаётся один")
    void twoOwnersUnownEachOther() throws Exception {
        long first = id("vladelec");
        long second = member("vtoroy", "OWNER");
        MockHttpSession one = login("vladelec");
        MockHttpSession two = login("vtoroy");
        // Разные роли — чтобы два запроса не встали в очередь на замке одной
        // строки роли (lockRole) и гонку решала проверка владельцев, а не он.
        long viewer = seeded("VIEWER");
        long seller = seeded("SELLER");

        List<Integer> statuses = race(
                () -> put("/api/access/members/" + second).with(csrf()).session(one)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":%d,\"overrides\":{}}".formatted(viewer)),
                () -> put("/api/access/members/" + first).with(csrf()).session(two)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":%d,\"overrides\":{}}".formatted(seller)));

        assertThat(ownersLeft()).as("действующих владельцев после гонки, ответы %s", statuses)
                .isEqualTo(1);
        assertThat(statuses).as("ровно один прошёл").containsOnlyOnce(200);
    }

    // ----------------------------------------- выдавать можно только своё

    @Test
    @DisplayName("Свои права: сотрудника с ролью шире своих не завести, с ролью в пределах своих — можно")
    void grantLimitOnCreateWithRole() throws Exception {
        MockHttpSession session = kadrovik();
        long wider = role("Отчётник-продавец", "deal.sell", "reports.view");
        long within = role("Продавец-узкий", "deal.sell");

        String message = text(createMember(session, "novyy1", "\"roleId\":" + wider)
                .andExpect(status().isForbidden()));
        assertThat(message).contains("Смотреть отчёты").doesNotContain("Продавать");
        assertThat(memberExists("novyy1")).as("отказ откатил заведение").isFalse();

        createMember(session, "novyy2", "\"roleId\":" + within).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Свои права: прежней строкой роли — та же проверка (роль переноса шире своих — отказ)")
    void grantLimitOnCreateWithLegacyRole() throws Exception {
        MockHttpSession session = kadrovik();
        String message = text(createMember(session, "novyy3", "\"role\":\"MANAGER\"")
                .andExpect(status().isForbidden()));
        assertThat(message).contains("Выдать можно только свои права");
        assertThat(memberExists("novyy3")).as("отказ откатил заведение").isFalse();

        createMember(session, "novyy4", "\"role\":\"SELLER\"").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Свои права: роль шире своих не назначить; в пределах своих — можно")
    void grantLimitOnAssign() throws Exception {
        MockHttpSession session = kadrovik();
        long target = member("prodavec", "SELLER");
        assign(session, target, seeded("MANAGER"), "{}").andExpect(status().isForbidden());
        assign(session, target, role("Продавец-узкий", "deal.sell"), "{}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Свои права: поверх роли не добавить чужое право; своё — можно; снять — можно")
    void grantLimitOnOverride() throws Exception {
        MockHttpSession session = kadrovik();
        long target = member("prodavec", "SELLER");
        long narrow = role("Продавец-узкий", "deal.sell");
        String message = text(assign(session, target, narrow, "{\"reports.view\":true}")
                .andExpect(status().isForbidden()));
        assertThat(message).contains("Смотреть отчёты");
        assign(session, target, narrow, "{\"deal.return\":true,\"deal.sell\":false}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Свои права: роль с набором шире своих не завести и не поправить; в пределах — можно")
    void grantLimitOnRoleSet() throws Exception {
        MockHttpSession session = kadrovik();
        mvc.perform(post("/api/access/roles").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Широкая\",\"permissions\":[\"deal.sell\",\"settings.company\"]}"))
                .andExpect(status().isForbidden());
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM access_role WHERE name = 'Широкая'", Integer.class))).isZero();
        String created = text(mvc.perform(post("/api/access/roles").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Узкая\",\"permissions\":[\"deal.sell\"]}"))
                .andExpect(status().isCreated()));
        long id = json.readTree(created).path("id").asLong();

        mvc.perform(put("/api/access/roles/" + id).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Узкая\",\"permissions\":[\"deal.sell\",\"journal.view\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/access/roles/" + id).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Узкая\",\"permissions\":[\"deal.sell\",\"deal.return\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Свои права: владелец выдаёт любое — роль на весь справочник и сотрудника с ней")
    void ownerGrantsAnything() throws Exception {
        MockHttpSession owner = login("vladelec");
        String all = java.util.Arrays.stream(Permission.values())
                .map(p -> "\"" + p.key() + "\"").collect(java.util.stream.Collectors.joining(","));
        long id = json.readTree(text(mvc.perform(post("/api/access/roles").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Почти владелец\",\"permissions\":[" + all + "]}"))
                .andExpect(status().isCreated()))).path("id").asLong();
        createMember(owner, "pochti", "\"roleId\":" + id).andExpect(status().isCreated());
        createMember(owner, "menedzher", "\"role\":\"MANAGER\"").andExpect(status().isCreated());
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

        // Обычную роль — в пределах своих прав (задача 0044, 2б) — он назначает.
        assign(session, target, role("Без прав"), "{}").andExpect(status().isOk());
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

    /**
     * Держатель «Сотрудники и роли» с набором прежнего «Продавца» сверху:
     * ему есть что выдать своего и чего не выдать чужого.
     */
    private MockHttpSession kadrovik() throws Exception {
        long kadrovik = member("kadrovik", "VIEWER");
        List<String> keys = new ArrayList<>(inTenant(() -> jdbc.queryForList("""
                SELECT permission FROM access_role_permission
                 WHERE role_id = (SELECT id FROM access_role WHERE seeded_from = 'SELLER')""",
                String.class)));
        keys.add("members.manage");
        assignSql(kadrovik, role("Кадровик", keys.toArray(String[]::new)));
        return login("kadrovik");
    }

    private ResultActions createMember(MockHttpSession session, String login, String role)
            throws Exception {
        inTenant(() -> jdbc.update("DELETE FROM tenant_member WHERE login = ?", login));
        return mvc.perform(post("/api/members").with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"%s\",\"password\":\"%s\",%s}".formatted(login, PASSWORD, role)));
    }

    private boolean memberExists(String login) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM tenant_member WHERE login = ?", Integer.class, login)) > 0;
    }

    private int ownersLeft() {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM tenant_member m
                 WHERE m.is_active AND m.login IS NOT NULL""" + " AND " + MemberService.OWNER_SQL,
                Integer.class));
    }

    /** Два запроса разом, от общей черты; ответы — кодами. */
    @SafeVarargs
    private List<Integer> race(Supplier<MockHttpServletRequestBuilder>... requests)
            throws Exception {
        CyclicBarrier start = new CyclicBarrier(requests.length);
        ExecutorService pool = Executors.newFixedThreadPool(requests.length);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (Supplier<MockHttpServletRequestBuilder> request : requests) {
                MockHttpServletRequestBuilder built = request.get();
                answers.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return mvc.perform(built).andReturn().getResponse().getStatus();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

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
