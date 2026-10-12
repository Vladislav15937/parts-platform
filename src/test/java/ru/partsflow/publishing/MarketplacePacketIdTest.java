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

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

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
            // Журнал правок выгрузок — только отсюда же: строки прежних
            // методов ссылались бы на выгрузку, которой уже нет.
            jdbc.update("DELETE FROM audit_log WHERE table_name = 'marketplace_account'");
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

    @Test
    @DisplayName("Правка номера видна в журнале изменений: кто, в какой роли, было → стало")
    void packetIdChangeIsJournaled() throws Exception {
        setPacketId("owner", "111").andExpect(status().isOk());
        setPacketId("owner", "222").andExpect(status().isOk());
        setPacketId("owner", "").andExpect(status().isOk());

        // Через экранный путь журнала, а не запросом в audit_log: решение
        // владельца — «через запись, которую читает экран журнала».
        // Свежие сверху: снятие, замена, вписывание.
        journal().andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].kind").value("Выгрузка"))
                .andExpect(jsonPath("$.items[0].subject").value("Новые"))
                .andExpect(jsonPath("$.items[0].subjectCode").doesNotExist())
                .andExpect(jsonPath("$.items[0].author").value("owner"))
                .andExpect(jsonPath("$.items[0].authorRole").value("OWNER"))
                .andExpect(jsonPath("$.items[0].changes[0].label").value("Номер прайс-листа"))
                .andExpect(jsonPath("$.items[0].changes[0].before").value("222"))
                .andExpect(jsonPath("$.items[0].changes[0].after").doesNotExist())
                .andExpect(jsonPath("$.items[1].changes[0].before").value("111"))
                .andExpect(jsonPath("$.items[1].changes[0].after").value("222"))
                .andExpect(jsonPath("$.items[2].changes[0].before").doesNotExist())
                .andExpect(jsonPath("$.items[2].changes[0].after").value("111"));
    }

    @Test
    @DisplayName("Сохранение того же номера журнал не засоряет")
    void unchangedPacketIdIsNotJournaled() throws Exception {
        setPacketId("owner", "111").andExpect(status().isOk());
        setPacketId("owner", "  111 ").andExpect(status().isOk());

        // Считается таблица, а не экран: экран правку без изменившегося поля
        // и так прячет, и проверка по нему зеленела бы на коде, пишущем
        // строку на каждое нажатие (проверено подделкой).
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE table_name = 'marketplace_account' AND record_id = ?""",
                Integer.class, accountId))).isEqualTo(1);
    }

    @Test
    @DisplayName("Ключ синхронизации в журнал не попадает ни в каком виде")
    void secretNeverReachesTheJournal() throws Exception {
        String secret = "ключ-синхронизации-7f3a";
        mvc.perform(put("/api/marketplace-accounts/" + accountId + "/credentials")
                        .with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"%s\"}".formatted(secret)))
                .andExpect(status().isNoContent());
        setPacketId("owner", "12345").andExpect(status().isOk());

        String body = journal().andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("12345").doesNotContain("7f3a");
        // И в самой таблице: экран показывает только известные колонки,
        // а снимок мог бы унести и лишнее.
        String stored = inTenant(() -> String.join(" ", jdbc.queryForList("""
                SELECT coalesce(old_value::text, '') || coalesce(new_value::text, '')
                  FROM audit_log WHERE table_name = 'marketplace_account' AND record_id = ?""",
                String.class, accountId)));
        assertThat(stored).contains("12345").doesNotContain("7f3a");
    }

    @Test
    @DisplayName("Форма заведения приводит номер тем же правилом, что и правка")
    void creationNormalizesPacketId() throws Exception {
        createFeed("Из адреса",
                "{\"packetId\":\" https://baza.drom.ru/personal/goods/packet/555/recurrent-update \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packetId").value("555"));
        createFeed("Пустой номер", "{\"packetId\":\"   \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packetId").doesNotExist());
        createFeed("Мусор", "{\"packetId\":\"12 345\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("не распознан")));

        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT jsonb_exists(settings, 'packetId') FROM marketplace_account
                 WHERE title = 'Пустой номер'""", Boolean.class))).isFalse();
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM marketplace_account WHERE title = 'Мусор'",
                Integer.class))).isZero();
    }

    private ResultActions createFeed(String title, String settings) throws Exception {
        String body = JSON.writeValueAsString(java.util.Map.of(
                "marketplace", "DROM", "title", title, "settings", settings,
                "productLine", "PART"));
        return mvc.perform(post("/api/marketplace-accounts")
                .with(csrf()).session(login("owner"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions journal() throws Exception {
        return mvc.perform(get("/api/organization/audit")
                .session(login("owner"))
                .param("kind", "Выгрузка")
                .param("q", "Новые"));
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
