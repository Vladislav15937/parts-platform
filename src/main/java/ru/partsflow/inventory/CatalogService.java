package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Витрина склада: таблица товаров, как её видит владелец.
 *
 * <p>Отдельно от поиска продавца ({@code PartService.searchAvailable}) и это
 * не дублирование. Продавец ищет, что можно продать прямо сейчас, и ему нужны
 * пять полей и свободный остаток. Владелец смотрит склад целиком: двадцать
 * с лишним колонок, сортировка по любой, постранично по тридцать пять тысяч
 * позиций. Один запрос на оба сценария был бы плох для обоих.
 *
 * <p><b>Колонки складов не фиксированы.</b> У клиента их два — «Ткацкая»
 * и «54 YARD», — у другого будет пять, и остаток по каждому складу идёт
 * своей колонкой. Поэтому склады отдаются отдельным списком, а остатки
 * строки — картой «склад → количество».
 *
 * <p><b>Сортировка задаётся именем колонки, а не строкой в SQL.</b>
 * Подставлять в {@code ORDER BY} то, что пришло из запроса, — это внедрение
 * SQL; поэтому имя ищется в белом списке, а не экранируется.
 */
@Service
public class CatalogService {

    /**
     * Разрешённые сортировки: имя из запроса → выражение SQL.
     *
     * <p>Белый список, а не экранирование: {@code ORDER BY} не принимает
     * параметр, и любая подстановка пришедшего текста — дыра.
     */
    private static final Map<String, String> SORTS = Map.ofEntries(
            Map.entry("code", "p.public_code"),
            Map.entry("title", "p.title"),
            Map.entry("price", "p.price"),
            Map.entry("brand", "b.name"),
            Map.entry("model", "m.name"),
            Map.entry("year", "d.year"),
            Map.entry("qty", "p.qty_on_hand"),
            Map.entry("created", "p.created_at"),
            Map.entry("manufacturer", "p.manufacturer"),
            Map.entry("section", "p.section"));

    private static final Map<String, String> SIDE_FR =
            Map.of("FRONT", "Перед.", "REAR", "Задн.");

    private static final Map<String, String> SIDE_LR =
            Map.of("LEFT", "Лев.", "RIGHT", "Прав.");

    private final JdbcTemplate jdbc;

    public CatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Страница витрины.
     *
     * @param query       поиск по номеру товара, наименованию или номеру детали
     * @param withReserved показывать ли отложенное; выключено — только свободное
     * @param withMissing  показывать ли то, чего нет на складе. Проданное
     *                     не исчезает из системы, и владелец иногда смотрит
     *                     именно его — но по умолчанию склад это то, что лежит
     * @param warehouseIds пусто — все склады
     */
    @Transactional(readOnly = true)
    public Page list(String query, boolean withReserved, boolean withMissing,
                     List<Long> warehouseIds, Vehicle vehicle, String sort, boolean descending,
                     int page, int size) {

        Filter filter = filterOf(query, withReserved, withMissing, warehouseIds, vehicle);
        StringBuilder where = filter.where();
        List<Object> args = filter.args();

        return finish(where, args, sort, descending, page, size);
    }

    /**
     * Условие отбора — одно на страницу и на выгрузку.
     *
     * <p>Разное условие означало бы, что скачанный файл не совпадает с тем,
     * что владелец видел на экране, — а он именно это и проверяет, скачивая.
     */
    private Filter filterOf(String query, boolean withReserved, boolean withMissing,
                            List<Long> warehouseIds) {
        return filterOf(query, withReserved, withMissing, warehouseIds, null);
    }

