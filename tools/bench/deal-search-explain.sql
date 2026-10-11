-- Замер поиска по реестру сделок (задача 0098): ветки по отдельности, весь
-- запрос, пустая выдача, и «доказательство» — снять индекс, на который
-- рассчитывает ветка, и увидеть, что время выросло.
--
--   psql -U app -d parts -f tools/bench/deal-search-explain.sql | grep -E "^(===|#####)|Execution|Scan"
--
-- Данные — tools/bench/deal-search-seed.sql. Запросы повторяют
-- SalesService.listDeals; снятие индекса идёт внутри транзакции и откатывается.
SET search_path = t_bench, public;
\echo === B1 deal.number=347
EXPLAIN (ANALYZE, COSTS OFF) SELECT id FROM deal WHERE number = 347;
\echo === B2 customer ILIKE
EXPLAIN (ANALYZE, COSTS OFF) SELECT d2.id FROM deal d2 JOIN customer c2 ON c2.id = d2.customer_id WHERE c2.name ILIKE '%Петров 7%';
\echo === B3 public_code exact 347
EXPLAIN (ANALYZE, COSTS OFF) SELECT i.deal_id FROM deal_item i JOIN part p ON p.id = i.part_id WHERE p.public_code = '347';
\echo === B3 public_code ILIKE
EXPLAIN (ANALYZE, COSTS OFF) SELECT i.deal_id FROM deal_item i JOIN part p ON p.id = i.part_id WHERE p.public_code ILIKE '%A1B2%';
\echo === B4 part.number=347
EXPLAIN (ANALYZE, COSTS OFF) SELECT i2.deal_id FROM deal_item i2 JOIN part p2 ON p2.id = i2.part_id WHERE p2.number = 347;
\echo === B4 part.number missing
EXPLAIN (ANALYZE, COSTS OFF) SELECT i2.deal_id FROM deal_item i2 JOIN part p2 ON p2.id = i2.part_id WHERE p2.number = 99999999;
\echo === FULL numeric 347 (page)
EXPLAIN (ANALYZE, COSTS OFF) SELECT d.id, d.number FROM deal d LEFT JOIN customer c ON c.id = d.customer_id LEFT JOIN tenant_member m ON m.id = d.manager_id WHERE 1=1 AND d.id IN (SELECT id FROM deal WHERE number = 347 UNION SELECT d2.id FROM deal d2 JOIN customer c2 ON c2.id = d2.customer_id WHERE c2.name ILIKE '%347%' UNION SELECT i.deal_id FROM deal_item i JOIN part p ON p.id = i.part_id WHERE p.public_code = '347' UNION SELECT i2.deal_id FROM deal_item i2 JOIN part p2 ON p2.id = i2.part_id WHERE p2.number = 347) ORDER BY d.id DESC LIMIT 50;
\echo === FULL numeric MISSING 99999999 (page)
EXPLAIN (ANALYZE, COSTS OFF) SELECT d.id, d.number FROM deal d LEFT JOIN customer c ON c.id = d.customer_id LEFT JOIN tenant_member m ON m.id = d.manager_id WHERE 1=1 AND d.id IN (SELECT id FROM deal WHERE number = 99999999 UNION SELECT d2.id FROM deal d2 JOIN customer c2 ON c2.id = d2.customer_id WHERE c2.name ILIKE '%99999999%' UNION SELECT i.deal_id FROM deal_item i JOIN part p ON p.id = i.part_id WHERE p.public_code = '99999999' UNION SELECT i2.deal_id FROM deal_item i2 JOIN part p2 ON p2.id = i2.part_id WHERE p2.number = 99999999) ORDER BY d.id DESC LIMIT 50;
\echo === FULL text MISSING (page)
EXPLAIN (ANALYZE, COSTS OFF) SELECT d.id, d.number FROM deal d LEFT JOIN customer c ON c.id = d.customer_id LEFT JOIN tenant_member m ON m.id = d.manager_id WHERE 1=1 AND d.id IN (SELECT d2.id FROM deal d2 JOIN customer c2 ON c2.id = d2.customer_id WHERE c2.name ILIKE '%Яяяя%' UNION SELECT i.deal_id FROM deal_item i JOIN part p ON p.id = i.part_id WHERE p.public_code ILIKE '%Яяяя%') ORDER BY d.id DESC LIMIT 50;

\echo ##### DROP part_number_uk
BEGIN;
DROP INDEX part_number_uk;
ANALYZE part;
EXPLAIN (ANALYZE, COSTS OFF) SELECT i2.deal_id FROM deal_item i2 JOIN part p2 ON p2.id = i2.part_id WHERE p2.number = 347;
EXPLAIN (ANALYZE, COSTS OFF) SELECT i2.deal_id FROM deal_item i2 JOIN part p2 ON p2.id = i2.part_id WHERE p2.number = 99999999;
EXPLAIN (ANALYZE, COSTS OFF) SELECT d.id FROM deal d WHERE d.id IN (SELECT id FROM deal WHERE number = 347 UNION SELECT d2.id FROM deal d2 JOIN customer c2 ON c2.id = d2.customer_id WHERE c2.name ILIKE '%347%' UNION SELECT i.deal_id FROM deal_item i JOIN part p ON p.id = i.part_id WHERE p.public_code = '347' UNION SELECT i2.deal_id FROM deal_item i2 JOIN part p2 ON p2.id = i2.part_id WHERE p2.number = 347) ORDER BY d.id DESC LIMIT 50;
ROLLBACK;
\echo ##### DROP deal_item_part_ix
BEGIN;
DROP INDEX deal_item_part_ix;
ANALYZE deal_item;
EXPLAIN (ANALYZE, COSTS OFF) SELECT i2.deal_id FROM deal_item i2 JOIN part p2 ON p2.id = i2.part_id WHERE p2.number = 347;
ROLLBACK;
\echo ##### DROP part_code_trgm (ветка 3, вхождение)
BEGIN;
DROP INDEX part_code_trgm;
EXPLAIN (ANALYZE, COSTS OFF) SELECT i.deal_id FROM deal_item i JOIN part p ON p.id = i.part_id WHERE p.public_code ILIKE '%A1B2%';
ROLLBACK;
\echo ##### DROP customer_name_trgm (ветка 2)
BEGIN;
DROP INDEX customer_name_trgm;
EXPLAIN (ANALYZE, COSTS OFF) SELECT d2.id FROM deal d2 JOIN customer c2 ON c2.id = d2.customer_id WHERE c2.name ILIKE '%Петров 7%';
ROLLBACK;
