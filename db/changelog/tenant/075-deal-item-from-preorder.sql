--liquibase formatted sql

--changeset partsflow:tenant-075-deal-item-from-preorder
--comment Признак «позиция сделки родилась предзаказом» — задача 0260
--
-- После прихода товара позиция предзаказа перестаёт быть PREORDER и
-- становится обычной резервной, и по состоянию уже не отличить её от
-- позиции, заведённой сразу с резервом. Признак ставит Java при
-- подтверждении предзаказа и больше не снимает. Здесь только форма данных:
-- тип, NOT NULL и умолчание. Прежний код колонки не знает и пишет умолчание,
-- поэтому changeset — expand. Индекса нет: по признаку не ищут.
ALTER TABLE ${tenant.schema}.deal_item
    ADD COLUMN from_preorder boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN ${tenant.schema}.deal_item.from_preorder IS
    'Позиция родилась предзаказом; ставится при подтверждении предзаказа и остаётся после прихода. Задача 0260';

--rollback-теряет признак from_preorder у всех позиций сделок: после отката позиции, родившиеся предзаказом, неотличимы от обычных резервных; на поведение до задания 0260 это не влияет
--rollback ALTER TABLE ${tenant.schema}.deal_item DROP COLUMN from_preorder;
