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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Роль «Просмотр» не меняет ничего.
 *
 * <p>Так она названа владельцу — «только смотреть», — и заводят её тому,
 * кому дают посмотреть: бухгалтеру, новому человеку, знакомому. А на деле
 * она заводила детали на склад, машины, инвентаризации и удаляла
 * фотографии: у каждого из этих путей просто не было своей проверки роли.
 * Снимок при этом удаляется навсегда, а на разборке продаёт фотография —
 * и оригиналы переехавшего клиента после переноса остаются только у нас.
 *
 * <p><b>Проверяется правило, а не список путей.</b> Расставленная по методам,
 * такая проверка обязана быть повторена в каждом новом эндпоинте, и забыть
 * её можно в любом — что и случилось четырежды. Поэтому запрет стоит один,
 * на изменяющий метод, а тест берёт заведомо неполный набор путей: он ловит
 * не «забыли на этом эндпоинте», а «сняли правило целиком».
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class ViewerRoleTest extends PostgresTestBase {

    private static final String TENANT = "t_000101";

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
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 101");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (101, ?, 'Смотровая', 'viewco')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM tenant_member WHERE login IN ('viewer', 'keeper')");
            member("viewer", "VIEWER");
            member("keeper", "STOREKEEPER");
            return null;
        });
    }

    @Test
    @DisplayName("«Просмотр» не заводит детали, машины и пересчёты")
    void viewerCreatesNothing() throws Exception {
        MockHttpSession session = login("viewer");

        mvc.perform(post("/api/intake/receipts").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"viewer-1","warehouseId":1,
                                 "items":[{"rawName":"Фара","price":100,"quantity":1}]}"""))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/intake/donors").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brandId\":1,\"year\":2010}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/inventory/sessions").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":1}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Удаление снимка — потеря данных, и заметят её не сразу: карточка
     * без фотографии выглядит как карточка, которую просто не сняли.
     */
    @Test
    @DisplayName("«Просмотр» не удаляет фотографии")
    void viewerDeletesNothing() throws Exception {
        mvc.perform(delete("/api/parts/1/photos/1").with(csrf()).session(login("viewer")))
                .andExpect(status().isForbidden());
    }

    /**
     * Смотреть — можно, иначе роль теряет смысл вовсе.
     */
    @Test
    @DisplayName("«Просмотр» читает склад и справочники")
    void viewerStillReads() throws Exception {
        MockHttpSession session = login("viewer");

        mvc.perform(get("/api/parts/catalog?size=1").session(session))
                .andExpect(status().isOk());
        mvc.perform(get("/api/intake/reference").session(session))
                .andExpect(status().isOk());
        // Журнал пересчётов — тоже: ссылку «Пересчёт №4» в истории карточки
        // должен открыть кто угодно с доступом посмотреть, не только тот,
        // кто вправе провести или отменить.
        mvc.perform(get("/api/inventory/sessions").session(session))
                .andExpect(status().isOk());
    }

    /**
     * Запрет обязан различать роли, а не запрещать всем: кладовщик
     * принимает детали — это его работа.
     */
    @Test
    @DisplayName("Кладовщику запрет не мешает работать")
    void storekeeperStillWorks() throws Exception {
        mvc.perform(post("/api/intake/receipts").with(csrf()).session(login("keeper"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"keeper-1","warehouseId":1,
                                 "items":[{"rawName":"Фара","price":100,"quantity":1}]}"""))
                // Ответ про данные, а не про роль: важно лишь, что запрос
                // дошёл до сервиса, а не был отбит на входе.
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("кладовщику запретили его собственную работу")
                        .isNotEqualTo(403));
    }

    /** Выйти может и «Просмотр»: выход — это POST, а запрет смотрит на метод. */
    @Test
    @DisplayName("«Просмотр» может выйти")
    void viewerCanLogOut() throws Exception {
        mvc.perform(post("/api/auth/logout").with(csrf()).session(login("viewer")))
                .andExpect(status().isNoContent());
    }

    /**
     * Сессия берётся из ответа, а не заводится своя: вход пересоздаёт её
     * заново — иначе оставшаяся от прежнего входа сессия открыла бы подмену.
     */
    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"viewco","login":"%s","password":"пароль-подлиннее"}"""
                                .formatted(login)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void member(String login, String role) {
        jdbc.update("""
                INSERT INTO tenant_member (login, display_name, password_hash, role)
                VALUES (?, ?, ?, ?)""",
                login, login, passwordEncoder.encode("пароль-подлиннее"), role);
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
