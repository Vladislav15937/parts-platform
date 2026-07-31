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

import java.math.BigDecimal;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Перемещение между складами.
 *
 * <p>Документ и движения были написаны давно, но снаружи операция была
 * недоступна: клиент с филиалом не мог перевезти деталь, не залезая в базу.
 *
 * <p>Главное здесь — что перемещение не меняет общий остаток, а только его
 * раскладку по складам. Ошибка в эту сторону тихая: склад сойдётся,
 * а деталь окажется в двух местах или ни в одном.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class StockMoveControllerTest extends PostgresTestBase {

    private static final String TENANT = "t_000083";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long fromWarehouse;
    private Long toWarehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 83");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (83, ?, 'Разборка', 'moveco')""", TENANT);

        inTenant(() -> {
            member("vladelec", "Владелец", "OWNER");
            member("prodavets", "Продавец", "SELLER");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            fromWarehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            toWarehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ангар') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Перемещение переносит остаток и не меняет общий")
    void moveShiftsStockBetweenWarehouses() throws Exception {
        Long partId = partWithStock("Фара для переезда", 3);

        mvc.perform(post("/api/stock/moves").with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromWarehouseId":%d,"toWarehouseId":%d,
                                 "items":[{"partId":%d,"quantity":2}]}"""
                                .formatted(fromWarehouse, toWarehouse, partId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.number").isNumber());

        assertThat(qtyAt(partId, fromWarehouse)).isEqualByComparingTo("1");
        assertThat(qtyAt(partId, toWarehouse)).isEqualByComparingTo("2");

        // Перемещение меняет раскладку, а не количество: деталь не появилась
        // и не исчезла, она переехала.
        assertThat(totalQty(partId))
                .as("общий остаток изменился — перемещение создало или съело деталь")
                .isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("Нельзя перевезти больше, чем лежит: 409, а не порча остатка")
    void movingMoreThanAvailableIsRejected() throws Exception {
        Long partId = partWithStock("Бампер для переезда", 1);

        mvc.perform(post("/api/stock/moves").with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromWarehouseId":%d,"toWarehouseId":%d,
                                 "items":[{"partId":%d,"quantity":5}]}"""
                                .formatted(fromWarehouse, toWarehouse, partId)))
                .andExpect(status().isConflict());

        assertThat(qtyAt(partId, fromWarehouse))
                .as("остаток поехал в минус: склад после этого не сходится ни с чем")
                .isEqualByComparingTo("1");
        assertThat(qtyAt(partId, toWarehouse)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Перемещение на тот же склад отвергается")
    void movingToTheSameWarehouseIsRejected() throws Exception {
        Long partId = partWithStock("Капот для переезда", 1);

        // Документ без переезда — это документ, который ничего не значит,
        // а в журнале останется навсегда.
        mvc.perform(post("/api/stock/moves").with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromWarehouseId":%d,"toWarehouseId":%d,
                                 "items":[{"partId":%d,"quantity":1}]}"""
                                .formatted(fromWarehouse, fromWarehouse, partId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Продавец товар не перевозит")
    void sellerCannotMove() throws Exception {
        Long partId = partWithStock("Дверь для переезда", 1);

        mvc.perform(post("/api/stock/moves").with(csrf()).session(login("prodavets"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromWarehouseId":%d,"toWarehouseId":%d,
                                 "items":[{"partId":%d,"quantity":1}]}"""
                                .formatted(fromWarehouse, toWarehouse, partId)))
                .andExpect(status().isForbidden());

        assertThat(qtyAt(partId, toWarehouse)).isEqualByComparingTo("0");
    }

    private Long partWithStock(String title, int qty) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, ?, 5000)
                    RETURNING id""", Long.class, title);
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id)
                    VALUES (?, 'INTAKE', ?, ?)""", partId, qty, fromWarehouse);
            return partId;
        });
    }

    private BigDecimal qtyAt(Long partId, Long warehouseId) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT COALESCE(sum(qty), 0) FROM part_stock
                 WHERE part_id = ? AND warehouse_id = ?""",
                BigDecimal.class, partId, warehouseId));
    }

    private BigDecimal totalQty(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT qty_on_hand FROM part WHERE id = ?", BigDecimal.class, partId));
    }

    private Long member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, displayName, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"moveco","login":"%s","password":"пароль"}"""
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
