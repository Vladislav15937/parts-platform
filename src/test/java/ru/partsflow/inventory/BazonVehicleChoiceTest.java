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
 * Машина позиции — ссылка на общий справочник {@code catalog.bazon_*} (задача 0377).
 *
 * <p>Через HTTP: ответы справочника и правки — record'ы, роль стоит аннотацией,
 * а отказ на несогласованную четвёрку собирает обработчик исключений.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class BazonVehicleChoiceTest extends PostgresTestBase {

    private static final String TENANT = "t_001001";
    private static final String OTHER = "t_001002";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper json;

    /** Запись справочника, выбранная в тестах: Toyota Camry с кузовом и двигателем. */
    private long mark;
    private long model;
    private long body;
    private long motor;
    private String markWord;
    private String modelWord;
    private String bodyWord;
    private String motorWord;
    private long partId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT, OTHER);
    }

    @BeforeEach
    void fixtures() {
        mark = jdbc.queryForObject("SELECT id FROM catalog.bazon_mark WHERE name = 'Toyota'", Long.class);
        model = jdbc.queryForObject("SELECT id FROM catalog.bazon_model WHERE bazon_mark_id = ?"
                + " AND name = 'Camry'", Long.class, mark);
        body = jdbc.queryForObject("""
                SELECT b.id FROM catalog.bazon_body b
                  JOIN catalog.bazon_generation g ON g.id = b.bazon_generation_id
                 WHERE g.bazon_model_id = ? AND b.name IS NOT NULL ORDER BY b.id LIMIT 1""",
                Long.class, model);
        motor = jdbc.queryForObject("SELECT id FROM catalog.bazon_motor WHERE bazon_mark_id = ?"
                + " ORDER BY id LIMIT 1", Long.class, mark);
        markWord = word("bazon_mark", mark);
        modelWord = word("bazon_model", model);
        bodyWord = word("bazon_body", body);
        motorWord = word("bazon_motor", motor);

        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id IN (1001, 1002)");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (1001, ?, 'Разборка', 'bazonveh'), (1002, ?, 'Вторая', 'bazonveh2')""",
                TENANT, OTHER);
        for (String tenant : new String[] {TENANT, OTHER}) {
            inTenant(tenant, () -> {
                member("vladelec0377", "Владелец", "OWNER");
                return null;
            });
        }
        // Позиция, перенесённая из Bazon: без донора и без своей машины, машина — только в названии.
        partId = inTenant(TENANT, () -> {
            Long kind = jdbc.queryForObject("""
                    INSERT INTO part_name (name) VALUES ('Фара ' || ?)
                    ON CONFLICT (lower(btrim(name))) DO UPDATE SET name = excluded.name
                    RETURNING id""", Long.class, "0377");
            jdbc.update("DELETE FROM part WHERE legacy_code = 'B-0377'");
            return jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, part_name_id, legacy_code,
                                      is_published)
                    VALUES (1, 'Фара 0377 Старая Модель Старый Кузов (б/у)', 4500, ?, 'B-0377', true)
                    RETURNING id""", Long.class, kind);
        });
    }

    @Test
    @DisplayName("Списки справочника сужаются выбранным: марка → модель → кузов, двигатели марки")
    void listsNarrowByTheParent() throws Exception {
        MockHttpSession owner = login("bazonveh");

        JsonNode marks = read(owner, "/api/catalog/bazon/marks");
        assertThat(names(marks)).contains("Toyota");

        JsonNode models = read(owner, "/api/catalog/bazon/marks/" + mark + "/models");
        assertThat(names(models)).contains("Camry");

        JsonNode bodies = read(owner, "/api/catalog/bazon/models/" + model + "/bodies");
        assertThat(bodies.size()).isPositive();
        assertThat(bodies.findValuesAsText("name")).contains(bodyWord);

        JsonNode motors = read(owner, "/api/catalog/bazon/marks/" + mark + "/motors");
        assertThat(motors.findValuesAsText("name")).contains(motorWord);
    }

    @Test
    @DisplayName("Выбрав машину в карточке перенесённой позиции, получаем название по формуле"
            + " со словами справочника, ссылки в форме и запись в истории «Наименование»")
    void chosenMachineRebuildsTheTitle() throws Exception {
        MockHttpSession owner = login("bazonveh");

        edit(owner, partId, """
                {"bazonVehicle":{"markId":%d,"modelId":%d,"bodyId":%d,"motorId":%d}}"""
                .formatted(mark, model, body, motor));

        String title = inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT title FROM part WHERE id = ?", String.class, partId));
        assertThat(title)
                .contains(markWord, modelWord, bodyWord, motorWord)
                .as("слов прежнего названия не осталось: машину теперь называют поля")
                .doesNotContain("Старая Модель", "Старый Кузов");

        JsonNode form = json.readTree(mvc.perform(get("/api/parts/" + partId + "/editable")
                        .session(owner)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(form.path("bazonVehicle").path("markId").asLong()).isEqualTo(mark);
        assertThat(form.path("bazonVehicle").path("motorId").asLong()).isEqualTo(motor);

        String history = mvc.perform(get("/api/parts/" + partId + "/history").session(owner))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(history).contains("Наименование", "Марка (справочник)", markWord);
    }

    @Test
    @DisplayName("Слова те же у позиции другой компании: источник один")
    void sameWordsInAnotherTenant() throws Exception {
        long other = inTenant(OTHER, () -> {
            Long kind = jdbc.queryForObject("""
                    INSERT INTO part_name (name) VALUES ('Фара другой')
                    ON CONFLICT (lower(btrim(name))) DO UPDATE SET name = excluded.name
                    RETURNING id""", Long.class);
            return jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, part_name_id, is_published)
                    VALUES (1, 'Фара другой', 100, ?, true) RETURNING id""", Long.class, kind);
        });
        MockHttpSession second = login("bazonveh2");

        mvc.perform(put("/api/parts/" + other).with(csrf()).session(second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bazonVehicle":{"markId":%d,"modelId":%d,"bodyId":null,"motorId":null}}"""
                                .formatted(mark, model)))
                .andExpect(status().isOk());

        String title = inTenant(OTHER, () -> jdbc.queryForObject(
                "SELECT title FROM part WHERE id = ?", String.class, other));
        assertThat(title).contains(markWord + " " + modelWord);
    }

    @Test
    @DisplayName("У арендатора нет копии справочника: ни таблиц, ни колонок со словами машины")
    void tenantSchemaHasNoCopyOfTheCatalog() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = ? AND table_name LIKE 'bazon%'""", String.class, TENANT))
                .isEmpty();
        // У позиции только ссылки: ни одной текстовой колонки bazon_*.
        assertThat(jdbc.queryForList("""
                SELECT column_name || ':' || data_type FROM information_schema.columns
                 WHERE table_schema = ? AND table_name = 'part' AND column_name LIKE 'bazon%'
                 ORDER BY column_name""", String.class, TENANT))
                .containsExactly("bazon_body_id:bigint", "bazon_mark_id:bigint",
                        "bazon_model_id:bigint", "bazon_motor_id:bigint");
    }

    @Test
    @DisplayName("Несогласованная четвёрка — отказ словами, а не пятисотка и не запись")
    void inconsistentSelectionIsRefusedInWords() throws Exception {
        MockHttpSession owner = login("bazonveh");
        long anotherMark = jdbc.queryForObject(
                "SELECT id FROM catalog.bazon_mark WHERE id <> ? ORDER BY id LIMIT 1", Long.class, mark);
        long anotherModel = jdbc.queryForObject(
                "SELECT id FROM catalog.bazon_model WHERE bazon_mark_id = ? ORDER BY id LIMIT 1",
                Long.class, anotherMark);

        String modelOfOtherMark = mvc.perform(put("/api/parts/" + partId).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bazonVehicle":{"markId":%d,"modelId":%d,"bodyId":null,"motorId":null}}"""
                                .formatted(mark, anotherModel)))
                .andExpect(status().is4xxClientError())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(modelOfOtherMark).contains("не относится к выбранной марке");

        String bodyWithoutModel = mvc.perform(put("/api/parts/" + partId).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bazonVehicle":{"markId":%d,"modelId":null,"bodyId":%d,"motorId":null}}"""
                                .formatted(mark, body)))
                .andExpect(status().is4xxClientError())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(bodyWithoutModel).contains("Кузов выбирается после модели");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bazonVehicle":{"markId":%d,"modelId":null,"bodyId":null,"motorId":%d}}"""
                                .formatted(anotherMark,
                                        jdbc.queryForObject("SELECT id FROM catalog.bazon_motor"
                                                + " WHERE bazon_mark_id = ? ORDER BY id LIMIT 1",
                                                Long.class, mark))))
                .andExpect(status().is4xxClientError());

        assertThat(inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT bazon_mark_id FROM part WHERE id = ?", Long.class, partId)))
                .as("отказанная правка что-то записала")
                .isNull();
    }

    @Test
    @DisplayName("Позиции без выбранной машины правка цены не трогает: название то, с каким пришла")
    void untouchedPositionKeepsItsTitle() throws Exception {
        MockHttpSession owner = login("bazonveh");

        edit(owner, partId, "{\"price\":4700}");

        assertThat(inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT title FROM part WHERE id = ?", String.class, partId)))
                .isEqualTo("Фара 0377 Старая Модель Старый Кузов (б/у)");
    }

    @Test
    @DisplayName("Приёмка с машиной из справочника: название со словами справочника и ссылки у позиции;"
            + " повтор по тому же ключу даёт ту же партию")
    void intakeWithChosenMachine() throws Exception {
        MockHttpSession owner = login("bazonveh");
        long warehouse = inTenant(TENANT, () -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал 0377') RETURNING id", Long.class);
            return jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад 0377') RETURNING id",
                    Long.class, branch);
        });
        String request = """
                {"warehouseId":%d,"requestId":"bazon-0377-1","items":[
                  {"rawName":"Крыло 0377","quantity":1,"price":1000,
                   "bazonVehicle":{"markId":%d,"modelId":%d,"bodyId":%d,"motorId":%d}}]}"""
                .formatted(warehouse, mark, model, body, motor);

        String first = mvc.perform(post("/api/intake/receipts").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String second = mvc.perform(post("/api/intake/receipts").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        // Сумма в повторе пишется иначе (1000.0 и 1000.00) — сравнивается партия: документ и позиция.
        assertThat(json.readTree(second).path("documentId"))
                .isEqualTo(json.readTree(first).path("documentId"));
        assertThat(json.readTree(second).path("parts").path(0).path("id"))
                .isEqualTo(json.readTree(first).path("parts").path(0).path("id"));

        var row = inTenant(TENANT, () -> jdbc.queryForMap("""
                SELECT title, bazon_mark_id, bazon_motor_id FROM part WHERE title LIKE 'Крыло 0377%'"""));
        assertThat((String) row.get("title")).contains(markWord, modelWord, bodyWord, motorWord);
        assertThat(((Number) row.get("bazon_mark_id")).longValue()).isEqualTo(mark);
        assertThat(((Number) row.get("bazon_motor_id")).longValue()).isEqualTo(motor);

        mvc.perform(post("/api/intake/receipts").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"requestId":"bazon-0377-2","items":[
                                  {"rawName":"Крыло 0377 б","quantity":1,"price":1000,
                                   "bazonVehicle":{"markId":%d,"modelId":%d,"bodyId":null,"motorId":null}}]}"""
                                .formatted(warehouse, anotherMarkId(), model)))
                .andExpect(status().is4xxClientError());
    }

    private long anotherMarkId() {
        return jdbc.queryForObject(
                "SELECT id FROM catalog.bazon_mark WHERE id <> ? ORDER BY id LIMIT 1", Long.class, mark);
    }

    private String word(String table, long id) {
        return jdbc.queryForObject("SELECT name FROM catalog." + table + " WHERE id = ?",
                String.class, id);
    }

    private static java.util.List<String> names(JsonNode list) {
        return list.findValuesAsText("name");
    }

    private JsonNode read(MockHttpSession session, String url) throws Exception {
        return json.readTree(mvc.perform(get(url).session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void edit(MockHttpSession session, long part, String body) throws Exception {
        mvc.perform(put("/api/parts/" + part).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private Long member(String login, String displayName, String role) {
        var found = jdbc.queryForList("SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, displayName, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String company) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"vladelec0377","password":"пароль"}"""
                                .formatted(company)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(String tenant, Supplier<T> body) {
        TenantContext.set(tenant);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
