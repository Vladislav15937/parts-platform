--liquibase formatted sql

-- Приведение схемы к операционной модели, снятой с работающей разборки
-- (см. docs/bazon-parity.md). Три структурных расхождения, которые нельзя
-- дописать полем: товар приходит поставкой, остаток живёт по складам,
-- движение оформляется документом.

--changeset platform:tenant-090-supply
--comment Поставка. Исходная модель предполагала, что источник товара всегда
--comment донор: купили битую машину, разобрали. На практике машины и запчасти
--comment приходят контейнерами, и контрактная запчасть приезжает вообще без
--comment донора. Поставка — узел, к которому привязаны и доноры, и товары.
CREATE TABLE ${tenant.schema}.supply (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind          text        NOT NULL DEFAULT 'CONTAINER',
    number        text        NOT NULL,
    supplier_name text,
    -- Дата прихода, а не создания записи: поставку заводят заранее,
    -- когда контейнер ещё в море.
    arrived_on    date,
    status        text        NOT NULL DEFAULT 'EXPECTED',
    note          text,
    created_by    bigint      REFERENCES ${tenant.schema}.tenant_member,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT supply_uk UNIQUE (kind, number),
    CONSTRAINT supply_kind_ck CHECK (kind IN ('CONTAINER','PURCHASE','OTHER')),
    CONSTRAINT supply_status_ck CHECK (status IN ('EXPECTED','IN_TRANSIT','ARRIVED','CLOSED'))
);
CREATE INDEX supply_status_ix ON ${tenant.schema}.supply (status, arrived_on DESC);

CREATE TRIGGER supply_touch BEFORE UPDATE ON ${tenant.schema}.supply
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.touch_updated_at();
--rollback DROP TABLE ${tenant.schema}.supply;

--changeset platform:tenant-091-donor-logistics
--comment Донор приезжает из-за границы: руль, привод, модель КПП и код цвета
--comment покупателю важнее года выпуска, а статус — это место в логистической
--comment цепочке («в пути», «в Барнауле»), а не стадия разбора.
ALTER TABLE ${tenant.schema}.donor
    ADD COLUMN supply_id           bigint REFERENCES ${tenant.schema}.supply,
    ADD COLUMN location            text,
    ADD COLUMN steering            text,
    ADD COLUMN drive_type          text,
    ADD COLUMN transmission_type   text,
    ADD COLUMN transmission_model  text,
    ADD COLUMN color_code          text,
    ADD COLUMN equipment_code      text;

ALTER TABLE ${tenant.schema}.donor
    ADD CONSTRAINT donor_steering_ck
        CHECK (steering IS NULL OR steering IN ('LEFT','RIGHT')),
    ADD CONSTRAINT donor_drive_ck
        CHECK (drive_type IS NULL OR drive_type IN ('FWD','RWD','AWD')),
    -- AMT — это «робот» в терминах разборщиков; в выгрузке он встречается,
    -- и без него четыре машины не проходят импорт.
    ADD CONSTRAINT donor_transmission_ck
        CHECK (transmission_type IS NULL OR transmission_type IN ('MT','AT','CVT','AMT','DCT'));

CREATE INDEX donor_supply_ix ON ${tenant.schema}.donor (supply_id) WHERE supply_id IS NOT NULL;
--rollback ALTER TABLE ${tenant.schema}.donor DROP CONSTRAINT donor_transmission_ck, DROP CONSTRAINT donor_drive_ck, DROP CONSTRAINT donor_steering_ck;
--rollback ALTER TABLE ${tenant.schema}.donor DROP COLUMN equipment_code, DROP COLUMN color_code, DROP COLUMN transmission_model, DROP COLUMN transmission_type, DROP COLUMN drive_type, DROP COLUMN steering, DROP COLUMN location, DROP COLUMN supply_id;

