--liquibase formatted sql

--changeset platform:tenant-010-branch
CREATE TABLE ${tenant.schema}.branch (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text        NOT NULL,
    address    text,
    phone      text,
    is_active  boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
--rollback DROP TABLE ${tenant.schema}.branch;

--changeset platform:tenant-011-tenant-member
--comment Личность пользователя живёт в control plane; здесь только членство и роль.
--comment Иначе человек, работающий на двух разборках, превращается в две
--comment несвязанные учётки.
CREATE TABLE ${tenant.schema}.tenant_member (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      bigint      NOT NULL,
    display_name text        NOT NULL,
    role         text        NOT NULL,
    branch_id    bigint      REFERENCES ${tenant.schema}.branch,
    is_active    boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_member_user_uk UNIQUE (user_id),
    CONSTRAINT tenant_member_role_ck CHECK (role IN
        ('OWNER','MANAGER','STOREKEEPER','SELLER','VIEWER'))
);
--rollback DROP TABLE ${tenant.schema}.tenant_member;

--changeset platform:tenant-012-warehouse
CREATE TABLE ${tenant.schema}.warehouse (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id  bigint  NOT NULL REFERENCES ${tenant.schema}.branch,
    name       text    NOT NULL,
    is_active  boolean NOT NULL DEFAULT true
);
--rollback DROP TABLE ${tenant.schema}.warehouse;

--changeset platform:tenant-013-storage-cell
--comment code — то, что физически напечатано на стеллаже.
CREATE TABLE ${tenant.schema}.storage_cell (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    warehouse_id  bigint  NOT NULL REFERENCES ${tenant.schema}.warehouse,
    code          text    NOT NULL,
    zone          text,
    is_active     boolean NOT NULL DEFAULT true,
    CONSTRAINT storage_cell_uk UNIQUE (warehouse_id, code)
);
--rollback DROP TABLE ${tenant.schema}.storage_cell;
