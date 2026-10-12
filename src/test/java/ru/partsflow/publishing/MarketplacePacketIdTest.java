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
import ru.partsflow.publishing.drom.DromAccountReader;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Номер прайс-листа Дрома у уже заведённой выгрузки (задача 0267).
 *
 * <p>Прогон через HTTP: ответ собирает Jackson, и тест, зовущий сервис, не
 * увидел бы, что поле не доехало до экрана.
 */
@SpringBootTest(properties = "app.crypto.key=" + MarketplaceCredentialsTest.TEST_KEY)
@AutoConfigureMockMvc
class MarketplacePacketIdTest extends PostgresTestBase {

    private static final String TENANT = "t_000970";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private DromAccountReader reader;

    private Long accountId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 970");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (970, ?, 'Разборка', 'packetco')""", TENANT);
        inTenant(() -> {
            jdbc.update("DELETE FROM marketplace_account");
            member("owner", "OWNER");
            member("manager", "MANAGER");
            member("seller", "SELLER");
            // Ключ задан, номера нет: состояние владельца, который ввёл ключ,
            // а номер получит только после размещения прайса у площадки.
            accountId = jdbc.queryForObject("""
                    INSERT INTO marketplace_account (marketplace, title, credentials, settings)
                    VALUES ('DROM', 'Новые', ?, '{}'::jsonb) RETURNING id""",
                    Long.class, "ключ".getBytes(StandardCharsets.UTF_8));
            return null;
        });
    }

    @Test
    @DisplayName("Номер записывается, виден в списке и открывает выгрузку для дельт")
    void packetIdIsStoredAndShown() throws Exception {
        assertThat(canReceiveDelta()).isFalse();

        setPacketId("owner", "12345").andExpect(status().isOk())
                .andExpect(jsonPath("$.packetId").value("12345"));

        mvc.perform(get("/api/marketplace-accounts").session(login("owner")))
                .andExpect(jsonPath("$[0].packetId").value("12345"));
        assertThat(canReceiveDelta()).isTrue();
    }

    @Test
    @DisplayName("Замена номера меняет его, а не дописывает")
    void packetIdCanBeReplaced() throws Exception {
        setPacketId("owner", "111").andExpect(status().isOk());
        setPacketId("owner", "222").andExpect(status().isOk());

        assertThat(storedPacketId()).isEqualTo("222");
        assertThat(inTenant(() -> reader.active(null).get(0).packetId())).isEqualTo("222");
    }

    @Test
    @DisplayName("Пустое значение снимает номер: ключа в settings нет, а не пустая строка")
    void blankRemovesTheKey() throws Exception {
        setPacketId("owner", "12345").andExpect(status().isOk());

        setPacketId("owner", "   ").andExpect(status().isOk())
                .andExpect(jsonPath("$.packetId").doesNotExist());

        // Пустая строка в jsonb тоже не слала бы дельт, поэтому проверяем ключ.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT jsonb_exists(settings, 'packetId') FROM marketplace_account WHERE id = ?",
                Boolean.class, accountId))).isFalse();
        assertThat(canReceiveDelta()).isFalse();
    }

    @Test
    @DisplayName("Номер и настройки сборки не стирают друг друга")
    void neighboursSurvive() throws Exception {
        saveSettings("{\"pricePercent\":10}").andExpect(status().isOk());

        setPacketId("owner", "12345").andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.pricePercent").value(10));

        // Обратное: сохранение настроек номер на месте оставляет.
        saveSettings("{\"pricePercent\":15}").andExpect(status().isOk())
                .andExpect(jsonPath("$.packetId").value("12345"));
        assertThat(storedPacketId()).isEqualTo("12345");
    }

    @Test
    @DisplayName("Пробелы по краям режутся, из адреса прайс-листа берётся номер")
    void inputIsNormalized() throws Exception {
        setPacketId("owner", "  777  ").andExpect(jsonPath("$.packetId").value("777"));
        setPacketId("owner",
                "https://baza.drom.ru/personal/goods/packet/12345/recurrent-update")
                .andExpect(jsonPath("$.packetId").value("12345"));
    }

    @Test
    @DisplayName("Номер с пробелом внутри или длиннее 32 знаков отвергается словами, запись не меняется")
    void garbageIsRejected() throws Exception {
        setPacketId("owner", "12345").andExpect(status().isOk());

        setPacketId("owner", "12 345").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("не распознан")));
        setPacketId("owner", "1".repeat(33)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("не распознан")));

        assertThat(storedPacketId()).isEqualTo("12345");
    }

    @Test
    @DisplayName("Номер правит только владелец: управляющий и продавец получают 403")
    void onlyOwnerSetsPacketId() throws Exception {
        setPacketId("manager", "1").andExpect(status().isForbidden());
        setPacketId("seller", "1").andExpect(status().isForbidden());
        assertThat(storedPacketId()).isNull();
    }

    private ResultActions setPacketId(String login, String value) throws Exception {
        return mvc.perform(put("/api/marketplace-accounts/" + accountId + "/packet-id")
                .with(csrf()).session(login(login))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packetId\":\"%s\"}".formatted(value)));
    }

    private ResultActions saveSettings(String json) throws Exception {
        return mvc.perform(put("/api/marketplace-accounts/" + accountId + "/settings")
                .with(csrf()).session(login("owner"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private boolean canReceiveDelta() {
        return inTenant(() -> reader.active(null).get(0).canReceiveDelta());
    }

    private String storedPacketId() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT settings ->> 'packetId' FROM marketplace_account WHERE id = ?",
                String.class, accountId));
    }

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
                                {"company":"packetco","login":"%s","password":"пароль"}"""
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
}
