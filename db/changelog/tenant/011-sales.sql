--liquibase formatted sql

-- Продажи по образцу работающей разборки (docs/bazon-parity.md §2, §5, §6).
-- Ключевое отличие от исходной модели: платёж перестаёт быть придатком сделки
-- и становится самостоятельным документом, у клиента появляется лицевой счёт,
-- а возврат — документ со своей нумерацией, а не строка.

--changeset platform:tenant-100-deal-source
--comment Источник сделки — справочник, а не свободный текст в deal.source:
--comment по нему считают, какой канал приносит деньги, и опечатка «Авито»/«авито»
--comment ломает весь отчёт.
CREATE TABLE ${tenant.schema}.deal_source (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text        NOT NULL,
    is_archived boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT deal_source_uk UNIQUE (name)
);
--rollback DROP TABLE ${tenant.schema}.deal_source;

--changeset platform:tenant-101-payment-source
--comment Источники платежей с типом: «Карта Сбер», «р/с Альфа банк», «ККМ»,
--comment «Авито доставка», «В долг». Тип нужен отчётам (наличные отдельно от
--comment расчётного счёта), а сам источник — кассиру.
CREATE TABLE ${tenant.schema}.payment_source (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text        NOT NULL,
    source_type text,
    is_archived boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT payment_source_uk UNIQUE (name),
    CONSTRAINT payment_source_type_ck CHECK (source_type IS NULL OR source_type IN
        ('CASH','BANK_ACCOUNT','ACQUIRING','CREDIT','MARKETPLACE'))
);
--rollback DROP TABLE ${tenant.schema}.payment_source;

