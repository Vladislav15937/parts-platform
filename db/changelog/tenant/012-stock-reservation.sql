--liquibase formatted sql

--changeset platform:tenant-110-part-stock-available
--comment Доступное к продаже — вычисляемая колонка, а не расчёт в коде.
--comment «Свободно» и «зарезервировано» лежат рядом, и любой забывший вычесть
--comment резерв запрос продаст деталь, которая уже обещана другому клиенту.
--comment Генерируемая колонка делает такую ошибку невозможной: её нельзя
--comment «забыть», её видно в любом SELECT *.
ALTER TABLE ${tenant.schema}.part_stock
    ADD COLUMN qty_available numeric(12,3)
        GENERATED ALWAYS AS (qty - qty_reserved) STORED;

-- Главный фильтр экрана продавца: что реально можно продать сейчас.
CREATE INDEX part_stock_available_ix ON ${tenant.schema}.part_stock (warehouse_id, part_id)
    WHERE qty_available > 0;
--rollback DROP INDEX ${tenant.schema}.part_stock_available_ix;
--rollback ALTER TABLE ${tenant.schema}.part_stock DROP COLUMN qty_available;

--changeset platform:tenant-111-deal-item-warehouse-required
--comment Позиция сделки обязана знать склад: резерв ставится на конкретный
--comment остаток, и позиция без склада — это резерв в никуда.
--comment
--comment Услуги (доставка, упаковка) под это условие не подойдут: у них склада
--comment нет. Когда они появятся, ограничение придётся расширить отдельным
--comment changeset'ом — сюда дописать нельзя, чек-сумма уже зафиксирована.
--comment Заранее заводить признак типа позиции не стали: тип номенклатуры
--comment живёт в каталоге, а не в позиции сделки, и решать это до появления
--comment самих услуг значит угадывать.
ALTER TABLE ${tenant.schema}.deal_item
    ADD CONSTRAINT deal_item_warehouse_ck
        CHECK (status = 'CANCELLED' OR warehouse_id IS NOT NULL);
--rollback ALTER TABLE ${tenant.schema}.deal_item DROP CONSTRAINT deal_item_warehouse_ck;

--changeset platform:tenant-112-reserve-functions splitStatements:false runOnChange:true
--comment Резервирование и снятие резерва — функции БД, а не UPDATE из кода.
--comment
--comment Причина в гонке. Два продавца одновременно кладут последнюю деталь
--comment в свои сделки: оба прочитали «свободно 1», оба записали «резерв 1».
--comment Если считать доступное в приложении, вторая запись затрёт первую и
--comment деталь окажется продана дважды — с реальным клиентом на пороге склада.
--comment
--comment Здесь проверка и изменение — одна инструкция: условие «хватает
--comment свободного» стоит в WHERE, поэтому второй транзакции не достанется
--comment ни одной строки, и она узнает об этом по количеству изменённых строк.
--comment Postgres сериализует конкурирующие UPDATE одной строки, так что
--comment второй увидит уже увеличенный резерв, а не устаревший снимок.
CREATE OR REPLACE FUNCTION ${tenant.schema}.reserve_stock(
        p_part_id bigint, p_warehouse_id bigint, p_qty numeric)
    RETURNS void LANGUAGE plpgsql AS $fn$
DECLARE
    v_updated int;
BEGIN
    IF p_qty IS NULL OR p_qty <= 0 THEN
        RAISE EXCEPTION 'Количество для резерва должно быть больше нуля';
    END IF;

    UPDATE ${tenant.schema}.part_stock
       SET qty_reserved = qty_reserved + p_qty,
           updated_at = now()
     WHERE part_id = p_part_id
       AND warehouse_id = p_warehouse_id
       AND qty - qty_reserved >= p_qty;

    GET DIAGNOSTICS v_updated = ROW_COUNT;

    IF v_updated = 0 THEN
        RAISE EXCEPTION
            'Недостаточно свободного остатка: деталь % на складе %, требуется %',
            p_part_id, p_warehouse_id, p_qty
            USING ERRCODE = 'check_violation';
    END IF;
