--liquibase formatted sql

-- Выгрузок на площадку бывает несколько, и различаются они отбором товара.
-- У живого клиента их пять на один Дром: «новые», «низкая», «средняя»,
-- «высокая», «очень высокая» — по ценовым диапазонам, каждая со своим
-- прайс-листом в кабинете площадки и своей ценой размещения.
--
-- Структурно несколько выгрузок были возможны и раньше: marketplace_account
-- уникален по паре «площадка + название», и у каждой строки свой feed_token.
-- Не хватало отбора: генератор отдавал весь склад независимо от того,
-- по какой ссылке пришли.

--changeset platform:tenant-260-feed-filters
--comment Отбор колонками, а не в settings jsonb: по ним фильтрует SQL прайса,
--comment и условие вида settings->>'priceFrom' читается хуже и индексируется
--comment хуже, чем колонка.
--comment
--comment Пусто в любом фильтре означает «без ограничения», а не «ничего»:
--comment выгрузка, у которой не заполнили цену, обязана отдавать весь склад,
--comment а не пустой прайс. Пустой прайс площадка примет молча, и объявления
--comment пропадут вместе с накопленными просмотрами.
ALTER TABLE ${tenant.schema}.marketplace_account
    ADD COLUMN price_from    numeric(14,2),
    ADD COLUMN price_to      numeric(14,2),
    ADD COLUMN conditions    text[],
    ADD COLUMN warehouse_ids bigint[];

ALTER TABLE ${tenant.schema}.marketplace_account
    ADD CONSTRAINT marketplace_account_price_range_ck
    CHECK (price_from IS NULL OR price_to IS NULL OR price_from <= price_to);

-- Состояние ограничено теми же значениями, что и у детали: опечатка
-- в фильтре даёт молча пустой прайс, а заметят это по исчезнувшим
-- объявлениям через сутки.
ALTER TABLE ${tenant.schema}.marketplace_account
    ADD CONSTRAINT marketplace_account_conditions_ck
    CHECK (conditions IS NULL OR conditions <@ ARRAY['NEW','USED','REFURBISHED']::text[]);
--rollback ALTER TABLE ${tenant.schema}.marketplace_account DROP CONSTRAINT marketplace_account_conditions_ck;
--rollback ALTER TABLE ${tenant.schema}.marketplace_account DROP CONSTRAINT marketplace_account_price_range_ck;
--rollback ALTER TABLE ${tenant.schema}.marketplace_account DROP COLUMN warehouse_ids, DROP COLUMN conditions, DROP COLUMN price_to, DROP COLUMN price_from;
