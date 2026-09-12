package ru.partsflow.reports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Что поступило с машины и с поставки — позициями.
 *
 * <p><b>Зачем.</b> {@code v_donor_profitability} уже отвечает числами: продано
 * на столько-то, лежит на столько-то. Спросить «а что именно лежит» было
 * нельзя — владелец уходил в склад и собирал отбор руками. По поставке
 * не было и чисел: вьюхи под неё нет вовсе, а контейнер из Японии окупается
 * ровно так же, как машина, и спрашивают про него теми же словами.
 *
 * <p><b>Вкладки — это выражения вьюхи, раскрытые до позиций, а не новая
 * выборка.</b> Иначе владелец получит два разных ответа на один вопрос:
 * в таблице окупаемости «продано 24», а на вкладке — двадцать три, и какое
 * из чисел верное, по экрану не понять. Поэтому «Продано» — это
 * {@code parts_sold} (статус позиции), «Остатки» — то, из чего сложен
 * {@code stock_value}, а сумма продаж собирается тем же условием, что
 * и {@code revenue}: сделка выдана <i>и</i> позиция выдана. Возвращённая
 * позиция выручкой быть перестаёт, а сделка при частичном возврате остаётся
 * выданной.
 *
 * <p><b>Четыре вкладки — разбиение, а не четыре независимых отбора.</b>
 * Статусов у позиции ровно четыре, и «Остатки» намеренно взяты как «не продано
 * и не списано»: карточка, заведённая без прихода ({@code DRAFT}), иначе
 * не попала бы никуда, и «Поступило» перестало бы сходиться с суммой трёх
 * вкладок. Остатка у неё нет, поэтому сумма «Остатков» от этого не меняется —
 * она по-прежнему равна {@code stock_value}.
 *
 * <p>Читается через {@code JdbcTemplate} внутри транзакции: {@code search_path}
 * выставляет провайдер соединений Hibernate, и вне транзакции запрос ушёл бы
 * в {@code public}. Это касается и новых перегрузок — аннотация
 * не наследуется.
 */
@Service
public class OriginReportService {

    /** Страница списка. Контейнер бывает на тысячи позиций, машина — на сотни. */
    public static final int DEFAULT_SIZE = 100;

    private static final int MAX_SIZE = 500;

    private final JdbcTemplate jdbc;

    public OriginReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Вкладка: чем позиция стала после того, как поступила.
     *
     * <p>Условия взяты у {@code v_donor_profitability}: «Поступило» — это
     * {@code parts_total}, «Продано» — {@code parts_sold}, «Остатки» — то,
     * по чему считается {@code stock_value}.
     */
    public enum Tab {

        /** Всё, что числится за машиной или партией: {@code parts_total}. */
        RECEIVED("received", "TRUE",
                "COALESCE(got.qty, 0)", "COALESCE(got.qty, 0) * COALESCE(p.price, 0)",
                "p.price", "p.cost_price"),

        /**
         * Остаток обнулён продажей: {@code parts_sold}. Сумма — по цене продажи.
         *
         * <p>И цена в строке — тоже: до задачи 0054 строка показывала
         * {@code p.price} и {@code p.cost_price} из карточки, то есть нынешний
         * прайс, а подвал считался по настоящим продажам. Владелец, сложивший
         * колонку «Цена» глазами, подвала не получал, а данная при продаже
         * скидка не была видна вовсе.
         */
        SOLD("sold", "p.status = 'SOLD'",
                "COALESCE(sold.qty, 0)", "COALESCE(sold.amount, 0)",
                "round(sold.amount / NULLIF(sold.qty, 0), 2)",
                "round(sold.cost / NULLIF(sold.qty, 0), 2)"),

        /** Остаток обнулён списанием. Сумма — по розничной цене: продажи не было. */
        WRITTEN_OFF("written-off", "p.status = 'WRITTEN_OFF'",
                "COALESCE(off.qty, 0)", "COALESCE(off.qty, 0) * COALESCE(p.price, 0)",
                "p.price", "p.cost_price"),

