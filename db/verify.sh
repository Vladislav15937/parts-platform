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
    $LB --changelog-file=changelog/db.changelog-tenant.xml $CRED \
        -Dtenant.schema=$T --default-schema-name=$T --liquibase-schema-name=$T update
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
INSERT INTO t_000042.storage_cell (warehouse_id, code)
    SELECT id, 'А-01-1' FROM t_000042.warehouse LIMIT 1;
INSERT INTO t_000042.part (category_id, title, price, status)
    VALUES (1, 'Фара левая Camry V50', 8500, 'IN_STOCK');
INSERT INTO t_000042.stock_movement (part_id, movement_type, qty_delta, to_cell_id)
    SELECT p.id, 'INTAKE', 1, c.id
    FROM t_000042.part p, t_000042.storage_cell c LIMIT 1;
SQL

QTY=$($PSQL -tAc "SELECT qty_on_hand FROM t_000042.part LIMIT 1;")
[ "${QTY%.*}" = "1" ] || fail "остаток не обновился триггером (получено: $QTY)"
ok "остаток пересчитан"

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

step "6. Идемпотентность повторного update"
$LB --changelog-file=changelog/db.changelog-tenant.xml $CRED \
    -Dtenant.schema=t_000042 --default-schema-name=t_000042 \
    --liquibase-schema-name=t_000042 update
ok "повторный прогон без изменений"

step "7. Rollback арендатора"
$LB --changelog-file=changelog/db.changelog-tenant.xml $CRED \
    -Dtenant.schema=t_000043 --default-schema-name=t_000043 \
    --liquibase-schema-name=t_000043 rollback-count --count=100
ok "rollback отработал"

printf '\n\033[1;32mВсе проверки пройдены.\033[0m\n'
printf 'Погасить: docker compose down -v\n'
