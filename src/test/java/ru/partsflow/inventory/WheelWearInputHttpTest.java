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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ввод в поле «Износ, %» (задача 0345, решение владельца 19.10.2026: 400 словами).
 *
 * <p>Проверяется через HTTP тем телом, которое шлёт экран: поле формы уходит
 * <b>строкой</b> («30,5»), и до правки такое тело отбивал разбор JSON общей
 * фразой «Запрос не разобран», а число {@code 30.5} молча округлялось вниз до 30
 * (Jackson по умолчанию принимает дробное в целое). Ни то ни другое не говорило
 * человеку, что не так с износом.
 *
 * <p>Своя схема ({@code t_000750}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class WheelWearInputHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000750";
    private static final String WORDS = "Износ шины — целое число процентов от 0 до 100";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockHttpSession owner;
    private long warehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 750");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (750, ?, 'Разборка износа', 'wear0345')""", TENANT);
        warehouse = inTenant(() -> {
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'vladelec'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Владелец Износа', 'OWNER', 'vladelec', ?)""",
                        passwordEncoder.encode("пароль"));
            }
            if (jdbc.queryForList("SELECT id FROM warehouse WHERE name = 'Склад износа 0345'").isEmpty()) {
                Long branch = jdbc.queryForObject(
                        "INSERT INTO branch (name) VALUES ('Филиал износа 0345') RETURNING id", Long.class);
                jdbc.update("INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад износа 0345')", branch);
            }
            return jdbc.queryForObject(
                    "SELECT id FROM warehouse WHERE name = 'Склад износа 0345'", Long.class);
        });
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"wear0345\",\"login\":\"vladelec\",\"password\":\"пароль\"}"))
                .andExpect(status().isOk()).andReturn();
        owner = (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    @DisplayName("«30,5», «30.5», «abc», «-1», «101», «1e1» отвечают 400 с объяснением, ничего не заводят")
    void unfitWearIsRefusedInWords() throws Exception {
        int before = wheels();
        for (String bad : new String[] {"\"30,5\"", "\"30.5\"", "\"abc\"", "\"-1\"", "\"101\"",
                "\"1e1\"", "30.5", "-1", "101"}) {
            send(bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(WORDS));
        }
        assertThat(wheels()).as("отказанный износ завёл колесо").isEqualTo(before);
    }

    @Test
    @DisplayName("«30», «30%», «0», «100», пусто и число 30 проходят")
    void fitWearPasses() throws Exception {
        for (String ok : new String[] {"\"30\"", "\" 30 \"", "\"30%\"", "\"0\"", "\"100\"",
                "30", "null", "\"\""}) {
            send(ok).andExpect(status().isCreated());
        }
        assertThat(inTenant(() -> jdbc.queryForList(
                "SELECT wear_percent FROM part_wheel WHERE wear_percent IS NOT NULL ORDER BY wear_percent",
                Integer.class))).contains(0, 30, 100);
    }

    private ResultActions send(String wear) throws Exception {
        return mvc.perform(post("/api/wheels/sets").with(csrf()).session(owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kind":"TYRE","warehouseId":%d,"quantity":1,"diameter":16,
                         "tyreWidth":205,"tyreHeight":55,"season":"SUMMER","wearPercent":%s,
                         "price":3000}""".formatted(warehouse, wear)));
    }

    private int wheels() {
        return inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_wheel", Integer.class));
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
