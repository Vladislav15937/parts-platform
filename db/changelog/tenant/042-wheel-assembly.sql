--liquibase formatted sql

--changeset platform:tenant-042-wheel-brands
--comment Производитель шины и производитель диска — разные поля, а не одно.
--comment
--comment Пока видов товара было два, поле годилось одно: строка — либо шина,
--comment либо диск, и «производитель» означал то или другое. У колеса
--comment в сборе оба заполнены разом и разными значениями: у живого клиента
--comment шина Dunlop на диске Mitsubishi. Одним полем это не записать.
--comment
--comment Прежние brand/model становятся шинными, дисковые переезжают
--comment в свои колонки. Колонки не удаляются в этом же changeset'е нарочно:
--comment порядок выкладки expand/contract, и код, читающий brand у диска,
--comment должен успеть уехать раньше, чем колонка исчезнет.
ALTER TABLE ${tenant.schema}.part_wheel
    ADD COLUMN disc_brand text,
    ADD COLUMN disc_model text;

UPDATE ${tenant.schema}.part_wheel
   SET disc_brand = brand, disc_model = model, brand = NULL, model = NULL
 WHERE kind = 'DISC';
--rollback ALTER TABLE ${tenant.schema}.part_wheel DROP COLUMN disc_brand, DROP COLUMN disc_model;

--changeset platform:tenant-042-wheel-assembly
--comment Колесо в сборе — третий вид товара.
--comment
--comment Снято с кабинета клиента: там у товара три значения — «Шина»,
--comment «Диск» и «Колесо», и у последнего заполнены оба набора свойств.
--comment Продают его так же поштучно и заводят комплектом: у живого клиента
--comment комплект №181 — четыре колеса 225/55 R18 Dunlop на дисках Rays.
--comment
--comment Отдельной таблицей это делать нельзя по той же причине, по которой
--comment шины и диски живут в одной: склад, резерв, продажа и пересчёт
--comment написаны на part, и третий путь через них разошёлся бы с первыми
--comment двумя на первой же правке.
ALTER TABLE ${tenant.schema}.part_wheel
    DROP CONSTRAINT part_wheel_kind_ck,
    ADD CONSTRAINT part_wheel_kind_ck CHECK (kind IN ('TYRE', 'DISC', 'ASSEMBLY'));
--rollback ALTER TABLE ${tenant.schema}.part_wheel DROP CONSTRAINT part_wheel_kind_ck, ADD CONSTRAINT part_wheel_kind_ck CHECK (kind IN ('TYRE', 'DISC'));

--changeset platform:tenant-042-winter-kinds
--comment Зимняя шина бывает шипованной и на липучке, и это разные товары.
--comment
--comment У клиента сезон так и записан — «Зимняя (липучка)». Разница
--comment не косметическая: шипы в части регионов запрещены летом,
--comment ездят на них иначе, и стоят они по-разному. Покупатель спрашивает
--comment именно «шипы или липучка», а ответ «зимняя» его не устраивает.
--comment
--comment Прежнее WINTER остаётся: у уже заведённых шин неизвестно, какие
--comment они, и додумывать за приёмщика нельзя — тихо поставленные шипы
--comment там, где липучка, хуже неизвестности.
ALTER TABLE ${tenant.schema}.part_wheel
    DROP CONSTRAINT part_wheel_season_ck,
    ADD CONSTRAINT part_wheel_season_ck CHECK (season IS NULL OR season IN
        ('SUMMER', 'WINTER', 'WINTER_STUDDED', 'WINTER_FRICTION', 'ALL_SEASON'));
--rollback ALTER TABLE ${tenant.schema}.part_wheel DROP CONSTRAINT part_wheel_season_ck, ADD CONSTRAINT part_wheel_season_ck CHECK (season IS NULL OR season IN ('SUMMER', 'WINTER', 'ALL_SEASON'));
