package ru.partsflow.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Инвентаризация: сверка факта с учётом.
 *
 * <p>Порядок работы: открыли сессию → она сняла учётный остаток склада →
 * кладовщик вносит фактические количества → завершили пересчёт → посмотрели
 * расхождения → провели. Движения появляются только на последнем шаге и только
 * там, где факт не совпал.
 *
 * <p><b>Главная тонкость — пересчёт не останавливает продажи.</b> На разборке
 * склад работает всегда, «заморозить на день» невозможно. Значит между
 * открытием сессии и проведением остаток легально меняется, и наивная разница
 * «посчитали минус сняли при открытии» превратила бы каждую продажу
 * в недостачу.
 *
 * <p>Поэтому расхождение считается против учётного остатка <b>на момент
 * подсчёта строки</b>: снимок при открытии плюс движения журнала до
 * {@code counted_at}. Журнал неизменяем, так что этот расчёт всегда даёт один
 * и тот же ответ, сколько бы раз его ни повторили.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    /**
     * Сентинел «без адреса» для выборки при открытии.
     *
     * <p>{@code cellId == null} значит «любая» (весь склад, как раньше)
     * и не путается с настоящей ячейкой: реальные ячейки нумеруются
     * с единицы ({@code GENERATED ALWAYS AS IDENTITY}), поэтому ноль
     * безопасно означает «cell_id IS NULL» и никогда не совпадёт
     * с существующим идентификатором.
     */
    static final long NO_CELL = 0L;

    private final InventorySessionRepository sessions;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final StockReservationRepository reservations;
    private final PartChangeLog partChanges;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public InventoryService(InventorySessionRepository sessions,
                            StockLedger ledger,
                            JdbcTemplate jdbc,
                            StockReservationRepository reservations,
                            PartChangeLog partChanges,
                            org.springframework.transaction.support.TransactionTemplate transactions) {
        this.sessions = sessions;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.reservations = reservations;
        this.partChanges = partChanges;
        this.transactions = transactions;
    }

    /**
     * Открывает инвентаризацию и снимает учётный остаток склада — целиком
     * или одной ячейкой.
     *
     * <p>В строки попадает всё, что учёт считает лежащим в этой выборке.
     * Позиции с нулевым остатком не попадают: инвентаризация отвечает
     * на вопрос «то ли лежит, что мы думаем», а не «нет ли на складе
     * чего-то, чего мы не знаем» — на последний отвечает найденный излишек,
     * вносимый сканом или руками.
     *
     * <p><b>Выборка ячейкой — весь смысл пересчёта одной полки.</b> Документ
     * на весь склад в 33 676 позиций никто не закрывает: обход идёт неделями,
     * а без «Завершить подсчёт» нельзя провести расхождения. Одна полка —
     * это минуты, а не недели.
     *
     * @param cellId {@code null} — весь склад, как раньше; {@link #NO_CELL} —
     *               «без адреса»; иначе — конкретная ячейка, которая обязана
     *               принадлежать этому складу
     */
    @Transactional
    public InventorySession open(Long warehouseId, Long cellId, Long authorId) {
        // Склад проверяется словами: чужой номер доезжал до внешнего ключа
        // и возвращался как «Операция нарушает целостность данных» — то есть
        // кладовщик получал сообщение о поломке сервера там, где ошибся
        // в выборе. Пустой лист обхода при этом не отличить от «на складе
        // ничего нет».
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM warehouse WHERE id = ?", Integer.class, warehouseId);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("Склад не найден: " + warehouseId);
        }
        if (cellId != null && cellId != NO_CELL) {
            Integer cellExists = jdbc.queryForObject(
                    "SELECT count(*) FROM storage_cell WHERE id = ? AND warehouse_id = ?",
                    Integer.class, cellId, warehouseId);
            if (cellExists == null || cellExists == 0) {
                throw new IllegalArgumentException(
                        "Ячейка %d не найдена на складе %d".formatted(cellId, warehouseId));
            }
        }

        List<InventorySession> alreadyOpen = sessions.findByWarehouseIdAndStatus(
                warehouseId, InventorySession.SessionStatus.OPEN);
        if (!alreadyOpen.isEmpty()) {
            throw new IllegalStateException(
                    "На складе %d уже идёт инвентаризация %d: две сессии дадут двойную корректировку"
                            .formatted(warehouseId, alreadyOpen.get(0).getId()));
        }

        InventorySession session = new InventorySession(warehouseId, authorId);
        queryPositions(warehouseId, cellId, "part_id, qty, cell_id", rs ->
                session.addLine(rs.getLong("part_id"),
                        rs.getBigDecimal("qty"),
                        rs.getObject("cell_id") == null ? null : rs.getLong("cell_id")));

        return detachable(sessions.saveAndFlush(session));
    }

    /**
     * «Найдено товаров: N» на форме открытия — тем же условием, что и {@link
     * #open}. Разойдись они, счётчик и лист обхода не совпадут, и владелец
     * решит, что часть позиций потерялась.
     */
    @Transactional(readOnly = true)
    public long countPositions(Long warehouseId, Long cellId) {
        if (warehouseId == null) {
            return 0;
        }
        String condition = cellCondition(cellId);
        String sql = "SELECT count(*) FROM part_stock WHERE warehouse_id = ? AND qty > 0"
                + condition;
        Long total = condition.contains("?")
                ? jdbc.queryForObject(sql, Long.class, warehouseId, cellId)
                : jdbc.queryForObject(sql, Long.class, warehouseId);
        return total == null ? 0 : total;
    }

    /** Условие ячейки для {@code WHERE ... part_stock}, общее для счётчика и открытия. */
    private String cellCondition(Long cellId) {
        if (cellId == null) {
            return "";
        }
        return cellId == NO_CELL ? " AND cell_id IS NULL" : " AND cell_id = ?";
    }

    private void queryPositions(Long warehouseId, Long cellId, String select,
                                org.springframework.jdbc.core.RowCallbackHandler handler) {
        String condition = cellCondition(cellId);
        String sql = "SELECT " + select + " FROM part_stock WHERE warehouse_id = ? AND qty > 0"
                + condition + " ORDER BY part_id";
        if (condition.contains("?")) {
            jdbc.query(sql, handler, warehouseId, cellId);
        } else {
            jdbc.query(sql, handler, warehouseId);
        }
    }

    /**
     * Вносит фактическое количество.
     *
     * <p>Позиция, которой в снимке не было, добавляется строкой с нулевым
     * учётным остатком: это найденный излишек — деталь лежит, а учёт про неё
     * не знает.
     */
    // Без @Transactional намеренно: транзакцией управляет перегрузка ниже —
    // ей нужно повторить подсчёт в новой, когда строку позиции успел завести
    // другой кладовщик. Открытая здесь транзакция стала бы внешней, повтор
    // шёл бы в ней же и падал бы на «transaction is marked rollback-only».
    public InventorySession count(Long sessionId, Long partId, BigDecimal qty, Long authorId) {
        return count(sessionId, partId, qty, authorId, null);
    }

    /**
     * @param countedAgo сколько времени прошло с подсчёта до этого запроса.
     *                   Обязателен для телефона: между подсчётом и приходом
     *                   запроса лежит офлайн-очередь, а расхождение считается
     *                   на момент подсчёта. Поставить время получения запроса
     *                   значит записать всё проданное за это время в излишки
     */
    public InventorySession count(Long sessionId, Long partId, BigDecimal qty,
                                  Long authorId, Duration countedAgo) {
        try {
            return transactions.execute(
                    status -> applyCount(sessionId, partId, qty, authorId, countedAgo));
        } catch (org.springframework.dao.DataIntegrityViolationException
                 | org.hibernate.exception.ConstraintViolationException e) {
            // Позицию, которой не было в снимке, второй кладовщик завёл
            // строкой между нашим чтением и записью — уникальный индекс
            // inventory_line_uk это отбил. Повторяем: теперь строка есть,
            // и подсчёт ляжет на неё.
            //
            // Повтор, а не отказ: посчитать одну позицию дважды законно
            // и последовательно — побеждает последний, — и по скорости
            // нажатия поведение расходиться не должно. А наружу это ехало
            // как «Операция нарушает целостность данных», то есть очередь
            // телефона уводила подсчёт в «требует внимания» и работа
            // кладовщика пропадала.
            //
            // Повтор точечный, а не блокировка сессии на каждый подсчёт:
            // столкновение редкое, а подсчёты идут с нескольких телефонов
            // разом, и общая блокировка замедлила бы их все ради него.
            log.warn("Строку позиции {} в сессии {} завёл кто-то другой — повторяем подсчёт",
                    partId, sessionId);
            return transactions.execute(
                    status -> applyCount(sessionId, partId, qty, authorId, countedAgo));
        }
    }

    private InventorySession applyCount(Long sessionId, Long partId, BigDecimal qty,
                                        Long authorId, Duration countedAgo) {
        InventorySession session = require(sessionId);
        if (!session.isOpen()) {
            throw new IllegalStateException(
                    "Считать можно в открытой сессии, а эта в состоянии " + session.getStatus());
        }

        InventoryLine line = session.getLines().stream()
                .filter(l -> l.getPartId().equals(partId))
                .findFirst()
                .orElseGet(() -> session.addLine(partId, BigDecimal.ZERO, null));

        line.count(qty, authorId, countedAt(session, countedAgo));
        return detachable(sessions.saveAndFlush(session));
    }

    /**
     * Восстанавливает момент подсчёта по давности, а не по времени телефона.
     *
     * <p><b>Абсолютное время устройства брать нельзя.</b> У телефонов врёт
     * не ход часов, а их смещение: сброшенное устройство приходит из ангара
     * с датой другого года, и присланный момент подсчёта окажется либо до
     * открытия сессии, либо в будущем. Давность же измеряется на устройстве
     * надёжно — смещение в ней сокращается, а уход хода за смену незаметен.
     *
     * <p>Отсчёт от времени сервера при получении запроса. Хуже точного времени
     * подсчёта на величину сетевой задержки, то есть на секунды; ошибка часов
     * измеряется годами.
     *
     * <p><b>«Сейчас» спрашивается у базы, а не у приложения.</b> Момент
     * подсчёта стоит посередине окна «открытие — подсчёт», а обе его границы
     * ставит база: {@code started_at} и {@code created_at} движений — это её
     * {@code now()}. Взятый по часам JVM, момент подсчёта сравнивался
     * с чужими часами, и расхождение между ними — на этой машине 87 мс,
     * между разными машинами секунды — переносит движение через границу
     * окна. Проданная сразу после подсчёта деталь тогда считается ушедшей
     * до него, то есть становится недостачей, и её списывают проведением.
     *
     * <p>Расхождение измерено, а не предположено: {@code now()} базы против
     * {@code Instant.now()} приложения дают на машине разработчика 87 мс.
     * В контейнере тестов они совпадают до миллисекунды — поэтому тестом
     * это не закрыть, и сторожем остаётся само правило «обе границы окна
     * спрашиваются у одних часов».
     */
    private Instant countedAt(InventorySession session, Duration countedAgo) {
        // Через Timestamp, а не Instant.class: `getObject(..., Instant.class)`
        // драйвер Postgres для timestamptz не умеет — «conversion ... not
        // supported», и падает весь подсчёт.
        Instant now = jdbc.queryForObject("SELECT now()", java.sql.Timestamp.class)
                .toInstant();
        if (countedAgo == null || countedAgo.isNegative()) {
            return now;
        }
        Instant counted = now.minus(countedAgo);
        if (counted.isBefore(session.getStartedAt())) {
            // Подсчёт якобы раньше открытия сессии: давность посчитана неверно.
            // Отвергать нельзя — кладовщик уже прошёл полку, и работа пропадёт;
            // сравним со снимком, как это делалось до появления телефона.
            log.warn("Подсчёт в сессии {}: давность {} уводит раньше её открытия",
                    session.getId(), countedAgo);
            return session.getStartedAt();
        }
        return counted;
    }

    @Transactional
    public InventorySession finishCounting(Long sessionId) {
        InventorySession session = require(sessionId);
        session.finishCounting();
        return detachable(sessions.saveAndFlush(session));
    }

    /**
     * Подтягивает строки перед выходом сессии за границу транзакции.
     *
     * <p>{@code open-in-view} выключен намеренно, а представление сессии
     * считает строки и посчитанные из них: сессия, отданная контроллеру
     * с ленивой коллекцией, превращается в {@code LazyInitializationException},
     * то есть в пятисотку — а офлайн-очередь повторяет 5xx вечно.
     *
     * <p>Тесты, зовущие сервис изнутри своей транзакции, этого не видят вовсе:
     * там коллекция инициализируется сама. Нужен прогон через HTTP, и
     * {@code InventoryHttpTest} заведён именно для этого. Поймано живым
     * прогоном на «завершить подсчёт»: открытие и подсчёт проходили, потому
     * что оба трогают строки по делу, а завершение — нет.
     */
    private InventorySession detachable(InventorySession session) {
        session.getLines().size();
        return session;
    }

    /**
     * Строки сессии с наименованиями — лист обхода для телефона.
     *
     * <p>Отдаётся целиком, одним запросом, при открытии сессии. Пересчёт идёт
     * по полкам в ангаре, где связи нет, поэтому подгружать строки по мере
     * обхода нельзя: кладовщик упрётся в пустой экран ровно там, где работает.
     * Пятьдесят тысяч позиций — это несколько мегабайт, и скачиваются они
     * за столом, где сессию и открывают.
     *
     * <p>Читается напрямую, а не через сущности: строки нужны только для показа,
     * а поднимать ради этого весь агрегат сессии в память незачем.
     */
    @Transactional(readOnly = true)
    public List<Line> lines(Long sessionId) {
        return jdbc.query("""
                SELECT l.part_id, l.qty_expected, l.qty_counted, l.cell_id,
                       p.title, c.code AS cell_code
                  FROM inventory_line l
                  JOIN part p ON p.id = l.part_id
                  LEFT JOIN storage_cell c ON c.id = l.cell_id
                 WHERE l.session_id = ?
                 ORDER BY c.code NULLS LAST, p.title""",
                (rs, i) -> new Line(
                        rs.getLong("part_id"),
                        rs.getString("title"),
                        rs.getObject("cell_id") == null ? null : rs.getLong("cell_id"),
                        rs.getString("cell_code"),
                        rs.getBigDecimal("qty_expected"),
                        rs.getBigDecimal("qty_counted")),
                sessionId);
    }

    /**
     * Открытая инвентаризация склада с той же выборкой, если она есть.
     *
     * <p>Своей колонки под выбор при открытии нет — она дублировала бы то,
     * что уже лежит в строках, и потребовала бы миграции ради этого. Вместо
     * неё сверяем с фактическим адресом строк: сессия, открытая по конкретной
     * ячейке, не заводит ни одной строки с другим {@code cell_id} (см.
     * {@link #open}), и наоборот не бывает. «Любая» подходит всегда — она
     * шире любой уже открытой выборки, а на складе разрешена только одна
     * открытая сессия, так что более точное совпадение искать не из чего.
     */
    @Transactional(readOnly = true)
    public Optional<InventorySession> openSessionOf(Long warehouseId, Long cellId) {
        return sessions.findByWarehouseIdAndStatus(
                warehouseId, InventorySession.SessionStatus.OPEN).stream()
                .findFirst()
                .filter(session -> matchesSelection(session, cellId))
                .map(this::detachable);
    }

    private boolean matchesSelection(InventorySession session, Long cellId) {
        if (cellId == null) {
            return true;
        }
        List<InventoryLine> lines = session.getLines();
        if (lines.isEmpty()) {
            // Выборка без единой позиции (пустая ячейка) не говорит ничего
            // о том, чем она была открыта — пропустить кладовщика дальше
            // дешевле, чем заставить его вслепую гадать со «складом занят».
            return true;
        }
        Long wanted = cellId == NO_CELL ? null : cellId;
        return lines.stream().allMatch(line -> java.util.Objects.equals(line.getCellId(), wanted));
    }

    /**
     * Сколько строк журнала отдаётся разом.
     *
     * <p>Предел нужен не ради экрана, а ради базы: у ориентира журнал
     * инвентаризаций — 1 924 документа, и пересчёт всего склада у клиента
     * с 36 тысячами позиций даёт 36 тысяч строк {@code inventory_line}.
     * Без предела каждое переключение воронки собирало бы счётчики по всему
     * журналу целиком, то есть по сотням тысяч строк — при том что человек
     * смотрит первый экран.
     *
     * <p>Двести, а не пятьдесят: журнал листают глазами сверху вниз, ища
     * «когда эту полку считали в последний раз», и при пересчёте полки
     * через день двести строк — это больше года работы. Число названо
     * в подвале честно: показанное меньше найденного — экран об этом
     * говорит, а не молчит.
     */
    private static final int SESSION_PAGE = 200;

    /**
     * Список пересчётов — владелец находит любой, не только открытый.
     *
     * <p>До этого экран умел искать сессию только по складу через {@link
     * #openSessionOf}, то есть исключительно открытую: проведённый вчера
     * пересчёт открыть было нельзя ни списком, ни по номеру, хотя журнал
     * склада на него ссылается ({@code ref_type = 'INVENTORY'}). Список
     * читается напрямую, а не через сущности — по той же причине, что
     * и лист обхода: строки нужны только для показа.
     *
     * <p><b>Статусов в воронке несколько, а не один.</b> «Выполненные» — это
     * и {@code COUNTED}, и {@code APPLIED}: нормально закрытый пересчёт
     * всегда проведён, и воронка, накрывающая один лишь {@code COUNTED},
     * отвечала бы пустотой на главный вопрос журнала — «когда эту полку
     * считали в последний раз». Группировку задаёт экран
     * ({@code SESSION_FUNNEL}), сервер просто отбирает по набору.
     *
     * @param statuses фильтр воронки; пусто — все статусы разом
     */
    @Transactional(readOnly = true)
    public SessionPage listSessions(List<InventorySession.SessionStatus> statuses) {
        if (statuses.isEmpty()) {
            return new SessionPage(querySummaries("", SESSION_PAGE),
                    jdbc.queryForObject("SELECT count(*) FROM inventory_session", Long.class));
        }
        String where = " WHERE s.status IN (%s)".formatted(
                String.join(", ", java.util.Collections.nCopies(statuses.size(), "?")));
        Object[] params = statuses.stream().map(Enum::name).toArray();
        return new SessionPage(querySummaries(where, SESSION_PAGE, params),
                jdbc.queryForObject("SELECT count(*) FROM inventory_session s" + where,
                        Long.class, params));
    }

    /** Одна сессия любого статуса — карточка списка открывает её нажатием на строку. */
    @Transactional(readOnly = true)
    public SessionSummary sessionSummary(Long sessionId) {
        List<SessionSummary> found = querySummaries(" WHERE s.id = ?", 1, sessionId);
        if (found.isEmpty()) {
            // Словом экрана, а не базы: вкладка называется «Пересчёт»,
            // и «Инвентаризация не найдена» человек читает как сообщение
            // о чём-то другом.
            throw new IllegalArgumentException("Пересчёт не найден: " + sessionId);
        }
        return found.get(0);
    }

    private List<SessionSummary> querySummaries(String where, int limit, Object... params) {
        // %s, а не склейка текстовых блоков "..." + where + "...": закрывающие
        // кавычки второго блока стоят на строке содержимого, и стрипается весь
        // отступ — «?» и «GROUP» слипаются в «?GROUP» без единого пробела между
        // ними, а компилятор молчит. Уже ловили это дважды на других запросах.
        //
        // Отбор страницы стоит отдельным шагом (page), а счётчики считаются
        // только по ней: соединение с inventory_line до предела прошло бы
        // по всем строкам журнала — у клиента, пересчитывающего склад
        // целиком, это 36 тысяч строк на документ.
        //
        // Выборка (ячейка) берётся тем же проходом: отдельный
        // «SELECT DISTINCT session_id, cell_id» был вторым чтением тех же
        // строк ради значения, которое агрегат и так знает.
        String sql = """
                WITH page AS (
                    SELECT s.id
                      FROM inventory_session s
                    %s
                     ORDER BY s.id DESC
                     LIMIT %d
                )
                SELECT s.id, s.warehouse_id, w.name AS warehouse_name, s.status,
                       s.started_at, s.applied_at, s.note,
                       count(l.part_id) AS lines_count,
                       count(l.qty_counted) AS counted_count,
                       count(DISTINCT l.cell_id) AS placed_cells,
                       min(l.cell_id) AS one_cell,
                       bool_or(l.part_id IS NOT NULL AND l.cell_id IS NULL) AS has_unplaced
                  FROM inventory_session s
                  JOIN warehouse w ON w.id = s.warehouse_id
                  LEFT JOIN inventory_line l ON l.session_id = s.id
                 WHERE s.id IN (SELECT id FROM page)
                 GROUP BY s.id, s.warehouse_id, w.name, s.status, s.started_at, s.applied_at,
                          s.note
                 ORDER BY s.id DESC""".formatted(where, limit);
        List<SummaryRow> rows = jdbc.query(sql,
                (rs, i) -> new SummaryRow(rs.getLong("id"), rs.getLong("warehouse_id"),
                        rs.getString("warehouse_name"),
                        InventorySession.SessionStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("applied_at") == null
                                ? null : rs.getTimestamp("applied_at").toInstant(),
                        rs.getInt("lines_count"), rs.getLong("counted_count"),
                        rs.getString("note"),
                        rs.getInt("placed_cells"),
                        rs.getObject("one_cell") == null ? null : rs.getLong("one_cell"),
                        rs.getBoolean("has_unplaced")),
                params);

        Map<Long, String> codes = cellCodesOf(rows);
        return rows.stream()
                .map(r -> new SessionSummary(r.id(), r.warehouseId(), r.warehouseName(),
                        r.warehouseName() + " · " + selectionOf(r, codes),
                        r.status(), r.startedAt(), r.appliedAt(), r.lines(), r.counted(), r.note()))
                .toList();
    }

    /**
     * Комментарий человека к пересчёту — то, ради чего в журнал заходят.
     *
     * <p>Пишет его тот, кто ходил по складу (роли — в {@code
     * InventoryController.COMMENTS}), и пишет по ходу подсчёта: номер и дата
     * говорят, что документ был, а «83619 не найден» — зачем его открывали.
     *
     * <p>Правило «пока не закрыт» держит сама сессия ({@link
     * InventorySession#changeNote}), а не проверка здесь: инвариант должен
     * жить в одном месте, иначе второй вызывающий его обойдёт.
     */
    @Transactional
    public InventorySession changeNote(Long sessionId, String note) {
        InventorySession session = require(sessionId);
        session.changeNote(note);
        return detachable(sessions.saveAndFlush(session));
    }

    /**
     * Выборка сессии из фактического адреса её строк — своей колонки под это
     * нет (см. {@link #matchesSelection}), а заводить её ради одной колонки
     * списка не стоит: значение и так лежит в строках.
     *
     * <p>Сессия, открытая одной ячейкой, заводит строки только с её
     * {@code cell_id} ({@link #open}), поэтому единственное встреченное
     * значение и есть выборка; несколько разных значений бывают только
     * у пересчёта всего склада. Та же неоднозначность, что у «Продолжить
     * начатую»: склад без ячеек вовсе или с одним, целиком без адресов,
     * не отличить от намеренного «Без адреса» — точное различение потребовало
     * бы миграции ради поля, дублирующего строки.
     *
     * <p>Сессия без единой строки (пустая ячейка бывает) читается как «весь
     * склад»: адреса, по которому её назвать, у неё нет вовсе.
     */
    private String selectionOf(SummaryRow row, Map<Long, String> codes) {
        int distinct = row.placedCells() + (row.hasUnplaced() ? 1 : 0);
        if (distinct != 1) {
            return "весь склад";
        }
        if (row.hasUnplaced()) {
            return "без адреса";
        }
        Long cellId = row.oneCell();
        return codes.getOrDefault(cellId, "ячейка " + cellId);
    }

    /** Коды ячеек только тех сессий страницы, у которых выборка — одна ячейка. */
    private Map<Long, String> cellCodesOf(List<SummaryRow> rows) {
        List<Long> cellIds = rows.stream()
                .filter(r -> r.placedCells() == 1 && !r.hasUnplaced())
                .map(SummaryRow::oneCell)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (cellIds.isEmpty()) {
            return Map.of();
        }
        String cellIn = cellIds.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        Map<Long, String> codes = new HashMap<>();
        jdbc.query("SELECT id, code FROM storage_cell WHERE id IN (" + cellIn + ")",
                rs -> { codes.put(rs.getLong("id"), rs.getString("code")); });
        return codes;
    }

    /**
     * @param placedCells  сколько разных ячеек встречено в строках (NULL не в счёт)
     * @param oneCell      наименьшая из них — она же единственная, когда {@code placedCells == 1}
     * @param hasUnplaced  есть ли строки без ячейки
     */
    private record SummaryRow(Long id, Long warehouseId, String warehouseName,
                              InventorySession.SessionStatus status,
                              Instant startedAt, Instant appliedAt,
                              int lines, long counted, String note,
                              int placedCells, Long oneCell, boolean hasUnplaced) {
    }

    /**
     * Страница журнала: показанное и сколько всего есть в воронке.
     *
     * <p>Общее число едет вместе со страницей — как у витрины склада
     * и вкладки колёс. Подвал обязан говорить, сколько пересчётов в воронке,
     * а не сколько строк влезло: счётчик, считающий показанное, врёт ровно
     * на то, чего не видно, и узнать об этом по экрану нельзя никак.
     */
    public record SessionPage(List<SessionSummary> rows, long total) {
    }

    /**
     * Строка списка пересчётов — то, что видно в таблице и в карточке одной
     * сессии, без захода в строки.
     *
     * @param selection склад и ячейка словами: «Основной · A-01-03»,
     *                  «Основной · весь склад» или «Основной · без адреса»
     * @param note      комментарий человека или {@code null}, если его нет.
     *                  Пустой строки тут не бывает — см. {@link
     *                  InventorySession#changeNote}
     */
    public record SessionSummary(Long id, Long warehouseId, String warehouseName, String selection,
                                 InventorySession.SessionStatus status,
                                 Instant startedAt, Instant appliedAt,
                                 int lines, long counted, String note) {
    }

    /**
     * Код детали → позиция, по всему складу сессии (не только по её выборке).
     *
     * <p>Отдаётся целиком, как и лист обхода: скан обязан работать без связи,
     * а различить «деталь лежит в другой ячейке этого же склада» (группа
     * «С проблемами») и «код не найден на этом складе» может только клиент,
     * у которого есть весь список кодов склада, а не только выбранной ячейки.
     */
    @Transactional(readOnly = true)
    public List<WarehouseCode> warehouseCodes(Long sessionId) {
        InventorySession session = require(sessionId);
        return jdbc.query("""
                SELECT p.id AS part_id, p.title, p.public_code, p.barcode,
                       ps.cell_id, c.code AS cell_code
                  FROM part_stock ps
                  JOIN part p ON p.id = ps.part_id
                  LEFT JOIN storage_cell c ON c.id = ps.cell_id
                 WHERE ps.warehouse_id = ? AND ps.qty > 0""",
                (rs, i) -> new WarehouseCode(rs.getLong("part_id"), rs.getString("title"),
                        rs.getString("public_code"), rs.getString("barcode"),
                        rs.getObject("cell_id") == null ? null : rs.getLong("cell_id"),
                        rs.getString("cell_code")),
                session.getWarehouseId());
    }

    /** Строка кода детали для сканера — код товара и владельческий штрихкод разом. */
    public record WarehouseCode(Long partId, String title, String publicCode, String barcode,
                                Long cellId, String cellCode) {
    }

    /**
     * Расхождения: что не сошлось и на сколько.
     *
     * <p>Считается по журналу, поэтому одинаково до и после проведения — можно
     * показать кладовщику, дать пересчитать спорные полки и посмотреть снова.
     */
    @Transactional(readOnly = true)
    public List<DiscrepancyLine> discrepancies(Long sessionId) {
        InventorySession session = require(sessionId);

        List<InventoryLine> lines = session.countedLines().stream()
                .filter(line -> discrepancyOf(session, line).delta().signum() != 0)
                .toList();
        // Наименования одним запросом на всю выдачу, а не по строке:
        // «деталь 4» отправит кладовщика искать её самому, а расхождений
        // на большом складе бывают десятки.
        Map<Long, String> titles = titlesOf(lines.stream().map(InventoryLine::getPartId).toList());

        return lines.stream()
                .map(line -> {
                    Discrepancy d = discrepancyOf(session, line);
                    return new DiscrepancyLine(d.partId(), titles.get(d.partId()),
                            d.qtyExpectedAtOpen(), d.qtyExpectedAtCount(), d.qtyCounted(),
                            d.delta(), d.isShortage(), line.isApplied());
                })
                .toList();
    }

    private Map<Long, String> titlesOf(List<Long> partIds) {
        if (partIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> titles = new HashMap<>();
        // Числа подставляются в текст запроса, а не параметрами: они пришли
        // из базы как long, а не из запроса пользователя, и списки бывают
        // в сотни позиций — на каждую по параметру Postgres не обязан
        // готовить свой план.
        String in = partIds.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        jdbc.query("SELECT id, title FROM part WHERE id IN (" + in + ")",
                rs -> {
                    titles.put(rs.getLong("id"), rs.getString("title"));
                });
        return titles;
    }

    /**
     * Строка расхождения для человека.
     *
     * @param applied проведена ли строка. После проведения расхождение
     *                не исчезает — оно считается на момент подсчёта,
     *                а корректировка записана позже, — и без этой отметки
     *                экран показывает «скорректировано» рядом с той же
     *                минусовой строкой
     */
    public record DiscrepancyLine(Long partId, String title,
                                  BigDecimal qtyExpectedAtOpen, BigDecimal qtyExpectedAtCount,
                                  BigDecimal qtyCounted, BigDecimal delta,
                                  boolean shortage, boolean applied) {
    }

    /**
     * Проводит инвентаризацию: пишет корректировки на расхождения.
     *
     * <p>Сошедшиеся позиции движений не порождают — движение на ноль БД
     * отвергнет, да и журнал не должен пухнуть от записей «всё в порядке».
     *
     * <p><b>Построчно, а не «всё или ничего».</b> Недостачу по детали,
     * обещанной покупателю, списать нельзя: остаток уйдёт ниже резерва,
     * и это отобьёт триггер склада. Останавливать из-за неё всю
     * инвентаризацию значит держать сорок посчитанных полок непроведёнными,
     * пока продавец говорит с покупателем, — а кладовщик, который считал,
     * снять резерв не может по роли.
     *
     * <p>Поэтому проводится всё, что можно, застрявшие строки остаются
     * непроведёнными и возвращаются списком, а сессия не закрывается.
     * Повтор после снятия резерва допишет только их: проведённая строка
     * второй корректировки не породит.
     */
    @Transactional
    public Applied apply(Long sessionId) {
        // Строка сессии берётся под блокировку: отметку applied_at два
        // одновременных проведения читают пустой оба и списывают недостачу
        // дважды. Подробности — у findByIdForUpdate.
        InventorySession session = sessions.findByIdForUpdate(sessionId).orElseThrow(
                () -> new IllegalArgumentException("Пересчёт не найден: " + sessionId));
        Instant now = Instant.now();

        int adjusted = 0;
        List<String> blocked = new ArrayList<>();

        for (InventoryLine line : session.countedLines()) {
            if (line.isApplied()) {
                continue;
            }
            Discrepancy d = discrepancyOf(session, line);
            if (d.delta().signum() == 0) {
                line.markApplied(now);
                continue;
            }
            if (d.delta().signum() < 0) {
                BigDecimal available = reservations.availableQuantity(
                        d.partId(), session.getWarehouseId());
                if (available.compareTo(d.delta().abs()) < 0) {
                    blocked.add(describePromised(d.partId(), available, d.delta().abs()));
                    continue;
                }
            }
            ledger.record(StockMovement.inventoryAdjust(
                    d.partId(), d.delta(), session.getWarehouseId(), sessionId));
            // Недостача обнуляет остаток и списывает карточку: на площадке
            // она обязана стать недоступной, а не ждать полного прайса.
            partChanges.changed(d.partId());
            line.markApplied(now);
            adjusted++;
        }

        session.apply(now);
        sessions.saveAndFlush(session);

        if (adjusted > 0 || !blocked.isEmpty()) {
            log.info("Инвентаризация {} на складе {}: скорректировано {}, застряло {}",
                    sessionId, session.getWarehouseId(), adjusted, blocked.size());
        }
        return new Applied(adjusted, blocked);
    }

    /**
     * @param adjusted сколько позиций скорректировано
     * @param blocked  что не проведено и почему: недостача по детали, которую
     *                 держит резерв. Снимет продавец — повтор допишет
     */
    public record Applied(int adjusted, List<String> blocked) {
    }

    /** Называет деталь и сделку: «деталь 4» отправит кладовщика искать самому. */
    private String describePromised(Long partId, BigDecimal available, BigDecimal needed) {
        String title = jdbc.query("SELECT title FROM part WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, partId);
        List<Long> deals = jdbc.queryForList("""
                SELECT DISTINCT d.number FROM deal_item i
                  JOIN deal d ON d.id = i.deal_id
                 WHERE i.part_id = ? AND i.status = 'RESERVED'
                 ORDER BY d.number""", Long.class, partId);

        return "«%s»: не хватает %s, свободно %s%s".formatted(
                title == null ? "деталь " + partId : title,
                needed.stripTrailingZeros().toPlainString(),
                available.stripTrailingZeros().toPlainString(),
                promisedBy(deals));
    }

    /**
     * Сделки называются числом и решёткой, а множественное число — словом.
     *
     * <p>Перечень под словом в единственном числе («по сделке 1, 14») читается
     * как один документ с составным номером, и кладовщик идёт искать
     * несуществующую сделку. А идёт он по этой строке действовать — снимать
     * резерв, — так что она обязана называть документы так, как их спросят
     * у продавца.
     */
    // Видимость пакета — ради теста: проверяется формулировка,
    // а не путь к базе, и городить две сделки в фикстуре ради строки незачем.
    static String promisedBy(List<Long> deals) {
        if (deals.isEmpty()) {
            return "";
        }
        String numbers = deals.stream()
                .map(number -> "№" + number)
                .collect(java.util.stream.Collectors.joining(", "));
        return deals.size() == 1
                ? ", обещана по сделке " + numbers
                : ", обещана по сделкам " + numbers;
    }

    @Transactional
    public InventorySession cancel(Long sessionId) {
        InventorySession session = require(sessionId);
        session.cancel();
        return detachable(sessions.saveAndFlush(session));
    }

    /**
     * Расхождение по строке.
     *
     * <p>Сравнивается не со снимком при открытии, а с учётным остатком
     * на момент подсчёта: снимок плюс движения журнала за окно
     * «открытие — подсчёт». Иначе проданная во время пересчёта деталь
     * выглядела бы недостачей.
     */
    private Discrepancy discrepancyOf(InventorySession session, InventoryLine line) {
        BigDecimal movedBeforeCount = warehouseDelta(
                line.getPartId(), session.getWarehouseId(),
                session.getStartedAt(), line.getCountedAt());

        BigDecimal expectedAtCount = line.getQtyExpected().add(movedBeforeCount);
        BigDecimal delta = line.getQtyCounted().subtract(expectedAtCount);

        return new Discrepancy(line.getPartId(), line.getQtyExpected(), expectedAtCount,
                line.getQtyCounted(), delta);
    }

    /**
     * Изменение остатка детали на складе за окно времени — по журналу движений.
     *
     * <p>Считается так же, как это делает триггер остатка: расход по складу
     * источника, приход по складу приёмника. Смотреть на знак {@code qty_delta}
     * нельзя — у перемещения он положительный, а для склада-источника это
     * расход.
     */
    private BigDecimal warehouseDelta(Long partId, Long warehouseId, Instant from, Instant to) {
        BigDecimal delta = jdbc.queryForObject("""
                SELECT COALESCE(sum(
                           CASE WHEN to_warehouse_id = ?   THEN abs(qty_delta) ELSE 0 END
                         - CASE WHEN from_warehouse_id = ? THEN abs(qty_delta) ELSE 0 END
                       ), 0)
                  FROM stock_movement
                 WHERE part_id = ?
                   AND created_at > ?
                   AND created_at <= ?""",
                BigDecimal.class,
                warehouseId, warehouseId, partId,
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));

        return delta == null ? BigDecimal.ZERO : delta;
    }

    private InventorySession require(Long sessionId) {
        return sessions.findById(sessionId).orElseThrow(
                () -> new IllegalArgumentException("Пересчёт не найден: " + sessionId));
    }

    /**
     * Расхождение по позиции.
     *
     * @param qtyExpectedAtOpen  учёт на момент открытия сессии
     * @param qtyExpectedAtCount учёт на момент подсчёта — с ним и сравнивают
     * @param delta              что уйдёт корректировкой: минус недостача, плюс излишек
     */
    /** Строка листа обхода. {@code qtyCounted} пусто — до полки не дошли. */
    public record Line(Long partId, String title, Long cellId, String cellCode,
                       BigDecimal qtyExpected, BigDecimal qtyCounted) {
    }

    public record Discrepancy(Long partId,
                              BigDecimal qtyExpectedAtOpen,
                              BigDecimal qtyExpectedAtCount,
                              BigDecimal qtyCounted,
                              BigDecimal delta) {

        public boolean isShortage() {
            return delta.signum() < 0;
        }
    }
}
