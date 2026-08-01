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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Инвентаризация через HTTP, а не через сервис.
 *
 * <p>Отдельный класс намеренно, как и {@code MarketplaceOrderHttpTest}.
 * {@code InventoryServiceTest} зовёт сервис изнутри своей транзакции
 * и потому не видит целого класса ошибок: строки сессии ленивые,
 * {@code open-in-view} выключен, а представление сессии их считает — сессия,
 * отданная контроллеру, за границей транзакции превращается
 * в {@code LazyInitializationException}, то есть в пятисотку.
 *
 * <p>Поймано живым прогоном на «завершить подсчёт»: открытие и подсчёт
 * проходили, потому что оба трогают строки по делу, а завершение — нет.
 * А пятисотку офлайн-очередь кладовщика повторяет вечно.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class InventoryHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000087";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long warehouseId;
    private Long partId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 87");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (87, ?, 'Разборка', 'invco')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM inventory_line");
            jdbc.update("DELETE FROM inventory_session");
            member();

            // Склад заводится свой на каждый прогон, а не чистится: журнал
            // движений неизменяем, и удалить приход нельзя — снимок сессии
            // на новом складе видит ровно одну позицию.

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, 'Фара для пересчёта', 4500)
                    RETURNING id""", Long.class);
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id)
                    VALUES (?, 'INTAKE', 2, ?)""", partId, warehouseId);
            return null;
        });
    }

    @Test
    @DisplayName("Открытие, подсчёт, завершение и применение проходят через HTTP")
    void countingTravelsOverHttp() throws Exception {
        MockHttpSession session = login();

        String opened = mvc.perform(post("/api/inventory/sessions").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(warehouseId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines").value(1))
                .andReturn().getResponse().getContentAsString();
        long sessionId = Long.parseLong(opened.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/inventory/sessions/%d/counts".formatted(sessionId))
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partId\":%d,\"qty\":1}".formatted(partId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counted").value(1));

        // Вот этот путь и отдавал пятисотку: завершение строк не трогает,
        // а представление сессии их считает.
        mvc.perform(post("/api/inventory/sessions/%d/finish".formatted(sessionId))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COUNTED"))
                .andExpect(jsonPath("$.lines").value(1));

        mvc.perform(get("/api/inventory/sessions/%d/discrepancies".formatted(sessionId))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].delta").value(-1.0));

        mvc.perform(post("/api/inventory/sessions/%d/apply".formatted(sessionId))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjusted").value(1));
    }

    @Test
    @DisplayName("Открытая сессия склада отдаётся, а не пятисоткой")
    void openSessionIsReadable() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/api/inventory/sessions").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(warehouseId)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/inventory/sessions/open?warehouseId=%d".formatted(warehouseId))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").value(1));
    }

    private void member() {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, "kladovshchik");
        if (found.isEmpty()) {
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES ('Кладовщик', 'STOREKEEPER', 'kladovshchik', ?)""",
                    passwordEncoder.encode("пароль"));
        }
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"invco","login":"kladovshchik","password":"пароль"}"""))
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