        /** Не продано и не списано — то, что лежит до сих пор: {@code stock_value}. */
        REMAINING("remaining", "p.status NOT IN ('SOLD', 'WRITTEN_OFF')",
                "p.qty_on_hand", "p.qty_on_hand * COALESCE(p.price, 0)",
                "p.price", "p.cost_price");

        private final String code;
        private final String condition;
        private final String quantity;
        private final String amount;
        private final String price;
        private final String costPrice;

        Tab(String code, String condition, String quantity, String amount,
            String price, String costPrice) {
            this.code = code;
            this.condition = condition;
            this.quantity = quantity;
            this.amount = amount;
            this.price = price;
            this.costPrice = costPrice;
        }

        public String code() {
            return code;
        }

        static Tab of(String code) {
            for (Tab tab : values()) {
                if (tab.code.equals(code)) {
                    return tab;
                }
            }
            // 400, а не 500: опечатка в адресе — ошибка запроса, и звонящий
            // должен видеть, из чего выбирать.
            throw new IllegalArgumentException(
                    "Вкладка называется received, sold, written-off или remaining, а не «%s»"
                            .formatted(code));
        }

        /**
         * Подзапрос нужен только той вкладке, которая из него считает.
         *
         * <p>Присоединять оба к каждой вкладке значит собирать продажи
         * и списания всего арендатора там, где спрашивают про остаток.
         */
        private String joins() {
            return switch (this) {
                case RECEIVED -> INTAKE_JOIN;
                case SOLD -> SOLD_JOIN;
                case WRITTEN_OFF -> WRITE_OFF_JOIN;
                case REMAINING -> "";
            };
        }
    }

    /**
     * Сколько пришло — по журналу, а не по полю карточки.
     *
     * <p><b>{@code part.quantity} — не «сколько приняли».</b> Приёмка кладёт
     * количество в строку документа и в движение, а поле карточки остаётся
     * единицей; перенос из предыдущей системы его не заполняет вовсе, то есть
     * у всех 35 841 перенесённой позиции там стоит единица. Считай подвал
     * по нему — «Поступило» показало бы «3 шт.» там, где на «Остатках» лежит
     * четыре, и объяснить эту разницу было бы нечем. Поймано живым прогоном:
     * принял две двери, а вкладка написала одну.
     */
    private static final String INTAKE_JOIN = """
            LEFT JOIN (SELECT part_id, sum(abs(qty_delta)) AS qty
                         FROM stock_movement
                        WHERE movement_type = 'INTAKE'
                        GROUP BY part_id) got ON got.part_id = p.id""";

    /**
     * Продажи по позициям — тем же условием, что и {@code revenue} во вьюхе.
     *
     * <p>Статус позиции, а не только документа: при частичном возврате сделка
     * остаётся выданной, а возвращённая позиция выручкой быть перестаёт.
     */
    private static final String SOLD_JOIN = """
            LEFT JOIN (SELECT di.part_id,
                              sum(di.quantity)                          AS qty,
                              sum(di.price * di.quantity - di.discount) AS amount,
                              sum(di.cost_price_snapshot * di.quantity) AS cost
                         FROM deal_item di
                         JOIN deal dl ON dl.id = di.deal_id
                        WHERE dl.status = 'ISSUED' AND di.status = 'ISSUED'
                        GROUP BY di.part_id) sold ON sold.part_id = p.id""";

    /**
     * Сколько списано — по журналу, а не по заведённому количеству.
     *
     * <p>У позиции из четырёх колёс две могли уйти продажей, и списаны тогда
     * не четыре, а две: {@code quantity} показал бы вдвое больше, и то же
     * количество посчиталось бы дважды — на «Продано» и на «Списано».
     */
    private static final String WRITE_OFF_JOIN = """
            LEFT JOIN (SELECT part_id, sum(abs(qty_delta)) AS qty
                         FROM stock_movement
                        WHERE movement_type = 'WRITE_OFF'
                        GROUP BY part_id) off ON off.part_id = p.id""";

