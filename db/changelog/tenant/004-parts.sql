--liquibase formatted sql

--changeset platform:tenant-030-part
--comment Запчасть — это партия, а не всегда штука: разборщик продаёт и двери,
--comment и болты пачками. Уникальная деталь — просто партия с количеством 1.
CREATE TABLE ${tenant.schema}.part (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_code      text        NOT NULL DEFAULT ${tenant.schema}.gen_public_code(),
    donor_id         bigint      REFERENCES ${tenant.schema}.donor,
    part_kind_id     bigint,
    category_id      bigint      NOT NULL,
    title            text        NOT NULL,
    description      text,
    side             text,
    condition        text        NOT NULL DEFAULT 'USED',
    quality_grade    text,
    marking          text,

    quantity         numeric(12,3) NOT NULL DEFAULT 1,
    unit             text          NOT NULL DEFAULT 'PCS',
    qty_on_hand      numeric(12,3) NOT NULL DEFAULT 0,

    price            numeric(14,2),
    min_price        numeric(14,2),
    cost_price       numeric(14,2),
    cost_allocation_method text,

    status           text        NOT NULL DEFAULT 'DRAFT',
    storage_cell_id  bigint      REFERENCES ${tenant.schema}.storage_cell,
    barcode          text,
    weight_kg        numeric(10,3),
    attributes       jsonb       NOT NULL DEFAULT '{}',
    is_published     boolean     NOT NULL DEFAULT false,

    created_by       bigint      REFERENCES ${tenant.schema}.tenant_member,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),

    search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('russian',
            coalesce(title,'') || ' ' || coalesce(description,'') || ' ' || coalesce(marking,''))
    ) STORED,

    CONSTRAINT part_public_code_uk UNIQUE (public_code),
    CONSTRAINT part_barcode_uk UNIQUE (barcode),
    CONSTRAINT part_status_ck CHECK (status IN
        ('DRAFT','IN_STOCK','RESERVED','SOLD','WRITTEN_OFF')),
    CONSTRAINT part_condition_ck CHECK (condition IN ('NEW','USED','REFURBISHED')),
    CONSTRAINT part_alloc_ck CHECK (cost_allocation_method IS NULL OR cost_allocation_method IN
        ('BY_PRICE_SHARE','EQUAL','MANUAL','NONE')),
    CONSTRAINT part_quantity_ck CHECK (quantity > 0),
    CONSTRAINT part_price_ck CHECK (price IS NULL OR price >= 0)
);

-- Частичный: главный экран — склад, проданное листают редко.
CREATE INDEX part_instock_ix ON ${tenant.schema}.part (category_id, updated_at DESC)
    WHERE status = 'IN_STOCK';
CREATE INDEX part_search_gin ON ${tenant.schema}.part USING gin (search_vector);
CREATE INDEX part_title_trgm ON ${tenant.schema}.part USING gin (title gin_trgm_ops);
CREATE INDEX part_donor_ix ON ${tenant.schema}.part (donor_id) WHERE donor_id IS NOT NULL;
CREATE INDEX part_cell_ix ON ${tenant.schema}.part (storage_cell_id) WHERE storage_cell_id IS NOT NULL;
CREATE INDEX part_status_ix ON ${tenant.schema}.part (status);
CREATE INDEX part_published_ix ON ${tenant.schema}.part (is_published, updated_at DESC) WHERE is_published;

CREATE TRIGGER part_touch BEFORE UPDATE ON ${tenant.schema}.part
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.touch_updated_at();
--rollback DROP TABLE ${tenant.schema}.part;

--changeset platform:tenant-031-part-oem
CREATE TABLE ${tenant.schema}.part_oem (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    part_id     bigint  NOT NULL REFERENCES ${tenant.schema}.part ON DELETE CASCADE,
    raw_number  text    NOT NULL,
    normalized  text    NOT NULL GENERATED ALWAYS AS
                        (catalog.normalize_oem(raw_number)) STORED,
    is_primary  boolean NOT NULL DEFAULT false,
    CONSTRAINT part_oem_uk UNIQUE (part_id, normalized)
);
CREATE INDEX part_oem_normalized_ix ON ${tenant.schema}.part_oem (normalized);
CREATE INDEX part_oem_trgm ON ${tenant.schema}.part_oem USING gin (normalized gin_trgm_ops);
CREATE UNIQUE INDEX part_oem_primary_uk ON ${tenant.schema}.part_oem (part_id) WHERE is_primary;
--rollback DROP TABLE ${tenant.schema}.part_oem;

--changeset platform:tenant-032-part-applicability
--comment Применимость на любом уровне точности: от «на любой Nissan» до
--comment «Camry V50 рестайлинг, 2AZ-FE». Заполненность полей и есть точность.
--comment is_verified отделяет подтверждённое человеком от подставленного
--comment автоматически — понадобится, когда появится распознавание по фото.
CREATE TABLE ${tenant.schema}.part_applicability (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    part_id         bigint  NOT NULL REFERENCES ${tenant.schema}.part ON DELETE CASCADE,
    brand_id        bigint  NOT NULL,
    model_id        bigint,
    generation_id   bigint,
    modification_id bigint,
    year_from       smallint,
    year_to         smallint,
    is_verified     boolean NOT NULL DEFAULT false,
    note            text,
    CONSTRAINT part_applicability_uk
        UNIQUE (part_id, brand_id, model_id, generation_id, modification_id)
);
-- Главный поисковый сценарий продавца: «что есть на Camry V50».
CREATE INDEX part_applicability_vehicle_ix
    ON ${tenant.schema}.part_applicability (brand_id, model_id, generation_id);
CREATE INDEX part_applicability_part_ix ON ${tenant.schema}.part_applicability (part_id);
--rollback DROP TABLE ${tenant.schema}.part_applicability;

--changeset platform:tenant-033-part-photo
CREATE TABLE ${tenant.schema}.part_photo (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    part_id      bigint      NOT NULL REFERENCES ${tenant.schema}.part ON DELETE CASCADE,
    s3_key       text        NOT NULL,
    variants     jsonb       NOT NULL DEFAULT '{}',
    sort_order   int         NOT NULL DEFAULT 0,
    is_main      boolean     NOT NULL DEFAULT false,
    width        int,
    height       int,
    bytes        bigint,
    status       text        NOT NULL DEFAULT 'UPLOADED',
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT part_photo_status_ck CHECK (status IN ('UPLOADED','PROCESSED','FAILED'))
);
CREATE INDEX part_photo_part_ix ON ${tenant.schema}.part_photo (part_id, sort_order);
CREATE UNIQUE INDEX part_photo_main_uk ON ${tenant.schema}.part_photo (part_id) WHERE is_main;
--rollback DROP TABLE ${tenant.schema}.part_photo;
