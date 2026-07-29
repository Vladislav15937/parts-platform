package ru.partsflow.platform.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    /**
     * Схема вне контекста арендатора. Hibernate спрашивает идентификатор и при
     * старте, когда арендатора ещё нет. Бизнес-данных в public нет, поэтому
     * фолбэк безопасен, а попытка реально работать с данными без арендатора
     * упрётся в {@link TenantContext#require()}.
     */
    private static final String NO_TENANT = "public";

    @Override
    public String resolveCurrentTenantIdentifier() {
        String current = TenantContext.getOrNull();
        return current != null ? current : NO_TENANT;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
