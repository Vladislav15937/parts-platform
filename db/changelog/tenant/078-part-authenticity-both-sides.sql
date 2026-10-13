--liquibase formatted sql

--changeset partsflow:tenant-078-part-authenticity-both-sides
--comment Оригинальность детали словом Дрома и сторона «Левая, Правая» (BOTH) у позиции — задача 0277
--
-- Файл переноса с Дрома (docs/drom-transfer-file.md) несёт два поля карточки,
-- которых в схеме не было, и задача 0277 их переносит — требование владельца
-- продукта от 13.10.2026 повторить объявление клиента «со всеми полями что есть».
--
-- ОРИГИНАЛЬНОСТЬ — текст дословно, а не перечисление. На карточке Дрома
-- (поле autoPartsAuthenticity) видели одно значение — «Оригинал»; каким
-- словом Дром пишет иное, не видел никто, и перечень, придуманный здесь,
-- либо отбил бы настоящее слово клиента, либо подменил его своим. Ближайшая
-- колонка quality_grade — про износ («как новая», «с дефектами»), это
-- другой вопрос. Пусто — NULL: CHECK не пускает строку из одних пробелов,
-- иначе «не заполнено» стало бы двумя разными значениями.
--
-- СТОРОНА «ОБЕ» — третье значение поперечной оси, а не вторая колонка.
-- У клиента амортизатор № 135618913 «цена за пару» стоит «Левая, Правая»;
-- одним значением LEFT|RIGHT это не записать, а две независимые отметки
-- («подходит слева», «подходит справа») дали бы и четвёртое состояние «ни
-- одна», уже выраженное NULL. Перечень расширяется — это expand: прежний
-- код BOTH не пишет, а прочитать его ему негде, кроме прайса и витрины
-- той же версии.
ALTER TABLE ${tenant.schema}.part
    ADD COLUMN authenticity text,
    ADD CONSTRAINT part_authenticity_ck CHECK (btrim(authenticity) <> '');

COMMENT ON COLUMN ${tenant.schema}.part.authenticity IS
    'Оригинальность детали словом площадки, дословно («Оригинал»). Приезжает файлом переноса с Дрома; NULL — не указана. Задача 0277';

ALTER TABLE ${tenant.schema}.part
    DROP CONSTRAINT part_side_lr_ck;

ALTER TABLE ${tenant.schema}.part
    ADD CONSTRAINT part_side_lr_ck CHECK (side_lr IS NULL OR side_lr IN ('LEFT','RIGHT','BOTH'));

-- ОТКАТ СУЖАЕТ ПЕРЕЧЕНЬ СТОРОН, ПОЭТОМУ СНАЧАЛА УВОДИТ ДАННЫЕ. Позиция
-- с BOTH уходит в NULL, а не в LEFT или RIGHT: любая одна сторона была бы
-- неправдой о детали, которая подходит на обе, а NULL — «сторона не указана»,
-- то самое, что было у неё до переноса этой колонки. На чистой базе
-- (db/verify.sh, шаг 7) строк нет, и без UPDATE разворот прошёл бы — а у
-- клиента с переехавшей парой амортизаторов встал бы на «check constraint
-- is violated by some row» (та же порода, что у tenant/062 и tenant/070).
--rollback-теряет оригинальность у всех позиций (слово «Оригинал» с карточки Дрома не восстановить, кроме повторной загрузкой файла переноса, а повтор пропустит уже заведённые объявления) и сторону «Левая, Правая»: такие позиции остаются без поперечной стороны, в прайсе у них пропадает lr, заголовки не пересобираются
--rollback UPDATE ${tenant.schema}.part SET side_lr = NULL WHERE side_lr = 'BOTH';
--rollback ALTER TABLE ${tenant.schema}.part DROP CONSTRAINT part_side_lr_ck;
--rollback ALTER TABLE ${tenant.schema}.part ADD CONSTRAINT part_side_lr_ck CHECK (side_lr IS NULL OR side_lr IN ('LEFT','RIGHT'));
--rollback ALTER TABLE ${tenant.schema}.part DROP CONSTRAINT part_authenticity_ck;
--rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN authenticity;
