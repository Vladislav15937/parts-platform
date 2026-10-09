--liquibase formatted sql

--changeset partsflow:tenant-073-part-client-request-id
--comment Ключ запроса при заведении ожидаемой позиции: ответ на одновременный повтор — задача 0170
--
-- Двойное нажатие «Завести» отправляет два запроса почти разом. Проверка
-- «нет ли уже такой позиции» между чтением и вставкой второй запрос не
-- останавливает, поэтому дубль отбивает уникальный индекс, а код перечитывает
-- результат первого запроса и отдаёт его (порядок тот же, что у
-- stock_document и part_photo в tenant/016).
--
-- Колонка у обычных позиций пуста: принятая с телефона деталь идемпотентна
-- по ключу документа, а не позиции. NULL друг с другом в индексе не
-- сталкиваются, поэтому индекс частичный.
ALTER TABLE ${tenant.schema}.part ADD COLUMN client_request_id text;
CREATE UNIQUE INDEX part_client_request_uk
    ON ${tenant.schema}.part (client_request_id)
    WHERE client_request_id IS NOT NULL;

COMMENT ON COLUMN ${tenant.schema}.part.client_request_id IS
    'Ключ запроса клиента при заведении ожидаемой позиции; повтор с тем же ключом возвращает первую позицию. NULL у остальных. Задача 0170';

--rollback-теряет ключи запросов у заведённых позиций: повтор уже отправленного запроса после отката создаст вторую позицию вместо возврата первой. Сами позиции остаются
--rollback DROP INDEX ${tenant.schema}.part_client_request_uk;
--rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN client_request_id;
