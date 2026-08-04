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
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.support.PostgresTestBase;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Заказы площадок через HTTP, а не через сервис.
 *
 * <p>Отдельный класс намеренно. {@code MarketplaceOrderTest} зовёт сервис
 * изнутри своей транзакции и потому не видит целого класса ошибок: коллекция
 * позиций ленивая, {@code open-in-view} выключен, и сделка, прочитанная
 * из базы и отданная контроллеру, превращается в пятисотку уже за границей
 * транзакции. Ровно это и случилось на живом прогоне — дважды, на разных
 * путях: сначала на очереди заказов, потом на повторе.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class MarketplaceOrderHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000086";

    @Autowired
    private ru.partsflow.inventory.StockLedger ledger;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long warehouseId;
    private Long customerId;
    private Long partId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 86");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (86, ?, 'Разборка', 'orderco')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM deal_item");
            jdbc.update("DELETE FROM deal");
            member("prodavets", "Продавец", "SELLER");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            customerId = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Дром') RETURNING id", Long.class);
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, 'Фара заказанная', 4500)
                    RETURNING id""", Long.class);
            ledger.record(StockMovement.intake(partId, new java.math.BigDecimal("2"), warehouseId, null));
            return null;
        });
    }

    @Test
    @DisplayName("Заказ, повтор и очередь отдаются через HTTP, а не пятисоткой")
    void ordersTravelOverHttp() throws Exception {
        MockHttpSession session = login();
        String body = """
                {"marketplace":"DROM","orderNo":"301-516-98","customerId":%d,
                 "deliveryNote":"ТК СДЭК, Надым",
                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                .formatted(customerId, partId, warehouseId);

        mvc.perform(post("/api/deals/orders").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.deal.status").value("RESERVED"))
                .andExpect(jsonPath("$.deal.externalOrderNo").value("301-516-98"))
                // Позиции обязаны доехать: без них экран показывает заказ,
                // по которому непонятно, что именно просят.
                .andExpect(jsonPath("$.deal.items.length()").value(1));

        // Повтор — 200 и прежняя сделка. Именно этот путь читает сделку
        // из базы и на живом прогоне отдавал пятисотку.
        mvc.perform(post("/api/deals/orders").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.deal.items.length()").value(1));

        mvc.perform(get("/api/deals/orders/awaiting-reply").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].items.length()").value(1));
    }

    @Test
    @DisplayName("Подтверждение убирает заказ из очереди")
    void acceptRemovesFromQueue() throws Exception {
        MockHttpSession session = login();
        String created = mvc.perform(post("/api/deals/orders").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","orderNo":"301-777-02","customerId":%d,
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customerId, partId, warehouseId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long dealId = Long.parseLong(created.replaceAll(".*\"deal\":\\{\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/deals/orders/%d/accept".formatted(dealId))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderAcceptedAt").isNotEmpty())
                // Подтверждение — ответ площадке, а не движение склада.
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mvc.perform(get("/api/deals/orders/awaiting-reply").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("История документа называет автора именем, а не номером")
    void historyNamesTheAuthor() throws Exception {
        MockHttpSession session = login();
        String created = mvc.perform(post("/api/deals/orders").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","orderNo":"301-900-03","customerId":%d,
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customerId, partId, warehouseId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long dealId = Long.parseLong(created.replaceAll(".*\"deal\":\\{\"id\":(\\d+).*", "$1"));

        // «Автор 3» не говорит ничего: историю разбирают через недели,
        // когда по номеру никто никого не вспомнит.
        mvc.perform(get("/api/deals/%d/history".formatted(dealId)).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authorName").isNotEmpty());
    }

    /**
     * Экран продавца показывает те же строки, что и ссылка клиенту: услуга
     * идёт наравне с деталью. Без неё сумма строк не сходится с итогом —
     * «итого 7 500» под деталями на 7 000, — и спор об этом начинается
     * в момент оплаты, когда продавец называет одно, а клиент считает другое.
     *
     * <p>Строка при этом обязана нести название, а не номер услуги: «услуга 1»
     * ничего не говорит ни продавцу, ни клиенту. Та же причина, по которой
     * название несёт и строка запчасти.
     */
    @Test
    @DisplayName("Услуга видна в сделке и названа по имени")
    void dealShowsServicesByName() throws Exception {
        MockHttpSession session = login();
        Long delivery = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM service WHERE name = 'Доставка'", Long.class));
        long dealId = orderDeal(session, "301-910-77", delivery);

        String body = mvc.perform(get("/api/deals/" + dealId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].name").value("Доставка"))
                .andReturn().getResponse().getContentAsString();

        var view = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        java.math.BigDecimal linesTotal = java.math.BigDecimal.ZERO;
        for (var item : view.get("items")) {
            linesTotal = linesTotal.add(
                    item.get("price").decimalValue().multiply(item.get("quantity").decimalValue()));
        }
        for (var line : view.get("services")) {
            linesTotal = linesTotal.add(
                    line.get("price").decimalValue().multiply(line.get("quantity").decimalValue()));
        }
        assertThat(linesTotal)
                .as("сумма строк сделки не сходится с её итогом")
                .isEqualByComparingTo(view.get("totalAmount").decimalValue());
    }

    @Test
    @DisplayName("Клиент видит свою покупку по ссылке — и только её")
    void sharedDealShowsOnlyThePurchase() throws Exception {
        MockHttpSession session = login();
        // С доставкой: иначе проверка «итог сходится со строками» пройдёт
        // сама собой и не проверит ничего.
        Long delivery = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM service WHERE name = 'Доставка'", Long.class));
        long dealId = orderDeal(session, "301-910-01", delivery);

        String share = mvc.perform(post("/api/deals/%d/share".formatted(dealId))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String path = share.replaceAll(".*\"path\":\"([^\"]+)\".*", "$1");

        // Ссылка вида /s/{код компании}/{токен}: код говорит, где искать,
        // доступ открывает токен.
        assertThat(path).startsWith("/s/orderco/");
        String token = path.substring(path.lastIndexOf('/') + 1);

        String body = mvc.perform(get("/api/shared/orderco/" + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"number\"").contains("\"items\"");

        // Сумма строк обязана сойтись с итогом: клиент, увидевший «итого 4800»
        // под деталью за 4500, начнёт спор в момент оплаты.
        var view = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        java.math.BigDecimal linesTotal = java.math.BigDecimal.ZERO;
        for (var item : view.get("items")) {
            linesTotal = linesTotal.add(
                    item.get("price").decimalValue().multiply(item.get("quantity").decimalValue()));
        }
        assertThat(linesTotal)
                .as("итог не сходится с суммой строк — клиенту показали не всё")
                .isEqualByComparingTo(view.get("total").decimalValue());
        // Закупочная цена — чужая тайна, и по ссылке, которую пересылают
        // в переписке, ей делать нечего.
        assertThat(body)
                .as("по клиентской ссылке уехало то, чего клиент знать не должен")
                .doesNotContain("costPrice")
                .doesNotContain("customerId")
                .doesNotContain("managerId");
    }

    @Test
    @DisplayName("Повторная выдача ссылки не отзывает прежнюю")
    void sharingTwiceKeepsTheSameLink() throws Exception {
        MockHttpSession session = login();
        long dealId = orderDeal(session, "301-910-02");

        String first = shareOf(session, dealId);
        String second = shareOf(session, dealId);

        // Продавец нажимает второй раз, потому что потерял ссылку
        // в переписке, а не потому, что хочет отозвать прежнюю: новая
        // при каждом нажатии оставила бы у клиента мёртвый адрес.
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("Чужой токен не открывает сделку")
    void foreignTokenIsRejected() throws Exception {
        MockHttpSession session = login();
        orderDeal(session, "301-910-03");

        mvc.perform(get("/api/shared/orderco/" + "0".repeat(48)))
                .andExpect(status().isNotFound());
    }

    private String shareOf(MockHttpSession session, long dealId) throws Exception {
        return mvc.perform(post("/api/deals/%d/share".formatted(dealId))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"path\":\"([^\"]+)\".*", "$1");
    }

    private long orderDeal(MockHttpSession session, String orderNo) throws Exception {
        return orderDeal(session, orderNo, null);
    }

    private long orderDeal(MockHttpSession session, String orderNo, Long serviceId)
            throws Exception {
        String services = serviceId == null ? "[]"
                : "[{\"serviceId\":%d,\"price\":300}]".formatted(serviceId);
        String created = mvc.perform(post("/api/deals/orders").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","orderNo":"%s","customerId":%d,
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}],
                                 "services":%s}"""
                                .formatted(orderNo, customerId, partId, warehouseId, services)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(created.replaceAll(".*\"deal\":\\{\"id\":(\\d+).*", "$1"));
    }

    private void member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (found.isEmpty()) {
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES (?, ?, ?, ?)""",
                    displayName, role, login, passwordEncoder.encode("пароль"));
        }
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"orderco","login":"prodavets","password":"пароль"}"""))
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
