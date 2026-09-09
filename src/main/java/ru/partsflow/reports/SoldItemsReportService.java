package ru.partsflow.reports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.inventory.CatalogService;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Проданные позиции — строками, а не сделками.
 *
 * <p><b>Зачем.</b> Себестоимость каждой проданной строки лежит в
 * {@code deal_item.cost_price_snapshot} с самого начала, но человеку
 * не показывалась нигде построчно: её читали только агрегаты — «Наценка»
 * в продажах по менеджерам и в отчёте по каналам. «Сколько мы заработали
 * на запчастях с этого контейнера», «на чём мы теряем» и «кто продаёт
 * в минус» — вопросы про строку, и отвечал на них разработчик с SQL.
 *
 * <p><b>Всё здесь — из сделки, ничего из карточки.</b> Цена, себестоимость
 * и выгода берутся из {@code deal_item}: цена карточки сегодня — это не та
 * цена, за которую деталь ушла в июле, а себестоимость донора могли
 * переоценить задним числом. Ровно на этом расходились строка и подвал
 * у вкладки «Продано» в разрезе по машине.
 *
 * <p><b>Что считается продажей — то же, что у {@code v_manager_sales}.</b>
 * Сделка выдана <i>и</i> позиция выдана: при частичном возврате документ
 * остаётся выданным, а возвращённая позиция выручкой быть перестаёт.
 * Свои условия дали бы владельцу два разных ответа на один вопрос —
 * в отчёте по менеджерам одна выручка, здесь другая.
 *
 * <p>Читается через {@code JdbcTemplate} внутри транзакции: {@code search_path}
 * выставляет провайдер соединений Hibernate, и вне транзакции запрос ушёл бы
 * в {@code public}. Это касается и новых перегрузок — аннотация
 * не наследуется.
 */
@Service
public class SoldItemsReportService {

    /** Страница списка. У живого клиента 82 549 проданных позиций. */
    public static final int DEFAULT_SIZE = 100;

    private static final int MAX_SIZE = 500;

    /**
     * Что вообще считается проданным.
     *
     * <p>Дословно условие {@code revenue} из {@code v_manager_sales}
     * и {@code v_donor_profitability}: разойдись оно — «выручено 331 716»
     * в окупаемости и сумма этого отчёта назвали бы разные числа.
     */
    private static final String SOLD = """
             WHERE dl.status = 'ISSUED' AND di.status = 'ISSUED'
               AND dl.closed_at IS NOT NULL""";

    private static final String FROM = """
              FROM deal_item di
              JOIN deal dl ON dl.id = di.deal_id
              JOIN part p ON p.id = di.part_id
              LEFT JOIN warehouse w ON w.id = di.warehouse_id
              LEFT JOIN tenant_member tm ON tm.id = dl.manager_id
              LEFT JOIN supply s ON s.id = p.supply_id
              LEFT JOIN donor d ON d.id = p.donor_id""";

    /**
     * Цена продажи за штуку.
     *
     * <p>Скидка у нас суммой на строку, а не процентом за единицу, поэтому
     * цена продажи считается делением: «две двери со скидкой 200» — это
     * не «цена 3000», а «по 2900». Прежняя цена ({@code di.price}) едет
     * рядом отдельным числом: у ориентира она стоит под ценой продажи
     * зачёркнутой, и бывает как больше, так и меньше — продают
     * и со скидкой, и с наценкой.
     */
    private static final String UNIT_PRICE =
            "round((di.price * di.quantity - di.discount) / di.quantity, 2)";

    /**
     * Выгода на штуку: цена продажи минус себестоимость.
     *
     * <p>Пусто, когда себестоимости у строки нет: ноль читался бы как
     * «продали в ноль», а это другое утверждение — так уже сказано
     * про наценку в отчёте по менеджерам. Склад, приехавший из чужой
     * таблицы, приходит без закупок целиком.
     */
    private static final String UNIT_PROFIT =
            "CASE WHEN di.cost_price_snapshot IS NULL THEN NULL"
                    + " ELSE " + UNIT_PRICE + " - di.cost_price_snapshot END";

    private final JdbcTemplate jdbc;

    public SoldItemsReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Отбор списка.
     *
     * <p>Пустой отбор — весь период и весь склад: у отчёта, который открывают
     * вопросом «сколько мы вообще заработали», умолчанием не может быть месяц.
     *
     * @param from    день выдачи включительно; пусто — с начала работы
     * @param to      день выдачи включительно: владелец пишет «по 30 сентября»
     *                и имеет в виду тридцатое, а не полночь перед ним
     * @param donorId машина-донор. Пусто — все, включая позиции без машины:
     *                у переехавшего клиента их 9 417 из 35 841
     */
    public record Filter(LocalDate from, LocalDate to, Long warehouseId, Long managerId,
                         Long donorId, Long supplyId) {
    }

