--liquibase formatted sql

--changeset partsflow:tenant-107-stock-document-event
--comment История складского документа: кто и когда создал, добавил позицию, провёл, отменил (задача 0360)

-- Задача 0360, решение владельца 19.10.2026 «Всё как у bazon»: у документа
-- поступления колонка «История документа» — записи «автор · время / текст»:
-- «Создано поступление», «Добавлена позиция N», «Поступление проведено»,
-- «Отменено». Документ с 0360 живёт дольше одного действия (черновик набирают
-- смену, проводят вечером, проводку отменяют), и вопрос «кто и когда» о нём
-- задают ровно тогда, когда что-то не сошлось.
--
-- ПОЧЕМУ СВОЯ ТАБЛИЦА, А НЕ audit_log. Журнал изменений видит строки, а не
-- действия: «проведение» в нём — UPDATE статуса и десяток движений, «отмена
-- проводки» — UPDATE обратно; собирать из этого текст на экран значило бы
-- угадывать действие по набору изменённых колонок. Здесь пишется само
-- действие, одной строкой, тем же кодом, что его выполняет.
--
-- kind — перечень действий; слово для человека собирает Java
-- (StockDocumentHistory), в базе хранится код.
-- part_id — только у «Добавлена позиция»: какая позиция; у остальных пусто,
-- это держит CHECK.
-- created_by — кто; пусто у действий без вошедшего (фоновая задача, перенос).
--
-- Expand: новая таблица, прежний код её не знает. Логики в базе нет — пишет
-- приложение. Позиции и документы не удаляются, поэтому ссылки без ON DELETE.
CREATE TABLE ${tenant.schema}.stock_document_event (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id bigint      NOT NULL REFERENCES ${tenant.schema}.stock_document,
    kind        text        NOT NULL,
    part_id     bigint      REFERENCES ${tenant.schema}.part,
    created_by  bigint      REFERENCES ${tenant.schema}.tenant_member,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT stock_document_event_kind_ck CHECK (kind IN
        ('CREATED', 'LINE_ADDED', 'POSTED', 'UNPOSTED', 'CANCELLED')),
    CONSTRAINT stock_document_event_part_ck CHECK ((kind = 'LINE_ADDED') = (part_id IS NOT NULL))
);

CREATE INDEX stock_document_event_document_ix
    ON ${tenant.schema}.stock_document_event (document_id, id);

COMMENT ON TABLE ${tenant.schema}.stock_document_event IS
    'История складского документа: создано, добавлена позиция, проведено, проводка отменена, отменено. Пишет приложение тем же действием. Задача 0360';

--rollback-теряет историю документов поступления — кто и когда создал документ, добавил позицию, провёл и отменил проводку; сами документы, их строки и движения склада остаются
--rollback DROP TABLE ${tenant.schema}.stock_document_event;
