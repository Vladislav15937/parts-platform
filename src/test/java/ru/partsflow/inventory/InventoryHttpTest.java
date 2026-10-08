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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

    private static final String TENANT = "t_000143";

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
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 143");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (143, ?, 'Разборка', 'invco')""", TENANT);

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
            // Номер позиции и внутренний id разведены намеренно: в свежей
            // схеме обе последовательности начинаются с единицы, и подмена
            // `p.number` на `p.id` прошла бы зелёной.
            jdbc.queryForObject("SELECT nextval('part_number_seq')", Long.class);
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
                .andExpect(jsonPath("$[0].delta").value(-1.0))
                // Номер позиции в расхождениях (задача 0168): публичного кода
                // здесь нет вовсе, и до него спорную полку нечем было назвать
                // вслух тому, кто сводит расхождения.
                .andExpect(jsonPath("$[0].number").value(partNumber()));

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
     *
     * <p><b>Проведённый пересчёт проверяется отдельно, и это главное.</b>
     * Нормально закрытый документ всегда {@code APPLIED}: «подсчёт завершён» —
     * промежуточный шаг. Пока воронка «Выполненные» слала один
     * {@code status=COUNTED}, проведённый пересчёт не попадал ни в одну
     * воронку, кроме «Все пересчёты», — то есть главный вопрос журнала
     * («когда эту полку считали в последний раз») отвечал пустотой.
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

        // Пересчёт всего склада, проведённый до конца, — «Проведён».
        long appliedSessionId = openWholeWarehouse(owner);
        mvc.perform(post("/api/inventory/sessions/%d/counts".formatted(appliedSessionId))
                        .with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partId\":%d,\"qty\":2}".formatted(partId)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/inventory/sessions/%d/finish".formatted(appliedSessionId))
                        .with(csrf()).session(owner))
                .andExpect(status().isOk());
        mvc.perform(post("/api/inventory/sessions/%d/apply".formatted(appliedSessionId))
                        .with(csrf()).session(owner))
                .andExpect(status().isOk());

        // Ещё один — завершён, но не проведён: «Подсчёт завершён».
        long countedSessionId = openWholeWarehouse(owner);
        mvc.perform(post("/api/inventory/sessions/%d/finish".formatted(countedSessionId))
                        .with(csrf()).session(owner))
                .andExpect(status().isOk());

        // И последний остаётся открытым — «В работе».
        long allSessionId = openWholeWarehouse(owner);

        // Без фильтра — все четыре, новые сверху, и общее число рядом.
        mvc.perform(get("/api/inventory/sessions").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.rows.length()").value(4))
                .andExpect(jsonPath("$.rows[0].id").value(allSessionId))
                .andExpect(jsonPath("$.rows[3].id").value(cellSessionId));

        // Воронка «В работе» находит только открытый, весь склад.
        mvc.perform(get("/api/inventory/sessions?status=OPEN").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].id").value(allSessionId))
                .andExpect(jsonPath("$.rows[0].selection").value("Ткацкая · весь склад"));

        // Воронка «Выполненные» — оба закрытых по-хорошему статуса разом.
        // Проведённый обязан быть здесь: воронка по одному COUNTED оставляла
        // его вне всех воронок, кроме «Все пересчёты».
        mvc.perform(get("/api/inventory/sessions?status=COUNTED&status=APPLIED").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].id").value(countedSessionId))
                .andExpect(jsonPath("$.rows[0].status").value("COUNTED"))
                .andExpect(jsonPath("$.rows[1].id").value(appliedSessionId))
                .andExpect(jsonPath("$.rows[1].status").value("APPLIED"));

        // Воронка «Отменённые» находит закрытый пересчёт ячейки — то, что
        // раньше не находилось вовсе ни списком, ни по номеру.
        mvc.perform(get("/api/inventory/sessions?status=CANCELLED").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].id").value(cellSessionId))
                .andExpect(jsonPath("$.rows[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.rows[0].selection").value("Ткацкая · A-01-1"));

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
        mvc.perform(get("/api/inventory/sessions?status=OPEN&status=NOPE").session(owner))
                .andExpect(status().isBadRequest());

        // Несуществующий пересчёт называется словом экрана, а не базы:
        // вкладка везде говорит «Пересчёт». И без номера строки (задача 0067):
        // по нему человеку нечего ни искать, ни спрашивать — он уходит в лог.
        // Утверждение отрицательное: «сказано „не найден“» проходило и до
        // правки, а номера в ответе быть не должно.
        mvc.perform(get("/api/inventory/sessions/999999").session(owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Пересчёт не найден — обновите страницу, список устарел"))
                .andExpect(jsonPath("$.message", not(containsString("999999"))));

        // «Просмотр» читает список наравне с владельцем — журнал ссылается
        // на пересчёт, и посмотреть, что тогда считали, это не то же самое,
        // что провести или отменить.
        MockHttpSession viewer = login("smotrjaschiy");
        mvc.perform(get("/api/inventory/sessions").session(viewer))
                .andExpect(status().isOk());
    }

    /**
     * Комментарий к пересчёту — пункт приёмки 7 задачи 0020.
     *
     * <p>Ради него в журнал пересчётов и заходят: номер с датой говорят,
     * что документ был, а «83619 не найден» — зачем его открывали.
     *
     * <p><b>Кладовщик пишет не сюда, а в своё поле</b> (задача 0169, тест
     * ниже). Отказ ему на этом пути проверяется намеренно: одно поле
     * на двоих означало бы, что второй пишущий молча затирает первого.
     *
     * <p>Отдельно проверяется, что пустой комментарий становится
     * {@code NULL}, а не пустой строкой. В этом проекте на разнице
     * «не заполнено» и «значение» спотыкались дважды — на снятом штрихкоде
     * и на нулевой цене установки, — и оба раза пустое выдавало себя
     * за ответ человека.
     */
    @Test
    @DisplayName("Комментарий пишет владелец, пустой стирается в NULL, закрытый не правится")
    void sessionNoteIsWrittenReadAndFrozen() throws Exception {
        MockHttpSession owner = login("vladelec");

        String opened = mvc.perform(post("/api/inventory/sessions").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(warehouseId)))
                .andExpect(status().isCreated())
                // Пересчёт без комментария — пусто, а не пустая строка.
                .andExpect(jsonPath("$.note").isEmpty())
                .andReturn().getResponse().getContentAsString();
        long sessionId = Long.parseLong(opened.replaceAll(".*\"id\":(\\d+).*", "$1"));
        String noteUrl = "/api/inventory/sessions/%d/note".formatted(sessionId);

        // Пробелы по краям срезаются — иначе «  » сохранилось бы значением.
        mvc.perform(post(noteUrl).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"  83619 не найден  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("83619 не найден"));

        // Написанное видно в колонке списка — ради этого журнал и открывают.
        mvc.perform(get("/api/inventory/sessions?status=OPEN").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].id").value(sessionId))
                .andExpect(jsonPath("$.rows[0].note").value("83619 не найден"));

        // Кладовщику здесь отказ, и он остаётся (задача 0169, пункт 2):
        // у ходившего своё поле и свой путь (`/counter-note`), а пиши они
        // оба сюда — второй молча затирал бы первого.
        MockHttpSession keeper = login("kladovshchik");
        mvc.perform(post(noteUrl).with(csrf()).session(keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"я тут ходил\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory/sessions/%d".formatted(sessionId)).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("83619 не найден"));

        // Стёртый комментарий — NULL, а не пустая строка: спрашиваем базу,
        // потому что снаружи «» и null выглядят одинаково пусто.
        mvc.perform(post(noteUrl).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").isEmpty());
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT note FROM inventory_session WHERE id = ?", String.class, sessionId)))
                .isNull();

        // Продавец по складу не ходит и пересчёт не комментирует.
        mvc.perform(post(noteUrl).with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"а я тут был\"}"))
                .andExpect(status().isForbidden());

        // Пункт приёмки 7: закрытый пересчёт не комментируют. Ответ —
        // 409 со словами, а не пятисотка: пишут комментарий с телефона,
        // а офлайн-очередь повторяет 5xx вечно.
        mvc.perform(post("/api/inventory/sessions/%d/cancel".formatted(sessionId))
                        .with(csrf()).session(owner))
                .andExpect(status().isOk());
        mvc.perform(post(noteUrl).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"уже поздно\"}"))
                .andExpect(status().isConflict());
    }

    /**
     * Комментарий ходившего — задача 0169, решение владельца продукта
     * от 29 сентября 2026 (записано в самом файле задачи).
     *
     * <p><b>Как это выглядело для человека.</b> Комментарий к пересчёту был
     * один на документ, и писали его только владелец и менеджер — то есть
     * «83619 не найден» знал кладовщик у полки, а до журнала доезжал пересказ.
     * Отдать кладовщику то же поле нельзя: второй пишущий молча затёр бы
     * первого. Поэтому у ходившего своё поле и свой путь, а нынешний
     * {@code /note} кладовщику по-прежнему закрыт (проверено выше).
     *
     * <p>Путь идемпотентен по {@code requestId}: пишут с телефона через
     * офлайн-очередь, и повтор обязан вернуть ответ, а не затереть то, что
     * успели написать после него.
     */
    @Test
    @DisplayName("Комментарий ходившего: своё поле, повтор по ключу не затирает, закрытый — 409")
    void counterNoteIsTheWalkersOwnField() throws Exception {
        MockHttpSession keeper = login("kladovshchik");
        MockHttpSession owner = login("vladelec");
        long sessionId = openWholeWarehouse(keeper);
        String counterUrl = "/api/inventory/sessions/%d/counter-note".formatted(sessionId);
        String noteUrl = "/api/inventory/sessions/%d/note".formatted(sessionId);

        // Кладовщик пишет своё — пробелы по краям срезаются, как у note.
        mvc.perform(post(counterUrl).with(csrf()).session(keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"  Катушки не считали  \",\"requestId\":\"r-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counterNote").value("Катушки не считали"))
                .andExpect(jsonPath("$.note").isEmpty());

        // Сводящий пишет своё — комментарий ходившего на месте.
        mvc.perform(post(noteUrl).with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"83619 не найден\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("83619 не найден"))
                .andExpect(jsonPath("$.counterNote").value("Катушки не считали"));

        // Ходивший пишет снова — последняя запись побеждает, а комментарий
        // сводившего не трогается.
        mvc.perform(post(counterUrl).with(csrf()).session(keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Катушки посчитали\",\"requestId\":\"r-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counterNote").value("Катушки посчитали"))
                .andExpect(jsonPath("$.note").value("83619 не найден"));

        // Опоздавший повтор первой записи: очередь не получила ответа и шлёт
        // её снова. Ответ — успех (иначе запись застрянет в очереди), но
        // написанное после неё остаётся: повтор — не новая правка.
        mvc.perform(post(counterUrl).with(csrf()).session(keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Катушки не считали\",\"requestId\":\"r-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counterNote").value("Катушки посчитали"));

        // Оба видны и в журнале, и в карточке — отдельными полями.
        mvc.perform(get("/api/inventory/sessions?status=OPEN").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].id").value(sessionId))
                .andExpect(jsonPath("$.rows[0].note").value("83619 не найден"))
                .andExpect(jsonPath("$.rows[0].counterNote").value("Катушки посчитали"));
        mvc.perform(get("/api/inventory/sessions/%d".formatted(sessionId)).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counterNote").value("Катушки посчитали"));

        // Пустое — NULL, а не пустая строка: спрашиваем базу.
        mvc.perform(post(counterUrl).with(csrf()).session(keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"   \",\"requestId\":\"r-3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counterNote").isEmpty());
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT counter_note FROM inventory_session WHERE id = ?", String.class, sessionId)))
                .isNull();
        mvc.perform(post(counterUrl).with(csrf()).session(keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Катушки посчитали\",\"requestId\":\"r-4\"}"))
                .andExpect(status().isOk());

        // Без ключа идемпотентности — 400 словами, а не запись без защиты
        // от повтора и не пятисотка.
        mvc.perform(post(counterUrl).with(csrf()).session(keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"без ключа\"}"))
                .andExpect(status().isBadRequest());

        // Продавец считает, но комментарий ходившего — у трёх ролей,
        // названных задачей.
        mvc.perform(post(counterUrl).with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"а я тут был\",\"requestId\":\"r-seller\"}"))
                .andExpect(status().isForbidden());

        // Закрытый пересчёт не комментируют: 409 со словами, а не 500 —
        // очередь телефона повторяет 5xx вечно, а 409 уводит запись
        // к человеку.
        mvc.perform(post("/api/inventory/sessions/%d/cancel".formatted(sessionId))
                        .with(csrf()).session(owner))
                .andExpect(status().isOk());
        mvc.perform(post(counterUrl).with(csrf()).session(keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"уже поздно\",\"requestId\":\"r-5\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Комментарий пишут, пока пересчёт не проведён и не отменён"));
        // А повтор записи, принятой до закрытия, — по-прежнему успех:
        // она уже сделана, и очереди нужен ответ, чтобы её убрать.
        mvc.perform(post(counterUrl).with(csrf()).session(keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Катушки посчитали\",\"requestId\":\"r-4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counterNote").value("Катушки посчитали"));

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM inventory_note_request WHERE session_id = ?",
                Long.class, sessionId)))
                .as("ключи принятых записей: r-1…r-4, повторы и отказы новых не добавляют")
                .isEqualTo(4L);
    }

    /**
     * Шесть одновременных повторов одной записи — тот случай, на котором
     * у приёмки, ссылки на снимок и заказа с площадки проверка чтением
     * пропускала второй запрос и наружу уезжало «нарушение целостности».
     * Здесь ключ ставится одной инструкцией ({@code ON CONFLICT DO NOTHING}),
     * и каждый повтор обязан получить успех.
     */
    @Test
    @DisplayName("Одновременные повторы комментария ходившего отвечают успехом все")
    void concurrentCounterNoteRepliesAllSucceed() throws Exception {
        MockHttpSession keeper = login("kladovshchik");
        long sessionId = openWholeWarehouse(keeper);
        String counterUrl = "/api/inventory/sessions/%d/counter-note".formatted(sessionId);

        int threads = 6;
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var replies = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < threads; i++) {
                replies.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post(counterUrl).with(csrf()).session(keeper)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"note\":\"Не сканировали\",\"requestId\":\"same\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (var reply : replies) {
                assertThat(reply.get()).as("повтор получил отказ").isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM inventory_note_request WHERE request_id = 'same'",
                Long.class))).isEqualTo(1L);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT counter_note FROM inventory_session WHERE id = ?", String.class, sessionId)))
                .isEqualTo("Не сканировали");
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

    /**
     * Лист обхода называет позицию номером, а не только наименованием.
     *
     * <p><b>Как это выглядело для человека</b> (задача 0168). Кладовщик идёт
     * по полкам с этим листом, и найдя не то, называет деталь вслух тому, кто
     * сводит расхождения. В строке стояло одно наименование: «Фара» на живом
     * складе это сотни строк, а публичного кода в листе обхода нет вовсе.
     *
     * <p>Сверяется с самой колонкой {@code part.number}, а номер в фикстуре
     * сдвинут относительно {@code id}: иначе подмена прошла бы зелёной.
     */
    @Test
    @DisplayName("Лист обхода несёт номер позиции, которым её называют вслух")
    void walkSheetCarriesThePartNumber() throws Exception {
        MockHttpSession session = login();
        long sessionId = openWholeWarehouse(session);
        long number = partNumber();

        assertThat(number)
                .as("номер позиции совпал с её id — проверка перестала ловить подмену")
                .isNotEqualTo(partId);

        mvc.perform(get("/api/inventory/sessions/%d/lines".formatted(sessionId))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].partId").value(partId))
                .andExpect(jsonPath("$[0].number").value(number))
                .andExpect(jsonPath("$[0].title").value("Фара для пересчёта"));
    }

    /**
     * И список кодов сканера — тоже, потому что из него заводится строка листа.
     *
     * <p>Деталь, отсканированная вне листа обхода, становится строкой «вне
     * списка» из этой самой записи. Без номера она оказалась бы единственной
     * строкой листа, которую нечем назвать вслух, — причём ровно той, из-за
     * которой разбирательство и началось.
     */
    @Test
    @DisplayName("Список кодов сканера несёт номер: из него заводится строка «вне списка»")
    void scannerCodesCarryThePartNumber() throws Exception {
        MockHttpSession session = login();
        long sessionId = openWholeWarehouse(session);

        mvc.perform(get("/api/inventory/sessions/%d/codes".formatted(sessionId))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].partId").value(partId))
                .andExpect(jsonPath("$[0].number").value(partNumber()));
    }

    /** Номер позиции из самой колонки: записанное в тест число сдвинулось бы с фикстурой. */
    private long partNumber() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT number FROM part WHERE id = ?", Long.class, partId));
    }

    /** Пересчёт всего склада, открытый заново: одновременно открытая на складе одна. */
    private long openWholeWarehouse(MockHttpSession session) throws Exception {
        String opened = mvc.perform(post("/api/inventory/sessions").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(warehouseId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(opened.replaceAll(".*\"id\":(\\d+).*", "$1"));
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
