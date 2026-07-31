--liquibase formatted sql

--changeset platform:tenant-280-feed-lists
--comment Списки в отборе выгрузки: виды деталей и марки машин.
--comment
--comment Каждый список работает в одну из двух сторон — «только эти»
--comment или «все, кроме этих», — потому что клиент хочет и того и другого:
--comment «на этот прайс только Тойоту» и «двигатели сюда не выгружать».
--comment Двумя отдельными списками это было бы двумя способами сказать одно
--comment и то же и разошлось бы при первом же редактировании.
--comment
--comment Производителя (part.manufacturer) в отборе нет намеренно: это
--comment свободный текст без справочника — у живого клиента он не заполнен
--comment ни разу из двухсот позиций, — и список по нему собрал бы «Тойота»,
--comment «тойота» и «Toyota» как три разных значения. Ровно та беда, от которой
--comment избавляет справочник наименований.
ALTER TABLE ${tenant.schema}.marketplace_account
    ADD COLUMN kind_ids        bigint[],
    ADD COLUMN kinds_excluded  boolean NOT NULL DEFAULT false,
    ADD COLUMN brand_ids       bigint[],
    ADD COLUMN brands_excluded boolean NOT NULL DEFAULT false;
--rollback ALTER TABLE ${tenant.schema}.marketplace_account DROP COLUMN brands_excluded, DROP COLUMN brand_ids, DROP COLUMN kinds_excluded, DROP COLUMN kind_ids;
