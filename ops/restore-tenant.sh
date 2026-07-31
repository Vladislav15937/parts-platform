#!/usr/bin/env bash
# Восстановление одного арендатора.
#
#   ops/restore-tenant.sh t_000042 [каталог-набора] [файл-окружения]
#
# Ради этого и заведена схема на арендатора: один клиент поднимается
# из бэкапа, остальные продолжают работать. Из кластерного дампа так нельзя —
# пришлось бы останавливать ячейку целиком.
#
# ВНИМАНИЕ: схема арендатора удаляется и создаётся заново. Всё, что появилось
# в ней после снятия набора, будет потеряно. Скрипт спрашивает подтверждение
# именно поэтому.
#
# Файл окружения выбирается переменной ENV_FILE — одинаково у всех трёх
# скриптов. Позиция аргумента у них разная (набор, схема), и запоминать,
# какой по счёту здесь env, — ровно тот способ однажды снять бэкап одной
# ячейки, а проверить другой:
#
#   ENV_FILE=.env.cell02 ops/backup.sh
#   ENV_FILE=.env.cell02 ops/verify-backup.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

SCHEMA="${1:?укажите схему, например t_000042}"
ENV_FILE="${3:-${ENV_FILE:-.env}}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

# После чтения окружения: APP_CELL приезжает оттуда.
SET_DIR="${2:-$(find "${BACKUP_DIR:-./backups/${APP_CELL:-cell01}}" -maxdepth 1 -type d -name '20*' | sort | tail -1)}"

COMPOSE="docker compose -f docker-compose.prod.yml --env-file $ENV_FILE"
DB_USER="${DB_USER:?укажите DB_USER}"
DUMP="$SET_DIR/$SCHEMA.dump"

[ -f "$DUMP" ] || { printf '\033[1;31mНет дампа: %s\033[0m\n' "$DUMP"; exit 1; }

printf 'Схема:  %s\n' "$SCHEMA"
printf 'Набор:  %s (снят %s)\n' "$SET_DIR" "$(basename "$SET_DIR")"
printf '\n\033[1;31mСхема %s будет удалена и восстановлена из набора.\033[0m\n' "$SCHEMA"
printf 'Всё, что клиент завёл после снятия набора, пропадёт.\n'
printf 'Введите имя схемы для подтверждения: '
read -r CONFIRM
[ "$CONFIRM" = "$SCHEMA" ] || { echo "Отменено."; exit 1; }

$COMPOSE exec -T postgres psql -U "$DB_USER" -d parts -v ON_ERROR_STOP=1 \
    -c "DROP SCHEMA IF EXISTS $SCHEMA CASCADE" >/dev/null
$COMPOSE exec -T postgres pg_restore -U "$DB_USER" -d parts --no-owner < "$DUMP"

PARTS=$($COMPOSE exec -T postgres psql -U "$DB_USER" -d parts -tAc \
    "SELECT count(*) FROM $SCHEMA.part")
printf '\n\033[1;32mВосстановлено: %s, позиций %s\033[0m\n' "$SCHEMA" "$PARTS"
printf 'Остальные арендаторы не затронуты.\n'
