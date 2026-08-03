--liquibase formatted sql

--changeset platform:tenant-043-outbox-claim
--comment Заявка на пачку событий: отметка «эту пачку уже кто-то отправляет».
--comment
--comment Нужна, потому что отправка в транспорт уезжает из транзакции БД.
--comment Раньше пачку держала блокировка строк FOR UPDATE SKIP LOCKED, а
--comment вместе с ней держались транзакция и соединение — всё время, пока
--comment брокер отвечает. При delivery.timeout.ms в две минуты лежащая Kafka
--comment останавливала релей всей ячейки: обход арендаторов однопоточный,
--comment и клиент, стоящий в реестре двухсотым, ждал первого.
--comment
--comment После коммита блокировка снимается, поэтому пачку надо пометить
--comment в самой строке. Отметка со сроком, а не флаг: процесс, умерший между
--comment отправкой и пометкой, оставил бы событие заявленным навсегда,
--comment то есть потерял бы его тихо. Просроченная заявка забирается заново —
--comment это тот же at-least-once, от которого потребители защищены
--comment вставкой в processed_event.
ALTER TABLE ${tenant.schema}.outbox ADD COLUMN claimed_at timestamptz;
--rollback ALTER TABLE ${tenant.schema}.outbox DROP COLUMN claimed_at;
