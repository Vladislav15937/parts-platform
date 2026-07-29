--liquibase formatted sql

--changeset platform:tenant-040-stock-movement
--comment Остаток — агрегат журнала, а не изменяемое поле. С полем любое
--comment расхождение неразбираемо задним числом: видно только текущее значение
--comment и неизвестно, как оно таким стало. Для разборок, которые покупают
--comment систему в том числе от воровства, это принципиально.
CREATE TABLE ${tenant.schema}.stock_movement (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    part_id       bigint        NOT NULL REFERENCES ${tenant.schema}.part,
    movement_type text          NOT NULL,
    qty_delta     numeric(12,3) NOT NULL,
    from_cell_id  bigint REFERENCES ${tenant.schema}.storage_cell,
    to_cell_id    bigint REFERENCES ${tenant.schema}.storage_cell,
    ref_type      text,
    ref_id        bigint,
    reason        text,
    created_by    bigint REFERENCES ${tenant.schema}.tenant_member,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT stock_movement_type_ck CHECK (movement_type IN
        ('INTAKE','MOVE','SALE','RETURN','WRITE_OFF','INVENTORY_ADJUST')),
    CONSTRAINT stock_movement_delta_ck CHECK (qty_delta <> 0 OR movement_type = 'MOVE')
);
CREATE INDEX stock_movement_part_ix ON ${tenant.schema}.stock_movement (part_id, created_at DESC);
CREATE INDEX stock_movement_created_ix ON ${tenant.schema}.stock_movement (created_at);
CREATE INDEX stock_movement_ref_ix ON ${tenant.schema}.stock_movement (ref_type, ref_id)
    WHERE ref_id IS NOT NULL;
--rollback DROP TABLE ${tenant.schema}.stock_movement;

--changeset platform:tenant-041-stock-immutable splitStatements:false runOnChange:true
--comment Журнал неизменяем на уровне БД: исправление только компенсирующим движением.
CREATE OR REPLACE FUNCTION ${tenant.schema}.stock_movement_immutable()
    RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    RAISE EXCEPTION 'stock_movement неизменяем: используйте компенсирующее движение';
END $fn$;
--rollback DROP FUNCTION IF EXISTS ${tenant.schema}.stock_movement_immutable();

--changeset platform:tenant-042-stock-immutable-trigger
CREATE TRIGGER stock_movement_no_update BEFORE UPDATE OR DELETE ON ${tenant.schema}.stock_movement
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.stock_movement_immutable();
--rollback DROP TRIGGER IF EXISTS stock_movement_no_update ON ${tenant.schema}.stock_movement;

--changeset platform:tenant-043-stock-apply splitStatements:false runOnChange:true
--comment Денормализация остатка ради скорости: читать сумму журнала на каждый
--comment показ карточки дорого. Раз в сутки — сверка агрегата с журналом.
CREATE OR REPLACE FUNCTION ${tenant.schema}.stock_movement_apply()
    RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    UPDATE ${tenant.schema}.part
       SET qty_on_hand = qty_on_hand + NEW.qty_delta,
           storage_cell_id = COALESCE(NEW.to_cell_id, storage_cell_id)
     WHERE id = NEW.part_id;
    RETURN NEW;
END $fn$;
--rollback DROP FUNCTION IF EXISTS ${tenant.schema}.stock_movement_apply();

--changeset platform:tenant-044-stock-apply-trigger
CREATE TRIGGER stock_movement_apply_trg AFTER INSERT ON ${tenant.schema}.stock_movement
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.stock_movement_apply();
--rollback DROP TRIGGER IF EXISTS stock_movement_apply_trg ON ${tenant.schema}.stock_movement;

--changeset platform:tenant-045-inventory
CREATE TABLE ${tenant.schema}.inventory_session (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    warehouse_id bigint      NOT NULL REFERENCES ${tenant.schema}.warehouse,
    status       text        NOT NULL DEFAULT 'OPEN',
    started_by   bigint      REFERENCES ${tenant.schema}.tenant_member,
    started_at   timestamptz NOT NULL DEFAULT now(),
    applied_at   timestamptz,
    CONSTRAINT inventory_session_status_ck CHECK (status IN
        ('OPEN','COUNTED','APPLIED','CANCELLED'))
);

CREATE TABLE ${tenant.schema}.inventory_line (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id   bigint        NOT NULL REFERENCES ${tenant.schema}.inventory_session ON DELETE CASCADE,
    part_id      bigint        NOT NULL REFERENCES ${tenant.schema}.part,
    cell_id      bigint        REFERENCES ${tenant.schema}.storage_cell,
    qty_expected numeric(12,3) NOT NULL,
    qty_counted  numeric(12,3),
    counted_by   bigint        REFERENCES ${tenant.schema}.tenant_member,
    counted_at   timestamptz,
    CONSTRAINT inventory_line_uk UNIQUE (session_id, part_id)
);
CREATE INDEX inventory_line_session_ix ON ${tenant.schema}.inventory_line (session_id);
--rollback DROP TABLE ${tenant.schema}.inventory_line;
--rollback DROP TABLE ${tenant.schema}.inventory_session;
