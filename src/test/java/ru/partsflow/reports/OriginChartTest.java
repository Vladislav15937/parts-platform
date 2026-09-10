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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.StockLedger;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * График окупаемости во времени: когда линия выручки перешла линию вложенного.
 *
 * <p><b>Главное здесь — не «график рисуется», а «график и таблица считают
 * одно и то же».</b> Последняя точка «Продано на сумму» обязана равняться
 * колонке «Выручено» той же машины, а «Себестоимость всех» — колонке
 * «Вложено»: разойдись они, владелец получит два ответа на один вопрос,
 * и это хуже отсутствия графика. Поэтому числа сверяются с самой
 * {@code v_donor_profitability}, а не с переписанными в тест ожиданиями.
 *
 * <p><b>Вторая проверка — накопление.</b> Фикстура сделана так, чтобы
 * помесячная сумма и накопительная различались: продано в двух разных
 * месяцах по 60 000 при затратах 100 000, и ответ на вопрос «когда
 * окупилась» — второй месяц, а не первый и не «никогда». Считай ряд
 * помесячно вместо накопления — линия выручки не пересекла бы вложенного
 * ни разу.
 *
 * <p><b>Даты продаж двигаются прямым UPDATE</b> по {@code deal.closed_at}:
 * выдать сделку задним числом система не даёт и не должна, а без разных
 * месяцев проверять на графике нечего.
 *
 * <p>Схема своя: график считает абсолютные числа по машине, и деталь
 * соседнего теста сдвинула бы их все.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class OriginChartTest extends PostgresTestBase {

    // Схема своя и проверена на занятость: номер, совпадающий с номером
    // задачи или PR, — первое, что приходит в голову, и ровно так этот тест
    // занял t_000125, уже принадлежавшую LoginSessionTest. Тот делит фикстуру
    // логином `vladelec`, но с другим паролем, и вход отвечал 401 — но только
    // в том порядке прогона, где сосед отработал первым.
    private static final String TENANT = "t_000127";

    /** Месяцы фикстуры: продажи разнесены, чтобы накопление было видно. */
    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    private static final YearMonth APRIL = YearMonth.of(2026, 4);

    private static Long warehouse;
    private static Long customer;
    private static Long donor;
    private static Long freshDonor;
    private static Long supply;
    private static boolean prepared;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockHttpSession owner;
    private MockHttpSession seller;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        register(127, TENANT, "grafik");
        inTenant(TENANT, () -> {
            member("vladelec", "Владелец", "OWNER");
            member("prodavets", "Продавец", "SELLER");
            return null;
        });

        owner = login("grafik", "vladelec");
        seller = login("grafik", "prodavets");

        prepare();
    }

    /**
     * Момент окупаемости читается с графика.
     *
     * <p>Затрата 100 000, продано в марте на 60 000 и в апреле ещё
     * на 60 000: в марте выручка ниже вложенного, в апреле выше. Это и есть
     * ответ «окупилась в апреле», ради которого график и заводили.
     */
    @Test
    @DisplayName("Линия выручки переходит линию вложенного в том месяце, когда окупилась")
    void paybackMonthIsVisible() throws Exception {
        JsonNode points = donorChart().path("points");

        JsonNode march = at(points, MARCH);
        JsonNode april = at(points, APRIL);

        assertThat(march.path("totalCost").decimalValue()).isEqualByComparingTo("100000");
        assertThat(march.path("revenue").decimalValue())
                .as("в марте продано на 60 000 — выручка не может быть другой")
                .isEqualByComparingTo("60000");
        assertThat(march.path("revenue").decimalValue())
                .as("в марте машина ещё не окупилась, а выручка уже перешла вложенное")
                .isLessThan(march.path("totalCost").decimalValue());

        assertThat(april.path("revenue").decimalValue())
                .as("ряд не накопительный: в апреле стоит выручка одного месяца, "
                        + "а не сумма марта с апрелем — момент окупаемости "
                        + "по такому графику не читается вовсе")
                .isEqualByComparingTo("120000");
        assertThat(april.path("revenue").decimalValue())
                .as("апрель обязан быть месяцем окупаемости: 120 000 против 100 000")
                .isGreaterThan(april.path("totalCost").decimalValue());
    }

    /**
     * Числа графика и числа таблицы — одни и те же.
     *
     * <p>Сверяется с самой вьюхой, а не с ожиданиями теста: перепиши
     * выражение вьюхи — и обе стороны сдвинулись бы вместе, а проверка
     * этого не заметила бы.
     */
    @Test
    @DisplayName("Последняя точка сходится с «Выручено» и «Вложено» из окупаемости")
    void lastPointAgreesWithProfitability() throws Exception {
        JsonNode points = donorChart().path("points");
        JsonNode last = points.get(points.size() - 1);

        assertThat(last.path("revenue").decimalValue())
                .as("график и таблица «Окупаемость машин» считают выручку по-разному")
                .isEqualByComparingTo(view("revenue"));
        assertThat(last.path("totalCost").decimalValue())
                .as("график и таблица «Окупаемость машин» считают вложенное по-разному")
                .isEqualByComparingTo(view("total_cost"));

        // «Планируемая сумма» — розничная стоимость всего поступившего,
        // то есть подвал вкладки «Поступило». Третье число, которое владелец
        // видит рядом, и разойтись оно может так же тихо.
        JsonNode received = json(get("/api/reports/donors/" + donor + "/items?tab=received")
                .session(owner));
        assertThat(last.path("planned").decimalValue())
                .as("«Планируемая сумма» разошлась с подвалом вкладки «Поступило»")
                .isEqualByComparingTo(received.path("totals").path("amount").decimalValue());
    }

    /**
     * Столбцы «Продажи по месяцам» — те же деньги, разложенные иначе.
     *
     * <p>Сумма столбцов равна последней точке накопительной линии. Разойдись
     * они, два графика в одном окне говорили бы о разной выручке.
     */
    @Test
    @DisplayName("Сумма столбцов равна последней точке накопительной линии")
    void barsAddUpToTheLine() throws Exception {
        JsonNode points = donorChart().path("points");

        BigDecimal bars = BigDecimal.ZERO;
        for (JsonNode point : points) {
            bars = bars.add(point.path("monthRevenue").decimalValue());
        }

        assertThat(bars)
                .as("столбцы и линия говорят о разной выручке")
                .isEqualByComparingTo(points.get(points.size() - 1).path("revenue").decimalValue());
        assertThat(bars).isEqualByComparingTo("120000");
    }

    /**
     * Машина без продаж рисует график, а не пустое место.
     *
     * <p>«Данных нет» тут неправда: вложено же. Владелец, купивший машину
     * на прошлой неделе, обязан увидеть линию вложенного и выручку на нуле —
     * это и есть правда о такой машине.
     */
    @Test
    @DisplayName("Машина без продаж даёт линию вложенного и выручку на нуле")
    void freshDonorStillHasAChart() throws Exception {
        JsonNode points = json(get("/api/reports/donors/" + freshDonor + "/chart")
                .session(owner)).path("points");

        assertThat(points.size())
                .as("у машины есть затраты и поступление, а рисовать нечего")
                .isPositive();

        JsonNode last = points.get(points.size() - 1);
        assertThat(last.path("totalCost").decimalValue()).isEqualByComparingTo("30000");
        assertThat(last.path("revenue").decimalValue()).isEqualByComparingTo("0");
        assertThat(last.path("planned").decimalValue()).isEqualByComparingTo("500");
    }

    /**
     * Ось тянется до текущего месяца, а не обрывается на последнем событии.
     *
     * <p>Иначе по графику нельзя отличить «продаётся до сих пор» от «умерла
     * год назад»: обе линии кончались бы на своём последнем месяце, и полки,
     * из которой это видно, не было бы вовсе.
     */
    @Test
    @DisplayName("Ось доходит до текущего месяца, а не до последней продажи")
    void axisRunsToTheCurrentMonth() throws Exception {
        JsonNode points = donorChart().path("points");
        String last = points.get(points.size() - 1).path("month").asText();

        assertThat(last).isEqualTo(YearMonth.now().toString());
        assertThat(points.get(0).path("month").asText())
                .as("ось обязана начинаться с первого события, а не с текущего месяца")
                .isEqualTo(MARCH.toString());
    }

    /**
     * Партия считается так же, только вложенное у неё другое.
     *
     * <p>Затрат у поставки в системе нет вовсе — {@code donor_cost} есть
     * только у машины, — и вложенным считается себестоимость поступившего.
     * Это решение исполнителя, и здесь оно закреплено числом: разойдись
     * определение, проверка скажет, какое было.
     */
    @Test
    @DisplayName("У партии вложенное — себестоимость поступившего товара")
    void supplyChartCountsCostOfGoods() throws Exception {
        JsonNode points = json(get("/api/reports/supplies/chart?supplyId=" + supply)
                .session(owner)).path("points");
        JsonNode last = points.get(points.size() - 1);

        // Две позиции партии по 700 закупки на штуку: 1 + 1 штуки.
        assertThat(last.path("totalCost").decimalValue()).isEqualByComparingTo("1400");
        assertThat(last.path("revenue").decimalValue()).isEqualByComparingTo("120000");
    }

    /** Себестоимость — то, ради чего отчёты закрыты от продавца. */
    @Test
    @DisplayName("График видит владелец или менеджер, но не продавец")
    void sellerCannotReadTheChart() throws Exception {
        mvc.perform(get("/api/reports/donors/" + donor + "/chart").session(seller))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/supplies/chart").session(seller))
                .andExpect(status().isForbidden());
    }

    private void prepare() throws Exception {
        if (prepared) {
            return;
        }
        prepared = true;

        inTenant(TENANT, () -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            customer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id", Long.class);
            supply = jdbc.queryForObject("""
                    INSERT INTO supply (kind, number, supplier_name, status)
                    VALUES ('CONTAINER', 'Г-1', 'Armtek', 'ARRIVED') RETURNING id""", Long.class);

            donor = donor("ГРАФИК-1", "Toyota Camry");
            freshDonor = donor("ГРАФИК-2", "Nissan Note");

            // 100 000 вложено — одной затратой, чтобы линия вложенного была
            // горизонтальной: так её пересечение с выручкой и читают.
            cost(donor, "100000", MARCH);
            cost(freshDonor, "30000", MARCH);
            return null;
        });

        Long first = inTenant(TENANT, () -> stocked("Двигатель", 70000, donor, supply));
        Long second = inTenant(TENANT, () -> stocked("Коробка", 70000, donor, supply));
        inTenant(TENANT, () -> stocked("Стекло", 500, freshDonor, null));

        sell(first, MARCH);
        sell(second, APRIL);
    }

    /** Продажа за 60 000 в названном месяце: выдача плюс сдвиг даты закрытия. */
    private void sell(Long partId, YearMonth month) throws Exception {
        var created = mvc.perform(post("/api/deals").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"items":[
                                  {"partId":%d,"quantity":1,"price":60000,"warehouseId":%d}]}"""
                                .formatted(customer, partId, warehouse)))
                .andExpect(status().isCreated())
                .andReturn();
        long deal = new ObjectMapper()
                .readTree(created.getResponse().getContentAsByteArray()).get("id").asLong();

        mvc.perform(post("/api/deals/" + deal + "/issue").with(csrf()).session(seller))
                .andExpect(status().isOk());

        inTenant(TENANT, () -> jdbc.update(
                "UPDATE deal SET closed_at = ?::timestamptz WHERE id = ?",
                month.atDay(15) + " 12:00:00+00", deal));
    }

    private void cost(Long donorId, String amount, YearMonth month) {
        jdbc.update("""
                INSERT INTO donor_cost (donor_id, cost_type, amount, incurred_on)
                VALUES (?, 'PURCHASE', ?::numeric, ?::date)""",
                donorId, amount, month.atDay(1).toString());
    }

    private Long donor(String code, String note) {
        return jdbc.queryForObject("""
                INSERT INTO donor (public_code, brand_id, note, status)
                VALUES (?, (SELECT id FROM catalog.brand ORDER BY id LIMIT 1), ?, 'DISMANTLING')
                RETURNING id""", Long.class, code, note);
    }

    private Long stocked(String title, int price, Long donorId, Long supplyId) {
        Long kind = jdbc.queryForObject(
                "SELECT id FROM catalog.part_kind ORDER BY id LIMIT 1", Long.class);
        Long partId = jdbc.queryForObject("""
                INSERT INTO part (category_id, part_kind_id, title, price, cost_price,
                                  quantity, donor_id, supply_id, product_line)
                VALUES (1, ?, ?, ?, 700, 1, ?, ?, 'PART') RETURNING id""",
                Long.class, kind, title, price, donorId, supplyId);
        ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
        return partId;
    }

    private JsonNode donorChart() throws Exception {
        return json(get("/api/reports/donors/" + donor + "/chart").session(owner));
    }

    /** Точка названного месяца: по номеру в массиве её искать нельзя. */
    private static JsonNode at(JsonNode points, YearMonth month) {
        for (JsonNode point : points) {
            if (point.path("month").asText().equals(month.toString())) {
                return point;
            }
        }
        throw new AssertionError("на оси нет месяца " + month);
    }

    /** Число из самой вьюхи: ожидания теста не переписывают её выражений. */
    private BigDecimal view(String column) {
        return inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT " + column + " FROM v_donor_profitability WHERE donor_id = ?",
                BigDecimal.class, donor));
    }

    private JsonNode json(RequestBuilder request) throws Exception {
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
    }

    private void register(int id, String schema, String code) {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", id);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка', ?)""", id, schema, code);
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

    private MockHttpSession login(String company, String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"%s","password":"пароль"}"""
                                .formatted(company, login)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(String tenant, Supplier<T> body) {
        TenantContext.set(tenant);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
