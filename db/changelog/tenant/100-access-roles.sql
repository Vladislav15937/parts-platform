--liquibase formatted sql

--changeset partsflow:tenant-100-access-roles
--comment Роли, которые клиент заводит сам: название и набор прав по действиям; права поверх роли у сотрудника (задача 0044)

-- Ответы владельца 23.10.2026 (tasks/0044, три раздела «Ответ владельца —
-- 23 октября 2026»):
--  - права режутся по действиям и выдаются каждому сотруднику отдельно;
--    у владельца — все и всегда;
--  - встроенная роль одна — «Владелец»; остальные роли клиент заводит сам:
--    пишет название, собирает набор прав, сохраняет и назначает одному
--    или нескольким сотрудникам;
--  - связь живая: правка набора роли меняет права у всех, кому она назначена;
--  - поверх роли сотруднику можно добавить или снять отдельное право:
--    итог — права роли плюс добавленные минус снятые.
--
-- ПОЭТОМУ РОЛЬ — ССЫЛКА, А НЕ КОПИЯ. Сотрудник ссылается на роль
-- (tenant_member.access_role_id), набор лежит у роли (access_role_permission),
-- и правка набора видна всем назначенным без единой записи у них.
-- Копия набора в сотрудника при назначении — это «заготовка», от которой
-- владелец отказался.
--
-- ПРАВО — СТРОКА-КЛЮЧ, СПРАВОЧНИК ДЕЙСТВИЙ ЖИВЁТ В КОДЕ. Перечень действий
-- (что значит «deal.return», каким словом оно показано владельцу, какие пути
-- оно открывает) — это код, а не данные клиента: таблица-справочник в схеме
-- была бы второй копией перечня рядом с первой, и разошлись бы они молча.
-- Схема держит только форму ключа (CHECK ниже): строчные латинские слова
-- через точку. Неизвестный коду ключ в строке — забытое право, а не дыра:
-- код спрашивает «есть ли у сотрудника вот этот ключ», и чужой ключ никому
-- ничего не открывает.
--
-- «ВЛАДЕЛЕЦ» — СТРОКА РОЛИ С ПРИЗНАКОМ, А НЕ ЕЩЁ ОДИН МЕХАНИЗМ. Встроенная
-- роль одна, и у неё нет набора: «все права всегда» означает, что код
-- не спрашивает её строки в access_role_permission вовсе. Строкой она
-- нужна затем, чтобы владельца назначали тем же способом, что и любую роль
-- (access_role_id), — иначе «кто владелец» читалось бы из двух мест.
-- Что её нельзя переименовать, удалить, дать ей набор и что последнего
-- владельца нельзя снять — держит код (это запреты вида «эту строку нельзя
-- менять», им в схеме не место); схема держит только то, что владелец-роль
-- одна и что она и есть заведённая из OWNER (access_role_owner_ck вместе
-- с уникальностью seeded_from).
--
-- ЭТО EXPAND. Прежний образ новых таблиц не знает, колонку access_role_id
-- не пишет (она допускает NULL) и решает права по tenant_member.role, как
-- раньше. Колонка role не трогается: её читает прежний образ, и ей же
-- подписан снимок роли автора в audit_log.changed_by_role (tenant/062).
-- Снять её можно будет только сужением после релиза, где код её больше
-- не читает (N = 1, db/CLAUDE.md).
CREATE TABLE ${tenant.schema}.access_role (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text        NOT NULL,
    is_owner    boolean     NOT NULL DEFAULT false,
    -- Из какой прежней жёсткой роли заведена эта (tenant/101), NULL — заведена
    -- клиентом. Нужна не для истории: строку tenant_member с пустой
    -- access_role_id заводит прежний образ (откат приложения законен, 0112),
    -- и новый код находит её роль по паре role → seeded_from. Владелец
    -- роль может переименовать и пересобрать — происхождение остаётся.
    seeded_from text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- Кто завёл роль. NULL — заведена переносом при выкладке (tenant/101).
    created_by  bigint      REFERENCES ${tenant.schema}.tenant_member,
    CONSTRAINT access_role_name_ck CHECK (name ~ '\S'),
    CONSTRAINT access_role_seeded_from_ck CHECK (seeded_from IN
        ('OWNER','MANAGER','STOREKEEPER','SELLER','VIEWER','AUDITOR')),
    -- Владелец-роль — ровно та, что заведена из OWNER, и только она.
    CONSTRAINT access_role_owner_ck CHECK (is_owner = (seeded_from IS NOT DISTINCT FROM 'OWNER'))
);

-- Две роли «Кладовщик» и «кладовщик » владелец на экране не различит:
-- уникальность без учёта регистра и пробелов по краям, как у part_name
-- и text_block (tenant/095).
CREATE UNIQUE INDEX access_role_name_uk
    ON ${tenant.schema}.access_role (lower(btrim(name)));
