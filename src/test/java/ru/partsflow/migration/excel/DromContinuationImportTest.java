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
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Лист «Продолжение» через HTTP, как это делает экран «Импорт» (задача 0281):
 * значение длиннее ячейки Excel доезжает до базы целиком, а повреждённый лист
 * называется в отчёте импорта.
 *
 * <p><b>Почему через «Фото».</b> Колонка, которую клиент упирает в предел, —
 * «Применимость», а её импорт пока не читает (задача 0278); «Описание» читать
 * научит 0275. Склейка же идёт по названию колонки, до разбора, и одинакова
 * для любой. Из читаемых сегодня длинной бывает только «Фото» — адреса через
 * перевод строки, — и на ней видно ровно то, что ломает неверная склейка:
 * потерянный перевод строки на стыке сливает два адреса в один, обрезанный
 * хвост теряет снимки, части не по порядку переставляют их.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DromContinuationImportTest extends PostgresTestBase {

    private static final String TENANT = "t_000281";
    private static final int REGISTRY_ID = 281;
    private static final String COMPANY = "dromcontinuation";

    private static final String SAMPLE = "/migration/excel/drom-transfer-sample.xlsx";

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
                VALUES (?, ?, 'Разборка с длинной применимостью', ?)""", REGISTRY_ID, TENANT, COMPANY);
        warehouseId = inTenant(() -> {
            // Объявление, номер которого уже есть, второй позицией не заводится
            // (задача 0274): без очистки второй тест видел бы «уже у позиции».
            jdbc.update("DELETE FROM import_run");
            jdbc.update("DELETE FROM part_photo_import");
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
    }

    /**
     * Доказательство задачи: значение длиннее 64 767 знаков (ячейка плюс
     * часть), то есть ячейка и две части или больше, доезжает до базы
     * побайтно — каждый адрес целиком, все и по порядку.
     */
    @Test
    @DisplayName("Значение длиннее ячейки Excel, разрезанное на 2+ части, доезжает до базы целиком")
    void longValueLandsWhole() throws Exception {
        List<String> photos = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        for (int i = 0; value.length() <= 70_000; i++) {
            String url = "https://static.baza.drom.ru/drom/17375201" + String.format("%05d", i) + "_default";
            photos.add(url);
            if (i > 0) {
                value.append('\n');
            }
            value.append(url);
        }
        List<String> chunks = DromContinuationTest.split(value.toString(),
                DromContinuationTest.CELL_MAX, DromContinuationTest.PART_MAX);
        assertThat(chunks).as("образец обязан резаться на ячейку и две части или больше")
                .hasSizeGreaterThan(2);

        // Части на листе в обратном порядке: склейка обязана идти по номеру.
        List<String[]> parts = DromContinuationTest.partsOf(firstAd(), "Фото", chunks);
        java.util.Collections.reverse(parts);
        byte[] file = withContinuation(sample(), chunks.get(0), parts);

        JsonNode report = load(file);

        assertThat(report.path("skipped").size()).as("пропущено: %s", report.path("skipped")).isZero();
        assertThat(report.path("imported").asInt()).isEqualTo(5);
        List<String> queued = inTenant(() -> jdbc.queryForList("""
                SELECT i.url FROM part_photo_import i JOIN part p ON p.id = i.part_id
                 WHERE p.drom_ad_number = ? ORDER BY i.sort_order""", String.class, firstAd()));
        assertThat(String.join("\n", queued))
                .as("значение склеено не побайтно: адресов %d из %d", queued.size(), photos.size())
                .isEqualTo(value.toString());
    }

    @Test
    @DisplayName("Часть без строки и разрыв нумерации названы в отчёте импорта, загрузка идёт")
    void damagedSheetIsNamedInReport() throws Exception {
        String head = "https://static.baza.drom.ru/drom/1_default\n";
        byte[] file = withContinuation(sample(), head, List.of(
                new String[] {firstAd(), "Фото", "1", "https://static.baza.drom.ru/drom/2_default\n"},
                new String[] {firstAd(), "Фото", "3", "https://static.baza.drom.ru/drom/4_default"},
                new String[] {"100000001", "Фото", "1", "https://static.baza.drom.ru/drom/5_default"},
                new String[] {firstAd(), "Цена\\\tскидка", "1", "хвост"}));

        JsonNode report = load(file);

        assertThat(report.path("imported").asInt()).as("повреждённый лист не должен ронять загрузку")
                .isEqualTo(5);
        List<String> reasons = new ArrayList<>();
        report.path("skipped").forEach(s -> reasons.add(s.path("reason").asText()));
        assertThat(reasons).containsExactlyInAnyOrder(
                "Лист «Продолжение»: у объявления № " + firstAd() + " колонка «Фото» — "
                        + "нет части 2 из 3; значение взято до разрыва",
                "Лист «Продолжение»: часть для объявления № 100000001 (колонка «Фото») "
                        + "не имеет строки на листе «Объявления»; пропущена",
                "Лист «Продолжение»: у объявления № " + firstAd() + " колонки «Цена\\\tскидка» "
                        + "на листе «Объявления» нет; часть пропущена");
        assertThat(inTenant(() -> jdbc.queryForList("""
                SELECT i.url FROM part_photo_import i JOIN part p ON p.id = i.part_id
                 WHERE p.drom_ad_number = ? ORDER BY i.sort_order""", String.class, firstAd())))
                .as("значение взято не до разрыва")
                .containsExactly("https://static.baza.drom.ru/drom/1_default",
                        "https://static.baza.drom.ru/drom/2_default");
    }

    // --- файл ---

    private static byte[] sample() throws Exception {
        try (InputStream in = DromContinuationImportTest.class.getResourceAsStream(SAMPLE)) {
            assertThat(in).as("образца %s нет в тестовых ресурсах", SAMPLE).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String firstAd() {
        return "125696381";
    }

    /** Образец, где у первой карточки «Фото» = {@code photoCell}, плюс лист «Продолжение». */
    private static byte[] withContinuation(byte[] file, String photoCell, List<String[]> parts)
            throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // Парсер называет первый лист «Объявления» (у образца имя своё);
            // сообщения называют лист его настоящим именем.
            book.setSheetName(0, "Объявления");
            Sheet sheet = book.getSheetAt(0);
            int photo = -1;
            for (var cell : sheet.getRow(0)) {
                if ("Фото".equals(cell.getStringCellValue())) {
                    photo = cell.getColumnIndex();
                }
            }
            Row first = sheet.getRow(1);
            assertThat(first.getCell(0).getStringCellValue())
                    .as("первая строка образца — не та карточка").isEqualTo(firstAd());
            first.createCell(photo).setCellValue(photoCell);

            Sheet continuation = book.createSheet(DromContinuation.SHEET);
            Row header = continuation.createRow(0);
            for (int i = 0; i < DromContinuation.HEADER.size(); i++) {
                header.createCell(i).setCellValue(DromContinuation.HEADER.get(i));
            }
            for (int r = 0; r < parts.size(); r++) {
                Row row = continuation.createRow(r + 1);
                for (int c = 0; c < parts.get(r).length; c++) {
                    row.createCell(c).setCellValue(parts.get(r)[c]);
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    // --- HTTP, как экран ---

    /** Сопоставление уходит тем, что пришло в разборе: человек ничего не выбирал. */
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

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
