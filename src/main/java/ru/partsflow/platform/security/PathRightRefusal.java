package ru.partsflow.platform.security;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Отказ пути словами: какого права нет (задача 0044, живой прогон волны 24.10c,
 * находка «в»). До этого отказ {@code @PreAuthorize} уходил наружу голым
 * «Недостаточно прав» — держатель одного из четырёх прав на раздачу, нажав
 * «Завести» или «Выключить», не узнавал, чего ему не хватает и что просить.
 *
 * <p>Право берётся <b>из аннотации самого пути</b>, а не из второго перечня
 * «путь → право»: второй перечень разошёлся бы с аннотацией на первой правке.
 * Названо оно только тогда, когда вошедшему не хватает всех прав выражения:
 * отказ, брошенный изнутри операции при имеющемся праве пути, правом пути
 * не объясняется, и для него остаются общие слова. Ключи наружу не уходят —
 * только названия справочника.
 */
public final class PathRightRefusal {

    private static final Pattern AUTHORITY = Pattern.compile("hasAuthority\\('([^']+)'\\)");

    /**
     * Выражения, которые этот класс понимает: права через {@code or}, плюс «себе»
     * ({@code #id == authentication.principal.memberId}) и запрет администратору
     * сервиса ({@code and !hasRole('ADMIN')}) в хвосте. Вне этой формы — {@code and}
     * между правами, отрицание права, {@code hasRole} — назвать «нет права «…»»
     * значило бы соврать (у двух прав через {@code and} не хватает обоих, а не одного
     * из них), и класс молчит. Держит форму на всех путях {@code PermissionGuardTest}.
     */
    private static final Pattern UNDERSTOOD = Pattern.compile(
            "hasAuthority\\('[^']+'\\)( or hasAuthority\\('[^']+'\\))*"
                    + "( or #id == authentication\\.principal\\.memberId)?"
                    + "( and !hasRole\\('ADMIN'\\))?");

    private PathRightRefusal() {
    }

    /**
     * @param handler обработчик запроса ({@code HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE})
     * @return слова отказа; пусто — назвать нечего
     */
    public static Optional<String> of(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return Optional.empty();
        }
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), PreAuthorize.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), PreAuthorize.class);
        }
        if (rule == null || !understood(rule.value())) {
            return Optional.empty();
        }
        List<Permission> named = new ArrayList<>();
        Matcher key = AUTHORITY.matcher(rule.value());
        while (key.find()) {
            Optional<Permission> permission = Permission.byKey(key.group(1));
            if (permission.isEmpty()) {
                return Optional.empty();
            }
            named.add(permission.get());
        }
        if (named.isEmpty()) {
            return Optional.empty();
        }
        Optional<TenantPrincipal> actor = CurrentUser.get();
        if (actor.isEmpty() || named.stream().anyMatch(actor.get()::can)) {
            // Вошедший не сотрудник кабинета (администратор в своей системе) — права
            // кабинета к нему не относятся, и «попросите владельца» было бы неправдой.
            // Право пути у вошедшего есть — отказ пришёл не за него.
            return Optional.empty();
        }
        return Optional.of(words(named));
    }

    /** Понимает ли класс выражение пути — форма {@link #UNDERSTOOD}. */
    public static boolean understood(String expression) {
        return UNDERSTOOD.matcher(expression.strip()).matches();
    }

    /**
     * Те же слова, что у погашенной кнопки на экране (`access/rights.ts`, `lockedReasonAny`).
     * Один источник слов отказа по праву: и для пути, и для проверки внутри операции
     * ({@code PostedDocumentDeletion}).
     */
    public static String words(List<Permission> named) {
        if (named.size() == 1) {
            return "Нет права «" + named.get(0).title() + "» — попросите владельца";
        }
        List<String> quoted = named.stream().map(p -> "«" + p.title() + "»").toList();
        return "Нет ни одного из прав: " + String.join(", ", quoted.subList(0, quoted.size() - 1))
                + " или " + quoted.get(quoted.size() - 1) + " — попросите владельца";
    }
}
