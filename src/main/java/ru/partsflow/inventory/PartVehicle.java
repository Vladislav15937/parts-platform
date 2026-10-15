package ru.partsflow.inventory;

/**
 * Машина позиции — одно правило на все поверхности, которые её читают
 * (задача 0303): <b>вписанное у позиции руками ({@code part.manual_*},
 * задача 0302), иначе то, что поверхность читала до этого</b> — донора,
 * а без него применимость.
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
        return own("p.manual_brand", otherwise);
    }

    public static String model(String otherwise) {
        return own("p.manual_model", otherwise);
    }

    public static String generation(String otherwise) {
        return own("p.manual_generation", otherwise);
    }

    public static String body(String otherwise) {
        return own("p.manual_body", otherwise);
    }

    public static String engine(String otherwise) {
        return own("p.manual_engine", otherwise);
    }

    public static String year(String otherwise) {
        return own("p.manual_year", otherwise);
    }

    public static String equipment(String otherwise) {
        return own("p.manual_equipment", otherwise);
    }

    public static String donorCode(String otherwise) {
        return own("p.manual_donor_code", otherwise);
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
        return "CASE WHEN p.manual_brand IS NULL THEN " + donorBrandId
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
        return "CASE WHEN p.manual_model IS NULL THEN " + donorModelId
                + " ELSE (SELECT cm.id FROM catalog.model cm"
                + " WHERE cm.brand_id = " + brandId(donorBrandId)
                + " AND lower(cm.name) = lower(btrim(p.manual_model))"
                + " ORDER BY cm.id LIMIT 1) END";
    }

    private static String own(String column, String otherwise) {
        return otherwise == null || otherwise.isBlank()
                ? column : "coalesce(" + column + ", " + otherwise + ")";
    }
}
