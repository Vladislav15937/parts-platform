package ru.partsflow.platform.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Невошедший, открывший страницу приложения, уводится на {@code /login}
 * (задача 0265), а не получает пустой 401.
 *
 * <p><b>Зачем.</b> Приложение уводит невошедшего на общую страницу входа
 * само — но только если его загрузили. На адрес, которого сервер не знает
 * ({@code /sales}, закладка, опечатка), без входа приходил 401 с пустым
 * телом раньше всякой страницы: белый экран, с которого увести некому.
 *
 * <p><b>Перенаправление, а не оболочка на любой адрес.</b> Оболочка
 * ({@code index.html} с ответом 200) на всё подряд сделала бы двухсотым
 * ответ на опечатку и у вошедшего — {@code SpaRoutingConfig} узкий намеренно,
 * и {@code NotFoundMappingTest} стережёт 404 вне {@code /api}. Перенаправление
 * касается только того, кто не вошёл, и отвечает одинаково на любой адрес —
 * по ответу по-прежнему не узнать, какие адреса существуют. И адрес в строке
 * сразу становится адресом страницы входа.
 *
 * <p><b>Только навигация, и только не машинные пути.</b> Ответ 401 вместо
 * перенаправления держится там, где его читает программа: офлайн-очередь
 * приёмки разобрала бы перенаправление как успех и удалила запись
 * ({@code SecurityConfig}). Она ходит только в {@code /api} (проверено
 * перебором вызовов фронтенда: всё через {@code /api}, кроме загрузки снимка
 * в хранилище по подписанной ссылке — это другой источник), поэтому
 * {@code /api} исключён целиком, как и {@code /actuator} — его спрашивают
 * сборщик метрик и шаг выкладки. Метод — только {@code GET}: навигация
 * браузера другой не бывает. Диспетчеризация — только {@code REQUEST}:
 * переадресация на {@code /error} несёт статус исходного ответа, и подменять
 * его перенаправлением нельзя.
 */
final class SignInRedirect {

    static final String LOGIN_PATH = "/login";

    /** Навигация невошедшего на страницу приложения. */
    static final RequestMatcher PAGE_NAVIGATION = SignInRedirect::isPageNavigation;

    /** Перенаправление относительным адресом: за терминатором схема и хост — его. */
    static final AuthenticationEntryPoint TO_LOGIN = (request, response, exception) ->
            response.sendRedirect(request.getContextPath() + LOGIN_PATH);

    private SignInRedirect() {
    }

    static boolean isPageNavigation(HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return false;
        }
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !isUnder(path, "/api") && !isUnder(path, "/actuator");
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
