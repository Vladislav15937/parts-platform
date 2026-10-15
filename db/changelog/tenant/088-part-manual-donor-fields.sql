--liquibase formatted sql

--changeset partsflow:tenant-088-part-manual-donor-fields
--comment Свои значения раздела «Автомобиль-донор» у позиции — восемь nullable-колонок part, задача 0302
--
-- Поля раздела «Автомобиль-донор» правятся у ПОЗИЦИИ, а не у машины-донора: правка у одной
-- детали не меняет соседнюю деталь той же машины и саму машину. Раньше эти значения читались
-- только из donor (а марка, модель и поколение у позиции без донора — из part_applicability).
--
-- NULL — «своего значения нет, показывается донор или применимость»; пустую строку код не пишет,
-- приводит к NULL. Отбор и сортировка идут выражением coalesce(manual_*, значение донора или
-- применимости), поэтому ни умолчаний, ни CHECK, ни индексов здесь нет: решение владельца —
-- никаких замков и запретов, объём как у прежних выражений.
--
-- Expand: прежний код колонок не знает, не читает и не пишет, все они допускают NULL.
ALTER TABLE ${tenant.schema}.part
    ADD COLUMN manual_donor_code text,
    ADD COLUMN manual_brand text,
    ADD COLUMN manual_model text,
    ADD COLUMN manual_generation text,
    ADD COLUMN manual_body text,
    ADD COLUMN manual_engine text,
    ADD COLUMN manual_year integer,
    ADD COLUMN manual_equipment text;

COMMENT ON COLUMN ${tenant.schema}.part.manual_donor_code IS
    'Номер донора, вписанный у позиции руками (задача 0302). NULL — своего значения нет, показывается номер донора';
COMMENT ON COLUMN ${tenant.schema}.part.manual_brand IS
    'Марка, вписанная у позиции руками (задача 0302). NULL — своего значения нет, показывается марка донора или применимости';
COMMENT ON COLUMN ${tenant.schema}.part.manual_model IS
    'Модель, вписанная у позиции руками (задача 0302). NULL — своего значения нет, показывается модель донора или применимости';
COMMENT ON COLUMN ${tenant.schema}.part.manual_generation IS
    'Поколение, вписанное у позиции руками (задача 0302). NULL — своего значения нет, показывается поколение донора или применимости';
COMMENT ON COLUMN ${tenant.schema}.part.manual_body IS
    'Модель кузова, вписанная у позиции руками (задача 0302). NULL — своего значения нет, показывается кузов донора';
COMMENT ON COLUMN ${tenant.schema}.part.manual_engine IS
    'Модель двигателя, вписанная у позиции руками (задача 0302). NULL — своего значения нет, показывается двигатель донора';
COMMENT ON COLUMN ${tenant.schema}.part.manual_year IS
    'Год выпуска, вписанный у позиции руками (задача 0302). NULL — своего значения нет, показывается год донора';
COMMENT ON COLUMN ${tenant.schema}.part.manual_equipment IS
    'Комплектация, вписанная у позиции руками (задача 0302). NULL — своего значения нет, показывается комплектация донора';

--rollback-теряет значения раздела «Автомобиль-донор», вписанные у позиций руками (номер донора, марка, модель, поколение, кузов, двигатель, год, комплектация): после отката позиции снова показывают только значения донора или применимости, вписанное не восстановить. Донор, применимость и сами позиции остаются
--rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN manual_donor_code, DROP COLUMN manual_brand, DROP COLUMN manual_model, DROP COLUMN manual_generation, DROP COLUMN manual_body, DROP COLUMN manual_engine, DROP COLUMN manual_year, DROP COLUMN manual_equipment;
