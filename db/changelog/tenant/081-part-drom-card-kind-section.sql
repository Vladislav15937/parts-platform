--liquibase formatted sql

--changeset partsflow:tenant-081-part-drom-card-kind-section
--comment Вид детали и раздел как у клиента на Дроме — две колонки в part_drom_card, задача 0286
--
-- Файл переноса с Дрома (docs/drom-transfer-file.md, колонки 4 и 5) несёт строку
-- вида детали («Карданный вал Lexus GX400») и раздел («Автозапчасти → Трансмиссия»).
-- Хранятся дословно, как у клиента: это НЕ part_kind_id (наш справочный вид без
-- марки) и НЕ part.section («Секция» — место на складе). Поэтому колонки названы
-- drom_kind и drom_section: имя section занято позицией, и журнал изменений,
-- у которого словарь колонок один на part и part_drom_card, спутал бы два поля.
--
-- Таблица та же, что завела tenant/080, и по той же причине: тексты не должны ехать
-- ни в сущность Part, ни в снимки audit_log. Пусто — NULL; CHECK отбивает пустую
-- строку и строку из одних пробелов (природа та же, что у снятого штрихкода).
--
-- Expand: прежний код этих колонок не знает, не читает и не пишет.
ALTER TABLE ${tenant.schema}.part_drom_card
    ADD COLUMN drom_kind    text,
    ADD COLUMN drom_section text;

ALTER TABLE ${tenant.schema}.part_drom_card
    ADD CONSTRAINT part_drom_card_drom_kind_ck CHECK (drom_kind ~ '\S'),
    ADD CONSTRAINT part_drom_card_drom_section_ck CHECK (drom_section ~ '\S');

COMMENT ON COLUMN ${tenant.schema}.part_drom_card.drom_kind IS
    'Вид детали как у клиента на Дроме («Карданный вал Lexus GX400»), дословно. Не part_kind_id и не part.section. NULL — не заполнено';
COMMENT ON COLUMN ${tenant.schema}.part_drom_card.drom_section IS
    'Раздел объявления как у клиента на Дроме («Автозапчасти → Трансмиссия»), дословно. Не part.section («Секция» — место на складе). NULL — не заполнено';

--rollback-теряет вид детали и раздел как у клиента у каждой позиции: строки, перенесённые файлом переноса или поправленные на карточке, из базы не восстановить, кроме повторной загрузки того же файла (она вернёт перенесённые значения, но правки владельца на карточке пропадут). Блоки гарантии, самовывоза и доставки и сами позиции остаются
--rollback ALTER TABLE ${tenant.schema}.part_drom_card DROP CONSTRAINT part_drom_card_drom_kind_ck, DROP CONSTRAINT part_drom_card_drom_section_ck, DROP COLUMN drom_kind, DROP COLUMN drom_section;
