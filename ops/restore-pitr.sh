#!/usr/bin/env bash
# Возврат к моменту времени: базовая копия + накат архива WAL.
#
#   ops/restore-pitr.sh --to '2026-09-12 13:59:00'              # поднять состояние на 13:59
#   ops/restore-pitr.sh --to '...' --tenant t_000042            # и вынуть одного клиента
#   ops/restore-pitr.sh --to latest                             # до конца архива
#   ops/restore-pitr.sh --verify                                # репетиция, так зовёт cron
#
# Ключевое решение: разворот идёт в ОТДЕЛЬНЫЙ временный кластер, а не поверх
# живого. Причин две, и обе про схему-на-арендатора.
#
# Первая: накат WAL возвращает кластер ЦЕЛИКОМ, то есть всех арендаторов
# на один момент. Если у одного клиента авария, а остальные работают, возврат
# поверх живой базы отнял бы у остальных всё, что они наработали с этого
# момента, — и именно от этого schema-per-tenant защищает. Во временном
# кластере состояние на нужный момент есть у всех, а забрать из него можно
# ОДНОГО: pg_dump одной схемы, дальше обычный ops/restore-tenant.sh.
#
# Вторая: разворот поверх живого невозвратим. Ошибся с моментом — второй
# попытки нет, потому что то, чем ты пользовался, уже перезаписано.
# Во временном кластере ошибка с моментом стоит повторного прогона.
#
# Что делать, когда погиб весь кластер, а не один клиент: поднять временный
# так же, убедиться, что момент тот, и переключить ячейку на его каталог
# данных (порядок — в docs/deployment.md, «Возврат к моменту времени»).
#
# ОТКАЗ ВМЕСТО ТИХОГО ВОССТАНОВЛЕНИЯ. Разрыв в архиве Postgres переживает
# молча: накат останавливается на пропавшем сегменте, и — если момент
# не задан — кластер поднимается на том, что успел накатить. Снаружи это
# выглядит успехом: база работает, данные есть, просто не все. Поэтому
# цепочка проверяется ДО разворота (ops/wal-archive.sh --chain), а после
# разворота сверяется, что накат дошёл до запрошенного момента, а не
# остановился раньше. Оба сторожа нужны: первый называет доступный момент
# заранее, второй ловит то, чего в именах файлов не видно.
set -euo pipefail
cd "$(dirname "$0")/.."

TARGET=""
TENANT=""
BASE_DIR=""
KEEP=0
VERIFY=0

while [ $# -gt 0 ]; do
    case "$1" in
        --to)     TARGET="${2:?укажите момент или latest}"; shift 2 ;;
        --tenant) TENANT="${2:?укажите схему, например t_000042}"; shift 2 ;;
        --base)   BASE_DIR="${2:?укажите каталог базовой копии}"; shift 2 ;;
        --keep)   KEEP=1; shift ;;
        --verify) VERIFY=1; shift ;;
        *)        printf 'Использование: %s --to МОМЕНТ|latest [--tenant СХЕМА] [--base КАТАЛОГ] [--keep]\n' "$0" >&2
                  exit 2 ;;
    esac
done

ENV_FILE="${ENV_FILE:-.env}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

