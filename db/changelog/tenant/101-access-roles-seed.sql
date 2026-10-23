--liquibase formatted sql

--changeset partsflow:tenant-101-access-roles-seed
--comment Перенос без потери доступа: прежние роли становятся обычными ролями клиента с прежними наборами, сотрудник получает равную своей (задача 0044)

-- Ответ владельца 23.10.2026 (tasks/0044, «Ответ владельца — 23 октября 2026
-- (на оставшиеся три)», пункт 3): «при выкладке у каждого клиента заводятся
-- роли „Менеджер“, „Кладовщик“, „Продавец“, „Просмотр“ как обычные роли (их
-- можно править и удалять) с наборами прав, равными нынешним; сотрудник
-- получает роль, равную своей прежней. Встроенной остаётся только „Владелец“».
--
-- СТРОКИ ЗАВОДИТ CHANGESET, А НЕ ПРОВИЖИНИНГ: тот заводит только новых
-- арендаторов, а роли нужны уже работающим (db/CLAUDE.md, tenant/066).
-- Новый клиент получает их тем же накатом — его владельца провижининг
-- заводит после миграций.
--
-- «РЕВИЗОР» ЗАВОДИТСЯ ТОЛЬКО ТАМ, ГДЕ ОН ЕСТЬ. Шестая жёсткая роль AUDITOR
-- (tenant/062, читатель журнала) в списке владельца не названа — список
-- повторял вопрос, а вопрос перечислял пять ролей из текста задачи. Но
-- условие ответа — «доступ при выкладке никто не теряет»: сотрудник-ревизор
-- без роли потерял бы журнал. Поэтому «Ревизор» заводится у клиента,
-- у которого есть хоть один такой сотрудник, и нигде больше: остальным
-- лишняя роль в списке была бы ролью, которой владелец не заводил.
-- Решение исполнителя, названо в PR.
--
-- ADMIN (администратор сервиса, tenant/076) роли не получает: он не
-- сотрудник клиента, права владельца ему даёт код (TenantPrincipal),
-- а не назначение, и владелец его не назначает и не снимает.
INSERT INTO ${tenant.schema}.access_role (name, is_owner, seeded_from)
VALUES ('Владелец',  true,  'OWNER'),
       ('Менеджер',  false, 'MANAGER'),
       ('Кладовщик', false, 'STOREKEEPER'),
       ('Продавец',  false, 'SELLER'),
       ('Просмотр',  false, 'VIEWER');

INSERT INTO ${tenant.schema}.access_role (name, is_owner, seeded_from)
SELECT 'Ревизор', false, 'AUDITOR'
 WHERE EXISTS (SELECT 1 FROM ${tenant.schema}.tenant_member WHERE role = 'AUDITOR');

