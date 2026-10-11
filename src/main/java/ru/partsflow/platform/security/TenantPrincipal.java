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

    /**
     * Роль администратора сервиса, вошедшего в кабинет по разрешению
     * владельца (задача 0042).
     */
    public static final String SERVICE_ADMIN_ROLE = "ADMIN";

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Роли живут в tenant_member: у одного человека одна роль в одном
        // арендаторе, поэтому список из одного элемента.
        //
        // Кроме администратора сервиса. Решение владельца продукта
        // от 8 сентября 2026 (tasks/0042): администратор «должен иметь
        // возможность делать абсолютно всё в личном кабинете владельца
        // разборки» — значит права у него ровно владельца, и выдаются они
        // одним местом, а не дописыванием ADMIN в полторы сотни
        // @PreAuthorize: забытое место молча отняло бы у него возможность,
        // а новое — заводилось бы без него. Своя роль при этом остаётся
        // первой: по ней журнал отличает его правку от правки владельца,
        // а немногие действия, которые владелец оставляет себе (разрешение
        // на вход самого администратора), закрыты от него явно —
        // «hasRole('OWNER') and !hasRole('ADMIN')».
        if (isServiceAdmin()) {
            return List.of(new SimpleGrantedAuthority("ROLE_" + role),
                    new SimpleGrantedAuthority("ROLE_OWNER"));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /** Администратор сервиса в кабинете разборки, а не её сотрудник. */
    public boolean isServiceAdmin() {
        return SERVICE_ADMIN_ROLE.equals(role);
    }

    /**
     * Права владельца: сам владелец либо администратор сервиса.
     *
     * <p>Для тех немногих мест, где роль сравнивается в коде, а не в
     * {@code @PreAuthorize}: там полномочие владельца проверяется строкой,
     * и администратор, не названный в ней, получил бы меньше, чем ему дано.
     */
    public boolean actsAsOwner() {
        return "OWNER".equals(role) || isServiceAdmin();
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