--changeset platform:tenant-092-part-stock
--comment Остаток по складам. Раньше деталь висела на одной ячейке, и остаток
--comment был скаляром. У разборки с несколькими площадками одна номенклатурная
--comment позиция лежит на нескольких складах одновременно, и продавец смотрит
--comment именно на разрез по складам — в списке товаров это отдельные колонки.
-- Остаток на складе — не одно число, а три. Это видно в выгрузке склада:
-- на каждый склад выгружаются колонки «свободно», «резерв» и «ожидается».
-- Продавец по телефону должен различать «есть сейчас», «обещано другому»
-- и «приедет контейнером» — это три разных ответа клиенту.
CREATE TABLE ${tenant.schema}.part_stock (
    part_id      bigint        NOT NULL REFERENCES ${tenant.schema}.part ON DELETE CASCADE,
    warehouse_id bigint        NOT NULL REFERENCES ${tenant.schema}.warehouse,
    -- Физически лежит и свободно к продаже. Ведётся триггером по журналу.
    qty          numeric(12,3) NOT NULL DEFAULT 0,
    -- Лежит, но обещано: заполняется из reservation, а не из журнала движений.
    qty_reserved numeric(12,3) NOT NULL DEFAULT 0,
    -- Ещё не приехало: заполняется из поставки, физического остатка нет.
    qty_expected numeric(12,3) NOT NULL DEFAULT 0,
    cell_id      bigint        REFERENCES ${tenant.schema}.storage_cell,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (part_id, warehouse_id),
    CONSTRAINT part_stock_qty_ck CHECK (qty >= 0 AND qty_reserved >= 0 AND qty_expected >= 0),
    -- Нельзя зарезервировать больше, чем лежит: это прямой путь к продаже
    -- одной детали двум клиентам.
    CONSTRAINT part_stock_reserved_ck CHECK (qty_reserved <= qty)
);
-- «Что лежит на этом складе» и «где искать эту деталь» — оба направления частые.
CREATE INDEX part_stock_warehouse_ix ON ${tenant.schema}.part_stock (warehouse_id)
    WHERE qty > 0;
CREATE INDEX part_stock_cell_ix ON ${tenant.schema}.part_stock (cell_id)
    WHERE cell_id IS NOT NULL;
--rollback DROP TABLE ${tenant.schema}.part_stock;

--changeset platform:tenant-093-stock-movement-warehouse
--comment Движение теперь знает склад-источник и склад-приёмник. Без этого
--comment перемещение между площадками неотличимо от прихода и расхода.
ALTER TABLE ${tenant.schema}.stock_movement
    ADD COLUMN from_warehouse_id bigint REFERENCES ${tenant.schema}.warehouse,
    ADD COLUMN to_warehouse_id   bigint REFERENCES ${tenant.schema}.warehouse,
    ADD COLUMN document_id       bigint;

CREATE INDEX stock_movement_document_ix ON ${tenant.schema}.stock_movement (document_id)
    WHERE document_id IS NOT NULL;
--rollback ALTER TABLE ${tenant.schema}.stock_movement DROP COLUMN document_id, DROP COLUMN to_warehouse_id, DROP COLUMN from_warehouse_id;

--changeset platform:tenant-094-stock-document
--comment Складской документ. Движение — это факт, но пользователь работает не
--comment с фактами, а с документами: «Поступление 7163», статус «Выполнен».
--comment Черновик можно собирать и править; проведение порождает движения,
--comment и с этого момента журнал неизменяем.
CREATE SEQUENCE ${tenant.schema}.stock_document_number_seq START 1;

