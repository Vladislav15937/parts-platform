--liquibase formatted sql

--changeset partsflow:catalog-016-roof-molding-synonym runOnChange:false
--comment «Молдинг крыши» принадлежит своему эталону, а не общему «Молдингу»

-- Вторая порция справочника отдала «молдинг крыши» общему «Молдингу»,
-- третья завела под него отдельный эталон — и синоним стал вести к двум.
-- Тогда сопоставление зависит от того, какая строка попалась первой,
-- то есть от случайности; стережёт это PartKindSeedTest.synonymsAreUnique.
-- Частное побеждает общее: молдинг крыши — это молдинг крыши.
UPDATE catalog.part_kind
   SET synonyms = array_remove(array_remove(synonyms, 'молдинг крыши'), 'молдинг на крышу')
 WHERE name = 'Молдинг';

--rollback UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['молдинг крыши', 'молдинг на крышу'] WHERE name = 'Молдинг';
