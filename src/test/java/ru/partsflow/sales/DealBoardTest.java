package ru.partsflow.sales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Доска сделок по состояниям (задача 0052).
 *
 * <p><b>Что проверяется главным.</b> Продавец читает доску числами над
 * колонками — «Просрочено 58, ждут оплаты 4, готов к выдаче 1», — и цена
 * ошибки здесь не в неверной карточке, а в неверном числе: сделка, попавшая
 * в две колонки, или потерявшаяся между ними, видна только сложением. Поэтому
 * тест складывает счётчики и сверяет их с числом незакрытых сделок арендатора,
 * а каждую заведённую сделку ищет по всем пяти колонкам разом и требует,
 * чтобы нашлась ровно одна.
 *
 * <p><b>И второе: «Истек срок» — тот же набор, что отдаёт
 * {@code GET /api/deals/expired-reservations}.</b> Условие живёт в двух
 * местах (репозиторий и {@code CASE} доски), и разъехавшись, они дадут два
 * ответа на один вопрос. Сверяются не числа, а сами номера сделок: равные
 * счётчики при разных наборах — то же самое расхождение, только незаметное.
 *
 * <p><b>Своя схема, а не общая с {@code SalesControllerTest}.</b> Числа
 * здесь абсолютные — по всем незакрытым сделкам арендатора, — и любая сделка
 * соседнего теста сдвинула бы их. По той же причине сделки заводятся одним
 * методом: разложенные по методам, они зависели бы от порядка запуска.
 *
 * <p><b>Через HTTP, а не вызовом сервиса:</b> ответ уходит record'ами,
 * и класс в стиле record Jackson не сериализует — тест на сервис этого
 * не увидит.
 *
 * <p><b>Порядок методов задан, потому что сделки в схеме накапливаются.</b>
 * Числа первого метода абсолютные — по всем незакрытым сделкам арендатора, —
 * а последний набивает колонку сотней; запустись он раньше, упал бы соседний,
 * к его правке отношения не имеющий. Это та же ловушка «схему на два теста
 * не делить» из корневого {@code CLAUDE.md}, только этажом ниже — между
 * методами одного класса; расселять их по схемам здесь дороже, чем назвать
 * очередь.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DealBoardTest extends PostgresTestBase {

    private static final String TENANT = "t_000124";

    /** Слова и порядок колонок — дословно из критерия приёмки задачи 0052. */
    private static final List<String> TITLES = List.of(
            "Новая сделка", "Истек срок", "Ждет оплаты",
            "Частично оплачен", "Готов к выдаче");

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

    private Long nearWarehouse;
    private Long farWarehouse;
    private Long customer;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 124");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (124, ?, 'Разборка', 'doska')""", TENANT);

        inTenant(() -> {
            member("vladelec", "Владелец", "OWNER");
            member("menedzher", "Менеджер", "MANAGER");
            member("prodavets", "Продавец", "SELLER");
            member("kladovshchik", "Кладовщик", "STOREKEEPER");
            member("smotryashchiy", "Смотрящий", "VIEWER");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            nearWarehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            farWarehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Дальний') RETURNING id",
                    Long.class, branch);
            customer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id", Long.class);
            return null;
        });
    }

    /**
     * Всё одним методом: числа доски абсолютные, а разложенные по методам
     * шаги зависели бы от порядка запуска — та же причина, что у реестра
     * платежей.
     */
    @Test
    @Order(1)
    @DisplayName("Каждая незакрытая сделка попадает ровно в одну колонку, и счётчики сходятся")
    void boardSplitsOpenDealsIntoFiveColumns() throws Exception {
        MockHttpSession seller = login("prodavets");
        MockHttpSession manager = login("menedzher");
        long site = dealSource("Сайт");
        long drom = dealSource("Дром");

        // Ждёт оплаты: отложена, срок не вышел, не платили.
        long waiting = createDeal(part("Фара для доски", nearWarehouse), nearWarehouse,
                site, seller);
        // Она же станет частично оплаченной — ниже, после первой проверки.
        long partly = createDeal(part("Стойка для доски", nearWarehouse), nearWarehouse,
                site, seller);
        // Истёк срок: та же отложенная, но срок в прошлом. Ставится записью
        // в базу — API назначить прошлое не даёт, а «до вчера» на разборке
        // это обычное состояние половины списка.
        long expired = createDeal(part("Дверь для доски", nearWarehouse), nearWarehouse,
                site, seller);
        expire(expired);
        // Оплаченная целиком, но с просроченным резервом. Колонок ей подходит
        // две, и выбрана «Истек срок»: этот же набор отдаёт эндпоинт
        // просроченных резервов, а два ответа на один вопрос обязаны совпасть.
        long paidExpired = createDeal(part("Зеркало для доски", nearWarehouse), nearWarehouse,
                site, seller);
        pay(paidExpired, "5000", seller);
        expire(paidExpired);
        // Готов к выдаче: оплачена целиком и не выдана. Менеджер, «Дром»
        // и дальний склад — на них же проверяются три отбора.
        long ready = createDeal(part("Капот для доски", farWarehouse), farWarehouse,
                drom, manager);
        pay(ready, "5000", manager);
        // Новая сделка: необеспеченный заказ с площадки. Клиента у него нет
        // вовсе — карточка обязана пережить пустого покупателя.
        long draft = unbackedOrder(seller);
        // И закрытая сделка: на доске её быть не должно ни в одной колонке.
        long issued = createDeal(part("Крыло для доски", nearWarehouse), nearWarehouse,
                site, seller);
        mvc.perform(post("/api/deals/" + issued + "/issue").with(csrf()).session(seller))
                .andExpect(status().isOk());

        JsonNode before = board("");
        assertThat(columnOf(before, waiting)).isEqualTo("Ждет оплаты");
        assertThat(columnOf(before, partly)).isEqualTo("Ждет оплаты");
        assertThat(count(before, "Ждет оплаты")).isEqualTo(2);
        assertThat(count(before, "Частично оплачен")).isZero();

        // Половина суммы — и сделка обязана перейти в соседнюю колонку,
        // не оставшись в прежней: «ровно одна колонка» проверяется поиском
        // по всем пяти сразу.
        pay(partly, "2500", seller);

        JsonNode board = board("");

        // 1. Слова и порядок колонок — те, по которым переходящий клиент
        // узнаёт свой экран.
        assertThat(titles(board)).isEqualTo(TITLES);

        // 2. Каждая заведённая сделка — ровно в одной колонке, и в той,
        // в какой ей положено.
        assertThat(columnOf(board, draft)).isEqualTo("Новая сделка");
        assertThat(columnOf(board, expired)).isEqualTo("Истек срок");
        assertThat(columnOf(board, paidExpired))
                .as("оплаченная сделка с просроченным резервом ушла из «Истек срок», "
                        + "и колонка разошлась с эндпоинтом просроченных резервов")
                .isEqualTo("Истек срок");
        assertThat(columnOf(board, waiting)).isEqualTo("Ждет оплаты");
        assertThat(columnOf(board, partly)).isEqualTo("Частично оплачен");
        assertThat(columnOf(board, ready)).isEqualTo("Готов к выдаче");

        // 3. Выданной сделки на доске нет вовсе: работа по ней кончилась.
        assertThat(idsOf(board))
                .as("закрытая сделка попала на доску: %s", board)
                .doesNotContain(issued);

        // 4. Сумма счётчиков равна числу незакрытых сделок арендатора.
        // Это и есть проверка «ровно одна колонка» с другой стороны:
        // задвоенная сделка даёт сумму больше, потерянная — меньше.
        long open = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM deal WHERE status IN ('DRAFT', 'RESERVED', 'READY')",
                Long.class));
        assertThat(sum(board))
                .as("сумма колонок разошлась с числом незакрытых сделок: %s", board)
                .isEqualTo(open);

        // 5. Карточка несёт то, по чему продавец её узнаёт, а у частично
        // оплаченной — ещё и внесённое.
        JsonNode card = cardOf(board, partly);
        assertThat(card.path("number").asLong()).isPositive();
        assertThat(card.path("createdAt").isNull()).isFalse();
        assertThat(card.path("totalAmount").decimalValue()).isEqualByComparingTo("5000");
        assertThat(card.path("paidAmount").decimalValue()).isEqualByComparingTo("2500");
        assertThat(card.path("status").asText()).isEqualTo("RESERVED");
        assertThat(card.path("customerName").asText()).isEqualTo("Автосервис");
        // У заказа с площадки покупателя нет, и выдумывать его нечем.
        assertThat(cardOf(board, draft).path("customerName").isNull()).isTrue();

        // 5а. Карточка несёт свою стадию, и у готовой к выдаче она расходится
        // с состоянием документа: оплачена целиком, но статус так и остался
        // `RESERVED` со сроком резерва. Подписанная сырым статусом, такая
        // карточка говорит «Отложена до 15 сентября» — то есть «ещё
        // не оплачена, ждём до этой даты», ровно наоборот. Поэтому стадия
        // едет в ответе, а не выводится экраном из статуса.
        JsonNode readyCard = cardOf(board, ready);
        assertThat(readyCard.path("stage").asText())
                .as("карточке не с чем подписаться, кроме сырого статуса: %s", readyCard)
                .isEqualTo("READY");
        assertThat(readyCard.path("status").asText())
                .as("документ готовой к выдаче сделки перестал быть отложенным — "
                        + "проверка стадии больше ничего не доказывает")
                .isEqualTo("RESERVED");
        assertThat(readyCard.path("reservedUntil").isNull()).isFalse();
        // И стадия карточки всегда равна колонке, в которой она лежит:
        // разойдись они, экран подписал бы карточку не тем, во что её
        // положил счётчик.
        assertThat(stagesOutOfPlace(board))
                .as("стадия карточки разошлась с колонкой: %s", board)
                .isEmpty();

        // 6. «Истек срок» — тот же набор, что отдаёт эндпоинт просроченных
        // резервов. Сверяются номера, а не числа: равные счётчики при разных
        // наборах — то же расхождение, только незаметное.
        List<Long> byEndpoint = expiredByEndpoint(seller);
        assertThat(byEndpoint).as("эндпоинт просроченных резервов не нашёл ничего")
                .isNotEmpty();
        assertThat(idsIn(board, "Истек срок"))
                .as("колонка «Истек срок» разошлась с GET /api/deals/expired-reservations")
                .containsExactlyInAnyOrderElementsOf(byEndpoint);

        // 7. Три отбора действуют на все колонки разом. Дальний склад, «Дром»
        // и менеджер есть ровно у одной сделки — значит доска обязана
        // сойтись к ней одной.
        for (String query : List.of("warehouseId=" + farWarehouse,
                "sourceId=" + drom, "managerId=" + memberId("menedzher"))) {
            JsonNode narrowed = board("?" + query);
            assertThat(idsOf(narrowed))
                    .as("отбор %s не сузил доску до одной сделки: %s", query, narrowed)
                    .containsExactly(ready);
            assertThat(sum(narrowed)).isEqualTo(1);
            assertThat(titles(narrowed))
                    .as("отбор потерял колонки: %s", narrowed)
                    .isEqualTo(TITLES);
        }

        // 8. Значения отборов считаются до отбора, а не после: сузив доску
        // одним складом, продавец обязан видеть остальные — иначе снять
        // поставленный отбор нечем.
        JsonNode narrowed = board("?warehouseId=" + farWarehouse);
        assertThat(names(narrowed, "warehouses")).contains("Ткацкая", "Дальний");
        assertThat(names(narrowed, "sources")).contains("Сайт", "Дром");
        assertThat(names(narrowed, "managers")).contains("Продавец", "Менеджер");
    }

    /** Роли те же, что у списка сделок: выборка чужих документов пачкой. */
    @Test
    @Order(2)
    @DisplayName("Доску видят владелец, менеджер и продавец, остальные — нет")
    void boardIsOpenToTheSameRolesAsTheList() throws Exception {
        for (String allowed : List.of("vladelec", "menedzher", "prodavets")) {
            mvc.perform(get("/api/deals/board").session(login(allowed)))
                    .andExpect(status().isOk());
        }
        for (String denied : List.of("kladovshchik", "smotryashchiy")) {
            mvc.perform(get("/api/deals/board").session(login(denied)))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * Счётчик над колонкой — это все её сделки, а карточек приезжает сотня.
     *
     * <p><b>Заглушкой это не проверяется вовсе:</b> экранный тест отвечает
     * готовой доской, то есть подтверждает собственную фикстуру, а не запрос.
     * Считает же колонку оконное выражение над той же выборкой, из которой
     * режутся карточки, — и посчитай оно показанное, продавец прочёл бы
     * «Ждет оплаты 100» там, где их сто пять, причём чем длиннее колонка,
     * тем точнее выглядит враньё. Поэтому колонка набивается по-настоящему,
     * а число сверяется с {@code count(*)} по базе.
     *
     * <p><b>Идёт последним, и это условие, а не порядок для красоты:</b> сотня
     * незакрытых сделок сдвигает абсолютные числа соседнего метода — доска
     * считает по всему арендатору.
     */
    @Test
    @Order(3)
    @DisplayName("Колонка длиннее сотни: счётчик считает всю, карточек приезжает сто")
    void columnCounterCountsWholePartitionWhileCardsAreCapped() throws Exception {
        MockHttpSession seller = login("prodavets");
        long site = dealSource("Сайт");
        long before = count(board(""), "Ждет оплаты");

        // Одна деталь с остатком в 105 штук, а не 105 деталей: доска считает
        // сделки, и запчасть под каждую стоила бы сотни лишних вставок ради
        // того, что проверке безразлично.
        Long partId = part("Болт для длинной колонки", nearWarehouse, 105);
        for (int i = 0; i < 105; i++) {
            createDeal(partId, nearWarehouse, site, seller);
        }

        JsonNode board = board("");
        long open = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM deal WHERE status IN ('DRAFT', 'RESERVED', 'READY')",
                Long.class));

        assertThat(count(board, "Ждет оплаты"))
                .as("счётчик посчитал показанные карточки, а не всю колонку")
                .isEqualTo(before + 105);
        assertThat(idsIn(board, "Ждет оплаты"))
                .as("колонка отдала не сотню карточек: доска не должна возить "
                        + "всю просрочку живого клиента")
                .hasSize(100);
        assertThat(sum(board))
                .as("сумма счётчиков разошлась с числом незакрытых сделок: %s", board)
                .isEqualTo(open);
    }

    // ---------------------------------------------------------------

    private JsonNode board(String query) throws Exception {
        var result = mvc.perform(get("/api/deals/board" + query).session(login("prodavets")))
                .andExpect(status().isOk())
                .andReturn();
        return new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<String> titles(JsonNode board) {
        List<String> titles = new ArrayList<>();
        board.path("columns").forEach(column -> titles.add(column.path("title").asText()));
        return titles;
    }

    private static long count(JsonNode board, String title) {
        return column(board, title).path("count").asLong();
    }

    private static JsonNode column(JsonNode board, String title) {
        for (JsonNode column : board.path("columns")) {
            if (title.equals(column.path("title").asText())) {
                return column;
            }
        }
        throw new AssertionError("на доске нет колонки «" + title + "»: " + board);
    }

    private static long sum(JsonNode board) {
        long sum = 0;
        for (JsonNode column : board.path("columns")) {
            sum += column.path("count").asLong();
        }
        return sum;
    }

    private static List<Long> idsIn(JsonNode board, String title) {
        List<Long> ids = new ArrayList<>();
        column(board, title).path("cards").forEach(card -> ids.add(card.path("id").asLong()));
        return ids;
    }

    private static List<Long> idsOf(JsonNode board) {
        List<Long> ids = new ArrayList<>();
        board.path("columns").forEach(column ->
                column.path("cards").forEach(card -> ids.add(card.path("id").asLong())));
        return ids;
    }

    /**
     * Карточки, чья стадия не совпала с колонкой, где они лежат. Экран
     * подписывает карточку по стадии, а счётчик считает по колонке —
     * разойдись они, подпись говорила бы одно, а положение другое.
     */
    private static List<String> stagesOutOfPlace(JsonNode board) {
        List<String> wrong = new ArrayList<>();
        for (JsonNode column : board.path("columns")) {
            for (JsonNode card : column.path("cards")) {
                if (!column.path("key").asText().equals(card.path("stage").asText())) {
                    wrong.add(card.path("id").asText() + ": " + card.path("stage").asText()
                            + " в колонке " + column.path("key").asText());
                }
            }
        }
        return wrong;
    }

    private static List<String> names(JsonNode board, String field) {
        List<String> names = new ArrayList<>();
        board.path(field).forEach(option -> names.add(option.path("name").asText()));
        return names;
    }

    /**
     * В какой колонке лежит сделка — и падает, если она нашлась в двух:
     * задвоенная карточка это и есть та ошибка, ради которой доска считается
     * одним {@code CASE}.
     */
    private static String columnOf(JsonNode board, long dealId) {
        List<String> found = new ArrayList<>();
        for (JsonNode column : board.path("columns")) {
            for (JsonNode card : column.path("cards")) {
                if (card.path("id").asLong() == dealId) {
                    found.add(column.path("title").asText());
                }
            }
        }
        assertThat(found)
                .as("сделка %d должна быть ровно в одной колонке, а она в %s: %s",
                        dealId, found, board)
                .hasSize(1);
        return found.get(0);
    }

    private static JsonNode cardOf(JsonNode board, long dealId) {
        for (JsonNode column : board.path("columns")) {
            for (JsonNode card : column.path("cards")) {
                if (card.path("id").asLong() == dealId) {
                    return card;
                }
            }
        }
        throw new AssertionError("сделки " + dealId + " нет на доске: " + board);
    }

    private List<Long> expiredByEndpoint(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/deals/expired-reservations").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        List<Long> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }

    /**
     * Источник сделки: справочник наполняется миграцией, и «Дром» там уже
     * есть — вставка вслепую валится о {@code deal_source_uk}.
     */
    private long dealSource(String name) {
        return inTenant(() -> {
            var found = jdbc.queryForList(
                    "SELECT id FROM deal_source WHERE name = ?", Long.class, name);
            return found.isEmpty()
                    ? jdbc.queryForObject(
                            "INSERT INTO deal_source (name) VALUES (?) RETURNING id",
                            Long.class, name)
                    : found.get(0);
        });
    }

    private Long part(String title, Long warehouse) {
        return part(title, warehouse, 1);
    }

    private Long part(String title, Long warehouse, int quantity) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price)
                    VALUES (1, ?, 5000, 2000) RETURNING id""", Long.class, title);
            ledger.record(StockMovement.intake(
                    partId, BigDecimal.valueOf(quantity), warehouse, null));
            return partId;
        });
    }

    private long createDeal(Long partId, Long warehouse, long source, MockHttpSession session)
            throws Exception {
        var result = mvc.perform(post("/api/deals").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"dealSourceId":%d,
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customer, source, partId, warehouse)))
                .andExpect(status().isCreated())
                .andReturn();
        return new ObjectMapper()
                .readTree(result.getResponse().getContentAsByteArray()).path("id").asLong();
    }

    /**
     * Заказ с площадки, который нечем обеспечить: сделка остаётся черновиком
     * и ничего не резервирует — это и есть наша «Новая сделка».
     */
    private long unbackedOrder(MockHttpSession session) throws Exception {
        Long partId = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, cost_price)
                VALUES (1, 'Бампер без остатка', 7000, 3000) RETURNING id""", Long.class));
        var result = mvc.perform(post("/api/deals/orders").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","orderNo":"DOSKA-1",
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(partId, nearWarehouse)))
                .andExpect(status().isCreated())
                .andReturn();
        return new ObjectMapper().readTree(result.getResponse().getContentAsByteArray())
                .path("deal").path("id").asLong();
    }

    private void pay(long dealId, String amount, MockHttpSession session) throws Exception {
        mvc.perform(post("/api/deals/" + dealId + "/payments").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    private void expire(long dealId) {
        inTenant(() -> jdbc.update(
                "UPDATE deal SET reserved_until = now() - interval '1 day' WHERE id = ?", dealId));
    }

    private Long memberId(String login) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login));
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
                                {"company":"doska","login":"%s","password":"пароль"}"""
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
