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
import ru.partsflow.platform.session.LoginSessions;
import ru.partsflow.platform.session.SessionTrackingFilter;

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
    private final LoginSessions sessions;
    private final SecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager, LoginSessions sessions) {
        this.authenticationManager = authenticationManager;
        this.sessions = sessions;
    }

    @PostMapping("/login")
    public ResponseEntity<MeView> login(@Valid @RequestBody LoginRequest request,
                                        HttpServletRequest httpRequest,
                                        jakarta.servlet.http.HttpServletResponse httpResponse) {
        // Адрес и строка браузера снимаются до всего остального: они нужны
        // и удачному входу, и отказу, а после уничтожения сессии их взять
        // уже неоткуда. Адрес — тот, что видит приложение: за терминатором
        // его подставляет клапан Tomcat из X-Forwarded-For
        // (server.forward-headers-strategy=native), в разработке это адрес
        // самого браузера.
        String ip = httpRequest.getRemoteAddr();
        String userAgent = httpRequest.getHeader("User-Agent");

        try {
            var authenticated = authenticationManager.authenticate(new TenantAuthenticationToken(
                    request.company(), request.login(), request.password()));

            // Сессия создаётся заново: если до входа была старая, оставить её
            // значит открыть подмену сессии.
            httpRequest.getSession(true).invalidate();
            var session = httpRequest.getSession(true);

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticated);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, httpRequest, httpResponse);

            TenantPrincipal principal = (TenantPrincipal) authenticated.getPrincipal();
            recordLogin(session, principal, ip, userAgent);

            return ResponseEntity.ok(MeView.of(principal));

        } catch (AuthenticationException e) {
            // Неудачная попытка пишется в журнал той компании, куда стучались:
            // «пять отказов подряд ценнее ста успешных входов» (ответы
            // владельца продукта от 9 сентября 2026, задача 0043). Схему
            // приносит само исключение — только она знает, куда писать.
            // Ответ при этом не меняется ни на букву: единая формулировка,
            // иначе форма входа работает справочником действующих компаний
            // и сотрудников.
            if (e instanceof LoginRejection rejected) {
                sessions.recordFailure(rejected.tenantSchema(), request.login(),
                        rejected.memberId(), rejected.memberRole(), rejected.reason(),
                        ip, userAgent);
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    /**
     * Отметка о входе и то, по чему её потом находят.
     *
     * <p>Ключ и время входа кладутся в саму сессию: по ключу её узнают выход
     * и отметка активности, по времени — отзыв прав (сессия, заведённая уже
     * после отзыва, законна). Хранится хеш, а не идентификатор сессии —
     * журнал не должен становиться складом действующих ключей.
     */
    private void recordLogin(jakarta.servlet.http.HttpSession session, TenantPrincipal principal,
                             String ip, String userAgent) {
        String key = LoginSessions.key(session.getId());
        session.setAttribute(SessionTrackingFilter.KEY, key);
        session.setAttribute(SessionTrackingFilter.LOGGED_IN_AT, java.time.Instant.now());
        sessions.recordLogin(principal.tenantSchema(), principal.memberId(), principal.login(),
                principal.role(), key, ip, userAgent);
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
