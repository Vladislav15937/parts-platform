--liquibase formatted sql

--changeset platform:tenant-080-donor-profitability splitStatements:false runOnChange:true
--comment Окупаемость донора: сколько вложено, сколько выручено, что осталось
--comment на складе. Прямой ответ на вопрос «стоит ли брать такие машины».
CREATE OR REPLACE VIEW ${tenant.schema}.v_donor_profitability AS
SELECT d.id AS donor_id,
       d.public_code,
       d.vin,
       d.year,
       COALESCE(c.total_cost, 0)                          AS total_cost,
       COALESCE(s.revenue, 0)                             AS revenue,
       COALESCE(s.revenue, 0) - COALESCE(c.total_cost, 0) AS profit,
       COALESCE(p.parts_total, 0)                         AS parts_total,
       COALESCE(p.parts_sold, 0)                          AS parts_sold,
       COALESCE(p.stock_value, 0)                         AS stock_value
FROM ${tenant.schema}.donor d
LEFT JOIN (
    SELECT donor_id, sum(amount) AS total_cost
    FROM ${tenant.schema}.donor_cost
    GROUP BY donor_id
) c ON c.donor_id = d.id
LEFT JOIN (
    SELECT pt.donor_id,
           sum(di.price * di.quantity - di.discount) AS revenue
    FROM ${tenant.schema}.deal_item di
    JOIN ${tenant.schema}.part pt ON pt.id = di.part_id
    JOIN ${tenant.schema}.deal dl ON dl.id = di.deal_id
    WHERE dl.status IN ('PAID','SHIPPED','COMPLETED')
    GROUP BY pt.donor_id
) s ON s.donor_id = d.id
LEFT JOIN (
    SELECT donor_id,
           count(*)                                                    AS parts_total,
           count(*) FILTER (WHERE status = 'SOLD')                     AS parts_sold,
           sum(price * qty_on_hand) FILTER (WHERE status = 'IN_STOCK') AS stock_value
    FROM ${tenant.schema}.part
    WHERE donor_id IS NOT NULL
    GROUP BY donor_id
) p ON p.donor_id = d.id;
--rollback DROP VIEW IF EXISTS ${tenant.schema}.v_donor_profitability;

--changeset platform:tenant-081-manager-sales splitStatements:false runOnChange:true
--comment Продажи по менеджерам: основа для расчёта зарплат.
CREATE OR REPLACE VIEW ${tenant.schema}.v_manager_sales AS
SELECT dl.manager_id,
       tm.display_name,
       date_trunc('month', dl.closed_at)          AS period,
       count(DISTINCT dl.id)                      AS deals_count,
       sum(di.price * di.quantity - di.discount)  AS revenue,
       sum((di.price - COALESCE(di.cost_price_snapshot, 0)) * di.quantity - di.discount) AS margin
FROM ${tenant.schema}.deal dl
JOIN ${tenant.schema}.deal_item di ON di.deal_id = dl.id
LEFT JOIN ${tenant.schema}.tenant_member tm ON tm.id = dl.manager_id
WHERE dl.status IN ('PAID','SHIPPED','COMPLETED')
  AND dl.closed_at IS NOT NULL
GROUP BY dl.manager_id, tm.display_name, date_trunc('month', dl.closed_at);
--rollback DROP VIEW IF EXISTS ${tenant.schema}.v_manager_sales;

--changeset platform:tenant-082-stock-reconciliation splitStatements:false runOnChange:true
--comment Сверка денормализованного остатка с журналом движений. Гоняется
--comment ежесуточно; непустой результат означает баг в триггере и требует алерта.
--comment Кэшей теперь два — part.qty_on_hand и раскладка part_stock по складам, —
--comment и разойтись может любой из них, поэтому сверяются оба.
--comment Перемещения из суммы журнала исключены: они не меняют общий остаток,
--comment у них положительная дельта означает «столько-то переехало».
CREATE OR REPLACE VIEW ${tenant.schema}.v_stock_discrepancy AS
SELECT p.id AS part_id,
       p.public_code,
       p.qty_on_hand                       AS cached_qty,
       COALESCE(m.journal_qty, 0)          AS journal_qty,
       COALESCE(s.warehouse_qty, 0)        AS warehouse_qty,
       p.qty_on_hand - COALESCE(m.journal_qty, 0) AS diff
FROM ${tenant.schema}.part p
LEFT JOIN (
    SELECT part_id, sum(qty_delta) AS journal_qty
    FROM ${tenant.schema}.stock_movement
    WHERE movement_type <> 'MOVE'
    GROUP BY part_id
) m ON m.part_id = p.id
LEFT JOIN (
    SELECT part_id, sum(qty) AS warehouse_qty
    FROM ${tenant.schema}.part_stock
    GROUP BY part_id
) s ON s.part_id = p.id
WHERE p.qty_on_hand <> COALESCE(m.journal_qty, 0)
   OR p.qty_on_hand <> COALESCE(s.warehouse_qty, 0);
--rollback DROP VIEW IF EXISTS ${tenant.schema}.v_stock_discrepancy;
