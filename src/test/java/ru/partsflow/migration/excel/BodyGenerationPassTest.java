package ru.partsflow.migration.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.migration.excel.DromTransferFile.Column;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Поколение своей машины по коду кузова, когда блок «Подходит» поколения
 * не называет (задача 0295). Всё через HTTP, как экран: загрузка файла,
 * кнопка «Разложить «Подходит»» и витрина склада; справочник машин настоящий.
 *
 * <p>Пары «код → поколение» дают две карточки без марки в наименовании (своей
 * машины у них нет, они только «рассказывают» про кузова): у Prado 3-е
 * поколение — GRJ120, KDJ120 и GRN215; 4-е — GRJ150, LJ150 и тот же GRN215;
 * у 4-го есть ещё рестайлинг (GRJ151). Строки — обезличенные по образцу
 * файла клиента Strikexz («Toyota Land Cruiser Prado — GRJ120, GRJ120W…»).
 */
@SpringBootTest
@AutoConfigureMockMvc
class BodyGenerationPassTest extends PostgresTestBase {

    private static final String TENANT = "t_000965";
    private static final int REGISTRY_ID = 965;
    private static final String COMPANY = "bodygen";

    private static final String PAIRS_A = "Toyota Land Cruiser Prado — 3 поколение (2002–2009): "
            + "GRJ120, KDJ120, GRN215; 4 поколение (2009–2013): GRJ150, LJ150, GRN215";
    private static final String PAIRS_B =
            "Toyota Land Cruiser Prado — 4 поколение, рестайлинг (2013⁠–⁠2017): GRJ151";