COMPOSE="docker compose -f docker-compose.prod.yml --env-file $ENV_FILE"
DB_USER="${DB_USER:?укажите DB_USER}"
CELL="${APP_CELL:-cell01}"
BACKUPS="${BACKUP_DIR:-./backups/$CELL}"
WAL_DIR="${WAL_ARCHIVE_DIR:-$BACKUPS/wal}"
BASES="$BACKUPS/base"
# Черновик возврата живёт ВНЕ каталога бэкапов намеренно: офсайт увозит
# каталог бэкапов целиком, и распакованный кластер уехал бы наружу вторым
# экземпляром базы, удвоив и место, и то, что можно потерять.
PITR="${PITR_DIR:-./backups/pitr/$CELL}"
IMAGE="postgres:16-alpine"
CNAME="partsflow-pitr-$CELL"
# Каталог данных — том docker, а не каталог на хосте: Postgres требует
# на каталоге данных права 700 и своего владельца, а у каталога, смонтированного
# с хоста, ни то, ни другое не гарантировано (на macOS разворот так и не
# поднимается). Заодно черновик не попадает ни в бэкап, ни в офсайт.
VOL="partsflow-pitr-$CELL"
WAIT="${PITR_WAIT:-900}"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    %s\033[0m\n' "$1"; }
warn() { printf '\033[1;33m    %s\033[0m\n' "$1"; }
fail() { printf '\033[1;31m    ОШИБКА: %s\033[0m\n' "$1"; exit 1; }

human() { date -d "@$1" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date -r "$1" '+%Y-%m-%d %H:%M:%S'; }
epoch_of() { date -d "$1" +%s 2>/dev/null || date -j -f '%Y-%m-%d %H:%M:%S' "$1" +%s 2>/dev/null; }
# Момент с явным смещением: без него Postgres толкует строку в часовом поясе
# СЕРВЕРА (в контейнере это UTC), и «вернуться к 13:59» молча становится
# «к 16:59» — то есть возврат уносит три часа работы, выглядя успешным.
with_offset() { date -d "@$1" '+%Y-%m-%d %H:%M:%S%z' 2>/dev/null || date -r "$1" '+%Y-%m-%d %H:%M:%S%z'; }
# Метка набора: 20260912T162000Z.
stamp_epoch() {
    local s="$1"
    date -u -d "${s:0:4}-${s:4:2}-${s:6:2} ${s:9:2}:${s:11:2}:${s:13:2}" +%s 2>/dev/null \
        || date -u -j -f '%Y%m%dT%H%M%SZ' "$s" +%s
}

if [ "$VERIFY" = 1 ]; then
    # Репетиция накатывает архив ДО КОНЦА, а не к моменту «десять минут
    # назад», и это следствие двух живых отказов, а не осторожность.
    #
    # Первый: последний сегмент ещё не закрыт (archive_timeout=300), записи
    # последних минут лежат в живом pg_wal, и накат до них не доходит —
    # цель «десять минут назад» краснела на полностью исправной ячейке.
    #
    # Второй, и он важнее: Postgres объявляет цель по времени достигнутой,
    # встретив в журнале ЗАПИСЬ ПОЗЖЕ неё, а записи эти — коммиты. Ночью
    # и в выходные коммитов нет вовсе, и цель по времени не достигается
    # ни при какой исправности: «recovery ended before configured recovery
    # target was reached», то есть тревога в понедельник утром на здоровой
    # ячейке. А репетиция стоит как раз в понедельник в 6:00.
    #
    # Накат до конца архива от этого не зависит, а проверяет ровно то, ради
    # чего задача и заводилась: дошёл ли накат до последнего уехавшего
    # сегмента — или тихо встал на разрыве. Проверку «дошёл до конца»
    # делает шаг ниже, общий с обычным `--to latest`.
    if [ -z "$(find "$WAL_DIR" -maxdepth 1 -type f -name '????????????????????????.gz' 2>/dev/null | head -1)" ]
    then
        printf 'В архиве нет ни одного сегмента — репетиция осмысленна, когда ячейка поработает.\n'
        exit 0
    fi
    TARGET="latest"
    TENANT=""
    KEEP=0
fi

[ -n "$TARGET" ] || fail "не сказано, к какому моменту возвращаться (--to)"

LATEST=0
if [ "$TARGET" = "latest" ]; then
    LATEST=1
    TARGET_EPOCH=$(date +%s)
else
    TARGET_EPOCH=$(epoch_of "$TARGET") || fail "не разобрал момент: $TARGET (нужно «ГГГГ-ММ-ДД ЧЧ:ММ:СС»)"
fi

step "Базовая копия"
if [ -z "$BASE_DIR" ]; then
    # Нужна самая свежая копия, снятая НЕ ПОЗЖЕ запрошенного момента:
    # накатывать WAL можно только вперёд. Копия из будущего физически
    # не догоняется до прошлого — и это самая частая ошибка в аварию,
    # потому что «взять последний бэкап» звучит правильно.
    for dir in $(find "$BASES" -maxdepth 1 -type d -name '20*' 2>/dev/null | sort); do
        [ -f "$dir/READY" ] || continue
        [ "$(stamp_epoch "$(basename "$dir")")" -le "$TARGET_EPOCH" ] || continue
        BASE_DIR="$dir"
    done
    [ -n "$BASE_DIR" ] || fail "нет базовой копии не позже $(human "$TARGET_EPOCH") — проверьте $BASES и ops/basebackup.sh"
fi
[ -f "$BASE_DIR/base.tar.gz" ] || fail "в $BASE_DIR нет base.tar.gz"
[ -f "$BASE_DIR/START_WAL" ]   || fail "в $BASE_DIR нет START_WAL — не с чего начинать накат"
START_WAL=$(cat "$BASE_DIR/START_WAL")
ok "$BASE_DIR (снята $(basename "$BASE_DIR")), накат с $START_WAL"

step "Цепочка архива WAL"
# ДО разворота, и это половина задачи: разрыв, найденный после, стоит уже
# потраченного часа и всё равно означает отказ. Тем же кодом, что проверяет
# архив по расписанию, — чтобы «до какого момента можно вернуться» отвечало
# одинаково и в спокойный день, и в аварию.
if [ "$LATEST" = 1 ]; then
    ops/wal-archive.sh --chain "" "$START_WAL" \
        || fail "архив рваный — возврат до конца архива невозможен (см. доступный момент выше)"
else
    ops/wal-archive.sh --chain "$TARGET" "$START_WAL" \
        || fail "к $TARGET вернуться нельзя — см. доступный момент выше"
fi

step "Черновик возврата"
if docker ps -a --format '{{.Names}}' | grep -q "^$CNAME$"; then
    warn "остался прошлый черновик $CNAME — убираю"
    docker rm -f "$CNAME" > /dev/null
fi
docker volume rm -f "$VOL" > /dev/null 2>&1 || true
docker volume create "$VOL" > /dev/null
mkdir -p "$PITR"

# Распаковка потоком: каталог бэкапов в контейнер не монтируется вовсе,
# а владельца и права на каталоге данных выставляем тут же — Postgres
# не поднимется на чужих.
docker run -i --rm -v "$VOL:/data" "$IMAGE" \
    sh -c 'tar xzf - -C /data && chown -R postgres:postgres /data && chmod 700 /data' \
    < "$BASE_DIR/base.tar.gz"
# recovery.signal — то, что отличает «поднять копию как есть» от «накатить
# на неё журнал». Без него кластер поднимется на состоянии базовой копии
# и объявит успех: то самое тихое восстановление неполного.
docker run -i --rm -v "$VOL:/data" "$IMAGE" \
    sh -c 'touch /data/recovery.signal && chown postgres:postgres /data/recovery.signal'
ok "копия распакована в том $VOL"

step "Накат журнала"
WAL_ABS="$(cd "$WAL_DIR" && pwd)"
RECOVERY_ARGS=(
    postgres
    # Архив только на чтение, и archive_mode=off вторым замком: черновик,
    # пишущий в архив живой ячейки, испортил бы историю, по которой
    # восстанавливаются, — и обнаружилось бы это при следующей аварии.
    -c archive_mode=off
    -c "restore_command=gunzip -c /wal-archive/%f.gz > %p"
    -c max_locks_per_transaction=512
)
if [ "$LATEST" = 1 ]; then
    ok "накат до конца архива"
else
    RECOVERY_TIME="$(with_offset "$TARGET_EPOCH")"
    ok "накат до $RECOVERY_TIME"
    RECOVERY_ARGS+=(
        -c "recovery_target_time=$RECOVERY_TIME"
        # pause, а не promote: остановившись на нужном месте, кластер даёт
        # сверить момент и снять дамп, не открывая новую линию времени.
        # Повышение здесь не нужно вовсе — pg_dump работает и на копии,
        # стоящей в восстановлении.
        -c recovery_target_action=pause
    )
fi

docker run -d --name "$CNAME" \
    -v "$VOL:/var/lib/postgresql/data" \
    -v "$WAL_ABS:/wal-archive:ro" \
    "$IMAGE" "${RECOVERY_ARGS[@]}" > /dev/null

cleanup() {
    local code=$?
    if [ "$KEEP" = 1 ] && [ "$code" = 0 ]; then
        printf '\nЧерновик оставлен: контейнер %s, том %s\n' "$CNAME" "$VOL"
        printf 'Посмотреть:  docker exec -it %s psql -U %s -d parts\n' "$CNAME" "$DB_USER"
        printf 'Убрать:      docker rm -f %s && docker volume rm %s\n' "$CNAME" "$VOL"
    else
        docker rm -f "$CNAME" > /dev/null 2>&1 || true
        docker volume rm -f "$VOL" > /dev/null 2>&1 || true
    fi
    return $code
}
trap cleanup EXIT

q() { docker exec "$CNAME" psql -U "$DB_USER" -d parts -tAqc "$1" 2>/dev/null; }

STARTED=$(date +%s)
STATE=""
REPLAYED=""
# Последняя накатанная транзакция, замеченная ПОКА шёл накат: после подъёма
# кластера Postgres этого числа больше не отдаёт, а человеку нужно именно
# оно — «запросили 13:59» и «накатано до 13:58:57» разные утверждения.
LAST_REPLAYED="—"
while :; do
    if ! docker ps --format '{{.Names}}' | grep -q "^$CNAME$"; then
        # Кластер умер во время наката. Самый частый случай — как раз тот,
        # ради которого задача и заводилась: журнал кончился раньше
        # запрошенного момента, и Postgres отказался подниматься. Отдаём
        # его же слова: догадка на этом месте дороже цитаты.
        printf '\n\033[1;31mНакат оборвался. Последние строки журнала:\033[0m\n'
        docker logs --tail 25 "$CNAME" 2>&1 | sed 's/^/    /'
        if docker logs "$CNAME" 2>&1 | grep -q 'recovery ended before configured recovery target'
        then
            fail "журнал кончился раньше $TARGET: к этому моменту вернуться нельзя"
        fi
        fail "накат не дошёл до конца — разбирайте по журналу выше"
    fi

    STATE=$(q "SELECT CASE WHEN pg_is_in_recovery()
                           THEN pg_get_wal_replay_pause_state() ELSE 'поднят' END" || true)
    REPLAYED=$(q "SELECT COALESCE(pg_last_xact_replay_timestamp()::text, '—')" || true)
    [ -n "$REPLAYED" ] && [ "$REPLAYED" != "—" ] && LAST_REPLAYED="$REPLAYED"
    case "$STATE" in
        paused) ok "накат остановлен на запрошенном моменте"; break ;;
        поднят) ok "накат дошёл до конца архива, кластер поднят"; break ;;
    esac

    [ "$(( $(date +%s) - STARTED ))" -lt "$WAIT" ] \
        || fail "накат не кончился за $WAIT с — смотрите docker logs $CNAME"
    sleep 2
