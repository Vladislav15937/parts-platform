package ru.partsflow.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Номер донора вместо внутреннего кода, поля формы «Создание донора»
 * и список статусов машины (задача 0364, вторая часть — после схемы
 * tenant/108–110).
 *
 * <p>Через HTTP: ответы — record'ы, номер назначается под блокировкой
 * в транзакции заведения, и шестнадцатеричный код машины надо искать
 * в том, что уходит наружу, а не в том, что вернул сервис.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class DonorNumberTest extends PostgresTestBase {

    private static final String TENANT = "t_000366";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long brand;
    private Long warehouse;
    private MockHttpSession owner;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 366");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (366, ?, 'Номер машины', 'donornumber')""", TENANT);
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    SELECT 'nomer-vladelets', 'Владелец номеров', ?, 'OWNER'
                     WHERE NOT EXISTS (SELECT 1 FROM tenant_member WHERE login = 'nomer-vladelets')""",
                    passwordEncoder.encode("пароль-подлиннее"));
            jdbc.update("UPDATE tenant_member SET display_name = 'Владелец номеров', role = 'OWNER' "
                    + "WHERE login = 'nomer-vladelets'");
            brand = jdbc.queryForObject(
                    "SELECT id FROM catalog.brand WHERE name = 'Toyota' ORDER BY id LIMIT 1",
                    Long.class);
            warehouse = jdbc.query("SELECT id FROM warehouse WHERE name = 'Ткацкая' ORDER BY id LIMIT 1",
                    (rs, i) -> rs.getLong(1)).stream().findFirst().orElseGet(() -> {
                        Long branch = jdbc.queryForObject(
                                "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
                        return jdbc.queryForObject(
                                "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                                Long.class, branch);
                    });
            return null;
        });
        owner = login();
    }

    @Test
    @DisplayName("«Следующий» номер — наибольший плюс один, а не очередное значение последовательности")
    void nextNumberCountsTheHighest() throws Exception {
        long highest = maxNumber() + 500;
        // Так ложится номер, вписанный руками или пришедший переносом:
        // последовательность о нём не знает.
        inTenant(() -> jdbc.update(
                "INSERT INTO donor (brand_id, number, status) VALUES (?, ?, 'PURCHASED')", brand, highest));

        assertThat(nextNumber()).as("форма подставила бы номер ниже занятого").isEqualTo(highest + 1);
        JsonNode created = createDonor("{\"brandId\":%d,\"year\":2007}");
        assertThat(created.path("number").asLong())
                .as("машина без вписанного номера получила не следующий за наибольшим")
                .isEqualTo(highest + 1);
        assertThat(nextNumber()).isEqualTo(highest + 2);
    }

    @Test
    @DisplayName("Вписанный номер сохраняется; занятый — 409 словами, и при заведении, и при правке")
    void typedNumberIsKeptAndTakenIsRefused() throws Exception {
        long typed = maxNumber() + 1000;
        JsonNode first = createDonor("{\"brandId\":%d,\"number\":" + typed + "}");
        assertThat(first.path("number").asLong()).isEqualTo(typed);

        MvcResult refused = send(post("/api/intake/donors"),
                "{\"brandId\":%d,\"number\":%d}".formatted(brand, typed), status().isConflict());
        assertThat(text(refused)).contains("Номер донора " + typed, "уже у другой машины");
        assertThat(createDonor("{\"brandId\":%d}").path("number").asLong()).isEqualTo(typed + 1);

        long second = createDonor("{\"brandId\":%d}").path("id").asLong();
        ObjectNode form = (ObjectNode) card(second).path("form").deepCopy();
        send(put("/api/intake/donors/" + second), form.put("number", typed).toString(),
                status().isConflict());
        JsonNode saved = json(send(put("/api/intake/donors/" + second),
                form.put("number", typed + 50).toString(), status().isOk()));
        assertThat(saved.path("card").path("code").asText()).isEqualTo(String.valueOf(typed + 50));

        send(post("/api/intake/donors"), "{\"brandId\":%d,\"number\":0}".formatted(brand),
                status().isBadRequest());
    }

    @Test
    @DisplayName("«Стоимость» из формы — закупка в журнале затрат машины, и только у того, кто ведёт затраты")
    void costIsAPurchaseInTheCostJournal() throws Exception {
        // Ответ владельца 31.10.2026: «Стоимость — журналом затрат».
        long donor = createDonor("{\"brandId\":%d,\"cost\":150000}").path("id").asLong();
        JsonNode costs = json(mvc.perform(get("/api/intake/donors/" + donor + "/costs").session(owner))
                .andExpect(status().isOk()).andReturn());
        assertThat(costs).as("стоимость из формы не легла в журнал затрат").hasSize(1);
        assertThat(costs.get(0).path("type").asText()).isEqualTo("PURCHASE");
        assertThat(costs.get(0).path("amount").decimalValue()).isEqualByComparingTo("150000");
        assertThat(json(mvc.perform(get("/api/organization/audit").param("kind", "Затрата по машине")
                        .session(owner)).andExpect(status().isOk()).andReturn()).path("items"))
                .as("закупка из формы не видна в журнале действий").isNotEmpty();

        long empty = createDonor("{\"brandId\":%d}").path("id").asLong();
        assertThat(json(mvc.perform(get("/api/intake/donors/" + empty + "/costs").session(owner))
                .andReturn())).as("пустая «Стоимость» завела затрату").isEmpty();

        long before = donorCount();
        MvcResult negative = send(post("/api/intake/donors"),
                "{\"brandId\":%d,\"cost\":-1}".formatted(brand), status().isBadRequest());
        assertThat(text(negative)).contains("не может быть отрицательной");
        assertThat(donorCount()).as("машина осталась без отказанной стоимости").isEqualTo(before);

        MockHttpSession storekeeper = login("nomer-kladovshchik", "STOREKEEPER");
        MvcResult refused = mvc.perform(post("/api/intake/donors").with(csrf()).session(storekeeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brandId\":%d,\"cost\":1000}".formatted(brand)))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(text(refused)).contains("Стоимость");
        assertThat(donorCount()).isEqualTo(before);
        mvc.perform(post("/api/intake/donors").with(csrf()).session(storekeeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brandId\":%d}".formatted(brand)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Статус, рестайлинг, описание для клиента и ссылка на видео — заводятся, правятся и попадают в журнал")
    void formFieldsAreKeptAndJournalled() throws Exception {
        Map<String, Long> statuses = statusIds();
        long donor = createDonor("""
                {"brandId":%d,"year":2012,"restyling":"1 рест.","clientDescription":"Целая, без ДТП",
                 "videoUrl":"https://youtu.be/abc123","statusId":""" + statuses.get("Куплена") + "}")
                .path("id").asLong();

        JsonNode card = card(donor).path("card");
        assertThat(card.path("statusName").asText()).isEqualTo("Куплена");
        assertThat(card.path("restyling").asText()).isEqualTo("1 рест.");
        assertThat(card.path("clientDescription").asText()).isEqualTo("Целая, без ДТП");
        assertThat(card.path("videoUrl").asText()).isEqualTo("https://youtu.be/abc123");

        ObjectNode form = (ObjectNode) card(donor).path("form").deepCopy();
        form.put("statusId", statuses.get("В разборе")).put("restyling", " ");
        JsonNode saved = json(send(put("/api/intake/donors/" + donor), form.toString(), status().isOk()));
        assertThat(saved.path("card").path("statusName").asText()).isEqualTo("В разборе");
        assertThat(saved.path("form").path("restyling").isNull()).as("пробелы — «не заполнено»").isTrue();

        assertThat(journal()).contains("Машина: Статус Куплена → В разборе",
                "Машина: Рестайлинг 1 рест. → null");

        MvcResult badLink = send(put("/api/intake/donors/" + donor),
                form.put("videoUrl", "смотрите видео в группе").toString(), status().isBadRequest());
        assertThat(text(badLink)).contains("Ссылка на видео");
        send(put("/api/intake/donors/" + donor),
                form.put("videoUrl", (String) null).put("statusId", 987654321L).toString(),
                status().isBadRequest());
    }

    @Test
    @DisplayName("Список статусов ведётся в настройках: повтор и удаление выбранного — 409 словами")
    void statusListIsKeptByTheOwner() throws Exception {
        String name = "В Японии " + System.nanoTime();
        JsonNode list = json(send(post("/api/intake/donor-statuses"),
                "{\"name\":\"  " + name + " \"}", status().isOk()));
        assertThat(list.toString()).contains("\"" + name + "\"");
        assertThat(statusIds()).as("новый статус не доехал до формы машины").containsKey(name);

        MvcResult twice = send(post("/api/intake/donor-statuses"),
                "{\"name\":\"" + name.toLowerCase() + "\"}", status().isConflict());
        assertThat(text(twice)).contains("уже есть в списке");
        send(post("/api/intake/donor-statuses"), "{\"name\":\"   \"}", status().isBadRequest());

        long id = statusIds().get(name);
        String renamed = name + " (порт)";
        send(put("/api/intake/donor-statuses/" + id), "{\"name\":\"" + renamed + "\"}", status().isOk());
        createDonor("{\"brandId\":%d,\"statusId\":" + id + "}");

        MvcResult used = send(delete("/api/intake/donor-statuses/" + id), null, status().isConflict());
        assertThat(text(used)).contains("«" + renamed + "»", "выбран у машин: 1");

        String spareName = "Лишний " + System.nanoTime();
        send(post("/api/intake/donor-statuses"), "{\"name\":\"" + spareName + "\"}", status().isOk());
        long spare = statusIds().get(spareName);
        send(delete("/api/intake/donor-statuses/" + spare), null, status().isOk());
        assertThat(statusIds()).doesNotContainValue(spare);

        assertThat(journal("Статус машины")).contains(
                "Статус машины: Название " + name + " → " + renamed);
    }

    @Test
    @DisplayName("Шестнадцатеричный код машины, заведённой у нас, не уходит наружу ни в одном ответе")
    void internalCodeIsNotShownAnywhere() throws Exception {
        JsonNode created = createDonor("{\"brandId\":%d,\"year\":2009,\"note\":\"Синий маркер\"}");
        long donor = created.path("id").asLong();
        long number = created.path("number").asLong();
        String hidden = inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM donor WHERE id = ?", String.class, donor));
        assertThat(hidden).as("у машины нет внутреннего кода — проверять нечего").matches("[0-9A-F]{12}");

        mvc.perform(post("/api/intake/donors/" + donor + "/dismantling").with(csrf()).session(owner))
                .andExpect(status().isOk());
        mvc.perform(post("/api/intake/receipts").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"number-%d","warehouseId":%d,"donorId":%d,
                                 "items":[{"rawName":"Фара","quantity":1,"price":5000}]}"""
                                .formatted(System.nanoTime(), warehouse, donor)))
                .andExpect(status().isCreated());
        long part = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE donor_id = ? ORDER BY id LIMIT 1", Long.class, donor));
        // Затрата по машине — ради журнала: строка затраты называет машину.
        mvc.perform(post("/api/intake/donors/" + donor + "/costs").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PURCHASE\",\"amount\":100000}"))
                .andExpect(status().is2xxSuccessful());

        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("POST /api/intake/donors", created.toString());
        for (String path : List.of(
                "/api/intake/donors",
                "/api/intake/donors/" + donor,
                "/api/intake/reference",
                "/api/parts/catalog?filter=donor:" + number,
                "/api/parts/" + part + "/donor",
                "/api/organization/audit",
                "/api/reports/donors")) {
            answers.put("GET " + path, new String(mvc.perform(get(path).session(owner))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                    StandardCharsets.UTF_8));
        }
        answers.forEach((where, body) -> assertThat(body)
                .as("%s отдаёт внутренний код машины", where).doesNotContain(hidden));

        assertThat(card(donor).path("card").path("code").asText()).isEqualTo(String.valueOf(number));
        JsonNode catalog = new ObjectMapper().readTree(answers.get(
                "GET /api/parts/catalog?filter=donor:" + number));
        assertThat(catalog.path("total").asInt())
                .as("«Номер донора» склада — номер машины, и отбор по нему находит её деталь")
                .isEqualTo(1);
        assertThat(catalog.path("rows").path(0).path("donorCode").asText())
                .isEqualTo(String.valueOf(number));
    }

    // ---------------------------------------------------------------- фикстуры

    private long maxNumber() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT coalesce(max(number), 0) FROM donor", Long.class));
    }

    private long nextNumber() throws Exception {
        return json(mvc.perform(get("/api/intake/donors/next-number").session(owner))
                .andExpect(status().isOk()).andReturn()).path("number").asLong();
    }

    private Map<String, Long> statusIds() throws Exception {
        Map<String, Long> found = new LinkedHashMap<>();
        json(mvc.perform(get("/api/intake/donor-vocabulary").session(owner))
                .andExpect(status().isOk()).andReturn()).path("statuses")
                .forEach(option -> found.put(option.path("title").asText(),
                        Long.parseLong(option.path("code").asText())));
        return found;
    }

    private List<String> journal() throws Exception {
        return journal("Машина");
    }

    private List<String> journal(String kind) throws Exception {
        List<String> changes = new ArrayList<>();
        for (JsonNode entry : json(mvc.perform(get("/api/organization/audit").param("kind", kind)
                .session(owner)).andExpect(status().isOk()).andReturn()).path("items")) {
            for (JsonNode change : entry.path("changes")) {
                changes.add(entry.path("kind").asText() + ": " + change.path("label").asText()
                        + " " + change.path("before").asText() + " → " + change.path("after").asText());
            }
        }
        return changes;
    }

    private JsonNode createDonor(String body) throws Exception {
        return json(send(post("/api/intake/donors"), body.formatted(brand), status().isCreated()));
    }

    private JsonNode card(long donor) throws Exception {
        return json(mvc.perform(get("/api/intake/donors/" + donor).session(owner))
                .andExpect(status().isOk()).andReturn());
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String body, ResultMatcher expected)
            throws Exception {
        request.with(csrf()).session(owner);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andExpect(expected).andReturn();
    }

    private static String text(MvcResult result) throws Exception {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
    }

    private long donorCount() {
        return inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM donor", Long.class));
    }

    private MockHttpSession login(String login, String role) throws Exception {
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    SELECT ?, 'Кладовщик номеров', ?, ?
                     WHERE NOT EXISTS (SELECT 1 FROM tenant_member WHERE login = ?)""",
                    login, passwordEncoder.encode("пароль-подлиннее"), role, login);
            jdbc.update("UPDATE tenant_member SET role = ? WHERE login = ?", role, login);
            return null;
        });
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"donornumber","login":"%s","password":"пароль-подлиннее"}"""
                                .formatted(login)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"donornumber","login":"nomer-vladelets","password":"пароль-подлиннее"}"""))
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
