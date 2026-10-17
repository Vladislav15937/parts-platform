package ru.partsflow.inventory;

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
import ru.partsflow.catalog.OemNumbers;
import ru.partsflow.inventory.OemRecount.Note;
import ru.partsflow.inventory.OemRecount.Options;
import ru.partsflow.inventory.OemRecount.Tally;
import ru.partsflow.migration.bazon.BazonCsvReader;
import ru.partsflow.migration.excel.ColumnMapping.Field;
import ru.partsflow.migration.excel.DromTransferFile;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.platform.tenant.TenantSweep;
import ru.partsflow.support.PostgresTestBase;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
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
 * Пересчёт уже сохранённых номеров производителя (задача 0317, решение владельца
 * «4б - б») на настоящей схеме арендатора.
 *
 * <p>Позиции заводятся прямой вставкой в том виде, в каком их оставила запись
 * до 0309: «8518100С» с русской «С» и {@code normalized = 8518100}. Через HTTP так
 * уже не завести — запись чистит номер сама, на то 0309 и делалась.
 *
 * <p>Схема своя ({@code t_000957}, тот же номер в реестре): пересчёт трогает каждую
 * строку {@code part_oem} схемы, и чужие строки в ней сделали бы числа отчёта
 * лотереей.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class OemRecountTest extends PostgresTestBase {

    private static final String TENANT = "t_000957";
    private static final int REGISTRY_ID = 957;
    private static final String COMPANY = "oemrecount0317";

    /** «8518100С» с русской «С». */
    private static final String RU_8518100C = "8518100С";
    /** «А123» с русской «А». */
    private static final String RU_A123 = "А123";
    /** «АНАЛОГ». */
    private static final String ANALOG = "АНАЛОГ";
    private static final String NBSP = " ";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private OemRecount recount;
    @Autowired
    private TenantSweep sweep;
    @Autowired
    private DataSource dataSource;

    private final ObjectMapper json = new ObjectMapper();

    /** Позиция с одной строкой «8518100С». */
    private long doubled;
    /** Позиция с двумя написаниями одного номера и кросс-номерами после. */
    private long twoSpellings;
    /** Невидимые пробелы и табуляция по краям. */
    private long invisible;
    /** Уже латиницей — пересчёт её не касается. */
    private long clean;
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
                VALUES (?, ?, 'Разборка: пересчёт номеров', ?)""", REGISTRY_ID, TENANT, COMPANY);
        inTenant(() -> {
            jdbc.update("DELETE FROM import_run");
            jdbc.update("DELETE FROM part_change");
            jdbc.update("DELETE FROM part_photo_import");
            jdbc.update("DELETE FROM part_oem");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM audit_log WHERE table_name = 'part'");
            jdbc.update("DELETE FROM part");
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'vladelec0317'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Пётр Пересчётов', 'OWNER', 'vladelec0317', ?)""",
                        passwordEncoder.encode("пароль"));
            }
            List<Long> existing = jdbc.queryForList("SELECT id FROM warehouse", Long.class);
            if (existing.isEmpty()) {
                Long branch = jdbc.queryForObject(
                        "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
                warehouseId = jdbc.queryForObject(
                        "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад') RETURNING id",
                        Long.class, branch);
            } else {
                warehouseId = existing.get(0);
            }

            doubled = part("Фара левая");
            oem(doubled, RU_8518100C, "8518100", true);
            oem(doubled, RU_A123, "123", false);
            oem(doubled, ANALOG, "", false);

            twoSpellings = part("Бампер передний");
            oem(twoSpellings, RU_8518100C, "8518100", true);
            oem(twoSpellings, "B-1", "B1", false);
            oem(twoSpellings, "8518100C", "8518100C", false);
            oem(twoSpellings, "C-2", "C2", false);

            invisible = part("Стартер");
            oem(invisible, NBSP + "28100-0D030​", "281000D030", true);
            oem(invisible, "\t52119-33660 ", "5211933660", false);

            clean = part("Генератор");
            oem(clean, "27060-0H100", "270600H100", true);
            return null;
        });
    }

    @Test
    @DisplayName("Проверка ничего не пишет и печатает те же числа, что потом сделает --apply")
    void dryRunWritesNothingAndPredictsApply() {
        List<List<Object>> before = rows();
        long journalBefore = count("SELECT count(*) FROM audit_log");
        long marksBefore = count("SELECT count(*) FROM part_change");

        Tally dry = one(recount.run(new Options(false, TENANT, true)));

        assertThat(rows()).as("проверка изменила part_oem").isEqualTo(before);
        assertThat(count("SELECT count(*) FROM audit_log")).isEqualTo(journalBefore);
        assertThat(count("SELECT count(*) FROM part_change")).isEqualTo(marksBefore);
        assertThat(dry.partsChanged).isEqualTo(3);
        assertThat(dry.rowsUpdated).as("«8518100С», «А123», основная бампера, две строки стартера")
                .isEqualTo(5);
        assertThat(dry.rowsMerged).as("латинское написание у бампера").isEqualTo(1);
        assertThat(dry.noted(Note.NOT_A_NUMBER)).isEqualTo(1);
        assertThat(dry.feedMarks).isEqualTo(3);

        Tally applied = one(recount.run(new Options(true, TENANT, true)));
        assertThat(applied.partsChanged).isEqualTo(dry.partsChanged);
        assertThat(applied.rowsUpdated).isEqualTo(dry.rowsUpdated);
        assertThat(applied.rowsMerged).isEqualTo(dry.rowsMerged);
        assertThat(applied.feedMarks).isEqualTo(dry.feedMarks);
        assertThat(applied.notes).isEqualTo(dry.notes);
        assertThat(applied.restore()).isEqualTo(dry.restore());
    }

    @Test
    @DisplayName("Проверка — транзакция только на чтение: запись в ней отбивает сама база")
    void dryRunIsReadOnlyByConstruction() {
        List<TenantSweep.Outcome<Integer>> outcomes = sweep.run("probe-0317", false, TENANT,
                (template, tenant) -> template.update("UPDATE part SET note = 'проба'"));
        assertThat(outcomes).singleElement()
                .satisfies(o -> assertThat(o.problem()).contains("read-only"));
        assertThat(count("SELECT count(*) FROM part WHERE note = 'проба'")).isZero();
    }

    @Test
    @DisplayName("После --apply каждая строка — clean и normalize; кроме «не номер»; повтор ничего не меняет")
    void applyHoldsTheInvariantAndIsIdempotent() {
        one(recount.run(new Options(true, TENANT, true)));

        for (List<Object> row : rows()) {
            String raw = (String) row.get(2);
            String normalized = (String) row.get(3);
            if (raw.equals(ANALOG)) {
                assertThat(normalized).as("«не номер» не тронут").isEmpty();
                continue;
            }
            assertThat(raw).as("raw_number строки %s", row.get(0)).isEqualTo(OemNumbers.clean(raw));
            assertThat(normalized).as("normalized строки %s («%s»)", row.get(0), raw)
                    .isEqualTo(OemNumbers.normalize(raw));
        }
        assertThat(numbers(doubled)).containsExactly("8518100C", "A123", ANALOG);
        assertThat(numbers(invisible)).containsExactly("28100-0D030", "52119-33660");
        assertThat(numbers(clean)).containsExactly("27060-0H100");

        Tally again = one(recount.run(new Options(true, TENANT, true)));
        assertThat(again.nothingToDo()).as("второй --apply что-то изменил").isTrue();
        assertThat(again.rowsUpdated + again.rowsMerged).isZero();
        Tally dryAfter = one(recount.run(new Options(false, TENANT, true)));
        assertThat(dryAfter.partsChanged).isZero();
        assertThat(dryAfter.noted(Note.NOT_A_NUMBER)).as("«не номер» называется и после").isEqualTo(1);
    }

    @Test
    @DisplayName("Два написания: одна строка, основная цела, порядок кросс-номеров прежний")
    void twoSpellingsBecomeOne() {
        List<Long> idsBefore = inTenant(() -> jdbc.queryForList(
                "SELECT id FROM part_oem WHERE part_id = ? ORDER BY id", Long.class, twoSpellings));
        one(recount.run(new Options(true, TENANT, true)));

        assertThat(numbers(twoSpellings)).containsExactly("8518100C", "B-1", "C-2");
        assertThat(count("SELECT count(*) FROM part_oem WHERE part_id = " + twoSpellings + " AND is_primary"))
                .isEqualTo(1);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT raw_number FROM part_oem WHERE part_id = ? AND is_primary", String.class, twoSpellings)))
                .isEqualTo("8518100C");
        List<Long> idsAfter = inTenant(() -> jdbc.queryForList(
                "SELECT id FROM part_oem WHERE part_id = ? ORDER BY id", Long.class, twoSpellings));
        assertThat(idsAfter).as("номера строк не перевыдаются, слитая пропадает")
                .containsExactly(idsBefore.get(0), idsBefore.get(1), idsBefore.get(3));
    }

    @Test
    @DisplayName("Поиск «8518100C» латиницей и «8518100С» русской находит позицию — после пересчёта, не до")
    void searchFindsInBothSpellings() throws Exception {
        String code = inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, doubled));
        MockHttpSession session = login();
        // Набранный номер подстрокой («8518100») находит и сейчас; приведённым видом
        // по «8518100C» — нет: в normalized лежит «8518100».
        assertThat(codes(session, "8518100C")).as("до пересчёта").doesNotContain(code);

        one(recount.run(new Options(true, TENANT, true)));

        assertThat(codes(session, "8518100C")).contains(code);
        assertThat(codes(session, RU_8518100C)).contains(code);
    }

    @Test
    @DisplayName("Отчёт восстанавливает каждую изменённую и слитую строку как была")
    void restoreFromReportReturnsEveryRow() {
        List<List<Object>> before = rows();
        Tally tally = one(recount.run(new Options(true, TENANT, true)));
        assertThat(rows()).isNotEqualTo(before);

        for (String sql : tally.restore()) {
            jdbc.update(sql);
        }
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    @DisplayName("Журнал позиции называет было и стало; отметки для площадки — только с ключом")
    void journalAndFeedMarks() {
        one(recount.run(new Options(true, TENANT, false)));
        assertThat(count("SELECT count(*) FROM part_change")).as("--no-feed-marks").isZero();
        Map<String, Object> entry = inTenant(() -> jdbc.queryForMap("""
                SELECT old_value ->> 'oem' AS was, new_value ->> 'oem' AS now,
                       old_value ->> 'crosses' AS crosses_was, changed_by
                  FROM audit_log WHERE table_name = 'part' AND record_id = ?""", doubled));
        assertThat(entry.get("was")).isEqualTo(RU_8518100C);
        assertThat(entry.get("now")).isEqualTo("8518100C");
        assertThat(entry.get("crosses_was")).isEqualTo(RU_A123 + ", " + ANALOG);
        assertThat(entry.get("changed_by")).as("служебный пересчёт — без автора").isNull();

        jdbc.update(inSchema("UPDATE %s.part_oem SET raw_number = ?, normalized = '8518100'"
                + " WHERE part_id = ? AND is_primary"), RU_8518100C, doubled);
        one(recount.run(new Options(true, TENANT, true)));
        assertThat(inTenant(() -> jdbc.queryForList("SELECT part_id FROM part_change", Long.class)))
                .containsExactly(doubled);
    }

    @Test
    @DisplayName("Позицию держит правка карточки — она не тронута, остальные пересчитаны, повтор доделывает")
    void lockedPartIsSkippedNotOverwritten() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            other.createStatement().execute("SET LOCAL search_path TO " + TENANT);
            try (PreparedStatement lock = other.prepareStatement("SELECT id FROM part WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, doubled);
                lock.executeQuery().close();
            }
            Tally first = one(recount.run(new Options(true, TENANT, true)));
            assertThat(first.busy).isEqualTo(1);
            assertThat(first.partsChanged).isEqualTo(2);
            assertThat(numbers(doubled)).as("занятая позиция переписана").startsWith(RU_8518100C);
            other.rollback();
        }
        Tally second = one(recount.run(new Options(true, TENANT, true)));
        assertThat(second.partsChanged).isEqualTo(1);
        assertThat(numbers(doubled)).startsWith("8518100C");
    }

    @Test
    @DisplayName("Повтор файла переноса по пересчитанной позиции не называет номер расхождением")
    void repeatLoadSeesNoDiscrepancyAfterRecount() throws Exception {
        byte[] file = dromFile("400000317", "Фара правая Toyota", RU_8518100C);
        load(file);
        long part = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE drom_ad_number = '400000317'", Long.class));
        // Каким этот номер оставила бы запись до 0309.
        jdbc.update(inSchema("UPDATE %s.part_oem SET raw_number = ?, normalized = '8518100'"
                + " WHERE part_id = ? AND is_primary"), RU_8518100C, part);

        JsonNode before = load(file).path("repeat");
        assertThat(before.path("discrepancies").findValuesAsText("text"))
                .as("без пересчёта повтор называет номер расхождением")
                .anySatisfy(text -> assertThat(text).contains(RU_8518100C));

        one(recount.run(new Options(true, TENANT, true)));

        JsonNode after = load(file).path("repeat");
        assertThat(after.path("discrepancyCount").asInt()).as(after.toString()).isZero();
    }

    @Test
    @DisplayName("Перенос из Bazon хранит номер тем же clean, что запись и пересчёт")
    void bazonStoresCleanNumbers() throws Exception {
        String catalog = """
                "Номер товара";"Запчасть";"Цена";"Номер производителя";"Кросс-номера";"Основной (свободно)";"Выгружать"
                "R-1-0317";"Фара базона";"6800";"%s";"%s, B-7";"1";"да"
                """.formatted(RU_8518100C, NBSP + RU_A123);
        mvc.perform(multipart("/api/import/bazon")
                        .file(csv("donors", """
                                "Номер донора";"Марка";"Модель";"Год выпуска"
                                """))
                        .file(csv("catalog", catalog))
                        .with(csrf()).session(login()))
                .andExpect(status().isOk());
        long part = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE legacy_code = 'R-1-0317'", Long.class));
        assertThat(numbers(part)).containsExactly("8518100C", "A123", "B-7");

        Tally tally = one(recount.run(new Options(false, TENANT, true)));
        assertThat(tally.lines).as("перенос из Bazon оставил работу пересчёту")
                .noneMatch(line -> line.startsWith("позиция " + part + ","));
    }

    // ------------------------------------------------------------------ помощники

    private static Tally one(List<TenantSweep.Outcome<Tally>> outcomes) {
        assertThat(outcomes).singleElement().satisfies(o -> assertThat(o.problem()).isNull());
        return outcomes.get(0).result();
    }

    private static String inSchema(String sql) {
        return sql.formatted(TENANT);
    }

    private long part(String title) {
        return jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, is_published)
                VALUES (1, ?, 5000, true) RETURNING id""", Long.class, title);
    }

    private void oem(long part, String raw, String normalized, boolean primary) {
        jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary) VALUES (?, ?, ?, ?)",
                part, raw, normalized, primary);
    }

    private List<String> numbers(long part) {
        return inTenant(() -> jdbc.queryForList(
                "SELECT raw_number FROM part_oem WHERE part_id = ? ORDER BY id", String.class, part));
    }

    private List<List<Object>> rows() {
        return inTenant(() -> jdbc.query(
                "SELECT id, part_id, raw_number, normalized, is_primary FROM part_oem ORDER BY id",
                (rs, i) -> List.<Object>of(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getBoolean(5))));
    }

    private long count(String sql) {
        return inTenant(() -> jdbc.queryForObject(sql, Long.class));
    }

    private List<String> codes(MockHttpSession session, String query) throws Exception {
        JsonNode catalog = json.readTree(mvc.perform(get("/api/parts/catalog").session(session)
                        .param("q", query).param("missing", "true").param("size", "200"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> codes = new ArrayList<>();
        catalog.path("rows").forEach(r -> codes.add(r.path("code").asText()));
        return codes;
    }

    private static byte[] dromFile(String ad, String title, String number) throws Exception {
        Map<String, String> row = new LinkedHashMap<>();
        row.put(DromTransferFile.ANCHOR, ad);
        row.put("Наименование", title);
        row.put("Цена", "7000");
        row.put("Наличие", "В наличии");
        row.put("Номер", number);
        List<String> header = DromTransferFile.COLUMNS.stream().map(DromTransferFile.Column::title).toList();
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Объявления");
            Row head = sheet.createRow(0);
            Row line = sheet.createRow(1);
            for (int c = 0; c < header.size(); c++) {
                head.createCell(c).setCellValue(header.get(c));
                String value = row.get(header.get(c));
                if (value != null) {
                    line.createCell(c).setCellValue(value);
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    /** Загрузка тем путём, каким её делает экран: разбор, затем сопоставление как есть. */
    private JsonNode load(byte[] file) throws Exception {
        MockHttpSession session = login();
        JsonNode preview = json.readTree(mvc.perform(multipart("/api/import/excel/preview").file(xlsx(file))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode detected = preview.path("detected");
        MockMultipartHttpServletRequestBuilder request = multipart("/api/import/excel");
        request.file(xlsx(file));
        detected.fieldNames().forEachRemaining(key -> request.param(key, detected.path(key).asText()));
        assertThat(detected.has(Field.OEM.name())).as("колонка «Номер» не сопоставлена").isTrue();
        String body = mvc.perform(request.with(csrf()).session(session)
                        .param("warehouseId", String.valueOf(warehouseId))
                        .param("requestId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private static MockMultipartFile xlsx(byte[] file) {
        return new MockMultipartFile("file", "perenos-s-droma.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file);
    }

    private static MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile(name, name + ".csv", "text/csv",
                content.getBytes(BazonCsvReader.CHARSET));
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"vladelec0317","password":"пароль"}"""
                                .formatted(COMPANY)))
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
