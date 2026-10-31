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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Адрес хранения из пяти частей «склад-зона-ряд-секция-полка» (задача 0368).
 *
 * <p>Через HTTP, а не вызовом сервиса: ставит на полку кладовщик, и право
 * у точки входа своё (форма правки карточки ему закрыта); ответ — record;
 * отказ «не по схеме» обязан быть 400 со словами, а не 500 — привязка
 * на телефоне идёт сканом, и пятисотку человек прочитает как поломку.
 *
 * <p>Своя схема: соседний тест, добавивший позицию с той же «Секцией»,
 * менял бы число найденного поиском по адресу.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class StorageAddressHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_005817";

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
    private Long otherId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 5817");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (5817, ?, 'Разборка', 'shelfco')""", TENANT);

        inTenant(() -> {
            member("vladelec", "Владелец", "OWNER");
            member("kladovshchik", "Кладовщик", "STOREKEEPER");
            member("prodavec", "Продавец", "SELLER");
            jdbc.update("UPDATE part SET section = NULL");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад А') RETURNING id",
                    Long.class, branch);
            partId = parts.saveAndFlush(
                    new Part(1L, "Фара левая Toyota Camry", new BigDecimal("4500"))).getId();
            otherId = parts.saveAndFlush(
                    new Part(1L, "Бампер передний Toyota Camry", new BigDecimal("9000"))).getId();
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
            ledger.record(StockMovement.intake(otherId, BigDecimal.ONE, warehouse, null));
            return null;
        });
    }

    @Test
    @DisplayName("Разбор: пять частей адреса, короткая часть — та же полка, прежняя ячейка — не по схеме")
    void addressIsParsedIntoParts() {
        StorageAddress address = StorageAddress.parse("01-01-03-01-02").orElseThrow();
        assertThat(address).isEqualTo(new StorageAddress(1, 1, 3, 1, 2));
        assertThat(address.text()).isEqualTo("01-01-03-01-02");
        assertThat(StorageAddress.parse("01-01-3-1-2").map(StorageAddress::text))
                .as("опечатка без нуля обязана быть той же полкой, а не новой")
                .contains("01-01-03-01-02");
        assertThat(StorageAddress.bySchema("А-01-1"))
                .as("прежняя ячейка со свободным названием — не адрес по схеме").isFalse();
        assertThat(StorageAddress.bySchema("01-01-03-01")).as("четыре части").isFalse();
        assertThat(StorageAddress.bySchema("01-01-03-01-02-07")).as("шесть частей").isFalse();
        assertThat(StorageAddress.bySchema("01-01-03-01-100")).as("трёхзначная полка").isFalse();
    }

    @Test
    @DisplayName("Кладовщик ставит позицию на полку адресом: «Секция» в списке и поиск по адресу")
    void storekeeperPlacesPartOnShelf() throws Exception {
        MockHttpSession keeper = login("kladovshchik");

        mvc.perform(put("/api/parts/%d/section".formatted(partId)).with(csrf()).session(keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"01-01-3-1-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section").value("01-01-03-01-02"));

        MockHttpSession owner = login("vladelec");
        mvc.perform(get("/api/parts/catalog").param("q", "01-01-03-01-02").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].id").value(partId))
                .andExpect(jsonPath("$.rows[0].section").value("01-01-03-01-02"));

        // Та же полка, набранная без нулей, находит ту же позицию.
        mvc.perform(get("/api/parts/catalog").param("q", "1-1-3-1-2").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].id").value(partId));
    }

    @Test
    @DisplayName("Адрес не по схеме — 400 со словами, «Секция» не тронута")
    void wrongAddressIsRefusedWithWords() throws Exception {
        MockHttpSession keeper = login("kladovshchik");
        mvc.perform(put("/api/parts/%d/section".formatted(partId)).with(csrf()).session(keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"А-01-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("не по схеме")))
                .andExpect(jsonPath("$.message").value(containsString("01-01-03-01-02")));
        assertThat(sectionOf(partId)).isNull();
    }

    @Test
    @DisplayName("Пустой адрес снимает полку — NULL, а не пустая строка")
    void emptyAddressTakesPartOffShelf() throws Exception {
        MockHttpSession keeper = login("kladovshchik");
        place(keeper, "01-01-03-01-02");
        assertThat(sectionOf(partId)).isEqualTo("01-01-03-01-02");
        place(keeper, "");
        assertThat(sectionOf(partId)).isNull();
    }

    @Test
    @DisplayName("Продавец на полку не ставит — 403")
    void sellerCannotPlace() throws Exception {
        mvc.perform(put("/api/parts/%d/section".formatted(partId)).with(csrf())
                        .session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"01-01-03-01-02\"}"))
                .andExpect(status().isForbidden());
    }

    private void place(MockHttpSession session, String section) throws Exception {
        mvc.perform(put("/api/parts/%d/section".formatted(partId)).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"%s\"}".formatted(section)))
                .andExpect(status().isOk());
    }

    private String sectionOf(Long id) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT section FROM part WHERE id = ?", String.class, id));
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
                                {"company":"shelfco","login":"%s","password":"пароль"}"""
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
