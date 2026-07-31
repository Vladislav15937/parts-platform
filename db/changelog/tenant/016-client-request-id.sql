--liquibase formatted sql

--changeset platform:tenant-150-client-request-id
--comment Ключ запроса от клиента: защита от повторов офлайн-очереди.
--comment
--comment Приёмка с телефона идёт через очередь в IndexedDB: телефон отправляет
--comment партию, соединение обрывается до ответа, очередь повторяет. Без ключа
--comment повтор создаёт вторую партию деталей — то есть удваивает склад
--comment по каждому обрыву связи в ангаре. Это не гипотетический риск: обрыв
--comment между отправкой и ответом — норма для мобильной сети.
--comment
--comment Ключ генерирует клиент при постановке в очередь и не меняет при
--comment повторах. Уникальность проверяет БД: серверная проверка «нет ли уже
--comment такого» между чтением и вставкой пропустит второй одновременный
--comment повтор, а уникальный индекс — нет.
ALTER TABLE ${tenant.schema}.stock_document ADD COLUMN client_request_id text;
CREATE UNIQUE INDEX stock_document_client_request_uk
    ON ${tenant.schema}.stock_document (client_request_id)
    WHERE client_request_id IS NOT NULL;

-- Фотографии тем же порядком: повтор запроса ссылки создавал бы вторую запись
-- и мусор в хранилище.
ALTER TABLE ${tenant.schema}.part_photo ADD COLUMN client_request_id text;
CREATE UNIQUE INDEX part_photo_client_request_uk
    ON ${tenant.schema}.part_photo (client_request_id)
    WHERE client_request_id IS NOT NULL;
--rollback DROP INDEX ${tenant.schema}.part_photo_client_request_uk;
--rollback ALTER TABLE ${tenant.schema}.part_photo DROP COLUMN client_request_id;
--rollback DROP INDEX ${tenant.schema}.stock_document_client_request_uk;
--rollback ALTER TABLE ${tenant.schema}.stock_document DROP COLUMN client_request_id;
