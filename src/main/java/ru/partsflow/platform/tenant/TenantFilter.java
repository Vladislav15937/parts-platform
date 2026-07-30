package ru.partsflow.platform.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.partsflow.platform.security.CurrentUser;

import java.io.IOException;

/**
 * Устанавливает арендатора на время запроса.
 *
 * <p><b>Источник — сессия, а не запрос.</b> Схема берётся из вошедшего
 * пользователя: она часть его личности, проверенной при входе паролем в этой
 * самой схеме. Раньше здесь читался заголовок {@code X-Tenant-Id}, и подставить
 * чужой номер мог кто угодно — при публикации API в интернет это означало
 * чтение чужого склада, а первые десять клиентов конкурируют в одном городе.
 *
 * <p>Порядок фильтров важен: этот должен идти <b>после</b> цепочки Spring
 * Security, иначе контекста безопасности ещё нет и арендатор не определится.
 * Отсюда {@code @Order} ниже приоритета security-цепочки.
 */
@Component
@Order(TenantFilter.ORDER)
public class TenantFilter extends OncePerRequestFilter {

    /**
     * Цепочка Spring Security по умолчанию сидит на -100. Идём заметно позже,
     * чтобы контекст безопасности был уже заполнен.
     */
    static final int ORDER = 0;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            CurrentUser.get().ifPresent(principal -> TenantContext.set(principal.tenantSchema()));
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
        // Вход происходит до того, как арендатор известен: провайдер
        // аутентификации выставляет схему сам, на время запроса к реестру.
        return path.startsWith("/actuator") || path.startsWith("/api/auth/");
    }
}
