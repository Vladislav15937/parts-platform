--liquibase formatted sql

--changeset partsflow:tenant-108-donor-number
--comment Номер донора: порядковый внутри компании, у перенесённых с Bazon — их номер (задача 0364, пункт 6)

-- Задача 0364, «Как проверить», пункт 6 (дописан 30.10.2026 по живому
-- прогону): машина называется человеку порядковым номером, как у Bazon
-- («Номер донора» 511, 510, 509 …), а не шестнадцатеричным public_code.
-- В форме «Создание донора» подставлен следующий и правится; уникален
-- в пределах организации; у перенесённых с Bazon номер остаётся их
-- (legacy_code), у заведённых у нас — следующий за наибольшим.
--
-- УСТРОЕНО КАК part.number (tenant/064) И tenant_member.number (tenant/097):
-- id остаётся внутренним, рядом свой номер из последовательности
-- с уникальным индексом. Организация — это схема арендатора (одна на
-- компанию), поэтому индекс по одной колонке и есть «уникален в организации».
--
-- public_code НЕ СНИМАЕТСЯ: он остаётся в базе и в выгрузках, как публичный
-- код позиции после 0366; на экране его заменяет номер (работа кода).
-- legacy_code НЕ ТРОГАЕТСЯ: по нему перенос узнаёт машину при повторе.
CREATE SEQUENCE ${tenant.schema}.donor_number_seq;

ALTER TABLE ${tenant.schema}.donor ADD COLUMN number bigint;

-- Перенесённые с Bazon получают свой номер. Берётся только legacy_code,
-- который целиком число больше нуля и умещается в bigint: разбор переноса
-- (BazonValueParser.parseDonorNumber) пропускает и не числа, и на таком
-- отказ наката у одного клиента означал бы, что не заводится и не догоняется
-- ни одна его схема. Два кода, дающие одно число («077» и «77»), — номер
-- получает заведённая раньше (меньший id), вторая уходит в общий ряд ниже;
-- legacy_code у обеих остаётся как был.
UPDATE ${tenant.schema}.donor d
   SET number = legacy.number
  FROM (SELECT id, number,
               row_number() OVER (PARTITION BY number ORDER BY id) AS place
          FROM (SELECT id, btrim(legacy_code)::numeric AS number
                  FROM ${tenant.schema}.donor
                 WHERE btrim(legacy_code) ~ '^0*[1-9][0-9]{0,17}$') parsed) legacy
 WHERE d.id = legacy.id AND legacy.place = 1;

-- Остальные — заведённые у нас и не разобранные выше — следующими
-- за наибольшим, в порядке заведения (по id: так их показывает список
-- машин, и строки одной транзакции имеют одно created_at). Наибольший
-- считается по снимку до этого UPDATE, то есть по номерам Bazon.
UPDATE ${tenant.schema}.donor d
   SET number = ordered.number
  FROM (SELECT id,
               coalesce((SELECT max(number) FROM ${tenant.schema}.donor), 0)
                 + row_number() OVER (ORDER BY id) AS number
          FROM ${tenant.schema}.donor
         WHERE number IS NULL) ordered
 WHERE d.id = ordered.id;

-- Последовательность продолжает с наибольшего, а не с единицы: иначе первая
-- же машина после наката получила бы занятый номер, и уникальность отбила
-- бы её заведение. На пустой схеме (новый клиент) первым будет 1.
SELECT setval('${tenant.schema}.donor_number_seq',
              coalesce((SELECT max(number) FROM ${tenant.schema}.donor), 0) + 1,
              false);

-- Номер присваивает умолчание: прежний образ колонку не знает и машину
-- заводит, не называя её, — поэтому умолчание стоит раньше NOT NULL,
-- и это expand. Номер, вписанный руками в форме или пришедший переносом,
-- последовательность не двигает: «следующий» обязан учитывать и то и другое,
-- и это работа кода, а не схемы (запись в PR и в задаче 0364).
ALTER TABLE ${tenant.schema}.donor
    ALTER COLUMN number SET DEFAULT nextval('${tenant.schema}.donor_number_seq');
ALTER TABLE ${tenant.schema}.donor ALTER COLUMN number SET NOT NULL;

-- Ноль и отрицательные номера человек не называет; у Bazon номер с единицы.
ALTER TABLE ${tenant.schema}.donor
    ADD CONSTRAINT donor_number_ck CHECK (number > 0);

-- Уникальность не для красоты: по номеру машину находят в складе
-- («Номер донора»), и два одинаковых означали бы детали не той машины.
CREATE UNIQUE INDEX donor_number_uk ON ${tenant.schema}.donor (number);

COMMENT ON COLUMN ${tenant.schema}.donor.number IS
    'Номер донора: порядковый внутри компании, его называют человеку вместо public_code. У перенесённых с Bazon — их номер (legacy_code), у заведённых у нас — следующий. Задача 0364';

--rollback-теряет номера доноров, вписанные руками в форме: повторный накат раздаёт номера заново — перенесённым с Bazon их номер из legacy_code, остальным по порядку заведения за наибольшим, — и номер, исправленный человеком, вернётся к выведенному. Сами машины, legacy_code и public_code остаются
--rollback DROP INDEX ${tenant.schema}.donor_number_uk;
--rollback ALTER TABLE ${tenant.schema}.donor DROP CONSTRAINT donor_number_ck;
--rollback ALTER TABLE ${tenant.schema}.donor DROP COLUMN number;
--rollback DROP SEQUENCE ${tenant.schema}.donor_number_seq;
