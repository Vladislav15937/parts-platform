--liquibase formatted sql

--changeset platform:catalog-030-provisioning-status
--comment Состояние «создаётся» в реестре арендаторов.
--comment
--comment Провижининг занимает секунды: создать схему, накатить семьдесят
--comment changeset'ов, завести владельца. Всё это время запись в реестре уже
--comment нужна — она резервирует номер и код компании, иначе два одновременных
--comment создания возьмут один номер.
--comment
--comment Но видимой такая запись быть не должна. Релей outbox идёт по ACTIVE,
--comment и полусозданная схема без таблицы outbox валила бы ему каждый заход.
--comment Вход по коду компании — тоже по ACTIVE: пускать в схему без таблиц
--comment незачем.
--comment
--comment Отдельного FAILED нет намеренно: сорвавшийся провижининг оставляет
--comment PROVISIONING, и это ровно то, что нужно человеку — запись видно,
--comment система её игнорирует, разбирается он.
ALTER TABLE public.tenant_registry DROP CONSTRAINT tenant_registry_status_ck;
ALTER TABLE public.tenant_registry ADD CONSTRAINT tenant_registry_status_ck
    CHECK (status IN ('PROVISIONING','ACTIVE','SUSPENDED','ARCHIVED'));

--rollback ALTER TABLE public.tenant_registry DROP CONSTRAINT tenant_registry_status_ck;
--rollback ALTER TABLE public.tenant_registry ADD CONSTRAINT tenant_registry_status_ck CHECK (status IN ('ACTIVE','SUSPENDED','ARCHIVED'));
