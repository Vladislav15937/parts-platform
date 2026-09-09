package ru.partsflow.sales;

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
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Реестр платежей — раздел «Платежи» (задача 0045).
 *
 * <p><b>Главная проверка одна: итог сходится с приходом и расходом.</b> Сумма
 * платежа в базе всегда положительная, знак несёт направление — и стоит знаку
 * у расхода разъехаться, как касса покажет больше денег, чем через неё
 * прошло. Итог сервер считает **своим запросом** (`CASE WHEN direction = 'IN'
 * THEN amount ELSE -amount END`), приход и расход — своими; сложенные из
 * одного выражения, они подтверждали бы сами себя.
 *
 * <p>Вторая проверка того же свойства — воронки: сумма «Приходных»
 * и «Расходных» обязана дать итог «Всех».
 *
 * <p>Три платежа заводятся <b>через API теми же операциями, что делает
 * человек</b> — принять оплату, оформить возврат денег, пополнить лицевой
 * счёт: реестр должен показать их с верным знаком и с верным источником,
 * а вставка строк прямо в таблицу этого не доказала бы. Четвёртый платёж,
 * без источника, кладётся SQL — так выглядит история переехавшего клиента
 * до задачи 0024, и потерять её реестр не имеет права.
 *
 * <p>Своя схема, а не общая с {@code SalesControllerTest}: числа здесь
 * абсолютные — по всем платежам арендатора, — и любой платёж соседнего
 * теста сдвинул бы их.
 *
 * <p>Через HTTP, а не вызовом сервиса: ответ уходит record'ами, и класс
 * в стиле record Jackson не сериализует — тест на сервис этого не увидит.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PaymentRegistryTest extends PostgresTestBase {

    private static final String TENANT = "t_000123";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ru.partsflow.inventory.StockLedger ledger;

    private Long warehouse;
    private Long customer;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 123");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (123, ?, 'Разборка', 'kassareg')""", TENANT);

        inTenant(() -> {
            // Числа реестра абсолютные — по всем платежам арендатора, — и схема
            // у методов класса общая: остатки прошлого метода сдвинули бы их.
            // Справочник источников чистится по той же причине и в этом
            // порядке: имя источника уникально, а платёж на него ссылается.
            jdbc.update("DELETE FROM customer_account_entry");
            jdbc.update("DELETE FROM payment");
            jdbc.update("DELETE FROM payment_source");
            member("vladelec", "Владелец", "OWNER");
            member("prodavets", "Продавец", "SELLER");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            customer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id", Long.class);
            return null;
        });
    }

    /**
     * Всё одним тестом, и это не лень: числа абсолютные — по всем платежам
     * арендатора, — а разложенные по методам шаги зависели бы от порядка
     * запуска.
     */
    @Test
    @DisplayName("Оплата, возврат денег и пополнение счёта видны в реестре с верным знаком")
    void registryShowsMoneyWithTheRightSign() throws Exception {
        long cash = source("ККМ", "CASH");
        MockHttpSession seller = login("prodavets");

        // 1. Принять оплату: деталь за 5 000 продана и оплачена наличными.
        Long partId = partWithStock("Фара для кассы", 1);
        long dealId = createDeal(partId, seller);
        long itemId = firstItemId(dealId, seller);
        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(seller))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deals/" + dealId + "/payments").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":5000,"paymentSourceId":%d}""".formatted(cash)))
                .andExpect(status().isCreated());

        // 2. Пополнить лицевой счёт: клиент оставил тысячу авансом.
        mvc.perform(post("/api/customers/" + customer + "/account/top-up")
                        .with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1000,"paymentSourceId":%d}""".formatted(cash)))
                .andExpect(status().isCreated());

        // 3. Оформить возврат денег из кассы: деталь принесли обратно.
        mvc.perform(post("/api/deals/" + dealId + "/returns").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"не подошла","refundToAccount":false,
                                 "paymentSourceId":%d,
                                 "items":[{"dealItemId":%d,"restocked":true}]}"""
                                .formatted(warehouse, cash, itemId)))
                .andExpect(status().isCreated());

        // 4. И платёж без источника — так выглядит история до задачи 0024.
        payment("IN", 100, null, Instant.now());

        JsonNode all = registry("");

        // Главное: итог посчитан своим запросом и обязан сойтись с приходом
        // и расходом. Разъехавшийся знак у расхода даёт 11 100 вместо 1 100.
        assertThat(total(all, "net"))
                .as("итог кассы не сошёлся с приходом и расходом: %s", all)
                .isEqualByComparingTo(total(all, "income").subtract(total(all, "expense")));
        assertThat(total(all, "income")).isEqualByComparingTo("6100");
        assertThat(total(all, "expense")).isEqualByComparingTo("5000");
        assertThat(total(all, "net")).isEqualByComparingTo("1100");
        assertThat(all.path("total").asInt()).isEqualTo(4);

        // Каждая из трёх операций видна строкой — с верным знаком и верным
        // источником. Возврат с направлением IN попал бы в приход, и касса
        // выросла бы на десять тысяч из ничего.
        assertThat(amountOf(all, "IN", "5000")).isEqualTo("ККМ");
        assertThat(amountOf(all, "IN", "1000")).isEqualTo("ККМ");
        assertThat(amountOf(all, "OUT", "5000")).isEqualTo("ККМ");

        // Платёж без источника не пропал: связь со справочником — LEFT JOIN,
        // иначе вся история переехавшего клиента исчезла бы из кассы молча.
        JsonNode nameless = rows(all).stream()
                .filter(r -> r.path("sourceId").isNull())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "платёж без источника пропал из реестра: " + all));
        assertThat(nameless.path("amount").decimalValue()).isEqualByComparingTo("100");

        // Оплата и возврат названы сделкой, пополнение счёта — нет: у него
        // документа продажи не существует вовсе.
        assertThat(rows(all).stream().filter(r -> !r.path("dealNumber").isNull()).count())
                .as("платежи по сделке потеряли её номер: %s", all)
                .isEqualTo(2);

        // Воронки: сумма приходных и расходных даёт итог всех. Это то же
        // утверждение с другой стороны — подвал считается по всей выборке
        // отбора, воронку включая.
        JsonNode income = registry("&direction=IN");
        JsonNode expense = registry("&direction=OUT");
        assertThat(income.path("total").asInt()).isEqualTo(3);
        assertThat(expense.path("total").asInt()).isEqualTo(1);
        assertThat(total(income, "expense")).isEqualByComparingTo("0");
        assertThat(total(expense, "income")).isEqualByComparingTo("0");
        assertThat(total(income, "net").add(total(expense, "net")))
                .as("суммы воронок не дают итога: %s / %s", income, expense)
                .isEqualByComparingTo(total(all, "net"));
    }

    /**
     * Период отсекает и строки, и подвал.
     *
     * <p>Без этого «итог сходится с приходом и расходом» доказывало бы только
     * то, что оба берут одинаково <b>всё</b>: границы у списка и у подсчёта
     * разъехались бы молча, и владелец читал бы сумму за всё время под
     * списком за неделю.
     */
    @Test
    @DisplayName("Платёж вне периода не попадает ни в список, ни в итог")
    void periodCutsRowsAndTotals() throws Exception {
        long cash = source("ККМ", "CASH");
        payment("IN", 1_000, cash, Instant.now());
        payment("IN", 777, cash, Instant.now().minus(40, ChronoUnit.DAYS));

        Instant from = Instant.now().minus(2, ChronoUnit.DAYS);
        JsonNode week = registry("&from=" + from);

        assertThat(week.path("total").asInt())
                .as("в период затесался платёж сорокадневной давности: %s", week)
                .isEqualTo(1);
        assertThat(total(week, "income")).isEqualByComparingTo("1000");
        assertThat(total(week, "net"))
                .isEqualByComparingTo(total(week, "income").subtract(total(week, "expense")));
    }

    /** Роль та же, что у отчётов: в кассе видно всё, чем живёт компания. */
    @Test
    @DisplayName("Реестр кассы видит владелец, но не продавец")
    void sellerCannotReadTheRegistry() throws Exception {
        mvc.perform(get("/api/payments").session(login("prodavets")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/payments").session(login("vladelec")))
                .andExpect(status().isOk());
    }

    /** Незнакомая воронка отвечает словами, а не пятисоткой на разборе. */
    @Test
    @DisplayName("Неизвестное направление объясняется словами")
    void unknownDirectionIsExplained() throws Exception {
        mvc.perform(get("/api/payments?direction=ПРИХОД").session(login("vladelec")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("направление")));
    }

    // ---------------------------------------------------------------

    private JsonNode registry(String query) throws Exception {
        var result = mvc.perform(get("/api/payments?size=100" + query).session(login("vladelec")))
                .andExpect(status().isOk())
                .andReturn();
        return new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<JsonNode> rows(JsonNode page) {
        List<JsonNode> rows = new ArrayList<>();
        page.path("items").forEach(rows::add);
        return rows;
    }

    private static BigDecimal total(JsonNode page, String field) {
        JsonNode value = page.path(field);
        assertThat(value.isNumber())
                .as("в подвале нет числа %s — пришло %s", field, page)
                .isTrue();
        return value.decimalValue();
    }

    /** Имя источника у платежа с таким направлением и суммой. */
    private static String amountOf(JsonNode page, String direction, String amount) {
        return rows(page).stream()
                .filter(r -> direction.equals(r.path("direction").asText())
                        && r.path("amount").decimalValue().compareTo(new BigDecimal(amount)) == 0)
                .findFirst()
                .map(r -> r.path("sourceName").asText(null))
                .orElseThrow(() -> new AssertionError(
                        "в реестре нет платежа " + direction + " на " + amount + ": " + page));
    }

    private long source(String name, String type) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO payment_source (name, source_type) VALUES (?, ?) RETURNING id""",
                Long.class, name, type));
    }

    private void payment(String direction, int amount, Long sourceId, Instant at) {
        inTenant(() -> jdbc.update("""
                INSERT INTO payment (customer_id, payment_source_id, direction, amount, paid_at)
                VALUES (?, ?, ?, ?, ?)""",
                customer, sourceId, direction, BigDecimal.valueOf(amount),
                java.sql.Timestamp.from(at)));
    }

    private Long partWithStock(String title, int qty) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price)
                    VALUES (1, ?, 5000, 2000) RETURNING id""", Long.class, title);
            ledger.record(StockMovement.intake(partId, BigDecimal.valueOf(qty), warehouse, null));
            return partId;
        });
    }

    private long createDeal(Long partId, MockHttpSession session) throws Exception {
        var result = mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"items":[{"partId":%d,"quantity":1,
                                 "warehouseId":%d}]}"""
                                .formatted(customer, partId, warehouse)))
                .andExpect(status().isCreated())
                .andReturn();
        return new ObjectMapper()
                .readTree(result.getResponse().getContentAsByteArray()).path("id").asLong();
    }

    private long firstItemId(long dealId, MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/deals/" + dealId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        return new ObjectMapper().readTree(result.getResponse().getContentAsByteArray())
                .path("items").get(0).path("id").asLong();
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
                                {"company":"kassareg","login":"%s","password":"пароль"}"""
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
