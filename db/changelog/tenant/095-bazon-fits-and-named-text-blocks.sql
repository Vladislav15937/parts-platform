--liquibase formatted sql

--changeset partsflow:tenant-095-bazon-fits-and-named-text-blocks
--comment Блок «<Вид> подходит:» из файлов Bazon у позиции (part_drom_card.bazon_fits) и именованные текстовые блоки (text_block, part.text_block_id) с переносом part.text_block, задача 0376

-- Решение владельца продукта 19.10.2026 (tasks/0337, «Ответ владельца»,
-- вопросы 10 и 11; tasks/0376): описание объявления Дрома собирается как
-- у Bazon. Под это схеме не хватало двух вещей, и обе здесь.
--
-- 1. ГОТОВЫЙ ТЕКСТ БЛОКА «<Вид> подходит:» ИЗ ФАЙЛОВ BAZON — колонка
-- part_drom_card.bazon_fits. Не part_drom_card.applicability (tenant/082):
-- там «Применимость» карточки Дрома, текст другого формата, и смешать их
-- значило бы потерять, откуда строка. Хранится дословно, как блок стоит
-- в файле выгрузки клиента, с переводами строк; разбирать его здесь
-- нечем и незачем — писатель описания (0337) печатает его как есть.
-- В боковой таблице, а не в part, по той же причине, что у 082–084:
-- блок бывает в десятки тысяч знаков, а журнал изменений снимает строку
-- part целиком до и после каждой правки. Индекса нет: по тексту
-- ничего не ищут.
ALTER TABLE ${tenant.schema}.part_drom_card
    ADD COLUMN bazon_fits text,
    ADD CONSTRAINT part_drom_card_bazon_fits_ck CHECK (bazon_fits ~ '\S');

COMMENT ON COLUMN ${tenant.schema}.part_drom_card.bazon_fits IS
    'Блок «<Вид> подходит:» описания объявления дословно, как в файле выгрузки предыдущей системы (задача 0376). Не то же, что applicability: там текст карточки Дрома. NULL — блока не было';

-- 2. ИМЕНОВАННЫЕ ТЕКСТОВЫЕ БЛОКИ. До этого changeset'а текст блока
-- («оплата только наличными» и т. п.) лежал в каждой позиции копией
-- (part.text_block, tenant/039). Теперь это справочник: имя и текст,
-- имя уникально в арендаторе без учёта регистра и пробелов по краям
-- (то же правило, что у part_name и логина сотрудника). Позиция
-- ссылается на блок. Удалить блок, выбранный хоть в одной позиции, база
-- не даст (ссылка без ON DELETE): снятый блок молча пропал бы из тысяч
-- объявлений, и что с ними делать, решает код, а не каскад.
CREATE TABLE ${tenant.schema}.text_block (
    id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name text NOT NULL,
    body text NOT NULL,
    CONSTRAINT text_block_name_ck CHECK (name ~ '\S'),
    CONSTRAINT text_block_body_ck CHECK (body ~ '\S')
);

CREATE UNIQUE INDEX text_block_name_uk ON ${tenant.schema}.text_block (lower(btrim(name)));

COMMENT ON TABLE ${tenant.schema}.text_block IS
    'Именованные текстовые блоки описания объявления, как у предыдущей системы: позиция выбирает блок по имени, текст уезжает в описание дословно (задача 0376)';
COMMENT ON COLUMN ${tenant.schema}.text_block.name IS
    'Имя блока для выбора в карточке. Уникально в компании без учёта регистра и пробелов по краям';
COMMENT ON COLUMN ${tenant.schema}.text_block.body IS
    'Текст блока, как уезжает в описание объявления: с переводами строк';

ALTER TABLE ${tenant.schema}.part
    ADD COLUMN text_block_id bigint REFERENCES ${tenant.schema}.text_block (id);

CREATE INDEX part_text_block_idx ON ${tenant.schema}.part (text_block_id)
    WHERE text_block_id IS NOT NULL;

COMMENT ON COLUMN ${tenant.schema}.part.text_block_id IS
    'Выбранный текстовый блок описания (text_block). NULL — блока нет. Заменяет part.text_block (задача 0376)';

-- ПЕРЕНОС ТОГО, ЧТО УЖЕ ЛЕЖИТ. Одинаковые тексты схлопываются в один
-- блок: равенство — побайтное, текст как лежит, без чистки (чистка была
-- бы догадкой о том, какие различия клиент считает одинаковыми). Текст
-- из одних пробелов блоком не становится — CHECK его не пустил бы,
-- и «блока нет» читается одним способом.
--
-- Имя — по тексту: сам текст в одну строку (пробелы и переводы строк
-- сжаты в пробел), не длиннее 60 знаков, длинный обрезан с «…». Имени,
-- данного в Bazon («Наличные»), в данных у нас нет, взять его неоткуда;
-- переименовывает человек в карточке (0337). Если два разных текста
-- дали одно имя (общее начало длиннее 60 знаков или разница только
-- в пробелах и регистре), второй и следующие получают « (2)», « (3)» —
-- по порядку первой позиции, где текст встретился, чтобы накат на одной
-- и той же схеме давал одни и те же имена.
INSERT INTO ${tenant.schema}.text_block (name, body)
SELECT CASE WHEN n = 1 THEN base ELSE base || ' (' || n || ')' END, body
  FROM (SELECT body, base,
               row_number() OVER (PARTITION BY lower(base) ORDER BY first_part) AS n,
               first_part
          FROM (SELECT body, first_part,
                       CASE WHEN char_length(flat) > 60
                            THEN btrim(left(flat, 59)) || '…'
                            ELSE flat END AS base
                  FROM (SELECT text_block AS body, min(id) AS first_part,
                               btrim(regexp_replace(text_block, '\s+', ' ', 'g')) AS flat
                          FROM ${tenant.schema}.part
                         WHERE text_block ~ '\S'
                         GROUP BY text_block) AS distinct_texts) AS named) AS numbered
 ORDER BY first_part;

UPDATE ${tenant.schema}.part AS p
   SET text_block_id = tb.id
  FROM ${tenant.schema}.text_block AS tb
 WHERE tb.body = p.text_block;

-- part.text_block НЕ СНИМАЕТСЯ этим changeset'ом. Его читает и пишет
-- нынешний код (прайс Дрома, витрина, карточка), а снимать колонку тем
-- же changeset'ом, где заводится замена, — не expand/contract. Снимает её
-- следующий changeset с пометкой --сужение, после релиза, где код
-- перестал её трогать (db/CLAUDE.md, «Порядок удаления и N»). До того
-- код, выбирающий блок, пишет и ссылку, и текст: прежний образ после
-- отката приложения читает текст.

--rollback-теряет имена текстовых блоков и выбор блока у позиций, и блоки «подходит» из файлов предыдущей системы: тексты блоков у позиций остаются в part.text_block (откат возвращает туда текст выбранного блока), а блок «подходит» вернуть можно только повторным переносом файлов клиента
--rollback UPDATE ${tenant.schema}.part AS p SET text_block = tb.body FROM ${tenant.schema}.text_block AS tb WHERE tb.id = p.text_block_id AND p.text_block IS DISTINCT FROM tb.body;
--rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN text_block_id;
--rollback DROP TABLE ${tenant.schema}.text_block;
--rollback ALTER TABLE ${tenant.schema}.part_drom_card DROP CONSTRAINT part_drom_card_bazon_fits_ck, DROP COLUMN bazon_fits;