done

step "Куда именно вернулись"
# Момент, до которого дошёл накат, спрашиваем у самой копии. «Запросили 13:59»
# и «накатано до 13:58:57» — разные утверждения, и человеку нужно второе:
# по нему он и решает, та ли это точка.
REPLAYED=$(q "SELECT COALESCE(pg_last_xact_replay_timestamp()::text, '—')")
[ -n "$REPLAYED" ] && [ "$REPLAYED" != "—" ] && LAST_REPLAYED="$REPLAYED"
printf '    запрошено: %s\n' "$([ "$LATEST" = 1 ] && echo "конец архива" || echo "$TARGET")"
printf '    последняя накатанная транзакция: %s\n' "$LAST_REPLAYED"
if [ "$LATEST" != 1 ] && [ "$STATE" != "paused" ]; then
    fail "момент запрошен, а накат остановился сам — состояние «$STATE», вернулись НЕ туда"
fi

if [ "$LATEST" = 1 ]; then
    # Второй сторож, и он ловит то, чего в именах файлов не видно. Накат
    # до конца архива Postgres обрывает МОЛЧА: не сумев прочитать очередной
    # сегмент, он объявляет «archive recovery complete» и поднимается на том,
    # что успел. Снаружи это успех — база работает, данные есть, просто
    # не все. Проверено подделкой: со снятыми сторожами возврат отчитался
    # нулём и поднял кластер, в котором из четырёх сделок клиента осталась
    # одна.
    #
    # Поэтому сверяем, до какого сегмента дошёл накат, с последним, что лежит
    # в архиве. Сравнение строкой без линии времени: после подъёма линия
    # уже другая, а шестнадцатеричные номера фиксированной ширины сравниваются
    # как текст.
    LAST_IN_ARCHIVE=$(find "$WAL_DIR" -maxdepth 1 -type f -name '????????????????????????.gz' \
                      | sed 's|.*/||; s|\.gz$||' | sort | tail -1)
    ENDED=$(q "SELECT pg_walfile_name(pg_current_wal_lsn())")
    if [ -n "$LAST_IN_ARCHIVE" ] && [[ "${ENDED:8}" < "${LAST_IN_ARCHIVE:8}" ]]; then
        fail "накат встал на $ENDED, а в архиве есть $LAST_IN_ARCHIVE — восстановлено НЕ всё"
    fi
    ok "накат дошёл до конца архива: $ENDED (последний в архиве $LAST_IN_ARCHIVE)"
