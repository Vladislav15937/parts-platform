package ru.partsflow.sales;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.inventory.StockReservationRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Предзаказ: деталь по ожидаемой поставке, отложенная под клиента до прихода
 * (задача 0170).
 *
 * <p><b>Складской резерв здесь не тронут ни на букву.</b> Резерв — одна
 * инструкция {@code UPDATE part_stock ... WHERE qty - qty_reserved >= ?}
 * ({@link StockReservationRepository}), и предзаказ по ней не проходит по
 * построению: у ожидаемой позиции нет строки раскладки. Поэтому предзаказ —
 * отдельное состояние позиции сделки ({@link DealItemStatus#PREORDER}), а не
 * «резерв с нулевым остатком», и считается он отдельно: сколько можно отложить
 * — от количества в карточке ожидаемой позиции, а не от склада. Настоящий
 * резерв появляется ровно один раз — когда приёмка кладёт деталь на склад
 * ({@link #convertOnArrival}), и ставит его тот же {@code reserve}, что и
 * любую продажу.
 *
 * <p><b>Две точки сериализации.</b> Создание предзаказа и приход поставки
 * берут одну и ту же строку {@code part} под блокировку ({@code FOR UPDATE}):
 * без неё приёмка, читающая список предзаказов, и продавец, дописывающий ещё
 * один, расходились бы молча. Остаток склада по-прежнему защищает условие
 * в {@code WHERE}, а не эта блокировка.
 */
@Service
public class PreorderService {

    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("d MMMM", Locale.of("ru"));

    private final JdbcTemplate jdbc;
    private final DealRepository deals;
    private final DocumentEventRepository events;
    private final StockReservationRepository reservations;

    public PreorderService(JdbcTemplate jdbc, DealRepository deals,
                           DocumentEventRepository events,
                           StockReservationRepository reservations) {
        this.jdbc = jdbc;
        this.deals = deals;
        this.events = events;
        this.reservations = reservations;
    }

    /**
     * Какие из названных позиций сейчас ожидаются: заведены как ожидаемые,
     * ничего не принято, поставка ещё не приехала.
     *
     * <p>Условие то же, по которому прайс площадки называет товар «в пути»
     * ({@code DromPriceGenerator}): позиция без остатка, привязанная к
     * поставке в состоянии {@code EXPECTED} или {@code IN_TRANSIT}. Отличие
     * одно — признак {@code expected_origin}: черновик, который кто-то завёл
     * карточкой и бросил, ожидаемым не становится.
     */
    @Transactional(readOnly = true)
    public Set<Long> expectedAmong(Collection<Long> partIds) {
        List<Long> ids = partIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.queryForList("""
                SELECT p.id FROM part p
                  JOIN supply s ON s.id = p.supply_id
                 WHERE p.id = ANY (?)
                   AND p.expected_origin AND p.status = 'DRAFT'
                   AND s.status IN ('EXPECTED', 'IN_TRANSIT')""",
                Long.class, (Object) ids.toArray(Long[]::new)));
    }

    /**
     * Откладывает количество из ожидаемой поставки под клиента — только
     * проверка и блокировка, строку сделки создаёт вызывающий.
     *
     * <p>Строка {@code part} берётся под блокировку, и сумма уже отложенного
     * читается <b>после</b> неё: два продавца на одну деталь выстраиваются в
     * очередь, и второй видит предзаказ первого. Без блокировки оба прочли бы
     * «свободно 3» и отложили бы по три — две сделки на ту же деталь, ровно
     * то, от чего склад защищён условием в {@code WHERE}.
     *
     * @param alreadyInRequest сколько этой же детали уже отложено ранее в
     *                         этом же запросе: те строки ещё не записаны
     * @return ожидаемая дата поставки (может быть пустой), либо {@code null},
     *         если позиция не ожидается — тогда это обычная продажа
     * @throws IllegalStateException второму покупателю на ту же деталь
     */
    @Transactional
    public Claim claim(Long partId, BigDecimal quantity, BigDecimal alreadyInRequest) {
        List<Claim> found = jdbc.query("""
                SELECT p.title, p.quantity, s.number, s.kind, s.expected_on
                  FROM part p
                  JOIN supply s ON s.id = p.supply_id
                 WHERE p.id = ?
                   AND p.expected_origin AND p.status = 'DRAFT'
                   AND s.status IN ('EXPECTED', 'IN_TRANSIT')
                   FOR UPDATE OF p""",
                (rs, i) -> new Claim(rs.getString("title"), rs.getBigDecimal("quantity"),
                        rs.getDate("expected_on") == null
                                ? null : rs.getDate("expected_on").toLocalDate()),
                partId);
        if (found.isEmpty()) {
            return null;
        }
        Claim part = found.get(0);

        BigDecimal taken = jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0) FROM deal_item
                 WHERE part_id = ? AND status = 'PREORDER'""",
                BigDecimal.class, partId);
        BigDecimal free = part.promised()
                .subtract(taken == null ? BigDecimal.ZERO : taken)
                .subtract(alreadyInRequest == null ? BigDecimal.ZERO : alreadyInRequest);
        if (quantity.compareTo(free) > 0) {
            // Причина другая, чем у склада: деталь не занята и не продана — её
            // нет ни у кого, она в пути, и обещано под покупателей уже
            // столько, сколько в поставке. Слова разные нарочно: продавец
            // отвечает покупателю по-разному.
            throw new IllegalStateException(
                    "Ожидаемую деталь «%s» уже отложили под других покупателей: в поставке %s, "
                            .formatted(part.title(), plain(part.promised()))
                            + "отложено %s, свободно %s, а нужно %s"
                            .formatted(plain(taken), plain(free.max(BigDecimal.ZERO)),
                                    plain(quantity)));
        }
        return part;
    }

    // ---------- пришедшее, но обещанное ----------

    /**
     * Сколько пришедшей детали ещё обещано предзаказам и не может уйти в
     * обычную продажу.
     *
     * <p><b>Частичный приход.</b> Предзаказов на три штуки, приехала одна:
     * {@link #convertOnArrival} её отдаёт первому в очереди, если она у него
     * целиком, а если нет — остаток лежит на складе свободным, и обычный
     * продавец отложил бы его первым же нажатием, мимо очереди. Обещанное
     * предзаказом должно получить деталь раньше, чем она достанется тому, кто
     * просто пришёл. Складской резерв при этом не тронут: гейт стоит в
     * продаже, а не в {@code reserve}.
     *
     * <p>Строка {@code part} берётся под блокировку, когда предзаказы есть, и
     * только тогда: это та же точка сериализации, что у прихода и у нового
     * предзаказа, — приход, превращающий предзаказ в резерв, и продажа, считающая
     * «сколько свободно за вычетом обещанного», не должны видеть друг друга
     * наполовину. У детали без предзаказов блокировки нет, и обычная продажа
     * не платит за них ничем.
     *
     * @return {@code null}, если ничего не обещано
     */
    @Transactional
    public Hold holdOf(Long partId) {
        BigDecimal pending = pendingOf(partId);
        if (pending.signum() == 0) {
            return null;
        }
        jdbc.queryForList("SELECT id FROM part WHERE id = ? FOR UPDATE", Long.class, partId);
        pending = pendingOf(partId);
        if (pending.signum() == 0) {
            return null;
        }
        BigDecimal free = jdbc.queryForObject("""
                SELECT COALESCE(sum(qty - qty_reserved), 0) FROM part_stock
                 WHERE part_id = ?""", BigDecimal.class, partId);
        String title = jdbc.queryForObject("SELECT title FROM part WHERE id = ?",
                String.class, partId);
        return new Hold(title, pending, free == null ? BigDecimal.ZERO : free);
    }

    private BigDecimal pendingOf(Long partId) {
        BigDecimal pending = jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0) FROM deal_item
                 WHERE part_id = ? AND status = 'PREORDER'""", BigDecimal.class, partId);
        return pending == null ? BigDecimal.ZERO : pending;
    }

    /**
     * Обещанное предзаказам по пришедшей детали.
     *
     * @param pending сколько отложено предзаказами и ещё не получило деталь
     * @param free    свободно на складах (без вычета предзаказов)
     */
    public record Hold(String title, BigDecimal pending, BigDecimal free) {

        /** Сколько можно продать «просто так»: свободное за вычетом обещанного. */
        public BigDecimal forOrdinarySale() {
            return free.subtract(pending).max(BigDecimal.ZERO);
        }

        /** Слова отказа продавцу: что пришло, что обещано, сколько осталось. */
        public String refusal(BigDecimal wanted) {
            return ("Пришла только часть поставки: «%s» — свободно %s, из них отложено под "
                    + "предзаказы клиентов %s, для обычной продажи %s, а нужно %s. "
                    + "Обещанное предзаказам отдаётся первым")
                    .formatted(title, plain(free), plain(pending),
                            plain(forOrdinarySale()), plain(wanted));
        }
    }

    /** @param expectedOn ожидаемая дата поставки; пусто — её не называли */
    public record Claim(String title, BigDecimal promised, LocalDate expectedOn) {
    }

    // ---------- приход поставки ----------

    /**
     * Проверка прихода — <b>до</b> записи движения и завершения документа.
     *
     * <p>Приёмщик видит отказ в ту же минуту, у стеллажа, а не продавец
     * задним числом и не сверка расхождений следующей ночью. Считает так, как
     * считал бы резерв после прихода: свободное по складам плюс то, что
     * приходом ляжет на {@code receiptWarehouseId}.
     *
     * @throws IllegalStateException обещанное не лежит целиком ни на одном
     *         складе, хотя в сумме его хватает
     */
    @Transactional
    public void checkArrival(Long partId, Long receiptWarehouseId, BigDecimal incoming) {
        allocate(partId, receiptWarehouseId, incoming);
    }

    /**
     * Превращает предзаказы принятой позиции в обычный резерв.
     *
     * <p>Зовётся <b>после</b> проведения документа прихода, в той же
     * транзакции: к этому моменту деталь лежит на складе, и {@code reserve}
     * ставит настоящий резерв тем же атомарным {@code UPDATE ... WHERE}, что
     * и любая продажа. Сделка остаётся той же — номер не меняется, покупатель
     * не теряет обещание; позиция из {@code PREORDER} становится
     * {@code RESERVED}, поэтому сумма {@code qty_reserved} по складу равна
     * сумме предзаказов, а не вдвое (удвоения не бывает: до этого момента
     * склад не отложил под предзаказ ничего).
     *
     * <p>Склад позиции становится <b>фактическим</b> — тем, где деталь легла.
     * Если он не тот, что был обещан при оформлении, подмена не молчит: в
     * историю документа пишется строка с обоими складами. Приход на обещанный
     * склад историю не засоряет — менять было нечего.
     */
    @Transactional
    public void convertOnArrival(Long partId, Long authorId) {
        for (Assignment a : allocate(partId, null, BigDecimal.ZERO)) {
            reservations.reserve(partId, a.warehouseId(), a.item().getQuantity());
            Long promised = a.item().getWarehouseId();
            a.item().confirmPreorder(a.warehouseId());
            if (!a.warehouseId().equals(promised)) {
                events.save(DocumentEvent.forDeal(a.deal().getId(), "PREORDER_WAREHOUSE_CHANGED",
                        "Деталь по предзаказу пришла на склад «%s», а при оформлении был обещан «%s»"
                                .formatted(warehouseName(a.warehouseId()), warehouseName(promised)),
                        authorId));
            }
            // Клиенту названа прежняя дата, но деталь пришла — пометка о сдвиге
            // свою задачу выполнила.
            a.deal().clearShift();
        }
        deals.flush();
    }

    private record Assignment(Deal deal, DealItem item, Long warehouseId) {
    }

    /**
     * Раздаёт предзаказы по складам: каждый — туда, где обещанное количество
     * лежит <b>целиком</b>.
     *
     * <p>Выдают с одного склада. Резерв, размазанный по двум, означает, что
     * продавец обещал то, чего не выдаст одним действием, — а узнает он об
     * этом в минуту выдачи, при покупателе. Поэтому: обещанный склад, если на
     * нём хватает; иначе тот, где свободного больше всех (при равенстве — с
     * меньшим номером, чтобы выбор не зависел от плана запроса); иначе — если
     * в сумме хватает, а целиком нигде нет, <b>отказ словами</b>. Если не
     * хватает и в сумме — поставка пришла не вся, предзаказ остаётся ждать.
     *
     * <p>Порядок — по сделке и позиции: кто отложил раньше, тот первым
     * получает деталь.
     */
    private List<Assignment> allocate(Long partId, Long receiptWarehouseId, BigDecimal incoming) {
        // Раскладку читает JdbcTemplate, который сессию не сбрасывает: без
        // flush неотправленное движение прихода осталось бы невидимым.
        deals.flush();
        Map<Long, BigDecimal> free = new LinkedHashMap<>();
        jdbc.query("""
                SELECT warehouse_id, qty - qty_reserved AS free FROM part_stock
                 WHERE part_id = ? ORDER BY warehouse_id""",
                rs -> {
                    free.put(rs.getLong("warehouse_id"), rs.getBigDecimal("free"));
                }, partId);
        if (receiptWarehouseId != null && incoming != null && incoming.signum() > 0) {
            free.merge(receiptWarehouseId, incoming, BigDecimal::add);
        }

        List<Assignment> result = new ArrayList<>();
        for (Deal deal : deals.findWithPreorderOf(partId)) {
            for (DealItem item : deal.getItems()) {
                if (item.getStatus() != DealItemStatus.PREORDER
                        || !item.getPartId().equals(partId)) {
                    continue;
                }
                BigDecimal need = item.getQuantity();
                Long chosen = pick(free, item.getWarehouseId(), need);
                if (chosen == null) {
                    BigDecimal total = free.values().stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (total.compareTo(need) >= 0) {
                        throw splitRefusal(partId, deal, need, free);
                    }
                    // Поставка пришла не вся: этот предзаказ ждёт остальное.
                    continue;
                }
                free.merge(chosen, need.negate(), BigDecimal::add);
                result.add(new Assignment(deal, item, chosen));
            }
        }
        return result;
    }

    private static Long pick(Map<Long, BigDecimal> free, Long promised, BigDecimal need) {
        if (promised != null && free.getOrDefault(promised, BigDecimal.ZERO).compareTo(need) >= 0) {
            return promised;
        }
        Long best = null;
        for (Map.Entry<Long, BigDecimal> e : free.entrySet()) {
            if (e.getValue().compareTo(need) >= 0
                    && (best == null || e.getValue().compareTo(free.get(best)) > 0)) {
                best = e.getKey();
            }
        }
        return best;
    }

    private IllegalStateException splitRefusal(Long partId, Deal deal, BigDecimal need,
                                               Map<Long, BigDecimal> free) {
        String where = free.entrySet().stream()
                .filter(e -> e.getValue().signum() > 0)
                .map(e -> "«%s» — %s".formatted(warehouseName(e.getKey()), plain(e.getValue())))
                .collect(Collectors.joining(", "));
        String title = jdbc.queryForObject("SELECT title FROM part WHERE id = ?",
                String.class, partId);
        // Приёмка не записана: ни движения, ни документа. Текст говорит, что
        // случилось и что делать, — иначе приёмщик увидит красное, заведёт
        // деталь второй раз руками и получит ровно то удвоение, ради
        // устранения которого вся приёмка и держится на ключе клиента.
        return new IllegalStateException(
                "Приход не записан: «%s» отложена под клиента по сделке №%s в количестве %s, "
                        .formatted(title, deal.getNumber(), plain(need))
                        + "а после этого прихода она лежит по складам частями — %s. "
                                .formatted(where)
                        + "Выдают с одного склада, поэтому обещанное должно лежать на одном: "
                        + "примите приход на тот склад, где уже лежит основная часть, "
                        + "или сначала перевезите остаток");
    }

    // ---------- сдвиг ожидаемой даты ----------

    /**
     * Ожидаемая дата поставки сдвинулась: срок резерва предзаказов едет за
     * ней, а продавцу это видно.
     *
     * <p>Срок пересчитывается сам — на то же число суток, на которое сдвинулась
     * дата (в обе стороны). Клиенту при этом названа прежняя дата, и узнать о
     * сдвиге продавец должен <b>на экране</b>, а не из ленты истории: в
     * историю заходят, когда уже что-то случилось, и если сдвиг виден только
     * там, звонка клиенту не будет вовсе. Поэтому на сделке остаётся пометка
     * (см. {@link Deal#markShifted}), а в истории — строка с обеими датами и
     * новым сроком, как у продления и смены клиента.
     *
     * <p>Если прежней даты не было, сдвигать нечего: срок назвал человек, и
     * относительно чего он называл — неизвестно. В историю пишется только
     * назначение даты.
     */
    @Transactional
    public void shiftExpected(Long supplyId, LocalDate was, LocalDate now, Long authorId) {
        List<Long> dealIds = jdbc.queryForList("""
                SELECT DISTINCT i.deal_id FROM deal_item i
                  JOIN part p ON p.id = i.part_id
                 WHERE i.status = 'PREORDER' AND p.supply_id = ?""",
                Long.class, supplyId);
        for (Deal deal : deals.findAllById(dealIds)) {
            if (deal.getStatus() != DealStatus.RESERVED) {
                continue;
            }
            if (was == null) {
                events.save(DocumentEvent.forDeal(deal.getId(), "EXPECTED_DATE_SET",
                        "Назначена ожидаемая дата поставки: " + DAY.format(now)
                                + ". Срок резерва не менялся",
                        authorId));
                continue;
            }
            long days = ChronoUnit.DAYS.between(was, now);
            Instant moved = deal.getReservedUntil() == null
                    ? null : deal.getReservedUntil().plus(days, ChronoUnit.DAYS);
            if (moved != null) {
                deal.moveReservationTo(moved);
            }
            deal.markShifted(was, now);
            events.save(DocumentEvent.forDeal(deal.getId(), "EXPECTED_DATE_SHIFTED",
                    "Ожидаемая дата поставки сдвинулась: было " + DAY.format(was)
                            + ", стало " + DAY.format(now)
                            + (moved == null ? "" : ". Срок резерва перенесён до "
                                    + DAY.format(moved.atZone(java.time.ZoneOffset.UTC)
                                            .toLocalDate())),
                    authorId));
        }
        deals.flush();
    }

    private String warehouseName(Long warehouseId) {
        if (warehouseId == null) {
            return "неизвестный склад";
        }
        List<String> found = jdbc.queryForList(
                "SELECT name FROM warehouse WHERE id = ?", String.class, warehouseId);
        // Без номера строки в базе: удалённый склад называется словом, а не
        // числом, которого человек никогда не видел.
        return found.isEmpty() ? "неизвестный склад" : found.get(0);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
