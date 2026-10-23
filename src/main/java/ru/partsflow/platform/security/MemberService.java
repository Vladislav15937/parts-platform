package ru.partsflow.platform.security;

import ru.partsflow.shared.NotFound;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.session.LoginSessions;
import ru.partsflow.platform.session.SessionRevocations;
import ru.partsflow.platform.tenant.TenantContext;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Сотрудники арендатора: создание, пароли, отключение.
 *
 * <p>Работает через {@code JdbcTemplate}, а не через сущность: таблица
 * административная, из шести полей, и второй способ доступа к ней уже есть —
 * {@link MemberAuthenticationProvider} читает её сырым SQL, потому что вход
 * происходит до маршрутизации арендатора. Заводить сущность ради одного сервиса
 * значит получить три пути к одной таблице вместо двух.
 */
@Service
public class MemberService {

    /**
     * Восемь символов — не про стойкость к подбору, а про то, что короче люди
     * ставят «12345». Стойкость даёт BCrypt и отсутствие публичного перебора.
     */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * Роли, которые владелец может выдать сотруднику.
     *
     * <p>{@code AUDITOR} — «Ревизор», читатель журнала действий: решение
     * владельца продукта от 8 сентября 2026 (tasks/0043) о том, что доступ
     * к журналу даёт роль, а не именное разрешение. Ничего не меняет —
     * изменяющие методы в {@code SecurityConfig} перечисляют разрешённые
     * роли, а не запрещённую, и роль, не внесённая в тот список, работает
     * как «Просмотр».
     */
    private static final Set<String> ROLES =
            Set.of("OWNER", "MANAGER", "STOREKEEPER", "SELLER", "VIEWER", "AUDITOR");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final LoginSessions sessions;
    private final SessionRevocations revocations;

    /**
     * Кто завёл сотрудника, сменил ему пароль, выключил — до задачи 0325
     * в журнал не попадало ничего: сотрудник правится прямым SQL. Пароль
     * пишется фактом замены, без значения и без хэша.
     */
    private final ru.partsflow.platform.audit.AuditJournal journal;

