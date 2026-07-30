--liquibase formatted sql

--changeset platform:tenant-200-dead-letter-retry
--comment Повторная доставка непринятых событий: разбор перестаёт быть
--comment запросом в базу. aggregate_type нужен, чтобы собрать событие заново —
--comment без него повтор отдал бы обработчику половину того, что уехало
--comment в первый раз. next_attempt_at держит выдержку между попытками:
--comment площадка, лежащая час, не должна получать одно и то же каждую минуту.
ALTER TABLE ${tenant.schema}.event_dead_letter
    ADD COLUMN aggregate_type  text,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN resolution      text,
    ADD COLUMN resolved_by     bigint REFERENCES ${tenant.schema}.tenant_member;

-- Снято человеком и доставлено повтором — разные исходы, и списывать их
-- в одно «resolved_at» значит потерять единственный след того, что событие
-- решили не доставлять вовсе.
ALTER TABLE ${tenant.schema}.event_dead_letter
    ADD CONSTRAINT event_dead_letter_resolution_ck
    CHECK (resolution IS NULL OR resolution IN ('RETRIED', 'DISCARDED'));

-- Автоповтор выбирает по сроку следующей попытки, и без этого индекса
-- каждый заход читал бы всю таблицу разбора.
CREATE INDEX event_dead_letter_due_ix
    ON ${tenant.schema}.event_dead_letter (next_attempt_at)
    WHERE resolved_at IS NULL;
--rollback ALTER TABLE ${tenant.schema}.event_dead_letter DROP COLUMN aggregate_type, DROP COLUMN next_attempt_at, DROP COLUMN resolution, DROP COLUMN resolved_by;
