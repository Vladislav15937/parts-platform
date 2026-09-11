--liquibase formatted sql

--changeset partsflow:tenant-064-part-number
--comment Свой номер позиции: порядковый, возрастающий, его и называют вслух

-- Задача 0060, уточнение владельца продукта от 11 сентября 2026: «порядковый
-- номер также должен отображаться, чтобы человек мог применять
-- человекочитаемые цифры для точной идентификации конкретной запчасти
-- при общении с другими работниками».
--
-- ПОЧЕМУ НЕ id. Он внутренний: меняется при переносе, не переживает
-- восстановление в другую схему, и названный человеку однажды обязывает
-- хранить его вечно. Образец в проекте уже есть и ровно про это — у сделки
-- два поля: id и number из deal_number_seq (tenant/006), и вслух называют
-- второе («Сделка №20»).
--
-- ПОЧЕМУ НЕ public_code. Тот шесть случайных байт — неугадываемость нужна
-- затем, чтобы по номеру объявления конкурент не посчитал склад, — и «посмотри
-- позицию 7584A8FEAE3D» по телефону не произносится. Он остаётся: он про
-- этикетку и сканер, Code128 кодирует его, а «347» — нет. Два поля живут
-- рядом, как id и number у сделки.
CREATE SEQUENCE ${tenant.schema}.part_number_seq;

ALTER TABLE ${tenant.schema}.part ADD COLUMN number bigint;

-- ГЛАВНОЕ МЕСТО ЭТОГО CHANGESET'А: номера раздаются строго в порядке
-- заведения, по id. У переехавшего клиента 35 841 позиция, и номер
-- обязан совпасть с тем порядком, в котором витрина их показывает,
-- иначе «позиция 347» окажется не там, где её ищут, — а названный
-- человеку номер менять уже нельзя.
--
-- Не по дате приёмки: 35 841 позиция переехавшего клиента легла одним днём,
-- и одинаковые даты не задают порядка вовсе. Не по public_code: он случаен.
UPDATE ${tenant.schema}.part p
   SET number = ordered.number
  FROM (SELECT id, row_number() OVER (ORDER BY id) AS number
          FROM ${tenant.schema}.part) ordered
 WHERE p.id = ordered.id;

-- Последовательность продолжает с розданного, а не начинает заново: иначе
-- первая же заведённая после наката позиция получила бы номер 1, занятый
-- строкой переезда, и уникальность отбила бы приёмку. На пустой схеме
-- (новый клиент) max нет — следующий будет 1.
SELECT setval('${tenant.schema}.part_number_seq',
              coalesce((SELECT max(number) FROM ${tenant.schema}.part), 0) + 1,
              false);

ALTER TABLE ${tenant.schema}.part
    ALTER COLUMN number SET DEFAULT nextval('${tenant.schema}.part_number_seq');
ALTER TABLE ${tenant.schema}.part ALTER COLUMN number SET NOT NULL;

COMMENT ON COLUMN ${tenant.schema}.part.number IS
    'Порядковый номер позиции для человека: его показывают, по нему сортируют и его называют вслух. Задача 0060';

-- Уникальность не для красоты: номер называют вслух, и два одинаковых
-- означают, что по нему нашли не ту деталь.
CREATE UNIQUE INDEX part_number_uk ON ${tenant.schema}.part (number);

--rollback DROP INDEX ${tenant.schema}.part_number_uk;
--rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN number;
--rollback DROP SEQUENCE ${tenant.schema}.part_number_seq;
