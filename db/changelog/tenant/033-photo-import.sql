--liquibase formatted sql

--changeset platform:tenant-033-photo-import
--comment Очередь переноса фотографий из предыдущей системы

-- Фотография на разборке продаёт: склад без снимков — это прайс, по которому
-- не звонят. Перенос их отделён от переноса склада намеренно.
--
-- Тридцать шесть тысяч позиций — это под сотню тысяч файлов с чужого CDN.
-- Внутри HTTP-запроса импорта это часы, то есть оборванное соединение
-- и непонятное состояние; внутри его транзакции — ещё и удерживаемые
-- блокировки. Поэтому импорт только записывает, что скачать, а качает
-- отдельный проход, который можно прервать и продолжить.
CREATE TABLE ${tenant.schema}.part_photo_import (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    part_id    bigint      NOT NULL REFERENCES ${tenant.schema}.part ON DELETE CASCADE,
    url        text        NOT NULL,
    sort_order int         NOT NULL DEFAULT 0,
    status     text        NOT NULL DEFAULT 'PENDING',
    error      text,
    photo_id   bigint      REFERENCES ${tenant.schema}.part_photo ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    done_at    timestamptz,
    CONSTRAINT part_photo_import_status_ck CHECK (status IN ('PENDING', 'DONE', 'FAILED'))
);

-- Повторный импорт склада — обычное действие, а не авария: он не должен
-- заводить второе задание на тот же файл. Уникальность стережёт база,
-- а не проверка «нет ли уже такого» в коде.
CREATE UNIQUE INDEX part_photo_import_uk
    ON ${tenant.schema}.part_photo_import (part_id, url);

-- Проход берёт ожидающие пачками, поэтому индекс частичный: выполненных
-- со временем станет сто тысяч, и они в этом запросе не нужны.
CREATE INDEX part_photo_import_pending_ix
    ON ${tenant.schema}.part_photo_import (id) WHERE status = 'PENDING';

--rollback DROP TABLE ${tenant.schema}.part_photo_import;
