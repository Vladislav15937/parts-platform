--liquibase formatted sql

--changeset platform:tenant-046-drop-feed-dirty-triggers
--comment Отметку об изменении позиции ставит теперь приложение
--comment (docs/triggers-to-java.md, пункт 4). Триггеры сняты.
--comment
--comment Чем платим, названо заранее: правка прямым SQL мимо приложения
--comment в очередь больше не попадёт. Таких правок две — перенос из прежней
--comment системы и доводка справочника после него, — и обе массовые: тридцать
--comment пять тысяч отметок означали бы семьдесят пачек дельт подряд на
--comment площадку сразу после переезда. Их состояние она и так узнает полным
--comment прайсом, который собирается в момент запроса. То есть в этом месте
--comment перенос не только не потерял, но и убрал вредное.
DROP TRIGGER IF EXISTS part_feed_dirty ON ${tenant.schema}.part;
DROP TRIGGER IF EXISTS part_stock_feed_dirty ON ${tenant.schema}.part_stock;
DROP TRIGGER IF EXISTS part_wheel_feed_dirty ON ${tenant.schema}.part_wheel;
DROP TRIGGER IF EXISTS part_oem_feed_dirty ON ${tenant.schema}.part_oem;
DROP TRIGGER IF EXISTS part_photo_feed_dirty ON ${tenant.schema}.part_photo;
DROP FUNCTION IF EXISTS ${tenant.schema}.feed_mark_dirty();
--rollback SELECT 1;

--changeset platform:tenant-046-rename-feed-dirty
--comment Очередь переезжает из publishing в inventory и вместе с этим меняет
--comment имя. «feed_dirty» — понятие выгрузки, а класть в него отметку теперь
--comment должны приёмка, склад и продажи: модуль, пишущий в таблицу с именем
--comment чужого модуля, — это зависимость, о которой узнают поздно.
--comment «part_change» описывает факт, а не то, кому он нужен; сегодня
--comment единственный читатель — выгрузки, завтра их может быть двое.
ALTER TABLE ${tenant.schema}.feed_dirty RENAME TO part_change;
ALTER TABLE ${tenant.schema}.part_change RENAME CONSTRAINT feed_dirty_pk TO part_change_pk;
ALTER TABLE ${tenant.schema}.part_change RENAME CONSTRAINT feed_dirty_part_fk TO part_change_part_fk;
ALTER INDEX ${tenant.schema}.feed_dirty_pending_ix RENAME TO part_change_pending_ix;
--rollback ALTER TABLE ${tenant.schema}.part_change RENAME TO feed_dirty;