    /** Позиции машины: «что именно с неё сняли и что из этого лежит». */
    @Transactional(readOnly = true)
    public Page donorItems(long donorId, Tab tab, Long after, Integer size) {
        return page("p.donor_id = ?", List.of(donorId), tab, after, size);
    }

    /**
     * Позиции партии.
     *
     * @param supplyId пусто — товар без поставки. Это отдельный разрез,
     *                 а не «все подряд»: у переехавшего клиента без партии
     *                 числится всё, что заводили руками, и спросить «сколько
     *                 из этого ещё лежит» больше негде
     */
    @Transactional(readOnly = true)
    public Page supplyItems(Long supplyId, Tab tab, Long after, Integer size) {
        return supplyId == null
                ? page("p.supply_id IS NULL", List.of(), tab, after, size)
                : page("p.supply_id = ?", List.of(supplyId), tab, after, size);
    }

    /**
     * Партии для выбора — <b>все</b>, включая закрытые.
     *
     * <p>Справочник приёмки отдаёт только те, в которые можно принимать,
     * и для отчёта это ровно наоборот: «окупился ли контейнер» спрашивают
     * про закрытый.
     */
    @Transactional(readOnly = true)
    public List<SupplyOption> supplies() {
        return jdbc.query("""
                SELECT id, kind, number, supplier_name, status, arrived_on
                  FROM supply
                 ORDER BY arrived_on DESC NULLS LAST, id DESC""",
                (rs, i) -> new SupplyOption(
                        rs.getLong("id"),
                        rs.getString("kind"),
                        rs.getString("number"),
                        rs.getString("supplier_name"),
                        rs.getString("status"),
                        rs.getDate("arrived_on") == null
                                ? null : rs.getDate("arrived_on").toLocalDate()));
    }

    /**
     * Окупаемость машины во времени: четыре накопительных ряда по месяцам.
     *
     * <p><b>Зачем.</b> Таблица «Окупаемость машин» отвечает «окупилась ли
     * на сегодня», но не «когда» и не «с какой скоростью»: машина, отбившая
     * вложенное за два месяца, и машина, отбившая столько же за два года,
     * выглядят в ней одинаково. «За сколько окупается контейнер» — вопрос,
     * вокруг которого у разборки крутится вся экономика.
     *
     * <p><b>Числа те же, что владелец уже видит.</b> Последняя точка
     * «Продано на сумму» равна колонке «Выручено» из
     * {@code v_donor_profitability}, «Себестоимость всех» — колонке
     * «Вложено», а последняя точка «Планируемой суммы» — подвалу вкладки
     * «Поступило». Разойдись они — график и таблица считают по-разному,
     * и это хуже отсутствия графика.
     */
    @Transactional(readOnly = true)
    public Chart donorChart(long donorId) {
        return chart("p.donor_id = ?", List.of(donorId), """
                SELECT to_char(date_trunc('month', incurred_on), 'YYYY-MM') AS ym,
                       sum(amount) AS amount
                  FROM donor_cost
                 WHERE donor_id = ?
                 GROUP BY 1""", List.of(donorId));
    }

    /**
     * То же по партии.
     *
     * <p><b>«Вложено» у партии считается иначе, чем у машины, и это решение
     * исполнителя.</b> Затрат у поставки в системе нет вовсе: таблица
     * {@code donor_cost} есть только у машины, а у контейнера — ни закупки,
     * ни доставки, ни растаможки. Поэтому вложенным здесь считается
     * себестоимость поступившего товара ({@code cost_price} на принятое
     * количество) — единственное число про деньги, которое у партии есть.
     * Оно же роднит оба ряда себестоимости: «Себестоимость проданных»
     * на этом графике — снимки тех же закупочных цен, то есть линии говорят
     * об одном, и пересечение с выручкой читается верно.
     *
     * @param supplyId пусто — товар без поставки: отдельный разрез, а не
     *                 «все подряд»
     */
    @Transactional(readOnly = true)
    public Chart supplyChart(Long supplyId) {
        String scope = supplyId == null ? "p.supply_id IS NULL" : "p.supply_id = ?";
        List<Object> args = supplyId == null ? List.of() : List.of(supplyId);
        return chart(scope, args, """
                SELECT to_char(date_trunc('month', m.created_at), 'YYYY-MM') AS ym,
                       sum(abs(m.qty_delta) * COALESCE(p.cost_price, 0)) AS amount
                  FROM stock_movement m
                  JOIN part p ON p.id = m.part_id
                 WHERE m.movement_type = 'INTAKE'""" + " AND " + scope + "\n"
                + " GROUP BY 1", args);
    }

