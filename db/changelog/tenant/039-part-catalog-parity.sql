--liquibase formatted sql

--changeset platform:tenant-039-part-catalog-parity
--comment Поля карточки, которых не хватало для паритета с таблицей товаров
--comment прежней системы.
--comment
--comment Сверено с живым каталогом клиента (35 759 позиций): из сорока двух
--comment его колонок у нас не было трёх полей вовсе, остальные лежали
--comment в базе и просто не доезжали до витрины.
--comment
--comment video_url — ссылка на ролик о детали. У клиента колонка есть
--comment и пуста, но пустая колонка в чужой системе означает «пока не сняли»,
--comment а не «не нужно»: на видео показывают работу двигателя и целость
--comment корпуса, и по нему деталь продают дороже.
--comment
--comment text_block — свободный текст объявления. От «Комментария» отличается
--comment назначением: комментарий пишут для себя («скол на креплении»),
--comment текстовый блок уезжает покупателю.
--comment
--comment updated_by — кто изменил карточку последним. Дата изменения была
--comment с самого начала, а имя — нет: «изменено 31 июля» не отвечает
--comment на вопрос, к кому идти с вопросом.
ALTER TABLE ${tenant.schema}.part
    ADD COLUMN video_url  text,
    ADD COLUMN text_block text,
    ADD COLUMN updated_by bigint REFERENCES ${tenant.schema}.tenant_member;
--rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN video_url, DROP COLUMN text_block, DROP COLUMN updated_by;
