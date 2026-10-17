-- Данные для замера пересчёта номеров производителя (задача 0317): склад как
-- у переехавшего клиента — 35 841 позиция, у каждой номер производителя и в
-- среднем полтора кросс-номера (~89 600 строк part_oem, как в замере 0306), —
-- и номера в том виде, в каком их оставила запись до 0309.
--
--   docker exec -i <контейнер> psql -U <роль> -d <база> -v schema=t_0000xx \
--       < tools/bench/oem-recount-seed.sql
--
-- Схема — заведённая провижинингом и пустая (компания для замера, не клиент).
-- Состав:
--   * у каждой десятой позиции основной номер кончается русской «С», а
--     normalized — без неё, как приводило до 0309 («8518100С» → «8518100»);
--   * у каждой пятидесятой — то же, и рядом кросс-номер того же номера
--     латиницей: два написания, которые пересчёт сольёт;
--   * у каждой тридцатой кросс-номер с неразрывным пробелом в начале
--     и табуляцией в конце;
--   * у каждой сотой кросс-номер «АНАЛОГ» с пустым normalized (так его
--     оставляла генерируемая колонка);
--   * остальное — латиницей и уже по правилу: пересчёт его читает и не трогает.
-- Потом ANALYZE.

SET search_path = :schema, public;

INSERT INTO part (title, price, status, quantity, qty_on_hand)
SELECT 'Деталь замера ' || k, 1000 + k % 500, 'IN_STOCK', 1, 1
  FROM generate_series(1, 35841) k;

CREATE TEMP TABLE bench_part AS
SELECT row_number() OVER (ORDER BY id) AS k, id FROM part WHERE title LIKE 'Деталь замера %';

-- Основной номер.
INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
SELECT id,
       CASE WHEN k % 10 = 0 THEN 'FP-' || k || E'С' ELSE 'FP-' || k || 'X' END,
       CASE WHEN k % 10 = 0 THEN 'FP' || k ELSE 'FP' || k || 'X' END,
       true
  FROM bench_part;

-- Два написания одного номера: латинский кросс-номер рядом с русской основной.
INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
SELECT id, 'FP-' || k || 'C', 'FP' || k || 'C', false
  FROM bench_part WHERE k % 50 = 0;

-- Кросс-номер с невидимыми знаками по краям.
INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
SELECT id, E' ' || 'AB-' || k || E'\t', 'AB' || k, false
  FROM bench_part WHERE k % 30 = 0;

-- «АНАЛОГ».
INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
SELECT id, E'АНАЛОГ', '', false
  FROM bench_part WHERE k % 100 = 0;

-- Остальные кросс-номера — уже по правилу; в среднем полтора на позицию вместе
-- с теми, что выше.
INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
SELECT id, 'CR-' || k || '-' || n, 'CR' || k || n, false
  FROM bench_part, generate_series(1, 2) n
 WHERE (k + n) % 3 <> 0;

ANALYZE part;
ANALYZE part_oem;
