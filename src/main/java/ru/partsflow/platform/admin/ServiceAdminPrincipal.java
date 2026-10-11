package ru.partsflow.platform.admin;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Администратор сервиса в системе администрирования — вне всех кабинетов.
 *
 * <p><b>Не {@code TenantPrincipal}, и это главное свойство.</b> У него нет
 * схемы арендатора: {@code TenantFilter} не выставит никакой, а все пути
 * кабинета ({@code /api/**} кроме {@code /api/admin/**}) пускают только
 * вошедшего в кабинет — см. {@code SecurityConfig}. Решение владельца продукта
 * от 8 сентября 2026 (tasks/0042, пункт 5): «разборка выбирается только
 * в административном окне, живущем отдельно от всех разборок». Значит здесь
 * он видит список клиентов сервиса и ничего из их данных, а в кабинет входит
 * отдельным действием — и только с разрешения владельца.
 *
 * <p>Учётная запись живёт в {@code public.service_admin} (решение владельца
 * от 29 сентября 2026), а не в {@code tenant_member}.
 */
public record ServiceAdminPrincipal(long adminId, String login, String displayName)
        implements UserDetails {

    /** Роль системы администрирования. В кабинете роль другая — {@code ADMIN}. */
    public static final String ROLE = "SERVICE_ADMIN";

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + ROLE));
    }

    /** Хеш пароля в принципал не кладётся: сверяет его провайдер, и только он. */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return login;
    }
}
