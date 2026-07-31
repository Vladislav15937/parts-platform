#!/usr/bin/env bash
# Полная проверка миграций на чистой базе.
#   ./verify.sh
#
# Что проверяется:
#   1. Каталог накатывается на пустую базу
#   2. Два арендатора провижинятся независимо
#   3. DATABASECHANGELOG у каждого арендатора — внутри его схемы
#   4. Изоляция: данные арендаторов не пересекаются
#   5. Триггеры работают (остаток, аудит, неизменяемость журнала)
#   6. rollback отрабатывает
#   7. Повторный update идемпотентен

set -euo pipefail
cd "$(dirname "$0")"

LB="docker compose exec -T liquibase liquibase"
PSQL="docker compose exec -T postgres psql -U app -d parts -v ON_ERROR_STOP=1"
URL="jdbc:postgresql://postgres:5432/parts"
CRED="--username=app --password=app --url=$URL"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    OK: %s\033[0m\n' "$1"; }
fail() { printf '\033[1;31m    FAIL: %s\033[0m\n' "$1"; exit 1; }

step "Поднимаем чистую базу"
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
until docker compose exec -T postgres pg_isready -U app -d parts >/dev/null 2>&1; do sleep 1; done
ok "postgres готов"

step "1. Каталог"
$LB --changelog-file=changelog/db.changelog-catalog.xml $CRED \
    --liquibase-schema-name=public update
ok "каталог накатан"

step "2. Арендаторы t_000042 и t_000043"
for T in t_000042 t_000043; do
    # Схему создаёт провижининг, а не миграции: DATABASECHANGELOG лежит внутри
    # неё, и Liquibase создаёт его до того, как выполнит первый changeset.
    $PSQL -c "CREATE SCHEMA IF NOT EXISTS $T;" >/dev/null
    # -D идёт после имени команды: Liquibase 4.x не принимает его среди
    # глобальных аргументов.
    $LB --changelog-file=changelog/db.changelog-tenant.xml $CRED \
        --default-schema-name=$T --liquibase-schema-name=$T update -Dtenant.schema=$T
    ok "$T провижинен"
done

step "3. DATABASECHANGELOG внутри схемы арендатора"
CNT=$($PSQL -tAc "SELECT count(*) FROM information_schema.tables
                  WHERE table_schema='t_000042' AND table_name='databasechangelog';")
[ "$CNT" = "1" ] || fail "changelog арендатора не в его схеме (найдено: $CNT)"
ok "версионирование независимое"

step "4. Изоляция данных"
$PSQL <<'SQL'
INSERT INTO t_000042.branch (name) VALUES ('Филиал 42');
INSERT INTO t_000043.branch (name) VALUES ('Филиал 43');
SQL
A=$($PSQL -tAc "SELECT count(*) FROM t_000042.branch WHERE name='Филиал 43';")
[ "$A" = "0" ] || fail "данные арендаторов пересекаются"
ok "схемы изолированы"

step "5. Триггеры: остаток, аудит, неизменяемость журнала"
$PSQL <<'SQL'
INSERT INTO t_000042.warehouse (branch_id, name)
    SELECT id, 'Основной' FROM t_000042.branch LIMIT 1;
INSERT INTO t_000042.warehouse (branch_id, name)
    SELECT id, 'Второй'   FROM t_000042.branch LIMIT 1;
INSERT INTO t_000042.storage_cell (warehouse_id, code)
    SELECT id, 'А-01-1' FROM t_000042.warehouse WHERE name='Основной';
INSERT INTO t_000042.supply (kind, number, supplier_name, arrived_on, status)
    VALUES ('CONTAINER', '17', 'Onteco 6', current_date, 'ARRIVED');
INSERT INTO t_000042.part (category_id, title, price, status, supply_id)
    SELECT 1, 'Фара левая Camry V50', 8500, 'IN_STOCK', id FROM t_000042.supply LIMIT 1;
INSERT INTO t_000042.stock_movement (part_id, movement_type, qty_delta, to_warehouse_id, to_cell_id)
    SELECT p.id, 'INTAKE', 1, c.warehouse_id, c.id
    FROM t_000042.part p, t_000042.storage_cell c LIMIT 1;
SQL

QTY=$($PSQL -tAc "SELECT qty_on_hand FROM t_000042.part LIMIT 1;")
[ "${QTY%.*}" = "1" ] || fail "остаток не обновился триггером (получено: $QTY)"
ok "остаток пересчитан"

WQ=$($PSQL -tAc "SELECT qty FROM t_000042.part_stock
                 JOIN t_000042.warehouse w ON w.id = warehouse_id WHERE w.name='Основной';")
[ "${WQ%.*}" = "1" ] || fail "остаток по складу не разложился (получено: $WQ)"
ok "остаток разложен по складам"

AUD=$($PSQL -tAc "SELECT count(*) FROM t_000042.audit_log WHERE table_name='part';")
[ "$AUD" -ge 1 ] || fail "аудит не записался"
ok "аудит пишется"

if $PSQL -c "UPDATE t_000042.stock_movement SET qty_delta = 99;" >/dev/null 2>&1; then
    fail "журнал движений оказался изменяемым"
fi
ok "журнал движений неизменяем"

DISC=$($PSQL -tAc "SELECT count(*) FROM t_000042.v_stock_discrepancy;")
[ "$DISC" = "0" ] || fail "расхождение остатка с журналом: $DISC"
ok "сверка остатка чистая"

step "5a. Перемещение между складами не меняет общий остаток"
$PSQL <<'SQL'
INSERT INTO t_000042.stock_movement (part_id, movement_type, qty_delta,
                                     from_warehouse_id, to_warehouse_id)
    SELECT p.id, 'MOVE', 1,
           (SELECT id FROM t_000042.warehouse WHERE name='Основной'),
           (SELECT id FROM t_000042.warehouse WHERE name='Второй')
    FROM t_000042.part p LIMIT 1;
SQL

TOTAL=$($PSQL -tAc "SELECT qty_on_hand FROM t_000042.part LIMIT 1;")
[ "${TOTAL%.*}" = "1" ] || fail "перемещение изменило общий остаток (получено: $TOTAL)"
ok "общий остаток сохранился"

HERE=$($PSQL -tAc "SELECT qty FROM t_000042.part_stock ps
                   JOIN t_000042.warehouse w ON w.id = ps.warehouse_id
                   WHERE w.name='Второй';")
[ "${HERE%.*}" = "1" ] || fail "деталь не доехала до второго склада (получено: $HERE)"
ok "деталь переехала на второй склад"

if $PSQL -c "INSERT INTO t_000042.stock_movement (part_id, movement_type, qty_delta, from_warehouse_id)
             SELECT p.id, 'SALE', -1, (SELECT id FROM t_000042.warehouse WHERE name='Основной')
             FROM t_000042.part p LIMIT 1;" >/dev/null 2>&1; then
    fail "списание с пустого склада прошло — триггер не стережёт остаток"
fi
ok "списание с пустого склада отбито"

DISC=$($PSQL -tAc "SELECT count(*) FROM t_000042.v_stock_discrepancy;")
[ "$DISC" = "0" ] || fail "после перемещения сверка разошлась: $DISC"
ok "сверка после перемещения чистая"

step "6. Идемпотентность повторного update"
$LB --changelog-file=changelog/db.changelog-tenant.xml $CRED \
    --default-schema-name=t_000042 --liquibase-schema-name=t_000042 \
    update -Dtenant.schema=t_000042
ok "повторный прогон без изменений"

step "7. Rollback арендатора"
# Откатываем всё, кроме самого первого changeset'а — создания схемы. Его
# rollback делает DROP SCHEMA CASCADE и сносит вместе со схемой сам
# DATABASECHANGELOG, после чего Liquibase не может вычеркнуть из него строку
# и падает. Удаление схемы — работа провижининга, а не миграций.
CNT=$($PSQL -tAc "SELECT count(*) - 1 FROM t_000043.databasechangelog;")
$LB --changelog-file=changelog/db.changelog-tenant.xml $CRED \
    --default-schema-name=t_000043 --liquibase-schema-name=t_000043 \
    rollback-count --count="$CNT" -Dtenant.schema=t_000043
ok "rollback отработал ($CNT changeset'ов)"

LEFT=$($PSQL -tAc "SELECT count(*) FROM information_schema.tables
                   WHERE table_schema='t_000043' AND table_name <> 'databasechangelog'
                     AND table_name <> 'databasechangeloglock';")
[ "$LEFT" = "0" ] || fail "после отката в схеме остались таблицы: $LEFT"
ok "схема вычищена до состояния «только служебные таблицы»"

printf '\n\033[1;32mВсе проверки пройдены.\033[0m\n'
printf 'Погасить: docker compose down -v\n'
