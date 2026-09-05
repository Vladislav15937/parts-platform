package ru.partsflow.reports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
                "COALESCE(got.qty, 0)", "COALESCE(got.qty, 0) * COALESCE(p.price, 0)"),

        /** Остаток обнулён продажей: {@code parts_sold}. Сумма — по цене продажи. */
        SOLD("sold", "p.status = 'SOLD'",
                "COALESCE(sold.qty, 0)", "COALESCE(sold.amount, 0)"),

        /** Остаток обнулён списанием. Сумма — по розничной цене: продажи не было. */
        WRITTEN_OFF("written-off", "p.status = 'WRITTEN_OFF'",
                "COALESCE(off.qty, 0)", "COALESCE(off.qty, 0) * COALESCE(p.price, 0)"),

        /** Не продано и не списано — то, что лежит до сих пор: {@code stock_value}. */
        REMAINING("remaining", "p.status NOT IN ('SOLD', 'WRITTEN_OFF')",
                "p.qty_on_hand", "p.qty_on_hand * COALESCE(p.price, 0)");

        private final String code;
        private final String condition;
        private final String quantity;
        private final String amount;

        Tab(String code, String condition, String quantity, String amount) {
            this.code = code;
            this.condition = condition;
            this.quantity = quantity;
            this.amount = amount;
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
                              sum(di.price * di.quantity - di.discount) AS amount
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
                SELECT p.id, p.public_code, k.name AS kind, p.title,
                """ + "       " + tab.quantity + " AS qty,\n" + """
                       p.price, p.cost_price,
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
     * @param publicCode номер, по которому позицию видно на витрине; внутренний
     *                   {@code id} владельцу не говорит ничего
     * @param kind       вид детали из справочника. Пусто — наименование
     *                   не распознано, и это правда о карточке
     * @param quantity   смысл зависит от вкладки: принято, продано, списано
     *                   или лежит — по той же величине считается и подвал
     * @param supplyNumber номер партии, которой позиция пришла
     * @param date       день, когда позицию завели
     */
    public record Item(long partId, String publicCode, String kind, String title,
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
}
