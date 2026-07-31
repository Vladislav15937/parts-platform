--liquibase formatted sql

-- Заказ с площадки: покупатель уже оформил и заплатил, а мы об этом узнаём
-- последними. До этой миграции такая продажа заводилась обычной сделкой,
-- а номер заказа площадки жил в комментарии к доставке — то есть по нему
-- нельзя было ни найти, ни защититься от повторного заведения.

--changeset platform:tenant-230-deal-source-seed
--comment Справочник источников заполняется, а не остаётся пустым. Пустой он
--comment означает, что поле «источник» в сделке никто не заполнит: выбирать
--comment не из чего, а заводить руками в момент продажи никто не станет.
--comment Каналы взяты те, что реально встречаются на разборке; клиент
--comment добавляет свои, лишние архивирует.
INSERT INTO ${tenant.schema}.deal_source (name) VALUES
    ('Дром'),
    ('Дром: защищённая сделка'),
    ('Авито'),
    ('Авито: доставка'),
    ('Звонок'),
    ('Пришёл сам'),
    ('Постоянный клиент')
ON CONFLICT (name) DO NOTHING;
--rollback DELETE FROM ${tenant.schema}.deal_source WHERE name IN ('Дром','Дром: защищённая сделка','Авито','Авито: доставка','Звонок','Пришёл сам','Постоянный клиент');

--changeset platform:tenant-231-deal-external-order
--comment Номер заказа площадки — колонкой, а не текстом в примечании.
--comment По нему сверяются с кабинетом площадки, когда покупатель звонит
--comment и называет свой номер, а не наш.
--comment
--comment marketplace — машинный код, а не ссылка на deal_source: справочник
--comment источников редактирует клиент, и ключ идемпотентности, зависящий
--comment от переименованной им строки, перестаёт быть ключом.
ALTER TABLE ${tenant.schema}.deal
    ADD COLUMN marketplace       text,
    ADD COLUMN external_order_no text,
    ADD COLUMN reply_deadline    timestamptz,
    ADD COLUMN order_accepted_at timestamptz;

ALTER TABLE ${tenant.schema}.deal ADD CONSTRAINT deal_marketplace_ck
    CHECK (marketplace IS NULL OR marketplace IN ('DROM', 'AVITO'));

-- Номер заказа без площадки и площадка без номера одинаково бесполезны:
-- по такой паре не найти заказ и не защититься от повтора.
ALTER TABLE ${tenant.schema}.deal ADD CONSTRAINT deal_external_order_ck
    CHECK ((marketplace IS NULL) = (external_order_no IS NULL));

-- Повторное заведение того же заказа — это вторая сделка на тот же товар,
-- то есть двойной резерв и обещание одной детали двум покупателям. Стережёт
-- индекс, а не проверка в коде: та пропускает второй одновременный запрос.
-- Ровно так же защищена приёмка.
CREATE UNIQUE INDEX deal_external_order_uk
    ON ${tenant.schema}.deal (marketplace, external_order_no)
    WHERE external_order_no IS NOT NULL;

-- Очередь «ждут ответа»: заказы, по которым продавец ещё не ответил площадке.
-- Частичный индекс — потому что таких единицы против десятков тысяч сделок.
CREATE INDEX deal_awaiting_reply_ix
    ON ${tenant.schema}.deal (reply_deadline)
    WHERE external_order_no IS NOT NULL AND order_accepted_at IS NULL;
--rollback DROP INDEX ${tenant.schema}.deal_awaiting_reply_ix;
--rollback DROP INDEX ${tenant.schema}.deal_external_order_uk;
--rollback ALTER TABLE ${tenant.schema}.deal DROP CONSTRAINT deal_external_order_ck;
--rollback ALTER TABLE ${tenant.schema}.deal DROP CONSTRAINT deal_marketplace_ck;
--rollback ALTER TABLE ${tenant.schema}.deal DROP COLUMN order_accepted_at, DROP COLUMN reply_deadline, DROP COLUMN external_order_no, DROP COLUMN marketplace;
