--liquibase formatted sql

--changeset platform:tenant-210-donor-legacy-code
--comment Номер машины из предыдущей системы. Нужен импорту как естественный
--comment ключ: без него повторный запуск заводит каждую машину второй раз,
--comment а детали к ней не привязываются — они пропускаются по своему
--comment legacy_code. В итоге в базе полсотни машин-призраков без единой
--comment детали, и окупаемость по ним считается чистым убытком.
ALTER TABLE ${tenant.schema}.donor
    ADD COLUMN legacy_code text;

-- Частичный: свои машины заводятся без него, и NULL их не сталкивает.
CREATE UNIQUE INDEX donor_legacy_code_uk ON ${tenant.schema}.donor (legacy_code)
    WHERE legacy_code IS NOT NULL;
--rollback DROP INDEX IF EXISTS ${tenant.schema}.donor_legacy_code_uk; ALTER TABLE ${tenant.schema}.donor DROP COLUMN legacy_code;
