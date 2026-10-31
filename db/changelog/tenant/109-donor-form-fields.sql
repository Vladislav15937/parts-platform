--liquibase formatted sql

--changeset partsflow:tenant-109-donor-form-fields
--comment Рестайлинг, описание для клиента и ссылка на видео у машины-донора (задача 0364, вторая часть)

-- Задача 0364, форма «Создание донора» как у Bazon (решения владельца
-- 19.10.2026 и 20.10.2026, разделы «Ответ владельца» файла задачи):
-- «Рестайлинг», «Описание (для клиента)», «Ссылка на видео». Колонок для них
-- у donor не было; решение 0009 («описания и видео у машины не заводились»)
-- этим закрывается.
--
-- РЕСТАЙЛИНГ — ТЕКСТ, А НЕ ССЫЛКА. У Bazon это запись их справочника
-- («1 пок., 1 рест. 2014—2020»), но ссылаться нам не на что: у
-- catalog.generation is_restyling ложь у всех строк (inventory/CLAUDE.md,
-- задача 0297), а снимок справочника Bazon (catalog/026) уровня рестайлинга
-- не несёт вовсе. Текст — как body_code и engine_code (tenant/032): то, что
-- выбрал или вписал человек. Сброс при смене поколения — код.
--
-- «ЗАМЕТКА (ДЛЯ ВАС)» КОЛОНКИ НЕ ПОЛУЧАЕТ: это donor.note, которую экран
-- «Машина» сегодня подписывает «Примечание». Вторая колонка для того же
-- значения разъехалась бы с первой у каждой уже заведённой машины.
--
-- «СТОИМОСТИ» ЗДЕСЬ НЕТ НАМЕРЕННО: деньги по машине живут в журнале
-- затрат donor_cost и считаются окупаемостью (v_donor_profitability);
-- поле рядом с журналом — вопрос владельцу, а не форма данных (PR, задача 0364).
--
-- Пусто — только NULL: CHECK отбивает пустую строку и строку из пробелов
-- (та же порода, что снятый штрихкод и блоки карточки Дрома, tenant/080).
-- Ссылка на видео проверяется только на непустоту: что Bazon принимает
-- в этом поле, не видели, а отказ базы на «youtu.be/…» без схемы стал бы
-- для человека отказом формы без объяснения. Проверку адреса делает код.
--
-- Expand: прежний образ колонок не знает, все допускают NULL. Индексов нет:
-- по этим полям ничего не ищется.
ALTER TABLE ${tenant.schema}.donor
    ADD COLUMN restyling          text,
    ADD COLUMN client_description text,
    ADD COLUMN video_url          text;

ALTER TABLE ${tenant.schema}.donor
    ADD CONSTRAINT donor_restyling_ck          CHECK (restyling ~ '\S'),
    ADD CONSTRAINT donor_client_description_ck CHECK (client_description ~ '\S'),
    ADD CONSTRAINT donor_video_url_ck          CHECK (video_url ~ '\S');

COMMENT ON COLUMN ${tenant.schema}.donor.restyling IS
    'Рестайлинг машины, как выбран или вписан в форме «Создание донора». Текст: справочника рестайлингов нет. NULL — не указан. Задача 0364';
COMMENT ON COLUMN ${tenant.schema}.donor.client_description IS
    'Описание машины для клиента («Описание (для клиента)» у Bazon). NULL — нет. Внутренняя заметка — donor.note. Задача 0364';
COMMENT ON COLUMN ${tenant.schema}.donor.video_url IS
    'Ссылка на видео машины, как вписана. NULL — нет. Задача 0364';

--rollback-теряет рестайлинг, описание для клиента и ссылку на видео у машин-доноров; остальные поля машины и её заметка (note) остаются
--rollback ALTER TABLE ${tenant.schema}.donor DROP CONSTRAINT donor_video_url_ck, DROP CONSTRAINT donor_client_description_ck, DROP CONSTRAINT donor_restyling_ck;
--rollback ALTER TABLE ${tenant.schema}.donor DROP COLUMN video_url, DROP COLUMN client_description, DROP COLUMN restyling;
