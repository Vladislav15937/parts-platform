package ru.partsflow.platform.security;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.audit.AuditJournal;
import ru.partsflow.platform.session.LoginSessions;
import ru.partsflow.platform.session.SessionRevocations;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.shared.NotFound;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Роли клиента и права сотрудника (задача 0044): клиент заводит роль —
 * название и набор прав, — назначает её одному или нескольким сотрудникам
 * и добавляет или снимает отдельное право поверх роли.
 *
 * <p><b>Связь живая</b> (ответ владельца 23.10.2026, «на оставшиеся три», 1):
 * правка набора роли меняет права у всех, кому она назначена, — набор лежит
 * у роли, а не копией у сотрудника.
 *
 * <p><b>Всё, что меняет права, закрывает сессии затронутых сотрудников</b>
 * (docs/sessions.md, §6): права лежат в сессии снимком ({@link TenantPrincipal}),
 * и без отзыва сотрудник с отнятым правом доработал бы смену со старым. Правка
 * набора роли закрывает сессии <b>всех</b>, кому роль назначена. Своя сессия
 * того, кто правит, не щадится, если меняются его же права: иначе он сам
 * остался бы со старыми, и экран показывал бы ему то, чего у него уже нет.
 *
 * <p><b>Владелец — все права всегда, и сузить это нечем</b> (ответ владельца
 * 23.10.2026, пункт 7). Роль «Владелец» не переименовывается, не удаляется
 * и набора не получает; права поверх неё не добавляются и не снимаются;
 * последнего владельца снять нельзя. Назначить и снять роль «Владелец» может
 * только владелец: сотрудник с правом «Сотрудники и роли» иначе сделал бы
 * владельцем себя.
 *
 * <p><b>Прежняя колонка {@code tenant_member.role} пишется и дальше</b>: её
 * читает прежний образ (откат приложения законен, задача 0112) и ей подписан
 * снимок роли автора в журнале. Владельцу — {@code OWNER}, роли переноса — её
 * прежняя жёсткая ({@code seeded_from}), своей роли клиента — {@code VIEWER}:
 * вниз, как во всех откатах ролей (запись {@code tenant/101} в PR 502).
 */
@Service
public class AccessRoleService {

    private final JdbcTemplate jdbc;
    private final AuditJournal journal;
    private final LoginSessions sessions;
    private final SessionRevocations revocations;
    private final MemberAccess access;
    private final MemberService members;
    private final MemberSignatures signatures;

    public AccessRoleService(JdbcTemplate jdbc, AuditJournal journal, LoginSessions sessions,
                             SessionRevocations revocations, MemberAccess access,
                             MemberService members, MemberSignatures signatures) {
        this.signatures = signatures;
        this.members = members;
        this.jdbc = jdbc;
        this.journal = journal;
        this.sessions = sessions;
        this.revocations = revocations;
        this.access = access;
    }

    // ------------------------------------------------------------------ справочник

    /** Справочник действий по разделам — для экрана ролей. */
    public List<Section> catalog() {
        Map<Permission.Section, List<Action>> sections = new LinkedHashMap<>();
        for (Permission.Section section : Permission.Section.values()) {
            sections.put(section, new ArrayList<>());
        }
        for (Permission permission : Permission.values()) {
            sections.get(permission.section()).add(
                    new Action(permission.key(), permission.title(), permission.hint()));
        }
        return sections.entrySet().stream()
                .map(entry -> new Section(entry.getKey().title(), entry.getValue()))
                .toList();
    }

    // ----------------------------------------------------------------------- роли