    /** Страница списка и итог по всему отбору. */
    @Transactional(readOnly = true)
    public Page page(Filter filter, String after, Integer size) {
        int limit = limit(size);

        List<Object> args = new ArrayList<>();
        String where = where(filter, args);

        String cursor = "";
        Cursor at = Cursor.of(after);
        if (at != null) {
            // Сравнение кортежей: порядок задан парой, и продолжать надо
            // по ней же. По одному `id` страница потеряла бы строки —
            // позиция попадает в сделку раньше, чем сделку выдают.
            cursor = " AND (dl.closed_at, di.id) < (?, ?)";
            args.add(Timestamp.from(at.soldAt()));
            args.add(at.itemId());
        }
        args.add(limit + 1);

        List<Item> rows = jdbc.query(
                "SELECT di.id AS item_id, dl.closed_at, dl.id AS deal_id, dl.number AS deal_number,"
                        + " p.id AS part_id, p.public_code, p.title,\n"
                        + "       " + CatalogService.CONDITION + " AS condition_word,\n"
                        + "       " + UNIT_PRICE + " AS unit_price,\n"
                        + "       di.price AS list_price, di.quantity,\n"
                        + "       di.cost_price_snapshot AS cost_price,\n"
                        + "       " + UNIT_PROFIT + " AS unit_profit,\n"
                        + "       w.name AS warehouse, tm.display_name AS manager,\n"
                        + "       s.number AS supply_number,\n"
                        + "       COALESCE(d.legacy_code, d.public_code) AS donor_code\n"
                        + FROM + "\n"
                        + SOLD + where + cursor + "\n"
                        + " ORDER BY dl.closed_at DESC, di.id DESC\n"
                        + " LIMIT ?",
                (rs, i) -> new Item(
                        rs.getLong("item_id"),
                        rs.getTimestamp("closed_at").toInstant(),
                        rs.getLong("deal_id"),
                        rs.getLong("deal_number"),
                        rs.getLong("part_id"),
                        rs.getString("public_code"),
                        rs.getString("title"),
                        rs.getString("condition_word"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("list_price"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("cost_price"),
                        rs.getBigDecimal("unit_profit"),
                        rs.getString("warehouse"),
                        rs.getString("manager"),
                        rs.getString("supply_number"),
                        rs.getString("donor_code")),
                args.toArray());

        // Лишняя строка — признак того, что дальше ещё есть; наружу она
        // не уходит, иначе страница окажется длиннее заявленной.
        String next = null;
        if (rows.size() > limit) {
            Item last = rows.get(limit - 1);
            next = new Cursor(last.soldAt(), last.itemId()).encode();
            rows = rows.subList(0, limit);
        }

        // Список ответственных считается по всем продажам, а не по отобранным:
        // сузившийся отбором, он не дал бы переключиться с одного продавца
        // на другого — выбранного в нём уже не было бы.
        List<Named> managers = after == null ? managers() : List.of();

        return new Page(rows, totals(filter), next, managers);
    }

    /**
     * Итог по всему отбору, а не по показанной странице.
     *
     * <p>Считается отдельным запросом — и это не украшение подвала, а сама
     * проверка: сложенные строки сходятся сами с собой при любом отборе,
     * а разъехавшуюся выборку показывает только независимо посчитанное число.
     */
    @Transactional(readOnly = true)
    public Totals totals(Filter filter) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);

        return jdbc.queryForObject("""
                SELECT count(*) AS items,
                       COALESCE(sum(di.quantity), 0) AS quantity,
                       round(COALESCE(sum(di.price * di.quantity - di.discount), 0), 2) AS revenue,
                       round(COALESCE(sum(di.cost_price_snapshot * di.quantity), 0), 2) AS cost,
                       round(COALESCE(sum((di.price - di.cost_price_snapshot) * di.quantity
                                          - di.discount), 0), 2) AS profit,
                       count(*) FILTER (WHERE di.cost_price_snapshot IS NULL) AS without_cost
                """ + FROM + "\n" + SOLD + where,
                (rs, i) -> new Totals(
                        rs.getInt("items"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("revenue"),
                        rs.getBigDecimal("cost"),
                        rs.getBigDecimal("profit"),
                        rs.getInt("without_cost")),
                args.toArray());
    }

    /**
     * Пишет отобранное в поток — всё, что прошло отбор.
     *
     * <p>Потоком, а не списком: у живого клиента 82 549 проданных позиций,
     * собранные в памяти перед отправкой — это сотни мегабайт на каждого
     * скачивающего. Курсор работает только внутри транзакции: при включённом
     * autoCommit Postgres игнорирует {@code fetchSize} и вычитывает всё разом.
     *
     * <p>Отбор тот же, что у страницы: скачанный файл обязан совпасть с тем,
     * что владелец видел на экране, — ради этой сверки он его и качает.
     */
    @Transactional(readOnly = true)
    public void export(Filter filter, Consumer<List<String>> writer) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);

