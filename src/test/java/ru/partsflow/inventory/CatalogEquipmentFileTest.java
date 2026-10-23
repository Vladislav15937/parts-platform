package ru.partsflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import ru.partsflow.intake.Donor;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * «Комплектация» в скачанном файле склада — то же, что в клетке экрана.
 *
 * <p>Файл качают ради сверки с экраном, а у файла была своя копия выражения
 * без {@code AMT → робот}: у позиции с донором на роботе клетка экрана
 * говорила «робот», файл — пусто (мелочь из ответа владельца по 0044,
 * 24.10.2026; разбор PR 500). Перебор по всем значениям словарей донора —
 * руль, коробка, привод, — а не по одному AMT: новое значение перечисления
 * попадает в проверку в тот день, когда его дописали.
 *
 * <p>Через HTTP: страница и файл — разные запросы с разными соединениями.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogEquipmentFileTest extends PostgresTestBase {

    private static final String TENANT = "t_000121";
    private static final int REGISTRY_ID = 121;
    private static final String COMPANY = "equipmentfile";

    private static boolean seeded;
    /** Номер позиции → чем собрана её комплектация (для сообщения). */
    private static final Map<Long, String> CASES = new LinkedHashMap<>();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private StockLedger ledger;

    private final ObjectMapper json = new ObjectMapper();
    private MockHttpSession session;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        if (!seeded) {
            seed();
            seeded = true;
        }
        session = login();
    }

    private void seed() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", REGISTRY_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка: комплектация в файле', ?)""", REGISTRY_ID, TENANT, COMPANY);
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES ('Владелец', 'OWNER', 'owner', ?)""", passwordEncoder.encode("пароль"));
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Основной') RETURNING id",
                    Long.class, branch);
            for (Donor.TransmissionType value : Donor.TransmissionType.values()) {
                part(warehouse, "коробка " + value, null, value.name(), null);
            }
            for (Donor.Steering value : Donor.Steering.values()) {
                part(warehouse, "руль " + value, value.name(), null, null);
            }
            for (Donor.DriveType value : Donor.DriveType.values()) {
                part(warehouse, "привод " + value, null, null, value.name());
            }
            return null;
        });
    }

    @Test
    @DisplayName("Комплектация в файле — та же, что в клетке экрана, у каждого значения донора")
    void fileEquipmentMatchesTheScreen() throws Exception {
        Map<Long, String> screen = new LinkedHashMap<>();
        page().path("rows").forEach(row ->
                screen.put(row.path("number").asLong(), row.path("equipment").asText("")));
        Map<Long, String> file = fileEquipment();

        assertThat(screen.keySet()).as("экран показал не все позиции").containsAll(CASES.keySet());
        for (var entry : CASES.entrySet()) {
            assertThat(file.get(entry.getKey()))
                    .as("%s: в файле «Комплектация» не та, что на экране", entry.getValue())
                    .isEqualTo(screen.get(entry.getKey()));
        }
        // Сверка с экраном сама по себе зелена и на двух одинаково пустых
        // клетках — поэтому сам случай из жалобы назван отдельно.
        long amt = CASES.entrySet().stream()
                .filter(e -> e.getValue().equals("коробка AMT")).findFirst().orElseThrow().getKey();
        assertThat(file.get(amt)).as("робот в скачанном файле").isEqualTo("робот");
    }

    // --- помощники ---

    private void part(long warehouse, String what, String steering, String transmission,
                      String drive) {
        Long donor = jdbc.queryForObject("""
                INSERT INTO donor (brand_id, steering, transmission_type, drive_type)
                VALUES ((SELECT id FROM catalog.brand ORDER BY id LIMIT 1), ?, ?, ?)
                RETURNING id""", Long.class, steering, transmission, drive);
        Long partId = jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, donor_id)
                VALUES (1, ?, 1000, ?) RETURNING id""", Long.class, "Деталь: " + what, donor);
        ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
        Long number = jdbc.queryForObject("SELECT number FROM part WHERE id = ?", Long.class, partId);
        CASES.put(number, what);
    }

    private JsonNode page() throws Exception {
        String body = mvc.perform(get("/api/parts/catalog")
                        .param("missing", "true")
                        .param("size", "50")
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    /** Номер позиции → «Комплектация» из скачанного файла, колонка ищется по заголовку. */
    private Map<Long, String> fileEquipment() throws Exception {
        String body = mvc.perform(get("/api/parts/catalog/export")
                        .param("missing", "true")
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] lines = body.replace("﻿", "").split("\r?\n");
        List<String> header = cells(lines[0]);
        int column = header.indexOf("Комплектация");
        assertThat(column).as("в файле нет колонки «Комплектация»: %s", header).isNotNegative();
        Map<Long, String> out = new LinkedHashMap<>();
        for (int at = 1; at < lines.length; at++) {
            if (lines[at].isBlank()) {
                continue;
            }
            List<String> row = cells(lines[at]);
            out.put(Long.parseLong(row.get(0).strip()), column < row.size() ? row.get(column) : "");
        }
        return out;
    }

    /** Строка файла по «;», с кавычками и удвоенной кавычкой внутри. */
    private static List<String> cells(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ';') {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(cell.toString());
        return out;
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"owner","password":"пароль"}"""
                                .formatted(COMPANY)))
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
