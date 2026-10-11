--liquibase formatted sql

--changeset partsflow:tenant-076-service-admin-member
--comment Седьмая роль ADMIN: администратор сервиса виден в организации отдельным участником (задача 0042).

-- Учётная запись администратора живёт в общей схеме ячейки (решение
-- владельца продукта от 29 сентября 2026), а в схеме арендатора нужна строка:
-- около двадцати пяти колонок «кто сделал» ссылаются внешним ключом на
-- tenant_member, и без строки журнал не назвал бы администратора настоящим
-- автором. Строка заводится при входе администратора в кабинет.
--
-- Учётных данных у строки нет: вход у неё свой, через общую схему.
--
-- service_admin_id указывает в public.service_admin без внешнего ключа:
-- восстановление одной схемы арендатора не должно зависеть от общей.
-- Одна строка на администратора в схеме — частичный уникальный индекс, а не
-- проверка чтением.
--
-- CHECK «роль ADMIN тогда и только тогда, когда есть ссылка» и «у строки
-- администратора нет логина» сознательно отложены: сторож совместимости
-- считает их новым ограничением на старые колонки (role, login). Инварианты
-- держит Java: service_admin_id пишет только AdminVisits (роль ADMIN, ссылка,
-- без логина), MemberService роль ADMIN не выдаёт, а вход сотрудника
-- отказывает строке с этой ролью. Вернуть CHECK можно отдельным сужающим
-- набором изменений после релиза, в котором код это пишет
-- (expand/contract, db/CLAUDE.md).
--
-- Прежний код колонку не знает и пишет NULL, а ролей не создаёт: expand.
ALTER TABLE ${tenant.schema}.tenant_member
    DROP CONSTRAINT tenant_member_role_ck;

ALTER TABLE ${tenant.schema}.tenant_member
    ADD CONSTRAINT tenant_member_role_ck CHECK (role IN
        ('OWNER','MANAGER','STOREKEEPER','SELLER','VIEWER','AUDITOR','ADMIN'));

ALTER TABLE ${tenant.schema}.tenant_member
    ADD COLUMN service_admin_id bigint;

CREATE UNIQUE INDEX tenant_member_service_admin_uk
    ON ${tenant.schema}.tenant_member (service_admin_id) WHERE service_admin_id IS NOT NULL;

-- Откат сначала уводит строки администраторов из снимаемого значения, иначе
-- сужение перечня встанет на «check constraint is violated by some row» у
-- клиента, в чьём кабинете администратор уже бывал (урок tenant/062). Удалить
-- строку нельзя: на неё ссылаются created_by и другие колонки «кто сделал».
-- Поэтому вниз, в «Просмотр», и выключенной: роль без существования оставила
-- бы вход, а повышение раздало бы права, которых никто не давал.
-- Ссылку service_admin_id тем же UPDATE обнуляем: без CHECK это не обязательно,
-- но строка с ролью «Просмотр» и ссылкой на администратора была бы ложью на
-- время, пока колонка ещё жива.
--rollback-теряет связь строк администратора сервиса с его учётной записью: они остаются выключенными участниками «Просмотр», а прежние записи журнала сохраняют автора
--rollback UPDATE ${tenant.schema}.tenant_member SET role = 'VIEWER', is_active = false, service_admin_id = NULL WHERE role = 'ADMIN';
--rollback DROP INDEX ${tenant.schema}.tenant_member_service_admin_uk;
--rollback ALTER TABLE ${tenant.schema}.tenant_member DROP COLUMN service_admin_id;
--rollback ALTER TABLE ${tenant.schema}.tenant_member DROP CONSTRAINT tenant_member_role_ck;
--rollback ALTER TABLE ${tenant.schema}.tenant_member ADD CONSTRAINT tenant_member_role_ck CHECK (role IN ('OWNER','MANAGER','STOREKEEPER','SELLER','VIEWER','AUDITOR'));
