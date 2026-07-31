package ru.partsflow.publishing.drom;

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
 * Постоянная ссылка на прайс Дрома.
 *
 * <p>Единственный путь в системе, открытый без сессии и отдающий данные склада.
 * Поэтому проверяется прежде всего то, чего он делать не должен: пускать
 * по чужому токену, по одному коду компании, и — главное — отдавать склад
 * одного арендатора по ссылке другого.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class DromFeedControllerTest extends PostgresTestBase {

    private static final String TENANT = "t_000066";
    private static final String OTHER_TENANT = "t_000067";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long accountId;
    private String feedPath;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT, OTHER_TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        register(66, TENANT, "feedco");
        register(67, OTHER_TENANT, "otherco");

        inTenant(TENANT, () -> {
            jdbc.update("DELETE FROM marketplace_account");
            member("owner", "OWNER");
            member("manager", "MANAGER");
            accountId = jdbc.queryForObject("""
                    INSERT INTO marketplace_account (marketplace, title, settings)
                    VALUES ('DROM', 'Кабинет', '{"packetId":"777"}'::jsonb) RETURNING id""",
                    Long.class);
            part("Фара левая Camry");
            return null;
        });

        inTenant(OTHER_TENANT, () -> {
            jdbc.update("DELETE FROM marketplace_account");
            part("Бампер чужой разборки");
            return null;
        });

        feedPath = rotate();
    }

    @Test
    @DisplayName("Дром забирает прайс по ссылке без всякого входа")
    void feedIsServedWithoutSession() throws Exception {
        String body = mvc.perform(get(feedPath))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Cookie у сервера площадки нет и не будет: права даёт секрет в адресе.
        assertThat(body).contains("Фара левая Camry");
    }

    @Test
    @DisplayName("Прайс не кэшируется: склад меняется между заборами")
    void feedIsNotCached() throws Exception {
        mvc.perform(get(feedPath))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Cache-Control"))
                        .isEqualTo("no-store"));
    }

    @Test
    @DisplayName("Чужой токен склад не открывает")
    void wrongTokenIsRejected() throws Exception {
        mvc.perform(get("/feeds/drom/feedco/" + "x".repeat(43) + ".xml"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Одного кода компании мало")
    void companyCodeAloneIsNotEnough() throws Exception {
        // Код компании публичен — он же логин в форму входа и будущий
        // поддомен. Доступ открывает только токен.
        mvc.perform(get("/feeds/drom/feedco/.xml"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Токен одного арендатора не открывает склад другого")
    void tokenDoesNotCrossTenants() throws Exception {
        String token = feedPath.substring(feedPath.lastIndexOf('/') + 1, feedPath.length() - 4);

        // Ровно та утечка, ради предотвращения которой убран X-Tenant-Id:
        // подставить чужой код компании и получить чужой склад.
        mvc.perform(get("/feeds/drom/otherco/" + token + ".xml"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Несуществующая компания отвечает так же, как неверный токен")
    void unknownCompanyLooksTheSame() throws Exception {
        mvc.perform(get("/feeds/drom/нетакой/" + "y".repeat(43) + ".xml"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Смена ссылки ломает прежнюю")
    void rotationInvalidatesOldLink() throws Exception {
        String old = feedPath;
        String fresh = rotate();

        assertThat(fresh).isNotEqualTo(old);
        // Прайс у площадки замрёт, пока новую ссылку не пропишет её
        // техспециалист — поэтому смена и сделана отдельным действием.
        mvc.perform(get(old)).andExpect(status().isNotFound());
        mvc.perform(get(fresh)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Ссылку видит владелец и управляющий, но не заводит управляющий")
    void feedUrlIsOwnerBusiness() throws Exception {
        mvc.perform(get("/api/marketplace-accounts/" + accountId + "/feed-url")
                        .session(login("manager")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(feedPath));

        mvc.perform(post("/api/marketplace-accounts/" + accountId + "/feed-url")
                        .with(csrf()).session(login("manager")))
                .andExpect(status().isForbidden());
    }

    private String rotate() throws Exception {
        String body = mvc.perform(post("/api/marketplace-accounts/" + accountId + "/feed-url")
                        .with(csrf()).session(login("owner")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return body.replaceAll("^\\{\"path\":\"(.*)\"\\}$", "$1");
    }

    private void register(long id, String schema, String code) {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", id);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка', ?)""", id, schema, code);
    }

    private void part(String title) {
        Long branch = jdbc.queryForObject(
                "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
        Long warehouse = jdbc.queryForObject(
                "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад') RETURNING id",
                Long.class, branch);
        Long partId = jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, cost_price, is_published)
                VALUES (1, ?, 5000, 2000, true) RETURNING id""", Long.class, title);
        jdbc.update("""
                INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id)
                VALUES (?, 'INTAKE', 1, ?)""", partId, warehouse);
    }

    private void member(String login, String role) {
        if (!jdbc.queryForList("SELECT id FROM tenant_member WHERE login = ?", login).isEmpty()) {
            return;
        }
        jdbc.update("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?)""", login, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"feedco","login":"%s","password":"пароль"}"""
                                .formatted(login)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
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
