package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Шины и диски.
 *
 * <p><b>Это товар с типом, а не отдельная сущность.</b> Склад, резерв,
 * продажа, возврат и инвентаризация написаны на {@code part}, и второй вид
 * товара со своим путём через всё это разошёлся бы с первым на первой же
 * правке. Колесо — это строка {@code part} с {@code product_line = WHEEL}
 * и свойствами в {@code part_wheel}.
 *
 * <p><b>Заводятся комплектом, продаются поштучно.</b> На разборке снимают
 * четыре колеса разом, и заводить их по одному значит четырежды повторить
 * одни и те же двенадцать полей. Но покупатель берёт и одно — запаску, —
 * поэтому каждое колесо остаётся отдельной карточкой со своим остатком,
 * а комплект их только связывает.
 *
 * <p>Заголовок собирается из свойств, как у запчасти из эталона: «Шина
 * 195/65 R15 Goodyear EfficientGrip летняя». Написание руками дало бы
 * у одного клиента «195/65R15» и «195 65 15» на соседних строках.
 */
@Service
public class WheelService {

    private final JdbcTemplate jdbc;

    private final PartChangeLog partChanges;
    private final StockLedger ledger;
    private final ru.partsflow.catalog.VehicleWords vehicleWords;

    public WheelService(JdbcTemplate jdbc, PartChangeLog partChanges, StockLedger ledger,
                        ru.partsflow.catalog.VehicleWords vehicleWords) {
        this.partChanges = partChanges;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.vehicleWords = vehicleWords;
    }

    /**
     * Виды товара колёсной линии.
     *
     * <p>Один список на отбор и на заведение: разойдясь, они дали бы вид,
     * который отбор пускает, а заведение нет. В колонке стоит CHECK, но
     * доводить до него значение из запроса незачем — оттуда оно возвращается
     * как «операция нарушает целостность данных», то есть человеку неясно,
     * что он ввёл не то, а офлайн-очередь читает такое как повод повторять.
     */
    private static final java.util.Set<String> KINDS =
            java.util.Set.of("TYRE", "DISC", "ASSEMBLY");

