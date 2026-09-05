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
 * Сводка владельца: что лежит на складе и что висит в незакрытых сделках.
 *
 * <p>Главная проверка одна — <b>отложенная деталь из остатка не пропадает</b>.
 * Свободный остаток у нас считается выражением {@code qty - qty_reserved}
 * везде, где спрашивают, и взять его здесь — самая естественная ошибка:
 * запрос выглядит правильным, экран показывает число, и врёт оно ровно
 * на объём отложенного. Владелец видит недостачу, которой нет, и идёт искать
 * её на складе. Разница появляется только тогда, когда по товару стоит
 * резерв, — поэтому сделка тут заводится по-настоящему, а не подставляется
 * в фикстуре.
 *
 * <p>Своя схема, а не общая с {@code ReportControllerTest}: сводка считает
 * склад арендатора целиком, и любая деталь, заведённая соседним тестом,
 * сдвинула бы абсолютные числа.
 *
 * <p>Через HTTP, а не вызовом сервиса: обычный класс в ответе контроллера
 * Jackson не сериализует, и тест, зовущий сервис напрямую, этого не увидит.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class SummaryReportTest extends PostgresTestBase {

    private static final String TENANT = "t_000114";

    /** Свежий провижининг: ни детали, ни сделки — ответ обязан быть нулём. */
    private static final String EMPTY_TENANT = "t_000115";

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

    private Long warehouse;
    private Long customer;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT, EMPTY_TENANT);
    }

    @BeforeEach
    void fixtures() {
        register(114, TENANT, "svodka");
        register(115, EMPTY_TENANT, "pustaya");

        inTenant(TENANT, () -> {
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

        inTenant(EMPTY_TENANT, () -> member("vladelec", "Владелец", "OWNER"));
    }

    /**
     * Весь путь одним тестом, и это не лень.
     *
     * <p>Сводка считает склад арендатора целиком, а схема между тестами
     * не чистится — журнал движений неизменяем, и заведённую деталь с него
     * не снять. Разложенные по методам шаги зависели бы от порядка запуска:
     * абсолютные числа второго теста включали бы товар первого.
     */
    @Test
    @DisplayName("Отложенная деталь из остатка не пропадает, а выданная уходит")
    void reservedStaysOnTheShelfAndIssuedLeavesIt() throws Exception {
        MockHttpSession owner = login("svodka", "vladelec");
        MockHttpSession seller = login("svodka", "prodavets");
        Long part = partWithStock("Фара сводки", 1000, 3);
        // Колесо той же ценой не берём: разъехавшись строками, суммы должны
        // разъехаться заметно.
        wheelWithStock("Шина сводки 205/55 R16", 4000, 2);

        // 1. Склад посчитан по всем складам вместе: три фары по тысяче
        //    и две шины по четыре. Колёса отдельной строкой — они продаются
        //    сезоном, и владелец смотрит на них не вместе с запчастями.
        //    Попади шина в запчасти, здесь стояло бы пять штук и 11 000.
        JsonNode shelf = summary(owner);
        assertThat(number(shelf, "parts", "qty")).isEqualByComparingTo("3");
        assertThat(number(shelf, "parts", "amount")).isEqualByComparingTo("3000");
        assertThat(number(shelf, "wheels", "qty")).isEqualByComparingTo("2");
        assertThat(number(shelf, "wheels", "amount")).isEqualByComparingTo("8000");
        assertThat(number(shelf, "deals", "count")).isEqualByComparingTo("0");

        // 2. Главное. Одна фара отложена под клиента — со склада она никуда
        //    не делась, и «Остаток товара» обязан остаться прежним. Возьми
        //    запрос свободный остаток, здесь было бы 2 штуки и 2 000 ₽.
        long dealId = reservedDeal(seller, part);

        JsonNode reserved = summary(owner);
        assertThat(number(reserved, "parts", "qty"))
                .as("отложенная деталь пропала из остатка — а она лежит на полке")
                .isEqualByComparingTo("3");
        assertThat(number(reserved, "parts", "amount")).isEqualByComparingTo("3000");
        // Сделок в работе стало на одну больше, и ровно на цену этой фары.
        assertThat(number(reserved, "deals", "count")).isEqualByComparingTo("1");
        assertThat(number(reserved, "deals", "amount")).isEqualByComparingTo("1000");
        assertThat(number(reserved, "deals", "prepaid")).isEqualByComparingTo("0");

        // 3. Частичная оплата растит предоплаты и не трогает сумму сделок:
        //    клиент внёс четыреста, сделка по-прежнему на тысячу.
        mvc.perform(post("/api/deals/" + dealId + "/payments").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":400}"))
                .andExpect(status().isCreated());

        JsonNode paid = summary(owner);
        assertThat(number(paid, "deals", "count")).isEqualByComparingTo("1");
        assertThat(number(paid, "deals", "amount")).isEqualByComparingTo("1000");
        assertThat(number(paid, "deals", "prepaid")).isEqualByComparingTo("400");

        // 4. Выдали — вот теперь деталь ушла со склада, а сделка ушла
        //    из работы. Оба числа меняются вместе, и это тот единственный
        //    момент, когда остаток обязан уменьшиться.
        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(seller))
                .andExpect(status().isOk());

        JsonNode issued = summary(owner);
        assertThat(number(issued, "parts", "qty")).isEqualByComparingTo("2");
        assertThat(number(issued, "parts", "amount")).isEqualByComparingTo("2000");
        // Шины всё это время стояли на месте: продавали запчасть.
        assertThat(number(issued, "wheels", "qty")).isEqualByComparingTo("2");
        assertThat(number(issued, "wheels", "amount")).isEqualByComparingTo("8000");
        assertThat(number(issued, "deals", "count")).isEqualByComparingTo("0");
        assertThat(number(issued, "deals", "amount")).isEqualByComparingTo("0");
        assertThat(number(issued, "deals", "prepaid")).isEqualByComparingTo("0");
    }

    /**
     * Свежий арендатор отвечает нулями.
     *
     * <p>В {@code part_stock} ни строки — группировка по видам товара
     * не вернёт ничего, и ответ, собранный из её строк, пришёл бы без полей:
     * экран нарисовал бы пустоту или упал. Владелец, только что заведший
     * компанию, должен увидеть шесть нулей — это правда о его складе.
     */
    @Test
    @DisplayName("Пустой арендатор — нули, а не пустой ответ и не пятисотка")
    void freshTenantAnswersZeros() throws Exception {
        JsonNode empty = summary(login("pustaya", "vladelec"));

        for (String card : new String[] {"parts", "wheels"}) {
            assertThat(number(empty, card, "qty")).isEqualByComparingTo("0");
            assertThat(number(empty, card, "amount")).isEqualByComparingTo("0");
        }
        assertThat(number(empty, "deals", "count")).isEqualByComparingTo("0");
        assertThat(number(empty, "deals", "amount")).isEqualByComparingTo("0");
        assertThat(number(empty, "deals", "prepaid")).isEqualByComparingTo("0");
    }

    /**
     * Роль та же, что у остальных отчётов.
     *
     * <p>Сводка — это склад в деньгах и незакрытые сделки всей смены:
     * продавцу столько знать незачем.
     */
    @Test
    @DisplayName("Сводку видит владелец или менеджер, но не продавец")
    void sellerCannotReadSummary() throws Exception {
        mvc.perform(get("/api/reports/summary").session(login("svodka", "prodavets")))
                .andExpect(status().isForbidden());
    }

    private JsonNode summary(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/reports/summary").session(session))
                .andExpect(status().isOk())
                .andReturn();
        return new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
    }

    /**
     * Число из ответа как {@code BigDecimal}.
     *
     * <p>Сравнивать надо значением, а не текстом: у количества
     * {@code numeric(12,3)} и у денег {@code numeric(14,2)} разный масштаб,
     * и «3.000» против «3» развалило бы проверку, ничего не сказав по делу.
     */
    private static BigDecimal number(JsonNode json, String card, String field) {
        JsonNode value = json.path(card).path(field);
        assertThat(value.isNumber())
                .as("в ответе нет числа %s.%s — пришло %s", card, field, json)
                .isTrue();
        return value.decimalValue();
    }

    /** Отложенная сделка: резерв стоит, товар с полки не уходил. */
    private long reservedDeal(MockHttpSession session, Long partId) throws Exception {
        var created = mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"items":[
                                  {"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customer, partId, warehouse)))
                .andExpect(status().isCreated())
                .andReturn();
        return new ObjectMapper()
                .readTree(created.getResponse().getContentAsByteArray())
                .get("id").asLong();
    }

    private Long partWithStock(String title, int price, int qty) {
        return stocked("PART", title, price, qty);
    }

    /** Колесо — тот же {@code part}, только другой товарной линии. */
    private Long wheelWithStock(String title, int price, int qty) {
        return stocked("WHEEL", title, price, qty);
    }

    private Long stocked(String productLine, String title, int price, int qty) {
        return inTenant(TENANT, () -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, product_line)
                    VALUES (1, ?, ?, ?) RETURNING id""",
                    Long.class, title, price, productLine);
            ledger.record(StockMovement.intake(
                    partId, BigDecimal.valueOf(qty), warehouse, null));
            return partId;
        });
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