-- Одна роль на прежнюю жёсткую. Вместе с access_role_owner_ck это же даёт
-- «владелец-роль одна»: is_owner бывает только у seeded_from = 'OWNER',
-- а такая строка одна. Отдельный индекс по is_owner повторял бы это вторым
-- объектом (снят по разбору PR 502).
CREATE UNIQUE INDEX access_role_seeded_from_uk
    ON ${tenant.schema}.access_role (seeded_from) WHERE seeded_from IS NOT NULL;

COMMENT ON TABLE ${tenant.schema}.access_role IS
    'Роль сотрудника: название и набор прав (access_role_permission). Встроенная одна — «Владелец» (is_owner), у неё набора нет: все права всегда. Остальные заводит клиент. Задача 0044';
COMMENT ON COLUMN ${tenant.schema}.access_role.seeded_from IS
    'Прежняя жёсткая роль, из которой эта заведена при выкладке 0044; NULL — заведена клиентом. По ней находят роль строки tenant_member, заведённой прежним образом без access_role_id';

-- Набор прав роли. Удаление роли уносит её набор (CASCADE): набор
-- без роли не значит ничего. Ключ — код действия из справочника в коде.
CREATE TABLE ${tenant.schema}.access_role_permission (
    role_id    bigint NOT NULL REFERENCES ${tenant.schema}.access_role ON DELETE CASCADE,
    permission text   NOT NULL,
    CONSTRAINT access_role_permission_pk PRIMARY KEY (role_id, permission),
    CONSTRAINT access_role_permission_key_ck
        CHECK (permission ~ '^[a-z][a-z_]*(\.[a-z][a-z_]*)+$')
);

COMMENT ON TABLE ${tenant.schema}.access_role_permission IS
    'Набор прав роли: код действия строкой, справочник действий — в коде. Правка набора меняет права у всех, кому роль назначена (живая связь). Задача 0044';

-- Права поверх роли у одного сотрудника: granted = true — добавлено,
-- false — снято. Итог: права роли плюс добавленные минус снятые.
-- Одна строка на пару «сотрудник — право»: добавить и снять одно и то же
-- разом нельзя, и это держит первичный ключ, а не проверка чтением.
-- Ссылка на сотрудника без ON DELETE: сотрудники не удаляются
-- (MemberService.setActive).
CREATE TABLE ${tenant.schema}.member_permission_override (
    member_id  bigint      NOT NULL REFERENCES ${tenant.schema}.tenant_member,
    permission text        NOT NULL,
    granted    boolean     NOT NULL,
    set_at     timestamptz NOT NULL DEFAULT now(),
    set_by     bigint      REFERENCES ${tenant.schema}.tenant_member,
    CONSTRAINT member_permission_override_pk PRIMARY KEY (member_id, permission),
    CONSTRAINT member_permission_override_key_ck
        CHECK (permission ~ '^[a-z][a-z_]*(\.[a-z][a-z_]*)+$')
);

COMMENT ON TABLE ${tenant.schema}.member_permission_override IS
    'Отдельные права сотрудника поверх роли: granted — добавлено, иначе снято. Итог — права роли плюс добавленные минус снятые; у владельца не действует (все права всегда). Задача 0044';

-- Ссылка без ON DELETE: удалить роль, назначенную сотрудникам, база не даст,
-- а что делать с назначенными (снять роль, назначить другую) — решает код
-- и человек, как у text_block (tenant/095). Тихий SET NULL оставил бы
-- сотрудника без прав, ничего ему не сказав.
ALTER TABLE ${tenant.schema}.tenant_member
    ADD COLUMN access_role_id bigint REFERENCES ${tenant.schema}.access_role;

-- «Кому назначена роль» спрашивают при каждой правке набора и при удалении
-- роли — по ссылке нужен индекс, иначе каждое удаление роли проходит
-- tenant_member целиком.
CREATE INDEX tenant_member_access_role_idx
    ON ${tenant.schema}.tenant_member (access_role_id) WHERE access_role_id IS NOT NULL;

COMMENT ON COLUMN ${tenant.schema}.tenant_member.access_role_id IS
    'Назначенная роль (задача 0044). NULL — строка администратора сервиса либо заведена прежним образом: роль тогда находят по role → access_role.seeded_from. Колонку role читает прежний образ и снимок роли в журнале';

--rollback-теряет роли, заведённые клиентом, их наборы прав, назначения ролей сотрудникам и отдельные права поверх роли: сотрудники остаются с прежней жёсткой ролью из tenant_member.role, и права снова решает она
--rollback DROP INDEX ${tenant.schema}.tenant_member_access_role_idx;
--rollback ALTER TABLE ${tenant.schema}.tenant_member DROP COLUMN access_role_id;
--rollback DROP TABLE ${tenant.schema}.member_permission_override;
--rollback DROP TABLE ${tenant.schema}.access_role_permission;
--rollback DROP TABLE ${tenant.schema}.access_role;
