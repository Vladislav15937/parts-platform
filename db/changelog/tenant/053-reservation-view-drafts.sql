--liquibase formatted sql

--changeset platform:tenant-053-reservation-view-without-drafts
--comment Черновик ничего не резервирует, и сверка не должна считать иначе.
--comment
--comment Заказ с площадки, который нечем закрыть, записывается сделкой
--comment в состоянии DRAFT: резерв не ставится вовсе — половина заказа
--comment держала бы товар ради сделки, которую всё равно придётся отклонить.
--comment Позиции при этом заводятся со статусом RESERVED, потому что для
--comment обычной сделки он верен: там резерв и статус ставятся вместе.
--comment
--comment Сверка же складывала обещанное по сделкам DRAFT, RESERVED и READY
--comment и получала «обещано шесть, отложена одна» — расхождение на ровном
--comment месте. Инвариант, обязанный быть пустым, начинал шуметь ровно
--comment в том сценарии, который задуман правильным, и переставал быть
--comment сигналом: у переехавшего клиента таких заказов будет по нескольку
--comment в день. Поймано прогоном на живой выгрузке, тестами — нет.
--comment
--comment Резерв и перевод в RESERVED происходят одной операцией во всех трёх
--comment путях — продажа, перенос позиций, приём заказа, — поэтому «сделка
--comment в DRAFT» и «резерва нет» означают одно и то же.
--comment
--comment Через DROP, а не CREATE OR REPLACE: последний не умеет менять имена
--comment и порядок колонок, а на схеме живого клиента это падает.
DROP VIEW IF EXISTS ${tenant.schema}.v_reservation_discrepancy;
CREATE VIEW ${tenant.schema}.v_reservation_discrepancy AS
SELECT ps.part_id,
       ps.warehouse_id,
       ps.qty_reserved                  AS cached_reserved,
       COALESCE(d.reserved_by_deals, 0) AS deals_reserved,
       ps.qty_reserved - COALESCE(d.reserved_by_deals, 0) AS diff
FROM ${tenant.schema}.part_stock ps
LEFT JOIN (
    SELECT di.part_id, di.warehouse_id, sum(di.quantity) AS reserved_by_deals
    FROM ${tenant.schema}.deal_item di
    JOIN ${tenant.schema}.deal dl ON dl.id = di.deal_id
    WHERE di.status = 'RESERVED'
      AND dl.status IN ('RESERVED','READY')
    GROUP BY di.part_id, di.warehouse_id
) d ON d.part_id = ps.part_id AND d.warehouse_id = ps.warehouse_id
WHERE ps.qty_reserved <> COALESCE(d.reserved_by_deals, 0);
--rollback DROP VIEW IF EXISTS ${tenant.schema}.v_reservation_discrepancy;
--rollback CREATE VIEW ${tenant.schema}.v_reservation_discrepancy AS SELECT ps.part_id, ps.warehouse_id, ps.qty_reserved AS cached_reserved, COALESCE(d.reserved_by_deals, 0) AS deals_reserved, ps.qty_reserved - COALESCE(d.reserved_by_deals, 0) AS diff FROM ${tenant.schema}.part_stock ps LEFT JOIN (SELECT di.part_id, di.warehouse_id, sum(di.quantity) AS reserved_by_deals FROM ${tenant.schema}.deal_item di JOIN ${tenant.schema}.deal dl ON dl.id = di.deal_id WHERE di.status = 'RESERVED' AND dl.status IN ('DRAFT','RESERVED','READY') GROUP BY di.part_id, di.warehouse_id) d ON d.part_id = ps.part_id AND d.warehouse_id = ps.warehouse_id WHERE ps.qty_reserved <> COALESCE(d.reserved_by_deals, 0);
