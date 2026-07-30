package ru.partsflow.platform.security;

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
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Вход и изоляция арендаторов через сессию.
 *
 * <p>До этого арендатор приходил заголовком {@code X-Tenant-Id}, и опубликованное
 * в интернет API давало чтение чужого склада всякому, кто подставит другой номер.
 * Здесь проверяется, что этого больше нет и что вход не работает справочником
 * действующих компаний.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationTest extends PostgresTestBase {

    private static final String TENANT_A = "t_000042";
    private static final String TENANT_B = "t_000043";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT_A, TENANT_B);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id IN (42, 43)");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (42, ?, 'YARD Ткацкая', 'yardt'), (43, ?, 'Вторая разборка', 'second')""",
                TENANT_A, TENANT_B);

        // Один и тот же логин у двух арендаторов: между клиентами он уникальным
        // быть не обязан, и это одна из причин держать учётки внутри схемы.
        member(TENANT_A, "ivan", "секрет-42", "Иван", "STOREKEEPER", true);
        member(TENANT_B, "ivan", "секрет-43", "Иван из второй", "SELLER", true);
        member(TENANT_A, "petr", "секрет-петра", "Пётр", "SELLER", false);
    }

    @Test
    @DisplayName("Вход с верными данными открывает сессию")
    void loginSucceeds() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("yardt", "ivan", "секрет-42")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Иван"))
                .andExpect(jsonPath("$.role").value("STOREKEEPER"))
                .andExpect(jsonPath("$.companySchema").value(TENANT_A));
    }

    @Test
    @DisplayName("Неверный пароль, чужая компания и несуществующий логин отвечают одинаково")
    void failuresAreIndistinguishable() throws Exception {
        // Разные коды или формулировки превратили бы форму входа в справочник
        // действующих компаний и сотрудников — а клиенты конкурируют друг с другом.
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("yardt", "ivan", "не-тот-пароль")))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("нет-такой", "ivan", "секрет-42")))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("yardt", "никого-нет", "секрет-42")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Отключённый сотрудник не входит")
    void disabledMemberCannotLogIn() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("yardt", "petr", "секрет-петра")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Пароль в базе не лежит открытым текстом")
    void passwordIsHashed() {
        String hash = inTenant(TENANT_A, () -> jdbc.queryForObject(
                "SELECT password_hash FROM tenant_member WHERE login = 'ivan'", String.class));

        assertThat(hash).doesNotContain("секрет-42").startsWith("$2");
    }

    @Test
    @DisplayName("Без входа API отвечает 401, а не редиректом на форму")
    void anonymousRequestIsRejected() throws Exception {
        // Редирект офлайн-очередь разберёт как успех и удалит запись, потеряв
        // работу приёмщика.
        mvc.perform(get("/api/parts/search").param("q", "фара"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Заголовок X-Tenant-Id доступа не даёт")
    void tenantHeaderGrantsNothing() throws Exception {
        mvc.perform(get("/api/parts/search").param("q", "фара").header("X-Tenant-Id", "42"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("После входа запросы идут в схему своего арендатора")
    void sessionCarriesTenant() throws Exception {
        MockHttpSession session = login("yardt", "ivan", "секрет-42");

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companySchema").value(TENANT_A));
    }

    @Test
    @DisplayName("Одинаковый логин у двух клиентов ведёт в разные схемы")
    void sameLoginInTwoTenantsStaysSeparate() throws Exception {
        MockHttpSession first = login("yardt", "ivan", "секрет-42");
        MockHttpSession second = login("second", "ivan", "секрет-43");

        mvc.perform(get("/api/auth/me").session(first))
                .andExpect(jsonPath("$.companySchema").value(TENANT_A))
                .andExpect(jsonPath("$.role").value("STOREKEEPER"));

        mvc.perform(get("/api/auth/me").session(second))
                .andExpect(jsonPath("$.companySchema").value(TENANT_B))
                .andExpect(jsonPath("$.role").value("SELLER"));
    }

    @Test
    @DisplayName("Пароль одного клиента не подходит другому")
    void passwordDoesNotCrossTenants() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("second", "ivan", "секрет-42")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Выход закрывает сессию")
    void logoutEndsSession() throws Exception {
        MockHttpSession session = login("yardt", "ivan", "секрет-42");

        mvc.perform(post("/api/auth/logout").with(csrf()).session(session))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Вход отмечается на сотруднике")
    void loginIsRecorded() throws Exception {
        login("yardt", "ivan", "секрет-42");

        assertThat(inTenant(TENANT_A, () -> jdbc.queryForObject(
                "SELECT last_login_at IS NOT NULL FROM tenant_member WHERE login = 'ivan'",
                Boolean.class))).isTrue();
    }

    @Test
    @DisplayName("Приостановленный арендатор не пускает никого")
    void suspendedTenantCannotLogIn() throws Exception {
        jdbc.update("UPDATE public.tenant_registry SET status = 'SUSPENDED' WHERE tenant_id = 42");

        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("yardt", "ivan", "секрет-42")))
                .andExpect(status().isUnauthorized());
    }

    // ---------- вспомогательное ----------

    private MockHttpSession login(String company, String login, String password) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(company, login, password)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String body(String company, String login, String password) {
        return """
                {"company":"%s","login":"%s","password":"%s"}"""
                .formatted(company, login, password);
    }

    private void member(String schema, String login, String password, String displayName,
                        String role, boolean active) {
        inTenant(schema, () -> {
            jdbc.update("DELETE FROM tenant_member WHERE lower(btrim(login)) = ?", login);
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash, is_active)
                    VALUES (?, ?, ?, ?, ?)""",
                    displayName, role, login, passwordEncoder.encode(password), active);
            return null;
        });
    }

    private <T> T inTenant(String schema, Supplier<T> action) {
        try {
            TenantContext.set(schema);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }
}
