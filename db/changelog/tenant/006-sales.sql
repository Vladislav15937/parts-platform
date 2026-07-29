--liquibase formatted sql

--changeset platform:tenant-050-customer
CREATE TABLE ${tenant.schema}.customer (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          text,
    phone         text,
    email         text,
    customer_type text        NOT NULL DEFAULT 'PERSON',
    source        text,
    note          text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_type_ck CHECK (customer_type IN ('PERSON','COMPANY'))
);
CREATE INDEX customer_phone_ix ON ${tenant.schema}.customer (phone) WHERE phone IS NOT NULL;
CREATE INDEX customer_name_trgm ON ${tenant.schema}.customer USING gin (name gin_trgm_ops);

CREATE TRIGGER customer_touch BEFORE UPDATE ON ${tenant.schema}.customer
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.touch_updated_at();
--rollback DROP TABLE ${tenant.schema}.customer;

--changeset platform:tenant-051-deal
--comment manager_id заполняется всегда: учёт продаж по менеджерам для расчёта
--comment зарплат невозможно достоверно восстановить задним числом, а именно
--comment на его отсутствие жалуются пользователи конкурирующих систем.
CREATE SEQUENCE ${tenant.schema}.deal_number_seq;

CREATE TABLE ${tenant.schema}.deal (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    number       bigint      NOT NULL DEFAULT nextval('${tenant.schema}.deal_number_seq'),
    customer_id  bigint      REFERENCES ${tenant.schema}.customer,
    branch_id    bigint      REFERENCES ${tenant.schema}.branch,
    manager_id   bigint      REFERENCES ${tenant.schema}.tenant_member,
    status       text        NOT NULL DEFAULT 'DRAFT',
    source       text,
    total_amount    numeric(14,2) NOT NULL DEFAULT 0,
    discount_amount numeric(14,2) NOT NULL DEFAULT 0,
    delivery_type    text,
    delivery_address text,
    note         text,
    created_by   bigint      REFERENCES ${tenant.schema}.tenant_member,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    closed_at    timestamptz,
    CONSTRAINT deal_number_uk UNIQUE (number),
    CONSTRAINT deal_status_ck CHECK (status IN
        ('DRAFT','RESERVED','PAID','SHIPPED','COMPLETED','CANCELLED','RETURNED'))
);
CREATE INDEX deal_manager_ix ON ${tenant.schema}.deal (manager_id, closed_at DESC);
CREATE INDEX deal_customer_ix ON ${tenant.schema}.deal (customer_id);
CREATE INDEX deal_status_ix ON ${tenant.schema}.deal (status, created_at DESC);

CREATE TRIGGER deal_touch BEFORE UPDATE ON ${tenant.schema}.deal
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.touch_updated_at();
--rollback DROP TABLE ${tenant.schema}.deal;
--rollback DROP SEQUENCE ${tenant.schema}.deal_number_seq;

--changeset platform:tenant-052-deal-item
--comment Себестоимость фиксируется снимком на момент продажи: переоценка донора
--comment задним числом не должна переписывать прибыль прошлых месяцев.
CREATE TABLE ${tenant.schema}.deal_item (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deal_id  bigint        NOT NULL REFERENCES ${tenant.schema}.deal ON DELETE CASCADE,
    part_id  bigint        NOT NULL REFERENCES ${tenant.schema}.part,
    quantity numeric(12,3) NOT NULL DEFAULT 1,
    price    numeric(14,2) NOT NULL,
    discount numeric(14,2) NOT NULL DEFAULT 0,
    cost_price_snapshot numeric(14,2),
    CONSTRAINT deal_item_qty_ck CHECK (quantity > 0)
);
CREATE INDEX deal_item_deal_ix ON ${tenant.schema}.deal_item (deal_id);
CREATE INDEX deal_item_part_ix ON ${tenant.schema}.deal_item (part_id);
--rollback DROP TABLE ${tenant.schema}.deal_item;

--changeset platform:tenant-053-payment
CREATE TABLE ${tenant.schema}.payment (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deal_id           bigint        NOT NULL REFERENCES ${tenant.schema}.deal ON DELETE CASCADE,
    payment_type      text          NOT NULL,
    amount            numeric(14,2) NOT NULL,
    fiscal_receipt_id text,
    paid_at           timestamptz   NOT NULL DEFAULT now(),
    created_by        bigint        REFERENCES ${tenant.schema}.tenant_member,
    CONSTRAINT payment_amount_ck CHECK (amount <> 0),
    CONSTRAINT payment_type_ck CHECK (payment_type IN ('CASH','CARD','ONLINE','TRANSFER'))
);
CREATE INDEX payment_deal_ix ON ${tenant.schema}.payment (deal_id);
CREATE INDEX payment_paid_at_ix ON ${tenant.schema}.payment (paid_at);
--rollback DROP TABLE ${tenant.schema}.payment;

--changeset platform:tenant-054-deal-return
CREATE TABLE ${tenant.schema}.deal_return (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deal_id    bigint        NOT NULL REFERENCES ${tenant.schema}.deal,
    part_id    bigint        NOT NULL REFERENCES ${tenant.schema}.part,
    quantity   numeric(12,3) NOT NULL,
    amount     numeric(14,2) NOT NULL,
    reason     text,
    restocked  boolean       NOT NULL DEFAULT true,
    created_by bigint        REFERENCES ${tenant.schema}.tenant_member,
    created_at timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX deal_return_deal_ix ON ${tenant.schema}.deal_return (deal_id);
--rollback DROP TABLE ${tenant.schema}.deal_return;

--changeset platform:tenant-055-reservation
--comment Резерв со сроком, а не флаг на детали: иначе через месяц весь склад
--comment «зарезервирован» под звонки, которые ничем не кончились.
CREATE TABLE ${tenant.schema}.reservation (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    part_id     bigint        NOT NULL REFERENCES ${tenant.schema}.part,
    deal_id     bigint        REFERENCES ${tenant.schema}.deal,
    quantity    numeric(12,3) NOT NULL DEFAULT 1,
    expires_at  timestamptz   NOT NULL,
    released_at timestamptz,
    created_by  bigint        REFERENCES ${tenant.schema}.tenant_member,
    created_at  timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX reservation_active_ix ON ${tenant.schema}.reservation (expires_at) WHERE released_at IS NULL;
CREATE INDEX reservation_part_ix ON ${tenant.schema}.reservation (part_id) WHERE released_at IS NULL;
--rollback DROP TABLE ${tenant.schema}.reservation;
