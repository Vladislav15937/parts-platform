--liquibase formatted sql

--changeset platform:tenant-055-search-trgm
--comment Индексы под поиск витрины: номер товара и номер производителя.
--comment
--comment Нагрузочная проба ячейки показала, что поиск по складу в 35 841
--comment позицию идёт полным перебором: 744 мс на запрос, а при тридцати
--comment параллельных пользователях — до 4,3 секунды. Владелец ищет деталь
--comment по телефону, пока покупатель ждёт на линии.
--comment
--comment Причина в том, что условия соединены через OR, и планировщик берёт
--comment индекс, только если проиндексированы все ветки. По title и по
--comment полнотекстовому вектору индексы были с самого начала, а по
--comment public_code и part_oem.raw_number — нет.
--comment
--comment Одних индексов мало: запрос ещё и переписан на UNION вместо OR
--comment (CatalogService.filterOf) — тогда каждая ветка идёт своим индексом,
--comment и планировщику не надо угадывать кардинальность '%фара%'.
--comment Замерено на живом складе: 744 мс → 38 мс.
CREATE INDEX part_code_trgm ON ${tenant.schema}.part USING gin (public_code gin_trgm_ops);
CREATE INDEX part_oem_raw_trgm ON ${tenant.schema}.part_oem USING gin (raw_number gin_trgm_ops);
--rollback DROP INDEX IF EXISTS ${tenant.schema}.part_code_trgm;
--rollback DROP INDEX IF EXISTS ${tenant.schema}.part_oem_raw_trgm;