    /**
     * Сборка месячной оси.
     *
     * <p>Ось тянется от первого события до текущего месяца, а не за выбранный
     * период: вопрос «когда окупилась» задают про всю жизнь машины. Месяц
     * без событий на ней остаётся — накопительная линия в нём горизонтальна,
     * и «выдохлась или ещё продаётся» видно как раз по длине полки.
     *
     * <p>Ряды накопительные, а столбцы — нет: сумма столбцов равна последней
     * точке накопительной линии по построению, а не по совпадению.
     */
    private Chart chart(String scope, List<Object> scopeArgs,
                        String investedSql, List<Object> investedArgs) {

        // Планируемая сумма: розничная стоимость всего, что поступило.
        // Считается по журналу, а не по part.quantity: то поле у принятой
        // партией позиции остаётся единицей, а у перенесённой из предыдущей
        // системы — единицей у всех 35 841.
        Map<YearMonth, BigDecimal> planned = byMonth("""
                SELECT to_char(date_trunc('month', m.created_at), 'YYYY-MM') AS ym,
                       sum(abs(m.qty_delta) * COALESCE(p.price, 0)) AS amount
                  FROM stock_movement m
                  JOIN part p ON p.id = m.part_id
                 WHERE m.movement_type = 'INTAKE'""" + " AND " + scope + "\n"
                + " GROUP BY 1", scopeArgs);

        // Выручка и себестоимость проданного — одним запросом и тем же
        // условием, что и revenue во вьюхе: сделка выдана И позиция выдана.
        // Дата — момент закрытия сделки; COALESCE на создание нужен затем,
        // чтобы выручка не исчезала с оси из-за незаполненной даты: последняя
        // точка обязана сходиться с «Выручено», а не «почти сходиться».
        Map<YearMonth, BigDecimal> revenue = new HashMap<>();
        Map<YearMonth, BigDecimal> soldCost = new HashMap<>();
        jdbc.query("""
                SELECT to_char(date_trunc('month',
                           COALESCE(dl.closed_at, dl.created_at)), 'YYYY-MM') AS ym,
                       sum(di.price * di.quantity - di.discount)  AS revenue,
                       sum(di.cost_price_snapshot * di.quantity)  AS cost
                  FROM deal_item di
                  JOIN deal dl ON dl.id = di.deal_id
                  JOIN part p ON p.id = di.part_id
                 WHERE dl.status = 'ISSUED' AND di.status = 'ISSUED'""" + " AND " + scope + "\n"
                + " GROUP BY 1",
                (RowCallbackHandler) rs -> {
                    YearMonth month = YearMonth.parse(rs.getString("ym"));
                    revenue.put(month, zeroIfNull(rs.getBigDecimal("revenue")));
                    soldCost.put(month, zeroIfNull(rs.getBigDecimal("cost")));
                },
                scopeArgs.toArray());

        Map<YearMonth, BigDecimal> invested = byMonth(investedSql, investedArgs);

        var months = new TreeSet<YearMonth>();
        months.addAll(planned.keySet());
        months.addAll(revenue.keySet());
        months.addAll(invested.keySet());
        if (months.isEmpty()) {
            // Ни затрат, ни поступлений, ни продаж: рисовать нечего, и нули
            // на пустой оси были бы утверждением о деньгах, которых не было.
            return new Chart(List.of());
        }

        YearMonth last = months.last();
        YearMonth today = YearMonth.now();
        if (today.isAfter(last)) {
            last = today;
        }

        List<Point> points = new ArrayList<>();
        BigDecimal plannedSum = BigDecimal.ZERO;
        BigDecimal revenueSum = BigDecimal.ZERO;
        BigDecimal soldCostSum = BigDecimal.ZERO;
        BigDecimal investedSum = BigDecimal.ZERO;
        for (YearMonth month = months.first();
             !month.isAfter(last);
             month = month.plusMonths(1)) {

            BigDecimal soldThisMonth = revenue.getOrDefault(month, BigDecimal.ZERO);
            plannedSum = plannedSum.add(planned.getOrDefault(month, BigDecimal.ZERO));
            revenueSum = revenueSum.add(soldThisMonth);
            soldCostSum = soldCostSum.add(soldCost.getOrDefault(month, BigDecimal.ZERO));
            investedSum = investedSum.add(invested.getOrDefault(month, BigDecimal.ZERO));

            points.add(new Point(month.toString(), plannedSum, revenueSum,
                    soldCostSum, investedSum, soldThisMonth));
        }
        return new Chart(points);
    }

