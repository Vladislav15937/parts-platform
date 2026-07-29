--liquibase formatted sql

--changeset platform:catalog-010-brand
CREATE TABLE catalog.brand (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text        NOT NULL,
    slug        text        NOT NULL,
    country     text,
    is_active   boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT brand_slug_uk UNIQUE (slug)
);
--rollback DROP TABLE catalog.brand;

--changeset platform:catalog-011-model
CREATE TABLE catalog.model (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    brand_id    bigint      NOT NULL REFERENCES catalog.brand,
    name        text        NOT NULL,
    slug        text        NOT NULL,
    year_from   smallint,
    year_to     smallint,
    is_active   boolean     NOT NULL DEFAULT true,
    CONSTRAINT model_slug_uk UNIQUE (brand_id, slug),
    CONSTRAINT model_years_ck CHECK (year_to IS NULL OR year_from IS NULL OR year_to >= year_from)
);
CREATE INDEX model_brand_ix ON catalog.model (brand_id);
--rollback DROP TABLE catalog.model;

--changeset platform:catalog-012-generation
CREATE TABLE catalog.generation (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_id      bigint      NOT NULL REFERENCES catalog.model,
    name          text        NOT NULL,
    code          text,
    year_from     smallint,
    year_to       smallint,
    is_restyling  boolean     NOT NULL DEFAULT false,
    CONSTRAINT generation_years_ck CHECK (year_to IS NULL OR year_from IS NULL OR year_to >= year_from)
);
CREATE INDEX generation_model_ix ON catalog.generation (model_id);
--rollback DROP TABLE catalog.generation;

--changeset platform:catalog-013-body-type
CREATE TABLE catalog.body_type (
    id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code  text NOT NULL UNIQUE,
    name  text NOT NULL
);
--rollback DROP TABLE catalog.body_type;

--changeset platform:catalog-014-modification
--comment Конкретное сочетание двигателя, кузова, привода и КПП.
CREATE TABLE catalog.modification (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    generation_id  bigint  NOT NULL REFERENCES catalog.generation,
    body_type_id   bigint  REFERENCES catalog.body_type,
    name           text    NOT NULL,
    engine_code    text,
    engine_volume  numeric(4,1),
    power_hp       smallint,
    fuel_type      text,
    transmission   text,
    drive_type     text,
    year_from      smallint,
    year_to        smallint
);
CREATE INDEX modification_generation_ix ON catalog.modification (generation_id);
CREATE INDEX modification_engine_ix ON catalog.modification (engine_code) WHERE engine_code IS NOT NULL;
--rollback DROP TABLE catalog.modification;
