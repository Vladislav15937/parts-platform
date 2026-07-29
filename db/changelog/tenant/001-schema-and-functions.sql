--liquibase formatted sql

-- Все объекты квалифицированы ${tenant.schema} намеренно. Полагаться на
-- search_path в миграциях нельзя: Liquibase не гарантирует границы транзакций
-- между changeset'ами, а неявная схема — самый дешёвый способ однажды создать
-- таблицу не тому арендатору.

--changeset platform:tenant-001-schema
CREATE SCHEMA IF NOT EXISTS ${tenant.schema};
--rollback DROP SCHEMA IF EXISTS ${tenant.schema} CASCADE;

--changeset platform:tenant-002-touch-updated-at splitStatements:false runOnChange:true
CREATE OR REPLACE FUNCTION ${tenant.schema}.touch_updated_at()
    RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END $fn$;
--rollback DROP FUNCTION IF EXISTS ${tenant.schema}.touch_updated_at();

--changeset platform:tenant-003-gen-public-code splitStatements:false runOnChange:true
--comment Неугадываемый публичный код для URL магазина, этикеток и объявлений.
--comment Последовательный id светить нельзя: по номеру объявления конкурент
--comment посчитает клиенту весь склад и темп поступлений.
--comment Имена квалифицированы: тело разрешается в рантайме, когда search_path
--comment приложения может не содержать public.
CREATE OR REPLACE FUNCTION ${tenant.schema}.gen_public_code()
    RETURNS text LANGUAGE sql VOLATILE AS $fn$
    SELECT upper(encode(public.gen_random_bytes(6), 'hex'));
$fn$;
--rollback DROP FUNCTION IF EXISTS ${tenant.schema}.gen_public_code();
