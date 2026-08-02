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

    public WheelService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                                            hub_bore, brand, model,
                                            marking_type, tread_type, run_flat, light_truck,
                                            speed_index, load_index)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?)""",
                    partId, request.kind(), setNo, request.diameter(),
                    request.tyreWidth(), request.tyreHeight(), request.construction(),
                    request.tyreType(), request.season(), request.wearMm(), request.madeYear(),
                    request.discType(), request.discWidth(), request.offsetMm(),
                    request.boltPattern(), request.hubBore(), request.brand(), request.model(),
                    request.markingType(), request.treadType(), request.runFlat(),
                    request.lightTruck(), request.speedIndex(), request.loadIndex());

            // Остаток появляется только движением: писать qty_on_hand напрямую
            // нельзя, его ведёт триггер.
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta,
                                                to_warehouse_id, created_by)
                    VALUES (?, 'INTAKE', 1, ?, ?)""", partId, warehouseId, createdBy);

            ids.add(partId);
        }
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
    public List<WheelRow> list(int limit) {
        List<WheelRow> rows = jdbc.query("""
                SELECT p.id, p.public_code, p.title, p.price, p.status, p.qty_on_hand,
                       p.condition, p.description, p.note, p.section, p.is_published,
                       p.barcode, p.legacy_code, p.created_at, p.updated_at,
                       p.price_changed_at,
                       w.kind, w.set_no, w.diameter, w.tyre_width, w.tyre_height,
                       w.construction, w.tyre_type, w.season, w.wear_mm, w.made_year,
                       w.disc_type, w.disc_width, w.offset_mm, w.bolt_pattern, w.hub_bore,
                       w.brand, w.model,
                       w.marking_type, w.tread_type, w.run_flat, w.light_truck,
                       w.speed_index, w.load_index,
                       pn.name AS part_name,
                       d.legacy_code AS donor_legacy, d.public_code AS donor_code,
                       (SELECT tm.display_name FROM tenant_member tm
                         WHERE tm.id = p.updated_by)       AS updated_by_name,
                       (SELECT tm.display_name FROM tenant_member tm
                         WHERE tm.id = p.price_changed_by) AS price_changed_by_name,
                       (SELECT count(*) FROM part_photo ph2
                         WHERE ph2.part_id = p.id)         AS photo_count,
                       (SELECT o.raw_number FROM part_oem o
                         WHERE o.part_id = p.id AND o.is_primary LIMIT 1) AS oem,
                       CASE WHEN s.id IS NULL THEN NULL
                            ELSE s.kind || ' №' || s.number
                                 || coalesce(' | ' || to_char(s.arrived_on, 'DD.MM.YYYY'), '')
                       END AS supply,
                       (SELECT ph.s3_key FROM part_photo ph
                         WHERE ph.part_id = p.id ORDER BY ph.is_main DESC, ph.sort_order,
                               ph.id LIMIT 1) AS photo_key
                  FROM part p
                  JOIN part_wheel w ON w.part_id = p.id
                  LEFT JOIN part_name pn ON pn.id = p.part_name_id
                  LEFT JOIN donor d ON d.id = p.donor_id
                  LEFT JOIN supply s ON s.id = p.supply_id
                 WHERE p.product_line = 'WHEEL'
                 ORDER BY w.set_no DESC NULLS LAST, p.id DESC
                 LIMIT ?""",
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
                limit);
        return withStock(rows);
    }

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
     */
    static String titleOf(WheelRequest r) {
        StringBuilder title = new StringBuilder("TYRE".equals(r.kind()) ? "Шина" : "Диск");

        if ("TYRE".equals(r.kind())) {
            if (r.tyreWidth() != null && r.tyreHeight() != null) {
                title.append(' ').append(r.tyreWidth()).append('/').append(r.tyreHeight());
            }
            if (r.diameter() != null) {
                title.append(' ').append(r.construction() == null ? "R" : r.construction())
                        .append(plain(r.diameter()));
            }
        } else {
            if (r.discType() != null) {
                title.append(' ').append(r.discType());
            }
            if (r.discWidth() != null && r.diameter() != null) {
                title.append(' ').append(plain(r.discWidth())).append('x').append(plain(r.diameter()));
            }
            if (r.boltPattern() != null) {
                title.append(' ').append(r.boltPattern());
            }
            if (r.offsetMm() != null) {
                title.append(" ET").append(r.offsetMm());
            }
        }

        if (r.brand() != null && !r.brand().isBlank()) {
            title.append(' ').append(r.brand().strip());
        }
        if (r.model() != null && !r.model().isBlank()) {
            title.append(' ').append(r.model().strip());
        }
        if ("TYRE".equals(r.kind()) && r.season() != null) {
            title.append(' ').append(switch (r.season()) {
                case "SUMMER" -> "летняя";
                case "WINTER" -> "зимняя";
                default -> "всесезонная";
            });
        }
        return title.toString();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * @param kind {@code TYRE} или {@code DISC}
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
                    markingType, treadType, runFlat, lightTruck, speedIndex, loadIndex,
                    partName, condition, supply, donorCode, oem, description, note, section,
                    published, barcode, legacyCode, photoCount, createdAt, updatedAt,
                    updatedByName, priceChangedAt, priceChangedByName, photoKey, byWarehouse);
        }
    }
}