    private static String requireKind(String kind) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException(
                    "Неизвестный вид товара: " + kind + ". Допустимы " + KINDS);
        }
        return kind;
    }

    /**
     * Остальные свойства колеса проверяются тем же списком, каким
     * показываются.
     *
     * <p>Белый список стоял только у вида товара, а у сезона, состояния,
     * маркировки и протектора — нет, хотя болезнь одна и уже описана выше:
     * значение доезжает до `CHECK` в колонке и возвращается как «операция
     * нарушает целостность данных». Человеку по такому ответу неясно, что
     * он ввёл не то, — он идёт искать поломку сервера, — а офлайн-очередь
     * читает 409 как повод повторять. Поймано попыткой завести комплект
     * с сезоном `WINTER_STUD` вместо `WINTER_STUDDED`: разница в четыре
     * буквы, ответ — про целостность данных.
     *
     * <p>Сверяется с тем же словарём, которым значение показывают
     * на экране: отдельный список разошёлся бы с показом на первой же
     * правке, и появилось бы значение, которое одна сторона пускает,
     * а другая нет.
     */
    private static void requireKnown(String field, String value, Map<String, String> allowed) {
        if (value != null && !allowed.containsKey(value)) {
            throw new IllegalArgumentException(
                    "Неизвестное значение поля «%s»: %s. Допустимы %s"
                            .formatted(field, value, allowed.keySet()));
        }
    }

    /**
     * Заводит комплект: {@code quantity} одинаковых колёс под общим номером.
     *
     * @return номера заведённых карточек
     */
    @Transactional
    public Created createSet(WheelRequest request, int quantity, Long warehouseId,
                             Long createdBy) {
        if (quantity < 1 || quantity > 8) {
            // Восемь — это две оси на грузовике. Больше означает опечатку
            // в поле количества, а комплект из сорока колёс придётся
            // разбирать руками.
            throw new IllegalArgumentException("Комплект — от одного до восьми колёс");
        }
        if (warehouseId == null) {
            throw new IllegalArgumentException("Не указан склад");
        }
        requireKind(request.kind());
        requireKnown("Сезон", request.season(), SEASONS);
        requireKnown("Состояние", request.condition(), CONDITIONS);
        requireKnown("Тип маркировки", request.markingType(), MARKING);
        requireKnown("Тип протектора", request.treadType(), TREAD);

        Integer setNo = quantity > 1
                ? jdbc.queryForObject("SELECT nextval('wheel_set_no_seq')", Integer.class)
                : null;

        String title = titleOf(request);
        List<Long> ids = new java.util.ArrayList<>();

        for (int at = 0; at < quantity; at++) {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, product_line,
                                      condition, created_by)
                    VALUES (1, ?, ?, ?, 'WHEEL', COALESCE(?, 'USED'), ?)
                    RETURNING id""",
                    Long.class, title, request.price(), request.costPrice(),
                    request.condition(), createdBy);

            jdbc.update("""
                    INSERT INTO part_wheel (part_id, kind, set_no, diameter,
                                            tyre_width, tyre_height, construction, tyre_type,
                                            season, wear_mm, made_year,
                                            disc_type, disc_width, offset_mm, bolt_pattern,
                                            hub_bore, brand, model, disc_brand, disc_model,
                                            marking_type, tread_type, run_flat, light_truck,
                                            speed_index, load_index)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?)""",
                    partId, request.kind(), setNo, request.diameter(),
                    request.tyreWidth(), request.tyreHeight(), request.construction(),
                    request.tyreType(), request.season(), request.wearMm(), request.madeYear(),
                    request.discType(), request.discWidth(), request.offsetMm(),
                    request.boltPattern(), request.hubBore(), request.brand(), request.model(),
                    request.discBrand(), request.discModel(),
                    request.markingType(), request.treadType(), request.runFlat(),
                    request.lightTruck(), request.speedIndex(), request.loadIndex());

            // Остаток появляется только движением, и записать его мало —
            // применить движение к раскладке и карточке должен тот же вызов.
            StockMovement intake = StockMovement.intake(
                    partId, java.math.BigDecimal.ONE, warehouseId, null);
            intake.setCreatedBy(createdBy);
            ledger.record(intake);

            ids.add(partId);
        }
        // Колёса в прайс запчастей не идут — там свой вид товара, — но отметка
        // ставится всё равно: выгрузка для шин и дисков появится, и лишний
        // список мест, который надо не забыть дополнить, никому не нужен.
        partChanges.changed(ids);
        return new Created(setNo, title, ids);
    }

    /**
     * Витрина колёс: то, что видно на вкладке «Шины и диски».
     *
     * <p>Отдельно от поиска запчастей: у колеса свои двенадцать свойств,
     * и показывать их в общем списке склада значит добавить каждой фаре
     * дюжину пустых колонок.
     */
    @Transactional(readOnly = true)
    public Page list(String query, String kind, boolean withMissing,
                     Map<String, String> columns, Map<String, String> words,
                     String sort, boolean descending, int page, int size) {
        Filter filter = filterOf(query, kind, withMissing, columns, words);

        // Общее число — не украшение: страница отдаёт полсотни строк,
        // и без него на экране нельзя ни написать «812 товаров», ни понять,
        // сколько страниц. Пока его не было, вкладка брала пятьсот строк
        // разом и считала товары длиной ответа: у клиента с бо́льшим складом
        // счётчик врал ровно на то, чего не видно, а до остального было
        // не добраться вовсе.
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM part p
                  JOIN part_wheel w ON w.part_id = p.id
                  LEFT JOIN part_name pn ON pn.id = p.part_name_id
                  LEFT JOIN donor d ON d.id = p.donor_id
                  LEFT JOIN supply s ON s.id = p.supply_id
                """ + filter.where(), Long.class, filter.args().toArray());

        List<Object> args = new java.util.ArrayList<>(filter.args());
        args.add(size);
        args.add((long) page * size);

        List<WheelRow> rows = jdbc.query("""
                SELECT p.id, p.public_code, p.title, p.price, p.status, p.qty_on_hand,
                       p.condition, p.description, p.note, p.section, p.is_published,
                       p.barcode, p.legacy_code, p.created_at, p.updated_at,
                       p.price_changed_at,
                       w.kind, w.set_no, w.diameter, w.tyre_width, w.tyre_height,
                       w.construction, w.tyre_type, w.season, w.wear_mm, w.made_year,
                       w.disc_type, w.disc_width, w.offset_mm, w.bolt_pattern, w.hub_bore,
                       w.brand, w.model, w.disc_brand, w.disc_model,
                       w.marking_type, w.tread_type, w.run_flat, w.light_truck,
                       w.speed_index, w.load_index,
                       pn.name AS part_name,
                       d.legacy_code AS donor_legacy, d.public_code AS donor_code,
                       (SELECT tm.display_name FROM tenant_member tm
                         WHERE tm.id = p.updated_by)       AS updated_by_name,
                       (SELECT tm.display_name FROM tenant_member tm
                         WHERE tm.id = p.price_changed_by) AS price_changed_by_name,
                       (SELECT count(*) FROM part_photo ph2
                         WHERE ph2.part_id = p.id AND ph2.status = 'PROCESSED')         AS photo_count,
                       (SELECT o.raw_number FROM part_oem o
                         WHERE o.part_id = p.id AND o.is_primary LIMIT 1) AS oem,
                       CASE WHEN s.id IS NULL THEN NULL
                            ELSE s.kind || ' №' || s.number
                                 || coalesce(' | ' || to_char(s.arrived_on, 'DD.MM.YYYY'), '')
                       END AS supply,
                       -- Только подтверждённый снимок: у оборванной загрузки
                       -- и у неподтверждённой записи файла в хранилище нет,
                       -- и в колонке «Превью» висит битая картинка. Прайс
                       -- и карточка это уже фильтруют, витрины — нет,
                       -- и поймано это глазами, а не тестом.
                       (SELECT ph.s3_key FROM part_photo ph
                         WHERE ph.part_id = p.id AND ph.status = 'PROCESSED'
                         ORDER BY ph.is_main DESC, ph.sort_order,
                               ph.id LIMIT 1) AS photo_key
                  FROM part p
                  JOIN part_wheel w ON w.part_id = p.id
                  LEFT JOIN part_name pn ON pn.id = p.part_name_id
                  LEFT JOIN donor d ON d.id = p.donor_id
                  LEFT JOIN supply s ON s.id = p.supply_id
                """ + filter.where() + orderOf(sort, descending) + " LIMIT ? OFFSET ?",
                (rs, i) -> new WheelRow(
                        rs.getLong("id"), rs.getString("public_code"), rs.getString("title"),
                        rs.getBigDecimal("price"), rs.getString("status"),
                        rs.getBigDecimal("qty_on_hand"),
                        rs.getString("kind"), (Integer) rs.getObject("set_no"),
                        rs.getBigDecimal("diameter"),
                        (Integer) rs.getObject("tyre_width"), (Integer) rs.getObject("tyre_height"),
                        rs.getString("construction"), rs.getString("tyre_type"),
                        rs.getString("season"), rs.getBigDecimal("wear_mm"),
                        (Integer) rs.getObject("made_year"),
                        rs.getString("disc_type"), rs.getBigDecimal("disc_width"),
                        (Integer) rs.getObject("offset_mm"), rs.getString("bolt_pattern"),
                        rs.getBigDecimal("hub_bore"),
                        rs.getString("brand"), rs.getString("model"),
                        rs.getString("disc_brand"), rs.getString("disc_model"),
                        rs.getString("marking_type"), rs.getString("tread_type"),
                        (Boolean) rs.getObject("run_flat"), (Boolean) rs.getObject("light_truck"),
                        rs.getString("speed_index"), (Integer) rs.getObject("load_index"),
                        rs.getString("part_name"), rs.getString("condition"),
                        rs.getString("supply"),
                        rs.getString("donor_legacy") != null
                                ? rs.getString("donor_legacy") : rs.getString("donor_code"),
                        rs.getString("oem"),
                        rs.getString("description"), rs.getString("note"), rs.getString("section"),
                        rs.getBoolean("is_published"), rs.getString("barcode"),
                        rs.getString("legacy_code"), rs.getInt("photo_count"),
                        instant(rs, "created_at"), instant(rs, "updated_at"),
                        rs.getString("updated_by_name"),
                        instant(rs, "price_changed_at"), rs.getString("price_changed_by_name"),
                        rs.getString("photo_key"),
                        Map.of()),
                args.toArray());
        return new Page(total == null ? 0 : total, withStock(rows));
    }

    /** @param total сколько всего под отбором, а не сколько строк на странице */
    public record Page(long total, List<WheelRow> rows) {
    }

    /**
     * Отбор витрины колёс.
     *
     * <p>Поиск по номеру товара и наименованию: владелец ищет одно и то же
     * двумя способами, и спрашивать, что именно он ввёл, значит заставить его
     * выбирать вкладку перед каждым поиском. Размер сюда же попадает сам —
     * он собран в заголовок («Шина 195/65 R15 …»), а покупатель называет
     * именно его.
     *
     * <p>Отбор по виду товара нужен ровно потому, что таблица одна на шины
     * и диски: половина колонок у второго вида пуста, и «покажи только диски»
     * — первое, что делает кладовщик, когда ищет комплект железа.
     */
    private Filter filterOf(String query, String kind, boolean withMissing,
                            Map<String, String> columns, Map<String, String> words) {
        StringBuilder where = new StringBuilder(" WHERE p.product_line = 'WHEEL'");
        List<Object> args = new java.util.ArrayList<>();

        if (query != null && !query.isBlank()) {
            // Размер разбирается из запроса и ищется по полям, а не по тексту
            // заголовка: покупатель называет «225 55 18», а в заголовке стоит
            // «225/55 R18», и по буквам это не совпадает. Нераспознанное
            // остаётся текстом — «Dunlop зимняя» так и ищется словами.
            // Марка приводится к латинскому написанию до разбора размера:
            // «бриджстоун 225 55 18» — обычный запрос по телефону, и словарь
            // тут тот же, что у машин.
            WheelSizeQuery size = WheelSizeQuery.parse(vehicleWords.translate(query));

            if (size.tyreWidth() != null) {
                where.append(" AND w.tyre_width = ?");
                args.add(size.tyreWidth());
            }
            if (size.tyreHeight() != null) {
                where.append(" AND w.tyre_height = ?");
                args.add(size.tyreHeight());
            }
            if (size.diameter() != null) {
                where.append(" AND w.diameter = ?");
                args.add(size.diameter());
            }
            if (size.discWidth() != null) {
                where.append(" AND w.disc_width = ?");
                args.add(size.discWidth());
            }
            if (size.boltPattern() != null) {
                // Приведение раскладки уже сделано разбором, поэтому
                // сравниваем без учёта регистра, но точно.
                where.append(" AND w.bolt_pattern ILIKE ?");
                args.add(size.boltPattern());
            }
            if (size.offsetMm() != null) {
                where.append(" AND w.offset_mm = ?");
                args.add(size.offsetMm());
            }
            if (size.text() != null) {
                // Словами, а не одной подстрокой. «Bridgestone зимняя» —
                // обычный запрос по телефону, а в заголовке между ними стоит
                // модель: «Шина 225/55 R18 Bridgestone Blizzak зимняя (шипы)».
                // Целиком такая фраза не совпадает ни с чем, и поиск отдавал
                // ноль при том, что каждое слово по отдельности находило обе
                // шины. Продавец в этот момент отвечает «нет такого».
                //
                // Слова соединяются через AND: они сужают запрос, а не
                // расширяют его. Иначе «Dunlop зимняя» вернуло бы вдобавок
                // все летние Dunlop и все зимние чужих марок — выдачу,
                // которую продавец читает глазами.
                for (String word : size.text().trim().split("\\s+")) {
                    where.append(" AND (p.public_code ILIKE ? OR p.title ILIKE ?)");
                    String like = "%" + word + "%";
                    args.add(like);
                    args.add(like);
                }
            }
        }
        if (kind != null && !kind.isBlank()) {
            requireKind(kind);
            where.append(" AND w.kind = ?");
            args.add(kind);
        }
        if (!withMissing) {
            where.append(" AND p.qty_on_hand > 0");
        }

        // Отбор колонками, как в кабинете: у каждой колонки свой список
        // значений, встречающихся на складе, и владелец выбирает из него,
        // а не набирает руками. Свободный ввод тут хуже: набрав «16» там,
        // где на складе только пятнадцатые, человек получает пустую таблицу
        // и не понимает, ошибся он или товара нет.
        if (words != null) {
            // Вбитое в колонку руками ищется вхождением: владелец набирает
            // «Nok», а не выбирает «Nokian» из списка, — и это другой вопрос,
            // чем выбор значения. Точным равенством оно не нашло бы ничего.
            for (var entry : words.entrySet()) {
                String expression = FILTERS.get(entry.getKey());
                if (expression == null) {
                    throw new IllegalArgumentException(
                            "По этой колонке отбор не делается: " + entry.getKey());
                }
                where.append(" AND ").append(expression).append(" ILIKE ?");
                args.add("%" + entry.getValue().strip() + "%");
            }
        }

        if (columns != null) {
            for (var entry : columns.entrySet()) {
                String expression = FILTERS.get(entry.getKey());
                if (expression == null) {
                    throw new IllegalArgumentException(
                            "По этой колонке отбор не делается: " + entry.getKey());
                }
                String value = entry.getValue();
                if (EMPTY.equals(value)) {
                    // «Пустые значения» — это вопрос «где не заполнено»,
                    // и он нужен: незаполненный сезон у шины видно только так.
                    where.append(" AND coalesce(").append(expression).append(", '') = ''");
                } else if (PRESENT.equals(value)) {
                    where.append(" AND coalesce(").append(expression).append(", '') <> ''");
                } else {
                    where.append(" AND ").append(expression).append(" = ?");
                    args.add(value);
                }
            }
        }
        return new Filter(where.toString(), args);
    }

    private record Filter(String where, List<Object> args) {
    }

    /** Незаполненное поле и «заполнено хоть чем-то» — тоже ответы на вопрос. */
    public static final String EMPTY = "\u2014пусто\u2014";
    public static final String PRESENT = "\u2014не пусто\u2014";

    /**
     * Различные значения колонки — то, из чего владелец выбирает отбор.
     *
     * <p>Считаются по всему складу колёс, а не по текущей выдаче: список,
     * который сужается от уже поставленных фильтров, не даёт снять один
     * и поставить другой — выбранного значения в нём уже нет.
     *
     * <p>Выражение колонки то же, что и в отборе: список показывает «Шина»
     * и «летняя», а не {@code TYRE} и {@code SUMMER}, и отбирает по ним же.
     * Разойдись они — выбранное из списка значение не находило бы ничего.
     */
    @Transactional(readOnly = true)
    public List<String> values(String column) {
        String expression = FILTERS.get(column);
        if (expression == null) {
            throw new IllegalArgumentException("По этой колонке отбор не делается: " + column);
        }
        return jdbc.queryForList("""
                SELECT DISTINCT %s AS value
                  FROM part p
                  JOIN part_wheel w ON w.part_id = p.id
                  LEFT JOIN part_name pn ON pn.id = p.part_name_id
                  LEFT JOIN donor d ON d.id = p.donor_id
                  LEFT JOIN supply s ON s.id = p.supply_id
                 WHERE p.product_line = 'WHEEL' AND %s IS NOT NULL AND %s <> ''
                 ORDER BY value""".formatted(expression, expression, expression),
                String.class);
    }

    /**
     * Колонка → выражение, дающее ровно то, что видно на экране.
     *
     * <p>Белый список, а не подстановка пришедшего имени: это и защита
     * от внедрения SQL, и единственный способ не разойтись со списком
     * значений.
     *
     * <p>Числа приводятся через {@code trim_scale}: в базе диаметр лежит
     * как {@code 15.0}, а в таблице стоит «15», и отбор по «15.0» не нашёл бы
     * ничего.
     */
    private static final Map<String, String> FILTERS = Map.ofEntries(
            Map.entry("code", "p.public_code"),
            Map.entry("set", "trim_scale(w.set_no)::text"),
            Map.entry("kind", "CASE w.kind WHEN 'TYRE' THEN 'Шина'"
                    + " WHEN 'DISC' THEN 'Диск' ELSE 'Колесо' END"),
            Map.entry("diameter", "trim_scale(w.diameter)::text"),
            Map.entry("tyreType", "w.tyre_type"),
            Map.entry("tyreWidth", "trim_scale(w.tyre_width)::text"),
            Map.entry("markingType", "CASE w.marking_type WHEN 'METRIC' THEN 'Метрическая'"
                    + " WHEN 'INCH' THEN 'Дюймовая' WHEN 'FLOTATION' THEN 'Флотационная' END"),
            Map.entry("treadType", "CASE w.tread_type WHEN 'STANDARD' THEN 'Стандартный'"
                    + " WHEN 'ASYMMETRIC' THEN 'Асимметричный'"
                    + " WHEN 'DIRECTIONAL' THEN 'Направленный' END"),
            Map.entry("construction", "w.construction"),
            Map.entry("tyreHeight", "trim_scale(w.tyre_height)::text"),
            Map.entry("wear", "trim_scale(w.wear_mm)::text"),
            Map.entry("tyreBrand", "w.brand"),
            Map.entry("tyreModel", "w.model"),
            Map.entry("season", "CASE w.season WHEN 'SUMMER' THEN 'летняя'"
                    + " WHEN 'WINTER' THEN 'зимняя'"
                    + " WHEN 'WINTER_STUDDED' THEN 'зимняя (шипы)'"
                    + " WHEN 'WINTER_FRICTION' THEN 'зимняя (липучка)'"
                    + " WHEN 'ALL_SEASON' THEN 'всесезонная' END"),
            Map.entry("madeYear", "trim_scale(w.made_year)::text"),
            Map.entry("discType", "w.disc_type"),
            Map.entry("discWidth", "trim_scale(w.disc_width)::text"),
            Map.entry("offset", "trim_scale(w.offset_mm)::text"),
            Map.entry("bolt", "w.bolt_pattern"),
            Map.entry("hub", "trim_scale(w.hub_bore)::text"),
            Map.entry("discBrand", "w.disc_brand"),
            Map.entry("discModel", "w.disc_model"),
            Map.entry("oem", "(SELECT o.raw_number FROM part_oem o"
                    + " WHERE o.part_id = p.id AND o.is_primary LIMIT 1)"),
            Map.entry("price", "trim_scale(p.price)::text"),
            Map.entry("description", "p.description"),
            Map.entry("note", "p.note"),
            Map.entry("section", "p.section"),
            Map.entry("supply", "CASE WHEN s.id IS NULL THEN NULL"
                    + " ELSE s.kind || ' №' || s.number END"),
            Map.entry("partName", "pn.name"),
            Map.entry("condition", "CASE p.condition WHEN 'NEW' THEN 'новая'"
                    + " WHEN 'USED' THEN 'б/у' WHEN 'REFURBISHED' THEN 'восстановленная' END"),
            Map.entry("runFlat", "CASE WHEN w.run_flat THEN 'да' END"),
            Map.entry("lightTruck", "CASE WHEN w.light_truck THEN 'да' END"),
            Map.entry("speedIndex", "w.speed_index"),
            Map.entry("loadIndex", "trim_scale(w.load_index)::text"),
            Map.entry("donor", "coalesce(d.legacy_code, d.public_code)"),
            Map.entry("published", "CASE WHEN p.is_published THEN 'Везде' ELSE 'Нет' END"),
            Map.entry("legacy", "p.legacy_code"),
            Map.entry("barcode", "p.barcode"),
            Map.entry("updatedBy", "(SELECT tm.display_name FROM tenant_member tm"
                    + " WHERE tm.id = p.updated_by)"),
            Map.entry("priceChangedBy", "(SELECT tm.display_name FROM tenant_member tm"
                    + " WHERE tm.id = p.price_changed_by)"));

    /**
     * Пишет вкладку колёс в поток — всю, что прошла отбор.
     *
     * <p>Потоком, а не списком: причина та же, что у выгрузки склада —
     * собранные в памяти строки это сотни мегабайт на каждого скачивающего.
     * Колёс у клиента две сотни, а не тридцать пять тысяч, но второй способ
     * выгружать разошёлся бы с первым на первой же правке.
     *
     * <p>Курсор работает только внутри транзакции: при autoCommit Postgres
     * игнорирует {@code fetchSize} и вычитывает всё в память.
     *
     * <p>Отбор тот же, что и у страницы: скачанный файл обязан совпасть с тем,
     * что владелец видел на экране, — ради этой сверки он его и качает.
     */
    @Transactional(readOnly = true)
    public void export(String query, String kind, boolean withMissing,
                       Map<String, String> columns, Map<String, String> words,
                       String sort, boolean descending,
                       List<CatalogService.Warehouse> warehouses,
                       CatalogService.RowWriter writer) {

        Filter filter = filterOf(query, kind, withMissing, columns, words);

        StringBuilder stock = new StringBuilder();
        for (CatalogService.Warehouse warehouse : warehouses) {
            // Идентификатор подставляется текстом, и это безопасно: он пришёл
            // из нашей же таблицы, а не из запроса. Параметром колонку не задать.
            stock.append("""
                    ,
                           (SELECT sum(s.qty - s.qty_reserved) FROM part_stock s
                             WHERE s.part_id = p.id AND s.warehouse_id = %1$d) AS free_%1$d,
                           (SELECT sum(s.qty_reserved) FROM part_stock s
                             WHERE s.part_id = p.id AND s.warehouse_id = %1$d) AS res_%1$d"""
                    .formatted(warehouse.id()));
        }

        String sql = """
                SELECT p.public_code, p.title, p.price, p.condition, p.description,
                       p.note, p.section, p.is_published, p.barcode, p.legacy_code,
                       p.created_at, p.updated_at, p.price_changed_at,
                       w.kind, w.set_no, w.diameter, w.tyre_width, w.tyre_height,
                       w.construction, w.tyre_type, w.season, w.wear_mm, w.made_year,
                       w.disc_type, w.disc_width, w.offset_mm, w.bolt_pattern, w.hub_bore,
                       w.brand, w.model, w.disc_brand, w.disc_model,
                       w.marking_type, w.tread_type, w.run_flat,
                       w.light_truck, w.speed_index, w.load_index,
                       pn.name AS part_name,
                       d.legacy_code AS donor_legacy, d.public_code AS donor_code,
                       (SELECT tm.display_name FROM tenant_member tm
                         WHERE tm.id = p.updated_by)       AS updated_by_name,
                       (SELECT tm.display_name FROM tenant_member tm
                         WHERE tm.id = p.price_changed_by) AS price_changed_by_name,
                       (SELECT count(*) FROM part_photo ph
                         WHERE ph.part_id = p.id AND ph.status = 'PROCESSED') AS photo_count,
                       (SELECT o.raw_number FROM part_oem o
                         WHERE o.part_id = p.id AND o.is_primary LIMIT 1) AS oem,
                       CASE WHEN s.id IS NULL THEN NULL
                            ELSE s.kind || ' №' || s.number
                                 || coalesce(' | ' || to_char(s.arrived_on, 'DD.MM.YYYY'), '')
                       END AS supply""" + stock + """

                  FROM part p
                  JOIN part_wheel w ON w.part_id = p.id
                  LEFT JOIN part_name pn ON pn.id = p.part_name_id
                  LEFT JOIN donor d ON d.id = p.donor_id
                  LEFT JOIN supply s ON s.id = p.supply_id
                """ + filter.where() + orderOf(sort, descending);

        jdbc.query(connection -> {
            var ps = connection.prepareStatement(sql);
            ps.setFetchSize(500);
            for (int at = 0; at < filter.args().size(); at++) {
                ps.setObject(at + 1, filter.args().get(at));
            }
            return ps;
        }, rs -> {
            List<String> cells = new java.util.ArrayList<>(List.of(
                    nullToEmpty(rs.getString("public_code")),
                    nullToEmpty(rs.getString("part_name")),
                    kindName(rs.getString("kind")),
                    number(rs, "set_no"),
                    number(rs, "diameter"),
                    nullToEmpty(rs.getString("tyre_type")),
                    number(rs, "tyre_width"),
                    MARKING.getOrDefault(nullToEmpty(rs.getString("marking_type")), ""),
                    TREAD.getOrDefault(nullToEmpty(rs.getString("tread_type")), ""),
                    nullToEmpty(rs.getString("construction")),
                    number(rs, "tyre_height"),
                    number(rs, "wear_mm"),
                    nullToEmpty(rs.getString("brand")),
                    nullToEmpty(rs.getString("model")),
                    SEASONS.getOrDefault(nullToEmpty(rs.getString("season")), ""),
                    number(rs, "made_year"),
                    nullToEmpty(rs.getString("disc_type")),
                    number(rs, "disc_width"),
                    number(rs, "offset_mm"),
                    nullToEmpty(rs.getString("bolt_pattern")),
                    number(rs, "hub_bore"),
                    nullToEmpty(rs.getString("disc_brand")),
                    nullToEmpty(rs.getString("disc_model")),
                    nullToEmpty(rs.getString("oem")),
                    number(rs, "price"),
                    nullToEmpty(rs.getString("description")),
                    nullToEmpty(rs.getString("note")),
                    nullToEmpty(rs.getString("section")),
                    day(rs, "created_at"),
                    day(rs, "updated_at"),
                    nullToEmpty(rs.getString("updated_by_name")),
                    nullToEmpty(rs.getString("supply")),
                    CONDITIONS.getOrDefault(nullToEmpty(rs.getString("condition")), ""),
                    // Пусто, а не «нет»: флажок сообщает что-то только когда
                    // он поднят, а колонка из одних «нет» — столбец шума.
                    rs.getBoolean("run_flat") ? "да" : "",
                    rs.getBoolean("light_truck") ? "да" : "",
                    nullToEmpty(rs.getString("speed_index")),
                    number(rs, "load_index"),
                    nullToEmpty(rs.getString("donor_legacy") != null
                            ? rs.getString("donor_legacy") : rs.getString("donor_code")),
                    rs.getBoolean("is_published") ? "Везде" : "Нет",
                    rs.getInt("photo_count") == 0 ? "" : String.valueOf(rs.getInt("photo_count")),
                    nullToEmpty(rs.getString("legacy_code")),
                    nullToEmpty(rs.getString("barcode")),
                    day(rs, "price_changed_at"),
                    nullToEmpty(rs.getString("price_changed_by_name"))));

            for (CatalogService.Warehouse warehouse : warehouses) {
                cells.add(number(rs, "free_" + warehouse.id()));
                cells.add(number(rs, "res_" + warehouse.id()));
            }
            writer.write(cells);
        });
    }

    /** Заголовок выгрузки: те же колонки и в том же порядке, что на экране. */
    public static List<String> exportHeader(List<CatalogService.Warehouse> warehouses) {
        List<String> header = new java.util.ArrayList<>(List.of(
                "Номер товара", "Наименование", "Товар", "Номер комплекта", "Диаметр",
                "Тип шины", "Ширина шины", "Тип маркировки", "Тип протектора",
                "Тип конструкции", "Высота шины", "Износ", "Производитель шины",
                "Модель шины", "Сезон", "Год производства", "Тип диска", "Ширина диска",
                "Вылет", "Сверловка", "Диаметр ЦО", "Производитель диска", "Модель диска",
                "Номер производителя", "Цена", "Комментарий", "Заметка", "Секция",
                "Создан", "Изменён", "Кто изменил", "Поставка", "Состояние",
                "RunFlat", "Легкогрузовая (LT)", "Индекс скорости", "Индекс нагрузки",
                "Номер донора", "Выгружать", "Количество фото", "Старые данные",
                "Ст. баркод", "Цена изменена в", "Кто изменил цену"));
        for (CatalogService.Warehouse warehouse : warehouses) {
            header.add(warehouse.name() + " (свободно)");
            header.add(warehouse.name() + " (резерв)");
        }
        return header;
    }

    private static final Map<String, String> MARKING = Map.of(
            "METRIC", "Метрическая", "INCH", "Дюймовая", "FLOTATION", "Флотационная");

    private static final Map<String, String> TREAD = Map.of(
            "STANDARD", "Стандартный", "ASYMMETRIC", "Асимметричный",
            "DIRECTIONAL", "Направленный");

    private static final Map<String, String> SEASONS = Map.of(
            "SUMMER", "летняя", "WINTER", "зимняя",
            "WINTER_STUDDED", "зимняя (шипы)", "WINTER_FRICTION", "зимняя (липучка)",
            "ALL_SEASON", "всесезонная");

    static String kindName(String kind) {
        return switch (kind) {
            case "TYRE" -> "Шина";
            case "DISC" -> "Диск";
            default -> "Колесо";
        };
    }

    private static final Map<String, String> CONDITIONS = Map.of(
            "NEW", "новая", "USED", "б/у", "REFURBISHED", "восстановленная");

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Числа словами не пишутся: пустая ячейка честнее нуля. */
    private static String number(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    /** Дата без времени: в файле время правки — шум. */
    private static String day(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? "" : java.time.format.DateTimeFormatter
                .ofPattern("dd.MM.yyyy")
                .withZone(java.time.ZoneId.systemDefault())
                .format(value.toInstant());
    }

    /**
     * Сортировка берётся из белого списка.
     *
     * <p>{@code ORDER BY} не принимает параметр, и подстановка пришедшего
     * из запроса текста — это внедрение SQL. Неизвестное имя молча становится
     * сортировкой по умолчанию.
     */
    private static String orderOf(String sort, boolean descending) {
        String column = SORTS.get(sort);
        if (column == null) {
            return " ORDER BY w.set_no DESC NULLS LAST, p.id DESC";
        }
        return " ORDER BY " + column + (descending ? " DESC" : " ASC") + " NULLS LAST, p.id DESC";
    }

    private static final Map<String, String> SORTS = Map.ofEntries(
            Map.entry("code", "p.public_code"),
            Map.entry("set", "w.set_no"),
            Map.entry("kind", "w.kind"),
            Map.entry("diameter", "w.diameter"),
            Map.entry("tyreWidth", "w.tyre_width"),
            Map.entry("tyreHeight", "w.tyre_height"),
            Map.entry("wear", "w.wear_mm"),
            Map.entry("season", "w.season"),
            Map.entry("madeYear", "w.made_year"),
            Map.entry("tyreBrand", "w.brand"),
            Map.entry("discBrand", "w.disc_brand"),
            Map.entry("price", "p.price"),
            Map.entry("section", "p.section"),
            Map.entry("created", "p.created_at"));

    /**
     * Момент из {@code timestamptz} читается через {@code getTimestamp}.
     *
     * <p>{@code getObject(..., Instant.class)} драйвер Postgres не умеет
     * и отвечает «conversion to class java.time.Instant from timestamptz
     * not supported» — вся витрина при этом падает.
     */
    private static Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /**
     * Остаток по складам — одним запросом на всю страницу.
     *
     * <p>Колонки складов не фиксированы: у живого клиента их два, у другого
     * будет пять, и остаток по каждому — своя колонка. Поэтому остаток строки
     * едет картой «склад → количество», как на витрине запчастей.
     */
    private List<WheelRow> withStock(List<WheelRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Long[] ids = rows.stream().map(WheelRow::id).toArray(Long[]::new);
        Map<Long, Map<Long, BigDecimal>> byPart = new java.util.HashMap<>();
        jdbc.query("""
                SELECT part_id, warehouse_id, sum(qty) AS qty FROM part_stock
                 WHERE part_id = ANY (?) GROUP BY part_id, warehouse_id""",
                rs -> {
                    byPart.computeIfAbsent(rs.getLong("part_id"), key -> new java.util.HashMap<>())
                            .put(rs.getLong("warehouse_id"), rs.getBigDecimal("qty"));
                },
                (Object) ids);

        return rows.stream()
                .map(row -> row.withStock(byPart.getOrDefault(row.id(), Map.of())))
                .toList();
    }

    /**
     * Заголовок из свойств: «Шина 195/65 R15 Goodyear EfficientGrip летняя».
     *
     * <p>Собирается, а не пишется руками, по той же причине, что и у запчасти:
     * иначе у одного клиента появятся «195/65R15» и «195 65 15» на соседних
     * строках, и ни поиск, ни выгрузка их не свяжут.
     *
     * <p>У колеса в сборе в заголовке оба набора: покупатель ищет его и по
     * размеру шины, и по сверловке — «225/55 R18 на 5x114.3». Уместить всё
     * нельзя, поэтому дисковая часть сокращена до того, чем диски
     * и различают: сверловка, вылет, модель.
     */
    static String titleOf(WheelRequest r) {
        boolean tyre = "TYRE".equals(r.kind()) || "ASSEMBLY".equals(r.kind());
        boolean disc = "DISC".equals(r.kind()) || "ASSEMBLY".equals(r.kind());

        StringBuilder title = new StringBuilder(switch (r.kind()) {
            case "TYRE" -> "Шина";
            case "DISC" -> "Диск";
            default -> "Колесо";
        });

        if (tyre) {
            if (r.tyreWidth() != null && r.tyreHeight() != null) {
                title.append(' ').append(r.tyreWidth()).append('/').append(r.tyreHeight());
            }
            if (r.diameter() != null) {
                title.append(' ').append(r.construction() == null ? "R" : r.construction())
                        .append(plain(r.diameter()));
            }
            append(title, r.brand());
            append(title, r.model());
            if (r.season() != null) {
                title.append(' ').append(seasonName(r.season()));
            }
        }

        if (disc) {
            // У сборки — после шины и отдельным словом: «…зимняя, диск Литой
            // 7x18 5x114.3 ET38 Rays». Без разделителя размер шины
            // и размер диска сливаются в одну неразбираемую строку.
            if ("ASSEMBLY".equals(r.kind())) {
                title.append(", диск");
            }
            if (r.discType() != null) {
                title.append(' ').append(r.discType());
            }
            if (r.discWidth() != null && r.diameter() != null) {
                title.append(' ').append(plain(r.discWidth()))
                        .append('x').append(plain(r.diameter()));
            }
            if (r.boltPattern() != null) {
                title.append(' ').append(r.boltPattern());
            }
            if (r.offsetMm() != null) {
                title.append(" ET").append(r.offsetMm());
            }
            append(title, r.discBrand());
            append(title, r.discModel());
        }
        return title.toString();
    }

    private static void append(StringBuilder title, String value) {
        if (value != null && !value.isBlank()) {
            title.append(' ').append(value.strip());
        }
    }

    /** Зимняя бывает шипованной и на липучке, и покупатель спрашивает именно это. */
    static String seasonName(String season) {
        return switch (season) {
            case "SUMMER" -> "летняя";
            case "WINTER" -> "зимняя";
            case "WINTER_STUDDED" -> "зимняя (шипы)";
            case "WINTER_FRICTION" -> "зимняя (липучка)";
            default -> "всесезонная";
        };
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * @param kind {@code TYRE}, {@code DISC} или {@code ASSEMBLY} — колесо
     *             в сборе, у которого заполнены оба набора свойств
     * @param brand производитель шины; у диска своё поле — у колеса в сборе
     *              они разные: шина Dunlop на диске Mitsubishi
     * @param wearMm остаток протектора в миллиметрах, а не в процентах:
     *               покупатель мерил глубиномером, а не долями
     */
    public record WheelRequest(String kind, BigDecimal diameter,
                               Integer tyreWidth, Integer tyreHeight, String construction,
                               String tyreType, String season, BigDecimal wearMm,
                               Integer madeYear,
                               String discType, BigDecimal discWidth, Integer offsetMm,
                               String boltPattern, BigDecimal hubBore,
                               String brand, String model,
                               String discBrand, String discModel,
                               String markingType, String treadType,
                               Boolean runFlat, Boolean lightTruck,
                               String speedIndex, Integer loadIndex,
                               BigDecimal price, BigDecimal costPrice, String condition) {
    }

    /** @param setNo пусто — колесо заведено поштучно, а не комплектом */
    public record Created(Integer setNo, String title, List<Long> partIds) {
    }

    /**
     * Строка вкладки «Шины и диски».
     *
     * <p>Колонок много, и это не украшение: у клиента их сорок пять, и все
     * они про то, по чему шину подбирают — размер, сезон, износ, индексы.
     * Пока строка несла шесть полей, свойства колеса лежали в базе и увидеть
     * их было негде.
     *
     * @param stock остаток по складам: ключ — идентификатор склада
     */
    public record WheelRow(Long id, String publicCode, String title, BigDecimal price,
                           String status, BigDecimal qty,
                           String kind, Integer setNo, BigDecimal diameter,
                           Integer tyreWidth, Integer tyreHeight, String construction,
                           String tyreType, String season, BigDecimal wearMm, Integer madeYear,
                           String discType, BigDecimal discWidth, Integer offsetMm,
                           String boltPattern, BigDecimal hubBore,
                           String brand, String model,
                           String discBrand, String discModel,
                           String markingType, String treadType,
                           Boolean runFlat, Boolean lightTruck,
                           String speedIndex, Integer loadIndex,
                           String partName, String condition, String supply, String donorCode,
                           String oem, String description, String note, String section,
                           boolean published, String barcode, String legacyCode, int photoCount,
                           Instant createdAt, Instant updatedAt, String updatedByName,
                           Instant priceChangedAt, String priceChangedByName,
                           String photoKey,
                           Map<Long, BigDecimal> stock) {

        WheelRow withStock(Map<Long, BigDecimal> byWarehouse) {
            return new WheelRow(id, publicCode, title, price, status, qty, kind, setNo, diameter,
                    tyreWidth, tyreHeight, construction, tyreType, season, wearMm, madeYear,
                    discType, discWidth, offsetMm, boltPattern, hubBore, brand, model,
                    discBrand, discModel, markingType, treadType, runFlat, lightTruck, speedIndex, loadIndex,
                    partName, condition, supply, donorCode, oem, description, note, section,
                    published, barcode, legacyCode, photoCount, createdAt, updatedAt,
                    updatedByName, priceChangedAt, priceChangedByName, photoKey, byWarehouse);
        }
    }
}
