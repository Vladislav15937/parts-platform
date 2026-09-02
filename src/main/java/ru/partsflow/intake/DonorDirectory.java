package ru.partsflow.intake;

import ru.partsflow.shared.SupplyKinds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Список машин целиком — для экрана машин, а не для приёмки.
 *
 * <p><b>Отличается от справочника приёмки составом, и это не дублирование.</b>
 * Приёмщику показывают только то, что в разборе: деталь, снятая с машины,
 * которую ещё везут, — это ошибка выбора, а не работа. Владельцу нужны все:
 * купленную машину он ставит в разбор, и за неё же платит эвакуатору
 * до того, как с неё снимут первую деталь.
 */
@Service
public class DonorDirectory {

    private final JdbcTemplate jdbc;

    public DonorDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Внутри транзакции обязательно: {@code search_path} выставляет провайдер
     * соединений Hibernate, и запрос снаружи уходит в {@code public}.
     */
    @Transactional(readOnly = true)
    public List<Entry> all() {
        return jdbc.query(LIST + " ORDER BY d.id DESC", ROW);
    }

    /**
     * Машины одной поставки: контейнер разбирают целиком, и «что пришло этой
     * партией» — вопрос, ради которого поставки и заведены.
     *
     * <p><b>Тот же запрос, что и список машин, а не свой.</b> Своим он отдавал
     * бы машину под другим именем: {@code DonorView} несёт внутренний
     * {@code public_code}, и экран показал бы владельцу столбец
     * шестнадцатеричных кодов, которых он никогда не видел. Ровно на этом
     * краснел отчёт окупаемости, пока не начал звать машину так, как её зовёт
     * клиент. Две подписи одной машины разошлись бы на первой же правке.
     */
    @Transactional(readOnly = true)
    public List<Entry> ofSupply(long supplyId) {
        return jdbc.query(LIST + " WHERE d.supply_id = ? ORDER BY d.id DESC", ROW, supplyId);
    }

    private static final String LIST = """
            SELECT d.id, d.public_code, d.legacy_code, d.vin, d.year, d.status, d.note,
                   d.location,
                   b.name AS brand, m.name AS model
              FROM donor d
              LEFT JOIN catalog.brand b ON b.id = d.brand_id
              LEFT JOIN catalog.model m ON m.id = d.model_id""";

    private static final RowMapper<Entry> ROW = (rs, i) -> new Entry(
            rs.getLong("id"),
            // Тот же выбор, что в карточке машины и в отчёте
            // окупаемости: клиент зовёт машину своим номером.
            rs.getString("legacy_code") == null
                    ? rs.getString("public_code") : rs.getString("legacy_code"),
            rs.getString("brand"),
            rs.getString("model"),
            rs.getObject("year") == null ? null : rs.getInt("year"),
            rs.getString("vin"),
            rs.getString("status"),
            rs.getString("note"),
            rs.getString("location"));

    /**
     * Машина, с которой снята позиция, — для её карточки.
     *
     * <p>До этого карточка сообщала «Донор задан» и на этом заканчивалась:
     * отметка о том, что данные есть, вместо самих данных. А продавец
     * по телефону отвечает именно ими — «правый руль, АКПП, передний привод,
     * пробег 85 тысяч»: подойдёт ли деталь, решает не марка, а комплектация
     * машины, с которой её сняли.
     *
     * <p><b>Запрос по позиции, а не по машине.</b> Карточка знает позицию,
     * и лишний идентификатор в витринной строке ради одного экрана — это ещё
     * одно поле, которое придётся протаскивать через обе таблицы, включая
     * колёсную.
     *
     * <p>Число снятых деталей считается тут же подзапросом. Формально это
     * склад, а не приёмка, но читающая модель одного экрана — не повод
     * заводить событие: витрина точно так же join'ит машину, чтобы показать
     * марку.
     */
    @Transactional(readOnly = true)
    public Optional<Card> cardByPart(long partId) {
        List<Card> found = jdbc.query("""
                SELECT d.id, d.public_code, d.legacy_code, d.status, d.location, d.vin,
                       d.year, d.body_code, d.engine_code, d.steering, d.drive_type,
                       d.transmission_type, d.transmission_model, d.color, d.color_code,
                       d.equipment_code, d.mileage_km, d.note,
                       b.name AS brand, m.name AS model, g.name AS generation,
                       s.kind AS supply_kind, s.number AS supply_number,
                       (SELECT count(*) FROM part sibling WHERE sibling.donor_id = d.id)
                           AS parts_count
                  FROM part p
                  JOIN donor d ON d.id = p.donor_id
                  LEFT JOIN catalog.brand b      ON b.id = d.brand_id
                  LEFT JOIN catalog.model m      ON m.id = d.model_id
                  LEFT JOIN catalog.generation g ON g.id = d.generation_id
                  LEFT JOIN supply s             ON s.id = d.supply_id
                 WHERE p.id = ?""",
                (rs, i) -> new Card(
                        rs.getLong("id"),
                        // Клиент помнит машину по своему номеру, а не по нашему:
                        // в отчёте окупаемости это уже учтено, тут то же самое.
                        rs.getString("legacy_code") == null
                                ? rs.getString("public_code") : rs.getString("legacy_code"),
                        label(STATUSES, rs.getString("status")),
                        supply(rs.getString("supply_kind"), rs.getString("supply_number")),
                        rs.getString("brand"),
                        rs.getString("model"),
                        rs.getString("generation"),
                        rs.getString("body_code"),
                        rs.getString("engine_code"),
                        rs.getObject("year") == null ? null : rs.getInt("year"),
                        label(STEERING, rs.getString("steering")),
                        transmission(rs.getString("transmission_type"),
                                rs.getString("transmission_model")),
                        label(DRIVE, rs.getString("drive_type")),
                        color(rs.getString("color"), rs.getString("color_code")),
                        rs.getString("equipment_code"),
                        rs.getObject("mileage_km") == null ? null : rs.getInt("mileage_km"),
                        rs.getString("vin"),
                        rs.getString("location"),
                        rs.getString("note"),
                        rs.getInt("parts_count")),
                partId);
        return found.stream().findFirst();
    }

