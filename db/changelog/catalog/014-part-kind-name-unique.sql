--liquibase formatted sql

--changeset partsflow:catalog-014-part-kind-name-unique runOnChange:false
--comment Уникальность имени эталона и дедупликация синонимов

-- ON CONFLICT DO NOTHING в наполняющих справочник changeset'ах до сих пор
-- был украшением: уникального индекса по имени не было, конфликту неоткуда
-- взяться, и повтор имени завёл бы второй эталон молча. Дальше сопоставление
-- зависело бы от того, какая из двух строк попалась первой, — то есть
-- от случайности, ровно как при синониме, ведущем к двум эталонам.
CREATE UNIQUE INDEX part_kind_name_uq ON catalog.part_kind (lower(btrim(name)));

-- Один и тот же синоним, записанный в массив дважды, — след того же
-- отсутствия проверки: changeset 012 добавлял то, что уже стояло с 011.
-- Сопоставлению это не мешает, а вот проверка «синоним не ведёт к двум
-- эталонам» на таких строках срабатывает впустую.
UPDATE catalog.part_kind k
   SET synonyms = deduped.synonyms
  FROM (SELECT id, array_agg(DISTINCT s ORDER BY s) AS synonyms
          FROM catalog.part_kind, unnest(synonyms) AS s
         GROUP BY id) AS deduped
 WHERE k.id = deduped.id
   AND array_length(k.synonyms, 1) <> array_length(deduped.synonyms, 1);

--rollback DROP INDEX catalog.part_kind_name_uq;
