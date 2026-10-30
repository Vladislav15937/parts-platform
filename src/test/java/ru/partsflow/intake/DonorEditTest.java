package ru.partsflow.intake;

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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Машина целиком одной формой, правка после заведения и «N запчастей —
 * Посмотреть» (задача 0364, часть без схемы).
 *
 * <p>Через HTTP: ответы — record'ы, а обычный класс уехал бы пятисоткой,
 * которой вызов сервиса не видит; и {@code equipmentCode} прежнего
 * {@code POST} отбрасывался Jackson'ом молча — увидеть это можно только
 * запросом.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class DonorEditTest extends PostgresTestBase {

    private static final String TENANT = "t_000364";

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
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 364");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (364, ?, 'Правка машины', 'donoredit')""", TENANT);
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    SELECT 'mashina-vladelets', 'Владелец машин', ?, 'OWNER'
                     WHERE NOT EXISTS (SELECT 1 FROM tenant_member WHERE login = 'mashina-vladelets')""",
                    passwordEncoder.encode("пароль-подлиннее"));
            jdbc.update("UPDATE tenant_member SET display_name = 'Владелец машин', role = 'OWNER' "
                    + "WHERE login = 'mashina-vladelets'");
            // LIMIT 1: справочник марок общий, одноимённых записей бывает несколько.
            brand = jdbc.queryForObject(
                    "SELECT id FROM catalog.brand WHERE name = 'Toyota' ORDER BY id LIMIT 1",
                    Long.class);
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
        owner = login();
    }

    @Test
    @DisplayName("Машина заводится со всеми полями формы, и позиция наследует «Правый руль, АКПП, Передний, комплектация»")
    void fullFormIsKeptAndInherited() throws Exception {
        long donor = createDonor("""
                {"brandId":%d,"year":2003,"steering":"RIGHT","transmissionType":"AT",
                 "driveType":"FWD","color":"Черный","equipmentCode":"500000","mileageKm":158000}""");

        JsonNode card = card(donor).path("card");
        assertThat(card.path("steering").asText()).isEqualTo("Правый руль");
        assertThat(card.path("transmission").asText()).isEqualTo("АКПП");
        assertThat(card.path("driveType").asText()).isEqualTo("Передний");
        assertThat(card.path("color").asText()).isEqualTo("Черный");
        assertThat(card.path("equipmentCode").asText())
                .as("комплектация, присланная при заведении, отброшена")
                .isEqualTo("500000");
        assertThat(card.path("mileageKm").asInt()).isEqualTo(158000);

        startDismantling(donor);
        receive(donor, "Фара");
        assertThat(equipmentOfParts(donor))
                .as("«Комплектация» позиции — руль, КПП, привод и комплектация донора")
                .containsExactly("Правый руль, АКПП, Передний, 500000");
    }

    @Test
    @DisplayName("Ошибочный год исправлен «карандашом»; в журнале действий «Машина: Год выпуска 2003 → 2004»")
    void yearIsCorrectedAndJournalled() throws Exception {
        long donor = createDonor("""
                {"brandId":%d,"year":2003,"vin":"JTDBR32E720123456","transmissionModel":"U340E"}""");
        // Чего на форме нет, то правка не трогает: место и код цвета переехавшей машины.
        inTenant(() -> jdbc.update(
                "UPDATE donor SET location = 'ряд 2', color_code = '202' WHERE id = ?", donor));

        JsonNode form = card(donor).path("form");
        String sent = new ObjectMapper().writeValueAsString(
                ((com.fasterxml.jackson.databind.node.ObjectNode) form.deepCopy())
                        .put("year", 2004).put("mileageKm", 91000).put("steering", "LEFT"));
        var result = mvc.perform(put("/api/intake/donors/" + donor).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content(sent))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode updated = json(result.getResponse().getContentAsByteArray());
        assertThat(updated.path("card").path("year").asInt()).isEqualTo(2004);
        assertThat(updated.path("card").path("steering").asText()).isEqualTo("Левый руль");
        assertThat(updated.path("form").path("mileageKm").asInt()).isEqualTo(91000);
        assertThat(updated.path("form").path("transmissionModel").asText())
                .as("нетронутое поле формы уехало тем, что лежало").isEqualTo("U340E");

        inTenant(() -> {
            assertThat(jdbc.queryForObject("SELECT location FROM donor WHERE id = ?",
                    String.class, donor)).as("место — не поле формы").isEqualTo("ряд 2");
            assertThat(jdbc.queryForObject("SELECT color_code FROM donor WHERE id = ?",
                    String.class, donor)).as("код цвета — не поле формы").isEqualTo("202");
            return null;
        });

        List<String> changes = new ArrayList<>();
        for (JsonNode entry : json(mvc.perform(get("/api/organization/audit?kind=Машина")
                        .session(owner)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray()).path("items")) {
            for (JsonNode change : entry.path("changes")) {
                changes.add(entry.path("kind").asText() + ": " + change.path("label").asText()
                        + " " + change.path("before").asText() + " → " + change.path("after").asText());
            }
        }
        assertThat(changes).contains(
                "Машина: Год выпуска 2003 → 2004",
                // Руль словом общего словаря, а не кодом RIGHT/LEFT.
                "Машина: Расположение руля null → Левый руль");
    }

    @Test
    @DisplayName("Пустое в форме очищает поле, а без марки машину не сохранить — 400 словами")
    void blankClearsAndBrandIsRequired() throws Exception {
        long donor = createDonor("""
                {"brandId":%d,"year":2003,"mileageKm":158000,"color":"Белый"}""");
        mvc.perform(put("/api/intake/donors/" + donor).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brandId\":%d,\"year\":2003,\"color\":\"  \"}".formatted(brand)))
                .andExpect(status().isOk());
        JsonNode form = card(donor).path("form");
        assertThat(form.path("mileageKm").isNull()).as("стёртый пробег").isTrue();
        assertThat(form.path("color").isNull()).as("пробелы — «не заполнено»").isTrue();

        var refused = mvc.perform(put("/api/intake/donors/" + donor).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2004}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(refused.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("без марки");
    }

    @Test
    @DisplayName("Незнакомая машина — отказ словами, без номера строки базы")
    void unknownDonorIsRefusedInWords() throws Exception {
        var result = mvc.perform(put("/api/intake/donors/987654").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brandId\":%d}".formatted(brand)))
                .andExpect(status().is4xxClientError())
                .andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("987654");
    }

    @Test
    @DisplayName("«N запчастей» на карточке машины совпадает с числом строк склада по её номеру")
    void partsCountMatchesTheCatalog() throws Exception {
        long donor = createDonor("{\"brandId\":%d,\"year\":2007}");
        startDismantling(donor);
        receive(donor, "Фара");
        receive(donor, "Дверь");
        receive(donor, "Капот");
        // Колесо, снятое с той же машины: у него своя вкладка, в список склада
        // оно не попадает — и в число плитки, ведущей туда, не входит.
        inTenant(() -> {
            Long wheel = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, product_line, qty_on_hand, donor_id)
                    VALUES (1, 'Шина с машины', 3000, 'WHEEL', 1, ?) RETURNING id""",
                    Long.class, donor);
            jdbc.update("INSERT INTO part_wheel (part_id, kind) VALUES (?, 'TYRE')", wheel);
            return null;
        });

        JsonNode card = card(donor).path("card");
        int count = card.path("partsCount").asInt();
        String code = card.path("code").asText();
        assertThat(count).isEqualTo(3);

        // Тем же отбором, каким ведёт «Посмотреть»: колонка «Номер донора»
        // и отсутствующие тоже — снятое и проданное с машины в счёт входит.
        JsonNode page = json(mvc.perform(get("/api/parts/catalog")
                        .param("filter", "donor:" + code).param("missing", "true")
                        .session(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(page.path("total").asInt())
                .as("число на плитке разошлось с числом строк, к которым она ведёт")
                .isEqualTo(count);
    }

    @Test
    @DisplayName("Руль, КПП и привод для формы — словами общего словаря")
    void vocabularyIsServedInWords() throws Exception {
        JsonNode words = json(mvc.perform(get("/api/intake/donor-vocabulary").session(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(words.path("steering").toString()).contains("\"RIGHT\"", "Правый руль");
        assertThat(words.path("transmission").toString()).contains("\"DCT\"", "Робот DSG");
        assertThat(words.path("drive").toString()).contains("\"AWD\"", "Полный");
    }

    // ---------------------------------------------------------------- фикстуры

    private long createDonor(String body) throws Exception {
        var result = mvc.perform(post("/api/intake/donors").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted(brand)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsByteArray()).path("id").asLong();
    }

    private void startDismantling(long donor) throws Exception {
        mvc.perform(post("/api/intake/donors/" + donor + "/dismantling").with(csrf()).session(owner))
                .andExpect(status().isOk());
    }

    private void receive(long donor, String name) throws Exception {
        mvc.perform(post("/api/intake/receipts").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"edit-%d","warehouseId":%d,"donorId":%d,
                                 "items":[{"rawName":"%s","quantity":1,"price":5000}]}"""
                                .formatted(System.nanoTime(), warehouse, donor, name)))
                .andExpect(status().isCreated());
    }

    private JsonNode card(long donor) throws Exception {
        var result = mvc.perform(get("/api/intake/donors/" + donor).session(owner))
                .andExpect(status().isOk())
                .andReturn();
        return json(result.getResponse().getContentAsByteArray());
    }

    private List<String> equipmentOfParts(long donor) throws Exception {
        String code = card(donor).path("card").path("code").asText();
        JsonNode page = json(mvc.perform(get("/api/parts/catalog")
                        .param("filter", "donor:" + code).session(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        List<String> found = new ArrayList<>();
        page.path("rows").forEach(row -> found.add(row.path("equipment").asText()));
        return found;
    }

    private static JsonNode json(byte[] body) throws Exception {
        return new ObjectMapper().readTree(body);
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"donoredit","login":"mashina-vladelets","password":"пароль-подлиннее"}"""))
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
