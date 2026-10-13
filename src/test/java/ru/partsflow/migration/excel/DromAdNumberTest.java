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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.CatalogService;
import ru.partsflow.inventory.PartService;
import ru.partsflow.migration.excel.DromTransferFile.Column;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Номер объявления Дрома хранится у позиции (задача 0274).
 *
 * <p>Зачем он владельцу: после переноса клиент должен ответить «какое
 * объявление — какая позиция» и убедиться, что осталось ровно по одному.
 * Поэтому проверяется то, что он увидит и сделает сам: загрузил файл, загрузил
 * его же второй раз, нашёл позицию по номеру, который ему назвали в чате.
 *
 * <p>Схемы свои, не общие с {@link DromTransferFileTest}: у обоих классов
 * остатки и названия в одной схеме мешали бы друг другу.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DromAdNumberTest extends PostgresTestBase {

    private static final String TENANT = "t_000989";
    private static final int REGISTRY_ID = 989;
    private static final String COMPANY = "dromadnumber";

    /** Вторая компания: тот же номер объявления в ней — другая позиция. */
    private static final String OTHER_TENANT = "t_000990";
    private static final int OTHER_REGISTRY_ID = 990;

    private static final String FIRST_AD = "125696381";
    private static final String SECOND_AD = "125977770";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CatalogService catalog;
    @Autowired
    private PartService parts;
    @Autowired
    private ru.partsflow.catalog.PartNameService partNames;

    private final ObjectMapper json = new ObjectMapper();
    private long warehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT, OTHER_TENANT);
    }

    @BeforeEach
    void fixtures() {
        registry(REGISTRY_ID, TENANT, COMPANY);
        registry(OTHER_REGISTRY_ID, OTHER_TENANT, "dromadnumber2");
        warehouseId = inTenant(TENANT, () -> {
            jdbc.update("DELETE FROM import_run");
            jdbc.update("DELETE FROM part_oem");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM part");
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
        inTenant(OTHER_TENANT, () -> jdbc.update("DELETE FROM part"));
    }

    @Test
    @DisplayName("Номер из файла ложится у позиции, а знак «№» и пустое не портят хранение")
    void numberIsStoredAndBlankIsNull() throws Exception {
        byte[] file = file(
                ad(FIRST_AD, "Карданный вал передний"),
                ad("№ " + SECOND_AD, "Фара левая"),
                ad("", "Бампер задний"),
                ad("", "Капот"));

        JsonNode report = load(file);

        assertThat(report.path("imported").asInt())
                .as("загружено не всё: %s", report.path("skipped"))
                .isEqualTo(4);
        assertThat(adOf("Карданный вал передний")).isEqualTo(FIRST_AD);
        assertThat(adOf("Фара левая"))
                .as("знак «№» перед числом дошёл до базы — по такому номеру не найти")
                .isEqualTo(SECOND_AD);
        // Пусто — NULL, а не пустая строка: две позиции без номера не сталкиваются.
        assertThat(adOf("Бампер задний")).isNull();
        assertThat(adOf("Капот")).isNull();
    }

    @Test
    @DisplayName("Повтор файла не заводит вторую позицию и называет, какая позиция занимает номер")
    void repeatedFileNamesTheHolder() throws Exception {
        byte[] file = file(ad(FIRST_AD, "Карданный вал передний"), ad(SECOND_AD, "Фара левая"));
        assertThat(load(file).path("imported").asInt()).isEqualTo(2);
        String firstCode = codeOf(FIRST_AD);
        String secondCode = codeOf(SECOND_AD);

        // Другой ключ запроса: это не повтор того же нажатия, а второй заход с тем же файлом.
        JsonNode again = load(file);

        assertThat(again.path("imported").asInt())
                .as("повтор файла завёл позиции заново — склад клиента удвоился")
                .isZero();
        assertThat(count("SELECT count(*) FROM part")).isEqualTo(2);
        List<String> reasons = again.path("skipped").findValuesAsText("reason");
        assertThat(reasons)
                .as("отказ обязан назвать позицию публичным кодом, а не номером базы")
                .containsExactly(
                        "объявление № " + FIRST_AD + " уже у позиции " + firstCode,
                        "объявление № " + SECOND_AD + " уже у позиции " + secondCode);
        assertThat(again.path("skipped").findValues("row").stream().map(JsonNode::asInt))
                .as("номера строк — как в Excel: заголовок первая, данные со второй")
                .containsExactly(2, 3);
    }

    @Test
    @DisplayName("Один номер дважды в одном файле: вторая строка пропущена, а не роняет загрузку")
    void duplicateInsideOneFile() throws Exception {
        JsonNode report = load(file(
                ad(FIRST_AD, "Карданный вал передний"),
                ad(FIRST_AD, "Карданный вал передний второй"),
                ad(SECOND_AD, "Фара левая")));

        assertThat(report.path("imported").asInt()).isEqualTo(2);
        assertThat(report.path("skipped").findValuesAsText("reason"))
                .singleElement().asString().startsWith("объявление № " + FIRST_AD + " уже у позиции ");
        assertThat(count("SELECT count(*) FROM part")).isEqualTo(2);
    }

    /**
     * Два нажатия «Загрузить» почти одновременно (разные ключи запроса — это
     * два захода, а не повтор одного). Проверка «нет ли уже такого» между
     * чтением и вставкой второго не останавливает, и дубль отбивает только
     * индекс: без ответа на гонку человек получил бы 409 «Операция нарушает
     * целостность данных» на весь файл и пошёл бы нажимать ещё раз.
     */
    @Test
    @DisplayName("Одновременные загрузки одного файла: позиции не задваиваются, отказа нет")
    void concurrentLoadsDoNotDuplicate() throws Exception {
        Ad[] ads = new Ad[40];
        for (int i = 0; i < ads.length; i++) {
            ads[i] = ad(String.valueOf(900000000 + i), "Деталь " + i);
        }
        byte[] file = file(ads);
        // Наименования заводятся до гонки: одновременное заведение нового
        // написания упирается в part_name_uk и отвечает 409 — это отдельная
        // давняя дыра справочника, она не про номер объявления и здесь
        // заслонила бы то, что проверяется.
        inTenant(TENANT, () -> {
            for (Ad each : ads) {
                partNames.resolve(each.title(), null);
            }
            return null;
        });
        int threads = 6;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<JsonNode>> runs = new ArrayList<>();
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            for (int i = 0; i < threads; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    return load(file);
                }));
            }
            go.countDown();
            int imported = 0;
            for (java.util.concurrent.Future<JsonNode> run : runs) {
                imported += run.get().path("imported").asInt();
            }
            assertThat(imported).as("позиций заведено больше или меньше, чем в файле")
                    .isEqualTo(ads.length);
            assertThat(count("SELECT count(*) FROM part")).isEqualTo(ads.length);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Номер не из цифр — строка пропущена со словами, позиция не заведена")
    void nonDigitNumberIsSkipped() throws Exception {
        // Так Excel показывает длинное число, если ячейку не сделали текстом.
        JsonNode report = load(file(ad("1,26E+08", "Карданный вал передний")));

        assertThat(report.path("imported").asInt()).isZero();
        assertThat(report.path("skipped").findValuesAsText("reason"))
                .singleElement().asString().contains("номер объявления Дрома не из цифр")
                .contains("1,26E+08");
        assertThat(count("SELECT count(*) FROM part")).isZero();
    }

    @Test
    @DisplayName("Позицию находят по номеру объявления: на складе и у продавца, но не по куску")
    void searchFindsByAdNumber() throws Exception {
        load(file(ad(FIRST_AD, "Карданный вал передний"), ad(SECOND_AD, "Фара левая")));
        Long driveshaft = partIdByTitle("Карданный вал передний");

        for (String query : new String[] {FIRST_AD, "№" + FIRST_AD, "№ " + FIRST_AD}) {
            assertThat(storefront(query)).as("склад, «%s»", query).containsExactly(driveshaft);
            assertThat(seller(query)).as("продавец, «%s»", query).containsExactly(driveshaft);
        }
        // Правило модуля: два поиска по одному складу находят одно и то же.
        assertThat(storefront("12569638"))
                .as("кусок номера объявления нашёл позицию — подстрочный поиск притащит чужие")
                .isEmpty();
        assertThat(seller("12569638")).isEmpty();
        assertThat(seller("вал " + FIRST_AD))
                .as("цифры внутри фразы номером объявления не считаются")
                .isEmpty();

        // Карточка получает номер той же строкой, что и таблица.
        CatalogService.Row row = inTenant(TENANT, () ->
                catalog.list(FIRST_AD, true, true, List.of(), null, Map.of(), Map.of(),
                        null, false, 0, 50)).rows().get(0);
        assertThat(row.dromAdNumber()).isEqualTo(FIRST_AD);
    }

    @Test
    @DisplayName("Схема: пустая строка и не цифры отбиты, дубль отбит, у другой компании тот же номер можно")
    void schemaGuardsTheColumn() {
        inTenant(TENANT, () -> {
            insertPart("Первая", FIRST_AD);
            return null;
        });

        for (String bad : new String[] {"", " ", "12a", "№1", "1,26E+08"}) {
            assertThatThrownBy(() -> inTenant(TENANT, () -> insertPart("Плохая", bad)))
                    .as("«%s» принято как номер объявления", bad)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> inTenant(TENANT, () -> insertPart("Вторая", FIRST_AD)))
                .as("второй позиции с тем же номером быть не может")
                .isInstanceOf(DataIntegrityViolationException.class);

        // Схема своя у каждой компании: объявление № 125696381 у двух клиентов —
        // две разные позиции, и индекс не должен их связывать.
        inTenant(OTHER_TENANT, () -> insertPart("Чужая", FIRST_AD));
        assertThat(inTenant(OTHER_TENANT, () -> jdbc.queryForObject(
                "SELECT count(*) FROM part WHERE drom_ad_number = ?", Integer.class, FIRST_AD)))
                .isEqualTo(1);
    }

    // --- вспомогательное ---

    private Long insertPart(String title, String ad) {
        return jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, drom_ad_number)
                VALUES (1, ?, 5000, ?) RETURNING id""", Long.class, title, ad);
    }

    private String adOf(String title) {
        return inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT drom_ad_number FROM part WHERE title = ?", String.class, title));
    }

    private String codeOf(String ad) {
        return inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE drom_ad_number = ?", String.class, ad));
    }

    private Long partIdByTitle(String title) {
        return inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT id FROM part WHERE title = ?", Long.class, title));
    }

    private int count(String sql) {
        return inTenant(TENANT, () -> jdbc.queryForObject(sql, Integer.class));
    }

    private List<Long> storefront(String query) {
        return inTenant(TENANT, () -> catalog.list(query, true, true, List.of(), null,
                        Map.of(), Map.of(), null, false, 0, 50)).rows().stream()
                .map(CatalogService.Row::id)
                .toList();
    }

    private List<Long> seller(String query) {
        return inTenant(TENANT, () -> parts.searchAvailable(query, 50)).rows().stream()
                .map(PartService.StockRow::partId)
                .toList();
    }

    private void registry(int id, String schema, String code) {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", id);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка с Дрома', ?)""", id, schema, code);
    }

    /** Строка файла переноса: номер объявления, наименование, цена и количество. */
    private record Ad(String number, String title) {
    }

    private static Ad ad(String number, String title) {
        return new Ad(number, title);
    }

    /**
     * Файл переноса по договору: все 22 колонки заголовка, ячейки текстом.
     * Читаются из них четыре; остальные пусты, как у парсера без данных.
     */
    private static byte[] file(Ad... ads) throws Exception {
        List<String> header = DromTransferFile.COLUMNS.stream().map(Column::title).toList();
        try (XSSFWorkbook book = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Перенос");
            Row head = sheet.createRow(0);
            for (int c = 0; c < header.size(); c++) {
                head.createCell(c).setCellValue(header.get(c));
            }
            for (int r = 0; r < ads.length; r++) {
                Row row = sheet.createRow(r + 1);
                put(row, header, "Номер объявления Дрома", ads[r].number());
                put(row, header, "Наименование", ads[r].title());
                put(row, header, "Цена", "16000");
                put(row, header, "Количество", "1");
                put(row, header, "Наличие", "В наличии");
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    private static void put(Row row, List<String> header, String title, String value) {
        if (!value.isEmpty()) {
            row.createCell(header.indexOf(title)).setCellValue(value);
        }
    }

    /** Загрузка тем путём, каким её делает экран: разбор, затем сопоставление как есть. */
    private JsonNode load(byte[] file) throws Exception {
        MockHttpSession session = login();
        String previewBody = mvc.perform(multipart("/api/import/excel/preview").file(xlsx(file))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode detected = json.readTree(previewBody).path("detected");
        assertThat(detected.has("DROM_AD_NUMBER"))
                .as("экран получил колонку номера объявления несопоставленной")
                .isTrue();

        MockMultipartHttpServletRequestBuilder request = multipart("/api/import/excel");
        request.file(xlsx(file));
        List<String> keys = new ArrayList<>();
        detected.fieldNames().forEachRemaining(keys::add);
        keys.forEach(key -> request.param(key, detected.path(key).asText()));
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

    private <T> T inTenant(String schema, Supplier<T> body) {
        TenantContext.set(schema);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
