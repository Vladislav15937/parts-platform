--liquibase formatted sql

--changeset platform:tenant-160-processed-event
--comment Журнал обработанных событий: защита потребителей от повторов.
--comment
--comment Транспорт даёт at-least-once. Одно и то же событие приходит дважды при
--comment любом сбое между обработкой и подтверждением — при перезапуске
--comment потребителя, при ребалансировке группы, при повторе релея. Для дельты
--comment на Дром повтор безобиден, но следующий же обработчик может списывать
--comment остаток или начислять премию менеджеру, и там дубль означает порчу
--comment данных.
--comment
--comment Ключ составной: одно событие обрабатывают несколько обработчиков
--comment независимо, и то, что дельту уже отправили, не значит, что уведомление
--comment уже ушло.
--comment
--comment Проверка — вставкой, а не чтением. Между SELECT «нет ли уже такого»
--comment и обработкой встанет второй экземпляр потребителя, и оба решат, что
--comment события не было. Первичный ключ так не обмануть.
CREATE TABLE ${tenant.schema}.processed_event (
    handler      text        NOT NULL,
    event_id     bigint      NOT NULL,
    event_type   text        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT processed_event_pk PRIMARY KEY (handler, event_id)
);

--rollback DROP TABLE ${tenant.schema}.processed_event;

--changeset platform:tenant-161-event-dead-letter
--comment События, которые обработчик не смог принять.
--comment
--comment Без этой таблицы отказ обработчика — тихая потеря: релей своё дело
--comment сделал, событие помечено опубликованным, и о том, что дельта на Дром
--comment не ушла, никто не узнает. Площадка при этом неделю показывает
--comment проданную деталь.
--comment
--comment Повторно обработанное здесь не удаляется, а помечается: журнал должен
--comment показывать, что отказ был. Иначе разбор выглядит так, будто ничего
--comment не случалось.
CREATE TABLE ${tenant.schema}.event_dead_letter (
    id           bigserial   PRIMARY KEY,
    handler      text        NOT NULL,
    event_id     bigint      NOT NULL,
    event_type   text        NOT NULL,
    aggregate_id bigint      NOT NULL,
    payload      bytea,
    error        text        NOT NULL,
    attempts     int         NOT NULL DEFAULT 1,
    created_at   timestamptz NOT NULL DEFAULT now(),
    resolved_at  timestamptz,
    CONSTRAINT event_dead_letter_uk UNIQUE (handler, event_id)
);

CREATE INDEX event_dead_letter_unresolved_ix
    ON ${tenant.schema}.event_dead_letter (created_at)
    WHERE resolved_at IS NULL;

--rollback DROP TABLE ${tenant.schema}.event_dead_letter;