    private Map<YearMonth, BigDecimal> byMonth(String sql, List<Object> args) {
        Map<YearMonth, BigDecimal> rows = new HashMap<>();
        jdbc.query(sql,
                (RowCallbackHandler) rs -> rows.put(YearMonth.parse(rs.getString("ym")),
                        zeroIfNull(rs.getBigDecimal("amount"))),
                args.toArray());
        return rows;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Страница позиций и итог по всей вкладке.
     *
     * <p>Итог считается отдельным запросом по всей выборке, а не складывается
     * из показанных строк: подвал отвечает на вопрос «сколько всего», и сумма
     * первой сотни, выданная за ответ, была бы враньём тем более наглядным,
     * чем больше партия.
     *
     * <p>Курсором по {@code id}, а не {@code OFFSET}: на глубокой странице
     * база читает и выбрасывает всё, что до неё. Колонка без {@code NULL} —
     * иначе страница молча теряет строки.
     */
    private Page page(String scope, List<Object> scopeArgs, Tab tab, Long after, Integer size) {
        int limit = limit(size);

        List<Object> args = new ArrayList<>(scopeArgs);
        String cursor = "";
        if (after != null) {
            cursor = " AND p.id > ?";
            args.add(after);
        }
        args.add(limit + 1);

        List<Item> rows = jdbc.query("""
                SELECT p.id, p.number, p.public_code, k.name AS kind, p.title,
                """ + "       " + tab.quantity + " AS qty,\n"
                // Цена и себестоимость проданного — из сделки, а не из карточки:
                // разделитель явной строкой, иначе текстовый блок съест отступ.
                + "       " + tab.price + " AS price,\n"
                + "       " + tab.costPrice + " AS cost_price,\n" + """
                       s.number AS supply_number,
                       p.created_at::date AS created_on
                  FROM part p
                  LEFT JOIN catalog.part_kind k ON k.id = p.part_kind_id
                  LEFT JOIN supply s ON s.id = p.supply_id
                """ + tab.joins() + "\n"
                + " WHERE " + scope + " AND " + tab.condition + cursor + "\n"
                + " ORDER BY p.id\n"
                + " LIMIT ?",
                (rs, i) -> new Item(
                        rs.getLong("id"),
                        rs.getLong("number"),
                        rs.getString("public_code"),
                        rs.getString("kind"),
                        rs.getString("title"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("price"),
                        rs.getBigDecimal("cost_price"),
                        rs.getString("supply_number"),
                        rs.getDate("created_on") == null
                                ? null : rs.getDate("created_on").toLocalDate()),
                args.toArray());

        // Лишняя строка — признак того, что дальше ещё есть; наружу она
        // не уходит, иначе страница окажется длиннее заявленной.
        Long next = rows.size() > limit ? rows.get(limit - 1).partId() : null;
        if (next != null) {
            rows = rows.subList(0, limit);
        }

        return new Page(rows, totals(scope, scopeArgs, tab), next);
    }

    private Totals totals(String scope, List<Object> scopeArgs, Tab tab) {
        return jdbc.queryForObject("""
                SELECT count(*) AS items,
                """ + "       COALESCE(sum(" + tab.quantity + "), 0) AS qty,\n"
                // До копейки: количество numeric(12,3) на цену numeric(14,2)
                // даёт пять знаков после запятой, и наружу уходило бы
                // «74698.00000» — деньги такими не бывают.
                + "       round(COALESCE(sum(" + tab.amount + "), 0), 2) AS amount\n"
                + "  FROM part p\n"
                + tab.joins() + "\n"
                + " WHERE " + scope + " AND " + tab.condition,
                (rs, i) -> new Totals(
                        rs.getInt("items"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("amount")),
                scopeArgs.toArray());
    }

    private static int limit(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /**
     * Строка вкладки.
     *
     * @param number     порядковый номер позиции — тот, которым её называют
     *                   вслух («посмотри позицию 347»). Общий с витриной
     *                   и вкладкой колёс: нумерация у товара одна
     * @param publicCode номер, по которому позицию видно на витрине; внутренний
     *                   {@code id} владельцу не говорит ничего
     * @param kind       вид детали из справочника. Пусто — наименование
     *                   не распознано, и это правда о карточке
     * @param quantity   смысл зависит от вкладки: принято, продано, списано
     *                   или лежит — по той же величине считается и подвал
     * @param price      на «Продано» — цена продажи за штуку, на остальных
     *                   вкладках розничная из карточки. Иначе строка и подвал
     *                   говорили бы о разном: подвал там считается по сделкам
     * @param costPrice  на «Продано» — снимок себестоимости на момент продажи,
     *                   на остальных — нынешняя из карточки. Переоценка донора
     *                   задним числом не должна переписывать прошлую прибыль
     * @param supplyNumber номер партии, которой позиция пришла
     * @param date       день, когда позицию завели
     */
    public record Item(long partId, long number, String publicCode, String kind, String title,
                       BigDecimal quantity, BigDecimal price, BigDecimal costPrice,
                       String supplyNumber, LocalDate date) {
    }

    /**
     * Подвал вкладки: сколько товаров, сколько штук и на какую сумму.
     *
     * @param items число позиций — их и показывает подвал первым числом;
     *              штук бывает больше, у позиции из четырёх колёс их четыре
     */
    public record Totals(int items, BigDecimal quantity, BigDecimal amount) {
    }

    /**
     * @param nextAfter с какой позиции продолжать. Пусто — показано всё,
     *                  и экран не должен предлагать «показать ещё»
     */
    public record Page(List<Item> rows, Totals totals, Long nextAfter) {
    }

    public record SupplyOption(long id, String kind, String number, String supplierName,
                               String status, LocalDate arrivedOn) {
    }

    /**
     * Точка месячной оси графика.
     *
     * <p>Первые четыре величины накопительные — вопрос «окупилась ли»
     * читается пересечением линий, а не сравнением столбиков соседних
     * месяцев. Пятая, наоборот, помесячная: из неё видно, выдохлась машина
     * или ещё продаётся.
     *
     * @param month        месяц вида {@code 2026-03}
     * @param planned      «Планируемая сумма»: розничная стоимость всего,
     *                     что поступило, накопительно
     * @param revenue      «Продано на сумму» накопительно; последняя точка
     *                     равна колонке «Выручено» окупаемости
     * @param soldCost     «Себестоимость проданных» накопительно: снимки
     *                     закупочной цены на момент продажи, а не нынешняя
     *                     цена карточки
     * @param totalCost    «Себестоимость всех»: сколько вложено. У машины —
     *                     затраты по ней ({@code donor_cost}), у партии —
     *                     себестоимость поступившего
     * @param monthRevenue продано на сумму за сам месяц — столбец второго
     *                     графика
     */
    public record Point(String month, BigDecimal planned, BigDecimal revenue,
                        BigDecimal soldCost, BigDecimal totalCost,
                        BigDecimal monthRevenue) {
    }

    /**
     * @param points пусто — по этой машине или партии не было ни затрат,
     *               ни поступлений, ни продаж. Это не «нет данных за период»:
     *               оси у графика в таком случае нет вовсе
     */
    public record Chart(List<Point> points) {
    }
}
