package ru.partsflow.platform.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Вход в систему администрирования: логин и пароль из {@code public.service_admin}.
 *
 * <p>Свой провайдер, а не ветка в {@code MemberAuthenticationProvider}: у входа
 * в кабинет есть код компании, и без него там искать негде, а у администратора
 * кода компании нет и быть не может — он «существует вне зависимости ни
 * от чего» (решение владельца продукта от 8 сентября 2026, tasks/0042).
 * Различаются они типом токена: кабинет — {@code TenantAuthenticationToken},
 * здесь — обычный {@link UsernamePasswordAuthenticationToken}.
 *
 * <p>Правила те же, что у входа в кабинет, и по той же причине: ответ на отказ
 * один («нет такого», «неверный пароль» и «выключен» неразличимы), а пароль
 * сверяется и для несуществующего логина — иначе по задержке ответа форма
 * работает справочником учётных записей. Таблица лежит в {@code public},
 * поэтому арендатор и транзакция здесь не нужны.
 */
@Component
public class ServiceAdminAuthenticationProvider implements AuthenticationProvider {

    /** Хеш заведомо недостижимого пароля — тот же приём, что у входа в кабинет. */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public ServiceAdminAuthenticationProvider(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String login = String.valueOf(authentication.getPrincipal());
        Object credentials = authentication.getCredentials();
        String password = credentials == null ? "" : credentials.toString();

        List<Admin> found = jdbc.query("""
                SELECT id, login, password_hash, display_name, is_active
                  FROM public.service_admin
                 WHERE lower(btrim(login)) = lower(btrim(?))""",
                (rs, i) -> new Admin(rs.getLong("id"), rs.getString("login"),
                        rs.getString("password_hash"), rs.getString("display_name"),
                        rs.getBoolean("is_active")),
                login);
        Admin admin = found.isEmpty() ? null : found.get(0);

        boolean matches = passwordEncoder.matches(password,
                admin == null ? DUMMY_HASH : admin.passwordHash());
        if (admin == null || !matches || !admin.active()) {
            throw new BadCredentialsException("Неверный логин или пароль");
        }

        ServiceAdminPrincipal principal =
                new ServiceAdminPrincipal(admin.id(), admin.login(), admin.displayName());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private record Admin(long id, String login, String passwordHash, String displayName,
                         boolean active) {
    }
}
