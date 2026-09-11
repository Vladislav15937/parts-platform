package ru.partsflow.reports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.sales.DealStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Отчёты владельца.
 *
 * <p><b>Отдельный модуль, а не часть продаж или приёмки.</b> Оба отчёта
 * пересекают границы: зарплата менеджера считается по сделкам и сотрудникам,
 * окупаемость донора — по машине, её расходам, складу и продажам. Разложить их
 * по модулям-владельцам данных значит разрезать один экран на два контроллера
 * и заставить каждый лезть в чужие таблицы.
 *
 * <p><b>Считает база, а не приложение.</b> Обе выборки — представления
 * (`v_manager_sales`, `v_donor_profitability`), и это не оптимизация:
 * в них записаны правила, которые нельзя разойтись с расчётом зарплаты.
 * Главное — выручка считается по статусу позиции, а не только документа:
 * при частичном возврате сделка остаётся выданной, и без этого условия
 * менеджеру капала бы премия за товар, привезённый обратно.
 *
 * <p>Читается через {@code JdbcTemplate} внутри транзакции: {@code search_path}
 * выставляет провайдер соединений Hibernate, и вне транзакции запрос ушёл бы
 * в {@code public}, то есть в пустоту.
 */
@Service
public class ReportService {

    private final JdbcTemplate jdbc;
    /**
     * Нужен ровно затем, чтобы узнать номер контрагента розничной продажи:
     * опознаётся он по имени, и второе место, где это имя написано, разошлось
     * бы с первым молча — как уже расходились белые списки колонок и копии
     * словаря состояний.
     */
    private final ru.partsflow.sales.CustomerService customers;

    public ReportService(JdbcTemplate jdbc, ru.partsflow.sales.CustomerService customers) {
        this.jdbc = jdbc;
        this.customers = customers;
    }

    /**
     * Продажи по менеджерам за месяц — основа для расчёта зарплат.
     *
     * <p>Месяцем, а не «за всё время»: премию считают за период, и суммарная
     * цифра с начала работы для этого бесполезна.
     */
    @Transactional(readOnly = true)
    public List<ManagerRow> managerSales(YearMonth month) {
        LocalDate first = month.atDay(1);
        return jdbc.query("""
                SELECT manager_id, display_name, deals_count, revenue, margin,
                       items_without_cost
                  FROM v_manager_sales
                 WHERE period = ?::date
                 ORDER BY revenue DESC NULLS LAST, display_name""",
                (rs, i) -> new ManagerRow(
                        rs.getObject("manager_id", Long.class),
                        rs.getString("display_name"),
                        rs.getInt("deals_count"),
                        rs.getBigDecimal("revenue"),
                        rs.getBigDecimal("margin"),
                        rs.getInt("items_without_cost")),
                first);
    }

    /**
     * Продажи по каналам за месяц: откуда пришли деньги.
     *
     * <p>Отвечает на вопрос владельца «стоит ли платить за размещение»:
     * без разреза по каналам счёт от площадки не с чем сравнить.
     *
     * <p><b>Сделки без источника не выбрасываются, а идут строкой с пустым
     * каналом.</b> Невидимая часть выручки делает отчёт бесполезным: по нему
     * нельзя понять, Дром не приносит денег или продавцы не отмечают источник.
     * Пока эта строка большая, остальным цифрам верить нельзя, и владелец
     * должен это видеть.
     */
    @Transactional(readOnly = true)
    public List<SourceRow> salesBySource(YearMonth month) {
        return jdbc.query("""
                SELECT deal_source_id, source_name, deals_count, revenue, margin,
                       items_without_cost
                  FROM v_sales_by_source
                 WHERE period = ?::date
                 ORDER BY revenue DESC NULLS LAST, source_name NULLS LAST""",
                (rs, i) -> new SourceRow(
                        rs.getObject("deal_source_id", Long.class),
                        rs.getString("source_name"),
                        rs.getInt("deals_count"),
                        rs.getBigDecimal("revenue"),
                        rs.getBigDecimal("margin"),
                        rs.getInt("items_without_cost")),
                month.atDay(1));
    }

    /**
     * @param sourceName пусто — источник у сделки не указан. Не «прочее»:
     *                   это не канал, а незаполненное поле, и лечится оно
     *                   не переименованием, а привычкой продавца
     */
    public record SourceRow(Long sourceId, String sourceName, int dealsCount,
                            BigDecimal revenue, BigDecimal margin, int itemsWithoutCost) {
    }

