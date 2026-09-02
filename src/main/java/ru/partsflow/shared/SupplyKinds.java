package ru.partsflow.shared;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Виды поставки словами — один словарь на все поверхности.
 *
 * <p><b>Зачем он общий.</b> «CONTAINER №18» на экране — это внутреннее
 * представление перед человеком, ровно то, чего избегает выгрузка витрины,
 * где стороны пишутся «Задн.» и «Лев.», а не {@code REAR} и {@code LEFT}.
 * Один раз это уже чинилось — в карточке машины, где рядом руль, коробка
 * и привод давно разложены по-русски, — и почини́ли тогда одну поверхность
 * из семи. Остальные шесть продолжали показывать код: витрина склада
 * (таблица, список значений отбора и скачанный файл), вкладка колёс
 * (те же три) и история карточки.
 *
 * <p>Поэтому словарь один и лежит в общем месте, а выражение для SQL
 * собирается из него же: второй список рядом разошёлся бы с первым на первой
 * правке, и появился бы вид поставки, который одна поверхность называет
 * словом, а другая кодом.
 */
public final class SupplyKinds {

    private static final Map<String, String> TITLES = new LinkedHashMap<>();

    static {
        TITLES.put("CONTAINER", "Контейнер");
        TITLES.put("PURCHASE", "Закупка");
        TITLES.put("OTHER", "Поставка");
    }

    private SupplyKinds() {
    }

    /** Словарь целиком: по нему сверяются слова экрана и слова сервера. */
    public static Map<String, String> titles() {
        return Map.copyOf(TITLES);
    }

    /** Вид словом; незнакомый код возвращается как есть, а не теряется. */
    public static String title(String kind) {
        return kind == null ? null : TITLES.getOrDefault(kind, kind);
    }

    /** Подпись поставки для человека: «Контейнер №18». */
    public static String label(String kind, String number) {
        return number == null ? null : title(kind) + " №" + number;
    }

    /**
     * То же выражением SQL, чтобы подпись собиралась в том же запросе,
     * что и строка: {@code alias} — псевдоним таблицы {@code supply}.
     *
     * <p>Собирается из словаря выше, а не пишется рядом руками: иначе
     * появится вид, который Java называет одним словом, а SQL другим.
     */
    /**
     * Подпись выражением SQL, чтобы она собиралась в том же запросе,
     * что и строка: {@code alias} — псевдоним таблицы {@code supply}.
     *
     * <p>Собирается из словаря выше, а не пишется рядом руками: иначе
     * появится вид, который Java называет одним словом, а SQL другим.
     */
    public static String sqlLabel(String alias) {
        return "CASE WHEN " + alias + ".id IS NULL THEN NULL ELSE "
                + kindCase(alias) + " || ' №' || " + alias + ".number END";
    }

    /**
     * То же с датой прихода: «Контейнер №18 | 30.08.2026».
     *
     * <p>Дата тут же, а не отдельной колонкой: в таблице на сорок с лишним
     * колонок отдельный столбец под неё стоит дороже, чем говорит.
     */
    public static String sqlLabelWithArrival(String alias) {
        return "CASE WHEN " + alias + ".id IS NULL THEN NULL ELSE "
                + kindCase(alias) + " || ' №' || " + alias + ".number"
                + " || coalesce(' | ' || to_char(" + alias
                + ".arrived_on, 'DD.MM.YYYY'), '') END";
    }

    /**
     * Вид словом внутри SQL. Отдельным методом, а не правкой готовой строки:
     * подстановка вида {@code replace(" END", …)} задела бы оба «END» —
     * и внутренний, и внешний, — превратив выражение в неразбираемое.
     */
    private static String kindCase(String alias) {
        StringBuilder sql = new StringBuilder("CASE ").append(alias).append(".kind");
        TITLES.forEach((code, title) -> sql.append(" WHEN '").append(code)
                .append("' THEN '").append(title).append("'"));
        return sql.append(" ELSE ").append(alias).append(".kind END").toString();
    }
}