CREATE TABLE ${tenant.schema}.stock_document (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    number          bigint      NOT NULL DEFAULT nextval('${tenant.schema}.stock_document_number_seq'),
    doc_type        text        NOT NULL,
    status          text        NOT NULL DEFAULT 'DRAFT',
    warehouse_id    bigint      NOT NULL REFERENCES ${tenant.schema}.warehouse,
    to_warehouse_id bigint      REFERENCES ${tenant.schema}.warehouse,
    supply_id       bigint      REFERENCES ${tenant.schema}.supply,
    note            text,
    created_by      bigint      REFERENCES ${tenant.schema}.tenant_member,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    completed_at    timestamptz,
    CONSTRAINT stock_document_number_uk UNIQUE (number),
    CONSTRAINT stock_document_type_ck CHECK (doc_type IN
        ('INTAKE','MOVE','WRITE_OFF','RETURN','INVENTORY')),
    CONSTRAINT stock_document_status_ck CHECK (status IN ('DRAFT','DONE','CANCELLED')),
    -- Перемещение обязано указывать куда, остальные документы — не должны.
    CONSTRAINT stock_document_move_ck CHECK (
        (doc_type = 'MOVE' AND to_warehouse_id IS NOT NULL AND to_warehouse_id <> warehouse_id)
        OR (doc_type <> 'MOVE' AND to_warehouse_id IS NULL)),
    CONSTRAINT stock_document_completed_ck CHECK (
        (status = 'DONE') = (completed_at IS NOT NULL))
);
CREATE INDEX stock_document_status_ix ON ${tenant.schema}.stock_document (status, created_at DESC);
CREATE INDEX stock_document_warehouse_ix ON ${tenant.schema}.stock_document (warehouse_id, created_at DESC);
CREATE INDEX stock_document_supply_ix ON ${tenant.schema}.stock_document (supply_id)
    WHERE supply_id IS NOT NULL;

CREATE TRIGGER stock_document_touch BEFORE UPDATE ON ${tenant.schema}.stock_document
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.touch_updated_at();

CREATE TABLE ${tenant.schema}.stock_document_line (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id bigint        NOT NULL REFERENCES ${tenant.schema}.stock_document ON DELETE CASCADE,
    part_id     bigint        NOT NULL REFERENCES ${tenant.schema}.part,
    qty         numeric(12,3) NOT NULL,
    price       numeric(14,2),
    cell_id     bigint        REFERENCES ${tenant.schema}.storage_cell,
    CONSTRAINT stock_document_line_uk UNIQUE (document_id, part_id),
    CONSTRAINT stock_document_line_qty_ck CHECK (qty > 0)
);
CREATE INDEX stock_document_line_document_ix ON ${tenant.schema}.stock_document_line (document_id);
CREATE INDEX stock_document_line_part_ix ON ${tenant.schema}.stock_document_line (part_id);

ALTER TABLE ${tenant.schema}.stock_movement
    ADD CONSTRAINT stock_movement_document_fk
        FOREIGN KEY (document_id) REFERENCES ${tenant.schema}.stock_document;
--rollback ALTER TABLE ${tenant.schema}.stock_movement DROP CONSTRAINT stock_movement_document_fk;
--rollback DROP TABLE ${tenant.schema}.stock_document_line;
--rollback DROP TABLE ${tenant.schema}.stock_document;
--rollback DROP SEQUENCE ${tenant.schema}.stock_document_number_seq;

