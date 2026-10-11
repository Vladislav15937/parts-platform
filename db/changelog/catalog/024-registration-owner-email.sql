--liquibase formatted sql

--changeset platform:catalog-024-registration-owner-email
--comment Почта владельца и ключ повтора регистрации в реестре арендаторов (задача 0184).
--comment
--comment Почему в tenant_registry, а не в отдельной таблице. Самостоятельная
--comment регистрация резервирует сразу четыре вещи: номер компании, код входа,
--comment почту владельца и ключ повтора запроса. Если все четыре лежат в одной
--comment строке, их занимает одна вставка, а арбитром служат уникальные
--comment индексы: проигравший одновременный запрос получает отказ базы целиком,
--comment и половины резерва не остаётся. Отдельная таблица почт потребовала бы
--comment двух шагов и дала бы осиротевшие строки, когда второй шаг не прошёл.
--comment
--comment Уникальность почты — по всей ячейке, а не внутри компании: по почте
--comment потом приходит сброс пароля, а письму всё равно, в какой схеме человек
--comment заведён. Колонки допускают NULL: старые записи и компании, заведённые
--comment управляющим контуром без почты, её не имеют. NULL в уникальном индексе
--comment друг с другом не сталкиваются, поэтому частичный индекс не нужен.
--comment
--comment owner_email хранится как введена (после обрезки пробелов): её читает
--comment человек и она нужна письму. owner_email_key — она же в нижнем регистре,
--comment по ней идут уникальность и поиск; приводит её Java, а не база
--comment (триггеров, функций и генерируемых колонок в схеме нет). Подтверждение
--comment почты встанет потом отдельной колонкой без переноса данных, потому что
--comment введённое значение мы не теряем.
--comment
--comment CHECK по почте намеренно уже, чем хотелось бы: проверяет непустоту,
--comment длину до 254, отсутствие пробелов по краям и отсутствие заглавных ASCII.
--comment Равенства вида key = lower(key) нет: lower() у Postgres зависит от
--comment локали базы (при локали C кириллицу не трогает, при ru_RU трогает),
--comment а Java приводит регистр по Unicode, и CHECK мог бы отбить честный адрес
--comment либо расходиться между ячейками. Так же и btrim снимает только пробелы,
--comment поэтому проверяется лишь то, что пробелов по краям в ключе нет. Остальное
--comment держит Java.
--comment
--comment Ключ повтора (registration_key) — uuid строкой от клиента: повтор того же
--comment запроса, например после обрыва связи, находит свою запись, а не заводит
--comment вторую компанию. Нарушение индекса вызывающий обязан перечитать и
--comment вернуть результат первой записи.
ALTER TABLE public.tenant_registry
    ADD COLUMN owner_email      text,
    ADD COLUMN owner_email_key  text,
    ADD COLUMN registration_key text;

ALTER TABLE public.tenant_registry ADD CONSTRAINT tenant_registry_owner_email_ck
    CHECK (owner_email_key IS NULL OR (
        owner_email_key = btrim(owner_email_key)
        AND owner_email_key !~ '[A-Z]'
        AND owner_email_key <> ''
        AND length(owner_email_key) <= 254));

ALTER TABLE public.tenant_registry ADD CONSTRAINT tenant_registry_registration_key_ck
    CHECK (registration_key IS NULL OR (registration_key <> '' AND length(registration_key) <= 64));

CREATE UNIQUE INDEX tenant_registry_owner_email_uk ON public.tenant_registry (owner_email_key);
CREATE UNIQUE INDEX tenant_registry_registration_key_uk ON public.tenant_registry (registration_key);

--rollback-теряет почту владельца каждой компании, заведённой самостоятельной регистрацией, и ключи повтора регистрации; повторный накат оставит их пустыми
--rollback DROP INDEX public.tenant_registry_registration_key_uk;
--rollback DROP INDEX public.tenant_registry_owner_email_uk;
--rollback ALTER TABLE public.tenant_registry DROP CONSTRAINT tenant_registry_registration_key_ck;
--rollback ALTER TABLE public.tenant_registry DROP CONSTRAINT tenant_registry_owner_email_ck;
--rollback ALTER TABLE public.tenant_registry DROP COLUMN registration_key, DROP COLUMN owner_email_key, DROP COLUMN owner_email;
