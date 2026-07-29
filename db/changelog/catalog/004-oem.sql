--liquibase formatted sql

--changeset platform:catalog-030-oem-number
CREATE TABLE catalog.oem_number (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    brand_id     bigint REFERENCES catalog.brand,
    raw_number   text NOT NULL,
    normalized   text NOT NULL,
    part_kind_id bigint REFERENCES catalog.part_kind,
    CONSTRAINT oem_number_uk UNIQUE (normalized, brand_id)
);
CREATE INDEX oem_number_normalized_ix ON catalog.oem_number (normalized);
CREATE INDEX oem_number_trgm ON catalog.oem_number USING gin (normalized gin_trgm_ops);
--rollback DROP TABLE catalog.oem_number;

--changeset platform:catalog-031-oem-cross
--comment Кроссы — ненаправленные пары. Порядок нормализован, иначе появятся
--comment дубли A-B и B-A с расходящимися данными.
CREATE TABLE catalog.oem_cross (
    oem_id_low   bigint NOT NULL REFERENCES catalog.oem_number,
    oem_id_high  bigint NOT NULL REFERENCES catalog.oem_number,
    source       text,
    PRIMARY KEY (oem_id_low, oem_id_high),
    CONSTRAINT oem_cross_order_ck CHECK (oem_id_low < oem_id_high)
);
CREATE INDEX oem_cross_high_ix ON catalog.oem_cross (oem_id_high);
--rollback DROP TABLE catalog.oem_cross;
