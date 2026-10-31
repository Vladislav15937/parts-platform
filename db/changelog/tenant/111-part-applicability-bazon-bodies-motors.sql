--liquibase formatted sql

--changeset partsflow:tenant-111-part-applicability-bazon-bodies-motors
--comment Выбранные у строки применимости кузова и двигатели — ссылки на общий справочник catalog.bazon_body и catalog.bazon_motor, задача 0343

-- Что человек сможет (tasks/0343): у поколения в применимости позиции выбрать конкретные
-- кузова и двигатели из списка поколения, и выбор не пропадает — по нему собирается строка
-- «Подходит» (0342). Решение владельца 19.10.2026 (tasks/0341, «Ответ владельца — 19 октября
-- 2026 (решения исполнителей)»): хранение — схема арендатора, part_applicability, migrator.
--
-- ДВЕ ТАБЛИЦЫ ПРИ part_applicability, А НЕ КОЛОНКИ В НЕЙ. Кузовов у строки бывает несколько
-- (у Bazon в панели применимости — чипы «J31», «PJ31», «TNJ31» у одного поколения), двигателей
-- тоже. Колонка на ссылку означала бы строку применимости на каждое сочетание кузова
-- и двигателя, а это ломает прежний образ: DromApplicabilityService пишет применимость
-- через ON CONFLICT (part_id, brand_id, model_id, generation_id, modification_id), и без
-- уникального индекса ровно на этих колонках (part_applicability_uq, tenant/034) Postgres
-- отказывает в разборе запроса; а поиск, показ и сборка «Подходит» увидели бы одно
-- поколение несколько раз.
--
-- ФОРМА ССЫЛКИ — та же, что в tenant/096: колонка bazon_<вид>_id bigint REFERENCES
-- catalog.bazon_<вид>, без ON DELETE. Удаление записи справочника, которую кто-то выбрал,
-- отказывает, а не снимает выбор молча; следующий снимок справочника — сверка по bazon_id
-- (catalog/026), а не пересборка удалением. Ссылка на строку применимости — ON DELETE
-- CASCADE: выбор принадлежит строке, без неё он ничего не значит, и прежний образ, снимая
-- строку применимости, не должен упираться в таблицу, которой он не знает.
--
-- ЧТО ЗНАЧИТ ПУСТО. Нет строк кузовов — кузова не выбраны: строка применимости означает
-- поколение целиком, как до этой задачи (так её и собирает BazonFitsResolver). Нет строк
-- двигателей — двигатели не выбраны, тоже как раньше. «Любой двигатель» — НЕ отсутствие
-- строк, а состояние part.engine_matters = false (tenant/094; решение владельца 19.10.2026,
-- tasks/0363, «Как сделать», п. 2: «любой двигатель» — то же состояние, второе не заводить).
-- Второго признака здесь нет намеренно: у Bazon «Двигатель важен / не важен» стоит над всем
-- списком машин позиции, а не у строки. Отсюда три различимых случая двигателя: «любой»
-- (engine_matters = false), «важен, выбраны такие-то» (true и строки здесь), «важен,
-- не выбран» (true и строк нет).
--
-- ЧЕГО СХЕМА НЕ ДЕРЖИТ, И ЭТО РАБОТА КОДА. Что кузов принадлежит поколению строки, а двигатель —
-- кузову или поколению: это связь между строками разных таблиц (и разных схем — наше поколение
-- Дрома с поколением Bazon схемой не связано, catalog/026), CHECK её не выразит, триггеров
-- не заводим. Хранятся ли двигатели при engine_matters = false — тоже код: схема не запрещает,
-- признак позиции их просто перекрывает.
--
-- Expand: прежний образ таблиц не знает, не читает и не пишет; part_applicability не тронута.
CREATE TABLE ${tenant.schema}.part_applicability_body (
    applicability_id bigint NOT NULL REFERENCES ${tenant.schema}.part_applicability ON DELETE CASCADE,
    bazon_body_id    bigint NOT NULL REFERENCES catalog.bazon_body,
    CONSTRAINT part_applicability_body_pk PRIMARY KEY (applicability_id, bazon_body_id)
);

CREATE TABLE ${tenant.schema}.part_applicability_motor (
    applicability_id bigint NOT NULL REFERENCES ${tenant.schema}.part_applicability ON DELETE CASCADE,
    bazon_motor_id   bigint NOT NULL REFERENCES catalog.bazon_motor,
    CONSTRAINT part_applicability_motor_pk PRIMARY KEY (applicability_id, bazon_motor_id)
);

-- Индексы по ссылке на справочник: проверка внешнего ключа при правке catalog.bazon_* идёт
-- по каждой схеме ячейки, и без индекса это полный проход таблицы в каждой. Ссылку на строку
-- применимости покрывает первичный ключ (она в нём первой).
CREATE INDEX part_applicability_body_ix  ON ${tenant.schema}.part_applicability_body (bazon_body_id);
CREATE INDEX part_applicability_motor_ix ON ${tenant.schema}.part_applicability_motor (bazon_motor_id);

COMMENT ON TABLE ${tenant.schema}.part_applicability_body IS
    'Кузова, выбранные у строки применимости позиции: записи общего справочника catalog.bazon_body (задача 0343). Строк нет — кузова не выбраны, строка применимости означает поколение целиком';
COMMENT ON TABLE ${tenant.schema}.part_applicability_motor IS
    'Двигатели, выбранные у строки применимости позиции: записи общего справочника catalog.bazon_motor (задача 0343). Строк нет — двигатели не выбраны. «Любой двигатель» — не пустота, а part.engine_matters = false';

--rollback-теряет выбранные у строк применимости кузова и двигатели: после отката строка «Подходит» снова собирается по поколению целиком, выбор вручную не восстановить. Сами строки применимости, признак «Двигатель важен» и справочник остаются
--rollback DROP TABLE ${tenant.schema}.part_applicability_motor;
--rollback DROP TABLE ${tenant.schema}.part_applicability_body;
