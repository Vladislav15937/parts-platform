package ru.partsflow.inventory;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.catalog.OemNumbers;
import ru.partsflow.inventory.OemRecount.Note;
import ru.partsflow.inventory.OemRecount.Options;
import ru.partsflow.inventory.OemRecount.Tally;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.platform.tenant.TenantSweep;
import ru.partsflow.support.PostgresTestBase;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Деление строки {@code part_oem} с несколькими кросс-номерами (задача 0319, решение
 * владельца продукта 18.10.2026 «1 - б») на настоящей схеме арендатора.
 *
 * <p>Строки заводятся прямой вставкой — так, как их оставила запись до 0309 или перенос
 * из Bazon, делящий только запятой: через карточку такую строку уже не завести.
 *
 * <p>Схема своя ({@code t_000960}, тот же номер в реестре): пересчёт трогает каждую строку
 * {@code part_oem} схемы, и чужие строки сделали бы числа отчёта лотереей. Русские буквы —
 * escape-последовательностями: в исходнике они неотличимы от латинских.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class OemRecountSplitTest extends PostgresTestBase {

    private static final String TENANT = "t_000960";
    private static final int REGISTRY_ID = 960;
    private static final String COMPANY = "oemsplit0319";

    /** «АНАЛОГ». */
    private static final String ANALOG = "АНАЛОГ";
    private static final String NBSP = " ";

    /**
     * Кросс-номера, как они лежат по строке на каждую — до деления. Набор нарочно
     * со всем, что умеет ввод: двойники, неразрывный пробел, повтор между строками
     * и с основным, «не номер», пустая часть, точка с запятой, переводы строки,
     * табуляция — и строка без разделителя посередине, чтобы её пришлось переставить.
     */
    private static final List<String> CROSS_ROWS = List.of(
            "8414012530, 8414012531",
            "С-111\t222Х",
            "666",
            NBSP + "333; 8414012530",
            ANALOG + ", 444",
            "a-1\nA1\r\n555",
            "777,,888",
            "999; p1");

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

    /** «8414012530, 8414012531» одной строкой кросс-номера. */
    private long kross;
    /** Весь набор {@link #CROSS_ROWS}, по строке на элемент. */
    private long many;
    /** Тот же текст в основном номере — делится с задачи 0324. */
    private long primarySplit;
    /** Основной с разделителем, его второй номер уже стоит кросс-номером, за ним ещё один. */
    private long primaryMerge;
    /** Основной, у которого первая часть «не номер», — не делится. */
    private long primaryWhole;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", REGISTRY_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка: деление номеров', ?)""", REGISTRY_ID, TENANT, COMPANY);
        inTenant(() -> {
            jdbc.update("DELETE FROM part_change");
            jdbc.update("DELETE FROM part_oem");
            jdbc.update("DELETE FROM audit_log WHERE table_name = 'part'");
            jdbc.update("DELETE FROM part");
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'vladelec0319'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Ольга Делимова', 'OWNER', 'vladelec0319', ?)""",
                        passwordEncoder.encode("пароль"));
            }
            kross = part("Компрессор кондиционера");
            oem(kross, "447220-3710", true);
            oem(kross, "8414012530, 8414012531", false);
            oem(kross, "447260-1234", false);

            many = part("Фара левая");
            oem(many, "P-1", true);
            CROSS_ROWS.forEach(raw -> oem(many, raw, false));

            primarySplit = part("Радиатор");
            oem(primarySplit, "8414012530, 8414012531", true);

            primaryMerge = part("Генератор");
            oem(primaryMerge, "8414012530, 8414012531", true);
            oem(primaryMerge, "8414012531", false);
            oem(primaryMerge, "447260-1234", false);

            primaryWhole = part("Капот");
            oem(primaryWhole, ANALOG + ", FP-3", true);
            return null;
        });
    }

    @Test
    @DisplayName("«8414012530, 8414012531» — два кросс-номера с правильным приведением, порядок прежний")
    void twoCrossNumbersInOneRowBecomeTwo() {
        List<Long> idsBefore = ids(kross);
        Tally tally = one(recount.run(new Options(true, TENANT, false)));

        assertThat(rowsOf(kross)).containsExactly(
                List.of("447220-3710", "4472203710", true),
                List.of("8414012530", "8414012530", false),
                List.of("8414012531", "8414012531", false),
                List.of("447260-1234", "4472601234", false));
        List<Long> idsAfter = ids(kross);
        assertThat(idsAfter.subList(0, 2)).as("основной и первая часть — на прежних строках")
                .isEqualTo(idsBefore.subList(0, 2));
        assertThat(idsAfter).as("порядок ORDER BY id — порядок кросс-номеров").isSorted();
        assertThat(tally.splitLines).anySatisfy(line -> assertThat(line)
                .contains("№ позиции").contains("«8414012530, 8414012531» → «8414012530» | «8414012531»"));
    }

    @Test
    @DisplayName("Одно правило с вводом: пересчёт даёт то же, что карточка на тех же кросс-номерах")
    void recountEqualsTheCardOnTheSameInput() throws Exception {
        long card = inTenant(() -> part("Фара левая (через карточку)"));
        mvc.perform(put("/api/parts/" + card).with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "oem", "P-1", "crosses", String.join(", ", CROSS_ROWS)))))
                .andExpect(status().isOk());

        one(recount.run(new Options(true, TENANT, false)));

        List<List<Object>> written = rowsOf(card);
        assertThat(written).as("карточка ничего не записала — сравнивать не с чем").hasSizeGreaterThan(5);
        assertThat(rowsOf(many)).isEqualTo(written);
        assertThat(ids(many)).isSorted();
    }

    @Test
    @DisplayName("Часть, уже имеющаяся у позиции, дубля не плодит; основной цел; отчёт отдельными числами")
    void reportCountsSplitsSeparately() {
        Tally dry = one(recount.run(new Options(false, TENANT, false)));
        // kross: 1 строка → 2 номера. many: 7 строк с разделителем
        // (8414012530|8414012531, C-111|222X, 333|8414012530, АНАЛОГ|444, a-1|A1|555, 777|888, 999|p1).
        assertThat(dry.splitRows).isEqualTo(8);
        assertThat(dry.splitNumbers).as("номеров, породивших строку").isEqualTo(2 + 2 + 2 + 1 + 1 + 2 + 2 + 1);
        assertThat(dry.splitMerged).as("8414012530 повтором, A1 повтором a-1, p1 — основной").isEqualTo(3);
        assertThat(dry.noted(Note.SPLIT_NOT_A_NUMBER)).isEqualTo(1);
        assertThat(dry.noted(Note.SPLIT_EMPTY)).isEqualTo(1);
        assertThat(dry.splitSkipped()).isEqualTo(2);
        assertThat(dry.primarySplitRows).as("разделено основных").isEqualTo(2);
        assertThat(dry.primarySplitCrosses).as("получено кросс-номеров из основных").isEqualTo(2);
        assertThat(dry.primarySplitMerged).as("8414012531 уже стоял кросс-номером у генератора").isEqualTo(1);
        assertThat(dry.primarySplitSkipped).isZero();
        assertThat(dry.noted(Note.PRIMARY_FIRST_NOT_A_NUMBER)).as("«АНАЛОГ, FP-3» не разделён").isEqualTo(1);
        assertThat(dry.splitLines).as("каждая разделяемая строка — в список для просмотра").hasSize(8 + 2);
        assertThat(dry.splitLines).anySatisfy(line -> assertThat(line).contains("(номер производителя)")
                .contains("«8414012530, 8414012531» → основной «8414012530», кросс-номера «8414012531»"));

        Tally applied = one(recount.run(new Options(true, TENANT, false)));
        assertThat(applied.splitRows).isEqualTo(dry.splitRows);
        assertThat(applied.splitNumbers).isEqualTo(dry.splitNumbers);
        assertThat(applied.splitMerged).isEqualTo(dry.splitMerged);
        assertThat(applied.rowsInserted).isEqualTo(dry.rowsInserted);
        assertThat(applied.rowsMoved).isEqualTo(dry.rowsMoved);
        assertThat(applied.splitLines).isEqualTo(dry.splitLines);
        assertThat(applied.primarySplitRows).isEqualTo(dry.primarySplitRows);
        assertThat(applied.primarySplitCrosses).isEqualTo(dry.primarySplitCrosses);
        assertThat(applied.primarySplitMerged).isEqualTo(dry.primarySplitMerged);
        assertThat(applied.restore()).isEqualTo(dry.restore());

        List<List<Object>> rows = rowsOf(many);
        assertThat(rows.stream().filter(r -> (boolean) r.get(2)).toList()).as("основной не потерян")
                .containsExactly(List.of("P-1", "P1", true));
        assertThat(rows.stream().map(r -> r.get(1)).distinct().count()).as("повторов нет").isEqualTo(rows.size());
        assertThat(rows.stream().filter(r -> r.get(0).equals("8414012530")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("0324: основной «8414012530, 8414012531» — основной 8414012530 на прежней строке, 8414012531 кросс-номером")
    void primaryWithSeparatorIsSplit() {
        List<Long> idsBefore = ids(primarySplit);
        one(recount.run(new Options(true, TENANT, false)));
        assertThat(rowsOf(primarySplit)).containsExactly(
                List.of("8414012530", "8414012530", true),
                List.of("8414012531", "8414012531", false));
        assertThat(ids(primarySplit).get(0)).as("основной — на прежней строке").isEqualTo(idsBefore.get(0));
    }

    @Test
    @DisplayName("0324: часть основного, уже стоящая кросс-номером, сливается; лишние — первыми, остальные кросс-номера за ними")
    void primaryPartMergesAndExtrasGoFirst() {
        one(recount.run(new Options(true, TENANT, false)));
        assertThat(rowsOf(primaryMerge)).containsExactly(
                List.of("8414012530", "8414012530", true),
                List.of("8414012531", "8414012531", false),
                List.of("447260-1234", "4472601234", false));
        assertThat(ids(primaryMerge)).isSorted();
    }

    @Test
    @DisplayName("0324: первая часть основного «не номер» — строка целиком, причина в отчёте")
    void primaryFirstNotANumberStaysWhole() {
        Tally tally = one(recount.run(new Options(true, TENANT, false)));
        assertThat(rowsOf(primaryWhole)).containsExactly(
                List.of(ANALOG + ", FP-3", OemNumbers.normalize(ANALOG + ", FP-3"), true));
        assertThat(tally.lines).anySatisfy(line -> assertThat(line)
                .startsWith("позиция " + primaryWhole + ",").contains(Note.PRIMARY_FIRST_NOT_A_NUMBER.words()));
    }

    @Test
    @DisplayName("0324: одно правило с вводом — пересчёт основного даёт то же, что карточка (0323) на том же тексте")
    void primaryRecountEqualsTheCard() throws Exception {
        long card = inTenant(() -> part("Генератор (через карточку)"));
        mvc.perform(put("/api/parts/" + card).with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "oem", "8414012530, 8414012531", "crosses", "8414012531, 447260-1234"))))
                .andExpect(status().isOk());

        one(recount.run(new Options(true, TENANT, false)));

        assertThat(rowsOf(primaryMerge)).isEqualTo(rowsOf(card));
    }

    @Test
    @DisplayName("0324: журнал пересчёта называет обе перемены — номер производителя и кросс-номера")
    void primarySplitIsJournaled() {
        one(recount.run(new Options(true, TENANT, false)));
        java.util.Map<String, Object> entry = inTenant(() -> jdbc.queryForMap("""
                SELECT old_value->>'oem' AS was, new_value->>'oem' AS now, new_value->>'crosses' AS crosses
                  FROM audit_log WHERE table_name = 'part' AND record_id = ? ORDER BY id DESC LIMIT 1""",
                primarySplit));
        assertThat(entry.get("was")).isEqualTo("8414012530, 8414012531");
        assertThat(entry.get("now")).isEqualTo("8414012530");
        assertThat(entry.get("crosses")).isEqualTo("8414012531");
    }

    @Test
    @DisplayName("Вперёд и назад: после возврата файлом part_oem та же до строки и номера строки")
    void restoreReturnsTheOriginalRows() {
        List<List<Object>> before = all();
        Tally tally = one(recount.run(new Options(true, TENANT, false)));
        assertThat(all()).isNotEqualTo(before);

        inTenant(() -> {
            tally.restore().forEach(jdbc::update);
            return null;
        });
        assertThat(all()).isEqualTo(before);
    }

    @Test
    @DisplayName("Повтор запуска ничего не меняет; проверка ничего не пишет")
    void repeatChangesNothingAndDryRunWritesNothing() {
        List<List<Object>> before = all();
        one(recount.run(new Options(false, TENANT, true)));
        assertThat(all()).as("проверка изменила part_oem").isEqualTo(before);
        assertThat(count("SELECT count(*) FROM part_change")).isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE table_name = 'part'")).isZero();

        one(recount.run(new Options(true, TENANT, false)));
        List<List<Object>> after = all();
        Tally again = one(recount.run(new Options(true, TENANT, false)));
        assertThat(again.nothingToDo()).isTrue();
        assertThat(again.splitRows + again.rowsInserted + again.rowsMoved).isZero();
        assertThat(all()).isEqualTo(after);
    }

    // ------------------------------------------------------------------ помощники

    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    private static Tally one(List<TenantSweep.Outcome<Tally>> outcomes) {
        assertThat(outcomes).singleElement().satisfies(o -> assertThat(o.problem()).isNull());
        return outcomes.get(0).result();
    }

    private long part(String title) {
        return jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, is_published)
                VALUES (1, ?, 5000, true) RETURNING id""", Long.class, title);
    }

    /** Строка как до 0309: приведение — всей строкой, как его считала генерируемая колонка. */
    private void oem(long part, String raw, boolean primary) {
        jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary) VALUES (?, ?, ?, ?)",
                part, raw, OemNumbers.normalize(raw), primary);
    }

    private List<List<Object>> rowsOf(long part) {
        return inTenant(() -> jdbc.query(
                "SELECT raw_number, normalized, is_primary FROM part_oem WHERE part_id = ? ORDER BY id",
                (rs, i) -> List.<Object>of(rs.getString(1), rs.getString(2), rs.getBoolean(3)), part));
    }

    private List<Long> ids(long part) {
        return inTenant(() -> jdbc.queryForList(
                "SELECT id FROM part_oem WHERE part_id = ? ORDER BY id", Long.class, part));
    }

    private List<List<Object>> all() {
        return inTenant(() -> jdbc.query(
                "SELECT id, part_id, raw_number, normalized, is_primary FROM part_oem ORDER BY id",
                (rs, i) -> List.<Object>of(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getBoolean(5))));
    }

    private long count(String sql) {
        return inTenant(() -> jdbc.queryForObject(sql, Long.class));
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"vladelec0319","password":"пароль"}"""
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
