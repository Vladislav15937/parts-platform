--liquibase formatted sql

--changeset platform:catalog-001-extensions
--comment Расширения. В управляемых облаках могут требовать роли с повышенными правами.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
--rollback DROP EXTENSION IF EXISTS pgcrypto;
--rollback DROP EXTENSION IF EXISTS ltree;
--rollback DROP EXTENSION IF EXISTS pg_trgm;

--changeset platform:catalog-002-schema
CREATE SCHEMA IF NOT EXISTS catalog;
--rollback DROP SCHEMA IF EXISTS catalog CASCADE;

--changeset platform:catalog-003-normalize-oem splitStatements:false runOnChange:true
--comment Нормализация номера детали. IMMUTABLE обязательно: по функции строится индекс.
CREATE OR REPLACE FUNCTION catalog.normalize_oem(p_number text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE
    STRICT
    PARALLEL SAFE
AS $fn$
    SELECT upper(regexp_replace(p_number, '[^A-Za-z0-9]', '', 'g'));
$fn$;
--rollback DROP FUNCTION IF EXISTS catalog.normalize_oem(text);

--changeset platform:catalog-004-join-text splitStatements:false runOnChange:true
--comment Обёртка над array_to_string: штатная функция помечена STABLE и потому
--comment недопустима в генерируемой колонке. Для text[] операция детерминирована.
CREATE OR REPLACE FUNCTION catalog.join_text(p_items text[], p_sep text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE
    STRICT
    PARALLEL SAFE
AS $fn$
    SELECT array_to_string(p_items, p_sep);
$fn$;
--rollback DROP FUNCTION IF EXISTS catalog.join_text(text[], text);
