package ru.partsflow.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
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
import java.util.Map;

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

    /**
     * Отказ не-владельцу, который заводит или назначает владельца, — словами
     * и одними на все пути: заведение прежней строкой, заведение ролью клиента,
     * назначение и снятие роли «Владелец» (AccessRoleService).
     */
    static final String OWNER_GRANT = "Владельца назначает только владелец: роль «Владелец» "
            + "даёт все права, и выдать её может только тот, у кого они есть";

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
    @PreAuthorize("hasAuthority('members.accounts')")
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
            // Словами, а не «Недостаточно прав» (повторный живой прогон 24.10.2026):
            // отказ в выдаче называет причину, как остальные (RightsRefusal).
            throw new RightsRefusal(OWNER_GRANT);
        }
        // Через роли клиента, а не MemberService напрямую: права заведённого
        // не шире прав заводящего (задача 0044, ответ владельца 24.10.2026, 2б).
        MemberService.Member created = roles.createMemberWithLegacyRole(
                request.login(), request.password(), request.displayName(),
                request.role(), request.branchId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Сотрудники — и у каждого, почему вошедшему нельзя менять ему пароль,
     * выключать и включать ({@code accountLocked}; пусто — можно). Признак
     * считает сервер тем же правилом, что отказ
     * ({@link AccessRoleService#requireMayChangeAccount}): экран гасит кнопки
     * по нему и своих правил не держит.
     */
    @GetMapping
    @PreAuthorize(AccessRoleController.ANY_MEMBERS)
    public List<Listed> all() {
        List<MemberService.Member> found = members.all();
        Map<Long, String> limits = roles.accountLimits(
                found.stream().map(MemberService.Member::id).toList());
        return found.stream().map(m -> new Listed(m.id(), m.number(), m.login(), m.displayName(),
                m.role(), m.active(), m.lastLoginAt(), limits.get(m.id()))).toList();
    }

    /**
     * Смена пароля: себе — каждому; другому — держателю «Сотрудники и роли»,
     * если права того не шире его собственных (владелец — любому).
     *
     * <p>Проверка «себе» через сравнение с {@code memberId} принципала: без неё
     * любой вошедший менял бы пароль владельцу и забирал компанию.
     */
    @PostMapping("/{id}/password")
    @PreAuthorize("hasAuthority('members.accounts') or #id == authentication.principal.memberId")
    public ResponseEntity<Void> changePassword(@PathVariable Long id,
                                               @Valid @RequestBody PasswordRequest request,
                                               HttpServletRequest httpRequest) {
        roles.requireMayChangeAccount(id, AccessRoleService.AccountChange.PASSWORD);
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
    @PreAuthorize("hasAuthority('members.accounts')")
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
        roles.requireMayChangeAccount(id, AccessRoleService.AccountChange.DISABLE);
        members.setActive(id, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('members.accounts')")
    public ResponseEntity<Void> enable(@PathVariable Long id) {
        roles.requireMayChangeAccount(id, AccessRoleService.AccountChange.ENABLE);
        members.setActive(id, true);
        return ResponseEntity.noContent().build();
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

    /**
     * Сотрудник в списке.
     *
     * @param accountLocked почему вошедшему нельзя менять пароль, выключать
     *                      и включать этого сотрудника; {@code null} — можно
     */
    public record Listed(Long id, long number, String login, String displayName, String role,
                         boolean active, java.time.Instant lastLoginAt, String accountLocked) {
    }

}
