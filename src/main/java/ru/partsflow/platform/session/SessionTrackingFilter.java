package ru.partsflow.platform.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.partsflow.platform.security.CurrentUser;
import ru.partsflow.platform.security.TenantPrincipal;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Что происходит с живой сессией на каждом запросе: отметка активности
 * и проверка отзыва.
 *
 * <p>Оба дела — про одно и то же: сессия существует ровно постольку, поскольку
 * с ней приходят, и наблюдать за ней можно только здесь. Разведённые по двум
 * фильтрам, они читали бы одни и те же атрибуты сессии дважды.
 *
 * <p><b>Отметка активности прорежена, и это главное требование задачи.</b>
 * «Отметку активности нельзя обновлять на каждый запрос — это запись в базу
 * на каждый клик. Обновлять не чаще раза в 1–5 минут» (ответы владельца
 * продукта от 9 сентября 2026, docs/sessions.md §5). Прореживание держится
 * пометкой в самой сессии, а не запросом «а когда была прошлая отметка»:
 * второе стоило бы ровно того же, чего мы избегаем.
 *
 * <p><b>Точность здесь — минуты, и она никому не нужна точнее.</b> Длительность
 * смены считается по последней активности, и ошибка в пару минут на границе
 * не меняет ни одного ответа, ради которого журнал открывают.
 *
 * <p><b>Фильтр стоит после цепочки Spring Security</b> (у неё порядок −100,
 * у обычного бина-фильтра — последний): до неё вошедшего ещё нет, и отмечать
 * было бы нечего.
 */
@Component
public class SessionTrackingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionTrackingFilter.class);

    /** Суррогат сессии для журнала. Кладётся при входе, читается здесь. */
    public static final String KEY = "partsflow.session.key";

    /** Когда вошли. Нужно отзыву: сессия, заведённая после него, законна. */
    public static final String LOGGED_IN_AT = "partsflow.session.loggedInAt";

    /** Когда в последний раз писали отметку активности в базу. */
    private static final String MARKED_AT = "partsflow.session.markedAt";

    /**
     * Ключ сессии, из которой пришёл запрос.
     *
     * <p>Нужен отзыву: сотрудник, сменивший свой пароль, остаётся работать,
     * а всех остальных выкидывает.
     *
     * @return {@code null} — запрос без сессии либо сессия не от входа
     */
    public static String keyOf(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(KEY) instanceof String key ? key : null;
    }

    private final LoginSessions sessions;
    private final SessionRevocations revocations;
    private final Duration markEvery;

    public SessionTrackingFilter(LoginSessions sessions, SessionRevocations revocations,
                                 @Value("${app.sessions.activity-interval:2m}") Duration markEvery) {
        this.sessions = sessions;
        this.revocations = revocations;
        this.markEvery = markEvery;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        HttpSession session = request.getSession(false);
        TenantPrincipal principal = CurrentUser.get().orElse(null);
        if (session == null || principal == null) {
            chain.doFilter(request, response);
            return;
        }

        Object key = session.getAttribute(KEY);
        Object loggedInAt = session.getAttribute(LOGGED_IN_AT);
        // Сессия, заведённая не входом (CSRF-токен до формы входа), журнала
        // не имеет — и это не ошибка, а её нормальное состояние.
        if (!(key instanceof String sessionKey) || !(loggedInAt instanceof Instant enteredAt)) {
            chain.doFilter(request, response);
            return;
        }

        if (revocations.revoked(principal.tenantSchema(), principal.memberId(),
                sessionKey, enteredAt)) {
            // Ровно то же, что видит невошедший: 401 с пустым телом. Клиент
            // разбирает его как потерю сессии и уводит на вход, а не как
            // ошибку запроса — офлайн-очередь при этом дожидается входа
            // и работу приёмщика не теряет.
            session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }

        markSeen(session, principal, sessionKey);
        chain.doFilter(request, response);
    }

    private void markSeen(HttpSession session, TenantPrincipal principal, String sessionKey) {
        Instant now = Instant.now();
        Object marked = session.getAttribute(MARKED_AT);
        if (marked instanceof Instant last && last.plus(markEvery).isAfter(now)) {
            return;
        }
        // Пометка ставится до запроса, а не после: отказ базы не должен
        // означать поход в неё на каждом следующем запросе.
        session.setAttribute(MARKED_AT, now);
        try {
            sessions.markSeen(principal.tenantSchema(), sessionKey);
        } catch (RuntimeException e) {
            // Журнал не мешает работать: пусть строка истории потеряется,
            // а продавец продаёт.
            log.debug("Отметка активности не записана", e);
        }
    }
}