    /**
     * Деньги по источникам платежа за месяц: сколько прошло наличными,
     * сколько картой, сколько осталось в долг.
     *
     * <p><b>Группируется по самому источнику, а не по его типу.</b> У владельца
     * бывает две карты разных банков, и «Карта Сбер» против «Карта Т-Банк» —
     * разные строки, хотя тип у обеих один: по типу он не сверяет выписку.
     *
     * <p><b>Архивный источник из отчёта не исчезает.</b> Отбор идёт по платежам,
     * а не по справочнику: платежи по снятому с работы способу были, и период
     * бывает прошлым. Отфильтруй мы `is_archived`, месяц молча недосчитался бы
     * их суммы — то есть отчёт перестал бы сходиться ровно тогда, когда
     * владелец наводит порядок в справочнике.
     *
     * <p><b>Платёж без источника — своя строка, а не выброшенный.</b> До задачи
     * 0024 источник не писали вовсе, и таких платежей у переехавшего клиента
     * целая история. Выкинь их отбором — сумма по строкам перестанет сходиться
     * с итогом за тот же период, и владелец получит два разных ответа
     * на один вопрос. Поэтому связь со справочником — `LEFT JOIN`.
     *
     * <p>Приход и расход разными числами: сумма у платежа всегда положительная,
     * знак несёт {@code direction}. Сложенные вместе, возврат из кассы и приём
     * денег дали бы «прошло наличными» больше, чем было на самом деле.
     *
     * <p>Старая колонка {@code payment_type} (`CASH`/`CARD`/…) здесь намеренно
     * не используется: она перечисляла способы до появления справочника
     * и двух карт разных банков не различает вовсе.
     */
    @Transactional(readOnly = true)
    public List<PaymentSourceRow> paymentsBySource(YearMonth month) {
        return jdbc.query("""
                SELECT p.payment_source_id                                             AS source_id,
                       ps.name                                                         AS source_name,
                       ps.source_type                                                  AS source_type,
                       COALESCE(ps.is_archived, false)                                 AS archived,
                       count(*)                                                        AS payments,
                       COALESCE(sum(p.amount) FILTER (WHERE p.direction = 'IN'), 0)    AS incoming,
                       COALESCE(sum(p.amount) FILTER (WHERE p.direction = 'OUT'), 0)   AS outgoing
                  FROM payment p
                  LEFT JOIN payment_source ps ON ps.id = p.payment_source_id
                 WHERE p.paid_at >= ?::date
                   AND p.paid_at < ?::date
                 GROUP BY p.payment_source_id, ps.name, ps.source_type, ps.is_archived
                 ORDER BY incoming DESC, outgoing DESC, source_name NULLS LAST""",
                (rs, i) -> new PaymentSourceRow(
                        rs.getObject("source_id", Long.class),
                        rs.getString("source_name"),
                        rs.getString("source_type"),
                        rs.getBoolean("archived"),
                        rs.getInt("payments"),
                        rs.getBigDecimal("incoming"),
                        rs.getBigDecimal("outgoing")),
                month.atDay(1), month.plusMonths(1).atDay(1));
    }

    /**
     * Итог по деньгам за тот же месяц — отдельным запросом, а не суммой строк.
     *
     * <p>В этом весь смысл проверки: сложенные на экране строки сойдутся сами
     * с собой при любом отборе, а разъехавшуюся выборку показывает только число,
     * посчитанное независимо — по всем платежам периода, без группировки
     * и без справочника.
     */
    @Transactional(readOnly = true)
    public PaymentTotals paymentTotals(YearMonth month) {
        return jdbc.queryForObject("""
                SELECT count(*)                                                      AS payments,
                       COALESCE(sum(amount) FILTER (WHERE direction = 'IN'), 0)      AS incoming,
                       COALESCE(sum(amount) FILTER (WHERE direction = 'OUT'), 0)     AS outgoing
                  FROM payment
                 WHERE paid_at >= ?::date
                   AND paid_at < ?::date""",
                (rs, i) -> new PaymentTotals(
                        rs.getInt("payments"),
                        rs.getBigDecimal("incoming"),
                        rs.getBigDecimal("outgoing")),
                month.atDay(1), month.plusMonths(1).atDay(1));
    }

