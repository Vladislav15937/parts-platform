package ru.partsflow.platform.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.shared.AuditedColumns;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Журнал действий организации: кто, что и когда поправил.
 *
 * <p><b>Журнал писался с самого начала и не читался ниоткуда.</b> Проверено
 * 8 сентября 2026: {@code AuditLogListener} пишет {@code audit_log} на каждое
 * изменение сущности, а наружу его не отдавал ни один эндпоинт. Это та самая
 * болезнь, которой в корневом {@code CLAUDE.md} посвящён отдельный раздел —
 * возможность написана, покрыта тестами и человеку недоступна, — только здесь
 * она хуже обычной: журнал ведут затем, чтобы однажды предъявить, и до того
 * дня никто не знает, что предъявить его нельзя.
 *
 * <p><b>Записи переводятся на язык экрана.</b> Решение владельца продукта
 * от 8 сентября 2026 (tasks/0043), дословно: «Иванов изменил цену „Фара
 * Toyota Camry“ с 5000 на 4500», а не «поле price у позиции 12345 стало 4500».
 * Отсюда состав строки: кто и в какой роли, когда, что за вещь названием
 * и публичным кодом, что именно изменилось и с чего на что.
 *
 * <p><b>Чего этот журнал не видит, и это не прячется.</b> Слушатель Hibernate
 * видит только то, что прошло через Hibernate: движение, записанное прямым SQL
 * мимо {@code StockLedger}, сюда не попадёт. Дыра записана в корневом
 * {@code CLAUDE.md} одной из трёх, экран её не закрывает — и не делает вид,
 * что её нет: под таблицей стоит строка о том, что журнал показывает правки,
 * прошедшие через приложение.
 *
 * <p><b>Правка с неизвестным «было» в журнал организации не идёт.</b>
 * {@code AuditLogListener} пишет {@code old_value = NULL}, когда сущность
 * пришла отсоединённой и Hibernate не знает, какой она была. Показать такую
 * строку значит написать «Цена: было — , стало 4500» у правки, которая цену
 * могла и не трогать: с чего на что — ровно тот вопрос, ради которого журнал
 * открывают, и выдуманный ответ на него хуже отсутствующего. Карточка позиции
 * такие правки показывает по-прежнему — там их видно рядом с остальной
 * историей одной вещи, а не вперемешку со всей организацией.
 */
@Service
public class OrganizationAuditService {

    /**
     * Сколько записей считаем, прежде чем сказать «больше стольких-то».
     *
     * <p>Точный счёт здесь стоит дорого и растёт с возрастом клиента: отбор
     * «что человек менял руками» разбирает снимок строки, а у переехавшего
     * клиента снимков 141 955 на 35 841 позицию. Считать их все ради подписи
     * в подвале — это секунды на каждое открытие экрана.
     *
     * <p>Обрезанный счёт назван словами («больше 2 000»), а не выдан за точный:
     * список, молчащий об обрезке, — это первый из четырёх способов соврать
     * человеку, перечисленных в {@code docs/frontend-rules.md}. Заодно подпись
     * говорит, что делать: уточнить отбор.
     */
    static final int COUNT_CAP = 2000;

