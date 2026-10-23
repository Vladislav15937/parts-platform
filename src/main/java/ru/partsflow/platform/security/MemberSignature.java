package ru.partsflow.platform.security;

/**
 * Подпись автора записи: «Пётр Массов (vladelec, ID 3)» (задача 0373).
 *
 * <p><b>Зачем три части, а не имя.</b> Решение владельца 23.10.2026:
 * «Показывать логин имя и номер сотрудника, мы должны идентифицировать
 * сотрудника на 100%». Имя повторяется (два Сергея на одной разборке —
 * обычное дело) и правится; логин уникален, но его нет у участника без
 * входа (администратор сервиса); номер есть у каждого и не меняется
 * никогда. Порядок — как у Bazon, откуда приходят клиенты: имя, за ним
 * в скобках «ID NNNN».
 *
 * <p><b>Номер — видимый {@code tenant_member.number} ({@code tenant/097}),
 * а не {@code id} строки:</b> его человек видит на экране «Сотрудники»
 * и называет вслух. Поэтому файл стоит в {@code ALLOWED}
 * у {@code InternalIdsNotShownTest} — решение владельца 20.10.2026
 * (задача 0373). Своим классом, а не методом {@code MemberService},
 * намеренно: перебор пропускает внесённый файл целиком, и в сервисе
 * сотрудников с его отказами «не найдено» пропущенным оказался бы
 * и настоящий номер строки, приклеенный к тексту. Здесь клеить больше
 * нечего.
 *
 * <p>Подпись собирается здесь одна на проект: две копии разошлись бы
 * в порядке частей на первой же правке одной из них.
 */
public final class MemberSignature {

    private MemberSignature() {
    }

    /**
     * @return {@code null}, если сотрудника нет (правка мимо приложения,
     *         перенос): прочерк честнее выдуманного автора
     */
    public static String of(String displayName, String login, Long number) {
        if (number == null) {
            return null;
        }
        String name = displayName == null || displayName.isBlank() ? login : displayName;
        if (login == null || login.isBlank() || login.equals(name)) {
            return "%s (ID %d)".formatted(name == null ? "Сотрудник" : name, number);
        }
        return "%s (%s, ID %d)".formatted(name, login, number);
    }

    /**
     * Та же подпись выражением SQL — для мест, где её отбирают и сортируют
     * в самом запросе (задача 0373, «подпись везде»).
     *
     * <p><b>Зачем вторая запись того же правила, и почему она здесь.</b>
     * Колонки витрины «Кто изменил» и «Кто изменил цену» показывают, отбирают
     * и сортируют <b>одним выражением</b> (`CatalogService.FILTERS`): выбранное
     * из меню значение обязано совпасть с клеткой буква в букву, иначе отбор
     * не находит ничего. Подпись, собранная в Java после запроса, с отбором
     * в {@code WHERE} не встречается. Поэтому SQL-запись лежит рядом
     * с Java-записью, в одном классе, и их равенство на всех четырёх ветках
     * держит {@code MemberSignatureSqlTest} на настоящем Postgres: разойдись
     * они — краснеет прогон, а не экран через неделю.
     *
     * <p>Пустое имя — пробелы по краям, как {@link String#isBlank()} для
     * пробела; табуляций в имени сотрудника форма не пропускает.
     *
     * @param alias псевдоним строки {@code tenant_member} в запросе
     * @return выражение; {@code NULL}, когда строки нет (как {@link #of})
     */
    public static String sql(String alias) {
        if (!alias.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("Псевдоним строки сотрудника: " + alias);
        }
        String name = "CASE WHEN btrim(coalesce(%1$s.display_name, '')) = ''"
                + " THEN %1$s.login ELSE %1$s.display_name END";
        String expression = "CASE WHEN %1$s.number IS NULL THEN NULL"
                + " WHEN btrim(coalesce(%1$s.login, '')) = '' OR %1$s.login = (" + name + ")"
                + " THEN coalesce((" + name + "), 'Сотрудник') || ' (ID ' || %1$s.number || ')'"
                + " ELSE (" + name + ") || ' (' || %1$s.login || ', ID ' || %1$s.number || ')'"
                + " END";
        return "(" + expression.formatted(alias) + ")";
    }

    /**
     * Подпись сотрудника по ссылке на него — подзапросом, для списка колонок.
     *
     * @param reference выражение со ссылкой на {@code tenant_member.id},
     *                  например {@code p.updated_by}
     */
    public static String sqlOf(String reference) {
        return "(SELECT " + sql("sig") + " FROM tenant_member sig WHERE sig.id = " + reference + ")";
    }
}
