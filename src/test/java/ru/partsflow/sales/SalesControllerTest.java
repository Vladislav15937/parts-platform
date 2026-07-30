package ru.partsflow.sales;

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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST продаж.
 *
 * <p>Логика сделок была закрыта тестами давно, но снаружи недоступна: вызвать
 * её было нечем. Здесь проверяется то, что появляется вместе с HTTP —
 * кто имеет право продавать, откуда берётся автор операции и во что
 * превращаются нарушения правил.
 */
@SpringBootTest(properties = "app.sales-rest-test=true")
@AutoConfigureMockMvc
class SalesControllerTest extends PostgresTestBase {

    private static final String TENANT = "t_000064";

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
    private Long sellerId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 64");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (64, ?, 'Разборка', 'salesco')""", TENANT);

        inTenant(() -> {
            // Сотрудники заводятся один раз и не удаляются между тестами:
            // на них ссылаются сделки, а сделки — на движения склада, которые
            // журнал удалять запрещает. Данные тестов не пересекаются
            // по-другому — каждый берёт свою деталь и своего клиента.
            sellerId = member("seller", "Продавец", "SELLER");
            member("viewer", "Смотрящий", "VIEWER");

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

    @Test
    @DisplayName("Продавец создаёт сделку, товар резервируется")
    void sellerCreatesDeal() throws Exception {
        Long partId = partWithStock("Фара левая", 2);
        MockHttpSession session = login("seller");

        mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(partId, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.number").isNumber())
                .andExpect(jsonPath("$.items[0].partId").value(partId));

        assertThat(reservedOf(partId))
                .as("резерв не встал: деталь продадут второй раз")
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Автор сделки — вошедший, а не тот, кого назвали в теле")
    void managerComesFromSession() throws Exception {
        Long partId = partWithStock("Бампер", 1);
        MockHttpSession session = login("seller");

        // managerId в теле есть, но контракт его не знает: премия считается
        // по продавцу из сессии, иначе её можно приписать себе.
        mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"managerId":999999,
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customer, partId, warehouse)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.managerId").value(sellerId));
    }

    @Test
    @DisplayName("Смотрящий продавать не может")
    void viewerCannotSell() throws Exception {
        Long partId = partWithStock("Дверь", 1);
        MockHttpSession session = login("viewer");

        mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(partId, 1)))
                .andExpect(status().isForbidden());

        assertThat(reservedOf(partId)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Смотреть сделки может любой вошедший")
    void viewerCanRead() throws Exception {
        Long partId = partWithStock("Капот", 1);
        long dealId = createDeal(partId);

        mvc.perform(get("/api/deals/" + dealId).session(login("viewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));
    }

    @Test
    @DisplayName("Без входа продажи не видны")
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/deals/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Выдача списывает товар со склада")
    void issueMovesStock() throws Exception {
        Long partId = partWithStock("Крыло", 1);
        long dealId = createDeal(partId);

        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(login("seller")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));

        assertThat(qtyOf(partId)).isEqualByComparingTo("0");
        assertThat(reservedOf(partId))
                .as("резерв не снят при выдаче — остаток разъедется")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Выданную сделку не отменяют: 409, а не 500")
    void issuedDealCannotBeCancelled() throws Exception {
        Long partId = partWithStock("Стойка", 1);
        long dealId = createDeal(partId);
        MockHttpSession session = login("seller");

        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());

        // Нарушение правила — это 409. Приедь оно как 500, офлайн-очередь
        // приёмки повторяла бы такое вечно; здесь очереди нет, но код ответа
        // общий для всего API, и разъезжаться ему нельзя.
        mvc.perform(post("/api/deals/" + dealId + "/cancel").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"передумал\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Нехватка остатка — 409, и сделка не создаётся")
    void insufficientStockIsRejected() throws Exception {
        Long partId = partWithStock("Радиатор", 1);
        MockHttpSession session = login("seller");

        mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(partId, 2)))
                .andExpect(status().isConflict());

        assertThat(reservedOf(partId))
                .as("резерв остался от неудавшейся сделки")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Оплата сверх долга уходит на лицевой счёт")
    void overpaymentGoesToAccount() throws Exception {
        Long partId = partWithStock("Фара правая", 1);
        long dealId = createDeal(partId);
        MockHttpSession session = login("seller");

        mvc.perform(post("/api/deals/" + dealId + "/payments").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":6000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(6000));

        // Округлили вверх, отдали лишнюю тысячу — обычное дело на разборке.
        assertThat(accountBalance())
                .as("переплата пропала")
                .isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("История сделки доступна и содержит создание")
    void historyIsExposed() throws Exception {
        Long partId = partWithStock("Зеркало", 1);
        long dealId = createDeal(partId);

        mvc.perform(get("/api/deals/" + dealId + "/history").session(login("seller")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("CREATED"));
    }

    @Test
    @DisplayName("Сделки клиента отдаются списком")
    void dealsOfCustomer() throws Exception {
        createDeal(partWithStock("Порог левый", 1));
        createDeal(partWithStock("Порог правый", 1));

        mvc.perform(get("/api/deals?customerId=" + customer).session(login("seller")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("Поиск продавца показывает свободный остаток, а не статус")
    void stockSearchShowsAvailable() throws Exception {
        Long partId = partWithStock("Интеркулер турбины", 3);
        MockHttpSession session = login("seller");

        // Одну из трёх отложили: карточка по-прежнему IN_STOCK, но продать
        // можно две. Статус про это не скажет ничего.
        mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(partId, 1)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/parts/stock?q=интеркулер").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].partId").value(partId))
                .andExpect(jsonPath("$[0].status").value("IN_STOCK"))
                .andExpect(jsonPath("$[0].qtyAvailable").value(2))
                .andExpect(jsonPath("$[0].qtyReserved").value(1))
                .andExpect(jsonPath("$[0].warehouseName").value("Ткацкая"));
    }

    @Test
    @DisplayName("Полностью отложенная деталь из поиска не исчезает")
    void fullyReservedIsStillVisible() throws Exception {
        Long partId = partWithStock("Стартер Приора", 1);
        MockHttpSession session = login("seller");

        mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(partId, 1)))
                .andExpect(status().isCreated());

        // Продавцу нужно ответить «есть, но отложена до завтра», а не «нет»:
        // клиент перезвонит, а деталь освободится.
        mvc.perform(get("/api/parts/stock?q=стартер").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].qtyAvailable").value(0));
    }

    @Test
    @DisplayName("Выданная деталь из поиска пропадает")
    void issuedPartLeavesSearch() throws Exception {
        Long partId = partWithStock("Компрессор кондиционера", 1);
        long dealId = createDeal(partId);
        MockHttpSession session = login("seller");

        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());

        // Остатка нет — предлагать её клиенту нельзя ни с какой оговоркой.
        mvc.perform(get("/api/parts/stock?q=компрессор").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Клиент находится по телефону в любом написании")
    void customerFoundByPhone() throws Exception {
        MockHttpSession session = login("seller");

        mvc.perform(post("/api/customers").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Пётр\",\"phone\":\"+7 (999) 123-45-67\"}"))
                .andExpect(status().isCreated());

        // Один и тот же номер записывают и с +7, и с 8, и со скобками.
        mvc.perform(get("/api/customers?q=9991234567").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Пётр"));
    }

    @Test
    @DisplayName("Позиция сделки несёт наименование, а не только номер детали")
    void itemsCarryTitle() throws Exception {
        Long partId = partWithStock("Бачок омывателя", 1);
        long dealId = createDeal(partId);

        // Без наименования экран возврата показывает «деталь 4712», и продавец
        // отмечает строки наугад.
        mvc.perform(get("/api/deals/" + dealId).session(login("seller")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Бачок омывателя"));
    }

    @Test
    @DisplayName("Возврат ставит деталь обратно на склад")
    void returnRestocksPart() throws Exception {
        Long partId = partWithStock("Генератор", 1);
        long dealId = createDeal(partId);
        MockHttpSession session = login("seller");

        long itemId = firstItemId(dealId, session);
        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());

        // Склад возврата не обязан совпадать со складом выдачи — здесь он тот же,
        // но приходит из тела запроса, а не подставляется из сделки.
        mvc.perform(post("/api/deals/" + dealId + "/returns").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"не подошла",
                                 "items":[{"dealItemId":%d,"restocked":true}]}"""
                                .formatted(warehouse, itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").isNumber())
                .andExpect(jsonPath("$.amount").value(5000));

        assertThat(qtyOf(partId))
                .as("возвращённая деталь не встала на склад — её нельзя продать снова")
                .isEqualByComparingTo("1");

        mvc.perform(get("/api/deals/" + dealId + "/returns").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reason").value("не подошла"));
    }

    @Test
    @DisplayName("Брак: деньги возвращают, а на склад деталь не ставят")
    void brokenReturnDoesNotRestock() throws Exception {
        Long partId = partWithStock("Турбина", 1);
        long dealId = createDeal(partId);
        MockHttpSession session = login("seller");

        long itemId = firstItemId(dealId, session);
        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());

        mvc.perform(post("/api/deals/" + dealId + "/returns").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"брак",
                                 "items":[{"dealItemId":%d,"restocked":false}]}"""
                                .formatted(warehouse, itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(5000));

        // Сломанное продать второй раз нельзя: деньги клиенту отдали,
        // а в остатке этой детали быть не должно.
        assertThat(qtyOf(partId))
                .as("бракованная деталь встала в остаток — её предложат следующему клиенту")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Перенос уводит позицию в новую сделку, не снимая резерв")
    void transferKeepsReservation() throws Exception {
        Long staying = partWithStock("Коллектор впускной", 1);
        Long moving = partWithStock("Коллектор выпускной", 1);
        MockHttpSession session = login("seller");

        var created = mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"items":[
                                   {"partId":%d,"quantity":1,"warehouseId":%d},
                                   {"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customer, staying, warehouse, moving, warehouse)))
                .andExpect(status().isCreated())
                .andReturn();

        long dealId = idOf(created);
        long movingItemId = itemIdOfPart(dealId, moving, session);

        mvc.perform(post("/api/deals/" + dealId + "/transfer").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemIds\":[%d]}".formatted(movingItemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not((int) dealId)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].partId").value(moving))
                // Новая сделка не черновик: товар на складе остался отложенным,
                // и документ, который этого не говорит, продавец не выдаст —
                // кнопка выдачи смотрит на статус.
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.reservedUntil").isNotEmpty());

        // Товар сменил документ, а не освободился: он по-прежнему обещан
        // тому же клиенту, и второму продавцу его отдавать нельзя.
        assertThat(reservedOf(moving))
                .as("резерв слетел при переносе — деталь уйдёт другому клиенту")
                .isEqualByComparingTo("1");
        assertThat(reservedOf(staying)).isEqualByComparingTo("1");

        mvc.perform(get("/api/deals/" + dealId).session(session))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].partId").value(staying));
    }

    private long firstItemId(long dealId, MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/deals/" + dealId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(result.getResponse().getContentAsString()
                .replaceAll("^.*\"items\":\\[\\{\"id\":(\\d+).*$", "$1"));
    }

    private long itemIdOfPart(long dealId, Long partId, MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/deals/" + dealId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        var matcher = java.util.regex.Pattern
                .compile("\\{\"id\":(\\d+),\"partId\":" + partId + ",")
                .matcher(result.getResponse().getContentAsString());
        assertThat(matcher.find()).as("позиция с деталью %s не найдена", partId).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static long idOf(org.springframework.test.web.servlet.MvcResult result)
            throws Exception {
        return Long.parseLong(result.getResponse().getContentAsString()
                .replaceAll("^\\{\"id\":(\\d+).*$", "$1"));
    }

    /** Заводит сотрудника, если его ещё нет, и возвращает идентификатор. */
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

    private String createBody(Long partId, int quantity) {
        return """
                {"customerId":%d,"items":[{"partId":%d,"quantity":%d,"warehouseId":%d}]}"""
                .formatted(customer, partId, quantity, warehouse);
    }

    private long createDeal(Long partId) throws Exception {
        var result = mvc.perform(post("/api/deals").with(csrf()).session(login("seller"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(partId, 1)))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll("^\\{\"id\":(\\d+).*$", "$1"));
    }

    private Long partWithStock(String title, int qty) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price)
                    VALUES (1, ?, 5000, 2000) RETURNING id""", Long.class, title);
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id)
                    VALUES (?, 'INTAKE', ?, ?)""", partId, qty, warehouse);
            return partId;
        });
    }

    private BigDecimal reservedOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT COALESCE(sum(qty_reserved), 0) FROM part_stock WHERE part_id = ?""",
                BigDecimal.class, partId));
    }

    private BigDecimal qtyOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT COALESCE(sum(qty), 0) FROM part_stock WHERE part_id = ?""",
                BigDecimal.class, partId));
    }

    private BigDecimal accountBalance() {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT COALESCE(sum(amount), 0) FROM customer_account_entry
                 WHERE customer_id = ? AND entry_type = 'TOP_UP'""",
                BigDecimal.class, customer));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"salesco","login":"%s","password":"пароль"}"""
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
