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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Итог последней отправки на Дром в списке выгрузок (задача 0268).
 *
 * <p>Прогон через HTTP: ответ собирает Jackson, и тест, зовущий сервис, не
 * увидел бы, что вложенный record не доехал до экрана.
 */
@SpringBootTest(properties = "app.crypto.key=" + MarketplaceCredentialsTest.TEST_KEY)
@AutoConfigureMockMvc
class MarketplaceDeliveryStatusTest extends PostgresTestBase {

    private static final String TENANT = "t_000971";

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
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 971");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (971, ?, 'Разборка', 'deliveryco')""", TENANT);
        inTenant(() -> {
            jdbc.update("DELETE FROM marketplace_account");
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'owner'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('owner', 'OWNER', 'owner', ?)""", passwordEncoder.encode("пароль"));
            }
            return null;
        });
    }

    @Test
    @DisplayName("До первой отправки говорит, чего не хватает; «принято» не пишет")
    void beforeTheFirstSend() throws Exception {
        feed("Ни того ни другого", false, null, "ACTIVE", false, null);
        feed("Нет номера", true, null, "ACTIVE", false, null);
        feed("Нет ключа", false, "12345", "ACTIVE", false, null);
        feed("Всё есть", true, "12345", "ACTIVE", false, null);

        assertState("Ни того ни другого", "NOT_CONNECTED");
        assertState("Нет номера", "NO_PACKET");
        assertState("Нет ключа", "NO_KEY");
        // Контрольная: подмена «нет исхода -> принято» валит именно эту проверку.
        assertState("Всё есть", "WAITING");
        mvc.perform(get("/api/marketplace-accounts").session(login()))
                .andExpect(jsonPath("$[?(@.title=='Всё есть')].deliveryStatus.at").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    @DisplayName("Принято и отказы: код приезжает перечислением, время — у любой попытки")
    void afterSends() throws Exception {
        feed("Принято", true, "1", "ACTIVE", true, null);
        feed("Нет прайса", true, "2", "ACTIVE", true, "ERROR_REASON_PACKET_NOT_FOUND");
        feed("Ключ", true, "3", "ACTIVE", true, "ERROR_REASON_AUTH_FAILED");
        feed("Пусто", true, "4", "ACTIVE", true, "ERROR_REASON_EMPTY_REQUEST");
        feed("Большая", true, "5", "ACTIVE", true, "дельта превысила лимит 5242880");
        feed("Сеть", true, "6", "ACTIVE", true, "Нет ответа от Дрома: timeout");
        feed("Иное", true, "7", "ACTIVE", true, "ERROR_REASON_SOMETHING_NEW");
        feed("Пятисотка", true, "8", "ACTIVE", true, "");

        assertState("Принято", "ACCEPTED");
        assertCode("Нет прайса", "PACKET_NOT_FOUND");
        assertCode("Ключ", "AUTH_FAILED");
        assertCode("Пусто", "EMPTY_REQUEST");
        assertCode("Большая", "TOO_BIG");
        assertCode("Сеть", "NO_ANSWER");
        // Незнакомый код не теряется и не превращается в «принято».
        assertCode("Иное", "OTHER");
        assertCode("Пятисотка", "OTHER");
        mvc.perform(get("/api/marketplace-accounts").session(login()))
                .andExpect(jsonPath("$[?(@.title=='Иное')].lastError").value(
                        org.hamcrest.Matchers.hasItem("ERROR_REASON_SOMETHING_NEW")))
                .andExpect(jsonPath("$[?(@.title=='Принято')].deliveryStatus.at").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.notNullValue())));
    }

    @Test
    @DisplayName("Выключенной выгрузке состояние отправки не показывается вовсе")
    void pausedHasNoStatus() throws Exception {
        feed("Выключена", true, "1", "PAUSED", true, "ERROR_REASON_PACKET_NOT_FOUND");

        mvc.perform(get("/api/marketplace-accounts").session(login()))
                .andExpect(jsonPath("$[0].deliveryStatus").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("Вынули номер после отказа — говорит «номера нет», а не вчерашний отказ")
    void missingPacketBeatsOldRefusal() throws Exception {
        feed("Вынут", true, null, "ACTIVE", true, "ERROR_REASON_PACKET_NOT_FOUND");

        assertState("Вынут", "NO_PACKET");
    }

    private void assertState(String title, String state) throws Exception {
        mvc.perform(get("/api/marketplace-accounts").session(login()))
                .andExpect(jsonPath("$[?(@.title=='%s')].deliveryStatus.state".formatted(title))
                        .value(org.hamcrest.Matchers.hasItem(state)));
    }

    private void assertCode(String title, String code) throws Exception {
        assertState(title, "REFUSED");
        mvc.perform(get("/api/marketplace-accounts").session(login()))
                .andExpect(jsonPath("$[?(@.title=='%s')].deliveryStatus.code".formatted(title))
                        .value(org.hamcrest.Matchers.hasItem(code)));
    }

    private void feed(String title, boolean key, String packetId, String status,
                      boolean sent, String lastError) {
        inTenant(() -> jdbc.update("""
                INSERT INTO marketplace_account
                    (marketplace, title, credentials, settings, status, last_sync_at, last_error)
                VALUES ('DROM', ?, ?, ?::jsonb, ?, CASE WHEN ? THEN now() END, ?)""",
                title,
                key ? "ключ".getBytes(StandardCharsets.UTF_8) : null,
                packetId == null ? "{}" : "{\"packetId\": \"" + packetId + "\"}",
                status, sent, lastError));
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"deliveryco","login":"owner","password":"пароль"}"""))
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
}
