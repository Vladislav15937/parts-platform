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

    private final InventorySessionRepository sessions;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final StockReservationRepository reservations;
    private final PartChangeLog partChanges;

    public InventoryService(InventorySessionRepository sessions,
                            StockLedger ledger,
                            JdbcTemplate jdbc,
                            StockReservationRepository reservations,
                            PartChangeLog partChanges) {
        this.sessions = sessions;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.reservations = reservations;
        this.partChanges = partChanges;
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
        InventorySession session = require(sessionId);
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
