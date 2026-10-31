--liquibase formatted sql

--changeset partsflow:tenant-110-donor-status-list
--comment Статус машины — список клиента, как у Bazon; прежний перечень переезжает в него первыми значениями (задача 0364)

-- Решение владельца 19.10.2026 (tasks/0364, раздел «Ответ владельца —
-- 19 октября 2026», «Решение (вопрос про статусы машины)»): статус машины —
-- свободный список клиента, как у Bazon («В Японии», «В [город]»), значения
-- заводит клиент (до обхода — владелец в настройках). Закрытый перечень
-- «Куплена / В разборе / Разобрана / Списана» отменён как единственный:
-- существующие значения переезжают в список первыми его значениями, данные
-- не теряются, а «В разборе» / «Разобрана» остаются как состояние приёмки.
--
-- ОТСЮДА ДВЕ ВЕЩИ, А НЕ ОДНА.
-- donor.status (CHECK на четыре кода, tenant/003) НЕ ТРОНУТ: это состояние
-- приёмки, его читает Donor.yieldsParts и прежний образ. Сужать его или
-- снимать — отдельное решение и отдельный релиз.
-- donor.status_id — новый статус из списка клиента, необязательный: у Bazon
-- в форме «– Не задано –».
--
-- СПРАВОЧНИК — ТАБЛИЦА, А НЕ jsonb В company_setting: у значения есть имя,
-- порядок и ссылки с машин; удаление назначенного база отбивает (ссылка
-- без ON DELETE, как у text_block, tenant/095) — что делать с машинами,
-- решает код. Имя уникально без учёта регистра и пробелов по краям, как
-- у part_name и text_block.
CREATE TABLE ${tenant.schema}.donor_status (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text        NOT NULL,
    sort_order int         NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT donor_status_name_ck CHECK (name ~ '\S')
);

CREATE UNIQUE INDEX donor_status_name_uk
    ON ${tenant.schema}.donor_status (lower(btrim(name)));

-- Первые значения списка — прежний перечень, словами экрана
-- (DonorDirectory.STATUSES), в порядке стадий. Заводятся всем, в том числе
-- новому клиенту: это «существующие значения» системы, и у нового они
-- те же, что у работающего. Клиент правит и удаляет их сам.
INSERT INTO ${tenant.schema}.donor_status (name, sort_order) VALUES
    ('Куплена',   1),
    ('В разборе', 2),
    ('Разобрана', 3),
    ('Списана',   4);

ALTER TABLE ${tenant.schema}.donor
    ADD COLUMN status_id bigint REFERENCES ${tenant.schema}.donor_status;

CREATE INDEX donor_status_id_ix ON ${tenant.schema}.donor (status_id)
    WHERE status_id IS NOT NULL;

-- Уже заведённые машины получают в списке то, что у них стоит сейчас:
-- данные не теряются. Дальше status и status_id живут раздельно — так
-- и решено («остаются как состояние приёмки»).
UPDATE ${tenant.schema}.donor d
   SET status_id = s.id
  FROM ${tenant.schema}.donor_status s
 WHERE s.name = CASE d.status
                    WHEN 'PURCHASED'   THEN 'Куплена'
                    WHEN 'DISMANTLING' THEN 'В разборе'
                    WHEN 'DISMANTLED'  THEN 'Разобрана'
                    WHEN 'WRITTEN_OFF' THEN 'Списана'
                END;

COMMENT ON TABLE ${tenant.schema}.donor_status IS
    'Статусы машины — список клиента, как у Bazon. Первые значения — прежний перечень стадий. Задача 0364, решение владельца 19.10.2026';
COMMENT ON COLUMN ${tenant.schema}.donor.status_id IS
    'Статус машины из списка клиента (donor_status). NULL — не задан. Не путать с donor.status: то состояние приёмки. Задача 0364';

--rollback-теряет список статусов машины, который вёл клиент, и выбранный у машин статус из этого списка; состояние приёмки (donor.status) и сами машины остаются. Повторный накат заведёт четыре прежних значения и раздаст их по состоянию приёмки — свои значения клиента и его выбор не вернутся
--rollback ALTER TABLE ${tenant.schema}.donor DROP COLUMN status_id;
--rollback DROP TABLE ${tenant.schema}.donor_status;
