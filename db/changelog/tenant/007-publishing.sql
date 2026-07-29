--liquibase formatted sql

--changeset platform:tenant-060-marketplace-account
--comment credentials шифруются приложением, ключ вне БД. Дамп базы, попавший
--comment не в те руки, не должен давать доступ к кабинетам клиента на площадках.
CREATE TABLE ${tenant.schema}.marketplace_account (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    marketplace  text        NOT NULL,
    title        text        NOT NULL,
    credentials  bytea,
    settings     jsonb       NOT NULL DEFAULT '{}',
    status       text        NOT NULL DEFAULT 'ACTIVE',
    last_sync_at timestamptz,
    last_error   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT marketplace_account_uk UNIQUE (marketplace, title),
    CONSTRAINT marketplace_account_code_ck CHECK (marketplace IN ('AVITO','DROM','JAPANCAR')),
    CONSTRAINT marketplace_account_status_ck CHECK (status IN ('ACTIVE','PAUSED','ERROR'))
);
--rollback DROP TABLE ${tenant.schema}.marketplace_account;

--changeset platform:tenant-061-listing
CREATE TABLE ${tenant.schema}.listing (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    part_id        bigint      NOT NULL REFERENCES ${tenant.schema}.part ON DELETE CASCADE,
    account_id     bigint      NOT NULL REFERENCES ${tenant.schema}.marketplace_account,
    external_id    text,
    status         text        NOT NULL DEFAULT 'PENDING',
    error_text     text,
    price_override numeric(14,2),
    published_at   timestamptz,
    last_synced_at timestamptz,
    CONSTRAINT listing_uk UNIQUE (part_id, account_id),
    CONSTRAINT listing_status_ck CHECK (status IN
        ('PENDING','PUBLISHED','REJECTED','REMOVED','ERROR'))
);
-- Экран «что не выгрузилось и почему» — половина обращений в поддержку.
CREATE INDEX listing_problems_ix ON ${tenant.schema}.listing (account_id, status)
    WHERE status IN ('REJECTED','ERROR');
CREATE INDEX listing_part_ix ON ${tenant.schema}.listing (part_id);
--rollback DROP TABLE ${tenant.schema}.listing;

--changeset platform:tenant-062-publication-log
--comment Тела запросов объёмны и ценны только пока свежи: чистка старше 90 дней.
--comment Само тело лежит в S3, в БД только ключ.
CREATE TABLE ${tenant.schema}.publication_log (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id    bigint      NOT NULL REFERENCES ${tenant.schema}.marketplace_account,
    operation     text        NOT NULL,
    http_status   int,
    request_ref   text,
    response_body text,
    item_count    int,
    duration_ms   int,
    is_success    boolean     NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT publication_log_op_ck CHECK (operation IN
        ('FEED_BUILD','SYNC','STATUS_PULL','REVALIDATE'))
);
CREATE INDEX publication_log_account_ix ON ${tenant.schema}.publication_log (account_id, created_at DESC);
CREATE INDEX publication_log_created_ix ON ${tenant.schema}.publication_log (created_at);
--rollback DROP TABLE ${tenant.schema}.publication_log;

--changeset platform:tenant-063-price-rule
CREATE TABLE ${tenant.schema}.price_rule (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id bigint      REFERENCES ${tenant.schema}.marketplace_account,
    name       text        NOT NULL,
    conditions jsonb       NOT NULL DEFAULT '{}',
    action     jsonb       NOT NULL,
    priority   int         NOT NULL DEFAULT 100,
    is_active  boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX price_rule_active_ix ON ${tenant.schema}.price_rule (priority) WHERE is_active;
--rollback DROP TABLE ${tenant.schema}.price_rule;
