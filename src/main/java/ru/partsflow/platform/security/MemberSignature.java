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
}
