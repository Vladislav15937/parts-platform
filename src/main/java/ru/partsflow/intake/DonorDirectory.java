package ru.partsflow.intake;

import ru.partsflow.shared.SupplyKinds;
import ru.partsflow.shared.ValueWords;
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
            SELECT d.id, d.number, d.vin, d.year, d.status, d.note,
                   d.location, ds.name AS status_name,
                   b.name AS brand, m.name AS model
              FROM donor d
              LEFT JOIN catalog.brand b ON b.id = d.brand_id
              LEFT JOIN catalog.model m ON m.id = d.model_id
              LEFT JOIN donor_status ds ON ds.id = d.status_id""";

    private static final RowMapper<Entry> ROW = (rs, i) -> new Entry(
            rs.getLong("id"),
            // Номер донора (tenant/108): у перенесённой — её номер из Bazon,
            // у заведённой у нас — порядковый. Внутренний public_code
            // наружу не уходит (задача 0364, п. 6).
            rs.getString("number"),
            rs.getString("brand"),
            rs.getString("model"),
            rs.getObject("year") == null ? null : rs.getInt("year"),
            rs.getString("vin"),
            rs.getString("status"),
            rs.getString("note"),
            rs.getString("location"),
            rs.getString("status_name"));

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
        return jdbc.query(CARD + " WHERE d.id = (SELECT donor_id FROM part WHERE id = ?)",
                CARD_ROW, partId).stream().findFirst();
    }

    /**
     * Та же карточка по самой машине — для экрана «Машина» (задача 0364):
     * плитка «N запчастей — Посмотреть» и строки машины, как у Bazon.
     *
     * <p><b>Один запрос на обе поверхности, а не свой.</b> Карточка позиции
     * и карточка машины показывают одну машину одними словами; свой запрос
     * назвал бы её иначе на первой же правке словаря.
     */
    @Transactional(readOnly = true)
    public Optional<Card> cardOf(long donorId) {
        return jdbc.query(CARD + " WHERE d.id = ?", CARD_ROW, donorId).stream().findFirst();
    }

    /*
     * Число запчастей считается по линии товара PART: «Посмотреть» ведёт
     * в список склада, а колёса в него не попадают вовсе (у них своя вкладка),
     * и число на плитке разошлось бы с числом строк, к которым оно ведёт.
     */
    private static final String CARD = """
            SELECT d.id, d.number, d.status, d.location, d.vin,
                   d.year, d.body_code, d.engine_code, d.steering, d.drive_type,
                   d.transmission_type, d.transmission_model, d.color, d.color_code,
                   d.equipment_code, d.mileage_km, d.note,
                   d.restyling, d.client_description, d.video_url, ds.name AS status_name,
                   b.name AS brand, m.name AS model, g.name AS generation,
                   s.kind AS supply_kind, s.number AS supply_number,
                   (SELECT count(*) FROM part sibling
                     WHERE sibling.donor_id = d.id AND sibling.product_line = 'PART')
                       AS parts_count
              FROM donor d
              LEFT JOIN catalog.brand b      ON b.id = d.brand_id
              LEFT JOIN catalog.model m      ON m.id = d.model_id
              LEFT JOIN catalog.generation g ON g.id = d.generation_id
              LEFT JOIN supply s             ON s.id = d.supply_id
              LEFT JOIN donor_status ds      ON ds.id = d.status_id""";

    private static final Map<String, String> STATUSES = Map.of(
            "PURCHASED", "Куплена", "DISMANTLING", "В разборе",
            "DISMANTLED", "Разобрана", "WRITTEN_OFF", "Списана");

    private static final RowMapper<Card> CARD_ROW = (rs, i) -> new Card(
                rs.getLong("id"),
                // Номер донора — тот же, что в списке машин и в колонке склада.
                rs.getString("number"),
                label(STATUSES, rs.getString("status")),
                supply(rs.getString("supply_kind"), rs.getString("supply_number")),
                rs.getString("brand"),
                rs.getString("model"),
                rs.getString("generation"),
                rs.getString("body_code"),
                rs.getString("engine_code"),
                rs.getObject("year") == null ? null : rs.getInt("year"),
                ValueWords.steering(rs.getString("steering")),
                transmission(rs.getString("transmission_type"),
                        rs.getString("transmission_model")),
                ValueWords.drive(rs.getString("drive_type")),
                color(rs.getString("color"), rs.getString("color_code")),
                rs.getString("equipment_code"),
                rs.getObject("mileage_km") == null ? null : rs.getInt("mileage_km"),
                rs.getString("vin"),
                rs.getString("location"),
                rs.getString("note"),
                rs.getInt("parts_count"),
                rs.getString("status_name"),
                rs.getString("restyling"),
                rs.getString("client_description"),
                rs.getString("video_url"));

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

    /**
     * «АКПП, U340E-05A»: модель коробки без её типа человеку ничего не говорит.
     *
     * <p>Тип словом — общим словарём {@link ValueWords}, а не своим: своя копия
     * здесь писала «Вариатор» с заглавной, «Комплектация» склада — «вариатор»,
     * а {@code DCT} не знала ни одна (решение владельца 24.10.2026 — «Робот DSG»).
     */
    private static String transmission(String type, String model) {
        String kind = ValueWords.transmission(type);
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
     * @param code       номер донора ({@code donor.number}): у перенесённой —
     *                   её номер из Bazon, у заведённой у нас — порядковый
     * @param statusName статус из списка клиента ({@code donor_status});
     *                   {@code status} рядом — состояние приёмки кодом
     */
    public record Entry(long id, String code, String brand, String model,
                        Integer year, String vin, String status, String note,
                        String location, String statusName) {
    }

    /**
     * @param code       номер, которым машину зовёт клиент
     * @param partsCount сколько деталей с неё снято — по этому числу видно,
     *                   разобрана машина или только начата
     * @param statusName статус из списка клиента; {@code status} — состояние
     *                   приёмки словом («В разборе»)
     */
    public record Card(long id, String code, String status, String supply,
                       String brand, String model, String generation,
                       String bodyCode, String engineCode, Integer year,
                       String steering, String transmission, String driveType,
                       String color, String equipmentCode, Integer mileageKm,
                       String vin, String location, String note, int partsCount,
                       String statusName, String restyling, String clientDescription,
                       String videoUrl) {
    }
}
