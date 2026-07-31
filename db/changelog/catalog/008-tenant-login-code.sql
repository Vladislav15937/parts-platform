--liquibase formatted sql

--changeset platform:catalog-008-tenant-login-code
--comment Код компании для входа.
--comment
--comment При логине надо знать схему арендатора до проверки пароля: учётные
--comment записи живут внутри схемы, и найти пользователя, не выбрав схему,
--comment невозможно. Имя схемы (t_000042) для этого не годится — его не должен
--comment ни знать, ни набирать сотрудник склада.
--comment
--comment Позже код будет браться из поддомена (yardt.partsflow.ru), как у Bazon.
--comment Переход на поддомен данные не двигает: меняется только источник кода.
ALTER TABLE public.tenant_registry ADD COLUMN code text;

-- Пока арендаторов нет, заполнять нечего; для уже существующих код выводим
-- из номера, чтобы NOT NULL можно было включить сразу.
UPDATE public.tenant_registry SET code = 'c' || tenant_id WHERE code IS NULL;

ALTER TABLE public.tenant_registry ALTER COLUMN code SET NOT NULL;
ALTER TABLE public.tenant_registry ADD CONSTRAINT tenant_registry_code_uk UNIQUE (code);
-- Латиница, цифры, дефис: код набирают руками на телефоне.
ALTER TABLE public.tenant_registry ADD CONSTRAINT tenant_registry_code_ck
    CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,30}$');
--rollback ALTER TABLE public.tenant_registry DROP CONSTRAINT tenant_registry_code_ck;
--rollback ALTER TABLE public.tenant_registry DROP CONSTRAINT tenant_registry_code_uk;
--rollback ALTER TABLE public.tenant_registry DROP COLUMN code;
