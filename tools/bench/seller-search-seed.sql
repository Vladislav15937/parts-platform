-- Данные для замера поиска продавца (задачи 0170, 0261): склад в 50 300 позиций.
--
--   psql -v schema=t_bench [-v drafts=15000] -f tools/bench/seller-search-seed.sql
--
-- Замер на этих данных — tools/bench/seller_search_bench.py (шапка там же).
-- Контейнер Postgres свой, не общий: db/docker-compose.yml поднимает чистую
-- базу, схему накатывает Liquibase так же, как это делает db/verify.sh.
-- Схема должна быть накатана целиком (db/verify.sh или провижининг) и пуста.
-- Состав повторяет сценарий замера PR 370 и ничего не берёт с живого склада:
--   * 50 000 позиций на складе, у каждой строка part_stock; «фара» — 2 из 19
--     (5 263 штуки), «модельNN» — 100 моделей по 500 позиций;
--   * 300 ожидаемых позиций из поставки EXPECTED (expected_origin, DRAFT,
--     раскладки нет) — все «фара», по три на каждую «модельNN»;
--   * 150 открытых предзаказов (deal_item PREORDER): 140 на ожидаемых и 10 на
--     позициях со складом — случай частичного прихода; у этих десяти есть
--     второй склад, а у пяти предзаказ на четыре штуки — вычитание «с меньшего
--     номера склада первым» работает через границу складов;
--   * -v drafts=N — N черновиков без раскладки, не из поставки (по умолчанию 0);
--   * 40 000 закрытых строк сделок (ISSUED) — сценарий «Б» PR 370: на малой
--     deal_item планировщик читает её последовательно на каждую находку, и
--     замер на 300 строк завышает цену против живого арендатора;
--   * 40 «машин» из справочника марок и моделей — для фасетов.
-- Потом ANALYZE: без статистики планы не те, что у живой базы.

\if :{?drafts}
\else
\set drafts 0
\endif

SET search_path = :schema, public;

INSERT INTO branch (name) VALUES ('Замер');
INSERT INTO warehouse (branch_id, name) SELECT id, 'Склад замера' FROM branch WHERE name = 'Замер';
INSERT INTO storage_cell (warehouse_id, code)
SELECT (SELECT min(id) FROM warehouse), 'A-' || g FROM generate_series(1, 200) g;
INSERT INTO supply (kind, number, status, expected_on)
VALUES ('CONTAINER', 'BENCH-1', 'EXPECTED', current_date + 10);

INSERT INTO donor (brand_id, model_id, year, status)
SELECT m.brand_id, m.id, 2000 + (row_number() OVER ())::int % 20, 'DISMANTLED'
  FROM (SELECT id, brand_id FROM catalog.model ORDER BY id LIMIT 40) m;

-- На складе. Вид детали — по остатку от деления: «фара» две девятнадцатых.
INSERT INTO part (title, price, status, donor_id, side_lr, side_fr, quality_grade,
                  quantity, qty_on_hand)
SELECT CASE WHEN g % 19 < 2 THEN 'Фара'
            ELSE (ARRAY['Бампер', 'Дверь', 'Капот', 'Крыло', 'Стекло', 'Зеркало',
                        'Радиатор', 'Фонарь', 'Двигатель', 'Коробка', 'Диск', 'Стойка',
                        'Рычаг', 'Замок', 'Ступица', 'Генератор', 'Стартер'])[1 + (g % 17)]
       END || ' ' || (ARRAY['левая', 'правая', 'передняя'])[1 + (g % 3)] || ' модель' || (g % 100),
       500 + (g * 37) % 30000, 'IN_STOCK',
       (SELECT array_agg(id ORDER BY id) FROM donor)[1 + (g % 40)],
       (ARRAY['LEFT', 'RIGHT', NULL])[1 + (g % 3)],
       (ARRAY['FRONT', 'REAR', NULL])[1 + (g % 3)],
       (ARRAY['AS_NEW', 'NO_DEFECTS', 'WITH_DEFECTS', NULL])[1 + (g % 4)],
       1 + g % 5, 1 + g % 5
  FROM generate_series(1, 50000) g;

INSERT INTO part_stock (part_id, warehouse_id, qty, qty_reserved, cell_id)
SELECT p.id, (SELECT min(id) FROM warehouse), p.quantity,
       CASE WHEN p.id % 50 = 0 THEN 1 ELSE 0 END,
       (SELECT min(id) FROM storage_cell) + (p.id % 200)
  FROM part p;

