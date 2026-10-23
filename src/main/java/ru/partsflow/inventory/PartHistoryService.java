package ru.partsflow.inventory;

import ru.partsflow.platform.audit.AuditBatch;
import ru.partsflow.platform.security.MemberSignature;
import ru.partsflow.shared.AuditedColumns;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * История позиции: кто её правил и чем двигался остаток.
 *
 * <p><b>Две ленты, а не одна, и это не оформление.</b> «Кто уронил цену»
 * и «куда делась деталь» — разные вопросы, их задают в разные моменты
 * и разные люди. Смешанные в один список, они дают ленту, где правка заметки
 * стоит между продажей и возвратом, и найти в ней движение остатка нельзя.
 * Так же разведено и в кабинете, с которого снят паритет.
 *
 * <p><b>Правки берутся из {@code audit_log}, который пишет триггер.</b>
 * Не из кода: прямой SQL мимо приложения журнал не обойдёт, а ровно от этого
 * он и защищает. Даром это досталось не полностью — триггер кладёт снимок всей
 * строки на каждое изменение, и большинство записей рождают сами движения
 * склада: остаток и статус ведут триггеры. У переехавшего клиента таких
 * снимков 141 955 на 35 841 позицию. Поэтому снимки сравниваются между собой,
 * и в ленту попадает только то, что человек менял руками.
 *
 * <p><b>Сравнение идёт в SQL, а не в Java.</b> Вытаскивать полные снимки
 * строки, чтобы отбросить девять десятых, — это мегабайты jsonb на карточку.
 * Postgres отдаёт сразу изменившиеся поля.
 *
 * <p><b>Список полей закрытый, и это не про удобство.</b> Себестоимость
 * и минимальная цена — деньги владельца, а карточку открывает и продавец:
 * они не «скрыты» на экране, а не приезжают с сервера. Остаток же и статус
 * не показываются в первой ленте вовсе — они целиком во второй, и дублировать
 * их значит вернуть ту самую кашу, ради которой лент две.
 */
@Service
public class PartHistoryService {

    /**
     * Поля, изменение которых видно человеку, и как они называются, —
     * в {@code shared.AuditedColumns}, а не здесь.
     *
     * <p>Тот же словарь читает журнал действий организации
     * ({@code platform.audit.OrganizationAuditService}): владелец, увидевший
     * там «Оценка состояния», открывает эту карточку и обязан увидеть то же
     * слово. Копия разошлась бы не в момент копирования, а позже — когда
     * правят одну из двух.
     *
     * <p>Себестоимость и минимальная цена — деньги владельца, а карточку
     * открывает и продавец: они не «скрываются на экране», а не приезжают
     * с сервера. Остаток же и статус не показываются в первой ленте вовсе —
     * они целиком во второй, и дублировать их значит вернуть ту самую кашу,
     * ради которой лент две.
     */
    private static final Map<String, String> MOVEMENTS = Map.of(
            "INTAKE", "Поступление", "MOVE", "Перемещение", "SALE", "Продажа",
            "RETURN", "Возврат", "WRITE_OFF", "Списание",
            "INVENTORY_ADJUST", "Корректировка");

    private static final Map<String, String> DOCUMENTS = Map.of(
            "INTAKE", "Поступление", "MOVE", "Перемещение", "WRITE_OFF", "Списание",
            "RETURN", "Возврат", "INVENTORY", "Пересчёт");

    private static final Map<String, String> DOC_STATUSES =
            Map.of("DRAFT", "Черновик", "DONE", "Выполнен", "CANCELLED", "Отменён");

    /**
     * Удалённый выполненный документ (задача 0367, решение владельца 23.10.2026):
     * «Выполнен (Удалён)», как у Bazon, — документ выполнен и потом удалён.
     * Экран красит строку по признаку {@link Movement#deleted}, а не по слову.
     */
    static final String DELETED_SUFFIX = " (Удалён)";

    /**
     * Позиции сделок, которые держат товар или ждут (задача 0367): остаток они
     * не двигают, движения в журнале у них нет, поэтому лента строит строку
     * из самой позиции. Выданное и возвращённое уже есть движениями.
     *
     * <p>Статус — отдельной колонкой, словами Bazon: «Черновик», «Резерв».
     * «Предзаказ» — наш: у Bazon предзаказа под ожидаемый товар нет, и назвать
     * его «Резервом» значило бы сказать, что деталь лежит на полке.
     */
    private static final Map<String, String> HELD_ITEMS =
            Map.of("DRAFT", "Черновик", "RESERVED", "Резерв", "PREORDER", "Предзаказ");

