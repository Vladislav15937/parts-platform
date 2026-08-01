--liquibase formatted sql

--changeset platform:tenant-038-account-discrepancy splitStatements:false runOnChange:true
--comment Сверка расчётов с клиентами. Обязана быть пустой.
--comment
--comment У склада такие сверки есть с самого начала — v_stock_discrepancy
--comment и v_reservation_discrepancy, — а у денег не было ни одной: сумма
--comment лицевых счетов ничем не связана с движением денег, и расхождение
--comment не измерял никто. Ручная правка остатка в системе без сверки
--comment со временем превращается в способ прятать расхождения вместо того,
--comment чтобы их находить.
--comment
--comment Каждая строка — нарушенный инвариант, и каждый из них уже случался
--comment или мог случиться:
--comment
--comment 1. Отрицательный остаток. Счёт — обязательство перед клиентом,
--comment    а не кредит ему.
--comment 2. Отменённая сделка с оплатой, по которой деньги не вернулись.
--comment    Ровно эта потеря и нашлась вручную: клиент оставил аванс, зачли
--comment    в отложенную сделку, сделку отменили — деньги не числятся нигде.
--comment 3. Зачёт с лицевого счёта, породивший платёж. Деньги получены
--comment    раньше; второй платёж задваивает выручку.
--comment 4. Пополнение или выдача без платежа. Запись о деньгах, за которой
--comment    не стоит их движения: касса не сойдётся ровно на эту сумму.
--comment 5. Правка остатка без причины. Через месяц её не отличить от ошибки.
-- DROP перед CREATE обязателен, как и у прочих вьюх: CREATE OR REPLACE
-- не умеет менять имена и порядок колонок, а новую в середину списка
-- добавляют рано или поздно. На чистой базе это проходит и падает только
-- на схеме живого клиента.
DROP VIEW IF EXISTS ${tenant.schema}.v_account_discrepancy;
CREATE OR REPLACE VIEW ${tenant.schema}.v_account_discrepancy AS
WITH balances AS (
    SELECT customer_id,
           sum(CASE entry_type
                   WHEN 'TOP_UP'       THEN amount
                   WHEN 'DEAL_REFUND'  THEN amount
                   WHEN 'CORRECTION'   THEN amount
                   ELSE -amount
               END) AS balance
      FROM ${tenant.schema}.customer_account_entry
     GROUP BY customer_id
)
SELECT b.customer_id,
       NULL::bigint AS entry_id,
       NULL::bigint AS deal_id,
       'отрицательный остаток счёта'::text AS problem,
       b.balance                            AS amount
  FROM balances b
 WHERE b.balance < 0

UNION ALL

SELECT d.customer_id, NULL::bigint, d.id,
       'сделка отменена, оплата не возвращена'::text,
       d.paid_amount
  FROM ${tenant.schema}.deal d
 WHERE d.status = 'CANCELLED'
   AND d.paid_amount > 0
   AND NOT EXISTS (SELECT 1 FROM ${tenant.schema}.customer_account_entry e
                    WHERE e.deal_id = d.id AND e.entry_type = 'DEAL_REFUND')
   AND NOT EXISTS (SELECT 1 FROM ${tenant.schema}.payment p
                    WHERE p.deal_id = d.id AND p.direction = 'OUT')

UNION ALL

SELECT e.customer_id, e.id, e.deal_id,
       'зачёт со счёта породил платёж: выручка задвоена'::text,
       e.amount
  FROM ${tenant.schema}.customer_account_entry e
 WHERE e.entry_type = 'DEAL_PAYMENT'
   AND e.payment_id IS NOT NULL

UNION ALL

SELECT e.customer_id, e.id, e.deal_id,
       'движение денег по счёту без платежа'::text,
       e.amount
  FROM ${tenant.schema}.customer_account_entry e
 WHERE e.entry_type IN ('TOP_UP', 'WITHDRAW')
   AND e.payment_id IS NULL

UNION ALL

SELECT e.customer_id, e.id, e.deal_id,
       'правка остатка без причины'::text,
       e.amount
  FROM ${tenant.schema}.customer_account_entry e
 WHERE e.entry_type = 'CORRECTION'
   AND (e.comment IS NULL OR btrim(e.comment) = '');
--rollback DROP VIEW IF EXISTS ${tenant.schema}.v_account_discrepancy;

--changeset platform:tenant-038-customer-settlements splitStatements:false runOnChange:true
--comment Расчёты с клиентами: аванс и долг по каждому.
--comment
--comment Владелец не видел своих обязательств перед клиентами ни одним
--comment числом: деньги на лицевых счетах есть, а сколько их всего — узнать
--comment было негде.
--comment
--comment Долг считается по выданным сделкам: пока товар не отдан, это
--comment не долг, а обещание, и требовать по нему нечего.
DROP VIEW IF EXISTS ${tenant.schema}.v_customer_settlement;
CREATE OR REPLACE VIEW ${tenant.schema}.v_customer_settlement AS
SELECT c.id                                  AS customer_id,
       c.name                                AS customer_name,
       c.phone                               AS phone,
       COALESCE(a.balance, 0)                AS account_balance,
       COALESCE(d.debt, 0)                   AS debt,
       COALESCE(d.deals, 0)                  AS unpaid_deals
  FROM ${tenant.schema}.customer c
  LEFT JOIN (
      SELECT customer_id,
             sum(CASE entry_type
                     WHEN 'TOP_UP'      THEN amount
                     WHEN 'DEAL_REFUND' THEN amount
                     WHEN 'CORRECTION'  THEN amount
                     ELSE -amount
                 END) AS balance
        FROM ${tenant.schema}.customer_account_entry
       GROUP BY customer_id
  ) a ON a.customer_id = c.id
  LEFT JOIN (
      SELECT customer_id,
             sum(total_amount - paid_amount) AS debt,
             count(*)                        AS deals
        FROM ${tenant.schema}.deal
       WHERE status = 'ISSUED'
         AND total_amount > paid_amount
       GROUP BY customer_id
  ) d ON d.customer_id = c.id
 WHERE COALESCE(a.balance, 0) <> 0 OR COALESCE(d.debt, 0) <> 0;
--rollback DROP VIEW IF EXISTS ${tenant.schema}.v_customer_settlement;
