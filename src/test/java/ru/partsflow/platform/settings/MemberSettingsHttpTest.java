package ru.partsflow.platform.settings;

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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Настройка экрана за сотрудником (задача 0060).
 *
 * <p><b>Зачем через HTTP.</b> Здесь три вещи, которых вызов сервиса
 * не проверяет вовсе. Первая: настройка своя — сотрудник берётся из сессии,
 * а не из запроса, и чужую не прочитать даже зная её. Вторая: «Просмотр»
 * обязан уметь её записать, хотя общее правило запрещает ему PUT — своя
 * сортировка не данные предприятия, а разложенный у себя экран; отказ здесь
 * означал бы, что настройка молча не сохраняется ровно у той роли, которая
 * на витрину и смотрит. Третья: ответ — record, иначе Jackson отдаёт
 * пятисотку «No acceptable representation».
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class MemberSettingsHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000149";

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
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 149");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (149, ?, 'Разборка настроек', 'settingsco')""", TENANT);

        inTenant(() -> {
            // Фикстура пишет то, что называет, а не принимает найденное:
            // схема своя, и заводить её заново дешевле, чем потом гадать,
            // чьим именем назван владелец.
            jdbc.update("DELETE FROM member_setting");
            jdbc.update("DELETE FROM tenant_member");
            member("vladelec", "Владелец", "OWNER");
            member("smotritel", "Смотритель", "VIEWER");
            return null;
        });
    }

    @Test
    @DisplayName("Настройка возвращается своему сотруднику и не видна чужому")
    void settingsAreKeptPerMember() throws Exception {
        MockHttpSession owner = login("vladelec");

        mvc.perform(get("/api/me/settings/catalog").session(owner))
                .andExpect(status().isOk())
                // Экран, который ещё не настраивали, — это пусто, а не отказ:
                // витрина обязана открыться умолчанием.
                .andExpect(jsonPath("$.value").doesNotExist());

        mvc.perform(put("/api/me/settings/catalog").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sort":"price","desc":true,"visible":["number","title"]}"""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/me/settings/catalog").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value.sort").value("price"))
                .andExpect(jsonPath("$.value.visible[0]").value("number"));

        // Своего идентификатора в адресе нет: настройка всегда своя, и чужую
        // не прочитать даже зная, что она есть.
        mvc.perform(get("/api/me/settings/catalog").session(login("smotritel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").doesNotExist());
    }

    /**
     * «Просмотр» настраивает свой экран, но по-прежнему ничего не меняет
     * в данных предприятия.
     *
     * <p>Обе половины обязательны. Без первой настройка молча не сохраняется
     * у той роли, которая на витрину и смотрит: каждое нажатие уезжает
     * отказом, и человек об этом узнаёт, только вернувшись завтра. Без второй
     * исключение из правила «„Просмотр“ не меняет ничего» разрослось бы
     * на весь {@code /api/**} — а оно размером в один адрес.
     */
    @Test
    @DisplayName("«Просмотр» сохраняет свою настройку, но склад ему по-прежнему не править")
    void viewerKeepsOwnScreenButNothingElse() throws Exception {
        MockHttpSession viewer = login("smotritel");

        mvc.perform(put("/api/me/settings/catalog").with(csrf()).session(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sort":"number","desc":false}"""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/me/settings/catalog").session(viewer))
                .andExpect(jsonPath("$.value.sort").value("number"));

        mvc.perform(put("/api/parts/1").with(csrf()).session(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Фара\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/parts/publication").with(csrf()).session(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partIds\":[1],\"published\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Мусор вместо настройки отбивается словами, а не размером базы")
    void oversizedSettingIsRefusedInWords() throws Exception {
        MockHttpSession owner = login("vladelec");

        String huge = "я".repeat(9000);
        mvc.perform(put("/api/me/settings/catalog").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"" + huge + "\"}"))
                .andExpect(status().isBadRequest());

        // Имя экрана — ключ строки, и мусором оно быть не должно.
        mvc.perform(put("/api/me/settings/Catalog_1").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private void member(String login, String name, String role) {
        jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES (?, ?, ?, ?)""",
                name, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"settingsco","login":"%s","password":"пароль"}"""
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
