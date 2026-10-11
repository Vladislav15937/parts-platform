package ru.partsflow.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import ru.partsflow.support.PostgresTestBase;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Адреса страниц без входа отдают оболочку приложения невошедшему
 * (задача 0265, после 0184).
 *
 * <p>Страница входа и регистрации живёт на {@code /login} (вкладка
 * регистрации — {@code /register}), и туда приложение уводит невошедшего
 * с любого своего адреса. Обновил страницу, открыл ссылку из закладки —
 * сервер обязан отдать оболочку, а не 401: пустой ответ без входа — это
 * белый экран, с которого уже никуда не увести.
 *
 * <p>Проверяются оба замка разом: {@code SpaRoutingConfig} (что отдать)
 * и {@code SecurityConfig} (пустить ли без входа). Поодиночке каждый
 * даёт тот же белый экран.
 *
 * <p>Свойства подобраны под кэш контекстов {@code SalesControllerTest}
 * и соседей: своим набором тест поднял бы ещё один Spring. Схему он
 * не занимает — арендатор ему не нужен.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class SpaRoutingTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/login", "/register", "/s/razborka/tok-123"})
    @DisplayName("Страница без входа отдаёт оболочку невошедшему")
    void pageWithoutSignInServesTheShell(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    /**
     * Правило узкое намеренно (см. {@code SpaRoutingConfig}): опечатка в пути
     * не получает двухсотого ответа. Проверено здесь, чтобы запись нового
     * адреса не превратилась в «отдавать оболочку на всё».
     */
    @Test
    @DisplayName("Незнакомый адрес оболочку не получает")
    void unknownPathIsNotTheShell() throws Exception {
        mvc.perform(get("/login-opechatka"))
                .andExpect(status().isUnauthorized());
    }
}
