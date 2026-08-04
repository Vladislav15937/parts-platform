#!/usr/bin/env bash
# Восстановление ячейки целиком — после потери машины или диска.
#
#   ops/restore-cell.sh [каталог-набора] [файл-окружения]
#
# Восстановление одного клиента делает restore-tenant.sh, и оно проверяется
# каждую неделю. А вот «поднять ячейку заново» до сих пор не проверялось ни
# разу: пока в Postgres стояло умолчание max_locks_per_transaction, полный
# дамп даже не снимался. Скрипт закрывает этот пробел и меряет время —
# чтобы в аварию было известно, сколько ждать, а не выяснялось на месте.
#
# Порядок жёсткий и следует из устройства ячейки:
#   1. Общие схемы (public с реестром, catalog со справочниками) — без них
#      дамп арендатора не развернуть, а провижининг не заведёт нового.
#   2. Схемы арендаторов по одной, из их же дампов.
#   3. Фотографии из зеркала: склад со ссылками в никуда — это не
#      возвращённый клиент, на разборке продаёт фотография.
#
# ВНИМАНИЕ: скрипт разворачивает в ПУСТУЮ базу. Существующие данные он
# не трогает и не перезаписывает — если схема уже есть, разворот встанет,
# и это правильно: восстановление поверх работающей ячейки означало бы
# смешать старое с новым.
#
# Файл окружения выбирается переменной ENV_FILE — как и у остальных скриптов.
set -euo pipefail
cd "$(dirname "$0")/.."

SET_DIR="${1:-}"
ENV_FILE="${2:-${ENV_FILE:-.env}}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

: "${DB_USER:?укажите DB_USER}"
BACKUPS="${BACKUP_DIR:-./backups/${APP_CELL:-cell01}}"
[ -n "$SET_DIR" ] || SET_DIR=$(find "$BACKUPS" -maxdepth 1 -type d -name '20*' | sort | tail -1)
[ -d "$SET_DIR" ] || { echo "Набор не найден: $SET_DIR"; exit 1; }

COMPOSE="docker compose -f docker-compose.prod.yml"
step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    %s\033[0m\n' "$1"; }

STARTED=$(date +%s)
step "Набор $SET_DIR"
[ -f "$SET_DIR/manifest.txt" ] && head -2 "$SET_DIR/manifest.txt" | sed 's/^/    /'

step "Расширения Postgres"
# pg_dump выгружает схемы, а расширения принадлежат базе — в дампе схем
# их нет. Без них разворот арендатора падает на первой же таблице:
# «function public.gen_random_bytes(integer) does not exist», потому что
# public_code выдаётся умолчанием колонки через pgcrypto. Найдено репетицией
# восстановления: до неё ячейка после потери диска просто не поднималась,
# и узналось бы это в тот единственный день, когда это нужно.
$COMPOSE exec -T postgres psql -U "$DB_USER" -d parts -v ON_ERROR_STOP=1 <<'SQL'
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
SQL
ok "pg_trgm, ltree, pgcrypto"

step "Общие схемы"
# --clean не нужен: разворот идёт в пустую базу. Отказ «schema public
# already exists» безобиден — эта схема есть в любой базе с рождения,
# поэтому ошибки разбираем по итогу, а не по коду возврата.
$COMPOSE exec -T postgres pg_restore -U "$DB_USER" -d parts --no-owner \
    < "$SET_DIR/shared.dump" 2>&1 | grep -v 'schema "public" already exists' \
    | grep -v 'CREATE SCHEMA public' || true
ok "реестр и справочники подняты"

step "Арендаторы"
COUNT=0
for dump in "$SET_DIR"/t_*.dump; do
    [ -e "$dump" ] || break
    schema=$(basename "$dump" .dump)
    $COMPOSE exec -T postgres pg_restore -U "$DB_USER" -d parts --no-owner < "$dump"
    COUNT=$((COUNT + 1))
    ok "$schema"
done
ok "восстановлено арендаторов: $COUNT"

step "Фотографии"
MIRROR="$BACKUPS/photos"
if [ -d "$MIRROR" ]; then
    $COMPOSE exec -T mc sh -c "mc mirror --overwrite /backup/photos local/${S3_BUCKET:-parts-photos}" \
        || echo "    зеркало не поднялось — проверьте mc и путь $MIRROR"
    ok "снимки возвращены из зеркала"
else
    echo "    зеркала нет: склад поднимется без фотографий"
fi

step "Права рабочей роли"
if [ -n "${APP_RUNTIME_ROLE:-}" ]; then
    echo "    выдаст приложение при первом накате: ops/migrate-tenants.sh"
else
    echo "    разделения ролей нет — пропускаем"
fi

step "Проверка"
$COMPOSE exec -T postgres psql -U "$DB_USER" -d parts -tAc \
    "SELECT count(*)||' арендаторов в реестре' FROM public.tenant_registry" | sed 's/^/    /'
$COMPOSE exec -T postgres psql -U "$DB_USER" -d parts -tAc \
    "SELECT count(*)||' схем в базе' FROM information_schema.schemata WHERE schema_name LIKE 't\_%'" \
    | sed 's/^/    /'

printf '\n\033[1;32mЯчейка восстановлена за %d с.\033[0m\n' "$(( $(date +%s) - STARTED ))"
echo "Дальше: поднять приложение и прогнать ops/migrate-tenants.sh —"
echo "он догонит схемы до версии кода и выдаст права рабочей роли."
