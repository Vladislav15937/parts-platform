--liquibase formatted sql

-- Доставка и упаковка — это деньги сделки, а не примечание к ней.
-- До этой миграции доставка приезжала текстом («ТК СДЭК, Надым»), и сумма
-- сделки состояла из одной цены детали. Для заказа с площадки это расхождение
-- с деньгами: Дром переводит стоимость вместе с доставкой, а сойтись перевод
-- должен с суммой документа.

--changeset platform:tenant-240-service
--comment Справочник услуг: отдельный от part намеренно. Карточку детали ведут
--comment триггеры склада — остаток, статус, раскладка по складам, — и строка,
--comment которая никогда не двигается, врала бы в каждом из них: попадала бы
--comment в выгрузку на площадку, в пересчёт склада и в печать этикеток.
CREATE TABLE ${tenant.schema}.service (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text        NOT NULL,
    -- Подсказка продавцу, а не тариф: доставка до Надыма и до соседней улицы
    -- стоит по-разному, и цена берётся из строки сделки, а не отсюда.
    price       numeric(14,2),
    is_archived boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT service_uk UNIQUE (name)
);

-- Наполняется миграцией по той же причине, что и источники сделок: пустой
-- справочник означает, что услугу не добавит никто — выбирать не из чего,
-- а заводить строку в момент продажи никто не станет.
INSERT INTO ${tenant.schema}.service (name) VALUES ('Доставка'), ('Упаковка')
ON CONFLICT (name) DO NOTHING;
--rollback DROP TABLE ${tenant.schema}.service;

--changeset platform:tenant-241-deal-service
--comment Услуга строкой сделки. Своя таблица, а не deal_item с пустым part_id:
--comment выдача пишет движение склада на каждую позицию, и услуга среди них
--comment означала бы движение детали, которой нет. Отдельная таблица делает
--comment это невозможным, а не оставляет на дисциплину в коде.
CREATE TABLE ${tenant.schema}.deal_service (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deal_id    bigint        NOT NULL REFERENCES ${tenant.schema}.deal ON DELETE CASCADE,
    service_id bigint        NOT NULL REFERENCES ${tenant.schema}.service,
    quantity   numeric(12,3) NOT NULL DEFAULT 1,
    price      numeric(14,2) NOT NULL,
    CONSTRAINT deal_service_qty_ck CHECK (quantity > 0),
    CONSTRAINT deal_service_price_ck CHECK (price >= 0)
);
CREATE INDEX deal_service_deal_ix ON ${tenant.schema}.deal_service (deal_id);
--rollback DROP TABLE ${tenant.schema}.deal_service;
