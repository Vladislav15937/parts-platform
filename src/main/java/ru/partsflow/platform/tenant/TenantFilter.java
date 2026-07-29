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
                TenantContext.set(TenantContext.schemaFor(Long.parseLong(header.trim())));
            }
            chain.doFilter(request, response);
        } catch (NumberFormatException | IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Некорректный " + HEADER);
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator");
    }
}
