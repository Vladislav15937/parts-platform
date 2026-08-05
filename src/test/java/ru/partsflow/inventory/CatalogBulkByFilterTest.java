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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Правка всего, что попало в отбор, а не отмеченной страницы.
 *
 * <p><b>Зачем.</b> Выгрузка прежней системы приходит без колонки «Выгружать»,
 * если её не включили в настройках таблицы перед экспортом, — и тогда весь
 * склад импортируется без разрешения на публикацию. У живого клиента это
 * 35 841 позиция: прайс на Дром уезжает пустым, 55 байт вместо двадцати
 * мегабайт, и площадка молча не заводит ни одного объявления.
 *
 * <p>Починить это владелец мог только отмечая строки на экране — по полсотни
 * за раз, семьсот семнадцать страниц, с потерей выделения на каждой. При этом
 * сама возможность была написана с самого начала: правка списком принимает
 * хоть весь склад и проходит его за двадцать секунд. Не было только способа
 * назвать «всё, что я вижу», — та же порода ошибки, что с экранами
 * сотрудников, складов, кабинета площадки и ссылки на прайс.
 *
 * <p><b>Через HTTP, а не через сервис.</b> Проверяется ровно то, чего
 * не было: путь снаружи. Тест сервиса прошёл бы и до правки — {@code
 * updateAll} умел это всегда.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class CatalogBulkByFilterTest extends PostgresTestBase {

    private static final String TENANT = "t_000103";

    /**
     * Позиций больше страницы витрины (пятьдесят): в этом вся суть.
     * Тест на горстке прошёл бы и с прежним поведением.
     */
    private static final int PARTS = 120;

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
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 103");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (103, ?, 'Переехавшая', 'pereehal')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM part_change");
            jdbc.update("DELETE FROM part");
            jdbc.update("DELETE FROM tenant_member WHERE login IN ('hozyain', 'prodavec')");
            member("hozyain", "OWNER");
            member("prodavec", "SELLER");

            // Склад, приехавший переносом без колонки «Выгружать»: цена есть,
            // разрешения публиковать нет ни у одной позиции.
            //
            // Остаток проставляется прямо в кэше, а не движением через
            // StockLedger: проверяется отбор витрины, а он смотрит
            // на qty_on_hand. Без остатка отбор по умолчанию («показывать
            // только то, что лежит») не вернул бы ни строки.
            for (int i = 0; i < PARTS; i++) {
                jdbc.update("""
                        INSERT INTO part (category_id, title, price, is_published,
                                          section, qty_on_hand)
                        VALUES (1, ?, 1000, false, ?, 1)""",
                        "Фара номер " + i, i < 30 ? "А" : "Б");
            }
            return null;
        });
    }

    /**
     * Главное утверждение: тронуто всё, а не страница.
     *
     * <p>До правки этот путь отвечал 404 — эндпоинта не было вовсе,
     * и включить публикацию складу целиком было нечем.
     */
    @Test
    @DisplayName("Правка по отбору включает выгрузку всему складу, а не странице")
    void wholeFilterIsEdited() throws Exception {
        mvc.perform(post("/api/parts/catalog/bulk").with(csrf()).session(login("hozyain"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":{\"published\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(PARTS));

        assertThat(published())
                .as("выгрузка включилась не всем — прайс уедет неполным")
                .isEqualTo(PARTS);
    }

    /**
     * Отбор обязан соблюдаться дословно: правка трогает ровно то, что владелец
     * видел на экране. Разойдись отбор страницы и отбор правки — он правил бы
     * одно, а менялось бы другое, и заметить это можно только пересчётом.
     */
    @Test
    @DisplayName("Отбор соблюдается: за его пределами ничего не меняется")
    void filterIsRespected() throws Exception {
        mvc.perform(post("/api/parts/catalog/bulk")
                        .param("filter", "section:А")
                        .with(csrf()).session(login("hozyain"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":{\"published\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(30));

        assertThat(published())
                .as("правка вышла за отбор — тронуто больше, чем видел владелец")
                .isEqualTo(30);
    }

    /**
     * «Изменено 0» владелец читает как «сделано», и ошибку в отборе
     * он после этого не ищет. Поэтому пустой результат — отказ, а не ноль.
     */
    @Test
    @DisplayName("Отбор, не нашедший ничего, отвергается")
    void emptyFilterIsRefused() throws Exception {
        mvc.perform(post("/api/parts/catalog/bulk")
                        .param("q", "такого-наименования-нет")
                        .with(csrf()).session(login("hozyain"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":{\"published\":true}}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Набор полей остаётся закрытым: шире становится не то, <i>что</i> можно
     * изменить, а только <i>скольким</i> позициям. Заголовок собирается
     * справочником, и правка по отбору не должна становиться лазейкой.
     */
    @Test
    @DisplayName("Поле не из списка правки списком не принимается")
    void fieldListStaysClosed() throws Exception {
        mvc.perform(post("/api/parts/catalog/bulk").with(csrf()).session(login("hozyain"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":{\"title\":\"Своё название\"}}"))
                .andExpect(status().isBadRequest());
    }

    /** Правка склада целиком — дело владельца и менеджера, не продавца. */
    @Test
    @DisplayName("Продавец складом целиком не правит")
    void sellerIsRefused() throws Exception {
        mvc.perform(post("/api/parts/catalog/bulk").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":{\"published\":true}}"))
                .andExpect(status().isForbidden());

        assertThat(published()).as("продавец включил выгрузку складу").isZero();
    }

    private int published() {
        Integer count = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part WHERE is_published", Integer.class));
        return count == null ? 0 : count;
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"pereehal","login":"%s","password":"пароль-подлиннее"}"""
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
