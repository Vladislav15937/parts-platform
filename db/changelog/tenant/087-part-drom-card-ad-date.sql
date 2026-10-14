--liquibase formatted sql

--changeset partsflow:tenant-087-part-drom-card-ad-date
--comment «Дата» объявления Дрома дословно — текстовая колонка в part_drom_card, задача 0298
--
-- «Дата» — колонка 23 договора docs/drom-transfer-file.md, дата объявления у Дрома в формате
-- ГГГГ-ММ-ДД. Хранится текстом, как в ячейке: не date, потому что перенос не должен ни
-- отбивать, ни править то, что прислал Дром. Пусто — NULL; CHECK отбивает пустую строку
-- и строку из одних пробелов.
--
-- Колонка в боковой таблице, а не в part, по той же причине, что у 082, 083 и 084: слушатель
-- журнала кладёт в audit_log всю строку part до и после каждой правки.
--
-- Expand: прежний код колонки не знает, не читает и не пишет. Индекса нет намеренно:
-- по дате ничего не ищется.
ALTER TABLE ${tenant.schema}.part_drom_card
    ADD COLUMN ad_date text;

ALTER TABLE ${tenant.schema}.part_drom_card
    ADD CONSTRAINT part_drom_card_ad_date_ck CHECK (ad_date ~ '\S');

COMMENT ON COLUMN ${tenant.schema}.part_drom_card.ad_date IS
    'Дата объявления Дрома (колонка 23 файла переноса) текстом как в ячейке, формат ГГГГ-ММ-ДД; не date намеренно, перенос ничего не правит. Не в part и не в поиске. NULL — не заполнено';

--rollback-теряет дату объявления Дрома у каждой позиции: значения, перенесённые файлом переноса или поправленные на карточке, из базы не восстановить, кроме повторной загрузки того же файла (она вернёт перенесённое, но правки владельца на карточке пропадут). Остальные блоки Дрома и сами позиции остаются
--rollback ALTER TABLE ${tenant.schema}.part_drom_card DROP CONSTRAINT part_drom_card_ad_date_ck, DROP COLUMN ad_date;
