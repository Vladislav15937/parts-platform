--liquibase formatted sql

--changeset platform:tenant-048-drop-stock-apply
--comment Остаток и статус ведёт Java (docs/triggers-to-java.md, пункт 1) —
--comment последний и самый крупный шаг правила «логика в приложении».
--comment
--comment Тело перенесено в StockLedger дословно: списание со склада-источника,
--comment приход на склад-приёмник, пересчёт карточки по раскладке. Порядок
--comment шагов сохранён — перемещение между складами это одно движение
--comment с обоими складами, и, поменяв их местами, мы на мгновение удвоили бы
--comment остаток. Условия статуса сохранены тоже, включая то, что
--comment корректировка инвентаризации в плюс статус не трогает: она не
--comment говорит, куда делась деталь.
--comment
--comment Чем платим: триггер нельзя было обойти, а теперь применяет вызвавший.
--comment Путь мимо StockLedger оставит журнал и кэш разошедшимися. Сторожей
--comment три: писателей движений немного и они перечислены в документе,
--comment сверка v_stock_discrepancy сравнивает кэш с журналом и обязана быть
--comment пустой, и StockLedgerTest проверяет каждый вид движения.
--comment
--comment Массовые операции применяются пачкой (recomputeAll): перенос пишет
--comment тридцать пять тысяч движений своим соединением, и применять их
--comment по одному значило бы столько же обращений к базе.
DROP TRIGGER IF EXISTS stock_movement_apply_trg ON ${tenant.schema}.stock_movement;
DROP FUNCTION IF EXISTS ${tenant.schema}.stock_movement_apply();
--rollback SELECT 1;
