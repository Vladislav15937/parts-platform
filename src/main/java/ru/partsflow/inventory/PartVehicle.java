package ru.partsflow.inventory;

/**
 * Машина позиции — одно правило на все поверхности, которые её читают
 * (задача 0303): <b>выбранное у позиции из общего справочника
 * ({@code part.bazon_*_id}, задача 0377), иначе вписанное руками
 * ({@code part.manual_*}, задача 0302), иначе то, что поверхность читала
 * до этого</b> — донора, а без него применимость.
 *
 * <p><b>Слова справочника — подзапросом по ссылке позиции</b>, а не
 * присоединением: правило обязано работать в любом запросе, где позиция
 * названа {@code p}, не требуя нового {@code JOIN} от каждого из них.
 * Ссылка пуста — подзапрос не выполняется. Кузов без кода
 * ({@code bazon_body.name} пуст) слова не даёт, и поле берётся по
 * прежнему правилу. Выбор «по полю», как и у вписанного руками: выбрав
 * только марку, человек получает слова марки и остальное — как раньше.
 *
 * <p><b>Зачем одно место.</b> После 0302 марку, исправленную в карточке,
 * видела витрина, а прайс Дрома, отбор продавца, подбор «что подходит»
 * и отбор выгрузки читали донора своими выражениями: владелец правил
 * машину, система отвечала «сохранено», а в объявлении и у продавца
 * оставалась прежняя. Копия «своё, иначе донор» в каждом из четырёх файлов
 * разошлась бы при следующей правке — например, когда у позиции появится
 * ещё одно своё поле.
 *
 * <p><b>Чем поверхности различаются — тем, что стоит после своего.</b>
 * Витрина без донора показывает свою машину из применимости (0290), прайс —
 * все марки применимости списком, продавец — только донора. Это их прежние
 * решения, и правило их не трогает: оно ставит вписанное <i>впереди</i>.
 * Поэтому методы принимают «иначе» выражением вызывающего.
 *
 * <p><b>Пустое своё — «как у донора или применимости», по полю.</b> Решение
 * 2 из PR 440 владелец не менял: исправив марку и не тронув модель, человек
 * получает свою марку и модель донора. Так и на витрине, и здесь.
 *
 * <p>Позиция в запросе названа {@code p}.
 */
public final class PartVehicle {

    private PartVehicle() {
    }

    public static String brand(String otherwise) {
        return own(ref("bazon_mark", "p.bazon_mark_id"), "p.manual_brand", otherwise);
    }

    public static String model(String otherwise) {
        return own(ref("bazon_model", "p.bazon_model_id"), "p.manual_model", otherwise);
    }

    public static String generation(String otherwise) {
        return own(null, "p.manual_generation", otherwise);
    }

    public static String body(String otherwise) {
        return own(ref("bazon_body", "p.bazon_body_id"), "p.manual_body", otherwise);
    }

    public static String engine(String otherwise) {
        return own(ref("bazon_motor", "p.bazon_motor_id"), "p.manual_engine", otherwise);
    }

    public static String year(String otherwise) {
        return own(null, "p.manual_year", otherwise);
    }

    public static String equipment(String otherwise) {
        return own(null, "p.manual_equipment", otherwise);
    }

    public static String donorCode(String otherwise) {
        return own(null, "p.manual_donor_code", otherwise);
    }

    /**
     * Марка позиции номером справочника — для отборов, которые сравнивают
     * номера ({@code brand_id}): подбор по машине и отбор выгрузки по маркам.
     *
     * <p>Своя марка — текст человека, номера у неё нет. Она сопоставляется
     * со справочником <b>по названию без учёта регистра и пробелов по
     * краям</b>: «lexus» и «Lexus» — одна марка. Не нашлась (опечатка,
     * «Лексус» кириллицей) — номера нет, и позиция не попадает ни в «только
     * Lexus», ни в «только Toyota» донора: своё сильнее донора и тогда, когда
     * оно справочнику незнакомо. Витрина и прайс при этом покажут её словами
     * человека.
     *
     * <p>Подзапрос стоит в ветке {@code CASE} и считается только у позиций
     * со своей маркой; у остальных — номер донора, как до правки.
     *
     * @param donorBrandId номер марки донора в запросе вызывающего
     */
    public static String brandId(String donorBrandId) {
        return "CASE WHEN p.bazon_mark_id IS NOT NULL THEN (SELECT bm.brand_id"
                + " FROM catalog.bazon_mark bm WHERE bm.id = p.bazon_mark_id)"
                + " WHEN p.manual_brand IS NULL THEN " + donorBrandId
                + " ELSE (SELECT cb.id FROM catalog.brand cb"
                + " WHERE lower(cb.name) = lower(btrim(p.manual_brand))"
                + " ORDER BY cb.id LIMIT 1) END";
    }

    /**
     * Модель позиции номером справочника — среди моделей её марки
     * ({@link #brandId}), тем же сопоставлением по названию.
     *
     * @param donorModelId номер модели донора в запросе вызывающего
     * @param donorBrandId номер марки донора — для поиска своей модели
     *                     у марки позиции
     */
    public static String modelId(String donorModelId, String donorBrandId) {
        return "CASE WHEN p.bazon_model_id IS NOT NULL THEN (SELECT bmo.model_id"
                + " FROM catalog.bazon_model bmo WHERE bmo.id = p.bazon_model_id)"
                + " WHEN p.manual_model IS NULL THEN " + donorModelId
                + " ELSE (SELECT cm.id FROM catalog.model cm"
                + " WHERE cm.brand_id = " + brandId(donorBrandId)
                + " AND lower(cm.name) = lower(btrim(p.manual_model))"
                + " ORDER BY cm.id LIMIT 1) END";
    }

    /** Слово записи справочника по ссылке позиции; кузов без кода — пусто. */
    private static String ref(String table, String column) {
        return "(SELECT r.name FROM catalog." + table + " r WHERE r.id = " + column + ")";
    }

    private static String own(String ref, String column, String otherwise) {
        String head = ref == null ? column : ref + ", " + column;
        return otherwise == null || otherwise.isBlank()
                ? (ref == null ? column : "coalesce(" + head + ")")
                : "coalesce(" + head + ", " + otherwise + ")";
    }
}
