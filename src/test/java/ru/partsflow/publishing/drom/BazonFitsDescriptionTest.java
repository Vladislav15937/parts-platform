package ru.partsflow.publishing.drom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.StockLedger;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.publishing.FeedSettings;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Блок «&lt;Вид&gt; подходит:» у позиций, заведённых у нас, собирается из справочника
 * {@code catalog.bazon_*} (задача 0342).
 *
 * <p>Справочник свой, выдуманный и обезличенный: настоящий снимок Bazon в общей схеме
 * лежит рядом, но тест не должен зависеть от того, каким будет следующий снимок.
 * Прайс собирается настоящим генератором по настоящему соединению: сборщик ходит
 * по нему же, пока открыт курсор склада, и тест на сервисе без курсора этого не видит.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class BazonFitsDescriptionTest extends PostgresTestBase {

    private static final String TENANT = "t_000999";
    private static final String MARK = "Фитмарка";
    private static final String MODEL = "Фитмодель";

    private static final String LINE_2 = MARK + " " + MODEL + " 2 поколение 2007–2015 P2 (P2A, P2B)";
    private static final String ENGINES_2 = "ELE1 (0.0 Э), ENG1 (2.5 Б), ENG2 (2.0 Д), GAS1 (1.5 Г), HYB1 (1.5 Б)";
    private static final String LINE_1 = MARK + " " + MODEL + " 1 поколение 2000–2007 PL1 двигатель ENG1 (2.5 Б)";
    private static final String LINE_3 = MARK + " " + MODEL + " 3 поколение 2022–н.в. двигатель ELE1 (0.0 Э)";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private DromPriceGenerator generator;

    private static long brandId;
    private static long modelId;
    /** Наши поколения по названию годов: «2007–2009» и т. д. */
    private static final java.util.Map<String, Long> OURS = new java.util.HashMap<>();

    private Long warehouse;
    private Long kindName;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeAll
    static void catalogFixture(@Autowired JdbcTemplate jdbc) {
        removeCatalog(jdbc);
        brandId = jdbc.queryForObject(
                "INSERT INTO catalog.brand (name, slug) VALUES (?, 'fits0342') RETURNING id", Long.class, MARK);
        modelId = jdbc.queryForObject(
                "INSERT INTO catalog.model (brand_id, name, slug) VALUES (?, ?, 'fitmodel') RETURNING id",
                Long.class, brandId, MODEL);
        long[][] ours = {{2007, 2009}, {2010, 2015}, {2022, 0}, {2030, 2035}, {1990, 1995}, {2000, 2007}, {2007, 2007}};
        for (long[] years : ours) {
            Long to = years[1] == 0 ? null : years[1];
            OURS.put(years[0] + "-" + years[1], jdbc.queryForObject("""
                    INSERT INTO catalog.generation (model_id, name, year_from, year_to)
                    VALUES (?, ?, ?, ?) RETURNING id""", Long.class, modelId, years[0] + "–" + years[1],
                    years[0], to));
        }
        long mark = jdbc.queryForObject(
                "INSERT INTO catalog.bazon_mark (bazon_id, name, brand_id) VALUES (9000001, ?, ?) RETURNING id",
                Long.class, MARK, brandId);
        long model = jdbc.queryForObject("""
                INSERT INTO catalog.bazon_model (bazon_id, bazon_mark_id, name, model_id)
                VALUES (9000002, ?, ?, ?) RETURNING id""", Long.class, mark, MODEL, modelId);
        long g1 = bazonGeneration(jdbc, 9000011, model, "1 поколение", "2000—2007", 2000, 2007, "PL1");
        long g2 = bazonGeneration(jdbc, 9000012, model, "2 поколение", "2007—2015", 2007, 2015, "P2");
        long g3 = bazonGeneration(jdbc, 9000013, model, "3 поколение", "2022—...", 2022, null, null);
        bazonGeneration(jdbc, 9000014, model, "4 поколение", "2030—2035", 2030, 2035, "P4");
        // Кузова второго поколения: порядок вставки не алфавитный, один без кода.
        body(jdbc, 9000021, g2, "P2B", "Пикап");
        body(jdbc, 9000022, g2, "P2A", null);
        body(jdbc, 9000023, g2, null, "Без кода");
        long ele = motor(jdbc, 9000031, mark, "ELE1", null, "electric");
        long eng1 = motor(jdbc, 9000032, mark, "ENG1", 2500, "gasoline");
        long eng2 = motor(jdbc, 9000033, mark, "ENG2", 2000, "diesel");
        long gas = motor(jdbc, 9000034, mark, "GAS1", 1500, "gas");
        long hyb = motor(jdbc, 9000035, mark, "HYB1", 1500, "hybrid");
        for (long m : new long[] {ele, eng1, eng2, gas, hyb}) {
            link(jdbc, g2, m);
        }
        link(jdbc, g1, eng1);
        link(jdbc, g3, ele);
    }

    @AfterAll
    static void dropCatalog(@Autowired JdbcTemplate jdbc) {
        removeCatalog(jdbc);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 999");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (999, ?, 'Подходит из справочника', 'fits0342')""", TENANT);
        inTenant(() -> {
            jdbc.update("UPDATE part SET is_published = false");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад') RETURNING id", Long.class, branch);
            kindName = jdbc.queryForObject(
                    "INSERT INTO part_name (name) VALUES ('деталь') ON CONFLICT (lower(btrim(name))) "
                            + "DO UPDATE SET name = excluded.name RETURNING id", Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Двигатель важен: строка с перечнем двигателей, кузова и платформа поколения Bazon — и блок стоит после «снята с»")
    void listsEnginesWhenTheyMatter() {
        Long part = part(true, row("2007-2009"));

        assertThat(descriptionOf(part)).isEqualTo("\nКомментарий клиента\n\n"
                + "Запчасть снята с: " + MARK + " " + MODEL + "\n\n"
                + "Деталь подходит:\n" + LINE_2 + " двигатель " + ENGINES_2);
    }

    @Test
    @DisplayName("Двигатель не важен — «любой двигатель» с кодами поколения")
    void anyEngineWhenTheyDoNotMatter() {
        Long part = part(false, row("2010-2015"));

        assertThat(descriptionOf(part)).endsWith("Деталь подходит:\n" + LINE_2
                + ", любой двигатель (ELE1, ENG1, ENG2, GAS1, HYB1)");
    }

    @Test
    @DisplayName("Стык поколений: больше пересечение по годам, а поровну — позднее; открытый конец — «н.в.»")
    void boundaryPicksTheLargerOverlapThenTheLater() {
        // 2000–2007 почти целиком первое поколение Bazon (8 лет против одного года второго).
        assertThat(descriptionOf(part(true, row("2000-2007")))).endsWith(LINE_1);
        // 2007–2007 поровну в первом и втором — берётся позднее, второе.
        assertThat(descriptionOf(part(true, row("2007-2007")))).contains(LINE_2 + " двигатель ");
        // Без конца годов — третье, и его годы дописаны «н.в.».
        assertThat(descriptionOf(part(true, row("2022-0")))).endsWith(LINE_3);
    }

    @Test
    @DisplayName("Несколько машин — по строке на каждую в порядке применимости; одно поколение дважды — одна строка")
    void severalRowsKeepTheirOrder() {
        Long part = part(true, row("2000-2007"), row("2007-2009"), row("2010-2015"));

        String block = descriptionOf(part).substring(descriptionOf(part).indexOf("Деталь подходит:"));
        assertThat(block).isEqualTo("Деталь подходит:\n" + LINE_1 + "\n" + LINE_2 + " двигатель " + ENGINES_2);
    }

    @Test
    @DisplayName("Поколения без двигателей или без годов в справочнике блока не дают и не роняют прайс; остальные строки целы")
    void gapsDoNotBreakTheFileOrTheOtherLines() {
        Long onlyGaps = part(true, row("2030-2035"), row("1990-1995"));
        assertThat(descriptionOf(onlyGaps)).doesNotContain("подходит");

        Long mixed = part(true, row("2030-2035"), row("2022-0"));
        assertThat(descriptionOf(mixed)).endsWith("Деталь подходит:\n" + LINE_3);
    }

    @Test
    @DisplayName("Поколение не выбрано: модель без поколения и без годов строки не даёт; метка «N поколение» выбирает по имени")
    void noGenerationNoLineAndLabelPicksByName() {
        assertThat(descriptionOf(part(true, new Fit(modelId, null, null, null, null)))).doesNotContain("подходит");
        assertThat(descriptionOf(part(true, new Fit(modelId, null, "3 поколение", null, null))))
                .endsWith(LINE_3);
        assertThat(descriptionOf(part(true, new Fit(modelId, null, "9 поколение", null, null))))
                .doesNotContain("подходит");
    }

    @Test
    @DisplayName("Без применимости блока нет")
    void noApplicabilityNoBlock() {
        assertThat(descriptionOf(part(true))).isEqualTo("\nКомментарий клиента");
    }

    @Test
    @DisplayName("Перенесённый из файла Bazon блок сильнее собранного: уходит дословно")
    void carriedTextBeatsTheComposedOne() {
        Long part = part(true, row("2007-2009"));
        inTenant(() -> jdbc.update("""
                INSERT INTO part_drom_card (part_id, bazon_fits)
                VALUES (?, 'Деталь подходит:\nПеренесённая строка дословно')""", part));

        String description = descriptionOf(part);

        assertThat(description).contains("Деталь подходит:\nПеренесённая строка дословно")
                .doesNotContain("двигатель ELE1");
    }

    // ---------- вспомогательное ----------

    private record Fit(Long modelId, Long generationId, String label, Integer yearFrom, Integer yearTo) {
    }

    private Fit row(String ours) {
        return new Fit(modelId, OURS.get(ours), null, null, null);
    }

    private Long part(boolean engineMatters, Fit... fits) {
        return inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, description, price, is_published, condition,
                                      part_name_id, engine_matters)
                    VALUES (1, ?, 'Комментарий клиента', 1000, true, 'USED', ?, ?) RETURNING id""",
                    Long.class, "Позиция " + System.nanoTime(), kindName, engineMatters);
            ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouse, null));
            for (Fit fit : fits) {
                jdbc.update("""
                        INSERT INTO part_applicability (part_id, brand_id, model_id, generation_id,
                                                        generation_label, year_from, year_to)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""", id, brandId, fit.modelId(), fit.generationId(),
                        fit.label(), fit.yearFrom(), fit.yearTo());
            }
            return id;
        });
    }

    /** Описание позиции в собранном файле; сам файл разбирается и на валидность. */
    private String descriptionOf(Long partId) {
        Long number = inTenant(() -> jdbc.queryForObject(
                "SELECT number FROM part WHERE id = ?", Long.class, partId));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        inTenant(() -> generator.writeTo(out, DromPriceGenerator.FeedFilter.everything(), null, null,
                FeedSettings.none()));
        String xml = out.toString(StandardCharsets.UTF_8);
        int at = xml.indexOf("<id>" + number + "</id>");
        assertThat(at).as("позиции нет в файле").isNotNegative();
        String part = xml.substring(at, xml.indexOf("</part>", at));
        Matcher m = Pattern.compile("<description><!\\[CDATA\\[(.*?)]]></description>", Pattern.DOTALL)
                .matcher(part);
        return m.find() ? m.group(1) : "";
    }

    private <T> T inTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }

    private static long bazonGeneration(JdbcTemplate jdbc, int bazonId, long model, String name, String years,
                                        int from, Integer to, String series) {
        return jdbc.queryForObject("""
                INSERT INTO catalog.bazon_generation (bazon_id, bazon_model_id, name, years, year_from, year_to, series)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, bazonId, model, name, years, from, to, series);
    }

    private static void body(JdbcTemplate jdbc, int bazonId, long generation, String name, String description) {
        jdbc.update("""
                INSERT INTO catalog.bazon_body (bazon_id, bazon_generation_id, name, description)
                VALUES (?, ?, ?, ?)""", bazonId, generation, name, description);
    }

    private static long motor(JdbcTemplate jdbc, int bazonId, long mark, String name, Integer cc, String fuel) {
        return jdbc.queryForObject("""
                INSERT INTO catalog.bazon_motor (bazon_id, bazon_mark_id, name, volume_cc, fuel)
                VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class, bazonId, mark, name, cc, fuel);
    }

    private static void link(JdbcTemplate jdbc, long generation, long motor) {
        jdbc.update("INSERT INTO catalog.bazon_generation_motor (bazon_generation_id, bazon_motor_id) VALUES (?, ?)",
                generation, motor);
    }

    private static void removeCatalog(JdbcTemplate jdbc) {
        List<String> steps = List.of(
                "DELETE FROM catalog.bazon_generation_motor WHERE bazon_generation_id IN "
                        + "(SELECT id FROM catalog.bazon_generation WHERE bazon_id BETWEEN 9000000 AND 9000999)",
                "DELETE FROM catalog.bazon_body WHERE bazon_id BETWEEN 9000000 AND 9000999",
                "DELETE FROM catalog.bazon_generation WHERE bazon_id BETWEEN 9000000 AND 9000999",
                "DELETE FROM catalog.bazon_motor WHERE bazon_id BETWEEN 9000000 AND 9000999",
                "DELETE FROM catalog.bazon_model WHERE bazon_id BETWEEN 9000000 AND 9000999",
                "DELETE FROM catalog.bazon_mark WHERE bazon_id BETWEEN 9000000 AND 9000999",
                "DELETE FROM catalog.generation WHERE model_id IN (SELECT id FROM catalog.model WHERE slug = 'fitmodel')",
                "DELETE FROM catalog.model WHERE slug = 'fitmodel'",
                "DELETE FROM catalog.brand WHERE slug = 'fits0342'");
        steps.forEach(jdbc::update);
    }
}
