package ru.partsflow.publishing;

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
import ru.partsflow.platform.crypto.SecretCipher;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ключ кабинета площадки в базе.
 *
 * <p>Схема обещала шифрование с самого начала, а кода не было: дамп базы
 * давал доступ к кабинету клиента на Дроме. Здесь проверяется весь путь —
 * ключ вводят через REST, в базу он ложится зашифрованным, обратно наружу
 * не отдаётся никогда, а выгрузка им пользуется.
 */
@SpringBootTest(properties = "app.crypto.key=" + MarketplaceCredentialsTest.TEST_KEY)
@AutoConfigureMockMvc
class MarketplaceCredentialsTest extends PostgresTestBase {

    /** Тестовый ключ. В бою приходит переменной окружения. */
    static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static final String TENANT = "t_000065";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MarketplaceAccountService accounts;

    private Long accountId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 65");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (65, ?, 'Разборка', 'cryptoco')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM marketplace_account");
            member("owner", "OWNER");
            member("seller", "SELLER");

            accountId = jdbc.queryForObject("""
                    INSERT INTO marketplace_account (marketplace, title, settings)
                    VALUES ('DROM', 'Кабинет', '{"packetId":"777"}'::jsonb) RETURNING id""",
                    Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Введённый ключ ложится в базу зашифрованным")
    void secretIsStoredEncrypted() throws Exception {
        setSecret("owner", "живой-ключ-кабинета");

        byte[] stored = inTenant(() -> jdbc.queryForObject(
                "SELECT credentials FROM marketplace_account WHERE id = ?",
                byte[].class, accountId));

        // Ровно то, ради чего всё: дамп базы кабинет клиента не открывает.
        assertThat(new String(stored, StandardCharsets.UTF_8))
                .doesNotContain("живой-ключ-кабинета");
        assertThat(SecretCipher.isEncrypted(stored)).isTrue();
    }

    @Test
    @DisplayName("Выгрузка получает расшифрованный ключ")
    void publishingReadsSecret() throws Exception {
        setSecret("owner", "живой-ключ-кабинета");

        assertThat(inTenant(() -> accounts.secretOf(accountId).orElse(null)))
                .isEqualTo("живой-ключ-кабинета");
    }

    @Test
    @DisplayName("Ключ наружу не отдаётся ни в каком виде")
    void secretIsNeverReturned() throws Exception {
        setSecret("owner", "живой-ключ-кабинета");

        String body = mvc.perform(get("/api/marketplace-accounts").session(login("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hasCredentials").value(true))
                .andReturn().getResponse().getContentAsString();

        // Метод «показать ключ» отсутствует намеренно: право читать настройки
        // не должно превращаться в доступ к кабинету.
        assertThat(body).doesNotContain("живой-ключ-кабинета");
        assertThat(body).doesNotContain("credentials\":\"");
    }

    @Test
    @DisplayName("Продавец ключ кабинета не заводит")
    void sellerCannotSetSecret() throws Exception {
        mvc.perform(put("/api/marketplace-accounts/" + accountId + "/credentials")
                        .with(csrf()).session(login("seller"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"чужой-ключ\"}"))
                .andExpect(status().isForbidden());

        assertThat(inTenant(() -> accounts.secretOf(accountId).orElse(null))).isNull();
    }

    @Test
    @DisplayName("Замена ключа перешифровывает, а не дописывает")
    void secretCanBeRotated() throws Exception {
        setSecret("owner", "первый-ключ");
        setSecret("owner", "второй-ключ");

        assertThat(inTenant(() -> accounts.secretOf(accountId).orElse(null)))
                .isEqualTo("второй-ключ");
    }

    @Test
    @DisplayName("Ключ, лежащий открытым текстом, виден в списке")
    void plaintextSecretIsFlagged() throws Exception {
        // Так выглядят записи, заведённые до появления шифрования.
        inTenant(() -> jdbc.update("UPDATE marketplace_account SET credentials = ? WHERE id = ?",
                "старый-ключ".getBytes(StandardCharsets.UTF_8), accountId));

        // Чинит это человек, поэтому знать об этом он должен из интерфейса,
        // а не из журнала приложения.
        mvc.perform(get("/api/marketplace-accounts").session(login("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].plaintextSecret").value(true));
    }

    @Test
    @DisplayName("Пустой ключ не принимается")
    void blankSecretIsRejected() throws Exception {
        mvc.perform(put("/api/marketplace-accounts/" + accountId + "/credentials")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    private void setSecret(String login, String secret) throws Exception {
        mvc.perform(put("/api/marketplace-accounts/" + accountId + "/credentials")
                        .with(csrf()).session(login(login))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"%s\"}".formatted(secret)))
                .andExpect(status().isNoContent());
    }

    /** Сотрудник заводится один раз: удалять его мешают ссылки из документов. */
    private void member(String login, String role) {
        if (!jdbc.queryForList("SELECT id FROM tenant_member WHERE login = ?", login).isEmpty()) {
            return;
        }
        jdbc.update("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?)""",
                login, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"cryptoco","login":"%s","password":"пароль"}"""
                                .formatted(login)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }

    private void inTenant(Runnable body) {
        inTenant(() -> {
            body.run();
            return null;
        });
    }
}
