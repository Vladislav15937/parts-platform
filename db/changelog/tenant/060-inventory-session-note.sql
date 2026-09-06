--liquibase formatted sql

--changeset partsflow:tenant-060-inventory-session-note
--comment Комментарий кладовщика к пересчёту — задача 0020, пункты приёмки 6 и 7
--
-- У ориентира в списке инвентаризаций комментарий и есть то, ради чего в него
-- заходят («83619 не найден», «Не сканировали»); у нас поля не было вовсе.
-- Пишут его с телефона по ходу подсчёта, поэтому колонка nullable и без
-- умолчания — пусто до тех пор, пока никто ничего не написал, а не пустая
-- строка. Наполнение и запрет правки после проведения/отмены — на стороне
-- Java (задача 0020), здесь только форма данных.
ALTER TABLE ${tenant.schema}.inventory_session
    ADD COLUMN note text;

--rollback ALTER TABLE ${tenant.schema}.inventory_session DROP COLUMN note;
