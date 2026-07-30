--liquibase formatted sql

--changeset platform:tenant-170-feed-token
--comment Секрет постоянной ссылки на прайс площадки.
--comment
--comment Дром забирает полный прайс сам по ссылке, которую техспециалист
--comment площадки прописывает в настройках прайс-листа один раз. Значит ссылка
--comment постоянная и без сессии: подписанная на 15 минут, как фотографии,
--comment здесь не годится — она протухнет до первого же забора.
--comment
--comment Отсюда секрет в самой ссылке. Это делает её доступом к складу того,
--comment кто её знает: остаток, цены и коды деталей видны без входа. Поэтому
--comment токен длинный и случайный, а не производный от кода компании,
--comment и его можно сменить, не трогая ничего больше.
ALTER TABLE ${tenant.schema}.marketplace_account ADD COLUMN feed_token text;

CREATE UNIQUE INDEX marketplace_account_feed_token_uk
    ON ${tenant.schema}.marketplace_account (feed_token)
    WHERE feed_token IS NOT NULL;

ALTER TABLE ${tenant.schema}.marketplace_account
    ADD CONSTRAINT marketplace_account_feed_token_ck
    CHECK (feed_token IS NULL OR length(feed_token) >= 32);

--rollback ALTER TABLE ${tenant.schema}.marketplace_account DROP CONSTRAINT marketplace_account_feed_token_ck;
--rollback DROP INDEX ${tenant.schema}.marketplace_account_feed_token_uk;
--rollback ALTER TABLE ${tenant.schema}.marketplace_account DROP COLUMN feed_token;
