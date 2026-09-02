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
 * Управление сотрудниками и первые проверки роли в проекте.
 *
 * <p>До этого {@code tenant_member.role} существовал, но не проверялся нигде:
 * вошедший мог всё. Здесь проверяется, что продавец не заведёт себе владельца
 * и не сменит владельцу пароль.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemberManagementTest extends PostgresTestBase {

    private static final String TENANT = "t_000052";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long ownerId;
    private Long sellerId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 52");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (52, ?, 'Тестовая разборка', 'testco')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM tenant_member");
            ownerId = jdbc.queryForObject("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES ('Владелец', 'OWNER', 'owner', ?) RETURNING id""",
                    Long.class, passwordEncoder.encode("пароль-владельца"));
            sellerId = jdbc.queryForObject("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES ('Продавец', 'SELLER', 'seller', ?) RETURNING id""",
                    Long.class, passwordEncoder.encode("пароль-продавца"));
            return null;
        });
    }

    @Test
    @DisplayName("Владелец создаёт сотрудника")
    void ownerCreatesMember() throws Exception {
        MockHttpSession session = login("owner", "пароль-владельца");

        mvc.perform(post("/api/members").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"kladovshik","password":"пароль-кладовщика",
                                 "displayName":"Кладовщик","role":"STOREKEEPER"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.login").value("kladovshik"))
                .andExpect(jsonPath("$.role").value("STOREKEEPER"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("Созданный сотрудник сразу может войти")
    void createdMemberCanLogIn() throws Exception {
        MockHttpSession session = login("owner", "пароль-владельца");
        mvc.perform(post("/api/members").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"novyi","password":"пароль-нового","role":"SELLER"}"""))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"testco","login":"novyi","password":"пароль-нового"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SELLER"));
    }

    @Test
    @DisplayName("Продавец сотрудников не создаёт")
    void sellerCannotCreateMembers() throws Exception {
        MockHttpSession session = login("seller", "пароль-продавца");

        // Иначе продавец завёл бы себе владельца и забрал компанию.
        mvc.perform(post("/api/members").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"свой-владелец","password":"пароль-подлиннее","role":"OWNER"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Продавец не видит список сотрудников")
    void sellerCannotListMembers() throws Exception {
        mvc.perform(get("/api/members").session(login("seller", "пароль-продавца")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Продавец не меняет пароль владельцу")
    void sellerCannotChangeOthersPassword() throws Exception {
        MockHttpSession session = login("seller", "пароль-продавца");

        mvc.perform(post("/api/members/" + ownerId + "/password").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"мой-новый-пароль"}"""))
                .andExpect(status().isForbidden());

        // Прежний пароль владельца продолжает работать.
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"testco","login":"owner","password":"пароль-владельца"}"""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Свой пароль сотрудник меняет сам")
    void memberChangesOwnPassword() throws Exception {
        MockHttpSession session = login("seller", "пароль-продавца");

        mvc.perform(post("/api/members/" + sellerId + "/password").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"новый-пароль-продавца"}"""))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"testco","login":"seller","password":"новый-пароль-продавца"}"""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Короткий пароль отбивается")
    void shortPasswordIsRejected() throws Exception {
        MockHttpSession session = login("owner", "пароль-владельца");

        mvc.perform(post("/api/members").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"korotkiy","password":"1234","role":"SELLER"}"""))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Занятый логин отбивается")
    void duplicateLoginIsRejected() throws Exception {
        MockHttpSession session = login("owner", "пароль-владельца");

        mvc.perform(post("/api/members").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"Seller ","password":"пароль-подлиннее","role":"VIEWER"}"""))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Отключённый сотрудник перестаёт входить")
    void disabledMemberLosesAccess() throws Exception {
        MockHttpSession session = login("owner", "пароль-владельца");

        mvc.perform(post("/api/members/" + sellerId + "/disable").with(csrf()).session(session))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"testco","login":"seller","password":"пароль-продавца"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Владелец не отключает сам себя")
    void ownerCannotLockHimselfOut() throws Exception {
        MockHttpSession session = login("owner", "пароль-владельца");

        // Единственный владелец запер бы компанию, и починить это можно было бы
        // только руками в БД.
        //
        // Отказ обязан быть словами, а не пустым 409. Экран показывает кнопку
        // «Выключить» всем, включая самого вошедшего, и в коде так и написано:
        // «последнего отобьёт сервер с объяснением». Объяснения не было — тело
        // ответа пустое, — и владелец видел «Запрос отклонён (409)»: ни что
        // случилось, ни что делать. Отказ без объяснения читается как поломка,
        // а это правило.
        mvc.perform(post("/api/members/" + ownerId + "/disable").with(csrf()).session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Себя")));
    }



    @Test
    @DisplayName("Вход без CSRF-токена не проходит")
    void loginStillRequiresCsrf() throws Exception {
        // Подделка входа — реальная атака: жертву логинят в чужой аккаунт,
        // и дальше её действия видит владелец аккаунта.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"testco","login":"owner","password":"пароль-владельца"}"""))
                .andExpect(status().isForbidden());
    }



    @Test
    @DisplayName("Без входа сотрудников не создать")
    void anonymousCannotCreateMembers() throws Exception {
        mvc.perform(post("/api/members").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"kto-to","password":"пароль-подлиннее","role":"OWNER"}"""))
                .andExpect(status().isUnauthorized());
    }

    // ---------- вспомогательное ----------

    private MockHttpSession login(String login, String password) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"testco","login":"%s","password":"%s"}"""
                                .formatted(login, password)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(Supplier<T> action) {
        try {
            TenantContext.set(TENANT);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }

    private void inTenant(Runnable action) {
        inTenant(() -> {
            action.run();
            return null;
        });
    }
}
