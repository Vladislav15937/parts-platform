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
 * Списание — то, чем закрывается петля пересчёта.
 *
 * <p>Недостача обнуляет остаток корректировкой, но статус карточки не меняет:
 * триггер различает продажу и списание, а корректировка не то и не другое.
 * Пока списания не было, такая карточка навсегда оставалась «в наличии»
 * с нулевым остатком, а битую деталь снять со склада было нечем вовсе.
 *
 * <p>Через HTTP, а не через сервис: роль проверяется аннотацией на методе,
 * и вызов сервиса напрямую её не касается.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class WriteOffTest extends PostgresTestBase {

    private static final String TENANT = "t_000088";

    @Autowired
    private StockLedger ledger;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockReservationRepository reservations;

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
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 88");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (88, ?, 'Разборка', 'writeco')""", TENANT);

        inTenant(() -> {
            member("vladelec", "Владелец", "OWNER");
            member("kladovshchik", "Кладовщик", "STOREKEEPER");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, 'Фара битая', 4500)
                    RETURNING id""", Long.class);
            ledger.record(StockMovement.intake(partId, new java.math.BigDecimal("2"), warehouseId, null));
            return null;
        });
    }

    @Test
    @DisplayName("Списание уносит остаток и делает карточку списанной, а не «в наличии»")
    void writeOffMarksThePart() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/stock/write-offs").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"разбита при разборе",
                                 "items":[{"partId":%d,"quantity":2}]}"""
                                .formatted(warehouseId, partId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.items").value(1));

        assertThat(partStatus(partId)).isEqualTo("WRITTEN_OFF");
        assertThat(onHand(partId)).isEqualByComparingTo("0");
    }

    // Частичное списание: разбили одну из двух — остаток остаётся, и карточка
    // остаётся в наличии. Статус ведёт остаток, а не тип движения.
    @Test
    @DisplayName("Списание части остатка карточку не закрывает")
    void partialWriteOffKeepsThePart() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/stock/write-offs").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"одна разбита",
                                 "items":[{"partId":%d,"quantity":1}]}"""
                                .formatted(warehouseId, partId)))
                .andExpect(status().isCreated());

        assertThat(partStatus(partId)).isEqualTo("IN_STOCK");
        assertThat(onHand(partId)).isEqualByComparingTo("1");
    }

    /**
     * Обещанное покупателю списать нельзя, и отказ обязан называть числа:
     * без них кладовщик идёт искать поломку сервера вместо того, чтобы
     * посмотреть, кому деталь отложена.
     */
    @Test
    @DisplayName("Отложенное покупателю не списывается, и отказ называет остаток")
    void reservedStockIsNotWrittenOff() throws Exception {
        inTenant(() -> {
            reservations.reserve(partId, warehouseId, new java.math.BigDecimal("2"));
            return null;
        });

        MockHttpSession session = login("vladelec");

        String message = mvc.perform(post("/api/stock/write-offs").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"разбита",
                                 "items":[{"partId":%d,"quantity":2}]}"""
                                .formatted(warehouseId, partId)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(message).contains("свободно 0").contains("требуется 2");
        assertThat(onHand(partId)).isEqualByComparingTo("2");
    }

    // Списанная деталь — это убыток, а не запись в журнале: находит недостачу
    // кладовщик, решение принимает тот, кто отвечает за деньги.
    @Test
    @DisplayName("Кладовщик списывать не может")
    void storekeeperCannotWriteOff() throws Exception {
        MockHttpSession session = login("kladovshchik");

        mvc.perform(post("/api/stock/write-offs").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"разбита",
                                 "items":[{"partId":%d,"quantity":1}]}"""
                                .formatted(warehouseId, partId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Списание без причины не принимается")
    void reasonIsRequired() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/stock/write-offs").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"  ",
                                 "items":[{"partId":%d,"quantity":1}]}"""
                                .formatted(warehouseId, partId)))
                .andExpect(status().isBadRequest());
    }

    private String partStatus(Long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM part WHERE id = ?", String.class, part));
    }

    private BigDecimal onHand(Long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT qty_on_hand FROM part WHERE id = ?", BigDecimal.class, part));
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

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"writeco","login":"%s","password":"пароль"}"""
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