    /**
     * @param sourceName пусто — способ у платежа не записан. Отдельная строка,
     *                   а не «прочее»: это незаполненное поле, и пока таких
     *                   платежей много, остальным строкам верить нельзя
     * @param archived   источник снят с работы. Из отчёта он не уходит:
     *                   платежи по нему были, и период бывает прошлым
     * @param total      приход минус расход — то, что этим способом реально
     *                   осталось у владельца за месяц
     */
    public record PaymentSourceRow(Long sourceId, String sourceName, String sourceType,
                                   boolean archived, int payments,
                                   BigDecimal incoming, BigDecimal outgoing, BigDecimal total) {

        public PaymentSourceRow(Long sourceId, String sourceName, String sourceType,
                                boolean archived, int payments,
                                BigDecimal incoming, BigDecimal outgoing) {
            this(sourceId, sourceName, sourceType, archived, payments,
                    incoming, outgoing, incoming.subtract(outgoing));
        }
    }

    public record PaymentTotals(int payments, BigDecimal incoming, BigDecimal outgoing,
                                BigDecimal total) {

        public PaymentTotals(int payments, BigDecimal incoming, BigDecimal outgoing) {
            this(payments, incoming, outgoing, incoming.subtract(outgoing));
        }
    }

    /**
     * Окупаемость доноров: сколько вложено, сколько выручено, сколько ещё лежит.
     *
     * <p>Убыточные сверху — это то, на что владелец смотрит. Но «убыток»
     * у только что купленной машины ничего не значит: с неё ещё ничего
     * не сняли. Поэтому строка несёт и остаток на складе, и число проданных
     * позиций из общего — по ним видно, машина плохая или свежая.
     */
    @Transactional(readOnly = true)
    public List<DonorRow> donorProfitability(int limit) {
        return jdbc.query("""
                SELECT donor_id, public_code, legacy_code, note, vin, year,
                       total_cost, revenue, profit,
                       parts_total, parts_sold, stock_value
                  FROM v_donor_profitability
                 ORDER BY profit, donor_id
                 LIMIT ?""",
                (rs, i) -> new DonorRow(
                        rs.getLong("donor_id"),
                        rs.getString("public_code"),
                        rs.getString("legacy_code"),
                        rs.getString("note"),
                        rs.getString("vin"),
                        rs.getObject("year", Integer.class),
                        rs.getBigDecimal("total_cost"),
                        rs.getBigDecimal("revenue"),
                        rs.getBigDecimal("profit"),
                        rs.getInt("parts_total"),
                        rs.getInt("parts_sold"),
                        rs.getBigDecimal("stock_value")),
                limit);
    }

    /**
     * Расчёты с клиентами: у кого наши деньги и кто должен нам.
     *
     * <p>Владелец не видел своих обязательств перед клиентами ни одним
     * числом: авансы на лицевых счетах есть, а сколько их всего — узнать
     * было негде.
     *
     * <p>Долг — по выданным сделкам: пока товар не отдан, это не долг,
     * а обещание, и требовать по нему нечего.
     */
    @Transactional(readOnly = true)
    public List<SettlementRow> customerSettlements(int limit) {
        // Контрагент розничной продажи помечается, а не выбрасывается
        // из отчёта. За его строкой стоят сотни продаж людям с улицы, и
        // «постоянный покупатель с сотней сделок» она не означает — но долг
        // в ней настоящий: товар отдали и денег не взяли. Убрав строку,
        // владелец увидел бы отчёт, который выглядит полным и не сходится
        // с кассой ровно на неё, — то же правило, по которому сделки без
        // источника идут строкой, а не пропадают из отчёта по каналам.
        Long retailId = customers.retailCustomerId();
        return jdbc.query("""
                SELECT customer_id, customer_name, phone,
                       account_balance, debt, unpaid_deals
                  FROM v_customer_settlement
                 ORDER BY debt DESC, account_balance DESC, customer_id
                 LIMIT ?""",
                (rs, i) -> new SettlementRow(
                        rs.getLong("customer_id"),
                        rs.getString("customer_name"),
                        rs.getString("phone"),
                        rs.getBigDecimal("account_balance"),
                        rs.getBigDecimal("debt"),
                        rs.getInt("unpaid_deals"),
                        retailId != null && retailId == rs.getLong("customer_id")),
                limit);
    }

    /**
     * Итог по расчётам и сверка.
     *
     * <p>Расхождения идут вместе с итогом намеренно: число обязательств,
     * рядом с которым не сказано, сходится ли оно, — это спокойствие
     * без основания. У склада такая сверка есть с самого начала, у денег
     * не было ни одной.
     */
    @Transactional(readOnly = true)
    public SettlementTotals settlementTotals() {
        SettlementTotals totals = jdbc.queryForObject("""
                SELECT COALESCE(sum(account_balance) FILTER (WHERE account_balance > 0), 0) AS advances,
                       COALESCE(sum(debt), 0)                                               AS debts,
                       count(*) FILTER (WHERE account_balance > 0)                          AS with_advance,
                       count(*) FILTER (WHERE debt > 0)                                     AS with_debt,
                       count(*)                                                             AS customers
                  FROM v_customer_settlement""",
                (rs, i) -> new SettlementTotals(
                        rs.getBigDecimal("advances"),
                        rs.getBigDecimal("debts"),
                        rs.getInt("with_advance"),
                        rs.getInt("with_debt"),
                        rs.getInt("customers"),
                        List.of()));

        return new SettlementTotals(
                totals.advances(), totals.debts(), totals.withAdvance(), totals.withDebt(),
                totals.customers(), discrepancies());
    }

