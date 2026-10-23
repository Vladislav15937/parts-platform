package ru.partsflow.shared;

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

import java.time.YearMonth;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Автор подписан «Имя (логин, ID N)» везде, где назван (задача 0373, пункт 5а).
 *
 * <p>Решение владельца 23.10.2026 (`tasks/0373`, раздел «после слияния PR 499,
 * 503»): не только «История изменений» карточки, а журнал правок, перевозки,
 * «Движение товара», и всё, где человеку назван сотрудник. Перебор исходников
 * держит {@code AuthorSignedTest}; здесь — каждая поверхность через HTTP,
 * то есть то, что получает экран.
 *
 * <p><b>Фикстура — два тёзки.</b> «Пётр Массов» с логином {@code vladelec}
 * и «Пётр Массов» с логином {@code petr2}: ровно то, что одно имя не различает.
 * Видимые номера заданы явно и далеко от {@code id} строки — подпись,
 * собранная по {@code id}, проверку не прошла бы.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class AuthorSignedHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000374";

    private static final String OWNER = "Пётр Массов (vladelec, ID 4207)";

    private static final String NAMESAKE = "Пётр Массов (petr2, ID 4208)";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long owner;
    private long part;
    private long from;
    private long to;
    private long customer;
    private long deal;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 374");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (374, ?, 'Разборка', 'signed')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM customer_account_entry");
            jdbc.update("DELETE FROM document_event");
            jdbc.update("DELETE FROM deal_item");
            jdbc.update("DELETE FROM deal");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM stock_document_line");
            jdbc.update("DELETE FROM stock_document");
            jdbc.update("DELETE FROM part_change");
            jdbc.update("DELETE FROM part");
            jdbc.update("DELETE FROM audit_log");
            jdbc.update("DELETE FROM login_session");
            jdbc.update("DELETE FROM tenant_member WHERE login IN ('vladelec', 'petr2')");
            owner = jdbc.queryForObject("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role, number)
                    VALUES ('vladelec', 'Пётр Массов', ?, 'OWNER', 4207) RETURNING id""",
                    Long.class, passwordEncoder.encode("пароль-подлиннее"));
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role, number)
                    VALUES ('petr2', 'Пётр Массов', ?, 'SELLER', 4208)""",
                    passwordEncoder.encode("пароль-подлиннее"));
            part = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, section, qty_on_hand)
                    VALUES (1, 'Фара подписи', 1000, 'Бокс', 1) RETURNING id""", Long.class);
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал подписи') RETURNING id", Long.class);
            from = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            to = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Дальний') RETURNING id",
                    Long.class, branch);
            customer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Покупатель подписи') RETURNING id",
                    Long.class);
            deal = jdbc.queryForObject(
                    "INSERT INTO deal (customer_id, manager_id) VALUES (?, ?) RETURNING id",
                    Long.class, customer, owner);
            return null;
        });
        org.assertj.core.api.Assertions.assertThat(owner)
                .as("фикстура: видимый номер совпал с id строки").isNotEqualTo(4207L);
    }

    @Test
    @DisplayName("Журнал правок: автор подписан, меню «Кто» различает тёзок, отбор по подписи находит своего")
    void auditJournal() throws Exception {
        MockHttpSession session = login();
        editNote(session);

        mvc.perform(get("/api/organization/audit").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].author").value(OWNER));
        mvc.perform(get("/api/organization/audit/values").param("column", "author").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItems(OWNER, NAMESAKE)));
        mvc.perform(get("/api/organization/audit").param("author", OWNER).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].author").value(OWNER))
                .andExpect(jsonPath("$.items[*].author", everyItem(org.hamcrest.Matchers.is(OWNER))));
        mvc.perform(get("/api/organization/audit").param("author", NAMESAKE).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("Склад: «Кто изменил» подписан, и значение из меню находит строку")
    void showcaseColumns() throws Exception {
        MockHttpSession session = login();
        editNote(session);

        mvc.perform(get("/api/parts/catalog").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].updatedByName").value(OWNER));
        mvc.perform(get("/api/parts/catalog/values").param("column", "updatedBy").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem(OWNER)));
        mvc.perform(get("/api/parts/catalog").param("filter", "updatedBy:" + OWNER).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("Перевозки: кто перевёз и кто удалил — подписью")
    void moveJournal() throws Exception {
        inTenant(() -> jdbc.update("""
                INSERT INTO stock_document (doc_type, status, warehouse_id, to_warehouse_id,
                                            completed_at, created_by, deleted_at, deleted_by)
                VALUES ('MOVE', 'DONE', ?, ?, now(), ?, now(), ?)""", from, to, owner, owner));

        mvc.perform(get("/api/stock/moves").session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].author").value(OWNER))
                .andExpect(jsonPath("$[0].deletedBy").value(OWNER));
    }

    @Test
    @DisplayName("«Движение товара» карточки: автор движения и ответственный держащей сделки — подписью")
    void partMovements() throws Exception {
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id,
                                                created_by)
                    VALUES (?, 'INTAKE', 1, ?, ?)""", part, from, owner);
            jdbc.update("""
                    INSERT INTO deal_item (deal_id, part_id, quantity, price, status, warehouse_id)
                    VALUES (?, ?, 1, 1000, 'RESERVED', ?)""", deal, part, from);
            return null;
        });

        mvc.perform(get("/api/parts/%d/history".formatted(part)).session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movements[*].author", hasItem(OWNER)))
                .andExpect(jsonPath("$.movements[*].author", everyItem(org.hamcrest.Matchers.is(OWNER))));
    }

    @Test
    @DisplayName("Журнал входов: сотрудник подписан, меню и отбор — подписью")
    void sessionJournal() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/organization/sessions").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].who").value(OWNER));
        mvc.perform(get("/api/organization/sessions/values").param("column", "member")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem(OWNER)))
                .andExpect(jsonPath("$", not(hasItem("Пётр Массов"))));
        mvc.perform(get("/api/organization/sessions").param("member", OWNER).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].who").value(OWNER));
    }

    @Test
    @DisplayName("Сделка: ответственный в карточке и реестре, автор истории и лицевого счёта — подписью")
    void dealAndAccount() throws Exception {
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO document_event (document_type, document_id, event_type, message,
                                                author_id)
                    VALUES ('DEAL', ?, 'CREATED', 'Сделка заведена', ?)""", deal, owner);
            jdbc.update("""
                    INSERT INTO customer_account_entry (customer_id, entry_type, amount, deal_id,
                                                        created_by)
                    VALUES (?, 'DEAL_PAYMENT', 100, ?, ?)""", customer, deal, owner);
            return null;
        });
        MockHttpSession session = login();

        mvc.perform(get("/api/deals/%d".formatted(deal)).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerName").value(OWNER));
        mvc.perform(get("/api/deals/registry").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].managerName").value(OWNER));
        mvc.perform(get("/api/deals/%d/history".formatted(deal)).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authorName").value(OWNER));
        mvc.perform(get("/api/customers/%d/account".formatted(customer)).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].authorName").value(OWNER));
    }

    @Test
    @DisplayName("Отчёт по менеджерам: менеджер подписан — по нему считают премию")
    void managerReport() throws Exception {
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO deal_item (deal_id, part_id, quantity, price, status, warehouse_id)
                    VALUES (?, ?, 1, 1000, 'ISSUED', ?)""", deal, part, from);
            jdbc.update("UPDATE deal SET status = 'ISSUED', closed_at = now(), issued_at = now()"
                    + " WHERE id = ?", deal);
            return null;
        });

        mvc.perform(get("/api/reports/managers").param("month", YearMonth.now().toString())
                        .session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].displayName").value(OWNER));
    }

    private void editNote(MockHttpSession session) throws Exception {
        mvc.perform(put("/api/parts/%d".formatted(part)).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Своя заметка\"}"))
                .andExpect(status().isOk());
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"signed","login":"vladelec","password":"пароль-подлиннее"}"""))
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