--changeset platform:tenant-095-stock-apply-warehouse splitStatements:false runOnChange:true
--comment ВНИМАНИЕ: заменяет функцию из 005-inventory.sql (changeset tenant-043).
--comment Тот changeset трогать нельзя — чек-сумма; поэтому новая версия живёт
--comment здесь и переопределяет старую порядком применения.
--comment Теперь триггер ведёт два кэша: part_stock по складам и part.qty_on_hand
--comment как сумму по всем складам. Оба — производные от журнала, писать в них
--comment из кода по-прежнему нельзя.
CREATE OR REPLACE FUNCTION ${tenant.schema}.stock_movement_apply()
    RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    -- Расход со склада-источника: приход и корректировка его не указывают.
    IF NEW.from_warehouse_id IS NOT NULL THEN
        UPDATE ${tenant.schema}.part_stock
           SET qty = qty - abs(NEW.qty_delta),
               updated_at = now()
         WHERE part_id = NEW.part_id
           AND warehouse_id = NEW.from_warehouse_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION
                'Нет остатка детали % на складе %: списывать нечего',
                NEW.part_id, NEW.from_warehouse_id;
        END IF;
    END IF;

    -- Приход на склад-приёмник.
    IF NEW.to_warehouse_id IS NOT NULL THEN
        INSERT INTO ${tenant.schema}.part_stock (part_id, warehouse_id, qty, cell_id)
        VALUES (NEW.part_id, NEW.to_warehouse_id, abs(NEW.qty_delta), NEW.to_cell_id)
        ON CONFLICT (part_id, warehouse_id) DO UPDATE
            SET qty = ${tenant.schema}.part_stock.qty + abs(NEW.qty_delta),
                cell_id = COALESCE(EXCLUDED.cell_id, ${tenant.schema}.part_stock.cell_id),
                updated_at = now();
    END IF;

    -- Общий остаток пересчитывается от part_stock, а не инкрементом: при
    -- перемещении дельта нулевая, а раскладка по складам меняется.
    UPDATE ${tenant.schema}.part p
       SET qty_on_hand = COALESCE((
               SELECT sum(ps.qty)
                 FROM ${tenant.schema}.part_stock ps
                WHERE ps.part_id = NEW.part_id), 0),
           storage_cell_id = COALESCE(NEW.to_cell_id, p.storage_cell_id)
     WHERE p.id = NEW.part_id;

    RETURN NEW;
END $fn$;
--rollback SELECT 1;

--changeset platform:tenant-096-part-name
--comment Справочник наименований арендатора. Приёмщик пишет «телевизор», имея
--comment в виду переднюю панель рамки радиатора, и так делают все — но каждый
--comment по-своему. Локальное написание сопоставляется с эталоном из общего
--comment каталога; несопоставленные висят отдельным списком и разгребаются
--comment руками. Без этого слоя не работают ни поиск, ни выгрузки.
CREATE TABLE ${tenant.schema}.part_name (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         text        NOT NULL,
    part_kind_id bigint,
    category_id  bigint,
    match_status text        NOT NULL DEFAULT 'UNMATCHED',
    usage_count  int         NOT NULL DEFAULT 0,
    created_by   bigint      REFERENCES ${tenant.schema}.tenant_member,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT part_name_match_ck CHECK (match_status IN ('UNMATCHED','AUTO','MANUAL')),
    -- Сопоставленное обязано ссылаться на эталон, несопоставленное — не может.
    CONSTRAINT part_name_kind_ck CHECK (
        (match_status = 'UNMATCHED') = (part_kind_id IS NULL))
);
-- Регистр и хвостовые пробелы не должны плодить дубли «Фара» / «фара ».
CREATE UNIQUE INDEX part_name_uk ON ${tenant.schema}.part_name (lower(btrim(name)));
CREATE INDEX part_name_unmatched_ix ON ${tenant.schema}.part_name (created_at DESC)
    WHERE match_status = 'UNMATCHED';
CREATE INDEX part_name_trgm ON ${tenant.schema}.part_name USING gin (name gin_trgm_ops);

CREATE TRIGGER part_name_touch BEFORE UPDATE ON ${tenant.schema}.part_name
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.touch_updated_at();
--rollback DROP TABLE ${tenant.schema}.part_name;

--changeset platform:tenant-097-part-attributes
--comment Поля товара, без которых не собрать ни объявление, ни доставку.
--comment Три оси стороны вместо одного текстового поля: этого требует формат
--comment прайса Дрома (lr / fr / ud), и так же устроен ввод у приёмщика.
ALTER TABLE ${tenant.schema}.part
    ADD COLUMN supply_id          bigint REFERENCES ${tenant.schema}.supply,
    ADD COLUMN part_name_id       bigint REFERENCES ${tenant.schema}.part_name,
    ADD COLUMN side_lr            text,
    ADD COLUMN side_fr            text,
    ADD COLUMN side_ud            text,
    ADD COLUMN manufacturer       text,
    ADD COLUMN color              text,
    -- Адрес хранения в номенклатуре клиента: в выгрузке это строго пять
    -- уровней вида 01-02-02-03-01. Дублирует storage_cell намеренно — клиенты
    -- переезжают со своей нумерацией полок и расставаться с ней не готовы.
    ADD COLUMN section            text,
    -- Цена установки детали силами разборки. Именно цена, а не место:
    -- в выгрузке это число, у 99% позиций нулевое.
    ADD COLUMN installation_price numeric(14,2),
    ADD COLUMN note               text,
    ADD COLUMN length_mm          int,
    ADD COLUMN width_mm           int,
    ADD COLUMN height_mm          int,
    ADD COLUMN package_length_mm  int,
    ADD COLUMN package_width_mm   int,
    ADD COLUMN package_height_mm  int,
    ADD COLUMN package_weight_kg  numeric(10,3),
    ADD COLUMN price_changed_at   timestamptz,
    ADD COLUMN price_changed_by   bigint REFERENCES ${tenant.schema}.tenant_member,
    ADD COLUMN legacy_code        text,
    ADD COLUMN legacy_data        jsonb;

