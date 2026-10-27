--liquibase formatted sql

--changeset partsflow:tenant-104-part-applicability-other-bodies-engines
--comment Другие кузова и двигатели машины в part_applicability — две текстовые колонки, задача 0296
--
-- other_bodies — «Другие кузова» машины из блока «Подходит» карточки Дрома дословно, через «, »
-- (например «GRJ120, GRJ120W, KDJ120»): кузова машины, не привязанные к поколению.
-- engines — двигатели из строки «Для двигателей — …» того же блока дословно, через «, »
-- (например «1GRFE, 1KDFTV»). Обе пишет разбор блока (DromApplicability и
-- DromApplicabilityService), машиной; руками их не вводят.
--
-- other_bodies пишется на каждую строку применимости этой машины (марка и модель) у позиции.
-- NULL — блок кузова не назвал, строка поставлена до задачи 0296, руками или разбором наименования.
--
-- engines пишется только когда машина в блоке одна: строка «Для двигателей» относится к блоку
-- целиком, и при нескольких машинах двигатель каждой из неё не определить. NULL — двигателей
-- в блоке нет, машин в блоке несколько, либо строка поставлена не разбором блока.
--
-- CHECK отбивает пустую строку и строку из одних пробелов (так же, как у
-- part_applicability.generation_label, tenant/085).
--
-- Существующие строки здесь не заполняются: колонки дополняет повторный проход разбора в Java,
-- логике в базе не место. Индексов нет намеренно: показ, отбор и сортировка идут агрегатом
-- над строками своей машины позиции, по колонкам напрямую не ищут.
--
-- Expand: прежний код колонок не знает, не читает и не пишет.
ALTER TABLE ${tenant.schema}.part_applicability
    ADD COLUMN other_bodies text,
    ADD COLUMN engines text;

ALTER TABLE ${tenant.schema}.part_applicability
    ADD CONSTRAINT part_applicability_other_bodies_ck CHECK (other_bodies ~ '\S'),
    ADD CONSTRAINT part_applicability_engines_ck CHECK (engines ~ '\S');

COMMENT ON COLUMN ${tenant.schema}.part_applicability.other_bodies IS
    'Другие кузова машины из блока «Подходит» Дрома дословно, через «, » (например «GRJ120, GRJ120W, KDJ120»): кузова, не привязанные к поколению. Пишет разбор блока, не человек. NULL — блок их не назвал, строка поставлена до задачи 0296, руками или разбором наименования. Не в поиске';

COMMENT ON COLUMN ${tenant.schema}.part_applicability.engines IS
    'Двигатели из строки «Для двигателей — …» блока «Подходит» Дрома дословно, через «, » (например «1GRFE, 1KDFTV»). Пишет разбор блока, не человек, и только когда машина в блоке одна. NULL — двигателей в блоке нет, машин в блоке несколько, либо строка поставлена не разбором блока. Не в поиске';

--rollback-теряет другие кузова и двигатели у строк применимости: они проставлены разбором блока «Подходит»; восстанавливаются повторным нажатием «Разложить «Подходит»», текст блока лежит дословно в part_drom_card.applicability. Сами строки применимости и позиции остаются
--rollback ALTER TABLE ${tenant.schema}.part_applicability DROP CONSTRAINT part_applicability_other_bodies_ck, DROP CONSTRAINT part_applicability_engines_ck, DROP COLUMN other_bodies, DROP COLUMN engines;
