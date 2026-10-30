package ru.partsflow.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Числа в журнале действий и в истории позиции — разрядами, как на карточке
 * и в списках (задача 0384): «Пробег, км: 210 000 → 215 000», а не
 * «210000 → 215000».
 *
 * <p>Через HTTP, обеими поверхностями: журнал организации
 * ({@code GET /api/organization/audit}) и история позиции
 * ({@code GET /api/parts/{id}/history}) печатают значение одной функцией
 * ({@code AuditedColumns.display}), и проверяется то, что уезжает человеку.
 *
 * <p>Снимки кладутся в {@code audit_log} напрямую — тем видом, каким их пишет
 * слушатель ({@code jsonb} с числом), — потому что задача про показ, а путь
 * записи у каждой из правок свой и здесь не меняется.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class AuditNumbersGroupedTest extends PostgresTestBase {

    private static final String TENANT = "t_001384";

    /** Разделитель разрядов экрана: {@code toLocaleString('ru-RU')} ставит неразрывный пробел. */
    private static final String NBSP = " ";

    /** Типы колонок, значения которых — числа. */
    private static final List<String> NUMERIC = List.of(
            "numeric", "integer", "smallint", "bigint", "real", "double precision");

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

    private long partId;
    private long donorId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 1384");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (1384, ?, 'Разборка разрядов', 'razryady')""", TENANT);
        inTenant(() -> {
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'vladelec'",
                    Long.class).isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Владелец разрядов', 'OWNER', 'vladelec', ?)""",
                        passwordEncoder.encode("пароль"));
            }
            jdbc.update("DELETE FROM audit_log");
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price)
                    VALUES (1, 'Фара Toyota Camry 2006 перед. лев. (б/у)', 24300)
                    RETURNING id""", Long.class);
            donorId = jdbc.queryForObject(
                    "INSERT INTO donor (note) VALUES ('Камри под разряды') RETURNING id", Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Журнал: пробег разрядами, год без пробела")
    void journalGroupsMileageButNotYear() throws Exception {
        inTenant(() -> jdbc.update("""
                INSERT INTO audit_log (table_name, record_id, operation, old_value, new_value)
                VALUES ('donor', ?, 'UPDATE',
                        '{"mileage_km": 210000, "year": 2003}',
                        '{"mileage_km": 215000, "year": 2004}')""", donorId));

        Map<String, String> shown = journalChanges();
        assertThat(shown.get("Пробег, км"))
                .isEqualTo("210" + NBSP + "000 → 215" + NBSP + "000");
        assertThat(shown.get("Год выпуска"))
                .as("год — не число для чтения разрядами: «2 003» хуже исходного")
                .isEqualTo("2003 → 2004");
    }

    @Test
    @DisplayName("Журнал: дробное — запятой, как на карточке; деньги разрядами")
    void journalWritesFractionWithComma() throws Exception {
        inTenant(() -> jdbc.update("""
                INSERT INTO audit_log (table_name, record_id, operation, old_value, new_value)
                VALUES ('part', ?, 'UPDATE',
                        '{"price": 27000.00, "weight_kg": 12.500}',
                        '{"price": 24300, "weight_kg": 13.000}')""", partId));

        Map<String, String> shown = journalChanges();
        assertThat(shown.get("Цена")).isEqualTo("27" + NBSP + "000 → 24" + NBSP + "300");
        assertThat(shown.get("Вес, кг")).isEqualTo("12,5 → 13");
    }

    @Test
    @DisplayName("История позиции: «27 000 → 24 300 (−10 %)», процент не пропал")
    void partHistoryGroupsPriceAndKeepsPercent() throws Exception {
        inTenant(() -> jdbc.update("""
                INSERT INTO audit_log (table_name, record_id, operation, old_value, new_value)
                VALUES ('part', ?, 'UPDATE',
                        '{"price": 27000.00, "weight_kg": 12.500, "manual_year": 2003}',
                        '{"price": 24300, "weight_kg": 13.000, "manual_year": 2004}')""", partId));

        String body = mvc.perform(get("/api/parts/{id}/history", partId).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        for (JsonNode change : json.readTree(body).path("changes")) {
            for (JsonNode field : change.path("fields")) {
                fields.putIfAbsent(field.path("label").asText(), field);
            }
        }
        JsonNode price = fields.get("Цена");
        assertThat(price).as("правки цены в истории нет: %s", body).isNotNull();
        assertThat(price.path("before").asText() + " → " + price.path("after").asText()
                + " (" + price.path("delta").asText() + ")")
                .isEqualTo("27" + NBSP + "000 → 24" + NBSP + "300 (−10 %)");
        JsonNode weight = fields.get("Вес, кг");
        assertThat(weight.path("before").asText() + " → " + weight.path("after").asText())
                .isEqualTo("12,5 → 13");
        JsonNode year = fields.get("Год выпуска");
        assertThat(year.path("before").asText() + " → " + year.path("after").asText())
                .isEqualTo("2003 → 2004");
    }

    /**
     * Вид тот же, что у {@code toLocaleString('ru-RU')} на карточке; сверено
     * с Node 22 30.10.2026: «1 234», «-1 500», «1 234,5», «210 000».
     */
    @Test
    @DisplayName("Разряды как на экране: неразрывный пробел, запятая, знак; не число — как есть")
    void groupedLooksLikeTheScreen() {
        assertThat(AuditedColumns.grouped("999")).isEqualTo("999");
        assertThat(AuditedColumns.grouped("1234")).isEqualTo("1" + NBSP + "234");
        assertThat(AuditedColumns.grouped("-1500")).isEqualTo("-1" + NBSP + "500");
        assertThat(AuditedColumns.grouped("1234567.891"))
                .isEqualTo("1" + NBSP + "234" + NBSP + "567,891");
        assertThat(AuditedColumns.grouped("0.5")).isEqualTo("0,5");
        assertThat(AuditedColumns.grouped("1 200")).as("заметка с пробелом — не число").isEqualTo("1 200");
        assertThat(AuditedColumns.grouped("12345678901234567890"))
                .as("не помещается в long — показывается как есть, а не пятисоткой")
                .isEqualTo("12345678901234567890");
    }

    /**
     * Перебор: у каждой числовой колонки под журналом решено, как её печатать.
     *
     * <p>Без него правило стоит ровно до первой новой колонки: подпись в словаре
     * завели, в набор не внесли — и в журнале опять «210000». Ссылки
     * ({@code AuditedColumns.REFERENCES}) — третий вид: человек видит имя,
     * а не число, и печатать там разрядами нечего.
     */
    @Test
    @DisplayName("Каждая числовая колонка под журналом — в наборе «разряды» или «как есть»")
    void everyNumericJournalColumnIsClassified() {
        List<String[]> numeric = jdbc.query("""
                        SELECT table_name, column_name FROM information_schema.columns
                         WHERE table_schema = ? AND data_type = ANY (?::text[])
                         ORDER BY table_name, column_name""",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)},
                TENANT, "{" + String.join(",", NUMERIC.stream().map(t -> '"' + t + '"').toList()) + "}");
        assertThat(numeric).as("в схеме не нашлось числовых колонок — перебор ничего не проверил")
                .isNotEmpty();

        List<String> missing = new ArrayList<>();
        int watched = 0;
        for (String[] column : numeric) {
            Map<String, String> labels = "part_drom_card".equals(column[0])
                    ? AuditedColumns.PART_DROM_CARD : AuditedColumns.of(column[0]);
            if (!labels.containsKey(column[1])) {
                continue;
            }
            watched++;
            if (!AuditedColumns.GROUPED.contains(column[1])
                    && !AuditedColumns.AS_IS.contains(column[1])
                    && !AuditedColumns.REFERENCES.contains(column[1])) {
                missing.add(column[0] + "." + column[1] + " («" + labels.get(column[1]) + "»)");
            }
        }
        assertThat(watched).as("ни одной числовой колонки под журналом — перебор пуст")
                .isGreaterThan(10);
        assertThat(missing)
                .as("числовая колонка под журналом без решения, печатать ли её разрядами:"
                        + " внесите в AuditedColumns.GROUPED или AS_IS")
                .isEmpty();
        assertThat(AuditedColumns.GROUPED).as("колонка не может быть и тем, и другим")
                .doesNotContainAnyElementsOf(AuditedColumns.AS_IS);
    }

    /**
     * Обратная сторона: колонка из набора «разряды» нигде под журналом не текст.
     *
     * <p>Набор ведётся по имени колонки, а не по таблице; имя, которое у одной
     * таблицы число, а у другой код («номер» поставки — текст), разрядами
     * разрезало бы код. Такое имя обязано стоять в «как есть».
     */
    @Test
    @DisplayName("Колонка «разрядами» не бывает текстом ни в одной таблице под журналом")
    void groupedColumnIsNeverTextElsewhere() {
        List<String[]> text = jdbc.query("""
                        SELECT table_name, column_name FROM information_schema.columns
                         WHERE table_schema = ? AND NOT (data_type = ANY (?::text[]))""",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)},
                TENANT, "{" + String.join(",", NUMERIC.stream().map(t -> '"' + t + '"').toList()) + "}");
        List<String> clash = new ArrayList<>();
        for (String[] column : text) {
            Map<String, String> labels = "part_drom_card".equals(column[0])
                    ? AuditedColumns.PART_DROM_CARD : AuditedColumns.of(column[0]);
            if (labels.containsKey(column[1]) && AuditedColumns.GROUPED.contains(column[1])) {
                clash.add(column[0] + "." + column[1]);
            }
        }
        assertThat(clash).as("колонка «разрядами» у этих таблиц не число").isEmpty();
    }

    private Map<String, String> journalChanges() throws Exception {
        String body = mvc.perform(get("/api/organization/audit").session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, String> shown = new LinkedHashMap<>();
        for (JsonNode item : json.readTree(body).path("items")) {
            for (JsonNode change : item.path("changes")) {
                shown.putIfAbsent(change.path("label").asText(),
                        change.path("before").asText() + " → " + change.path("after").asText());
            }
        }
        assertThat(shown).as("в журнале нет правок: %s", body).isNotEmpty();
        return shown;
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"razryady","login":"vladelec","password":"пароль"}"""))
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