    @Transactional(readOnly = true)
    public List<RoleView> roles() {
        Map<Long, List<String>> sets = new LinkedHashMap<>();
        jdbc.query("SELECT role_id, permission FROM access_role_permission ORDER BY permission",
                rs -> {
                    sets.computeIfAbsent(rs.getLong("role_id"), id -> new ArrayList<>())
                            .add(rs.getString("permission"));
                });
        TenantPrincipal actor = CurrentUser.require();
        String schema = TenantContext.require();
        MemberAccess.Access mine = actorAccess(schema, actor);
        // Владелец первым, дальше по названию: «кто есть кто» читают сверху вниз.
        return jdbc.query("""
                SELECT r.id, r.name, r.is_owner, r.seeded_from,
                       (SELECT count(*) FROM tenant_member m
                         WHERE m.login IS NOT NULL
                           AND (m.access_role_id = r.id
                                OR (m.access_role_id IS NULL AND m.role = r.seeded_from))) AS members
                  FROM access_role r
                 ORDER BY r.is_owner DESC, lower(r.name)""",
                (rs, i) -> {
                    long id = rs.getLong("id");
                    boolean owner = rs.getBoolean("is_owner");
                    List<String> wider = owner ? List.of()
                            : widerAssigned(schema, actor, mine, id, rs.getString("seeded_from"));
                    return new RoleView(id, rs.getString("name"), owner,
                            owner ? List.of() : known(sets.getOrDefault(id, List.of())),
                            rs.getInt("members"),
                            wider.isEmpty() ? null : roleLocked(rs.getString("name"), wider));
                });
    }

