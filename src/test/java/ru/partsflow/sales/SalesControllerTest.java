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

import java.math.BigDecimal;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class SalesControllerTest extends PostgresTestBase {

    private static final String TENANT = "t_000064";

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
                .andExpect(jsonPath("$.amount").value(6000))
                // Момент оплаты ставит база, и вычитывать его обязан Hibernate —
                // как номер документа. Иначе ответ на оплату уходит с null,
                // а тип на клиенте объявляет строку: первый же экран, решивший
                // показать время платежа, нарисует «01.01.1970».
                .andExpect(jsonPath("$.paidAt").isNotEmpty());

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

        // Выдача едет вместе с числом найденного: список обрезан
        // на полусотне, и молча этого делать нельзя.
        mvc.perform(get("/api/parts/stock?q=интеркулер").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].partId").value(partId))
                .andExpect(jsonPath("$.rows[0].status").value("IN_STOCK"))
                .andExpect(jsonPath("$.rows[0].qtyAvailable").value(2))
                .andExpect(jsonPath("$.rows[0].qtyReserved").value(1))
                .andExpect(jsonPath("$.rows[0].warehouseName").value("Ткацкая"))
                .andExpect(jsonPath("$.total").value(1));
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
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].qtyAvailable").value(0));
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
                .andExpect(jsonPath("$.rows.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
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

    /**
     * Продление срока резерва.
     *
     * <p>Число в базе стояло с самого начала, а продлить его было нечем:
     * клиент звонит и просит подержать до пятницы, а продавцу ответить
     * на это в системе некуда. Проверяется всё вместе — что срок сдвинулся
     * в базе, что об этом сказано в истории документа именем автора,
     * и что продлевать умеет продавец, а не только владелец.
     */
    @Test
    @DisplayName("Срок резерва продлевается продавцом и пишется в историю")
    void extendsReservation() throws Exception {
        Long partId = partWithStock("Фара левая", 1);
        MockHttpSession session = login("seller");
        long dealId = createDeal(partId);

        java.time.Instant before = reservedUntilOf(dealId);
        java.time.Instant until = java.time.Instant.now().plus(java.time.Duration.ofDays(10));

        mvc.perform(post("/api/deals/" + dealId + "/reservation").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservedUntil\":\"%s\"}".formatted(until)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));

        assertThat(reservedUntilOf(dealId))
                .as("срок не сдвинулся: продавец сказал клиенту «подержим», а система нет")
                .isAfter(before)
                .isCloseTo(until, within(1, java.time.temporal.ChronoUnit.SECONDS));

        // Через неделю «почему деталь всё ещё лежит» спрашивают именно здесь,
        // и ответом должно быть «продлил Продавец», а не молча изменившаяся дата.
        // Явная кодировка: ответ идёт без charset в Content-Type, и по умолчанию
        // тело читается как ISO-8859-1 — русский текст превращается в кракозябры,
        // и проверка падает на исправном коде.
        String history = mvc.perform(get("/api/deals/" + dealId + "/history").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(history)
                .as("продление не попало в историю документа")
                .contains("Срок резерва продлён до " + java.time.format.DateTimeFormatter
                        .ofPattern("d MMMM", java.util.Locale.of("ru"))
                        .withZone(java.time.ZoneOffset.UTC).format(until))
                .contains("\"authorName\":\"Продавец\"");
    }

    /**
     * Закрытой сделке продлевать нечего.
     *
     * <p>Товар либо у клиента, либо снова на полке, и дата в таком документе
     * обещала бы то, чего никто не обещал. Отказ словами, а не пятисотка:
     * продавец по «ошибке сервера» идёт искать поломку.
     */
    @Test
    @DisplayName("Отменённой сделке срок не продлевают")
    void refusesToExtendClosedDeal() throws Exception {
        Long partId = partWithStock("Фара правая", 1);
        MockHttpSession session = login("seller");
        long dealId = createDeal(partId);

        mvc.perform(post("/api/deals/" + dealId + "/cancel").with(csrf()).session(session))
                .andExpect(status().isOk());

        mvc.perform(post("/api/deals/" + dealId + "/reservation").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservedUntil\":\"%s\"}"
                                .formatted(java.time.Instant.now().plus(java.time.Duration.ofDays(2)))))
                .andExpect(status().isConflict());
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

    /**
     * Реестр возвратов: список видит документы разных сделок, а не только
     * открытой.
     *
     * <p>Тег в причине изолирует эту проверку от возвратов, заведённых
     * другими тестами в той же схеме, — фикстуры их не чистят, поэтому
     * общий счётчик по всей таблице был бы случайным числом.
     */
    @Test
    @DisplayName("Реестр возвратов видит документы разных сделок")
    void returnsListedAcrossDeals() throws Exception {
        String tag = "реестр-обзор";
        MockHttpSession session = login("seller");

        long dealA = createDeal(partWithStock("Амортизатор передний", 1));
        long itemA = firstItemId(dealA, session);
        mvc.perform(post("/api/deals/" + dealA + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deals/" + dealA + "/returns").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"%s a",
                                 "items":[{"dealItemId":%d,"restocked":true}]}"""
                                .formatted(warehouse, tag, itemA)))
                .andExpect(status().isCreated());

        long dealB = createDeal(partWithStock("Амортизатор задний", 1));
        long itemB = firstItemId(dealB, session);
        mvc.perform(post("/api/deals/" + dealB + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deals/" + dealB + "/returns").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"%s b",
                                 "items":[{"dealItemId":%d,"restocked":true}]}"""
                                .formatted(warehouse, tag, itemB)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/deals/returns?q=" + tag).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalAmount").value(10000))
                // Свежие сверху: последний оформленный возврат идёт первой строкой.
                .andExpect(jsonPath("$.items[0].dealId").value((int) dealB))
                .andExpect(jsonPath("$.items[0].customerName").value("Автосервис"))
                .andExpect(jsonPath("$.items[0].dealNumber").isNumber())
                .andExpect(jsonPath("$.items[1].dealId").value((int) dealA));
    }

    @Test
    @DisplayName("Поиск по номеру сделки — точное совпадение")
    void returnsSearchByDealNumberIsExact() throws Exception {
        MockHttpSession session = login("seller");
        long dealId = createDeal(partWithStock("Стекло лобовое", 1));
        long itemId = firstItemId(dealId, session);
        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deals/" + dealId + "/returns").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"поиск-по-номеру",
                                 "items":[{"dealItemId":%d,"restocked":true}]}"""
                                .formatted(warehouse, itemId)))
                .andExpect(status().isCreated());

        Long dealNumber = inTenant(() -> jdbc.queryForObject(
                "SELECT number FROM deal WHERE id = ?", Long.class, dealId));

        // Точный матч по номеру идёт в одном OR с вхождением по причине
        // (§ реестра возвратов): при большом числе сделок в общей схеме
        // теста номер вроде «31» иногда оказывается подстрокой чужой причины
        // вроде «предел-хвост-92831» — это законное поведение отбора, а не
        // повод по нему проверять count(*). Свежий возврат идёт первым
        // (ORDER BY r.id DESC), и именно это здесь проверяется — точный
        // номер нашёл нужный документ, а не единственный документ вообще.
        mvc.perform(get("/api/deals/returns?q=" + dealNumber).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].dealId").value((int) dealId));
    }

    @Test
    @DisplayName("Поиск по причине — вхождение, найдёт по куску слова")
    void returnsSearchByReasonSubstring() throws Exception {
        MockHttpSession session = login("seller");
        long dealId = createDeal(partWithStock("Подшипник ступицы", 1));
        long itemId = firstItemId(dealId, session);
        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deals/" + dealId + "/returns").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"скрипит-хвост-73920",
                                 "items":[{"dealItemId":%d,"restocked":true}]}"""
                                .formatted(warehouse, itemId)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/deals/returns?q=хвост-73920").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("скрипит-хвост-73920"));
    }

    @Test
    @DisplayName("Брак виден флагом restocked=false, и деньги вернулись")
    void returnsCarryRestockedFlag() throws Exception {
        MockHttpSession session = login("seller");
        long dealId = createDeal(partWithStock("Радиатор печки", 1));
        long itemId = firstItemId(dealId, session);
        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deals/" + dealId + "/returns").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"брак-флаг-58204",
                                 "items":[{"dealItemId":%d,"restocked":false}]}"""
                                .formatted(warehouse, itemId)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/deals/returns?q=брак-флаг-58204").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].restocked").value(false));
    }

    /**
     * Отменённый возврат остаётся в счёте по отбору, а из суммы уходит.
     *
     * <p>{@code cancelReturn} в API отклоняет завершённый документ, а
     * возврат завершается в той же транзакции, что и создаётся, — то есть
     * отменённых через обычный путь не бывает. Строка помечается напрямую,
     * это проверка самого запроса выборки, а не бизнес-сценария.
     */
    @Test
    @DisplayName("Сумма отменённого возврата не входит в подвал, а счёт — входит")
    void returnsFooterExcludesCancelledAmount() throws Exception {
        MockHttpSession session = login("seller");
        long dealId = createDeal(partWithStock("Насос гидроусилителя", 1));
        long itemId = firstItemId(dealId, session);
        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deals/" + dealId + "/returns").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"отменённый-возврат-11029",
                                 "items":[{"dealItemId":%d,"restocked":true}]}"""
                                .formatted(warehouse, itemId)))
                .andExpect(status().isCreated());

        inTenant(() -> jdbc.update(
                "UPDATE deal_return SET status = 'CANCELLED' WHERE deal_id = ?", dealId));

        mvc.perform(get("/api/deals/returns?q=отменённый-возврат-11029").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalAmount").value(0));
    }

    @Test
    @DisplayName("Клиент у сделки пуст — customerId пуст и в реестре возвратов")
    void returnsCarryNullCustomer() throws Exception {
        MockHttpSession session = login("seller");
        long dealId = createDeal(partWithStock("Ремень ГРМ", 1));
        long itemId = firstItemId(dealId, session);
        mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deals/" + dealId + "/returns").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"без-клиента-40217",
                                 "items":[{"dealItemId":%d,"restocked":true}]}"""
                                .formatted(warehouse, itemId)))
                .andExpect(status().isCreated());

        // Возврат наследует клиента сделки при создании (SalesService.registerReturn);
        // здесь проверяется само чтение — что LEFT JOIN не роняет строку
        // и не путает «нет клиента» с ошибкой запроса.
        inTenant(() -> jdbc.update("UPDATE deal_return SET customer_id = NULL WHERE deal_id = ?", dealId));

        mvc.perform(get("/api/deals/returns?q=без-клиента-40217").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].customerId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].customerName").value(org.hamcrest.Matchers.nullValue()));
    }

    /**
     * Растущий предел вместо курсора: {@code size} режет строки, а счётчик
     * по отбору остаётся полным — иначе владелец решит, что возвратов
     * меньше, чем на самом деле.
     */
    @Test
    @DisplayName("Предел режет выдачу, но не счётчик по отбору")
    void returnsSizeLimitsRowsNotTotal() throws Exception {
        String tag = "предел-хвост-92831";
        MockHttpSession session = login("seller");

        for (int i = 0; i < 2; i++) {
            long dealId = createDeal(partWithStock("Свеча зажигания " + i, 1));
            long itemId = firstItemId(dealId, session);
            mvc.perform(post("/api/deals/" + dealId + "/issue").with(csrf()).session(session))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/deals/" + dealId + "/returns").with(csrf()).session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"warehouseId":%d,"reason":"%s",
                                     "items":[{"dealItemId":%d,"restocked":true}]}"""
                                    .formatted(warehouse, tag, itemId)))
                    .andExpect(status().isCreated());
        }

        mvc.perform(get("/api/deals/returns?q=" + tag + "&size=1").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @DisplayName("Кладовщик и «Просмотр» реестр возвратов не видят")
    void returnsHiddenFromStorekeeperAndViewer() throws Exception {
        inTenant(() -> member("storekeeper", "Кладовщик", "STOREKEEPER"));

        mvc.perform(get("/api/deals/returns").session(login("storekeeper")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/deals/returns").session(login("viewer")))
                .andExpect(status().isForbidden());
    }

    /**
     * Вкладка «Возвраты» карточки клиента (задача 0022) переиспользует
     * {@code GET /api/deals/returns}, только с добавленным {@code customerId}:
     * возврат другого клиента попадать в неё не должен.
     */
    @Test
    @DisplayName("Отбор по customerId показывает только возвраты этого клиента")
    void returnsFilteredByCustomerId() throws Exception {
        MockHttpSession session = login("seller");
        Long otherCustomer = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Другой клиент') RETURNING id", Long.class));

        long dealMine = createDeal(partWithStock("Зеркало моё", 1));
        long itemMine = firstItemId(dealMine, session);
        mvc.perform(post("/api/deals/" + dealMine + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deals/" + dealMine + "/returns").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"мой-возврат-без-цифр",
                                 "items":[{"dealItemId":%d,"restocked":true}]}"""
                                .formatted(warehouse, itemMine)))
                .andExpect(status().isCreated());

        Long partOther = partWithStock("Зеркало чужое", 1);
        long dealOther = createDealForCustomer(partOther, otherCustomer, session);
        long itemOther = firstItemId(dealOther, session);
        mvc.perform(post("/api/deals/" + dealOther + "/issue").with(csrf()).session(session))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deals/" + dealOther + "/returns").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"reason":"чужой-возврат-без-цифр",
                                 "items":[{"dealItemId":%d,"restocked":true}]}"""
                                .formatted(warehouse, itemOther)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/deals/returns?customerId=" + customer).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].reason")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.not("чужой-возврат-без-цифр"))));

        mvc.perform(get("/api/deals/returns?customerId=" + otherCustomer).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("чужой-возврат-без-цифр"));
    }

    /**
     * Платёж по сделке несёт номер сделки, а пополнение счёта — нет: у него
     * сделки не было вовсе, и «По сделке» на экране обязано быть пустым,
     * а не нулём.
     */
    @Test
    @DisplayName("Платежи клиента: с номером сделки и без него")
    void paymentsOfCustomerListed() throws Exception {
        MockHttpSession session = login("seller");
        Long partId = partWithStock("Радиатор для платежа", 1);
        long dealId = createDeal(partId);
        mvc.perform(post("/api/deals/" + dealId + "/payments").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5000}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/customers/" + customer + "/account/top-up").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/deals/payments?customerId=" + customer).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Свежие сверху: пополнение сделано вторым.
                .andExpect(jsonPath("$[0].dealId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].dealNumber").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].dealId").value((int) dealId))
                .andExpect(jsonPath("$[1].dealNumber").isNumber());
    }

    /**
     * «Ответственный» в карточке клиента показывает имя, а не идентификатор —
     * {@code GET /api/members}, откуда его можно взять на клиенте, доступен
     * только владельцу, а вкладку «Сделки» видит и продавец.
     */
    @Test
    @DisplayName("Сделка несёт имя ответственного, а не только его id")
    void dealCarriesManagerName() throws Exception {
        Long partId = partWithStock("Помпа для ответственного", 1);
        long dealId = createDeal(partId);

        mvc.perform(get("/api/deals?customerId=" + customer).session(login("seller")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].managerId").value(sellerId))
                .andExpect(jsonPath("$[0].managerName").value("Продавец"));
    }

    private long createDealForCustomer(Long partId, Long customerId, MockHttpSession session)
            throws Exception {
        var result = mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customerId, partId, warehouse)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll("^\\{\"id\":(\\d+).*$", "$1"));
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
            ledger.record(StockMovement.intake(partId, java.math.BigDecimal.valueOf(qty), warehouse, null));
            return partId;
        });
    }

    private java.time.Instant reservedUntilOf(long dealId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT reserved_until FROM deal WHERE id = ?",
                java.sql.Timestamp.class, dealId)).toInstant();
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
