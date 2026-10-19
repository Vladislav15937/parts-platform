package ru.partsflow.migration.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
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
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.publishing.drom.DromPriceGenerator;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Поля карточки Дрома в файле переноса (задача 0277): состояние,
 * оригинальность, производитель, номера замен и три стороны — на образце
 * из пяти настоящих карточек клиента, через HTTP, как это делает экран.
 *
 * <p><b>{@link #CHECKS} — проверки этих семи колонок, и их же зовёт
 * {@link DromTransferFileTest}</b> в общем списке договора: одна проверка
 * на колонку, а не две, разъехавшиеся бы на первой правке. Сравнивают они
 * базу со значением файла <b>своим словарём</b> (слово Дрома → код), а не
 * разборщиком импорта: проверка тем же кодом, что и проверяемое, зеленела
 * бы на любой его ошибке.
 *
 * <p>Схема своя, не общая с соседями по пакету: каждый из них грузит тот же
 * образец, а объявление, номер которого уже есть, второй позицией
 * не заводится (задача 0274).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DromCardFieldsTest extends PostgresTestBase {

    private static final String TENANT = "t_000977";
    private static final int REGISTRY_ID = 977;
    private static final String COMPANY = "dromcard";

    private static final String SAMPLE = "/migration/excel/drom-transfer-sample.xlsx";

    /** Чтение одной колонки позиции в схеме арендатора: SQL с одним параметром — номер позиции. */
    @FunctionalInterface
    interface Reader {
        List<String> strings(String sql, long part);

        default String one(String sql, long part) {
            List<String> found = strings(sql, part);
            assertThat(found).as("запрос «%s» вернул не одну строку", sql).hasSize(1);
            return found.get(0);
        }
    }

    /** Проверка одной колонки: значение из файла против того, что в базе у позиции. */
    @FunctionalInterface
    interface CardCheck {
        void verify(Reader db, long part, String fileValue);
    }

    // Слова Дрома → код в базе. Свои, а не разборщик импорта (см. заголовок).
    // «Новый» на образце не встречается — его ставит сам тест, когда ему нужно
    // значение, отличное от умолчания.
    private static final Map<String, String> CONDITIONS = Map.of(
            "Б/у", "USED", "Новый", "NEW", "Новое", "NEW", "", "USED");
    private static final Map<String, String> LATERAL = Map.of(
            "Левая", "LEFT", "Правая", "RIGHT", "Левая, Правая", "BOTH");
    private static final Map<String, String> LONGITUDINAL = Map.of(
            "Переднее", "FRONT", "Заднее", "REAR", "Переднее, Заднее", "BOTH");
    private static final Map<String, String> VERTICAL = Map.of(
            "Верхнее", "UPPER", "Нижнее", "LOWER", "Верхнее, Нижнее", "BOTH");

    /** Колонки задачи 0277 — и чем доказано, что значение доехало. */
    static final Map<String, CardCheck> CHECKS = Map.of(
            "Состояние", (db, part, value) -> assertThat(db.one(
                    "SELECT condition FROM part WHERE id = ?", part))
                    .as("состояние из файла не доехало (в файле «%s»)", value)
                    .isEqualTo(word(CONDITIONS, value)),
            "Оригинальность", (db, part, value) -> assertThat(db.one(
                    "SELECT authenticity FROM part WHERE id = ?", part))
                    .as("оригинальность клиента доехала не дословно")
                    .isEqualTo(blankToNull(value)),
            "Производитель", (db, part, value) -> assertThat(db.one(
                    "SELECT manufacturer FROM part WHERE id = ?", part))
                    .as("производитель детали из файла не доехал")
                    .isEqualTo(blankToNull(value)),
            "Номера замен", (db, part, value) -> assertThat(db.strings("""
                    SELECT raw_number FROM part_oem
                     WHERE part_id = ? AND NOT is_primary ORDER BY id""", part))
                    .as("номера замен доехали не все или не в порядке страницы")
                    .containsExactlyElementsOf(value.isBlank()
                            ? List.of() : Arrays.asList(value.split(", "))),
            "Сторона", (db, part, value) -> assertThat(db.one(
                    "SELECT side_lr FROM part WHERE id = ?", part))
                    .as("сторона из файла не доехала (в файле «%s»)", value)
                    .isEqualTo(word(LATERAL, value)),
            "Положение", (db, part, value) -> assertThat(db.one(
                    "SELECT side_fr FROM part WHERE id = ?", part))
                    .as("положение из файла не доехало (в файле «%s»)", value)
                    .isEqualTo(word(LONGITUDINAL, value)),
            "Верх/низ", (db, part, value) -> assertThat(db.one(
                    "SELECT side_ud FROM part WHERE id = ?", part))
                    .as("верх/низ из файла не доехал (в файле «%s»)", value)
                    .isEqualTo(word(VERTICAL, value)));

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

    private final ObjectMapper json = new ObjectMapper();
    private long warehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", REGISTRY_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка с Дрома', ?)""", REGISTRY_ID, TENANT, COMPANY);
        warehouseId = inTenant(() -> {
            clean();
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'owner'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Владелец', 'OWNER', 'owner', ?)""", passwordEncoder.encode("пароль"));
            }
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Барнаул') RETURNING id", Long.class);
            return jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Исакова') RETURNING id",
                    Long.class, branch);
        });
    }

    /**
     * Образец, в котором у каждой из семи колонок есть значение, отличное
     * от того, что позиция получила бы без колонки.
     *
     * <p>На образце как есть «Состояние» у всех «Б/у» — ровно умолчание, —
     * а «Сторона» и «Верх/низ» почти пусты: убери такую колонку, и позиции
     * не изменятся, то есть проверка на неё промолчит при сломанном импорте.
     * Поэтому три ячейки подменены: разъёму — «Новый» и пара сторон
     * (как у амортизатора клиента № 135618913), панели — «Нижнее».
     */
    private static byte[] covering() throws Exception {
        return edited(sample(), Map.of(
                3, Map.of("Состояние", "Новый", "Сторона", "Левая, Правая",
                        "Положение", "Переднее, Заднее"),
                4, Map.of("Верх/низ", "Верхнее, Нижнее")));
    }

    /**
     * Критерий 4 задачи 0277: колонка убрана из файла — падает ровно её
     * проверка, и никакая другая.
     *
     * <p>Обе половины нужны. «Падает её» — импорт действительно читает
     * колонку (иначе без неё ничего не изменилось бы). «Только её» — проверки
     * не перепутаны и не дублируют друг друга: проверка производителя,
     * смотрящая на чужую колонку, упала бы вместе с ней.
     */
    @Test
    @DisplayName("Колонка убрана из файла — падает ровно одна проверка, на эту колонку")
    void eachColumnHasItsOwnCheck() throws Exception {
        byte[] full = covering();
        List<Map<String, String>> rows = sampleRows(full);

        // Контроль: целый файл проходит все семь проверок.
        assertThat(failingAfterLoad(full, rows))
                .as("на целом файле проверки уже падают — дальше сравнивать не с чем")
                .isEmpty();

        for (String column : CHECKS.keySet()) {
            inTenant(() -> {
                clean();
                return null;
            });
            assertThat(failingAfterLoad(without(full, column), rows))
                    .as("убрали колонку «%s»", column)
                    .containsExactly(column);
        }
    }

    @Test
    @DisplayName("«Левая, Правая» хранится одной стороной BOTH, в прайс уходит своим словом, отбор продавца её находит")
    void bothSidesSurviveToThePriceAndTheSeller() throws Exception {
        byte[] file = covering();
        load(file);
        long connector = partOf("Разъем 6 контактов Toyota");
        long door = partOf("Дверь передняя правая Toyota Land Cruiser Prado 120");

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT side_lr FROM part WHERE id = ?", String.class, connector)))
                .isEqualTo("BOTH");

        String price = price();
        assertThat(offerOf(price, connector))
                .as("в прайсе у пары нет стороны — у клиента на карточке она есть")
                .contains("<leftRight>left,right</leftRight>");
        assertThat(offerOf(price, door))
                .as("одиночная сторона уезжает прежним словом")
                .contains("<leftRight>right</leftRight>");

        // Задача 0283: «Переднее, Заднее» и «Верхнее, Нижнее» — тем же путём.
        long panel = partOf("Панель приборов Toyota Land Cruiser Prado 120");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT side_fr FROM part WHERE id = ?", String.class, connector))).isEqualTo("BOTH");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT side_ud FROM part WHERE id = ?", String.class, panel))).isEqualTo("BOTH");
        assertThat(offerOf(price, connector)).contains("<frontRear>front,rear</frontRear>");
        // Вертикальной оси в формате Bazon нет вовсе (задача 0333): «Верхнее,
        // Нижнее» хранится и видно продавцу, а в прайс не уходит.
        assertThat(offerOf(price, panel)).doesNotContain("<ud>");
        assertThat(positionTitles("Разъем", "FRONT")).contains("Разъем 6 контактов Toyota");
        assertThat(positionTitles("Разъем", "REAR")).contains("Разъем 6 контактов Toyota");

        // Продавцу, спросившему левый, пара — ответ; правая дверь — нет.
        assertThat(stockTitles("Разъем", "LEFT")).contains("Разъем 6 контактов Toyota");
        assertThat(stockTitles("Разъем", "RIGHT")).contains("Разъем 6 контактов Toyota");
        assertThat(stockTitles("Дверь", "LEFT"))
                .as("отбор по левой стороне отдал правую дверь")
                .doesNotContain("Дверь передняя правая Toyota Land Cruiser Prado 120");
    }

    @Test
    @DisplayName("Оригинальность видна в карточке (в прайсе Bazon-вида её тега нет); замены — по порядку страницы")
    void authenticityAndSubstitutesReachCardAndPrice() throws Exception {
        load(covering());
        long cardan = partOf(
                "Карданный вал передний (Пробег 130т. км)Toyota Land Cruiser Prado 120");

        String offer = offerOf(price(), cardan);
        // Тега оригинальности у Bazon нет, и в нашем файле его нет тоже
        // (решение 1 задачи 0333); в карточке она остаётся — ниже.
        assertThat(offer).doesNotContain("<authenticity>");
        assertThat(offer).contains("<manufacturer>Toyota</manufacturer>");
        assertThat(offer)
                .as("номера замен в прайсе не все или не в порядке страницы клиента")
                .contains("<analogsNumbers>HLKA0017, NWNTY011, ST3714060380, 3950TO0380, "
                        + "ZVPV236, DSZ1140PQ, AFR3714060380</analogsNumbers>");
        assertThat(offer).contains("<frontRear>front</frontRear>");

        // Карточка позиции читает строку витрины — её и спрашиваем, как экран.
        JsonNode row = catalogRow(cardan);
        assertThat(row.path("authenticity").asText())
                .as("оригинальность не видна в карточке")
                .isEqualTo("Оригинал");
        assertThat(row.path("crosses").asText())
                .startsWith("HLKA0017, NWNTY011, ST3714060380");
    }

    @Test
    @DisplayName("Непонятное слово стороны или состояния пропускает строку и называет слово")
    void unknownWordSkipsTheRowWithWords() throws Exception {
        byte[] file = edited(sample(), Map.of(
                1, Map.of("Сторона", "Справа"),
                2, Map.of("Состояние", "Отличное")));

        JsonNode report = load(file);

        assertThat(report.path("imported").asInt()).isEqualTo(3);
        Map<Integer, String> skipped = new HashMap<>();
        report.path("skipped").forEach(s -> skipped.put(s.path("row").asInt(), s.path("reason").asText()));
        assertThat(skipped).containsExactlyInAnyOrderEntriesOf(Map.of(
                3, "сторона не распознана: «Справа»",
                4, "состояние не распознано: «Отличное»"));
    }

    // --- проверки ---

    /** Грузит файл и возвращает колонки, чьи проверки не прошли хотя бы на одной строке. */
    private Set<String> failingAfterLoad(byte[] file, List<Map<String, String>> expected)
            throws Exception {
        JsonNode report = load(file);
        assertThat(report.path("imported").asInt())
                .as("загружено не всё: %s", report.path("skipped"))
                .isEqualTo(expected.size());
        Set<String> failing = new LinkedHashSet<>();
        Reader db = (sql, part) -> inTenant(() -> jdbc.queryForList(sql, String.class, part));
        for (Map<String, String> row : expected) {
            long part = partOf(row.get("Наименование"));
            CHECKS.forEach((column, check) -> {
                try {
                    check.verify(db, part, row.get(column));
                } catch (AssertionError failed) {
                    failing.add(column);
                }
            });
        }
        return failing;
    }

    private static String word(Map<String, String> words, String value) {
        if (value == null || value.isBlank()) {
            return words.get("");
        }
        assertThat(words).as("в словаре теста нет слова «%s»", value).containsKey(value);
        return words.get(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // --- образец ---

    private static byte[] sample() throws Exception {
        try (InputStream in = DromCardFieldsTest.class.getResourceAsStream(SAMPLE)) {
            assertThat(in).as("образца %s нет в тестовых ресурсах", SAMPLE).isNotNull();
            return in.readAllBytes();
        }
    }

    /** Образец с подменёнными ячейками: номер строки данных (с нуля) → колонка → значение. */
    private static byte[] edited(byte[] file, Map<Integer, Map<String, String>> cells)
            throws Exception {
        List<String> header = header(file);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.getSheetAt(0);
            cells.forEach((dataRow, values) -> values.forEach((title, value) ->
                    sheet.getRow(dataRow + 1).createCell(header.indexOf(title)).setCellValue(value)));
            book.write(out);
            return out.toByteArray();
        }
    }

    /** Тот же файл без одной колонки — вовсе, вместе с заголовком, а не пустой. */
    private static byte[] without(byte[] file, String column) throws Exception {
        List<String> header = header(file);
        List<Map<String, String>> rows = sampleRows(file);
        try (XSSFWorkbook book = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Дром");
            List<String> kept = header.stream().filter(title -> !title.equals(column)).toList();
            assertThat(kept).as("колонки «%s» в образце нет", column).hasSize(header.size() - 1);
            Row head = sheet.createRow(0);
            for (int c = 0; c < kept.size(); c++) {
                head.createCell(c).setCellValue(kept.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < kept.size(); c++) {
                    row.createCell(c).setCellValue(rows.get(r).get(kept.get(c)));
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    private static List<String> header(byte[] file) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            List<String> header = new ArrayList<>();
            book.getSheetAt(0).getRow(0).forEach(c -> header.add(c.getStringCellValue()));
            return header;
        }
    }

    /** Строки образца: заголовок → значение. Читается POI напрямую, мимо импорта. */
    private static List<Map<String, String>> sampleRows(byte[] file) throws Exception {
        List<String> header = header(file);
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = book.getSheetAt(0);
            List<Map<String, String>> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                Map<String, String> values = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) {
                    Cell cell = row.getCell(c);
                    values.put(header.get(c), cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(values);
            }
            return rows;
        }
    }

    // --- HTTP, как экран ---

    /** Разбор и загрузка тем сопоставлением, которое экран получил сам. */
    private JsonNode load(byte[] file) throws Exception {
        MockHttpSession session = login();
        String previewBody = mvc.perform(multipart("/api/import/excel/preview").file(xlsx(file))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode detected = json.readTree(previewBody).path("detected");

        MockMultipartHttpServletRequestBuilder request = multipart("/api/import/excel");
        request.file(xlsx(file));
        detected.fields().forEachRemaining(e -> request.param(e.getKey(), e.getValue().asText()));
        String body = mvc.perform(request.with(csrf()).session(session)
                        .param("warehouseId", String.valueOf(warehouseId))
                        .param("requestId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private List<String> stockTitles(String query, String side) throws Exception {
        String body = mvc.perform(get("/api/parts/stock").session(login())
                        .param("q", query).param("side", side))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> titles = new ArrayList<>();
        json.readTree(body).path("rows").forEach(r -> titles.add(r.path("title").asText()));
        return titles;
    }

    private List<String> positionTitles(String query, String position) throws Exception {
        String body = mvc.perform(get("/api/parts/stock").session(login())
                        .param("q", query).param("position", position))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> titles = new ArrayList<>();
        json.readTree(body).path("rows").forEach(r -> titles.add(r.path("title").asText()));
        return titles;
    }

    private JsonNode catalogRow(long partId) throws Exception {
        String code = inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, partId));
        String body = mvc.perform(get("/api/parts/catalog").session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (JsonNode row : json.readTree(body).path("rows")) {
            if (code.equals(row.path("code").asText())) {
                return row;
            }
        }
        throw new AssertionError("позиции " + code + " нет на витрине");
    }

    private static MockMultipartFile xlsx(byte[] file) {
        return new MockMultipartFile("file", "perenos-s-droma.xlsx",
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

    // --- прайс ---

    private String price() {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out);
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    /** Одна позиция {@code <part>} прайса — по номеру позиции, он и есть её {@code id} (задачи 0334, 0333). */
    private String offerOf(String price, long partId) {
        String code = inTenant(() -> jdbc.queryForObject(
                "SELECT number::text FROM part WHERE id = ?", String.class, partId));
        Matcher offer = Pattern.compile("<part>.*?</part>", Pattern.DOTALL).matcher(price);
        while (offer.find()) {
            if (offer.group().contains("<id>" + code + "</id>")) {
                return offer.group();
            }
        }
        throw new AssertionError("позиции " + code + " нет в прайсе");
    }

    // --- база ---

    /** Позиция, заведённая из строки с этим наименованием, — последняя такая. */
    private long partOf(String title) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT max(id) FROM part WHERE title = ?", Long.class, title));
    }

    private void clean() {
        jdbc.update("DELETE FROM import_run");
        jdbc.update("DELETE FROM part_oem");
        jdbc.update("DELETE FROM part_stock");
        jdbc.update("DELETE FROM stock_movement");
        jdbc.update("DELETE FROM part");
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
