package ru.partsflow.platform.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Вошедший сотрудник.
 *
 * <p><b>Схема арендатора — часть личности пользователя, а не параметр запроса.</b>
 * Раньше арендатор приходил заголовком {@code X-Tenant-Id}, и подставить чужой
 * номер мог кто угодно. Теперь схема берётся отсюда, то есть из сессии, а сессия
 * выдана после проверки пароля в этой самой схеме — подменить её запросом нельзя.
 *
 * @param memberId идентификатор сотрудника внутри арендатора
 */
public record TenantPrincipal(String tenantSchema,
                              long tenantId,
                              Long memberId,
                              String login,
                              String displayName,
                              String role,
                              boolean active) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Роли живут в tenant_member: у одного человека одна роль в одном
        // арендаторе, поэтому список всегда из одного элемента.
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /**
     * Пароль наружу не отдаётся: он нужен только провайдеру аутентификации,
     * а тот сверяет хеш сам и в принципал его не кладёт.
     */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return login;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    @Override
    public boolean isAccountNonExpired() {
        return active;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
