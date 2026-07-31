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
                     List<Long> warehouseIds, String sort, boolean descending,
                     int page, int size) {

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
