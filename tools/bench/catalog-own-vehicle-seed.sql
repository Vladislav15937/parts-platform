-- Данные для замера витрины склада со своей машиной из применимости
-- (задача 0290): склад как у переехавшего с Bazon клиента — 35 841 позиция,
-- 26 424 с донором и 9 417 без донора, у которых машины названы
-- в наименовании и лежат в part_applicability (~4,6 строки на позицию,
-- у живого склада 43 261 строка на 9 157 позиций).
--
--   docker exec -i <контейнер> psql -U app -d parts -v schema=t_1000002 \
--       < tools/bench/catalog-own-vehicle-seed.sql
--
-- Схема — заведённая провижинингом и пустая (компания для замера, не клиент).
-- Состав ничего не берёт с живого склада:
--   * 440 машин-доноров по 120 моделям справочника (марка, модель, поколение,
--     кузов, двигатель, год);
--   * 26 424 позиции с донором — по 60 на машину;
--   * 9 417 позиций без донора: «<деталь> <Марка> <Модель> 120», и в
--     применимости у каждой — своя машина (у трети одним поколением, у трети
--     двумя, у трети без поколения) плюс три чужие модели той же марки;
--     у каждой двадцатой — подтверждена человеком.
-- Потом ANALYZE: без статистики планы не те, что у живой базы.

SET search_path = :schema, public;

CREATE TEMP TABLE bench_model AS
SELECT row_number() OVER (ORDER BY m.id) AS n, m.id AS model_id, m.brand_id,
       b.name AS brand, m.name AS model
  FROM catalog.model m JOIN catalog.brand b ON b.id = m.brand_id
 WHERE b.name IN ('Toyota', 'Nissan', 'Honda', 'Mazda', 'Mitsubishi', 'Lexus', 'Subaru')
   AND EXISTS (SELECT 1 FROM catalog.generation g WHERE g.model_id = m.id)
 ORDER BY m.id
 LIMIT 120;

INSERT INTO donor (brand_id, model_id, generation_id, body_code, engine_code, year, status)
SELECT bm.brand_id, bm.model_id,
       (SELECT min(g.id) FROM catalog.generation g WHERE g.model_id = bm.model_id),
       'KUZ' || (k % 7), 'DV' || (k % 5), 1995 + (k % 25), 'DISMANTLED'
  FROM generate_series(1, 440) k
  JOIN bench_model bm ON bm.n = 1 + (k % 120);

INSERT INTO part (title, price, status, donor_id, quantity, qty_on_hand)
SELECT (ARRAY['Фара', 'Бампер', 'Дверь', 'Капот', 'Крыло', 'Стекло', 'Зеркало',
              'Радиатор', 'Фонарь', 'Стартер'])[1 + (g % 10)] || ' ' || bm.brand || ' '
           || bm.model || ' ' || g,
       500 + (g * 37) % 30000, 'IN_STOCK',
       (SELECT array_agg(id ORDER BY id) FROM donor)[1 + (g % 440)], 1, 1
  FROM generate_series(1, 26424) g
  JOIN donor d0 ON d0.id = (SELECT array_agg(id ORDER BY id) FROM donor)[1 + (g % 440)]
  JOIN bench_model bm ON bm.model_id = d0.model_id;

CREATE TEMP TABLE bench_free AS
SELECT g, bm.n, bm.brand_id, bm.model_id, bm.brand, bm.model
  FROM generate_series(1, 9417) g
  JOIN bench_model bm ON bm.n = 1 + (g % 120);

INSERT INTO part (title, price, status, quantity, qty_on_hand, legacy_code)
SELECT (ARRAY['Карданный вал', 'Суппорт', 'Ступица', 'Рычаг', 'Амортизатор',
              'Полуось', 'Рейка', 'Глушитель'])[1 + (g % 8)] || ' ' || brand || ' ' || model
           || ' 120',
       700 + (g * 41) % 20000, 'IN_STOCK', 1, 1, 'free-' || g
  FROM bench_free;

-- Своя машина: поколение одно, два или ни одного.
INSERT INTO part_applicability (part_id, brand_id, model_id, generation_id, is_verified)
SELECT p.id, f.brand_id, f.model_id, gen.id, f.g % 20 = 0
  FROM bench_free f
  JOIN part p ON p.legacy_code = 'free-' || f.g
  LEFT JOIN LATERAL (SELECT g.id FROM catalog.generation g WHERE g.model_id = f.model_id
                      ORDER BY g.id LIMIT (CASE f.g % 3 WHEN 0 THEN 1 WHEN 1 THEN 2 ELSE 0 END)) gen
       ON true
ON CONFLICT DO NOTHING;
INSERT INTO part_applicability (part_id, brand_id, model_id, is_verified)
SELECT p.id, f.brand_id, f.model_id, false
  FROM bench_free f JOIN part p ON p.legacy_code = 'free-' || f.g
 WHERE f.g % 3 = 2
ON CONFLICT DO NOTHING;
-- Три чужие модели той же марки — то, к чему деталь ещё подходит.
INSERT INTO part_applicability (part_id, brand_id, model_id, is_verified)
SELECT p.id, other.brand_id, other.model_id, false
  FROM bench_free f
  JOIN part p ON p.legacy_code = 'free-' || f.g
  JOIN LATERAL (SELECT bm.brand_id, bm.model_id FROM bench_model bm
                 WHERE bm.brand_id = f.brand_id AND bm.model_id <> f.model_id
                 ORDER BY (bm.n * 7 + f.g) % 120 LIMIT 3) other ON true
ON CONFLICT DO NOTHING;

UPDATE part SET legacy_code = NULL WHERE legacy_code LIKE 'free-%';

ANALYZE;

SELECT (SELECT count(*) FROM part) AS parts,
       (SELECT count(*) FROM part WHERE donor_id IS NULL) AS without_donor,
       (SELECT count(*) FROM part_applicability) AS applicability_rows;
