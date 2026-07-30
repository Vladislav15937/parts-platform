--liquibase formatted sql

--changeset platform:tenant-140-member-credentials
--comment Сотрудник арендатора становится учётной записью.
--comment
--comment До этого пользователей не было нигде: tenant_member.user_id — висячий
--comment bigint без внешнего ключа, ссылавшийся на таблицу, которой нет,
--comment а арендатор определялся заголовком X-Tenant-Id. Опубликовать такое API
--comment в интернет нельзя: любой читает чужой склад, подставив другой номер,
--comment а первые десять клиентов конкурируют в одном городе.
--comment
--comment Учётные записи живут в схеме арендатора, а не в control plane. Причины:
--comment восстановление одного клиента из бэкапа перестаёт терять его
--comment пользователей; логин не обязан быть уникальным между арендаторами —
--comment у двух разборок может быть один директор с одним адресом; control plane
--comment остаётся без бизнес-данных, как записано в архитектуре.
ALTER TABLE ${tenant.schema}.tenant_member
    ADD COLUMN login         text,
    ADD COLUMN password_hash text,
    ADD COLUMN last_login_at timestamptz;

-- Уникальность по нормализованному логину: «Ivan» и «ivan » — один человек,
-- и второй такой сотрудник должен получить отказ, а не второй аккаунт.
CREATE UNIQUE INDEX tenant_member_login_uk
    ON ${tenant.schema}.tenant_member (lower(btrim(login)))
    WHERE login IS NOT NULL;

-- Пароль обязателен, если есть логин: запись с логином и без пароля означала бы
-- вход без проверки.
ALTER TABLE ${tenant.schema}.tenant_member ADD CONSTRAINT tenant_member_credentials_ck
    CHECK ((login IS NULL) = (password_hash IS NULL));

-- user_id перестаёт быть обязательным: он ссылался в пустоту, а роль
-- идентификатора внутри арендатора исполняет id.
ALTER TABLE ${tenant.schema}.tenant_member ALTER COLUMN user_id DROP NOT NULL;
--rollback ALTER TABLE ${tenant.schema}.tenant_member ALTER COLUMN user_id SET NOT NULL;
--rollback ALTER TABLE ${tenant.schema}.tenant_member DROP CONSTRAINT tenant_member_credentials_ck;
--rollback DROP INDEX ${tenant.schema}.tenant_member_login_uk;
--rollback ALTER TABLE ${tenant.schema}.tenant_member DROP COLUMN last_login_at, DROP COLUMN password_hash, DROP COLUMN login;