-- НАБОРЫ — ТО, ЧТО КАЖДАЯ РОЛЬ ПРОХОДИТ СЕГОДНЯ, ПО КОДУ НА 23.10.2026
-- (@PreAuthorize по контроллерам и запрет изменяющих методов в
-- SecurityConfig). Ключ — действие, а не путь: под одним ключом собраны
-- пути, у которых сегодня одинаковый список ролей и один смысл. Владельцу
-- набор не пишется (все права всегда); то, что сегодня может только
-- владелец, не получает ни одна роль — ключи этого рода перечислены в PR
-- (среди них stock_document.delete_done, удаление проведённых документов
-- из 0367).
--
-- Чтения, открытые сегодня любому вошедшему (склад, поиск, сделка по
-- ссылке, справочники), ключа не имеют и в наборы не входят. Заведёт код
-- ключ и на них — раздавать его ролям придётся новым changeset'ом, как и
-- при переименовании ключа: строки здесь — данные клиента, и после
-- выкладки их правит владелец.
INSERT INTO ${tenant.schema}.access_role_permission (role_id, permission)
SELECT r.id, p.permission
  FROM ${tenant.schema}.access_role r
  JOIN (VALUES
        -- Приёмка: позиции, поставки и «прибыла», машины, разбор, место машины.
        ('MANAGER', 'intake.receive'), ('STOREKEEPER', 'intake.receive'), ('SELLER', 'intake.receive'),
        -- Снимки позиций и машин: загрузить, сделать главным, удалить.
        ('MANAGER', 'photos.manage'), ('STOREKEEPER', 'photos.manage'), ('SELLER', 'photos.manage'),
        -- Затраты по машине: добавить, удалить.
        ('MANAGER', 'donor.costs'),
        -- Перемещение по складу и журнал перемещений.
        ('MANAGER', 'stock.move'), ('STOREKEEPER', 'stock.move'),
        -- Списание.
        ('MANAGER', 'stock.write_off'),
        -- Правка карточки и цены, правка списком, применимость из карточки,
        -- «Двигатель важен».
        ('MANAGER', 'part.edit'),
        -- «Выгружать» списком.
        ('MANAGER', 'part.publish'),
        -- Ячейка позиции.
        ('MANAGER', 'part.cell'), ('STOREKEEPER', 'part.cell'),
        -- Деньги в истории позиции: закупка и себестоимость.
        ('MANAGER', 'part.money.view'),
        -- Сопоставление наименований: сопоставить, снять, пересопоставить.
        ('MANAGER', 'names.match'),
        -- Именованные текстовые блоки.
        ('MANAGER', 'text_blocks.manage'),
        -- Выгрузка таблицы склада и колёс в файл.
        ('MANAGER', 'export.table'),
        -- Колёса комплектом.
        ('MANAGER', 'wheels.set'), ('STOREKEEPER', 'wheels.set'),
        -- Пересчёт: открыть, считать, обход полок.
        ('MANAGER', 'inventory.count'), ('STOREKEEPER', 'inventory.count'), ('SELLER', 'inventory.count'),
        -- Пометка счётчика к пересчёту.
        ('MANAGER', 'inventory.counter_note'), ('STOREKEEPER', 'inventory.counter_note'),
        -- Свести расхождения: завершить, провести, отменить, комментарий.
        ('MANAGER', 'inventory.reconcile'),
        -- Журнал пересчётов (сегодня его видит «Просмотр», а кладовщик и продавец — нет).
        ('MANAGER', 'inventory.journal.view'), ('VIEWER', 'inventory.journal.view'),
        -- Продажа: сделка, заказ с площадки, резерв, оплата, перенос, ссылка покупателю.
        ('MANAGER', 'deal.sell'), ('SELLER', 'deal.sell'),
        -- Выдача: открыть сделку, напечатать, выдать, история сделки.
        ('MANAGER', 'deal.issue'), ('STOREKEEPER', 'deal.issue'), ('SELLER', 'deal.issue'),
        -- Возврат: оформить и отменить.
        ('MANAGER', 'deal.return'), ('SELLER', 'deal.return'),
        -- Клиенты: карточка, справочник, «Частное лицо», лицевой счёт на просмотр.
        ('MANAGER', 'customer.view'), ('SELLER', 'customer.view'),
        ('MANAGER', 'customer.create'), ('SELLER', 'customer.create'),
        ('MANAGER', 'customer.edit'),
        -- Лицевой счёт: пополнить, снять.
        ('MANAGER', 'customer.account'), ('SELLER', 'customer.account'),
        -- Лицевой счёт: исправить.
        ('MANAGER', 'customer.account.correct'),
        -- Реестр платежей.
        ('MANAGER', 'payments.registry.view'),
        -- Выгрузки: список, адрес прайса, счёт отбора.
        ('MANAGER', 'marketplace.view'),
        -- Выгрузки: отбор и настройки прайса.
        ('MANAGER', 'marketplace.configure'),
        -- Отчёты владельца.
        ('MANAGER', 'reports.view'),
        -- «Доставка»: недоставленные события, повторить и снять.
        ('MANAGER', 'delivery.manage'),
        -- Склады и ячейки: завести.
        ('MANAGER', 'org.warehouses'),
        -- Журнал действий и журнал входов.
        ('AUDITOR', 'journal.view')
       ) AS p(seeded_from, permission)
    ON p.seeded_from = r.seeded_from;

-- Сотрудник получает роль, равную своей прежней. Администратор сервиса
-- (ADMIN) пары не находит и остаётся без назначения — намеренно, см. выше.
--
-- Назначается только тому, у кого роли нет. На первом накате это все, а при
-- повторном (откат 101 и накат обратно) у сотрудника может стоять роль,
-- которую завёл клиент: откат её не снимает. Без условия такой сотрудник
-- молча переехал бы на роль по своей прежней колонке role — для своей роли
-- клиента там «Просмотр», то есть тихая смена прав (найдено разбором PR 502).
UPDATE ${tenant.schema}.tenant_member m
   SET access_role_id = r.id
  FROM ${tenant.schema}.access_role r
 WHERE r.seeded_from = m.role
   AND m.access_role_id IS NULL;

-- Откат снимает только заведённое здесь: роли с происхождением и их наборы
-- (CASCADE), а назначения на них — до удаления, иначе ссылка не даст.
-- Роли, заведённые клиентом, остаются: их снимет откат tenant/100.
--rollback-теряет роли «Владелец», «Менеджер», «Кладовщик», «Продавец», «Просмотр» и «Ревизор» вместе с тем, как владелец успел их переименовать и пересобрать, и назначения сотрудникам на них: права снова решает прежняя жёсткая роль из tenant_member.role
--rollback UPDATE ${tenant.schema}.tenant_member SET access_role_id = NULL WHERE access_role_id IN (SELECT id FROM ${tenant.schema}.access_role WHERE seeded_from IS NOT NULL);
--rollback DELETE FROM ${tenant.schema}.access_role WHERE seeded_from IS NOT NULL;
