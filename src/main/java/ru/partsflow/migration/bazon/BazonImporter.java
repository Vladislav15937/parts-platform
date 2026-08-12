package ru.partsflow.migration.bazon;

import ru.partsflow.inventory.PartCondition;
import ru.partsflow.inventory.PartTitleGenerator;

import javax.sql.DataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Перенос склада из выгрузки предыдущей учётной системы.
 *
 * <p><b>Почему JDBC, а не JPA.</b> Тридцать пять тысяч товаров с движениями —
 * это сотни тысяч операций. JPA потратит их на управление контекстом
 * персистентности и мусор в heap, а выигрыша не даст: импорт ничего не читает
 * и не меняет по одной сущности, он пишет пакетами.
 *
 * <p><b>Почему имена схем подставляются в SQL, а не через search_path.</b>
 * Ровно по той же причине, что и в миграциях: полагаться на search_path
 * значит однажды залить чужой склад не тому арендатору. Имя схемы проверяется
 * регулярным выражением — подстановка в SQL иначе была бы дырой.
 *
 * <p><b>Порядок жёсткий:</b> поставки → доноры → наименования → товары.
 * Товар ссылается на все три, поэтому справочники идут первыми.
 */
public final class BazonImporter {

    /** Имя схемы арендатора подставляется в SQL, поэтому проверяется строго. */
    private static final Pattern SCHEMA = Pattern.compile("t_\\d{6,}");

    private static final int BATCH_SIZE = 500;

    private final DataSource dataSource;
    private final String schema;
    private final PartTitleGenerator titleGenerator = new PartTitleGenerator();
    private final VehicleLookup brands = new VehicleLookup();

    /** Резервы из выгрузки: оформляются одной сделкой после переноса товаров. */
    private final List<Reservation> legacyReservations = new java.util.ArrayList<>();

    private record Reservation(long partId, long warehouseId, BigDecimal qty) {
    }

    public BazonImporter(DataSource dataSource, String schema) {
        if (schema == null || !SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("Недопустимое имя схемы арендатора: " + schema);
        }
        this.dataSource = dataSource;
        this.schema = schema;
    }

    /**
     * Импортирует выгрузку целиком.
     *
     * <p>Каждый этап — своя транзакция. Разбивать намеренно: импорт склада идёт
     * минутами, и одна гигантская транзакция на всё означала бы, что сбой на
     * последнем товаре откатывает и справочники тоже. Повторный запуск
     * идемпотентен по естественным ключам, поэтому продолжить с середины дешевле,
     * чем начинать заново.
     */
    public ImportReport importAll(Path donorsCsv, Path catalogCsv) throws SQLException {
        ImportReport report = new ImportReport();

        long branchId = ensureBranch();
        long categoryId = ensureUncategorized();

        Map<String, Long> supplies = importSupplies(donorsCsv, report);
        Map<String, Long> donors = importDonors(donorsCsv, supplies, report);
        Map<String, Long> donorSupplies = donorSupplies(donorsCsv, supplies);
        Map<String, Long> partNames = importPartNames(catalogCsv, report);
        Map<String, Long> warehouses = ensureWarehouses(catalogCsv, branchId, report);

        importParts(catalogCsv, categoryId, donorSupplies, donors, partNames, warehouses, report);
        // Пересчёт до резервов: те проверяют свободный остаток, а он берётся
        // из раскладки, которую как раз этот шаг и заполняет.
        report.count("карточек с пересчитанным остатком", applyMovements());
        importReservations(report);
        return report;
    }

    /**
     * Применяет записанные движения к раскладке и карточкам — пачкой.
     *
     * <p>До 3 августа 2026 это делал триггер на каждой вставке. Перенос пишет
     * тридцать пять тысяч движений, и применять их по одному значило бы
     * столько же обращений к базе; остаток и статус выводятся из журнала,
     * поэтому пачкой получается то же самое. Тот же расчёт, что
     * в {@code StockLedger.recomputeAll} — но своим соединением: перенос идёт
     * вне JPA.
     *
     * <p>Статус выводится из остатка, а не из вида последнего движения:
     * у переехавшего склада все движения — приход. Позиция, которой в чужой
     * выгрузке нет ни на одном складе, так и остаётся черновиком — обещать
     * наличие за неё нельзя.
     */
    private int applyMovements() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET search_path TO " + schema + ", catalog, public");
            s.executeUpdate("""
                    INSERT INTO part_stock (part_id, warehouse_id, qty)
                    SELECT part_id, warehouse, sum(delta)
                      FROM (
                          SELECT part_id, to_warehouse_id AS warehouse, abs(qty_delta) AS delta
                            FROM stock_movement WHERE to_warehouse_id IS NOT NULL
                          UNION ALL
                          SELECT part_id, from_warehouse_id, -abs(qty_delta)
                            FROM stock_movement WHERE from_warehouse_id IS NOT NULL
                      ) m
                     GROUP BY part_id, warehouse
                    ON CONFLICT (part_id, warehouse_id) DO UPDATE
                        SET qty = EXCLUDED.qty, updated_at = now()""");

