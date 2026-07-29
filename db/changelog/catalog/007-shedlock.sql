--liquibase formatted sql

--changeset platform:catalog-060-shedlock
--comment Блокировка планировщика. Нужна с первого дня, а не «когда появится
--comment второй инстанс»: без неё два экземпляра приложения одновременно
--comment пересоберут фиды и перельют outbox, и клиент получит дубли объявлений
--comment на площадке — разгребать это придётся вручную через модерацию.
CREATE TABLE public.shedlock (
    name       text        NOT NULL PRIMARY KEY,
    lock_until timestamptz NOT NULL,
    locked_at  timestamptz NOT NULL,
    locked_by  text        NOT NULL
);
--rollback DROP TABLE public.shedlock;
