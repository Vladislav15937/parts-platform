#!/usr/bin/env bash
# Резервное копирование ячейки.
#
#   ops/backup.sh [файл-окружения]
#
# Схема-на-арендатора выбиралась ради одного: восстановить одного клиента,
# не трогая остальных. Поэтому каждый арендатор выгружается отдельным дампом,
# а не куском общего. Из кластерного дампа поднять одного клиента можно только
# подняв всех — то есть остановив ячейку.
#
# Общие схемы (public с реестром и catalog со справочниками) идут отдельным
# файлом: без них дамп арендатора не развернуть, а дублировать 800 КБ
# справочника в каждый из двухсот дампов незачем.
#
# Дампы содержат хеши паролей сотрудников и зашифрованные ключи кабинетов.
# Хранить их надо с той же осторожностью, что и саму базу; ключ шифрования
# держите ОТДЕЛЬНО от бэкапа — вместе они дают то же, что открытый текст.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${1:-.env}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

COMPOSE="docker compose -f docker-compose.prod.yml --env-file $ENV_FILE"
DB_USER="${DB_USER:?укажите DB_USER}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
# Каталог свой на каждую ячейку: два комплекта дампов в одной папке
# различались бы только временем снятия, и восстановление пошло бы не туда.
BACKUPS="${BACKUP_DIR:-./backups/${APP_CELL:-cell01}}"
OUT="$BACKUPS/$STAMP"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    %s\033[0m\n' "$1"; }
fail() { printf '\033[1;31m    ОШИБКА: %s\033[0m\n' "$1"; exit 1; }

psql_() { $COMPOSE exec -T postgres psql -U "$DB_USER" -d parts -v ON_ERROR_STOP=1 "$@"; }

# Отметка об успехе для наблюдения.
#
# Бэкап, который тихо перестал сниматься, обнаруживается в день, когда
# он понадобился. Опрашивать задачу по расписанию нельзя — между запусками
# процесса нет, — поэтому она сама кладёт время последнего успеха, а тревога
# смотрит на его возраст.
#
# Отметка ставится ПОСЛЕ всех шагов: поставленная в начале, она означала бы
# «запускался», а знать надо «получилось».
mark_success() {
    local metric="$1"
    local url="${PUSHGATEWAY_URL:-http://localhost:9091}"
    if ! printf '# TYPE %s gauge\n%s %s\n' "$metric" "$metric" "$(date -u +%s)" \
        | curl -sf --max-time 10 --data-binary @- "$url/metrics/job/backup" > /dev/null
    then
        # Не «выход с ошибкой»: бэкап-то снялся, и валить его из-за
        # недоступного наблюдения значит терять данные ради метрики.
        printf '\033[1;33m    Отметка в наблюдение не ушла — проверьте pushgateway\033[0m\n'
    fi
}


mkdir -p "$OUT"

step "Общие схемы: реестр арендаторов и справочники"
# --schema, а не весь кластер: роли и настройки сервера восстанавливает
# провижининг ячейки, а не бэкап данных.
$COMPOSE exec -T postgres pg_dump -U "$DB_USER" -d parts \
    --format=custom --schema=public --schema=catalog \
    > "$OUT/shared.dump"
ok "shared.dump — $(du -h "$OUT/shared.dump" | cut -f1)"

step "Арендаторы"
# Берём из реестра, а не перечисляем схемы: схема без записи в реестре —
# это мусор от сорвавшегося провижининга, и восстанавливать его незачем.
TENANTS=$(psql_ -tAc "SELECT schema_name FROM public.tenant_registry
                      WHERE status IN ('ACTIVE','SUSPENDED') ORDER BY tenant_id")

if [ -z "$TENANTS" ]; then
    ok "арендаторов нет"
else
    for schema in $TENANTS; do
        # Каждый дамп согласован сам по себе: pg_dump держит снимок на время
        # одного вызова. Между арендаторами согласованности нет и не нужно —
        # их данные не пересекаются.
        $COMPOSE exec -T postgres pg_dump -U "$DB_USER" -d parts \
            --format=custom --schema="$schema" > "$OUT/$schema.dump"
        ok "$schema — $(du -h "$OUT/$schema.dump" | cut -f1)"
    done
fi

step "Опись"
{
    echo "снято: $STAMP"
    echo "арендаторов: $(echo "$TENANTS" | grep -c . || true)"
    psql_ -tAc "SELECT tenant_id||' '||schema_name||' '||code||' '||status
                  FROM public.tenant_registry ORDER BY tenant_id"
} > "$OUT/manifest.txt"
ok "manifest.txt"

step "Уборка старше $KEEP_DAYS дней"
find "$BACKUPS" -maxdepth 1 -type d -name '20*' -mtime "+$KEEP_DAYS" \
    -exec rm -rf {} + 2>/dev/null || true
ok "осталось наборов: $(find "$BACKUPS" -maxdepth 1 -type d -name '20*' | wc -l | tr -d ' ')"

mark_success partsflow_backup_success_timestamp_seconds

printf '\n\033[1;32mБэкап снят: %s\033[0m\n' "$OUT"
printf 'Проверить восстановлением: ops/verify-backup.sh %s\n' "$OUT"
