package ru.partsflow.inventory;

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
import java.util.Map;
import ru.partsflow.platform.tenant.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import ru.partsflow.support.PostgresTestBase;

import java.util.function.Supplier;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Инвентаризация через HTTP, а не через сервис.
 *
 * <p>Отдельный класс намеренно, как и {@code MarketplaceOrderHttpTest}.
 * {@code InventoryServiceTest} зовёт сервис изнутри своей транзакции
 * и потому не видит целого класса ошибок: строки сессии ленивые,
 * {@code open-in-view} выключен, а представление сессии их считает — сессия,
 * отданная контроллеру, за границей транзакции превращается
 * в {@code LazyInitializationException}, то есть в пятисотку.
 *
 * <p>Поймано живым прогоном на «завершить подсчёт»: открытие и подсчёт
 * проходили, потому что оба трогают строки по делу, а завершение — нет.
 * А пятисотку офлайн-очередь кладовщика повторяет вечно.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class InventoryHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000087";

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

    private Long warehouseId;
    private Long partId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 87");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (87, ?, 'Разборка', 'invco')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM inventory_line");
            jdbc.update("DELETE FROM inventory_session");
            member();

            // Склад заводится свой на каждый прогон, а не чистится: журнал
            // движений неизменяем, и удалить приход нельзя — снимок сессии
            // на новом складе видит ровно одну позицию.

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, 'Фара для пересчёта', 4500)
                    RETURNING id""", Long.class);
            ledger.record(StockMovement.intake(partId, new java.math.BigDecimal("2"), warehouseId, null));
            return null;
        });
    }

    @Test
    @DisplayName("Открытие, подсчёт, завершение и применение проходят через HTTP")
    void countingTravelsOverHttp() throws Exception {
        MockHttpSession session = login();

        String opened = mvc.perform(post("/api/inventory/sessions").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(warehouseId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines").value(1))
                .andReturn().getResponse().getContentAsString();
        long sessionId = Long.parseLong(opened.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/inventory/sessions/%d/counts".formatted(sessionId))
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partId\":%d,\"qty\":1}".formatted(partId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counted").value(1));

        // Дальше сводит расхождения владелец: кладовщик обошёл полки
        // и внёс факт, а списанная недостача — это убыток.
        MockHttpSession owner = login("vladelec");

        // Вот этот путь и отдавал пятисотку: завершение строк не трогает,
        // а представление сессии их считает.
        mvc.perform(post("/api/inventory/sessions/%d/finish".formatted(sessionId))
                        .with(csrf()).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COUNTED"))
                .andExpect(jsonPath("$.lines").value(1));

        mvc.perform(get("/api/inventory/sessions/%d/discrepancies".formatted(sessionId))
                        .session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].delta").value(-1.0));

        mvc.perform(post("/api/inventory/sessions/%d/apply".formatted(sessionId))
                        .with(csrf()).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjusted").value(1))
                // Пустой список — не украшение: экран по нему решает, показывать
                // ли кладовщику, что часть строк осталась непроведённой.
                .andExpect(jsonPath("$.blocked.length()").value(0));

        // Движение недостачи обязано объяснять себя. У продажи в журнале
        // стоит сделка, у возврата — возврат, у списания и перевозки —
        // документ, а корректировка пересчёта не ссылалась ни на что:
        // «остаток уменьшился на два» без указания пересчёта не отвечает
        // на вопрос, ради которого журнал и ведут.
        Map<String, Object> movement = inTenant(() -> jdbc.queryForMap("""
                SELECT ref_type, ref_id, created_by FROM stock_movement
                 WHERE movement_type = 'INVENTORY_ADJUST' ORDER BY id DESC LIMIT 1"""));
        assertThat(movement).containsEntry("ref_type", "INVENTORY")
                .containsEntry("ref_id", sessionId);
        // И автора: спрашивают журнал ровно тогда, когда ищут, кто унёс деталь.
        assertThat(movement.get("created_by")).as("движение без автора").isNotNull();
    }

    /**
     * Считать может любой, кто работает руками, а проводить — нет.
     *
     * <p>Проведение превращает недостачу в убыток: то же правило, по которому
     * списывает владелец или менеджер, а не кладовщик. Экран это делал
     * с самого начала — блок «Свести расхождения» показан владельцу
     * и менеджеру, — а сервер не проверял ничего: правило жило в одном
     * интерфейсе из двух. Продавец, зашедший запросом, открывал пересчёт,
     * завершал его и проводил, то есть списывал недостачу по всему складу.
     *
     * <p>Поймано не чтением кода, а входом под каждой ролью и попыткой
     * сделать ею всё подряд — тем же способом, что и дыра у «Просмотра».
     */
    @Test
    @DisplayName("Продавец считает, но расхождения не сводит")
    void countingIsNotReconciling() throws Exception {
        MockHttpSession seller = login("prodavec");

        String opened = mvc.perform(post("/api/inventory/sessions").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(warehouseId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long sessionId = Long.parseLong(opened.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/inventory/sessions/%d/counts".formatted(sessionId))
                        .with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partId\":%d,\"qty\":0}".formatted(partId)))
                .andExpect(status().isOk());

        // Дальше — деньги, и продавцу туда нельзя. Посчитанный ноль это
        // недостача: пройди проведение, склад бы её списал.
        mvc.perform(post("/api/inventory/sessions/%d/finish".formatted(sessionId))
                        .with(csrf()).session(seller))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory/sessions/%d/discrepancies".formatted(sessionId))
                        .session(seller))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/sessions/%d/apply".formatted(sessionId))
                        .with(csrf()).session(seller))
                .andExpect(status().isForbidden());

        // Остаток на месте: проведения не было.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT qty FROM part_stock WHERE part_id = ? AND warehouse_id = ?",
                java.math.BigDecimal.class, partId, warehouseId)))
                .as("недостачу списали в обход роли").isEqualByComparingTo("2");

        // А владельцу — можно, иначе проверка запрещает саму работу.
        mvc.perform(post("/api/inventory/sessions/%d/cancel".formatted(sessionId))
                        .with(csrf()).session(login("vladelec")))
                .andExpect(status().isOk());
    }

    /**
     * Журнал пересчётов: закрытый документ можно найти списком, чего раньше
     * не умел ни один эндпоинт — {@code /sessions/open} отдаёт только
     * открытую сессию по складу.
     */
    @Test
    @DisplayName("Список пересчётов находит любой статус, а выборка называет ячейку")
    void sessionsListFindsAnyStatus() throws Exception {
        MockHttpSession owner = login("vladelec");

        Long cellId = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO storage_cell (warehouse_id, code) VALUES (?, 'A-01-1') RETURNING id""",
                Long.class, warehouseId));
        Long cellPartId = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price) VALUES (1, 'Дверь для пересчёта', 3000)
                RETURNING id""", Long.class));
        inTenant(() -> {
            ledger.record(StockMovement.intake(
                    cellPartId, new java.math.BigDecimal("1"), warehouseId, cellId));
            return null;
        });

        // Пересчёт одной ячейки — открыт и сразу отменён, чтобы попасть
        // в воронку «Отменённые», а не «В работе».
        String openedCell = mvc.perform(post("/api/inventory/sessions").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d,\"cellId\":%d}".formatted(warehouseId, cellId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long cellSessionId = Long.parseLong(openedCell.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(post("/api/inventory/sessions/%d/cancel".formatted(cellSessionId))
                        .with(csrf()).session(owner))
                .andExpect(status().isOk());

        // Пересчёт всего склада остаётся открытым — «В работе».
        String openedAll = mvc.perform(post("/api/inventory/sessions").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(warehouseId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long allSessionId = Long.parseLong(openedAll.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // Без фильтра — оба, новые сверху.
        mvc.perform(get("/api/inventory/sessions").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(allSessionId))
                .andExpect(jsonPath("$[1].id").value(cellSessionId));

        // Воронка «В работе» находит только открытый, весь склад.
        mvc.perform(get("/api/inventory/sessions?status=OPEN").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(allSessionId))
                .andExpect(jsonPath("$[0].selection").value("Ткацкая · весь склад"));

        // Воронка «Отменённые» находит закрытый пересчёт ячейки — то, что
        // раньше не находилось вовсе ни списком, ни по номеру.
        mvc.perform(get("/api/inventory/sessions?status=CANCELLED").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(cellSessionId))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$[0].selection").value("Ткацкая · A-01-1"));

        // Карточка одной сессии — тот же набор полей, по любому статусу.
        mvc.perform(get("/api/inventory/sessions/%d".formatted(cellSessionId)).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouseName").value("Ткацкая"))
                .andExpect(jsonPath("$.selection").value("Ткацкая · A-01-1"))
                .andExpect(jsonPath("$.lines").value(1))
                .andExpect(jsonPath("$.counted").value(0));

        // Неизвестный статус — 400 с объяснением, а не 500: это ошибка
        // вызывающего, офлайн-очередь такое не должна повторять вечно.
        mvc.perform(get("/api/inventory/sessions?status=NOPE").session(owner))
                .andExpect(status().isBadRequest());

        // «Просмотр» читает список наравне с владельцем — журнал ссылается
        // на пересчёт, и посмотреть, что тогда считали, это не то же самое,
        // что провести или отменить.
        MockHttpSession viewer = login("smotrjaschiy");
        mvc.perform(get("/api/inventory/sessions").session(viewer))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Открытая сессия склада отдаётся, а не пятисоткой")
    void openSessionIsReadable() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/api/inventory/sessions").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(warehouseId)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/inventory/sessions/open?warehouseId=%d".formatted(warehouseId))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").value(1));
    }

    private void member() {
        member("kladovshchik", "Кладовщик", "STOREKEEPER");
        // Сводит расхождения не тот, кто считает: проведение превращает
        // недостачу в убыток.
        member("vladelec", "Владелец", "OWNER");
        member("prodavec", "Продавец", "SELLER");
        member("smotrjaschiy", "Наблюдатель", "VIEWER");
    }

    private void member(String login, String name, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (found.isEmpty()) {
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES (?, ?, ?, ?)""",
                    name, role, login, passwordEncoder.encode("пароль"));
        }
    }

    private MockHttpSession login() throws Exception {
        return login("kladovshchik");
    }

    private MockHttpSession login(String who) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"invco","login":"%s","password":"пароль"}"""
                                .formatted(who)))
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
