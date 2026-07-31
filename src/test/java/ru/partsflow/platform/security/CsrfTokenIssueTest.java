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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Выдача CSRF-токена и вход настоящим токеном.
 *
 * <p><b>Почему отдельный класс.</b> Здесь намеренно не используется
 * {@code .with(csrf())}: этот помощник подставляет токен мимо репозитория,
 * и рядом с ним проверка теряет смысл — в общем прогоне класса он оставляет
 * за собой состояние, из-за которого репозиторий считает токен уже выданным
 * и cookie не пишет.
 *
 * <p>Именно из-за подстановки токена помощником и не был замечен настоящий баг:
 * {@code GET /api/auth/csrf} возвращал 204, не создавая токена вовсе. В Spring
 * Security 6 токен ленивый, cookie появляется только когда значение запросили.
 * Живой клиент получал пустой токен, вход отбивался фильтром CSRF и — поскольку
 * пользователь ещё анонимный — наружу это выходило как 401, то есть выглядело
 * неверным паролем.
 */
// Своё свойство здесь не настройка, а способ получить отдельный контекст Spring:
// .with(csrf()) в других классах подменяет репозиторий токенов прямо в цепочке
// фильтров, и подмена переживает границу класса, пока контекст общий. В общем
// контексте настоящий CSRF проверить невозможно.
@SpringBootTest(properties = "app.csrf-isolated-context=true")
@AutoConfigureMockMvc
class CsrfTokenIssueTest extends PostgresTestBase {

    private static final String TENANT = "t_000053";

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
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 53");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (53, ?, 'Компания для CSRF', 'csrfco')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM tenant_member");
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES ('Иван', 'STOREKEEPER', 'ivan', ?)""",
                    passwordEncoder.encode("пароль-ивана"));
            return null;
        });
    }

    @Test
    @DisplayName("Эндпоинт токена действительно ставит cookie")
    void csrfEndpointIssuesCookie() throws Exception {
        var response = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn()
                .getResponse();

        var cookie = response.getCookie("XSRF-TOKEN");
        assertThat(cookie).as("cookie с токеном не поставлена: токен ленивый и его надо запросить")
                .isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isHttpOnly())
                .as("токен должен читаться скриптом, иначе приложение не переложит его в заголовок")
                .isFalse();
    }

    @Test
    @DisplayName("Вход проходит токеном, полученным как настоящим клиентом")
    void loginWithTokenFromCookie() throws Exception {
        var cookie = mvc.perform(get("/api/auth/csrf"))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();

        mvc.perform(post("/api/auth/login")
                        .cookie(cookie)
                        .header("X-XSRF-TOKEN", cookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"csrfco","login":"ivan","password":"пароль-ивана"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companySchema").value(TENANT));
    }

    @Test
    @DisplayName("Вход без токена отбивается 403, а не 401")
    void loginWithoutTokenIsRejected() throws Exception {
        // Код важен для клиента. Отказ CSRF даёт именно 403: CsrfFilter вызывает
        // свой AccessDeniedHandler напрямую и до трансляции исключений дело
        // не доходит, поэтому анонимность роли не играет.
        //
        // Следствие для офлайн-очереди: 403 не всегда «вам нельзя». Просроченный
        // за часы офлайна токен даёт тот же 403, и клиент обязан обновить токен
        // и повторить один раз, прежде чем счесть ошибку постоянной. Иначе
        // вся накопленная смена уедет в «требует внимания».
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"csrfco","login":"ivan","password":"пароль-ивана"}"""))
                .andExpect(status().isForbidden());
    }

    private <T> T inTenant(Supplier<T> action) {
        try {
            TenantContext.set(TENANT);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }
}
