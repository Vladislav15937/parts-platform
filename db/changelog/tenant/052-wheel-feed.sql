--liquibase formatted sql

--changeset platform:tenant-052-account-product-line
--comment Выгрузка знает, чем торгует: запчастями или колёсами.
--comment
--comment Площадка требует этого прямо: «создавайте отдельный прайс-лист
--comment для шин; если вы торгуете шинами, дисками и автозапчастями, то для
--comment шин рекомендуется сделать отдельный прайс-лист»
--comment (farpost.ru/help/trebovaniya_k_price_listam_po_shinam, п. 1.2).
--comment И это не каприз разметки: у шины свои поля — маркировка, сезон,
--comment шиповка, износ, — которых у фары нет и быть не может, а у прайса
--comment запчастей своя настройка разбора на стороне Дрома.
--comment
--comment Отдельной таблицы не заводим: выгрузка и так строка
--comment marketplace_account со своим отбором и своей постоянной ссылкой.
--comment Меняется только то, какой генератор её собирает.
ALTER TABLE ${tenant.schema}.marketplace_account
    ADD COLUMN product_line text NOT NULL DEFAULT 'PART',
    ADD CONSTRAINT marketplace_account_line_ck CHECK (product_line IN ('PART', 'WHEEL'));
--rollback ALTER TABLE ${tenant.schema}.marketplace_account DROP CONSTRAINT marketplace_account_line_ck, DROP COLUMN product_line;
