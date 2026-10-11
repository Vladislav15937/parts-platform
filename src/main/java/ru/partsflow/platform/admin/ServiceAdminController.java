package ru.partsflow.platform.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.TenantAuthenticationToken;
import ru.partsflow.platform.security.TenantPrincipal;
import ru.partsflow.platform.session.LoginSessions;
import ru.partsflow.platform.session.SessionTrackingFilter;

import java.time.Instant;
import java.util.List;

/**
 * Система администрирования сервиса — отдельная от всех кабинетов.
 *
 * <p>Решение владельца продукта от 8 сентября 2026 (tasks/0042, пункт 5):
 * «разборка выбирается только в административном окне, живущем отдельно
 * от всех разборок, это отдельная система администрирования». Отсюда свой
 * вход (логин и пароль из {@code public.service_admin}, без кода компании),
 * свой список клиентов сервиса и свой путь в кабинет. Ни один из этих путей
 * не доступен вошедшему в кабинет сотруднику, а пути кабинета — вошедшему
 * сюда администратору: разграничение стоит в {@code SecurityConfig}.
 *
 * <p>Права на пути — там же, по путям, а не {@code @PreAuthorize}: здесь
 * не одно правило на операцию, а граница между двумя системами, и она
 * читается одним местом.
 */
@RestController
@RequestMapping("/api/admin")
public class ServiceAdminController {

    private final AuthenticationManager authenticationManager;
    private final AdminAccess access;
    private final AdminVisits visits;
    private final LoginSessions sessions;
    private final SecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();

    public ServiceAdminController(AuthenticationManager authenticationManager, AdminAccess access,
                                  AdminVisits visits, LoginSessions sessions) {
        this.authenticationManager = authenticationManager;
        this.access = access;
        this.visits = visits;
        this.sessions = sessions;
    }

    /**
     * Вход в систему администрирования. Отказ — 401 с пустым телом, как у входа
     * в кабинет: что именно не сошлось, снаружи не различить.
     */
    @PostMapping("/login")
    public ResponseEntity<AdminMe> login(@Valid @RequestBody LoginRequest request,
                                         HttpServletRequest http, HttpServletResponse response) {
        Authentication authenticated;
        try {
            authenticated = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.login(), request.password()));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        startSession(authenticated, http, response);
        ServiceAdminPrincipal admin = (ServiceAdminPrincipal) authenticated.getPrincipal();
        return ResponseEntity.ok(new AdminMe(admin.login(), admin.displayName(), null));
    }

    /**
     * Кто я — и не сижу ли я сейчас в чьём-то кабинете.
     *
     * <p>Отвечает и в системе администрирования, и из кабинета: оттуда
     * приложение показывает администратору, в чьём кабинете он работает
     * и сколько осталось до конца разрешения (критерий 5 — «срок окончания
     * виден обеим сторонам»).
     */
    @GetMapping("/me")
    public AdminMe me() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof TenantPrincipal visit) {
            AdminVisits.Tenant tenant = visits.tenant(visit.tenantId()).orElse(null);
            AdminAccess.Grant grant = access.state(visit.tenantId()).grant();
            return new AdminMe(visit.login(), visit.displayName(), new Visit(
                    tenant == null ? null : tenant.code(),
                    tenant == null ? null : tenant.companyName(),
                    grant == null ? null : grant.expiresAt()));
        }
        ServiceAdminPrincipal admin = (ServiceAdminPrincipal) principal;
        return new AdminMe(admin.login(), admin.displayName(), null);
    }

    /** Клиенты сервиса с состоянием доступа: есть ли разрешение, до когда, просили ли. */
    @GetMapping("/tenants")
    public List<AdminAccess.TenantAccess> tenants() {
        return access.tenants();
    }

    /**
     * Попросить доступ кнопкой. Владелец увидит запрос у себя, в разделе
     * «Доступ администратора сервиса», и ответит своей кнопкой.
     */
    @PostMapping("/tenants/{code}/request")
    public ResponseEntity<Void> requestAccess(@PathVariable String code) {
        ServiceAdminPrincipal admin = currentAdmin();
        AdminVisits.Tenant tenant = visits.tenantByCodeOrThrow(code);
        access.request(tenant.id(), admin.adminId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Войти в кабинет разборки — собой, отдельным участником с ролью
     * администратора, и только при действующем разрешении.
     *
     * <p>Сессия заводится заново, как при обычном входе: смена личности
     * в той же сессии — это подмена, которую тот же приём закрывает у входа
     * в кабинет. Вход пишется в журнал входов разборки (критерий 3: «клиент
     * видит входы администратора той же вкладкой „Входы“»).
     */
    @PostMapping("/tenants/{code}/enter")
    public VisitStarted enter(@PathVariable String code, HttpServletRequest http,
                              HttpServletResponse response) {
        ServiceAdminPrincipal admin = currentAdmin();
        TenantPrincipal visit = visits.enter(admin, code);

        String ip = http.getRemoteAddr();
        String userAgent = http.getHeader("User-Agent");
        HttpSession session = startSession(
                new TenantAuthenticationToken(visit, visit.getAuthorities()), http, response);
        session.setAttribute(AdminVisits.ADMIN_ID, admin.adminId());

        String key = LoginSessions.key(session.getId());
        session.setAttribute(SessionTrackingFilter.KEY, key);
        sessions.recordLogin(visit.tenantSchema(), visit.memberId(), visit.login(),
                visit.role(), key, ip, userAgent);
        return new VisitStarted(code);
    }

    /**
     * Выйти из кабинета обратно в систему администрирования.
     *
     * <p>В журнале входов разборки это выход по кнопке — тот же исход, что
     * у сотрудника, нажавшего «Выйти»: человек ушёл сам.
     */
    @PostMapping("/visit/leave")
    public ResponseEntity<AdminMe> leave(HttpServletRequest http, HttpServletResponse response) {
        TenantPrincipal visit = (TenantPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        HttpSession current = http.getSession(false);
        Object adminId = current == null ? null : current.getAttribute(AdminVisits.ADMIN_ID);

        String key = SessionTrackingFilter.keyOf(http);
        if (key != null) {
            sessions.recordLogout(visit.tenantSchema(), key);
        }
        ServiceAdminPrincipal admin = adminId instanceof Long id
                ? visits.admin(id).orElse(null) : null;
        if (admin == null) {
            // Учётную запись выключили, пока он был в кабинете: вернуться
            // некуда, и сессия кончается совсем.
            if (current != null) {
                current.invalidate();
            }
            SecurityContextHolder.clearContext();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        startSession(UsernamePasswordAuthenticationToken.authenticated(
                admin, null, admin.getAuthorities()), http, response);
        return ResponseEntity.ok(new AdminMe(admin.login(), admin.displayName(), null));
    }

    private HttpSession startSession(Authentication authentication, HttpServletRequest http,
                                     HttpServletResponse response) {
        http.getSession(true).invalidate();
        HttpSession session = http.getSession(true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, http, response);
        return session;
    }

    private static ServiceAdminPrincipal currentAdmin() {
        return (ServiceAdminPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
    }

    public record LoginRequest(@NotBlank String login, @NotBlank String password) {
    }

    /** @param visit кабинет, в котором администратор сейчас, либо {@code null} */
    public record AdminMe(String login, String displayName, Visit visit) {
    }

    /** @param expiresAt конец разрешения; {@code null} — до отзыва */
    public record Visit(String code, String companyName, Instant expiresAt) {
    }

    public record VisitStarted(String code) {
    }
}
