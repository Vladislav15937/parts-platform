package ru.partsflow.platform.security;

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
    private final MemberBootstrap bootstrap;

    public MemberController(MemberService members, MemberBootstrap bootstrap) {
        this.members = members;
        this.bootstrap = bootstrap;
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<MemberService.Member> create(@Valid @RequestBody CreateRequest request) {
        MemberService.Member created = members.create(
                request.login(), request.password(), request.displayName(),
                request.role(), request.branchId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
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
    @PreAuthorize("hasRole('OWNER') or #id == authentication.principal.memberId")
    public ResponseEntity<Void> changePassword(@PathVariable Long id,
                                               @Valid @RequestBody PasswordRequest request) {
        members.changePassword(id, request.password());
        return ResponseEntity.noContent().build();
    }

    /**
     * Отключение доступа.
     *
     * <p>Себя отключить нельзя: единственный владелец запер бы компанию,
     * и починить это можно было бы только руками в БД.
     */
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> disable(@PathVariable Long id) {
        if (id.equals(CurrentUser.require().memberId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        members.setActive(id, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> enable(@PathVariable Long id) {
        members.setActive(id, true);
        return ResponseEntity.noContent().build();
    }

    /**
     * Первый владелец арендатора.
     *
     * <p>Разрывает замкнутый круг: создавать сотрудников может владелец,
     * а владельца создать некому. Работает только пока у арендатора нет ни одной
     * учётной записи и только с настроенным секретом — см. {@link MemberBootstrap}.
     *
     * <p>Правильное место для этого — провижининг арендатора, но его в коде
     * пока нет: схемы создаются скриптом. Когда появится, этот эндпоинт уйдёт.
     */
    @PostMapping("/bootstrap")
    public ResponseEntity<MemberService.Member> bootstrapOwner(
            @Valid @RequestBody BootstrapRequest request) {

        return bootstrap.createFirstOwner(request)
                .map(member -> ResponseEntity.status(HttpStatus.CREATED).body(member))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }

    public record CreateRequest(@NotBlank String login,
                                @NotBlank String password,
                                String displayName,
                                @NotBlank String role,
                                Long branchId) {
    }

    public record PasswordRequest(@NotBlank String password) {
    }

    /**
     * Заявка на первого владельца. Код компании здесь, а не из сессии: сессии
     * ещё нет и быть не может — входить некому.
     */
    public record BootstrapRequest(@NotBlank String company,
                                   @NotBlank String token,
                                   @NotBlank String login,
                                   @NotBlank String password,
                                   String displayName) {
    }
}
