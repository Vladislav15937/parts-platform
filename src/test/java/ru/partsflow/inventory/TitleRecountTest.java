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
import ru.partsflow.inventory.TitleRecount.Note;
import ru.partsflow.inventory.TitleRecount.Options;
import ru.partsflow.inventory.TitleRecount.Tally;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.platform.tenant.TenantSweep;
import ru.partsflow.publishing.drom.DromPriceGenerator;
import ru.partsflow.support.PostgresTestBase;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Разовый пересчёт хранимых названий по формуле Bazon (задача 0336, пункт 4)
 * на настоящей схеме арендатора.
 *
 * <p>Позиции заводятся прямой вставкой в том виде, в каком их оставил перенос
 * до 0336: вид из эталона («стеклоподъёмника» с «ё»), год числом, без «(б/у)».
 * Через HTTP так уже не завести — запись собирает название сама.
 * Примеры обезличены: номера и машины придуманы.
 *
 * <p>Схема своя ({@code t_001336}, тот же номер в реестре): пересчёт читает
 * каждую позицию схемы, и чужие сделали бы числа отчёта лотереей.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class TitleRecountTest extends PostgresTestBase {

    private static final String TENANT = "t_001336";
    private static final int REGISTRY_ID = 1336;
    private static final String COMPANY = "titlerecount0336";

    /** Перенесена из Bazon с донором: вид клиента без «ё», год текстом, «(б/у)». */
    private static final String DONOR_WAS =
            "Кнопка стеклоподъёмника Toyota Camry ACV30 2AZFE 2003 задн. лев. 12345-AB000";
    private static final String DONOR_NOW =
            "Кнопка стеклоподъемника Toyota Camry ACV30 2AZFE 03 задн. лев. (б/у) 12345-AB000";
    /** Уже по формуле — новая, без пометки. */
    private static final String FORMULA = "Фара Toyota Camry ACV30 2AZFE 2004";
    /** Машины только в названии — не трогается. */
    private static final String IN_TITLE = "Блок круиз-контроля Lincoln,Ford Navigator UN173 (б/у)";
    /** Без машины и у Bazon — уже по формуле. */
    private static final String NO_CAR = "Ремень поликлиновой (б/у) 4PK905";
    /** Перенесена с Дрома: вид — заголовок объявления целиком. */
    private static final String DROM_WAS = "Стартер Toyota Camry 2.4";
    private static final String DROM_NOW = "Стартер Toyota Camry 2.4 (б/у) 28100-0H100";
    private static final String NO_KIND = "Позиция без вида";
    /** Своя машина у перенесённой без донора. */
    private static final String OWN_WAS = "Дефлектор Toyota,Lexus Camry";
    private static final String OWN_NOW = "Дефлектор Toyota Camry (б/у)";
    private static final String WHEEL = "Шина 195/65 R15";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TitleRecount recount;
    @Autowired
    private StockLedger ledger;
    @Autowired
    private DromPriceGenerator generator;
    @Autowired
    private DataSource dataSource;

    private long donorPart;
    private long formula;
    private long inTitle;
    private long noCar;
    private long drom;
    private long noKind;
    private long own;
    private long wheel;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", REGISTRY_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка: пересчёт названий', ?)""", REGISTRY_ID, TENANT, COMPANY);
        inTenant(() -> {
            jdbc.update("DELETE FROM part_change");
            jdbc.update("DELETE FROM part_oem");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM audit_log WHERE table_name = 'part'");
            jdbc.update("DELETE FROM part");
            jdbc.update("DELETE FROM donor");
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'vladelec0336'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Ольга Названова', 'OWNER', 'vladelec0336', ?)""",
                        passwordEncoder.encode("пароль"));
            }
            long warehouse;
            List<Long> existing = jdbc.queryForList("SELECT id FROM warehouse", Long.class);
            if (existing.isEmpty()) {
                Long branch = jdbc.queryForObject(
                        "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
                warehouse = jdbc.queryForObject(
                        "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад') RETURNING id",
                        Long.class, branch);
            } else {
                warehouse = existing.get(0);
            }
            Long donor = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, model_id, body_code, engine_code, year, status)
                    VALUES ((SELECT id FROM catalog.brand WHERE slug = 'toyota'),
                            (SELECT m.id FROM catalog.model m JOIN catalog.brand b ON b.id = m.brand_id
                              WHERE b.slug = 'toyota' AND m.slug = 'camry'),
                            'ACV30', '2AZFE', 2004, 'DISMANTLING')
                    RETURNING id""", Long.class);

            donorPart = part("Кнопка стеклоподъемника", DONOR_WAS, "USED");
            jdbc.update("""
                    UPDATE part SET legacy_code = 'B-0336-1', donor_id = ?, year_text = '03-08',
                                    side_fr = 'REAR', side_lr = 'LEFT' WHERE id = ?""", donor, donorPart);
            oem(donorPart, "12345-AB000", "12345AB000");

            formula = part("Фара", FORMULA, "NEW");
            jdbc.update("UPDATE part SET donor_id = ? WHERE id = ?", donor, formula);

            inTitle = part("Блок круиз-контроля", IN_TITLE, "CONTRACT");
            jdbc.update("UPDATE part SET legacy_code = 'B-0336-3' WHERE id = ?", inTitle);

            noCar = part("Ремень поликлиновой", NO_CAR, "USED");
            jdbc.update("UPDATE part SET legacy_code = 'B-0336-4' WHERE id = ?", noCar);
            oem(noCar, "4PK905", "4PK905");

            drom = part(DROM_WAS, DROM_WAS, "USED");
            jdbc.update("UPDATE part SET drom_ad_number = '900000336' WHERE id = ?", drom);
            oem(drom, "28100-0H100", "281000H100");

            noKind = part(null, NO_KIND, "USED");

            own = part("Дефлектор", OWN_WAS, "CONTRACT");
            jdbc.update("""
                    UPDATE part SET legacy_code = 'B-0336-7', manual_brand = 'Toyota', manual_model = 'Camry'
                     WHERE id = ?""", own);

            wheel = part(null, WHEEL, "USED");
            jdbc.update("UPDATE part SET product_line = 'WHEEL' WHERE id = ?", wheel);

            for (long id : List.of(donorPart, formula, inTitle, noCar, drom, noKind, own)) {
                ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouse, null));
            }
            return null;
        });
    }

    @Test
    @DisplayName("Проверка ничего не пишет и печатает те же числа, что потом сделает --apply")
    void dryRunWritesNothingAndPredictsApply() {
        Map<Long, String> before = titles();
        long journalBefore = count("SELECT count(*) FROM audit_log");
        long marksBefore = count("SELECT count(*) FROM part_change");
        String updatedBefore = inTenant(() -> jdbc.queryForObject(
                "SELECT string_agg(id || ':' || updated_at, ',' ORDER BY id) FROM part", String.class));

        Tally dry = one(recount.run(new Options(false, TENANT, true)));

        assertThat(titles()).as("проверка изменила названия").isEqualTo(before);
        assertThat(count("SELECT count(*) FROM audit_log")).isEqualTo(journalBefore);
        assertThat(count("SELECT count(*) FROM part_change")).isEqualTo(marksBefore);
        assertThat(dry.parts).as("колесо не читается как запчасть").isEqualTo(7);
        assertThat(dry.wheels).isEqualTo(1);
        assertThat(dry.changed).as("с донором, с Дрома, своя машина").isEqualTo(3);
        assertThat(dry.unchanged).as("уже по формуле и без машины у Bazon").isEqualTo(2);
        assertThat(dry.noted(Note.NO_KIND)).isEqualTo(1);
        assertThat(dry.noted(Note.VEHICLE_ONLY_IN_TITLE)).isEqualTo(1);
        assertThat(dry.lines).as("строки «было → стало» для просмотра человеком")
                .anyMatch(l -> l.contains("«" + DONOR_WAS + "» → «" + DONOR_NOW + "»"));
        assertThat(dry.skipped).as("не тронутое называется с причиной")
                .anyMatch(l -> l.contains(IN_TITLE) && l.contains("машины лежат только в названии"))
                .anyMatch(l -> l.contains(NO_KIND) && l.contains("нет вида"));

        assertThat(dry.restore).as("проверка печатает возврат того, чего не записала").isEmpty();
        assertThat(dry.feedMarks).as("с --feed-marks проверка называет, сколько отметок поставит")
                .isEqualTo(3);
        assertThat(one(recount.run(new Options(false, TENANT, false))).feedMarks)
                .as("без --feed-marks отметок нет — и число их ноль, а не «для площадки 3»")
                .isZero();

        Tally applied = one(recount.run(new Options(true, TENANT, false)));
        assertThat(applied.changed).isEqualTo(dry.changed);
        assertThat(applied.unchanged).isEqualTo(dry.unchanged);
        assertThat(applied.notes).isEqualTo(dry.notes);
        assertThat(applied.lines).isEqualTo(dry.lines);
        assertThat(applied.restore).as("возврат — по строке на записанное").hasSize(3);
        assertThat(applied.feedMarks).as("без --feed-marks").isZero();
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT string_agg(id || ':' || updated_at, ',' ORDER BY id) FROM part", String.class)))
                .as("«дата правки» не сдвинута: названия правил не человек")
                .isEqualTo(updatedBefore);
    }

    @Test
    @DisplayName("После --apply — названия по формуле, не тронутое как было; повтор ничего не меняет")
    void applyWritesTheFormulaAndIsIdempotent() {
        one(recount.run(new Options(true, TENANT, false)));

        Map<Long, String> after = titles();
        assertThat(after.get(donorPart)).isEqualTo(DONOR_NOW);
        assertThat(after.get(drom)).as("с Дрома — по формуле, как все").isEqualTo(DROM_NOW);
        assertThat(after.get(own)).isEqualTo(OWN_NOW);
        assertThat(after.get(formula)).isEqualTo(FORMULA);
        assertThat(after.get(noCar)).isEqualTo(NO_CAR);
        assertThat(after.get(inTitle)).as("машины, лежавшие только в названии, стёрты").isEqualTo(IN_TITLE);
        assertThat(after.get(noKind)).isEqualTo(NO_KIND);
        assertThat(after.get(wheel)).isEqualTo(WHEEL);

        Tally again = one(recount.run(new Options(true, TENANT, false)));
        assertThat(again.nothingToDo()).as("второй --apply что-то изменил: %s", again.lines).isTrue();
        Tally dryAfter = one(recount.run(new Options(false, TENANT, false)));
        assertThat(dryAfter.changed).isZero();
        assertThat(dryAfter.noted(Note.VEHICLE_ONLY_IN_TITLE)).as("не тронутое называется и после")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Перенесённые из Bazon без донора и своей машины — название из файла, пересчёт его не меняет")
    void transferredWithoutVehicleKeepTheirTitle() {
        // Решение владельца 22.10.2026 (tasks/0336, «Ответ владельца — 22 октября 2026», п. 2):
        // название таких позиций — то, что в их файле. Разные формы, как они лежат после
        // переноса: списки машин, без пометки, с годом числом, новая с номером.
        Map<String, String> transferred = new java.util.LinkedHashMap<>();
        transferred.put("Блок управления", "Блок управления Toyota,Lexus Harrier,RX300 MCU15 1MZFE 1999 12345-00000");
        transferred.put("Насос гидроусилителя", "Насос гидроусилителя Nissan Teana J31 VQ23DE (б/у)");
        transferred.put("Крышка багажника", "Крышка багажника Mazda Atenza GG3S 2004");
        Map<Long, String> made = new java.util.LinkedHashMap<>();
        inTenant(() -> {
            int i = 0;
            for (Map.Entry<String, String> e : transferred.entrySet()) {
                long id = part(e.getKey(), e.getValue(), i == 2 ? "NEW" : "CONTRACT");
                jdbc.update("UPDATE part SET legacy_code = ?, year_text = '03-08' WHERE id = ?",
                        "B-0336-T" + i++, id);
                made.put(id, e.getValue());
            }
            return null;
        });

        Tally applied = one(recount.run(new Options(true, TENANT, false)));

        Map<Long, String> after = titles();
        made.forEach((id, title) -> assertThat(after.get(id)).as("название из файла переписано").isEqualTo(title));
        assertThat(applied.noted(Note.VEHICLE_ONLY_IN_TITLE)).as("названы в отчёте все четыре, с прежней")
                .isEqualTo(4);
        assertThat(applied.skipped).allMatch(l -> !l.contains(NO_CAR),
                "уже по формуле — не «не тронута», а «без изменений»");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE table_name = 'part' AND record_id = ANY (?)",
                Long.class, (Object) made.keySet().toArray(Long[]::new))))
                .as("в журнал правка, которой не было").isZero();
    }

    @Test
    @DisplayName("Правка карточки после пересчёта собирает то же название: формула одна")
    void cardEditAfterRecountKeepsTheTitle() throws Exception {
        one(recount.run(new Options(true, TENANT, false)));
        MockHttpSession session = login();
        // «Контракт» вместо «б/у»: поле формулы стало другим, название пересобирается,
        // а пометка та же — значит, разница могла бы прийти только от другой формулы
        // (год числом донора вместо «Года выпуска» текстом).
        mvc.perform(put("/api/parts/{id}", donorPart).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"condition\":\"CONTRACT\"}"))
                .andExpect(status().isOk());
        assertThat(titles().get(donorPart)).isEqualTo(DONOR_NOW);
    }

    @Test
    @DisplayName("Правка названия — в журнале изменений позиции, как правка карточки")
    void changeIsInThePartHistory() throws Exception {
        one(recount.run(new Options(true, TENANT, false)));
        mvc.perform(get("/api/parts/{id}/history", donorPart).session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].fields[0].label").value("Наименование"))
                .andExpect(jsonPath("$.changes[0].fields[0].before").value(DONOR_WAS))
                .andExpect(jsonPath("$.changes[0].fields[0].after").value(DONOR_NOW));
    }

    @Test
    @DisplayName("Отметки для площадки — только с --feed-marks; файл Дрома от пересчёта не меняется")
    void feedMarksOnlyByKeyAndDromFileIsTheSame() {
        String fileBefore = dromFile();
        assertThat(fileBefore).as("контроль: позиции в файле есть").contains("<part>");

        // Приход ставит отметки сам — снимаем их, чтобы считать только пересчёт.
        inTenant(() -> jdbc.update("DELETE FROM part_change"));
        one(recount.run(new Options(true, TENANT, false)));
        assertThat(count("SELECT count(*) FROM part_change")).as("отметки без ключа").isZero();
        assertThat(dromFile()).as("хранимое название ушло в файл Дрома").isEqualTo(fileBefore);

        inTenant(() -> jdbc.update("UPDATE part SET title = ? WHERE id = ?", DONOR_WAS, donorPart));
        one(recount.run(new Options(true, TENANT, true)));
        assertThat(inTenant(() -> jdbc.queryForList("SELECT part_id FROM part_change", Long.class)))
                .containsExactly(donorPart);
    }

    @Test
    @DisplayName("Инструкция возврата возвращает прежнее название")
    void restoreSqlBringsTheOldTitleBack() {
        Tally applied = one(recount.run(new Options(true, TENANT, false)));
        String restore = applied.restore.stream().filter(sql -> sql.contains("WHERE id = " + donorPart + " "))
                .findFirst().orElseThrow();
        assertThat(restore).startsWith("UPDATE " + TENANT + ".part SET title = ");
        jdbc.execute(restore);
        assertThat(titles().get(donorPart)).isEqualTo(DONOR_WAS);
    }

    @Test
    @DisplayName("Позицию держит другая правка — не ждём и не перезаписываем, называем; повтор доделывает")
    void lockedPartIsSkippedAndRepeatFinishes() throws Exception {
        Tally applied;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute(
                    "SELECT id FROM " + TENANT + ".part WHERE id = " + donorPart + " FOR UPDATE");
            applied = one(recount.run(new Options(true, TENANT, false)));
            holder.rollback();
        }
        assertThat(applied.busy).isEqualTo(1);
        assertThat(applied.changed).isEqualTo(2);
        assertThat(applied.lines).anyMatch(l -> l.contains("правлена во время запуска"));
        assertThat(titles().get(donorPart)).isEqualTo(DONOR_WAS);
        assertThat(titles().get(drom)).isEqualTo(DROM_NOW);

        Tally again = one(recount.run(new Options(true, TENANT, false)));
        assertThat(again.changed).isEqualTo(1);
        assertThat(titles().get(donorPart)).isEqualTo(DONOR_NOW);
    }

    // ------------------------------------------------------------------ помощники

    private static Tally one(List<TenantSweep.Outcome<Tally>> outcomes) {
        assertThat(outcomes).singleElement().satisfies(o -> assertThat(o.problem()).isNull());
        return outcomes.get(0).result();
    }

    private long part(String kind, String title, String condition) {
        Long name = kind == null ? null : jdbc.queryForObject("""
                INSERT INTO part_name (name) VALUES (?)
                ON CONFLICT (lower(btrim(name))) DO UPDATE SET name = EXCLUDED.name
                RETURNING id""", Long.class, kind);
        return jdbc.queryForObject("""
                INSERT INTO part (category_id, part_name_id, title, price, is_published, condition)
                VALUES (1, ?, ?, 5000, true, ?) RETURNING id""", Long.class, name, title, condition);
    }

    private void oem(long part, String raw, String normalized) {
        jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary) VALUES (?, ?, ?, true)",
                part, raw, normalized);
    }

    private Map<Long, String> titles() {
        return inTenant(() -> {
            Map<Long, String> found = new java.util.LinkedHashMap<>();
            jdbc.query("SELECT id, title FROM part ORDER BY id",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                            found.put(rs.getLong(1), rs.getString(2)));
            return found;
        });
    }

    private String dromFile() {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out);
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    private long count(String sql) {
        return inTenant(() -> jdbc.queryForObject(sql, Long.class));
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"vladelec0336","password":"пароль"}"""
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
