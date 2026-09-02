--liquibase formatted sql

--changeset partsflow:tenant-056-deal-item-draft runOnChange:false
--comment Позиция необеспеченного заказа не называется зарезервированной

-- Заказ с площадки, который нечем закрыть, остаётся черновиком и ничего
-- не резервирует — это записано и работает. А позиции его при этом
-- заводились со статусом RESERVED: умолчание поля, верное для обычной
-- продажи, где резерв ставится тут же.
--
-- Ложь стоила возможности закрыть такой заказ вовсе. Отмена сделки идёт
-- по позициям в статусе RESERVED и снимает резерв, которого никто не ставил:
-- «Нечего снимать с резерва: деталь 250, склад 2, требуется 5.000», то есть
-- 409 на единственное действие, которое с этим заказом можно сделать.
-- А сам заказ висит в очереди «ждут ответа» вечно: клиента у него нет,
-- и добраться до него больше неоткуда.
--
-- Сверку это не ломало только потому, что она смотрит ещё и на статус
-- документа. Второй читатель того же поля наступил бы на то же самое.
ALTER TABLE deal_item DROP CONSTRAINT deal_item_status_ck;
ALTER TABLE deal_item ADD CONSTRAINT deal_item_status_ck
    CHECK (status IN ('DRAFT', 'RESERVED', 'ISSUED', 'RETURNED', 'CANCELLED'));

-- Уже заведённые необеспеченные заказы. Признак точный: резерв ставится
-- ровно там же, где сделка переходит в RESERVED, — значит у черновика
-- зарезервированных позиций не бывает.
UPDATE deal_item i
   SET status = 'DRAFT'
  FROM deal d
 WHERE d.id = i.deal_id
   AND d.status = 'DRAFT'
   AND i.status = 'RESERVED';

--rollback UPDATE deal_item SET status = 'RESERVED' WHERE status = 'DRAFT';
--rollback ALTER TABLE deal_item DROP CONSTRAINT deal_item_status_ck;
--rollback ALTER TABLE deal_item ADD CONSTRAINT deal_item_status_ck CHECK (status IN ('RESERVED', 'ISSUED', 'RETURNED', 'CANCELLED'));
