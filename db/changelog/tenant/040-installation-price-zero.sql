--liquibase formatted sql

--changeset platform:tenant-040-installation-price-zero
--comment Разовая починка цены установки, приехавшей нулём из прежней системы.
--comment
--comment В её выгрузке незаполненная «Установка» приходит нулём, и импортёр
--comment писал его как есть: у прогонного клиента 367 позиций из 381
--comment утверждали «установим за 0 ₽». Пока цена установки нигде
--comment не показывалась, это было незаметно; в карточке товара это уже
--comment обещание покупателю.
--comment
--comment Чистка безопасна: писать это поле умел только импорт, значит
--comment намеренного нуля в базе взяться неоткуда. Сам импортёр исправлен
--comment в ту же сторону, иначе следующий перенос принесёт нули заново.
UPDATE ${tenant.schema}.part
   SET installation_price = NULL
 WHERE installation_price = 0;
--rollback SELECT 1;
