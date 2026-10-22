--liquibase formatted sql

--changeset partsflow:tenant-096-part-bazon-vehicle-refs
--comment Машина позиции — ссылки на общий справочник catalog.bazon_* (марка, модель, кузов, двигатель), задача 0377
--
-- Решение владельца 22.10.2026 (tasks/0377): справочник машин один на всех клиентов, в общей
-- схеме catalog; у арендатора его копии нет и не будет, у позиции хранится ТОЛЬКО ссылка на
-- запись справочника («деталь снята с такой-то машины» — данные клиента, слова — общие).
-- Текстовых колонок марки/модели/кузова/двигателя рядом не заводится.
--
-- ФОРМА ССЫЛКИ — одна на проект и она общая с задачей 0343 («подходит к»): колонка
-- bazon_<вид>_id bigint, REFERENCES catalog.bazon_<вид>, вид — mark | model | body | motor.
-- 0343 повторит ровно её в part_applicability (bazon_body_id, bazon_motor_id) — вторую форму не заводить.
--
-- ВНЕШНИЙ КЛЮЧ ЧЕРЕЗ СХЕМЫ — ставится: так уже сделано для donor.brand_id/model_id -> catalog.brand/model
-- (tenant/023); общая схема накатывается раньше схем арендаторов, провижининг на это опирается.
-- Цена: снимок справочника нельзя пересобирать удалением строк, на которые ссылается позиция
-- (следующий снимок — сверка по bazon_id, как сказано в catalog/026). Без ON DELETE: удаление
-- записи справочника, на которую кто-то ссылается, должно отказывать, а не обнулять машины молча.
--
-- ВСЕ ЧЕТЫРЕ ПОЛЯ НЕОБЯЗАТЕЛЬНЫ, NULL — «машина не выбрана». Согласованность (модель принадлежит
-- марке, кузов — модели, двигатель — марке) схема не держит: это связь между строками разных
-- таблиц, CHECK её не выразит, а триггеров не заводим; проверяет код при записи.
-- Expand: прежний код колонок не знает, все они допускают NULL.
ALTER TABLE ${tenant.schema}.part
    ADD COLUMN bazon_mark_id  bigint REFERENCES catalog.bazon_mark,
    ADD COLUMN bazon_model_id bigint REFERENCES catalog.bazon_model,
    ADD COLUMN bazon_body_id  bigint REFERENCES catalog.bazon_body,
    ADD COLUMN bazon_motor_id bigint REFERENCES catalog.bazon_motor;

CREATE INDEX part_bazon_mark_ix  ON ${tenant.schema}.part (bazon_mark_id)  WHERE bazon_mark_id  IS NOT NULL;
CREATE INDEX part_bazon_model_ix ON ${tenant.schema}.part (bazon_model_id) WHERE bazon_model_id IS NOT NULL;
CREATE INDEX part_bazon_body_ix  ON ${tenant.schema}.part (bazon_body_id)  WHERE bazon_body_id  IS NOT NULL;
CREATE INDEX part_bazon_motor_ix ON ${tenant.schema}.part (bazon_motor_id) WHERE bazon_motor_id IS NOT NULL;

COMMENT ON COLUMN ${tenant.schema}.part.bazon_mark_id IS
    'Марка машины, с которой снята позиция: запись общего справочника catalog.bazon_mark (задача 0377). NULL — не выбрана';
COMMENT ON COLUMN ${tenant.schema}.part.bazon_model_id IS
    'Модель машины позиции: запись catalog.bazon_model (задача 0377). NULL — не выбрана';
COMMENT ON COLUMN ${tenant.schema}.part.bazon_body_id IS
    'Кузов машины позиции: запись catalog.bazon_body (задача 0377). NULL — не выбран';
COMMENT ON COLUMN ${tenant.schema}.part.bazon_motor_id IS
    'Двигатель машины позиции: запись catalog.bazon_motor (задача 0377). NULL — не выбран';

--rollback-теряет выбранную у позиций машину из общего справочника (марка, модель, кузов, двигатель): после отката позиции снова показывают машину донора или вписанную руками, выбранное не восстановить. Сам справочник, доноры и позиции остаются
--rollback DROP INDEX ${tenant.schema}.part_bazon_motor_ix;
--rollback DROP INDEX ${tenant.schema}.part_bazon_body_ix;
--rollback DROP INDEX ${tenant.schema}.part_bazon_model_ix;
--rollback DROP INDEX ${tenant.schema}.part_bazon_mark_ix;
--rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN bazon_motor_id, DROP COLUMN bazon_body_id, DROP COLUMN bazon_model_id, DROP COLUMN bazon_mark_id;
