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
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Весь склад одним файлом скачивает не любой вошедший.
 *
 * <p>Экраны «Склад» и «Шины и диски» открыты всем ролям намеренно: цену
 * и наличие смотрит и кладовщик, и «Просмотр». Кнопка «Скачать таблицу»
 * стояла там же и ролей не спрашивала — а файл этот другой природы:
 * вся номенклатура разом, с ценами, поставками, заметками, кросс-номерами
 * и остатками по каждому складу. Для разборки это опись имущества, а первые
 * десять клиентов — компании из одного города, которые общаются между собой.
 *
 * <p><b>Обе стороны обязательны.</b> Правка, закрывшая выгрузку всем подряд,
 * прошла бы проверку «кладовщик получает 403» и оставила бы владельца без
 * файла — молча, потому что жалуется на это только он сам и не сразу.
 * Поэтому у каждой выгрузки здесь две проверки: отказ роли без права
 * и настоящий файл роли с правом.
 *
 * <p>Проверка идёт через HTTP-контур со всей цепочкой фильтров и методной
 * безопасностью, а не звонком в сервис: вызванный напрямую, сервис отдаёт
 * склад кому угодно — {@code @PreAuthorize} для него не существует.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class ExportRoleTest extends PostgresTestBase {

    private static final String TENANT = "t_000119";

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
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 119");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (119, ?, 'Выгрузочная', 'expco')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM tenant_member WHERE login IN ('boss', 'chief', 'keeper', 'looker')");
            member("boss", "OWNER");
            member("chief", "MANAGER");
            member("keeper", "STOREKEEPER");
            member("looker", "VIEWER");
            return null;
        });
    }

    @Test
    @DisplayName("Склад таблицей не уносят кладовщик и «Просмотр»")
    void warehouseExportRefused() throws Exception {
        mvc.perform(get("/api/parts/catalog/export").session(login("keeper")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/parts/catalog/export").session(login("looker")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Колёса таблицей не уносят кладовщик и «Просмотр»")
    void wheelExportRefused() throws Exception {
        mvc.perform(get("/api/wheels/export").session(login("keeper")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/wheels/export").session(login("looker")))
                .andExpect(status().isForbidden());
    }

    /**
     * Вторая сторона: закрыв путь всем, дыру тоже «починишь». Поэтому здесь
     * проверяется не код ответа, а что файл действительно приехал — заголовок
     * выгрузки в теле.
     */
    @Test
    @DisplayName("Владелец и менеджер получают файл склада")
    void warehouseExportAllowed() throws Exception {
        for (String login : new String[]{"boss", "chief"}) {
            String body = mvc.perform(get("/api/parts/catalog/export").session(login(login)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body)
                    .as("«%s» получил пустой ответ вместо выгрузки склада", login)
                    .contains("Номер товара")
                    .contains("Кросс-номера");
        }
    }

    @Test
    @DisplayName("Владелец и менеджер получают файл колёс")
    void wheelExportAllowed() throws Exception {
        for (String login : new String[]{"boss", "chief"}) {
            String body = mvc.perform(get("/api/wheels/export").session(login(login)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body)
                    .as("«%s» получил пустой ответ вместо выгрузки колёс", login)
                    .contains("Номер комплекта")
                    .contains("Индекс скорости");
        }
    }

    /**
     * Закрыта выгрузка, а не экран: сам склад кладовщик и «Просмотр» смотрят
     * как раньше. Иначе правка стоила бы им работы, а не украденного файла.
     */
    @Test
    @DisplayName("Витрину и колёса на экране по-прежнему видят все")
    void screensStillOpen() throws Exception {
        mvc.perform(get("/api/parts/catalog?size=1").session(login("keeper")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/wheels?size=1").session(login("looker")))
                .andExpect(status().isOk());
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"expco","login":"%s","password":"пароль-подлиннее"}"""
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
