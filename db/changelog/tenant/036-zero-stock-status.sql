--liquibase formatted sql

--changeset platform:tenant-036-shortage-status-fixup
--comment Разовая починка карточек, обнулённых недостачей до появления
--comment tenant/035: триггер правит только новые движения, а уже стоящие
--comment в базе записи он не переписывает.
--comment
--comment Признак — не «остаток ноль», а «последнее движение было
--comment корректировкой в минус»: у карточки без движений вовсе ноль означает
--comment другое, и её лечит следующий changeset.
UPDATE ${tenant.schema}.part p
   SET status = 'WRITTEN_OFF'
 WHERE p.qty_on_hand = 0
   AND p.status = 'IN_STOCK'
   AND (SELECT m.movement_type
          FROM ${tenant.schema}.stock_movement m
         WHERE m.part_id = p.id
         ORDER BY m.id DESC
         LIMIT 1) = 'INVENTORY_ADJUST';
--rollback SELECT 1;

--changeset platform:tenant-036-imported-without-stock
--comment Карточки переезда, за которыми не лежит ничего: импортёр ставил
--comment «в наличии» при вставке, а движение писал только там, где в выгрузке
--comment было количество. Позиция, которой в прежней системе нет ни на одном
--comment складе, оставалась «в наличии» без единого движения — у переехавшего
--comment клиента таких десять.
--comment
--comment DRAFT, а не «списана»: списание — утверждение о том, что деталь была
--comment и делась, а тут неизвестно даже этого. Появится приход — триггер
--comment сам переведёт в «в наличии».
UPDATE ${tenant.schema}.part p
   SET status = 'DRAFT'
 WHERE p.qty_on_hand = 0
   AND p.status = 'IN_STOCK'
   AND NOT EXISTS (SELECT 1 FROM ${tenant.schema}.stock_movement m
                    WHERE m.part_id = p.id);
--rollback SELECT 1;
