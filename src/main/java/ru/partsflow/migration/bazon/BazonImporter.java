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
        importReservations(report);
        return report;
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
     */
    private long ensureUncategorized() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            Long existing = selectId(c,
                    "SELECT id FROM catalog.part_category WHERE slug = 'uncategorized'");
            if (existing != null) {
                return existing;
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO catalog.part_category (name, slug, path, sort_order)
                    VALUES ('Не разобрано', 'uncategorized', 'uncategorized', 9999)
                    RETURNING id""")) {
                return firstLong(ps);
            }
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

    private Map<String, Long> importDonors(Path donorsCsv, Map<String, Long> supplies,
                                           ImportReport report) throws SQLException {
        Map<String, Long> ids = new HashMap<>();

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + schema + """
                    .donor (vin, brand_id, year, color, mileage_km, note, supply_id, location,
                            steering, drive_type, transmission_type, transmission_model,
                            color_code, equipment_code, legacy_code, model_id,
                            body_code, engine_code, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DISMANTLED')
                    RETURNING id""")) {

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
                        // Повторный запуск не заводит машину второй раз.
                        // Раньше заводил — и оставлял её пустой: детали
                        // пропускались по своему legacy_code и оставались
                        // у первой. Полсотни машин-призраков в отчёте
                        // об окупаемости выглядят чистым убытком.
                        Long existing = selectId(c, "SELECT id FROM " + schema
                                + ".donor WHERE legacy_code = ?", number.number());
                        if (existing != null) {
                            ids.put(number.number(), existing);
                            report.count("машин пропущено (уже есть)");
                            return;
                        }
                    } catch (SQLException e) {
                        report.problem(row.lineNumber(),
                                "не удалось проверить машину: " + e.getMessage());
                        return;
                    }
                    try {
                        var color = BazonValueParser.parseColor(row.get("Цвет"));
                        var years = BazonValueParser.parseYearRange(row.get("Год выпуска"));

                        ps.setString(1, row.get("VIN"));
                        // Марка и модель ищутся в общем каталоге по имени.
                        // Не нашлись — остаются пустыми: заводить свою марку
                        // нельзя, через месяц в справочнике будут «Тойота»,
                        // «тойота» и «Toyota». Раньше здесь стоял ноль —
                        // ссылка на несуществующую марку, из-за которой
                        // у переехавшего клиента не работали ни фильтр
                        // по марке, ни применимость.
                        Long brandId = brands.find(c, row.get("Марка"));
                        setLong(ps, 2, brandId);
                        setInt(ps, 3, years == null ? null : years.from());
                        ps.setString(4, color == null ? null : color.name());
                        setInt(ps, 5, BazonValueParser.parseInteger(row.get("Пробег")));
                        ps.setString(6, buildDonorNote(row));
                        setLong(ps, 7, supplies.get(row.get("Поставка")));
                        ps.setString(8, row.get("Статус"));
                        ps.setString(9, BazonValueParser.parseSteering(row.get("Руль")));
                        ps.setString(10, BazonValueParser.parseDriveType(row.get("Привод")));
                        ps.setString(11, BazonValueParser.parseTransmissionType(row.get("Тип КПП")));
                        ps.setString(12, row.get("Модель КПП"));
                        ps.setString(13, color == null ? null : color.code());
                        ps.setString(14, row.get("Комплектация"));
                        ps.setString(15, number.number());
                        setLong(ps, 16, brandId == null
                                ? null : brands.findModel(c, brandId, row.get("Модель")));
                        // Кузов и двигатель — своими полями: по ним продавец
                        // отличает подходящую деталь, и на витрине это колонки.
                        ps.setString(17, row.get("Кузов"));
                        ps.setString(18, row.get("Двигатель"));

                        ids.put(number.number(), firstLong(ps));
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
                 PreparedStatement reserve = c.prepareStatement(
                         "SELECT " + schema + ".reserve_stock(?, ?, ?)")) {

                for (Reservation r : legacyReservations) {
                    java.sql.Savepoint savepoint = c.setSavepoint();
                    try {
                        // Через ту же функцию, что и продажа: она проверяет
                        // свободный остаток и меняет его одной инструкцией.
                        reserve.setLong(1, r.partId());
                        reserve.setLong(2, r.warehouseId());
                        reserve.setBigDecimal(3, r.qty());
                        reserve.execute();

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
    private void warnIfPublishFlagMissing(List<String> header, ImportReport report) {
        boolean present = header.stream().anyMatch(column -> "Выгружать".equals(column.trim()));
        if (!present) {
            report.problem(1, "в выгрузке нет колонки «Выгружать»: все позиции импортированы "
                    + "без разрешения на публикацию, площадки получат пустой прайс. "
                    + "Включите колонку в настройках таблицы товаров Bazon и повторите экспорт");
        }
    }

    // ---------- товары ----------

    private void importParts(Path catalogCsv, long categoryId,
                             Map<String, Long> donorSupplies, Map<String, Long> donors,
                             Map<String, Long> partNames, Map<String, Long> warehouses,
                             ImportReport report) throws SQLException {

        List<BazonWarehouseColumns.Warehouse> warehouseColumns;
        try (InputStream in = Files.newInputStream(catalogCsv);
             BazonCsvReader reader = new BazonCsvReader(in)) {
            warehouseColumns = BazonWarehouseColumns.discover(reader.header());
            warnIfPublishFlagMissing(reader.header(), report);
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
            try (PreparedStatement insertPart = c.prepareStatement("INSERT INTO " + schema + """
                     .part (category_id, part_name_id, donor_id, supply_id, title, description,
                            note, side_lr, side_fr, condition, quality_grade, marking, manufacturer,
                            color, section, installation_price, price, legacy_code, is_published,
                            status)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'USED', ?, ?, ?, ?, ?, ?, ?, ?, ?,
                             'DRAFT')
                     ON CONFLICT (legacy_code) WHERE legacy_code IS NOT NULL DO NOTHING
                     RETURNING id""");
                 PreparedStatement insertMovement = c.prepareStatement("INSERT INTO " + schema + """
                     .stock_movement (part_id, movement_type, qty_delta, to_warehouse_id, reason)
                     VALUES (?, 'INTAKE', ?, ?, 'Перенос из предыдущей системы')""");
                 PreparedStatement setReserved = c.prepareStatement("UPDATE " + schema + """
                     .part_stock SET qty_reserved = ? WHERE part_id = ? AND warehouse_id = ?""");
                 PreparedStatement insertOem = c.prepareStatement("INSERT INTO " + schema + """
                     .part_oem (part_id, raw_number, is_primary) VALUES (?, ?, ?)
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

                        Long partId = insertPart(insertPart, row, categoryId,
                                donorSupplies, donors, partNames);
                        if (partId == null) {
                            // Уже импортирован: повторный запуск не создаёт дублей.
                            c.releaseSavepoint(savepoint);
                            report.count("товаров пропущено (уже есть)");
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
    private void queuePhotos(PreparedStatement ps, BazonCsvReader.Row row, long partId,
                             ImportReport report) throws SQLException {

        List<String> urls = BazonValueParser.parsePhotoUrls(row.get("Превью"));
        int order = 0;
        for (String url : urls) {
            ps.setLong(1, partId);
            ps.setString(2, url);
            ps.setInt(3, order++);
            ps.addBatch();
        }
        if (order > 0) {
            ps.executeBatch();
            report.count("фотографий в очереди", order);
        }
    }

    private Long insertPart(PreparedStatement ps, BazonCsvReader.Row row, long categoryId,
                            Map<String, Long> donorSupplies, Map<String, Long> donors,
                            Map<String, Long> partNames) throws SQLException {

        String partName = row.get("Запчасть");
        var donorNumber = BazonValueParser.parseDonorNumber(row.get("Номер донора"));
        var years = BazonValueParser.parseYearRange(row.get("Год выпуска"));
        String donorKey = donorNumber == null ? null : donorNumber.number();

        ps.setLong(1, categoryId);
        setLong(ps, 2, partName == null ? null : partNames.get(partName.toLowerCase().strip()));
        setLong(ps, 3, donorKey == null ? null : donors.get(donorKey));
        setLong(ps, 4, donorKey == null ? null : donorSupplies.get(donorKey));
        ps.setString(5, buildTitle(row, partName, years));
        ps.setString(6, row.get("Комментарий"));
        ps.setString(7, row.get("Заметка"));
        setEnum(ps, 8, BazonValueParser.parseLateralSide(row.get("Левый / Правый")));
        setEnum(ps, 9, BazonValueParser.parseLongitudinalSide(row.get("Передний / Задний")));
        setEnum(ps, 10, BazonValueParser.parseQualityGrade(row.get("Оценка состояния")));
        ps.setString(11, row.get("Маркировка"));
        ps.setString(12, row.get("Производитель"));
        ps.setString(13, row.get("Цвет"));
        ps.setString(14, row.get("Секция"));
        setAmount(ps, 15, BazonValueParser.parseAmount(row.get("Установка")));
        setAmount(ps, 16, BazonValueParser.parseAmount(row.get("Цена")));
        // Номер в прежней системе — естественный ключ импорта: по нему повторный
        // запуск узнаёт уже загруженное.
        ps.setString(17, row.get("Номер товара"));

        // Разрешение публиковать переносится как есть: клиент выгружал эти
        // позиции на площадки и после переноса должен продолжить. Когда колонки
        // в выгрузке нет, публикацию не включаем — выложить чужой склад
        // на площадку по своей инициативе нельзя. О пропаже предупреждает
        // warnIfPublishFlagMissing.
        Boolean publish = BazonValueParser.parsePublishFlag(row.get("Выгружать"));
        ps.setBoolean(18, Boolean.TRUE.equals(publish));

        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : null;
        }
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

        String primary = row.get("Номер производителя");
        if (primary != null) {
            ps.setLong(1, partId);
            ps.setString(2, primary);
            ps.setBoolean(3, true);
            ps.executeUpdate();
        }
        for (String cross : BazonValueParser.parseList(row.get("Кросс-номера"))) {
            ps.setLong(1, partId);
            ps.setString(2, cross);
            ps.setBoolean(3, false);
            ps.executeUpdate();
        }
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
