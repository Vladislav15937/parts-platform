package ru.partsflow.platform.settings;

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

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Срок резервирования — настройка компании, а не константа в сборке
 * (задача 0049).
 *
 * <p>До этой правки в {@code SalesController} стояло
 * {@code DEFAULT_RESERVATION = Duration.ofDays(3)}, и поменять его можно было
 * только релизом. Разборка с ходовыми деталями держит резерв сутки, разборка
 * на редкие машины — неделю.
 *
 * <p><b>Главная проверка здесь — {@link #nextDealTakesNewTerm()}:</b> она
 * заводит сделку, меняет настройку и заводит вторую, требуя **разных** сроков
 * от двух сделок одного теста. Проверка «срок равен трём дням» прошла бы
 * и на прежней константе, то есть ничего бы не сторожила, — а «первая три,
 * вторая один» на константе падает.
 *
 * <p>Своя схема: сроки считаются от настройки арендатора, и сосед, поменявший
 * её на свою, менял бы её и здесь.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class CompanyReservationTest extends PostgresTestBase {

    private static final String TENANT = "t_000152";

    private static final long TENANT_ID = 152;

    /**
     * Арендатор, не докатанный до {@code tenant/066}: у него нет таблицы
     * настроек вовсе.
     *
     * <p>Своя схема, а не снос таблицы в {@link #TENANT}: порядок методов
     * в классе не гарантирован, и снесённая посреди прогона таблица уронила
     * бы соседние проверки, к правке отношения не имеющие. Отставание здесь —
     * постоянное состояние схемы, а не шаг одного теста.
     */
    private static final String BEHIND = "t_000153";

    private static final long BEHIND_ID = 153;

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
    private Long behindWarehouse;
    private Long behindCustomer;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT, BEHIND);
    }

    @BeforeEach
    void fixtures() {
        registry(TENANT_ID, TENANT, "srokco");
        registry(BEHIND_ID, BEHIND, "otstal");

        inTenant(TENANT, () -> {
            members();
            // Настройка живёт между тестами класса, а каждый из них
            // рассчитывает на умолчание: три дня — то, с чем клиент приходит.
            jdbc.update("UPDATE company_setting SET reservation_days = 3");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            customer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id",
                    Long.class);
            return null;
        });

        inTenant(BEHIND, () -> {
            members();
            // Схема, не докатанная до tenant/066. Снос, а не «строку удалить»:
            // строку заводит тот же changeset, что и таблицу, — отставание
            // выглядит именно так и никак иначе.
            jdbc.update("DROP TABLE IF EXISTS company_setting");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            behindWarehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            behindCustomer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id",
                    Long.class);
            return null;
        });
    }

    private void registry(long id, String schema, String code) {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", id);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка', ?)""", id, schema, code);
    }

    private void members() {
        member("vladelec", "Владелец", "OWNER");
        member("prodavec", "Продавец", "SELLER");
        member("smotryashchiy", "Смотрящий", "VIEWER");
    }

    @Test
    @DisplayName("Новая компания откладывает товар на три дня")
    void defaultIsThreeDays() throws Exception {
        mvc.perform(get("/api/company/settings").session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationDays").value(3));
    }

    /**
     * То, ради чего задача и заведена: настройка меняет **следующую** сделку.
     *
     * <p>Сроки сравниваются между двумя сделками одного прогона, а не
     * с записанным числом: утверждение «сделка отложена на три дня» верно
     * и на константе, которую эта задача убирает.
     */
    @Test
    @DisplayName("Владелец меняет срок — следующая сделка отложена на сутки, а не на трое")
    void nextDealTakesNewTerm() throws Exception {
        long before = createDeal(part("Фара левая до правки"));
        assertThat(reservedUntilOf(before))
                .as("умолчание компании — трое суток")
                .isCloseTo(Instant.now().plus(Duration.ofDays(3)), within(1, java.time.temporal.ChronoUnit.HOURS));

        mvc.perform(put("/api/company/settings").with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationDays\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationDays").value(1));

        long after = createDeal(part("Фара левая после правки"));
        assertThat(reservedUntilOf(after))
                .as("срок новой сделки не взял настройку компании")
                .isCloseTo(Instant.now().plus(Duration.ofDays(1)), within(1, java.time.temporal.ChronoUnit.HOURS));

        assertThat(reservedUntilOf(before))
                .as("настройка переписала срок уже открытой сделки — так быть не должно")
                .isCloseTo(Instant.now().plus(Duration.ofDays(3)), within(1, java.time.temporal.ChronoUnit.HOURS));
    }

    /**
     * Ноль и год с хвостом отбиваются словами, а не отказом базы по
     * {@code CHECK}: тот приезжает пятисоткой без объяснения.
     */
    @Test
    @DisplayName("Срок вне границ отбивается словами")
    void boundsAreExplained() throws Exception {
        MockHttpSession owner = login("vladelec");

        mvc.perform(put("/api/company/settings").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationDays\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("от 1 до 365")));

        mvc.perform(put("/api/company/settings").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationDays\":400}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/company/settings").session(owner))
                .andExpect(jsonPath("$.reservationDays").value(3));
    }

    @Test
    @DisplayName("Настройку не видит и не правит никто, кроме владельца")
    void ownerOnly() throws Exception {
        for (String login : new String[]{"prodavec", "smotryashchiy"}) {
            MockHttpSession session = login(login);
            mvc.perform(get("/api/company/settings").session(session))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/company/settings").with(csrf()).session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reservationDays\":7}"))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * У заказа с площадки срок диктует площадка, и настройка его не трогает
     * («Срок ответа площадке — не срок резерва», sales/CLAUDE.md).
     */
    @Test
    @DisplayName("Заказ с площадки берёт срок ответа площадки, а не настройку")
    void marketplaceOrderKeepsItsDeadline() throws Exception {
        mvc.perform(put("/api/company/settings").with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationDays\":1}"))
                .andExpect(status().isOk());

        Long partId = part("Бампер для заказа");
        Instant deadline = Instant.now().plus(Duration.ofDays(9));
        var result = mvc.perform(post("/api/deals/orders").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","orderNo":"0049-1",
                                 "replyDeadline":"%s",
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(deadline, partId, warehouse)))
                .andExpect(status().isCreated())
                .andReturn();

        // Первый «id» в ответе заказа — это id сделки: она стоит первым полем
        // OrderView. Почему поиском, а не срезом всей строки, — в idOf.
        long dealId = idOf(result.getResponse().getContentAsString());
        assertThat(reservedUntilOf(dealId))
                .as("настройка компании вмешалась в срок заказа площадки")
                .isCloseTo(deadline, within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    /**
     * Отставшая схема продаёт как раньше, а не отвечает пятисоткой.
     *
     * <p><b>Это главная проверка правки после разбора PR #163.</b> До задачи
     * 0049 создание сделки на схеме, не докатанной до `tenant/066`, работало
     * всегда — срок брался константой. Заменив константу чтением таблицы,
     * мы поставили на путь **каждой** сделки запрос к отношению, которого
     * у отставшего арендатора нет: `BadSqlGrammarException` уходил в общий
     * обработчик и превращался в 500 «Внутренняя ошибка» на самом частом
     * действии продавца. То есть настройка срока резерва уронила бы продажи
     * там, где раньше всё работало.
     *
     * <p>Проверяются три поверхности сразу, потому что ломались бы все три
     * одинаково: продажа, чтение настроек владельцем и запись. У первых двух
     * ответ — прежние три дня; у записи — <b>отказ словами</b>, а не молчаливое
     * «сохранено»: сохранять некуда, и владелец, ушедший со страницы
     * уверенным, что у него теперь сутки, узнал бы правду по отложенным
     * на трое сделкам.
     */
    @Test
    @DisplayName("Схема без настроек продаёт как раньше и объясняет, почему не сохраняет")
    void behindSchemaStillSells() throws Exception {
        Long partId = part(BEHIND, behindWarehouse, "Фара на отставшей схеме");
        long dealId = createDeal("otstal", behindCustomer, behindWarehouse, partId);

        assertThat(reservedUntilOf(BEHIND, dealId))
                .as("сделка на отставшей схеме взяла не прежние три дня")
                .isCloseTo(Instant.now().plus(Duration.ofDays(3)),
                        within(1, java.time.temporal.ChronoUnit.HOURS));

        MockHttpSession owner = login("otstal", "vladelec");
        mvc.perform(get("/api/company/settings").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationDays").value(3));

        mvc.perform(put("/api/company/settings").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationDays\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("не накатаны")));
    }

    private long createDeal(Long partId) throws Exception {
        return createDeal("srokco", customer, warehouse, partId);
    }

    /** Схему выбирает вход: она берётся из сессии, а не из запроса. */
    private long createDeal(String company, Long buyer, Long stock, Long partId)
            throws Exception {
        var result = mvc.perform(post("/api/deals").with(csrf())
                        .session(login(company, "prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(buyer, partId, stock)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result.getResponse().getContentAsString());
    }

    /**
     * Номер из ответа достаётся поиском, а не {@code replaceAll("…​.*$")},
     * и это не вкусовщина.
     *
     * <p>{@code getContentAsString()} читает тело <b>не</b> в UTF-8, поэтому
     * кириллица приезжает побитой, — и среди побитых символов попадается
     * U+0085 (NEL): «х» в UTF-8 это {@code D1 85}, а прочитанное по байту
     * даёт {@code Ñ} и {@code NEL}. Для регулярного выражения Java это
     * <b>разделитель строк</b>, который {@code .} не покрывает, и
     * {@code ".*$"} перестаёт доходить до конца тела. Поймано живым прогоном:
     * та же строка разбора работала на «Фара левая до правки» и отказала
     * на «Фара на отставшей схеме» — разница ровно в букве «х».
     */
    private static long idOf(String body) {
        var found = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (!found.find()) {
            throw new AssertionError("В ответе нет идентификатора: " + body);
        }
        return Long.parseLong(found.group(1));
    }

    private Long part(String title) {
        return part(TENANT, warehouse, title);
    }

    private Long part(String schema, Long stock, String title) {
        return inTenant(schema, () -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price)
                    VALUES (1, ?, 5000, 2000) RETURNING id""", Long.class, title);
            ledger.record(StockMovement.intake(
                    partId, java.math.BigDecimal.ONE, stock, null));
            return partId;
        });
    }

    private Instant reservedUntilOf(long dealId) {
        return reservedUntilOf(TENANT, dealId);
    }

    private Instant reservedUntilOf(String schema, long dealId) {
        return inTenant(schema, () -> jdbc.queryForObject(
                "SELECT reserved_until FROM deal WHERE id = ?",
                java.sql.Timestamp.class, dealId)).toInstant();
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
        return login("srokco", login);
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

    private <T> T inTenant(Supplier<T> body) {
        return inTenant(TENANT, body);
    }

    private <T> T inTenant(String schema, Supplier<T> body) {
        TenantContext.set(schema);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
