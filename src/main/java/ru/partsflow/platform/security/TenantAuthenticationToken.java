package ru.partsflow.platform.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Запрос на вход: код компании плюс логин и пароль.
 *
 * <p>Код компании нужен до проверки пароля: учётные записи живут внутри схемы
 * арендатора, и найти пользователя, не выбрав схему, невозможно. Стандартный
 * {@code UsernamePasswordAuthenticationToken} третьего поля не несёт, поэтому
 * токен свой.
 *
 * <p>Позже код будет приходить из поддомена, и тогда с формы он уйдёт — но тип
 * токена останется тем же.
 */
public class TenantAuthenticationToken extends AbstractAuthenticationToken {

    private final String companyCode;
    private final Object principal;
    private String credentials;

    /** Непроверенный токен: пришёл с формы входа. */
    public TenantAuthenticationToken(String companyCode, String login, String password) {
        super(null);
        this.companyCode = companyCode;
        this.principal = login;
        this.credentials = password;
        setAuthenticated(false);
    }

    /** Проверенный токен: пароль сошёлся, внутри полноценный принципал. */
    public TenantAuthenticationToken(TenantPrincipal principal,
                                     Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.companyCode = null;
        this.principal = principal;
        this.credentials = null;
        super.setAuthenticated(true);
    }

    public String getCompanyCode() {
        return companyCode;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public String getCredentials() {
        return credentials;
    }

    /**
     * Пароль стирается сразу после проверки: сессия живёт часами, и держать
     * в ней пароль в открытом виде незачем.
     */
    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        this.credentials = null;
    }
}
