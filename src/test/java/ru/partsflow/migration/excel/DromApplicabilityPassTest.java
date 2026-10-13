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
import org.springframework.test.web.servlet.MvcResult;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Блок «Подходит» с Дрома раскладывается в справочную применимость позиции —
 * кнопкой по уже загруженному складу и сам при загрузке файла (задача 0289).
 *
 * <p>Всё через HTTP, как экран: кнопка — {@code POST
 * /api/parts/catalog/applicability/from-drom}, загрузка — {@code /api/import/excel}.
 * Справочник машин настоящий (`catalog/009`): поколения ищутся по нему, а не
 * по выдуманным номерам.
 *
 * <p>Состояние живого клиента воспроизводится так: файл загружен, разложения
 * нет (оно появилось позже загрузки), а разбор наименований поставил
 * «Toyota / Land Cruiser Prado» без поколения и лет.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DromApplicabilityPassTest extends PostgresTestBase {

    private static final String TENANT = "t_000991";
    private static final int REGISTRY_ID = 991;
    private static final String COMPANY = "dromfits";

    private static final String APPLICABILITY = "Применимость";

    private static final String AD_A = "410000001";
    private static final String AD_B = "410000002";
    private static final String AD_C = "410000003";

    /** Живое написание клиента: строка на машину, пункты через «; ». */
    private static final String FITS_A = "Lexus GX400 — GRJ158\n"
            + "Toyota Land Cruiser Prado — 4 поколение, рестайлинг (2013⁠–⁠2017): GRJ150W; "
            + "4 поколение (2009–2013): GRJ150L; Другие кузова: GRJ120\n"
            + "Для двигателей — 1GRFE, 5VZFE";
    /** Написание договора: строка на пункт, части через « | ». */
    private static final String FITS_B = "Toyota 4Runner | 5 поколение (2009–2013) | TRN285, GRN285\n"
            + "Для двигателя | 1GRFE";
    /** Поколения 1950 года в справочнике нет: марка, модель и годы клиента ложатся без него. */
    private static final String FITS_C = "Toyota Land Cruiser Prado — 9 поколение (1950–1955): XJ9";

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

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", REGISTRY_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка с Дрома: применимость', ?)""", REGISTRY_ID, TENANT, COMPANY);
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
    }

    // --- 1, 2, 3, 5, 7, 10: кнопка по загруженному складу ---

    @Test
    @DisplayName("Кнопка: поколения с годами легли, строка из наименования уточнена или снята, "
            + "подтверждённое не тронуто, текст и отметка для площадки прежние, не легшее названо")
    void buttonLaysTheBlockOverTheTitleRows() throws Exception {
        loadAsBeforeTheFeature();
        long a = partOf(AD_A);
        long b = partOf(AD_B);
        long c = partOf(AD_C);
        long prado = model("Toyota", "Land Cruiser Prado");
        long runner = model("Toyota", "4Runner");
        long camry = model("Toyota", "Camry");
        // Разбор наименований: Prado без поколения и лет — у A и у C.
        long titleA = row(a, toyota(), prado, null, false);
        long titleC = row(c, toyota(), prado, null, false);
        // Подтверждено человеком в карточке: другая машина у A и та же модель грубее у B.
        long verifiedA = row(a, toyota(), camry, null, true);
        long verifiedB = row(b, toyota(), runner, null, true);
        List<Map<String, Object>> verifiedBefore = verified();
        Map<Long, String> textBefore = texts();
        List<Map<String, Object>> marksBefore = marks();

        JsonNode report = pressButton();

        assertThat(report.path("parts").asInt()).isEqualTo(3);
        // 2. Подтверждённые — те же строки, и та, что грубее (4Runner у B), не снята и не дополнена.
        assertThat(verified()).as("проход тронул подтверждённое человеком").isEqualTo(verifiedBefore);
        assertThat(exists(verifiedA) && exists(verifiedB)).isTrue();
        assertThat(report.path("added").asInt()).as("A — два поколения, B — одно").isEqualTo(3);
        assertThat(report.path("refined").asInt()).as("у C строка из наименования дополнена годами")
                .isEqualTo(1);
        assertThat(report.path("removed").asInt()).as("у A строка из наименования снята").isEqualTo(1);
        assertThat(miss(report, "модель не найдена у марки").path("names").get(0).asText())
                .isEqualTo("Lexus GX400");
        assertThat(miss(report, "поколение не найдено").path("names").get(0).asText())
                .isEqualTo("Toyota Land Cruiser Prado — 9 поколение (1950–1955)");

        // 1. Поколение с годами клиента; кузова пункта — в note.
        assertThat(rowsOf(a)).containsExactlyInAnyOrder(
                "Toyota/Camry/-/-/-/да",
                "Toyota/Land Cruiser Prado/2009/2009-2013/Кузова: GRJ150L/нет",
                "Toyota/Land Cruiser Prado/2013/2013-2017/Кузова: GRJ150W/нет");
        // 3. Уточнение, а не дубль: у A строка из наименования снята, у C — та же строка с годами.
        assertThat(exists(titleA)).as("у A осталась строка «Toyota / Land Cruiser Prado» без поколения")
                .isFalse();
        assertThat(rowsOf(c)).containsExactly("Toyota/Land Cruiser Prado/-/1950-1955/Кузова: XJ9/нет");
        assertThat(exists(titleC)).as("уточнение завело новую строку вместо прежней").isTrue();
        assertThat(rowsOf(b)).containsExactlyInAnyOrder(
                "Toyota/4Runner/-/-/-/да",
                "Toyota/4Runner/2009/2009-2013/Кузова: TRN285, GRN285/нет");
        // 10. Текст клиента побайтно прежний.
        assertThat(texts()).as("проход изменил текст «Подходит»").isEqualTo(textBefore);
        // 7. Отметка для площадки не двинулась.
        assertThat(marks()).as("проход поставил отметку «позиция изменилась»").isEqualTo(marksBefore);
    }

    @Test
    @DisplayName("Подтверждённая строка с тем же ключом, что у пункта блока, не дополняется "
            + "годами и кузовами и не снимается рядом с точной")
    void verifiedRowOfTheSameKeyStaysAsIs() throws Exception {
        loadAsBeforeTheFeature();
        long prado = model("Toyota", "Land Cruiser Prado");
        // C: блок даёт Prado без поколения с годами и кузовом — ровно этот ключ.
        long verifiedC = row(partOf(AD_C), toyota(), prado, null, true);
        // A: блок даёт поколения Prado — подтверждённая грубая строка рядом с ними.
        long verifiedA = row(partOf(AD_A), toyota(), prado, null, true);
        List<Map<String, Object>> before = verified();

        JsonNode report = pressButton();

        assertThat(verified()).as("проход дополнил или снял подтверждённое человеком")
                .isEqualTo(before);
        assertThat(exists(verifiedC) && exists(verifiedA)).isTrue();
        assertThat(report.path("refined").asInt()).as("подтверждённую строку посчитали дополненной")
                .isZero();
        assertThat(rowsOf(partOf(AD_C))).containsExactly("Toyota/Land Cruiser Prado/-/-/-/да");
    }

    // --- 4: повтор ---

    @Test
    @DisplayName("Второй проход подряд ничего не заводит, два одновременных нажатия — оба 200 и итог как от одного")
    void repeatAndConcurrentPressesAddNothing() throws Exception {
        loadAsBeforeTheFeature();
        long prado = model("Toyota", "Land Cruiser Prado");
        row(partOf(AD_A), toyota(), prado, null, false);
        row(partOf(AD_C), toyota(), prado, null, false);

        // Одновременно, с первого раза: строк столько же, сколько от одного прохода.
        List<String> statuses = pressTogether();
        assertThat(statuses).as("второе нажатие получило отказ").containsOnly("200");
        List<String> afterConcurrent = allRows();
        assertThat(afterConcurrent).hasSize(4);

        JsonNode second = pressButton();
        assertThat(second.path("parts").asInt()).isEqualTo(3);
        assertThat(second.path("added").asInt() + second.path("refined").asInt()
                + second.path("removed").asInt()).as("повтор что-то сделал: %s", second).isZero();
        assertThat(allRows()).isEqualTo(afterConcurrent);
    }

    // --- 1, 7: при загрузке файла ---

    @Test
    @DisplayName("Загрузка файла раскладывает «Подходит» сама; повтор файла не задваивает; дозаполненный текст тоже раскладывается")
    void loadLaysItItself() throws Exception {
        JsonNode first = load(file(List.of(card(AD_A, "Фара левая", FITS_A),
                card(AD_B, "Фара правая", FITS_B)), Set.of()));
        // Итог разложения — в отчёте загрузки, а не только в базе (разбор PR 416).
        JsonNode laid = first.path("applicability");
        assertThat(laid.path("parts").asInt()).as("итога разложения нет в отчёте: %s", first)
                .isEqualTo(2);
        assertThat(laid.path("added").asInt()).isEqualTo(3);
        assertThat(miss(laid, "модель не найдена у марки").path("names").get(0).asText())
                .isEqualTo("Lexus GX400");
        assertThat(rowsOf(partOf(AD_A))).containsExactlyInAnyOrder(
                "Toyota/Land Cruiser Prado/2009/2009-2013/Кузова: GRJ150L/нет",
                "Toyota/Land Cruiser Prado/2013/2013-2017/Кузова: GRJ150W/нет");
        assertThat(marks()).as("загрузка поставила отметку из-за разложения").isEmpty();
        List<String> once = allRows();

        JsonNode again = load(file(List.of(card(AD_A, "Фара левая", FITS_A),
                card(AD_B, "Фара правая", FITS_B)), Set.of()));
        assertThat(allRows()).as("повтор файла задвоил применимость").isEqualTo(once);
        assertThat(again.path("applicability").isNull() || again.path("applicability").isMissingNode())
                .as("повтор без нового текста отчитался разложением: %s", again).isTrue();

        // Первая загрузка без колонки, вторая с ней — ветка дозаполнения (0282).
        load(file(List.of(card(AD_C, "Бампер", FITS_C)), Set.of(APPLICABILITY)));
        assertThat(rowsOf(partOf(AD_C))).isEmpty();
        load(file(List.of(card(AD_C, "Бампер", FITS_C)), Set.of()));
        assertThat(rowsOf(partOf(AD_C))).containsExactly(
                "Toyota/Land Cruiser Prado/-/1950-1955/Кузова: XJ9/нет");
    }

    // --- 8: порциями ---

    @Test
    @DisplayName("Склад больше пачки проходится целиком: курсор переходит через границу пачки")
    void passCrossesTheBatch() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            rows.add(card(String.valueOf(420_000_000 + i), "Фара " + i, FITS_B));
        }
        load(file(rows, Set.of()));
        inTenant(() -> jdbc.update("DELETE FROM part_applicability"));

        JsonNode report = pressButton();

        assertThat(report.path("parts").asInt()).isEqualTo(205);
        assertThat(report.path("added").asInt()).isEqualTo(205);
    }

    // --- помощники ---

    /** Три позиции с текстом и без разложения — как у клиента, загруженного до этой задачи. */
    private void loadAsBeforeTheFeature() throws Exception {
        load(file(List.of(card(AD_A, "Фара левая", FITS_A), card(AD_B, "Фара правая", FITS_B),
                card(AD_C, "Бампер", FITS_C)), Set.of()));
        inTenant(() -> jdbc.update("DELETE FROM part_applicability"));
        inTenant(() -> jdbc.update("DELETE FROM part_change"));
    }

    private JsonNode pressButton() throws Exception {
        String body = mvc.perform(post("/api/parts/catalog/applicability/from-drom")
                        .with(csrf()).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private List<String> pressTogether() throws Exception {
        MockHttpSession first = login();
        MockHttpSession second = login();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (MockHttpSession session : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    MvcResult result = mvc.perform(post("/api/parts/catalog/applicability/from-drom")
                            .with(csrf()).session(session)).andReturn();
                    return String.valueOf(result.getResponse().getStatus());
                }));
            }
            start.countDown();
            List<String> statuses = new ArrayList<>();
            for (Future<String> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static JsonNode miss(JsonNode report, String reason) {
        for (JsonNode miss : report.path("misses")) {
            if (miss.path("reason").asText().equals(reason)) {
                return miss;
            }
        }
        throw new AssertionError("в отчёте нет причины «" + reason + "»: " + report);
    }

    private long toyota() {
        return jdbc.queryForObject("SELECT id FROM catalog.brand WHERE name = 'Toyota'", Long.class);
    }

    private long model(String brand, String name) {
        return jdbc.queryForObject("""
                SELECT m.id FROM catalog.model m JOIN catalog.brand b ON b.id = m.brand_id
                 WHERE b.name = ? AND m.name = ?""", Long.class, brand, name);
    }

    private long row(long part, long brand, long model, Long generation, boolean verified) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part_applicability (part_id, brand_id, model_id, generation_id, is_verified)
                VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class,
                part, brand, model, generation, verified));
    }

    private boolean exists(long id) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_applicability WHERE id = ?", Integer.class, id)) == 1;
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
                SELECT id || ':' || part_id || ':' || brand_id || ':' || coalesce(model_id::text, '')
                       || ':' || coalesce(generation_id::text, '') || ':'
                       || coalesce(year_from::text, '') || '-' || coalesce(year_to::text, '')
                  FROM part_applicability ORDER BY id""", String.class));
    }

    private List<Map<String, Object>> verified() {
        return inTenant(() -> jdbc.queryForList("""
                SELECT id, part_id, brand_id, model_id, generation_id, modification_id,
                       year_from, year_to, note
                  FROM part_applicability WHERE is_verified ORDER BY id"""));
    }

    private Map<Long, String> texts() {
        Map<Long, String> texts = new LinkedHashMap<>();
        inTenant(() -> jdbc.queryForList(
                "SELECT part_id, applicability FROM part_drom_card ORDER BY part_id"))
                .forEach(r -> texts.put(((Number) r.get("part_id")).longValue(),
                        (String) r.get("applicability")));
        return texts;
    }

    private List<Map<String, Object>> marks() {
        return inTenant(() -> jdbc.queryForList(
                "SELECT part_id, marked_at, claimed_at FROM part_change ORDER BY part_id"));
    }

    private long partOf(String ad) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE drom_ad_number = ?", Long.class, ad));
    }

    private static Map<String, String> card(String ad, String name, String fits) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Номер объявления Дрома", ad);
        row.put("Наименование", name);
        row.put("Цена", "16000");
        row.put("Наличие", ExcelWarehouseImporter.IN_STOCK);
        row.put(APPLICABILITY, fits);
        return row;
    }

    private static byte[] file(List<Map<String, String>> rows, Set<String> without) throws Exception {
        List<String> header = new ArrayList<>(
                DromTransferFile.COLUMNS.stream().map(Column::title).toList());
        header.removeAll(without);
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
