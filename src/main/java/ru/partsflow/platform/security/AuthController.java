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

import java.util.List;

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

    private final MemberAccess access;

    public AuthController(AuthenticationManager authenticationManager, LoginSessions sessions,
                          MemberAccess access) {
        this.access = access;
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

            return ResponseEntity.ok(MeView.of(principal, roleName(principal)));

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
     * <p>Ключ кладётся в саму сессию: по нему её узнают выход, отметка
     * активности и отзыв — он же отличает сессию, из которой отзыв делают,
     * от всех остальных. Хранится хеш, а не идентификатор сессии: журнал
     * не должен становиться складом действующих ключей, и сессия в общей
     * схеме ячейки — тем более.
     */
    private void recordLogin(jakarta.servlet.http.HttpSession session, TenantPrincipal principal,
                             String ip, String userAgent) {
        String key = LoginSessions.key(session.getId());
        session.setAttribute(SessionTrackingFilter.KEY, key);
        sessions.recordLogin(principal.tenantSchema(), principal.memberId(), principal.login(),
                principal.role(), key, ip, userAgent);
    }

    /**
     * Почему закрыт вход, с которым пришёл этот браузер (задача 0044, живой
     * прогон 24.10.2026): «Сессия кончилась — войдите заново» без причины
     * у сотрудника, которому владелец сменил права, читается как поломка.
     *
     * <p><b>Отвечает только о своей сессии.</b> Сессия ищется по идентификатору
     * из cookie запроса (его знает лишь держатель) в журнале входов той
     * компании, которую браузер помнит. Компания, которой нет, сессия, которой
     * нет, и сессия, закрытая не отзывом, неразличимы — 204. Учётной записи
     * путь не требует по построению: спрашивают ровно тогда, когда её уже нет.
     *
     * @param company схема компании из запомненной личности ({@code companySchema})
     */
    @GetMapping("/ended")
    public ResponseEntity<EndedView> ended(
            @org.springframework.web.bind.annotation.RequestParam String company,
            HttpServletRequest httpRequest) {
        String sessionId = httpRequest.getRequestedSessionId();
        if (sessionId == null || !company.matches("t_\\d{6,}")) {
            return ResponseEntity.noContent().build();
        }
        return sessions.revokedDetail(company, LoginSessions.key(sessionId))
                .map(detail -> ResponseEntity.ok(
                        new EndedView("Вход закрыт: " + detail + ". Войдите заново.")))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** @param message почему закрыт вход — словами журнала входов */
    public record EndedView(String message) {
    }

    /** Кто я. Нужно приложению после перезапуска: сессия могла остаться живой. */
    @GetMapping("/me")
    public MeView me() {
        TenantPrincipal principal = CurrentUser.require();
        return MeView.of(principal, roleName(principal));
    }

    /**
     * Название роли клиента для шапки (задача 0044): своя роль «Кладовщик-2»
     * в прежней колонке значится «Просмотром», и шапка называла бы человека
     * не той ролью. Спрашивается при входе и при «кто я» — не на каждый запрос.
     */
    private String roleName(TenantPrincipal principal) {
        if (principal.isServiceAdmin()) {
            return null;
        }
        MemberAccess.Access rights = access.resolve(principal.tenantSchema(), principal.memberId());
        return rights == null ? null : rights.roleName();
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

    /**
     * @param roleName    роль клиента названием; {@code null} — роли нет либо администратор
     * @param owner       роль «Владелец» или администратор сервиса: все права всегда
     * @param permissions ключи прав вошедшего ({@link Permission}); у владельца —
     *                    весь справочник. Экран решает по ним, что показать
     *                    доступным, а что погашенным, — своего списка ролей у него
     *                    нет (задача 0044: «полномочия приезжают с сервера»)
     */
    public record MeView(Long memberId, String login, String displayName, String role,
                         String companySchema, String roleName, boolean owner,
                         List<String> permissions) {

        static MeView of(TenantPrincipal principal, String roleName) {
            return new MeView(principal.memberId(), principal.login(), principal.displayName(),
                    principal.role(), principal.tenantSchema(), roleName, principal.actsAsOwner(),
                    principal.getAuthorities().stream()
                            .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                            .filter(key -> Permission.byKey(key).isPresent())
                            .sorted()
                            .toList());
        }
    }
}
