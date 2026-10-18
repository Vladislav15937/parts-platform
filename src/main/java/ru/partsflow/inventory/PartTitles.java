package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Пересборка названия позиции из её полей (задача 0336, решение владельца
 * продукта 19.10.2026: название — производное от полей карточки, как у Bazon,
 * и руками не правится).
 *
 * <p>Поля формулы — вид (написание клиента), машина позиции, год, положение,
 * состояние и основной номер производителя. Правка любого из них в карточке
 * пересобирает название тем же {@link PartTitleGenerator}, что собирал его
 * при приёмке, — двух формул нет.
 *
 * <p><b>Машина — по правилу {@link PartVehicle}:</b> вписанное у позиции
 * руками, иначе донор (кузов и двигатель — как у приёмки: введённое у донора,
 * иначе поколение и модификация справочника). Применимость в название
 * не идёт: у позиции без донора и без своей машины машины в полях нет.
 *
 * <p><b>Когда название не пересобирается — и почему.</b>
 * <ul>
 *   <li>У позиции нет вида ({@code part_name_id} пуст): колёса (название
 *       из размеров, {@code WheelService.titleOf}) и позиции, заведённые
 *       мимо справочника наименований. Собрать название не из чего.</li>
 *   <li>Позиция перенесена из Bazon ({@code legacy_code}), у неё нет донора
 *       и своей машины. Её машины — списки марок, моделей, кузовов
 *       и двигателей — лежат только в названии: полей под них перенос
 *       не завёл. Пересборка стёрла бы их правкой цены соседнего поля.
 *       Это переходное правило до разового пересчёта перенесённых
 *       (0336, пункт 4), который и решит, где эти поля живут.</li>
 * </ul>
 */
@Component
public class PartTitles {

    private final JdbcTemplate jdbc;
    private final PartTitleGenerator generator;

    public PartTitles(JdbcTemplate jdbc, PartTitleGenerator generator) {
        this.jdbc = jdbc;
        this.generator = generator;
    }

    private record Fields(String kind, PartTitleGenerator.VehicleTitlePart vehicle,
                          String oem, boolean ownVehicle) {
    }

    /**
     * Пересобирает название позиции по её нынешним полям.
     *
     * <p>Положение, состояние, донор и «Старые данные» берутся у сущности —
     * правка могла их уже поменять, не сбросив в базу; машина позиции и номер
     * производителя — из базы: их пишет {@link PartIdentity} мимо сущности,
     * в этой же транзакции. Название меняется сущностью, а не SQL: правка
     * уходит в историю «Наименование: … → …», в прайс и в дельту.
     *
     * @return {@code true}, если название стало другим
     */
    @Transactional
    public boolean retitle(Part part) {
        if (part.getPartNameId() == null) {
            return false;
        }
        List<Fields> found = jdbc.query("""
                SELECT pn.name AS kind,
                       """ + PartVehicle.brand("b.name") + " AS brand,\n"
                        + PartVehicle.model("m.name") + " AS model,\n"
                        + PartVehicle.body("d.body_code, g.code") + " AS body,\n"
                        + PartVehicle.engine("d.engine_code, mo.engine_code") + " AS engine,\n"
                        + PartVehicle.year("d.year") + " AS year,\n"
                        + """
                       (SELECT o.raw_number FROM part_oem o
                         WHERE o.part_id = p.id AND o.is_primary
                         ORDER BY o.id LIMIT 1) AS oem,
                       (p.manual_brand IS NOT NULL OR p.manual_model IS NOT NULL
                        OR p.manual_body IS NOT NULL OR p.manual_engine IS NOT NULL
                        OR p.manual_year IS NOT NULL) AS own_vehicle
                  FROM part p
                  JOIN part_name pn ON pn.id = ?
                  LEFT JOIN donor d ON d.id = ?
                  LEFT JOIN catalog.brand b        ON b.id = d.brand_id
                  LEFT JOIN catalog.model m        ON m.id = d.model_id
                  LEFT JOIN catalog.generation g   ON g.id = d.generation_id
                  LEFT JOIN catalog.modification mo ON mo.id = d.modification_id
                 WHERE p.id = ?""",
                (rs, i) -> new Fields(
                        rs.getString("kind"),
                        new PartTitleGenerator.VehicleTitlePart(
                                rs.getString("brand"), rs.getString("model"),
                                rs.getString("body"), rs.getString("engine"),
                                PartTitleGenerator.VehicleTitlePart.year(
                                        (Integer) rs.getObject("year"))),
                        rs.getString("oem"),
                        rs.getBoolean("own_vehicle")),
                part.getPartNameId(), part.getDonorId(), part.getId());
        if (found.isEmpty()) {
            return false;
        }
        Fields fields = found.get(0);
        if (part.getLegacyCode() != null && part.getDonorId() == null && !fields.ownVehicle()) {
            return false;
        }
        String title = generator.generate(fields.kind(), fields.vehicle(),
                new PartTitleGenerator.Sides(part.getSideFr(), part.getSideLr(), part.getSideUd()),
                part.getCondition(), fields.oem());
        if (title.equals(part.getTitle())) {
            return false;
        }
        part.setTitle(title);
        return true;
    }
}
