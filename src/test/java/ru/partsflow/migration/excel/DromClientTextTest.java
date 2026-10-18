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
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import ru.partsflow.migration.excel.DromTransferFile.Column;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
 * Текст объявления клиента доезжает до прайса Дрома дословно (задача 0275):
 * файл переноса → импорт через HTTP, как экран «Импорт» → прайс по ссылке
 * выгрузки → сравнение по полям.
 *
 * <p>Требование владельца продукта от 13 октября 2026, дословно: «мы должны
 * повторить объявление клиента на сто процентов. Со всеми фото, со всеми
 * описаниями клиента и со всеми полями что есть. Это обязательно. И именно
 * в том виде как это выглядит у него».
 *
 * <p><b>Почему насквозь, а не только писатель прайса.</b> Описание теряется
 * на любом из трёх шагов: импорт клал его во внутреннее примечание, прайс
 * чистил заголовок и собирал описание сам. Проверка одного шага оставила бы
 * два других непроверенными.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DromClientTextTest extends PostgresTestBase {

    private static final String TENANT = "t_000976";
    private static final int REGISTRY_ID = 976;
    private static final String COMPANY = "dromtext";

    /** Описание с переводом строки и с ошибкой клиента — карточка № 125696381. */
    private static final String TWO_LINES =
            "Подходит на автомобиль с левым и правым рулем.\nХорошее состоянии.";

    /**
     * Длиннее 4 000 знаков, с переводами строк и двойным пробелом внутри:
     * предел Дрома — вопрос площадке (задача 0280), и резать за неё нельзя.
     */
    private static final String LONG = "Без пробега по РФ\nПробег 118 422 км\n\n"
            + "Цена  за голый мотор. ".repeat(250) + "Звоните.";

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
                VALUES (?, ?, 'Разборка с Дрома: текст', ?)""", REGISTRY_ID, TENANT, COMPANY);
        warehouseId = inTenant(() -> {
            // Объявление, номер которого уже есть, второй позицией не заводится
            // (задача 0274): без очистки повторный прогон видел бы «уже у позиции».
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

    @Test
    @DisplayName("Файл из трёх объявлений → прайс: заголовок и описание клиента дословно")
    void clientTextReachesThePriceVerbatim() throws Exception {
        // Три строки критерия 4: описание с переводом строки; описания нет
        // (рулевая рейка № 131811260 — поля на странице нет вовсе); в заголовке
        // «(б/у)» и сокращения, которые чистка вырезала бы. Четвёртая — контроль:
        // строка без номера объявления — не текст клиента, и с ней прайс
        // обязан поступать по-прежнему, иначе зелень выше ничего не значит.
        List<Map<String, String>> rows = List.of(
                Map.of("Номер объявления Дрома", "125696381",
                        "Наименование", "Карданный вал передний (Пробег 130т. км)Toyota Land Cruiser Prado 120",
                        "Описание", TWO_LINES),
                Map.of("Номер объявления Дрома", "131811260",
                        "Наименование", "Рулевая рейка ( правый руль 110т. км) Toyota Land Cruiser Prado 120",
                        "Описание", ""),
                Map.of("Номер объявления Дрома", "107112187",
                        "Наименование", "Двигатель (б/у) перед. лев. 4M41  Mitsubishi Pajero 4",
                        "Описание", LONG),
                Map.of("Номер объявления Дрома", "",
                        "Наименование", "Фара (б/у) перед. лев. Toyota Camry",
                        "Описание", ""));
        byte[] file = file(rows);

        JsonNode preview = preview(file);
        assertThat(preview.path("detected").path("DESCRIPTION").asInt(-1))
                .as("«Описание» не сопоставлено описанию объявления — экран показал бы его "
                        + "«не переносится» либо примечанием")
                .isEqualTo(titles().indexOf("Описание"));
        assertThat(preview.path("detected").has("NOTE"))
                .as("описание объявления ушло во внутреннее примечание")
                .isFalse();

        JsonNode report = load(file, preview.path("detected"));
        assertThat(report.path("imported").asInt())
                .as("загружено не всё: %s", report.path("skipped"))
                .isEqualTo(rows.size());

        // Публикация и категория — не предмет проверки: у переехавшей позиции
        // их ставят свои задачи (0278, «Выгружать»), а прайс без них позицию
        // не возьмёт вовсе.
        inTenant(() -> jdbc.update("UPDATE part SET category_id = 1, is_published = true"));

        assertThat(column("SELECT description FROM part WHERE drom_ad_number = ?", "107112187"))
                .as("длинное описание обрезано или нормализовано при импорте")
                .isEqualTo(LONG);

        Map<String, Element> offers = offers(price());

        Element shaft = offerOf(offers, "125696381");
        assertThat(text(shaft, "name")).isEqualTo(rows.get(0).get("Наименование"));
        assertThat(text(shaft, "description"))
                .as("описание клиента дописано или потеряло перевод строки")
                .isEqualTo(TWO_LINES);

        Element rack = offerOf(offers, "131811260");
        assertThat(text(rack, "name"))
                .as("пробел после скобки в заголовке клиента потерян")
                .isEqualTo(rows.get(1).get("Наименование"));
        assertThat(text(rack, "description"))
                .as("у клиента описания нет, а прайс его собрал")
                .isNull();

        Element engine = offerOf(offers, "107112187");
        assertThat(text(engine, "name"))
                .as("из заголовка клиента вычищено «(б/у)» или сокращения")
                .isEqualTo(rows.get(2).get("Наименование"));
        assertThat(text(engine, "description")).isEqualTo(LONG);

        Element ours = offers.get(inTenant(() -> jdbc.queryForObject(
                "SELECT number::text FROM part WHERE drom_ad_number IS NULL", String.class)));
        assertThat(ours).as("контрольной позиции нет в прайсе").isNotNull();
        assertThat(text(ours, "name"))
                .as("позиция не с Дрома перестала чиститься")
                .isEqualTo("Фара Toyota Camry");
        assertThat(text(ours, "description"))
                .as("позиции не с Дрома перестало собираться описание")
                .isEqualTo("Состояние: б/у.");
    }

    // --- файл ---

    private static List<String> titles() {
        return DromTransferFile.COLUMNS.stream().map(Column::title).toList();
    }

    /** Файл переноса во всех колонках договора; чего в строке нет — пусто или по образцу. */
    private static byte[] file(List<Map<String, String>> rows) throws Exception {
        List<String> titles = titles();
        try (XSSFWorkbook book = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Объявления");
            Row head = sheet.createRow(0);
            for (int c = 0; c < titles.size(); c++) {
                head.createCell(c).setCellValue(titles.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                Map<String, String> values = new HashMap<>(Map.of(
                        "Цена", "16000", "Наличие", ExcelWarehouseImporter.IN_STOCK));
                values.putAll(rows.get(r));
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < titles.size(); c++) {
                    String value = values.getOrDefault(titles.get(c), "");
                    if (!value.isEmpty()) {
                        row.createCell(c).setCellValue(value);
                    }
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    // --- прайс ---

    /** Прайс по ссылке выгрузки — как его забирает площадка. */
    private String price() throws Exception {
        JsonNode feed = json.readTree(mvc.perform(post("/api/marketplace-accounts")
                        .with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","title":"Дром %s","settings":"{}",
                                 "productLine":"PART"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String path = json.readTree(mvc.perform(post("/api/marketplace-accounts/"
                                + feed.path("id").asLong() + "/feed-url")
                        .with(csrf()).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("path").asText();
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Позиции прайса по {@code ordercode} — номеру позиции (задача 0334). Разбор XML, как его прочтёт площадка. */
    private static Map<String, Element> offers(String xml) throws Exception {
        NodeList list = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getElementsByTagName("offer");
        Map<String, Element> byCode = new HashMap<>();
        for (int i = 0; i < list.getLength(); i++) {
            Element offer = (Element) list.item(i);
            byCode.put(text(offer, "ordercode"), offer);
        }
        return byCode;
    }

    private Element offerOf(Map<String, Element> offers, String adNumber) {
        Element offer = offers.get(column(
                "SELECT number::text FROM part WHERE drom_ad_number = ?", adNumber));
        assertThat(offer).as("объявления № %s нет в прайсе", adNumber).isNotNull();
        return offer;
    }

    /** Текст элемента позиции; элемента нет — {@code null}. */
    private static String text(Element offer, String name) {
        NodeList found = offer.getElementsByTagName(name);
        assertThat(found.getLength()).as("элемент <%s> повторён", name).isLessThanOrEqualTo(1);
        return found.getLength() == 0 ? null : found.item(0).getTextContent();
    }

    // --- HTTP, как экран ---

    private JsonNode preview(byte[] file) throws Exception {
        return json.readTree(mvc.perform(multipart("/api/import/excel/preview").file(xlsx(file))
                        .with(csrf()).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Сопоставление уходит тем, что пришло в разборе: человек ничего не выбирал. */
    private JsonNode load(byte[] file, JsonNode detected) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/import/excel");
        request.file(xlsx(file));
        detected.fields().forEachRemaining(e -> request.param(e.getKey(), e.getValue().asText()));
        return json.readTree(mvc.perform(request.with(csrf()).session(login())
                        .param("warehouseId", String.valueOf(warehouseId))
                        .param("requestId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile xlsx(byte[] file) {
        return new MockMultipartFile("file", "perenos-s-droma.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file);
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"owner","password":"пароль"}"""
                                .formatted(COMPANY)))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private String column(String sql, String adNumber) {
        return inTenant(() -> jdbc.queryForObject(sql, String.class, adNumber));
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
