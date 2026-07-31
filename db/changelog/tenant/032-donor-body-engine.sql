--liquibase formatted sql

--changeset platform:tenant-310-donor-body-engine
--comment Кузов и двигатель донора — своими колонками, а не строкой в заметке.
--comment
--comment В выгрузке предыдущей системы это отдельные поля, и на витрине склада
--comment они отдельные колонки: по «ACA33» и «2AZFE» продавец отличает
--comment подходящую деталь от неподходящей. Импорт складывал их в note —
--comment «временное хранилище до сопоставления с каталогом», как там и
--comment написано, — и на витрине четыре колонки из двадцати шести оставались
--comment пустыми.
--comment
--comment Текстом, а не ссылкой на catalog.modification: у переехавшего клиента
--comment это его собственные значения, и подобрать к ним модификацию из чужого
--comment каталога значит угадать. Ссылка остаётся для машин, заведённых
--comment у нас: там модификацию выбирают из справочника.
ALTER TABLE ${tenant.schema}.donor
    ADD COLUMN body_code   text,
    ADD COLUMN engine_code text;
--rollback ALTER TABLE ${tenant.schema}.donor DROP COLUMN engine_code, DROP COLUMN body_code;