--changeset platform:tenant-102-customer-account
--comment Лицевой счёт клиента. Постоянные покупатели — автосервисы и перекупы —
--comment оставляют деньги авансом и разбирают детали в течение недели.
--comment Баланс — агрегат журнала, как и остаток: «сколько на счету» без истории
--comment операций невозможно разобрать, когда клиент с ним не согласен.
ALTER TABLE ${tenant.schema}.customer
    ADD COLUMN balance          numeric(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN reserved_amount  numeric(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN inn              text,
    ADD COLUMN company_name     text,
    -- Примечание видно клиенту и печатается в накладной, заметка — только свои.
    ADD COLUMN public_note      text;

CREATE TABLE ${tenant.schema}.customer_account_entry (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id bigint        NOT NULL REFERENCES ${tenant.schema}.customer ON DELETE CASCADE,
    entry_type  text          NOT NULL,
    amount      numeric(14,2) NOT NULL,
    deal_id     bigint,
    payment_id  bigint,
    comment     text,
    created_by  bigint        REFERENCES ${tenant.schema}.tenant_member,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT customer_entry_type_ck CHECK (entry_type IN
        ('TOP_UP','WITHDRAW','DEAL_PAYMENT','DEAL_REFUND','CORRECTION')),
    CONSTRAINT customer_entry_amount_ck CHECK (amount <> 0)
);
CREATE INDEX customer_entry_customer_ix
    ON ${tenant.schema}.customer_account_entry (customer_id, created_at DESC);
--rollback DROP TABLE ${tenant.schema}.customer_account_entry;
--rollback ALTER TABLE ${tenant.schema}.customer DROP COLUMN public_note, DROP COLUMN company_name, DROP COLUMN inn, DROP COLUMN reserved_amount, DROP COLUMN balance;

--changeset platform:tenant-103-customer-balance-trigger splitStatements:false runOnChange:true
--comment Баланс поддерживается триггером по журналу, как и остаток товара.
--comment Писать в customer.balance из кода нельзя: разойдётся с историей.
CREATE OR REPLACE FUNCTION ${tenant.schema}.customer_balance_apply()
    RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    UPDATE ${tenant.schema}.customer
       SET balance = balance + NEW.amount
     WHERE id = NEW.customer_id;
    RETURN NEW;
END $fn$;
--rollback DROP FUNCTION IF EXISTS ${tenant.schema}.customer_balance_apply();

--changeset platform:tenant-104-customer-balance-trigger-attach
CREATE TRIGGER customer_balance_apply_trg
    AFTER INSERT ON ${tenant.schema}.customer_account_entry
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.customer_balance_apply();

CREATE TRIGGER customer_account_entry_immutable
    BEFORE UPDATE OR DELETE ON ${tenant.schema}.customer_account_entry
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.stock_movement_immutable();
--rollback DROP TRIGGER IF EXISTS customer_account_entry_immutable ON ${tenant.schema}.customer_account_entry;
--rollback DROP TRIGGER IF EXISTS customer_balance_apply_trg ON ${tenant.schema}.customer_account_entry;

--changeset platform:tenant-105-deal-fields
--comment Срок резерва хранится в сделке, а не выводится из reservation:
--comment менеджер продлевает его в самой сделке, и это первое, что он делает
--comment по звонку «придержите до завтра».
ALTER TABLE ${tenant.schema}.deal
    ADD COLUMN deal_source_id bigint REFERENCES ${tenant.schema}.deal_source,
    ADD COLUMN warehouse_id   bigint REFERENCES ${tenant.schema}.warehouse,
    ADD COLUMN reserved_until timestamptz,
    ADD COLUMN paid_amount    numeric(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN delivery_note  text,
    ADD COLUMN issued_at      timestamptz;

-- Статусы приведены к тем, что реально существуют на разборке. «Истёк срок»
-- и «частично оплачен» сюда не входят намеренно: это не состояния, а следствия
-- срока резерва и суммы оплат, и хранить их значило бы дублировать данные,
-- которые разъедутся.
ALTER TABLE ${tenant.schema}.deal DROP CONSTRAINT deal_status_ck;
ALTER TABLE ${tenant.schema}.deal ADD CONSTRAINT deal_status_ck CHECK (status IN
    ('DRAFT','RESERVED','READY','ISSUED','CANCELLED','RETURNED'));

CREATE INDEX deal_reserved_until_ix ON ${tenant.schema}.deal (reserved_until)
    WHERE status = 'RESERVED';
--rollback ALTER TABLE ${tenant.schema}.deal DROP CONSTRAINT deal_status_ck;
--rollback ALTER TABLE ${tenant.schema}.deal ADD CONSTRAINT deal_status_ck CHECK (status IN ('DRAFT','RESERVED','PAID','SHIPPED','COMPLETED','CANCELLED','RETURNED'));
--rollback ALTER TABLE ${tenant.schema}.deal DROP COLUMN issued_at, DROP COLUMN delivery_note, DROP COLUMN paid_amount, DROP COLUMN reserved_until, DROP COLUMN warehouse_id, DROP COLUMN deal_source_id;

--changeset platform:tenant-106-deal-item-status
--comment Статус на уровне позиции, а не только документа: часть сделки могут
--comment выдать, часть вернуть, а часть отменить, и в списке «по товарам»
--comment продавец смотрит именно на позицию.
ALTER TABLE ${tenant.schema}.deal_item
    ADD COLUMN status       text NOT NULL DEFAULT 'RESERVED',
    ADD COLUMN warehouse_id bigint REFERENCES ${tenant.schema}.warehouse,
    ADD CONSTRAINT deal_item_status_ck CHECK (status IN
        ('RESERVED','ISSUED','RETURNED','CANCELLED'));
--rollback ALTER TABLE ${tenant.schema}.deal_item DROP CONSTRAINT deal_item_status_ck, DROP COLUMN warehouse_id, DROP COLUMN status;

--changeset platform:tenant-107-payment-document
--comment Платёж становится самостоятельным документом: бывает приход без сделки
--comment (пополнение лицевого счёта) и расход (возврат денег, инкассация).
--comment Поэтому deal_id перестаёт быть обязательным, а направление задаётся явно.
ALTER TABLE ${tenant.schema}.payment
    ALTER COLUMN deal_id DROP NOT NULL,
    ADD COLUMN customer_id       bigint REFERENCES ${tenant.schema}.customer,
    ADD COLUMN payment_source_id bigint REFERENCES ${tenant.schema}.payment_source,
    ADD COLUMN direction         text NOT NULL DEFAULT 'IN',
    ADD COLUMN comment           text,
    ADD CONSTRAINT payment_direction_ck CHECK (direction IN ('IN','OUT'));

-- payment_type был перечислением способов оплаты; его роль занял справочник
-- payment_source, поэтому ограничение снимается, а колонка остаётся для
-- совместимости с уже написанным кодом.
ALTER TABLE ${tenant.schema}.payment DROP CONSTRAINT payment_type_ck;
ALTER TABLE ${tenant.schema}.payment ALTER COLUMN payment_type DROP NOT NULL;

CREATE INDEX payment_customer_ix ON ${tenant.schema}.payment (customer_id, paid_at DESC)
    WHERE customer_id IS NOT NULL;
CREATE INDEX payment_source_ix ON ${tenant.schema}.payment (payment_source_id);
--rollback DROP INDEX ${tenant.schema}.payment_source_ix;
--rollback DROP INDEX ${tenant.schema}.payment_customer_ix;
--rollback ALTER TABLE ${tenant.schema}.payment DROP CONSTRAINT payment_direction_ck, DROP COLUMN comment, DROP COLUMN direction, DROP COLUMN payment_source_id, DROP COLUMN customer_id;
--rollback ALTER TABLE ${tenant.schema}.payment ALTER COLUMN payment_type SET NOT NULL;
--rollback ALTER TABLE ${tenant.schema}.payment ADD CONSTRAINT payment_type_ck CHECK (payment_type IN ('CASH','CARD','ONLINE','TRANSFER'));
--rollback ALTER TABLE ${tenant.schema}.payment ALTER COLUMN deal_id SET NOT NULL;

--changeset platform:tenant-108-return-document
--comment Возврат становится документом со своей нумерацией и складом возврата.
--comment Строкой он быть не может: возвращают несколько позиций разом, документ
--comment печатают, а склад возврата не обязан совпадать со складом выдачи.
CREATE SEQUENCE ${tenant.schema}.return_number_seq START 1;

ALTER TABLE ${tenant.schema}.deal_return
    ADD COLUMN number       bigint,
    ADD COLUMN customer_id  bigint REFERENCES ${tenant.schema}.customer,
    ADD COLUMN warehouse_id bigint REFERENCES ${tenant.schema}.warehouse,
    ADD COLUMN status       text NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN completed_at timestamptz,
    ADD CONSTRAINT deal_return_status_ck CHECK (status IN ('DRAFT','DONE','CANCELLED'));

ALTER TABLE ${tenant.schema}.deal_return
    ALTER COLUMN number SET DEFAULT nextval('${tenant.schema}.return_number_seq');
UPDATE ${tenant.schema}.deal_return SET number = nextval('${tenant.schema}.return_number_seq')
    WHERE number IS NULL;
ALTER TABLE ${tenant.schema}.deal_return ALTER COLUMN number SET NOT NULL;
ALTER TABLE ${tenant.schema}.deal_return ADD CONSTRAINT deal_return_number_uk UNIQUE (number);

-- Позиции переезжают в отдельную таблицу: part_id и quantity были полями
-- документа, а должны быть строками.
ALTER TABLE ${tenant.schema}.deal_return
    ALTER COLUMN part_id DROP NOT NULL,
    ALTER COLUMN quantity DROP NOT NULL;

CREATE TABLE ${tenant.schema}.deal_return_item (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    return_id bigint        NOT NULL REFERENCES ${tenant.schema}.deal_return ON DELETE CASCADE,
    part_id   bigint        NOT NULL REFERENCES ${tenant.schema}.part,
    quantity  numeric(12,3) NOT NULL,
    amount    numeric(14,2) NOT NULL,
    restocked boolean       NOT NULL DEFAULT true,
    CONSTRAINT deal_return_item_qty_ck CHECK (quantity > 0)
);
CREATE INDEX deal_return_item_return_ix ON ${tenant.schema}.deal_return_item (return_id);
CREATE INDEX deal_return_status_ix ON ${tenant.schema}.deal_return (status, created_at DESC);
--rollback DROP TABLE ${tenant.schema}.deal_return_item;
--rollback ALTER TABLE ${tenant.schema}.deal_return DROP CONSTRAINT deal_return_number_uk, DROP CONSTRAINT deal_return_status_ck, DROP COLUMN completed_at, DROP COLUMN status, DROP COLUMN warehouse_id, DROP COLUMN customer_id, DROP COLUMN number;
--rollback DROP SEQUENCE ${tenant.schema}.return_number_seq;

--changeset platform:tenant-109-document-event
--comment История документа — человекочитаемая лента: «сделка создана и
--comment зарезервирована», «перенесено в новую сделку 67971». Это не аудит полей:
--comment audit_log отвечает на вопрос «что изменилось в таблице», а менеджеру
--comment нужен ответ на «что происходило со сделкой», и собрать второе из первого
--comment нельзя.
CREATE TABLE ${tenant.schema}.document_event (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_type text        NOT NULL,
    document_id   bigint      NOT NULL,
    event_type    text        NOT NULL,
    message       text        NOT NULL,
    payload       jsonb       NOT NULL DEFAULT '{}',
    author_id     bigint      REFERENCES ${tenant.schema}.tenant_member,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_event_type_ck CHECK (document_type IN
        ('DEAL','RETURN','STOCK_DOCUMENT','PAYMENT'))
);
CREATE INDEX document_event_doc_ix
    ON ${tenant.schema}.document_event (document_type, document_id, created_at);

CREATE TRIGGER document_event_immutable
    BEFORE UPDATE OR DELETE ON ${tenant.schema}.document_event
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.stock_movement_immutable();
--rollback DROP TABLE ${tenant.schema}.document_event;
