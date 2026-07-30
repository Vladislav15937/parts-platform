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
                SELECT donor_id, public_code, vin, year, total_cost, revenue, profit,
                       parts_total, parts_sold, stock_value
                  FROM v_donor_profitability
                 ORDER BY profit, donor_id
                 LIMIT ?""",
                (rs, i) -> new DonorRow(
                        rs.getLong("donor_id"),
                        rs.getString("public_code"),
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
     * @param stockValue во сколько оценено то, что с машины ещё не продано:
     *                   без него свежий донор неотличим от убыточного
     */
    public record DonorRow(Long donorId, String publicCode, String vin, Integer year,
                           BigDecimal totalCost, BigDecimal revenue, BigDecimal profit,
                           int partsTotal, int partsSold, BigDecimal stockValue) {
    }

    public record DonorTotals(int donors, BigDecimal totalCost, BigDecimal revenue,
                              BigDecimal stockValue) {
    }
}
