package ru.partsflow.platform.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Чьи права у сотрудника и какие — по ролям клиента (задача 0044,
 * {@code tenant/100}–{@code 101}).
 *
 * <p>Итог: роль «Владелец» — все права всегда; иначе права роли плюс
 * добавленные поверх роли минус снятые (ответ владельца 23.10.2026,
 * «на оставшиеся три», пункт 2).
 *
 * <p><b>Роль сотрудника — ссылка {@code tenant_member.access_role_id}, а если её
 * нет — пара {@code role → access_role.seeded_from}.</b> Пустую ссылку оставляет
 * строка, заведённая прежним образом после наката (откат приложения законен,
 * задача 0112), и сотрудник, заведённый провижинингом: оба пишут только
 * прежнюю колонку {@code role}. Пара находит ту же роль, которую ему дал бы
 * перенос {@code tenant/101}.
 *
 * <p><b>«Роли нет» — это три случая, и решены они по-разному.</b>
 * Владелец (прежняя колонка {@code OWNER}) без строки роли «Владелец» —
 * владелец: все права всегда.
 * <ul>
 *   <li>Ревизор ({@code AUDITOR}), заведённый там, где «Ревизора» нет (его
 *       заводит {@code tenant/101} только клиентам, у которых такой сотрудник
 *       был), получает журнал — {@link Permission#JOURNAL_VIEW}, то, ради чего
 *       роль и существовала. Молчаливое «прав нет» отняло бы у ревизора
 *       журнал, а «доступ никто не теряет» — условие владельца при переносе.</li>
 *   <li>Прежняя роль, которую клиент удалил сам, ничего не даёт: удалил её
 *       владелец, и вернуть её набор задним числом значило бы решить за него.
 *       Сотрудник остаётся с правами поверх роли, а экран сотрудников
 *       называет его «без роли» — назначить роль владелец может сразу.</li>
 * </ul>
 *
 * <p><b>Читается со схемой в тексте запроса, а не через {@code TenantContext}.</b>
 * Вход происходит до того, как арендатор известен, — тот же довод и тот же
 * приём, что у {@link MemberAuthenticationProvider}; имя схемы проверяется
 * регуляркой до склейки.
 */
@Component
public class MemberAccess {

    private static final Pattern SCHEMA = Pattern.compile("^t_[0-9]{6,}$");

    private final JdbcTemplate jdbc;

    public MemberAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Права сотрудника.
     *
     * @return {@code null}, если сотрудника нет
     */
    public Access resolve(String schema, long memberId) {
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("Недопустимое имя схемы арендатора");
        }
        List<Role> found = jdbc.query("""
                SELECT r.id, r.name, coalesce(r.is_owner, false) AS is_owner, m.role
                  FROM %1$s.tenant_member m
                  LEFT JOIN %1$s.access_role r
                    ON r.id = coalesce(m.access_role_id,
                              (SELECT s.id FROM %1$s.access_role s
                                WHERE s.seeded_from = m.role AND m.role <> 'ADMIN'))
                 WHERE m.id = ?""".formatted(schema),
                (rs, i) -> new Role(
                        rs.getObject("id") == null ? null : rs.getLong("id"),
                        rs.getString("name"),
                        rs.getBoolean("is_owner"),
                        rs.getString("role")),
                memberId);
        if (found.isEmpty()) {
            return null;
        }
        Role role = found.get(0);
        // Владелец без строки роли «Владелец» (её снесли прямым SQL либо схема
        // наполнена мимо 101): прежняя колонка OWNER — тоже владелец. У владельца
        // права на всё и всегда, и пропавшая строка этого не отнимает.
        if (role.owner() || (role.id() == null && "OWNER".equals(role.legacyRole()))) {
            // Набор владельца и права поверх него не читаются вовсе: у владельца
            // права на всё и всегда, и строка в базе этого не сузит.
            return new Access(role.id(), role.name(), true, Set.of());
        }

        Set<String> granted = new LinkedHashSet<>();
        if (role.id() != null) {
            granted.addAll(jdbc.queryForList(
                    "SELECT permission FROM %s.access_role_permission WHERE role_id = ?"
                            .formatted(schema), String.class, role.id()));
        } else if ("AUDITOR".equals(role.legacyRole())) {
            granted.add(Permission.JOURNAL_VIEW.key());
        }
        jdbc.query("""
                SELECT permission, granted FROM %s.member_permission_override
                 WHERE member_id = ?""".formatted(schema),
                rs -> {
                    if (rs.getBoolean("granted")) {
                        granted.add(rs.getString("permission"));
                    } else {
                        granted.remove(rs.getString("permission"));
                    }
                },
                memberId);
        // Ключ, которого код не знает (переименован, забыт), не открывает ничего
        // и наружу не уезжает: экран назвать его не сможет.
        granted.removeIf(key -> Permission.byKey(key).isEmpty());
        return new Access(role.id(), role.name(), false, Set.copyOf(granted));
    }

    /**
     * @param roleId      роль сотрудника; {@code null} — роли нет
     * @param roleName    её название, как его написал владелец
     * @param owner       роль «Владелец»
     * @param permissions права не-владельца; у владельца пусто — он может всё
     */
    public record Access(Long roleId, String roleName, boolean owner, Set<String> permissions) {
    }

    private record Role(Long id, String name, boolean owner, String legacyRole) {
    }
}
