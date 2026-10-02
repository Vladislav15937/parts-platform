package ru.partsflow.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Состояние запчасти — то, чем товар маркируют для покупателя.
 *
 * <p><b>Название человеку живёт здесь же, а не рядом.</b> До задачи 0039
 * слова были написаны в пяти местах: {@code CASE} витрины, такой же
 * {@code CASE} у вкладки колёс, словарь журнала изменений и два словаря
 * на фронтенде. Совпадали они только потому, что их писал один человек
 * в один день, — а разойдись одна буква, и выбранное из списка значение
 * перестаёт находить что-либо, причём причина видна только в SQL. Этот
 * случай в проекте уже оплачен оценкой состояния: там словарей было три
 * и все три разные (задача 0033, {@link QualityGrade}).
 *
 * <p>Поэтому код, слово и пометка заголовка лежат в одном месте, а
 * выражение для SQL собирается из них же.
 */
public enum PartCondition {

    NEW("новая", "(новая)"),

    USED("б/у", "(б/у)"),

    /**
     * Ремонтировалась.
     *
     * <p>Не то же, что контрактная, и путать их нельзя: контрактную никто
     * не ремонтировал вовсе. На разборке это разные товары и разная цена.
     */
    REFURBISHED("восстановленная", "(восст.)"),

    /**
     * Снята с целой машины, привезённой из-за границы, и не ремонтировалась.
     *
     * <p>Решение владельца продукта от 8 сентября 2026 ({@code tasks/0039}):
     * «состояние должно быть и клиент должен иметь возможность его менять».
     * Это не частный случай б/у: у переехавшего клиента контрактных
     * 9 417 позиций из 35 841 — они приходят контейнером без донора,
     * и клиент маркирует их для покупателя отдельно.
     */
    CONTRACT("контрактная", "(контракт)");

    private final String title;
    private final String titleMark;

    PartCondition(String title, String titleMark) {
        this.title = title;
        this.titleMark = titleMark;
    }

    /**
     * Как состояние называется человеку.
     *
     * <p>Строчными и в женском роде — согласовано с «деталью»: так стоят
     * три слова, которые клиент уже видит в колонке витрины и в отборе.
     * Журнал изменений поднимает первую букву сам
     * ({@code shared/AuditedColumns}): там это значение поля в таблице,
     * а не слово посреди фразы.
     */
    public String title() {
        return title;
    }

    /**
     * Пометка в собранном заголовке: «Фара Toyota Camry 2007 лев. (б/у)».
     *
     * <p>Живёт рядом со словом, а не в {@link PartTitleGenerator}: иначе
     * при четвёртом значении пометку забыли бы дописать, а заголовок
     * собирается у каждой принятой детали.
     */
    public String titleMark() {
        return titleMark;
    }

    /** Словарь целиком: по нему сверяются слова экрана и слова сервера. */
    public static Map<String, String> titles() {
        Map<String, String> titles = new LinkedHashMap<>();
        for (PartCondition condition : values()) {
            titles.put(condition.name(), condition.title);
        }
        return titles;
    }

    /**
     * Все пометки заголовка — их вычищает выгрузка на площадку.
     *
     * <p>Площадка требует наименование без сокращений, и сторона с
     * состоянием уезжают туда своими полями; в заголовке они повторение.
     * Список берётся отсюда, а не пишется регуляркой на месте: забытая
     * в ней пометка уехала бы покупателю в названии товара.
     */
    public static List<String> titleMarks() {
        return java.util.Arrays.stream(values()).map(PartCondition::titleMark).toList();
    }

    /**
     * То же выражением SQL: {@code alias} — псевдоним таблицы {@code part}.
     *
     * <p>Запасной ветки нет намеренно, как и у {@link QualityGrade}:
     * колонку держит {@code part_condition_ck}, и появиться там чужое
     * значение может только правкой базы мимо приложения.
     */
    public static String sqlLabel(String alias) {
        StringBuilder sql = new StringBuilder("CASE ").append(alias).append(".condition");
        for (PartCondition condition : values()) {
            sql.append(" WHEN '").append(condition.name()).append("' THEN '")
                    .append(condition.title).append("'");
        }
        return sql.append(" END").toString();
    }
}
