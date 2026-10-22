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
 * <p><b>Машина — по правилу {@link PartVehicle}:</b> выбранное у позиции
 * из общего справочника машин (слова записей {@code catalog.bazon_*}, задача
 * 0377), иначе вписанное руками, иначе донор (кузов и двигатель — как у приёмки: введённое у донора,
 * иначе поколение и модификация справочника). Применимость в название
 * не идёт: у позиции без донора и без своей машины машины в полях нет.
 *
 * <p><b>Когда название не пересобирается — и почему.</b>
 * <ul>
 *   <li>У позиции нет вида ({@code part_name_id} пуст): колёса (название
 *       из размеров, {@code WheelService.titleOf}) и позиции, заведённые
 *       мимо справочника наименований. Собрать название не из чего.</li>
 *   <li>Позиция перенесена из Bazon ({@code legacy_code}), у неё нет донора
 *       и своей машины (в том числе выбранной из справочника — выбрав её
 *       в карточке, человек пересобирает название по формуле, и это
 *       единственный путь для уже перенесённых). Её машины — списки марок, моделей, кузовов
 *       и двигателей — лежат только в названии: полей под них перенос
 *       не завёл. Пересборка стёрла бы их правкой цены соседнего поля.
 *       Разовый пересчёт ({@link TitleRecount}) их тоже не трогает
 *       и называет в отчёте.</li>
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

    /**
     * Поля формулы, прочитанные из базы: вид, машина позиции, основной номер
     * и есть ли у позиции своя машина. Читает их одно выражение
     * ({@link #fieldsSql}) — и правка карточки, и разовый пересчёт
     * ({@link TitleRecount}): две копии разошлись бы, и пересчитанное
     * название сменилось бы при первой же правке.
     */
    record Fields(String kind, PartTitleGenerator.VehicleTitlePart vehicle,
                  String oem, boolean ownVehicle) {
    }

    /**
     * Выборка полей формулы. Позиция названа {@code p}, вид — {@code pn},
     * донор — {@code d}; как они присоединяются, решает вызывающий
     * ({@code kindJoin}, {@code donorJoin} — условия {@code ON}).
     *
     * <p><b>Год — {@code part.year_text}, иначе число</b> (решение владельца
     * продукта 20.10.2026 к пересчёту 0336: «год — {@code year_text}, иначе
     * число»): поле «Год выпуска» Bazon дословно («03-08»), а без него — своё
     * число позиции, затем год донора. В название идёт первое число
     * ({@link PartTitleGenerator#yearOf}): «03-08» → «03». То же правило,
     * что у названия в файле Дрома (0335, {@code DromTitle.year}).
     */
    static String fieldsSql(String kindJoin, String donorJoin) {
        return """
                SELECT pn.name AS kind,
                       """ + PartVehicle.brand("b.name") + " AS brand,\n"
                + PartVehicle.model("m.name") + " AS model,\n"
                + PartVehicle.body("d.body_code, g.code") + " AS body,\n"
                + PartVehicle.engine("d.engine_code, mo.engine_code") + " AS engine,\n"
                + "coalesce(p.year_text, (" + PartVehicle.year("d.year") + ")::text) AS year,\n"
                + """
                       (SELECT o.raw_number FROM part_oem o
                         WHERE o.part_id = p.id AND o.is_primary
                         ORDER BY o.id LIMIT 1) AS oem,
                       (p.manual_brand IS NOT NULL OR p.manual_model IS NOT NULL
                        OR p.manual_body IS NOT NULL OR p.manual_engine IS NOT NULL
                        OR p.manual_year IS NOT NULL
                        OR p.bazon_mark_id IS NOT NULL OR p.bazon_model_id IS NOT NULL
                        OR p.bazon_body_id IS NOT NULL OR p.bazon_motor_id IS NOT NULL) AS own_vehicle
                  FROM part p
                  LEFT JOIN part_name pn ON """ + " " + kindJoin + "\n"
                + "  LEFT JOIN donor d ON " + donorJoin + "\n"
                + """
                  LEFT JOIN catalog.brand b        ON b.id = d.brand_id
                  LEFT JOIN catalog.model m        ON m.id = d.model_id
                  LEFT JOIN catalog.generation g   ON g.id = d.generation_id
                  LEFT JOIN catalog.modification mo ON mo.id = d.modification_id
                """;
    }

    static Fields fieldsOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Fields(
                rs.getString("kind"),
                new PartTitleGenerator.VehicleTitlePart(
                        rs.getString("brand"), rs.getString("model"),
                        rs.getString("body"), rs.getString("engine"),
                        rs.getString("year")),
                rs.getString("oem"),
                rs.getBoolean("own_vehicle"));
    }

    /**
     * Машины позиции лежат только в её названии: перенесена из Bazon
     * ({@code legacy_code}), ни донора, ни своей машины. Пересборка стёрла бы
     * их — такую позицию не пересобирает ни правка, ни пересчёт.
     */
    static boolean vehicleOnlyInTitle(String legacyCode, Long donorId, boolean ownVehicle) {
        return legacyCode != null && donorId == null && !ownVehicle;
    }

    /** Название по формуле из полей; вид обязан быть. */
    String compose(Fields fields, PartTitleGenerator.Sides sides, PartCondition condition) {
        return generator.generate(fields.kind(), fields.vehicle(), sides, condition, fields.oem());
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
        List<Fields> found = jdbc.query(fieldsSql("pn.id = ?", "d.id = ?") + " WHERE p.id = ?",
                (rs, i) -> fieldsOf(rs),
                part.getPartNameId(), part.getDonorId(), part.getId());
        if (found.isEmpty()) {
            return false;
        }
        Fields fields = found.get(0);
        if (fields.kind() == null
                || vehicleOnlyInTitle(part.getLegacyCode(), part.getDonorId(), fields.ownVehicle())) {
            return false;
        }
        String title = compose(fields,
                new PartTitleGenerator.Sides(part.getSideFr(), part.getSideLr(), part.getSideUd()),
                part.getCondition());
        if (title.equals(part.getTitle())) {
            return false;
        }
        part.setTitle(title);
        return true;
    }
}
