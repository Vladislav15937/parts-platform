--liquibase formatted sql

--changeset platform:tenant-051-oem-normalized-plain
--comment Приведение номера производителя переехало в Java
--comment (`catalog.OemNumbers.normalize`).
--comment
--comment Это настоящая логика, а не форма данных: приведение решает, что
--comment считать одним номером. Пока его считала колонка, решение жило
--comment в двух местах — в базе и в голове у того, кто ищет.
--comment
--comment DROP EXPRESSION, а не «удалить и завести заново»: значения остаются
--comment на месте, индексы тоже. Колонка из генерируемой становится обычной,
--comment и заполняет её теперь тот, кто пишет номер.
ALTER TABLE ${tenant.schema}.part_oem ALTER COLUMN normalized DROP EXPRESSION;
--rollback SELECT 1;

--changeset platform:tenant-051-search-vector-expression splitStatements:false
--comment Полнотекстовый вектор больше не колонка, а выражение в запросе
--comment и индекс на нём.
--comment
--comment Собрать его вне Postgres нельзя: русская морфология живёт в самой
--comment базе, и «фары» с «фарой» сводит к одному слову словарь, которого
--comment в Java нет. Поэтому выражение остаётся, но перестаёт быть
--comment хранимой колонкой — а GIN-индекс на том же выражении сохраняет
--comment план запроса: поиск по-прежнему идёт по индексу, а не перебором
--comment тридцати пяти тысяч строк.
--comment
--comment Индекс — не логика: он ничего не меняет и ни на что не влияет,
--comment кроме скорости. Убери его — ответы будут те же.
DROP INDEX IF EXISTS ${tenant.schema}.part_search_gin;
ALTER TABLE ${tenant.schema}.part DROP COLUMN IF EXISTS search_vector;
CREATE INDEX part_search_gin ON ${tenant.schema}.part USING gin (
    to_tsvector('russian', coalesce(title, '') || ' '
        || coalesce(description, '') || ' ' || coalesce(marking, '')));
--rollback SELECT 1;

--changeset platform:tenant-051-qty-available-expression
--comment Свободный остаток считается там, где спрашивают, а не хранится
--comment колонкой.
--comment
--comment `qty - qty_reserved` — вычитание, и держать его в базе значило
--comment держать там правило «свободно это всё минус обещанное». Правило
--comment теперь в запросах приложения, а частичный индекс экрана продавца
--comment переписан на то же выражение: план не изменился.
DROP INDEX IF EXISTS ${tenant.schema}.part_stock_available_ix;
ALTER TABLE ${tenant.schema}.part_stock DROP COLUMN IF EXISTS qty_available;
CREATE INDEX part_stock_available_ix ON ${tenant.schema}.part_stock (warehouse_id, part_id)
    WHERE qty - qty_reserved > 0;
--rollback SELECT 1;
