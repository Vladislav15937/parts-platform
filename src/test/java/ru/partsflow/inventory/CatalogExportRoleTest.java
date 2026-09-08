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
import org.springframework.test.web.servlet.MvcResult;
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
 * Склад одним файлом скачивает владелец и менеджер, а не любой вошедший.
 *
 * <p>Экран «Склад» и вкладка «Шины и диски» открыты всем ролям намеренно:
 * продавцу нужна цена и наличие, кладовщику — полка. Кнопка «Скачать
 * таблицу» стояла там же и была открыта тем же всем — то есть опись всей
 * номенклатуры с ценами, поставками, заметками и остатками по каждому
 * складу уносилась одним нажатием, в том числе ролью «Просмотр», которой
 * закрыты и продажи, и клиенты. Себестоимости в файле нет, но первые
 * десять клиентов — компании из одного города, и ушедший кладовщик
 * с таким файлом это готовый прайс конкурента.
 *
 * <p>Список ролей — решение владельца продукта от 9 сентября 2026
 * (`tasks/0050-vygruzka-sklada-dostupna-vsem.md`), тот же, что у отчётов.
 *
 * <p><b>Обе стороны обязательны.</b> Одна проверка «роль без права получает
 * отказ» зеленеет и на правке, закрывшей выгрузку вообще всем, — а это
 * не починка, а потеря возможности у того, ради кого она заведена. Поэтому
 * рядом стоит проверка, что владелец и менеджер получают файл.
 *
 * <p><b>Заплатка.</b> Когда задача 0044 заменит жёсткие роли полномочиями,
 * проверка станет полномочием «скачивать таблицу», и этот тест переедет
 * на него вместе с ней.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class CatalogExportRoleTest extends PostgresTestBase {

    private static final String TENANT = "t_000120";

    private static final String SKLAD = "/api/parts/catalog/export";
    private static final String KOLESA = "/api/wheels/export";

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
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 120");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (120, ?, 'Выгрузочная', 'exportco')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM tenant_member WHERE login IN "
                    + "('boss', 'chief', 'looker', 'keeper', 'seller')");
            member("boss", "OWNER");
            member("chief", "MANAGER");
            member("looker", "VIEWER");
            member("keeper", "STOREKEEPER");
            member("seller", "SELLER");
            return null;
        });
    }

    /**
     * Роль без права получает отказ, а не файл. Три роли, а не одна:
     * запрет обязан различать роли, и «Просмотр» здесь не единственный —
     * кладовщик и продавец видят тот же экран.
     */
    @Test
    @DisplayName("«Просмотр», кладовщик и продавец склад файлом не скачивают")
    void withoutRightForbidden() throws Exception {
        for (String login : new String[] {"looker", "keeper", "seller"}) {
            MockHttpSession session = login(login);

            mvc.perform(get(SKLAD).session(session))
                    .andExpect(status().isForbidden());
            mvc.perform(get(KOLESA).session(session))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * Вторая сторона: тем, кому право оставлено, приезжает файл.
     *
     * <p>Проверяется не только статус: заголовок выгрузки в теле означает,
     * что обработчик дошёл до записи в поток, а не отдал двухсотый с нулём
     * байт. Имя файла — то же, по которому владелец его потом ищет
     * у себя в загрузках.
     */
    @Test
    @DisplayName("Владелец и менеджер получают файл")
    void withRightGetsFile() throws Exception {
        for (String login : new String[] {"boss", "chief"}) {
            MockHttpSession session = login(login);

            file(get(SKLAD).session(session), "sklad.csv", "Номер товара");
            file(get(KOLESA).session(session), "kolesa.csv", "Номер комплекта");
        }
    }

    private void file(org.springframework.test.web.servlet.RequestBuilder request,
                      String name, String header) throws Exception {
        MvcResult result = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .as("выгрузка отдаётся файлом, а не текстом в браузере")
                .contains(name);
        assertThat(result.getResponse().getContentAsString())
                .as("в теле нет заголовка выгрузки — файл пустой")
                .contains(header);
    }

    /**
     * Сессия берётся из ответа, а не заводится своя: вход пересоздаёт её
     * заново.
     */
    private MockHttpSession login(String login) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"exportco","login":"%s","password":"пароль-подлиннее"}"""
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
