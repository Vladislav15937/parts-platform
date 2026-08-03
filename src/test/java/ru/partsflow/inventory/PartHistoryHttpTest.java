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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * История позиции через HTTP.
 *
 * <p>Через HTTP, а не через сервис, по трём причинам сразу. Автор правки
 * берётся из вошедшего и доезжает до базы настройкой соединения — вызов
 * сервиса из теста этого пути не проходит вовсе. Право видеть себестоимость
 * решает контроллер по роли. И ответ — набор record'ов: обычный класс
 * Jackson не сериализует, а тест сервиса этого не заметит.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartHistoryHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000091";

    @Autowired
    private StockLedger ledger;

    @Autowired
    private PartRepository parts;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long partId;
    private Long warehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 91");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (91, ?, 'Разборка', 'histco')""", TENANT);

        inTenant(() -> {
            members();
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            // Через JPA, а не прямым SQL: журнал изменений пишет слушатель
            // Hibernate, и вставка мимо сессии в ленту не попадёт. Пока писал
            // триггер, это было безразлично — он видел всё.
            Part part = new Part(1L, "Фара для истории", new java.math.BigDecimal("4500"));
            part.setCostPrice(new java.math.BigDecimal("1200"));
            partId = parts.saveAndFlush(part).getId();
            // Приход двигает остаток и статус — это правка строки, которую
            // делает триггер, а не человек. В ленте правок её быть не должно.
            ledger.record(StockMovement.intake(partId, new java.math.BigDecimal("2"), warehouseId, null));
            return null;
        });
    }

    @Test
    @DisplayName("Правка попадает в ленту с автором, движение остатка — нет")
    void editIsListedAndMovementIsNot() throws Exception {
        MockHttpSession owner = login("vladelec");

        mvc.perform(put("/api/parts/%d".formatted(partId)).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(form(5200)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/parts/%d/history".formatted(partId)).session(owner))
                .andExpect(status().isOk())
                // Свежее сверху: историю читают с конца.
                .andExpect(jsonPath("$.changes[0].fields[0].label").value("Цена"))
                .andExpect(jsonPath("$.changes[0].fields[0].before").value("4500.00"))
                .andExpect(jsonPath("$.changes[0].fields[0].after").value("5200"))
                // Автор подписан: до этой правки app.user_id не выставлял никто,
                // и в audit_log.changed_by у всех записей стоял null.
                .andExpect(jsonPath("$.changes[0].author").value("Владелец"))
                // Приход не должен появиться в ленте правок: он во второй ленте.
                // Записи от триггеров остатка отличаются тем, что ни одно поле
                // из списка в них не изменилось.
                .andExpect(jsonPath("$.changes[1].action").value("Товар создан"))
                .andExpect(jsonPath("$.changes.length()").value(2))
                .andExpect(jsonPath("$.movements.length()").value(1))
                .andExpect(jsonPath("$.movements[0].type").value("Поступление"))
                .andExpect(jsonPath("$.movements[0].qty").value(2))
                .andExpect(jsonPath("$.movements[0].warehouse").value("Ткацкая"))
                // Документа у движения нет — так приезжает перенесённый склад,
                // и придумывать номер нельзя.
                .andExpect(jsonPath("$.movements[0].document").doesNotExist());
    }

    @Test
    @DisplayName("Себестоимость в ленте правок продавцу не отдаётся")
    void sellerDoesNotSeeCost() throws Exception {
        MockHttpSession owner = login("vladelec");

        mvc.perform(put("/api/parts/%d".formatted(partId)).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(form(4500).replace("\"costPrice\":1200", "\"costPrice\":900")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/parts/%d/history".formatted(partId)).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].fields[0].label").value("Себестоимость"));

        // Продавцу эта правка не приезжает вовсе — не «скрыта на экране».
        // Кроме неё в этой правке ничего не менялось, значит и записи нет.
        mvc.perform(get("/api/parts/%d/history".formatted(partId)).session(login("prodavec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].action").value("Товар создан"))
                .andExpect(jsonPath("$.changes.length()").value(1))
                // Движения при этом видны: «куда делась деталь» спрашивает
                // и продавец, и кладовщик.
                .andExpect(jsonPath("$.movements.length()").value(1));
    }

    /** Форма правки уезжает целиком: пустое поле означает «очищено». */
    private String form(int price) {
        return """
                {"price":%d,"costPrice":1200,"published":true}""".formatted(price);
    }

    private void members() {
        member("vladelec", "Владелец", "OWNER");
        member("prodavec", "Продавец", "SELLER");
    }

    private void member(String login, String name, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (found.isEmpty()) {
            jdbc.update("""
                            INSERT INTO tenant_member (display_name, role, login, password_hash)
                            VALUES (?, ?, ?, ?)""",
                    name, role, login, passwordEncoder.encode("пароль"));
        }
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"histco","login":"%s","password":"пароль"}"""
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
