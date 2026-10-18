-- Замер задачи 0329: сколько стоит основной номер производителя в длинных
-- ответах — лист обхода (50 300 строк), коды склада для сканера (50 300)
-- и файл «Проданных позиций» (50 300 строк сделок). Остальные места —
-- страница по ссылке, печать, расхождения, перевозка, страницы отчётов —
-- это десятки строк или одна страница: там подзапрос на строку незаметен.
--
--   docker run -d --name bench0329 -e POSTGRES_PASSWORD=x postgres:16-alpine
--   docker exec -i bench0329 psql -U postgres < tools/bench/oem-in-documents-bench.sql
--
-- Таблицы — урезанные копии схемы арендатора: только колонки и индексы,
-- которые участвуют в запросах (у part_oem — те же индексы, что в tenant/004:
-- подзапрос идёт по part_oem_primary_uk). Номера — как у замера 0327: у 90 %
-- позиций основной, у всех кросс-номер. Запросы — те же тексты, что
-- в InventoryService.lines / warehouseCodes и в export «Проданных позиций»
-- (там — без соединений, не влияющих на подзапрос). «До» и «после» идут
-- вперемешку, по 15 прогонов; печатается медиана, минимум и максимум
-- Execution Time из EXPLAIN ANALYZE.
\set ON_ERROR_STOP on
DROP SCHEMA IF EXISTS b CASCADE;
CREATE SCHEMA b;
SET search_path = b;
CREATE TABLE part (id bigserial PRIMARY KEY, number bigint, title text, condition text,
                   public_code text, barcode text);
CREATE TABLE part_oem (id bigserial PRIMARY KEY, part_id bigint NOT NULL REFERENCES part,
                       raw_number text NOT NULL, normalized text NOT NULL,
                       is_primary boolean NOT NULL DEFAULT false,
                       CONSTRAINT part_oem_uk UNIQUE (part_id, normalized));
CREATE INDEX part_oem_normalized_ix ON part_oem (normalized);
CREATE UNIQUE INDEX part_oem_primary_uk ON part_oem (part_id) WHERE is_primary;
CREATE TABLE storage_cell (id bigserial PRIMARY KEY, code text);
CREATE TABLE part_stock (part_id bigint, warehouse_id bigint, cell_id bigint, qty numeric);
CREATE INDEX part_stock_wh_ix ON part_stock (warehouse_id);
CREATE TABLE inventory_line (id bigserial PRIMARY KEY, session_id bigint, part_id bigint,
                             cell_id bigint, qty_expected numeric, qty_counted numeric);
CREATE INDEX inventory_line_session_ix ON inventory_line (session_id);
CREATE TABLE deal_item (id bigserial PRIMARY KEY, part_id bigint, price numeric,
                        quantity numeric, closed_at timestamptz);

INSERT INTO storage_cell (code) SELECT 'A-' || g FROM generate_series(1, 200) g;
INSERT INTO part (number, title, condition, public_code)
SELECT g, 'Фара модель' || (g % 100), 'USED', upper(md5(g::text))
  FROM generate_series(1, 50300) g;
INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
SELECT id, '84140-' || id, '84140' || id, true FROM part WHERE id % 10 <> 0;
INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
SELECT id, '81550-' || id, '81550' || id, false FROM part;
INSERT INTO part_stock SELECT id, 1, 1 + id % 200, 1 FROM part;
INSERT INTO inventory_line (session_id, part_id, cell_id, qty_expected)
SELECT 1, id, 1 + id % 200, 1 FROM part;
INSERT INTO deal_item (part_id, price, quantity, closed_at)
SELECT id, 1000, 1, now() - (id || ' minutes')::interval FROM part;
ANALYZE;

CREATE FUNCTION ms(q text) RETURNS numeric LANGUAGE plpgsql AS $$
DECLARE plan json;
BEGIN
  EXECUTE 'EXPLAIN (ANALYZE, FORMAT JSON) ' || q INTO plan;
  RETURN round((plan -> 0 ->> 'Execution Time')::numeric, 1);
END $$;

CREATE TABLE runs (query text, variant text, ms numeric);
DO $$
DECLARE
  oem text := ', (SELECT o.raw_number FROM part_oem o WHERE o.part_id = p.id AND o.is_primary) AS primary_oem';
  q text[] := ARRAY[
    'лист обхода', 'SELECT l.part_id, l.qty_expected, l.qty_counted, l.cell_id, p.number, p.title, p.condition, c.code AS cell_code%s FROM inventory_line l JOIN part p ON p.id = l.part_id LEFT JOIN storage_cell c ON c.id = l.cell_id WHERE l.session_id = 1 ORDER BY c.code NULLS LAST, p.title',
    'коды склада', 'SELECT p.id AS part_id, p.number, p.title, p.condition, p.public_code, p.barcode, ps.cell_id, c.code AS cell_code%s FROM part_stock ps JOIN part p ON p.id = ps.part_id LEFT JOIN storage_cell c ON c.id = ps.cell_id WHERE ps.warehouse_id = 1 AND ps.qty > 0',
    'файл проданных', 'SELECT di.closed_at, p.number, p.public_code, p.title, p.condition%s, di.price, di.quantity FROM deal_item di JOIN part p ON p.id = di.part_id ORDER BY di.closed_at DESC, di.id DESC'];
  i int; k int;
BEGIN
  FOR k IN 1..3 LOOP  -- прогрев
    PERFORM b.ms(format(q[2 * k], ''));
    PERFORM b.ms(format(q[2 * k], oem));
  END LOOP;
  FOR i IN 1..15 LOOP
    FOR k IN 1..3 LOOP
      INSERT INTO b.runs VALUES (q[2 * k - 1], 'до', b.ms(format(q[2 * k], '')));
      INSERT INTO b.runs VALUES (q[2 * k - 1], 'после', b.ms(format(q[2 * k], oem)));
    END LOOP;
  END LOOP;
END $$;

SELECT query, variant,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY ms) AS median_ms,
       min(ms) AS min_ms, max(ms) AS max_ms
  FROM runs GROUP BY query, variant ORDER BY query, variant DESC;
