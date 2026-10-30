package ru.partsflow.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.Part;
import ru.partsflow.inventory.PartRepository;
import ru.partsflow.inventory.PartTitleGenerator;
import ru.partsflow.inventory.PartTitles;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.publishing.drom.DromPriceGenerator;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Правка машины пересобирает названия её позиций и отмечает их для дельты
 * Дрома (задача 0383).
 *
 * <p>Через HTTP: правку машины человек делает формой, и ответ с числом
 * переименованных — record, который вызов сервиса не проверил бы. Файл Дрома
 * собирается настоящим генератором: отмечать надо то, что изменилось
 * в объявлении, а не то, что кажется изменившимся.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class DonorRetitleTest extends PostgresTestBase {

    private static final String TENANT = "t_000383";

    /** Позиция, на пересборке которой подмена падает; пусто — подмена молчит. */
    private static volatile Long failOn;

    @TestConfiguration
    static class FailingTitles {
        @Bean
        @Primary
        PartTitles failingPartTitles(JdbcTemplate jdbc, PartTitleGenerator generator) {
            return new PartTitles(jdbc, generator) {
                @Override
                public boolean retitle(Part part) {
                    if (part.getId().equals(failOn)) {
                        throw new IllegalStateException("Подмена теста: пересборка упала");
                    }
                    return super.retitle(part);
                }
            };
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DromPriceGenerator generator;

    @Autowired
    private PartRepository parts;

    @Autowired
    private PartTitles titles;

    private final ObjectMapper json = new ObjectMapper();

    private Long toyota;
    private Long lexus;
    private Long camry;
    private Long corolla;
    private Long warehouse;
    private MockHttpSession owner;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        failOn = null;
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 383");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (383, ?, 'Правка машины и названия', 'retitle')""", TENANT);
        toyota = jdbc.queryForObject("SELECT id FROM catalog.brand WHERE slug = 'toyota'", Long.class);
        lexus = jdbc.queryForObject("SELECT id FROM catalog.brand WHERE slug = 'lexus'", Long.class);
        camry = jdbc.queryForObject(
                "SELECT id FROM catalog.model WHERE brand_id = ? AND slug = 'camry'", Long.class, toyota);
        corolla = jdbc.queryForObject(
                "SELECT id FROM catalog.model WHERE brand_id = ? AND slug = 'corolla'", Long.class, toyota);
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    SELECT 'mashina-pravit', 'Правящий машину', ?, 'OWNER'
                     WHERE NOT EXISTS (SELECT 1 FROM tenant_member WHERE login = 'mashina-pravit')""",
                    passwordEncoder.encode("пароль-подлиннее"));
            jdbc.update("UPDATE tenant_member SET display_name = 'Правящий машину', role = 'OWNER' "
                    + "WHERE login = 'mashina-pravit'");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
        owner = login();
    }

    @AfterEach
    void quiet() {
        failOn = null;
    }

    @Test
    @DisplayName("Год 2008 → 2007: обычная позиция называется 2007, вписанный руками год остаётся, история — от правившего")
    void yearReachesTheTitles() throws Exception {
        long donor = donor();
        long plain = receive(donor, "Фара левая");
        long ownModel = receive(donor, "Дверь");
        long ownYear = receive(donor, "Капот");
        ownVehicle(ownModel, "manual_model = 'Mark II'");
        ownVehicle(ownYear, "manual_year = 2010");
        String plainWas = title(plain);
        String ownYearWas = title(ownYear);
        assertThat(plainWas).contains("2008");
        assertThat(title(ownModel)).contains("Mark II", "2008");
        assertThat(ownYearWas).contains("2010");
        Map<String, Object> manualWas = manual(ownModel, ownYear);
        clearMarks();

        JsonNode saved = save(donor, form -> form.put("year", 2007)).andExpect(status().isOk())
                .andReturnBody();

        assertThat(title(plain)).as("название обычной позиции после правки года машины")
                .contains("2007").doesNotContain("2008");
        // Правило машины позиции — по полю (PartVehicle, решение 2 из PR 440):
        // вписанная модель года не закрывает, год идёт от машины.
        assertThat(title(ownModel)).contains("Mark II", "2007").doesNotContain("2008");
        assertThat(title(ownYear)).as("вписанный руками год сильнее года машины")
                .isEqualTo(ownYearWas);
        assertThat(manual(ownModel, ownYear)).as("правка машины тронула manual_* позиций")
                .isEqualTo(manualWas);
        assertThat(saved.path("renamed").asInt()).as("число переименованных в ответе").isEqualTo(2);

        List<String> renames = new ArrayList<>();
        for (JsonNode change : history(plain).path("changes")) {
            for (JsonNode field : change.path("fields")) {
                if (field.path("label").asText().equals("Наименование")) {
                    // Автор на экране — «Имя (логин, ID n)»: сверяется имя.
                    renames.add(change.path("author").asText().replaceFirst(" \\(.*\\)$", "") + ": " + field.path("before").asText()
                            + " → " + field.path("after").asText());
                }
            }
        }
        assertThat(renames).containsExactly(
                "Правящий машину: " + plainWas + " → " + plainWas.replace("2008", "2007"));
        assertThat(marked()).as("отметки для дельты Дрома").containsExactlyInAnyOrder(plain, ownModel);
    }

    @Test
    @DisplayName("Журнал действий: «Машина: Год выпуска 2008 → 2007» — одна запись, позиции не размножают её")
    void journalKeepsOneDonorEntry() throws Exception {
        long donor = donor();
        receive(donor, "Фара левая");
        receive(donor, "Дверь");
        // Схема общая у методов класса: считаются только записи этой правки.
        Set<Long> earlier = new HashSet<>();
        journal("Машина").path("items").forEach(e -> earlier.add(e.path("id").asLong()));
        journal("Товар").path("items").forEach(e -> earlier.add(e.path("id").asLong()));
        save(donor, form -> form.put("year", 2007)).andExpect(status().isOk());

        List<String> donorChanges = new ArrayList<>();
        for (JsonNode entry : journal("Машина").path("items")) {
            if (earlier.contains(entry.path("id").asLong())) {
                continue;
            }
            for (JsonNode change : entry.path("changes")) {
                if (change.path("label").asText().equals("Год выпуска")) {
                    donorChanges.add(change.path("before").asText() + " → " + change.path("after").asText());
                }
            }
        }
        assertThat(donorChanges).containsExactly("2008 → 2007");
        int renames = 0;
        for (JsonNode entry : journal("Товар").path("items")) {
            if (earlier.contains(entry.path("id").asLong())) {
                continue;
            }
            for (JsonNode change : entry.path("changes")) {
                if (change.path("label").asText().equals("Наименование")) {
                    renames++;
                }
            }
        }
        assertThat(renames).as("по записи «Наименование» на каждую переименованную позицию, не больше")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Правка заметки и пробега не переименовывает и не отмечает позиции")
    void noteAndMileageTouchNothing() throws Exception {
        long donor = donor();
        long part = receive(donor, "Фара левая");
        int historyWas = history(part).path("changes").size();
        clearMarks();

        JsonNode saved = save(donor, form -> form.put("note", "Синий маркер").put("mileageKm", 91000))
                .andExpect(status().isOk()).andReturnBody();

        assertThat(saved.path("renamed").asInt()).isZero();
        assertThat(history(part).path("changes").size()).as("запись в истории позиции").isEqualTo(historyWas);
        assertThat(marked()).as("строки в part_change после правки заметки").isEmpty();
    }

    @Test
    @DisplayName("По каждому полю формы: строка файла Дрома изменилась ⇔ позиция отмечена для дельты")
    void everyFieldMarksExactlyWhatTheFeedChanged() throws Exception {
        long donor = donor();
        long plain = receive(donor, "Фара левая");
        long ownYear = receive(donor, "Дверь");
        long ownBodyEngine = receive(donor, "Капот");
        long noKind = receive(donor, "Бампер");
        long bazonYear = receive(donor, "Зеркало");
        long ownModel = receive(donor, "Стекло");
        ownVehicle(ownYear, "manual_year = 2010");
        ownVehicle(ownBodyEngine, "manual_body = 'SXV20', manual_engine = '5SFE'");
        ownVehicle(bazonYear, "year_text = '03-08'");
        ownVehicle(ownModel, "manual_model = 'Mark II'");
        inTenant(() -> jdbc.update("UPDATE part SET part_name_id = NULL WHERE id = ?", noKind));
        Long otherGeneration = jdbc.query("""
                SELECT id FROM catalog.generation WHERE model_id = ?
                 ORDER BY (year_from IS NULL), year_from DESC, id LIMIT 1""",
                rs -> rs.next() ? rs.getLong(1) : null, camry);
        Long supply = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO supply (number) VALUES ('383') RETURNING id", Long.class));

        Map<String, java.util.function.Consumer<ObjectNode>> fields = new LinkedHashMap<>();
        fields.put("brandId", f -> f.put("brandId", lexus));
        fields.put("modelId", f -> f.put("modelId", corolla).putNull("generationId"));
        fields.put("generationId", f -> f.put("generationId", otherGeneration));
        fields.put("year", f -> f.put("year", 2007));
        fields.put("bodyCode", f -> f.put("bodyCode", "ACV40"));
        fields.put("engineCode", f -> f.put("engineCode", "1MZ-FE"));
        fields.put("steering", f -> f.put("steering", "LEFT"));
        fields.put("transmissionType", f -> f.put("transmissionType", "MT"));
        fields.put("transmissionModel", f -> f.put("transmissionModel", "U241E"));
        fields.put("driveType", f -> f.put("driveType", "AWD"));
        fields.put("color", f -> f.put("color", "Синий"));
        fields.put("equipmentCode", f -> f.put("equipmentCode", "700000"));
        fields.put("vin", f -> f.put("vin", "JTNBE40K003012345"));
        fields.put("mileageKm", f -> f.put("mileageKm", 12345));
        fields.put("supplyId", f -> f.put("supplyId", supply));
        fields.put("note", f -> f.put("note", "заметка"));

        ObjectNode base = (ObjectNode) card(donor).path("form");
        Set<Long> ours = Set.of(plain, ownYear, ownBodyEngine, noKind, bazonYear, ownModel);
        List<String> mismatches = new ArrayList<>();
        Set<String> moved = new TreeSet<>();
        for (var field : fields.entrySet()) {
            send(donor, base).andExpect(status().isOk());
            clearMarks();
            Map<Long, String> before = feed();
            assertThat(before.keySet()).as("позиции машины в файле Дрома").containsAll(ours);

            ObjectNode changed = base.deepCopy();
            field.getValue().accept(changed);
            send(donor, changed).andExpect(status().isOk());

            Map<Long, String> after = feed();
            Set<Long> differs = new HashSet<>();
            for (Long id : ours) {
                if (!before.get(id).equals(after.get(id))) {
                    differs.add(id);
                }
            }
            Set<Long> marks = new HashSet<>(marked());
            if (!differs.equals(marks)) {
                mismatches.add(field.getKey() + ": изменились строки " + differs + ", отмечены " + marks);
            }
            if (!differs.isEmpty()) {
                moved.add(field.getKey());
            }
        }
        assertThat(mismatches).as("поле формы, на котором файл и отметки разошлись").isEmpty();
        // Сверка не пустая: файл двигают ровно поля машины, остальные — нет.
        assertThat(moved).contains("brandId", "modelId", "year", "bodyCode", "engineCode")
                .doesNotContain("steering", "transmissionType", "transmissionModel", "driveType",
                        "color", "equipmentCode", "vin", "mileageKm", "supplyId", "note");
    }

    @Test
    @DisplayName("Пересборка упала — машина осталась с прежним годом, названия и отметки не тронуты")
    void failedRetitleRollsTheDonorBack() throws Exception {
        long donor = donor();
        long first = receive(donor, "Фара левая");
        long second = receive(donor, "Дверь");
        String firstWas = title(first);
        clearMarks();
        failOn = second;

        save(donor, form -> form.put("year", 2007))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("правка с упавшей пересборкой ответила успехом").isNotEqualTo(200));

        assertThat(card(donor).path("form").path("year").asInt())
                .as("год машины после упавшей пересборки").isEqualTo(2008);
        assertThat(title(first)).isEqualTo(firstWas);
        assertThat(marked()).isEmpty();
    }

    // ---------------------------------------------------------------- фикстуры

    private long donor() throws Exception {
        var result = mvc.perform(post("/api/intake/donors").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brandId":%d,"modelId":%d,"year":2008,"bodyCode":"ACV30",
                                 "engineCode":"2AZ-FE","steering":"RIGHT","transmissionType":"AT",
                                 "driveType":"FWD","color":"Белый","equipmentCode":"500000",
                                 "mileageKm":100000}""".formatted(toyota, camry)))
                .andExpect(status().isCreated())
                .andReturn();
        long donor = json.readTree(result.getResponse().getContentAsByteArray()).path("id").asLong();
        mvc.perform(post("/api/intake/donors/" + donor + "/dismantling").with(csrf()).session(owner))
                .andExpect(status().isOk());
        return donor;
    }

    private long receive(long donor, String name) throws Exception {
        String marker = "retitle-" + System.nanoTime();
        mvc.perform(post("/api/intake/receipts").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","warehouseId":%d,"donorId":%d,
                                 "items":[{"rawName":"%s","quantity":1,"price":5000}]}"""
                                .formatted(marker, warehouse, donor, name)))
                .andExpect(status().isCreated());
        return inTenant(() -> jdbc.queryForObject(
                "SELECT max(id) FROM part WHERE donor_id = ?", Long.class, donor));
    }

    /** Своя машина позиции, как её оставила карточка: поле записано, название пересобрано. */
    private void ownVehicle(long part, String assignment) {
        inTenant(() -> {
            jdbc.update("UPDATE part SET " + assignment + " WHERE id = ?", part);
            titles.retitle(parts.findById(part).orElseThrow());
            return null;
        });
    }

    private Map<String, Object> manual(long... ids) {
        Map<String, Object> found = new HashMap<>();
        inTenant(() -> {
            for (long id : ids) {
                found.put(String.valueOf(id), jdbc.queryForMap("""
                        SELECT manual_brand, manual_model, manual_generation, manual_body,
                               manual_engine, manual_year, manual_equipment, manual_donor_code
                          FROM part WHERE id = ?""", id));
            }
            return null;
        });
        return found;
    }

    private String title(long part) {
        return inTenant(() -> jdbc.queryForObject("SELECT title FROM part WHERE id = ?", String.class, part));
    }

    private List<Long> marked() {
        return inTenant(() -> jdbc.queryForList("SELECT part_id FROM part_change ORDER BY part_id", Long.class));
    }

    private void clearMarks() {
        inTenant(() -> jdbc.update("DELETE FROM part_change"));
    }

    /** Файл Дрома целиком, строка каждой позиции по её номеру в базе. */
    private Map<Long, String> feed() {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out);
            String xml = out.toString(StandardCharsets.UTF_8);
            Map<Long, Long> byNumber = new HashMap<>();
            jdbc.query("SELECT id, number FROM part", rs -> {
                byNumber.put(rs.getLong("number"), rs.getLong("id"));
            });
            Map<Long, String> found = new HashMap<>();
            Matcher part = Pattern.compile("<part>(.*?)</part>", Pattern.DOTALL).matcher(xml);
            while (part.find()) {
                Matcher id = Pattern.compile("<id>(\\d+)</id>").matcher(part.group(1));
                if (id.find()) {
                    found.put(byNumber.get(Long.parseLong(id.group(1))), part.group(1));
                }
            }
            return found;
        });
    }

    private Saved save(long donor, java.util.function.Consumer<ObjectNode> change) throws Exception {
        ObjectNode form = (ObjectNode) card(donor).path("form").deepCopy();
        change.accept(form);
        return new Saved(send(donor, form));
    }

    private ResultActions send(long donor, JsonNode form) throws Exception {
        return mvc.perform(put("/api/intake/donors/" + donor).with(csrf()).session(owner)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(form)));
    }

    /** Ответ правки — с телом, чтобы читать его после проверки статуса. */
    private final class Saved {
        private final ResultActions actions;

        Saved(ResultActions actions) {
            this.actions = actions;
        }

        Saved andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        JsonNode andReturnBody() throws Exception {
            return json.readTree(actions.andReturn().getResponse().getContentAsByteArray());
        }
    }

    private JsonNode card(long donor) throws Exception {
        return json.readTree(mvc.perform(get("/api/intake/donors/" + donor).session(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode history(long part) throws Exception {
        return json.readTree(mvc.perform(get("/api/parts/" + part + "/history").session(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode journal(String kind) throws Exception {
        return json.readTree(mvc.perform(get("/api/organization/audit").param("kind", kind)
                        .param("size", "200").session(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"retitle","login":"mashina-pravit","password":"пароль-подлиннее"}"""))
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
