--liquibase formatted sql

--changeset partsflow:tenant-072-expected-supply-preorder
--comment Ожидаемая дата поставки, состояние позиции сделки PREORDER и пометка о сдвиге даты — задача 0170
--
-- Товар по ожидаемой поставке продаётся до прихода. Четыре вещи схемы, и все
-- четыре — форма данных, логики здесь нет.
--
-- 1. supply.expected_on — ОЖИДАЕМАЯ дата прихода. arrived_on остаётся
--    фактом; смешать их нельзя — дата, которой ещё не было, не факт.
--    NULL — «дату не называли», а не «сегодня».
--
-- 2. deal_item.status = 'PREORDER' — позиция сделки, отложенная под клиента
--    из ожидаемой поставки. Это отдельное состояние, а не RESERVED с нулевым
--    остатком: v_reservation_discrepancy сравнивает qty_reserved со суммой
--    позиций в RESERVED, и предзаказ, которому склад ничего не откладывал,
--    обязан быть для неё невидим — так же, как DRAFT необеспеченного заказа
--    (tenant/053, 056). Складской резерв (UPDATE ... WHERE qty - qty_reserved
--    >= ?) этим changeset'ом не затронут.
--
-- 3. deal.preorder_shift_from / preorder_shift_to — пометка «ожидаемая дата
--    сдвинулась»: продавец обязан увидеть её на экране, а не узнать из ленты
--    истории. Пара заполняется вместе и гасится вместе (приход или то, что
--    продавец отметил «клиенту сообщил»); пары с одной половиной не бывает.
-- 4. part.expected_origin — позиция заведена как ожидаемая (владельцем,
--    из карточки поставки). Без признака «принять заведённую» нечем отличить
--    от обычной карточки той же поставки: количество у принятой обычным путём
--    не значит ничего (умолчание 1), а у ожидаемой это обещанное по поставке.
--    Признак остаётся и после прихода: вторая партия того же контейнера,
--    приехавшая на другой склад, принимается в ту же позицию.
ALTER TABLE ${tenant.schema}.supply
    ADD COLUMN expected_on date;

ALTER TABLE ${tenant.schema}.part
    ADD COLUMN expected_origin boolean NOT NULL DEFAULT false;

ALTER TABLE ${tenant.schema}.deal_item DROP CONSTRAINT deal_item_status_ck;
ALTER TABLE ${tenant.schema}.deal_item ADD CONSTRAINT deal_item_status_ck
    CHECK (status IN ('DRAFT', 'PREORDER', 'RESERVED', 'ISSUED', 'RETURNED', 'CANCELLED'));

-- Предзаказы одной детали считает каждое новое оформление («сколько ещё можно
-- отложить»); индекс частичный, потому что строк в PREORDER единицы на фоне
-- всех позиций сделок.
CREATE INDEX deal_item_preorder_ix
    ON ${tenant.schema}.deal_item (part_id) WHERE status = 'PREORDER';

ALTER TABLE ${tenant.schema}.deal
    ADD COLUMN preorder_shift_from date,
    ADD COLUMN preorder_shift_to   date,
    ADD CONSTRAINT deal_preorder_shift_ck
        CHECK ((preorder_shift_from IS NULL) = (preorder_shift_to IS NULL));

COMMENT ON COLUMN ${tenant.schema}.supply.expected_on IS
    'Ожидаемая дата прихода поставки. Факт хранится в arrived_on. NULL — дату не называли. Задача 0170';
COMMENT ON COLUMN ${tenant.schema}.part.expected_origin IS
    'Позиция заведена как ожидаемая по поставке; её количество — обещанное по поставке. Остаётся после прихода. Задача 0170';
COMMENT ON COLUMN ${tenant.schema}.deal.preorder_shift_from IS
    'Дата прихода, которая была названа клиенту до сдвига. Пара с preorder_shift_to; NULL — сдвига, который продавец ещё не видел, нет. Задача 0170';
COMMENT ON COLUMN ${tenant.schema}.deal.preorder_shift_to IS
    'Новая ожидаемая дата прихода после сдвига. Задача 0170';

--rollback-теряет пометки о сдвиге даты, ожидаемые даты поставок, признак «заведена как ожидаемая» у позиций (после этого принять заведённую позицию нечем отличить от обычной) и переводит позиции PREORDER в DRAFT: склад под них ничего не откладывал, так что это точное описание «в документе есть, резерва нет». Обещания покупателям остаются в документах, но перестают быть отличимы от необеспеченных заказов
--rollback UPDATE ${tenant.schema}.deal_item SET status = 'DRAFT' WHERE status = 'PREORDER';
--rollback ALTER TABLE ${tenant.schema}.deal DROP CONSTRAINT deal_preorder_shift_ck, DROP COLUMN preorder_shift_to, DROP COLUMN preorder_shift_from;
--rollback DROP INDEX ${tenant.schema}.deal_item_preorder_ix;
--rollback ALTER TABLE ${tenant.schema}.deal_item DROP CONSTRAINT deal_item_status_ck;
--rollback ALTER TABLE ${tenant.schema}.deal_item ADD CONSTRAINT deal_item_status_ck CHECK (status IN ('DRAFT', 'RESERVED', 'ISSUED', 'RETURNED', 'CANCELLED'));
--rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN expected_origin;
--rollback ALTER TABLE ${tenant.schema}.supply DROP COLUMN expected_on;