-- Черновики приёмки: DRAFT, но не из поставки и без раскладки (приёмка
-- сохранила карточку и не провела). Их число задаёт -v drafts=N, по умолчанию
-- ноль. Нужны затем, что на «status = 'DRAFT'» без частичного индекса
-- планировщик ходит по ним всем: при трёхстах ожидаемых и пустых черновиках
-- индекс part_expected_ix не виден в замере вовсе, при пятнадцати тысячах —
-- виден (задача 0261).
INSERT INTO part (title, price, status, quantity, qty_on_hand)
SELECT CASE WHEN g % 19 < 2 THEN 'Фара' ELSE 'Бампер' END
       || ' черновик модель' || (g % 100), 1000 + g, 'DRAFT', 1, 0
  FROM generate_series(1, :drafts) g;

-- Ожидаемые: черновик из поставки, раскладки нет.
INSERT INTO part (title, price, status, expected_origin, supply_id, quantity, qty_on_hand)
SELECT 'Фара ожидаемая модель' || (g % 100), 1000 + g, 'DRAFT', true,
       (SELECT id FROM supply WHERE number = 'BENCH-1'), 5, 0
  FROM generate_series(1, 300) g;

-- Сделки: одна с предзаказами, остальные — закрытые, ради размера deal_item.
INSERT INTO deal (status, branch_id, warehouse_id, reserved_until)
SELECT 'RESERVED', (SELECT min(id) FROM branch), (SELECT min(id) FROM warehouse),
       now() + interval '7 days';
INSERT INTO deal_item (deal_id, part_id, quantity, price, status, warehouse_id)
SELECT (SELECT min(id) FROM deal), e.id, 1, e.price, 'PREORDER', (SELECT min(id) FROM warehouse)
  FROM (SELECT id, price FROM part WHERE expected_origin ORDER BY id LIMIT 140) e;
INSERT INTO deal_item (deal_id, part_id, quantity, price, status, warehouse_id)
SELECT (SELECT min(id) FROM deal), s.id, 1, s.price, 'PREORDER', (SELECT min(id) FROM warehouse)
  FROM (SELECT id, price FROM part WHERE NOT expected_origin AND title LIKE 'Фара%'
         ORDER BY id LIMIT 10) s;

INSERT INTO deal (status, branch_id, warehouse_id)
SELECT 'ISSUED', (SELECT min(id) FROM branch), (SELECT min(id) FROM warehouse)
  FROM generate_series(1, 400);
INSERT INTO deal_item (deal_id, part_id, quantity, price, status, warehouse_id)
SELECT (SELECT min(id) FROM deal WHERE status = 'ISSUED') + (g % 400),
       (SELECT min(id) FROM part) + (g * 7) % 50000, 1, 1000, 'ISSUED',
       (SELECT min(id) FROM warehouse)
  FROM generate_series(1, 40000) g;

-- Второй склад для десяти предзаказанных позиций со складом: обещанное
-- вычитается с меньшего номера склада первым, остальное — со следующего, и
-- этот порядок виден только когда складов больше одного. У пяти позиций
-- предзаказ на четыре штуки — больше, чем лежит на первом складе.
INSERT INTO warehouse (branch_id, name) SELECT min(id), 'Склад замера 2' FROM branch;
INSERT INTO part_stock (part_id, warehouse_id, qty, qty_reserved)
SELECT di.part_id, (SELECT max(id) FROM warehouse), 2, 0
  FROM deal_item di JOIN part p ON p.id = di.part_id AND NOT p.expected_origin
 WHERE di.status = 'PREORDER';
UPDATE deal_item SET quantity = 4
 WHERE id IN (SELECT di.id FROM deal_item di JOIN part p ON p.id = di.part_id
               WHERE di.status = 'PREORDER' AND NOT p.expected_origin ORDER BY di.id LIMIT 5);

-- VACUUM, а не один ANALYZE: без карты видимости индексные чтения без доступа
-- к таблице (счёт на v0) читают таблицу и замер врёт в пользу полного чтения.
VACUUM ANALYZE part;
VACUUM ANALYZE part_stock;
VACUUM ANALYZE deal_item;
ANALYZE;
