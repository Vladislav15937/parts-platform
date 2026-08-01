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
import java.util.List;
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

    private final InventorySessionRepository sessions;
    private final StockMovementRepository movements;
    private final JdbcTemplate jdbc;
    private final StockReservationRepository reservations;

    public InventoryService(InventorySessionRepository sessions,
                            StockMovementRepository movements,
                            JdbcTemplate jdbc,
                            StockReservationRepository reservations) {
        this.sessions = sessions;
        this.movements = movements;
        this.jdbc = jdbc;
        this.reservations = reservations;
    }

    /**
     * Открывает инвентаризацию и снимает учётный остаток склада.
     *
     * <p>В строки попадает всё, что учёт считает лежащим на складе. Позиции
     * с нулевым остатком не попадают: инвентаризация отвечает на вопрос
     * «то ли лежит, что мы думаем», а не «нет ли на складе чего-то, чего
     * мы не знаем» — на последний отвечает найденный излишек, вносимый руками.
     */
    @Transactional
    public InventorySession open(Long warehouseId, Long authorId) {
        List<InventorySession> alreadyOpen = sessions.findByWarehouseIdAndStatus(
                warehouseId, InventorySession.SessionStatus.OPEN);
        if (!alreadyOpen.isEmpty()) {
            throw new IllegalStateException(
                    "На складе %d уже идёт инвентаризация %d: две сессии дадут двойную корректировку"
                            .formatted(warehouseId, alreadyOpen.get(0).getId()));
        }

        InventorySession session = new InventorySession(warehouseId, authorId);
        jdbc.query("""
                SELECT part_id, qty, cell_id
                  FROM part_stock
                 WHERE warehouse_id = ? AND qty > 0
                 ORDER BY part_id""",
                rs -> {
                    session.addLine(rs.getLong("part_id"),
                            rs.getBigDecimal("qty"),
                            rs.getObject("cell_id") == null ? null : rs.getLong("cell_id"));
                },
                warehouseId);

        return detachable(sessions.saveAndFlush(session));
    }

    /**
     * Вносит фактическое количество.
     *
     * <p>Позиция, которой в снимке не было, добавляется строкой с нулевым
     * учётным остатком: это найденный излишек — деталь лежит, а учёт про неё
     * не знает.
     */
    @Transactional
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
    @Transactional
    public InventorySession count(Long sessionId, Long partId, BigDecimal qty,
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
     */
    private Instant countedAt(InventorySession session, Duration countedAgo) {
        Instant now = Instant.now();
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

    /** Открытая инвентаризация склада, если она есть. */
    @Transactional(readOnly = true)
    public Optional<InventorySession> openSessionOf(Long warehouseId) {
        return sessions.findByWarehouseIdAndStatus(
                warehouseId, InventorySession.SessionStatus.OPEN).stream()
                .findFirst()
                .map(this::detachable);
    }

    /**
     * Расхождения: что не сошлось и на сколько.
     *
     * <p>Считается по журналу, поэтому одинаково до и после проведения — можно
     * показать кладовщику, дать пересчитать спорные полки и посмотреть снова.
     */
    @Transactional(readOnly = true)
    public List<Discrepancy> discrepancies(Long sessionId) {
        InventorySession session = require(sessionId);

        return session.countedLines().stream()
                .map(line -> discrepancyOf(session, line))
                .filter(d -> d.delta().signum() != 0)
                .toList();
    }

    /**
     * Проводит инвентаризацию: пишет корректировки на расхождения.
     *
     * <p>Сошедшиеся позиции движений не порождают — движение на ноль БД
     * отвергнет, да и журнал не должен пухнуть от записей «всё в порядке».
     *
     * @return сколько позиций скорректировано
     */
    @Transactional
    public int apply(Long sessionId) {
        InventorySession session = require(sessionId);
        List<Discrepancy> found = session.countedLines().stream()
                .map(line -> discrepancyOf(session, line))
                .filter(d -> d.delta().signum() != 0)
                .toList();

        requireShortagesNotPromised(session, found);

        for (Discrepancy d : found) {
            movements.save(StockMovement.inventoryAdjust(
                    d.partId(), d.delta(), session.getWarehouseId()));
        }
        session.apply(Instant.now());
        sessions.saveAndFlush(session);

        if (!found.isEmpty()) {
            log.info("Инвентаризация {} на складе {}: скорректировано {} позиций",
                    sessionId, session.getWarehouseId(), found.size());
        }
        return found.size();
    }

    /**
     * Недостача по детали, обещанной покупателю, останавливает проведение —
     * целиком, а не частично.
     *
     * <p>Списать такую недостачу нельзя: остаток уйдёт ниже резерва, и это
     * отобьёт триггер склада. Пока проверки не было, проведение падало
     * с «Операция нарушает целостность данных» — кладовщик получал непонятный
     * отказ на всю инвентаризацию и не знал, что делать. Случай не редкий:
     * деталь обещали, а на полке её нет, — и узнать об этом важнее всего
     * именно тогда, потому что покупателю надо звонить.
     *
     * <p><b>Всё или ничего, а не пропуск проблемных позиций.</b> Пропустив
     * их, пришлось бы оставить сессию непроведённой ради повтора — а её
     * корректировки к тому моменту уже записаны, и повтор списал бы всё
     * второй раз. Отказ до первой записи оставляет базу нетронутой, и повтор
     * безопасен.
     *
     * <p>Резерв при этом не снимается сам: обещание отменяет продавец,
     * говоря с покупателем, а не пересчёт за его спиной.
     */
    private void requireShortagesNotPromised(InventorySession session,
                                             List<Discrepancy> found) {
        List<String> blocked = new ArrayList<>();
        for (Discrepancy d : found) {
            if (d.delta().signum() >= 0) {
                continue;
            }
            BigDecimal available = reservations.availableQuantity(
                    d.partId(), session.getWarehouseId());
            if (available.compareTo(d.delta().abs()) < 0) {
                blocked.add(describePromised(d.partId(), available, d.delta().abs()));
            }
        }
        if (!blocked.isEmpty()) {
            throw new IllegalStateException(
                    "Провести пересчёт нельзя: недостача по деталям, обещанным покупателям. "
                            + String.join("; ", blocked)
                            + ". Снимите резерв — отмените или перенесите позицию в сделке — "
                            + "и проведите заново");
        }
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
                deals.isEmpty() ? "" : ", обещана по сделке " + deals.stream()
                        .map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")));
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
                () -> new IllegalArgumentException("Инвентаризация не найдена: " + sessionId));
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
