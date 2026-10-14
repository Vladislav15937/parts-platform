package ru.partsflow.migration.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import ru.partsflow.migration.excel.DromTransferFile.Column;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * «Гарантия и условия возврата», «Самовывоз» и «Доставка и оплата» из файла
 * переноса с Дрома принимаются и хранятся дословно, видны и правятся на карточке
 * (задача 0285).
 *
 * <p>Требование владельца продукта от 13 октября 2026: «мы должны повторить
 * объявление клиента на сто процентов. Со всеми фото, со всеми описаниями
 * клиента и со всеми полями что есть». Всё идёт через HTTP, как экран:
 * слова отчёта и форма правки закрепляются на ответе, а не на вызове сервиса.
 *
 * <p>Схема своя: соседи по пакету грузят те же номера объявлений.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DromShopTermsTest extends PostgresTestBase {

    private static final String TENANT = "t_000983";
    private static final int REGISTRY_ID = 983;
    private static final String COMPANY = "dromterms";

    private static final String SAMPLE = "/migration/excel/drom-transfer-sample.xlsx";

    private static final String WARRANTY = "Гарантия";
    private static final String PICKUP = "Самовывоз";
    private static final String DELIVERY = "Доставка и оплата";

    private static final String AD_A = "400000001";
    private static final String AD_B = "400000002";

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
                VALUES (?, ?, 'Разборка с Дрома: условия', ?)""", REGISTRY_ID, TENANT, COMPANY);
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

    // --- 2. Дословно ---

    @Test
    @DisplayName("Образец из пяти карточек: три значения в базе побайтно равны ячейкам файла")
    void sampleTermsLandVerbatim() throws Exception {
        byte[] file = sample();
        List<Map<String, String>> rows = sampleRows(file);

        JsonNode report = load(file);
        assertThat(report.path("imported").asInt())
                .as("загружено не всё: %s", report.path("skipped")).isEqualTo(5);

        for (Map<String, String> row : rows) {
            String ad = row.get(DromTransferFile.ANCHOR);
            String[] stored = terms(ad);
            assertThat(stored[0]).as("«Гарантия» № %s доехала не дословно", ad)
                    .isEqualTo(row.get(WARRANTY));
            assertThat(stored[1]).as("«Самовывоз» № %s доехал не дословно", ad)
                    .isEqualTo(row.get(PICKUP));
            assertThat(stored[2]).as("«Доставка и оплата» № %s доехала не дословно", ad)
                    .isEqualTo(row.get(DELIVERY));
        }
        // Образец говорит сам за себя: без этого сверка выше зеленела бы на пустых ячейках.
        assertThat(rows.get(0).get(DELIVERY))
                .as("в образце «Доставка и оплата» — 968 знаков с переводами строк")
                .hasSize(968).contains("\n");
        assertThat(terms(rows.get(0).get(DromTransferFile.ANCHOR))[0])
                .isEqualTo("Обговаривается индивидуально");
        assertThat(terms(rows.get(0).get(DromTransferFile.ANCHOR))[1])
                .isEqualTo("Самовывоз — улица Георгия Исакова 9");
    }

    @Test
    @DisplayName("Пустые строки, двойные пробелы и пустые ячейки: текст не трогается, пусто — NULL")
    void messyTermsAreKeptAndEmptyIsNull() throws Exception {
        String delivery = "Доставка по городу — 300 р.\n\n\nДо ТК  в Барнауле — 300 р.\n"
                + "  с отступом\nПоследняя строка";
        Map<String, String> a = row(AD_A, "Фара левая Toyota Camry");
        a.put(WARRANTY, "Гарантия 14 дней.\n\nВозврат в течение 7 дней");
        a.put(PICKUP, "Самовывоз  —  улица Георгия Исакова 9");
        a.put(DELIVERY, delivery);
        Map<String, String> onlyWarranty = row(AD_B, "Бампер задний Toyota Camry");
        onlyWarranty.put(WARRANTY, "Обговаривается индивидуально");

        JsonNode report = load(file(List.of(a, onlyWarranty), Set.of(), null));
        assertThat(report.path("imported").asInt()).isEqualTo(2);

        assertThat(terms(AD_A)).containsExactly(
                "Гарантия 14 дней.\n\nВозврат в течение 7 дней",
                "Самовывоз  —  улица Георгия Исакова 9", delivery);
        assertThat(terms(AD_B)).as("пустые ячейки — NULL, а не пустая строка")
                .containsExactly("Обговаривается индивидуально", null, null);
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM part_drom_card
                 WHERE warranty = '' OR pickup = '' OR delivery_payment = ''""", Integer.class)))
                .as("пустая строка легла в базу").isZero();
        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "INSERT INTO part_drom_card (part_id, pickup) SELECT id, '  ' FROM part LIMIT 1")))
                .as("схема приняла строку из одних пробелов")
                .hasMessageContaining("part_drom_card_pickup_ck");
    }

    // --- 3. Лист «Продолжение» ---

    @Test
    @DisplayName("«Доставка и оплата» длиннее ячейки Excel приходит склейкой с листа «Продолжение»")
    void longDeliveryIsGluedFromTheContinuationSheet() throws Exception {
        StringBuilder value = new StringBuilder();
        for (int i = 0; value.length() <= 70_000; i++) {
            value.append("Пункт доставки № ").append(i).append(" — по договорённости\n");
        }
        String delivery = value.toString().strip();
        List<String> chunks = DromContinuationTest.split(delivery,
                DromContinuationTest.CELL_MAX, DromContinuationTest.PART_MAX);
        assertThat(chunks).as("значение обязано резаться на ячейку и две части").hasSizeGreaterThan(2);

        Map<String, String> a = row(AD_A, "Фара левая Toyota Camry");
        a.put(DELIVERY, chunks.get(0));
        byte[] file = file(List.of(a), Set.of(), DromContinuationTest.partsOf(AD_A, DELIVERY, chunks));

        JsonNode report = load(file);

        assertThat(report.path("skipped")).as("пропущено: %s", report.path("skipped")).isEmpty();
        assertThat(terms(AD_A)[2]).as("склейка не побайтная").isEqualTo(delivery);
    }

    // --- 5 и 7. Повтор файла ---

    @Test
    @DisplayName("Файл без колонок, затем с ними: блоки заполнены, остальное прежнее, отметка для площадки не двинулась")
    void secondLoadFillsTermsWithoutMovingTheFeedMark() throws Exception {
        Map<String, String> a = termsRow(AD_A, "Фара левая Toyota Camry");
        Map<String, String> b = termsRow(AD_B, "Бампер задний Toyota Camry");
        List<Map<String, String>> rows = List.of(a, b);

        load(file(rows, Set.of(WARRANTY, PICKUP, DELIVERY), null));
        assertThat(terms(AD_A)).containsOnlyNulls();
        Map<String, Object> before = partRows();

        JsonNode second = load(file(rows, Set.of(), null));

        JsonNode repeat = second.path("repeat");
        assertThat(second.path("imported").asInt()).isZero();
        assertThat(repeat.path("refilled").asInt()).as("%s", repeat).isEqualTo(2);
        assertThat(repeat.path("fields").findValuesAsText("field"))
                .containsExactly("Гарантия и условия возврата", "Самовывоз", "Доставка и оплата");
        assertThat(terms(AD_A)).containsExactly(
                a.get(WARRANTY), a.get(PICKUP), a.get(DELIVERY));
        assertThat(terms(AD_B)).containsExactly(
                b.get(WARRANTY), b.get(PICKUP), b.get(DELIVERY));
        assertThat(partRows()).as("повтор тронул саму позицию").isEqualTo(before);
        assertThat(feedMarks())
                .as("дозаполнение блоков двинуло отметку «позиция изменилась» — в дельту Дрома "
                        + "уехали бы позиции без изменений для площадки")
                .isZero();

        // Контроль: дозаполнение другого поля отметку по-прежнему ставит.
        inTenant(() -> jdbc.update("UPDATE part SET description = NULL WHERE drom_ad_number = ?", AD_A));
        load(file(rows, Set.of(), null));
        assertThat(feedMarks()).as("отметка при настоящем дозаполнении пропала").isEqualTo(1);
    }

    @Test
    @DisplayName("Блок, поправленный у нас, повтор не перезаписывает и называет расхождение с обоими значениями")
    void ownersEditSurvivesAndIsNamed() throws Exception {
        Map<String, String> a = termsRow(AD_A, "Фара левая Toyota Camry");
        byte[] file = file(List.of(a), Set.of(), null);
        load(file);
        long part = partOf(AD_A);

        ObjectNode form = editable(part);
        form.put("pickup", "Самовывоз — склад на Ленина 5");
        update(part, form);

        JsonNode repeat = load(file).path("repeat");

        assertThat(terms(AD_A)[1]).as("правка владельца перезаписана файлом")
                .isEqualTo("Самовывоз — склад на Ленина 5");
        assertThat(repeat.path("discrepancies").findValuesAsText("text")).containsExactly(
                "№ " + AD_A + ", поле «Самовывоз»: в файле «Самовывоз — улица Георгия Исакова 9», "
                        + "у позиции «Самовывоз — склад на Ленина 5» (оставлено как есть)");
        assertThat(repeat.path("refilled").asInt()).isZero();
        assertThat(repeat.path("unchanged").asInt()).isEqualTo(1);
    }

    // --- 4 и 7. Карточка ---

    @Test
    @DisplayName("Карточка: три блока видны, правятся тем же PUT, пустой ввод — NULL, автор в истории, отметка не двигается")
    void cardShowsEditsAndClearsTheTerms() throws Exception {
        Map<String, String> a = termsRow(AD_A, "Фара левая Toyota Camry");
        load(file(List.of(a), Set.of(), null));
        long part = partOf(AD_A);

        ObjectNode form = editable(part);
        assertThat(form.path("warranty").asText()).isEqualTo(a.get(WARRANTY));
        assertThat(form.path("pickup").asText()).isEqualTo(a.get(PICKUP));
        assertThat(form.path("deliveryPayment").asText()).isEqualTo(a.get(DELIVERY));

        // Правка одних блоков отметку не двигает: прайс их пока не несёт. (Форма,
        // сохранённая вовсе без правок, отметку ставит, как и до задачи, — тут её нет.)
        form.put("warranty", "Гарантия 30 дней");
        form.put("deliveryPayment", "Первая строка\n\nТретья строка");
        update(part, form);
        assertThat(terms(AD_A)).containsExactly("Гарантия 30 дней", a.get(PICKUP),
                "Первая строка\n\nТретья строка");
        assertThat(feedMarks())
                .as("правка одних блоков двинула отметку «позиция изменилась»").isZero();

        // Очистка: пустая строка и строка из пробелов приходят в базу NULL, а не пустой строкой.
        form.put("pickup", "   ");
        form.put("warranty", "");
        update(part, form);
        assertThat(terms(AD_A)).containsExactly(null, null, "Первая строка\n\nТретья строка");

        // Автор правки записан слушателем, а не вызовом из сервиса.
        List<String> authors = inTenant(() -> jdbc.queryForList("""
                SELECT m.display_name FROM audit_log a
                  JOIN tenant_member m ON m.id = a.changed_by
                 WHERE a.table_name = 'part_drom_card' AND a.record_id = ?""", String.class, part));
        assertThat(authors).as("правка блоков не записана в журнал изменений").isNotEmpty()
                .containsOnly("Владелец");

        // Лента правок карточки называет блоки словами Дрома.
        JsonNode history = json.readTree(mvc.perform(get("/api/parts/" + part + "/history")
                        .session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> labels = new ArrayList<>();
        history.path("changes").forEach(c -> c.path("fields").forEach(f -> labels.add(f.path("label").asText())));
        assertThat(labels).contains("Гарантия и условия возврата", "Самовывоз", "Доставка и оплата");

        // Форма старой вкладки не знает про блоки и ключей не шлёт: блоки не стираются.
        ObjectNode stale = form.deepCopy();
        stale.remove(List.of("warranty", "pickup", "deliveryPayment"));
        stale.put("note", "с полки 3");
        update(part, stale);
        assertThat(terms(AD_A)).as("форма без ключей блоков стёрла тексты клиента")
                .containsExactly(null, null, "Первая строка\n\nТретья строка");

        // Контроль: правка цены отметку ставит.
        form.put("price", 17500);
        update(part, form);
        assertThat(feedMarks()).isEqualTo(1);
    }

    @Test
    @DisplayName("Первое заполнение блока на карточке (строки ещё нет) видно в ленте правок с автором")
    void firstFillOfTheBlockIsInHistory() throws Exception {
        load(file(List.of(row(AD_A, "Фара левая Toyota Camry")), Set.of(), null));
        long part = partOf(AD_A);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_drom_card WHERE part_id = ?", Integer.class, part)))
                .as("у позиции без блоков в файле строка завелась").isZero();

        ObjectNode form = editable(part);
        assertThat(form.path("warranty").isNull()).as("нет блока — null, не пустая строка").isTrue();
        form.put("warranty", "Гарантия 14 дней");
        update(part, form);

        assertThat(terms(AD_A)).containsExactly("Гарантия 14 дней", null, null);
        JsonNode history = json.readTree(mvc.perform(get("/api/parts/" + part + "/history")
                        .session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> seen = new ArrayList<>();
        history.path("changes").forEach(c -> c.path("fields").forEach(f -> {
            if ("Гарантия и условия возврата".equals(f.path("label").asText())) {
                seen.add(c.path("author").asText() + ": " + f.path("before").asText("—")
                        + " -> " + f.path("after").asText());
            }
        }));
        assertThat(seen).as("первое заполнение блока не попало в ленту правок")
                .containsExactly("Владелец: — -> Гарантия 14 дней");
    }

    // --- Задача 0286: «Вид детали» и «Раздел» как у клиента ---

    private static final String KIND = "Вид детали";
    private static final String SECTION = "Раздел";

    @Test
    @DisplayName("Образец из пяти карточек: «Вид детали» и «Раздел» в базе побайтно равны ячейкам, стрелка « → » на месте")
    void sampleKindAndSectionLandVerbatim() throws Exception {
        byte[] file = sample();
        List<Map<String, String>> rows = sampleRows(file);

        JsonNode report = load(file);
        assertThat(report.path("imported").asInt())
                .as("загружено не всё: %s", report.path("skipped")).isEqualTo(5);

        for (Map<String, String> row : rows) {
            String ad = row.get(DromTransferFile.ANCHOR);
            String[] stored = kindAndSection(ad);
            assertThat(stored[0]).as("«Вид детали» № %s доехал не дословно", ad)
                    .isEqualTo(row.get(KIND));
            assertThat(stored[1]).as("«Раздел» № %s доехал не дословно", ad)
                    .isEqualTo(row.get(SECTION));
        }
        // Без этого сверка выше зеленела бы на пустых ячейках.
        String first = rows.get(0).get(DromTransferFile.ANCHOR);
        assertThat(kindAndSection(first)[0]).isEqualTo("Карданный вал Lexus GX400");
        assertThat(rows).extracting(r -> r.get(SECTION))
                .allSatisfy(v -> assertThat(v).startsWith("Автозапчасти").contains(" → "));
        // Вид клиента — не наше имя вида и не заголовок: это отдельное поле.
        assertThat(rows.get(0).get("Наименование")).isNotEqualTo(rows.get(0).get(KIND));
    }

    @Test
    @DisplayName("Позиция, чьё наименование не совпало с эталоном, всё равно несёт строку вида и раздел")
    void unmatchedNameStillKeepsClientsKind() throws Exception {
        Map<String, String> a = row(AD_A, "Неведомая штуковина 9000");
        a.put(KIND, "Штуковина Lexus GX400");
        a.put(SECTION, "Автозапчасти → Неведомое");

        JsonNode report = load(file(List.of(a), Set.of(), null));

        assertThat(report.path("imported").asInt()).isEqualTo(1);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT part_kind_id FROM part WHERE drom_ad_number = ?", Long.class, AD_A)))
                .as("наименование должно было не сопоставиться с эталоном").isNull();
        assertThat(kindAndSection(AD_A)).containsExactly(
                "Штуковина Lexus GX400", "Автозапчасти → Неведомое");
        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "UPDATE part_drom_card SET drom_kind = '  ', drom_section = ' ' WHERE part_id > 0")))
                .as("схема приняла строку из одних пробелов")
                .hasMessageContaining("part_drom_card_drom_");
    }

    @Test
    @DisplayName("Файл без «Вида детали» и «Раздела», затем с ними: заполнены, остальное прежнее, отметка для площадки не двинулась; поправленное не затирается")
    void secondLoadFillsKindAndSectionAndKeepsEdits() throws Exception {
        Map<String, String> a = row(AD_A, "Фара левая Toyota Camry");
        a.put(KIND, "Фара левая Toyota Camry XV70");
        a.put(SECTION, "Автозапчасти → Электрооборудование");
        Map<String, String> b = row(AD_B, "Бампер задний Toyota Camry");
        b.put(KIND, "Бампер Toyota Camry");
        b.put(SECTION, "Автозапчасти → Кузов");
        List<Map<String, String>> rows = List.of(a, b);

        load(file(rows, Set.of(KIND, SECTION), null));
        assertThat(kindAndSection(AD_A)).containsOnlyNulls();
        Map<String, Object> before = partRows();

        JsonNode repeat = load(file(rows, Set.of(), null)).path("repeat");

        assertThat(repeat.path("refilled").asInt()).as("%s", repeat).isEqualTo(2);
        assertThat(repeat.path("fields").findValuesAsText("field"))
                .containsExactly("Вид детали", "Раздел");
        assertThat(kindAndSection(AD_A)).containsExactly(a.get(KIND), a.get(SECTION));
        assertThat(kindAndSection(AD_B)).containsExactly(b.get(KIND), b.get(SECTION));
        assertThat(partRows()).as("повтор тронул саму позицию").isEqualTo(before);
        assertThat(feedMarks())
                .as("дозаполнение вида и раздела двинуло отметку «позиция изменилась»").isZero();

        // Поправили у нас — повтор не перезаписывает и называет обе строки.
        long part = partOf(AD_A);
        ObjectNode form = editable(part);
        form.put("dromKind", "Фара Toyota Camry (правка)");
        update(part, form);
        JsonNode again = load(file(rows, Set.of(), null)).path("repeat");
        assertThat(kindAndSection(AD_A)[0]).isEqualTo("Фара Toyota Camry (правка)");
        assertThat(again.path("discrepancies").findValuesAsText("text")).containsExactly(
                "№ " + AD_A + ", поле «Вид детали»: в файле «Фара левая Toyota Camry XV70», "
                        + "у позиции «Фара Toyota Camry (правка)» (оставлено как есть)");
    }

    @Test
    @DisplayName("Карточка: «Вид детали» и «Раздел» видны, правятся тем же PUT, пустой ввод — NULL, форма без ключей их не стирает")
    void cardShowsEditsAndClearsKindAndSection() throws Exception {
        Map<String, String> a = row(AD_A, "Фара левая Toyota Camry");
        a.put(KIND, "Фара левая Toyota Camry XV70");
        a.put(SECTION, "Автозапчасти → Электрооборудование");
        load(file(List.of(a), Set.of(), null));
        long part = partOf(AD_A);

        ObjectNode form = editable(part);
        assertThat(form.path("dromKind").asText()).isEqualTo(a.get(KIND));
        assertThat(form.path("dromSection").asText()).isEqualTo(a.get(SECTION));
        assertThat(form.path("section").isNull() || form.path("section").asText().isEmpty())
                .as("«Секция» склада и «Раздел» Дрома — разные поля").isTrue();

        form.put("dromSection", "Автозапчасти → Кузов");
        update(part, form);
        assertThat(kindAndSection(AD_A)).containsExactly(a.get(KIND), "Автозапчасти → Кузов");
        assertThat(feedMarks()).as("правка одних текстов Дрома двинула отметку").isZero();

        // Форма старой вкладки ключей не шлёт: тексты не стираются.
        ObjectNode stale = form.deepCopy();
        stale.remove(List.of("dromKind", "dromSection"));
        stale.put("note", "с полки 3");
        update(part, stale);
        assertThat(kindAndSection(AD_A)).as("форма без ключей стёрла тексты клиента")
                .containsExactly(a.get(KIND), "Автозапчасти → Кузов");

        form.put("dromKind", "   ");
        form.put("dromSection", "");
        update(part, form);
        assertThat(kindAndSection(AD_A)).containsOnlyNulls();

        JsonNode history = json.readTree(mvc.perform(get("/api/parts/" + part + "/history")
                        .session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> labels = new ArrayList<>();
        history.path("changes").forEach(c -> c.path("fields").forEach(f -> labels.add(f.path("label").asText())));
        assertThat(labels).contains("Вид детали на Дроме", "Раздел на Дроме");
    }

    // --- Задача 0287: «Применимость» («Подходит») дословно блоками ---

    private static final String APPLICABILITY = "Применимость";

    /** Список блоков, как его пишет парсер: строка на пункт, части через « | », пустые края внутри. */
    private static final String FITS = "Lexus GX400 | GRJ158 | \n"
            + "Toyota 4Runner | 5 поколение (2009–2013) | TRN285, GRN285\n"
            + "Toyota Hilux Surf | Другие кузова | GRN215, VZN215\n"
            + "Для двигателей | 1GR-FE, 2UZ-FE";

    @Test
    @DisplayName("Образец из пяти карточек: «Применимость» в базе побайтно равна ячейке — с « | », переводами строк и без разбора")
    void sampleApplicabilityLandsVerbatim() throws Exception {
        byte[] file = sample();
        List<Map<String, String>> rows = sampleRows(file);

        JsonNode report = load(file);
        assertThat(report.path("imported").asInt())
                .as("загружено не всё: %s", report.path("skipped")).isEqualTo(5);

        for (Map<String, String> row : rows) {
            String ad = row.get(DromTransferFile.ANCHOR);
            assertThat(applicability(ad)).as("«Применимость» № %s доехала не дословно", ad)
                    .isEqualTo(row.get(APPLICABILITY).isEmpty() ? null : row.get(APPLICABILITY));
        }
        // Без этого сверка выше зеленела бы на пустых ячейках.
        String first = applicability(rows.get(0).get(DromTransferFile.ANCHOR));
        assertThat(first).isNotNull().contains(" | ").contains("\n")
                .contains("Toyota 4Runner | 5 поколение (2009–2013) | TRN285, GRN285");
    }

    @Test
    @DisplayName("«Применимость» длиннее ячейки Excel (≥ 33 000 знаков) приходит склейкой с листа «Продолжение» побайтно")
    void longApplicabilityIsGluedFromTheContinuationSheet() throws Exception {
        StringBuilder value = new StringBuilder();
        for (int i = 0; value.length() < 41_000; i++) {
            value.append("Toyota Land Cruiser Prado ").append(i).append(" | 4 поколение (2002–2005) | GRJ")
                    .append(i).append("\n");
        }
        String text = value.toString().strip();
        assertThat(text.length()).isGreaterThan(33_000);
        List<String> chunks = DromContinuationTest.split(text,
                DromContinuationTest.CELL_MAX, DromContinuationTest.PART_MAX);
        assertThat(chunks).as("значение обязано резаться на ячейку и часть").hasSizeGreaterThan(1);

        Map<String, String> a = row(AD_A, "Фара левая Toyota Camry");
        a.put(APPLICABILITY, chunks.get(0));
        byte[] file = file(List.of(a), Set.of(), DromContinuationTest.partsOf(AD_A, APPLICABILITY, chunks));

        JsonNode report = load(file);

        assertThat(report.path("skipped")).as("пропущено: %s", report.path("skipped")).isEmpty();
        assertThat(applicability(AD_A)).as("склейка не побайтная").isEqualTo(text);
    }

    @Test
    @DisplayName("Применимость текстом не создаёт строк справочной применимости и не входит в поиск")
    void applicabilityTextIsNotTheReferenceApplicability() throws Exception {
        Map<String, String> a = row(AD_A, "Фара левая Toyota Camry");
        a.put(APPLICABILITY, "Мифомобиль ZQXWORD | 1 поколение | ABC1");
        int before = inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_applicability", Integer.class));

        load(file(List.of(a), Set.of(), null));

        assertThat(applicability(AD_A)).contains("ZQXWORD");
        assertThat(inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_applicability", Integer.class)))
                .as("текст применимости создал строки справочной применимости").isEqualTo(before);
        JsonNode found = json.readTree(mvc.perform(get("/api/parts/search").param("q", "ZQXWORD")
                        .session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(found).as("поиск нашёл позицию по тексту применимости").isEmpty();
    }

    @Test
    @DisplayName("Файл без «Применимости», затем с ней: заполнена, остальное прежнее, отметка не двинулась; поправленная не затирается, расхождение усечено")
    void secondLoadFillsApplicabilityAndKeepsEdits() throws Exception {
        String huge = ("Toyota 4Runner | 5 поколение (2009–2013) | TRN285, GRN285\n").repeat(700).strip();
        assertThat(huge.length()).isGreaterThan(40_000);
        // Длиннее ячейки Excel: в ячейке начало, остальное — лист «Продолжение».
        List<String> chunks = DromContinuationTest.split(huge,
                DromContinuationTest.CELL_MAX, DromContinuationTest.PART_MAX);
        List<String[]> continuation = DromContinuationTest.partsOf(AD_A, APPLICABILITY, chunks);
        Map<String, String> a = row(AD_A, "Фара левая Toyota Camry");
        a.put(APPLICABILITY, chunks.get(0));
        Map<String, String> b = row(AD_B, "Бампер задний Toyota Camry");
        b.put(APPLICABILITY, FITS);
        List<Map<String, String>> rows = List.of(a, b);

        load(file(rows, Set.of(APPLICABILITY), null));
        assertThat(applicability(AD_A)).isNull();
        Map<String, Object> before = partRows();

        JsonNode repeat = load(file(rows, Set.of(), continuation)).path("repeat");

        assertThat(repeat.path("refilled").asInt()).as("%s", repeat).isEqualTo(2);
        assertThat(repeat.path("fields").findValuesAsText("field")).containsExactly("Применимость");
        assertThat(applicability(AD_A)).isEqualTo(huge);
        assertThat(applicability(AD_B)).isEqualTo(FITS);
        assertThat(partRows()).as("повтор тронул саму позицию").isEqualTo(before);
        assertThat(feedMarks())
                .as("дозаполнение применимости двинуло отметку «позиция изменилась»").isZero();

        // Поправили у нас — повтор не перезаписывает, называет расхождение, значения усечены.
        long part = partOf(AD_A);
        ObjectNode form = editable(part);
        form.put("applicability", huge.replace("TRN285", "TRN999"));
        update(part, form);
        JsonNode again = load(file(rows, Set.of(), continuation));
        assertThat(applicability(AD_A)).isEqualTo(huge.replace("TRN285", "TRN999"));
        JsonNode discrepancy = again.path("repeat").path("discrepancies").get(0);
        assertThat(discrepancy.path("field").asText()).isEqualTo("Применимость");
        assertThat(discrepancy.path("text").asText().length())
                .as("значения применимости в отчёте не усечены — 42 000 знаков уехали бы в ответ")
                .isLessThan(400);
        assertThat(again.toString().length()).isLessThan(5_000);
    }

    @Test
    @DisplayName("Карточка: «Применимость» видна, правится тем же PUT, пустой ввод — NULL, форма без ключа её не стирает, лента называет «Подходит»")
    void cardShowsEditsAndClearsApplicability() throws Exception {
        Map<String, String> a = row(AD_A, "Фара левая Toyota Camry");
        a.put(APPLICABILITY, FITS);
        load(file(List.of(a), Set.of(), null));
        long part = partOf(AD_A);

        ObjectNode form = editable(part);
        assertThat(form.path("applicability").asText()).isEqualTo(FITS);

        String edited = FITS + "\nToyota Camry | 7 поколение | XV70";
        form.put("applicability", edited);
        update(part, form);
        assertThat(applicability(AD_A)).isEqualTo(edited);
        assertThat(feedMarks()).as("правка одной применимости двинула отметку").isZero();

        ObjectNode stale = form.deepCopy();
        stale.remove("applicability");
        stale.put("note", "с полки 3");
        update(part, stale);
        assertThat(applicability(AD_A)).as("форма без ключа стёрла применимость клиента").isEqualTo(edited);

        form.put("applicability", "  \n ");
        update(part, form);
        assertThat(applicability(AD_A)).isNull();

        JsonNode history = json.readTree(mvc.perform(get("/api/parts/" + part + "/history")
                        .session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> labels = new ArrayList<>();
        history.path("changes").forEach(c -> c.path("fields").forEach(f -> labels.add(f.path("label").asText())));
        assertThat(labels).contains("Подходит");
    }

    // --- «Прочие поля» → «Характеристики» (задача 0292) ---

    private static final String OTHER = "Прочие поля";

    /**
     * Ячейка «Прочие поля» строки 466 файла клиента Strikexz (колёса Bridgestone
     * Blizzak DM-Z3 на литых дисках) — как есть: служебные дубли Дрома
     * ({@code model~tireFirmAndModel}, {@code marking [marking]}), кавычка дюйма,
     * «колёс» с «ё». Пятнадцать строк (в задаче сказано «14» — пересчитано по файлу).
     */
    private static final String WHEELS_466 = "model~tireFirmAndModel [model~tireFirmAndModel]: Bridgestone Blizzak DM-Z3\n"
            + "Количество колёс в комплекте [inSetQuantity]: 4 шт\n"
            + "Количество комплектов [quantity]: 1 шт\n"
            + "Год выпуска шин [year]: 2006\n"
            + "Протектор [wheelSeason]: Зимние\n"
            + "Износ [wheelTireWear]: 10 %\n"
            + "marking [marking]: 265/65 R17\n"
            + "Диаметр [marking]: 17\"\n"
            + "Ширина профиля [marking]: 265 мм\n"
            + "Высота профиля [marking]: 65 %\n"
            + "Каркас [marking]: радиальный\n"
            + "Тип [diskType]: Литой\n"
            + "Модель шины [tireFirmAndModel]: Bridgestone Blizzak DM-Z3\n"
            + "Тип шины [predestination]: Обычная\n"
            + "Модель диска [discFirmAndModel]: noname";

    /** Магнитола (строка 211 файла клиента): первые две строки дублируют друг друга. */
    private static final String RADIO = "model [model]: Toyota NHZT-W58\n"
            + "Модель магнитолы [model]: Toyota NHZT-W58\n"
            + "Размер [size]: 2 DIN — 178x100 мм";

    @Test
    @DisplayName("«Прочие поля» ложатся в «Характеристики» побайтно (ячейка клиента, 15 строк); пустая колонка — NULL")
    void characteristicsLandVerbatim() throws Exception {
        assertThat(WHEELS_466.split("\n")).hasSize(15);
        Map<String, String> a = row(AD_A, "Колеса Bridgestone Blizzak DM-Z3 265/65 R17");
        a.put(OTHER, WHEELS_466);
        Map<String, String> b = row(AD_B, "Фара левая Toyota Camry");

        JsonNode report = load(file(List.of(a, b), Set.of(), null));

        assertThat(report.path("imported").asInt()).as("%s", report.path("skipped")).isEqualTo(2);
        assertThat(characteristics(AD_A)).as("характеристики доехали не дословно").isEqualTo(WHEELS_466);
        assertThat(characteristics(AD_B)).as("пустая колонка дала не NULL").isNull();
    }

    @Test
    @DisplayName("Строка парсера «Обложка в выдаче Дрома (isMain): …» в характеристики не переносится, остальные — как есть")
    void coverLineIsNotACharacteristic() throws Exception {
        Map<String, String> a = row(AD_A, "Усилитель звука Zapco ST-4B");
        a.put(OTHER, "Обложка в выдаче Дрома (isMain): https://static.baza.drom.ru/drom/1737520136727_full\n"
                + "Количество каналов [channel]: 4-канальный\n"
                + "Номинальная мощность [nominalPower]: 65 Вт");
        Map<String, String> b = row(AD_B, "Видеорегистратор Nextec");
        b.put(OTHER, "Обложка в выдаче Дрома (isMain): https://static.baza.drom.ru/drom/1_full");

        load(file(List.of(a, b), Set.of(), null));

        assertThat(characteristics(AD_A)).as("в блоке не ровно две характеристики")
                .isEqualTo("Количество каналов [channel]: 4-канальный\n"
                        + "Номинальная мощность [nominalPower]: 65 Вт");
        assertThat(characteristics(AD_B)).as("одна строка обложки — это пусто, а не характеристика").isNull();
    }

    @Test
    @DisplayName("Характеристики не входят в поиск и не трогают справочную применимость")
    void characteristicsAreNotSearched() throws Exception {
        Map<String, String> a = row(AD_A, "Магнитола Toyota");
        a.put(OTHER, "Модель магнитолы [model]: ZQXRADIO-58");
        int before = inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_applicability", Integer.class));

        load(file(List.of(a), Set.of(), null));

        assertThat(characteristics(AD_A)).contains("ZQXRADIO");
        assertThat(inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_applicability", Integer.class)))
                .isEqualTo(before);
        JsonNode found = json.readTree(mvc.perform(get("/api/parts/search").param("q", "ZQXRADIO")
                        .session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(found).as("поиск нашёл позицию по тексту характеристик").isEmpty();
    }

    @Test
    @DisplayName("Файл без «Прочих полей», затем с ними: дозаполнены, отметка не двинулась; поправленные не затираются, расхождение названо")
    void secondLoadFillsCharacteristicsAndKeepsEdits() throws Exception {
        Map<String, String> a = row(AD_A, "Магнитола NHZT-W58");
        a.put(OTHER, RADIO);
        Map<String, String> b = row(AD_B, "Колеса Bridgestone Blizzak DM-Z3 265/65 R17");
        b.put(OTHER, WHEELS_466);
        List<Map<String, String>> rows = List.of(a, b);

        load(file(rows, Set.of(OTHER), null));
        assertThat(characteristics(AD_A)).isNull();
        Map<String, Object> before = partRows();

        JsonNode repeat = load(file(rows, Set.of(), null)).path("repeat");

        assertThat(repeat.path("refilled").asInt()).as("%s", repeat).isEqualTo(2);
        assertThat(repeat.path("fields").findValuesAsText("field")).containsExactly("Характеристики");
        assertThat(characteristics(AD_A)).isEqualTo(RADIO);
        assertThat(characteristics(AD_B)).isEqualTo(WHEELS_466);
        assertThat(partRows()).as("повтор тронул саму позицию").isEqualTo(before);
        assertThat(feedMarks()).as("дозаполнение характеристик двинуло отметку для площадки").isZero();

        long part = partOf(AD_A);
        ObjectNode form = editable(part);
        form.put("characteristics", "Модель магнитолы [model]: Toyota NHZT-W59");
        update(part, form);
        JsonNode again = load(file(rows, Set.of(), null)).path("repeat");
        assertThat(characteristics(AD_A)).isEqualTo("Модель магнитолы [model]: Toyota NHZT-W59");
        assertThat(again.path("discrepancies").findValuesAsText("field")).containsExactly("Характеристики");
    }

    @Test
    @DisplayName("Карточка: «Характеристики» видны, правятся тем же PUT, пустой ввод — NULL, форма без ключа их не стирает, отметка не двигается")
    void cardShowsEditsAndClearsCharacteristics() throws Exception {
        Map<String, String> a = row(AD_A, "Магнитола NHZT-W58");
        a.put(OTHER, RADIO);
        load(file(List.of(a), Set.of(), null));
        long part = partOf(AD_A);

        ObjectNode form = editable(part);
        assertThat(form.path("characteristics").asText()).isEqualTo(RADIO);

        String edited = RADIO + "\nЦвет подсветки: синий";
        form.put("characteristics", edited);
        update(part, form);
        assertThat(characteristics(AD_A)).isEqualTo(edited);
        assertThat(feedMarks()).as("правка одних характеристик двинула отметку").isZero();

        ObjectNode stale = form.deepCopy();
        stale.remove("characteristics");
        stale.put("note", "с полки 3");
        update(part, stale);
        assertThat(characteristics(AD_A)).as("форма без ключа стёрла характеристики клиента").isEqualTo(edited);

        form.put("characteristics", "");
        update(part, form);
        assertThat(characteristics(AD_A)).isNull();

        JsonNode history = json.readTree(mvc.perform(get("/api/parts/" + part + "/history")
                        .session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> labels = new ArrayList<>();
        history.path("changes").forEach(c -> c.path("fields").forEach(f -> labels.add(f.path("label").asText())));
        assertThat(labels).contains("Характеристики");
    }

    private String characteristics(String ad) {
        List<String> found = inTenant(() -> jdbc.query("""
                SELECT c.characteristics
                  FROM part p LEFT JOIN part_drom_card c ON c.part_id = p.id
                 WHERE p.drom_ad_number = ?""",
                (rs, i) -> rs.getString(1), ad));
        assertThat(found).as("позиции № %s нет", ad).hasSize(1);
        return found.get(0);
    }

    // --- «Может подойти» дословно (задача 0293) ---

    private static final String MAY_FIT = "Может подойти";

    /**
     * Блок «Может подойти» в том виде, как его пишет парсер (колонка X файла клиента
     * Strikexz): строка на машину «Марка Модель — КОД, КОД…», без поколений. Первая
     * буква кода во второй строке — кириллическая «К» (у клиента 185 таких ячеек):
     * хранится как пришла.
     */
    private static final String MAY_FIT_PRADO = "Toyota Land Cruiser Prado — KDJ120, KDJ120W, KZJ120, LJ120, LJ150L\n"
            + "Toyota Land Cruiser Prado — КZJ95, КZJ90, RZJ95\n"
            + "Toyota Hilux Surf — KZN185, VZN185";

    @Test
    @DisplayName("«Может подойти» ложится побайтно — переводы строк и кириллическая «К» в коде целы; пустая колонка — NULL")
    void mayFitLandsVerbatim() throws Exception {
        assertThat(MAY_FIT_PRADO).contains("КZJ95");
        Map<String, String> a = row(AD_A, "Фара левая Toyota Land Cruiser Prado");
        a.put(MAY_FIT, MAY_FIT_PRADO);
        Map<String, String> b = row(AD_B, "Фара левая Toyota Camry");

        JsonNode report = load(file(List.of(a, b), Set.of(), null));

        assertThat(report.path("imported").asInt()).as("%s", report.path("skipped")).isEqualTo(2);
        assertThat(mayFit(AD_A)).as("«Может подойти» доехало не дословно").isEqualTo(MAY_FIT_PRADO);
        assertThat(mayFit(AD_B)).as("пустая колонка дала не NULL").isNull();
        assertThat(applicability(AD_A)).as("«Может подойти» легло в «Подходит»").isNull();
    }

    @Test
    @DisplayName("«Может подойти» длиннее ячейки Excel (≥ 33 000 знаков) приходит склейкой с листа «Продолжение» побайтно")
    void longMayFitIsGluedFromTheContinuationSheet() throws Exception {
        StringBuilder value = new StringBuilder();
        for (int i = 0; value.length() < 34_000; i++) {
            value.append("Toyota Land Cruiser Prado — KDJ").append(i).append(", КZJ").append(i).append('\n');
        }
        String text = value.toString().strip();
        assertThat(text.length()).isGreaterThan(33_000);
        List<String> chunks = DromContinuationTest.split(text,
                DromContinuationTest.CELL_MAX, DromContinuationTest.PART_MAX);
        assertThat(chunks).as("значение обязано резаться на ячейку и часть").hasSizeGreaterThan(1);

        Map<String, String> a = row(AD_A, "Фара левая Toyota Land Cruiser Prado");
        a.put(MAY_FIT, chunks.get(0));
        byte[] file = file(List.of(a), Set.of(), DromContinuationTest.partsOf(AD_A, MAY_FIT, chunks));

        JsonNode report = load(file);

        assertThat(report.path("skipped")).as("пропущено: %s", report.path("skipped")).isEmpty();
        assertThat(mayFit(AD_A)).as("склейка не побайтная").isEqualTo(text);
    }

    @Test
    @DisplayName("«Может подойти» не заводит справочной применимости и не входит в поиск")
    void mayFitIsNotTheReferenceApplicability() throws Exception {
        Map<String, String> a = row(AD_A, "Фара левая Toyota Land Cruiser Prado");
        // Настоящая машина справочника: разложи импорт этот блок, как «Подходит», строка бы легла.
        a.put(MAY_FIT, "Toyota Camry — ZQXMAYFIT, ACV40");
        int before = inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_applicability", Integer.class));

        load(file(List.of(a), Set.of(), null));

        assertThat(mayFit(AD_A)).contains("ZQXMAYFIT");
        assertThat(inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_applicability", Integer.class)))
                .as("«Может подойти» завело строки справочной применимости").isEqualTo(before);
        JsonNode found = json.readTree(mvc.perform(get("/api/parts/search").param("q", "ZQXMAYFIT")
                        .session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(found).as("поиск нашёл позицию по тексту «Может подойти»").isEmpty();
    }

    @Test
    @DisplayName("Файл без «Может подойти», затем с ним: дозаполнено, отметка не двинулась; поправленное не затирается, расхождение названо и усечено")
    void secondLoadFillsMayFitAndKeepsEdits() throws Exception {
        String huge = ("Toyota Land Cruiser Prado — KDJ120, KDJ120W, KZJ120, LJ120, LJ150L\n").repeat(200).strip();
        Map<String, String> a = row(AD_A, "Фара левая Toyota Land Cruiser Prado");
        a.put(MAY_FIT, huge);
        Map<String, String> b = row(AD_B, "Бампер задний Toyota Land Cruiser Prado");
        b.put(MAY_FIT, MAY_FIT_PRADO);
        List<Map<String, String>> rows = List.of(a, b);

        load(file(rows, Set.of(MAY_FIT), null));
        assertThat(mayFit(AD_A)).isNull();
        Map<String, Object> before = partRows();

        JsonNode repeat = load(file(rows, Set.of(), null)).path("repeat");

        assertThat(repeat.path("refilled").asInt()).as("%s", repeat).isEqualTo(2);
        assertThat(repeat.path("fields").findValuesAsText("field")).containsExactly("Может подойти");
        assertThat(mayFit(AD_A)).isEqualTo(huge);
        assertThat(mayFit(AD_B)).isEqualTo(MAY_FIT_PRADO);
        assertThat(partRows()).as("повтор тронул саму позицию").isEqualTo(before);
        assertThat(feedMarks()).as("дозаполнение «Может подойти» двинуло отметку для площадки").isZero();

        long part = partOf(AD_A);
        ObjectNode form = editable(part);
        form.put("mayFit", huge.replace("KDJ120W", "KDJ999"));
        update(part, form);
        JsonNode again = load(file(rows, Set.of(), null)).path("repeat");
        assertThat(mayFit(AD_A)).isEqualTo(huge.replace("KDJ120W", "KDJ999"));
        JsonNode discrepancy = again.path("discrepancies").get(0);
        assertThat(discrepancy.path("field").asText()).isEqualTo("Может подойти");
        assertThat(discrepancy.path("text").asText().length())
                .as("значения «Может подойти» в отчёте не усечены").isLessThan(400);
    }

    @Test
    @DisplayName("Карточка: «Может подойти» видно, правится тем же PUT, пустой ввод — NULL, форма без ключа его не стирает, отметка не двигается, лента называет «Может подойти»")
    void cardShowsEditsAndClearsMayFit() throws Exception {
        Map<String, String> a = row(AD_A, "Фара левая Toyota Land Cruiser Prado");
        a.put(MAY_FIT, MAY_FIT_PRADO);
        load(file(List.of(a), Set.of(), null));
        long part = partOf(AD_A);

        ObjectNode form = editable(part);
        assertThat(form.path("mayFit").asText()).isEqualTo(MAY_FIT_PRADO);

        String edited = MAY_FIT_PRADO + "\nLexus GX470 — UZJ120";
        form.put("mayFit", edited);
        update(part, form);
        assertThat(mayFit(AD_A)).isEqualTo(edited);
        assertThat(feedMarks()).as("правка одного «Может подойти» двинула отметку").isZero();

        ObjectNode stale = form.deepCopy();
        stale.remove("mayFit");
        stale.put("note", "с полки 3");
        update(part, stale);
        assertThat(mayFit(AD_A)).as("форма без ключа стёрла «Может подойти» клиента").isEqualTo(edited);

        form.put("mayFit", "");
        update(part, form);
        assertThat(mayFit(AD_A)).isNull();

        JsonNode history = json.readTree(mvc.perform(get("/api/parts/" + part + "/history")
                        .session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> labels = new ArrayList<>();
        history.path("changes").forEach(c -> c.path("fields").forEach(f -> labels.add(f.path("label").asText())));
        assertThat(labels).contains("Может подойти");
    }

    private String mayFit(String ad) {
        List<String> found = inTenant(() -> jdbc.query("""
                SELECT c.may_fit
                  FROM part p LEFT JOIN part_drom_card c ON c.part_id = p.id
                 WHERE p.drom_ad_number = ?""",
                (rs, i) -> rs.getString(1), ad));
        assertThat(found).as("позиции № %s нет", ad).hasSize(1);
        return found.get(0);
    }

    // --- файлы ---

    private static byte[] sample() throws Exception {
        try (InputStream in = DromShopTermsTest.class.getResourceAsStream(SAMPLE)) {
            assertThat(in).as("образца %s нет в тестовых ресурсах", SAMPLE).isNotNull();
            return in.readAllBytes();
        }
    }

    /** Строки образца: заголовок → значение. Читается POI напрямую, мимо импорта. */
    private static List<Map<String, String>> sampleRows(byte[] file) throws Exception {
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = book.getSheetAt(0);
            List<String> header = new ArrayList<>();
            sheet.getRow(0).forEach(c -> header.add(c.getStringCellValue()));
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

    private static Map<String, String> row(String ad, String name) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Номер объявления Дрома", ad);
        row.put("Наименование", name);
        row.put("Цена", "16000");
        row.put("Наличие", ExcelWarehouseImporter.IN_STOCK);
        row.put("Описание", "Описание " + name);
        return row;
    }

    private static Map<String, String> termsRow(String ad, String name) {
        Map<String, String> row = row(ad, name);
        row.put(WARRANTY, "Обговаривается индивидуально");
        row.put(PICKUP, "Самовывоз — улица Георгия Исакова 9");
        row.put(DELIVERY, "Доставка по городу Барнаул — 300 р.\nДо транспортной компании в Барнауле — 300 р."
                + "\n\nОплата при получении " + ad);
        return row;
    }

    /**
     * Файл переноса: колонки договора; колонки из {@code without} убраны вместе
     * с заголовком. {@code continuation} — строки листа «Продолжение».
     */
    private static byte[] file(List<Map<String, String>> rows, Set<String> without,
                               List<String[]> continuation) throws Exception {
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
            if (continuation != null) {
                Sheet side = book.createSheet(DromContinuation.SHEET);
                Row sideHead = side.createRow(0);
                for (int c = 0; c < DromContinuation.HEADER.size(); c++) {
                    sideHead.createCell(c).setCellValue(DromContinuation.HEADER.get(c));
                }
                for (int r = 0; r < continuation.size(); r++) {
                    Row row = side.createRow(r + 1);
                    for (int c = 0; c < continuation.get(r).length; c++) {
                        row.createCell(c).setCellValue(continuation.get(r)[c]);
                    }
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    // --- база ---

    /** {Гарантия, Самовывоз, Доставка и оплата} позиции; нет строки — все три NULL. */
    private String[] terms(String ad) {
        return inTenant(() -> jdbc.query("""
                SELECT c.warranty, c.pickup, c.delivery_payment
                  FROM part p LEFT JOIN part_drom_card c ON c.part_id = p.id
                 WHERE p.drom_ad_number = ?""",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, ad)
                .stream().findFirst().orElseThrow(() -> new AssertionError("позиции № " + ad + " нет")));
    }

    /** {Вид детали, Раздел} как у клиента; нет строки — оба NULL. */
    private String[] kindAndSection(String ad) {
        return inTenant(() -> jdbc.query("""
                SELECT c.drom_kind, c.drom_section
                  FROM part p LEFT JOIN part_drom_card c ON c.part_id = p.id
                 WHERE p.drom_ad_number = ?""",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, ad)
                .stream().findFirst().orElseThrow(() -> new AssertionError("позиции № " + ad + " нет")));
    }

    private String applicability(String ad) {
        List<String> found = inTenant(() -> jdbc.query("""
                SELECT c.applicability
                  FROM part p LEFT JOIN part_drom_card c ON c.part_id = p.id
                 WHERE p.drom_ad_number = ?""",
                (rs, i) -> rs.getString(1), ad));
        assertThat(found).as("позиции № %s нет", ad).hasSize(1);
        return found.get(0);
    }

    private long partOf(String ad) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE drom_ad_number = ?", Long.class, ad));
    }

    private int feedMarks() {
        return inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_change", Integer.class));
    }

    /** Строки {@code part} целиком, без отметок времени: то, что повтор не вправе тронуть. */
    private Map<String, Object> partRows() {
        return inTenant(() -> {
            Map<String, Object> rows = new LinkedHashMap<>();
            for (String row : jdbc.queryForList("""
                    SELECT p.drom_ad_number || ' ' || (to_jsonb(p) - 'updated_at' - 'updated_by')::text
                      FROM part p ORDER BY p.id""", String.class)) {
                rows.put(row.substring(0, row.indexOf(' ')), row);
            }
            return rows;
        });
    }

    // --- HTTP, как экран ---

    private ObjectNode editable(long part) throws Exception {
        return (ObjectNode) json.readTree(mvc.perform(get("/api/parts/" + part + "/editable")
                        .session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void update(long part, ObjectNode form) throws Exception {
        mvc.perform(put("/api/parts/" + part).with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(form)))
                .andExpect(status().isOk());
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
