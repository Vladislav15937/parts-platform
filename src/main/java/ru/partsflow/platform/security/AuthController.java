package ru.partsflow.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Вход и выход.
 *
 * <p>Логин отдельным эндпоинтом, а не формой Spring по умолчанию: клиент —
 * приложение, ему нужен JSON и внятные коды ответа, а не редиректы.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/login")
    public ResponseEntity<MeView> login(@Valid @RequestBody LoginRequest request,
                                        HttpServletRequest httpRequest,
                                        jakarta.servlet.http.HttpServletResponse httpResponse) {
        try {
            var authenticated = authenticationManager.authenticate(new TenantAuthenticationToken(
                    request.company(), request.login(), request.password()));

            // Сессия создаётся заново: если до входа была старая, оставить её
            // значит открыть подмену сессии.
            httpRequest.getSession(true).invalidate();
            httpRequest.getSession(true);

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticated);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, httpRequest, httpResponse);

            return ResponseEntity.ok(MeView.of((TenantPrincipal) authenticated.getPrincipal()));

        } catch (AuthenticationException e) {
            // Единая формулировка: форма входа не должна работать справочником
            // действующих компаний и сотрудников.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    /** Кто я. Нужно приложению после перезапуска: сессия могла остаться живой. */
    @GetMapping("/me")
    public MeView me() {
        return MeView.of(CurrentUser.require());
    }

    /**
     * Выдаёт CSRF-токен.
     *
     * <p>Отдельный эндпоинт, потому что первый запрос приложения — GET, а токен
     * нужен уже для входа. Тело пустое: токен уезжает cookie, которую скрипт
     * прочитает сам.
     *
     * <p><b>Обращение к {@code token.getToken()} обязательно.</b> В Spring
     * Security 6 токен ленивый: cookie записывается только когда значение
     * действительно запросили. Метод, который просто возвращает 204, не создаёт
     * ничего — приложение получает пустой токен, вход отбивается фильтром CSRF,
     * а поскольку пользователь ещё анонимный, наружу это выходит как 401,
     * то есть выглядит неверным паролем. На отладку такого уходит вечер.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken token) {
        token.getToken();
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(@NotBlank String company,
                               @NotBlank String login,
                               @NotBlank String password) {
    }

    public record MeView(Long memberId, String login, String displayName, String role,
                         String companySchema) {

        static MeView of(TenantPrincipal principal) {
            return new MeView(principal.memberId(), principal.login(), principal.displayName(),
                    principal.role(), principal.tenantSchema());
        }
    }
}