    @Transactional
    public RoleView create(String name, Collection<String> permissions) {
        String title = requireName(name);
        Set<String> keys = requireKnown(permissions);
        requireOwnRights(keys, "В наборе роли есть права, которых у вас нет: ");
        long id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO access_role (name, created_by) VALUES (?, ?) RETURNING id""",
                    Long.class, title, CurrentUser.memberId());
        } catch (DuplicateKeyException e) {
            throw nameTaken(title);
        }
        writeSet(id, keys);
        journal.created("access_role", id, snapshot(title, keys));
        return role(id);
    }

    /**
     * Название и набор роли. Живая связь: сессии всех, кому роль назначена,
     * закрываются — права у них поменялись.
     */
    @Transactional
    public RoleView update(long roleId, String name, Collection<String> permissions) {
        Stored was = lockRole(roleId);
        if (was.owner()) {
            throw new IllegalStateException(
                    "Роль «Владелец» встроенная: у владельца все права всегда, её не "
                            + "переименовать и набор у неё не меняется");
        }
        String title = requireName(name);
        Set<String> keys = requireKnown(permissions);
        if (!was.permissions().equals(keys)) {
            // Только переименование прав не выдаёт и не сужает никого — его не
            // проверяют ни «свои права», ни «назначена тем, кто шире» (живой
            // прогон волны 24.10c: «Менеджер» с прежним набором и новым названием
            // отвечал 403, хотя решено «переименовать можно»).
            requireOwnRights(keys, "В наборе роли есть права, которых у вас нет: ");
            // Живая связь: новый набор получат все, кому роль назначена, — значит
            // правка набора меняет права и тем из них, у кого прав больше, чем
            // у правящего. Сужать их ему нельзя (ответ владельца 24.10.2026 после
            // волны, вопрос 2: «нельзя»), расширить не даёт requireOwnRights выше.
            // Переименовать роль — можно: права это не трогает.
            TenantPrincipal actor = CurrentUser.require();
            String schema = TenantContext.require();
            List<String> wider = widerAssigned(schema, actor, actorAccess(schema, actor), roleId,
                    was.seededFrom());
            if (!wider.isEmpty()) {
                throw new RightsRefusal(roleLocked(was.name(), wider));
            }
        }
        try {
            jdbc.update("UPDATE access_role SET name = ? WHERE id = ?", title, roleId);
        } catch (DuplicateKeyException e) {
            throw nameTaken(title);
        }
        jdbc.update("DELETE FROM access_role_permission WHERE role_id = ?", roleId);
        writeSet(roleId, keys);

        if (!was.name().equals(title) || !was.permissions().equals(keys)) {
            journal.changed("access_role", roleId, snapshot(was.name(), was.permissions()),
                    snapshot(title, keys));
        }
        if (!was.permissions().equals(keys)) {
            for (long member : assignedTo(roleId, was.seededFrom())) {
                revoke(member, "права роли изменены");
            }
        }
        return role(roleId);
    }

    /**
     * Удаление роли. Назначенную — нельзя: что делать с её сотрудниками
     * (назначить другую), решает человек, а не тихое «без роли».
     */
    @Transactional
    public void delete(long roleId) {
        Stored was = lockRole(roleId);
        if (was.owner()) {
            throw new IllegalStateException(
                    "Роль «Владелец» встроенная и не удаляется: без неё в компании "
                            + "не было бы того, у кого все права");
        }
        int assigned = assignedTo(roleId, was.seededFrom()).size();
        if (assigned > 0) {
            throw new IllegalStateException(
                    "Роль «%s» назначена: %s. Сначала назначьте им другую роль"
                            .formatted(was.name(), employees(assigned)));
        }
        jdbc.update("DELETE FROM access_role WHERE id = ?", roleId);
        journal.deleted("access_role", roleId, snapshot(was.name(), was.permissions()));
    }

    // ------------------------------------------------------------------ сотрудники

    /** Роль и права поверх роли у каждого сотрудника — для экрана сотрудников. */
    @Transactional(readOnly = true)
    public List<MemberAccessView> members() {
        String schema = TenantContext.require();
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM tenant_member WHERE login IS NOT NULL OR role = 'ADMIN'
                 ORDER BY id""", Long.class);
        Map<Long, Map<String, Boolean>> overrides = overrides();
        List<MemberAccessView> views = new ArrayList<>();
        for (long id : ids) {
            MemberAccess.Access rights = access.resolve(schema, id);
            if (rights == null) {
                continue;
            }
            Map<String, Boolean> own = overrides.getOrDefault(id, Map.of());
            views.add(new MemberAccessView(id, rights.roleId(), rights.roleName(), rights.owner(),
                    keysWhere(own, true), keysWhere(own, false),
                    rights.owner() ? allKeys() : known(rights.permissions())));
        }
        return views;
    }

    /**
     * Назначить сотруднику роль и права поверх неё.
     *
     * @param overrides право → {@code true} добавлено, {@code false} снято.
     *                  Совпадающее с ролью (добавленное, которое и так в роли;
     *                  снятое, которого в роли нет) не хранится: оно ничего
     *                  не меняет, а на экране выглядело бы решением
     */
    @Transactional
    public MemberAccessView assign(long memberId, long roleId, Map<String, Boolean> overrides) {
        String schema = TenantContext.require();
        // Роль не «Владелец» — значит, назначение может снять с сотрудника
        // владельца, и проверка «владельцев не меньше одного» идёт первой
        // блокировкой транзакции (MemberService.requireOwnerRemains — одна на все
        // пути). Признак роли читается без замка: у роли «Владелец» он не
        // меняется никогда, а несуществующую роль ниже назовёт lockRole.
        List<Boolean> toOwner = jdbc.queryForList(
                "SELECT is_owner FROM access_role WHERE id = ?", Boolean.class, roleId);
        // Не-владелец, трогающий владельца, слышит про владельца, а не про
        // «последнего владельца» (живой прогон волны 24.10c): ему отказ за то,
        // что владельца меняет только владелец, сколько бы владельцев ни было.
        // Проверка только читает — первой блокировкой остаётся requireOwnerRemains;
        // после замка строки сотрудника она повторяется ниже.
        requireNotWider(schema, memberId, AccountChange.ASSIGN);
        if (toOwner.isEmpty() || !Boolean.TRUE.equals(toOwner.get(0))) {
            members.requireOwnerRemains(memberId);
        }
        Member member = lockMember(memberId);
        if (TenantPrincipal.SERVICE_ADMIN_ROLE.equals(member.legacyRole())) {
            throw new IllegalStateException(
                    "Администратору сервиса роль не назначают: права владельца даёт ему "
                            + "разрешение на вход, а закрывается оно кнопкой «Отозвать доступ»");
        }
        // Сужать того, у кого прав больше, чем у назначающего, нельзя (ответ владельца
        // 24.10.2026 после волны, вопрос 2: «нельзя»): ни сменой роли, ни правами
        // поверх неё — добавленными, снятыми или сброшенными. Правило то же, что
        // у пароля и выключения (requireMayChangeAccount), и после замка строки
        // сотрудника: его права не поменяются, пока мы решаем.
        requireNotWider(schema, memberId, AccountChange.ASSIGN);
        Stored role = lockRole(roleId);
        Map<String, Boolean> requested = overrides == null ? Map.of() : overrides;
        requireKnown(requested.keySet());
        if (role.owner() && !requested.isEmpty()) {
            throw new IllegalStateException(
                    "У владельца все права всегда: отдельные права ему не добавляют и не снимают");
        }

        MemberAccess.Access before = access.resolve(schema, memberId);
        boolean wasOwner = before != null && before.owner();
        if ((role.owner() || wasOwner) && wasOwner != role.owner()
                && !CurrentUser.require().actsAsOwner()) {
            throw new RightsRefusal(
                    "Назначить и снять роль «Владелец» может только владелец");
        }
        if (!role.owner()) {
            // Выдавать можно только свои права (ответ владельца 24.10.2026, 2б):
            // и роль целиком, и добавленное поверх неё. Снятое поверх роли —
            // не выдача, и его не проверяем; роль «Владелец» шире любого
            // не-владельца, и её выдачу держит проверка строкой выше.
            Set<String> granted = new TreeSet<>(role.permissions());
            requested.forEach((key, on) -> {
                if (Boolean.TRUE.equals(on)) {
                    granted.add(key);
                }
            });
            requireOwnRights(granted, "Выдать можно только свои права: у вас нет ");
        }

        Map<String, Boolean> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : new TreeMap<>(requested).entrySet()) {
            boolean inRole = role.permissions().contains(entry.getKey());
            if (Boolean.TRUE.equals(entry.getValue()) != inRole) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, Boolean> wasOverrides = overrides().getOrDefault(memberId, Map.of());
        requireSplitRight(before, roleId, kept, wasOverrides);

        String legacy = legacyOf(role);
        jdbc.update("UPDATE tenant_member SET access_role_id = ?, role = ? WHERE id = ?",
                roleId, legacy, memberId);
        jdbc.update("DELETE FROM member_permission_override WHERE member_id = ?", memberId);
        for (Map.Entry<String, Boolean> entry : kept.entrySet()) {
            jdbc.update("""
                    INSERT INTO member_permission_override (member_id, permission, granted, set_by)
                    VALUES (?, ?, ?, ?)""",
                    memberId, entry.getKey(), entry.getValue(), CurrentUser.memberId());
        }

        String wasRole = before == null || before.roleName() == null ? "—" : before.roleName();
        boolean changed = before == null || !Long.valueOf(roleId).equals(before.roleId())
                || !wasOverrides.equals(kept);
        if (changed) {
            journal.changed("tenant_member", memberId,
                    memberSnapshot(member.name(), wasRole, wasOverrides),
                    memberSnapshot(member.name(), role.name(), kept));
            revoke(memberId, "роль или права изменены");
        }
        return members().stream().filter(view -> view.memberId() == memberId)
                .findFirst().orElseThrow();
    }

    /**
     * Завести сотрудника сразу с ролью клиента — одной транзакцией: отказ
     * назначения (роли нет, «Владелец» не от владельца) откатывает и заведение.
     */
    @Transactional
    public MemberService.Member createMember(String login, String password, String displayName,
                                             Long branchId, long roleId) {
        // Проверки назначения — до вставки, а не правкой после неё: правка
        // писала в журнал «Роль: Просмотр → Владелец», то есть повышение
        // у сотрудника, которого завели владельцем (живой прогон 24.10.2026).
        Stored role = lockRole(roleId);
        if (role.owner() && !CurrentUser.require().actsAsOwner()) {
            throw new RightsRefusal(MemberController.OWNER_GRANT);
        }
        if (!role.owner()) {
            requireOwnRights(role.permissions(), "Выдать можно только свои права: у вас нет ");
        }
        return members.createWithAccessRole(login, password, displayName, branchId, roleId,
                legacyOf(role), role.name());
    }

    /**
     * Завести сотрудника прежней строкой роли ({@code role}) — от вкладки,
     * открытой до 0044. Одной транзакцией: права заведённого считаются тем же
     * чтением, что у входа ({@link MemberAccess}), и если они шире прав
     * заводящего — отказ откатывает и заведение.
     */
    @Transactional
    public MemberService.Member createMemberWithLegacyRole(String login, String password,
                                                           String displayName, String role,
                                                           Long branchId) {
        MemberService.Member created = members.create(login, password, displayName, role, branchId);
        MemberAccess.Access rights = access.resolve(TenantContext.require(), created.id());
        if (rights != null && !rights.owner()) {
            requireOwnRights(rights.permissions(), "Выдать можно только свои права: у вас нет ");
        }
        return created;
    }

    // ------------------------------------------------- учётная запись сотрудника

    /**
     * Менять пароль, выключать и включать сотрудника может только тот, у кого
     * есть все права этого сотрудника — права цели не шире его собственных
     * (ответ владельца 24.10.2026 после волны, пункт 1а); владелец — всех.
     * Иначе держатель «Сотрудники и роли» с правами продавца менял пароль
     * менеджеру и входил под ним — то есть получал права, которых ему не дали
     * (живой прогон 24.10.2026: 204 и 200).
     *
     * <p><b>Одно место на все три действия и на владельца.</b> Прежний запрет
     * «учётную запись владельца меняет только владелец»
     * ({@code MemberController.requireOwnerTouchesOwner}, голое «Недостаточно
     * прав») — частный случай: у владельца все права, и шире него любой
     * не-владелец. Свой пароль меняет каждый — себя правило не касается;
     * выключить себя нельзя по-прежнему, это отказ контроллера до проверки.
     *
     * <p>Права обеих сторон — из базы ({@link MemberAccess}, то же чтение, что
     * у входа и у {@link #requireOwnRights}), а не из снимка в сессии: роль
     * «Владелец», прежняя колонка, роль переноса, права поверх роли и метка
     * Ревизора решаются одинаково. Выключенный сотрудник читается со своими
     * правами — иначе включение вернуло бы в работу того, кто шире.
     */
    @Transactional(readOnly = true)
    public void requireMayChangeAccount(long memberId, AccountChange change) {
        requireNotWider(TenantContext.require(), memberId, change);
    }

    private void requireNotWider(String schema, long memberId, AccountChange change) {
        TenantPrincipal actor = CurrentUser.require();
        Wider wider = wider(schema, actor, actorAccess(schema, actor), memberId);
        if (wider != null) {
            throw new RightsRefusal(change.refusal(wider));
        }
    }

    /**
     * Кому из назначенных роль сотрудникам шире правящего — подписями
     * («Имя (логин, ID N)»), как сотрудник назван везде. Пусто — таких нет.
     */
    private List<String> widerAssigned(String schema, TenantPrincipal actor, MemberAccess.Access mine,
                                       long roleId, String seededFrom) {
        if (mine.owner()) {
            return List.of();
        }
        List<Long> wider = new ArrayList<>();
        for (long member : assignedTo(roleId, seededFrom)) {
            if (wider(schema, actor, mine, member) != null) {
                wider.add(member);
            }
        }
        if (wider.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = signatures.of(wider);
        return wider.stream().map(id -> names.getOrDefault(id, "сотрудник")).toList();
    }

    private static String roleLocked(String role, List<String> wider) {
        return "Набор роли «%s» не поправить: она назначена сотрудникам, у которых прав больше, "
                .formatted(role) + "чем у вас (" + String.join(", ", wider)
                + "), — новый набор сузил бы их. Править его может тот, у кого прав не меньше";
    }

    /**
     * Почему вошедшему нельзя менять пароль, выключать и включать каждого
     * из сотрудников, — для списка «Сотрудники». Тем же правилом, что отказ
     * ({@link #requireMayChangeAccount}): экран своих правил не держит, он
     * гасит кнопки по этому признаку. Нет записи — можно.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> accountLimits(Collection<Long> memberIds) {
        TenantPrincipal actor = CurrentUser.require();
        String schema = TenantContext.require();
        MemberAccess.Access mine = actorAccess(schema, actor);
        Map<Long, String> limits = new LinkedHashMap<>();
        for (long id : memberIds) {
            Wider wider = wider(schema, actor, mine, id);
            if (wider != null) {
                limits.put(id, wider.owner()
                        ? "Учётную запись владельца меняет только владелец"
                        : "У сотрудника прав больше, чем у вас: " + wider.titles()
                                + ". Менять ему пароль, роль и права, выключать и включать "
                                + "может тот, у кого прав не меньше");
            }
        }
        return limits;
    }

    /** Права вошедшего: администратору сервиса — как владельцу (его правила не тронуты). */
    private MemberAccess.Access actorAccess(String schema, TenantPrincipal actor) {
        if (actor.isServiceAdmin()) {
            return new MemberAccess.Access(null, null, true, Set.of());
        }
        MemberAccess.Access mine = access.resolve(schema, actor.memberId());
        return mine == null ? new MemberAccess.Access(null, null, false, Set.of()) : mine;
    }

    /** Чем цель шире вошедшего; {@code null} — не шире. */
    private Wider wider(String schema, TenantPrincipal actor, MemberAccess.Access mine,
                        long memberId) {
        if (mine.owner() || Long.valueOf(memberId).equals(actor.memberId())) {
            return null;
        }
        MemberAccess.Access target = access.resolve(schema, memberId);
        if (target == null) {
            // Сотрудника нет — словами откажет сама операция (NotFound).
            return null;
        }
        if (target.owner()) {
            return new Wider(true, "");
        }
        List<String> beyond = target.permissions().stream()
                .filter(key -> !mine.permissions().contains(key)).sorted().toList();
        return beyond.isEmpty() ? null : new Wider(false, beyond.stream()
                .map(key -> "«" + Permission.byKey(key).map(Permission::title).orElse(key) + "»")
                .collect(Collectors.joining(", ")));
    }

    /** Что делают с учётной записью — для слов отказа. */
    public enum AccountChange {
        PASSWORD("менять пароль сотруднику", "менять пароль владельцу"),
        DISABLE("выключить сотрудника", "выключить владельца"),
        ENABLE("включить сотрудника", "включить владельца"),
        ASSIGN("менять роль и права сотруднику", "менять роль и права владельцу");

        private final String member;
        private final String owner;

        AccountChange(String member, String owner) {
            this.member = member;
            this.owner = owner;
        }

        String refusal(Wider wider) {
            return wider.owner()
                    ? "Нельзя " + owner + ": у владельца все права, и его учётную запись "
                            + "меняет только владелец"
                    : "Нельзя " + member + ", у которого прав больше, чем у вас: " + wider.titles();
        }
    }

    /** @param titles лишние права названиями справочника, через запятую */
    private record Wider(boolean owner, String titles) {
    }

    // -------------------------------------------------------------- вспомогательное

    private void revoke(long memberId, String detail) {
        String schema = TenantContext.require();
        sessions.revoke(schema, memberId, detail, null);
        revocations.revoke(schema, memberId, null);
    }

    /**
     * Кому роль назначена: ссылкой и — для роли переноса — прежней колонкой
     * без ссылки (строка, заведённая прежним образом).
     */
    private List<Long> assignedTo(long roleId, String seededFrom) {
        return jdbc.queryForList("""
                SELECT id FROM tenant_member
                 WHERE access_role_id = ?
                    OR (access_role_id IS NULL AND ?::text IS NOT NULL AND role = ?::text)""",
                Long.class, roleId, seededFrom, seededFrom);
    }

    /**
     * Выдавать можно только те права, что есть у выдающего (ответ владельца
     * 24.10.2026, пункт 2б): сотрудник с «Сотрудники и роли» не заводит
     * сотрудника, не назначает роль, не добавляет право поверх роли и не
     * собирает роль шире своих прав. Владелец может всё.
     *
     * <p>Права выдающего читаются из базы, а не из снимка в сессии: снимок
     * закрывается при каждой смене прав, но проверка, которая решает, кому
     * что достанется, не должна зависеть от того, успел ли отзыв.
     */
    private void requireOwnRights(Collection<String> keys, String prefix) {
        TenantPrincipal actor = CurrentUser.require();
        if (actor.actsAsOwner()) {
            return;
        }
        MemberAccess.Access mine = access.resolve(TenantContext.require(), actor.memberId());
        if (mine != null && mine.owner()) {
            return;
        }
        Set<String> own = mine == null ? Set.of() : mine.permissions();
        List<String> beyond = keys.stream().filter(key -> !own.contains(key)).sorted().toList();
        if (!beyond.isEmpty()) {
            throw new RightsRefusal(prefix + beyond.stream()
                    .map(key -> "«" + Permission.byKey(key).map(Permission::title).orElse(key) + "»")
                    .collect(Collectors.joining(", ")));
        }
    }

    /**
     * Назначение роли и права поверх роли — два разных права (ответ владельца
     * 24.10.2026, «развёрнутые вопросы», 2а), а сохраняются одним запросом:
     * экран «Права» шлёт роль и права поверх неё вместе. Поэтому проверяется
     * то, что меняется: другая роль — «Назначать сотрудникам готовые роли»;
     * другие права поверх роли — «Выдавать и снимать права поверх роли».
     * Права поверх прежней роли, сброшенные сменой роли, — следствие
     * назначения, а не выдача: экран говорит о сбросе до «Сохранить».
     */
    private static void requireSplitRight(MemberAccess.Access before, long roleId,
                                          Map<String, Boolean> kept,
                                          Map<String, Boolean> wasOverrides) {
        TenantPrincipal actor = CurrentUser.require();
        boolean roleChanged = before == null || !Long.valueOf(roleId).equals(before.roleId());
        boolean overridesChanged = roleChanged ? !kept.isEmpty() : !kept.equals(wasOverrides);
        if (roleChanged && !actor.can(Permission.MEMBERS_ASSIGN_ROLES)) {
            throw new RightsRefusal("Назначать роли может тот, у кого есть право «"
                    + Permission.MEMBERS_ASSIGN_ROLES.title() + "»");
        }
        if (overridesChanged && !actor.can(Permission.MEMBERS_GRANT_OVERRIDES)) {
            throw new RightsRefusal("Менять права поверх роли может тот, у кого есть право «"
                    + Permission.MEMBERS_GRANT_OVERRIDES.title() + "»");
        }
    }

    /** Прежняя колонка для роли клиента: вниз, как во всех откатах ролей. */
    private static String legacyOf(Stored role) {
        return role.owner() ? "OWNER" : role.seededFrom() != null ? role.seededFrom() : "VIEWER";
    }

    private Stored lockRole(long roleId) {
        List<Stored> found = jdbc.query("""
                SELECT id, name, is_owner, seeded_from FROM access_role WHERE id = ? FOR UPDATE""",
                (rs, i) -> new Stored(rs.getLong("id"), rs.getString("name"),
                        rs.getBoolean("is_owner"), rs.getString("seeded_from"), Set.of()),
                roleId);
        if (found.isEmpty()) {
            throw NotFound.ACCESS_ROLE.error(roleId);
        }
        Stored role = found.get(0);
        // Ключ, которого код не знает (прежний members.manage рядом с четырьмя после
        // tenant/103), — не право: требовать его у назначающего значило бы отказать
        // внутренним ключом в тексте.
        Set<String> keys = new TreeSet<>(known(jdbc.queryForList(
                "SELECT permission FROM access_role_permission WHERE role_id = ?", String.class, roleId)));
        return new Stored(role.id(), role.name(), role.owner(), role.seededFrom(), keys);
    }

    private Member lockMember(long memberId) {
        List<Member> found = jdbc.query("""
                SELECT coalesce(display_name, login) AS name, role FROM tenant_member
                 WHERE id = ? FOR UPDATE""",
                (rs, i) -> new Member(rs.getString("name"), rs.getString("role")), memberId);
        if (found.isEmpty()) {
            throw NotFound.MEMBER.error(memberId);
        }
        return found.get(0);
    }

    private RoleView role(long roleId) {
        return roles().stream().filter(view -> view.id() == roleId).findFirst().orElseThrow();
    }

    private void writeSet(long roleId, Set<String> keys) {
        for (String key : keys) {
            jdbc.update("INSERT INTO access_role_permission (role_id, permission) VALUES (?, ?)",
                    roleId, key);
        }
    }

    private Map<Long, Map<String, Boolean>> overrides() {
        Map<Long, Map<String, Boolean>> found = new LinkedHashMap<>();
        jdbc.query("SELECT member_id, permission, granted FROM member_permission_override "
                        + "ORDER BY permission",
                rs -> {
                    found.computeIfAbsent(rs.getLong("member_id"), id -> new LinkedHashMap<>())
                            .put(rs.getString("permission"), rs.getBoolean("granted"));
                });
        return found;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название роли обязательно");
        }
        return name.strip();
    }

    private static Set<String> requireKnown(Collection<String> keys) {
        Set<String> known = new TreeSet<>();
        for (String key : keys == null ? List.<String>of() : keys) {
            if (Permission.byKey(key).isEmpty()) {
                throw new IllegalArgumentException(
                        "Такого права нет: «%s». Обновите страницу — список прав устарел"
                                .formatted(key));
            }
            known.add(key);
        }
        return known;
    }

    private static IllegalStateException nameTaken(String title) {
        return new IllegalStateException(
                "Роль «%s» уже есть: два одинаковых названия в списке не различить".formatted(title));
    }

    private static String employees(int count) {
        int tens = count % 100;
        int ones = count % 10;
        String word = tens >= 11 && tens <= 14 ? "сотрудникам"
                : ones == 1 ? "сотруднику" : "сотрудникам";
        return count + " " + word;
    }

    /** Снимок роли для журнала: названия прав словами экрана, а не ключами. */
    private static Map<String, Object> snapshot(String name, Set<String> keys) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", name);
        snapshot.put("permissions", titles(keys));
        return snapshot;
    }

    private static Map<String, Object> memberSnapshot(String name, String role,
                                                      Map<String, Boolean> overrides) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("display_name", name);
        snapshot.put("access_role", role);
        snapshot.put("permissions_added", titles(keysWhere(overrides, true)));
        snapshot.put("permissions_removed", titles(keysWhere(overrides, false)));
        return snapshot;
    }

    private static String titles(Collection<String> keys) {
        String joined = keys.stream().sorted()
                .map(key -> Permission.byKey(key).map(Permission::title).orElse(key))
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? "—" : joined;
    }

    private static List<String> keysWhere(Map<String, Boolean> overrides, boolean granted) {
        return overrides.entrySet().stream().filter(entry -> entry.getValue() == granted)
                .map(Map.Entry::getKey).sorted().toList();
    }

    private static List<String> known(Collection<String> keys) {
        return keys.stream().filter(key -> Permission.byKey(key).isPresent()).sorted().toList();
    }

    private static List<String> allKeys() {
        return Arrays.stream(Permission.values()).map(Permission::key).sorted().toList();
    }

    /** Действие справочника: ключ, слово экрана, что под ним мелко. */
    public record Action(String key, String title, String hint) {
    }

    public record Section(String title, List<Action> permissions) {
    }

    /**
     * @param permissions набор роли ключами; у владельца пусто — у него всё
     * @param members     скольким сотрудникам назначена (с входом)
     * @param editLocked  почему вошедшему нельзя менять набор роли (она назначена
     *                    сотрудникам шире его); {@code null} — можно
     */
    public record RoleView(long id, String name, boolean owner, List<String> permissions,
                           int members, String editLocked) {
    }

    /**
     * @param roleId    назначенная роль; {@code null} — роли нет
     * @param added     права, добавленные поверх роли
     * @param removed   права, снятые поверх роли
     * @param effective итог: что сотрудник может (у владельца — весь справочник)
     */
    public record MemberAccessView(long memberId, Long roleId, String roleName, boolean owner,
                                   List<String> added, List<String> removed,
                                   List<String> effective) {
    }

    private record Stored(long id, String name, boolean owner, String seededFrom,
                          Set<String> permissions) {
    }

    private record Member(String name, String legacyRole) {
    }
}
