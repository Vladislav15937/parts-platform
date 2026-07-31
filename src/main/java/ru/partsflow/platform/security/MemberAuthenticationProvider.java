package ru.partsflow.platform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Проверка входа сотрудника арендатора.
 *
 * <p>Порядок обязателен: сначала код компании превращается в схему через реестр
 * арендаторов, потом в этой схеме ищется сотрудник, и только потом сверяется
 * пароль. Иначе искать негде — учётные записи лежат внутри схемы.
 *
 * <p><b>Ответ об ошибке всегда один и тот же.</b> «Нет такой компании», «нет
 * такого логина» и «неверный пароль» снаружи неразличимы: иначе форма входа
 * превращается в справочник действующих компаний и сотрудников, а первые десять
 * клиентов — конкуренты из одного города.
 *
 * <p>Пароль сверяется даже когда сотрудник не найден: сравнение с заведомо
 * неверным хешем занимает столько же времени, что и с настоящим, и по задержке
 * ответа нельзя понять, существует ли логин.
 */
@Component
public class MemberAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(MemberAuthenticationProvider.class);

    /**
     * Хеш заведомо недостижимого пароля. Нужен, чтобы сверка выполнялась
     * и для несуществующего логина — иначе время ответа выдаёт существование.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public MemberAuthenticationProvider(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        TenantAuthenticationToken request = (TenantAuthenticationToken) authentication;
        String login = String.valueOf(request.getPrincipal());
        String password = request.getCredentials();

        Tenant tenant = findTenant(request.getCompanyCode());
        Member member = tenant == null ? null : findMember(tenant.schemaName(), login);

        String hash = member == null ? DUMMY_HASH : member.passwordHash();
        boolean matches = passwordEncoder.matches(password == null ? "" : password, hash);

        if (member == null || !matches) {
            // Одна формулировка на все случаи: не подсказываем, что именно
            // не сошлось.
            log.debug("Вход отклонён: компания {}, логин {}", request.getCompanyCode(), login);
            throw new BadCredentialsException("Неверный код компании, логин или пароль");
        }
        if (!member.active()) {
            throw new DisabledException("Сотрудник отключён");
        }

        touchLastLogin(tenant.schemaName(), member.id());

        TenantPrincipal principal = new TenantPrincipal(
                tenant.schemaName(), tenant.tenantId(), member.id(),
                member.login(), member.displayName(), member.role(), member.active());

        return new TenantAuthenticationToken(principal, principal.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return TenantAuthenticationToken.class.isAssignableFrom(authentication);
    }

    /** Реестр арендаторов лежит в public, поэтому читается без контекста схемы. */
    private Tenant findTenant(String companyCode) {
        if (companyCode == null || companyCode.isBlank()) {
            return null;
        }
        try {
            return jdbc.queryForObject("""
                    SELECT tenant_id, schema_name
                      FROM public.tenant_registry
                     WHERE code = lower(btrim(?)) AND status = 'ACTIVE'""",
                    (rs, i) -> new Tenant(rs.getLong("tenant_id"), rs.getString("schema_name")),
                    companyCode);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Сотрудник ищется в схеме арендатора, и схема указывается в запросе явно.
     *
     * <p>Через {@code TenantContext} тут нельзя: маршрутизацию соединений делает
     * провайдер Hibernate, и {@code search_path} выставляется только внутри
     * транзакции JPA. Аутентификация же происходит <b>до</b> того, как арендатор
     * установлен, — на то она и аутентификация. Заводить транзакцию ради одного
     * чтения незачем.
     *
     * <p>Имя схемы подставляется в текст запроса, и это безопасно: оно приходит
     * не от пользователя, а из {@code tenant_registry}, где ограничение
     * {@code schema_name ~ '^t_[0-9]{6,}$'} не оставляет места ничему другому.
     */
    private Member findMember(String schema, String login) {
        List<Member> found = jdbc.query("""
                SELECT id, login, password_hash, display_name, role, is_active
                  FROM %s.tenant_member
                 WHERE lower(btrim(login)) = lower(btrim(?))""".formatted(schema),
                (rs, i) -> new Member(
                        rs.getLong("id"),
                        rs.getString("login"),
                        rs.getString("password_hash"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        rs.getBoolean("is_active")),
                login == null ? "" : login);

        return found.isEmpty() ? null : found.get(0);
    }

    private void touchLastLogin(String schema, Long memberId) {
        jdbc.update("UPDATE %s.tenant_member SET last_login_at = now() WHERE id = ?"
                .formatted(schema), memberId);
    }

    private record Tenant(long tenantId, String schemaName) {
    }

    private record Member(Long id, String login, String passwordHash, String displayName,
                          String role, boolean active) {
    }
}
