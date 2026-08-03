--liquibase formatted sql

--changeset platform:catalog-017-part-kind-search splitStatements:false
--comment Последняя генерируемая колонка общей схемы — вектор поиска
--comment по справочнику видов деталей. Убрана вместе с индексом и без
--comment замены: её не читает никто.
--comment
--comment Полнотекстового поиска по видам деталей в приложении нет вовсе —
--comment написание сводится к эталону точным совпадением с именем или
--comment синонимом, а похожее показывается подсказками по триграммам
--comment (part_kind_name_trgm, он остаётся). То есть колонка и её GIN-индекс
--comment три года считались и обновлялись впустую.
--comment
--comment Вместе с ней уходит catalog.join_text — обёртка над array_to_string,
--comment заведённая ради того, чтобы выражение колонки было IMMUTABLE.
--comment Попытка заменить колонку индексом на том же выражении это и вскрыла:
--comment array_to_string у Postgres STABLE, и в индекс его не пустят.
DROP INDEX IF EXISTS catalog.part_kind_search_gin;
ALTER TABLE catalog.part_kind DROP COLUMN IF EXISTS search_vector;
DROP FUNCTION IF EXISTS catalog.join_text(text[], text);
--rollback SELECT 1;

--changeset platform:catalog-017-normalize-oem-comment splitStatements:false
--comment catalog.normalize_oem остаётся, и это не недоделка.
--comment
--comment Приведение номера производителя переехало в Java
--comment (`catalog.OemNumbers.normalize`); из работающего кода функцию
--comment не зовёт никто, а tenant/051 снимает с колонки выражение.
--comment
--comment Но удалить её нельзя: changeset tenant/004 неизменяем, и при
--comment заведении нового арендатора он проигрывается заново — вместе
--comment со строкой `GENERATED ALWAYS AS (catalog.normalize_oem(raw_number))`.
--comment Без функции провижининг падает на первом же клиенте. Проверено:
--comment ровно так и упало.
--comment
--comment То есть это не логика в базе, а след истории миграций: она нужна
--comment одному changeset'у из пятидесяти одного и живёт ровно до того
--comment мгновения, пока tenant/051 не снимет выражение. Уходит она вместе
--comment со схлопыванием changelog'а, когда клиентов станет столько,
--comment что проигрывать полсотни шагов на каждого будет дорого.
COMMENT ON FUNCTION catalog.normalize_oem(text) IS
    'След истории миграций: нужна только для проигрывания tenant/004 при заведении арендатора. Приведение номера живёт в ru.partsflow.catalog.OemNumbers.';
--rollback SELECT 1;
