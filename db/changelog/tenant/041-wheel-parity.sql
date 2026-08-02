--liquibase formatted sql

--changeset platform:tenant-041-wheel-parity
--comment Свойства шины, которых не хватало для паритета с вкладкой
--comment «Шины и диски» прежней системы.
--comment
--comment Сверено с живым кабинетом клиента (221 товар): из сорока пяти его
--comment колонок шесть не имели у нас поля вовсе, и все шесть — про шину.
--comment
--comment marking_type — метрическая («195/65 R15»), дюймовая («31x10.5 R15»)
--comment или флотационная. Это не украшение: по дюймовой маркировке шину
--comment ищут другими числами, и «31» в колонке ширины означало бы шину
--comment шириной 31 миллиметр.
--comment
--comment tread_type — стандартный, асимметричный или направленный рисунок.
--comment У направленной шины есть сторона вращения, у асимметричной —
--comment внешняя сторона: поставленная наоборот, она хуже держит воду,
--comment и покупатель об этом спрашивает.
--comment
--comment run_flat — шина, на которой можно доехать спущенной. Дороже обычной
--comment вдвое, и продавать её как обычную значит терять деньги.
--comment
--comment light_truck — усиленная шина для лёгких грузовиков. От легковой
--comment того же размера отличается нагрузкой, и поставленная на легковушку
--comment даёт жёсткую подвеску, а на «Газель» легковая — разрыв.
--comment
--comment speed_index и load_index — буква и число с боковины. Покупатель
--comment называет их, когда подбирает по документам на машину.
ALTER TABLE ${tenant.schema}.part_wheel
    ADD COLUMN marking_type text,
    ADD COLUMN tread_type   text,
    ADD COLUMN run_flat     boolean,
    ADD COLUMN light_truck  boolean,
    ADD COLUMN speed_index  text,
    ADD COLUMN load_index   integer,
    ADD CONSTRAINT part_wheel_marking_ck CHECK (marking_type IS NULL OR marking_type IN
        ('METRIC', 'INCH', 'FLOTATION')),
    ADD CONSTRAINT part_wheel_tread_ck CHECK (tread_type IS NULL OR tread_type IN
        ('STANDARD', 'ASYMMETRIC', 'DIRECTIONAL')),
    -- Индекс нагрузки — это код по таблице, а не килограммы: 91 означает
    -- 615 кг. Диапазон таблицы и стережём.
    ADD CONSTRAINT part_wheel_load_ck CHECK (load_index IS NULL OR
        (load_index BETWEEN 20 AND 279));
--rollback ALTER TABLE ${tenant.schema}.part_wheel DROP COLUMN marking_type, DROP COLUMN tread_type, DROP COLUMN run_flat, DROP COLUMN light_truck, DROP COLUMN speed_index, DROP COLUMN load_index;
