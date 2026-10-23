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
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Применимость ставится сама при заведении из донора, у неё есть автор и время,
 * у позиции — «Двигатель важен» (задача 0363, решение владельца 19.10.2026,
 * «всё как у bazon»).
 *
 * <p>Через HTTP: приёмку зовёт телефон, карточку и список читает экран; сервис
 * напрямую не увидел бы ни сериализации ответа, ни прав. Своя схема
 * ({@code t_000986}, тот же номер в реестре).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class FitFromDonorTest extends PostgresTestBase {

    private static final String TENANT = "t_000986";

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
    /** Донор с поколением и донор без него — одной марки и модели. */
    private static Long donorWithGeneration;
    private static Long donorWithoutGeneration;
    private static long generationId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 986");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (986, ?, 'Применимость из донора', 'fitdonor')""", TENANT);

        inTenant(() -> {
            if (warehouse != null) {
                return null;
            }
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    VALUES ('priemshchik', 'Приёмщик', ?, 'STOREKEEPER'),
                           ('vladelec', 'Владелец', ?, 'OWNER')""",
                    passwordEncoder.encode("пароль-подлиннее"),
                    passwordEncoder.encode("пароль-подлиннее"));
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            Long brand = jdbc.queryForObject(
                    "SELECT id FROM catalog.brand WHERE slug = 'toyota'", Long.class);
            Long model = jdbc.queryForObject(
                    "SELECT id FROM catalog.model WHERE brand_id = ? AND name = 'Camry'",
                    Long.class, brand);
            generationId = jdbc.queryForObject(
                    "SELECT min(id) FROM catalog.generation WHERE model_id = ? AND year_from IS NOT NULL",
                    Long.class, model);
            donorWithGeneration = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, model_id, generation_id, status)
                    VALUES (?, ?, ?, 'DISMANTLING') RETURNING id""",
                    Long.class, brand, model, generationId);
            donorWithoutGeneration = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, model_id, status)
                    VALUES (?, ?, 'DISMANTLING') RETURNING id""", Long.class, brand, model);
            return null;
        });
    }

    @Test
    @DisplayName("Позиция принята с донора с поколением: применимость задана сама, с автором и временем создания")
    void receiptSetsApplicabilityFromTheDonor() throws Exception {
        long part = receive(donorWithGeneration, "fit-gen-" + System.nanoTime());

        JsonNode fits = json(get("/api/parts/catalog/" + part + "/applicability"), "vladelec");
        assertThat(fits).as("после приёмки применимость пуста").hasSize(1);
        JsonNode fit = fits.get(0);
        assertThat(fit.get("brand").asText()).isEqualTo("Toyota");
        assertThat(fit.get("model").asText()).isEqualTo("Camry");
        assertThat(fit.get("generation").isNull()).as("поколение донора не записано").isFalse();
        assertThat(fit.get("verified").asBoolean())
                .as("поставила машина, а не человек").isFalse();
        assertThat(fit.get("setBy").asText()).as("автор приёмки").startsWith("Приёмщик (");
        assertThat(fit.get("setAt").isNull()).as("момент записи").isFalse();

        // Как у Bazon: «Применимость» установлена тем же моментом, что «Товар создан».
        Boolean sameMoment = inTenant(() -> jdbc.queryForObject("""
                SELECT a.set_at = (SELECT changed_at FROM audit_log
                                    WHERE table_name = 'part' AND record_id = a.part_id
                                      AND operation = 'INSERT')
                  FROM part_applicability a WHERE a.part_id = ?""", Boolean.class, part));
        assertThat(sameMoment).as("время установки совпало со временем заведения").isTrue();

        JsonNode state = json(get("/api/parts/catalog/" + part + "/applicability/state"), "vladelec");
        assertThat(state.get("engineMatters").asBoolean()).as("умолчание «не важен»").isFalse();
        assertThat(state.get("note").isNull()).isTrue();

        JsonNode changes = json(get("/api/parts/" + part + "/history"), "vladelec").get("changes");
        JsonNode line = null;
        for (JsonNode change : changes) {
            for (JsonNode field : change.get("fields")) {
                if ("Применимость".equals(field.get("label").asText())) {
                    line = change;
                    assertThat(field.get("before").isNull()).isTrue();
                    assertThat(field.get("after").asText()).startsWith("Toyota Camry");
                }
            }
        }
        assertThat(line).as("в истории позиции нет строки «Применимость: → …»").isNotNull();
        // Подпись автора — «Имя (логин, ID N)» (задача 0373).
        assertThat(line.get("author").asText()).startsWith("Приёмщик (");
    }

    @Test
    @DisplayName("Донор без поколения: применимости нет, и карточка говорит почему")
    void donorWithoutGenerationGivesNothingAndSaysWhy() throws Exception {
        long part = receive(donorWithoutGeneration, "fit-nogen-" + System.nanoTime());

        assertThat(json(get("/api/parts/catalog/" + part + "/applicability"), "vladelec"))
                .as("угадывать поколение нельзя").isEmpty();
        JsonNode state = json(get("/api/parts/catalog/" + part + "/applicability/state"), "vladelec");
        assertThat(state.get("note").asText()).contains("не указано поколение");
    }

    @Test
    @DisplayName("Ожидаемая позиция владельца с донором тоже получает применимость")
    void expectedPartGetsTheFitToo() throws Exception {
        Long supply = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO supply (kind, number, status)
                VALUES ('CONTAINER', ?, 'EXPECTED') RETURNING id""",
                Long.class, "ПР-" + System.nanoTime()));
        String requestId = "fit-exp-" + System.nanoTime();
        mvc.perform(post("/api/intake/supplies/" + supply + "/expected-parts")
                        .with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rawName":"Фара левая","donorId":%d,"quantity":1,"price":9000,
                                 "condition":"USED","requestId":"%s"}""".formatted(
                                donorWithGeneration, requestId)))
                .andExpect(status().isCreated());
        long part = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE client_request_id = ?", Long.class, requestId));

        JsonNode fits = json(get("/api/parts/catalog/" + part + "/applicability"), "vladelec");
        assertThat(fits).hasSize(1);
        assertThat(fits.get(0).get("setBy").asText()).startsWith("Владелец (");
    }

    @Test
    @DisplayName("«Двигатель важен» правит владелец, кладовщик получает 403; смена видна в истории, список показывает автора")
    void engineMattersIsEditableAndListed() throws Exception {
        long part = receive(donorWithGeneration, "fit-engine-" + System.nanoTime());

        mvc.perform(put("/api/parts/catalog/" + part + "/engine-matters").with(csrf())
                        .session(login("priemshchik"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"matters\":true}"))
                .andExpect(status().isForbidden());
        assertThat(json(get("/api/parts/catalog/" + part + "/applicability/state"), "vladelec")
                .get("engineMatters").asBoolean()).as("отказ записал признак").isFalse();

        JsonNode state = json(put("/api/parts/catalog/" + part + "/engine-matters")
                .contentType(MediaType.APPLICATION_JSON).content("{\"matters\":true}"), "vladelec");
        assertThat(state.get("engineMatters").asBoolean()).isTrue();

        JsonNode changes = json(get("/api/parts/" + part + "/history"), "vladelec").get("changes");
        boolean logged = false;
        for (JsonNode change : changes) {
            for (JsonNode field : change.get("fields")) {
                if ("Двигатель важен".equals(field.get("label").asText())) {
                    logged = "нет".equals(field.get("before").asText())
                            && "да".equals(field.get("after").asText());
                }
            }
        }
        assertThat(logged).as("в истории позиции нет «Двигатель важен: нет → да»").isTrue();

        JsonNode page = json(get("/api/parts/catalog/applicability-list"), "vladelec");
        JsonNode mine = null;
        for (JsonNode row : page.get("rows")) {
            if (row.get("partId").asLong() == part) {
                mine = row;
            }
        }
        assertThat(mine).as("позиции нет в списке «Применимость подробно»").isNotNull();
        assertThat(mine.get("engineMatters").asBoolean()).isTrue();
        assertThat(mine.get("setBy").asText()).startsWith("Приёмщик (");
        assertThat(mine.get("setAt").isNull()).isFalse();
        assertThat(page.get("total").asLong()).isPositive();
    }

    private long receive(Long donor, String requestId) throws Exception {
        mvc.perform(post("/api/intake/receipts").with(csrf()).session(login("priemshchik"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","warehouseId":%d,"donorId":%d,"items":[
                                 {"rawName":"Брызговик","price":100,"quantity":1,
                                  "sideFr":"FRONT","sideLr":"LEFT","condition":"USED"}]}"""
                                .formatted(requestId, warehouse, donor)))
                .andExpect(status().isCreated());
        return inTenant(() -> jdbc.queryForObject("""
                SELECT l.part_id FROM stock_document_line l
                  JOIN stock_document d ON d.id = l.document_id
                 WHERE d.client_request_id = ?""", Long.class, requestId));
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          String login) throws Exception {
        String body = mvc.perform(request.with(csrf()).session(login(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"fitdonor","login":"%s","password":"пароль-подлиннее"}"""
                                .formatted(login)))
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
