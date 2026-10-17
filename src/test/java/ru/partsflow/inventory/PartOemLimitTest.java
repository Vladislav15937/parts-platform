package ru.partsflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.catalog.OemNumbers;
import ru.partsflow.migration.bazon.BazonCsvReader;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Номер производителя длиннее, чем можно сохранить, отбивается словами (400),
 * а не пятисоткой — задача 0312.
 *
 * <p>Ответ владельца продукта 17.10.2026 дословно: «1 - отказывай словами».
 * Замер 0310: номер из 5000 несжимаемых знаков отбивал индекс {@code part_oem_uk}
 * («index row size … exceeds btree version 4 maximum 2704»), наружу уходило
 * 500 «Внутренняя ошибка», а на приёмке — запись, которую офлайн-очередь
 * повторяет вечно.
 *
 * <p>Поверхности записи {@code part_oem} — перебором всех вставок в
 * {@code src/main/java}: карточка ({@code PartIdentity}), приёмка
 * ({@code IntakeService}), перенос из таблицы ({@code ExcelWarehouseImporter},
 * четыре вставки за одним разбором строки) и перенос из Bazon
 * ({@code BazonImporter}). Каждая — через HTTP.
 *
 * <p>Знаки случайные, а не повтор одной буквы: повтор Postgres сжимает, и индекс
 * его пропускает — поэтому на ПРОМ «5000 знаков проходили». Предел при этом
 * считается по длине, а не по тому, как сожмёт Postgres: сжимаемые 2000 — тоже отказ.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartOemLimitTest extends PostgresTestBase {

    private static final String TENANT = "t_000955";

    private static final int LIMIT = OemNumbers.MAX_NORMALIZED_LENGTH;

    private static final AtomicInteger SEQ = new AtomicInteger();

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

    private Long partId;
    private Long warehouseId;
    private final Random random = new Random(312);

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 955");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (955, ?, 'Разборка', 'oemlimit0312')""", TENANT);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        inTenant(() -> {
            var found = jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'vladelec0312'",
                    Long.class);
            if (found.isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Пётр Владельцев', 'OWNER', 'vladelec0312', ?)""",
                        passwordEncoder.encode("пароль"));
            }
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES (?) RETURNING id", Long.class, "Филиал " + suffix);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, ?) RETURNING id",
                    Long.class, branch, "Склад " + suffix);
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published, note)
                    VALUES (1, 'Фара', 100, true, 'заметка прежняя') RETURNING id""", Long.class);
            jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)"
                    + " VALUES (?, '81170-33670', '8117033670', true)", partId);
            return null;
        });
    }

    // ------------------------------------------------------------------ карточка

    @Test
    @DisplayName("Карточка: номер производителя в 5000 знаков — 400 словами, не записано ничего из запроса")
    void cardRefusesALongOem() throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("oem", randomNumber(5000));
        body.put("note", "заметка из того же запроса");
        MvcResult result = send(body);

        assertRefused(result, "Номер производителя длиннее " + LIMIT + " знаков: сохранить нельзя");
        assertThat(oems()).as("в part_oem что-то записано").containsExactly("8117033670");
        assertThat(note()).as("отказ до записи, а сохранилась заметка").isEqualTo("заметка прежняя");
    }

    @Test
    @DisplayName("Карточка: один кросс-номер в 5000 знаков и список, где длинный только один, — 400 словами")
    void cardRefusesALongCross() throws Exception {
        String longCross = randomNumber(5000);
        for (String crosses : List.of(longCross, "AB-100, " + longCross + ", CD-200")) {
            ObjectNode body = json.createObjectNode();
            body.put("crosses", crosses);
            MvcResult result = send(body);
            assertRefused(result, "Кросс-номер «" + longCross.substring(0, 20) + "…» длиннее "
                    + LIMIT + " знаков");
            assertThat(text(result)).as("в ответ ушёл кросс-номер целиком")
                    .doesNotContain(longCross.substring(0, 100));
            assertThat(oems()).containsExactly("8117033670");
        }
    }

    @Test
    @DisplayName("Карточка: ровно предел сохраняется, на знак больше — отказ, сжимаемые 2000 — тоже отказ")
    void limitIsByLength() throws Exception {
        String exact = randomNumber(LIMIT);
        ObjectNode atLimit = json.createObjectNode();
        atLimit.put("oem", exact);
        MvcResult saved = send(atLimit);
        assertThat(saved.getResponse().getStatus()).as(text(saved)).isEqualTo(200);
        assertThat(oems()).containsExactly(exact);

        ObjectNode over = json.createObjectNode();
        over.put("oem", randomNumber(LIMIT + 1));
        assertRefused(send(over), "длиннее " + LIMIT + " знаков");

        ObjectNode compressible = json.createObjectNode();
        compressible.put("oem", "A".repeat(2000));
        assertRefused(send(compressible), "длиннее " + LIMIT + " знаков");
        assertThat(oems()).containsExactly(exact);
    }

    @Test
    @DisplayName("Карточка: длинный номер при чужой правке — 400 про номер, а не 409 и не «сохранено частично»")
    void lengthIsCheckedBeforeTheForeignEdit() throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("oem", randomNumber(5000));
        body.put("note", "заметка из того же запроса");
        ObjectNode seen = body.putObject("seen");
        seen.put("oem", "чужое значение");
        seen.put("note", "заметка прежняя");
        assertRefused(send(body), "Номер производителя длиннее");
        assertThat(note()).isEqualTo("заметка прежняя");
    }

    // ------------------------------------------------------------------ приёмка

    @Test
    @DisplayName("Приёмка: номер в 5000 знаков — 400 словами с названием позиции, партия не заведена")
    void intakeRefusesALongOem() throws Exception {
        String requestId = UUID.randomUUID().toString();
        MvcResult result = mvc.perform(post("/api/intake/receipts").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"requestId":"%s",
                                 "items":[{"rawName":"стартер приёмки","quantity":1,"price":5000},
                                          {"rawName":"генератор приёмки","quantity":1,"price":5000,
                                           "oemNumber":"%s"}]}"""
                                .formatted(warehouseId, requestId, randomNumber(5000))))
                .andReturn();

        assertRefused(result, "«генератор приёмки»: Номер производителя длиннее " + LIMIT);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part WHERE title ILIKE '%приёмки%'", Integer.class)))
                .as("отказ по одной позиции оставил за собой половину партии").isZero();
    }

    // ------------------------------------------------------------------ перенос из таблицы

    @Test
    @DisplayName("Перенос из таблицы: строка с длинным номером или кросс-номером пропущена с причиной, остальные перенесены")
    void excelSkipsOnlyTheLongRow() throws Exception {
        String longOem = randomNumber(5000);
        String longCross = randomNumber(5000);
        byte[] book = workbook(List.of("Наименование", "Цена", "Кол-во", "Артикул", "Замены"),
                List.of(List.of("Стартер таблицы", "4000", "1", "28100-0D030", ""),
                        List.of("Фара таблицы", "6000", "1", longOem, ""),
                        List.of("Бампер таблицы", "9000", "1", "52119-33660", "AB-1, " + longCross)));

        MvcResult result = mvc.perform(multipart("/api/import/excel")
                        .file(new MockMultipartFile("file", "sklad.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", book))
                        .param("NAME", "0").param("PRICE", "1").param("QUANTITY", "2")
                        .param("OEM", "3").param("OEM_SUBSTITUTES", "4")
                        .param("warehouseId", String.valueOf(warehouseId))
                        .param("requestId", UUID.randomUUID().toString())
                        .with(csrf()).session(login()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        JsonNode report = json.readTree(text(result));
        assertThat(report.path("imported").asInt()).as("остальные строки файла").isEqualTo(1);
        assertThat(report.path("skipped").toString())
                .contains("\"row\":3", "Номер производителя длиннее " + LIMIT + " знаков")
                .contains("\"row\":4", "Кросс-номер «" + longCross.substring(0, 20) + "…»");
        assertThat(inTenant(() -> jdbc.queryForList(
                "SELECT title FROM part WHERE title LIKE '% таблицы%'", String.class)))
                .containsExactly("Стартер таблицы");
    }

    // ------------------------------------------------------------------ перенос из Bazon

    @Test
    @DisplayName("Перенос из Bazon: длинный номер не заведён и назван строкой отчёта, товар перенесён")
    void bazonSkipsTheNumberAndNamesIt() throws Exception {
        String longOem = randomNumber(5000);
        String catalog = """
                "Номер товара";"Запчасть";"Цена";"Номер производителя";"Основной (свободно)";"Выгружать"
                "B-1-%1$s";"Стартер базона";"6800";"28100-0D030";"1";"да"
                "B-2-%1$s";"Фара базона";"9500";"%2$s";"1";"да"
                """.formatted(SEQ.get(), longOem);
        String donors = """
                "Номер донора";"Марка";"Модель";"Год выпуска"
                """;
        MvcResult result = mvc.perform(multipart("/api/import/bazon")
                        .file(csv("donors", donors))
                        .file(csv("catalog", catalog))
                        .with(csrf()).session(login()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(text(result)).contains("Номер производителя длиннее " + LIMIT
                + " знаков: сохранить нельзя");
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM part p WHERE p.title LIKE 'Фара базона%'
                   AND NOT EXISTS (SELECT 1 FROM part_oem o WHERE o.part_id = p.id)""",
                Integer.class))).as("товар с длинным номером не перенесён — или перенесён с ним")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ помощники

    private void assertRefused(MvcResult result, String words) throws Exception {
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(400);
        assertThat(json.readTree(text(result)).path("message").asText())
                .contains(words)
                .contains("Похоже, вставлено не то — проверьте номер");
    }

    private String randomNumber(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder number = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            number.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return number.toString();
    }

    private List<String> oems() {
        return inTenant(() -> jdbc.queryForList(
                "SELECT normalized FROM part_oem WHERE part_id = ? ORDER BY id", String.class, partId));
    }

    private String note() throws Exception {
        return json.readTree(text(mvc.perform(get("/api/parts/" + partId + "/editable")
                .session(login())).andReturn())).path("note").asText();
    }

    private MvcResult send(JsonNode body) throws Exception {
        return mvc.perform(put("/api/parts/" + partId).with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn();
    }

    private static String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile(name, name + ".csv", "text/csv",
                content.getBytes(BazonCsvReader.CHARSET));
    }

    private static byte[] workbook(List<String> header, List<List<String>> rows) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Склад");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < header.size(); i++) {
                headerRow.createCell(i).setCellValue(header.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    if (!values.get(c).isEmpty()) {
                        row.createCell(c).setCellValue(values.get(c));
                    }
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"oemlimit0312","login":"vladelec0312","password":"пароль"}"""))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
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
