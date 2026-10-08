--liquibase formatted sql

--changeset partsflow:tenant-071-inventory-counter-note
--comment Второй комментарий к пересчёту — комментарий ходившего по полкам; ключи идемпотентности его записи — задача 0169
--
-- У пересчёта уже есть комментарий (note, tenant/060). Одно поле на двоих
-- означало бы, что второй пишущий молча затирает первого, поэтому комментарий
-- кладовщика живёт в своей колонке. Пусто — NULL, а не пустая строка: «не
-- заполнено» и «написал пусто» различает Java при записи; без умолчания.
-- Здесь только форма данных.
ALTER TABLE ${tenant.schema}.inventory_session
    ADD COLUMN counter_note text;

-- Телефон шлёт комментарий из офлайн-очереди с requestId, который клиент
-- создаёт один раз и не меняет при повторах. Сервер вставляет ключ
-- с ON CONFLICT DO NOTHING и по числу вставленных строк узнаёт, первый это
-- раз или повтор. Уникальность стережёт первичный ключ, а не проверка
-- чтением: та пропускает второй одновременный повтор (tenant/016, 069).
-- Тип text, как client_request_id в 016 и 069.
-- ON DELETE CASCADE: ключ без своего пересчёта не значит ничего.
CREATE TABLE ${tenant.schema}.inventory_note_request (
    request_id text        PRIMARY KEY,
    session_id bigint      NOT NULL REFERENCES ${tenant.schema}.inventory_session ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX inventory_note_request_session_ix
    ON ${tenant.schema}.inventory_note_request (session_id);

COMMENT ON COLUMN ${tenant.schema}.inventory_session.counter_note IS
    'Комментарий того, кто ходил по полкам (кладовщика), рядом с note — комментарием проводящего. Отдельное поле, чтобы второй пишущий не затирал первого. NULL — не заполнено; пустой строкой не писать. Задача 0169';
COMMENT ON TABLE ${tenant.schema}.inventory_note_request IS
    'Ключи идемпотентности записи комментария кладовщика из офлайн-очереди: повтор с тем же request_id не создаёт второй записи. Задача 0169';

--rollback-теряет колонку counter_note со всеми написанными комментариями кладовщиков и таблицу ключей повтора. Комментарии не восстановить; повтор из очереди, пришедший после отката, не узнает свой ключ
--rollback DROP TABLE ${tenant.schema}.inventory_note_request;
--rollback ALTER TABLE ${tenant.schema}.inventory_session DROP COLUMN counter_note;
