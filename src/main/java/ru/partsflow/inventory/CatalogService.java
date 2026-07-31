package ru.partsflow.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

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

    /**
     * Проставляет применимость по машинам, названным в заголовках.
     *
     * <p>У переехавшего клиента четверть склада без донора: это детали,
     * подходящие к нескольким машинам, и машины перечислены прямо
     * в наименовании — записать их было больше некуда. Подбор по машине
     * их не находит вовсе.
     *
     * <p><b>Отметка `is_verified` остаётся снятой.</b> Это разобранное
     * машиной, а не подтверждённое человеком, и различать их надо: правка
     * в карточке ставит отметку, а повторный разбор чужого не трогает.
     *
     * @return сколько позиций получили применимость и сколько строк добавлено
     */
    @Transactional
    public Parsed applyFromTitles() {
        TitleApplicability.Dictionary dictionary = new TitleApplicability.Dictionary(
                jdbc.query("SELECT id, name FROM catalog.brand",
                        (rs, i) -> new TitleApplicability.Brand(rs.getLong(1), rs.getString(2))),
                jdbc.query("SELECT id, brand_id, name FROM catalog.model",
                        (rs, i) -> new TitleApplicability.Model(
                                rs.getLong(1), rs.getLong(2), rs.getString(3))));

        List<long[]> pairs = new ArrayList<>();
        int[] parts = {0};
        jdbc.query("SELECT id, title FROM part WHERE title IS NOT NULL", rs -> {
            long partId = rs.getLong(1);
            List<TitleApplicability.Vehicle> found =
                    TitleApplicability.parse(rs.getString(2), dictionary);
            if (!found.isEmpty()) {
                parts[0]++;
                for (TitleApplicability.Vehicle vehicle : found) {
                    pairs.add(new long[]{partId, vehicle.brandId(), vehicle.modelId()});
                }
            }
        });

        int added = 0;
        for (long[] pair : pairs) {
            added += jdbc.update("""
                    INSERT INTO part_applicability (part_id, brand_id, model_id, is_verified)
                    VALUES (?, ?, ?, false)
                    ON CONFLICT DO NOTHING""", pair[0], pair[1], pair[2]);
        }
        log.info("Применимость из заголовков: позиций {}, строк добавлено {}", parts[0], added);
        return new Parsed(parts[0], added);
    }

    /**
     * @param parts сколько позиций назвали машину в заголовке
     * @param added сколько строк применимости добавлено — повтор не дублирует
     */
    public record Parsed(int parts, int added) {
    }

    /** Добавляет применимость руками — из карточки. Отметка подтверждения стоит. */
    @Transactional
    public boolean addApplicability(long partId, long brandId, Long modelId) {
        return jdbc.update("""
                INSERT INTO part_applicability (part_id, brand_id, model_id, is_verified)
                VALUES (?, ?, ?, true)
                ON CONFLICT (part_id, brand_id, model_id, generation_id, modification_id)
                DO UPDATE SET is_verified = true""", partId, brandId, modelId) > 0;
    }

    @Transactional
    public void removeApplicability(long partId, long applicabilityId) {
        jdbc.update("DELETE FROM part_applicability WHERE id = ? AND part_id = ?",
                applicabilityId, partId);
    }

    /**
     * Заявленная применимость позиции — для карточки.
     *
     * <p>У переехавшего клиента она пуста: на прогонном арендаторе ноль строк
     * при тридцати пяти тысячах позиций. Карточка это и показывает — «не
     * задана», а не пустой список без объяснения: подбор по машине у такой
     * позиции работает только от донора, и если донора тоже нет, найти её
     * по машине нельзя вовсе.
     */
    @Transactional(readOnly = true)
    public List<Applicability> applicabilityOf(long partId) {
        return jdbc.query("""
                SELECT a.id, a.is_verified, b.name AS brand, m.name AS model,
                       g.name AS generation, a.year_from, a.year_to
                  FROM part_applicability a
                  JOIN catalog.brand b ON b.id = a.brand_id
                  LEFT JOIN catalog.model m ON m.id = a.model_id
                  LEFT JOIN catalog.generation g ON g.id = a.generation_id
                 WHERE a.part_id = ?
                 ORDER BY b.name, m.name""",
                (rs, i) -> new Applicability(rs.getLong("id"), rs.getBoolean("is_verified"),
                        rs.getString("brand"), rs.getString("model"), rs.getString("generation"),
                        (Integer) rs.getObject("year_from"), (Integer) rs.getObject("year_to")),
                partId);
    }

    /** Строка применимости: к какой машине заявлена деталь. */
    public record Applicability(long id, boolean verified, String brand, String model,
                                String generation, Integer yearFrom, Integer yearTo) {
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
                  LEFT JOIN catalog.modification mo ON mo.id = d.modification_id
                  LEFT JOIN supply s ON s.id = d.supply_id""";

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
                       -- Поставка и комплектация — для карточки позиции.
                       -- Читаются вместе со строкой: карточка открывается
                       -- по нажатию и второй запрос ради двух полей не делает.
                       CASE WHEN s.id IS NULL THEN NULL
                            ELSE s.kind || ' №' || s.number
                                 || coalesce(' | ' || to_char(s.arrived_on, 'DD.MM.YYYY'), '')
                       END AS supply,
                       nullif(concat_ws(', ',
                           CASE d.steering WHEN 'RIGHT' THEN 'правый руль'
                                           WHEN 'LEFT' THEN 'левый руль' END,
                           CASE d.transmission_type WHEN 'AT' THEN 'АКПП'
                                                    WHEN 'MT' THEN 'МКПП'
                                                    WHEN 'CVT' THEN 'вариатор'
                                                    WHEN 'AMT' THEN 'робот' END,
                           CASE d.drive_type WHEN 'FWD' THEN 'передний'
                                             WHEN 'RWD' THEN 'задний'
                                             WHEN 'AWD' THEN 'полный' END,
                           d.transmission_model,
                           d.equipment_code), '') AS equipment,
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
                        rs.getString("supply"), rs.getString("equipment"),
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
                      String supply, String equipment,
                      Map<Long, BigDecimal> stock) {

        Row withStock(Map<Long, BigDecimal> found) {
            return new Row(id, code, title, qualityGrade, condition, brand, model, generation,
                    yearFrom, yearTo, body, engine, year, donorCode, price, installationPrice,
                    color, description, note, manufacturer, marking, section, sideLr, sideFr,
                    qty, oem, crosses, photoKey, supply, equipment, found);
        }
    }
}
