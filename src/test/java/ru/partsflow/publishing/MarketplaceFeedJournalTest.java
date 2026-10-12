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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Журнал изменений видит любую правку выгрузки, а не один номер прайс-листа
 * (задача 0270).
 *
 * <p>Читается тем же путём, что и экран журнала: {@code GET /api/organization/audit}.
 * Тест, зовущий сервис и смотрящий в таблицу, не увидел бы, что строка не
 * доехала до экрана.
 */
@SpringBootTest(properties = "app.crypto.key=" + MarketplaceCredentialsTest.TEST_KEY)
@AutoConfigureMockMvc
class MarketplaceFeedJournalTest extends PostgresTestBase {

    private static final String TENANT = "t_000972";
    private static final String OLD_SECRET = "старый-ключ-5b1d";
    private static final String NEW_SECRET = "новый-ключ-9c4e";
    private static final String OLD_TOKEN = "oldtoken-aaaa1111bbbb2222cccc3333dddd4444";

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
    private Long warehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 972");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (972, ?, 'Разборка', 'feedjournal')""", TENANT);
        inTenant(() -> {
            jdbc.update("DELETE FROM audit_log WHERE table_name = 'marketplace_account'");
            jdbc.update("DELETE FROM marketplace_account");
            member("owner", "OWNER");
            if (jdbc.queryForList("SELECT id FROM warehouse WHERE name = 'Полка'").isEmpty()) {
                Long branch = jdbc.queryForObject(
                        "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
                jdbc.update("INSERT INTO warehouse (branch_id, name) VALUES (?, 'Полка')", branch);
            }
            warehouseId = jdbc.queryForObject(
                    "SELECT id FROM warehouse WHERE name = 'Полка'", Long.class);
            accountId = jdbc.queryForObject("""
                    INSERT INTO marketplace_account
                        (marketplace, title, credentials, feed_token, settings)
                    VALUES ('DROM', 'Новые', ?, ?, '{}'::jsonb) RETURNING id""",
                    Long.class, OLD_SECRET.getBytes(StandardCharsets.UTF_8), OLD_TOKEN);
            return null;
        });
    }

    @Test
    @DisplayName("Включение и выключение: состояние словами экрана, автор и роль снимком")
    void statusIsJournaled() throws Exception {
        send(put("/api/marketplace-accounts/" + accountId + "/status"),
                "{\"status\":\"PAUSED\"}").andExpect(status().isOk());

        journal().andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("Выгрузка"))
                .andExpect(jsonPath("$.items[0].subject").value("Новые"))
                .andExpect(jsonPath("$.items[0].author").value("owner"))
                .andExpect(jsonPath("$.items[0].authorRole").value("OWNER"))
                .andExpect(jsonPath("$.items[0].changes.length()").value(1))
                .andExpect(jsonPath("$.items[0].changes[0].label").value("Состояние выгрузки"))
                .andExpect(jsonPath("$.items[0].changes[0].before").value("Включена"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("Выключена"));
    }

    @Test
    @DisplayName("Переименование: было → стало, запись названа новым названием")
    void renameIsJournaled() throws Exception {
        send(put("/api/marketplace-accounts/" + accountId + "/title"),
                "{\"title\":\"Дорогие\"}").andExpect(status().isOk());

        journal().andExpect(jsonPath("$.items[0].subject").value("Дорогие"))
                .andExpect(jsonPath("$.items[0].changes[0].label").value("Название выгрузки"))
                .andExpect(jsonPath("$.items[0].changes[0].before").value("Новые"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("Дорогие"));
    }

    @Test
    @DisplayName("Настройки — по полю: меняется наценка, в строке одно поле, а не весь jsonb")
    void settingsAreJournaledByField() throws Exception {
        send(put("/api/marketplace-accounts/" + accountId + "/settings"),
                "{\"pricePercent\":5,\"photoLimit\":3}").andExpect(status().isOk());
        send(put("/api/marketplace-accounts/" + accountId + "/settings"),
                "{\"pricePercent\":15,\"photoLimit\":3}").andExpect(status().isOk());

        // Свежие сверху: сначала наценка 5 → 15, одна строка, одно поле.
        journal().andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].changes.length()").value(1))
                .andExpect(jsonPath("$.items[0].changes[0].label").value("Наценка, %"))
                .andExpect(jsonPath("$.items[0].changes[0].before").value("5"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("15"))
                .andExpect(jsonPath("$.items[1].changes.length()").value(2))
                .andExpect(jsonPath("$.items[1].changes[0].label").value("Наценка, %"))
                .andExpect(jsonPath("$.items[1].changes[1].label")
                        .value("Число снимков в объявлении"));
    }

    @Test
    @DisplayName("Сохранение тех же настроек строки не пишет")
    void unchangedSettingsAreNotJournaled() throws Exception {
        send(put("/api/marketplace-accounts/" + accountId + "/settings"),
                "{\"pricePercent\":5}").andExpect(status().isOk());
        send(put("/api/marketplace-accounts/" + accountId + "/settings"),
                "{\"pricePercent\":5.00}").andExpect(status().isOk());
        send(put("/api/marketplace-accounts/" + accountId + "/status"),
                "{\"status\":\"ACTIVE\"}").andExpect(status().isOk());

        // Считается таблица, а не экран: экран строку без изменившегося поля
        // и так прячет, и проверка по нему зеленела бы на коде, пишущем строку
        // на каждое нажатие.
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("Отбор: цена, состояния, склады — названиями, а не номерами")
    void filterIsJournaled() throws Exception {
        send(put("/api/marketplace-accounts/" + accountId + "/filter"), """
                {"priceFrom":100,"priceTo":900,"conditions":["USED"],
                 "warehouseIds":[%d],"kindsExcluded":false,"brandsExcluded":false}"""
                .formatted(warehouseId)).andExpect(status().isOk());

        journal().andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].changes[?(@.label=='Отбор: цена от')].after")
                        .value("100"))
                .andExpect(jsonPath("$.items[0].changes[?(@.label=='Отбор: цена до')].after")
                        .value("900"))
                .andExpect(jsonPath("$.items[0].changes[?(@.label=='Отбор: состояния')].after")
                        .value("б/у"))
                .andExpect(jsonPath("$.items[0].changes[?(@.label=='Отбор: склады')].after")
                        .value("Полка"));

        // Тот же отбор ещё раз — ничего не изменилось, строки нет.
        send(put("/api/marketplace-accounts/" + accountId + "/filter"), """
                {"priceFrom":100,"priceTo":900,"conditions":["USED"],
                 "warehouseIds":[%d],"kindsExcluded":false,"brandsExcluded":false}"""
                .formatted(warehouseId)).andExpect(status().isOk());
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("Имя файла прайса: было → стало")
    void feedFileNameIsJournaled() throws Exception {
        send(put("/api/marketplace-accounts/" + accountId + "/feed-file"),
                "{\"fileName\":\"drom-novye.xml\"}").andExpect(status().isOk());

        journal().andExpect(jsonPath("$.items[0].changes[0].label").value("Имя файла прайса"))
                .andExpect(jsonPath("$.items[0].changes[0].before").doesNotExist())
                .andExpect(jsonPath("$.items[0].changes[0].after").value("drom-novye.xml"));
    }

    @Test
    @DisplayName("Замена ключа и ссылки — фактом: значения нет ни в журнале, ни в таблице")
    void secretsAreOnlyFacts() throws Exception {
        putSecret("/credentials", NEW_SECRET);
        mvc.perform(post("/api/marketplace-accounts/" + accountId + "/feed-url")
                        .with(csrf()).session(login("owner")))
                .andExpect(status().isOk());
        String newToken = inTenant(() -> jdbc.queryForObject(
                "SELECT feed_token FROM marketplace_account WHERE id = ?",
                String.class, accountId));

        String body = journal().andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String stored = inTenant(() -> String.join(" ", jdbc.queryForList("""
                SELECT coalesce(old_value::text, '') || coalesce(new_value::text, '')
                  FROM audit_log WHERE table_name = 'marketplace_account'""", String.class)));
        for (String text : new String[]{body, stored}) {
            assertThat(text).doesNotContain("5b1d").doesNotContain("9c4e")
                    .doesNotContain(OLD_TOKEN).doesNotContain(newToken);
        }
        journal().andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].changes[0].label").value("Ссылка на прайс"))
                .andExpect(jsonPath("$.items[0].changes[0].before").value("задан"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("заменён"))
                .andExpect(jsonPath("$.items[1].changes[0].label").value("Ключ синхронизации"))
                .andExpect(jsonPath("$.items[1].changes[0].after").value("заменён"));

        assertThat(newToken).isNotEqualTo(OLD_TOKEN);
    }

    @Test
    @DisplayName("Замена ключа: прежний отказ забыт и в журнале ровно одна запись «заменён»")
    void replacedKeyForgetsRefusalAndIsJournaledOnce() throws Exception {
        // Шов задач 0268 и 0270 в setCredentials: одно сохранение ключа
        // обязано дать оба следствия — ни одно не вытесняет другое.
        inTenant(() -> jdbc.update("""
                UPDATE marketplace_account
                   SET settings = '{"packetId":"777"}'::jsonb,
                       last_sync_at = now(), last_error = 'ERROR_REASON_AUTH_FAILED'
                 WHERE id = ?""", accountId));
        mvc.perform(get("/api/marketplace-accounts").session(login("owner")))
                .andExpect(jsonPath("$[?(@.title=='Новые')].deliveryStatus.state").value("REFUSED"));

        putSecret("/credentials", NEW_SECRET);

        mvc.perform(get("/api/marketplace-accounts").session(login("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title=='Новые')].deliveryStatus.state").value("WAITING"));
        assertThat(rows()).isEqualTo(1);
        journal().andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].changes.length()").value(1))
                .andExpect(jsonPath("$.items[0].changes[0].label").value("Ключ синхронизации"))
                .andExpect(jsonPath("$.items[0].changes[0].before").value("задан"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("заменён"));
    }

    @Test
    @DisplayName("Удаление видно в журнале при скрытой выгрузке, названное её именем")
    void deletionStaysInTheJournal() throws Exception {
        mvc.perform(delete("/api/marketplace-accounts/" + accountId)
                        .with(csrf()).session(login("owner")))
                .andExpect(status().isNoContent());

        journal().andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].action").value("Выгрузка удалена"))
                .andExpect(jsonPath("$.items[0].subject").value("Новые"))
                .andExpect(jsonPath("$.items[0].author").value("owner"));
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM marketplace_account WHERE id = ?",
                Boolean.class, accountId))).isTrue();
    }

    @Test
    @DisplayName("Заведение пишется событием; номер, если задан, в нём есть, пустой — нет")
    void creationIsJournaled() throws Exception {
        create("С номером", "{\"packetId\":\"555\"}").andExpect(status().isCreated());
        create("Без номера", "{\"packetId\":\"  \"}").andExpect(status().isCreated());

        journal().andExpect(jsonPath("$.items[0].subject").value("Без номера"))
                .andExpect(jsonPath("$.items[0].action").value("Выгрузка заведена"))
                .andExpect(jsonPath("$.items[1].subject").value("С номером"))
                .andExpect(jsonPath("$.items[1].action")
                        .value("Выгрузка заведена, номер прайс-листа 555"));
    }

    @Test
    @DisplayName("Откат правки не оставляет записи о том, чего не было")
    void rolledBackEditLeavesNoEntry() {
        inTenant(() -> accounts.setStatus(accountId, "PAUSED"));
        assertThat(rows()).isEqualTo(1);

        TenantContext.set(TENANT);
        try {
            transactionTemplate.execute(tx -> {
                accounts.setStatus(accountId, "ACTIVE");
                tx.setRollbackOnly();
                return null;
            });
        } finally {
            TenantContext.clear();
        }
        assertThat(rows()).isEqualTo(1);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM marketplace_account WHERE id = ?",
                String.class, accountId))).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("Меню отбора по виду предлагает «Выгрузка»")
    void kindMenuOffersFeeds() throws Exception {
        mvc.perform(get("/api/organization/audit/values").param("column", "kind")
                        .session(login("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@=='Выгрузка')]").exists());
    }

    // ----------------------------------------------------------------- помощники

    private void putSecret(String path, String secret) throws Exception {
        send(put("/api/marketplace-accounts/" + accountId + path),
                "{\"secret\":\"%s\"}".formatted(secret)).andExpect(status().isNoContent());
    }

    private ResultActions create(String title, String settings) throws Exception {
        return send(post("/api/marketplace-accounts"), new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(java.util.Map.of("marketplace", "DROM", "title", title,
                        "settings", settings, "productLine", "PART")));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String body) throws Exception {
        return mvc.perform(request.with(csrf()).session(login("owner"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions journal() throws Exception {
        return mvc.perform(get("/api/organization/audit")
                .session(login("owner")).param("kind", "Выгрузка"));
    }

    private int rows() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE table_name = 'marketplace_account'",
                Integer.class));
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
                                {"company":"feedjournal","login":"%s","password":"пароль"}"""
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
