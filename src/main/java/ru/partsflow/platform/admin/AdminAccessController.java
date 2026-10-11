package ru.partsflow.platform.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;
import ru.partsflow.platform.security.TenantPrincipal;

/**
 * Доступ администратора сервиса — со стороны владельца разборки.
 *
 * <p>Своя кнопка владельца, а не ответ на входящий запрос: решение владельца
 * продукта от 8 сентября 2026 (tasks/0042) — «у клиента должна быть отдельная
 * кнопка дать доступ администратору», и сценарий «позвонил в поддержку, дал
 * доступ» обязан работать без того, чтобы с той стороны сначала нажали
 * «прошу».
 *
 * <p><b>Давать, отзывать и отклонять — только владелец, и не администратор
 * в его кабинете.</b> Права у администратора внутри — владельца (так решено
 * тем же днём: «делать абсолютно всё»), и без явного «не ADMIN» он мог бы
 * продлить себе доступ сам, то есть «только с разрешения владельца»
 * перестало бы быть правдой. Смотреть состояние ему можно.
 *
 * <p>Держит это теперь правило на путях входа в {@code SecurityConfig}
 * ({@code ACCESS_CONTROL}): оно закрывает от администратора и этот контроллер,
 * и сотрудников с паролями. Запись здесь осталась вторым рубежом — на случай,
 * если контроллер когда-нибудь переедет на другой адрес.
 */
@RestController
@RequestMapping("/api/organization/admin-access")
public class AdminAccessController {

    private static final String OWNER_ONLY = "hasRole('OWNER') and !hasRole('ADMIN')";

    private final AdminAccess access;

    public AdminAccessController(AdminAccess access) {
        this.access = access;
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public AdminAccess.State state() {
        return access.state(CurrentUser.require().tenantId());
    }

    /**
     * Дать доступ. Пустой срок — до отзыва; иначе число часов, которое выбрал
     * владелец (решение 8 сентября: «клиент сам выбирает»).
     */
    @PostMapping("/grant")
    @PreAuthorize(OWNER_ONLY)
    public AdminAccess.State grant(@RequestBody GrantRequest request) {
        TenantPrincipal owner = CurrentUser.require();
        return access.grant(owner.tenantId(), owner.memberId(), request.hours());
    }

    /** Отозвать доступ — в том числе пока администратор внутри: его выгонит сразу. */
    @PostMapping("/revoke")
    @PreAuthorize(OWNER_ONLY)
    public AdminAccess.State revoke() {
        TenantPrincipal owner = CurrentUser.require();
        return access.revoke(owner.tenantId(), owner.memberId());
    }

    /** Отклонить запрос администратора, не давая доступа. */
    @PostMapping("/decline")
    @PreAuthorize(OWNER_ONLY)
    public AdminAccess.State decline() {
        return access.decline(CurrentUser.require().tenantId());
    }

    /** @param hours {@code null} — до отзыва */
    public record GrantRequest(Integer hours) {
    }
}
