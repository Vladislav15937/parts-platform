package ru.partsflow.intake;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Справочники для приёмки с телефона — одним запросом.
 *
 * <p><b>Почему одним, а не пятью.</b> Телефон забирает их перед выходом
 * к стеллажам, и связь в ангаре плохая. Пять запросов означают пять шансов
 * оборваться и пять частично заполненных кэшей, из которых непонятно, можно
 * ли работать. Один запрос либо доехал целиком, либо не доехал вовсе.
 *
 * <p><b>Проекции, а не сущности.</b> Телефону нужны имена и коды, а не агрегаты:
 * тянуть доноров сущностями с их двадцатью полями значит гонять по мобильной
 * сети то, что там не покажут. Отсюда JdbcTemplate и плоские записи —
 * тот же приём, что для фидов и отчётов.
 */
@Service
public class IntakeReferenceService {

    /**
     * Наименований у клиента, с которого снята карта функционала, 2 259 —
     * это сотни килобайт JSON, и они нужны целиком для подсказок ввода.
     * Предел на случай, если у кого-то их окажется на порядок больше:
     * лучше урезанные подсказки, чем несколько мегабайт по мобильной сети.
     */
    private static final int MAX_PART_NAMES = 5_000;

    private final JdbcTemplate jdbc;

    public IntakeReferenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Reference load() {
        return new Reference(Instant.now(), warehouses(), supplies(), donors(), partNames());
    }

    /**
     * Склады с ячейками. Одним запросом с соединением, а не складом на запрос:
     * ячеек у клиента тысячи, но на телефон они уезжают один раз.
     */
    private List<Warehouse> warehouses() {
        List<Warehouse> result = new ArrayList<>();
        jdbc.query("""
                SELECT w.id AS warehouse_id, w.name AS warehouse_name,
                       c.id AS cell_id, c.code AS cell_code, c.zone
                  FROM warehouse w
                  LEFT JOIN storage_cell c ON c.warehouse_id = w.id AND c.is_active
                 WHERE w.is_active
                 ORDER BY w.name, c.code""",
                rs -> {
                    long warehouseId = rs.getLong("warehouse_id");
                    Warehouse current = result.isEmpty() ? null : result.get(result.size() - 1);
                    if (current == null || current.id() != warehouseId) {
                        current = new Warehouse(warehouseId, rs.getString("warehouse_name"),
                                new ArrayList<>());
                        result.add(current);
                    }
                    if (rs.getObject("cell_id") != null) {
                        current.cells().add(new Cell(rs.getLong("cell_id"),
                                rs.getString("cell_code"), rs.getString("zone")));
                    }
                });
        return result;
    }

    /**
     * Поставки, в которые можно принимать: прибывшие и ещё в пути.
     *
     * <p>Ожидаемые тоже нужны: контейнер заводят заранее, и деталь из него могут
     * начать принимать до того, как кто-то отметит поставку прибывшей.
     */
    private List<SupplyRef> supplies() {
        return jdbc.query("""
                SELECT id, kind, number, supplier_name, status, arrived_on
                  FROM supply
                 WHERE status IN ('EXPECTED', 'IN_TRANSIT', 'ARRIVED')
                 ORDER BY arrived_on DESC NULLS LAST, id DESC""",
                (rs, i) -> new SupplyRef(
                        rs.getLong("id"),
                        rs.getString("kind"),
                        rs.getString("number"),
                        rs.getString("supplier_name"),
                        rs.getString("status"),
                        rs.getDate("arrived_on") == null
                                ? null : rs.getDate("arrived_on").toLocalDate()));
    }

    /**
     * Машины, с которых снимают детали: в разборе и разобранные.
     *
     * <p>Разобранные остаются в списке: снять с машины забытую деталь через
     * неделю после закрытия разбора — обычное дело, и отсутствие её в списке
     * означает, что приёмщик заведёт деталь без донора.
     */
    private List<DonorRef> donors() {
        return jdbc.query("""
                SELECT d.id, d.public_code, d.vin, d.year, d.status, d.location,
                       b.name AS brand, m.name AS model
                  FROM donor d
                  LEFT JOIN catalog.brand b ON b.id = d.brand_id
                  LEFT JOIN catalog.model m ON m.id = d.model_id
                 WHERE d.status IN ('DISMANTLING', 'DISMANTLED')
                 ORDER BY d.id DESC""",
                (rs, i) -> new DonorRef(
                        rs.getLong("id"),
                        rs.getString("public_code"),
                        rs.getString("brand"),
                        rs.getString("model"),
                        rs.getObject("year") == null ? null : rs.getInt("year"),
                        rs.getString("vin"),
                        rs.getString("status"),
                        rs.getString("location")));
    }

    /**
     * Наименования арендатора для подсказок ввода.
     *
     * <p>Подсказки важнее, чем кажется: приёмщик, которому предложили «фара
     * левая» из уже существующих, не заведёт «фара лев.» двадцать первым
     * написанием. Список нераспознанных растёт ровно из отсутствия подсказок.
     *
     * <p>Порядок по частоте использования: сверху то, что пишут каждый день.
     */
    private List<PartNameRef> partNames() {
        return jdbc.query("""
                SELECT id, name, match_status, usage_count
                  FROM part_name
                 ORDER BY usage_count DESC, name
                 LIMIT ?""",
                (rs, i) -> new PartNameRef(
                        rs.getLong("id"),
                        rs.getString("name"),
                        !"UNMATCHED".equals(rs.getString("match_status")),
                        rs.getInt("usage_count")),
                MAX_PART_NAMES);
    }

    /**
     * Всё, что нужно приёмщику офлайн.
     *
     * @param loadedAt момент выгрузки: телефон показывает его приёмщику, чтобы
     *                 тот понимал, насколько свежи справочники
     */
    public record Reference(Instant loadedAt,
                            List<Warehouse> warehouses,
                            List<SupplyRef> supplies,
                            List<DonorRef> donors,
                            List<PartNameRef> partNames) {
    }

    public record Warehouse(long id, String name, List<Cell> cells) {
    }

    public record Cell(long id, String code, String zone) {
    }

    public record SupplyRef(long id, String kind, String number, String supplierName,
                            String status, LocalDate arrivedOn) {
    }

    public record DonorRef(long id, String publicCode, String brand, String model,
                           Integer year, String vin, String status, String location) {
    }

    /** @param matched сопоставлено ли с эталоном — приёмщику видно, что распознано */
    public record PartNameRef(long id, String name, boolean matched, int usageCount) {
    }
}
