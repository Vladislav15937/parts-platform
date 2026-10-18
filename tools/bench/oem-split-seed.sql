-- Добавка к tools/bench/oem-recount-seed.sql для замера деления (задача 0319):
-- строки part_oem, в которых несколько кросс-номеров через разделитель.
-- Накатывается после oem-recount-seed.sql на ту же схему:
--
--   docker exec -i <контейнер> psql -U <роль> -d <база> -v schema=t_0000xx \
--       < tools/bench/oem-split-seed.sql
--
-- Состав (k — номер позиции замера из oem-recount-seed.sql):
--   * у каждой сороковой — строка «KR-k-1, KR-k-2» (запятая с пробелом, как в
--     договоре файла), а за ней ещё один кросс-номер «TL-k»: пересчёт обязан
--     разделить строку и переставить «TL-k» за её части, сохранив порядок;
--   * у каждой четырёхсотой — «KR-k-3;FP-kC\tАНАЛОГ»: вторая часть — повтор
--     основного номера (слита), третья — не номер (отброшена);
--   * у каждой тысячной основной номер «FP-kX, FP-kY» — его ввод не делит, и
--     пересчёт тоже (называет).
-- Потом ANALYZE.

SET search_path = :schema, public;

CREATE TEMP TABLE bench_split AS
SELECT row_number() OVER (ORDER BY id) AS k, id FROM part WHERE title LIKE 'Деталь замера %';

INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
SELECT id, 'KR-' || k || '-1, KR-' || k || '-2', 'KR' || k || '1KR' || k || '2', false
  FROM bench_split WHERE k % 40 = 0;

INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
SELECT id, 'TL-' || k, 'TL' || k, false
  FROM bench_split WHERE k % 40 = 0;

INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
SELECT id, 'KR-' || k || '-3;FP-' || k || E'C\tАНАЛОГ', 'KR' || k || '3FP' || k || 'C', false
  FROM bench_split WHERE k % 400 = 0;

UPDATE part_oem o
   SET raw_number = 'FP-' || s.k || 'X, FP-' || s.k || 'Y', normalized = 'FP' || s.k || 'XFP' || s.k || 'Y'
  FROM bench_split s
 WHERE o.part_id = s.id AND o.is_primary AND s.k % 1000 = 0;

ANALYZE part_oem;
