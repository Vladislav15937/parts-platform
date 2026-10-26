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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Машина позиции с донором — под замком, меняется только «Номер донора»
 * (задача 0365, решение владельца продукта 19.10.2026 «Всё как у bazon»).
 *
 * <p>Через HTTP: замок живёт в правке карточки, перевязка — свой путь, и оба
 * зовёт экран; разбор тела и коды ответа вызов сервиса напрямую не увидел бы.
 * Своя схема ({@code t_001365}, тот же номер в реестре).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartDonorLockTest extends PostgresTestBase {

    private static final String TENANT = "t_001365";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static Long warehouse;
    /** Toyota Camry с поколением. */
    private static Long donorA;
    /** Машина другой марки с поколением — на неё перевязывают. */
    private static Long donorB;
    private static String brandB;
    private static String modelB;
    private static long markId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 1365");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (1365, ?, 'Замок машины', 'donorlock')""", TENANT);
        inTenant(() -> {
            if (warehouse != null) {
                return null;
            }
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    VALUES ('vladelec1365', 'Владелец Замков', ?, 'OWNER')""",
                    passwordEncoder.encode("пароль-подлиннее"));
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            Long toyota = jdbc.queryForObject(
                    "SELECT id FROM catalog.brand WHERE slug = 'toyota'", Long.class);
            Long camry = jdbc.queryForObject(
                    "SELECT id FROM catalog.model WHERE brand_id = ? AND name = 'Camry'",
                    Long.class, toyota);
            Long camryGen = jdbc.queryForObject("SELECT min(id) FROM catalog.generation"
                    + " WHERE model_id = ? AND year_from IS NOT NULL", Long.class, camry);
            donorA = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, model_id, generation_id, status, legacy_code)
                    VALUES (?, ?, ?, 'DISMANTLING', '261') RETURNING id""",
                    Long.class, toyota, camry, camryGen);
            Map<String, Object> other = jdbc.queryForMap("""
                    SELECT g.id AS generation_id, m.id AS model_id, b.id AS brand_id,
                           b.name AS brand, m.name AS model
                      FROM catalog.generation g
                      JOIN catalog.model m ON m.id = g.model_id
                      JOIN catalog.brand b ON b.id = m.brand_id
                     WHERE b.id <> ? AND g.year_from IS NOT NULL
                       AND m.name !~ '[,]' AND b.name !~ '[,]'
                     ORDER BY g.id LIMIT 1""", toyota);
            brandB = (String) other.get("brand");
            modelB = (String) other.get("model");
            donorB = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, model_id, generation_id, status, legacy_code)
                    VALUES (?, ?, ?, 'DISMANTLING', '262') RETURNING id""", Long.class,
                    other.get("brand_id"), other.get("model_id"), other.get("generation_id"));
            markId = jdbc.queryForObject("SELECT min(id) FROM catalog.bazon_mark", Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("У позиции с донором правка марки — 400 словами, своя марка не записана")
    void brandOfDonorPartIsLocked() throws Exception {
        long part = receive(donorA);

        MvcResult refused = edit(part, "{\"brand\":\"Lexus\"}");
        assertThat(refused.getResponse().getStatus())
                .as("правка марки у позиции с донором прошла").isEqualTo(400);
        assertThat(message(refused)).contains("«Марка»").contains("«Номер донора»");
        assertThat(own(part, "manual_brand")).as("своя марка записана").isNull();
    }

    @Test
    @DisplayName("Каждое поле машины под замком — перебором; «Изменить» прочих полей работает")
    void everyVehicleFieldIsLocked() throws Exception {
        long part = receive(donorA);
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("donorCode", "{\"donorCode\":\"999\"}");
        bodies.put("brand", "{\"brand\":\"Lexus\"}");
        bodies.put("model", "{\"model\":\"ES350\"}");
        bodies.put("body", "{\"body\":\"GSV40\"}");
        bodies.put("engine", "{\"engine\":\"2GR-FE\"}");
        bodies.put("year", "{\"year\":\"2008\"}");
        bodies.put("equipment", "{\"equipment\":\"полный привод\"}");
        bodies.put("generation", "{\"generation\":\"XV50\"}");
        bodies.put("bazonVehicle", "{\"bazonVehicle\":{\"markId\":" + markId + "}}");
        assertThat(bodies.keySet()).as("перебор не совпал с полями под замком")
                .containsExactlyElementsOf(PartIdentity.DONOR_LOCKED);

        List<String> passed = new ArrayList<>();
        for (var entry : bodies.entrySet()) {
            if (edit(part, entry.getValue()).getResponse().getStatus() != 400) {
                passed.add(entry.getKey());
            }
        }
        assertThat(passed).as("правка прошла мимо замка").isEmpty();

        MvcResult note = edit(part, "{\"note\":\"полка у окна\"}");
        assertThat(note.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Значение, равное лежащему, — не правка: вкладка, открытая до выкладки, сохраняет")
    void sameValueIsNotRefused() throws Exception {
        long part = receive(donorA);
        MvcResult result = edit(part, """
                {"brand":"","model":"","year":"","bazonVehicle":{},"note":"с той же машиной"}""");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("У позиции без донора марка и модель правятся, как раньше")
    void partWithoutDonorKeepsEditing() throws Exception {
        long part = receive(null);
        MvcResult result = edit(part, "{\"brand\":\"Lexus\",\"model\":\"ES350\"}");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(own(part, "manual_brand")).isEqualTo("Lexus");
    }

    @Test
    @DisplayName("Сохранённое своё у позиции с донором не стирается правкой соседнего поля")
    void storedOwnValuesStay() throws Exception {
        long part = receive(donorA);
        inTenant(() -> jdbc.update("UPDATE part SET manual_brand = 'Lexus' WHERE id = ?", part));

        assertThat(json(get("/api/parts/" + part + "/editable")).get("brand").asText())
                .isEqualTo("Lexus");
        assertThat(edit(part, "{\"note\":\"другая заметка\"}").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(own(part, "manual_brand")).isEqualTo("Lexus");
    }

    @Test
    @DisplayName("Смена номера донора меняет марку, модель, название и применимость; своё снимается, в истории — «Донор»")
    void changingTheDonorRecomputesTheVehicle() throws Exception {
        long part = receive(donorA);
        inTenant(() -> jdbc.update(
                "UPDATE part SET manual_body = 'ACV40', bazon_mark_id = ? WHERE id = ?", markId, part));
        String titleWas = title(part);
        assertThat(titleWas).contains("Toyota");

        MvcResult result = changeDonor(part, donorB);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT donor_id FROM part WHERE id = ?", Long.class, part))).isEqualTo(donorB);
        assertThat(own(part, "manual_body")).as("своё прежней машины осталось").isNull();
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT bazon_mark_id FROM part WHERE id = ?", Long.class, part))).isNull();
        String titleNow = title(part);
        assertThat(titleNow).as("название не пересобрано").contains(brandB).doesNotContain("Toyota");
        assertThat(JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("title").asText()).isEqualTo(titleNow);

        JsonNode row = catalogRow(part);
        assertThat(row.get("brand").asText()).isEqualTo(brandB);
        assertThat(row.get("model").asText()).isEqualTo(modelB);
        assertThat(row.get("donorCode").asText()).isEqualTo("262");

        JsonNode fits = json(get("/api/parts/catalog/" + part + "/applicability"));
        List<String> brands = new ArrayList<>();
        fits.forEach(f -> brands.add(f.get("brand").asText()));
        assertThat(brands).as("применимость не перевязана").containsExactly(brandB);

        boolean donorLine = false;
        for (JsonNode change : json(get("/api/parts/" + part + "/history")).get("changes")) {
            for (JsonNode field : change.get("fields")) {
                if ("Донор".equals(field.get("label").asText())) {
                    donorLine = "261".equals(field.get("before").asText())
                            && "262".equals(field.get("after").asText());
                }
            }
        }
        assertThat(donorLine).as("в истории нет «Донор: 261 → 262»").isTrue();
    }

    @Test
    @DisplayName("Подтверждённая человеком применимость прежней машины при смене донора остаётся")
    void verifiedFitStays() throws Exception {
        long part = receive(donorA);
        inTenant(() -> jdbc.update(
                "UPDATE part_applicability SET is_verified = true WHERE part_id = ?", part));
        assertThat(changeDonor(part, donorB).getResponse().getStatus()).isEqualTo(200);
        assertThat(json(get("/api/parts/catalog/" + part + "/applicability"))).hasSize(2);
    }

    @Test
    @DisplayName("Номер донора у позиции без донора, неизвестная машина и пустой выбор — 400 словами")
    void donorChangeRefusals() throws Exception {
        MvcResult noDonor = changeDonor(receive(null), donorB);
        assertThat(noDonor.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(noDonor)).contains("без машины-донора");

        long part = receive(donorA);
        MvcResult unknown = changeDonor(part, 987_654_321L);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(unknown)).doesNotContain("987654321");

        MvcResult empty = mvc.perform(put("/api/parts/" + part + "/donor").with(csrf())
                        .session(login()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertThat(empty.getResponse().getStatus()).isEqualTo(400);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT donor_id FROM part WHERE id = ?", Long.class, part))).isEqualTo(donorA);
    }

    private long receive(Long donor) throws Exception {
        String requestId = "lock-" + System.nanoTime();
        mvc.perform(post("/api/intake/receipts").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","warehouseId":%d,%s"items":[
                                 {"rawName":"Фара","price":100,"quantity":1,
                                  "sideFr":"FRONT","sideLr":"LEFT","condition":"USED"}]}"""
                                .formatted(requestId, warehouse,
                                        donor == null ? "" : "\"donorId\":" + donor + ",")))
                .andExpect(status().isCreated());
        return inTenant(() -> jdbc.queryForObject("""
                SELECT l.part_id FROM stock_document_line l
                  JOIN stock_document d ON d.id = l.document_id
                 WHERE d.client_request_id = ?""", Long.class, requestId));
    }

    private MvcResult edit(long part, String body) throws Exception {
        return mvc.perform(put("/api/parts/" + part).with(csrf()).session(login())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult changeDonor(long part, long donor) throws Exception {
        return mvc.perform(put("/api/parts/" + part + "/donor").with(csrf()).session(login())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"donorId\":" + donor + "}")).andReturn();
    }

    private JsonNode catalogRow(long part) throws Exception {
        String code = inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, part));
        JsonNode page = json(get("/api/parts/catalog").param("q", code));
        for (JsonNode row : page.get("rows")) {
            if (row.get("id").asLong() == part) {
                return row;
            }
        }
        throw new AssertionError("позиции нет на витрине");
    }

    private String own(long part, String column) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT " + column + " FROM part WHERE id = ?", String.class, part));
    }

    private String title(long part) {
        return own(part, "title");
    }

    private static String message(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("message").asText();
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        String body = mvc.perform(request.with(csrf()).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"donorlock","login":"vladelec1365","password":"пароль-подлиннее"}"""))
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
