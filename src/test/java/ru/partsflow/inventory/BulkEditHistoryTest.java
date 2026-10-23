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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * История позиции называет объём массовой правки (задача 0373).
 *
 * <p><b>Как это выглядело.</b> «Правка списком» по сорока позициям писала
 * в историю каждой обычную запись «Заметка: → …» от автора — неотличимую
 * от правки руками по одной. На вопрос «почему у этой детали вдруг другое»
 * ответ искали по журналу действий.
 *
 * <p><b>Через HTTP:</b> пометка ложится в журнал при сбросе правок в базу,
 * а сброс — граница транзакции сервиса; вызов сервиса изнутри своей
 * транзакции этого пути не прошёл бы. Позиций сорок, а не две: пометка
 * обязана дойти до каждой, а не до первых сброшенных.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class BulkEditHistoryTest extends PostgresTestBase {

    private static final String TENANT = "t_000373";

    private static final int IN_FILTER = 40;

    private static final long OWNER_NUMBER = 4207;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<Long> inFilter = new ArrayList<>();
    private Long outside;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 373");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (373, ?, 'Разборка', 'bulkhist')""", TENANT);

        inFilter.clear();
        inTenant(() -> {
            jdbc.update("DELETE FROM part_change");
            jdbc.update("DELETE FROM part");
            jdbc.update("DELETE FROM tenant_member WHERE login = 'vladelec'");
            // Видимый номер задан явно и далеко от id строки: подпись,
            // собранная по id, на этой фикстуре проверку не прошла бы.
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role, number)
                    VALUES ('vladelec', 'Пётр Массов', ?, 'OWNER', ?)""",
                    passwordEncoder.encode("пароль-подлиннее"), OWNER_NUMBER);
            // Остаток прямо в кэше: отбор витрины по умолчанию показывает
            // только то, что лежит, и без него отбор не нашёл бы ни строки.
            for (int i = 0; i < IN_FILTER; i++) {
                inFilter.add(jdbc.queryForObject("""
                        INSERT INTO part (category_id, title, price, section, qty_on_hand)
                        VALUES (1, ?, 1000, 'Бокс', 1) RETURNING id""",
                        Long.class, "Фара номер " + i));
            }
            outside = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, section, qty_on_hand)
                    VALUES (1, 'Фара вне отбора', 1000, 'Склад', 1) RETURNING id""",
                    Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Правка по отбору: у любой из сорока позиций запись названа массовой и называет 40")
    void filterEditNamesItsSize() throws Exception {
        MockHttpSession owner = login();
        mvc.perform(post("/api/parts/catalog/bulk").param("filter", "section:Бокс")
                        .with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":{\"note\":\"Наличные\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(IN_FILTER));

        // Первая, середина и последняя: пометка обязана дойти до каждой,
        // а не только до тех, что сброшены в базу под ней первыми.
        for (Long partId : List.of(inFilter.get(0), inFilter.get(IN_FILTER / 2),
                inFilter.get(IN_FILTER - 1))) {
            mvc.perform(get("/api/parts/%d/history".formatted(partId)).session(owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.changes[0].fields[0].label").value("Заметка"))
                    .andExpect(jsonPath("$.changes[0].fields[0].after").value("Наличные"))
                    .andExpect(jsonPath("$.changes[0].bulk.size").value(IN_FILTER))
                    .andExpect(jsonPath("$.changes[0].bulk.scope").value("FILTER"));
        }
    }

    @Test
    @DisplayName("Правка отмеченных: запись называет число отмеченных и что они отмечены")
    void selectedEditNamesItsSize() throws Exception {
        MockHttpSession owner = login();
        mvc.perform(post("/api/parts/bulk").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d,%d,%d],"changes":{"price":1500}}"""
                                .formatted(inFilter.get(0), inFilter.get(1), inFilter.get(2))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/parts/%d/history".formatted(inFilter.get(1))).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].fields[0].label").value("Цена"))
                .andExpect(jsonPath("$.changes[0].bulk.size").value(3))
                .andExpect(jsonPath("$.changes[0].bulk.scope").value("SELECTED"));
    }

    @Test
    @DisplayName("Правка одной карточки — запись без подписи, и пометка массовой к ней не прилипает")
    void singleEditIsNotBulk() throws Exception {
        MockHttpSession owner = login();
        // Сначала массовая в том же потоке: пометка, не снятая после неё,
        // подписала бы следующую правку чужим объёмом.
        mvc.perform(post("/api/parts/catalog/bulk").param("filter", "section:Бокс")
                        .with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":{\"note\":\"Наличные\"}}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/parts/%d".formatted(inFilter.get(0))).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Своя заметка\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/parts/%d".formatted(outside)).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Своя заметка\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/parts/%d/history".formatted(inFilter.get(0))).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].fields[0].after").value("Своя заметка"))
                .andExpect(jsonPath("$.changes[0].bulk").doesNotExist())
                // Прежняя массовая осталась массовой.
                .andExpect(jsonPath("$.changes[1].fields[0].after").value("Наличные"))
                .andExpect(jsonPath("$.changes[1].bulk.size").value(IN_FILTER));

        mvc.perform(get("/api/parts/%d/history".formatted(outside)).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].fields[0].after").value("Своя заметка"))
                .andExpect(jsonPath("$.changes[0].bulk").doesNotExist());
    }

    /**
     * Оценку состояния из карточки ставит правка списком на одну позицию
     * ({@code PartQualityBlock}) и называет себя источником {@code CARD}.
     * Для человека это правка карточки — подписи нет.
     */
    @Test
    @DisplayName("Правка из карточки (оценка состояния) — без подписи")
    void cardEditIsNotBulk() throws Exception {
        MockHttpSession owner = login();
        mvc.perform(post("/api/parts/bulk").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d],"changes":{"qualityGrade":"NO_DEFECTS"},"source":"CARD"}"""
                                .formatted(outside)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/parts/%d/history".formatted(outside)).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].fields[0].label").value("Оценка состояния"))
                .andExpect(jsonPath("$.changes[0].bulk").doesNotExist());
    }

    /**
     * Решение владельца 23.10.2026 (задача 0373, пункт 12): «клиент должен
     * явно понимать что правит не одну позицию а выбранные». Одна отмеченная
     * строка «Правки списком» подписывается так же, как три; правило PR 491
     * «массовая — от двух позиций» отменено.
     */
    @Test
    @DisplayName("Правка списком одной отмеченной строки — подписана «1 … по отмеченным»")
    void listEditOfOneIsSigned() throws Exception {
        MockHttpSession owner = login();
        for (String source : List.of(",\"source\":\"LIST\"", "")) {
            mvc.perform(post("/api/parts/bulk").with(csrf()).session(owner)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"partIds":[%d],"changes":{"note":"Одна%s"}%s}"""
                                    .formatted(outside, source.length(), source)))
                    .andExpect(status().isOk());

            mvc.perform(get("/api/parts/%d/history".formatted(outside)).session(owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.changes[0].fields[0].after")
                            .value("Одна" + source.length()))
                    .andExpect(jsonPath("$.changes[0].bulk.size").value(1))
                    .andExpect(jsonPath("$.changes[0].bulk.scope").value("SELECTED"));
        }
    }

    /**
     * Решение владельца 23.10.2026 (пункт 10): «Показывать логин имя и номер
     * сотрудника, мы должны идентифицировать сотрудника на 100%». Номер —
     * видимый {@code tenant_member.number}, а не {@code id} строки; у этого
     * сотрудника они нарочно разные.
     */
    @Test
    @DisplayName("Автор правки подписан именем, логином и видимым номером, а не id строки")
    void authorIsSignedWithLoginNameAndNumber() throws Exception {
        long id = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM tenant_member WHERE login = 'vladelec'", Long.class));
        org.assertj.core.api.Assertions.assertThat(id)
                .as("фикстура: видимый номер совпал с id строки").isNotEqualTo(OWNER_NUMBER);

        MockHttpSession owner = login();
        mvc.perform(put("/api/parts/%d".formatted(outside)).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Своя заметка\"}"))
                .andExpect(status().isOk());

        String signed = "Пётр Массов (vladelec, ID 4207)";
        mvc.perform(get("/api/parts/%d/history".formatted(outside)).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].fields[0].after").value("Своя заметка"))
                .andExpect(jsonPath("$.changes[0].author").value(signed));
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"bulkhist","login":"vladelec","password":"пароль-подлиннее"}"""))
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
}
