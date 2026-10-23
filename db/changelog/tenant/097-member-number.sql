--liquibase formatted sql

--changeset partsflow:tenant-097-member-number
--comment Видимый номер сотрудника: порядковый внутри компании, неизменный, его называют вслух (задача 0373)

-- Ответ владельца 23.10.2026 (tasks/0373, раздел «Ответ владельца — 23 октября
-- 2026», пункт 10): «Показывать логин имя и номер сотрудника, мы должны
-- идентифицировать сотрудника на 100%». Автор записи в истории карточки
-- подписывается логином, именем и номером, как у Bazon («ID NNNN»).
--
-- ПОЧЕМУ НЕ id. Он внутренний: меняется при переносе, не переживает
-- восстановление в другую схему, и наружу не показывается
-- (InternalIdsNotShownTest). Образец тот же, что у part.number (tenant/064)
-- и deal.number (tenant/006): id остаётся внутренним, рядом стоит свой номер
-- из последовательности с уникальным индексом.
--
-- «Внутри компании» — это внутри схемы арендатора: схема одна на компанию,
-- поэтому уникальный индекс по одной колонке и есть уникальность в компании,
-- а у второй компании номера снова начинаются с единицы.
--
-- «Неизменный» держит код, а не база: колонку не правит ни один UPDATE
-- приложения, а запрета на правку строки в схеме не заводим (это логика,
-- а не форма данных). Сотрудники не удаляются (MemberService.setActive),
-- поэтому однажды названный номер не освобождается и второму не достаётся.
CREATE SEQUENCE ${tenant.schema}.tenant_member_number_seq;

ALTER TABLE ${tenant.schema}.tenant_member ADD COLUMN number bigint;

-- Уже заведённым номера раздаются в порядке заведения, по id: так нумерация
-- совпадает с тем, как список «Сотрудники» их и показывает (ORDER BY id),
-- и владелец получает первым номером себя. Не по created_at: строки,
-- заведённые одной транзакцией провижининга, имеют одно время.
UPDATE ${tenant.schema}.tenant_member m
   SET number = ordered.number
  FROM (SELECT id, row_number() OVER (ORDER BY id) AS number
          FROM ${tenant.schema}.tenant_member) ordered
 WHERE m.id = ordered.id;

-- Последовательность продолжает с розданного, а не с единицы: иначе первый
-- же сотрудник, заведённый после наката, получил бы занятый номер,
-- и уникальность отбила бы его заведение. На пустой схеме (новый клиент)
-- max нет, и первым будет 1.
SELECT setval('${tenant.schema}.tenant_member_number_seq',
              coalesce((SELECT max(number) FROM ${tenant.schema}.tenant_member), 0) + 1,
              false);

-- Номер присваивает умолчание: все три места, где заводится строка
-- (MemberService, провижининг, AdminVisits), колонку не называют. Прежний
-- образ её не знает и получает номер так же — поэтому умолчание ставится
-- раньше NOT NULL, и это expand.
ALTER TABLE ${tenant.schema}.tenant_member
    ALTER COLUMN number SET DEFAULT nextval('${tenant.schema}.tenant_member_number_seq');
ALTER TABLE ${tenant.schema}.tenant_member ALTER COLUMN number SET NOT NULL;

COMMENT ON COLUMN ${tenant.schema}.tenant_member.number IS
    'Видимый номер сотрудника: порядковый внутри компании, не меняется; его показывают рядом с логином и именем. Задача 0373';

-- Уникальность не для красоты: номер затем и заведён, чтобы опознать
-- сотрудника «на 100%», и два одинаковых означали бы, что по нему нашли
-- не того человека.
CREATE UNIQUE INDEX tenant_member_number_uk ON ${tenant.schema}.tenant_member (number);

--rollback-теряет видимые номера сотрудников: повторный накат раздаёт их заново по порядку заведения, и номер, названный человеку, может стать другим, если сотрудники заводились одновременно. Сами сотрудники, их логины и роли остаются
--rollback DROP INDEX ${tenant.schema}.tenant_member_number_uk;
--rollback ALTER TABLE ${tenant.schema}.tenant_member DROP COLUMN number;
--rollback DROP SEQUENCE ${tenant.schema}.tenant_member_number_seq;