    /**
     * Сводка: что лежит на складе и что висит в незакрытых сделках.
     *
     * <p>Первый вопрос владельца разборки — «сколько у меня сейчас на складе
     * в деньгах», и до этого на него не отвечал никто: все отчёты про прошлое.
     *
     * <p><b>Считается {@code qty}, а не свободный остаток.</b> Вопрос здесь —
     * сколько лежит на полке, а не сколько можно продать: отложенная под
     * клиента деталь никуда со склада не делась, и вычесть её значило бы
     * показать владельцу недостачу ровно на объём отложенного. Разница видна
     * только тому, кто знает, что резерв вообще есть, — поэтому она и стоит
     * отдельной проверкой.
     *
     * <p>Запчасти и колёса — разными строками: колёса продаются сезоном,
     * и владелец смотрит на них отдельно. Обе строки собираются всегда,
     * даже когда база не вернула по ним ничего: у свежего арендатора ответ
     * должен быть нулём, а не отсутствующим полем.
     *
     * <p>Цена берётся розничная и незаполненная считается нулём. Деталь без
     * цены при этом остаётся в количестве: она лежит на полке независимо
     * от того, оценили её или нет.
     */
    @Transactional(readOnly = true)
    public Summary summary() {
        Map<String, StockLine> byLine = jdbc.query("""
                SELECT p.product_line                                          AS product_line,
                       COALESCE(sum(s.qty), 0)                                 AS qty,
                       -- До копейки: остаток numeric(12,3) на цену numeric(14,2)
                       -- даёт пять знаков после запятой, и наружу уходило бы
                       -- «2000.00000» — деньги такими не бывают.
                       round(COALESCE(sum(s.qty * COALESCE(p.price, 0)), 0), 2) AS amount
                  FROM part_stock s
                  JOIN part p ON p.id = s.part_id
                 GROUP BY p.product_line""",
                (rs, i) -> Map.entry(rs.getString("product_line"),
                        new StockLine(rs.getBigDecimal("qty"), rs.getBigDecimal("amount"))))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return new Summary(
                byLine.getOrDefault("PART", StockLine.EMPTY),
                byLine.getOrDefault("WHEEL", StockLine.EMPTY),
                openDeals());
    }

