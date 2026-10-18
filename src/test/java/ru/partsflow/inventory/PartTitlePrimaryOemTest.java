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
import ru.partsflow.migration.bazon.BazonCsvReader;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * В наименование позиции попадает только основной номер производителя (задача 0330).
 *
 * <p>Решение владельца продукта 18.10.2026, ответ «Всё да» на вопрос «класть
 * в наименование только основной номер, как его разделит {@code OemNumbers.primary}»;
 * принцип из 0323 — «у детали один номер производителя, остальное — кросс-номера».
 * До задачи приёмщик, вписавший «8414012530, 8414012531», получал «Фара … 8414012530,
 * 8414012531» — кросс-номер в названии, которое идёт на Дром и в печать.
 *
 * <p>Поверхности — перебором всех мест, где собирается наименование: генератор
 * зовут приёмка и перенос из Bazon (их проверяет этот класс через HTTP), перенос
 * из таблицы и колёса его не зовут (название из файла и из размеров), правка
 * карточки наименование не пересчитывает (0304). Повтор переноса собранное
 * не переписывает. Своя схема ({@code t_000979}, тот же номер в реестре).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartTitlePrimaryOemTest extends PostgresTestBase {

    private static final String TENANT = "t_000979";

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** Без разделителя, «не номер» в начале, без номера вовсе — заголовок прежний. */
    private static final List<String> AS_BEFORE = List.of(
            "8414012530", "84140-12530", "81170 33670", "8518100С", "б/н", "АНАЛОГ, FP-3");

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
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 979");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (979, ?, 'Разборка', 'title0330')""", TENANT);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        inTenant(() -> {
            var found = jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'vladelec0330'",
                    Long.class);
            if (found.isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Пётр Владельцев', 'OWNER', 'vladelec0330', ?)""",
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

    // ------------------------------------------------------------------ приёмка

    @Test
    @DisplayName("Приёмка: «8414012530, 8414012531» — название оканчивается основным, кросс-номер в карточке, а не в названии")
    void intakeTitleCarriesOnlyThePrimary() throws Exception {
        String prefix = intakeTitle(null);

        Long partId = intake("8414012530, 8414012531");
        assertThat(title(partId)).isEqualTo(prefix + " 8414012530");
        assertThat(rows(partId)).as("кросс-номер обязан остаться в карточке")
                .containsExactly("8414012530|true", "8414012531|false");

        Long other = intake("АВ-123С; 9");
        assertThat(title(other)).as("в названии — основной в виде хранения, тот же, что в part_oem")
                .isEqualTo(prefix + " " + rows(other).get(0).replace("|true", ""));
    }

    @Test
    @DisplayName("Приёмка: без разделителя, «не номер» и «б/н» — название прежнее, байт в байт")
    void intakeTitleWithoutSeparatorIsAsBefore() throws Exception {
        String prefix = intakeTitle(null);
        for (String oem : AS_BEFORE) {
            assertThat(title(intake(oem))).as("номер «%s»", oem).isEqualTo(prefix + " " + oem);
        }
    }

    @Test
    @DisplayName("Правка карточки: номер «A, B» делится, а наименование не пересчитывается — как до задачи")
    void cardEditDoesNotRebuildTheTitle() throws Exception {
        Long partId = intake("8414012530");
        String before = title(partId);
        ObjectNode body = json.createObjectNode();
        body.put("oem", "8414012530, 8414012531");
        MvcResult saved = mvc.perform(put("/api/parts/" + partId).with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn();
        assertThat(saved.getResponse().getStatus()).as(text(saved)).isEqualTo(200);
        assertThat(title(partId)).as("правка карточки наименование не пересчитывает (0304)").isEqualTo(before);
    }

    // ------------------------------------------------------------------ перенос из Bazon

    @Test
    @DisplayName("Перенос из Bazon: «A; B» — название оканчивается основным; без разделителя — прежнее; повтор собранное не переписывает")
    void bazonTitleCarriesOnlyThePrimary() throws Exception {
        String run = SEQ.get() + "-" + System.nanoTime();
        StringBuilder catalog = new StringBuilder(
                "\"Номер товара\";\"Запчасть\";\"Марка\";\"Модель\";\"Цена\";\"Номер производителя\";\"Основной (свободно)\";\"Выгружать\"\n");
        catalog.append("\"N-%s\";\"Стартер\";\"Toyota\";\"Camry\";\"1000\";\"\";\"1\";\"да\"\n".formatted(run));
        catalog.append("\"S-%s\";\"Стартер\";\"Toyota\";\"Camry\";\"1000\";\"8414012530; 8414012531\";\"1\";\"да\"\n"
                .formatted(run));
        for (int i = 0; i < AS_BEFORE.size(); i++) {
            catalog.append("\"A-%s-%d\";\"Стартер\";\"Toyota\";\"Camry\";\"1000\";\"%s\";\"1\";\"да\"\n"
                    .formatted(run, i, AS_BEFORE.get(i)));
        }
        bazon(catalog.toString());

        String prefix = title(legacy("N-" + run));
        Long split = legacy("S-" + run);
        assertThat(title(split)).isEqualTo(prefix + " 8414012530");
        assertThat(rows(split)).containsExactly("8414012530|true", "8414012531|false");
        for (int i = 0; i < AS_BEFORE.size(); i++) {
            assertThat(title(legacy("A-" + run + "-" + i))).as("номер «%s»", AS_BEFORE.get(i))
                    .isEqualTo(prefix + " " + AS_BEFORE.get(i));
        }

        // Название, собранное до задачи 0330 с обоими номерами, повтор переноса не трогает:
        // title нет среди дозаполняемых полей, и переписывать хранимое никто не просил.
        String old = prefix + " 8414012530, 8414012531";
        inTenant(() -> jdbc.update("UPDATE part SET title = ? WHERE id = ?", old, split));
        bazon(catalog.toString());
        assertThat(title(split)).as("повтор переноса переписал хранимое наименование").isEqualTo(old);
    }

    // ------------------------------------------------------------------ перенос из таблицы

    @Test
    @DisplayName("Перенос из таблицы: название из файла как есть, и с «A, B» в колонке номера, и на повторе")
    void excelTitleIsAsInFile() throws Exception {
        String ad = String.valueOf(900_000_000L + System.nanoTime() % 100_000_000L);
        String name = "Фара таблицы " + ad + " 8414012530, 8414012531";
        byte[] book = workbook(List.of("Наименование", "Цена", "Кол-во", "Артикул", "Номер объявления"),
                List.of(List.of(name, "6000", "1", "8414012530, 8414012531", ad)));

        JsonNode first = loadExcel(book);
        assertThat(first.path("imported").asInt()).as(first.toString()).isEqualTo(1);
        Long partId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE drom_ad_number = ?", Long.class, ad));
        assertThat(title(partId)).isEqualTo(name);
        assertThat(rows(partId)).containsExactly("8414012530|true", "8414012531|false");

        loadExcel(book);
        assertThat(title(partId)).isEqualTo(name);
    }

    // ------------------------------------------------------------------ помощники

    /** Название позиции, принятой с этим номером (null — без номера). */
    private String intakeTitle(String oem) throws Exception {
        return title(intake(oem));
    }

    private Long intake(String oem) throws Exception {
        String requestId = UUID.randomUUID().toString();
        ObjectNode body = json.createObjectNode();
        body.put("warehouseId", warehouseId);
        body.put("requestId", requestId);
        ObjectNode item = body.putArray("items").addObject();
        item.put("rawName", "генератор приёмки");
        item.put("quantity", 1);
        item.put("price", 5000);
        if (oem != null) {
            item.put("oemNumber", oem);
        }
        MvcResult result = mvc.perform(post("/api/intake/receipts").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(201);
        return inTenant(() -> jdbc.queryForObject("""
                SELECT l.part_id FROM stock_document_line l
                  JOIN stock_document d ON d.id = l.document_id
                 WHERE d.client_request_id = ?""", Long.class, requestId));
    }

    private String title(Long partId) {
        return inTenant(() -> jdbc.queryForObject("SELECT title FROM part WHERE id = ?", String.class, partId));
    }

    private Long legacy(String code) {
        return inTenant(() -> jdbc.queryForObject("SELECT id FROM part WHERE legacy_code = ?", Long.class, code));
    }

    /** Строки part_oem позиции: «raw|is_primary», основной первым, затем по id. */
    private List<String> rows(Long partId) {
        return inTenant(() -> jdbc.queryForList("""
                SELECT raw_number || '|' || is_primary FROM part_oem
                 WHERE part_id = ? ORDER BY is_primary DESC, id""", String.class, partId));
    }

    private JsonNode loadExcel(byte[] book) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/import/excel")
                        .file(new MockMultipartFile("file", "sklad.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", book))
                        .param("NAME", "0").param("PRICE", "1").param("QUANTITY", "2").param("OEM", "3")
                        .param("DROM_AD_NUMBER", "4")
                        .param("warehouseId", String.valueOf(warehouseId))
                        .param("requestId", UUID.randomUUID().toString())
                        .with(csrf()).session(login()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        return json.readTree(text(result));
    }

    private void bazon(String catalog) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/import/bazon")
                        .file(csv("donors", """
                                "Номер донора";"Марка";"Модель";"Год выпуска"
                                """))
                        .file(csv("catalog", catalog))
                        .with(csrf()).session(login()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
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
                    row.createCell(c).setCellValue(values.get(c));
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
                                {"company":"title0330","login":"vladelec0330","password":"пароль"}"""))
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
