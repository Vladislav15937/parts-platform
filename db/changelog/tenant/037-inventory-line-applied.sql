--liquibase formatted sql

--changeset platform:tenant-037-inventory-line-applied
--comment Отметка проведения на строке пересчёта, а не только на сессии.
--comment
--comment Проведение перестаёт быть «всё или ничего». Недостачу по детали,
--comment обещанной покупателю, списать нельзя — остаток уйдёт ниже резерва, —
--comment и раньше из-за одной такой строки не проводилась вся инвентаризация:
--comment сорок посчитанных полок ждали, пока продавец поговорит с покупателем
--comment и снимет резерв. А кладовщик, который считал, снять его не может
--comment по роли.
--comment
--comment Со строчной отметкой проводится всё, что можно, застрявшие остаются
--comment на месте, и повтор после снятия резерва дописывает только их:
--comment проведённая строка второй корректировки не породит. Сессия
--comment закрывается, когда не осталось ни одной непроведённой.
ALTER TABLE ${tenant.schema}.inventory_line
    ADD COLUMN applied_at timestamptz;

--comment Уже проведённые сессии: их строки проведены по определению,
--comment и без отметки повторное проведение списало бы всё второй раз.
UPDATE ${tenant.schema}.inventory_line l
   SET applied_at = s.applied_at
  FROM ${tenant.schema}.inventory_session s
 WHERE s.id = l.session_id
   AND s.status = 'APPLIED';
--rollback ALTER TABLE ${tenant.schema}.inventory_line DROP COLUMN applied_at;