    /**
     * Отбор по машине: «покажи, что подходит к этой».
     *
     * <p><b>Ищется по двум признакам сразу.</b> Первый — машина, с которой
     * деталь снята: у переехавшего клиента это единственное, что есть, —
     * заявленной применимости у него ноль строк на двадцать шесть тысяч
     * деталей с донором. Второй — сама применимость, если её заполнили
     * в приёмке. Искать только по второму значило бы показать переехавшему
     * пустоту, только по первому — потерять деталь, которая подходит
     * не только к своей машине.
     *
     * <p>Кузов и двигатель сравниваются по вхождению: у клиента в этих полях
     * лежит «ACA33», а у другой машины той же модели — «ACA38», и точное
     * равенство отсекло бы половину подходящего.
     */
    private Filter filterOf(String query, boolean withReserved, boolean withMissing,
                            List<Long> warehouseIds, Vehicle vehicle) {
        StringBuilder where = new StringBuilder(" WHERE p.product_line = 'PART'");
        List<Object> args = new ArrayList<>();

        if (query != null && !query.isBlank()) {
            // Номер товара, наименование и номер детали — три способа, которыми
            // владелец ищет одно и то же. Спрашивать, что именно он ввёл,
            // значит заставить его выбирать вкладку перед каждым поиском.
            where.append("""

                     AND (p.public_code ILIKE ? OR p.title ILIKE ?
                          OR EXISTS (SELECT 1 FROM part_oem o
                                      WHERE o.part_id = p.id AND o.raw_number ILIKE ?))""");
            String like = "%" + query.strip() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (!withMissing) {
            where.append(" AND p.qty_on_hand > 0");
        }
        if (!withReserved) {
            // «Не показывать отложенное» прячет то, что лежит, но обещано
            // другому, — и только это. Позиции без остатка вовсе им управлять
            // не должно: за них отвечает «отображать отсутствующие», иначе
            // два независимых флажка молча складываются в один.
            where.append("""

                     AND (p.qty_on_hand = 0
                          OR EXISTS (SELECT 1 FROM part_stock s
                                      WHERE s.part_id = p.id AND s.qty_available > 0))""");
        }
        if (warehouseIds != null && !warehouseIds.isEmpty()) {
            where.append("""

                     AND EXISTS (SELECT 1 FROM part_stock s
                                  WHERE s.part_id = p.id AND s.qty > 0
                                    AND s.warehouse_id = ANY (?))""");
            args.add(warehouseIds.toArray(Long[]::new));
        }

        if (vehicle != null && vehicle.brandId() != null) {
            StringBuilder donor = new StringBuilder("d.brand_id = ?");
            List<Object> donorArgs = new ArrayList<>();
            donorArgs.add(vehicle.brandId());
            if (vehicle.modelId() != null) {
                donor.append(" AND d.model_id = ?");
                donorArgs.add(vehicle.modelId());
            }
            if (vehicle.body() != null && !vehicle.body().isBlank()) {
                donor.append(" AND d.body_code ILIKE ?");
                donorArgs.add("%" + vehicle.body().strip() + "%");
            }
            if (vehicle.engine() != null && !vehicle.engine().isBlank()) {
                donor.append(" AND d.engine_code ILIKE ?");
                donorArgs.add("%" + vehicle.engine().strip() + "%");
            }

            StringBuilder declared = new StringBuilder("a.brand_id = ?");
            List<Object> declaredArgs = new ArrayList<>();
            declaredArgs.add(vehicle.brandId());
            if (vehicle.modelId() != null) {
                declared.append(" AND a.model_id = ?");
                declaredArgs.add(vehicle.modelId());
            }

            where.append("\n AND ((").append(donor)
                    .append(") OR EXISTS (SELECT 1 FROM part_applicability a")
                    .append(" WHERE a.part_id = p.id AND ").append(declared).append("))");
            args.addAll(donorArgs);
            args.addAll(declaredArgs);
        }
        return new Filter(where, args);
    }

    private record Filter(StringBuilder where, List<Object> args) {
    }

    /**
     * Машины, к которым на складе что-то есть, — с числом деталей.
     *
     * <p><b>Список свой, а не весь справочник.</b> В каталоге четыре с половиной
     * тысячи моделей, из них на складе лежат детали от полутора сотен: подбор
     * по общему справочнику — это выбрать модель и получить пустую таблицу,
     * ничего не сказав о том, почему пусто. Число рядом отвечает сразу.
     *
     * <p>Строк тут по числу разобранных машин, то есть сотни, — уровни кузова
     * и двигателя экран складывает сам, вторым запросом их брать незачем.
     */
    @Transactional(readOnly = true)
    public List<VehicleOption> vehicles() {
        return jdbc.query("""
                SELECT b.id AS brand_id, b.name AS brand, m.id AS model_id, m.name AS model,
                       d.body_code, d.engine_code, count(*) AS parts
                  FROM part p
                  JOIN donor d ON d.id = p.donor_id
                  JOIN catalog.brand b ON b.id = d.brand_id
                  LEFT JOIN catalog.model m ON m.id = d.model_id
                 GROUP BY b.id, b.name, m.id, m.name, d.body_code, d.engine_code
                 ORDER BY b.name, m.name""",
                (rs, i) -> new VehicleOption(rs.getLong("brand_id"), rs.getString("brand"),
                        (Long) rs.getObject("model_id"), rs.getString("model"),
                        rs.getString("body_code"), rs.getString("engine_code"),
                        rs.getLong("parts")));
    }

    /** Марка, модель, кузов и двигатель одной разобранной машины. */
    public record VehicleOption(Long brandId, String brand, Long modelId, String model,
                                String body, String engine, long parts) {
    }

    /** Машина, к которой подбирают деталь. Пустая марка — отбора нет. */
    public record Vehicle(Long brandId, Long modelId, String body, String engine) {
    }

    private Page finish(StringBuilder where, List<Object> args, String sort,
                        boolean descending, int page, int size) {
        String joins = """

                  FROM part p
                  LEFT JOIN donor d ON d.id = p.donor_id
                  LEFT JOIN catalog.brand b ON b.id = d.brand_id
                  LEFT JOIN catalog.model m ON m.id = d.model_id
                  LEFT JOIN catalog.generation g ON g.id = d.generation_id
                  LEFT JOIN catalog.modification mo ON mo.id = d.modification_id""";

        Long total = jdbc.queryForObject("SELECT count(*)" + joins + where, Long.class,
                args.toArray());

        String order = SORTS.getOrDefault(sort, "p.id") + (descending ? " DESC" : " ASC");
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);

        List<Row> rows = jdbc.query("""
                SELECT p.id, p.public_code, p.title, p.quality_grade, p.condition,
                       b.name AS brand, m.name AS model,
                       g.name AS generation, g.year_from, g.year_to,
                       d.body_code, d.engine_code,
                       d.year, d.public_code AS donor_code, d.legacy_code AS donor_legacy,
                       p.price, p.installation_price, p.color, p.description, p.note,
                       p.manufacturer, p.marking, p.section, p.side_lr, p.side_fr,
                       p.qty_on_hand,
                       (SELECT o.raw_number FROM part_oem o
                         WHERE o.part_id = p.id AND o.is_primary LIMIT 1) AS oem,
                       (SELECT string_agg(o.raw_number, ', ') FROM part_oem o
                         WHERE o.part_id = p.id AND NOT o.is_primary) AS crosses,
                       (SELECT ph.s3_key FROM part_photo ph
                         WHERE ph.part_id = p.id ORDER BY ph.is_main DESC, ph.sort_order,
                               ph.id LIMIT 1) AS photo_key
                """ + joins + where + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                (rs, i) -> new Row(
                        rs.getLong("id"), rs.getString("public_code"), rs.getString("title"),
                        rs.getString("quality_grade"), rs.getString("condition"),
                        rs.getString("brand"), rs.getString("model"),
                        rs.getString("generation"),
                        (Integer) rs.getObject("year_from"), (Integer) rs.getObject("year_to"),
                        rs.getString("body_code"), rs.getString("engine_code"),
                        (Integer) rs.getObject("year"),
                        rs.getString("donor_legacy") != null
                                ? rs.getString("donor_legacy") : rs.getString("donor_code"),
                        rs.getBigDecimal("price"), rs.getBigDecimal("installation_price"),
                        rs.getString("color"), rs.getString("description"), rs.getString("note"),
                        rs.getString("manufacturer"), rs.getString("marking"),
                        rs.getString("section"), rs.getString("side_lr"), rs.getString("side_fr"),
                        rs.getBigDecimal("qty_on_hand"),
                        rs.getString("oem"), rs.getString("crosses"), rs.getString("photo_key"),
                        Map.of()),
                pageArgs.toArray());

        return new Page(total == null ? 0 : total, withStock(rows));
    }

