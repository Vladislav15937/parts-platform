--liquibase formatted sql

--changeset platform:tenant-190-import-run
--comment Ключ идемпотентности загрузки склада из таблицы.
--comment
--comment Загрузка — самая разрушительная операция в системе: она заводит
--comment тысячи позиций, и отменить её можно только восстановлением из бэкапа.
--comment При этом до сих пор повтор давал вторую копию склада целиком.
--comment
--comment Так и случилось при первой же проверке: запись прошла, а ответ упал
--comment на сериализации. Владелец увидел «внутреннюю ошибку», нажал ещё раз —
--comment и получил склад в двух экземплярах.
--comment
--comment Ключ генерирует клиент при выборе файла и не меняет при повторах —
--comment ровно как в приёмке. Уникальность стережёт индекс, а не проверка
--comment «нет ли уже такого»: та пропустит второе одновременное нажатие.
CREATE TABLE ${tenant.schema}.import_run (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    client_request_id text        NOT NULL,
    warehouse_id      bigint      NOT NULL REFERENCES ${tenant.schema}.warehouse,
    imported          int         NOT NULL DEFAULT 0,
    skipped           jsonb       NOT NULL DEFAULT '[]',
    created_by        bigint      REFERENCES ${tenant.schema}.tenant_member,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT import_run_request_uk UNIQUE (client_request_id)
);

--rollback DROP TABLE ${tenant.schema}.import_run;