        String sql = "SELECT dl.closed_at, dl.number AS deal_number, p.public_code, p.title,\n"
                + "       " + CatalogService.CONDITION + " AS condition_word,\n"
                + "       " + UNIT_PRICE + " AS unit_price, di.price AS list_price,\n"
                + "       di.quantity, di.cost_price_snapshot AS cost_price,\n"
                + "       " + UNIT_PROFIT + " AS unit_profit,\n"
                + "       w.name AS warehouse, tm.display_name AS manager,\n"
                + "       s.number AS supply_number,\n"
                + "       COALESCE(d.legacy_code, d.public_code) AS donor_code\n"
                + FROM + "\n"
                + SOLD + where + "\n"
                + " ORDER BY dl.closed_at DESC, di.id DESC";

        jdbc.query(connection -> {
            var ps = connection.prepareStatement(sql);
            // Курсор пачками: без этого драйвер вычитывает всё проданное разом.
            ps.setFetchSize(500);
            for (int at = 0; at < args.size(); at++) {
                ps.setObject(at + 1, args.get(at));
            }
            return ps;
        }, rs -> {
            writer.accept(List.of(
                    day(rs.getTimestamp("closed_at")),
                    String.valueOf(rs.getLong("deal_number")),
                    text(rs.getString("public_code")),
                    text(rs.getString("title")),
                    text(rs.getString("condition_word")),
                    number(rs.getBigDecimal("unit_price")),
                    number(rs.getBigDecimal("list_price")),
                    number(rs.getBigDecimal("cost_price")),
                    number(rs.getBigDecimal("unit_profit")),
                    number(rs.getBigDecimal("quantity")),
                    text(rs.getString("warehouse")),
                    text(rs.getString("manager")),
                    text(rs.getString("supply_number")),
                    text(rs.getString("donor_code"))));
        });
    }

    /** Заголовок файла — теми же словами, что колонки на экране. */
    public static List<String> exportHeader() {
        return List.of("Дата выдачи", "Номер сделки", "Номер товара", "Наименование",
                "Состояние", "Цена продажи", "Прежняя цена", "Себестоимость", "Выгода",
                "Количество", "Склад выдачи", "Ответственный", "Поставка", "Номер донора");
    }

    /**
     * Кто продавал — по всем продажам, а не по отобранным.
     *
     * <p>Список сотрудников целиком тут не годится: отбор, предлагающий
     * человека, у которого продаж нет, — это отбор, который врёт. И сам
     * справочник сотрудников открыт только владельцу, а отчёты читает
     * ещё и менеджер.
     */
    private List<Named> managers() {
        return jdbc.query("SELECT DISTINCT dl.manager_id AS id, tm.display_name AS name\n"
                        + FROM + "\n" + SOLD + " AND dl.manager_id IS NOT NULL\n"
                        + " ORDER BY name",
                (rs, i) -> new Named(rs.getLong("id"), rs.getString("name")));
    }

    /**
     * Отбор в {@code WHERE}, а не отсеивание показанного.
     *
     * <p>Список обрезан сотней строк, и сузить уже показанное значит искать
     * в первой сотне то, что отбор нашёл бы во всех восьмидесяти тысячах.
     */
    private static String where(Filter filter, List<Object> args) {
        StringBuilder where = new StringBuilder();
        if (filter.from() != null) {
            where.append(" AND dl.closed_at >= ?");
            args.add(Timestamp.valueOf(filter.from().atStartOfDay()));
        }
        if (filter.to() != null) {
            // Включительно: «по 30 сентября» означает тридцатое целиком,
            // а не полночь перед ним — иначе из отчёта пропадает день,
            // и пропадает молча.
            where.append(" AND dl.closed_at < ?");
            args.add(Timestamp.valueOf(filter.to().plusDays(1).atStartOfDay()));
        }
        if (filter.warehouseId() != null) {
            where.append(" AND di.warehouse_id = ?");
            args.add(filter.warehouseId());
        }
        if (filter.managerId() != null) {
            where.append(" AND dl.manager_id = ?");
            args.add(filter.managerId());
        }
        if (filter.donorId() != null) {
            where.append(" AND p.donor_id = ?");
            args.add(filter.donorId());
        }
        if (filter.supplyId() != null) {
            where.append(" AND p.supply_id = ?");
            args.add(filter.supplyId());
        }
        return where.toString();
    }

    private static int limit(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    /**
     * Дата в файле пишется так же, как на экране, и так же, как в выгрузке
     * склада: `05.09.2026`, а не `2026-09-05`.
     *
     * <p>ISO-вид — внутреннее представление. Файл открывают в Excel и читают
     * глазами, и дата в нём обязана быть на том же языке, что и на экране,
     * иначе один и тот же день выглядит двумя разными. Формат взят
     * у {@code CatalogService.day} — не переписан рядом своими словами:
     * две записи одного правила расходятся молча, и в этом проекте уже
     * расходились (таблица месяцев, копии словаря состояний).
     */
    private static String day(Timestamp value) {
        return value == null ? "" : java.time.format.DateTimeFormatter
                .ofPattern("dd.MM.yyyy")
                .withZone(java.time.ZoneId.systemDefault())
                .format(value.toInstant());
    }

    /**
     * Метка продолжения: момент выдачи и номер строки сделки.
     *
     * <p>Одним непрозрачным значением, а не двумя параметрами: экран передаёт
     * его обратно как есть и о его устройстве не знает — иначе порядок отчёта
     * пришлось бы повторить на клиенте, и он разошёлся бы с серверным.
     *
     * <p>Цифрами, точкой и подчёркиванием, а не {@code Instant.toString()}:
     * то содержит двоеточия, и метка, уехавшая в адрес и вернувшаяся оттуда,
     * зависела бы от того, кто и сколько раз её закодировал. Наносекунды
     * не отбрасываются — у {@code timestamptz} точность до микросекунды,
     * и округлённая метка теряла бы строки или повторяла их.
     */
    private record Cursor(Instant soldAt, long itemId) {

        String encode() {
            return soldAt.getEpochSecond() + "." + soldAt.getNano() + "_" + itemId;
        }

        static Cursor of(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            int split = value.lastIndexOf('_');
            int dot = value.lastIndexOf('.', split);
            if (split <= 0 || dot <= 0) {
                // 400, а не 500: метку продолжения экран получает от нас же,
                // и битая означает ошибку запроса.
                throw new IllegalArgumentException("Метка продолжения испорчена: " + value);
            }
            try {
                return new Cursor(
                        Instant.ofEpochSecond(Long.parseLong(value.substring(0, dot)),
                                Long.parseLong(value.substring(dot + 1, split))),
                        Long.parseLong(value.substring(split + 1)));
            } catch (RuntimeException cause) {
                throw new IllegalArgumentException("Метка продолжения испорчена: " + value);
            }
        }
    }

    /**
     * Строка отчёта.
     *
     * @param soldAt    момент выдачи сделки: «дата выдачи» у ориентира
     * @param price     цена продажи за штуку — из сделки, а не из карточки
     * @param listPrice цена до скидки. Совпала с {@code price} — скидки
     *                  не было, и зачёркивать нечего
     * @param costPrice снимок себестоимости на момент продажи. Пусто — закупки
     *                  у позиции не было (склад из чужой таблицы)
     * @param profit    цена продажи минус себестоимость, за штуку. Пусто там же,
     *                  где пуста себестоимость: ноль читался бы как «в ноль»
     * @param donorCode номер машины так, как её зовёт владелец: его собственный,
     *                  если машина переехала, иначе наш
     */
    public record Item(long itemId, Instant soldAt, long dealId, long dealNumber,
                       long partId, String publicCode, String title, String condition,
                       BigDecimal price, BigDecimal listPrice, BigDecimal quantity,
                       BigDecimal costPrice, BigDecimal profit,
                       String warehouse, String manager,
                       String supplyNumber, String donorCode) {
    }

    /**
     * Подвал: по всему отбору, а не по показанному.
     *
     * @param withoutCost сколько строк не вошло в себестоимость и выгоду.
     *                    Молчать нельзя: посчитанная по ним прибыль была бы
     *                    завышена на всю их закупочную стоимость
     */
    public record Totals(int items, BigDecimal quantity, BigDecimal revenue,
                         BigDecimal cost, BigDecimal profit, int withoutCost) {
    }

    /** @param nextAfter пусто — показано всё, и «Показать ещё» предлагать нечего */
    public record Page(List<Item> rows, Totals totals, String nextAfter, List<Named> managers) {
    }

    public record Named(long id, String name) {
    }
}
