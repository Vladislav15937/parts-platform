--liquibase formatted sql

--changeset platform:tenant-044-feed-dirty
--comment Очередь «эта позиция изменилась, площадку надо обновить».
--comment
--comment До этого дельта уходила на Дром ровно в одном случае — при выдаче
--comment сделки. Всё остальное площадка узнавала из полного прайса, который
--comment она забирает раз в сутки: подорожавшая деталь, снятая с публикации,
--comment списанная по недостаче, только что принятая, поправленная списком —
--comment всё это висело на сайте в прежнем виде до следующего забора.
--comment
--comment Отметку ставит триггер, а не приложение, и это главное решение здесь.
--comment Половину изменений позиции делает сама база: остаток и статус ведёт
--comment stock_movement_apply, раскладку по складам — он же, резерв меняют
--comment reserve_stock и release_stock. Приложение о них не знает и знать
--comment не должно. Отметка из кода означала бы, что каждый новый путь
--comment изменения надо не забыть подписать — а забывают всегда, и заметно
--comment это становится через сутки на чужом сайте. По той же причине сюда
--comment попадает и прямой SQL: перенос, доводка справочника, починка руками.
--comment
--comment Ключ — сама позиция, а не запись на каждое изменение: площадке нужно
--comment текущее состояние, а не история. Сто правок одной детали за минуту
--comment дают одну строку и одну дельту.
CREATE TABLE ${tenant.schema}.feed_dirty
(
    part_id    bigint      NOT NULL,
    marked_at  timestamptz NOT NULL DEFAULT now(),
    -- Заявка со сроком, как у outbox: отправка уезжает из транзакции, и
    -- процесс, умерший между отправкой и уборкой, не должен запереть позицию.
    claimed_at timestamptz,
    CONSTRAINT feed_dirty_pk PRIMARY KEY (part_id),
    CONSTRAINT feed_dirty_part_fk FOREIGN KEY (part_id)
        REFERENCES ${tenant.schema}.part (id) ON DELETE CASCADE
);
--rollback DROP TABLE ${tenant.schema}.feed_dirty;

--changeset platform:tenant-044-feed-dirty-fn splitStatements:false runOnChange:true
--comment Одна функция на все таблицы: у части из них позиция лежит в part_id,
--comment у самой part — в id. Имена квалифицированы, потому что тело
--comment разрешается в рантайме, когда search_path может быть чужим.
CREATE OR REPLACE FUNCTION ${tenant.schema}.feed_mark_dirty()
    RETURNS trigger LANGUAGE plpgsql AS $fn$
DECLARE
    target bigint;
BEGIN
    IF TG_TABLE_NAME = 'part' THEN
        target := COALESCE(NEW.id, OLD.id);
    ELSE
        target := COALESCE(NEW.part_id, OLD.part_id);
    END IF;

    IF target IS NULL THEN
        RETURN NULL;
    END IF;

    -- Удалённую позицию отмечать нечем и незачем: внешний ключ каскадом
    -- уберёт и отметку, а объявление снимет полный прайс.
    IF TG_OP = 'DELETE' AND TG_TABLE_NAME = 'part' THEN
        RETURN NULL;
    END IF;

    INSERT INTO ${tenant.schema}.feed_dirty (part_id)
    VALUES (target)
    ON CONFLICT (part_id) DO UPDATE
        -- Отметку освежаем и заявку снимаем: изменение, случившееся после
        -- того, как пачку забрали на отправку, обязано уехать следующей.
        SET marked_at = now(), claimed_at = NULL;

    RETURN NULL;
END;
$fn$;
--rollback DROP FUNCTION IF EXISTS ${tenant.schema}.feed_mark_dirty();

--changeset platform:tenant-044-feed-dirty-triggers splitStatements:false runOnChange:true
--comment Всё, из чего собран offer прайса: сама позиция, её остаток
--comment по складам, свойства колеса, номера и снимки. Триггеры AFTER
--comment и без условий на колонки: разбирать, какое поле влияет на выгрузку,
--comment значит завести второй список полей рядом с генератором прайса —
--comment и разойтись с ним на первой же новой колонке. Лишняя дельта стоит
--comment одного запроса, пропущенная — суток чужого объявления.
DROP TRIGGER IF EXISTS part_feed_dirty ON ${tenant.schema}.part;
CREATE TRIGGER part_feed_dirty
    AFTER INSERT OR UPDATE ON ${tenant.schema}.part
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.feed_mark_dirty();

DROP TRIGGER IF EXISTS part_stock_feed_dirty ON ${tenant.schema}.part_stock;
CREATE TRIGGER part_stock_feed_dirty
    AFTER INSERT OR UPDATE OR DELETE ON ${tenant.schema}.part_stock
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.feed_mark_dirty();

DROP TRIGGER IF EXISTS part_wheel_feed_dirty ON ${tenant.schema}.part_wheel;
CREATE TRIGGER part_wheel_feed_dirty
    AFTER INSERT OR UPDATE OR DELETE ON ${tenant.schema}.part_wheel
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.feed_mark_dirty();

DROP TRIGGER IF EXISTS part_oem_feed_dirty ON ${tenant.schema}.part_oem;
CREATE TRIGGER part_oem_feed_dirty
    AFTER INSERT OR UPDATE OR DELETE ON ${tenant.schema}.part_oem
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.feed_mark_dirty();

DROP TRIGGER IF EXISTS part_photo_feed_dirty ON ${tenant.schema}.part_photo;
CREATE TRIGGER part_photo_feed_dirty
    AFTER INSERT OR UPDATE OR DELETE ON ${tenant.schema}.part_photo
    FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.feed_mark_dirty();
--rollback DROP TRIGGER IF EXISTS part_feed_dirty ON ${tenant.schema}.part;
--rollback DROP TRIGGER IF EXISTS part_stock_feed_dirty ON ${tenant.schema}.part_stock;
--rollback DROP TRIGGER IF EXISTS part_wheel_feed_dirty ON ${tenant.schema}.part_wheel;
--rollback DROP TRIGGER IF EXISTS part_oem_feed_dirty ON ${tenant.schema}.part_oem;
--rollback DROP TRIGGER IF EXISTS part_photo_feed_dirty ON ${tenant.schema}.part_photo;

--changeset platform:tenant-044-feed-dirty-ix
--comment Забирают по возрасту отметки и по свободной заявке.
CREATE INDEX feed_dirty_pending_ix ON ${tenant.schema}.feed_dirty (marked_at)
    WHERE claimed_at IS NULL;
--rollback DROP INDEX IF EXISTS ${tenant.schema}.feed_dirty_pending_ix;
