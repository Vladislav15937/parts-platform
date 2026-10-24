package ru.partsflow.platform.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import ru.partsflow.platform.admin.AdminAccessController;
import ru.partsflow.platform.admin.ServiceAdminPrincipal;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Отказ по праву называет право словами — на каждом пути кабинета (задача 0044,
 * живой прогон волны 24.10d).
 *
 * <p>Перебор, а не список: пути берутся у самого приложения
 * ({@code RequestMappingHandlerMapping}), и каждый вызывается сотрудником без единого
 * права — номер, которого нет, тело {@code {}}. Каждый ответ 403 обязан назвать
 * право («Нет права «…»» или «Нет ни одного из прав: …»), а не голое «Недостаточно
 * прав». Так нашёлся единственный путь без слов — удаление выполненного документа:
 * у него нет {@code @PreAuthorize}, право решает {@code PostedDocumentDeletion}, и
 * отказ шёл обычным {@code AccessDeniedException}.
 *
 * <p>Ответы, отличные от 403, перебор не судит: тело, не прошедшее разбор, отвечает
 * 400 раньше права, а путь, который вошедшему без прав открыт, — тем, чем отвечает.
 * Чтобы пустой перебор не прошёл сам собой, отказов обязано быть не меньше
 * {@link #MIN_REFUSALS} (на 24.10.2026 у сотрудника без прав их 109). Схема своя — {@code t_000403}.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class RefusalWordsSweepTest extends PostgresTestBase {

    private static final String TENANT = "t_000403";
    private static final String PASSWORD = "пароль-длинный";

    /** Сколько отказов по праву перебор обязан увидеть — иначе он перебирает не то. */
    private static final int MIN_REFUSALS = 100;

    /**
     * Пути, которые перебор не зовёт, — с причиной у каждого. Вход и выход меняют саму
     * сессию перебора; система администрирования и управляющий контур — другие
     * вошедшие и свои секреты, прав сотрудника у них нет.
     */
    private static final Map<String, String> NOT_SWEPT = Map.of(
            "/api/auth/", "вход и выход: меняют сессию самого перебора, прав сотрудника у них нет",
            "/api/admin/", "система администрирования: вошедший не сотрудник кабинета",
            "/api/provisioning/", "управляющий контур: свой секрет, а не право сотрудника",
            "/api/registration/", "регистрация компании: открыта всем без входа",
            "/api/mail/events/", "отчёты оператора писем: свой секрет в адресе");

    @Autowired
    private MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

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

    @Test
    @DisplayName("Каждый отказ по праву на каждом пути кабинета называет право словами, а не «Недостаточно прав»")
    void everyRefusalNamesTheRight() throws Exception {
        MockHttpSession session = employeeWithoutRights();
        int refusals = 0;
        Map<String, String> bare = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            for (String pattern : entry.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/") || NOT_SWEPT.keySet().stream().anyMatch(pattern::startsWith)) {
                    continue;
                }
                for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
                    MockHttpServletResponse response = mvc.perform(request(method, pattern, session))
                            .andReturn().getResponse();
                    if (response.getStatus() != 403) {
                        continue;
                    }
                    refusals++;
                    String body = response.getContentAsString(StandardCharsets.UTF_8);
                    if (!body.contains("\"message\":\"Нет ") || body.contains("Недостаточно прав")) {
                        bare.put(method + " " + pattern, body);
                    }
                }
            }
        }
        assertThat(bare).as("отказ по праву без слов — назовите право (PathRightRefusal.words)").isEmpty();
        assertThat(refusals).as("перебор увидел слишком мало отказов — он перебирает не то").isGreaterThanOrEqualTo(MIN_REFUSALS);
        assertThat(NOT_SWEPT.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    /**
     * Путь доступа администратора стоит формой {@code admin_access.manage and !hasRole('ADMIN')}
     * (разбор PR 518). Вошедшему, который не сотрудник кабинета (администратор
     * в своей системе), «нет права … попросите владельца» неправда: права кабинета
     * к нему не относятся. Слов нет — остаётся общий отказ.
     */
    @Test
    @DisplayName("Вошедшему не сотруднику кабинета отказ словами не называет права кабинета и не шлёт к владельцу")
    void nonCabinetPrincipalGetsNoCabinetWords() {
        HandlerMethod grant = handlerMapping.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().equals(AdminAccessController.class))
                .filter(handler -> handler.getMethod().isAnnotationPresent(
                        org.springframework.web.bind.annotation.PostMapping.class))
                .findFirst().orElseThrow();
        ServiceAdminPrincipal admin = new ServiceAdminPrincipal(1L, "admin", "Администратор");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        try {
            assertThat(PathRightRefusal.of(grant)).as("слова кабинета вошедшему не из кабинета").isEmpty();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private MockHttpServletRequestBuilder request(RequestMethod method, String pattern, MockHttpSession session) {
        String path = pattern.replaceAll("\\{[^}]+}", "987654");
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders
                .request(HttpMethod.valueOf(method.name()), path).session(session).with(csrf());
        if (method == RequestMethod.POST || method == RequestMethod.PUT || method == RequestMethod.PATCH) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return builder;
    }

    /** Сотрудник с ролью без единого права. */
    private MockHttpSession employeeWithoutRights() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 403");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (403, ?, 'Слова отказа', 'slova403')""", TENANT);
        inTenant(() -> {
            jdbc.update("DELETE FROM tenant_member WHERE login = 'bezprav'");
            jdbc.update("DELETE FROM access_role WHERE name = 'Без прав'");
            long role = jdbc.queryForObject("INSERT INTO access_role (name) VALUES ('Без прав') RETURNING id",
                    Long.class);
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash, access_role_id)
                    VALUES ('Без прав', 'VIEWER', 'bezprav', ?, ?)""", passwordEncoder.encode(PASSWORD), role);
            return null;
        });
        return (MockHttpSession) mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"slova403\",\"login\":\"bezprav\",\"password\":\"%s\"}"
                                .formatted(PASSWORD)))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
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
