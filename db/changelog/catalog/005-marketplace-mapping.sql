--liquibase formatted sql

--changeset platform:catalog-040-marketplace-mapping
--comment Соответствие узлов категориям и параметрам площадки. Самая кропотливая
--comment часть выгрузок и главный источник отказов модерации, поэтому живёт
--comment в данных, а не в коде: правка требований Авито не должна означать релиз.
CREATE TABLE catalog.marketplace_mapping (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    marketplace       text   NOT NULL,
    part_category_id  bigint NOT NULL REFERENCES catalog.part_category,
    external_category text   NOT NULL,
    params            jsonb  NOT NULL DEFAULT '{}',
    valid_from        timestamptz NOT NULL DEFAULT now(),
    valid_to          timestamptz,
    CONSTRAINT marketplace_mapping_uk UNIQUE (marketplace, part_category_id, valid_from),
    CONSTRAINT marketplace_mapping_code_ck CHECK (marketplace IN ('AVITO','DROM','JAPANCAR'))
);
CREATE INDEX marketplace_mapping_lookup_ix
    ON catalog.marketplace_mapping (marketplace, part_category_id)
    WHERE valid_to IS NULL;
--rollback DROP TABLE catalog.marketplace_mapping;
