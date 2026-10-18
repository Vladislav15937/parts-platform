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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Основной номер производителя, вписанный несколькими через разделитель, делится
 * на основной и кросс-номера — на всех путях записи (задача 0323).
 *
 * <p>Решение владельца продукта 18.10.2026 дословно: «у детали только один номер
 * произвоителя - остальное - кросс номера» и «2 - а» (перенос из Bazon делит
 * кросс-номера тем же правилом, что ввод). До задачи «8414012530, 8414012531»
 * ложилось одной строкой с запятой, которая не находилась поиском ни по одному
 * из двух номеров, а пересчёт 0324 разделил бы её, и следующий ввод записал бы
 * такую же заново.
 *
 * <p>Пути — перебором всех записей основного номера в {@code src/main/java}:
 * карточка ({@code PUT /api/parts/{id}}), приёмка, перенос из таблицы, перенос
 * из Bazon. Каждый через HTTP, и результат сравнивается с одним правилом —
 * {@link OemNumbers#primary}.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class OemPrimarySplitTest extends PostgresTestBase {

    private static final String TENANT = "t_000966";

    private static final AtomicInteger SEQ = new AtomicInteger();

    /**
     * Один набор строк на все пути. Разделители — все, какими делит
     * {@link OemNumbers#list}: запятая, точка с запятой, табуляция, перевод строки
     * (и «\r\n»); пробел и дефис номер не режут; «АНАЛОГ» строки не даёт; первая
     * часть «не номер» — текст остаётся целиком (основной не угадывается); повтор
     * номера в другом написании — один номер.
     */
    private static final List<String> INPUTS = List.of(
            "8414012530, 8414012531",
            "84140-12530;84140-12531",
            "KR-1\tKR-2",
            "KR-3\nKR-4\r\nKR-5",
            "FP-1, АНАЛОГ, FP-2",
            "АНАЛОГ, FP-3",
            "DS-1, ds1, DS 1",
            "81170 33670",
            "АВ-123С; 9");

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

    private Long warehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 966");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (966, ?, 'Разборка', 'oemsplit0323')""", TENANT);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        inTenant(() -> {
            var found = jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'vladelec0323'",
                    Long.class);
            if (found.isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Пётр Владельцев', 'OWNER', 'vladelec0323', ?)""",
                        passwordEncoder.encode("пароль"));
            }
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES (?) RETURNING id", Long.class, "Филиал " + suffix);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, ?) RETURNING id",
                    Long.class, branch, "Склад " + suffix);
            return null;
        });
    }

    // ------------------------------------------------------------------ по путям

    @Test
    @DisplayName("Карточка: «8414012530, 8414012531» — основной первый, второй кросс-номером; форма после записи — разделённая; журнал — обе перемены")
    void cardSplitsThePrimary() throws Exception {
        Long partId = newPart();
        ObjectNode body = json.createObjectNode();
        body.put("oem", "8414012530, 8414012531");
        MvcResult saved = send(partId, body);
        assertThat(saved.getResponse().getStatus()).as(text(saved)).isEqualTo(200);

        assertThat(rows(partId)).containsExactly("8414012530|8414012530|true", "8414012531|8414012531|false");
        JsonNode form = editable(partId);
        assertThat(form.path("oem").asText()).as("форма после записи несёт набранное, а не разделённое")
                .isEqualTo("8414012530");
        assertThat(form.path("crosses").asText()).isEqualTo("8414012531");

        Map<String, Object> journal = inTenant(() -> jdbc.queryForMap("""
                SELECT new_value->>'oem' AS oem, new_value->>'crosses' AS crosses FROM audit_log
                 WHERE table_name = 'part' AND record_id = ? AND new_value->'oem' IS NOT NULL
                 ORDER BY id DESC LIMIT 1""", partId));
        assertThat(journal.get("oem")).isEqualTo("8414012530");
        assertThat(journal.get("crosses")).as("журнал не назвал перемену кросс-номеров").isEqualTo("8414012531");
    }

    @Test
    @DisplayName("Карточка: лишние номера основного встают перед набранными и перед хранимыми кросс-номерами, без повторов")
    void cardPutsExtrasFirst() throws Exception {
        Long partId = newPart();
        inTenant(() -> jdbc.update("""
                INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
                VALUES (?, 'OLD-1', 'OLD1', false)""", partId));

        ObjectNode onlyOem = json.createObjectNode();
        onlyOem.put("oem", "A-1, B-2, OLD-1");
        assertThat(send(partId, onlyOem).getResponse().getStatus()).isEqualTo(200);
        assertThat(rows(partId)).as("без ключа crosses — хранимые остаются, лишние встают перед ними")
                .containsExactly("A-1|A1|true", "B-2|B2|false", "OLD-1|OLD1|false");

        ObjectNode both = json.createObjectNode();
        both.put("oem", "C-3; D-4");
        both.put("crosses", "E-5, D-4, C-3");
        assertThat(send(partId, both).getResponse().getStatus()).isEqualTo(200);
        assertThat(rows(partId)).as("набранный повтор основного или лишнего не заводится")
                .containsExactly("C-3|C3|true", "D-4|D4|false", "E-5|E5|false");
    }

    @Test
    @DisplayName("Приёмка: лишние номера — кросс-номерами; повтор по requestId и шесть одновременных — результат первого")
    void intakeSplitsAndReplays() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String body = intakeBody(requestId, "8414012530, 8414012531");
        MockHttpSession session = login();

        List<Integer> codes = new ArrayList<>();
        CyclicBarrier start = new CyclicBarrier(6);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Thread thread = new Thread(() -> {
                int status;
                try {
                    start.await();
                    status = mvc.perform(post("/api/intake/receipts").with(csrf()).session(session)
                                    .contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    status = -1;
                }
                synchronized (codes) {
                    codes.add(status);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(codes).as("одновременный повтор ответил не результатом первого: %s", codes).containsOnly(201);
        int replay = mvc.perform(post("/api/intake/receipts").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
        assertThat(replay).isEqualTo(201);

        List<Long> parts = intakeParts(requestId);
        assertThat(parts).as("повтор завёл вторую позицию").hasSize(1);
        assertThat(rows(parts.get(0))).containsExactly("8414012530|8414012530|true", "8414012531|8414012531|false");
    }

    @Test
    @DisplayName("Перенос из таблицы: «A, B» — основной и замена; повтор файла — «без изменений», расхождений нет")
    void excelSplitsAndRepeatSeesNoDiscrepancy() throws Exception {
        String ad = String.valueOf(900_000_000L + System.nanoTime() % 100_000_000L);
        byte[] book = workbook(List.of("Наименование", "Цена", "Кол-во", "Артикул", "Замены", "Номер объявления"),
                List.of(List.of("Фара таблицы " + ad, "6000", "1", "8414012530, 8414012531", "AB-1", ad)));

        JsonNode first = loadExcel(book, true);
        assertThat(first.path("imported").asInt()).as(first.toString()).isEqualTo(1);
        Long partId = partByTitle("Фара таблицы " + ad);
        assertThat(rows(partId)).as("лишний номер основного — перед заменами файла")
                .containsExactly("8414012530|8414012530|true", "8414012531|8414012531|false", "AB-1|AB1|false");

        JsonNode second = loadExcel(book, true);
        JsonNode repeat = second.path("repeat");
        assertThat(repeat.path("discrepancyCount").asInt()).as("повтор назвал разделённый номер расхождением: %s", second)
                .isZero();
        assertThat(repeat.path("unchanged").asInt()).as(second.toString()).isEqualTo(1);
        assertThat(rows(partId)).hasSize(3);
    }

    @Test
    @DisplayName("Перенос из Bazon: основной делится, кросс-номера делятся «;», табуляцией и переводом строки, а не только запятой")
    void bazonSplitsPrimaryAndCrosses() throws Exception {
        String code = "B-" + SEQ.get() + "-" + System.nanoTime();
        String catalog = """
                "Номер товара";"Запчасть";"Цена";"Номер производителя";"Кросс-номера";"Основной (свободно)";"Выгружать"
                "%s";"Стартер базона";"6800";"8414012530, 8414012531";"X-1;X-2\tX-3
                X-4, X-5";"1";"да"
                """.formatted(code);
        bazon(catalog);
        Long partId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE legacy_code = ?", Long.class, code));
        assertThat(rows(partId)).containsExactly(
                "8414012530|8414012530|true", "8414012531|8414012531|false",
                "X-1|X1|false", "X-2|X2|false", "X-3|X3|false", "X-4|X4|false", "X-5|X5|false");
    }

    // ------------------------------------------------------------------ одно правило

    @Test
    @DisplayName("Одно правило: один набор строк через карточку, приёмку, таблицу и Bazon даёт одни и те же строки part_oem, что и OemNumbers.primary")
    void everyPathGivesTheSameRows() throws Exception {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        for (String input : INPUTS) {
            OemNumbers.Primary rule = OemNumbers.primary(input);
            List<String> rows = new ArrayList<>();
            if (rule.primary() != null) {
                rows.add(rule.primary() + "|" + OemNumbers.normalize(rule.primary()) + "|true");
            }
            for (String cross : rule.crosses()) {
                rows.add(cross + "|" + OemNumbers.normalize(cross) + "|false");
            }
            expected.put(input, rows);
        }
        // Правило само по себе — то, что задача требует (не своя копия в тесте):
        assertThat(expected.get("8414012530, 8414012531"))
                .containsExactly("8414012530|8414012530|true", "8414012531|8414012531|false");
        assertThat(expected.get("FP-1, АНАЛОГ, FP-2")).containsExactly("FP-1|FP1|true", "FP-2|FP2|false");
        assertThat(expected.get("АНАЛОГ, FP-3")).as("первая часть не номер — строка целиком")
                .containsExactly("АНАЛОГ, FP-3|FP3|true");
        assertThat(expected.get("81170 33670")).containsExactly("81170 33670|8117033670|true");
        assertThat(expected.get("DS-1, ds1, DS 1")).containsExactly("DS-1|DS1|true");

        Map<String, List<String>> card = new LinkedHashMap<>();
        Map<String, List<String>> intake = new LinkedHashMap<>();
        Map<String, List<String>> excel = new LinkedHashMap<>();
        Map<String, List<String>> bazon = new LinkedHashMap<>();
        StringBuilder catalog = new StringBuilder(
                "\"Номер товара\";\"Запчасть\";\"Цена\";\"Номер производителя\";\"Основной (свободно)\";\"Выгружать\"\n");
        List<List<String>> sheet = new ArrayList<>();
        String run = SEQ.get() + "-" + System.nanoTime();
        for (int i = 0; i < INPUTS.size(); i++) {
            String input = INPUTS.get(i);
            Long partId = newPart();
            ObjectNode body = json.createObjectNode();
            body.put("oem", input);
            MvcResult saved = send(partId, body);
            assertThat(saved.getResponse().getStatus()).as(text(saved)).isEqualTo(200);
            card.put(input, rows(partId));

            String requestId = UUID.randomUUID().toString();
            MvcResult received = mvc.perform(post("/api/intake/receipts").with(csrf()).session(login())
                            .contentType(MediaType.APPLICATION_JSON).content(intakeBody(requestId, input)))
                    .andReturn();
            assertThat(received.getResponse().getStatus()).as(text(received)).isEqualTo(201);
            intake.put(input, rows(intakeParts(requestId).get(0)));

            sheet.add(List.of("Строка " + run + " " + i, "1000", "1", input));
            catalog.append("\"S-%s-%d\";\"Деталь\";\"1000\";\"%s\";\"1\";\"да\"\n".formatted(run, i, input));
        }
        loadExcel(workbook(List.of("Наименование", "Цена", "Кол-во", "Артикул"), sheet), false);
        bazon(catalog.toString());
        for (int i = 0; i < INPUTS.size(); i++) {
            excel.put(INPUTS.get(i), rows(partByTitle("Строка " + run + " " + i)));
            String legacy = "S-" + run + "-" + i;
            bazon.put(INPUTS.get(i), rows(inTenant(() -> jdbc.queryForObject(
                    "SELECT id FROM part WHERE legacy_code = ?", Long.class, legacy))));
        }

        assertThat(card).as("карточка").isEqualTo(expected);
        assertThat(intake).as("приёмка").isEqualTo(expected);
        assertThat(excel).as("перенос из таблицы").isEqualTo(expected);
        assertThat(bazon).as("перенос из Bazon").isEqualTo(expected);
    }

    // ------------------------------------------------------------------ предел 0312

    @Test
    @DisplayName("Предел 0312 по каждому номеру: «короткий, 600 + 600» сохраняется везде, номер сверх предела среди коротких — отказ словами")
    void limitIsPerNumber() throws Exception {
        String a = "A".repeat(600);
        String b = "B".repeat(600);
        String two = a + ", " + b;
        Long partId = newPart();
        ObjectNode ok = json.createObjectNode();
        ok.put("oem", two);
        MvcResult saved = send(partId, ok);
        assertThat(saved.getResponse().getStatus()).as("строка из двух номеров по 600 мерилась целиком: %s", text(saved))
                .isEqualTo(200);
        assertThat(rows(partId)).hasSize(2);

        String over = "AB-1, " + "Z".repeat(OemNumbers.MAX_NORMALIZED_LENGTH + 1);
        ObjectNode bad = json.createObjectNode();
        bad.put("oem", over);
        MvcResult refused = send(partId, bad);
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(text(refused)).contains("Номер производителя длиннее");

        MvcResult intakeOk = mvc.perform(post("/api/intake/receipts").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intakeBody(UUID.randomUUID().toString(), two)))
                .andReturn();
        assertThat(intakeOk.getResponse().getStatus()).as(text(intakeOk)).isEqualTo(201);
        MvcResult intakeBad = mvc.perform(post("/api/intake/receipts").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intakeBody(UUID.randomUUID().toString(), over)))
                .andReturn();
        assertThat(intakeBad.getResponse().getStatus()).isEqualTo(400);
        assertThat(text(intakeBad)).contains("Номер производителя длиннее");

        String run = SEQ.get() + "-" + System.nanoTime();
        JsonNode report = loadExcel(workbook(List.of("Наименование", "Цена", "Кол-во", "Артикул"),
                List.of(List.of("Лимит " + run + " ок", "1000", "1", two),
                        List.of("Лимит " + run + " нет", "1000", "1", over))), false);
        assertThat(report.path("imported").asInt()).as(report.toString()).isEqualTo(1);
        assertThat(report.path("skipped").toString()).contains("\"row\":3", "Номер производителя длиннее");

        String text = bazon("""
                "Номер товара";"Запчасть";"Цена";"Номер производителя";"Основной (свободно)";"Выгружать"
                "L-%1$s-1";"Деталь";"1000";"%2$s";"1";"да"
                "L-%1$s-2";"Деталь";"1000";"%3$s";"1";"да"
                """.formatted(run, two, over));
        assertThat(text).contains("Номер производителя длиннее");
        assertThat(rows(inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE legacy_code = ?", Long.class, "L-" + run + "-1")))).hasSize(2);
        assertThat(rows(inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE legacy_code = ?", Long.class, "L-" + run + "-2"))))
                .as("товар перенесён, длинный номер пропущен, короткий основной записан")
                .containsExactly("AB-1|AB1|true");
    }

    // ------------------------------------------------------------------ помощники

    private Long newPart() {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, is_published)
                VALUES (1, 'Фара', 100, true) RETURNING id""", Long.class));
    }

    /** Строки part_oem позиции: «raw|normalized|is_primary», основной первым, затем по id. */
    private List<String> rows(Long partId) {
        return inTenant(() -> jdbc.queryForList("""
                SELECT raw_number || '|' || normalized || '|' || is_primary FROM part_oem
                 WHERE part_id = ? ORDER BY is_primary DESC, id""", String.class, partId));
    }

    private Long partByTitle(String title) {
        return inTenant(() -> jdbc.queryForObject("SELECT id FROM part WHERE title = ?", Long.class, title));
    }

    private List<Long> intakeParts(String requestId) {
        return inTenant(() -> jdbc.queryForList("""
                SELECT l.part_id FROM stock_document_line l
                  JOIN stock_document d ON d.id = l.document_id
                 WHERE d.client_request_id = ?""", Long.class, requestId));
    }

    private String intakeBody(String requestId, String oem) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("warehouseId", warehouseId);
        body.put("requestId", requestId);
        ObjectNode item = body.putArray("items").addObject();
        item.put("rawName", "генератор приёмки");
        item.put("quantity", 1);
        item.put("price", 5000);
        item.put("oemNumber", oem);
        return body.toString();
    }

    private JsonNode loadExcel(byte[] book, boolean withAd) throws Exception {
        var request = multipart("/api/import/excel")
                .file(new MockMultipartFile("file", "sklad.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", book))
                .param("NAME", "0").param("PRICE", "1").param("QUANTITY", "2").param("OEM", "3")
                .param("warehouseId", String.valueOf(warehouseId))
                .param("requestId", UUID.randomUUID().toString());
        if (withAd) {
            request.param("OEM_SUBSTITUTES", "4").param("DROM_AD_NUMBER", "5");
        }
        MvcResult result = mvc.perform(request.with(csrf()).session(login())).andReturn();
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        return json.readTree(text(result));
    }

    private String bazon(String catalog) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/import/bazon")
                        .file(csv("donors", """
                                "Номер донора";"Марка";"Модель";"Год выпуска"
                                """))
                        .file(csv("catalog", catalog))
                        .with(csrf()).session(login()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        return text(result);
    }

    private JsonNode editable(Long partId) throws Exception {
        return json.readTree(text(mvc.perform(get("/api/parts/" + partId + "/editable")
                .session(login())).andReturn()));
    }

    private MvcResult send(Long partId, JsonNode body) throws Exception {
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
                                {"company":"oemsplit0323","login":"vladelec0323","password":"пароль"}"""))
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
