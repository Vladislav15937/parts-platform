--liquibase formatted sql

--changeset platform:tenant-034-applicability-unique
--comment Применимость не дублируется при повторе

-- Ограничение из 004 не работает там, где оно нужнее всего. В Postgres
-- NULL не равен NULL, поэтому UNIQUE (part_id, brand_id, model_id,
-- generation_id, modification_id) пропускает любые строки с пустым
-- поколением — а такие и есть все, что проставлены разбором заголовков
-- и правкой из карточки: поколение там неизвестно.
--
-- Поймано повтором: разбор заголовков склада дал 44 067 строк, второй
-- запуск — 88 134. ON CONFLICT DO NOTHING при этом отработал честно,
-- ему просто не на что было среагировать.
DELETE FROM ${tenant.schema}.part_applicability a
 WHERE a.id > (SELECT min(b.id) FROM ${tenant.schema}.part_applicability b
                WHERE b.part_id = a.part_id
                  AND b.brand_id = a.brand_id
                  AND b.model_id IS NOT DISTINCT FROM a.model_id
                  AND b.generation_id IS NOT DISTINCT FROM a.generation_id
                  AND b.modification_id IS NOT DISTINCT FROM a.modification_id);

-- NULLS NOT DISTINCT — с Postgres 15. До него пришлось бы городить
-- частичные индексы на каждое сочетание пустых полей.
CREATE UNIQUE INDEX part_applicability_uq
    ON ${tenant.schema}.part_applicability (part_id, brand_id, model_id,
                                            generation_id, modification_id)
    NULLS NOT DISTINCT;

--rollback DROP INDEX ${tenant.schema}.part_applicability_uq;
