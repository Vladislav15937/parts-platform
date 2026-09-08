package ru.partsflow.reports;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Колонка «Источник» в отчётах (задача 0046): сколько прошло наличными,
 * сколько картой, сколько осталось в долг.
 *
 * <p><b>Главная проверка одна — сумма по всем источникам за период равна итогу
 * оплат за тот же период.</b> Она ловит ровно то, ради чего отчёт и заводился:
 * разъехавшиеся выборки. Строки и итог считаются двумя независимыми запросами —
 * первый группирует по источнику и ходит в справочник, второй не делает
 * ни того, ни другого, — поэтому равенство здесь настоящее утверждение,
 * а не «сложили и сравнили с собой же».
 *
 * <p>Две вещи, из-за которых сумма разъезжается тише всего, проверяются
 * отдельно: платёж без источника (до задачи 0024 их не писали вовсе)
 * и платёж по источнику, снятому в архив (владелец наводит порядок
 * в справочнике, а период остаётся прошлым).
 *
 * <p>Своя схема, а не общая с соседями: отчёт считает все платежи арендатора
 * за месяц, и любой платёж, заведённый чужим тестом, сдвинул бы абсолютные
 * числа.
 *
 * <p>Через HTTP, а не вызовом сервиса: ответ уходит record'ами, и класс
 * в стиле record Jackson не сериализует — тест на сервис этого не увидит.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PaymentSourceReportTest extends PostgresTestBase {

    private static final String TENANT = "t_000118";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Месяц отчёта — прошлый: сегодняшний зависел бы от дня прогона. */
    private static final YearMonth MONTH = YearMonth.now().minusMonths(1);

    private Long customer;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 118");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (118, ?, 'Разборка', 'kassa')""", TENANT);

        inTenant(() -> {
            // Платежи считаются по всему арендатору за месяц: остатки прошлого
            // прогона сдвинули бы абсолютные числа.
            jdbc.update("DELETE FROM customer_account_entry");
            jdbc.update("DELETE FROM payment");
            jdbc.update("DELETE FROM payment_source");
            member("vladelec", "Владелец", "OWNER");
            member("prodavets", "Продавец", "SELLER");
            customer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id", Long.class);
            return null;
        });
    }

    /**
     * Весь отчёт одним тестом, и это не лень: числа здесь абсолютные —
     * по всем платежам арендатора за месяц, — и разложенные по методам шаги
     * зависели бы от порядка запуска.
     */
    @Test
    @DisplayName("Сумма по всем источникам равна итогу оплат за тот же период")
    void sourcesAddUpToThePeriodTotal() throws Exception {
        long cash = source("ККМ", "CASH");
        long sber = source("Карта Сбер", "BANK_ACCOUNT");
        // Вторая карта другого банка: по типу она неотличима от первой,
        // а владелец сверяет выписку по каждой отдельно.
        long tbank = source("Карта Т-Банк", "BANK_ACCOUNT");
        long credit = source("В долг", "CREDIT");

        payment("IN", 12_000, cash);
        payment("IN", 3_000, cash);
        payment("OUT", 1_500, cash);
        payment("IN", 40_000, sber);
        payment("IN", 7_000, tbank);
        payment("IN", 2_500, credit);
        // До задачи 0024 источник не писали вовсе: такие платежи есть
        // у каждого переехавшего клиента.
        payment("IN", 900, null);

        JsonNode report = report(login("vladelec"));

        // 1. Главное. Строки и итог посчитаны двумя независимыми запросами;
        //    сложенные строки обязаны дать то же самое число. Не сойдись они —
        //    владелец получил бы два разных ответа на один вопрос.
        assertThat(sum(report, "incoming"))
                .as("приход по источникам не сошёлся с итогом за период: %s", report)
                .isEqualByComparingTo(total(report, "incoming"));
        assertThat(sum(report, "outgoing"))
                .as("расход по источникам не сошёлся с итогом за период: %s", report)
                .isEqualByComparingTo(total(report, "outgoing"));
        assertThat(sum(report, "total")).isEqualByComparingTo(total(report, "total"));

        // 2. И само число настоящее, а не «ноль равен нулю»: 65 400 принято,
        //    1 500 отдано.
        assertThat(total(report, "incoming")).isEqualByComparingTo("65400");
        assertThat(total(report, "outgoing")).isEqualByComparingTo("1500");
        assertThat(total(report, "total")).isEqualByComparingTo("63900");

        // 3. Считается источник, а не его тип: две карты разных банков —
        //    две строки, хотя `source_type` у них один. Схлопни их отчёт
        //    по типу, здесь стояла бы одна строка на 47 000.
        assertThat(row(report, "Карта Сбер").path("incoming").decimalValue())
                .as("две карты разных банков схлопнулись в одну строку по типу: %s", report)
                .isEqualByComparingTo("40000");
        assertThat(row(report, "Карта Т-Банк").path("incoming").decimalValue())
                .as("две карты разных банков схлопнулись в одну строку по типу: %s", report)
                .isEqualByComparingTo("7000");

        // 4. Приход и расход разными числами: сумма платежа всегда
        //    положительная, знак несёт направление. Сложи их вместе —
        //    «прошло наличными» стало бы 16 500 вместо 15 000.
        JsonNode till = row(report, "ККМ");
        assertThat(till.path("incoming").decimalValue()).isEqualByComparingTo("15000");
        assertThat(till.path("outgoing").decimalValue()).isEqualByComparingTo("1500");
        assertThat(till.path("total").decimalValue()).isEqualByComparingTo("13500");
        assertThat(till.path("payments").asInt()).isEqualTo(3);

        // 5. Платёж без источника виден отдельной строкой, а не пропал:
        //    пустое имя, и сумма его в итог входит.
        JsonNode unknown = rows(report).stream()
                .filter(r -> r.path("sourceId").isNull())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "платёж без источника пропал из отчёта: " + report));
        assertThat(unknown.path("incoming").decimalValue()).isEqualByComparingTo("900");
    }

    /**
     * Архивный источник из отчёта не исчезает.
     *
     * <p>Владелец снимает способ с работы сегодня, а отчёт открывает
     * за прошлый месяц, когда им ещё платили. Отфильтруй мы справочник
     * по {@code is_archived}, месяц молча недосчитался бы этой суммы —
     * и заметить это можно было бы только по несходящемуся итогу.
     */
    @Test
    @DisplayName("Архивный источник остаётся в отчёте: платежи по нему были")
    void archivedSourceStaysInTheReport() throws Exception {
        MockHttpSession owner = login("vladelec");
        long old = source("Старая касса", "CASH");
        payment("IN", 5_000, old);

        mvc.perform(post("/api/payment-sources/" + old + "/archive").with(csrf()).session(owner))
                .andExpect(status().isOk());

        JsonNode report = report(owner);
        JsonNode archived = row(report, "Старая касса");
        assertThat(archived.path("archived").asBoolean())
                .as("отчёт обязан сказать, что источник снят с работы: %s", report)
                .isTrue();
        assertThat(archived.path("incoming").decimalValue()).isEqualByComparingTo("5000");
        assertThat(sum(report, "incoming")).isEqualByComparingTo(total(report, "incoming"));
    }

    /**
     * Соседний месяц в отчёт не попадает.
     *
     * <p>Без этого «сумма сходится с итогом» доказывала бы только то, что оба
     * запроса берут одинаково **всё**: границы периода у них разъехались бы
     * молча, и обе стороны равенства выросли бы вместе.
     */
    @Test
    @DisplayName("Платёж соседнего месяца в период не попадает — ни в строки, ни в итог")
    void neighbourMonthStaysOut() throws Exception {
        long cash = source("ККМ", "CASH");
        payment("IN", 1_000, cash);
        paymentAt("IN", 777, cash, MONTH.minusMonths(1).atDay(15));
        paymentAt("IN", 888, cash, MONTH.plusMonths(1).atDay(2));

        JsonNode report = report(login("vladelec"));
        assertThat(total(report, "incoming"))
                .as("в период затесался платёж соседнего месяца: %s", report)
                .isEqualByComparingTo("1000");
        assertThat(sum(report, "incoming")).isEqualByComparingTo(total(report, "incoming"));
    }

    /** Роль та же, что у остальных отчётов: там видно всю выручку смены. */
    @Test
    @DisplayName("Деньги по источникам видит владелец или менеджер, но не продавец")
    void sellerCannotReadPayments() throws Exception {
        mvc.perform(get("/api/reports/payments").session(login("prodavets")))
                .andExpect(status().isForbidden());
    }

    /** Пустой месяц — нули, а не пустой ответ и не пятисотка. */
    @Test
    @DisplayName("Месяц без единого платежа отвечает нулями")
    void emptyMonthAnswersZeros() throws Exception {
        JsonNode report = report(login("vladelec"), YearMonth.of(2000, 1));
        assertThat(rows(report)).isEmpty();
        assertThat(total(report, "incoming")).isEqualByComparingTo("0");
        assertThat(total(report, "outgoing")).isEqualByComparingTo("0");
        assertThat(total(report, "total")).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------

    private JsonNode report(MockHttpSession session) throws Exception {
        return report(session, MONTH);
    }

    private JsonNode report(MockHttpSession session, YearMonth month) throws Exception {
        var result = mvc.perform(get("/api/reports/payments?month=" + month).session(session))
                .andExpect(status().isOk())
                .andReturn();
        return new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<JsonNode> rows(JsonNode report) {
        List<JsonNode> rows = new ArrayList<>();
        report.path("rows").forEach(rows::add);
        return rows;
    }

    /** Сумма показанных строк по колонке — то, что владелец сложил бы глазами. */
    private static BigDecimal sum(JsonNode report, String field) {
        return rows(report).stream()
                .map(row -> row.path(field).decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal total(JsonNode report, String field) {
        JsonNode value = report.path("totals").path(field);
        assertThat(value.isNumber())
                .as("в итоге нет числа %s — пришло %s", field, report)
                .isTrue();
        return value.decimalValue();
    }

    private static JsonNode row(JsonNode report, String sourceName) {
        return rows(report).stream()
                .filter(r -> sourceName.equals(r.path("sourceName").asText(null)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "источник «" + sourceName + "» пропал из отчёта: " + report));
    }

    private long source(String name, String type) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO payment_source (name, source_type) VALUES (?, ?) RETURNING id""",
                Long.class, name, type));
    }

    /** Платёж кладётся прямо в журнал: отчёт читает `payment`, а не сделку. */
    private void payment(String direction, int amount, Long sourceId) {
        paymentAt(direction, amount, sourceId, MONTH.atDay(10));
    }

    private void paymentAt(String direction, int amount, Long sourceId, LocalDate day) {
        inTenant(() -> jdbc.update("""
                INSERT INTO payment (customer_id, payment_source_id, direction, amount, paid_at)
                VALUES (?, ?, ?, ?, ?::date)""",
                customer, sourceId, direction, BigDecimal.valueOf(amount), day));
    }

    private Long member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, displayName, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"kassa","login":"%s","password":"пароль"}"""
                                .formatted(login)))
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