fi
TENANTS=$(q "SELECT count(*) FROM public.tenant_registry")
ok "арендаторов в восстановленном реестре: $TENANTS"

if [ "$VERIFY" = 1 ]; then
    step "Сверка с живой ячейкой"
    LIVE=$($COMPOSE exec -T postgres psql -U "$DB_USER" -d parts -tAqc \
        "SELECT count(*) FROM public.tenant_registry")
    # Равенства здесь НЕ требуем, и это не небрежность: возврат идёт к моменту
    # в прошлом, и клиент, заведённый после него, в восстановленном реестре
    # законно отсутствует — требование равенства красило бы репетицию каждый
    # раз после подключения клиента. Невозможное — арендаторов БОЛЬШЕ, чем
    # в живой, — это уже чужие данные, и вот это отказ.
    printf '    в живой ячейке %s, в восстановленной %s\n' "$LIVE" "$TENANTS"
    [ "$TENANTS" -le "$LIVE" ] \
        || fail "в восстановленном реестре арендаторов больше, чем в живом — это не наш набор"
    # Главное утверждение репетиции — выше: накат ОСТАНОВИЛСЯ на запрошенном
    # моменте. Отказавшись дойти, Postgres не поднимается вовсе, и до этого
    # места мы бы не добрались.
    ok "накат прошёл весь архив, реестр читается"

    # Отметка ставится только здесь: «архив непрерывен» и «к моменту можно
    # вернуться» — разные утверждения, и следить надо за вторым. Цепочка
    # сегментов проверяется только разворотом.
    if ! printf '# TYPE partsflow_pitr_verified_timestamp_seconds gauge\npartsflow_pitr_verified_timestamp_seconds %s\n' \
            "$(date -u +%s)" \
        | curl -sf --max-time 10 --data-binary @- \
            "${PUSHGATEWAY_URL:-http://localhost:9091}/metrics/job/backup" > /dev/null
    then
        warn "отметка в наблюдение не ушла — проверьте pushgateway"
    fi
    printf '\n\033[1;32mВозврат проверен разворотом: базовая копия %s плюс весь архив.\033[0m\n' \
        "$(basename "$BASE_DIR")"
    exit 0
