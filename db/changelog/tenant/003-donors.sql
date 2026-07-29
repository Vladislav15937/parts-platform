--liquibase formatted sql

--changeset platform:tenant-020-donor
--comment Ссылки на catalog.* — обычные bigint без FK. Это осознанно: внешние ключи
--comment между схемами связали бы восстановление арендатора с состоянием каталога
--comment и сломали бы независимый pg_restore -n.
CREATE TABLE ${tenant.schema}.donor (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_code      text        NOT NULL DEFAULT ${tenant.schema}.gen_public_code(),
    vin              text,
    brand_id         bigint      NOT NULL,
    model_id         bigint,
    generation_id    bigint,
    modification_id  bigint,
    year             smallint,
    color            text,
    mileage_km       integer,
    plate_number     text,
    status           text        NOT NULL DEFAULT 'PURCHASED',
    purchase_date    date,
    supplier_name    text,
    branch_id        bigint      REFERENCES ${tenant.schema}.branch,
    note             text,
    created_by       bigint      REFERENCES ${tenant.schema}.tenant_member,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT donor_public_code_uk UNIQUE (public_code),
    CONSTRAINT donor_status_ck CHECK (status IN
        ('PURCHASED','DISMANTLING','DISMANTLED','WRITTEN_OFF'))
);
CREATE INDEX donor_status_ix ON ${tenant.schema}.donor (status);
CREATE INDEX donor_vehicle_ix ON ${tenant.schema}.donor (brand_id, model_id, generation_id);
CREATE INDEX donor_vin_ix ON ${tenant.schema}.donor (vin) WHERE vin IS NOT NULL;

CREATE TRIGGER donor_touch BEFORE UPDATE ON ${tenant.schema}.donor
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.touch_updated_at();
--rollback DROP TABLE ${tenant.schema}.donor;

--changeset platform:tenant-021-donor-cost
--comment Все затраты по донору: база для расчёта окупаемости.
CREATE TABLE ${tenant.schema}.donor_cost (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    donor_id    bigint        NOT NULL REFERENCES ${tenant.schema}.donor ON DELETE CASCADE,
    cost_type   text          NOT NULL,
    amount      numeric(14,2) NOT NULL,
    incurred_on date          NOT NULL DEFAULT current_date,
    note        text,
    created_by  bigint        REFERENCES ${tenant.schema}.tenant_member,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT donor_cost_amount_ck CHECK (amount >= 0),
    CONSTRAINT donor_cost_type_ck CHECK (cost_type IN
        ('PURCHASE','DELIVERY','CUSTOMS','DISMANTLING','STORAGE','OTHER'))
);
CREATE INDEX donor_cost_donor_ix ON ${tenant.schema}.donor_cost (donor_id);
--rollback DROP TABLE ${tenant.schema}.donor_cost;
