--liquibase formatted sql

--changeset platform:catalog-025-service-admin
--comment Администратор сервиса, разрешения владельцев на его вход и запросы доступа (задача 0042).
--comment
--comment Почему в public, а не в схеме арендатора. Решение владельца продукта
--comment от 29 сентября 2026: учётная запись администратора живёт рядом с реестром
--comment арендаторов. Запись внутри кабинета клиента была бы видна владельцу
--comment разборки у себя, а система администрирования обязана жить вне всех
--comment кабинетов. Разрешение и запрос тоже здесь: они связывают администратора
--comment с арендатором, а ни одной из двух сторон своей схемы не принадлежат.
--comment
--comment Одна открытая строка на арендатора держится частичным уникальным
--comment индексом, а не проверкой чтением: два одновременных нажатия проходят
--comment проверку оба, и второе разрешение (или второй запрос) завелось бы рядом
--comment с первым. Закрытые строки остаются историей и индексу не мешают.
--comment Проигравшего одновременный повтор обязан перечитать вызывающий.
--comment
--comment granted_by и closed_by без внешнего ключа: это tenant_member.id из схемы
--comment арендатора, а ссылка из общей схемы в чужую сделала бы восстановление
--comment одной схемы зависимым от этой. expires_at NULL означает «до отзыва».
--comment Сроки и порядок закрытия ставит Java; базе остаются CHECK на форму данных.
CREATE TABLE public.service_admin (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    login         text        NOT NULL,
    password_hash text        NOT NULL,
    display_name  text        NOT NULL,
    is_active     boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX service_admin_login_uk ON public.service_admin (lower(btrim(login)));

CREATE TABLE public.admin_access_grant (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  bigint      NOT NULL REFERENCES public.tenant_registry (tenant_id),
    granted_at timestamptz NOT NULL DEFAULT now(),
    granted_by bigint      NOT NULL,
    expires_at timestamptz,
    closed_at  timestamptz,
    closed_by  bigint,
    CONSTRAINT admin_access_grant_expiry_ck CHECK (expires_at IS NULL OR expires_at > granted_at),
    CONSTRAINT admin_access_grant_closed_ck CHECK (closed_at IS NULL OR closed_at >= granted_at)
);

CREATE UNIQUE INDEX admin_access_grant_open_uk
    ON public.admin_access_grant (tenant_id) WHERE closed_at IS NULL;

CREATE TABLE public.admin_access_request (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    bigint      NOT NULL REFERENCES public.tenant_registry (tenant_id),
    admin_id     bigint      NOT NULL REFERENCES public.service_admin (id),
    requested_at timestamptz NOT NULL DEFAULT now(),
    closed_at    timestamptz,
    outcome      text,
    CONSTRAINT admin_access_request_outcome_ck CHECK (outcome IN ('GRANTED','DECLINED')),
    CONSTRAINT admin_access_request_closed_ck CHECK ((closed_at IS NULL) = (outcome IS NULL))
);

CREATE UNIQUE INDEX admin_access_request_open_uk
    ON public.admin_access_request (tenant_id) WHERE closed_at IS NULL;

--rollback-теряет учётные записи администраторов сервиса и всю историю разрешений и запросов доступа; открытые разрешения закрываются вместе с таблицей, и администратор теряет вход во все кабинеты
--rollback DROP TABLE public.admin_access_request;
--rollback DROP TABLE public.admin_access_grant;
--rollback DROP TABLE public.service_admin;