    private final JdbcTemplate jdbc;

    public PartHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param money отдавать ли себестоимость и минимальную цену —
     *              право проверяет контроллер, сюда приезжает решение
     */
    @Transactional(readOnly = true)
    public History of(long partId, boolean money) {
        List<Movement> all = new ArrayList<>(movements(partId, PostedDocumentDeletion.allowed()));
        all.addAll(heldByDeals(partId));
        // Свежее сверху: строки двух источников встают в одну ленту по времени.
        all.sort(java.util.Comparator.comparing(Movement::at).reversed());
        return new History(changes(partId, money), all, intakeOf(partId), supplyOf(partId));
    }

    /**
     * «Поступление» колонкой «Движения товара», как у Bazon: номер документа,
     * которым позиция пришла на склад. Первый выполненный и не удалённый —
     * удалённое поступление позицию на склад, по решению владельца, не привело.
     * Пусто у перенесённого склада: документа прихода там не было.
     */
    private String intakeOf(long partId) {
        return jdbc.query("""
                        SELECT d.number
                          FROM stock_document d
                          JOIN stock_document_line l ON l.document_id = d.id
                         WHERE l.part_id = ? AND d.doc_type = 'INTAKE'
                           AND d.status = 'DONE' AND d.deleted_at IS NULL
                         ORDER BY d.completed_at, d.id
                         LIMIT 1""",
                (rs, i) -> String.valueOf(rs.getLong(1)), partId).stream().findFirst().orElse(null);
    }

    /** «Поставка» колонкой: тем же словарём, что витрина («Контейнер №18»). */
    private String supplyOf(long partId) {
        // Без поставки подпись — NULL, и findFirst() на списке из null
        // ответил бы NullPointerException вместо пустой клетки.
        List<String> found = jdbc.query("SELECT " + ru.partsflow.shared.SupplyKinds.sqlLabel("s")
                        + " AS supply FROM part p LEFT JOIN supply s ON s.id = p.supply_id"
                        + " WHERE p.id = ?",
                (rs, i) -> rs.getString(1), partId);
        return found.isEmpty() ? null : found.get(0);
    }

    // ------------------------------------------------------------------ правки

    private List<Change> changes(long partId, boolean money) {
        String columns = String.join(",", visibleFields(money));

        String was = AuditedColumns.normalized("a.old_value ->> f.key");
        String now = AuditedColumns.normalized("a.new_value ->> f.key");
        // Пометка массовой правки лежит в «стало» рядом с колонками (0373).
        // Разделители — явной строкой: текстовый блок срезает пробелы по краям.
        String bulk = "a.new_value ->> '" + AuditBatch.SIZE_KEY + "' AS bulk_size, "
                + "a.new_value ->> '" + AuditBatch.SCOPE_KEY + "' AS bulk_scope, ";

        List<Diff> diffs = jdbc.query("""
                        SELECT a.id, a.changed_at, f.key, m.display_name AS author,
                               m.login AS author_login, m.number AS author_number,
                        """ + bulk + was + """
                            AS was,
                        """ + now + """
                            AS now
                          FROM audit_log a
                          CROSS JOIN unnest(string_to_array(?, ',')) AS f(key)
                          LEFT JOIN tenant_member m ON m.id = a.changed_by
                         WHERE a.table_name IN ('part', 'part_drom_card') AND a.record_id = ?
                           -- Блоки карточки Дрома заводятся строкой при первой правке
                           -- (INSERT): без этого первое заполнение в ленте не видно.
                           AND (a.operation = 'UPDATE'
                                OR (a.operation = 'INSERT' AND a.table_name = 'part_drom_card'))
                           AND\s""" + was + " IS DISTINCT FROM " + now + """

                         ORDER BY a.changed_at DESC, a.id DESC,
                                  array_position(string_to_array(?, ','), f.key)""",
                (rs, i) -> new Diff(rs.getLong("id"),
                        rs.getTimestamp("changed_at").toInstant(),
                        authorOf(rs),
                        rs.getString("key"),
                        rs.getString("was"),
                        rs.getString("now"),
                        bulkOf(rs.getString("bulk_size"), rs.getString("bulk_scope"))),
                columns, partId, columns);

        Map<String, Map<Long, String>> titles = resolveReferences(diffs);

        Map<Long, Change> byAudit = new LinkedHashMap<>();
        for (Diff diff : diffs) {
            Change change = byAudit.computeIfAbsent(diff.auditId(),
                    id -> new Change(diff.at(), diff.author(), null, new ArrayList<>(),
                            diff.bulk()));
            Map<Long, String> lookup = titles.getOrDefault(diff.column(), Map.of());
            change.fields().add(new Field(labelOf(diff.column()),
                    AuditedColumns.display(diff.column(), diff.was(), lookup),
                    AuditedColumns.display(diff.column(), diff.now(), lookup),
                    delta(diff.column(), diff.was(), diff.now())));
        }

        List<Change> changes = new ArrayList<>(byAudit.values());
        changes.addAll(applicabilityChanges(partId));
        // Правки идут от свежих к старым; сортировка устойчивая, и при равном
        // моменте остаётся порядок запроса.
        changes.sort(java.util.Comparator.comparing(Change::at).reversed());
        created(partId).ifPresent(changes::add);
        return changes;
    }