fi

if [ -n "$TENANT" ]; then
    step "Вынимаем $TENANT"
    # Дамп одной схемы из черновика — и есть ответ на «вернуть одного
    # клиента к 13:59, не трогая остальных». Дальше он разворачивается тем же
    # ops/restore-tenant.sh, которым разворачивают ночной дамп: вторая дорога
    # к тому же месту разъехалась бы с первой.
    q "SELECT 1 FROM information_schema.schemata WHERE schema_name = '$TENANT'" | grep -q 1 \
        || fail "схемы $TENANT в восстановленном кластере нет"
    mkdir -p "$PITR/$(basename "$BASE_DIR")"
    DUMP_SET="$PITR/$(basename "$BASE_DIR")"
    docker exec "$CNAME" pg_dump -U "$DB_USER" -d parts --format=custom --schema="$TENANT" \
        > "$DUMP_SET/$TENANT.dump"
    ok "$DUMP_SET/$TENANT.dump — $(du -h "$DUMP_SET/$TENANT.dump" | cut -f1)"
    printf '\nВернуть клиента в живую ячейку (схема будет пересоздана):\n'
    printf '    PHOTO_DIR=%s/photos ops/restore-tenant.sh %s %s\n' "$BACKUPS" "$TENANT" "$DUMP_SET"
    printf '\nСнимки при этом не откатываются, и это намеренно: ключи в S3\n'
    printf 'неизменяемы, а зеркало бэкапа не удаляет — значит все снимки,\n'
    printf 'на которые ссылаются восстановленные карточки, на месте.\n'
fi

printf '\n\033[1;32mСостояние на %s поднято.\033[0m\n' \
    "$([ "$LATEST" = 1 ] && echo "конец архива" || echo "$TARGET")"
[ "$KEEP" = 1 ] || printf 'Черновик убран. Нужен для разбора — повторите с --keep.\n'
