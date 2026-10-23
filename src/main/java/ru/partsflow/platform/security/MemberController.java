package ru.partsflow.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.session.SessionTrackingFilter;

import java.util.List;

/**
 * Сотрудники арендатора.
 *
 * <p><b>Первые проверки роли в проекте.</b> До этого {@code tenant_member.role}
 * существовал, но не проверялся нигде: вошедший сотрудник мог всё. Здесь роль
 * начинает работать, потому что иначе продавец завёл бы себе владельца.
 *
 * <p>Проверки стоят рядом с операциями, а не в конфигурации по путям: правило
 * «создавать сотрудников может владелец» читается там, где создают сотрудника,
 * а не в файле настроек, куда никто не смотрит.
 */
@RestController
@RequestMapping("/api/members")
public class MemberController {

    private final MemberService members;
    private final AccessRoleService roles;

    public MemberController(MemberService members, AccessRoleService roles) {
        this.members = members;
        this.roles = roles;
    }

    /**
     * Заведение сотрудника.
     *
     * <p>С задачи 0044 роль приходит ссылкой на роль клиента ({@code roleId}):
     * заведение и назначение — одна транзакция ({@link AccessRoleService#createMember}),
     * иначе отказ назначения оставил бы заведённого сотрудника без роли.
     * Прежняя строка {@code role} принимается по-прежнему — её шлёт вкладка,
     * открытая до выкладки, — но роль «Владелец» ею выдаёт только владелец:
     * иначе сотрудник с правом «Сотрудники и роли» завёл бы себе владельца.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('members.manage')")
    public ResponseEntity<MemberService.Member> create(@Valid @RequestBody CreateRequest request) {
        if (request.roleId() != null) {
            return ResponseEntity.status(HttpStatus.CREATED).body(roles.createMember(
                    request.login(), request.password(), request.displayName(),
                    request.branchId(), request.roleId()));
        }
        if (request.role() == null || request.role().isBlank()) {
            throw new IllegalArgumentException("Роль сотрудника обязательна");
        }
        // Владельца заводит только владелец (задача 0044): иначе сотрудник
        // с правом «Управлять сотрудниками и ролями» завёл бы себе владельца
        // и получил бы через него права на всё.
        if ("OWNER".equals(request.role()) && !CurrentUser.require().actsAsOwner()) {
            throw new AccessDeniedException("Владельца заводит только владелец");
        }
        MemberService.Member created = members.create(
                request.login(), request.password(), request.displayName(),
                request.role(), request.branchId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('members.manage')")
    public List<MemberService.Member> all() {
        return members.all();
    }

    /**
     * Смена пароля: владельцем — любому, сотрудником — только себе.
     *
     * <p>Проверка «себе» через сравнение с {@code memberId} принципала: без неё
     * любой вошедший менял бы пароль владельцу и забирал компанию.
     */
    @PostMapping("/{id}/password")
    @PreAuthorize("hasAuthority('members.manage') or #id == authentication.principal.memberId")
    public ResponseEntity<Void> changePassword(@PathVariable Long id,
                                               @Valid @RequestBody PasswordRequest request,
                                               HttpServletRequest httpRequest) {
        requireOwnerTouchesOwner(id);
        // Своя сессия называется явно: смена пароля закрывает все остальные,
        // а того, кто её меняет, оставляет работать.
        members.changePassword(id, request.password(),
                SessionTrackingFilter.keyOf(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /**
     * Отключение доступа.
     *
     * <p>Себя отключить нельзя: единственный владелец запер бы компанию,
     * и починить это можно было бы только руками в БД.
     */
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('members.manage')")
    public ResponseEntity<Void> disable(@PathVariable Long id) {
        if (id.equals(CurrentUser.require().memberId())) {
            // Словами, а не пустым 409. Экран показывает эту кнопку всем,
            // включая самого вошедшего, и рассчитывает, что отказ объяснит
            // сервер, — но тело ответа было пустым, и владелец видел
            // «Запрос отклонён (409)»: ни что случилось, ни что делать.
            // Отказ без объяснения читается как поломка, а это правило.
            throw new IllegalStateException(
                    "Себя выключить нельзя: выйти из компании было бы некому "
                            + "вернуть. Попросите другого владельца");
        }
        requireOwnerTouchesOwner(id);
        members.setActive(id, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('members.manage')")
    public ResponseEntity<Void> enable(@PathVariable Long id) {
        requireOwnerTouchesOwner(id);
        members.setActive(id, true);
        return ResponseEntity.noContent().build();
    }


    /**
     * Учётную запись владельца — пароль, выключение, включение — меняет
     * только владелец (задача 0044). Право «Управлять сотрудниками и ролями»
     * сотрудника иначе давало бы ему пароль владельца, то есть права на всё.
     * Свой пароль меняет каждый.
     */
    private void requireOwnerTouchesOwner(Long id) {
        TenantPrincipal actor = CurrentUser.require();
        if (!actor.actsAsOwner() && !id.equals(actor.memberId()) && members.isOwner(id)) {
            throw new AccessDeniedException("Учётную запись владельца меняет только владелец");
        }
    }

    /**
     * @param role   прежняя жёсткая роль строкой — от вкладки, открытой до 0044
     * @param roleId роль клиента; если указана, {@code role} не читается
     */
    public record CreateRequest(@NotBlank String login,
                                @NotBlank String password,
                                String displayName,
                                String role,
                                Long branchId,
                                Long roleId) {
    }

    public record PasswordRequest(@NotBlank String password) {
    }

}
