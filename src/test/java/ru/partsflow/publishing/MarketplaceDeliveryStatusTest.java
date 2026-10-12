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

import com.jayway.jsonpath.JsonPath;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    @DisplayName("Принято и отказы: причина приезжает перечислением, статус и незнакомый ответ — как есть")
    void afterSends() throws Exception {
        feed("Принято", true, "1", "ACTIVE", true, null);
        feed("Нет прайса", true, "2", "ACTIVE", true, "[HTTP 200] ERROR_REASON_PACKET_NOT_FOUND");
        feed("Ключ", true, "3", "ACTIVE", true, "[HTTP 403] {\"error\":\"ERROR_REASON_AUTH_FAILED\"}");
        feed("Пусто", true, "4", "ACTIVE", true, "[HTTP 200] ERROR_REASON_EMPTY_REQUEST");
        feed("Большая", true, "5", "ACTIVE", true, "[TOO_BIG] дельта превысила лимит 5242880 байт");
        feed("Срок", true, "6", "ACTIVE", true, "[TIMEOUT] request timed out");
        feed("Имя", true, "7", "ACTIVE", true, "[DNS] UnresolvedAddressException");
        feed("Незнакомый код", true, "8", "ACTIVE", true, "[HTTP 200] ERROR_REASON_SOMETHING_NEW");
        feed("Пятисотка", true, "9", "ACTIVE", true,
                "[HTTP 502] <!DOCTYPE html><html><title>502 Bad Gateway</title></html>");
        feed("Странный", true, "10", "ACTIVE", true, "[HTTP 200] {\"status\":\"maintenance\"}");

        assertState("Принято", "ACCEPTED");
        assertCode("Нет прайса", "PACKET_NOT_FOUND");
        assertCode("Ключ", "AUTH_FAILED");
        assertCode("Пусто", "EMPTY_REQUEST");
        assertCode("Большая", "TOO_BIG");
        assertCode("Срок", "TIMEOUT");
        assertCode("Имя", "HOST_UNKNOWN");
        // Незнакомый код не теряется, не становится «принято» и едет сам.
        assertCode("Незнакомый код", "UNKNOWN_REASON");
        assertCode("Пятисотка", "HTTP_ERROR");
        assertCode("Странный", "UNRECOGNIZED");
        mvc.perform(get("/api/marketplace-accounts").session(login()))
                .andExpect(jsonPath("$[?(@.title=='Незнакомый код')].deliveryStatus.detail").value(
                        org.hamcrest.Matchers.hasItem("ERROR_REASON_SOMETHING_NEW")))
                // Метка — наша внутренняя запись: наружу уходит только текст площадки.
                .andExpect(jsonPath("$[?(@.title=='Незнакомый код')].lastError").value(
                        org.hamcrest.Matchers.hasItem("ERROR_REASON_SOMETHING_NEW")))
                .andExpect(jsonPath("$[?(@.title=='Пятисотка')].lastError").value(
                        org.hamcrest.Matchers.hasItem(
                                "<!DOCTYPE html><html><title>502 Bad Gateway</title></html>")))
                .andExpect(jsonPath("$[?(@.title=='Срок')].lastError").value(
                        org.hamcrest.Matchers.hasItem("request timed out")))
                // Запрос сверх лимита не уходил — «Ответа Дрома» у него нет.
                .andExpect(jsonPath("$[?(@.title=='Большая')].lastError").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$[*].lastError").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.nullValue(),
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.startsWith("["))))))
                .andExpect(jsonPath("$[?(@.title=='Пятисотка')].deliveryStatus.httpStatus").value(
                        org.hamcrest.Matchers.hasItem(502)))
                .andExpect(jsonPath("$[?(@.title=='Пятисотка')].deliveryStatus.answerForm").value(
                        org.hamcrest.Matchers.hasItem("PAGE")))
                .andExpect(jsonPath("$[?(@.title=='Пятисотка')].deliveryStatus.detail").value(
                        org.hamcrest.Matchers.hasItem("502 Bad Gateway")))
                .andExpect(jsonPath("$[?(@.title=='Странный')].deliveryStatus.detail").value(
                        org.hamcrest.Matchers.hasItem("{\"status\":\"maintenance\"}")))
                .andExpect(jsonPath("$[?(@.title=='Принято')].deliveryStatus.at").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.notNullValue())));
    }

    /**
     * Заменили номер или ключ — вчерашний отказ про прежние не висит до следующей
     * попытки: владелец поправил то, что ему велели, и не видит прежнего упрёка.
     */
    @Test
    @DisplayName("Смена номера или ключа забывает прежний отказ; тот же номер — нет")
    void changedPacketOrKeyForgetsOldRefusal() throws Exception {
        long packet = feed("Номер", true, "1", "ACTIVE", true, "[HTTP 200] ERROR_REASON_PACKET_NOT_FOUND");
        long key = feed("Ключ", true, "2", "ACTIVE", true, "[HTTP 200] ERROR_REASON_AUTH_FAILED");
        long same = feed("Тот же", true, "3", "ACTIVE", true, "[HTTP 200] ERROR_REASON_PACKET_NOT_FOUND");
        MockHttpSession session = login();

        mvc.perform(put("/api/marketplace-accounts/" + packet + "/packet-id").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"packetId\":\"777\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/marketplace-accounts/" + key + "/credentials").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"0b8e3c1a-1111-2222-3333-444455556666\"}"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(put("/api/marketplace-accounts/" + same + "/packet-id").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"packetId\":\"3\"}"))
                .andExpect(status().isOk());

        // Все три разом, одной сверкой: откат одной половины правки называет
        // именно её, а не прячет вторую за первым упавшим утверждением.
        String body = mvc.perform(get("/api/marketplace-accounts").session(session))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        java.util.Map<String, String> states = new java.util.TreeMap<>();
        for (String title : List.of("Номер", "Ключ", "Тот же")) {
            List<String> found = JsonPath.read(body,
                    "$[?(@.title=='%s')].deliveryStatus.state".formatted(title));
            states.put(title, found.get(0));
        }
        // Ничего не поменялось — и отказ по-прежнему правда.
        assertThat(states).isEqualTo(java.util.Map.of(
                "Номер", "WAITING", "Ключ", "WAITING", "Тот же", "REFUSED"));
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
        feed("Вынут", true, null, "ACTIVE", true, "[HTTP 200] ERROR_REASON_PACKET_NOT_FOUND");

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

    private long feed(String title, boolean key, String packetId, String status,
                      boolean sent, String lastError) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO marketplace_account
                    (marketplace, title, credentials, settings, status, last_sync_at, last_error)
                VALUES ('DROM', ?, ?, ?::jsonb, ?, CASE WHEN ? THEN now() END, ?)
                RETURNING id""", Long.class,
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
