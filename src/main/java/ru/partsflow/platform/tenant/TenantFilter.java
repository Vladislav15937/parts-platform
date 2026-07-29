package ru.partsflow.platform.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Устанавливает арендатора на время запроса.
 *
 * <p>Заглушка на период до появления аутентификации: арендатор берётся из
 * заголовка {@code X-Tenant-Id}. После внедрения Spring Security источником
 * должен стать токен пользователя, а заголовок надо убрать — иначе любой
 * желающий сможет читать чужой склад, просто подставив другой номер.
 */
@Component
@Order(1)
public class TenantFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        try {
            if (header != null && !header.isBlank()) {
                try {
                    TenantContext.set(TenantContext.schemaFor(Long.parseLong(header.trim())));
                } catch (IllegalArgumentException e) {
                    // Ловим только разбор заголовка. Если накрыть тем же catch и
                    // chain.doFilter, любой IllegalArgumentException из контроллера
                    // вернётся клиенту как «Некорректный X-Tenant-Id» и уведёт
                    // отладку не туда.
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Некорректный " + HEADER);
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            // Освобождать ThreadLocal обязательно: пул потоков переиспользует поток,
            // и оставленный арендатор утечёт в следующий запрос.
            TenantContext.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator");
    }
}