    /**
     * Автор правки — логином, именем и видимым номером (задача 0373, решение
     * владельца 23.10.2026: «мы должны идентифицировать сотрудника на 100%»).
     * Подпись собирает {@code MemberSignature}, одна на проект.
     *
     * <p>Лента движений подписана по-прежнему именем: решение касалось
     * «Истории изменений», а не движений склада.
     */
    private static String authorOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return MemberSignature.of(rs.getString("author"), rs.getString("author_login"),
                (Long) rs.getObject("author_number"));
    }

    /**
     * Насколько подвинулись деньги: «5 000 → 4 500 (−10 %)».
     *
     * <p><b>Зачем.</b> Правку цены смотрят затем, чтобы понять, почему деталь
     * ушла дешевле, — и два числа сами по себе на это не отвечают: «27 000 →
     * 24 300» надо делить в уме, а разбираются с этим по два десятка строк
     * за раз. Процент виден сразу и одинаково читается у детали за тысячу
     * и за сто тысяч.
     *
     * <p><b>Это посчитанная разница, а не записанная операция, и разница
     * между ними важна.</b> Какой операцией владелец двигал цену — процентом
     * или новым числом, — не хранится нигде: {@code audit_log} кладёт снимки
     * строки, и своего поля под «чем правили» у него нет. Поэтому строка
     * говорит ровно то, что можно доказать двумя снимками: цена стала ниже
     * на десять процентов. Утверждать «нажали „Уменьшить на %“» мы права
     * не имеем — то же правило, по которому не подставляется автор
     * и не выдумывается роль.
     *
     * <p>От пустого значения процент не считается: доли от «не заполнено»
     * не бывает, и «+∞ %» на месте первой заведённой цены — это шум.
     */
    private static String delta(String column, String was, String now) {
        if (!AMOUNTS.contains(column) || was == null || now == null) {
            return null;
        }
        BigDecimal before;
        BigDecimal after;
        try {
            before = new BigDecimal(was);
            after = new BigDecimal(now);
        } catch (NumberFormatException notANumber) {
            return null;
        }
        if (before.signum() == 0) {
            return null;
        }
        BigDecimal percent = after.subtract(before)
                .multiply(HUNDRED)
                .divide(before, 1, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros();
        if (percent.signum() == 0) {
            return null;
        }
        String number = percent.abs().toPlainString().replace('.', ',');
        return (percent.signum() < 0 ? "−" : "+") + number + " %";
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Поля, у которых процент разницы что-то значит: деньги, а не вес и не габарит. */
    private static final Set<String> AMOUNTS =
            Set.of("price", "min_price", "cost_price", "installation_price");

    private List<String> visibleFields(boolean money) {
        return java.util.stream.Stream.concat(
                        AuditedColumns.PART.keySet().stream(),
                        AuditedColumns.PART_DROM_CARD.keySet().stream())
                .filter(column -> money || !AuditedColumns.MONEY.contains(column))
                .toList();
    }

    /** Слово колонки: поле позиции либо блок карточки Дрома (задача 0285). */
    private static String labelOf(String column) {
        String label = AuditedColumns.PART.get(column);
        return label != null ? label : AuditedColumns.PART_DROM_CARD.get(column);
    }

    /**
     * «Применимость: → Nissan Teana 2003—2008» (задача 0363, как у Bazon): заведение и
     * снятие машины в применимости позиции. Берётся из журнала строк применимости
     * ({@code AuditJournal.created/deleted}), где машина названа словами на момент записи:
     * строки уже может не быть. Отбор по {@code part_id} из снимка, а не по номеру строки —
     * снятая строка по номеру уже не найти.
     */
    private List<Change> applicabilityChanges(long partId) {
        return jdbc.query("""
                        SELECT a.changed_at, a.operation, m.display_name AS author,
                               m.login AS author_login, m.number AS author_number,
                               coalesce(a.new_value, a.old_value) ->> 'vehicle' AS vehicle
                          FROM audit_log a
                          LEFT JOIN tenant_member m ON m.id = a.changed_by
                         WHERE a.table_name = 'part_applicability'
                           AND a.operation IN ('INSERT', 'DELETE')
                           AND coalesce(a.new_value, a.old_value) ->> 'part_id' = ?
                         ORDER BY a.changed_at DESC, a.id DESC""",
                (rs, i) -> {
                    boolean added = "INSERT".equals(rs.getString("operation"));
                    String vehicle = rs.getString("vehicle");
                    return new Change(rs.getTimestamp("changed_at").toInstant(),
                            authorOf(rs), null,
                            List.of(new Field("Применимость",
                                    added ? null : vehicle, added ? vehicle : null, null)),
                            null);
                },
                String.valueOf(partId));
    }

    /**
     * Заведение карточки — последней строкой ленты: она читается сверху вниз,
     * от свежего к старому.
     */
    private java.util.Optional<Change> created(long partId) {
        return jdbc.query("""
                        SELECT a.changed_at, m.display_name AS author,
                               m.login AS author_login, m.number AS author_number
                          FROM audit_log a
                          LEFT JOIN tenant_member m ON m.id = a.changed_by
                         WHERE a.table_name = 'part' AND a.record_id = ?
                           AND a.operation = 'INSERT'
                         ORDER BY a.changed_at LIMIT 1""",
                (rs, i) -> new Change(rs.getTimestamp("changed_at").toInstant(),
                        authorOf(rs), "Товар создан", List.of(), null),
                partId).stream().findFirst();
    }

    /**
     * Имена вместо идентификаторов — по одному запросу на вид ссылки,
     * а не по запросу на строку: правок у позиции бывают десятки.
     */
    private Map<String, Map<Long, String>> resolveReferences(List<Diff> diffs) {
        Map<String, Set<Long>> wanted = new HashMap<>();
        for (Diff diff : diffs) {
            if (!AuditedColumns.REFERENCES.contains(diff.column())) {
                continue;
            }
            Set<Long> ids = wanted.computeIfAbsent(diff.column(), k -> new LinkedHashSet<>());
            addId(ids, diff.was());
            addId(ids, diff.now());
        }

        Map<String, Map<Long, String>> titles = new HashMap<>();
        wanted.forEach((column, ids) -> titles.put(column, AuditedColumns.titlesOf(jdbc, column, ids)));
        return titles;
    }

    private static void addId(Set<Long> into, String raw) {
        if (raw == null) {
            return;
        }
        try {
            into.add(Long.parseLong(raw));
        } catch (NumberFormatException ignored) {
            // Не идентификатор — покажется как есть.
        }
    }

    /**
     * Словарь названий, терпимый к пустоте.
     *
     * <p>{@code Map.of} — неизменяемая карта, и она бросает
     * {@code NullPointerException} даже на чтении по {@code null}-ключу,
     * а не отвечает «нет такого». У движения перенесённого склада статуса
     * документа нет вовсе, и вся история падала пятисоткой на первой же
     * такой строке. Поймано тестом, а не чтением кода.
     */
    private static String label(Map<String, String> dictionary, String key) {
        if (key == null) {
            return null;
        }
        return dictionary.getOrDefault(key, key);
    }

    // --------------------------------------------------------------- движения

    private List<Movement> movements(long partId, boolean mayDelete) {
        return jdbc.query("""
                        SELECT m.created_at, m.movement_type, m.qty_delta,
                               -- Причина списания лежит в документе, а не
                               -- в движении: списание оформляется документом,
                               -- и «почему» пишется там. Пока история читала
                               -- только m.reason, у списания в ленте стоял
                               -- прочерк — при том что причина обязательна
                               -- и её только что ввели руками. Единственная
                               -- операция, уносящая товар без покупателя
                               -- и без денег, и «почему» через месяц
                               -- не восстановить ничем, кроме этой строки.
                               coalesce(m.reason, d.note) AS reason,
                               d.id AS doc_id, d.number AS doc_number, d.doc_type,
                               d.status AS doc_status, d.deleted_at AS doc_deleted_at,
                               m.from_warehouse_id, m.to_warehouse_id,
                               wf.name AS from_warehouse, wt.name AS to_warehouse,
                               -- Полки нужны перестановке: у неё склад один
                               -- и тот же, и «Основной → Основной» не говорит
                               -- ничего — а сказать надо, с какой полки
                               -- на какую.
                               cf.code AS from_cell, ct.code AS to_cell,
                               deal.number AS deal_number, ret.number AS return_number,
                               -- Цена у продажи — цена строки сделки, как у
                               -- Bazon в колонке «Цена»; у складских документов
                               -- цены нет, и клетка пустая, а не ноль.
                               (SELECT di.price FROM deal_item di
                                 WHERE m.ref_type = 'DEAL' AND di.deal_id = m.ref_id
                                   AND di.part_id = m.part_id
                                 ORDER BY di.id LIMIT 1) AS price,
                               inv.id AS inventory_id,
                               who.display_name AS author
                          FROM stock_movement m
                          LEFT JOIN stock_document d  ON d.id = m.document_id
                          LEFT JOIN warehouse wf      ON wf.id = m.from_warehouse_id
                          LEFT JOIN warehouse wt      ON wt.id = m.to_warehouse_id
                          LEFT JOIN storage_cell cf   ON cf.id = m.from_cell_id
                          LEFT JOIN storage_cell ct   ON ct.id = m.to_cell_id
                          LEFT JOIN deal              ON m.ref_type = 'DEAL' AND deal.id = m.ref_id
                          LEFT JOIN deal_return ret   ON m.ref_type = 'RETURN' AND ret.id = m.ref_id
                          -- Недостача объясняется пересчётом: без этого в ленте
                          -- стоит «Корректировка −2» и больше ничего.
                          LEFT JOIN inventory_session inv ON m.ref_type = 'INVENTORY'
                                                         AND inv.id = m.ref_id
                          LEFT JOIN tenant_member who ON who.id = m.created_by
                         WHERE m.part_id = ?
                         ORDER BY m.created_at DESC, m.id DESC""",
                (rs, i) -> {
                    String type = label(MOVEMENTS, rs.getString("movement_type"));
                    String document = documentOf(rs.getObject("doc_number"), rs.getString("doc_type"),
                            rs.getObject("deal_number"), rs.getObject("return_number"),
                            rs.getObject("inventory_id"));
                    boolean deleted = rs.getTimestamp("doc_deleted_at") != null;
                    // Кнопку «Удалить» экран рисует по этому признаку, а не по
                    // своему списку ролей: право одно — PostedDocumentDeletion,
                    // правило «что удаляется» одно — StockDocument.isDeletable.
                    boolean removable = StockDocument.isDeletable(
                            rs.getString("doc_type"), rs.getString("doc_status"), deleted);
                    boolean deletable = mayDelete && removable;
                    return new Movement(
                            rs.getTimestamp("created_at").toInstant(),
                            type,
                            rs.getBigDecimal("qty_delta"),
                            // Колонки «Что» больше нет (решение 7 владельца,
                            // 23.10.2026): у движения без документа — перенос,
                            // перестановка — вид встаёт на место документа,
                            // иначе строка не говорит, что произошло.
                            document != null ? document : type,
                            statusOf(rs.getString("doc_status"), deleted,
                                    rs.getObject("deal_number"), rs.getObject("return_number"),
                                    rs.getObject("inventory_id")),
                            placeOf(rs.getString("from_warehouse"), rs.getString("to_warehouse"),
                                    (Long) rs.getObject("from_warehouse_id"),
                                    (Long) rs.getObject("to_warehouse_id"),
                                    rs.getString("from_cell"), rs.getString("to_cell")),
                            rs.getString("reason"),
                            rs.getString("author"),
                            false,
                            rs.getBigDecimal("price"),
                            deleted,
                            deletable ? (Long) rs.getObject("doc_id") : null,
                            removable && !mayDelete ? PostedDocumentDeletion.NEEDS : null);
                },
                partId);
    }

    /**
     * Статус строки — своей колонкой, словами Bazon: Черновик · Резерв · Выдан ·
     * Выполнен · «Выполнен (Удалён)». У документа склада он свой, у выдачи
     * и возврата документа нет, и слово даёт сама операция («Выдан»,
     * «Выполнен»); выполненный пересчёт — тоже «Выполнен».
     *
     * <p>Удалённый документ (задача 0367) — «Выполнен (Удалён)» у обеих его
     * строк: и у исходного движения, и у встречного. Статус документа при
     * этом остаётся {@code DONE} ({@code tenant/098}), и без отметки
     * удалённое читалось бы обычным выполненным.
     */
    private static String statusOf(String docStatus, boolean deleted, Object dealNumber,
                                   Object returnNumber, Object inventoryId) {
        if (docStatus != null) {
            String word = label(DOC_STATUSES, docStatus);
            return deleted ? word + DELETED_SUFFIX : word;
        }
        if (dealNumber != null) {
            return "Выдан";
        }
        return returnNumber != null || inventoryId != null ? "Выполнен" : null;
    }

    /**
     * Позиции сделок, которые держат товар, — строками ленты движений.
     *
     * <p>Время — момент, когда позиция попала в сделку ({@code deal_item.added_at},
     * {@code tenant/099}), а не заведение сделки: «время у каждого товара своё»
     * (решение 8 владельца, 23.10.2026). Перенесённая в другую сделку позиция
     * сохраняет момент попадания в первую: с тех пор она и отложена.
     */
    private List<Movement> heldByDeals(long partId) {
        return jdbc.query("""
                        SELECT i.added_at, d.number AS deal_number, i.status, i.quantity, i.price,
                               w.name AS warehouse, who.display_name AS author
                          FROM deal_item i
                          JOIN deal d ON d.id = i.deal_id
                          LEFT JOIN warehouse w ON w.id = i.warehouse_id
                          LEFT JOIN tenant_member who ON who.id = d.manager_id
                         WHERE i.part_id = ? AND i.status IN ('DRAFT', 'RESERVED', 'PREORDER')
                         ORDER BY i.added_at DESC, i.id DESC""",
                (rs, i) -> new Movement(
                        rs.getTimestamp("added_at").toInstant(),
                        "Сделка",
                        rs.getBigDecimal("quantity"),
                        "Сделка №" + rs.getObject("deal_number"),
                        label(HELD_ITEMS, rs.getString("status")),
                        rs.getString("warehouse"),
                        null,
                        rs.getString("author"),
                        true,
                        rs.getBigDecimal("price"),
                        false,
                        null,
                        null),
                partId);
    }

    /**
     * Чем движение оформлено.
     *
     * <p>У перенесённого склада документа нет вовсе: импорт пишет движение
     * напрямую, потому что документа в чужой выгрузке не было. Прочерк тут
     * честнее выдуманного номера.
     */
    private static String documentOf(Object docNumber, String docType,
                                     Object dealNumber, Object returnNumber,
                                     Object inventoryId) {
        if (docNumber != null) {
            return "%s №%s".formatted(label(DOCUMENTS, docType), docNumber);
        }
        if (dealNumber != null) {
            return "Сделка №" + dealNumber;
        }
        if (returnNumber != null) {
            return "Возврат №" + returnNumber;
        }
        if (inventoryId != null) {
            return "Пересчёт №" + inventoryId;
        }
        return null;
    }

    /**
     * Где это произошло: склад, а у перестановки — ещё и обе полки.
     *
     * <p>Перестановка на другую полку того же склада записана движением
     * с одинаковыми складами, и «Основной → Основной» не говорит ничего.
     * Между тем это единственное место, где след перекладки виден человеку
     * у позиции, лежащей на двух складах: лента правок про неё молчит
     * намеренно — `part.storage_cell_id` одно поле на весь товар, и правка
     * его сравнила бы полку одного склада с полкой другого.
     *
     * <p>Склады сравниваются по номеру, а не по названию: два склада могут
     * называться одинаково, и «Ткацкая → Ткацкая» тогда стало бы
     * перестановкой, которой не было.
     */
    private static String placeOf(String from, String to, Long fromId, Long toId,
                                  String fromCell, String toCell) {
        if (fromId != null && fromId.equals(toId)) {
            return "%s · %s → %s".formatted(from, cellName(fromCell), cellName(toCell));
        }
        if (from != null && to != null) {
            return from + " → " + to;
        }
        return from != null ? from : to;
    }

    /**
     * Пусто — «без адреса», а не прочерк: у клиента без полок ячеек нет
     * вовсе, и это «не заведено», а не «не знаем». Тем же словом называет
     * пустой адрес карточка.
     */
    private static String cellName(String code) {
        return code == null ? "без адреса" : code;
    }

    // ----------------------------------------------------------------- ответы

    /**
     * @param intake номер поступления, которым позиция пришла (колонка
     *               «Поступление» у Bazon); {@code null} — документа прихода
     *               нет (перенос) или он удалён
     * @param supply поставка словами; {@code null} — не указана
     */
    public record History(List<Change> changes, List<Movement> movements,
                          String intake, String supply) {
    }

    /**
     * @param author  подпись сотрудника: имя, логин и видимый номер —
     *                «Пётр Массов (vladelec, ID 3)» (задача 0373).
     *                {@code null} у правок, сделанных до того, как приложение
     *                начало сообщать базе вошедшего, и у всего, что приехало
     *                переносом. Врать тут нельзя — экран показывает прочерк
     * @param action  заполнен у событий без полей («Товар создан»)
     * @param bulk    правка была частью массовой (задача 0373); {@code null} —
     *                правка одной карточки, либо сделана до того, как массовая
     *                правка начала себя помечать, — отличить их нечем, и прочерк
     *                здесь честнее догадки
     */
    public record Change(Instant at, String author, String action, List<Field> fields,
                         Bulk bulk) {
    }

    /**
     * Объём массовой правки: «всего были отредактированы 40 товаров по фильтру».
     *
     * @param size  сколько позиций изменила правка
     * @param scope {@code FILTER} — всё, что попало в отбор витрины;
     *              {@code SELECTED} — отмеченные строки. Слова ставит экран
     */
    public record Bulk(int size, String scope) {
    }

    /**
     * Пометка массовой правки из снимка, терпимая к пустоте и к мусору:
     * снимок пишет слушатель журнала, а прочесть его обязан и старый,
     * и чужой — запись, которую не разобрать, остаётся обычной правкой.
     */
    private static Bulk bulkOf(String size, String scope) {
        if (size == null || scope == null) {
            return null;
        }
        try {
            return new Bulk(Integer.parseInt(size), scope);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * @param delta насколько подвинулись деньги — «−10 %», «+5 %».
     *              {@code null} у всего, что деньгами не является, и у правки
     *              от пустого значения: процент от «не заполнено» не считается
     */
    public record Field(String label, String before, String after, String delta) {
    }

    /**
     * @param type     вид словом; на экране не показывается отдельной колонкой
     *                 (решение 7, задача 0367) — у строки без документа он стоит
     *                 в {@code document}
     * @param held     строка не двигала остаток, а держит его или ждёт (задача 0367):
     *                 количество у такой строки без знака, а экран не рисует «+»
     * @param price    цена строки сделки — у продажи и у позиции, держащей товар;
     *                 {@code null} у складских документов
     * @param deleted  документ строки удалён (задача 0367) — «Выполнен (Удалён)»
     *                 красным
     * @param deletableDocumentId документ строки, который вошедший может удалить;
     *                 {@code null} — удалить нельзя или нечем (нет права, нет
     *                 документа, не тот вид, уже удалён)
     * @param deleteNeeds право, которого не хватает, чтобы удалить документ строки,
     *                 который удалить можно в принципе: экран рисует «Удалить»
     *                 погашенной с причиной, а не прячет (решение владельца
     *                 23.10.2026, задача 0044, вопрос 3); {@code null} — либо
     *                 удалить можно ({@code deletableDocumentId}), либо нечего
     */
    public record Movement(Instant at, String type, BigDecimal qty, String document,
                           String status, String warehouse, String reason, String author,
                           boolean held, BigDecimal price, boolean deleted,
                           Long deletableDocumentId, String deleteNeeds) {
    }

    private record Diff(long auditId, Instant at, String author,
                        String column, String was, String now, Bulk bulk) {
    }
}
