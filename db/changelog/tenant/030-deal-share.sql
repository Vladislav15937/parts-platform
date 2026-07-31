--liquibase formatted sql

--changeset platform:tenant-290-deal-share
--comment Ссылка на сделку для клиента: продавец отправляет её сам — в Telegram,
--comment WhatsApp или SMS со своего телефона.
--comment
--comment Своего отправителя не заводим: SMS и Telegram — это договор с
--comment провайдером и деньги, а интерфейс, написанный раньше первого договора,
--comment повторит форму воображаемого провайдера. Ссылка же работает в любом
--comment канале и не требует ничего.
--comment
--comment Секрет в самом адресе, как у прайса площадки: у клиента нет и не будет
--comment учётной записи. Отсюда два ограничения. Первое — срок: ссылка
--comment на отложенный товар нужна дни, а не годы, и просроченная перестаёт
--comment показывать склад тому, кто её однажды получил. Второе — состав:
--comment по ссылке видно ровно то, что клиент и так знает про свою покупку,
--comment без закупочной цены и без чужих сделок.
ALTER TABLE ${tenant.schema}.deal
    ADD COLUMN share_token   text,
    ADD COLUMN share_expires timestamptz;

CREATE UNIQUE INDEX deal_share_token_uk
    ON ${tenant.schema}.deal (share_token)
    WHERE share_token IS NOT NULL;

ALTER TABLE ${tenant.schema}.deal ADD CONSTRAINT deal_share_token_ck
    CHECK (share_token IS NULL OR length(share_token) >= 32);
--rollback ALTER TABLE ${tenant.schema}.deal DROP CONSTRAINT deal_share_token_ck;
--rollback DROP INDEX ${tenant.schema}.deal_share_token_uk;
--rollback ALTER TABLE ${tenant.schema}.deal DROP COLUMN share_expires, DROP COLUMN share_token;