    /**
     * Сделки в работе: сколько их, на сколько и сколько по ним уже внесено.
     *
     * <p>Состояния берутся у самой сделки ({@link DealStatus#holdsStock()}),
     * а не переписываются списком: выданная сделка — уже не работа, а её
     * выпадение отсюда обязано случиться ровно тогда же, когда товар уходит
     * со склада. Свой список разошёлся бы с продажами на первом новом
     * состоянии, и разошёлся бы молча.
     *
     * <p>Внесённое читается из {@code paid_amount}, а не складывается
     * по платежам: зачёт с лицевого счёта платежа не создаёт — деньги
     * получены раньше, — и сумма по журналу платежей занизила бы предоплаты
     * ровно на зачтённые авансы.
     */
    private OpenDeals openDeals() {
        List<String> open = Arrays.stream(DealStatus.values())
                .filter(DealStatus::holdsStock)
                .map(Enum::name)
                .toList();
        String places = String.join(", ", Collections.nCopies(open.size(), "?"));

        return jdbc.queryForObject("""
                SELECT count(*)                       AS deals,
                       COALESCE(sum(total_amount), 0) AS amount,
                       COALESCE(sum(paid_amount), 0)  AS prepaid
                  FROM deal
                 WHERE status IN (%s)""".formatted(places),
                (rs, i) -> new OpenDeals(
                        rs.getInt("deals"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("prepaid")),
                open.toArray());
    }

    /**
     * @param qty    сколько штук физически лежит на всех складах вместе
     * @param amount во сколько это оценено по розничной цене
     */
    public record StockLine(BigDecimal qty, BigDecimal amount) {

        /** Вида товара на складе нет вовсе — это ноль, а не отсутствие ответа. */
        static final StockLine EMPTY = new StockLine(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * @param prepaid сколько по этим сделкам уже внесено. Отдельно от суммы:
     *                владелец смотрит на разницу — это то, что ему ещё должны
     */
    public record OpenDeals(int count, BigDecimal amount, BigDecimal prepaid) {
    }

    public record Summary(StockLine parts, StockLine wheels, OpenDeals deals) {
    }

    /** Нарушенные инварианты расчётов. Пусто — деньги сходятся. */
    @Transactional(readOnly = true)
    public List<Discrepancy> discrepancies() {
        return jdbc.query("""
                SELECT customer_id, entry_id, deal_id, problem, amount
                  FROM v_account_discrepancy
                 ORDER BY customer_id, problem""",
                (rs, i) -> new Discrepancy(
                        rs.getObject("customer_id", Long.class),
                        rs.getObject("entry_id", Long.class),
                        rs.getObject("deal_id", Long.class),
                        rs.getString("problem"),
                        rs.getBigDecimal("amount")));
    }

    /**
     * @param retail это контрагент розничной продажи, а не постоянный
     *               покупатель: за строкой стоят все продажи людям с улицы,
     *               и складываются в ней долги разных людей. Помечается,
     *               а не выбрасывается, — деньги в ней настоящие
     */
    public record SettlementRow(Long customerId, String customerName, String phone,
                                BigDecimal accountBalance, BigDecimal debt, int unpaidDeals,
                                boolean retail) {
    }

    /**
     * @param customers сколько клиентов в расчётах всего. Список обрезан
     *                  пределом, и без общего числа экран не может сказать,
     *                  что показывает не всех, — а владелец, не найдя клиента
     *                  в списке, решит, что за ним ничего не числится
     * @param problems  нарушенные инварианты. Непусто — деньги не сходятся
     */
    public record SettlementTotals(BigDecimal advances, BigDecimal debts,
                                   int withAdvance, int withDebt, int customers,
                                   List<Discrepancy> problems) {
    }

    public record Discrepancy(Long customerId, Long entryId, Long dealId,
                              String problem, BigDecimal amount) {
    }

    /**
     * Итог по всем донорам.
     *
     * <p>Отдельным запросом по всей выборке, а не суммой показанных строк:
     * список ограничен, и сложенные в интерфейсе полсотни строк дали бы число,
     * которое выглядит как ответ на вопрос «окупаются ли машины вообще»,
     * не будучи им.
     */
    @Transactional(readOnly = true)
    public DonorTotals donorTotals() {
        return jdbc.queryForObject("""
                SELECT count(*)                        AS donors,
                       COALESCE(sum(total_cost), 0)    AS total_cost,
                       COALESCE(sum(revenue), 0)       AS revenue,
                       COALESCE(sum(stock_value), 0)   AS stock_value
                  FROM v_donor_profitability""",
                (rs, i) -> new DonorTotals(
                        rs.getInt("donors"),
                        rs.getBigDecimal("total_cost"),
                        rs.getBigDecimal("revenue"),
                        rs.getBigDecimal("stock_value")));
    }

    /**
     * @param managerId пусто, если сделку оформили до появления учёта продавцов
     * @param margin    наценка по снимку себестоимости на момент продажи,
     *                  а не по текущей цене закупки. Пусто, когда себестоимости
     *                  не было ни у одной позиции: ноль вместо неё означал бы
     *                  «продали в ноль», а это другое утверждение
     * @param itemsWithoutCost сколько позиций в наценку не вошли. Склад,
     *                  загруженный из чужой таблицы, приходит без закупочных
     *                  цен, и без этого числа отчёт молча занижает себестоимость
     */
    public record ManagerRow(Long managerId, String displayName, int dealsCount,
                             BigDecimal revenue, BigDecimal margin, int itemsWithoutCost) {
    }

    /**
     * @param legacyCode номер машины в предыдущей системе. Переехавший клиент
     *                   зовёт её именно так, а не нашим внутренним кодом
     * @param note марка и модель, пока каталог не сопоставлен
     * @param stockValue во сколько оценено то, что с машины ещё не продано:
     *                   без него свежий донор неотличим от убыточного
     */
    public record DonorRow(Long donorId, String publicCode, String legacyCode, String note,
                           String vin, Integer year,
                           BigDecimal totalCost, BigDecimal revenue, BigDecimal profit,
                           int partsTotal, int partsSold, BigDecimal stockValue) {
    }

    public record DonorTotals(int donors, BigDecimal totalCost, BigDecimal revenue,
                              BigDecimal stockValue) {
    }
}