    /**
     * Остатки по складам — одним запросом на страницу, а не на строку.
     *
     * <p>Тридцать пять тысяч позиций показываются постранично, но пятьдесят
     * запросов на страницу — это пятьдесят обращений к базе там, где хватает
     * одного.
     */
    private List<Row> withStock(List<Row> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Long[] ids = rows.stream().map(Row::id).toArray(Long[]::new);
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
     * Пишет витрину в поток — всю, что прошла отбор.
     *
     * <p><b>Потоком, а не списком.</b> Тридцать пять тысяч строк с двадцатью
     * с лишним колонками, собранные в памяти перед отправкой, — это сотни
     * мегабайт на каждого скачивающего. Та же причина, по которой прайс
     * площадки пишется через StAX.
     *
     * <p>Курсор работает только внутри транзакции: при включённом autoCommit
     * Postgres игнорирует {@code fetchSize} и вычитывает весь склад в память.
     *
     * <p>Отбор тот же, что и у страницы: скачанный файл обязан совпасть с тем,
     * что владелец видел на экране, — ради этой сверки он его и качает.
     *
     * <p>Идентификаторы складов подставляются в SQL текстом, и это безопасно:
     * они пришли из нашей же таблицы, а не из запроса. Параметром колонку
     * не задать.
     */
    @Transactional(readOnly = true)
    public void export(String query, boolean withReserved, boolean withMissing,
                       List<Long> warehouseIds, Vehicle vehicle, String sort, boolean descending,
                       List<Warehouse> warehouses, RowWriter writer) {

        Filter filter = filterOf(query, withReserved, withMissing, warehouseIds, vehicle);
        String order = SORTS.getOrDefault(sort, "p.id") + (descending ? " DESC" : " ASC");

        StringBuilder stock = new StringBuilder();
        for (Warehouse warehouse : warehouses) {
            stock.append("""
                    ,
                           (SELECT sum(s.qty_available) FROM part_stock s
                             WHERE s.part_id = p.id AND s.warehouse_id = %1$d) AS free_%1$d,
                           (SELECT sum(s.qty_reserved) FROM part_stock s
                             WHERE s.part_id = p.id AND s.warehouse_id = %1$d) AS res_%1$d"""
                    .formatted(warehouse.id()));
        }

        String sql = """
                SELECT p.public_code, p.title, p.quality_grade, p.condition,
                       b.name AS brand, m.name AS model, g.year_from, g.year_to,
                       d.body_code, d.engine_code, d.year,
                       COALESCE(d.legacy_code, d.public_code) AS donor_code,
                       p.side_fr, p.side_lr, p.price, p.installation_price, p.color,
                       p.description, p.manufacturer, p.marking, p.section, p.note,
                       (SELECT o.raw_number FROM part_oem o
                         WHERE o.part_id = p.id AND o.is_primary LIMIT 1) AS oem,
                       (SELECT string_agg(o.raw_number, ', ') FROM part_oem o
                         WHERE o.part_id = p.id AND NOT o.is_primary) AS crosses"""
                + stock + """

                  FROM part p
                  LEFT JOIN donor d ON d.id = p.donor_id
                  LEFT JOIN catalog.brand b ON b.id = d.brand_id
                  LEFT JOIN catalog.model m ON m.id = d.model_id
                  LEFT JOIN catalog.generation g ON g.id = d.generation_id"""
                + filter.where() + " ORDER BY " + order;

        jdbc.query(connection -> {
            var ps = connection.prepareStatement(sql);
            // Курсор пачками: без этого драйвер вычитывает весь склад разом.
            ps.setFetchSize(500);
            for (int at = 0; at < filter.args().size(); at++) {
                Object arg = filter.args().get(at);
                if (arg instanceof Long[] array) {
                    ps.setArray(at + 1, connection.createArrayOf("bigint", array));
                } else {
                    ps.setObject(at + 1, arg);
                }
            }
            return ps;
        }, rs -> {
            List<String> cells = new ArrayList<>();
            cells.add(text(rs, "public_code"));
            cells.add(text(rs, "title"));
            cells.add(text(rs, "quality_grade"));
            cells.add(text(rs, "brand"));
            cells.add(text(rs, "model"));
            cells.add(text(rs, "year_from"));
            cells.add(text(rs, "year_to"));
            cells.add(text(rs, "body_code"));
            cells.add(text(rs, "engine_code"));
            cells.add(text(rs, "year"));
            // Словами, а не кодами: файл открывают в Excel и читают глазами,
            // и «REAR» там означает утечку внутреннего представления.
            // В выгрузке прежней системы стоит ровно «Задн.» и «Лев.».
            cells.add(SIDE_FR.getOrDefault(text(rs, "side_fr"), ""));
            cells.add(SIDE_LR.getOrDefault(text(rs, "side_lr"), ""));
            cells.add(text(rs, "donor_code"));
            cells.add(number(rs, "price"));
            cells.add(number(rs, "installation_price"));
            cells.add(text(rs, "color"));
            cells.add(text(rs, "description"));
            cells.add(text(rs, "manufacturer"));
            cells.add(text(rs, "oem"));
            cells.add(text(rs, "crosses"));
            cells.add(text(rs, "note"));
            cells.add(text(rs, "marking"));
            cells.add(text(rs, "section"));
            for (Warehouse warehouse : warehouses) {
                cells.add(number(rs, "free_" + warehouse.id()));
                cells.add(number(rs, "res_" + warehouse.id()));
            }
            writer.write(cells);
        });
    }

    /** Заголовок выгрузки: тот же состав и порядок, что у строк. */
    public static List<String> exportHeader(List<Warehouse> warehouses) {
        List<String> header = new ArrayList<>(List.of(
                "Номер товара", "Запчасть", "Оценка состояния", "Марка", "Модель",
                "Поколение с", "Поколение по", "Кузов", "Двигатель", "Год выпуска",
                "Передний / Задний", "Левый / Правый", "Номер донора",
                "Цена", "Установка", "Цвет", "Комментарий", "Производитель",
                "Номер производителя", "Кросс-номера", "Заметка", "Маркировка", "Секция"));
        for (Warehouse warehouse : warehouses) {
            header.add(warehouse.name() + " (свободно)");
            header.add(warehouse.name() + " (резерв)");
        }
        return header;
    }

    private static String text(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        String value = rs.getString(column);
        return value == null ? "" : value;
    }

    private static String number(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    /** Приёмник строки выгрузки: реализуется контроллером, пишущим в ответ. */
    public interface RowWriter {
        void write(List<String> cells);
    }

    /** Склады арендатора: из них получаются колонки остатка. */
    @Transactional(readOnly = true)
    public List<Warehouse> warehouses() {
        return jdbc.query("SELECT id, name FROM warehouse ORDER BY id",
                (rs, i) -> new Warehouse(rs.getLong("id"), rs.getString("name")));
    }

    public record Warehouse(Long id, String name) {
    }

    public record Page(long total, List<Row> rows) {
    }

    /**
     * @param stock остаток по складам: ключ — идентификатор склада. Колонок
     *              столько, сколько складов у клиента
     */
    public record Row(Long id, String code, String title, String qualityGrade, String condition,
                      String brand, String model, String generation,
                      Integer yearFrom, Integer yearTo, String body, String engine,
                      Integer year, String donorCode,
                      BigDecimal price, BigDecimal installationPrice,
                      String color, String description, String note,
                      String manufacturer, String marking, String section,
                      String sideLr, String sideFr, BigDecimal qty,
                      String oem, String crosses, String photoKey,
                      Map<Long, BigDecimal> stock) {

        Row withStock(Map<Long, BigDecimal> found) {
            return new Row(id, code, title, qualityGrade, condition, brand, model, generation,
                    yearFrom, yearTo, body, engine, year, donorCode, price, installationPrice,
                    color, description, note, manufacturer, marking, section, sideLr, sideFr,
                    qty, oem, crosses, photoKey, found);
        }
    }
}
