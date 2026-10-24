--liquibase formatted sql

--changeset partsflow:tenant-103-members-manage-split
--comment Право «Управлять сотрудниками и ролями» делится на четыре: учётные записи, назначение ролей, правка ролей, права поверх роли (задача 0044)

-- Ответ владельца 24.10.2026 (tasks/0044, «Ответ владельца — 24 октября 2026
-- (развёрнутые вопросы)», пункт 2а): members.manage делится на четыре права,
-- правило «не шире своих» действует на каждое, а у кого право было — тот
-- получает все четыре, доступ никто не теряет.
--
-- КЛЮЧИ — КОНТРАКТ С КОДОМ (Permission.java), справочника в схеме нет
-- (tenant/100):
--   members.accounts        — заводить, выключать сотрудников и менять им пароль;
--   members.assign_roles    — назначать сотрудникам готовые роли;
--   members.roles           — заводить и править роли;
--   members.grant_overrides — выдавать отдельные права поверх роли.
-- Все четыре проходят access_role_permission_key_ck
-- и member_permission_override_key_ck: строчные слова через точку.
--
-- ЭТО EXPAND. members.manage не снимается ни у роли, ни у сотрудника: его
-- читает прежний образ (@PreAuthorize в MemberController и AccessRoleController
-- на main), и откат приложения после этого наката законен (0112). Снять
-- старый ключ — отдельным changeset'ом после релиза, где код его больше
-- не читает и не пишет (N = 1, db/CLAUDE.md).
--
-- ПОВТОРНЫЙ НАКАТ НЕ ВОСКРЕШАЕТ СНЯТОГО. Четвёрка выдаётся только той роли
-- (и тому сотруднику), у кого members.manage есть, а ни одного из четырёх
-- ключей ещё нет — то есть деление здесь ещё не проходило. Если клиент
-- после наката снял у роли одно из четырёх, а members.manage на ней остался
-- (новый код старый ключ может не трогать), повтор этого changeset'а
-- снятое не вернёт: у роли стоят остальные три. Тот же приём, что
-- access_role_id IS NULL в tenant/101 (разбор PR 502). Путь «откат и накат
-- обратно» держит откат ниже: он оставляет members.manage только там, где
-- стоят все четыре.

-- Набор роли. «Владелец» строк не имеет (все права всегда), его это
-- не касается.
INSERT INTO ${tenant.schema}.access_role_permission (role_id, permission)
SELECT p.role_id, k.permission
  FROM ${tenant.schema}.access_role_permission p
 CROSS JOIN (VALUES ('members.accounts'), ('members.assign_roles'),
                    ('members.roles'), ('members.grant_overrides')) AS k(permission)
 WHERE p.permission = 'members.manage'
   AND NOT EXISTS (
       SELECT 1 FROM ${tenant.schema}.access_role_permission x
        WHERE x.role_id = p.role_id
          AND x.permission IN ('members.accounts', 'members.assign_roles',
                               'members.roles', 'members.grant_overrides'))
ON CONFLICT (role_id, permission) DO NOTHING;

-- Права поверх роли: добавленное (granted = true) становится четырьмя
-- добавленными, снятое (false) — четырьмя снятыми. Кто и когда решил —
-- переносится с исходной строки: это то же решение того же человека,
-- а не новое, и день наката здесь был бы неправдой.
INSERT INTO ${tenant.schema}.member_permission_override (member_id, permission, granted, set_at, set_by)
SELECT o.member_id, k.permission, o.granted, o.set_at, o.set_by
  FROM ${tenant.schema}.member_permission_override o
 CROSS JOIN (VALUES ('members.accounts'), ('members.assign_roles'),
                    ('members.roles'), ('members.grant_overrides')) AS k(permission)
 WHERE o.permission = 'members.manage'
   AND NOT EXISTS (
       SELECT 1 FROM ${tenant.schema}.member_permission_override x
        WHERE x.member_id = o.member_id
          AND x.permission IN ('members.accounts', 'members.assign_roles',
                               'members.roles', 'members.grant_overrides'))
ON CONFLICT (member_id, permission) DO NOTHING;

-- Откат возвращает одно право вместо четырёх и сводит их ВНИЗ: прежний образ
-- знает только members.manage, а он означает все четыре сразу, поэтому
-- там, где после наката клиент дал или оставил не все четыре, старое право
-- снимается, а не выдаётся. Порядок: роли (members.manage остаётся только
-- у роли со всеми четырьмя и ставится ей, если его там нет), затем
-- сотрудники (поверх роли: все четыре добавлены — добавлено, хоть одно
-- снято — снято, иначе строки нет и решает роль), затем снимаются четыре
-- ключа. Итог прежнего образа поэтому никогда не шире итога нового,
-- и повторный накат выводит четвёрку ровно из того, что осталось.
--rollback-теряет деление права «Управлять сотрудниками и ролями» на четыре: роль, собранная не из всех четырёх, теряет управление сотрудниками целиком; сотрудник, которому поверх роли добавлены не все четыре, теряет добавленное, а у кого снято хоть одно — снимается всё право; вернуть частичную раздачу можно только раздав права заново после повторного наката
--rollback INSERT INTO ${tenant.schema}.access_role_permission (role_id, permission) SELECT role_id, 'members.manage' FROM ${tenant.schema}.access_role_permission WHERE permission IN ('members.accounts', 'members.assign_roles', 'members.roles', 'members.grant_overrides') GROUP BY role_id HAVING count(*) = 4 ON CONFLICT (role_id, permission) DO NOTHING;
--rollback DELETE FROM ${tenant.schema}.access_role_permission p WHERE p.permission = 'members.manage' AND (SELECT count(*) FROM ${tenant.schema}.access_role_permission x WHERE x.role_id = p.role_id AND x.permission IN ('members.accounts', 'members.assign_roles', 'members.roles', 'members.grant_overrides')) < 4;
--rollback INSERT INTO ${tenant.schema}.member_permission_override (member_id, permission, granted, set_at, set_by) SELECT member_id, 'members.manage', bool_and(granted), max(set_at), (array_agg(set_by ORDER BY set_at DESC))[1] FROM ${tenant.schema}.member_permission_override WHERE permission IN ('members.accounts', 'members.assign_roles', 'members.roles', 'members.grant_overrides') GROUP BY member_id HAVING NOT bool_and(granted) OR count(*) = 4 ON CONFLICT (member_id, permission) DO UPDATE SET granted = EXCLUDED.granted, set_at = EXCLUDED.set_at, set_by = EXCLUDED.set_by;
--rollback DELETE FROM ${tenant.schema}.member_permission_override o WHERE o.permission = 'members.manage' AND NOT EXISTS (SELECT 1 FROM ${tenant.schema}.member_permission_override x WHERE x.member_id = o.member_id AND x.permission IN ('members.accounts', 'members.assign_roles', 'members.roles', 'members.grant_overrides') GROUP BY x.member_id HAVING NOT bool_and(x.granted) OR count(*) = 4);
--rollback DELETE FROM ${tenant.schema}.access_role_permission WHERE permission IN ('members.accounts', 'members.assign_roles', 'members.roles', 'members.grant_overrides');
--rollback DELETE FROM ${tenant.schema}.member_permission_override WHERE permission IN ('members.accounts', 'members.assign_roles', 'members.roles', 'members.grant_overrides');
