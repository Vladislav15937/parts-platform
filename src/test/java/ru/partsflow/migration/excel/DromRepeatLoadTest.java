package ru.partsflow.migration.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import ru.partsflow.migration.excel.ColumnMapping.Field;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Повторная загрузка файла переноса с Дрома дозаполняет уже перенесённые
 * позиции (задача 0282): перенос клиента в два захода — загрузили сейчас,
 * импорт научился новой колонке, загрузили тот же файл ещё раз.
 *
 * <p>Всё через HTTP, как это делает экран: слова отчёта закрепляются на ответе,
 * а не на вызове сервиса. Файлы собираются здесь же — по договору колонок
 * плюс «Примечание», которое у файла с Дрома сопоставляется руками: так
 * в один перебор попадают все поля, которые повтор дозаполняет.
 *
 * <p>Схема своя: соседи по пакету грузят те же номера объявлений.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DromRepeatLoadTest extends PostgresTestBase {

    private static final String TENANT = "t_000982";
    private static final int REGISTRY_ID = 982;
    private static final String COMPANY = "dromrepeat";

    private static final String NOTE = "Примечание";
    private static final String AD_A = "300000001";
    private static final String AD_B = "300000002";

    /**
     * Какую колонку файла дозаполняет поле и что у позиции от неё меняется.
     * Поле без строки здесь — проверка {@link #everyRefilledFieldIsCovered}
     * краснеет: правило дозаполнения без доказательства не принимается.
     */
    private static final Map<Field, Facet> FACETS = Map.ofEntries(
            Map.entry(Field.CONDITION, new Facet("Состояние", "condition")),
            Map.entry(Field.PRICE, new Facet("Цена", "price")),
            Map.entry(Field.DESCRIPTION, new Facet("Описание", "description")),
            Map.entry(Field.NOTE, new Facet(NOTE, "note")),
            // Блоки карточки Дрома (задача 0285) лежат в part_drom_card, не в part.
            Map.entry(Field.WARRANTY, new Facet("Гарантия", "warranty")),
            Map.entry(Field.PICKUP, new Facet("Самовывоз", "pickup")),
            Map.entry(Field.DELIVERY, new Facet("Доставка и оплата", "delivery_payment")),
            // Вид детали и раздел как у клиента (задача 0286) — туда же.
            Map.entry(Field.DROM_KIND, new Facet("Вид детали", "drom_kind")),
            Map.entry(Field.DROM_SECTION, new Facet("Раздел", "drom_section")),
            // Применимость текстом (задача 0287) — туда же.
            // Из неё же разбор блока считает кузова своей машины (part.own_body_codes,
            // задача 0296): дозаполненная применимость обязана их пересчитать, и это
            // часть того же поля, а не «тронул соседнее». Совпадение с одной загрузкой
            // проверяет сравнение с эталоном ниже.
            Map.entry(Field.APPLICABILITY, new Facet("Применимость", "applicability",
                    Set.of("own_body_codes"))),
            // «Прочие поля» — «Характеристики» дословно (задача 0292) — туда же.
            Map.entry(Field.CHARACTERISTICS, new Facet("Прочие поля", "characteristics")),
            // «Может подойти» дословно (задача 0293) — туда же.
            Map.entry(Field.MAY_FIT, new Facet("Может подойти", "may_fit")),
            // «Дата» объявления Дрома текстом (задача 0298) — туда же.
            Map.entry(Field.DROM_DATE, new Facet("Дата", "ad_date")),
            Map.entry(Field.MANUFACTURER, new Facet("Производитель", "manufacturer")),
            Map.entry(Field.AUTHENTICITY, new Facet("Оригинальность", "authenticity")),
            Map.entry(Field.SIDE_LR, new Facet("Сторона", "side_lr")),
            Map.entry(Field.SIDE_FR, new Facet("Положение", "side_fr")),
            Map.entry(Field.SIDE_UD, new Facet("Верх/низ", "side_ud")),
            Map.entry(Field.OEM, new Facet("Номер", "oem_primary")),
            Map.entry(Field.OEM_SUBSTITUTES, new Facet("Номера замен", "oem_substitutes")),
            Map.entry(Field.PHOTOS, new Facet("Фото", "photos")));

    /** {@code derived} — колонки, которые законно пересчитываются из поля вместе с ним. */
    private record Facet(String column, String key, Set<String> derived) {
        Facet(String column, String key) {
            this(column, key, Set.of());
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ru.partsflow.catalog.PartNameService partNames;

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
                VALUES (?, ?, 'Разборка с Дрома: повтор', ?)""", REGISTRY_ID, TENANT, COMPANY);
        warehouseId = inTenant(() -> {
            clean();
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

    private void clean() {
        jdbc.update("DELETE FROM import_run");
        jdbc.update("DELETE FROM part_change");
        jdbc.update("DELETE FROM part_photo_import");
        jdbc.update("DELETE FROM part_photo");
        jdbc.update("DELETE FROM part_oem");
        jdbc.update("DELETE FROM part_stock");
        jdbc.update("DELETE FROM stock_movement");
        jdbc.update("DELETE FROM audit_log WHERE table_name = 'part'");
        jdbc.update("DELETE FROM part");
    }

    // --- Пункт 1: дозаполнение, перебором по всем полям, которые повтор дозаполняет ---

    static Stream<Field> refilledFields() {
        return ExcelWarehouseImporter.REFILLED.keySet().stream();
    }

    @Test
    @DisplayName("У каждого дозаполняемого поля есть колонка файла и проверка в переборе")
    void everyRefilledFieldIsCovered() {
        assertThat(FACETS.keySet())
                .as("поле дозаполняется, а перебор его не проверяет — допишите FACETS")
                .containsExactlyInAnyOrderElementsOf(ExcelWarehouseImporter.REFILLED.keySet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refilledFields")
    @DisplayName("Файл без колонки, затем с ней: поле дозаполнено, остальное байт в байт прежнее")
    void secondLoadFillsTheColumnTheFirstFileLacked(Field field) throws Exception {
        Facet facet = FACETS.get(field);
        List<Map<String, String>> rows = List.of(rowA(), rowB());

        // Эталон: тот же файл целиком, загруженный один раз.
        load(file(rows, Set.of(), null));
        Map<String, Map<String, Object>> reference = contentByAd();
        inTenant(() -> {
            clean();
            return null;
        });

        JsonNode first = load(file(rows, Set.of(facet.column()), null));
        assertThat(first.path("imported").asInt()).as("первая загрузка: %s", first).isEqualTo(2);
        Map<String, Map<String, Object>> before = stateByAd();

        JsonNode second = load(file(rows, Set.of(), null));
        Map<String, Map<String, Object>> after = stateByAd();

        assertThat(second.path("imported").asInt())
                .as("повтор завёл позиции заново — склад удвоился").isZero();
        assertThat(second.path("skipped")).as("повтор пропустил строки").isEmpty();
        JsonNode repeat = second.path("repeat");
        assertThat(repeat.path("refilled").asInt()).as("дозаполнено позиций: %s", repeat)
                .isEqualTo(2);
        assertThat(repeat.path("unchanged").asInt()).isZero();
        assertThat(repeat.path("discrepancyCount").asInt()).as("%s", repeat).isZero();
        String label = ExcelWarehouseImporter.REFILLED.get(field);
        assertThat(repeat.path("fields").findValuesAsText("field"))
                .as("отчёт по полям").containsExactly(label);
        assertThat(repeat.path("fields").get(0).path("positions").asInt()).isEqualTo(2);
        assertThat(repeat.path("fields").get(0).path("links").asInt())
                .isEqualTo(field == Field.PHOTOS ? 4 : 0);

        for (String ad : List.of(AD_A, AD_B)) {
            assertThat(after.get(ad).get(facet.key()))
                    .as("№ %s: поле «%s» не дозаполнилось", ad, facet.column())
                    .isNotEqualTo(before.get(ad).get(facet.key()))
                    .isEqualTo(reference.get(ad).get(facet.key()));
            Map<String, Object> restBefore = new TreeMap<>(before.get(ad));
            Map<String, Object> restAfter = new TreeMap<>(after.get(ad));
            restBefore.remove(facet.key());
            restAfter.remove(facet.key());
            restBefore.keySet().removeAll(facet.derived());
            restAfter.keySet().removeAll(facet.derived());
            assertThat(restAfter).as("№ %s: повтор тронул не только «%s»", ad, facet.column())
                    .isEqualTo(restBefore);
            assertThat(content(after.get(ad)))
                    .as("№ %s: после двух заходов позиция не та, что после одного", ad)
                    .isEqualTo(reference.get(ad));
        }
    }

    // --- Пункт 2: заполненное не перезаписывается, и разница названа ---

    @Test
    @DisplayName("Цену и описание поправили у нас — повтор их не трогает и называет обе разницы")
    void ownersEditSurvivesAndIsNamed() throws Exception {
        byte[] file = file(List.of(rowA(), rowB()), Set.of(), null);
        load(file);
        long a = partOf(AD_A);
        edit(a, Map.of("price", 17500, "description", "Правлено владельцем",
                "manufacturer", "Depo", "note", "с полки 3", "published", true));

        String key = UUID.randomUUID().toString();
        JsonNode again = load(file, key);

        assertThat(column(a, "price", java.math.BigDecimal.class)).isEqualByComparingTo("17500");
        assertThat(column(a, "description", String.class)).isEqualTo("Правлено владельцем");
        JsonNode repeat = again.path("repeat");
        assertThat(repeat.path("discrepancies").findValuesAsText("text")).containsExactly(
                "№ 300000001, поле «Цена»: в файле «16000», у позиции «17500» (оставлено как есть)",
                "№ 300000001, поле «Описание»: в файле «Фара целая. Без сколов.», "
                        + "у позиции «Правлено владельцем» (оставлено как есть)");
        assertThat(repeat.path("discrepancyCount").asInt()).isEqualTo(2);
        assertThat(repeat.path("unchanged").asInt()).isEqualTo(2);
        assertThat(repeat.path("refilled").asInt()).isZero();

        // Повтор того же нажатия отдаёт тот же отчёт, а не «ничего не было».
        assertThat(load(file, key).path("repeat")).isEqualTo(repeat);
    }

    @Test
    @DisplayName("«Переднее, Заднее» и «Верхнее, Нижнее» заводятся строкой с фото, а в отчёте называются своей парой (задача 0283)")
    void bothSidesLoadAndAreNamedByTheirOwnAxis() throws Exception {
        Map<String, String> both = rowA();
        both.put("Положение", "Переднее, Заднее");
        both.put("Верх/низ", "Нижнее, Верхнее");
        byte[] file = file(List.of(both, rowB()), Set.of(), null);

        JsonNode first = load(file);
        assertThat(first.path("imported").asInt()).isEqualTo(2);
        assertThat(first.path("skipped")).isEmpty();
        long a = partOf(AD_A);
        assertThat(column(a, "side_fr", String.class)).isEqualTo("BOTH");
        assertThat(column(a, "side_ud", String.class)).isEqualTo("BOTH");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_photo_import WHERE part_id = ?", Integer.class, a)))
                .as("фото строки с парой сторон не встали в очередь")
                .isEqualTo(2);

        // Расхождение: у позиции одна сторона, в файле пара. Слово пары у каждой оси своё.
        inTenant(() -> jdbc.update(
                "UPDATE part SET side_fr = 'FRONT', side_ud = 'UPPER' WHERE id = ?", a));
        JsonNode repeat = load(file).path("repeat");
        assertThat(repeat.path("discrepancies").findValuesAsText("text")).containsExactly(
                "№ 300000001, поле «Положение»: в файле «Переднее, Заднее», "
                        + "у позиции «Переднее» (оставлено как есть)",
                "№ 300000001, поле «Верх/низ»: в файле «Верхнее, Нижнее», "
                        + "у позиции «Верхнее» (оставлено как есть)");
    }

    @Test
    @DisplayName("Состояние, которое человек правил, повтор не трогает, даже если оно снова «б/у»")
    void editedConditionIsNotRefilled() throws Exception {
        List<Map<String, String>> rows = List.of(rowA(), rowB());
        load(file(rows, Set.of("Состояние"), null));
        long a = partOf(AD_A);
        Map<String, Object> form = new HashMap<>(Map.of("price", 16000,
                "description", "Фара целая.\nБез сколов.", "manufacturer", "Depo",
                "note", "с полки 3", "published", true));
        form.put("condition", "NEW");
        edit(a, form);
        form.put("condition", "USED");
        edit(a, form);

        JsonNode again = load(file(rows, Set.of(), null));

        assertThat(column(a, "condition", String.class))
                .as("правленое человеком состояние перезаписано файлом").isEqualTo("USED");
        assertThat(column(partOf(AD_B), "condition", String.class))
                .as("неправленое «б/у» — пусто, его дозаполняют").isEqualTo("CONTRACT");
        assertThat(again.path("repeat").path("discrepancies").findValuesAsText("text"))
                .contains("№ 300000001, поле «Состояние»: в файле «новая», у позиции «б/у» "
                        + "(оставлено как есть)");
    }

    @Test
    @DisplayName("Длинные значения в расхождении — первые 80 знаков и «…», и в полях, и в строке")
    void longDiscrepancyValuesAreCut() throws Exception {
        String fileText = "Ф".repeat(100);
        String partText = "П".repeat(120);
        Map<String, String> longA = new LinkedHashMap<>(rowA());
        longA.put("Описание", fileText);
        byte[] file = file(List.of(longA), Set.of(), null);
        load(file);
        long a = partOf(AD_A);
        inTenant(() -> jdbc.update("UPDATE part SET description = ? WHERE id = ?", partText, a));

        JsonNode found = load(file).path("repeat").path("discrepancies");

        assertThat(found.size()).isEqualTo(1);
        JsonNode only = found.get(0);
        String fileCut = "Ф".repeat(80) + "…";
        String partCut = "П".repeat(80) + "…";
        assertThat(only.path("inFile").asText()).as("значение файла не обрезано").isEqualTo(fileCut);
        assertThat(only.path("atPart").asText()).as("значение позиции не обрезано").isEqualTo(partCut);
        assertThat(only.path("text").asText()).isEqualTo("№ " + AD_A + ", поле «Описание»: в файле «"
                + fileCut + "», у позиции «" + partCut + "» (оставлено как есть)");
    }

    // --- Пункт 3: остаток и движение ---

    @Test
    @DisplayName("Повтор не трогает остаток и не пишет движения; другое количество — расхождение")
    void repeatKeepsStock() throws Exception {
        load(file(List.of(rowA(), rowB()), Set.of(), null));
        long a = partOf(AD_A);

        Map<String, String> moreA = new LinkedHashMap<>(rowA());
        moreA.put("Количество", "3");
        JsonNode again = load(file(List.of(moreA, rowB()), Set.of(), null));

        assertThat(column(a, "qty_on_hand", java.math.BigDecimal.class)).isEqualByComparingTo("1");
        assertThat(inTenant(() -> jdbc.queryForList(
                "SELECT movement_type FROM stock_movement WHERE part_id = ?", String.class, a)))
                .as("движение склада по позиции не одно").containsExactly("INTAKE");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM v_stock_discrepancy", Integer.class))).isZero();
        assertThat(again.path("repeat").path("discrepancies").findValuesAsText("text"))
                .containsExactly("№ 300000001, поле «Количество»: в файле «3», у позиции «1» "
                        + "(оставлено как есть)");
    }

    // --- Пункт 4: снимки ---

    @Test
    @DisplayName("Снимки: уже скачанный не заводится второй раз, новый встаёт после всех, главный прежний")
    void photosAreAppendedNotDuplicated() throws Exception {
        load(file(List.of(rowA(), rowB()), Set.of(), null));
        long a = partOf(AD_A);
        // Первый снимок уже скачан (строка очереди остаётся DONE с photo_id),
        // плюс один владелец добавил руками в карточке.
        inTenant(() -> {
            Long main = jdbc.queryForObject("""
                    INSERT INTO part_photo (part_id, s3_key, sort_order, is_main, status)
                    VALUES (?, 'k/main.jpg', 0, true, 'PROCESSED') RETURNING id""", Long.class, a);
            jdbc.update("""
                    UPDATE part_photo_import SET status = 'DONE', photo_id = ?, done_at = now()
                     WHERE part_id = ? AND sort_order = 0""", main, a);
            jdbc.update("""
                    INSERT INTO part_photo (part_id, s3_key, sort_order, is_main, status)
                    VALUES (?, 'k/owner.jpg', 5, false, 'PROCESSED')""", a);
            return null;
        });

        load(file(List.of(rowA(), rowB()), Set.of(), null));
        assertThat(count("SELECT count(*) FROM part_photo_import WHERE part_id = " + a)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM part_photo WHERE part_id = " + a)).isEqualTo(2);

        Map<String, String> withThird = new LinkedHashMap<>(rowA());
        withThird.put("Фото", rowA().get("Фото") + "\nhttps://example.test/a/3.jpg");
        JsonNode again = load(file(List.of(withThird, rowB()), Set.of(), null));

        assertThat(inTenant(() -> jdbc.queryForList("""
                SELECT url || ' ' || sort_order || ' ' || status FROM part_photo_import
                 WHERE part_id = ? ORDER BY sort_order""", String.class, a)))
                .containsExactly("https://example.test/a/1.jpg 0 DONE",
                        "https://example.test/a/2.jpg 1 PENDING",
                        "https://example.test/a/3.jpg 6 PENDING");
        assertThat(inTenant(() -> jdbc.queryForList(
                "SELECT s3_key FROM part_photo WHERE part_id = ? AND is_main", String.class, a)))
                .containsExactly("k/main.jpg");
        assertThat(count("SELECT count(*) FROM part_photo WHERE s3_key = 'k/owner.jpg'")).isEqualTo(1);
        assertThat(again.path("repeat").path("fields").toString())
                .contains("\"field\":\"Снимки\",\"positions\":1,\"links\":1");
    }

    // --- Пункт 5: лист «Продолжение» ---

    @Test
    @DisplayName("Без листа «Продолжение» описание доехало началом — повтор с листом дописывает хвост")
    void continuationCompletesTheCutValue() throws Exception {
        String head = "Начало описания.\n";
        String tail = "Хвост описания: совместимость и состояние.";
        Map<String, String> cut = new LinkedHashMap<>(rowA());
        cut.put("Описание", head);
        load(file(List.of(cut), Set.of(), null));
        long a = partOf(AD_A);
        assertThat(column(a, "description", String.class)).isEqualTo("Начало описания.");

        byte[] whole = file(List.of(cut), Set.of(),
                List.<String[]>of(new String[] {AD_A, "Описание", "1", tail}));
        JsonNode again = load(whole);

        assertThat(column(a, "description", String.class)).isEqualTo(head + tail);
        assertThat(again.path("repeat").path("fields").findValuesAsText("field"))
                .containsExactly("Описание");

        // Поправленное у нас — не начало файлового, и это расхождение, не перезапись.
        inTenant(() -> jdbc.update("UPDATE part SET description = 'Своё' WHERE id = ?", a));
        JsonNode third = load(whole);
        assertThat(column(a, "description", String.class)).isEqualTo("Своё");
        assertThat(third.path("repeat").path("discrepancies").findValuesAsText("field"))
                .containsExactly("Описание");
    }

    // --- Пункт 6: отметка для площадки ---

    @Test
    @DisplayName("Повтор без изменений не отмечает позицию для площадки, с новым снимком — отмечает её одну")
    void changeMarkOnlyForRealChange() throws Exception {
        byte[] file = file(List.of(rowA(), rowB()), Set.of(), null);
        load(file);
        inTenant(() -> jdbc.update("DELETE FROM part_change"));

        load(file);
        assertThat(count("SELECT count(*) FROM part_change"))
                .as("повтор без изменений двинул отметку — дельта уедет зря").isZero();

        Map<String, String> withThird = new LinkedHashMap<>(rowA());
        withThird.put("Фото", rowA().get("Фото") + "\nhttps://example.test/a/3.jpg");
        load(file(List.of(withThird, rowB()), Set.of(), null));
        assertThat(inTenant(() -> jdbc.queryForList("SELECT part_id FROM part_change", Long.class)))
                .containsExactly(partOf(AD_A));
    }

    // --- Пункт 7: одновременные повторы ---

    @RepeatedTest(3)
    @DisplayName("Шесть одновременных загрузок одного файла: без отказов, дублей и с тем же итогом")
    void concurrentLoadsEndLikeOne() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Map<String, String> row = new LinkedHashMap<>(i % 2 == 0 ? rowA() : rowB());
            row.put("Номер объявления Дрома", String.valueOf(310000000 + i));
            row.put("Наименование", "Деталь " + i);
            row.put("Фото", "https://example.test/" + i + "/1.jpg\nhttps://example.test/" + i + "/2.jpg");
            row.put("Номер", "OEM-" + i);
            row.put("Номера замен", "SUB-" + i + "-1, SUB-" + i + "-2");
            rows.add(row);
        }
        byte[] file = file(rows, Set.of(), null);
        // Наименования заранее: одновременное заведение нового написания —
        // давняя отдельная дыра справочника (part_name_uk), см. DromAdNumberTest.
        inTenant(() -> {
            rows.forEach(row -> partNames.resolve(row.get("Наименование"), null));
            return null;
        });
        load(file);
        Map<String, Map<String, Object>> reference = contentByAd();
        inTenant(() -> {
            clean();
            return null;
        });

        int threads = 6;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var go = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<JsonNode>> runs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    return load(file);
                }));
            }
            go.countDown();
            int imported = 0;
            int refilled = 0;
            int unchanged = 0;
            for (var run : runs) {
                JsonNode report = run.get();
                imported += report.path("imported").asInt();
                refilled += report.path("repeat").path("refilled").asInt();
                unchanged += report.path("repeat").path("unchanged").asInt();
                assertThat(report.path("repeat").path("discrepancyCount").asInt()).isZero();
            }
            assertThat(imported).as("заведено больше или меньше, чем строк").isEqualTo(20);
            assertThat(refilled).as("одно и то же поле насчитано дозаполненным дважды").isZero();
            assertThat(unchanged).isEqualTo(5 * 20);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM part")).isEqualTo(20);
        assertThat(count("SELECT count(*) FROM part_photo_import")).isEqualTo(40);
        assertThat(count("SELECT count(*) FROM stock_movement")).isEqualTo(20);
        assertThat(contentByAd()).as("итог шести загрузок не тот, что у одной").isEqualTo(reference);
    }

    /**
     * Пункт 7, вторая половина: шесть одновременных повторов, которым есть что
     * дозаполнять. Тест выше грузит с нуля, и дозаполнять там нечего, поэтому
     * «дозаполнено 0» он подтвердил бы и у записи без условия в {@code WHERE}.
     * Здесь первая загрузка идёт без цены, производителя и состояния, а потом
     * шесть потоков грузят полный файл: каждое поле обязано насчитаться
     * дозаполненным ровно один раз на позицию, и проигравшие не называют
     * расхождением значение, которое записал победитель.
     */
    @RepeatedTest(3)
    @DisplayName("Шесть одновременных повторов дозаполняют каждое поле один раз и не выдумывают расхождений")
    void concurrentRefillCountsOnce() throws Exception {
        List<Map<String, String>> rows = generatedRows(20);
        byte[] full = file(rows, Set.of(), null);
        load(full);
        Map<String, Map<String, Object>> reference = contentByAd();
        inTenant(() -> {
            clean();
            return null;
        });
        load(file(rows, Set.of("Цена", "Производитель", "Состояние"), null));

        List<JsonNode> reports = concurrently(6, full);

        Map<String, Integer> filled = new TreeMap<>();
        int refilled = 0;
        int unchanged = 0;
        List<String> discrepancies = new ArrayList<>();
        for (JsonNode report : reports) {
            assertThat(report.path("imported").asInt()).isZero();
            JsonNode repeat = report.path("repeat");
            refilled += repeat.path("refilled").asInt();
            unchanged += repeat.path("unchanged").asInt();
            repeat.path("fields").forEach(f ->
                    filled.merge(f.path("field").asText(), f.path("positions").asInt(), Integer::sum));
            repeat.path("discrepancies").forEach(d -> discrepancies.add(d.path("text").asText()));
        }
        assertThat(discrepancies)
                .as("проигравший повтор назвал расхождением значение, записанное победителем")
                .isEmpty();
        assertThat(filled)
                .as("поле насчитано дозаполненным не один раз на позицию")
                .isEqualTo(Map.of("Состояние", 20, "Цена", 20, "Производитель", 20));
        assertThat(refilled).isEqualTo(20);
        assertThat(unchanged).isEqualTo(5 * 20);
        assertThat(contentByAd()).as("итог шести повторов не тот, что у одной загрузки")
                .isEqualTo(reference);
    }

    /**
     * Блоки карточки Дрома (задача 0285) пишутся в свою таблицу вставкой-или-обновлением,
     * и условие «блок ещё пуст» стоит в {@code WHERE} этой инструкции. Без него шесть
     * потоков, прочитавшие блок пустым, перезаписывали бы друг друга и насчитали бы
     * поле дозаполненным не двадцать раз, а больше: прежний тест это не ловит —
     * там блоков нет, а владельцем поправленное отсекает проверка до записи.
     */
    @RepeatedTest(3)
    @DisplayName("Шесть одновременных повторов дозаполняют каждый блок карточки Дрома один раз")
    void concurrentRefillCountsTermsOnce() throws Exception {
        List<Map<String, String>> rows = generatedRows(20);
        byte[] full = file(rows, Set.of(), null);
        load(full);
        Map<String, Map<String, Object>> reference = contentByAd();
        inTenant(() -> {
            clean();
            return null;
        });
        load(file(rows, Set.of("Гарантия", "Самовывоз", "Доставка и оплата",
                "Вид детали", "Раздел", "Применимость", "Прочие поля", "Может подойти", "Дата"), null));

        List<JsonNode> reports = concurrently(6, full);

        Map<String, Integer> filled = new TreeMap<>();
        int refilled = 0;
        List<String> discrepancies = new ArrayList<>();
        for (JsonNode report : reports) {
            JsonNode repeat = report.path("repeat");
            refilled += repeat.path("refilled").asInt();
            repeat.path("fields").forEach(f ->
                    filled.merge(f.path("field").asText(), f.path("positions").asInt(), Integer::sum));
            repeat.path("discrepancies").forEach(d -> discrepancies.add(d.path("text").asText()));
        }
        assertThat(discrepancies)
                .as("проигравший повтор назвал расхождением блок, записанный победителем")
                .isEmpty();
        assertThat(filled)
                .as("блок насчитан дозаполненным не один раз на позицию")
                .isEqualTo(Map.of("Гарантия и условия возврата", 20, "Самовывоз", 20,
                        "Доставка и оплата", 20, "Вид детали", 20, "Раздел", 20,
                        "Применимость", 20, "Характеристики", 20, "Может подойти", 20, "Дата", 20));
        // Позицию могут разделить победители разных блоков (их девять), поэтому не ровно 20.
        assertThat(refilled).isBetween(20, 180);
        assertThat(contentByAd()).as("итог шести повторов не тот, что у одной загрузки")
                .isEqualTo(reference);
    }

    private List<Map<String, String>> generatedRows(int count) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, String> row = new LinkedHashMap<>(i % 2 == 0 ? rowA() : rowB());
            row.put("Номер объявления Дрома", String.valueOf(330000000 + i));
            row.put("Наименование", "Деталь повтора " + i);
            row.put("Фото", "https://example.test/r" + i + "/1.jpg");
            row.put("Номер", "ROEM-" + i);
            row.put("Номера замен", "RSUB-" + i);
            rows.add(row);
        }
        // Наименования заранее — см. concurrentLoadsEndLikeOne.
        inTenant(() -> {
            rows.forEach(row -> partNames.resolve(row.get("Наименование"), null));
            return null;
        });
        return rows;
    }

    private List<JsonNode> concurrently(int threads, byte[] file) throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var go = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<JsonNode>> runs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    return load(file);
                }));
            }
            go.countDown();
            List<JsonNode> reports = new ArrayList<>();
            for (var run : runs) {
                reports.add(run.get());
            }
            return reports;
        } finally {
            pool.shutdownNow();
        }
    }

    // --- Пункт 8: отчёт — предел показа назван, полный счёт в числе ---

    @Test
    @DisplayName("Расхождений больше двухсот: в ответе двести, число — полное; строка без номера посчитана")
    void discrepancyListIsCappedButCounted() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            Map<String, String> row = new LinkedHashMap<>(rowA());
            row.put("Номер объявления Дрома", String.valueOf(320000000 + i));
            row.put("Наименование", "Деталь " + i);
            row.put("Количество", "1");
            rows.add(row);
        }
        Map<String, String> noAd = new LinkedHashMap<>(rowB());
        noAd.put("Номер объявления Дрома", "");
        List<Map<String, String>> withNoAd = new ArrayList<>(rows);
        withNoAd.add(noAd);
        JsonNode first = load(file(withNoAd, Set.of(), null));
        assertThat(first.path("repeat").path("withoutAdNumber").asInt()).isEqualTo(1);

        List<Map<String, String>> changed = new ArrayList<>();
        for (Map<String, String> row : rows) {
            Map<String, String> other = new LinkedHashMap<>(row);
            other.put("Наименование", row.get("Наименование") + " другое");
            other.put("Цена", "99");
            other.put("Количество", "2");
            changed.add(other);
        }
        JsonNode again = load(file(changed, Set.of(), null));

        JsonNode repeat = again.path("repeat");
        assertThat(repeat.path("discrepancyCount").asInt()).isEqualTo(210);
        assertThat(repeat.path("discrepancies").size())
                .isEqualTo(ExcelWarehouseImporter.DISCREPANCY_LIMIT);
    }

    // --- вспомогательное ---

    private static Map<String, String> rowA() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Номер объявления Дрома", AD_A);
        row.put("Наименование", "Фара левая Toyota Camry");
        row.put("Цена", "16000");
        row.put("Наличие", "В наличии");
        row.put("Состояние", "Новый");
        row.put("Производитель", "Depo");
        row.put("Оригинальность", "Оригинал");
        row.put("Номер", "81170-33550");
        row.put("Номера замен", "A111, B222");
        row.put("Сторона", "Левая");
        row.put("Положение", "Переднее");
        row.put("Верх/низ", "Верхнее");
        row.put("Описание", "Фара целая.\nБез сколов.");
        row.put("Вид детали", "Фара левая Toyota Camry");
        row.put("Раздел", "Автозапчасти → Электрооборудование → Фары");
        row.put("Применимость", "Toyota Camry | 7 поколение (2017–2024) | XV70\nДля двигателей | 2AR-FE");
        row.put("Прочие поля", "Модель магнитолы [model]: Toyota NHZT-W58\nРазмер [size]: 2 DIN — 178x100 мм");
        row.put("Может подойти", "Toyota Camry — ACV40, \u041aCV41\nLexus ES — GSV40");
        row.put("Дата", "2026-09-28");
        row.put("Гарантия", "Обговаривается индивидуально");
        row.put("Самовывоз", "Самовывоз — улица Георгия Исакова 9");
        row.put("Доставка и оплата", "Доставка по городу — 300 р.\n\nОплата при получении");
        row.put("Фото", "https://example.test/a/1.jpg\nhttps://example.test/a/2.jpg");
        row.put(NOTE, "с полки 3");
        return row;
    }

    private static Map<String, String> rowB() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Номер объявления Дрома", AD_B);
        row.put("Наименование", "Бампер задний Toyota Camry");
        row.put("Цена", "9000");
        row.put("Наличие", "В наличии");
        row.put("Состояние", "Контракт");
        row.put("Производитель", "Toyota");
        row.put("Оригинальность", "Оригинал");
        row.put("Номер", "52159-33010");
        row.put("Номера замен", "C333");
        row.put("Сторона", "Правая");
        row.put("Положение", "Заднее");
        row.put("Верх/низ", "Нижнее");
        row.put("Описание", "Бампер без трещин.");
        row.put("Вид детали", "Бампер задний Toyota Camry");
        row.put("Раздел", "Автозапчасти → Кузов → Бампера");
        row.put("Применимость", "Toyota Camry | Другие кузова | ACV40");
        row.put("Прочие поля", "Бренд [model]: Zapco\nКоличество каналов [channel]: 4-канальный");
        row.put("Может подойти", "Toyota Camry — ACV30");
        row.put("Дата", "2026-10-05");
        row.put("Гарантия", "Гарантия 14 дней");
        row.put("Самовывоз", "Самовывоз — склад на Ленина 5");
        row.put("Доставка и оплата", "Доставка ТК — по тарифу\nОплата картой");
        row.put("Фото", "https://example.test/b/1.jpg\nhttps://example.test/b/2.jpg");
        row.put(NOTE, "под потолком");
        return row;
    }

    /**
     * Файл переноса: колонки договора плюс «Примечание»; колонки из {@code without}
     * убраны вместе с заголовком. {@code continuation} — строки листа «Продолжение».
     */
    private static byte[] file(List<Map<String, String>> rows, Set<String> without,
                               List<String[]> continuation) throws Exception {
        List<String> header = new ArrayList<>(
                DromTransferFile.COLUMNS.stream().map(Column::title).toList());
        header.add(NOTE);
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

    private JsonNode load(byte[] file) throws Exception {
        return load(file, UUID.randomUUID().toString());
    }

    /**
     * Загрузка тем путём, каким её делает экран: разбор, затем сопоставление
     * как есть. «Примечание» договором не сопоставляется — его ставим руками,
     * как человек выбрал бы колонку на экране.
     */
    private JsonNode load(byte[] file, String requestId) throws Exception {
        MockHttpSession session = login();
        String previewBody = mvc.perform(multipart("/api/import/excel/preview").file(xlsx(file))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode preview = json.readTree(previewBody);
        JsonNode detected = preview.path("detected");

        MockMultipartHttpServletRequestBuilder request = multipart("/api/import/excel");
        request.file(xlsx(file));
        detected.fieldNames().forEachRemaining(key -> request.param(key, detected.path(key).asText()));
        List<String> header = new ArrayList<>();
        preview.path("header").forEach(h -> header.add(h.asText()));
        if (header.contains(NOTE)) {
            request.param(Field.NOTE.name(), String.valueOf(header.indexOf(NOTE)));
        }
        String body = mvc.perform(request.with(csrf()).session(session)
                        .param("warehouseId", String.valueOf(warehouseId))
                        .param("requestId", requestId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private void edit(long part, Map<String, Object> form) throws Exception {
        mvc.perform(put("/api/parts/" + part).with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(form)))
                .andExpect(status().isOk());
    }

    /**
     * Всё, что повтор мог бы тронуть у позиции, по номеру объявления:
     * строка {@code part} целиком (колонка за колонкой), номера и очередь снимков.
     */
    private Map<String, Map<String, Object>> stateByAd() {
        return inTenant(() -> {
            Map<String, Map<String, Object>> byAd = new TreeMap<>();
            for (String row : jdbc.queryForList("SELECT to_jsonb(p)::text FROM part p", String.class)) {
                Map<String, Object> state = new TreeMap<>(readMap(row));
                long id = ((Number) state.get("id")).longValue();
                state.put("oem_primary", jdbc.queryForList(
                        "SELECT raw_number FROM part_oem WHERE part_id = ? AND is_primary",
                        String.class, id));
                state.put("oem_substitutes", jdbc.queryForList("""
                        SELECT raw_number FROM part_oem WHERE part_id = ? AND NOT is_primary
                         ORDER BY id""", String.class, id));
                // Блоки карточки Дрома: нет строки — все NULL (задача 0285).
                for (String block : List.of("warranty", "pickup", "delivery_payment",
                        "drom_kind", "drom_section", "applicability", "characteristics", "may_fit",
                        "ad_date")) {
                    List<String> value = jdbc.queryForList(
                            "SELECT " + block + " FROM part_drom_card WHERE part_id = ?",
                            String.class, id);
                    state.put(block, value.isEmpty() ? null : value.get(0));
                }
                state.put("photos", jdbc.queryForList("""
                        SELECT url || ' ' || sort_order FROM part_photo_import WHERE part_id = ?
                         ORDER BY sort_order""", String.class, id));
                byAd.put(String.valueOf(state.get("drom_ad_number")), state);
            }
            return byAd;
        });
    }

    /** Состояние без того, что у двух разных заведений законно разное. */
    private Map<String, Map<String, Object>> contentByAd() {
        Map<String, Map<String, Object>> content = new TreeMap<>();
        stateByAd().forEach((ad, state) -> content.put(ad, content(state)));
        return content;
    }

    private static Map<String, Object> content(Map<String, Object> state) {
        Map<String, Object> kept = new TreeMap<>(state);
        kept.keySet().removeIf(key -> key.equals("id") || key.equals("public_code")
                || key.equals("number") || key.endsWith("_at"));
        return kept;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String text) {
        try {
            return json.readValue(text, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long partOf(String ad) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE drom_ad_number = ?", Long.class, ad));
    }

    private <T> T column(long part, String column, Class<T> type) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT " + column + " FROM part WHERE id = ?", type, part));
    }

    private int count(String sql) {
        return inTenant(() -> jdbc.queryForObject(sql, Integer.class));
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
