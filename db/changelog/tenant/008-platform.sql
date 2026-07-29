--liquibase formatted sql

--changeset platform:tenant-070-outbox
--comment Событие пишется в той же транзакции, что и данные. Без этого гарантий
--comment доставки в Kafka не существует в принципе: «сохранил и отправил» —
--comment это две операции, между которыми процесс может умереть.
CREATE TABLE ${tenant.schema}.outbox (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type text        NOT NULL,
    aggregate_id   bigint      NOT NULL,
    event_type     text        NOT NULL,
    partition_key  text        NOT NULL,
    payload        bytea       NOT NULL,
    headers        jsonb       NOT NULL DEFAULT '{}',
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);
CREATE INDEX outbox_unpublished_ix ON ${tenant.schema}.outbox (id) WHERE published_at IS NULL;
--rollback DROP TABLE ${tenant.schema}.outbox;

--changeset platform:tenant-071-audit-log
CREATE TABLE ${tenant.schema}.audit_log (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    table_name text        NOT NULL,
    record_id  bigint,
    operation  text        NOT NULL,
    old_value  jsonb,
    new_value  jsonb,
    changed_by bigint,
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_record_ix ON ${tenant.schema}.audit_log (table_name, record_id, changed_at DESC);
CREATE INDEX audit_log_user_ix ON ${tenant.schema}.audit_log (changed_by, changed_at DESC);
CREATE INDEX audit_log_changed_ix ON ${tenant.schema}.audit_log (changed_at);
--rollback DROP TABLE ${tenant.schema}.audit_log;

--changeset platform:tenant-072-audit-function splitStatements:false runOnChange:true
--comment Аудит на триггерах, а не через ORM: прямой SQL его не обойдёт.
--comment Именно это и нужно, когда журнал служит защитой от недобросовестных действий.
CREATE OR REPLACE FUNCTION ${tenant.schema}.audit_trigger()
    RETURNS trigger LANGUAGE plpgsql AS $fn$
DECLARE
    v_user bigint := nullif(current_setting('app.user_id', true), '')::bigint;
    v_id   bigint;
BEGIN
    v_id := CASE WHEN TG_OP = 'DELETE' THEN (to_jsonb(OLD)->>'id')::bigint
                 ELSE (to_jsonb(NEW)->>'id')::bigint END;

    INSERT INTO ${tenant.schema}.audit_log
        (table_name, record_id, operation, old_value, new_value, changed_by)
    VALUES (TG_TABLE_NAME, v_id, TG_OP,
            CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) END,
            CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) END,
            v_user);

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $fn$;
--rollback DROP FUNCTION IF EXISTS ${tenant.schema}.audit_trigger();

--changeset platform:tenant-073-audit-triggers
--comment Аудит вешается на таблицы, изменения в которых имеют денежные последствия.
CREATE TRIGGER part_audit AFTER INSERT OR UPDATE OR DELETE ON ${tenant.schema}.part
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.audit_trigger();
CREATE TRIGGER deal_audit AFTER INSERT OR UPDATE OR DELETE ON ${tenant.schema}.deal
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.audit_trigger();
CREATE TRIGGER deal_item_audit AFTER INSERT OR UPDATE OR DELETE ON ${tenant.schema}.deal_item
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.audit_trigger();
CREATE TRIGGER payment_audit AFTER INSERT OR UPDATE OR DELETE ON ${tenant.schema}.payment
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.audit_trigger();
CREATE TRIGGER donor_cost_audit AFTER INSERT OR UPDATE OR DELETE ON ${tenant.schema}.donor_cost
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.audit_trigger();
--rollback DROP TRIGGER IF EXISTS donor_cost_audit ON ${tenant.schema}.donor_cost;
--rollback DROP TRIGGER IF EXISTS payment_audit ON ${tenant.schema}.payment;
--rollback DROP TRIGGER IF EXISTS deal_item_audit ON ${tenant.schema}.deal_item;
--rollback DROP TRIGGER IF EXISTS deal_audit ON ${tenant.schema}.deal;
--rollback DROP TRIGGER IF EXISTS part_audit ON ${tenant.schema}.part;
