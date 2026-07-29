--liquibase formatted sql

--changeset platform:catalog-020-part-category
--comment Дерево узлов. ltree даёт запросы «всё из подвески» без рекурсии.
CREATE TABLE catalog.part_category (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id  bigint REFERENCES catalog.part_category,
    name       text    NOT NULL,
    slug       text    NOT NULL,
    path       ltree   NOT NULL,
    sort_order int     NOT NULL DEFAULT 0,
    is_active  boolean NOT NULL DEFAULT true,
    CONSTRAINT part_category_path_uk UNIQUE (path)
);
CREATE INDEX part_category_path_gist ON catalog.part_category USING gist (path);
CREATE INDEX part_category_parent_ix ON catalog.part_category (parent_id);
--rollback DROP TABLE catalog.part_category;

--changeset platform:catalog-021-part-kind
--comment Синонимы критичны: покупатель спрашивает «телевизор», имея в виду рамку радиатора.
CREATE TABLE catalog.part_kind (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id  bigint  NOT NULL REFERENCES catalog.part_category,
    name         text    NOT NULL,
    synonyms     text[]  NOT NULL DEFAULT '{}',
    has_side     boolean NOT NULL DEFAULT false,
    is_active    boolean NOT NULL DEFAULT true,
    search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('russian', name || ' ' || catalog.join_text(synonyms, ' '))
    ) STORED
);
CREATE INDEX part_kind_category_ix ON catalog.part_kind (category_id);
CREATE INDEX part_kind_search_gin ON catalog.part_kind USING gin (search_vector);
CREATE INDEX part_kind_name_trgm ON catalog.part_kind USING gin (name gin_trgm_ops);
--rollback DROP TABLE catalog.part_kind;
