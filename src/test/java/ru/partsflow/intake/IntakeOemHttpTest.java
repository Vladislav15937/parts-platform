package ru.partsflow.intake;

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

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Номер производителя с приёмки хранится тем же правилом, что из карточки
 * (задача 0309): русские двойники латиницей, неразрывный пробел по краям срезан.
 *
 * <p>Приёмщик набирает номер с телефона, и раскладка там переключается сама:
 * «АВ-123С» с русскими буквами выглядит ровно как латинское, а на Дроме
 * не совпадёт ни с чьим поиском. Через HTTP — приёмка зовётся офлайн-очередью
 * телефона, и ответ её контроллера — то, что та читает.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class IntakeOemHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000996";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long warehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 996");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (996, ?, 'Приёмка номеров', 'oemintake')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM tenant_member WHERE login = 'priemshchik'");
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    VALUES ('priemshchik', 'Приёмщик', ?, 'STOREKEEPER')""",
                    passwordEncoder.encode("пароль-подлиннее"));
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Номер с приёмки: русские двойники — латиница, неразрывный пробел по краям срезан")
    void intakeStoresLatinNumber() throws Exception {
        String requestId = "oem-" + System.nanoTime();
        // «АВ-123С» с русскими А, В, С и неразрывными пробелами по краям.
        String oem = "\u00A0\u0410\u0412-123\u0421\u00A0";
        mvc.perform(post("/api/intake/receipts").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","warehouseId":%d,
                                 "items":[{"rawName":"Фара","price":100,"quantity":1,"oemNumber":"%s"}]}"""
                                .formatted(requestId, warehouse, oem)))
                .andExpect(status().isCreated());

        List<Map<String, Object>> rows = inTenant(() -> jdbc.queryForList("""
                SELECT o.raw_number, o.normalized FROM part_oem o
                  JOIN stock_document_line l ON l.part_id = o.part_id
                  JOIN stock_document d ON d.id = l.document_id
                 WHERE d.client_request_id = ?""", requestId));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("raw_number"))
                .as("в raw_number легли русские буквы или неразрывный пробел")
                .isEqualTo("AB-123C");
        assertThat(rows.get(0).get("normalized")).isEqualTo("AB123C");
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"oemintake","login":"priemshchik","password":"пароль-подлиннее"}"""))
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
