--liquibase formatted sql

--changeset platform:tenant-050-inline-public-code
--comment Последняя функция в схеме арендатора убрана.
--comment
--comment gen_public_code() генерировала неугадываемый код для этикеток
--comment и объявлений — двенадцать шестнадцатеричных знаков. Она стояла
--comment значением по умолчанию у колонки, то есть логикой в том смысле,
--comment в каком ею является now(), не была. Но правило записано одно
--comment на всех: в схеме арендатора не должно остаться ничего, кроме
--comment описания данных.
--comment
--comment Выражение осталось тем же, просто без имени: пересчитывать уже
--comment выданные коды нельзя — они напечатаны на этикетках и уехали
--comment в объявления площадки.
--comment
--comment Генерировать в Java не стали намеренно, и это не отступление
--comment от правила: код обязан быть у каждой строки, а вставок в part
--comment и donor прямым SQL хватает — перенос, импорт из таблицы, починка.
--comment Умолчание колонки гарантирует его всем; генерация в приложении
--comment гарантировала бы только тем, кто прошёл через приложение.
ALTER TABLE ${tenant.schema}.part
    ALTER COLUMN public_code SET DEFAULT upper(encode(public.gen_random_bytes(6), 'hex'));
ALTER TABLE ${tenant.schema}.donor
    ALTER COLUMN public_code SET DEFAULT upper(encode(public.gen_random_bytes(6), 'hex'));
DROP FUNCTION IF EXISTS ${tenant.schema}.gen_public_code();
--rollback SELECT 1;
