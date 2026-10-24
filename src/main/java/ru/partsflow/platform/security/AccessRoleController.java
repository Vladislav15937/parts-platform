package ru.partsflow.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Роли клиента и права сотрудников — экран «Сотрудники» (задача 0044).
 *
 * <p>Всё, кроме справочника, — по праву «Сотрудники и роли»: кто что может
 * в компании, решает тот же, кто заводит сотрудников. Изменяющие пути
 * лежат в {@code SecurityConfig.ACCESS_CONTROL}: администратор сервиса,
 * у которого права владельца, раздавать права не может — выданное им
 * пережило бы отзыв его доступа, как заведённый им сотрудник (разбор PR 393).
 */
@RestController
@RequestMapping("/api/access")
public class AccessRoleController {

    /**
     * Списки ролей и прав сотрудников видит держатель любого из четырёх прав
     * на раздачу: назначить роль, выдать право поверх роли или поправить роль
     * без них нельзя (ответ владельца 24.10.2026, «развёрнутые вопросы», 2а).
     */
    static final String ANY_MEMBERS = "hasAuthority('members.accounts') or hasAuthority('members.assign_roles')"
            + " or hasAuthority('members.roles') or hasAuthority('members.grant_overrides')";

    private static final String ROLES = "hasAuthority('members.roles')";

    /** Роль и права поверх роли — одним сохранением; что именно меняется, проверяет сервис. */
    private static final String ASSIGNS = "hasAuthority('members.assign_roles')"
            + " or hasAuthority('members.grant_overrides')";

    private final AccessRoleService roles;

    public AccessRoleController(AccessRoleService roles) {
        this.roles = roles;
    }

    /**
     * Справочник действий словами — любому вошедшему: погашенная кнопка
     * называет причину «нет права «…»», и слово для неё нужно каждому,
     * а не только тому, кто раздаёт права. Второй копии слов на экране нет.
     */
    @GetMapping("/catalog")
    public List<AccessRoleService.Section> catalog() {
        return roles.catalog();
    }

    @GetMapping("/roles")
    @PreAuthorize(ANY_MEMBERS)
    public List<AccessRoleService.RoleView> roles() {
        return roles.roles();
    }

    @PostMapping("/roles")
    @PreAuthorize(ROLES)
    public ResponseEntity<AccessRoleService.RoleView> create(@Valid @RequestBody RoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(roles.create(request.name(), request.permissions()));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize(ROLES)
    public AccessRoleService.RoleView update(@PathVariable long id,
                                             @Valid @RequestBody RoleRequest request) {
        return roles.update(id, request.name(), request.permissions());
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize(ROLES)
    public ResponseEntity<Void> delete(@PathVariable long id) {
        roles.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/members")
    @PreAuthorize(ANY_MEMBERS)
    public List<AccessRoleService.MemberAccessView> members() {
        return roles.members();
    }

    /** Роль сотрудника и права поверх неё — одним сохранением. */
    @PutMapping("/members/{id}")
    @PreAuthorize(ASSIGNS)
    public AccessRoleService.MemberAccessView assign(@PathVariable long id,
                                                     @Valid @RequestBody AssignRequest request) {
        return roles.assign(id, request.roleId(), request.overrides());
    }

    /** Название и набор прав ключами справочника. */
    public record RoleRequest(String name, List<String> permissions) {
    }

    /**
     * @param overrides право → {@code true} добавлено поверх роли, {@code false} снято
     */
    public record AssignRequest(@NotNull Long roleId, Map<String, Boolean> overrides) {
    }
}
