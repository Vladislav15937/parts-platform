package ru.partsflow.sales;

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
 * Заказы площадок через HTTP, а не через сервис.
 *
 * <p>Отдельный класс намеренно. {@code MarketplaceOrderTest} зовёт сервис
 * изнутри своей транзакции и потому не видит целого класса ошибок: коллекция
 * позиций ленивая, {@code open-in-view} выключен, и сделка, прочитанная
 * из базы и отданная контроллеру, превращается в пятисотку уже за границей
 * транзакции. Ровно это и случилось на живом прогоне — дважды, на разных
 * путях: сначала на очереди заказов, потом на повторе.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class MarketplaceOrderHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000086";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long warehouseId;
    private Long customerId;
    private Long partId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 86");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (86, ?, 'Разборка', 'orderco')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM deal_item");
            jdbc.update("DELETE FROM deal");
            member("prodavets", "Продавец", "SELLER");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            customerId = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Дром') RETURNING id", Long.class);
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, 'Фара заказанная', 4500)
                    RETURNING id""", Long.class);
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id)
                    VALUES (?, 'INTAKE', 2, ?)""", partId, warehouseId);
            return null;
        });
    }

    @Test
    @DisplayName("Заказ, повтор и очередь отдаются через HTTP, а не пятисоткой")
    void ordersTravelOverHttp() throws Exception {
        MockHttpSession session = login();
        String body = """
                {"marketplace":"DROM","orderNo":"301-516-98","customerId":%d,
                 "deliveryNote":"ТК СДЭК, Надым",
                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                .formatted(customerId, partId, warehouseId);

        mvc.perform(post("/api/deals/orders").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.deal.status").value("RESERVED"))
                .andExpect(jsonPath("$.deal.externalOrderNo").value("301-516-98"))
                // Позиции обязаны доехать: без них экран показывает заказ,
                // по которому непонятно, что именно просят.
                .andExpect(jsonPath("$.deal.items.length()").value(1));

        // Повтор — 200 и прежняя сделка. Именно этот путь читает сделку
        // из базы и на живом прогоне отдавал пятисотку.
        mvc.perform(post("/api/deals/orders").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.deal.items.length()").value(1));

        mvc.perform(get("/api/deals/orders/awaiting-reply").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].items.length()").value(1));
    }

    @Test
    @DisplayName("Подтверждение убирает заказ из очереди")
    void acceptRemovesFromQueue() throws Exception {
        MockHttpSession session = login();
        String created = mvc.perform(post("/api/deals/orders").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","orderNo":"301-777-02","customerId":%d,
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customerId, partId, warehouseId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long dealId = Long.parseLong(created.replaceAll(".*\"deal\":\\{\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/deals/orders/%d/accept".formatted(dealId))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderAcceptedAt").isNotEmpty())
                // Подтверждение — ответ площадке, а не движение склада.
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mvc.perform(get("/api/deals/orders/awaiting-reply").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private void member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (found.isEmpty()) {
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES (?, ?, ?, ?)""",
                    displayName, role, login, passwordEncoder.encode("пароль"));
        }
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"orderco","login":"prodavets","password":"пароль"}"""))
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
