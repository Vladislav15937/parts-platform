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
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Все поля позиции правятся в «Изменить» — задача 0302.
 *
 * <p>Жалоба живого клиента: «создал позицию и не могу вписать OEM: стоит замок».
 * На сервере то же самое выглядело хуже замка: {@code UpdateRequest} этих
 * ключей не знал, Jackson молча их отбрасывал, и клиент получал 200 при том,
 * что ничего не сохранилось. Поэтому каждое поле проверяется перечитыванием —
 * формой правки и витриной, — а не кодом ответа.
 *
 * <p>Через HTTP: ответ контроллера — record, роль стоит аннотацией, а ответ
 * на занятое значение собирает обработчик исключений.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartAllFieldsEditTest extends PostgresTestBase {

    private static final String TENANT = "t_000994";

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

    private Long donor;
    private Long partId;
    private Long neighbour;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 994");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (994, ?, 'Разборка', 'allfields')""", TENANT);

        inTenant(() -> {
            member("vladelec0302", "Владелец", "OWNER");
            member("menedzher0302", "Менеджер", "MANAGER");
            Long toyota = jdbc.queryForObject(
                    "SELECT id FROM catalog.brand WHERE slug = 'toyota'", Long.class);
            donor = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, year, body_code, engine_code, note)
                    VALUES (?, 2006, 'ACV40', '2AZ-FE', 'Камри с разбора') RETURNING id""",
                    Long.class, toyota);
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, donor_id, is_published)
                    VALUES (1, 'Фара Toyota Camry 2006 перед. лев. (б/у)', 4500, ?, true)
                    RETURNING id""", Long.class, donor);
            neighbour = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, donor_id, is_published)
                    VALUES (1, 'Бампер Toyota Camry 2006 (б/у)', 9000, ?, true)
                    RETURNING id""", Long.class, donor);
            return null;
        });
    }

    @Test
    @DisplayName("Каждое поле, стоявшее с замком, записывается и перечитывается")
    void everyFormerlyLockedFieldIsSaved() throws Exception {
        MockHttpSession owner = login("vladelec0302");
        long freeNumber = inTenant(() -> jdbc.queryForObject(
                "SELECT max(number) + 1 FROM part", Long.class));

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":4500,"published":true,
                                 "number":%d,"code":"FARA-0302","title":"Фара левая ксенон",
                                 "sideLr":"RIGHT","sideFr":"REAR","authenticity":"Оригинал",
                                 "oem":"81170-33670","crosses":"81170 33671, 8117033672",
                                 "legacyCode":"ST-0302","dromAdNumber":"№ 125696381",
                                 "donorCode":"261","brand":"Lexus","model":"ES350",
                                 "generation":"XV40","body":"GSV40","engine":"2GR-FE",
                                 "year":"2008","equipment":"полный привод"}"""
                                .formatted(freeNumber)))
                .andExpect(status().isOk());

        JsonNode form = editable(owner);
        Map<String, String> expected = Map.ofEntries(
                Map.entry("number", String.valueOf(freeNumber)),
                Map.entry("code", "FARA-0302"),
                Map.entry("title", "Фара левая ксенон"),
                Map.entry("sideLr", "RIGHT"),
                Map.entry("sideFr", "REAR"),
                Map.entry("authenticity", "Оригинал"),
                Map.entry("oem", "81170-33670"),
                Map.entry("crosses", "81170 33671, 8117033672"),
                Map.entry("legacyCode", "ST-0302"),
                Map.entry("dromAdNumber", "125696381"),
                Map.entry("donorCode", "261"),
                Map.entry("brand", "Lexus"),
                Map.entry("model", "ES350"),
                Map.entry("generation", "XV40"),
                Map.entry("body", "GSV40"),
                Map.entry("engine", "2GR-FE"),
                Map.entry("year", "2008"),
                Map.entry("equipment", "полный привод"));
        expected.forEach((field, value) -> assertThat(form.path(field).asText(null))
                .as("поле «%s» не записалось: 200 при несохранённом значении", field)
                .isEqualTo(value));

        // Витрина показывает вписанное у позиции, а не донора.
        JsonNode row = catalogRow(owner, "FARA-0302");
        assertThat(row.path("brand").asText()).isEqualTo("Lexus");
        assertThat(row.path("model").asText()).isEqualTo("ES350");
        assertThat(row.path("generation").asText()).isEqualTo("XV40");
        assertThat(row.path("body").asText()).isEqualTo("GSV40");
        assertThat(row.path("engine").asText()).isEqualTo("2GR-FE");
        assertThat(row.path("year").asInt()).isEqualTo(2008);
        assertThat(row.path("donorCode").asText()).isEqualTo("261");
        assertThat(row.path("oem").asText()).isEqualTo("81170-33670");
    }

    @Test
    @DisplayName("Номер производителя — главный в part_oem с приведением, кросс-номера — заменами; пусто снимает")
    void oemIsStoredAsPrimaryAndCrosses() throws Exception {
        MockHttpSession owner = login("vladelec0302");

        save(owner, "\"oem\":\"81170-33670\",\"crosses\":\"81170 33671; 81170-33670\"");
        List<Map<String, Object>> rows = inTenant(() -> jdbc.queryForList(
                "SELECT raw_number, normalized, is_primary FROM part_oem"
                        + " WHERE part_id = ? ORDER BY id", partId));
        assertThat(rows).extracting(r -> r.get("normalized"), r -> r.get("is_primary"))
                .as("кросс, совпавший с главным, не дублируется; приведение — OemNumbers")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("8117033670", true),
                        org.assertj.core.groups.Tuple.tuple("8117033671", false));

        // Номер находится поиском по любому написанию.
        mvc.perform(get("/api/parts/by-oem/8117033670").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(
                        org.hamcrest.Matchers.hasItem(partId.intValue())));

        save(owner, "\"oem\":\"\",\"crosses\":\"\"");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_oem WHERE part_id = ?", Integer.class, partId)))
                .as("пустое значение снимает номер, а не пишет пустую строку")
                .isZero();
    }

    @Test
    @DisplayName("Форма без новых ключей (вкладка до выкладки) номер производителя не стирает")
    void absentKeysLeaveValuesAlone() throws Exception {
        MockHttpSession owner = login("vladelec0302");
        save(owner, "\"oem\":\"81170-33670\",\"brand\":\"Lexus\"");

        save(owner, "\"note\":\"старая вкладка\"");

        JsonNode form = editable(owner);
        assertThat(form.path("oem").asText()).isEqualTo("81170-33670");
        assertThat(form.path("brand").asText()).isEqualTo("Lexus");
    }

    @Test
    @DisplayName("Поля донора правят эту позицию, а не машину и не соседнюю деталь с неё")
    void donorFieldsChangeOnlyThisPart() throws Exception {
        MockHttpSession owner = login("vladelec0302");
        String neighbourCode = codeOf(neighbour);

        save(owner, "\"brand\":\"Lexus\",\"year\":\"2008\",\"body\":\"GSV40\"");

        JsonNode other = catalogRow(owner, neighbourCode);
        assertThat(other.path("brand").asText()).as("соседняя деталь той же машины")
                .isEqualTo("Toyota");
        assertThat(other.path("year").asInt()).isEqualTo(2006);
        assertThat(other.path("body").asText()).isEqualTo("ACV40");
        Map<String, Object> car = inTenant(() -> jdbc.queryForMap(
                "SELECT year, body_code FROM donor WHERE id = ?", donor));
        assertThat(car.get("body_code")).as("сама машина").isEqualTo("ACV40");
        assertThat(((Number) car.get("year")).intValue()).isEqualTo(2006);

        // Пустое своё возвращает значение донора.
        save(owner, "\"brand\":\"\"");
        assertThat(catalogRow(owner, codeOf(partId)).path("brand").asText()).isEqualTo("Toyota");
    }

    @Test
    @DisplayName("Занятые № позиции, номер товара и номер объявления называют позицию словами")
    void takenIdentifiersAreExplained() throws Exception {
        MockHttpSession owner = login("vladelec0302");
        String neighbourCode = codeOf(neighbour);
        Long neighbourNumber = inTenant(() -> jdbc.queryForObject(
                "SELECT number FROM part WHERE id = ?", Long.class, neighbour));
        inTenant(() -> jdbc.update(
                "UPDATE part SET drom_ad_number = '777' WHERE id = ?", neighbour));

        for (String body : List.of("\"number\":" + neighbourNumber,
                "\"code\":\"" + neighbourCode + "\"", "\"dromAdNumber\":\"777\"")) {
            mvc.perform(put("/api/parts/" + partId).with(csrf()).session(owner)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"price\":4500,\"published\":true," + body + "}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString(neighbourCode)));
        }
    }

    @Test
    @DisplayName("Номер позиции выше выданных двигает нумерацию: приёмка его второй раз не выдаст")
    void numberAheadMovesTheSequence() throws Exception {
        MockHttpSession owner = login("vladelec0302");
        long ahead = inTenant(() -> jdbc.queryForObject(
                "SELECT max(number) + 5 FROM part", Long.class));

        save(owner, "\"number\":" + ahead);

        Long next = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price) VALUES (1, 'Следующая', 100)
                RETURNING number""", Long.class));
        assertThat(next).isGreaterThan(ahead);
    }

    @Test
    @DisplayName("Состояние меняет и менеджер — замка нет и у него")
    void managerChangesCondition() throws Exception {
        MockHttpSession manager = login("menedzher0302");

        save(manager, "\"condition\":\"NEW\"");

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT condition FROM part WHERE id = ?", String.class, partId)))
                .isEqualTo("NEW");
    }

    @Test
    @DisplayName("Правка пишется в историю позиции словами поля")
    void editIsInTheHistory() throws Exception {
        MockHttpSession owner = login("vladelec0302");

        save(owner, "\"oem\":\"81170-33670\",\"brand\":\"Lexus\",\"title\":\"Фара левая\"");

        String history = mvc.perform(get("/api/parts/" + partId + "/history").session(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(history).contains("Номер производителя", "81170-33670",
                "Марка", "Lexus", "Наименование", "Фара левая");
    }

    /**
     * Задача 0309: «АВ-123С» с русскими буквами, набранное в карточке,
     * хранится латиницей — иначе на Дроме оно не совпадёт ни с чьим поиском.
     * Русские буквы — escape-последовательностями: в исходнике они неотличимы.
     */
    @Test
    @DisplayName("Номер и кросс-номера карточки: двойники латиницей, неразрывный пробел срезан, табуляция делит")
    void cardStoresLatinNumbers() throws Exception {
        MockHttpSession owner = login("vladelec0302");
        String nbsp = "\u00A0";
        save(owner, "\"oem\":\"" + nbsp + "\u0410\u0412-123\u0421" + nbsp + "\","
                + "\"crosses\":\"\u0430\u0430111\\t\u0412\u0412222" + nbsp + ", dd-444\\t4520\u0411\"");

        List<Map<String, Object>> rows = inTenant(() -> jdbc.queryForList(
                "SELECT raw_number, normalized, is_primary FROM part_oem"
                        + " WHERE part_id = ? ORDER BY id", partId));
        assertThat(rows).extracting(r -> r.get("raw_number"), r -> r.get("normalized"))
                .as("в raw_number обязана лечь латиница, табуляция — разделить номера")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("AB-123C", "AB123C"),
                        org.assertj.core.groups.Tuple.tuple("aa111", "AA111"),
                        org.assertj.core.groups.Tuple.tuple("BB222", "BB222"),
                        org.assertj.core.groups.Tuple.tuple("dd-444", "DD444"),
                        // «Б» двойника не имеет: слово не трогается, а не портится.
                        org.assertj.core.groups.Tuple.tuple("4520\u0411", "4520"));

        JsonNode form = editable(owner);
        assertThat(form.path("oem").asText()).isEqualTo("AB-123C");
        assertThat(form.path("crosses").asText()).isEqualTo("aa111, BB222, dd-444, 4520\u0411");
    }

    @Test
    @DisplayName("Повторное сохранение того же номера ничего не меняет и не пишет правку в историю")
    void sameNumberAgainIsNoEdit() throws Exception {
        MockHttpSession owner = login("vladelec0302");
        save(owner, "\"oem\":\"\u0410\u0412-123\u0421\",\"crosses\":\"dd-444\"");
        // Кросс-номер с русскими буквами, лежащий с прежних времён: задача
        // уже сохранённое не пересчитывает (п. 6), и правка одного номера
        // производителя не должна молча переписывать кросс-номера.
        inTenant(() -> jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)"
                + " VALUES (?, '\u0412\u04121', 'BB1', false)", partId));
        int before = historyRows();

        save(owner, "\"oem\":\"AB-123C\"");
        save(owner, "\"oem\":\"\u0410\u0412-123\u0421\"");

        assertThat(historyRows())
                .as("номер не менялся, а история записала правку")
                .isEqualTo(before);
        assertThat(inTenant(() -> jdbc.queryForList("SELECT raw_number FROM part_oem"
                + " WHERE part_id = ? AND NOT is_primary ORDER BY id", String.class, partId)))
                .as("нетронутые кросс-номера переписаны")
                .containsExactly("dd-444", "\u0412\u04121");
    }

    @Test
    @DisplayName("Марка и модель вне справочника сохраняются как набраны: «Лексус» — не отказ, «тойота» — не Toyota")
    void brandOutsideTheCatalogIsKeptAsTyped() throws Exception {
        // Задача 0308, решение владельца 16.10.2026: подсказка из справочника,
        // «но также должна быть возможность свободного текста»; русские
        // названия со справочником не сопоставлять. Экран шлёт набранное
        // как есть — сервер обязан так же его и записать, без замены
        // на «похожее» и без отказа.
        MockHttpSession owner = login("vladelec0302");
        String code = codeOf(partId);

        save(owner, "\"brand\":\"Лексус\",\"model\":\"Эр-икс\"");
        assertThat(editable(owner).path("brand").asText()).isEqualTo("Лексус");
        assertThat(editable(owner).path("model").asText()).isEqualTo("Эр-икс");
        assertThat(catalogRow(owner, code).path("brand").asText())
                .as("витрина показывает набранное, а не марку донора и не «похожую»")
                .isEqualTo("Лексус");

        save(owner, "\"brand\":\"тойота\"");
        assertThat(editable(owner).path("brand").asText())
                .as("русское написание сопоставилось со справочником")
                .isEqualTo("тойота");
        assertThat(catalogRow(owner, code).path("brand").asText()).isEqualTo("тойота");
    }

    // ------------------------------------------------------------------ помощники

    /** Правки номера производителя и кросс-номеров в истории позиции. */
    private int historyRows() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE table_name = 'part' AND record_id = ?"
                        + " AND (jsonb_exists(new_value, 'oem') OR jsonb_exists(new_value, 'crosses'))",
                Integer.class, partId));
    }

    private void save(MockHttpSession session, String fields) throws Exception {
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":4500,\"published\":true," + fields + "}"))
                .andExpect(status().isOk());
    }

    private JsonNode editable(MockHttpSession session) throws Exception {
        String body = mvc.perform(get("/api/parts/" + partId + "/editable").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private JsonNode catalogRow(MockHttpSession session, String code) throws Exception {
        String body = mvc.perform(get("/api/parts/catalog").session(session)
                        .param("missing", "true").param("size", "200"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (JsonNode row : json.readTree(body).path("rows")) {
            if (code.equals(row.path("code").asText())) {
                return row;
            }
        }
        throw new AssertionError("на витрине нет позиции " + code);
    }

    private String codeOf(Long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, part));
    }

    private Long member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            jdbc.update("UPDATE tenant_member SET display_name = ?, role = ? WHERE id = ?",
                    displayName, role, found.get(0));
            return found.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, displayName, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"allfields","login":"%s","password":"пароль"}"""
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
