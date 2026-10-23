package ru.partsflow.platform.security;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import ru.partsflow.support.PostgresTestBase;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сотрудники, заведённые <b>до</b> выкладки 0044, после наката {@code tenant/100}–{@code 101}
 * проходят ровно то, что проходили прежним кодом (задача 0044, условие слияния PR 508).
 *
 * <p><b>Эталон — прежний код, а не текст миграции.</b> Таблица «путь → роли»
 * ({@code src/test/resources/access/legacy-access-b07449e9.tsv}) снята на коммите до PR 506
 * тем же {@link AccessMatrix}, которым здесь спрашивается нынешнее приложение: прежние
 * {@code SecurityConfig} (запрет изменяющих методов, правила путей) и {@code @PreAuthorize}
 * по контроллерам. Сверять наборы ролей с {@code 101} значило бы сверять миграцию с самой
 * собой: ошибка переноса — это ровно расхождение между ними.
 *
 * <p><b>Схема наполняется так, как её застанет выкладка.</b> Changeset'ы до {@code tenant/100}
 * накатываются, сотрудники всех прежних ролей (включёнными и выключенными) заводятся прежней
 * записью — одной колонкой {@code role}, — и только потом накатывается остальное тем же путём,
 * что провижининг. Чистая база этого не проверяет: на ней назначать некого.
 *
 * <p><b>Вошедший — настоящий.</b> Каждый сотрудник входит {@code POST /api/auth/login}, а права
 * для матрицы берутся из его сессии в общем хранилище: то, что положил туда вход после
 * {@code MemberAccess}, а не собранное тестом. Сверх матрицы — настоящие запросы по путям
 * разных модулей ({@link #PROBES}): что перехватчик и цепочка решают так же, как матрица.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LegacyAccessAfterDeployTest extends PostgresTestBase {

    /** Все прежние роли, включённые и выключенные, ревизор при наличии. */
    private static final String ALL = "t_000761";
    private static final long ALL_ID = 761;
    private static final String ALL_CODE = "perenos761";

    /** Ревизора при выкладке не было — «Ревизор» не заведён, ревизора заводит прежний образ после. */
    private static final String NO_AUDITOR = "t_000762";
    private static final long NO_AUDITOR_ID = 762;
    private static final String NO_AUDITOR_CODE = "perenos762";

    /** Ревизор был, «Ревизора» клиент потом удалил, а прежний образ завёл ещё одного. */
    private static final String DELETED = "t_000763";
    private static final long DELETED_ID = 763;
    private static final String DELETED_CODE = "perenos763";

    private static final String PASSWORD = "пароль-переноса";
    private static final String ADMIN_LOGIN = "sluzhba761";
    private static final String ADMIN_PASSWORD = "пароль-администратора-761";

    /** Первый changeset, которого нет у схемы, заставшей выкладку. */
    private static final String FIRST_NEW = "tenant-100-access-roles";

    private static final List<String> ROLES =
            List.of("OWNER", "MANAGER", "STOREKEEPER", "SELLER", "VIEWER", "AUDITOR");

    /**
     * Пути, где «пропускает ли» решает не цепочка и не аннотация, а сервис, — их матрица
     * не видит, и они проверены настоящим запросом в {@link #PROBES} с прежним итогом.
     */
    private static final Map<String, String> DECIDED_IN_SERVICE = Map.of(
            "DELETE /api/stock/documents/{id}",
            "до 0044 путь пропускал пишущие роли, а сервис отказывал всем, кроме владельца "
                    + "(PostedDocumentDeletion); теперь аннотации нет, а сервис спрашивает право "
                    + "stock_document.delete_done — итог для прежних ролей тот же, проверен запросом");

    /**
     * Расхождения с прежним кодом, которые решил владелец, — каждое с решением.
     * Ключ — «путь роль» (или «путь» — снятый путь). Пустой список — то, к чему стремимся: любое новое расхождение
     * обязано либо оказаться здесь с решением, либо быть ошибкой переноса.
     */
    private static final Map<String, String> DECIDED_CHANGES = Map.of();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SessionRepository<Session> sessions;

    @BeforeAll
    static void deploy() throws Exception {
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);

        // До выкладки: все прежние роли, по включённому и выключенному на каждую,
        // и строка администратора сервиса, заведённая его прежним визитом.
        beforeDeploy(ALL);
        List<String[]> all = new ArrayList<>();
        for (String role : ROLES) {
            all.add(new String[] {role.toLowerCase(), role, "true"});
            all.add(new String[] {"off" + role.toLowerCase(), role, "false"});
        }
        legacyMembers(ALL, hash, all);
        legacyAdminRow(ALL);
        provisionTenants(ALL);

        beforeDeploy(NO_AUDITOR);
        legacyMembers(NO_AUDITOR, hash, List.of(
                new String[] {"owner", "OWNER", "true"},
                new String[] {"seller", "SELLER", "true"}));
        provisionTenants(NO_AUDITOR);

        beforeDeploy(DELETED);
        legacyMembers(DELETED, hash, List.of(
                new String[] {"owner", "OWNER", "true"},
                new String[] {"auditor", "AUDITOR", "true"}));
        provisionTenants(DELETED);
    }

    // ------------------------------------------------------------------ тесты

    @Test
    @DisplayName("Каждый сотрудник, заведённый до выкладки, проходит ровно прежние пути — матрицей и запросами")
    void everyLegacyMemberKeepsTheirPaths() throws Exception {
        registry(ALL_ID, ALL, ALL_CODE);
        Map<String, Set<String>> baseline = baseline();

        // Назначение — по прежней роли, у администратора сервиса назначения нет.
        Map<String, String> assigned = new TreeMap<>();
        jdbc.query("""
                SELECT m.login, m.role, r.seeded_from FROM %s.tenant_member m
                  LEFT JOIN %s.access_role r ON r.id = m.access_role_id""".formatted(ALL, ALL),
                rs -> {
                    assigned.put(Objects.requireNonNullElse(rs.getString(1), "(администратор сервиса)"),
                            rs.getString(2) + " → " + rs.getString(3));
                });
        assertThat(assigned).as("каждый получил роль, равную прежней; администратор — без назначения")
                .allSatisfy((login, pair) -> {
                    String[] parts = pair.split(" → ");
                    if (parts[0].equals("ADMIN")) {
                        assertThat(parts[1]).isEqualTo("null");
                    } else {
                        assertThat(parts[1]).as(login).isEqualTo(parts[0]);
                    }
                });

        // Выключенный не входит, как не входил.
        for (String role : ROLES) {
            mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(credentials(ALL_CODE, "off" + role.toLowerCase())))
                    .andExpect(status().isUnauthorized());
        }

        Map<String, MockHttpSession> sessionsByRole = new LinkedHashMap<>();
        for (String role : ROLES) {
            sessionsByRole.put(role, login(ALL_CODE, role.toLowerCase()));
        }
        sessionsByRole.put("ADMIN", adminEnters(sessionsByRole.get("OWNER")));

        // Выключенного владелец включает — и тот получает права своей прежней роли.
        MockHttpSession owner = sessionsByRole.get("OWNER");
        Map<String, MockHttpSession> reenabled = new LinkedHashMap<>();
        for (String role : ROLES) {
            long id = jdbc.queryForObject("SELECT id FROM " + ALL + ".tenant_member WHERE login = ?",
                    Long.class, "off" + role.toLowerCase());
            mvc.perform(post("/api/members/" + id + "/enable").with(csrf()).session(owner))
                    .andExpect(status().isNoContent());
            reenabled.put(role, login(ALL_CODE, "off" + role.toLowerCase()));
        }

        Map<String, String> mismatches = new TreeMap<>();
        int compared = 0;
        for (var entry : sessionsByRole.entrySet()) {
            compared += compare(baseline, entry.getKey(), entry.getKey(), entry.getValue(), mismatches);
        }
        for (var entry : reenabled.entrySet()) {
            compared += compare(baseline, entry.getKey(), "включённый " + entry.getKey(),
                    entry.getValue(), mismatches);
        }
        assertThat(compared).as("сверено решений «путь × сотрудник»").isGreaterThan(200 * 13);
        assertThat(mismatches)
                .as("после наката tenant/100–101 сотрудник проходит не то, что проходил прежним кодом "
                        + "(путь → было/стало); решение владельца — в DECIDED_CHANGES с причиной")
                .isEmpty();

        // Настоящие запросы: перехватчик и цепочка решают то же, что матрица и прежний код.
        Map<String, String> wrong = new TreeMap<>();
        for (Probe probe : PROBES) {
            Set<String> allowed = probe.expected() != null ? probe.expected() : baseline.get(probe.route());
            assertThat(allowed).as("путь пробы есть в эталоне: " + probe.route()).isNotNull();
            for (var entry : sessionsByRole.entrySet()) {
                int code = mvc.perform(probe.request().get().with(csrf()).session(entry.getValue()))
                        .andReturn().getResponse().getStatus();
                boolean passed = code != 403 && code != 401;
                if (passed != allowed.contains(entry.getKey())) {
                    wrong.put(probe.route() + " " + entry.getKey(),
                            "ждали " + (allowed.contains(entry.getKey()) ? "пропуска" : "403")
                                    + ", ответ " + code);
                }
            }
        }
        assertThat(PROBES.stream().map(p -> p.route().split("/")[2]).distinct().count())
                .as("пробы — по разным модулям").isGreaterThanOrEqualTo(8);
        assertThat(PROBES).as("проб не меньше 25").hasSizeGreaterThanOrEqualTo(25);
        assertThat(wrong).as("настоящий запрос разошёлся с прежним кодом").isEmpty();
    }

    @Test
    @DisplayName("Ревизор, которого при выкладке не было: прежний образ завёл его после — журнал у него есть, как был")
    void auditorAddedWhereNoAuditorRoleExists() throws Exception {
        registry(NO_AUDITOR_ID, NO_AUDITOR, NO_AUDITOR_CODE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + NO_AUDITOR
                + ".access_role WHERE seeded_from = 'AUDITOR'", Integer.class))
                .as("«Ревизор» не заведён: ревизора при выкладке не было").isZero();
        // Прежний образ (откат приложения) заводит ревизора одной колонкой role.
        legacyMemberNow(NO_AUDITOR, "auditor", "AUDITOR");

        Map<String, String> mismatches = new TreeMap<>();
        compare(baseline(), "AUDITOR", "ревизор без «Ревизора»", login(NO_AUDITOR_CODE, "auditor"), mismatches);
        compare(baseline(), "SELLER", "продавец", login(NO_AUDITOR_CODE, "seller"), mismatches);
        assertThat(mismatches).as("ревизор без роли «Ревизор» проходит не то, что прежним кодом").isEmpty();

        MockHttpSession auditor = login(NO_AUDITOR_CODE, "auditor");
        mvc.perform(get("/api/organization/audit").session(auditor)).andExpect(status().isOk());
        mvc.perform(put("/api/parts/999999").with(csrf()).session(auditor)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    /**
     * Ответ владельца 24.10.2026, пункт 1(б): удалённая клиентом роль «Ревизор» — отказ,
     * журнал ревизору прежнего образа после удаления не открыт. Метки, различающей «не заводили»
     * и «удалили», в схеме пока нет (её заводит migrator), и {@code MemberAccess} оба случая
     * решает одинаково — журналом. Поэтому здесь сверка с прежним кодом, как у соседнего
     * случая: появится метка — этот тест обязан стать проверкой отказа, а не уйти молча.
     */
    @Test
    @DisplayName("Ревизор после удаления «Ревизора»: сегодня проходит то же, что прежним кодом (пункт 1б ждёт метки)")
    void auditorAddedAfterAuditorRoleDeleted() throws Exception {
        registry(DELETED_ID, DELETED, DELETED_CODE);
        assertThat(jdbc.queryForObject("SELECT seeded_from FROM " + DELETED
                + ".access_role r JOIN " + DELETED + ".tenant_member m ON m.access_role_id = r.id"
                + " WHERE m.login = 'auditor'", String.class))
                .as("ревизор, бывший при выкладке, получил «Ревизора»").isEqualTo("AUDITOR");
        // Клиент снимает роль с сотрудника и удаляет её; прежний образ заводит нового ревизора.
        jdbc.update("UPDATE " + DELETED + ".tenant_member SET access_role_id = (SELECT id FROM " + DELETED
                + ".access_role WHERE seeded_from = 'VIEWER') WHERE login = 'auditor'");
        jdbc.update("DELETE FROM " + DELETED + ".access_role WHERE seeded_from = 'AUDITOR'");
        legacyMemberNow(DELETED, "auditor2", "AUDITOR");

        Map<String, String> mismatches = new TreeMap<>();
        compare(baseline(), "AUDITOR", "ревизор после удаления роли", login(DELETED_CODE, "auditor2"),
                mismatches);
        assertThat(mismatches).isEmpty();
    }

    // ----------------------------------------------------- настоящие запросы

    /**
     * Пробы — по путям разных модулей, с телом, которое разбирается, и с номером, которого нет:
     * пропущенный отвечает 400/404/409 (дальше права), не пропущенный — 403. Изменяющие среди них
     * обязательны: у «Просмотра» и «Ревизора» они и есть то, что прежний код запрещал.
     * {@code expected} — только у путей, решаемых сервисом ({@link #DECIDED_IN_SERVICE}).
     */
    private static final List<Probe> PROBES = List.of(
            // platform
            probe("GET /api/organization/audit", () -> get("/api/organization/audit")),
            probe("GET /api/organization/sessions", () -> get("/api/organization/sessions")),
            probe("GET /api/members", () -> get("/api/members")),
            probe("GET /api/company/settings", () -> get("/api/company/settings")),
            probe("PUT /api/company/settings", () -> json(put("/api/company/settings"), "{\"reservationDays\":3}")),
            probe("POST /api/organization/branches", () -> json(post("/api/organization/branches"), "{\"name\":\"Филиал проверки переноса\"}")),
            probe("POST /api/members/{id}/disable", () -> post("/api/members/999999/disable")),
            probe("PUT /api/me/settings/{screen}", () -> json(put("/api/me/settings/catalog"), "{}")),
            probe("GET /api/events/dead-letters", () -> get("/api/events/dead-letters")),
            // inventory
            probe("GET /api/parts/catalog", () -> get("/api/parts/catalog")),
            probe("GET /api/parts/{id}/editable", () -> get("/api/parts/999999/editable")),
            probe("PUT /api/parts/{id}", () -> json(put("/api/parts/999999"), "{}")),
            probe("POST /api/stock/write-offs", () -> json(post("/api/stock/write-offs"),
                    "{\"warehouseId\":999999,\"reason\":\"проверка\",\"items\":[{\"partId\":999999,\"quantity\":1}]}")),
            probe("POST /api/stock/moves", () -> json(post("/api/stock/moves"),
                    "{\"fromWarehouseId\":999998,\"toWarehouseId\":999999,\"items\":[{\"partId\":999999,\"quantity\":1}]}")),
            probe("GET /api/stock/moves", () -> get("/api/stock/moves")),
            probe("GET /api/inventory/sessions", () -> get("/api/inventory/sessions")),
            probe("POST /api/inventory/sessions/{id}/apply", () -> post("/api/inventory/sessions/999999/apply")),
            probe("POST /api/inventory/sessions/{id}/counter-note", () -> json(post("/api/inventory/sessions/999999/counter-note"),
                            "{\"note\":\"проверка\",\"requestId\":\"perenos-1\"}")),
            probe("DELETE /api/text-blocks/{id}", () -> delete("/api/text-blocks/999999")),
            new Probe("DELETE /api/stock/documents/{id}", () -> delete("/api/stock/documents/999999"),
                    Set.of("OWNER", "ADMIN")),
            // sales
            probe("GET /api/deals/board", () -> get("/api/deals/board")),
            probe("GET /api/customers/directory", () -> get("/api/customers/directory")),
            probe("POST /api/deals/{id}/cancel", () -> post("/api/deals/999999/cancel")),
            probe("POST /api/deals/{id}/issue", () -> post("/api/deals/999999/issue")),
            probe("POST /api/customers/{customerId}/account/correct", () -> json(post("/api/customers/999999/account/correct"),
                            "{\"amount\":1,\"reason\":\"проверка\"}")),
            probe("GET /api/payments", () -> get("/api/payments")),
            // reports
            probe("GET /api/reports/summary", () -> get("/api/reports/summary")),
            // intake
            probe("POST /api/intake/donors/{id}/dismantling", () -> post("/api/intake/donors/999999/dismantling")),
            probe("GET /api/intake/supplies/{id}/expected-parts", () -> get("/api/intake/supplies/999999/expected-parts")),
            // publishing
            probe("GET /api/marketplace-accounts", () -> get("/api/marketplace-accounts")),
            probe("POST /api/marketplace-accounts/{id}/feed-url", () -> post("/api/marketplace-accounts/999999/feed-url")));

    private record Probe(String route, Supplier<MockHttpServletRequestBuilder> request, Set<String> expected) {
    }

    private static Probe probe(String route, Supplier<MockHttpServletRequestBuilder> request) {
        return new Probe(route, request, null);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---------------------------------------------------------- сверка матрицей

    /**
     * Сверяет пути вошедшего с эталоном его прежней роли.
     *
     * @return сколько решений сверено
     */
    private int compare(Map<String, Set<String>> baseline, String legacyRole, String who,
                        MockHttpSession tab, Map<String, String> mismatches) {
        AccessMatrix matrix = new AccessMatrix(context);
        Map<String, HandlerMethod> routes = matrix.routes();
        Authentication authentication = authenticationOf(tab);
        int compared = 0;
        for (var expected : baseline.entrySet()) {
            String route = expected.getKey();
            if (DECIDED_IN_SERVICE.containsKey(route)) {
                continue;
            }
            HandlerMethod handler = routes.get(route);
            if (handler == null) {
                if (!DECIDED_CHANGES.containsKey(route)) {
                    mismatches.put(route, "в эталоне есть, у приложения нет: путь снят или переименован — "
                            + "назовите решение в DECIDED_CHANGES");
                }
                continue;
            }
            String decision = matrix.decide(route, handler, authentication);
            boolean before = expected.getValue().contains(legacyRole);
            boolean now = decision.equals("allow");
            if ((before != now || decision.equals("error"))
                    && !DECIDED_CHANGES.containsKey(route + " " + legacyRole)) {
                mismatches.put(route + " · " + who,
                        (before ? "пропускал" : "не пропускал") + " → " + decision);
            }
            compared++;
        }
        return compared;
    }

    private Authentication authenticationOf(MockHttpSession tab) {
        String cookie = (String) tab.getAttribute("partsflow.test.sessionCookie");
        assertThat(cookie).as("вход оставил cookie сессии").isNotBlank();
        String id = new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
        Session session = sessions.findById(id);
        assertThat(session).as("сессия лежит в общем хранилище").isNotNull();
        SecurityContext security = session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return security.getAuthentication();
    }

    private static Map<String, Set<String>> baseline() throws Exception {
        Map<String, Set<String>> rows = new TreeMap<>();
        try (var in = LegacyAccessAfterDeployTest.class.getResourceAsStream(
                "/access/legacy-access-b07449e9.tsv");
             var reader = new BufferedReader(new InputStreamReader(
                     Objects.requireNonNull(in, "нет эталона"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cells = line.split("\t");
                Set<String> roles = new LinkedHashSet<>();
                if (!cells[1].equals("-")) {
                    roles.addAll(List.of(cells[1].split(",")));
                }
                rows.put(cells[0], roles);
            }
        }
        assertThat(rows).as("эталон прочитан").hasSizeGreaterThan(200);
        return rows;
    }

    // ------------------------------------------------------------- вход

    private MockHttpSession login(String company, String login) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(company, login)))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private static String credentials(String company, String login) {
        return "{\"company\":\"%s\",\"login\":\"%s\",\"password\":\"%s\"}".formatted(company, login, PASSWORD);
    }

    /** Администратор сервиса входит в кабинет по разрешению владельца — своей прежней строкой участника. */
    private MockHttpSession adminEnters(MockHttpSession owner) throws Exception {
        mvc.perform(post("/api/organization/admin-access/grant").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hours\":null}"))
                .andExpect(status().isOk());
        MockHttpSession admin = (MockHttpSession) mvc.perform(post("/api/admin/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN_LOGIN, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
        mvc.perform(post("/api/admin/tenants/" + ALL_CODE + "/enter").with(csrf()).session(admin))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + ALL
                + ".tenant_member WHERE role = 'ADMIN'", Integer.class))
                .as("вошёл прежней строкой участника, а не новой").isEqualTo(1);
        return admin;
    }

    // --------------------------------------------------------- наполнение

    private void registry(long id, String schema, String code) {
        jdbc.update("DELETE FROM public.admin_access_grant WHERE tenant_id = ?", id);
        jdbc.update("DELETE FROM public.admin_access_request WHERE tenant_id = ?", id);
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", id);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Перенос ролей', ?)""", id, schema, code);
    }

    /** Строка, которую заводит прежний образ уже после наката: одна колонка role, ссылки нет. */
    private void legacyMemberNow(String schema, String login, String role) {
        jdbc.update("DELETE FROM " + schema + ".tenant_member WHERE login = ?", login);
        jdbc.update("INSERT INTO " + schema + ".tenant_member (display_name, role, login, password_hash)"
                        + " VALUES (?, ?, ?, ?)",
                login, role, login, new BCryptPasswordEncoder().encode(PASSWORD));
    }

    /** Накатывает changeset'ы схемы до {@link #FIRST_NEW} — схема в том виде, в каком её застанет выкладка. */
    private static void beforeDeploy(String schema) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            statement.execute("CREATE SCHEMA " + schema);
        }
        try (Connection connection = connection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setLiquibaseSchemaName(schema);
            database.setDefaultSchemaName(schema);
            try (Liquibase liquibase = new Liquibase("db/changelog/db.changelog-tenant.xml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.setChangeLogParameter("tenant.schema", schema);
                List<ChangeSet> pending = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
                int before = -1;
                for (int i = 0; i < pending.size(); i++) {
                    if (pending.get(i).getId().equals(FIRST_NEW)) {
                        before = i;
                    }
                }
                assertThat(before).as("changeset " + FIRST_NEW + " найден").isPositive();
                liquibase.update(before, new Contexts(), new LabelExpression());
            }
        }
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            var rs = statement.executeQuery("SELECT to_regclass('" + schema + ".access_role')");
            rs.next();
            assertThat(rs.getString(1)).as("до выкладки ролей клиента ещё нет").isNull();
        }
    }

    private static void legacyMembers(String schema, String hash, List<String[]> members) throws Exception {
        try (Connection connection = connection();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO " + schema
                     + ".tenant_member (display_name, role, login, password_hash, is_active) VALUES (?, ?, ?, ?, ?)")) {
            for (String[] member : members) {
                insert.setString(1, "Сотрудник " + member[0]);
                insert.setString(2, member[1]);
                insert.setString(3, member[0]);
                insert.setString(4, hash);
                insert.setBoolean(5, Boolean.parseBoolean(member[2]));
                insert.executeUpdate();
            }
        }
    }

    /** Учётная запись администратора сервиса и его строка участника от визита до выкладки. */
    private static void legacyAdminRow(String schema) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM public.admin_access_request WHERE admin_id IN "
                    + "(SELECT id FROM public.service_admin WHERE login = '" + ADMIN_LOGIN + "')");
            statement.execute("DELETE FROM public.service_admin WHERE login = '" + ADMIN_LOGIN + "'");
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO public.service_admin (login, password_hash, display_name)
                    VALUES (?, ?, 'Поддержка переноса') RETURNING id""")) {
                insert.setString(1, ADMIN_LOGIN);
                insert.setString(2, new BCryptPasswordEncoder().encode(ADMIN_PASSWORD));
                var rs = insert.executeQuery();
                rs.next();
                statement.execute("INSERT INTO " + schema + ".tenant_member (display_name, role, service_admin_id)"
                        + " VALUES ('Поддержка переноса', 'ADMIN', " + rs.getLong(1) + ")");
            }
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
