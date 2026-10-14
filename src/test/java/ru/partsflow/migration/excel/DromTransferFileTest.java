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
import ru.partsflow.migration.excel.DromTransferFile.Column;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Договор колонок файла переноса с Дрома (задача 0272) — на образце из пяти
 * настоящих карточек клиента, через HTTP, как это делает экран «Импорт».
 *
 * <p><b>Каждая колонка договора стоит ровно в одном из двух списков.</b>
 * {@link #CHECKS} — колонки, которые импорт читает, и проверка того, что
 * значение из файла доехало до базы. {@link #PENDING} — колонки, объявленные
 * договором и ждущие своей задачи. Тест краснеет, если колонка не стоит
 * ни в одном, стоит в обоих или стоит не в том: импорт научили читать
 * колонку, а проверки на неё нет — красное; проверку написали, а импорт
 * колонку не читает — тоже красное.
 *
 * <p><b>Как следующей задаче включить проверку.</b> Научив импорт колонке
 * (например, «Фото» в задаче 0273): поставьте ей поле в
 * {@link DromTransferFile#COLUMNS}, перенесите строку из {@link #PENDING}
 * в {@link #CHECKS} со своей проверкой и поправьте «Импорт сегодня» в
 * {@code docs/drom-transfer-file.md}. Пропущенный шаг тест назовёт сам.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DromTransferFileTest extends PostgresTestBase {

    private static final String TENANT = "t_000980";
    private static final int REGISTRY_ID = 980;
    private static final String COMPANY = "dromtransfer";

    private static final String SAMPLE = "/migration/excel/drom-transfer-sample.xlsx";
    private static final Path CONTRACT = Path.of("docs/drom-transfer-file.md");

    /** Пять карточек, названных в задаче 0272. */
    private static final List<String> SAMPLE_ADS =
            List.of("125696381", "125977770", "107112187", "119224869", "119271995");

    /** Колонки договора, которые импорт ещё не читает, — и кто их прочтёт. */
    private static final Map<String, String> PENDING = Map.ofEntries(
            // Задача 0292 назвала колонки, которые парсер уже кладёт в файл, а договор
            // до неё не знал (файл нёс 25 колонок при 22 в договоре). «Может подойти» читает
            // с задачи 0293, «Дату» — с 0298: ждёт только ссылка.
            Map.entry("Ссылка", "0274"));

    /** Проверка одной колонки: значение из файла против того, что в базе у позиции. */
    @FunctionalInterface
    private interface Check {
        void verify(DromTransferFileTest test, long partId, String fileValue);
    }

    /**
     * Блоки карточки Дрома (задача 0285): гарантия, самовывоз, доставка и оплата
     * лежат в {@code part_drom_card} дословно, и пусто у клиента — NULL у позиции.
     * Подробно (пустые строки внутри, склейка с листа «Продолжение», правка на
     * карточке) — {@link DromShopTermsTest}; здесь только то, что колонка читается.
     */
    private static Check term(String column, String what) {
        return (t, part, value) -> assertThat(t.column(part,
                "SELECT (SELECT " + column + " FROM part_drom_card WHERE part_id = p.id) FROM part p WHERE p.id = ?",
                String.class))
                .as(what + " доехала не дословно")
                .isEqualTo(value.isEmpty() ? null : value);
    }

    private static final Map<String, Check> TERM_CHECKS = Map.of(
            "Гарантия", term("warranty", "«Гарантия»"),
            "Самовывоз", term("pickup", "«Самовывоз»"),
            "Доставка и оплата", term("delivery_payment", "«Доставка и оплата»"),
            // Задача 0286: строка вида и раздел дословно, не part_kind_id и не part.section.
            "Вид детали", term("drom_kind", "«Вид детали»"),
            "Раздел", term("drom_section", "«Раздел»"),
            // Задача 0287: «Применимость» («Подходит») текстом, не part_applicability.
            "Применимость", term("applicability", "«Применимость»"),
            // Задача 0292: «Прочие поля» — характеристики объявления текстом. У пяти
            // карточек образца (запчасти) колонка пуста — проверяется NULL; дословность
            // на ячейке клиента и снятие строки обложки — DromCharacteristicsTest.
            "Прочие поля", term("characteristics", "«Прочие поля»"),
            // Задача 0293: «Может подойти» — догадка Дрома текстом, не part_applicability.
            // У карточек образца колонка пуста — проверяется NULL; дословность, кириллическая
            // «К» и склейка с листа «Продолжение» — DromShopTermsTest.
            "Может подойти", term("may_fit", "«Может подойти»"),
            // Задача 0298: «Дата» объявления Дрома — текстом как в ячейке. У карточек образца
            // колонка пуста — проверяется NULL; значение, повтор и правка — DromShopTermsTest.
            "Дата", term("ad_date", "«Дата»"));

    /**
     * Колонки, которые импорт читает, — и чем это доказано.
     *
     * <p>Поля карточки Дрома (задача 0277) проверяет {@link DromCardFieldsTest#CHECKS}
     * и подмешивается сюда ({@link #withCardChecks}): одна проверка на колонку,
     * а не две, разъехавшиеся бы на первой правке.
     */
    private static final Map<String, Check> CHECKS = withCardChecks(Map.of(
            "Номер объявления Дрома", (t, part, value) -> assertThat(t.column(part,
                    "SELECT drom_ad_number FROM part WHERE id = ?", String.class))
                    .as("номер объявления из файла не доехал до позиции")
                    .isEqualTo(value),
            "Наименование", (t, part, value) -> assertThat(t.column(part,
                    "SELECT title FROM part WHERE id = ?", String.class))
                    .as("заголовок клиента доехал не дословно")
                    .isEqualTo(value),
            "Цена", (t, part, value) -> assertThat(t.column(part,
                    "SELECT price FROM part WHERE id = ?", BigDecimal.class))
                    .as("цена из файла не доехала")
                    .isEqualByComparingTo(value),
            // Пустое количество — одна штука (задача 0279): на карточке Дрома
            // его нет, и парсер оставляет колонку пустой.
            "Количество", (t, part, value) -> assertThat(t.stock(part))
                    .as("остаток не равен количеству из файла (пусто — одна штука)")
                    .isEqualByComparingTo(value.isBlank() ? "1" : value),
            "Наличие", (t, part, value) -> {
                assertThat(value)
                        .as("в образце позиция не «В наличии» — а заведена")
                        .isEqualTo(ExcelWarehouseImporter.IN_STOCK);
                assertThat(t.stock(part))
                        .as("позиция «В наличии» заведена без остатка")
                        .isPositive();
            },
            "Номер", (t, part, value) -> assertThat(t.inTenant(() -> t.jdbc.queryForList("""
                    SELECT raw_number FROM part_oem WHERE part_id = ? AND is_primary""",
                    String.class, part)))
                    .as("номер производителя не доехал главным номером позиции")
                    .containsExactly(value),
            "Фото", (t, part, value) -> assertThat(t.inTenant(() -> t.jdbc.queryForList("""
                    SELECT url FROM part_photo_import WHERE part_id = ? ORDER BY sort_order""",
                    String.class, part)))
                    .as("снимки не встали в очередь переноса все и в порядке галереи")
                    .containsExactlyElementsOf(List.of(value.split("\n"))),
            // Описание объявления, а не внутреннее примечание; дословно,
            // с переводами строк. Пусто у клиента — пусто и у позиции:
            // ни пустой строки, ни собранного за него текста (задача 0275).
            "Описание", (t, part, value) -> {
                assertThat(t.column(part, "SELECT description FROM part WHERE id = ?", String.class))
                        .as("описание клиента доехало не дословно")
                        .isEqualTo(value.isEmpty() ? null : value);
                assertThat(t.column(part, "SELECT note FROM part WHERE id = ?", String.class))
                        .as("описание объявления легло во внутреннее примечание")
                        .isNull();
            }));

    private static Map<String, Check> withCardChecks(Map<String, Check> own) {
        Map<String, Check> all = new HashMap<>(own);
        all.putAll(TERM_CHECKS);
        DromCardFieldsTest.CHECKS.forEach((title, card) -> all.put(title,
                (t, part, value) -> card.verify((sql, id) -> t.inTenant(
                        () -> t.jdbc.queryForList(sql, String.class, id)), part, value)));
        return Map.copyOf(all);
    }

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
                VALUES (?, ?, 'Разборка с Дрома', ?)""", REGISTRY_ID, TENANT, COMPANY);
        warehouseId = inTenant(() -> {
            // Образец грузится в каждом тесте этого класса, а объявление, номер
            // которого уже есть, второй позицией не заводится (задача 0274):
            // без очистки второй тест видел бы «уже у позиции» вместо загрузки.
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
    }

    @Test
    @DisplayName("Договор в коде совпадает с документом: колонки, порядок, обязательность, кто читает")
    void contractInCodeMatchesDocument() throws Exception {
        List<String[]> rows = documentRows();

        assertThat(rows).extracting(r -> r[0])
                .as("колонки docs/drom-transfer-file.md разошлись с DromTransferFile.COLUMNS — "
                        + "договор обязан быть один")
                .containsExactlyElementsOf(DromTransferFile.COLUMNS.stream().map(Column::title).toList());

        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            Column column = DromTransferFile.COLUMNS.get(i);
            assertThat(row[1])
                    .as("обязательность колонки «%s» в документе и в коде разная", column.title())
                    .isEqualTo(column.required() ? "да" : "нет");

            Matcher task = Pattern.compile("задача (\\d{4})").matcher(row[2]);
            if (column.reads() == null) {
                assertThat(task.find())
                        .as("колонку «%s» импорт не читает — документ обязан назвать задачу, "
                                + "которая её прочтёт", column.title())
                        .isTrue();
                assertThat(task.group(1))
                        .as("документ и проверка называют для «%s» разные задачи", column.title())
                        .isEqualTo(PENDING.get(column.title()));
            } else {
                assertThat(row[2])
                        .as("колонку «%s» импорт читает, а документ этого не говорит", column.title())
                        .startsWith("читает");
            }
        }
    }

    @Test
    @DisplayName("Колёсные строки: договор называет ровно те ключи «Прочих полей», что читает импорт")
    void wheelKeysAreTheContract() throws Exception {
        // Задача 0291: у колёсной строки колонка 25 — договорная. Ключ, который импорт
        // научили читать, а договор не назвал, парсер не обязан класть; названный
        // в договоре и не читаемый — обещание, которого импорт не держит.
        List<String> keys = new ArrayList<>();
        boolean inSection = false;
        for (String line : Files.readAllLines(CONTRACT, StandardCharsets.UTF_8)) {
            if (line.startsWith("## ")) {
                inSection = line.startsWith("## Колёсные строки");
                continue;
            }
            Matcher key = Pattern.compile("^\\| `([^`]+)` \\|").matcher(line);
            if (inSection && key.find()) {
                keys.add(key.group(1));
            }
        }
        assertThat(keys)
                .as("ключи раздела «Колёсные строки» в %s разошлись с DromWheelCard.KEYS", CONTRACT)
                .containsExactlyInAnyOrderElementsOf(DromWheelCard.KEYS);
        // Список — не только то, что договор называет, но и то, что разбор читает.
        // В одну сторону это держит сам DromWheelCard: ключ мимо KEYS — отказ разбора
        // на любой строке. В другую — здесь: ключ из списка, значение которого разбор
        // не читает, — обещание без исполнения. Поэтому у каждого ключа два значения,
        // и разбор обязан их различить: одного присутствия мало, вид колеса меняется
        // от самого ключа, а не от того, что под ним написано.
        Map<String, String[]> samples = Map.ofEntries(
                Map.entry(DromWheelCard.MARKER, new String[] {"Dunlop Grandtrek PT3", "Maxxis Bravo"}),
                Map.entry("tireFirmAndModel", new String[] {"Dunlop Grandtrek PT3", "Maxxis Bravo"}),
                Map.entry("inSetQuantity", new String[] {"4 шт", "2 шт"}),
                Map.entry("quantity", new String[] {"1 шт", "3 шт"}),
                Map.entry("year", new String[] {"2023", "2013"}),
                Map.entry("wheelSeason", new String[] {"Летние", "Зимние"}),
                Map.entry("wheelTireWear", new String[] {"10 %", "50 %"}),
                Map.entry("marking", new String[] {"265/65 R19", "245/40 R18"}),
                Map.entry("wheelDiameter", new String[] {"17\"", "18\""}),
                Map.entry("diskType", new String[] {"Литой", "Штампованный"}),
                Map.entry("discFirmAndModel", new String[] {"noname", "Enkei"}),
                Map.entry("discParameters", new String[] {
                        "Ширина диска 7.5\" Вылет (ET) 37 мм.", "Ширина диска 8\" Вылет (ET) 45 мм."}),
                Map.entry("wheelPcd", new String[] {"6x139.7", "5x114.3"}),
                Map.entry("predestination", new String[] {"Обычная", "Внедорожная"}));
        assertThat(samples.keySet()).as("образцы значений разошлись с DromWheelCard.KEYS")
                .containsExactlyInAnyOrderElementsOf(DromWheelCard.KEYS);
        for (String wheelKey : DromWheelCard.KEYS) {
            String[] values = samples.get(wheelKey);
            DromWheelCard.Parsed one = DromWheelCard.parse("Колёса", DromWheelCard.SECTION,
                    "Подпись [" + wheelKey + "]: " + values[0]);
            DromWheelCard.Parsed other = DromWheelCard.parse("Колёса", DromWheelCard.SECTION,
                    "Подпись [" + wheelKey + "]: " + values[1]);
            assertThat(one)
                    .as("ключ %s назван в договоре и KEYS, а разбор его значения не читает", wheelKey)
                    .isNotEqualTo(other);
        }

        String characteristics = documentRows().stream()
                .filter(r -> r[0].equals("Прочие поля")).findFirst().orElseThrow()[2];
        assertThat(characteristics)
                .as("строка «Прочие поля» договора не говорит, что у колёсной строки колонка договорная")
                .contains("колёсной строки").contains("договорная");
    }

    @Test
    @DisplayName("Каждая колонка договора — либо проверена, либо ждёт названной задачи, и не то и другое")
    void everyColumnIsEitherCheckedOrPending() {
        Set<String> seen = new HashSet<>();
        for (Column column : DromTransferFile.COLUMNS) {
            seen.add(column.title());
            if (column.reads() != null) {
                assertThat(CHECKS)
                        .as("импорт читает «%s» в %s, а проверки, что значение доехало, нет — "
                                + "допишите её в CHECKS", column.title(), column.reads())
                        .containsKey(column.title());
                assertThat(PENDING)
                        .as("«%s» уже читается — уберите её из PENDING", column.title())
                        .doesNotContainKey(column.title());
            } else {
                assertThat(PENDING)
                        .as("импорт не читает «%s», и ни одна задача за неё не названа",
                                column.title())
                        .containsKey(column.title());
                assertThat(CHECKS)
                        .as("на «%s» есть проверка, а импорт её не читает — поставьте поле "
                                + "в DromTransferFile.COLUMNS", column.title())
                        .doesNotContainKey(column.title());
            }
        }
        Set<String> listed = new HashSet<>(CHECKS.keySet());
        listed.addAll(PENDING.keySet());
        assertThat(seen)
                .as("в проверке названы колонки, которых в договоре нет")
                .containsAll(listed);
    }

    @Test
    @DisplayName("Образец — пять настоящих карточек ровно в колонках договора")
    void sampleFollowsContract() throws Exception {
        List<Map<String, String>> rows = sampleRows(sample());

        assertThat(rows).extracting(r -> r.get(DromTransferFile.ANCHOR))
                .as("образец не те пять карточек, что названы в задаче")
                .containsExactlyElementsOf(SAMPLE_ADS);
        for (Map<String, String> row : rows) {
            for (Column column : DromTransferFile.COLUMNS) {
                if (column.required()) {
                    assertThat(row.get(column.title()))
                            .as("у объявления %s пусто в обязательной «%s»",
                                    row.get(DromTransferFile.ANCHOR), column.title())
                            .isNotBlank();
                }
            }
        }
    }

    @Test
    @DisplayName("Экран получает сопоставленным ровно то, что импорт читает, — сам, без выбора")
    void previewMapsReadColumnsOnly() throws Exception {
        List<String> header = sampleHeader(sample());
        JsonNode preview = preview(sample());

        assertThat(preview.path("format").asText())
                .as("файл переноса не узнан — колонки пришлось бы сопоставлять руками")
                .isEqualTo(ExcelWarehouseImporter.Preview.DROM_TRANSFER);

        Map<String, Integer> expected = new HashMap<>();
        for (Column column : DromTransferFile.COLUMNS) {
            if (column.reads() != null) {
                expected.put(column.reads().name(), header.indexOf(column.title()));
            }
        }
        Map<String, Integer> detected = new HashMap<>();
        preview.path("detected").fields()
                .forEachRemaining(e -> detected.put(e.getKey(), e.getValue().asInt()));

        // Лишнее здесь — это колонка, показанная «сопоставленной», которую
        // импорт молча отбросит: «Состояние» узнавалось по синониму, а
        // «Описание» ложилось во внутреннее примечание.
        assertThat(detected)
                .as("сопоставление на экране разошлось с тем, что импорт читает")
                .isEqualTo(expected);
        assertThat(preview.path("missingRequired").size())
                .as("экран потребует выбрать колонку руками")
                .isZero();
    }

    /**
     * Образец грузится как есть — без количества, с «В наличии».
     *
     * <p>До задачи 0279 здесь стояла подстановка единицы в «Количество»
     * и отдельный тест, требовавший, чтобы образец как есть пропускался
     * целиком («нет количества» у всех пяти строк). Количества на странице
     * Дрома нет, парсер оставляет колонку пустой — и теперь это одна штука
     * с пометкой в отчёте, а не пропуск.
     */
    @Test
    @DisplayName("Каждая читаемая колонка образца доезжает до позиции — образец как есть")
    void everyReadColumnLands() throws Exception {
        byte[] file = sample();
        JsonNode preview = preview(file);

        JsonNode report = load(file, preview.path("detected"));
        assertThat(report.path("imported").asInt())
                .as("загружено не всё: %s", report.path("skipped"))
                .isEqualTo(SAMPLE_ADS.size());
        assertThat(report.path("quantityDefaulted").asInt())
                .as("количества в образце нет — отчёт обязан сказать, что заведено по одной штуке")
                .isEqualTo(SAMPLE_ADS.size());

        for (Map<String, String> row : sampleRows(file)) {
            long part = partOf(row);
            CHECKS.forEach((title, check) -> check.verify(this, part, row.get(title)));
        }
    }

    /**
     * Не «В наличии» — пропуск со словами; число в «Количестве» — то число;
     * ноль — пропуск. Критерий задачи 0279, пункты 2–4, на настоящих строках.
     */
    @Test
    @DisplayName("Не «В наличии» пропускается словами, число количества читается, ноль — пропуск")
    void availabilityAndQuantityRules() throws Exception {
        byte[] file = edited(sample(), Map.of(
                1, Map.of("Наличие", "Под заказ"),
                2, Map.of("Количество", "3"),
                3, Map.of("Количество", "0")));
        List<Map<String, String>> rows = sampleRows(file);

        JsonNode report = load(file, preview(file).path("detected"));

        assertThat(report.path("imported").asInt())
                .as("пропущено: %s", report.path("skipped"))
                .isEqualTo(3);
        assertThat(report.path("quantityDefaulted").asInt()).isEqualTo(2);
        Map<Integer, String> skipped = new HashMap<>();
        report.path("skipped").forEach(s -> skipped.put(s.path("row").asInt(), s.path("reason").asText()));
        // Номер строки — как в Excel: заголовок первой строкой, данные со второй.
        assertThat(skipped)
                .as("пропуски названы не те или не теми словами")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        3, "не в наличии: «Под заказ»",
                        5, "нет количества"));

        assertThat(stock(partOf(rows.get(2))))
                .as("количество, вписанное клиентом, не прочитано")
                .isEqualByComparingTo("3");
        assertThat(stock(partOf(rows.get(0))))
                .as("пустое количество при «В наличии» не дало одну штуку")
                .isEqualByComparingTo("1");
    }

    // --- образец ---

    private static byte[] sample() throws Exception {
        try (InputStream in = DromTransferFileTest.class.getResourceAsStream(SAMPLE)) {
            assertThat(in).as("образца %s нет в тестовых ресурсах", SAMPLE).isNotNull();
            return in.readAllBytes();
        }
    }

    /** Образец с подменёнными ячейками: номер строки данных (с нуля) → колонка → значение. */
    private static byte[] edited(byte[] file, Map<Integer, Map<String, String>> cells)
            throws Exception {
        List<String> header = sampleHeader(file);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.getSheetAt(0);
            cells.forEach((dataRow, values) -> values.forEach((title, value) -> {
                Row row = sheet.getRow(dataRow + 1);
                row.createCell(header.indexOf(title)).setCellValue(value);
            }));
            book.write(out);
            return out.toByteArray();
        }
    }

    private static List<String> sampleHeader(byte[] file) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            List<String> header = new ArrayList<>();
            book.getSheetAt(0).getRow(0).forEach(c -> header.add(c.getStringCellValue()));
            return header;
        }
    }

    /** Строки образца: заголовок → значение. Читается POI напрямую, мимо импорта. */
    private static List<Map<String, String>> sampleRows(byte[] file) throws Exception {
        List<String> header = sampleHeader(file);
        assertThat(header)
                .as("заголовок образца разошёлся с договором — колонку убрали, "
                        + "переименовали или переставили")
                .containsExactlyElementsOf(DromTransferFile.COLUMNS.stream().map(Column::title).toList());
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

    // --- договор в документе ---

    /** Строки таблицы договора: [колонка, обязательна, импорт сегодня]. */
    private static List<String[]> documentRows() throws Exception {
        List<String[]> rows = new ArrayList<>();
        boolean inTable = false;
        for (String line : Files.readAllLines(CONTRACT, StandardCharsets.UTF_8)) {
            if (line.startsWith("| № | Колонка файла")) {
                inTable = true;
                continue;
            }
            if (!inTable) {
                continue;
            }
            if (!line.startsWith("|")) {
                break;
            }
            // «\\|» внутри клетки — это знак формата («Применимость»), а не граница.
            String[] cells = line.split("(?<!\\\\)\\|", -1);
            if (!cells[1].strip().matches("\\d+")) {
                continue; // разделитель под заголовком
            }
            rows.add(new String[] {
                    unmark(cells[2]), unmark(cells[4]), unmark(cells[cells.length - 2])});
        }
        assertThat(rows).as("таблица договора в %s не найдена", CONTRACT).isNotEmpty();
        return rows;
    }

    private static String unmark(String cell) {
        return cell.replace("**", "").replace("`", "").strip();
    }

    // --- HTTP, как экран ---

    private JsonNode preview(byte[] file) throws Exception {
        String body = mvc.perform(multipart("/api/import/excel/preview").file(xlsx(file))
                        .with(csrf()).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    /** Сопоставление уходит тем, что пришло в разборе: человек ничего не выбирал. */
    private JsonNode load(byte[] file, JsonNode detected) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/import/excel");
        request.file(xlsx(file));
        detected.fields().forEachRemaining(e -> request.param(e.getKey(), e.getValue().asText()));
        String body = mvc.perform(request.with(csrf()).session(login())
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

    /** Позиция, заведённая из этой строки, — последняя с таким заголовком. */
    private long partOf(Map<String, String> row) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT max(id) FROM part WHERE title = ?", Long.class,
                row.get("Наименование")));
    }

    private BigDecimal stock(long partId) {
        return column(partId,
                "SELECT COALESCE(sum(qty), 0) FROM part_stock WHERE part_id = ?",
                BigDecimal.class);
    }

    private <T> T column(long partId, String sql, Class<T> type) {
        return inTenant(() -> jdbc.queryForObject(sql, type, partId));
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