ALTER TABLE ${tenant.schema}.part
    ADD CONSTRAINT part_side_lr_ck CHECK (side_lr IS NULL OR side_lr IN ('LEFT','RIGHT')),
    ADD CONSTRAINT part_side_fr_ck CHECK (side_fr IS NULL OR side_fr IN ('FRONT','REAR')),
    ADD CONSTRAINT part_side_ud_ck CHECK (side_ud IS NULL OR side_ud IN ('UPPER','LOWER'));

CREATE INDEX part_supply_ix ON ${tenant.schema}.part (supply_id) WHERE supply_id IS NOT NULL;
CREATE INDEX part_name_ref_ix ON ${tenant.schema}.part (part_name_id) WHERE part_name_id IS NOT NULL;

-- Естественный ключ переноса: по нему повторный запуск импорта узнаёт уже
-- загруженное и не плодит дубли. Частичный, потому что у позиций, заведённых
-- уже у нас, кода в прежней системе нет и быть не должно.
CREATE UNIQUE INDEX part_legacy_code_uk ON ${tenant.schema}.part (legacy_code)
    WHERE legacy_code IS NOT NULL;
--rollback ALTER TABLE ${tenant.schema}.part DROP CONSTRAINT part_side_ud_ck, DROP CONSTRAINT part_side_fr_ck, DROP CONSTRAINT part_side_lr_ck;
--rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN legacy_data, DROP COLUMN legacy_code, DROP COLUMN price_changed_by, DROP COLUMN price_changed_at, DROP COLUMN package_weight_kg, DROP COLUMN package_height_mm, DROP COLUMN package_width_mm, DROP COLUMN package_length_mm, DROP COLUMN height_mm, DROP COLUMN width_mm, DROP COLUMN length_mm, DROP COLUMN note, DROP COLUMN installation_price, DROP COLUMN section, DROP COLUMN color, DROP COLUMN manufacturer, DROP COLUMN side_ud, DROP COLUMN side_fr, DROP COLUMN side_lr, DROP COLUMN part_name_id, DROP COLUMN supply_id;

--changeset platform:tenant-098-part-side-drop
--comment Свободное текстовое `side` заменено тремя осями. Данных на этот
--comment момент нет — проект не запущен, — поэтому перенос не нужен.
ALTER TABLE ${tenant.schema}.part DROP COLUMN side;
--rollback ALTER TABLE ${tenant.schema}.part ADD COLUMN side text;

--changeset platform:tenant-099-part-quality-grade
--comment Оценка состояния уходит на площадки, поэтому свободный текст здесь
--comment недопустим: значения должны маппиться один в один. Четыре градации —
--comment ровно те, что понимает продавец на приёмке.
ALTER TABLE ${tenant.schema}.part
    ADD CONSTRAINT part_quality_grade_ck CHECK (quality_grade IS NULL OR quality_grade IN
        ('AS_NEW','NO_DEFECTS','WITH_DEFECTS','NEEDS_REPAIR'));
--rollback ALTER TABLE ${tenant.schema}.part DROP CONSTRAINT part_quality_grade_ck;