    /** 120-я деталь: кузова 120-го ряда и один 150-й — сужение до ряда оставляет 120-е. */
    private static final String HOOD = "Капот Toyota Land Cruiser Prado 120";
    private static final String HOOD_FITS = "Toyota Land Cruiser Prado — GRJ120, GRJ120W, LJ150\n"
            + "Для двигателей — 1GRFE, 1KDFTV";
    private static final String FRAME = "Рама Toyota Land Cruiser Prado";
    private static final String WING = "Крыло Toyota Land Cruiser Prado";
    private static final String BUMPER = "Бампер Toyota Land Cruiser Prado 150";
    private static final String DOOR = "Дверь Toyota Land Cruiser Prado 120";
    private static final String MIRROR = "Зеркало Toyota Land Cruiser Prado";
    private static final String SHORT = "Капот Toyota Land Cruiser Prado 12";
    private static final String GLASS = "Стекло Toyota Land Cruiser Prado";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();
    private long warehouseId;
    private int ad;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", REGISTRY_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка с Дрома: поколение по кузову', ?)""",
                REGISTRY_ID, TENANT, COMPANY);
        warehouseId = inTenant(() -> {
            jdbc.update("DELETE FROM import_run");
            jdbc.update("DELETE FROM part_change");
            jdbc.update("DELETE FROM part_photo_import");
            jdbc.update("DELETE FROM part_oem");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM audit_log WHERE table_name IN ('part', 'part_drom_card')");
            jdbc.update("DELETE FROM part");
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'owner'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Владелец', 'OWNER', 'owner', ?)""", passwordEncoder.encode("пароль"));
            }
            List<Long> existing = jdbc.queryForList("SELECT id FROM warehouse", Long.class);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Барнаул') RETURNING id", Long.class);
            return jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Исакова') RETURNING id",
                    Long.class, branch);
        });
        ad = 430_000_000;
    }

    @Test
    @DisplayName("Загрузка: 120-я деталь с кузовами без поколения получает 3 поколение с годами клиента "
            + "и пометкой, грубая строка снята, витрина показывает поколение и коды, по которым оно взято")
    void loadSetsTheGenerationByBody() throws Exception {
        JsonNode report = load(file(cards())).path("applicability");

        assertThat(report.path("byBody").asInt()).as("капот, стекло и бампер: %s", report).isEqualTo(3);
        long hood = partOf(HOOD);
        assertThat(rowsOf(hood)).as("строка поколения по коду кузова").containsExactly(
                "Toyota/Land Cruiser Prado/2002/2002-2009/"
                        + "Кузова: GRJ120, GRJ120W; Поколение по коду кузова: GRJ120/нет");

        JsonNode row = catalogRow(HOOD);
        assertThat(row.path("generation").isNull()).as("поколение не показано: %s", row).isFalse();
        assertThat(row.path("yearFrom").asInt()).isEqualTo(2002);
        assertThat(row.path("yearTo").asInt()).isEqualTo(2009);
        assertThat(row.path("vehicleSource").asText()).isEqualTo("PARSED");
        assertThat(row.path("vehicleGenerationBodies").asText()).isEqualTo("GRJ120");
        assertThat(row.path("generation").asText())
                .as("одна строка с меткой — тоже поколение Дрома, как у 0297 («у всех»)")
                .isEqualTo("3 поколение");
        // Чужая 120-я деталь без пометки — поле пусто: подсказка не врёт про заявленное клиентом.
        assertThat(catalogRow(FRAME).path("vehicleGenerationBodies").isNull()).isTrue();
    }

    @Test
    @DisplayName("Сомнение — пусто: два поколения, кузов в двух поколениях, другой ряд, "
            + "«Prado 12», неизвестный код — поколения нет, машина в применимости, причина в отчёте")
    void doubtLeavesItEmptyAndNamesTheReason() throws Exception {
        JsonNode report = load(file(cards())).path("applicability");

        for (String title : List.of(FRAME, WING, DOOR, MIRROR, SHORT)) {
            assertThat(rowsOf(partOf(title))).as("у «%s» поколение поставлено по догадке", title)
                    .singleElement().asString().startsWith("Toyota/Land Cruiser Prado/-/-/Кузова: ");
            assertThat(catalogRow(title).path("generation").isNull()).as(title).isTrue();
            assertThat(catalogRow(title).path("model").asText()).isEqualTo("Land Cruiser Prado");
        }
        assertThat(bodyMiss(report, "кузова встречаются в разных поколениях").path("names"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(FRAME + ": поколения 3 и 4",
                        WING + ": кузов GRN215 есть в поколениях 3 и 4");
        assertThat(bodyMiss(report, "кузова блока другого ряда, чем номер в наименовании")
                .path("names")).extracting(JsonNode::asText)
                .as("«Prado 12» — не сокращение 120-го (решение владельца, 0294)")
                .containsExactlyInAnyOrder(DOOR, SHORT);
        assertThat(bodyMiss(report, "кузова не встречаются в карточках с поколением")
                .path("names").get(0).asText()).isEqualTo(MIRROR + ": XYZ999");
    }

    @Test
    @DisplayName("Кнопка по загруженному складу: то же самое; подтверждённое не тронуто; повтор — "
            + "ничего нового; отметка «позиция изменилась» и updated_at прежние")
    void buttonDoesTheSameAndRepeatAddsNothing() throws Exception {
        load(file(cards()));
        inTenant(() -> jdbc.update("DELETE FROM part_applicability"));
        inTenant(() -> jdbc.update("DELETE FROM part_change"));
        long glass = partOf(GLASS);
        long prado = jdbc.queryForObject("""
                SELECT m.id FROM catalog.model m JOIN catalog.brand b ON b.id = m.brand_id
                 WHERE b.name = 'Toyota' AND m.name = 'Land Cruiser Prado'""", Long.class);
        long brand = jdbc.queryForObject("SELECT id FROM catalog.brand WHERE name = 'Toyota'", Long.class);
        inTenant(() -> jdbc.update("""
                INSERT INTO part_applicability (part_id, brand_id, model_id, is_verified)
                VALUES (?, ?, ?, true)""", glass, brand, prado));
        String updatedBefore = updatedAt();

        JsonNode first = press();

        assertThat(first.path("byBody").asInt()).as("отчёт: %s", first).isEqualTo(3);
        assertThat(rowsOf(partOf(HOOD))).containsExactly("Toyota/Land Cruiser Prado/2002/2002-2009/"
                + "Кузова: GRJ120, GRJ120W; Поколение по коду кузова: GRJ120/нет");
        assertThat(rowsOf(glass)).as("подтверждённая строка снята или поправлена")
                .containsExactlyInAnyOrder("Toyota/Land Cruiser Prado/-/-/-/да",
                        "Toyota/Land Cruiser Prado/2002/2002-2009/"
                                + "Кузова: GRJ120; Поколение по коду кузова: GRJ120/нет");
        List<String> once = allRows();

        JsonNode second = press();
        assertThat(second.path("byBody").asInt() + second.path("added").asInt()
                + second.path("refined").asInt() + second.path("removed").asInt())
                .as("повтор что-то сделал: %s", second).isZero();
        assertThat(allRows()).isEqualTo(once);
        assertThat(inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_change", Integer.class)))
                .as("проход поставил отметку «позиция изменилась»").isZero();
        assertThat(updatedAt()).isEqualTo(updatedBefore);
    }

    @Test
    @DisplayName("Рестайлинги по коду кузова — поколение Дрома целиком (ответ владельца 14.10.2026, п. 2): "
            + "строка на рестайлинг с меткой, витрина показывает «4 поколение» 2009–2017 и код")
    void restylingsByBodyShowTheWholeDromGeneration() throws Exception {
        JsonNode report = load(file(cards())).path("applicability");

        long bumper = partOf(BUMPER);
        assertThat(rowsOf(bumper)).as("строки рестайлингов по коду кузова").containsExactlyInAnyOrder(
                "Toyota/Land Cruiser Prado/2009/2009-2013/"
                        + "Кузова: GRJ150; Поколение по коду кузова: GRJ150/нет",
                "Toyota/Land Cruiser Prado/2013/2013-2017/"
                        + "Кузова: GRJ150; Поколение по коду кузова: GRJ150/нет");
        assertThat(inTenant(() -> jdbc.queryForList(
                "SELECT DISTINCT generation_label FROM part_applicability WHERE part_id = ?",
                String.class, bumper))).as("метка Дрома у каждой строки").containsExactly("4 поколение");

        JsonNode row = catalogRow(BUMPER);
        assertThat(row.path("generation").asText()).as("витрина: %s", row).isEqualTo("4 поколение");
        assertThat(row.path("yearFrom").asInt()).isEqualTo(2009);
        assertThat(row.path("yearTo").asInt()).isEqualTo(2017);
        assertThat(row.path("vehicleGenerationMerged").asBoolean()).isTrue();
        assertThat(row.path("vehicleGenerationBodies").asText()).isEqualTo("GRJ150");
        for (JsonNode miss : report.path("bodyMisses")) {
            assertThat(miss.path("names").toString()).as("бампер назван пропущенным: %s", miss)
                    .doesNotContain(BUMPER);
        }
    }

    // --- помощники ---

    private List<Map<String, String>> cards() {
        return List.of(card("Фара левая", PAIRS_A), card("Фара правая", PAIRS_B),
                card(HOOD, HOOD_FITS),
                card(FRAME, "Toyota Land Cruiser Prado — KDJ120, GRJ150"),
                card(WING, "Toyota Land Cruiser Prado — GRN215"),
                card(BUMPER, "Toyota Land Cruiser Prado — GRJ150"),
                card(DOOR, "Toyota Land Cruiser Prado — GRJ150"),
                card(MIRROR, "Toyota Land Cruiser Prado — XYZ999"),
                card(SHORT, "Toyota Land Cruiser Prado — GRJ120"),
                card(GLASS, "Toyota Land Cruiser Prado — GRJ120"));
    }

    private Map<String, String> card(String name, String fits) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Номер объявления Дрома", String.valueOf(ad++));
        row.put("Наименование", name);
        row.put("Цена", "16000");
        row.put("Наличие", ExcelWarehouseImporter.IN_STOCK);
        row.put("Применимость", fits);
        return row;
    }

    private static JsonNode bodyMiss(JsonNode report, String reason) {
        for (JsonNode miss : report.path("bodyMisses")) {
            if (miss.path("reason").asText().equals(reason)) {
                return miss;
            }
        }
        throw new AssertionError("в отчёте нет причины «" + reason + "»: " + report);
    }

    private JsonNode catalogRow(String title) throws Exception {
        String body = mvc.perform(get("/api/parts/catalog?missing=true&size=100").session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (JsonNode row : json.readTree(body).path("rows")) {
            if (row.path("title").asText().equals(title)) {
                return row;
            }
        }
        throw new AssertionError("на витрине нет «" + title + "»: " + body);
    }

    private JsonNode press() throws Exception {
        String body = mvc.perform(post("/api/parts/catalog/applicability/from-drom")
                        .with(csrf()).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private long partOf(String title) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE title = ?", Long.class, title));
    }

    /** Строки позиции словами: марка/модель/год начала поколения/годы/note/подтверждено. */
    private List<String> rowsOf(long part) {
        return inTenant(() -> jdbc.queryForList("""
                SELECT b.name || '/' || coalesce(m.name, '-') || '/'
                       || coalesce(g.year_from::text, '-') || '/'
                       || coalesce(a.year_from || '-' || coalesce(a.year_to::text, 'н.в.'), '-') || '/'
                       || coalesce(a.note, '-') || '/'
                       || CASE WHEN a.is_verified THEN 'да' ELSE 'нет' END
                  FROM part_applicability a
                  JOIN catalog.brand b ON b.id = a.brand_id
                  LEFT JOIN catalog.model m ON m.id = a.model_id
                  LEFT JOIN catalog.generation g ON g.id = a.generation_id
                 WHERE a.part_id = ?""", String.class, part));
    }

    private List<String> allRows() {
        return inTenant(() -> jdbc.queryForList("""
                SELECT id || ':' || part_id || ':' || coalesce(generation_id::text, '') || ':'
                       || coalesce(note, '')
                  FROM part_applicability ORDER BY id""", String.class));
    }

    private String updatedAt() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT string_agg(id || '=' || updated_at::text, ',' ORDER BY id) FROM part",
                String.class));
    }

    private static byte[] file(List<Map<String, String>> rows) throws Exception {
        List<String> header = DromTransferFile.COLUMNS.stream().map(Column::title).toList();
        try (XSSFWorkbook book = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Объявления");
            Row head = sheet.createRow(0);
            for (int c = 0; c < header.size(); c++) {
                head.createCell(c).setCellValue(header.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < header.size(); c++) {
                    String value = rows.get(r).get(header.get(c));
                    if (value != null && !value.isEmpty()) {
                        row.createCell(c).setCellValue(value);
                    }
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    private JsonNode load(byte[] file) throws Exception {
        MockHttpSession session = login();
        String previewBody = mvc.perform(multipart("/api/import/excel/preview").file(xlsx(file))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode detected = json.readTree(previewBody).path("detected");

        MockMultipartHttpServletRequestBuilder request = multipart("/api/import/excel");
        request.file(xlsx(file));
        Map<String, String> fields = new HashMap<>();
        detected.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue().asText()));
        fields.forEach(request::param);
        String body = mvc.perform(request.with(csrf()).session(session)
                        .param("warehouseId", String.valueOf(warehouseId))
                        .param("requestId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode report = json.readTree(body);
        assertThat(report.path("skipped")).as("загрузка пропустила строки: %s", report).isEmpty();
        return report;
    }

    private static MockMultipartFile xlsx(byte[] file) {
        return new MockMultipartFile("file", "drom.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file);
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
