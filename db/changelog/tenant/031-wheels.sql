--liquibase formatted sql

-- Шины и диски. У Bazon это отдельная вкладка «Товары и услуги», но не
-- отдельная сущность: товар с типом «Шина» или «Диск», своим набором свойств
-- и номером комплекта — четыре шины под одним номером.

--changeset platform:tenant-300-product-line
--comment Товарная линия у карточки: запчасть или колесо. Отдельной таблицей
--comment товаров это делать нельзя — склад, резерв, продажа, возврат
--comment и инвентаризация написаны на part, и второй вид товара со своим
--comment путём через всё это разошёлся бы с первым на первой же правке.
--comment
--comment Умолчание PART: у переехавшего клиента весь склад — запчасти,
--comment и колонка, требующая заполнения, остановила бы импорт.
ALTER TABLE ${tenant.schema}.part
    ADD COLUMN product_line text NOT NULL DEFAULT 'PART';

ALTER TABLE ${tenant.schema}.part ADD CONSTRAINT part_product_line_ck
    CHECK (product_line IN ('PART', 'WHEEL'));

-- Витрина запчастей и витрина колёс — разные экраны и разные выгрузки,
-- и оба ходят по этому полю.
CREATE INDEX part_product_line_ix ON ${tenant.schema}.part (product_line)
    WHERE product_line <> 'PART';
--rollback DROP INDEX ${tenant.schema}.part_product_line_ix;
--rollback ALTER TABLE ${tenant.schema}.part DROP CONSTRAINT part_product_line_ck;
--rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN product_line;

--changeset platform:tenant-301-part-wheel
--comment Свойства колеса — отдельной таблицей, а не колонками в part:
--comment семнадцать полей, пустых у пятидесяти тысяч обычных деталей, —
--comment это шум в каждом запросе к складу и в каждом ALTER.
--comment
--comment Шина и диск живут в одной таблице с общим типом: у них общие диаметр,
--comment номер комплекта и производитель, а различаются наборы полей. Две
--comment таблицы означали бы два пути через приёмку, витрину и выгрузку ради
--comment товара, который клиент продаёт одним комплектом — диски в шинах.
CREATE TABLE ${tenant.schema}.part_wheel (
    part_id       bigint PRIMARY KEY REFERENCES ${tenant.schema}.part ON DELETE CASCADE,
    kind          text        NOT NULL,
    -- Номер комплекта: четыре шины под одним номером. Продают их поштучно,
    -- но заводят и показывают комплектом — цена в объявлении за комплект.
    set_no        integer,
    diameter      numeric(4,1),

    -- Шина
    tyre_width    integer,
    tyre_height   integer,
    construction  text,
    tyre_type     text,
    season        text,
    wear_mm       numeric(4,1),
    made_year     integer,

    -- Диск
    disc_type     text,
    disc_width    numeric(4,1),
    offset_mm     integer,
    bolt_pattern  text,
    hub_bore      numeric(5,1),

    brand         text,
    model         text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT part_wheel_kind_ck CHECK (kind IN ('TYRE', 'DISC')),
    CONSTRAINT part_wheel_season_ck CHECK (season IS NULL OR season IN
        ('SUMMER', 'WINTER', 'ALL_SEASON')),
    -- Износ в миллиметрах остатка протектора, а не в процентах: у новой шины
    -- 8 мм, у изношенной 2, и «осталось 25 %» покупатель пересчитывать
    -- не станет — он мерил глубиномером.
    CONSTRAINT part_wheel_wear_ck CHECK (wear_mm IS NULL OR wear_mm >= 0)
);

CREATE INDEX part_wheel_set_ix ON ${tenant.schema}.part_wheel (set_no)
    WHERE set_no IS NOT NULL;

-- Номер комплекта берётся последовательностью, а не «максимальный плюс один»:
-- второй приёмщик, заводящий комплект в ту же секунду, получил бы тот же
-- номер, и четыре шины двух разных комплектов слиплись бы в один.
CREATE SEQUENCE ${tenant.schema}.wheel_set_no_seq START 1;
--rollback DROP SEQUENCE ${tenant.schema}.wheel_set_no_seq;
--rollback DROP TABLE ${tenant.schema}.part_wheel;
