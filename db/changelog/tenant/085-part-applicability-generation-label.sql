--liquibase formatted sql

--changeset partsflow:tenant-085-part-applicability-generation-label
--comment Метка поколения Дрома в part_applicability — текстовая колонка, задача 0297
--
-- Метка поколения из блока «Подходит» объявления Дрома дословно: только «N поколение»
-- (например «4 поколение»), без «рестайлинг» и без годов. Пишет её разбор блока
-- (DromApplicability и DromApplicabilityService, задача 0289), машиной; руками её не вводят.
--
-- NULL — метки нет: строка поставлена до задачи 0297, руками в карточке, разбором
-- наименования, либо поколение в тексте блока не названо номером. CHECK отбивает пустую
-- строку и строку из одних пробелов (так же, как у part_drom_card.may_fit, tenant/084).
--
-- Существующие строки здесь не заполняются: метку дополняет повторный проход разбора в Java,
-- логике в базе не место. Индекса нет намеренно: по колонке не ищут, показ идёт агрегатом
-- над строками позиции.
--
-- Expand: прежний код колонки не знает, не читает и не пишет.
ALTER TABLE ${tenant.schema}.part_applicability
    ADD COLUMN generation_label text;

ALTER TABLE ${tenant.schema}.part_applicability
    ADD CONSTRAINT part_applicability_generation_label_ck CHECK (generation_label ~ '\S');

COMMENT ON COLUMN ${tenant.schema}.part_applicability.generation_label IS
    'Метка поколения Дрома дословно из блока «Подходит»: только «N поколение» (например «4 поколение»), без «рестайлинг» и годов. Пишет разбор блока, не человек. NULL — метки нет: строка поставлена до задачи 0297, руками, разбором наименования, либо поколение в тексте не названо номером. Не в поиске';

--rollback-теряет метки поколения Дрома у строк применимости: они проставлены разбором блока «Подходит»; восстанавливаются повторным нажатием «Разложить «Подходит»», текст блока лежит дословно в part_drom_card.applicability. Сами строки применимости и позиции остаются
--rollback ALTER TABLE ${tenant.schema}.part_applicability DROP CONSTRAINT part_applicability_generation_label_ck, DROP COLUMN generation_label;
