package ru.partsflow.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Пароль, выключение и включение — только тому, у кого прав не меньше, чем у цели
 * (ответ владельца 24.10.2026 после волны, пункт 1а), и четыре права на раздачу
 * вместо одного (ответ 24.10.2026, «развёрнутые вопросы», 2а).
 *
 * <p>Через HTTP: отказ — 403 словами ({@code RightsRefusal}), а то, что пароль
 * не сменился, видно только входом под целью. Сценарий живого прогона
 * 24.10.2026 — «Кадровик с правами продавца меняет пароль менеджеру» (было 204,
 * вход под менеджером 200). Схема своя — {@code t_000401}.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class AccountChangeLimitHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000401";
    private static final String COMPANY = "konta401";
    private static final String PASSWORD = "пароль-длинный";
    private static final String STOLEN = "чужой-пароль-1";

    private static final String WIDER_PASSWORD =
            "Нельзя менять пароль сотруднику, у которого прав больше, чем у вас: ";
    private static final String WIDER_DISABLE =
            "Нельзя выключить сотрудника, у которого прав больше, чем у вас: ";
    private static final String WIDER_ENABLE =
            "Нельзя включить сотрудника, у которого прав больше, чем у вас: ";

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

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 401");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (401, ?, 'Учётные записи', ?)""", TENANT, COMPANY);
        inTenant(() -> {
            jdbc.update("DELETE FROM member_permission_override");
            jdbc.update("UPDATE tenant_member SET access_role_id = NULL");
            jdbc.update("UPDATE tenant_member SET role = 'VIEWER' WHERE login <> 'vladelec'");
            jdbc.update("DELETE FROM tenant_member WHERE role = 'ADMIN'");
            jdbc.update("DELETE FROM access_role WHERE seeded_from IS NULL AND NOT is_owner");
            return null;
        });
        member("vladelec", "OWNER");
    }

    // --------------------------------------------- три пути, у каждого две стороны

    @Test
    @DisplayName("Сценарий живого прогона: кадровик с правами продавца не меняет пароль менеджеру — 403 словами, менеджер входит прежним паролем; продавцу — меняет")
    void passwordOfWiderIsRefused() throws Exception {
        MockHttpSession session = kadrovik("members.accounts");
        long manager = member("menedzher", "MANAGER");
        long seller = member("prodavec", "SELLER");

        String body = text(password(session, manager).andExpect(status().isForbidden()));
        assertThat(body).contains(WIDER_PASSWORD);
        beyondTitles("MANAGER").forEach(title -> assertThat(body).contains("«" + title + "»"));
        assertWords(body);

        loginAs("menedzher", PASSWORD).andExpect(status().isOk());
        loginAs("menedzher", STOLEN).andExpect(status().isUnauthorized());

        password(session, seller).andExpect(status().isNoContent());
        loginAs("prodavec", STOLEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Выключить сотрудника с правами шире своих нельзя — 403 словами, он остаётся включённым; продавца — можно")
    void disableOfWiderIsRefused() throws Exception {
        MockHttpSession session = kadrovik("members.accounts");
        long manager = member("menedzher", "MANAGER");
        long seller = member("prodavec", "SELLER");

        String body = text(act(session, manager, "disable").andExpect(status().isForbidden()));
        assertThat(body).contains(WIDER_DISABLE);
        assertWords(body);
        assertThat(active(manager)).as("менеджер остался включённым").isTrue();

        act(session, seller, "disable").andExpect(status().isNoContent());
        assertThat(active(seller)).isFalse();
    }

    @Test
    @DisplayName("Включить выключенного сотрудника с правами шире своих нельзя — 403 словами, он остаётся выключенным; продавца — можно")
    void enableOfWiderIsRefused() throws Exception {
        MockHttpSession session = kadrovik("members.accounts");
        long manager = member("menedzher", "MANAGER");
        long seller = member("prodavec", "SELLER");
        setActive(manager, false);
        setActive(seller, false);

        String body = text(act(session, manager, "enable").andExpect(status().isForbidden()));
        assertThat(body).contains(WIDER_ENABLE);
        assertWords(body);
        assertThat(active(manager)).as("выключенный менеджер остался выключенным").isFalse();

        act(session, seller, "enable").andExpect(status().isNoContent());
        assertThat(active(seller)).isTrue();
    }

    @Test
    @DisplayName("Владельца — прежней колонкой и ссылкой на роль — не владелец не трогает: все три отказа словами, без «Недостаточно прав»; владелец трогает менеджера")
    void ownerAccountIsOwnersOnly() throws Exception {
        MockHttpSession session = kadrovik("members.accounts");
        long byColumn = id("vladelec");
        long byLink = member("vtoroy", "VIEWER");
        inTenant(() -> jdbc.update("""
                UPDATE tenant_member SET access_role_id = (SELECT id FROM access_role WHERE is_owner)
                 WHERE id = ?""", byLink));
        long disabledOwner = member("tretiy", "OWNER");
        setActive(disabledOwner, false);

        List<String> refusals = new ArrayList<>();
        for (long owner : List.of(byColumn, byLink)) {
            refusals.add(text(password(session, owner).andExpect(status().isForbidden())));
            refusals.add(text(act(session, owner, "disable").andExpect(status().isForbidden())));
        }
        refusals.add(text(act(session, disabledOwner, "enable").andExpect(status().isForbidden())));
        assertThat(refusals).hasSize(5).allSatisfy(body -> {
            assertThat(body).contains("у владельца все права, и его учётную запись меняет только владелец");
            assertWords(body);
        });
        assertThat(refusals.get(0)).contains("Нельзя менять пароль владельцу");
        assertThat(refusals.get(1)).contains("Нельзя выключить владельца");
        assertThat(refusals.get(4)).contains("Нельзя включить владельца");
        assertThat(active(byLink)).isTrue();
        assertThat(active(disabledOwner)).isFalse();
        loginAs("vladelec", PASSWORD).andExpect(status().isOk());

        // Владелец — всех.
        MockHttpSession owner = login("vladelec");
        long manager = member("menedzher", "MANAGER");
        password(owner, manager).andExpect(status().isNoContent());
        act(owner, manager, "disable").andExpect(status().isNoContent());
        act(owner, manager, "enable").andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------- обходные пути

    @Test
    @DisplayName("Право поверх роли считается: продавец с добавленным правом, которого у кадровика нет, — шире")
    void overrideCountsAsWider() throws Exception {
        MockHttpSession session = kadrovik("members.accounts");
        long seller = member("prodavec", "SELLER");
        inTenant(() -> jdbc.update("""
                INSERT INTO member_permission_override (member_id, permission, granted)
                VALUES (?, 'settings.company', true)""", seller));
        String body = text(password(session, seller).andExpect(status().isForbidden()));
        assertThat(body).contains(WIDER_PASSWORD + "«" + Permission.SETTINGS_COMPANY.title() + "»");
    }

    @Test
    @DisplayName("Ревизор прежнего образа без роли «Ревизор» читает журнал — значит шире кадровика без журнала")
    void legacyAuditorIsWider() throws Exception {
        MockHttpSession session = kadrovik("members.accounts");
        long auditor = member("revizor", "AUDITOR");
        String body = text(password(session, auditor).andExpect(status().isForbidden()));
        assertThat(body).contains("«" + Permission.JOURNAL_VIEW.title() + "»");
    }

    @Test
    @DisplayName("Сужен владельцем — уже не шире: пароль меняется, и вход под ним не даёт прав сверх своих; повышенный — снова шире")
    void narrowedTargetIsTouchable() throws Exception {
        MockHttpSession session = kadrovik("members.accounts");
        long manager = member("menedzher", "MANAGER");
        long seller = member("prodavec", "SELLER");
        // Роль меняет владелец (прямой записью — как его назначение): права
        // читаются на каждом запросе из базы, а не из снимка.
        inTenant(() -> {
            jdbc.update("UPDATE tenant_member SET access_role_id = "
                    + "(SELECT id FROM access_role WHERE seeded_from = 'SELLER') WHERE id = ?", manager);
            jdbc.update("UPDATE tenant_member SET access_role_id = "
                    + "(SELECT id FROM access_role WHERE seeded_from = 'MANAGER') WHERE id = ?", seller);
            return null;
        });

        password(session, manager).andExpect(status().isNoContent());
        Set<String> gained = keys(me(loginAs("menedzher", STOLEN)));
        assertThat(kadrovikKeys()).as("вход под сменённым паролем не дал прав сверх своих")
                .containsAll(gained);
        password(session, seller).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Свой пароль меняет каждый: и кадровик, и сотрудник без единого права")
    void ownPasswordIsAlwaysAllowed() throws Exception {
        MockHttpSession session = kadrovik("members.accounts");
        password(session, id("kadrovik")).andExpect(status().isNoContent());
        long viewer = member("nikto", "VIEWER");
        MockHttpSession nikto = login("nikto");
        // Чужой пароль без права на учётные записи — по-прежнему отказ пути.
        password(nikto, id("vladelec")).andExpect(status().isForbidden());
        password(nikto, viewer).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Администратор сервиса — правила прежние: кадровику его пароль и выключение отбивает 409 словами про «Отозвать доступ»")
    void serviceAdminRowUnchanged() throws Exception {
        MockHttpSession session = kadrovik("members.accounts");
        long admin = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role) VALUES ('Администратор сервиса', 'ADMIN')
                RETURNING id""", Long.class));
        assertThat(text(password(session, admin).andExpect(status().isConflict())))
                .contains("Отозвать доступ");
        assertThat(text(act(session, admin, "disable").andExpect(status().isConflict())))
                .contains("Отозвать доступ");
    }

    @Test
    @DisplayName("Прежний ключ «Управлять сотрудниками и ролями» не читается: снятое у роли одно из четырёх прав не воскресает через него")
    void legacyMembersManageIsDead() throws Exception {
        long seller = member("prodavec", "SELLER");
        // Роль после переноса tenant/103: прежний ключ лежит рядом с четырьмя, а одно
        // из четырёх клиент снял. Раскрой код прежний ключ — снятое вернулось бы молча.
        MockHttpSession session = kadrovik("members.manage", "members.assign_roles", "members.roles",
                "members.grant_overrides");
        password(session, seller).andExpect(status().isForbidden());
        assertThat(keys(me(session))).doesNotContain("members.accounts", "members.manage");
    }

    // ------------------------- сужать того, кто шире, нельзя (ответ 24.10, вопрос 2)

    private static final String WIDER_ASSIGN =
            "Нельзя менять роль и права сотруднику, у которого прав больше, чем у вас: ";

    @Test
    @DisplayName("Сменить роль сотруднику шире своих нельзя — 403 словами, роль прежняя; продавцу — можно")
    void assignRoleToWiderIsRefused() throws Exception {
        MockHttpSession session = kadrovik("members.assign_roles", "members.grant_overrides");
        long manager = member("menedzher", "MANAGER");
        long seller = member("prodavec", "SELLER");
        long narrow = role("Продавец-узкий", "deal.sell");

        String body = text(assignAs(session, manager, "{\"roleId\":%d,\"overrides\":{}}".formatted(narrow))
                .andExpect(status().isForbidden()));
        assertThat(body).contains(WIDER_ASSIGN);
        assertWords(body);
        assertThat(roleOf(manager)).as("менеджер остался менеджером").isEqualTo("MANAGER");

        assignAs(session, seller, "{\"roleId\":%d,\"overrides\":{}}".formatted(narrow))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Права поверх роли у сотрудника шире своих — ни снять, ни добавить: 403 словами; продавцу — можно")
    void overridesOnWiderAreRefused() throws Exception {
        MockHttpSession session = kadrovik("members.assign_roles", "members.grant_overrides");
        long manager = member("menedzher", "MANAGER");
        long seller = member("prodavec", "SELLER");
        long managerRole = seeded("MANAGER");
        long sellerRole = seeded("SELLER");

        String removed = text(assignAs(session, manager,
                "{\"roleId\":%d,\"overrides\":{\"deal.sell\":false}}".formatted(managerRole))
                .andExpect(status().isForbidden()));
        assertThat(removed).contains(WIDER_ASSIGN);
        String added = text(assignAs(session, manager,
                "{\"roleId\":%d,\"overrides\":{\"deal.sell\":true}}".formatted(managerRole))
                .andExpect(status().isForbidden()));
        assertThat(added).contains(WIDER_ASSIGN);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM member_permission_override WHERE member_id = ?", Integer.class, manager)))
                .as("менеджеру ничего не записали").isZero();

        assignAs(session, seller, "{\"roleId\":%d,\"overrides\":{\"deal.sell\":false}}".formatted(sellerRole))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Набор роли, назначенной сотруднику шире правящего, не поправить — 403 словами с подписью; переименовать можно; роль только узких — правится")
    void roleSetOfWiderIsLocked() throws Exception {
        MockHttpSession session = kadrovik("members.roles");
        long shared = role("Смешанная", "deal.sell");
        long narrowOnly = role("Узкая", "deal.sell");
        long wide = member("shirokiy", "VIEWER");
        long narrowMember = member("uzkiy", "VIEWER");
        inTenant(() -> {
            jdbc.update("UPDATE tenant_member SET access_role_id = ? WHERE id = ?", shared, wide);
            jdbc.update("UPDATE tenant_member SET access_role_id = ? WHERE id = ?", narrowOnly, narrowMember);
            jdbc.update("""
                    INSERT INTO member_permission_override (member_id, permission, granted)
                    VALUES (?, 'settings.company', true)""", wide);
            return null;
        });

        String body = text(roleUpdate(session, shared, "Смешанная", "deal.return")
                .andExpect(status().isForbidden()));
        assertThat(body).contains("Набор роли «Смешанная» не поправить")
                .contains("shirokiy").doesNotContain("Недостаточно прав");
        assertThat(inTenant(() -> jdbc.queryForList(
                "SELECT permission FROM access_role_permission WHERE role_id = ?", String.class, shared)))
                .as("набор не тронут").containsExactly("deal.sell");

        roleUpdate(session, shared, "Смешанная-2", "deal.sell").andExpect(status().isOk());
        roleUpdate(session, narrowOnly, "Узкая", "deal.return").andExpect(status().isOk());

        Map<Long, JsonNode> roles = new HashMap<>();
        json.readTree(text(mvc.perform(get("/api/access/roles").session(session))
                .andExpect(status().isOk()))).forEach(r -> roles.put(r.path("id").asLong(), r));
        assertThat(roles.get(shared).path("editLocked").asText()).contains("не поправить");
        assertThat(roles.get(narrowOnly).path("editLocked").isNull()).isTrue();
    }

    // ----------------------------------------------------- список «Сотрудники»

    @Test
    @DisplayName("Список сотрудников называет, почему нельзя: шире — словами с правами, владелец — «только владелец», свой и узкий — пусто; владельцу — пусто у всех")
    void listNamesTheLimit() throws Exception {
        MockHttpSession session = kadrovik("members.accounts");
        long manager = member("menedzher", "MANAGER");
        long seller = member("prodavec", "SELLER");
        long self = id("kadrovik");
        long owner = id("vladelec");

        Map<Long, JsonNode> rows = listed(session);
        assertThat(rows.get(manager).path("accountLocked").asText())
                .startsWith("У сотрудника прав больше, чем у вас: «");
        assertThat(rows.get(owner).path("accountLocked").asText())
                .isEqualTo("Учётную запись владельца меняет только владелец");
        assertThat(rows.get(seller).path("accountLocked").isNull()).as("продавец").isTrue();
        assertThat(rows.get(self).path("accountLocked").isNull()).as("свой").isTrue();

        assertThat(listed(login("vladelec")).values())
                .allSatisfy(row -> assertThat(row.path("accountLocked").isNull()).isTrue());
    }

    // ------------------------------------------------ четыре права на раздачу

    @Test
    @DisplayName("Четыре права — каждое на своём действии: заводить и пароль, назначать роль, править роли, права поверх роли")
    void fourRightsEachOnItsAction() throws Exception {
        inTenant(() -> jdbc.update("DELETE FROM tenant_member WHERE login = 'novyy'"));
        long target = member("prodavec", "SELLER");
        long narrow = role("Продавец-узкий", "deal.sell");
        String assign = "{\"roleId\":%d,\"overrides\":{}}".formatted(narrow);

        // Только «Заводить сотрудников…»: пароль — да; роль, права поверх, роли — нет.
        MockHttpSession accounts = kadrovik("members.accounts");
        password(accounts, target).andExpect(status().isNoContent());
        assignAs(accounts, target, assign).andExpect(status().isForbidden());
        roleCreate(accounts).andExpect(status().isForbidden());
        mvc.perform(get("/api/members").session(accounts)).andExpect(status().isOk());

        // Только «Назначать роли»: роль — да, право поверх — нет (словами), пароль — нет.
        MockHttpSession roles = kadrovik("members.assign_roles");
        assignAs(roles, target, assign).andExpect(status().isOk());
        assertThat(text(assignAs(roles, target,
                "{\"roleId\":%d,\"overrides\":{\"deal.return\":true}}".formatted(narrow))
                .andExpect(status().isForbidden())))
                .contains("Менять права поверх роли может тот, у кого есть право «"
                        + Permission.MEMBERS_GRANT_OVERRIDES.title() + "»");
        password(roles, target).andExpect(status().isForbidden());
        roleCreate(roles).andExpect(status().isForbidden());

        // Только «Права поверх роли»: право поверх той же роли — да, другая роль — нет.
        MockHttpSession grants = kadrovik("members.grant_overrides");
        assignAs(grants, target, "{\"roleId\":%d,\"overrides\":{\"deal.return\":true}}"
                .formatted(narrow)).andExpect(status().isOk());
        assertThat(text(assignAs(grants, target, "{\"roleId\":%d,\"overrides\":{}}"
                .formatted(role("Другая", "deal.sell"))).andExpect(status().isForbidden())))
                .contains("Назначать роли может тот, у кого есть право «"
                        + Permission.MEMBERS_ASSIGN_ROLES.title() + "»");

        // Только «Заводить и править роли»: роль — да, сотрудника и пароль — нет.
        MockHttpSession editor = kadrovik("members.roles");
        roleCreate(editor).andExpect(status().isCreated());
        password(editor, target).andExpect(status().isForbidden());
        mvc.perform(get("/api/access/roles").session(editor)).andExpect(status().isOk());
    }

    /**
     * Каждое из четырёх прав на раздачу — на своём пути, а не «любое из четырёх».
     * Держатель ровно одного другого права получает 403 за отсутствие своего права
     * (путь — «Недостаточно прав»; общий путь «Права» — сервис называет нужное право),
     * а не за правило «не шире своих»: цель и роль здесь узкие, кадровик сверх них
     * держит набор продавца. Держатель своего — проходит.
     * Подмена аннотации на «любое из четырёх» (ANY_MEMBERS) краснит свой путь.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST /api/members", "POST /api/members/{id}/password",
            "POST /api/members/{id}/disable", "POST /api/members/{id}/enable",
            "POST /api/access/roles", "PUT /api/access/roles/{id}", "DELETE /api/access/roles/{id}",
            "PUT /api/access/members/{id} (роль)", "PUT /api/access/members/{id} (права поверх)"})
    @DisplayName("Каждое из четырёх прав на раздачу — на своём пути: с другим одним — 403 пути, со своим — пропуск")
    void eachRightOnItsOwnPath(String path) throws Exception {
        String own = switch (path) {
            case "POST /api/members", "POST /api/members/{id}/password", "POST /api/members/{id}/disable",
                 "POST /api/members/{id}/enable" -> "members.accounts";
            case "POST /api/access/roles", "PUT /api/access/roles/{id}", "DELETE /api/access/roles/{id}" ->
                    "members.roles";
            case "PUT /api/access/members/{id} (роль)" -> "members.assign_roles";
            default -> "members.grant_overrides";
        };
        for (String key : List.of("members.accounts", "members.assign_roles", "members.roles",
                "members.grant_overrides")) {
            int status = attempt(path, kadrovik(key)).andReturn().getResponse().getStatus();
            String body = lastBody;
            if (key.equals(own)) {
                assertThat(status).as("%s со своим «%s»: %s", path, key, body).isBetween(200, 204);
            } else {
                assertThat(status).as("%s с одним чужим «%s»: %s", path, key, body).isEqualTo(403);
                // Отказ — за отсутствие своего права: путём («Недостаточно прав») либо, у общего
                // пути «Права», сервисом с названием нужного права; не правилом «не шире своих».
                // Второе право той же пары у пути «Права» пропускает аннотация, и отказ даёт
                // сервис; права вне пары обязан отбить сам путь.
                boolean pair = path.startsWith("PUT /api/access/members")
                        && (key.equals("members.assign_roles") || key.equals("members.grant_overrides"));
                String title = Permission.byKey(own).orElseThrow().title();
                assertThat(pair ? body.contains("«" + title + "»") : body.contains("Недостаточно прав"))
                        .as("%s с «%s»: отказ не за отсутствие «%s» — %s", path, key, own, body).isTrue();
                assertThat(body).doesNotContain("прав больше");
            }
        }
    }

    private String lastBody = "";

    /** Одна попытка пути над свежей узкой целью — ничего у неё не шире кадровика. */
    private ResultActions attempt(String path, MockHttpSession session) throws Exception {
        inTenant(() -> jdbc.update("DELETE FROM tenant_member WHERE login = 'novyy'"));
        long target = member("prodavec", "SELLER");
        long narrow = role("Продавец-узкий", "deal.sell");
        long otherNarrow = role("Продавец-узкий-2", "deal.sell");
        inTenant(() -> jdbc.update("UPDATE tenant_member SET access_role_id = ? WHERE id = ?", narrow, target));
        long freeRole = role("Свободная", "deal.sell");
        ResultActions result = switch (path) {
            case "POST /api/members" -> {
                inTenant(() -> jdbc.update("DELETE FROM tenant_member WHERE login = 'novyy'"));
                yield mvc.perform(post("/api/members").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"novyy\",\"password\":\"%s\",\"roleId\":%d}"
                                .formatted(PASSWORD, narrow)));
            }
            case "POST /api/members/{id}/password" -> password(session, target);
            case "POST /api/members/{id}/disable" -> act(session, target, "disable");
            case "POST /api/members/{id}/enable" -> {
                setActive(target, false);
                yield act(session, target, "enable");
            }
            case "POST /api/access/roles" -> roleCreate(session);
            case "PUT /api/access/roles/{id}" -> roleUpdate(session, freeRole, "Свободная", "deal.return");
            case "DELETE /api/access/roles/{id}" -> mvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/access/roles/" + freeRole).with(csrf()).session(session));
            case "PUT /api/access/members/{id} (роль)" -> assignAs(session, target,
                    "{\"roleId\":%d,\"overrides\":{}}".formatted(otherNarrow));
            default -> assignAs(session, target,
                    "{\"roleId\":%d,\"overrides\":{\"deal.sell\":false}}".formatted(narrow));
        };
        lastBody = text(result);
        return result;
    }

    // ------------------------------------------------------------ вспомогательное

    /** Никаких внутренних ключей, номеров строк и общей фразы — только слова. */
    private static void assertWords(String body) {
        assertThat(body).doesNotContain("Недостаточно прав").doesNotContainPattern("\\d");
        for (Permission permission : Permission.values()) {
            assertThat(body).doesNotContain(permission.key());
        }
    }

    /** Кадровик: набор прежнего «Продавца» плюс названные права на раздачу. */
    private MockHttpSession kadrovik(String... extra) throws Exception {
        long kadrovik = member("kadrovik", "VIEWER");
        List<String> keys = new ArrayList<>(seededKeys("SELLER"));
        keys.addAll(List.of(extra));
        long role = role("Кадровик " + String.join(",", extra), keys.toArray(String[]::new));
        inTenant(() -> jdbc.update("UPDATE tenant_member SET access_role_id = ? WHERE id = ?",
                role, kadrovik));
        return login("kadrovik");
    }

    private Set<String> kadrovikKeys() throws Exception {
        return keys(me(login("kadrovik")));
    }

    /** Названия прав роли переноса, которых у кадровика (продавец) нет. */
    private List<String> beyondTitles(String seeded) {
        Set<String> beyond = new TreeSet<>(seededKeys(seeded));
        beyond.removeAll(seededKeys("SELLER"));
        assertThat(beyond).as("у %s есть права сверх продавца", seeded).isNotEmpty();
        return beyond.stream().map(key -> Permission.byKey(key).orElseThrow().title()).toList();
    }

    private List<String> seededKeys(String seeded) {
        return inTenant(() -> jdbc.queryForList("""
                SELECT permission FROM access_role_permission
                 WHERE role_id = (SELECT id FROM access_role WHERE seeded_from = ?)""",
                String.class, seeded));
    }

    private ResultActions password(MockHttpSession session, long member) throws Exception {
        return mvc.perform(post("/api/members/" + member + "/password").with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"%s\"}".formatted(STOLEN)));
    }

    private ResultActions act(MockHttpSession session, long member, String action) throws Exception {
        return mvc.perform(post("/api/members/" + member + "/" + action).with(csrf()).session(session));
    }

    private ResultActions assignAs(MockHttpSession session, long member, String body) throws Exception {
        return mvc.perform(put("/api/access/members/" + member).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions roleUpdate(MockHttpSession session, long role, String name, String permission)
            throws Exception {
        return mvc.perform(put("/api/access/roles/" + role).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"permissions\":[\"%s\"]}".formatted(name, permission)));
    }

    private long seeded(String from) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM access_role WHERE seeded_from = ?", Long.class, from));
    }

    private String roleOf(long member) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT coalesce(r.seeded_from, r.name, m.role) FROM tenant_member m
                  LEFT JOIN access_role r ON r.id = m.access_role_id WHERE m.id = ?""", String.class, member));
    }

    private ResultActions roleCreate(MockHttpSession session) throws Exception {
        inTenant(() -> jdbc.update("DELETE FROM access_role WHERE name = 'Новая роль'"));
        return mvc.perform(post("/api/access/roles").with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Новая роль\",\"permissions\":[\"deal.sell\"]}"));
    }

    private Map<Long, JsonNode> listed(MockHttpSession session) throws Exception {
        Map<Long, JsonNode> rows = new HashMap<>();
        json.readTree(text(mvc.perform(get("/api/members").session(session))
                .andExpect(status().isOk()))).forEach(row -> rows.put(row.path("id").asLong(), row));
        return rows;
    }

    private long member(String login, String role) {
        return inTenant(() -> {
            List<Long> found = jdbc.queryForList(
                    "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
            if (!found.isEmpty()) {
                // Фикстура пишет то, что называет (корневой CLAUDE.md, «Тесты»).
                jdbc.update("""
                        UPDATE tenant_member SET role = ?, access_role_id = NULL, is_active = true,
                               password_hash = ?
                         WHERE id = ?""", role, passwordEncoder.encode(PASSWORD), found.get(0));
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
            jdbc.update("DELETE FROM access_role WHERE name = ?", name);
            long id = jdbc.queryForObject(
                    "INSERT INTO access_role (name) VALUES (?) RETURNING id", Long.class, name);
            for (String permission : permissions) {
                jdbc.update("INSERT INTO access_role_permission (role_id, permission) VALUES (?, ?)",
                        id, permission);
            }
            return id;
        });
    }

    private long id(String login) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login));
    }

    private boolean active(long member) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT is_active FROM tenant_member WHERE id = ?", Boolean.class, member));
    }

    private void setActive(long member, boolean active) {
        inTenant(() -> jdbc.update("UPDATE tenant_member SET is_active = ? WHERE id = ?", active, member));
    }

    private ResultActions loginAs(String login, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"company\":\"%s\",\"login\":\"%s\",\"password\":\"%s\"}"
                        .formatted(COMPANY, login, password)));
    }

    private MockHttpSession login(String login) throws Exception {
        return (MockHttpSession) loginAs(login, PASSWORD).andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private MockHttpSession session(ResultActions login) throws Exception {
        return (MockHttpSession) login.andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private JsonNode me(ResultActions login) throws Exception {
        return me(session(login));
    }

    private JsonNode me(MockHttpSession session) throws Exception {
        return json.readTree(text(mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())));
    }

    private static Set<String> keys(JsonNode me) {
        Set<String> keys = new TreeSet<>();
        me.path("permissions").forEach(key -> keys.add(key.asText()));
        return keys;
    }

    private static String text(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
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
