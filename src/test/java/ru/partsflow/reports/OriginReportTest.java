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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Позиции машины и партии четырьмя вкладками.
 *
 * <p>Главная проверка — <b>вкладки не разъезжаются ни между собой,
 * ни с числами, которые владелец уже видит</b>. «Поступило» обязано
 * складываться из «Продано», «Списано» и «Остатков» до последней позиции,
 * а суммы — сходиться с {@code v_donor_profitability}: разойдясь, экран даёт
 * два разных ответа на один вопрос, и какой из них верный, по нему не понять.
 * Поэтому числа сверяются с самой вьюхой, а не с переписанными в тест
 * ожиданиями: перепиши выражение вьюхи — и обе стороны сдвинулись бы вместе.
 *
 * <p>Позиция без прихода ({@code DRAFT}) заведена намеренно: это тот случай,
 * на котором разбиение ломается первым — она не продана, не списана
 * и на полке её нет.
 *
 * <p><b>Склад заводится один раз на весь класс</b>, а не в каждом методе:
 * отчёт считает абсолютные числа по машине, а схема между тестами
 * не чистится — журнал движений неизменяем, и заведённую деталь с него
 * не снять. Заводись фикстуры на каждый метод, вторая продажа удвоила бы
 * все числа, и падал бы не тот тест, который виноват.
 *
 * <p>Схема своя: деталь соседнего теста сдвинула бы те же числа.
 *
 * <p>Через HTTP, а не вызовом сервиса: обычный класс в ответе контроллера
 * Jackson не сериализует, и тест, зовущий сервис напрямую, этого не увидит.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class OriginReportTest extends PostgresTestBase {

    private static final String TENANT = "t_000116";

    private static Long warehouse;
    private static Long customer;
    private static Long donor;
    private static Long donorWithoutSales;
    private static Long supply;
    private static Long soldPart;
    private static Long writtenOffPart;
    private static String kindName;
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
        register(116, TENANT, "razrez");
        inTenant(TENANT, () -> {
            member("vladelec", "Владелец", "OWNER");
            member("prodavets", "Продавец", "SELLER");
            return null;
        });

        owner = login("razrez", "vladelec");
        seller = login("razrez", "prodavets");

        prepare();
    }

    @Test
    @DisplayName("Поступило = продано + списано + остатки, а суммы сходятся с окупаемостью")
    void tabsAddUpAndAgreeWithProfitability() throws Exception {
        JsonNode received = donorTab("received");
        JsonNode sold = donorTab("sold");
        JsonNode writtenOff = donorTab("written-off");
        JsonNode remaining = donorTab("remaining");

        // 1. Главное: вкладки — разбиение, а не четыре независимых отбора.
        //    Черновик приёмки не продан, не списан и на полке его нет —
        //    выпади он из «Остатков», здесь стало бы 4 против 3.
        assertThat(items(received))
                .as("«Поступило» не равно сумме трёх вкладок — выборки разъехались")
                .isEqualTo(items(sold) + items(writtenOff) + items(remaining));
        assertThat(items(received)).isEqualTo(4);

        // 2. Суммы — с теми числами, которые владелец уже видит в окупаемости.
        //    Разойдутся — на один вопрос будет два ответа.
        assertThat(amount(sold))
                .as("вкладка «Продано» разошлась с revenue из v_donor_profitability")
                .isEqualByComparingTo(view("revenue"));
        assertThat(amount(remaining))
                .as("вкладка «Остатки» разошлась с stock_value из v_donor_profitability")
                .isEqualByComparingTo(view("stock_value"));
        assertThat(items(received)).isEqualTo(view("parts_total").intValue());
        assertThat(items(sold)).isEqualTo(view("parts_sold").intValue());

        // 3. Подвал считает и штуки: у позиции из двух дверей их две,
        //    и «4 товара (4 шт.)» — не описка. Считаются они по журналу:
        //    part.quantity у принятой партией позиции остаётся единицей,
        //    а у перенесённой из предыдущей системы — единицей у всех.
        //    Четвёртая позиция — черновик, по ней не пришло ничего.
        assertThat(quantity(received)).isEqualByComparingTo("4");
        assertThat(amount(received)).isEqualByComparingTo("9000");
        assertThat(quantity(remaining)).isEqualByComparingTo("2");
        assertThat(amount(writtenOff)).isEqualByComparingTo("2000");

        // 4. Строка называет позицию тем, что видно на витрине, и несёт
        //    себестоимость: ради неё отчёт и закрыт от продавца.
        JsonNode row = remaining.path("rows").get(0);
        assertThat(row.path("publicCode").asText()).isNotEmpty();
        assertThat(row.path("title").asText()).isEqualTo("Дверь");
        assertThat(row.path("kind").asText()).isEqualTo(kindName);
        assertThat(row.path("costPrice").decimalValue()).isEqualByComparingTo("700");
        assertThat(row.path("date").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    /**
     * Машина без единой продажи.
     *
     * <p>Свежий донор — обычное дело, и вкладка «Продано» у него пуста.
     * Пусто здесь означает «ничего не нашли», а не поломку: пятисотка
     * на этом месте читается как «отчёт сломан», и владелец идёт не туда.
     */
    @Test
    @DisplayName("Машина без продаж отвечает пустой вкладкой, а не пятисоткой")
    void donorWithoutSalesAnswersEmpty() throws Exception {
        JsonNode sold = tab("/api/reports/donors/" + donorWithoutSales + "/items?tab=sold");
        assertThat(sold.path("rows").size()).isZero();
        assertThat(items(sold)).isZero();
        assertThat(amount(sold)).isEqualByComparingTo("0");

        // А поступило с неё одно стекло — то есть машина в отчёте есть.
        assertThat(items(tab("/api/reports/donors/" + donorWithoutSales + "/items"))).isEqualTo(1);
    }

    /**
     * Партия и «поставка не указана».
     *
     * <p>Второе — не «всё подряд», а отдельный разрез: у переехавшего клиента
     * без партии числится всё, что заводили руками.
     */
    @Test
    @DisplayName("Поставка считается так же, а «не указана» — отдельным разрезом")
    void supplyIsCountedTheSameWay() throws Exception {
        JsonNode container = tab("/api/reports/supplies/items?supplyId=" + supply);
        assertThat(items(container)).isEqualTo(3);
        assertThat(amount(container)).isEqualByComparingTo("3500");

        JsonNode without = tab("/api/reports/supplies/items");
        assertThat(items(without)).isEqualTo(2);
        // Две двери и черновик, по которому не пришло ничего.
        assertThat(quantity(without)).isEqualByComparingTo("2");
        assertThat(amount(without)).isEqualByComparingTo("6000");
    }

    /**
     * Партия видна в списке выбора — включая закрытую.
     *
     * <p>Справочник приёмки закрытые прячет, и правильно: принимать в них
     * уже нельзя. Здесь наоборот — «окупился ли контейнер» спрашивают
     * ровно про закрытый.
     */
    @Test
    @DisplayName("В списке для выбора есть и закрытая партия")
    void closedSupplyStaysInTheList() throws Exception {
        inTenant(TENANT, () -> jdbc.update(
                "UPDATE supply SET status = 'CLOSED' WHERE id = ?", supply));

        JsonNode supplies = json(get("/api/reports/supplies").session(owner)).path("rows");
        assertThat(supplies.findValuesAsText("number")).contains("К-1");
        assertThat(supplies.get(0).path("supplierName").asText()).isEqualTo("Armtek");
    }

    /**
     * Страница, а не всё сразу.
     *
     * <p>У живого клиента 162 позиции на машине и тысячи в контейнере.
     * Подвал при этом считает всю вкладку, а не показанную страницу:
     * сумма первой сотни, выданная за итог, — враньё тем более наглядное,
     * чем больше партия.
     */
    @Test
    @DisplayName("Позиции отдаются страницей, а итог остаётся по всей вкладке")
    void itemsComeInPages() throws Exception {
        JsonNode first = donorTab("received&size=2");
        assertThat(first.path("rows").size()).isEqualTo(2);
        assertThat(items(first)).isEqualTo(4);
        assertThat(amount(first)).isEqualByComparingTo("9000");

        long after = first.path("nextAfter").asLong();
        assertThat(after).isPositive();

        JsonNode second = donorTab("received&size=2&after=" + after);
        assertThat(second.path("rows").size()).isEqualTo(2);
        assertThat(items(second)).isEqualTo(4);
        assertThat(second.path("nextAfter").isNull())
                .as("страница кончилась, а экран получил бы «показать ещё»")
                .isTrue();
    }

    /** Себестоимость — то, ради чего отчёты закрыты от продавца. */
    @Test
    @DisplayName("Разрез видит владелец или менеджер, но не продавец")
    void sellerCannotReadBreakdown() throws Exception {
        mvc.perform(get("/api/reports/donors/" + donor + "/items").session(seller))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/supplies/items").session(seller))
                .andExpect(status().isForbidden());
    }

    /** Опечатка в адресе — ошибка запроса, и звонящий должен видеть, из чего выбирать. */
    @Test
    @DisplayName("Незнакомая вкладка — 400 со списком, а не пятисотка")
    void unknownTabIsRefusedWithExplanation() throws Exception {
        mvc.perform(get("/api/reports/donors/" + donor + "/items?tab=vsyo").session(owner))
                .andExpect(status().isBadRequest());
    }

    /**
     * Машина, с которой сняли четыре позиции: продана, списана, лежит
     * и заведена без прихода. Плюс вторая машина без единой продажи.
     */
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

            Long kind = jdbc.queryForObject(
                    "SELECT id FROM catalog.part_kind ORDER BY id LIMIT 1", Long.class);
            kindName = jdbc.queryForObject(
                    "SELECT name FROM catalog.part_kind WHERE id = ?", String.class, kind);

            supply = jdbc.queryForObject("""
                    INSERT INTO supply (kind, number, supplier_name, status)
                    VALUES ('CONTAINER', 'К-1', 'Armtek', 'ARRIVED') RETURNING id""", Long.class);

            donor = donor("РАЗРЕЗ-1", "Toyota Camry");
            donorWithoutSales = donor("РАЗРЕЗ-2", "Nissan Note");

            soldPart = part("Фара", 1000, 1, donor, supply, kind);
            stock(soldPart, 1);
            writtenOffPart = part("Бампер", 2000, 1, donor, supply, kind);
            stock(writtenOffPart, 1);
            stock(part("Дверь", 3000, 2, donor, null, kind), 2);
            // Карточка заведена, приход не проведён: остатка нет, статус DRAFT.
            part("Черновик", 4000, 1, donor, null, kind);
            stock(part("Стекло", 500, 1, donorWithoutSales, supply, kind), 1);
            return null;
        });

        long deal = createDeal(soldPart);
        mvc.perform(post("/api/deals/" + deal + "/issue").with(csrf()).session(seller))
                .andExpect(status().isOk());

        inTenant(TENANT, () -> {
            ledger.record(StockMovement.writeOff(writtenOffPart, BigDecimal.ONE, warehouse));
            return null;
        });
    }

    private Long donor(String code, String note) {
        return jdbc.queryForObject("""
                INSERT INTO donor (public_code, brand_id, note, status)
                VALUES (?, (SELECT id FROM catalog.brand ORDER BY id LIMIT 1), ?, 'DISMANTLING')
                RETURNING id""", Long.class, code, note);
    }

    private Long part(String title, int price, int quantity,
                      Long donorId, Long supplyId, Long kindId) {
        return jdbc.queryForObject("""
                INSERT INTO part (category_id, part_kind_id, title, price, cost_price,
                                  quantity, donor_id, supply_id, product_line)
                VALUES (1, ?, ?, ?, 700, ?, ?, ?, 'PART') RETURNING id""",
                Long.class, kindId, title, price, quantity, donorId, supplyId);
    }

    private void stock(Long partId, int qty) {
        ledger.record(StockMovement.intake(partId, BigDecimal.valueOf(qty), warehouse, null));
    }

    private long createDeal(Long partId) throws Exception {
        var created = mvc.perform(post("/api/deals").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        // Цена продажи ниже розничной намеренно: продали
                        // со скидкой. Совпади они, вкладка «Продано», считающая
                        // по прайсу вместо цены сделки, дала бы тот же ответ,
                        // и сверка с revenue ничего бы не поймала.
                        .content("""
                                {"customerId":%d,"items":[
                                  {"partId":%d,"quantity":1,"price":900,"warehouseId":%d}]}"""
                                .formatted(customer, partId, warehouse)))
                .andExpect(status().isCreated())
                .andReturn();
        return new ObjectMapper()
                .readTree(created.getResponse().getContentAsByteArray())
                .get("id").asLong();
    }

    /** Число из самой вьюхи: ожидания теста не переписывают её выражений. */
    private BigDecimal view(String column) {
        return inTenant(TENANT, () -> jdbc.queryForObject(
                "SELECT " + column + " FROM v_donor_profitability WHERE donor_id = ?",
                BigDecimal.class, donor));
    }

    private JsonNode donorTab(String tab) throws Exception {
        return tab("/api/reports/donors/" + donor + "/items?tab=" + tab);
    }

    private JsonNode tab(String url) throws Exception {
        return json(get(url).session(owner));
    }

    private JsonNode json(RequestBuilder request) throws Exception {
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
    }

    private static int items(JsonNode page) {
        return page.path("totals").path("items").asInt();
    }

    private static BigDecimal amount(JsonNode page) {
        return page.path("totals").path("amount").decimalValue();
    }

    private static BigDecimal quantity(JsonNode page) {
        return page.path("totals").path("quantity").decimalValue();
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
