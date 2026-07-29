--liquibase formatted sql

--changeset platform:catalog-050-tenant-registry
--comment Реестр арендаторов ячейки. Служебное, бизнес-данных не содержит.
--comment schema_version дублирует DATABASECHANGELOG арендатора и нужен, чтобы
--comment оркестратор видел разъезд версий одним запросом, без обхода схем.
CREATE TABLE public.tenant_registry (
    tenant_id      bigint      PRIMARY KEY,
    schema_name    text        NOT NULL UNIQUE,
    company_name   text        NOT NULL,
    status         text        NOT NULL DEFAULT 'ACTIVE',
    schema_version text,
    migrated_at    timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_registry_schema_ck CHECK (schema_name ~ '^t_[0-9]{6,}$'),
    CONSTRAINT tenant_registry_status_ck CHECK (status IN ('ACTIVE','SUSPENDED','ARCHIVED'))
);
--rollback DROP TABLE public.tenant_registry;
