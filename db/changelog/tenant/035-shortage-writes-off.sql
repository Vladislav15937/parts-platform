--liquibase formatted sql

--changeset platform:tenant-035-shortage-writes-off splitStatements:false runOnChange:true
--comment Недостача, обнулившая остаток, закрывает карточку, а не оставляет
--comment её «в наличии» с нулём.
--comment
--comment Прежнее правило говорило: корректировка статус не меняет, обнулившаяся
--comment недостача разбирается руками. Рук не было — списания в системе
--comment не существовало вовсе, — и карточка оставалась «в наличии» навсегда.
--comment Списание появилось (POST /api/stock/write-offs), но этот случай оно
--comment не закрывает: остаток уже ноль, списывать нечего.
--comment
--comment Карточка с нулевым остатком, числящаяся в наличии, врёт про самое
--comment важное — про наличие. Отметка «списана» верна: детали нет, и ушла
--comment она не покупателю. Причина при этом не теряется: в журнале стоит
--comment INVENTORY_ADJUST, а не WRITE_OFF, и «не нашли при пересчёте»
--comment отличимо от «разбили при разборе».
--comment
--comment Излишек и частичная недостача статус не трогают: у них остаётся
--comment остаток, а первая ветка CASE отдаёт IN_STOCK.
CREATE OR REPLACE FUNCTION ${tenant.schema}.stock_movement_apply()
    RETURNS trigger LANGUAGE plpgsql AS $fn$
DECLARE
    v_qty numeric(12,3);
BEGIN
    -- Расход со склада-источника: приход и корректировка его не указывают.
    IF NEW.from_warehouse_id IS NOT NULL THEN
        UPDATE ${tenant.schema}.part_stock
           SET qty = qty - abs(NEW.qty_delta),
               updated_at = now()
         WHERE part_id = NEW.part_id
           AND warehouse_id = NEW.from_warehouse_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION
                'Нет остатка детали % на складе %: списывать нечего',
                NEW.part_id, NEW.from_warehouse_id;
        END IF;
    END IF;

    -- Приход на склад-приёмник.
    IF NEW.to_warehouse_id IS NOT NULL THEN
        INSERT INTO ${tenant.schema}.part_stock (part_id, warehouse_id, qty, cell_id)
        VALUES (NEW.part_id, NEW.to_warehouse_id, abs(NEW.qty_delta), NEW.to_cell_id)
        ON CONFLICT (part_id, warehouse_id) DO UPDATE
            SET qty = ${tenant.schema}.part_stock.qty + abs(NEW.qty_delta),
                cell_id = COALESCE(EXCLUDED.cell_id, ${tenant.schema}.part_stock.cell_id),
                updated_at = now();
    END IF;

    -- Общий остаток пересчитывается от part_stock, а не инкрементом: при
    -- перемещении дельта нулевая, а раскладка по складам меняется.
    SELECT COALESCE(sum(ps.qty), 0) INTO v_qty
      FROM ${tenant.schema}.part_stock ps
     WHERE ps.part_id = NEW.part_id;

    UPDATE ${tenant.schema}.part p
       SET qty_on_hand = v_qty,
           storage_cell_id = COALESCE(NEW.to_cell_id, p.storage_cell_id),
           status = CASE
               -- Есть остаток — деталь на складе, чем бы она ни была раньше:
               -- возврат от клиента возвращает проданное в продажу.
               WHEN v_qty > 0 THEN 'IN_STOCK'
               WHEN NEW.movement_type = 'SALE' THEN 'SOLD'
               WHEN NEW.movement_type = 'WRITE_OFF' THEN 'WRITTEN_OFF'
               -- Недостача, обнулившая остаток: детали нет, и ушла она
               -- не покупателю. Тип движения в журнале остаётся своим,
               -- поэтому причина различима.
               WHEN NEW.movement_type = 'INVENTORY_ADJUST' AND NEW.qty_delta < 0
                   THEN 'WRITTEN_OFF'
               -- Перемещение статус не меняет: деталь просто на другом складе.
               ELSE p.status
           END
     WHERE p.id = NEW.part_id;

    RETURN NEW;
END $fn$;
--rollback SELECT 1;
