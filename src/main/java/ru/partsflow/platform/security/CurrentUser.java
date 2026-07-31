package ru.partsflow.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Вошедший сотрудник из контекста безопасности.
 *
 * <p>Нужен там, где раньше идентификатор автора приходил параметром запроса:
 * {@code authorId} в телах приёмки и инвентаризации — это то, что клиент про
 * себя сообщил, а не то, что проверено. Автор операции должен браться отсюда.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<TenantPrincipal> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof TenantPrincipal principal
                ? Optional.of(principal)
                : Optional.empty();
    }

    public static TenantPrincipal require() {
        return get().orElseThrow(() -> new IllegalStateException(
                "Нет вошедшего пользователя: запрос должен быть отклонён фильтром безопасности"));
    }

    /** Идентификатор сотрудника для полей «кто сделал». */
    public static Long memberId() {
        return get().map(TenantPrincipal::memberId).orElse(null);
    }
}
