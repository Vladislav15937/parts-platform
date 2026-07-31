--liquibase formatted sql

--changeset platform:tenant-220-donor-brand-nullable
--comment Марка машины может быть неизвестной, и это честнее нуля.
--comment Импорт из чужой системы ставил brand_id = 0 — ссылку на марку,
--comment которой в каталоге нет вовсе. Внешнего ключа на колонке не было,
--comment и база это пропускала: у переехавшего клиента не работали ни фильтр
--comment по марке, ни применимость, а выглядело это как заполненное поле.
--comment
--comment Заводить свою марку вместо ненайденной нельзя: через месяц в каталоге
--comment будут «Тойота», «тойота» и «Toyota» — ровно та беда, от которой
--comment избавляет общий справочник.
ALTER TABLE ${tenant.schema}.donor ALTER COLUMN brand_id DROP NOT NULL;

UPDATE ${tenant.schema}.donor SET brand_id = NULL WHERE brand_id = 0;

-- Теперь колонка либо пуста, либо указывает на настоящую марку, и это
-- стережёт база, а не дисциплина импортёра.
ALTER TABLE ${tenant.schema}.donor
    ADD CONSTRAINT donor_brand_fk FOREIGN KEY (brand_id) REFERENCES catalog.brand;
ALTER TABLE ${tenant.schema}.donor
    ADD CONSTRAINT donor_model_fk FOREIGN KEY (model_id) REFERENCES catalog.model;
--rollback ALTER TABLE ${tenant.schema}.donor DROP CONSTRAINT donor_model_fk, DROP CONSTRAINT donor_brand_fk;