            return s.executeUpdate("""
                    UPDATE part p
                       SET qty_on_hand = stock.qty,
                           updated_at = now(),
                           status = CASE WHEN stock.qty > 0 THEN 'IN_STOCK' ELSE p.status END
                      FROM (SELECT part_id, sum(qty) AS qty
                              FROM part_stock GROUP BY part_id) stock
                     WHERE p.id = stock.part_id
                       AND (p.qty_on_hand IS DISTINCT FROM stock.qty
                            OR (stock.qty > 0 AND p.status <> 'IN_STOCK'))""");
        }
    }

    /**
     * Поставка по номеру донора.
     *
     * <p>В выгрузке товаров колонки «Поставка» нет — она есть только у доноров.
     * Но деталь приехала тем же контейнером, что и машина, с которой её сняли,
     * поэтому поставку наследуем через донора.
     *
     * <p>Чего это не спасает: контрактные запчасти, приехавшие без донора
     * (четверть склада). У них поставка останется пустой, и восстановить её
     * из этой выгрузки нечем — нужно включить колонку «Поставка» в настройках
     * таблицы товаров перед выгрузкой.
     */
    private Map<String, Long> donorSupplies(Path donorsCsv, Map<String, Long> supplies) {
        Map<String, Long> byDonorNumber = new HashMap<>();
        forEachRow(donorsCsv, new ImportReport(), row -> {
            var number = BazonValueParser.parseDonorNumber(row.get("Номер донора"));
            Long supplyId = supplies.get(row.get("Поставка"));
            if (number != null && number.isParsed() && supplyId != null) {
                byDonorNumber.put(number.number(), supplyId);
            }
        });
        return byDonorNumber;
    }

    // ---------- справочники организации ----------

    /** Склады обязаны принадлежать филиалу, поэтому филиал должен существовать. */
    private long ensureBranch() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            Long existing = selectId(c, "SELECT id FROM " + schema + ".branch ORDER BY id LIMIT 1");
            if (existing != null) {
                return existing;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + schema + ".branch (name) VALUES (?) RETURNING id")) {
                ps.setString(1, "Основной");
                return firstLong(ps);
            }
        }
    }

    /**
     * Категория-заглушка в общем каталоге.
     *
     * <p>{@code part.category_id} обязателен, а выгрузка категорию не отдаёт:
     * там есть только наименование детали. Раскладывать 35 тысяч позиций по
     * дереву категорий автоматически — значит разложить их неверно, поэтому
     * все едут в «Не разобрано» и разбираются потом через справочник
     * наименований. Пустая категория честнее выдуманной.
     *
     * <p>Строку сеет миграция {@code catalog/020}, а импорт её только читает.
     * Раньше он заводил её сам одним INSERT — и на ячейке с разделением ролей
     * падал: рабочая роль на общей схеме {@code catalog} имеет только
     * {@code SELECT} (справочники пишет миграция, читают все), и INSERT отвечал
     * «permission denied for table part_category», уводя весь перенос
     * в пятисотку ещё до первой позиции. Теперь в {@code catalog} импорт
     * не пишет вовсе; отсутствие строки — это не накатанная миграция, и об этом
     * честнее сказать прямо, чем пытаться её вставить.
     */
    private long ensureUncategorized() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            Long existing = selectId(c,
                    "SELECT id FROM catalog.part_category WHERE slug = 'uncategorized'");
            if (existing == null) {
                throw new IllegalStateException(
                        "В каталоге нет категории «Не разобрано» (slug=uncategorized): "
                        + "не накатана миграция catalog/020");
            }
            return existing;
        }
    }

    /**
     * Склады берутся из заголовка выгрузки: их имена у каждого клиента свои.
     * Склад заводится, даже если по нему нет остатка, — он существует у клиента,
     * и товар туда переместят завтра.
     */
    private Map<String, Long> ensureWarehouses(Path catalogCsv, long branchId, ImportReport report)
            throws SQLException {

        List<BazonWarehouseColumns.Warehouse> discovered;
        try (InputStream in = Files.newInputStream(catalogCsv);
             BazonCsvReader reader = new BazonCsvReader(in)) {
            discovered = BazonWarehouseColumns.discover(reader.header());
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать заголовок выгрузки товаров", e);
        }

        Map<String, Long> result = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection()) {
            for (var w : discovered) {
                Long id = selectId(c,
                        "SELECT id FROM " + schema + ".warehouse WHERE name = ?", w.name());
                if (id == null) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + schema
                            + ".warehouse (branch_id, name) VALUES (?, ?) RETURNING id")) {
                        ps.setLong(1, branchId);
                        ps.setString(2, w.name());
                        id = firstLong(ps);
                    }
                    report.count("складов заведено");
                }
                result.put(w.name(), id);
            }
        }
        return result;
    }

    // ---------- поставки ----------

    private Map<String, Long> importSupplies(Path donorsCsv, ImportReport report) throws SQLException {
        Map<String, BazonValueParser.SupplyRef> unique = new LinkedHashMap<>();

        forEachRow(donorsCsv, report, row -> {
            var supply = BazonValueParser.parseSupply(row.get("Поставка"));
            if (supply != null) {
                unique.putIfAbsent(supply.raw(), supply);
            }
        });

        Map<String, Long> ids = new HashMap<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            for (var e : unique.entrySet()) {
                var s = e.getValue();
                String kind = s.kind().name();
                // Ключ — пара «тип + номер»: у поставки без номера в него уходит
                // исходная строка, поэтому «Автозапчасти BMW» не сливается
                // с настоящими контейнерами.
                Long id = selectId(c, "SELECT id FROM " + schema
                        + ".supply WHERE kind = ? AND number = ?", kind, s.number());
                if (id == null) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + schema
                            + ".supply (kind, number, supplier_name, arrived_on, status)"
                            + " VALUES (?, ?, ?, ?, ?) RETURNING id")) {
                        ps.setString(1, kind);
                        ps.setString(2, s.number());
                        ps.setString(3, s.supplierName());
                        if (s.arrivedOn() == null) {
                            ps.setNull(4, Types.DATE);
                        } else {
                            ps.setObject(4, s.arrivedOn());
                        }
                        // Всё, что уже приехало и разобрано, импортируется как прибывшее.
                        ps.setString(5, "ARRIVED");
                        id = firstLong(ps);
                    }
                    report.count("поставок");
                }
                ids.put(e.getKey(), id);
            }
            c.commit();
        }
        return ids;
    }

    // ---------- доноры ----------

    /**
     * Поля машины, которые перенос заполняет из выгрузки.
     *
     * <p>Один список на вставку и на дозаполнение — из него собираются оба
     * запроса. Разойдись они, и колонка, добавленная в перенос, у клиентов,
     * загруженных раньше, не появилась бы никогда: ровно это и случилось
     * с кузовом и двигателем.
     */
    private static final List<Column> DONOR_FIELDS = List.of(
            new Column("vin", Types.VARCHAR),
            new Column("brand_id", Types.BIGINT),
            new Column("model_id", Types.BIGINT),
            new Column("year", Types.INTEGER),
            new Column("color", Types.VARCHAR),
            new Column("color_code", Types.VARCHAR),
            new Column("mileage_km", Types.INTEGER),
            new Column("supply_id", Types.BIGINT),
            new Column("location", Types.VARCHAR),
            new Column("steering", Types.VARCHAR),
            new Column("drive_type", Types.VARCHAR),
            new Column("transmission_type", Types.VARCHAR),
            new Column("transmission_model", Types.VARCHAR),
            new Column("equipment_code", Types.VARCHAR),
            new Column("body_code", Types.VARCHAR),
            new Column("engine_code", Types.VARCHAR),
            new Column("note", Types.VARCHAR));

    /** Колонка переноса: имя и тип, чтобы пустое значение уходило типизированным. */
    private record Column(String name, int sqlType) {
    }

    /**
     * Машины из выгрузки.
     *
     * <p><b>Повтор дозаполняет, а не пропускает.</b> Раньше найденная машина
     * пропускалась целиком, и это работало против нас: колонка, появившаяся
     * в переносе позже, у клиента, загруженного раньше, не заполнялась
     * никогда — повторный перенос его же выгрузки ничего не менял. Так у
     * живого клиента остались пустыми кузов и двигатель всех 440 машин,
     * притом что в его выгрузке они есть.
     *
     * <p><b>Заполняется только пустое.</b> {@code COALESCE} не трогает
     * ни того, что ввёл человек, ни того, что положил прошлый перенос:
     * выгрузка бывает старше правки, и затирать ею живые данные нельзя.
     * Поэтому же дозаполнение — часть обычного переноса, а не миграция:
     * миграция чинит одного клиента и один раз, а здесь любой, повторив
     * свою выгрузку, получает то же самое.
     *
     * <p>Статус машины не трогается вовсе: он ведётся у нас — купленная
     * встаёт в разбор, разобранная списывается, — и выгрузка про это
     * не знает.
     */
    private Map<String, Long> importDonors(Path donorsCsv, Map<String, Long> supplies,
                                           ImportReport report) throws SQLException {
        Map<String, Long> ids = new HashMap<>();
        String columns = DONOR_FIELDS.stream()
                .map(Column::name).collect(java.util.stream.Collectors.joining(", "));
        String holders = DONOR_FIELDS.stream()
                .map(f -> "?").collect(java.util.stream.Collectors.joining(", "));
        String assignments = DONOR_FIELDS.stream()
                .map(f -> "%s = COALESCE(%s, ?)".formatted(f.name(), f.name()))
                .collect(java.util.stream.Collectors.joining(", "));
        String coalesced = DONOR_FIELDS.stream()
                .map(f -> "COALESCE(%s, ?)".formatted(f.name()))
                .collect(java.util.stream.Collectors.joining(", "));

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO %s.donor (%s, legacy_code, status)
                    VALUES (%s, ?, 'DISMANTLED') RETURNING id"""
                    .formatted(schema, columns, holders));
                 // Обновление только при настоящем расхождении: без этого
                 // повтор отчитывался бы «дополнено» по каждой машине,
                 // ничего не изменив, и число перестало бы что-либо значить.
                 PreparedStatement fill = c.prepareStatement("""
                    UPDATE %s.donor SET %s
                     WHERE legacy_code = ? AND (%s) IS DISTINCT FROM (%s)"""
                    .formatted(schema, assignments, columns, coalesced))) {

                forEachRow(donorsCsv, report, row -> {
                    var number = BazonValueParser.parseDonorNumber(row.get("Номер донора"));
                    if (number == null || !number.isParsed()) {
                        report.problem(row.lineNumber(), "донор без разбираемого номера: "
                                + row.get("Номер донора"));
                        return;
                    }
                    if (ids.containsKey(number.number())) {
                        return;
                    }
                    try {
                        Object[] values = donorValues(c, row, supplies);
                        // Повторный запуск не заводит машину второй раз.
                        // Раньше заводил — и оставлял её пустой: детали
                        // пропускались по своему legacy_code и оставались
                        // у первой. Полсотни машин-призраков в отчёте
                        // об окупаемости выглядят чистым убытком.
                        Long existing = selectId(c, "SELECT id FROM " + schema
                                + ".donor WHERE legacy_code = ?", number.number());
                        if (existing != null) {
                            ids.put(number.number(), existing);
                            report.count(fillDonor(fill, values, number.number()) > 0
                                    ? "машин дополнено" : "машин пропущено (уже есть)");
                            return;
                        }

                        int at = 1;
                        for (int i = 0; i < DONOR_FIELDS.size(); i++) {
                            bind(insert, at++, DONOR_FIELDS.get(i), values[i]);
                        }
                        insert.setString(at, number.number());

                        ids.put(number.number(), firstLong(insert));
                        report.count("доноров");
                    } catch (SQLException e) {
                        report.problem(row.lineNumber(), "донор не загружен: " + e.getMessage());
                    }
                });
            }
            c.commit();
        }
        return ids;
    }

    /** @return сколько строк изменилось: единица — машину дополнили */
    private int fillDonor(PreparedStatement fill, Object[] values, String legacyCode)
            throws SQLException {
        int at = 1;
        for (int i = 0; i < DONOR_FIELDS.size(); i++) {
            bind(fill, at++, DONOR_FIELDS.get(i), values[i]);
        }
        fill.setString(at++, legacyCode);
        // Те же значения второй раз — для сравнения «изменится ли что-нибудь».
        for (int i = 0; i < DONOR_FIELDS.size(); i++) {
            bind(fill, at++, DONOR_FIELDS.get(i), values[i]);
        }
        return fill.executeUpdate();
    }

    /** Значения полей машины в порядке {@link #DONOR_FIELDS}. */
    private Object[] donorValues(Connection c, BazonCsvReader.Row row,
                                 Map<String, Long> supplies) throws SQLException {
        var color = BazonValueParser.parseColor(row.get("Цвет"));
        var years = BazonValueParser.parseYearRange(row.get("Год выпуска"));
        // Марка и модель ищутся в общем каталоге по имени. Не нашлись —
        // остаются пустыми: заводить свою марку нельзя, через месяц
        // в справочнике будут «Тойота», «тойота» и «Toyota». Раньше здесь
        // стоял ноль — ссылка на несуществующую марку, из-за которой
        // у переехавшего клиента не работали ни фильтр по марке,
        // ни применимость.
        Long brandId = brands.find(c, row.get("Марка"));

        return new Object[] {
                row.get("VIN"),
                brandId,
                brandId == null ? null : brands.findModel(c, brandId, row.get("Модель")),
                years == null ? null : years.from(),
                color == null ? null : color.name(),
                color == null ? null : color.code(),
                BazonValueParser.parseInteger(row.get("Пробег")),
                supplies.get(row.get("Поставка")),
                row.get("Статус"),
                BazonValueParser.parseSteering(row.get("Руль")),
                BazonValueParser.parseDriveType(row.get("Привод")),
                BazonValueParser.parseTransmissionType(row.get("Тип КПП")),
                row.get("Модель КПП"),
                row.get("Комплектация"),
                // Кузов и двигатель — своими полями: по ним продавец отличает
                // подходящую деталь, и на витрине это колонки.
                row.get("Кузов"),
                row.get("Двигатель"),
                buildDonorNote(row)};
    }

    private static void bind(PreparedStatement ps, int index, Column column, Object value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, column.sqlType());
        } else {
            ps.setObject(index, value);
        }
    }

    /**
     * Оформляет перенесённые резервы одной сделкой.
     *
     * <p><b>Резерв без документа — это обещание, о котором никто не знает.</b>
     * Продавец видит «отложено 1» и не может выяснить, кому и до какого числа;
     * сверка {@code v_reservation_discrepancy} при этом считает каждую такую
     * позицию расхождением, и инвариант, который обязан быть пустым, шумит
     * с первого дня — то есть от него перестают ждать сигнала.
     *
     * <p>Клиент в выгрузке не назван, поэтому сделка заводится без него.
     * Срок недельный: перенесённое обещание надо подтвердить у клиента,
     * а неподтверждённое обязано всплыть у продавца в просроченных,
     * а не держать товар вечно.
     *
     * <p>Одна сделка на весь перенос, а не по одной на позицию: сотню
     * документов «перенос» разобрать нельзя, а разложить одну по клиентам
     * продавец умеет — для этого есть перенос позиций.
     */
    private void importReservations(ImportReport report) throws SQLException {
        if (legacyReservations.isEmpty()) {
            return;
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);

            Long dealId;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + schema
                    + ".deal (status, reserved_until, note)"
                    + " VALUES ('RESERVED', now() + interval '7 days', ?) RETURNING id")) {
                ps.setString(1, "Резервы, перенесённые из предыдущей системы. "
                        + "Клиент в выгрузке не назван — подтвердите обещания "
                        + "и разнесите позиции по сделкам.");
                dealId = firstLong(ps);
            }

            try (PreparedStatement item = c.prepareStatement("INSERT INTO " + schema
                    + ".deal_item (deal_id, part_id, quantity, price, warehouse_id, status)"
                    + " VALUES (?, ?, ?, COALESCE((SELECT price FROM " + schema
                    + ".part WHERE id = ?), 0), ?, 'RESERVED')");
                 // Тот же единственный запрос, что делает
                 // StockReservationRepository. Копия, а не вызов репозитория:
                 // перенос идёт своим соединением и своей транзакцией, вне
                 // JPA. Разойдясь с оригиналом, копия перестанет проверять
                 // свободный остаток — поэтому условие здесь дословное.
                 PreparedStatement reserve = c.prepareStatement("UPDATE " + schema + """
                         .part_stock
                            SET qty_reserved = qty_reserved + ?, updated_at = now()
                          WHERE part_id = ? AND warehouse_id = ?
                            AND qty - qty_reserved >= ?""")) {

                for (Reservation r : legacyReservations) {
                    java.sql.Savepoint savepoint = c.setSavepoint();
                    try {
                        // Проверка и изменение — одна инструкция, как
                        // и при продаже: ноль изменённых строк означает, что
                        // свободного остатка не хватило.
                        reserve.setBigDecimal(1, r.qty());
                        reserve.setLong(2, r.partId());
                        reserve.setLong(3, r.warehouseId());
                        reserve.setBigDecimal(4, r.qty());
                        if (reserve.executeUpdate() == 0) {
                            throw new SQLException(
                                    "недостаточно свободного остатка на складе "
                                            + r.warehouseId());
                        }

                        item.setLong(1, dealId);
                        item.setLong(2, r.partId());
                        item.setBigDecimal(3, r.qty());
                        item.setLong(4, r.partId());
                        item.setLong(5, r.warehouseId());
                        item.executeUpdate();

                        c.releaseSavepoint(savepoint);
                        report.count("резервов перенесено");
                    } catch (SQLException e) {
                        report.problem(0, "резерв не перенесён по детали "
                                + r.partId() + ": " + e.getMessage());
                        c.rollback(savepoint);
                    }
                }
            }
            c.commit();
        }
    }

    /**
     * Поиск марки и модели в общем каталоге.
     *
     * <p>С памятью: у клиента десяток марок на тысячу машин, и ходить в базу
     * за каждой строкой выгрузки незачем. Ненайденное запоминается тоже —
     * иначе марка, которой нет в дереве, стоит запроса на каждой машине.
     *
     * <p>Сравнение по имени без учёта регистра и пробелов: в выгрузке
     * встречается и «Toyota», и «TOYOTA», и «Toyota ».
     */
    private static final class VehicleLookup {

        private final Map<String, Long> byBrand = new HashMap<>();
        private final Map<String, Long> byModel = new HashMap<>();

        Long find(Connection c, String name) throws SQLException {
            if (name == null || name.isBlank()) {
                return null;
            }
            String key = name.strip().toLowerCase();
            if (byBrand.containsKey(key)) {
                return byBrand.get(key);
            }
            Long id = selectId(c,
                    "SELECT id FROM catalog.brand WHERE lower(btrim(name)) = ?", key);
            byBrand.put(key, id);
            return id;
        }

        Long findModel(Connection c, long brandId, String name) throws SQLException {
            if (name == null || name.isBlank()) {
                return null;
            }
            String key = brandId + "/" + name.strip().toLowerCase();
            if (byModel.containsKey(key)) {
                return byModel.get(key);
            }
            Long id = selectId(c, """
                    SELECT id FROM catalog.model
                     WHERE brand_id = ? AND lower(btrim(name)) = ?""",
                    brandId, name.strip().toLowerCase());
            byModel.put(key, id);
            return id;
        }
    }

    /**
     * Марка, модель, кузов и двигатель донора складываются в заметку.
     *
     * <p>Пока общий каталог не наполнен, привязать их к справочникам нельзя,
     * а выбросить — значит потерять то, по чему продавец ищет деталь.
     * Заметка — временное хранилище до сопоставления с каталогом.
     */
    private static String buildDonorNote(BazonCsvReader.Row row) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, row.get("Марка"));
        appendIfPresent(sb, row.get("Модель"));
        appendIfPresent(sb, row.get("Кузов"));
        appendIfPresent(sb, row.get("Двигатель"));
        appendIfPresent(sb, row.get("Заметка"));
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value != null) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(value);
        }
    }

    // ---------- справочник наименований ----------

    /**
     * Уникальные наименования из выгрузки.
     *
     * <p>Все заводятся как {@code UNMATCHED}: сопоставление с эталонным
     * каталогом — отдельный шаг, и делать его автоматически при импорте нельзя.
     * Ошибочное сопоставление хуже отсутствующего: оно выглядит как готовые
     * данные и не попадает в список на разбор.
     */
    private Map<String, Long> importPartNames(Path catalogCsv, ImportReport report)
            throws SQLException {

        Map<String, String> unique = new LinkedHashMap<>();
        forEachRow(catalogCsv, report, row -> {
            String name = row.get("Запчасть");
            if (name != null) {
                unique.putIfAbsent(name.toLowerCase().strip(), name);
            }
        });

        Map<String, Long> ids = new HashMap<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            for (var e : unique.entrySet()) {
                Long id = selectId(c, "SELECT id FROM " + schema
                        + ".part_name WHERE lower(btrim(name)) = ?", e.getKey());
                if (id == null) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + schema
                            + ".part_name (name, match_status) VALUES (?, 'UNMATCHED') RETURNING id")) {
                        ps.setString(1, e.getValue());
                        id = firstLong(ps);
                    }
                    report.count("наименований");
                }
                ids.put(e.getKey(), id);
            }
            c.commit();
        }
        return ids;
    }

    /**
     * Предупреждает, что колонки «Выгружать» в выгрузке нет.
     *
     * <p>Без неё ни одна позиция не будет публиковаться, и первый собранный
     * прайс Дрома или фид Авито выйдет пустым при полностью рабочем коде —
     * ошибка, на поиск которой уходит день. В Bazon колонка неактивна,
     * её включают в настройках таблицы товаров перед экспортом
     * (см. {@code docs/bazon-parity.md} §11).
     */
    private void warnPublishFlagMissing(ImportReport report) {
        // Говорится только о том, что действительно завелось. На повторе
        // переноса все позиции пропускаются как уже существующие, публикацию
        // повтор не трогает вовсе — а предупреждение всё равно обещало пустой
        // прайс. Владелец, повторивший выгрузку ради дозаполнения полей,
        // читал, что остался без объявлений, при полностью целой публикации.
        // Поймано повтором на живом складе: 35 841 пропущено, ноль заведено,
        // и тревога на ровном месте.
        int created = report.loaded("товаров");
        if (created == 0) {
            return;
        }
        report.problem(1, "в выгрузке нет колонки «Выгружать»: заведённые позиции (%d) "
                .formatted(created)
                + "импортированы без разрешения на публикацию, и в прайс они не попадут. "
                + "Включите колонку в настройках таблицы товаров Bazon и повторите экспорт — "
                + "либо включите выгрузку отбором на витрине склада");
    }

    // ---------- товары ----------

    private void importParts(Path catalogCsv, long categoryId,
                             Map<String, Long> donorSupplies, Map<String, Long> donors,
                             Map<String, Long> partNames, Map<String, Long> warehouses,
                             ImportReport report) throws SQLException {

        boolean publishFlagMissing;
        List<BazonWarehouseColumns.Warehouse> warehouseColumns;
        try (InputStream in = Files.newInputStream(catalogCsv);
             BazonCsvReader reader = new BazonCsvReader(in)) {
            warehouseColumns = BazonWarehouseColumns.discover(reader.header());
            publishFlagMissing = reader.header().stream()
                    .noneMatch(column -> "Выгружать".equals(column.trim()));
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать заголовок выгрузки товаров", e);
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);

            // Статус при вставке — DRAFT, а не IN_STOCK: у позиции с остатком
            // его через мгновение перепишет триггер прихода, а у позиции,
            // которой в выгрузке нет ни на одном складе, движения не будет
            // вовсе — и «в наличии» осталось бы у карточки, за которой ничего
            // не лежит. У переехавшего клиента таких оказалось десять.
            try (PreparedStatement insertPart = c.prepareStatement("""
                     INSERT INTO %s.part (%s, category_id, title, legacy_code, is_published,
                                          condition, status)
                     VALUES (%s, ?, ?, ?, ?, 'USED', 'DRAFT')
                     ON CONFLICT (legacy_code) WHERE legacy_code IS NOT NULL DO NOTHING
                     RETURNING id"""
                     .formatted(schema, partColumns(), partHolders()));
                 // Дозаполнение уже загруженной позиции — то же, что у машин:
                 // колонка, появившаяся в переносе позже, иначе не доедет
                 // до клиента, загруженного раньше, никогда.
                 PreparedStatement fillPart = c.prepareStatement("""
                     UPDATE %s.part SET %s
                      WHERE legacy_code = ? AND (%s) IS DISTINCT FROM (%s)"""
                     .formatted(schema, partAssignments(), partColumns(), partCoalesced()));
                 PreparedStatement insertMovement = c.prepareStatement("INSERT INTO " + schema + """
                     .stock_movement (part_id, movement_type, qty_delta, to_warehouse_id, reason)
                     VALUES (?, 'INTAKE', ?, ?, 'Перенос из предыдущей системы')""");
                 PreparedStatement setReserved = c.prepareStatement("UPDATE " + schema + """
                     .part_stock SET qty_reserved = ? WHERE part_id = ? AND warehouse_id = ?""");
                 PreparedStatement insertOem = c.prepareStatement("INSERT INTO " + schema + """
                     .part_oem (part_id, raw_number, normalized, is_primary)
                     VALUES (?, ?, ?, ?)
                     ON CONFLICT DO NOTHING""");
                 PreparedStatement insertPhoto = c.prepareStatement("INSERT INTO " + schema + """
                     .part_photo_import (part_id, url, sort_order) VALUES (?, ?, ?)
                     ON CONFLICT DO NOTHING""")) {

                int[] pending = {0};

                forEachRow(catalogCsv, report, row -> {
                    // Точка сохранения на каждый товар. Без неё первая же
                    // отвергнутая строка переводит транзакцию в aborted, и все
                    // последующие падают с «current transaction is aborted» —
                    // одна плохая позиция уносит весь склад.
                    java.sql.Savepoint savepoint = null;
                    try {
                        savepoint = c.setSavepoint();

                        Object[] values = partValues(row, donorSupplies, donors, partNames);
                        Long partId = insertPart(insertPart, values, row, categoryId);
                        if (partId == null) {
                            // Уже загружена. Не пропускаем целиком, как раньше:
                            // дозаполняем пустое, дописываем номера и ставим
                            // в очередь недостающие снимки. Движения при этом
                            // не повторяются — они бы удвоили остаток.
                            Long existing = selectId(c, "SELECT id FROM " + schema
                                    + ".part WHERE legacy_code = ?", row.get("Номер товара"));
                            boolean filled = false;
                            if (existing != null) {
                                filled = fillPart(fillPart, values, row.get("Номер товара")) > 0;
                                insertNumbers(insertOem, row, existing);
                                filled |= queuePhotos(insertPhoto, row, existing, report) > 0;
                            }
                            c.releaseSavepoint(savepoint);
                            report.count(filled
                                    ? "товаров дополнено" : "товаров пропущено (уже есть)");
                            return;
                        }

                        insertStock(insertMovement, setReserved, row, partId, warehouseColumns, warehouses);
                        insertNumbers(insertOem, row, partId);
                        queuePhotos(insertPhoto, row, partId, report);
                        c.releaseSavepoint(savepoint);

                        report.count("товаров");
                        if (++pending[0] % BATCH_SIZE == 0) {
                            c.commit();
                        }
                    } catch (SQLException e) {
                        report.problem(row.lineNumber(), "товар не загружен: " + e.getMessage());
                        rollbackQuietly(c, savepoint, report, row.lineNumber());
                    }
                });
            }
            c.commit();
        }

        // После прохода, а не по заголовку: предупреждать надо о том, что
        // действительно завелось без разрешения публиковать.
        if (publishFlagMissing) {
            warnPublishFlagMissing(report);
        }
    }


    /**
     * Ставит фотографии товара в очередь переноса.
     *
     * <p><b>Записывает, но не качает.</b> Тридцать шесть тысяч позиций — это
     * под сотню тысяч файлов с чужого CDN: внутри запроса импорта это часы,
     * то есть оборванное соединение и непонятное состояние. Качает отдельный
     * проход, который можно прервать и продолжить.
     *
     * <p>Колонка называется «Превью» — так она называется в настройках таблицы
     * товаров прежней системы. Её тоже надо включить перед выгрузкой, как
     * и «Выгружать»: невключённая колонка означает склад без единого снимка,
     * а на разборке продаёт фотография.
     */
    /**
     * Ставит снимки в очередь на перенос.
     *
     * <p>Считается поставленное, а не перечисленное в строке: у повторного
     * переноса большинство ссылок уже в очереди, и отчёт «поставлено сто
     * тысяч» на втором прогоне был бы неправдой. Уникальность пары
     * «позиция + ссылка» стережёт индекс, поэтому повтор безопасен.
     *
     * @return сколько ссылок добавилось
     */
    private int queuePhotos(PreparedStatement ps, BazonCsvReader.Row row, long partId,
                            ImportReport report) throws SQLException {

        List<String> urls = BazonValueParser.parsePhotoUrls(row.get("Превью"));
        int order = 0;
        for (String url : urls) {
            ps.setLong(1, partId);
            ps.setString(2, url);
            ps.setInt(3, order++);
            ps.addBatch();
        }
        if (order == 0) {
            return 0;
        }
        int queued = 0;
        for (int affected : ps.executeBatch()) {
            if (affected > 0) {
                queued++;
            }
        }
        if (queued > 0) {
            report.count("фотографий в очереди", queued);
        }
        return queued;
    }

    /**
     * Поля позиции, которые перенос заполняет из выгрузки.
     *
     * <p>Список тот же, что у машин, и по той же причине: из него собираются
     * и вставка, и дозаполнение. Чего здесь нет — тоже решение. Заголовок
     * и категорию ведёт справочник наименований: перенос собрал их однажды,
     * а дальше их правит сопоставление, и выгрузка не должна возвращать
     * старое. «Выгружать» не трогается вовсе: владелец мог снять позицию
     * с площадки, и повтор переноса не имеет права её вернуть. Остаток
     * и статус ведут триггеры склада.
     */
    private static final List<Column> PART_FIELDS = List.of(
            new Column("part_name_id", Types.BIGINT),
            new Column("donor_id", Types.BIGINT),
            new Column("supply_id", Types.BIGINT),
            new Column("description", Types.VARCHAR),
            new Column("note", Types.VARCHAR),
            new Column("side_lr", Types.VARCHAR),
            new Column("side_fr", Types.VARCHAR),
            new Column("quality_grade", Types.VARCHAR),
            new Column("marking", Types.VARCHAR),
            new Column("manufacturer", Types.VARCHAR),
            new Column("color", Types.VARCHAR),
            new Column("section", Types.VARCHAR),
            new Column("installation_price", Types.NUMERIC),
            new Column("price", Types.NUMERIC));

    private static String partColumns() {
        return PART_FIELDS.stream().map(Column::name)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String partHolders() {
        return PART_FIELDS.stream().map(f -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String partAssignments() {
        return PART_FIELDS.stream().map(f -> "%s = COALESCE(%s, ?)".formatted(f.name(), f.name()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String partCoalesced() {
        return PART_FIELDS.stream().map(f -> "COALESCE(%s, ?)".formatted(f.name()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** Значения полей позиции в порядке {@link #PART_FIELDS}. */
    private Object[] partValues(BazonCsvReader.Row row, Map<String, Long> donorSupplies,
                                Map<String, Long> donors, Map<String, Long> partNames) {

        String partName = row.get("Запчасть");
        var donorNumber = BazonValueParser.parseDonorNumber(row.get("Номер донора"));
        String donorKey = donorNumber == null ? null : donorNumber.number();

        return new Object[] {
                partName == null ? null : partNames.get(partName.toLowerCase().strip()),
                donorKey == null ? null : donors.get(donorKey),
                donorKey == null ? null : donorSupplies.get(donorKey),
                row.get("Комментарий"),
                row.get("Заметка"),
                name(BazonValueParser.parseLateralSide(row.get("Левый / Правый"))),
                name(BazonValueParser.parseLongitudinalSide(row.get("Передний / Задний"))),
                name(BazonValueParser.parseQualityGrade(row.get("Оценка состояния"))),
                row.get("Маркировка"),
                row.get("Производитель"),
                row.get("Цвет"),
                row.get("Секция"),
                // Ноль в колонке «Установка» — это незаполненное поле прежней
                // системы, а не бесплатная установка: в её карточке пустое
                // и нулевое выглядят одинаково, а у нас «Цена установки 0 ₽» —
                // утверждение, которого никто не делал. У переехавшего клиента
                // таких строк 367 из 381.
                zeroToNull(BazonValueParser.parseAmount(row.get("Установка"))),
                BazonValueParser.parseAmount(row.get("Цена"))};
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private Long insertPart(PreparedStatement ps, Object[] values, BazonCsvReader.Row row,
                            long categoryId) throws SQLException {

        var years = BazonValueParser.parseYearRange(row.get("Год выпуска"));
        int at = 1;
        for (int i = 0; i < PART_FIELDS.size(); i++) {
            bind(ps, at++, PART_FIELDS.get(i), values[i]);
        }
        ps.setLong(at++, categoryId);
        ps.setString(at++, buildTitle(row, row.get("Запчасть"), years));
        // Номер в прежней системе — естественный ключ импорта: по нему повторный
        // запуск узнаёт уже загруженное.
        ps.setString(at++, row.get("Номер товара"));

        // Разрешение публиковать переносится как есть: клиент выгружал эти
        // позиции на площадки и после переноса должен продолжить. Когда колонки
        // в выгрузке нет, публикацию не включаем — выложить чужой склад
        // на площадку по своей инициативе нельзя. О пропаже предупреждает
        // warnIfPublishFlagMissing.
        Boolean publish = BazonValueParser.parsePublishFlag(row.get("Выгружать"));
        ps.setBoolean(at, Boolean.TRUE.equals(publish));

        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }

    /** @return сколько строк изменилось: единица — позицию дополнили */
    private int fillPart(PreparedStatement fill, Object[] values, String legacyCode)
            throws SQLException {
        if (legacyCode == null) {
            return 0;
        }
        int at = 1;
        for (int i = 0; i < PART_FIELDS.size(); i++) {
            bind(fill, at++, PART_FIELDS.get(i), values[i]);
        }
        fill.setString(at++, legacyCode);
        for (int i = 0; i < PART_FIELDS.size(); i++) {
            bind(fill, at++, PART_FIELDS.get(i), values[i]);
        }
        return fill.executeUpdate();
    }

    private String buildTitle(BazonCsvReader.Row row, String partName,
                              BazonValueParser.YearRange years) {
        if (partName == null) {
            return "Без наименования";
        }
        var vehicle = new PartTitleGenerator.VehicleTitlePart(
                row.get("Марка"), row.get("Модель"), row.get("Кузов"),
                row.get("Двигатель"), years == null ? null : years.from());
        var sides = new PartTitleGenerator.Sides(
                BazonValueParser.parseLongitudinalSide(row.get("Передний / Задний")),
                BazonValueParser.parseLateralSide(row.get("Левый / Правый")),
                null);

        return titleGenerator.generate(partName, vehicle, sides, PartCondition.USED,
                row.get("Номер производителя"));
    }

    /**
     * Остаток кладётся движением, а не записью в {@code part_stock} напрямую.
     *
     * <p>Иначе журнал движений разойдётся с остатком с первого же дня, и
     * ежесуточная сверка будет ругаться на весь перенесённый склад. Резерв —
     * другое дело: он не движение, а обещание, и ставится полем.
     */
    private void insertStock(PreparedStatement insertMovement, PreparedStatement setReserved,
                             BazonCsvReader.Row row, long partId,
                             List<BazonWarehouseColumns.Warehouse> columns,
                             Map<String, Long> warehouses) throws SQLException {

        for (var w : columns) {
            Long warehouseId = warehouses.get(w.name());
            if (warehouseId == null) {
                continue;
            }
            BigDecimal free = w.free(row);
            BigDecimal reserved = w.reserved(row);
            BigDecimal onHand = free.add(reserved);

            if (onHand.signum() > 0) {
                insertMovement.setLong(1, partId);
                insertMovement.setBigDecimal(2, onHand);
                insertMovement.setLong(3, warehouseId);
                insertMovement.executeUpdate();
            }
            if (reserved.signum() > 0) {
                // Не пишем в part_stock напрямую: резерв без документа ломает
                // сверку v_reservation_discrepancy, и она у переехавшего
                // клиента шумит с первого дня — то есть перестаёт быть
                // сигналом. Копим и оформляем сделкой после импорта.
                legacyReservations.add(new Reservation(partId, warehouseId, reserved));
            }
        }
    }

    private void insertNumbers(PreparedStatement ps, BazonCsvReader.Row row, long partId)
            throws SQLException {

        writeNumber(ps, partId, row.get("Номер производителя"), true);
        for (String cross : BazonValueParser.parseList(row.get("Кросс-номера"))) {
            writeNumber(ps, partId, cross, false);
        }
    }

    /**
     * Один номер, если после приведения от него что-то осталось.
     *
     * <p>Приведённый номер считает приложение — тем же методом, что и приёмка.
     * До 4 августа 2026 его считала генерируемая колонка, и слово вместо номера
     * превращалось в пустую строку; теперь это {@code null}, а колонка
     * {@code NOT NULL} — то есть отказ базы, уносящий карточку целиком вместе
     * с остатком и очередью снимков. В выгрузке живого клиента такое есть:
     * в кросс-номерах стоит «АНАЛОГ». Пропускаем номер, а не теряем товар.
     */
    private void writeNumber(PreparedStatement ps, long partId, String raw, boolean primary)
            throws SQLException {

        String normalized = ru.partsflow.catalog.OemNumbers.normalize(raw);
        if (normalized == null) {
            return;
        }
        ps.setLong(1, partId);
        ps.setString(2, raw);
        ps.setString(3, normalized);
        ps.setBoolean(4, primary);
        ps.executeUpdate();
    }

    // ---------- вспомогательное ----------

    /**
     * Откат одной строки к точке сохранения.
     *
     * <p>Если не удался и он — транзакция уже нерабочая, и продолжать нельзя:
     * дальше посыплются ложные ошибки по всем оставшимся строкам, а настоящая
     * причина утонет. Лучше остановиться на понятном месте.
     */
    private static void rollbackQuietly(Connection c, java.sql.Savepoint savepoint,
                                        ImportReport report, long line) {
        if (savepoint == null) {
            return;
        }
        try {
            c.rollback(savepoint);
        } catch (SQLException e) {
            report.problem(line, "не удалось откатиться к точке сохранения: " + e.getMessage());
            throw new IllegalStateException("Импорт остановлен: транзакция нерабочая", e);
        }
    }

    private void forEachRow(Path csv, ImportReport report, BazonCsvReader.RowHandler handler) {
        try (InputStream in = Files.newInputStream(csv);
             BazonCsvReader reader = new BazonCsvReader(in)) {
            reader.forEachRow(handler, (line, values) ->
                    report.problem(line, "строка не разобрана: колонок " + values.size()));
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать " + csv, e);
        }
    }

    private static void setLong(PreparedStatement ps, int i, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(i, Types.BIGINT);
        } else {
            ps.setLong(i, value);
        }
    }

    private static void setInt(PreparedStatement ps, int i, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, value);
        }
    }

    /** Только для необязательных денег: у цены товара ноль — это ноль. */
    private static BigDecimal zeroToNull(BigDecimal value) {
        return value == null || value.signum() == 0 ? null : value;
    }

    private static void setAmount(PreparedStatement ps, int i, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(i, Types.NUMERIC);
        } else {
            ps.setBigDecimal(i, value);
        }
    }

    private static void setEnum(PreparedStatement ps, int i, Enum<?> value) throws SQLException {
        ps.setString(i, value == null ? null : value.name());
    }

    private static Long selectId(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static long firstLong(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("Вставка не вернула идентификатор");
            }
            return rs.getLong(1);
        }
    }
}
