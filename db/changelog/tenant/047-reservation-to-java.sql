--liquibase formatted sql

--changeset platform:tenant-047-drop-reserved-guard
--comment Страж резерва снят (docs/triggers-to-java.md, пункт 6).
--comment
--comment Оказалось, что переносить его некуда: ограничение
--comment part_stock_reserved_ck CHECK (qty_reserved <= qty) стоит в схеме
--comment с самого начала, в том же файле, где заведена таблица. Триггер
--comment проверял ровно то же самое и существовал ради внятного текста
--comment ошибки — «при выдаче резерв снимают до списания, а не после».
--comment
--comment Но текст этот никто никогда не читал. Наружу приложение отдаёт
--comment сообщение только для SQLSTATE P0001 — это наши RAISE, написанные
--comment по-русски, — а здесь стоит USING ERRCODE = 'check_violation',
--comment то есть 23514. Такое нарушение уезжает клиенту общей формулировкой,
--comment ровно как и нарушение самого CHECK. Разработчику же имя ограничения
--comment говорит не меньше, чем текст.
--comment
--comment Итого: две проверки одного и того же, одна из них невидима.
DROP TRIGGER IF EXISTS part_stock_reserved_guard ON ${tenant.schema}.part_stock;
DROP FUNCTION IF EXISTS ${tenant.schema}.part_stock_check_reserved();
--rollback SELECT 1;

--changeset platform:tenant-047-drop-reserve-functions
--comment Резерв переехал в Java (docs/triggers-to-java.md, пункт 2):
--comment StockReservationRepository делает то же самое одним UPDATE
--comment с условием в WHERE и смотрит на число затронутых строк.
--comment
--comment Свойство, ради которого функции и заводились, сохранено дословно:
--comment проверка и изменение — одна инструкция. Два продавца, кладущие
--comment последнюю деталь в свои сделки, по-прежнему сериализуются Postgres
--comment на одной строке, и второму не достанется ни одной изменённой.
--comment Наивный перенос — прочитать свободный остаток, вычесть в Java,
--comment записать обратно — дал бы деталь, проданную дважды, и узнали бы
--comment об этом от клиента на пороге склада. Стережёт это тест на двух
--comment потоках, он был написан раньше переноса.
--comment
--comment Что перенос дал: отказ теперь различим. Прежний код заворачивал
--comment в «недостаточно свободного остатка» любое исключение вызова,
--comment включая обрыв соединения; теперь нехватка — это ноль изменённых
--comment строк, а всё остальное летит как есть.
DROP FUNCTION IF EXISTS ${tenant.schema}.reserve_stock(bigint, bigint, numeric);
DROP FUNCTION IF EXISTS ${tenant.schema}.release_stock(bigint, bigint, numeric);
--rollback SELECT 1;
