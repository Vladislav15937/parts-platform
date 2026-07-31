#!/usr/bin/env bash
# Проверка бэкапа восстановлением.
#
#   ops/verify-backup.sh [каталог-набора] [файл-окружения]
#
# «Дамп снялся» — это не бэкап. Бэкап — это когда из него поднялся арендатор
# и в нём тот же склад. Проверяется именно это: набор разворачивается
# в отдельную базу, и агрегаты сверяются с живой.
#
# Восстановление идёт в ОТДЕЛЬНУЮ базу, а не в ту же под другим именем схемы:
# pg_restore кладёт схему туда, откуда её сняли, переименовать на лету нечем.
# Заодно это ближе к настоящему восстановлению — на чистый кластер.
set -euo pipefail
cd "$(dirname "$0")/.."

SET_DIR="${1:-$(find "${BACKUP_DIR:-./backups/${APP_CELL:-cell01}}" -maxdepth 1 -type d -name '20*' | sort | tail -1)}"
ENV_FILE="${2:-.env}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

COMPOSE="docker compose -f docker-compose.prod.yml --env-file $ENV_FILE"
DB_USER="${DB_USER:?укажите DB_USER}"
CHECK_DB="parts_verify"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    OK: %s\033[0m\n' "$1"; }
fail() { printf '\033[1;31m    ПРОВАЛ: %s\033[0m\n' "$1"; exit 1; }

[ -d "$SET_DIR" ] || fail "набор не найден: $SET_DIR"
[ -f "$SET_DIR/shared.dump" ] || fail "в наборе нет shared.dump"

live()  { $COMPOSE exec -T postgres psql -U "$DB_USER" -d parts -tAc "$1"; }
check() { $COMPOSE exec -T postgres psql -U "$DB_USER" -d "$CHECK_DB" -tAc "$1"; }

# Проверочную базу убираем в любом случае. Оставленная после провала, она
# занимает место кластера и путает следующий запуск: увидев её, легко решить,
# что проверка идёт прямо сейчас.
cleanup() {
    $COMPOSE exec -T postgres psql -U "$DB_USER" -d postgres \
        -c "DROP DATABASE IF EXISTS $CHECK_DB" >/dev/null 2>&1 || true
}
trap cleanup EXIT

printf 'Набор: %s\n' "$SET_DIR"

step "Готовим чистую базу для проверки"
$COMPOSE exec -T postgres psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 \
    -c "DROP DATABASE IF EXISTS $CHECK_DB" -c "CREATE DATABASE $CHECK_DB" >/dev/null
ok "$CHECK_DB создана"

step "Восстанавливаем общие схемы"
$COMPOSE exec -T postgres pg_restore -U "$DB_USER" -d "$CHECK_DB" --no-owner \
    < "$SET_DIR/shared.dump" >/dev/null 2>&1 || true
BRANDS=$(check "SELECT count(*) FROM catalog.brand")
[ "$BRANDS" -gt 0 ] || fail "справочник марок пуст — общие схемы не развернулись"
ok "справочники на месте: марок $BRANDS"

step "Восстанавливаем арендаторов и сверяем"
FAILED=0
for dump in "$SET_DIR"/t_*.dump; do
    [ -e "$dump" ] || { ok "арендаторов в наборе нет"; break; }
    schema=$(basename "$dump" .dump)

    $COMPOSE exec -T postgres pg_restore -U "$DB_USER" -d "$CHECK_DB" --no-owner \
        < "$dump" >/dev/null 2>&1 || true

    # Сверяем то, что клиент заметит первым: сколько позиций, сколько лежит
    # на складе, сколько записей в журнале и сколько сделок. Совпадение
    # количества строк без совпадения остатка ничего не значит: остаток —
    # агрегат журнала, и разъехаться он может независимо.
    for query in \
        "SELECT count(*) FROM $schema.part" \
        "SELECT COALESCE(sum(qty), 0) FROM $schema.part_stock" \
        "SELECT count(*) FROM $schema.stock_movement" \
        "SELECT count(*) FROM $schema.deal" \
        "SELECT count(*) FROM $schema.tenant_member"
    do
        expected=$(live "$query")
        actual=$(check "$query")
        if [ "$expected" != "$actual" ]; then
            printf '\033[1;31m    %s: живая %s, восстановленная %s\033[0m\n' \
                "${query#SELECT }" "$expected" "$actual"
            FAILED=1
        fi
    done
    [ "$FAILED" = 0 ] && ok "$schema сошёлся"
done

[ "$FAILED" = 0 ] || fail "восстановленные данные расходятся с живыми"

# Отметка ставится только здесь, после сверки: «дамп открылся» и «клиента
# можно вернуть» — разные утверждения, и тревога должна следить за вторым.
if ! printf '# TYPE partsflow_backup_verified_timestamp_seconds gauge\npartsflow_backup_verified_timestamp_seconds %s\n' \
    "$(date -u +%s)" \
    | curl -sf --max-time 10 --data-binary @- \
        "${PUSHGATEWAY_URL:-http://localhost:9091}/metrics/job/backup" > /dev/null
then
    printf '\033[1;33m    Отметка в наблюдение не ушла — проверьте pushgateway\033[0m\n'
fi

printf '\n\033[1;32mБэкап проверен восстановлением.\033[0m\n'
