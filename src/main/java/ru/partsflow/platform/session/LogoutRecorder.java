package ru.partsflow.platform.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;
import ru.partsflow.platform.security.TenantPrincipal;

/**
 * Отметка о выходе по кнопке.
 *
 * <p>«Вышел в 18:03» — один из трёх исходов сессии, и он единственный, про
 * который точно известно, что человек ушёл сам (решение владельца продукта
 * от 9 сентября 2026). Без этой отметки выход был бы неотличим от истечения,
 * а разница как раз в том, оставил человек рабочее место открытым или закрыл.
 *
 * <p><b>Ключ берётся из атрибута сессии, а при её отсутствии — из запрошенного
 * идентификатора.</b> Порядок обработчиков выхода в Spring Security таков, что
 * свои идут раньше того, который сессию убивает; полагаться на это целиком
 * не стоит — {@code getRequestedSessionId()} переживает уничтожение сессии
 * и даёт тот же ключ.
 */
@Component
public class LogoutRecorder implements LogoutHandler {

    private final LoginSessions sessions;

    public LogoutRecorder(LoginSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response,
                       Authentication authentication) {

        if (authentication == null
                || !(authentication.getPrincipal() instanceof TenantPrincipal principal)) {
            // Выход без входа: закрывать нечего, и придумывать арендатора
            // не из чего.
            return;
        }
        String key = sessionKey(request);
        if (key != null) {
            sessions.recordLogout(principal.tenantSchema(), key);
        }
    }

    private static String sessionKey(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null
                && session.getAttribute(SessionTrackingFilter.KEY) instanceof String key) {
            return key;
        }
        String requested = request.getRequestedSessionId();
        return requested == null ? null : LoginSessions.key(requested);
    }
}
