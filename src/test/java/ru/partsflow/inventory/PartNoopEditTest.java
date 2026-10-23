package ru.partsflow.inventory;

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
import ru.partsflow.support.PostgresTestBase;

import java.sql.Timestamp;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сохранение карточки без единой перемены — не правка: «Кто изменил» и дата
 * правки остаются прежними (прогон экрана ролей, сессия 24.10.2026).
 *
 * <p>Наблюдали: {@code PUT /api/parts/{id}} с телом {@code {}} от менеджера
 * поставил в карточке «Кто изменил: Мария Менеджерова» — при том что ни одно
 * поле не поменялось. Автор писался безусловно ({@code touchedBy} после всех
 * полей) с 2 августа 2026, то есть задолго до 0044; с задачи 0307 экран
 * без перемен запроса не шлёт, а пустую правку присылают прямой запрос, вкладка,
 * открытая до выкладки (форма целиком, те же значения), и значение, равное
 * хранимому. Автор — ответ на вопрос «кто это поменял», и человек, ничего
 * не поменявший, ответом быть не может.
 *
 * <p>Обе стороны обязательны: правка, переставшая подписывать вообще, тоже
 * «чинила» бы пустое сохранение.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartNoopEditTest extends PostgresTestBase {

    private static final String TENANT = "t_000764";
    private static final String COMPANY = "noop764";
    private static final String PASSWORD = "пароль-пустой-правки";
    private static final Timestamp BEFORE = Timestamp.valueOf("2026-01-01 10:00:00");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long owner;
    private long partId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 764");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (764, ?, 'Пустая правка', ?)""", TENANT, COMPANY);
        owner = member("vladelec764", "Пётр Владельцев", "OWNER");
        member("menedzher764", "Мария Менеджерова", "MANAGER");
        partId = jdbc.queryForObject("INSERT INTO " + TENANT + ".part"
                + " (category_id, title, price, note, updated_by, updated_at)"
                + " VALUES (1, 'Фара', 1000, 'заметка', ?, ?) RETURNING id", Long.class, owner, BEFORE);
    }

    @Test
    @DisplayName("Пустое тело — автор и дата правки прежние")
    void emptyBodyIsNotAnEdit() throws Exception {
        save("{}");
        assertThat(stamp()).as("сохранение без перемен подписано тем, кто ничего не менял")
                .containsEntry("updated_by", owner)
                .containsEntry("updated_at", BEFORE);
    }

    @Test
    @DisplayName("Значение, равное хранимому, — тоже не правка")
    void sameValueIsNotAnEdit() throws Exception {
        save("{\"note\":\"заметка\",\"price\":1000}");
        assertThat(stamp()).containsEntry("updated_by", owner).containsEntry("updated_at", BEFORE);
    }

    @Test
    @DisplayName("Настоящая правка подписана тем, кто правил")
    void realEditIsSigned() throws Exception {
        save("{\"note\":\"другая заметка\"}");
        Map<String, Object> stamp = stamp();
        assertThat(stamp.get("updated_by")).as("автор правки").isNotEqualTo(owner);
        assertThat(stamp.get("updated_at")).as("дата правки сдвинулась").isNotEqualTo(BEFORE);
    }

    private void save(String body) throws Exception {
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(login("menedzher764"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private Map<String, Object> stamp() {
        return jdbc.queryForMap("SELECT updated_by, updated_at FROM " + TENANT + ".part WHERE id = ?", partId);
    }

    private MockHttpSession login(String login) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"%s\",\"login\":\"%s\",\"password\":\"%s\"}"
                                .formatted(COMPANY, login, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    /** Фикстура пишет то, что называет: имя, роль и пароль — заново при каждом методе. */
    private long member(String login, String name, String role) {
        jdbc.update("DELETE FROM " + TENANT + ".login_session");
        var found = jdbc.queryForList("SELECT id FROM " + TENANT + ".tenant_member WHERE login = ?",
                Long.class, login);
        if (!found.isEmpty()) {
            jdbc.update("UPDATE " + TENANT + ".tenant_member SET display_name = ?, role = ?,"
                            + " password_hash = ?, is_active = true WHERE id = ?",
                    name, role, passwordEncoder.encode(PASSWORD), found.get(0));
            return found.get(0);
        }
        return jdbc.queryForObject("INSERT INTO " + TENANT + ".tenant_member"
                        + " (display_name, role, login, password_hash) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, name, role, login, passwordEncoder.encode(PASSWORD));
    }
}
