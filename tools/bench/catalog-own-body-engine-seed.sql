-- Данные для замера колонок «Кузов» и «Двигатель» у позиции без донора
-- (задача 0296). Идёт после catalog-own-vehicle-seed.sql, по той же схеме:
--
--   docker exec -i <контейнер> psql -U app -d parts -v schema=t_1000002 \
--       < tools/bench/catalog-own-vehicle-seed.sql
--   docker exec -i <контейнер> psql -U app -d parts -v schema=t_1000002 \
--       < tools/bench/catalog-own-body-engine-seed.sql
--
-- Худший случай: у каждой строки применимости есть и кузова в примечании,
-- и «Другие кузова», и двигатели — на живом файле двигатели есть у 2 687
-- блоков из 2 875, но строку получает только машина, единственная в блоке.
-- Коды названы рядом «120», как и наименования заготовки («… 120»), —
-- значит сужение до ряда работает на каждой строке, а не пропускается.
-- Строки с поколением и без — как в заготовке; подтверждённые не трогаются
-- ничем, кроме этого файла.

SET search_path = :schema, public;

UPDATE part_applicability
   SET note = 'Кузова: GRJ120, GRJ120W, KDJ120W, KDJ121W, TRJ120W, RZJ120W',
       other_bodies = 'GRJ125W, KDJ125W, LJ125, VZJ125',
       engines = '1GRFE, 1KDFTV, 2TRFE, 3RZFE';

ANALYZE part_applicability;

SELECT count(*) AS rows,
       count(other_bodies) AS with_other_bodies,
       count(engines) AS with_engines
  FROM part_applicability;
