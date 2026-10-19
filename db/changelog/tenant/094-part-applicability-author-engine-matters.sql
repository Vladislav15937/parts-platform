--liquibase formatted sql

--changeset partsflow:tenant-094-part-applicability-author-engine-matters
--comment Применимость: кто и когда её задал (part_applicability.set_by, set_at) и признак «Двигатель важен» у позиции (part.engine_matters), задача 0363

-- Решение владельца продукта 19.10.2026 (tasks/0363, «Всё как у bazon»): применимость
-- ставится сама при заведении позиции из донора, у неё есть автор и момент записи, а у
-- позиции — признак «Двигатель важен / не важен». Это ОДНО состояние с «любым двигателем»
-- из задачи 0343: второго заводить нельзя, колонка принадлежит позиции, а не строке
-- применимости — у Bazon «Двигатель не важен» стоит над всем списком машин позиции.
--
-- ЧТО ЗДЕСЬ ТОЛЬКО ФОРМА ДАННЫХ. Умолчание признака — false («не важен»): список видов
-- деталей, где «важен» ставится сам (ДВС, КПП, тросик газа), у Bazon снаружи не виден и
-- отдан обходу (tasks/0363, «Под обходом»). Логики выбора по виду в схеме нет и не будет.
--
-- set_by / set_at ПУСТЫ У УЖЕ ЛЕЖАЩИХ СТРОК, и это намеренно. Проставить им now() значило бы
-- объявить, что всю применимость склада задали в день наката, — вопрос «кто и когда» получил
-- бы уверенный неверный ответ. Пусто читается как «неизвестно» (так у переехавших и у разбора
-- наименований), умолчание now() включается отдельным шагом ниже и действует только на новые
-- вставки. Прежний образ приложения set_by не пишет, set_at получит от умолчания — это верно:
-- момент записи строки им и является, автор остаётся пустым.
ALTER TABLE ${tenant.schema}.part_applicability
    ADD COLUMN set_by bigint REFERENCES ${tenant.schema}.tenant_member,
    ADD COLUMN set_at timestamptz;

ALTER TABLE ${tenant.schema}.part_applicability
    ALTER COLUMN set_at SET DEFAULT now();

COMMENT ON COLUMN ${tenant.schema}.part_applicability.set_by IS
    'Кто установил применимость: сотрудник, чьё действие записало строку (для автозаполнения при заведении из донора — автор приёмки). NULL — неизвестно: строка лежала до задачи 0363, перенесена или разобрана без вошедшего';
COMMENT ON COLUMN ${tenant.schema}.part_applicability.set_at IS
    'Когда установлена применимость (момент записи строки). NULL — неизвестно: строка лежала до задачи 0363';

ALTER TABLE ${tenant.schema}.part
    ADD COLUMN engine_matters boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN ${tenant.schema}.part.engine_matters IS
    'Двигатель важен для применимости позиции (как у Bazon: «Двигатель важен» / «не важен»). false — не важен: умолчание, список видов деталей, где важен сам по себе, отдан обходу (задача 0363)';

--rollback-теряет авторов и время установки применимости и признак «Двигатель важен» у позиций: после отката применимость остаётся, но без «кто и когда», а признак возвращается к «не важен»
--rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN engine_matters;
--rollback ALTER TABLE ${tenant.schema}.part_applicability DROP COLUMN set_at, DROP COLUMN set_by;
