--liquibase formatted sql

--changeset partsflow:tenant-061-feed-off-and-deleted
--comment Удалённая выгрузка помечается, а не удаляется; её название и имя файла освобождаются

-- Выключить выгрузку было нечем: ссылка живёт вечно, и площадка продолжает
-- забирать товар, который клиент там видеть не хочет. Единственным обходным
-- путём была смена токена — то есть поломка адреса, уже прописанного
-- в кабинете площадки, без единого слова о том, почему он перестал работать.
--
-- Выключение при этом колонки не требует: status у marketplace_account есть
-- с самого начала (ACTIVE/PAUSED/ERROR), и выдача прайса с отправкой дельт
-- и так читают только ACTIVE. Здесь заводится второе — удаление.
--
-- ПОЧЕМУ УДАЛЁННАЯ СТРОКА ОСТАЁТСЯ. Решение владельца продукта от 5 сентября
-- 2026 (tasks/0003-vygruzku-mozhno-vyklyuchit.md, раздел «Что известно»):
-- помечать удалённой, а не удалять. Удаление уносит историю отправок,
-- а спрашивают именно её — «эта выгрузка вообще работала и когда её последний
-- раз забирали». Строка, удалённая полгода назад, отвечает на такой вопрос
-- лучше, чем её отсутствие. Плюс к тому на marketplace_account ссылаются
-- publication_log, listing и price_rule: настоящий DELETE упёрся бы во внешний
-- ключ, то есть «удалить» означало бы «удалить вместе с журналом публикаций».
--
-- ПОЧЕМУ ОТДЕЛЬНАЯ КОЛОНКА, А НЕ ЕЩЁ ОДНО ЗНАЧЕНИЕ status. Три довода, и все
-- три про то, что это разные величины. Первый: status отвечает на вопрос
-- «работает ли выгрузка сейчас», и его пишет не только человек — соседняя
-- last_error приезжает от площадки, и однажды написанный туда же ERROR
-- воскресил бы удалённую выгрузку молча. Второй: удаление — событие, и его
-- время и есть ответ на вопрос про историю; статус времени не хранит.
-- Третий: освобождение имён (ниже) выражается предикатом «deleted_at IS NULL»,
-- то есть тем же понятием, а не перечислением живых статусов, которое
-- пришлось бы дописывать при каждом новом статусе.
--
-- ОСВОБОЖДЕНИЕ ИМЁН — ЧАСТЬ ТОГО ЖЕ РЕШЕНИЯ, А НЕ УДОБСТВО. Уникальность тут
-- заведена ради человека, а не ради базы: имя файла (changeset 059) сверяет
-- глазами техспециалист площадки, название выгрузки (marketplace_account_uk
-- из 007) владелец придумывает сам. Оставленные за удалённой строкой, они
-- отвечают на попытку завести выгрузку заново отказом, который называет
-- запись, невидимую в списке, — ровно та ложь, ради устранения которой
-- уникальность и объясняется словами. Поэтому обе уникальности сужаются
-- до живых строк: удалённая уходит из индекса и перестаёт занимать имя,
-- сохраняя его в себе для истории.
ALTER TABLE ${tenant.schema}.marketplace_account
    ADD COLUMN deleted_at timestamptz;

COMMENT ON COLUMN ${tenant.schema}.marketplace_account.deleted_at IS
    'Когда выгрузку удалили; NULL — живая. Помеченная не отдаётся по ссылке, не видна в списке, но хранит отметки о заборе и отправках';

-- Название уникально среди живых. Ограничение таблицы частичным не бывает,
-- поэтому оно снимается и заменяется индексом: имя индекса другое, чтобы
-- откат вернул именно прежнее ограничение, а не одноимённый объект другой
-- природы.
ALTER TABLE ${tenant.schema}.marketplace_account
    DROP CONSTRAINT marketplace_account_uk;

CREATE UNIQUE INDEX marketplace_account_title_uk
    ON ${tenant.schema}.marketplace_account (marketplace, title)
    WHERE deleted_at IS NULL;

-- Имя файла — то же самое, только индекс уже был частичным: NULL означает
-- «имени не задавали», и такие строки в него не попадают вовсе.
DROP INDEX ${tenant.schema}.marketplace_account_feed_file_uk;

CREATE UNIQUE INDEX marketplace_account_feed_file_uk
    ON ${tenant.schema}.marketplace_account (marketplace, feed_file_name)
    WHERE feed_file_name IS NOT NULL AND deleted_at IS NULL;

-- Откат возвращает прежнее состояние в обратном порядке, и порядок здесь
-- смысловой: индексы по deleted_at обязаны исчезнуть до самой колонки,
-- а прежний индекс имени файла — появиться раньше, чем откат changeset'а 059
-- станет его удалять. Иначе разворот встаёт на «index does not exist», и видно
-- это только полным откатом на чистой базе (db/verify.sh, шаг 7).
--rollback DROP INDEX ${tenant.schema}.marketplace_account_feed_file_uk;
--rollback DROP INDEX ${tenant.schema}.marketplace_account_title_uk;
--rollback ALTER TABLE ${tenant.schema}.marketplace_account DROP COLUMN deleted_at;
--rollback CREATE UNIQUE INDEX marketplace_account_feed_file_uk ON ${tenant.schema}.marketplace_account (marketplace, feed_file_name) WHERE feed_file_name IS NOT NULL;
--rollback ALTER TABLE ${tenant.schema}.marketplace_account ADD CONSTRAINT marketplace_account_uk UNIQUE (marketplace, title);