    public MemberService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                         LoginSessions sessions, SessionRevocations revocations,
                         ru.partsflow.platform.audit.AuditJournal journal) {
        this.journal = journal;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.revocations = revocations;
    }

    @Transactional
    public Member create(String login, String password, String displayName, String role,
                         Long branchId) {
        validate(login, password, role);
        // Филиал обязан существовать, и сказать об этом надо словами: роль
        // проверяется белым списком и отбивается внятно, а чужой филиал
        // доезжал до внешнего ключа и возвращался как «Операция нарушает
        // целостность данных». Пусто законно: филиал у сотрудника
        // необязателен.
        if (branchId != null) {
            Integer branch = jdbc.queryForObject(
                    "SELECT count(*) FROM branch WHERE id = ?", Integer.class, branchId);
            if (branch == null || branch == 0) {
                throw NotFound.BRANCH.error(branchId);
            }
        }

        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash, branch_id)
                    VALUES (?, ?, ?, ?, ?) RETURNING id""",
                    Long.class,
                    displayName == null || displayName.isBlank() ? login.strip() : displayName.strip(),
                    role, login.strip(), passwordEncoder.encode(password), branchId);

            java.util.Map<String, Object> now = new java.util.LinkedHashMap<>();
            now.put("display_name", displayName == null || displayName.isBlank()
                    ? login.strip() : displayName.strip());
            now.put("login", login.strip());
            now.put("role", role);
            journal.created("tenant_member", id, now);
            // Ссылка на роль переноса той же прежней роли (задача 0044): иначе
            // заведённый здесь сотрудник жил бы на паре role → seeded_from,
            // и удаление клиентом этой роли молча оставило бы его без прав.
            // Роли нет (клиент её удалил) — ссылки нет, как у прежнего образа.
            jdbc.update("""
                    UPDATE tenant_member SET access_role_id =
                           (SELECT r.id FROM access_role r WHERE r.seeded_from = ?)
                     WHERE id = ?""", role, id);
            return byId(id);
        } catch (DuplicateKeyException e) {
            // Уникальность по нормализованному логину стоит в БД: «Ivan» и «ivan »
            // это один человек, и второй такой должен получить отказ.
            throw new IllegalArgumentException("Сотрудник с логином «%s» уже есть".formatted(login));
        }
    }

    /**
     * Сотрудники, у которых есть вход, — и администратор сервиса.
     *
     * <p>Администратор логина в схеме арендатора не имеет (вход у него свой,
     * в общей схеме ячейки), и без второго условия он пропадал бы из списка.
     * А решение владельца продукта от 8 сентября 2026 (tasks/0042, критерий 1)
     * требует обратного: «в организации он виден отдельным участником» —
     * владелец обязан видеть, что такой участник у него есть.
     */
    @Transactional(readOnly = true)
    public List<Member> all() {
        return jdbc.query("""
                SELECT id, number, login, display_name, role, is_active, last_login_at
                  FROM tenant_member
                 WHERE login IS NOT NULL OR role = 'ADMIN'
                 ORDER BY id""", MemberService::map);
    }

    /**
     * Меняет пароль.
     *
     * <p>Старый пароль не спрашивается: менять может владелец (тогда старого
     * он не знает) либо сам сотрудник в уже подтверждённой сессии. Требовать
     * старый имеет смысл против угона сессии, но это отдельный разговор
     * и отдельный эндпоинт «сменить свой пароль».
     *
     * <p><b>Смена пароля закрывает сессии, и это главный её смысл.</b>
     * «Смена пароля — всегда, это главный сценарий „меня взломали“»
     * (docs/sessions.md, §6; ответы владельца продукта от 9 сентября 2026).
     * Пока сессии оставались живыми, смена пароля не выгоняла угонщика вовсе:
     * он продолжал работать под старой cookie до конца дня, а владелец считал,
     * что доступ закрыт.
     *
     * @param actingSessionKey сессия, из которой меняют пароль: её оставляем
     *                         работать. Наказывать выходом того, кто как раз
     *                         всё сделал правильно, — верный способ отучить
     *                         менять пароли
     */
    @Transactional
    public void changePassword(Long memberId, String newPassword, String actingSessionKey) {
        requireNotServiceAdmin(memberId);
        requirePassword(newPassword);

        int updated = jdbc.update("""
                UPDATE tenant_member SET password_hash = ?
                 WHERE id = ? AND login IS NOT NULL""",
                passwordEncoder.encode(newPassword), memberId);

        if (updated == 0) {
            throw NotFound.MEMBER.error(memberId);
        }
        // Фактом, а не значением: журнал открыт ревизору, и даже хэш пароля
        // в нём лишний — по нему пароль подбирают офлайн.
        journal.changed("tenant_member", memberId,
                java.util.Map.of("display_name", nameOf(memberId), "password", "задан"),
                java.util.Map.of("display_name", nameOf(memberId), "password", "заменён"));
        revokeSessions(memberId, "смена пароля", actingSessionKey);
    }

    /**
     * Включает и отключает сотрудника.
     *
     * <p>Удаления нет намеренно: сотрудник стоит в {@code created_by} у сделок,
     * движений и документов. Удалить его значит либо порвать историю, либо
     * оставить в ней пустоту вместо имени — а по этой истории считают зарплаты.
     */
    @Transactional
    public void setActive(Long memberId, boolean active) {
        requireNotServiceAdmin(memberId);
        if (!active) {
            // Первой блокировкой в транзакции: строки владельцев берутся раньше
            // строки сотрудника — тем же порядком, что у назначения роли
            // (AccessRoleService.assign), иначе два встречных запроса взяли бы
            // одни строки в обратном порядке и упёрлись бы друг в друга.
            requireOwnerRemains(memberId);
        }
        java.util.List<Boolean> was = jdbc.queryForList(
                "SELECT is_active FROM tenant_member WHERE id = ? FOR UPDATE", Boolean.class, memberId);
        int updated = jdbc.update("UPDATE tenant_member SET is_active = ? WHERE id = ?",
                active, memberId);
        if (updated == 0) {
            throw NotFound.MEMBER.error(memberId);
        }
        if (!was.isEmpty() && !Boolean.valueOf(active).equals(was.get(0))) {
            journal.changed("tenant_member", memberId,
                    java.util.Map.of("display_name", nameOf(memberId), "is_active", !active),
                    java.util.Map.of("display_name", nameOf(memberId), "is_active", active));
        }
        if (!active) {
            // Выключенный сотрудник до этого дня доработывал смену: роль
            // и признак «работает» лежат в сессии снимком, и выключение
            // учётной записи их не касалось — оно закрывало только следующий
            // вход. Уволенный уходил с открытым кабинетом.
            revokeSessions(memberId, "сотрудник выключен", null);
        }
    }

    /**
     * Закрыть сессии сотрудника: в журнале и на самом деле.
     *
     * <p>Двумя действиями, и оба нужны. Запись в журнале отвечает на вопрос
     * «почему его вышибло» через неделю; удаление сессии из общего хранилища
     * закрывает доступ на ближайшем же запросе — сессия наблюдаема только
     * тогда, когда с ней приходят, и раньше этого её не закрыть ничем.
     *
     * <p><b>Порядок такой: сначала журнал, потом сама сессия — и откатывается
     * из этих двух только первое.</b> Журнал идёт в транзакции и уедет вместе
     * с ней; удаление сессии живёт своей транзакцией и отката не знает.
     * Направление отказа выбрано намеренно: не прошедшая смена пароля,
     * выкинувшая человека, — это лишний вход, а обратное — доступ, который
     * владелец считает закрытым.
     */
    private void revokeSessions(Long memberId, String detail, String actingSessionKey) {
        String schema = TenantContext.require();
        sessions.revoke(schema, memberId, detail, actingSessionKey);
        revocations.revoke(schema, memberId, actingSessionKey);
    }

    /** Отказ «последнего владельца» — одними словами на все пути. */
    static final String LAST_OWNER =
            "Это последний владелец, а в компании должен остаться хотя бы один владелец. "
                    + "Сначала назначьте владельцем другого сотрудника";

    /**
     * Владельцев всегда не меньше одного — <b>одна проверка на все пути</b>,
     * которые могут отнять у сотрудника владельца: выключение
     * ({@link #setActive}) и смена роли, то есть и снятие роли «Владелец»
     * ({@code AccessRoleService.assign}). Удаления сотрудника в системе нет
     * вовсе. Решение владельца продукта 24.10.2026 (tasks/0044): «владелец
     * есть всегда, как минимум 1».
     *
     * <p>Иначе компания запирается снаружи: сотрудников, выгрузки, ключи
     * кабинетов видит только владелец, а вернуть владельца может тоже только
     * владелец — остаётся провижининг, то есть разработчик.
     *
     * <p><b>Проверка и изменение не разъезжаются — строки владельцев
     * блокируются до счёта.</b> «Прочитали — проверили — записали» пропускает
     * два встречных запроса двух последних владельцев («выключить другого»):
     * каждый видит второго действующим, оба проходят, владельцев ноль.
     * Поэтому сначала {@code FOR UPDATE} по <b>всем</b> строкам владельцев
     * (действующим и нет — набор не зависит от того, что меняет сосед),
     * в одном порядке у всех вызывающих, и только потом — счёт отдельной
     * инструкцией: в READ COMMITTED она видит то, что успел записать тот,
     * кого мы ждали на блокировке. Проигравший получает 409 словами.
     * Пока строки заперты, ни один из оставшихся владельцев не может
     * перестать им быть — даже если сотрудник, которого мы меняем, сам
     * владельцем станет в эту же секунду.
     *
     * <p>Зовётся <b>первой блокировкой</b> транзакции и только внутри неё
     * ({@code MANDATORY}): замок в своей короткой транзакции снимался бы
     * раньше записи и ничего бы не держал.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireOwnerRemains(long memberId) {
        jdbc.queryForList("SELECT m.id FROM tenant_member m WHERE " + OWNER_SQL
                + " ORDER BY m.id FOR UPDATE OF m", Long.class);
        if (!isOwner(memberId)) {
            return;
        }
        Integer others = jdbc.queryForObject("""
                SELECT count(*) FROM tenant_member m
                 WHERE m.is_active AND m.login IS NOT NULL AND m.id <> ?""" + " AND " + OWNER_SQL,
                Integer.class, memberId);
        if (others == null || others == 0) {
            throw new IllegalStateException(LAST_OWNER);
        }
    }

    /**
     * Владелец ли сотрудник — по роли клиента (задача 0044), как его видит
     * вход ({@link MemberAccess}): назначенная роль «Владелец» (ссылкой или
     * парой {@code role → seeded_from}), а если роли нет вовсе — прежняя
     * колонка {@code OWNER}. Одно выражение на «последнего владельца»
     * и на запрет менять владельца не владельцем: прежде оно смотрело только
     * на колонку и не видело владельца, назначенного ссылкой.
     */
    @Transactional(readOnly = true)
    public boolean isOwner(Long memberId) {
        List<Boolean> found = jdbc.queryForList(
                "SELECT " + OWNER_SQL + " FROM tenant_member m WHERE m.id = ?",
                Boolean.class, memberId);
        return !found.isEmpty() && Boolean.TRUE.equals(found.get(0));
    }

    /** Владелец ли строка {@code m} — то же правило, что у входа ({@link MemberAccess}). */
    static final String OWNER_SQL = """
            coalesce((SELECT r.is_owner FROM access_role r
                       WHERE r.id = coalesce(m.access_role_id,
                             (SELECT s.id FROM access_role s
                               WHERE s.seeded_from = m.role AND m.role <> 'ADMIN'))),
                     m.role = 'OWNER')""";

    /**
     * Участника «администратор сервиса» владелец не меняет.
     *
     * <p>Решение владельца продукта от 8 сентября 2026 (tasks/0042): роль
     * «существует вне зависимости ни от чего», владелец её «не заводит и не
     * может отобрать». Рычаг у владельца другой и сильнее — разрешение
     * на вход, которое он даёт и отзывает сам; выключенный же участник
     * ничего бы не закрыл (вход у администратора не через эту строку),
     * а только выглядел бы закрытым доступом. Пароля у этой строки нет вовсе.
     */
    private void requireNotServiceAdmin(Long memberId) {
        String role = jdbc.query("SELECT role FROM tenant_member WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, memberId);
        if (TenantPrincipal.SERVICE_ADMIN_ROLE.equals(role)) {
            throw new IllegalStateException(
                    "Администратора сервиса владелец не выключает и пароль ему не меняет: "
                            + "его вход закрывается кнопкой «Отозвать доступ» в разделе "
                            + "«Доступ администратора сервиса»");
        }
    }

    /** Есть ли у арендатора хоть один сотрудник с доступом. */
    @Transactional(readOnly = true)
    public boolean hasAnyWithCredentials() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tenant_member WHERE login IS NOT NULL", Integer.class);
        return count != null && count > 0;
    }

    /**
     * Создаёт первого владельца, если у арендатора нет ни одной учётной записи.
     *
     * <p>Проверка и вставка — одна инструкция: раздельные «посмотреть, нет ли»
     * и «вставить» пропустят второй одновременный запрос, и у компании окажется
     * два владельца от разных людей. Тот же приём, что в {@code reserve_stock}.
     *
     * @return пусто, если учётные записи уже есть
     */
    @Transactional
    public Optional<Member> createFirstOwnerIfEmpty(String login, String password,
                                                    String displayName) {
        validate(login, password, "OWNER");

        List<Long> created = jdbc.queryForList("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                SELECT ?, 'OWNER', ?, ?
                 WHERE NOT EXISTS (SELECT 1 FROM tenant_member WHERE login IS NOT NULL)
                RETURNING id""",
                Long.class,
                displayName == null || displayName.isBlank() ? login.strip() : displayName.strip(),
                login.strip(), passwordEncoder.encode(password));

        return created.isEmpty() ? Optional.empty() : Optional.of(byId(created.get(0)));
    }

    /**
     * Имена сотрудников по идентификаторам — одним запросом на всю выдачу.
     *
     * <p>История документа — это десятки строк, и запрос на каждую превратил
     * бы её открытие в столько же обращений к базе. Без имени же строка
     * читается как «автор 3»: разбирают историю через недели, когда
     * по номеру никто никого не вспомнит.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> namesOf(Collection<Long> memberIds) {
        List<Long> ids = memberIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new java.util.HashMap<>();
        jdbc.query("SELECT id, display_name FROM tenant_member WHERE id = ANY (?)",
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("display_name"));
                },
                (Object) ids.toArray(Long[]::new));
        return names;
    }

    public Member byId(Long memberId) {
        List<Member> found = jdbc.query("""
                SELECT id, number, login, display_name, role, is_active, last_login_at
                  FROM tenant_member WHERE id = ?""", MemberService::map, memberId);

        if (found.isEmpty()) {
            throw NotFound.MEMBER.error(memberId);
        }
        return found.get(0);
    }

    private void validate(String login, String password, String role) {
        if (login == null || login.isBlank()) {
            throw new IllegalArgumentException("Логин обязателен");
        }
        // Пустую роль отбиваем до белого списка: ROLES — это Set.of, а он
        // на null-ключе бросает NPE, то есть сообщение ниже при незаполненной
        // роли не появлялось никогда. Снаружи это прикрывал @NotBlank
        // в MemberController — защита в другом файле, и на путь
        // провижининга она не распространяется.
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("Роль сотрудника обязательна");
        }
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException(
                    "Неизвестная роль «%s», допустимы: %s".formatted(role, ROLES));
        }
        requirePassword(password);
    }

    private void requirePassword(String password) {
        if (password == null || password.strip().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Пароль короче " + MIN_PASSWORD_LENGTH + " символов");
        }
    }

    private static Member map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Member(
                rs.getLong("id"),
                rs.getLong("number"),
                rs.getString("login"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getBoolean("is_active"),
                rs.getTimestamp("last_login_at") == null
                        ? null : rs.getTimestamp("last_login_at").toInstant());
    }

    /**
     * Сотрудник без пароля: наружу хеш не отдаётся никогда.
     *
     * @param number видимый номер сотрудника (задача 0373, {@code tenant/097}):
     *               порядковый внутри компании и неизменный, его показывают
     *               человеку рядом с логином и именем — в отличие от {@code id},
     *               который остаётся внутренним. Присваивает его умолчание
     *               колонки при заведении, код его не пишет и не правит
     */
    public record Member(Long id, long number, String login, String displayName, String role,
                         boolean active, Instant lastLoginAt) {
    }

    /** Имя в снимке журнала: строку о выключенном сотруднике узнают по нему. */
    private String nameOf(Long memberId) {
        String name = jdbc.queryForObject(
                "SELECT coalesce(display_name, login) FROM tenant_member WHERE id = ?",
                String.class, memberId);
        return name == null ? "" : name;
    }

}