END $fn$;

CREATE OR REPLACE FUNCTION ${tenant.schema}.release_stock(
        p_part_id bigint, p_warehouse_id bigint, p_qty numeric)
    RETURNS void LANGUAGE plpgsql AS $fn$
DECLARE
    v_updated int;
BEGIN
    UPDATE ${tenant.schema}.part_stock
       SET qty_reserved = qty_reserved - p_qty,
           updated_at = now()
     WHERE part_id = p_part_id
       AND warehouse_id = p_warehouse_id
       AND qty_reserved >= p_qty;

    GET DIAGNOSTICS v_updated = ROW_COUNT;

    IF v_updated = 0 THEN
        -- Снятие несуществующего резерва — признак рассогласования, а не
        -- безобидная операция: молча пропустив его, мы оставим деталь
        -- заблокированной навсегда.
        RAISE EXCEPTION
            'Нечего снимать с резерва: деталь % на складе %, требуется %',
            p_part_id, p_warehouse_id, p_qty
            USING ERRCODE = 'check_violation';
    END IF;
END $fn$;
--rollback DROP FUNCTION IF EXISTS ${tenant.schema}.release_stock(bigint, bigint, numeric);
--rollback DROP FUNCTION IF EXISTS ${tenant.schema}.reserve_stock(bigint, bigint, numeric);

--changeset platform:tenant-113-reservation-guard splitStatements:false runOnChange:true
--comment Страховка от неверного порядка операций при выдаче.
--comment
--comment Выдача уменьшает qty движением склада. Если резерв при этом не снят,
--comment получится qty_reserved > qty — состояние, в котором «зарезервировано
--comment больше, чем лежит». Ограничение part_stock_qty_ck это поймает, но
--comment сообщение будет невнятным, а причина — далеко от места ошибки.
--comment Поэтому даём осмысленный текст: порядок «сначала снять резерв, потом
--comment списать» нарушить легко, а найти потом трудно.
CREATE OR REPLACE FUNCTION ${tenant.schema}.part_stock_check_reserved()
    RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    IF NEW.qty_reserved > NEW.qty THEN
        RAISE EXCEPTION
            'Резерв (%) больше остатка (%) по детали % на складе %. '
            'При выдаче резерв снимают до списания, а не после',
            NEW.qty_reserved, NEW.qty, NEW.part_id, NEW.warehouse_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $fn$;
--rollback DROP FUNCTION IF EXISTS ${tenant.schema}.part_stock_check_reserved();

--changeset platform:tenant-114-reservation-guard-attach
CREATE TRIGGER part_stock_reserved_guard
    BEFORE INSERT OR UPDATE ON ${tenant.schema}.part_stock
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.part_stock_check_reserved();
--rollback DROP TRIGGER IF EXISTS part_stock_reserved_guard ON ${tenant.schema}.part_stock;

--changeset platform:tenant-115-reserved-stock-view splitStatements:false runOnChange:true
--comment Сверка резервов: сумма активных резервов по позициям сделок должна
--comment совпадать с part_stock.qty_reserved. Непустой результат означает, что
--comment где-то резерв поставили или сняли мимо функций — гоняется вместе
--comment с ежесуточной сверкой остатка.
CREATE OR REPLACE VIEW ${tenant.schema}.v_reservation_discrepancy AS
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
      AND dl.status IN ('DRAFT','RESERVED','READY')
    GROUP BY di.part_id, di.warehouse_id
) d ON d.part_id = ps.part_id AND d.warehouse_id = ps.warehouse_id
WHERE ps.qty_reserved <> COALESCE(d.reserved_by_deals, 0);
--rollback DROP VIEW IF EXISTS ${tenant.schema}.v_reservation_discrepancy;
