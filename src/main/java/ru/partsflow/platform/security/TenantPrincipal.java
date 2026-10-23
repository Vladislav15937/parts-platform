package ru.partsflow.platform.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Вошедший сотрудник.
 *
 * <p><b>Схема арендатора — часть личности пользователя, а не параметр запроса.</b>
 * Раньше арендатор приходил заголовком {@code X-Tenant-Id}, и подставить чужой
 * номер мог кто угодно. Теперь схема берётся отсюда, то есть из сессии, а сессия
 * выдана после проверки пароля в этой самой схеме — подменить её запросом нельзя.
 *
 * <p><b>Права лежат здесь снимком — набором ключей {@link Permission}, — и это
 * то же решение, что у роли до задачи 0044.</b> Читаются они при входе
 * ({@link MemberAccess}), а всё, что их меняет (роль, её набор, право поверх
 * роли), закрывает сессии затронутых сотрудников: «отозвали право — сессия
 * обязана узнать сразу» (docs/sessions.md, §6). Спрашивать базу на каждом
 * запросе значило бы ещё один запрос к схеме арендатора на каждый клик ради
 * события, которое случается раз в месяц.
 *
 * @param memberId    идентификатор сотрудника внутри арендатора
 * @param role        прежняя жёсткая роль из {@code tenant_member.role}. Прав она
 *                    больше не решает: ей подписан снимок роли автора в журнале
 *                    ({@code audit_log.changed_by_role}), и по ней узнаётся
 *                    администратор сервиса
 * @param owner       назначена роль «Владелец»: все права всегда, набор
 *                    не спрашивается вовсе (ответ владельца 23.10.2026, пункт 7)
 * @param permissions права не-владельца: права роли плюс добавленные поверх
 *                    роли минус снятые. {@code null} — сессия заведена сборкой
 *                    до задачи 0044 и прав ещё не прочла; её дочитывает
 *                    {@link LegacySessionUpgrade} до первой проверки
 */
public record TenantPrincipal(String tenantSchema,
                              long tenantId,
                              Long memberId,
                              String login,
                              String displayName,
                              String role,
                              boolean active,
                              boolean owner,
                              Set<String> permissions) implements UserDetails {

    /**
     * Роль администратора сервиса, вошедшего в кабинет по разрешению
     * владельца (задача 0042).
     */
    public static final String SERVICE_ADMIN_ROLE = "ADMIN";

    public TenantPrincipal {
        permissions = permissions == null ? null : Set.copyOf(permissions);
    }

    /**
     * Ключи прав — как полномочия Spring Security: на них отвечает
     * {@code hasAuthority('deal.sell')} в {@code @PreAuthorize}.
     *
     * <p>Владельцу и администратору сервиса — <b>весь справочник</b>,
     * собранный в момент вопроса, а не при входе: право, появившееся
     * в следующей сборке, у них есть сразу, сколько бы ни жила сессия.
     * Набора роли «Владелец» и прав поверх неё код не читает вовсе — их
     * и не бывает, а если бы кто-то записал их в базу прямым SQL, сузить
     * владельца они всё равно не смогли бы.
     *
     * <p>Администратор сервиса (задача 0042) получает права владельца одним
     * этим местом, как и раньше, плюс {@code ROLE_ADMIN}: по нему
     * {@code SecurityConfig} закрывает от него то, что владелец оставляет
     * себе, — кто входит в кабинет.
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (isServiceAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + SERVICE_ADMIN_ROLE));
        }
        if (actsAsOwner()) {
            for (Permission permission : Permission.values()) {
                authorities.add(new SimpleGrantedAuthority(permission.key()));
            }
        } else if (permissions != null) {
            for (String key : permissions) {
                authorities.add(new SimpleGrantedAuthority(key));
            }
        }
        return authorities;
    }

    /** Администратор сервиса в кабинете разборки, а не её сотрудник. */
    public boolean isServiceAdmin() {
        return SERVICE_ADMIN_ROLE.equals(role);
    }

    /**
     * Права владельца: роль «Владелец» либо администратор сервиса.
     */
    public boolean actsAsOwner() {
        return owner || isServiceAdmin();
    }

    /**
     * Есть ли право — для тех мест, где оно решает не путь, а содержимое
     * ответа или признак кнопки (деньги в истории позиции, удаление
     * выполненного документа). Тот же ответ, что у {@code hasAuthority}.
     */
    public boolean can(Permission permission) {
        return actsAsOwner() || (permissions != null && permissions.contains(permission.key()));
    }

    /** Те же права с прочитанным набором — для дочитывания прежней сессии. */
    public TenantPrincipal withAccess(boolean owner, Set<String> permissions) {
        return new TenantPrincipal(tenantSchema, tenantId, memberId, login, displayName, role,
                active, owner, permissions);
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
