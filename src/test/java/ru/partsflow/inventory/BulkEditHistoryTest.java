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
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    VALUES ('vladelec', 'Пётр Массов', ?, 'OWNER')""",
                    passwordEncoder.encode("пароль-подлиннее"));
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
     * ({@code PartQualityBlock}). Для человека это правка карточки.
     */
    @Test
    @DisplayName("Правка списком одной позиции (оценка из карточки) — без подписи")
    void bulkOfOneIsNotBulk() throws Exception {
        MockHttpSession owner = login();
        mvc.perform(post("/api/parts/bulk").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d],"changes":{"qualityGrade":"NO_DEFECTS"}}"""
                                .formatted(outside)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/parts/%d/history".formatted(outside)).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].fields[0].label").value("Оценка состояния"))
                .andExpect(jsonPath("$.changes[0].bulk").doesNotExist());
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
