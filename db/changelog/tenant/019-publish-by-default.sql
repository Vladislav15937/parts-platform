--liquibase formatted sql

--changeset platform:tenant-180-publish-by-default
--comment Принятая деталь публикуется по умолчанию.
--comment
--comment Флаг существовал с первого дня, прайс площадки по нему фильтрует —
--comment а выставлял его только импорт из Bazon. Ни приёмка, ни импорт
--comment из таблицы, ни один эндпоинт его не трогали, поэтому прайс любого
--comment нового клиента оставался пустым, а вся цепочка выгрузки была
--comment недостижима. Нашлось сквозным прогоном, а не тестами: по отдельности
--comment каждый кусок работал.
--comment
--comment Значение по умолчанию именно true. На разборке деталь снимают, чтобы
--comment продать: не публиковать — это исключение (битая, под заказ, оставлена
--comment себе), и оно отмечается руками. Обратное умолчание означало бы, что
--comment клиент отмечает каждую из пятидесяти тысяч позиций, то есть
--comment не отмечает ни одной.
ALTER TABLE ${tenant.schema}.part ALTER COLUMN is_published SET DEFAULT true;

--comment Уже заведённое подтягиваем к тому же правилу: позиции без цены
--comment в прайс всё равно не попадут, а с ценой — ровно то, что клиент
--comment и собирался продавать.
UPDATE ${tenant.schema}.part
   SET is_published = true
 WHERE NOT is_published AND price IS NOT NULL AND status IN ('IN_STOCK', 'SOLD');

--rollback ALTER TABLE ${tenant.schema}.part ALTER COLUMN is_published SET DEFAULT false;
