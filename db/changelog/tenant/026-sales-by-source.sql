--liquibase formatted sql

-- Откуда пришли деньги. Справочник источников заведён давно, но поле
-- никем не заполнялось и ни один отчёт по нему не считал — то есть на вопрос
-- владельца «окупается ли размещение на Дроме» ответить было нечем.

--changeset platform:tenant-250-sales-by-source splitStatements:false runOnChange:true
--comment Продажи по каналам за месяц. Считается по позициям, как и отчёт
--comment по менеджерам, и по той же причине: при частичном возврате сделка
--comment остаётся ISSUED, и без фильтра по позиции канал получал бы выручку
--comment за товар, приехавший обратно.
--comment
--comment Сделки без источника собираются в отдельную строку с пустым каналом,
--comment а не выбрасываются: невидимая часть выручки делает отчёт бесполезным —
--comment по нему нельзя понять, Дром не приносит денег или продавцы
--comment не отмечают источник.
DROP VIEW IF EXISTS ${tenant.schema}.v_sales_by_source;
CREATE OR REPLACE VIEW ${tenant.schema}.v_sales_by_source AS
SELECT dl.deal_source_id,
       ds.name                                    AS source_name,
       date_trunc('month', dl.closed_at)          AS period,
       count(DISTINCT dl.id)                      AS deals_count,
       sum(di.price * di.quantity - di.discount)  AS revenue,
       sum((di.price - di.cost_price_snapshot) * di.quantity - di.discount)
           FILTER (WHERE di.cost_price_snapshot IS NOT NULL) AS margin,
       count(*) FILTER (WHERE di.cost_price_snapshot IS NULL) AS items_without_cost
FROM ${tenant.schema}.deal dl
JOIN ${tenant.schema}.deal_item di ON di.deal_id = dl.id
LEFT JOIN ${tenant.schema}.deal_source ds ON ds.id = dl.deal_source_id
WHERE dl.status = 'ISSUED'
  AND di.status = 'ISSUED'
  AND dl.closed_at IS NOT NULL
GROUP BY dl.deal_source_id, ds.name, date_trunc('month', dl.closed_at);
--rollback DROP VIEW IF EXISTS ${tenant.schema}.v_sales_by_source;
