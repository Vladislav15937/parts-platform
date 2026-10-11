-- Данные для замера поиска по реестру сделок (задача 0098): 35 841 позиция,
-- 8 000 клиентов, 12 000 сделок, 24 000 строк сделок.
--
--   psql -U app -d parts -f tools/bench/deal-search-seed.sql
--
-- Схема t_bench накатана целиком (db/verify.sh или провижининг) и пуста.
-- Замер — tools/bench/deal-search-explain.sql. Контейнер Postgres свой, не общий.
SET search_path = t_bench, public;
INSERT INTO supply (kind, number, supplier_name, arrived_on, status) VALUES ('CONTAINER','B1','x',current_date,'ARRIVED');
INSERT INTO part (category_id, title, price, status, supply_id)
SELECT 1, 'Фара ' || g || ' модель' || (g % 100), 1000 + g, 'IN_STOCK', (SELECT min(id) FROM supply)
FROM generate_series(1,35841) g;
INSERT INTO customer (name)
SELECT (ARRAY['Иванов','Петров','Сидоров','Кузнецов','Смирнов','Попов','Васильев','Соколов'])[1+g%8] || ' ' || g
FROM generate_series(1,8000) g;
INSERT INTO deal (customer_id, status)
SELECT 1 + (g % 8000), 'RESERVED' FROM generate_series(1,12000) g;
-- Строке сделки нужен склад (deal_item_warehouse_ck), иначе вставка отбита.
INSERT INTO branch (name) VALUES ('b');
INSERT INTO warehouse (branch_id, name) SELECT id, 'w' FROM branch;
INSERT INTO deal_item (deal_id, part_id, quantity, price, warehouse_id)
SELECT d.id, 1 + ((d.id * 7 + k * 3001) % 35841), 1, 1000, (SELECT min(id) FROM warehouse)
FROM deal d, generate_series(1,2) k;
-- Без статистики планы не те, что у живой базы.
ANALYZE;
