package ru.partsflow.reports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

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

    public ReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                        rs.getInt("unpaid_deals")),
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

    public record SettlementRow(Long customerId, String customerName, String phone,
                                BigDecimal accountBalance, BigDecimal debt, int unpaidDeals) {
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
