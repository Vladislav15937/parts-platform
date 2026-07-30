package ru.partsflow.platform.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.partsflow.support.PostgresTestBase;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Создание арендатора одной операцией.
 *
 * <p>Раньше клиент заводился в четыре приёма руками, и забыть один шаг ничего
 * не стоило. Поэтому главная проверка тут не «запись появилась», а «в компанию
 * можно войти и сразу работать»: провижининг, после которого нужен ещё один
 * шаг, ничем не лучше прежнего.
 */
@SpringBootTest(properties = "app.provisioning-token=секрет-провижининга")
@AutoConfigureMockMvc
class TenantProvisioningTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantProvisioning provisioning;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Созданный арендатор сразу рабочий: владелец входит и видит справочники")
    void newTenantIsImmediatelyUsable() throws Exception {
        String code = uniqueCode();

        TenantProvisioning.Result created = provisioning.provision(
                new TenantProvisioning.Request(code, "Разборка на Ткацкой",
                        "vladelec", "пароль-владельца", "Владелец"));

        assertThat(created.schemaName()).matches("t_\\d{6}");

        // Вход — это и есть проверка всей цепочки: схема создана, миграции
        // накатаны, владелец заведён, запись в реестре активна.
        MockHttpSession session = login(code, "vladelec", "пароль-владельца");

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));

        // Справочники приёмки читаются из схемы клиента: пустые, но живые —
        // значит таблицы на месте, а не «схема создана и пуста».
        mvc.perform(get("/api/intake/reference").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouses").isArray());
    }

    @Test
    @DisplayName("Арендатор становится активным только после миграций")
    void tenantIsActiveOnlyWhenReady() {
        String code = uniqueCode();
        TenantProvisioning.Result created = provisioning.provision(
                new TenantProvisioning.Request(code, "Компания", "owner", "пароль-8симв", null));

        // Релей outbox идёт по ACTIVE: увидев полусозданную схему без таблицы
        // outbox, он валился бы каждый заход.
        assertThat(statusOf(created.tenantId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT migrated_at IS NOT NULL FROM public.tenant_registry WHERE tenant_id = ?",
                Boolean.class, created.tenantId())).isTrue();
    }

    @Test
    @DisplayName("Занятый код компании отвергается, схема не создаётся")
    void duplicateCodeIsRejected() {
        String code = uniqueCode();
        provisioning.provision(new TenantProvisioning.Request(
                code, "Первая", "owner", "пароль-8симв", null));

        long before = tenantCount();

        assertThatThrownBy(() -> provisioning.provision(new TenantProvisioning.Request(
                code, "Вторая", "owner2", "пароль-8симв", null)))
                .hasMessageContaining("занят");

        assertThat(tenantCount())
                .as("после отказа осталась висячая запись реестра")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("Код компании проверяется: он станет поддоменом")
    void codeIsValidated() {
        assertThatThrownBy(() -> provisioning.provision(new TenantProvisioning.Request(
                "Разборка №1", "Компания", "owner", "пароль-8симв", null)))
                .hasMessageContaining("Код компании");
    }

    @Test
    @DisplayName("Слабый пароль владельца не принимается")
    void weakOwnerPasswordIsRejected() {
        assertThatThrownBy(() -> provisioning.provision(new TenantProvisioning.Request(
                uniqueCode(), "Компания", "owner", "123", null)))
                .hasMessageContaining("минимум 8");
    }

    @Test
    @DisplayName("Без секрета арендатора не создать")
    void secretIsRequired() throws Exception {
        mvc.perform(post("/api/provisioning/tenants").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"не тот","companyCode":"%s","companyName":"К",
                                 "ownerLogin":"o","ownerPassword":"пароль-8симв"}"""
                                .formatted(uniqueCode())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Создание идёт без входа: арендатора ещё не существует")
    void worksWithoutSession() throws Exception {
        String code = uniqueCode();

        mvc.perform(post("/api/provisioning/tenants").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"секрет-провижининга","companyCode":"%s",
                                 "companyName":"Разборка","ownerLogin":"hozyain",
                                 "ownerPassword":"пароль-8симв"}""".formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyCode").value(code));

        assertThat(login(code, "hozyain", "пароль-8симв")).isNotNull();
    }

    @Test
    @DisplayName("Создание идёт без CSRF-токена")
    void worksWithoutCsrfToken() throws Exception {
        String code = uniqueCode();

        // Cookie в запросе не участвуют, авторизует его секрет в теле:
        // подделывать нечего. Требовать токен значило бы заставить того,
        // кто подключает клиента, сначала сходить за ним на пустой сайт.
        mvc.perform(post("/api/provisioning/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"секрет-провижининга","companyCode":"%s",
                                 "companyName":"Разборка","ownerLogin":"bez-csrf",
                                 "ownerPassword":"пароль-8симв"}""".formatted(code)))
                .andExpect(status().isCreated());
    }

    private String statusOf(long tenantId) {
        return jdbc.queryForObject(
                "SELECT status FROM public.tenant_registry WHERE tenant_id = ?",
                String.class, tenantId);
    }

    private long tenantCount() {
        return jdbc.queryForObject("SELECT count(*) FROM public.tenant_registry", Long.class);
    }

    /** Код должен быть свой у каждого теста: реестр общий и не чистится. */
    private static String uniqueCode() {
        return "co" + UUID.randomUUID().toString().substring(0, 8);
    }

    private MockHttpSession login(String company, String login, String password) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"%s","password":"%s"}"""
                                .formatted(company, login, password)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
