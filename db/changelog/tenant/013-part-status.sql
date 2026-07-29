--liquibase formatted sql

-- Статус товара перестаёт быть полем, которое кто-то не забыл обновить,
-- и становится следствием журнала движений — как и остаток.
--
-- До этой миграции part.status выставлялся один раз при приёмке и больше
-- не менялся ничем: ни кодом, ни триггером. Проданная деталь оставалась
-- IN_STOCK, продолжала показываться продавцу в поиске по применимости,
-- а v_donor_profitability.parts_sold считал проданные по status = 'SOLD'
-- и всегда возвращал ноль.

--changeset platform:tenant-120-part-status-derived splitStatements:false runOnChange:true
--comment Третья версия stock_movement_apply: к двум кэшам остатка добавляется
--comment статус товара. ВНИМАНИЕ: заменяет версию из 010 (changeset tenant-095),
--comment та в свою очередь заменяла версию из 005. Старые трогать нельзя —
--comment чек-сумма, поэтому новая версия живёт здесь и переопределяет их
--comment порядком применения.
--comment
--comment Почему статус считается здесь, а не в коде: он производная от остатка,
--comment а остаток — агрегат журнала. Держать его отдельным полем, которое
--comment обновляет вызывающий, значит гарантированно однажды забыть.
--comment
--comment Почему нельзя вывести статус только из количества: у DRAFT, SOLD
--comment и WRITTEN_OFF остаток одинаково нулевой. Различает их тип движения,
--comment которое обнулило остаток, поэтому статус пишется здесь, а не в вьюхе.
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
               -- Перемещение и корректировка статус не меняют: обнулившаяся
               -- недостача — это не продажа и не списание, разбирается руками.
               ELSE p.status
           END
     WHERE p.id = NEW.part_id;

    RETURN NEW;
END $fn$;
--rollback SELECT 1;

--changeset platform:tenant-121-part-status-backfill
--comment Разовая правка накопленного: у импортированных из Bazon позиций
--comment статус проставлен как IN_STOCK всем подряд, включая давно проданные.
--comment Логика та же, что в триггере — статус по последнему движению.
UPDATE ${tenant.schema}.part p
   SET status = CASE last.movement_type
           WHEN 'SALE'      THEN 'SOLD'
           WHEN 'WRITE_OFF' THEN 'WRITTEN_OFF'
           ELSE p.status
       END
  FROM (
      SELECT DISTINCT ON (part_id) part_id, movement_type
        FROM ${tenant.schema}.stock_movement
       ORDER BY part_id, id DESC
  ) last
 WHERE last.part_id = p.id
   AND p.qty_on_hand = 0
   AND p.status <> 'DRAFT';
--rollback SELECT 1;

--changeset platform:tenant-122-part-status-drop-reserved
--comment RESERVED убирается из статусов товара.
--comment
--comment Резерв — следствие part_stock.qty_reserved, а не состояние карточки,
--comment и как состояние он попросту неверен: у детали количеством 3 может быть
--comment зарезервирована одна, и «RESERVED» тогда врёт про остальные две.
--comment Та же причина, по которой в DealStatus нет «частично оплачен».
UPDATE ${tenant.schema}.part SET status = 'IN_STOCK' WHERE status = 'RESERVED';

ALTER TABLE ${tenant.schema}.part DROP CONSTRAINT part_status_ck;
ALTER TABLE ${tenant.schema}.part ADD CONSTRAINT part_status_ck CHECK (status IN
    ('DRAFT','IN_STOCK','SOLD','WRITTEN_OFF'));
--rollback ALTER TABLE ${tenant.schema}.part DROP CONSTRAINT part_status_ck;
--rollback ALTER TABLE ${tenant.schema}.part ADD CONSTRAINT part_status_ck CHECK (status IN ('DRAFT','IN_STOCK','RESERVED','SOLD','WRITTEN_OFF'));
