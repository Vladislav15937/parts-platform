--liquibase formatted sql

--changeset partsflow:tenant-079-part-side-both-fr-ud
--comment Сторона «обе» (BOTH) и у продольной, и у вертикальной оси — «Переднее, Заднее», «Верхнее, Нижнее», задача 0283
--
-- Тот же довод, что у tenant/078 для поперечной оси: у клиента на карточке
-- Дрома стоит «Переднее, Заднее» (37 объявлений из 2 897 при переносе 14
-- октября 2026), и одним значением FRONT|REAR это не записать, а две
-- независимые отметки дали бы и четвёртое состояние «ни одна», уже
-- выраженное NULL. Третье значение, а не вторая колонка. «Верхнее, Нижнее»
-- на образце не встретилось, но разбор устроен так же и другой клиент
-- может его заполнить — ось включена, чтобы не повторять задачу.
-- Перечень расширяется — это expand: прежний код BOTH не пишет.
ALTER TABLE ${tenant.schema}.part
    DROP CONSTRAINT part_side_fr_ck,
    DROP CONSTRAINT part_side_ud_ck;

ALTER TABLE ${tenant.schema}.part
    ADD CONSTRAINT part_side_fr_ck CHECK (side_fr IS NULL OR side_fr IN ('FRONT','REAR','BOTH')),
    ADD CONSTRAINT part_side_ud_ck CHECK (side_ud IS NULL OR side_ud IN ('UPPER','LOWER','BOTH'));

-- ОТКАТ СУЖАЕТ ПЕРЕЧНИ, ПОЭТОМУ СНАЧАЛА УВОДИТ ДАННЫЕ, как у tenant/078:
-- позиция с BOTH уходит в NULL, а не в одну из сторон — любая одна была бы
-- неправдой о детали, которая подходит на обе. db/verify.sh шаг 7 идёт по
-- пустым таблицам и этого не видит: без UPDATE разворот у клиента с
-- переехавшим «Переднее, Заднее» встал бы на «check constraint is violated
-- by some row» (та же порода, что у tenant/062, 070 и 078).
--rollback-теряет сторону «обе» по продольной и вертикальной осям: такие позиции остаются без этой стороны, в прайсе у них пропадают fr/ud, заголовки не пересобираются
--rollback UPDATE ${tenant.schema}.part SET side_fr = NULL WHERE side_fr = 'BOTH';
--rollback UPDATE ${tenant.schema}.part SET side_ud = NULL WHERE side_ud = 'BOTH';
--rollback ALTER TABLE ${tenant.schema}.part DROP CONSTRAINT part_side_fr_ck, DROP CONSTRAINT part_side_ud_ck;
--rollback ALTER TABLE ${tenant.schema}.part ADD CONSTRAINT part_side_fr_ck CHECK (side_fr IS NULL OR side_fr IN ('FRONT','REAR')), ADD CONSTRAINT part_side_ud_ck CHECK (side_ud IS NULL OR side_ud IN ('UPPER','LOWER'));
