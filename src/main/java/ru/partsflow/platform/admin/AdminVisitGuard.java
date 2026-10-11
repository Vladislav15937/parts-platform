package ru.partsflow.platform.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.partsflow.platform.security.CurrentUser;
import ru.partsflow.platform.security.TenantPrincipal;
import ru.partsflow.platform.session.LoginSessions;

import java.io.IOException;

/**
 * На каждом запросе администратора в кабинете: действует ли ещё разрешение.
 *
 * <p><b>Зачем, если отзыв и так удаляет сессию.</b> Отзыв владельцем — одно
 * из двух событий, закрывающих доступ; второе — истёкший срок. Решение
 * владельца продукта от 8 сентября 2026 (tasks/0042): срок «на определённое
 * количество часов» выбирает клиент, и в этот час доступ обязан закрыться
 * сам — никто не нажимает ничего. У сессии своего знания о сроке нет:
 * она живёт по сроку простоя, и администратор, работающий без перерыва,
 * продолжал бы работать сколько угодно после того, как разрешение кончилось.
 * Значит спрашивать приходится разрешение, и на каждом запросе.
 *
 * <p>И отзыв от этого становится надёжнее, а не только быстрее: даже если
 * удаление сессии где-то не прошло, закрытое разрешение отбивает следующий
 * же запрос на любом экземпляре — оно лежит в общей схеме ячейки.
 *
 * <p><b>Цена — один запрос по индексу на каждый запрос администратора</b>,
 * и только его: у сотрудников разборки эта проверка не делает ничего.
 * Администратор один и работает в кабинете эпизодически.
 *
 * <p>Ответ — 401 с пустым телом, как у любой кончившейся сессии: приложение
 * понимает его само («сессия кончилась — войдите заново»), а сессия
 * уничтожается здесь же, чтобы следующий запрос не спрашивал разрешение
 * снова. В журнале входов разборки сессия закрывается с причиной — владелец
 * видит, что администратор вышел не сам.
 *
 * <p>Стоит после цепочки Spring Security, как {@code TenantFilter}: раньше
 * вошедшего ещё нет.
 */
@Component
@Order(1)
public class AdminVisitGuard extends OncePerRequestFilter {

    static final String EXPIRED_DETAIL = "срок доступа администратора истёк";

    private final AdminAccess access;
    private final LoginSessions sessions;

    public AdminVisitGuard(AdminAccess access, LoginSessions sessions) {
        this.access = access;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        TenantPrincipal principal = CurrentUser.get().orElse(null);
        if (principal == null || !principal.isServiceAdmin()
                || access.isOpen(principal.tenantId())) {
            chain.doFilter(request, response);
            return;
        }

        // Отозванное владельцем журнал уже закрыл сам (AdminAccess.revoke),
        // и здесь закрывать нечего — запись идёт только по открытым строкам.
        // Остаётся истёкший срок.
        sessions.revoke(principal.tenantSchema(), principal.memberId(), EXPIRED_DETAIL, null);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/feeds/");
    }
}