    private static final Map<String, String> STATUSES = Map.of(
            "PURCHASED", "Куплена", "DISMANTLING", "В разборе",
            "DISMANTLED", "Разобрана", "WRITTEN_OFF", "Списана");

    private static final Map<String, String> STEERING =
            Map.of("LEFT", "Левый руль", "RIGHT", "Правый руль");

    private static final Map<String, String> DRIVE =
            Map.of("FWD", "Передний", "RWD", "Задний", "AWD", "Полный");

    /**
     * Поставка словами, а не кодом.
     *
     * <p>Рядом в этой же карточке руль, коробка и привод уже разложены
     * по-русски — а поставка уходила как «CONTAINER №16». Это внутреннее
     * представление на экране, ровно то, чего избегает выгрузка витрины,
     * где стороны пишутся «Задн.» и «Лев.», а не `REAR` и `LEFT`.
     *
     * <p>Словарь общий ({@link SupplyKinds}), а не свой: своя копия здесь
     * и была причиной того, что починили одну поверхность из семи.
     */
    private static String supply(String kind, String number) {
        if (number == null) {
            return null;
        }
        return SupplyKinds.label(kind, number);
    }

    private static final Map<String, String> TRANSMISSIONS = Map.of(
            "AT", "АКПП", "MT", "МКПП", "CVT", "Вариатор", "AMT", "Робот");

    /** «АКПП, U340E-05A»: модель коробки без её типа человеку ничего не говорит. */
    private static String transmission(String type, String model) {
        String kind = label(TRANSMISSIONS, type);
        if (kind == null) {
            return model;
        }
        return model == null || model.isBlank() ? kind : kind + ", " + model;
    }

    /** «Серебро (1D9)»: по коду подбирают краску, по названию — узнают на площадке. */
    private static String color(String name, String code) {
        if (name == null || name.isBlank()) {
            return code;
        }
        return code == null || code.isBlank() ? name : "%s (%s)".formatted(name, code);
    }

    /**
     * {@code Map.of} падает на чтении по {@code null}-ключу, а не отвечает
     * «нет такого»: у машины без коробки тип пуст, и карточка валилась бы
     * пятисоткой на первой такой.
     */
    private static String label(Map<String, String> dictionary, String key) {
        return key == null ? null : dictionary.getOrDefault(key, key);
    }

    /**
     * @param code номер, которым машину зовёт клиент: свой, если он есть,
     *             и наш внутренний, если машину завели уже у нас
     */
    public record Entry(long id, String code, String brand, String model,
                        Integer year, String vin, String status, String note,
                        String location) {
    }

    /**
     * @param code       номер, которым машину зовёт клиент
     * @param partsCount сколько деталей с неё снято — по этому числу видно,
     *                   разобрана машина или только начата
     */
    public record Card(long id, String code, String status, String supply,
                       String brand, String model, String generation,
                       String bodyCode, String engineCode, Integer year,
                       String steering, String transmission, String driveType,
                       String color, String equipmentCode, Integer mileageKm,
                       String vin, String location, String note, int partsCount) {
    }
}
