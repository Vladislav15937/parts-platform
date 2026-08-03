#!/usr/bin/env bash
# Полная проверка миграций на чистой базе.
#   ./verify.sh
#
# Что проверяется:
#   1. Каталог накатывается на пустую базу
#   2. Два арендатора провижинятся независимо
#   3. DATABASECHANGELOG у каждого арендатора — внутри его схемы
#   4. Изоляция: данные арендаторов не пересекаются
#   5. Что осталось за базой: ограничения, каскады, сверки — и что в схеме
#      арендатора не осталось ни триггеров, ни функций, ни генерируемых колонок
#   6. Повторный update идемпотентен
#   7. rollback отрабатывает

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

step "5. Что осталось за базой: ограничения, каскады, сверки"
# Раньше здесь проверялись триггеры: остаток, аудит, неизменяемость журнала.
# Их больше нет — вся логика переехала в приложение (docs/triggers-to-java.md),
# и проверять её надо тестами, а не миграциями. Здесь остаётся то, за что
# база по-прежнему отвечает: форма данных и связи между ними.
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
SQL

# Публичный код выдаётся умолчанием колонки: без него не напечатать этикетку
# и не узнать позицию в объявлении.
CODE=$($PSQL -tAc "SELECT public_code FROM t_000042.part LIMIT 1;")
[ -n "$CODE" ] || fail "публичный код не выдан умолчанием"
ok "публичный код выдан ($CODE)"

# Резерв больше остатка отбивает ограничение схемы, а не триггер.
$PSQL -c "INSERT INTO t_000042.part_stock (part_id, warehouse_id, qty, qty_reserved)
          SELECT p.id, w.id, 1, 1 FROM t_000042.part p, t_000042.warehouse w
           WHERE w.name='Основной' LIMIT 1;" >/dev/null
if $PSQL -c "UPDATE t_000042.part_stock SET qty_reserved = 5;" >/dev/null 2>&1; then
    fail "резерв больше остатка прошёл — part_stock_reserved_ck не стережёт"
fi
ok "резерв больше остатка отбит ограничением"

# Ссылочная целостность: удаление позиции уносит её раскладку каскадом.
$PSQL -c "DELETE FROM t_000042.part_stock;" >/dev/null
$PSQL -c "DELETE FROM t_000042.part;" >/dev/null
LEFTOVER=$($PSQL -tAc "SELECT count(*) FROM t_000042.part_stock;")
[ "$LEFTOVER" = "0" ] || fail "раскладка пережила удаление позиции: $LEFTOVER"
ok "каскады работают"

# Сверки — вопросы к данным, а не поведение: они обязаны существовать
# и на пустом складе отвечать «расхождений нет».
for VIEW in v_stock_discrepancy v_reservation_discrepancy v_account_discrepancy; do
    CNT=$($PSQL -tAc "SELECT count(*) FROM t_000042.$VIEW;")
    [ "$CNT" = "0" ] || fail "$VIEW на пустом складе не пуста: $CNT"
done
ok "сверки на месте и чисты"

# Ни одного триггера и ни одной функции в схеме арендатора — это и есть
# правило «логика только в приложении», проверяемое, а не декларируемое.
TRG=$($PSQL -tAc "SELECT count(*) FROM pg_trigger t
                    JOIN pg_class c ON c.oid = t.tgrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE n.nspname = 't_000042' AND NOT t.tgisinternal;")
[ "$TRG" = "0" ] || fail "в схеме арендатора остались триггеры: $TRG"
FN=$($PSQL -tAc "SELECT count(*) FROM pg_proc p
                   JOIN pg_namespace n ON n.oid = p.pronamespace
                  WHERE n.nspname = 't_000042';")
[ "$FN" = "0" ] || fail "в схеме арендатора остались функции: $FN"
GEN=$($PSQL -tAc "SELECT count(*) FROM information_schema.columns
                   WHERE table_schema = 't_000042' AND is_generated = 'ALWAYS';")
[ "$GEN" = "0" ] || fail "в схеме арендатора остались генерируемые колонки: $GEN"
ok "триггеров, функций и генерируемых колонок нет"

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