    /** Вид вещи словом. Ключ — имя таблицы, как его пишет слушатель. */
    private static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("part", "Товар");
        KINDS.put("deal", "Сделка");
        KINDS.put("deal_item", "Позиция сделки");
        KINDS.put("payment", "Платёж");
        KINDS.put("donor_cost", "Затрата по машине");
    }

    /**
     * Что случилось, когда полей нет.
     *
     * <p>{@code INSERT} и {@code DELETE} — это не «изменение поля», а событие
     * целиком, и называть его перечислением тридцати четырёх полей со значением
     * «стало» бессмысленно: человек спрашивает «кто завёл» и «кто удалил».
     */
    private static final Map<String, String> CREATED = Map.of(
            "part", "Товар заведён",
            "deal", "Сделка заведена",
            "deal_item", "Позиция добавлена в сделку",
            "payment", "Платёж записан",
            "donor_cost", "Затрата записана");

    private static final Map<String, String> REMOVED = Map.of(
            "part", "Товар удалён",
            "deal", "Сделка удалена",
            "deal_item", "Позиция убрана из сделки",
            "payment", "Платёж удалён",
            "donor_cost", "Затрата удалена");

    /**
     * Одно выражение, по которому ищут вещь.
     *
     * <p>«По вещи» из ответа владельца — это и название, и публичный код,
     * и номер сделки: продавец помнит одно, кладовщик другое. Собрано
     * {@code concat_ws}, чтобы отбор был одной строкой в {@code WHERE},
     * а не семью {@code OR}, расходящимися при каждой правке.
     */
    private static final String SEARCHABLE = "concat_ws(' ', p.title, p.public_code,"
            + " dip.title, dip.public_code, d.number::text, paid.number::text,"
            + " coalesce(dcd.legacy_code, dcd.public_code), cust.name)";

    /**
     * Присоединение вещи к записи журнала.
     *
     * <p>Строкой с явными пробелами, а не текстовым блоком: блок срезает
     * пробелы по краям, и склейка «… ON a.table_name = 'part'» + следующий
     * кусок даёт грамматическую ошибку Postgres, то есть пятисотку на живом
     * запросе при молчащем компиляторе. В этом проекте так ломались история
     * карточки и выгрузка колёс — дважды за один вечер.
     */
    private static final String JOINS = " LEFT JOIN tenant_member m ON m.id = a.changed_by"
            + " LEFT JOIN part p ON a.table_name = 'part' AND p.id = a.record_id"
            + " LEFT JOIN deal d ON a.table_name = 'deal' AND d.id = a.record_id"
            + " LEFT JOIN customer cust ON cust.id = d.customer_id"
            + " LEFT JOIN deal_item di ON a.table_name = 'deal_item' AND di.id = a.record_id"
            + " LEFT JOIN deal idl ON idl.id = di.deal_id"
            + " LEFT JOIN part dip ON dip.id = di.part_id"
            + " LEFT JOIN payment pay ON a.table_name = 'payment' AND pay.id = a.record_id"
            + " LEFT JOIN deal paid ON paid.id = pay.deal_id"
            + " LEFT JOIN donor_cost dc ON a.table_name = 'donor_cost' AND dc.id = a.record_id"
            + " LEFT JOIN donor dcd ON dcd.id = dc.donor_id";

    private final JdbcTemplate jdbc;

    public OrganizationAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Два слова, которыми меню колонки говорит «пусто» и «не пусто».
     *
     * <p>Те же, что на витрине склада и на вкладке колёс: меню одно на все три
     * экрана, и второй словарь пустоты разошёлся бы с первым молча. Здесь оба
     * значат ровно то, о чём спрашивают: у автора — «правка без вошедшего»,
     * у поля — «событие без полей», то есть заведение или удаление. Колонка,
     * у которой пустота ничего не значила бы, в {@link #filterable()}
     * не входит вовсе: пункт меню, всегда возвращающий ноль строк, — это
     * отбор, который врёт.
     */
    static final String FILTER_EMPTY = "—пусто—";
    static final String FILTER_PRESENT = "—не пусто—";

    /**
     * Страница журнала.
     *
     * @param author     имя сотрудника из меню колонки либо слово пустоты;
     *                   {@code null} — все
     * @param kind       вид вещи словом («Товар»); {@code null} — все
     * @param field      название поля («Цена») либо слово пустоты;
     *                   {@code null} — любое
     * @param query      что искать в названии вещи, её коде или номере сделки
     * @param from       начало периода включительно
     * @param to         конец периода исключительно
     * @param after      курсор: номер последней показанной записи
     * @param size       сколько записей вернуть
     */
    @Transactional(readOnly = true)
    public Journal page(String author, String kind, String field,
                        String query, Instant from, Instant to, Long after, int size) {

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();

        // Автор отбирается по номеру, а не по имени: под именем стоит индекс
        // audit_log_user_ix, а под join'ом с tenant_member — нет. Имён-тёзок
        // в компании бывает несколько, поэтому берутся все совпавшие номера.
        if (FILTER_EMPTY.equals(author)) {
            where.append(" AND a.changed_by IS NULL");
        } else if (FILTER_PRESENT.equals(author)) {
            where.append(" AND a.changed_by IS NOT NULL");
        } else if (author != null && !author.isBlank()) {
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM tenant_member WHERE display_name = ?", Long.class, author);
            if (ids.isEmpty()) {
                // Такого сотрудника нет — пустая выдача честнее, чем отбор,
                // молча снятый за ненадобностью.
                return new Journal(List.of(), 0, false, false, filterable());
            }
            where.append(" AND a.changed_by IN (")
                    .append(AuditedColumns.placeholders(ids)).append(')');
            args.addAll(ids);
        }

        if (kind != null && !kind.isBlank()) {
            String table = tableOf(kind);
            if (table == null) {
                return new Journal(List.of(), 0, false, false, filterable());
            }
            where.append(" AND a.table_name = ?");
            args.add(table);
        }

        if (query != null && !query.isBlank()) {
            where.append(" AND ").append(SEARCHABLE).append(" ILIKE ?");
            args.add("%" + query.trim() + "%");
        }
        if (from != null) {
            where.append(" AND a.changed_at >= ?");
            args.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND a.changed_at < ?");
            args.add(java.sql.Timestamp.from(to));
        }

        // Правка, у которой не изменилось ни одно видимое человеку поле,
        // в журнал не идёт: остаток и статус ведут движения склада, и снимков
        // от них у переехавшего клиента вчетверо больше, чем позиций.
        // Отбор по названию поля — это тот же вопрос, только про одну колонку.
        boolean anyField = field == null || field.isBlank()
                || FILTER_EMPTY.equals(field) || FILTER_PRESENT.equals(field);
        Set<String> columns = anyField ? null : columnsLabelled(field);
        if (columns != null && columns.isEmpty()) {
            return new Journal(List.of(), 0, false, false, filterable());
        }
        where.append(" AND (a.operation <> 'UPDATE' OR (a.old_value IS NOT NULL AND ")
                .append(changedExists(columns == null ? "w.cols" : "?::text[]"))
                .append("))");
        if (columns != null) {
            args.add(literal(columns));
        }
        if (FILTER_EMPTY.equals(field)) {
            // «Поле не заполнено» у записи журнала означает событие без полей:
            // товар завели, платёж записали, позицию убрали из сделки.
            where.append(" AND a.operation <> 'UPDATE'");
        } else if (field != null && !field.isBlank()) {
            // Заведение и удаление полей не меняют — отбор по полю их исключает.
            where.append(" AND a.operation = 'UPDATE'");
        }

        long total = count(where.toString(), args);

        List<Object> pageArgs = new ArrayList<>(args);
        StringBuilder page = new StringBuilder(where);
        if (after != null) {
            page.append(" AND a.id < ?");
            pageArgs.add(after);
        }
        pageArgs.add(size);

        List<Entry> entries = jdbc.query(watched()
                        + " SELECT a.id, a.changed_at, a.table_name, a.record_id, a.operation,"
                        + " a.changed_by_role AS author_role, m.display_name AS author,"
                        + " p.title AS part_title, p.public_code AS part_code,"
                        + " d.number AS deal_number, cust.name AS deal_customer,"
                        + " dip.title AS item_title, dip.public_code AS item_code,"
                        + " idl.number AS item_deal_number,"
                        + " paid.number AS payment_deal_number,"
                        + " coalesce(dcd.legacy_code, dcd.public_code) AS cost_donor"
                        + " FROM audit_log a JOIN watched w ON w.table_name = a.table_name"
                        + JOINS + page + " ORDER BY a.id DESC LIMIT ?",
                (rs, i) -> new Entry(
                        rs.getLong("id"),
                        rs.getTimestamp("changed_at").toInstant(),
                        rs.getString("author"),
                        rs.getString("author_role"),
                        KINDS.get(rs.getString("table_name")),
                        subjectOf(rs.getString("table_name"), rs.getString("part_title"),
                                rs.getString("deal_customer"), rs.getString("item_title")),
                        codeOf(rs.getString("table_name"), rs.getString("part_code"),
                                rs.getObject("deal_number"), rs.getString("item_code"),
                                rs.getLong("record_id")),
                        contextOf(rs.getString("table_name"), rs.getObject("item_deal_number"),
                                rs.getObject("payment_deal_number"), rs.getString("cost_donor")),
                        actionOf(rs.getString("table_name"), rs.getString("operation")),
                        new ArrayList<>()),
                withWatched(pageArgs));

        fillChanges(entries);
        boolean more = entries.size() == size;
        return new Journal(entries, Math.min(total, COUNT_CAP), total > COUNT_CAP,
                more, filterable());
    }

    /**
     * Счёт с потолком.
     *
     * <p>{@code LIMIT} внутри подзапроса — это не украшение: он даёт Postgres
     * остановиться, набрав потолок, вместо того чтобы разобрать снимок каждой
     * строки журнала.
     */
    private long count(String where, List<Object> args) {
        List<Object> counted = new ArrayList<>(args);
        counted.add(COUNT_CAP + 1);
        Long found = jdbc.queryForObject(watched()
                        + " SELECT count(*) FROM (SELECT 1 FROM audit_log a"
                        + " JOIN watched w ON w.table_name = a.table_name" + JOINS + where
                        + " LIMIT ?) capped",
                Long.class, withWatched(counted));
        return found == null ? 0 : found;
    }

    /**
     * Перечень отслеживаемых таблиц и их колонок — таблицей внутри запроса.
     *
     * <p>Иначе набор колонок пришлось бы либо хранить в базе (второй источник
     * правды рядом со словарём), либо переписывать запрос под каждую таблицу.
     */
    private String watched() {
        return "WITH watched(table_name, cols) AS (VALUES "
                + String.join(",", KINDS.keySet().stream()
                        .map(table -> "(?, ?::text[])").toList())
                + ")";
    }

    /**
     * Набор колонок — литералом массива Postgres, а не {@code String[]}.
     *
     * <p>Драйвер отдаёт Java-массив параметром не всегда и не одинаково,
     * а литерал с приведением {@code ?::text[]} читается одинаково везде.
     * Имена колонок сюда приходят из словаря, а не из запроса, и состоят
     * из букв и подчёркиваний — кавычить внутри литерала нечего.
     */
    private static String literal(java.util.Collection<String> columns) {
        return "{" + String.join(",", columns) + "}";
    }

    private Object[] withWatched(List<Object> rest) {
        List<Object> all = new ArrayList<>();
        for (String table : KINDS.keySet()) {
            all.add(table);
            all.add(literal(AuditedColumns.of(table).keySet()));
        }
        all.addAll(rest);
        return all.toArray();
    }

    /** «Хоть одно из этих полей стало другим» — сравнением приведённых значений. */
    private static String changedExists(String columns) {
        String was = AuditedColumns.normalized("a.old_value ->> f.key");
        String now = AuditedColumns.normalized("a.new_value ->> f.key");
        return "EXISTS (SELECT 1 FROM unnest(" + columns + ") AS f(key) WHERE "
                + was + " IS DISTINCT FROM " + now + ")";
    }

    /**
     * Что именно изменилось — вторым запросом, по номерам уже отобранной
     * страницы.
     *
     * <p>Не одним запросом с первым: там {@code LIMIT} считал бы изменившиеся
     * поля, а не записи журнала, и страница из пятидесяти строк оказалась бы
     * страницей из восьми правок с шестью полями каждая.
     */
    private void fillChanges(List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        Map<Long, Entry> byId = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>();
        for (Entry entry : entries) {
            byId.put(entry.id(), entry);
            ids.add(entry.id());
        }

        String was = AuditedColumns.normalized("a.old_value ->> f.key");
        String now = AuditedColumns.normalized("a.new_value ->> f.key");

        List<Diff> diffs = jdbc.query(watched()
                        + " SELECT a.id, a.table_name, f.key, " + was + " AS was, " + now
                        + " AS now FROM audit_log a JOIN watched w ON w.table_name = a.table_name"
                        + " CROSS JOIN unnest(w.cols) AS f(key)"
                        + " WHERE a.id IN (" + AuditedColumns.placeholders(ids) + ")"
                        + " AND a.operation = 'UPDATE' AND a.old_value IS NOT NULL AND "
                        + was + " IS DISTINCT FROM " + now
                        + " ORDER BY a.id DESC, array_position(w.cols, f.key)",
                (rs, i) -> new Diff(rs.getLong("id"), rs.getString("table_name"),
                        rs.getString("key"), rs.getString("was"), rs.getString("now")),
                withWatched(new ArrayList<>(ids)));

        Map<String, Map<Long, String>> titles = resolveReferences(diffs);

        for (Diff diff : diffs) {
            Entry entry = byId.get(diff.auditId());
            if (entry == null) {
                continue;
            }
            Map<Long, String> lookup = titles.getOrDefault(diff.column(), Map.of());
            entry.changes().add(new Change(diff.table(), diff.column(),
                    AuditedColumns.of(diff.table()).get(diff.column()),
                    AuditedColumns.display(diff.column(), diff.was(), lookup),
                    AuditedColumns.display(diff.column(), diff.now(), lookup)));
        }
    }

    /** Имена вместо идентификаторов — по одному запросу на вид ссылки. */
    private Map<String, Map<Long, String>> resolveReferences(List<Diff> diffs) {
        Map<String, Set<Long>> wanted = new HashMap<>();
        for (Diff diff : diffs) {
            if (!AuditedColumns.REFERENCES.contains(diff.column())) {
                continue;
            }
            Set<Long> ids = wanted.computeIfAbsent(diff.column(), key -> new LinkedHashSet<>());
            addId(ids, diff.was());
            addId(ids, diff.now());
        }
        Map<String, Map<Long, String>> titles = new HashMap<>();
        wanted.forEach((column, ids) -> titles.put(column,
                AuditedColumns.titlesOf(jdbc, column, ids)));
        return titles;
    }

    private static void addId(Set<Long> into, String raw) {
        if (raw == null) {
            return;
        }
        try {
            into.add(Long.parseLong(raw));
        } catch (NumberFormatException notAnId) {
            // Не идентификатор — покажется как есть.
        }
    }

    // ------------------------------------------------------------- слова строки

    private static String subjectOf(String table, String partTitle,
                                    String dealCustomer, String itemTitle) {
        return switch (table) {
            case "part" -> partTitle;
            case "deal" -> dealCustomer;
            case "deal_item" -> itemTitle;
            default -> null;
        };
    }

    /**
     * Код вещи — тот, по которому её находит человек.
     *
     * <p>У товара это публичный код с витрины, у сделки — её номер. Когда сама
     * вещь уже удалена, остаётся только номер записи, и он показывается как
     * есть: выдумывать нечего, а прочерк не даст даже зацепки для запроса
     * в базу.
     */
    private static String codeOf(String table, String partCode, Object dealNumber,
                                 String itemCode, long recordId) {
        String found = switch (table) {
            case "part" -> partCode;
            case "deal" -> dealNumber == null ? null : "№" + dealNumber;
            case "deal_item" -> itemCode;
            default -> null;
        };
        return found != null ? found : "запись №" + recordId;
    }

    /** Где это случилось: в какой сделке, по какой машине. */
    private static String contextOf(String table, Object itemDealNumber,
                                    Object paymentDealNumber, String costDonor) {
        return switch (table) {
            case "deal_item" -> itemDealNumber == null ? null : "Сделка №" + itemDealNumber;
            case "payment" -> paymentDealNumber == null ? null : "Сделка №" + paymentDealNumber;
            case "donor_cost" -> costDonor == null ? null : "Машина " + costDonor;
            default -> null;
        };
    }

    private static String actionOf(String table, String operation) {
        return switch (operation) {
            case "INSERT" -> CREATED.get(table);
            case "DELETE" -> REMOVED.get(table);
            default -> null;
        };
    }

    private static String tableOf(String kind) {
        return KINDS.entrySet().stream()
                .filter(entry -> entry.getValue().equals(kind))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    /** Колонки, названные этим словом. «Сумма» есть и у сделки, и у платежа. */
    private static Set<String> columnsLabelled(String label) {
        Set<String> columns = new LinkedHashSet<>();
        for (String table : KINDS.keySet()) {
            AuditedColumns.of(table).forEach((column, name) -> {
                if (name.equals(label)) {
                    columns.add(column);
                }
            });
        }
        return columns;
    }

    // ------------------------------------------------------------------ отбор

    /**
     * Колонки, по которым отбирает сервер.
     *
     * <p>Список приходит с сервера, а не пишется во фронтенде: меню колонки
     * рисует стрелку по нему, и локальная копия рано или поздно предложит
     * отбор, которого сервер не делает.
     */
    public List<String> filterable() {
        return List.of("author", "field");
    }

    /** Значения для меню колонки — тем же механизмом, что на витрине склада. */
    @Transactional(readOnly = true)
    public List<String> values(String column) {
        return switch (column) {
            case "author" -> jdbc.queryForList("""
                    SELECT DISTINCT display_name FROM tenant_member
                     WHERE display_name IS NOT NULL ORDER BY 1""", String.class);
            case "kind" -> List.copyOf(KINDS.values());
            case "field" -> {
                Set<String> labels = new TreeSet<>(Comparator.naturalOrder());
                KINDS.keySet().forEach(table -> labels.addAll(AuditedColumns.of(table).values()));
                yield List.copyOf(labels);
            }
            // Меню открывают только у колонок из filterable(); чужое имя —
            // это рассинхрон экрана и сервера, и пустой список честнее выдумки.
            default -> List.of();
        };
    }

    // ----------------------------------------------------------------- ответы

    /**
     * @param author     {@code null} — автор не записан: правка сделана
     *                   до 4 августа 2026, приехала переносом или сделана
     *                   фоновой задачей. Подставлять сюда текущего
     *                   пользователя нельзя: это выглядит как ответ
     *                   на вопрос «кто», будучи догадкой
     * @param authorRole роль **на момент правки**, снимком. {@code null}
     *                   у всего, что записано до 8 сентября 2026: текущая
     *                   роль здесь соврала бы задним числом
     * @param action     заполнен у событий без полей («Товар заведён»)
     */
    public record Entry(long id, Instant at, String author, String authorRole,
                        String kind, String subject, String subjectCode, String context,
                        String action, List<Change> changes) {
    }

    /**
     * @param table  и {@code column} нужны экрану, чтобы назвать словом
     *               состояние сделки: словарь состояний живёт во фронтенде
     *               одним файлом, и копия его на сервере была бы пятой
     */
    public record Change(String table, String column, String label,
                         String before, String after) {
    }

    /**
     * @param total   сколько записей нашлось, не больше {@link #COUNT_CAP}
     * @param capped  счёт упёрся в потолок — записей больше, чем сказано
     * @param more    есть что показать дальше по кнопке
     */
    public record Journal(List<Entry> items, long total, boolean capped, boolean more,
                          List<String> filterable) {
    }

    private record Diff(long auditId, String table, String column, String was, String now) {
    }
}
