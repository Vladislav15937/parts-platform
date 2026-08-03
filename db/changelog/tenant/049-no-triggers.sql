--liquibase formatted sql

--changeset platform:tenant-049-drop-touch-triggers
--comment Момент последней правки ставит приложение (@PreUpdate у сущностей,
--comment `updated_at = now()` в тех запросах, что идут прямым SQL).
--comment
--comment Семь одинаковых триггеров на семи таблицах — самая безобидная часть
--comment переноса: они не вычисляли ничего, кроме времени. Но правило одно
--comment на всех: логика в приложении.
DROP TRIGGER IF EXISTS customer_touch ON ${tenant.schema}.customer;
DROP TRIGGER IF EXISTS deal_touch ON ${tenant.schema}.deal;
DROP TRIGGER IF EXISTS donor_touch ON ${tenant.schema}.donor;
DROP TRIGGER IF EXISTS part_touch ON ${tenant.schema}.part;
DROP TRIGGER IF EXISTS part_name_touch ON ${tenant.schema}.part_name;
DROP TRIGGER IF EXISTS stock_document_touch ON ${tenant.schema}.stock_document;
DROP TRIGGER IF EXISTS supply_touch ON ${tenant.schema}.supply;
DROP FUNCTION IF EXISTS ${tenant.schema}.touch_updated_at();
--rollback SELECT 1;

--changeset platform:tenant-049-drop-immutability-triggers
--comment Неизменяемость журналов теперь держится кодом.
--comment
--comment Триггер отбивал UPDATE и DELETE на журнале движений, журнале
--comment лицевого счёта и событиях документа. Заменить его правами
--comment (REVOKE UPDATE, DELETE) не вышло: приложение ходит в базу
--comment суперпользователем, а он обходит любые права — проверено опытом
--comment на живой схеме, UPDATE прошёл при снятых правах.
--comment
--comment Значит гарантия переезжает в код, и держится она там, где её нельзя
--comment обойти по невнимательности: репозитории этих трёх сущностей
--comment расширяют Repository, а не JpaRepository, то есть методов delete
--comment и deleteAll в них нет вовсе — написать их не даст компилятор.
--comment Сами сущности помечены @Immutable: Hibernate не отправит UPDATE,
--comment даже если поле изменят в памяти.
--comment
--comment Чего это не закрывает: правку прямым SQL из psql. Раньше её отбивал
--comment триггер, теперь не отбивает ничто. Это осознанная цена правила,
--comment и вернуть защиту можно ролью без SUPERUSER — тогда сработает REVOKE.
DROP TRIGGER IF EXISTS stock_movement_no_update ON ${tenant.schema}.stock_movement;
DROP TRIGGER IF EXISTS customer_account_entry_immutable
    ON ${tenant.schema}.customer_account_entry;
DROP TRIGGER IF EXISTS document_event_immutable ON ${tenant.schema}.document_event;
DROP FUNCTION IF EXISTS ${tenant.schema}.stock_movement_immutable();
--rollback SELECT 1;

--changeset platform:tenant-049-drop-audit-triggers
--comment Журнал изменений пишет AuditLogListener — слушатель Hibernate.
--comment
--comment Формат снимка сохранён дословно: имена колонок базы, а не свойств
--comment класса, — журнал читает PartHistoryService и сравнивает снимки
--comment между собой, поэтому расхождение в именах сломало бы историю
--comment карточки целиком.
--comment
--comment Слушатель, а не запись из сервисов: список мест, где надо не забыть
--comment записать, рос бы с каждым новым методом, а забытая запись всплывает
--comment через месяц при разбирательстве.
--comment
--comment Чего это не закрывает — и это самая дорогая потеря всего переноса:
--comment триггер видел любую правку, включая прямой SQL, перенос из прежней
--comment системы и починку руками. Слушатель видит только то, что прошло
--comment через Hibernate. «Кто уронил цену» спрашивают ровно тогда, когда
--comment подозревают правку мимо интерфейса, и теперь такой правки в журнале
--comment не будет. Возвращается это тоже ролью без SUPERUSER: пока прямой SQL
--comment возможен, журнал неполон.
DROP TRIGGER IF EXISTS part_audit ON ${tenant.schema}.part;
DROP TRIGGER IF EXISTS deal_audit ON ${tenant.schema}.deal;
DROP TRIGGER IF EXISTS deal_item_audit ON ${tenant.schema}.deal_item;
DROP TRIGGER IF EXISTS payment_audit ON ${tenant.schema}.payment;
DROP TRIGGER IF EXISTS donor_cost_audit ON ${tenant.schema}.donor_cost;
DROP FUNCTION IF EXISTS ${tenant.schema}.audit_trigger();
--rollback SELECT 1;
