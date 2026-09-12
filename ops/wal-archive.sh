#!/usr/bin/env bash
# Сторож архива WAL: цел ли он, уехал ли наружу и до какого момента
# по нему можно вернуться.
#
#   ops/wal-archive.sh                              # проверить и увезти (так его зовёт cron)
#   ops/wal-archive.sh --chain [МОМЕНТ] [ОТ]        # отчёт о цепочке: МОМЕНТ — куда хотим
#                                                   # вернуться, ОТ — начальный сегмент
#                                                   # базовой копии
#   ops/wal-archive.sh --selftest                   # проверка самого сторожа
#
# Зачем отдельный сторож, когда есть тревога на дамп. Дамп — это один файл:
# снялся или нет. Архив WAL — цепочка, и ценность у неё ровно до первого
# разрыва: сегмент, которого нет, обрывает накат, а всё, что случилось
# позже, становится недостижимым. При этом каждый отдельный сегмент лежит
# на месте, размер архива растёт, отметка «бэкап снят» стоит — и ячейка
# выглядит защищённой. Поэтому проверяется НЕПРЕРЫВНОСТЬ, и проверяется
# часто: разрыв, замеченный через сутки, это сутки, за которые вернуться
# уже нельзя.
#
# Отчёт о цепочке нужен и восстановлению (ops/restore-pitr.sh зовёт этот же
# код), и по той же причине: «до какого момента можно вернуться» — вопрос
# к архиву, а не к базе, и отвечать на него надо ДО того, как начали
# разворачивать.
#
# Файл окружения выбирается переменной ENV_FILE — одинаково у всех скриптов
# ops/.
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="проверка"
case "${1:-}" in
    --chain)    MODE="цепочка"; shift ;;
    --selftest) MODE="самопроверка"; shift ;;
    --*)        printf 'Использование: %s [--chain [МОМЕНТ]|--selftest]\n' "$0" >&2; exit 2 ;;
esac

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    %s\033[0m\n' "$1"; }
warn() { printf '\033[1;33m    %s\033[0m\n' "$1"; }
bad()  { printf '\033[1;31m    %s\033[0m\n' "$1"; }

# stat и date разные на хосте ячейки (Linux) и на машине разработчика (macOS),
# а сторож обязан работать на обеих: разбирают аварию там, где сидят.
mtime() { stat -c %Y "$1" 2>/dev/null || stat -f %m "$1"; }
human() { date -d "@$1" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date -r "$1" '+%Y-%m-%d %H:%M:%S'; }
epoch_of() { date -d "$1" +%s 2>/dev/null || date -j -f '%Y-%m-%d %H:%M:%S' "$1" +%s 2>/dev/null; }

# Номер сегмента внутри линии времени. Имя — 24 шестнадцатеричных знака:
# линия времени (8), логический файл (8), номер сегмента (8, значения 00..FF).
# Сегменты идут подряд по (логический файл * 256 + номер) — с 9.3 в Postgres
# не пропускается и FF, поэтому арифметика простая.
seg_index() {
    local name="$1"
    printf '%d\n' "$(( 16#${name:8:8} * 256 + 16#${name:16:8} ))"
}
seg_name() {
    local tli="$1" idx="$2"
    printf '%s%08X%08X\n' "$tli" "$(( idx / 256 ))" "$(( idx % 256 ))"
}

# Отчёт о цепочке. Заполняет CHAIN_GAPS, CHAIN_LAST_EPOCH, CHAIN_LAST_NAME
# и возвращает 1, если вернуться к запрошенному моменту нельзя.
#
# Считается ПОСЛЕДНЯЯ линия времени. Своя линия появляется после настоящего
# возврата живой ячейки, и цепочка через границу линий читается только
# с файлом истории — это отдельный разговор, поэтому про несколько линий
# сторож говорит прямо, а не молча считает их одной.
chain_report() {
    local dir="$1" want="${2:-}" from="${3:-}"
    CHAIN_GAPS=0; CHAIN_LAST_EPOCH=0; CHAIN_LAST_NAME=""

    [ -d "$dir" ] || { bad "архива нет: $dir — точки возврата не существует"; return 1; }

    local names tli count
    # Только сегменты: .backup, .history и .partial — не звенья цепочки.
    names=$(find "$dir" -maxdepth 1 -name '????????????????????????.gz' -type f \
            | sed 's|.*/||; s|\.gz$||' | sort)
    count=$(printf '%s\n' "$names" | grep -c . || true)
    [ "$count" -gt 0 ] || { bad "в архиве нет ни одного сегмента: $dir"; return 1; }

    local lines
    lines=$(printf '%s\n' "$names" | cut -c1-8 | sort -u)
    if [ "$(printf '%s\n' "$lines" | grep -c .)" -gt 1 ]; then
        warn "в архиве несколько линий времени: $(printf '%s' "$lines" | tr '\n' ' ')"
        warn "после возврата ячейки линия меняется — снимите новую базовую копию"
    fi
    tli=$(printf '%s\n' "$lines" | tail -1)
    names=$(printf '%s\n' "$names" | grep "^$tli")

    # Начало цепочки — сегмент, с которого пойдёт накат базовой копии.
    # Разрыв РАНЬШЕ него не мешает: те сегменты уже никому не нужны, и валить
    # из-за них возможный возврат значит отказывать в восстановлении, которое
    # получилось бы. Зато отсутствие самого начального сегмента — отказ:
    # накатывать копию нечем.
    local from_idx=-1
    if [ -n "$from" ]; then
        from_idx=$(seg_index "$from")
        # Своя линия времени у базовой копии — отдельный разговор, и путать
        # его с разрывом нельзя: копия цела, архив цел, а накатить одно
        # на другое всё равно нельзя. Случается это после каждого настоящего
        # переключения ячейки на восстановленный кластер, то есть ровно
        # тогда, когда человек меньше всего готов разбирать невнятное
        # сообщение. Проверено репетицией переключения.
        if [ "${from:0:8}" != "$tli" ]; then
            bad "базовая копия снята на линии времени ${from:0:8}, а архив считается по $tli"
            bad "так бывает после переключения ячейки на восстановленный кластер:"
            bad "снимите новую базовую копию (ops/basebackup.sh) — прежняя к этому архиву не подходит"
            return 1
        fi
        if ! grep -q "^$from$" <<< "$names"; then
            bad "в архиве нет начального сегмента $from — базовую копию накатить нечем"
            return 1
        fi
        printf '    цепочка считается от %s\n' "$from"
    fi

    local prev=-1 prev_name="" first_gap_name="" first_gap_epoch=0 missing=0
    local name idx
    while read -r name; do
        [ -n "$name" ] || continue
        idx=$(seg_index "$name")
        [ "$idx" -lt "$from_idx" ] && continue
        if [ "$prev" -ge 0 ] && [ "$idx" -ne $((prev + 1)) ]; then
            CHAIN_GAPS=$((CHAIN_GAPS + 1))
            missing=$((idx - prev - 1))
            bad "РАЗРЫВ: после $prev_name нет $(seg_name "$tli" $((prev + 1))) (не хватает $missing)"
            if [ -z "$first_gap_name" ]; then
                first_gap_name="$prev_name"
                first_gap_epoch=$(mtime "$dir/$prev_name.gz")
            fi
        fi
        prev=$idx; prev_name="$name"
    done <<< "$names"

    CHAIN_LAST_NAME="$prev_name"
    CHAIN_LAST_EPOCH=$(mtime "$dir/$prev_name.gz")

    local reach_name="$CHAIN_LAST_NAME" reach_epoch="$CHAIN_LAST_EPOCH"
    if [ -n "$first_gap_name" ]; then
        reach_name="$first_gap_name"; reach_epoch="$first_gap_epoch"
    fi

    printf '    сегментов: %s, линия времени %s\n' "$count" "$tli"
    if [ "$CHAIN_GAPS" = 0 ]; then
        ok "разрывов нет; последний сегмент $CHAIN_LAST_NAME ($(human "$CHAIN_LAST_EPOCH"))"
    else
        # Главное сообщение всей задачи: не «архив повреждён», а «вернуться
        # можно вот до какого момента». Время — по архивации последнего
        # целого сегмента: точнее из архива не узнать, не накатив его.
        bad "вернуться можно НЕ ПОЗЖЕ $(human "$reach_epoch") (последний целый сегмент $reach_name)"
        bad "всё, что клиент наработал после этого, из архива не восстановить"
    fi

    if [ -n "$want" ]; then
        local want_epoch
        want_epoch=$(epoch_of "$want") || { bad "не разобрал момент: $want"; return 1; }
        if [ "$want_epoch" -gt "$reach_epoch" ]; then
            bad "запрошен $(human "$want_epoch") — архив до него не доходит"
            return 1
        fi
        ok "запрошенный момент $(human "$want_epoch") цепочкой покрыт"
    elif [ "$CHAIN_GAPS" != 0 ]; then
        return 1
    fi
    return 0
}

# --- Самопроверка -----------------------------------------------------------
#
# Сторож, который не краснеет на дефекте, хуже отсутствующего — и установить
# это можно только попыткой. Поэтому три случая: целая цепочка молчит,
# цепочка с дырой краснеет и называет момент, а момент ДО дыры по-прежнему
# достижим (разрыв в конце не отменяет возврата к раннему времени).
selftest() {
    local rc out
    # Каталог глобальной переменной, а не local: ловушка EXIT срабатывает
    # вне функции, и local-имя там уже не существует — при set -u это падение
    # с «unbound variable» вместо честного сообщения самопроверки.
    SELFTEST_TMP=$(mktemp -d)
    trap 'rm -rf "${SELFTEST_TMP:-}"' EXIT
    local tmp="$SELFTEST_TMP"

    local i name
    for i in 1 2 3 4 5; do
        name=$(seg_name 00000001 "$i")
        printf 'сегмент %s' "$i" | gzip -c > "$tmp/$name.gz"
        # Разные времена: сторож обязан называть момент последнего целого,
        # а не первого попавшегося.
        touch -t "2609121000.0$i" "$tmp/$name.gz"
    done
    # Посторонние файлы архива: на них сторож краснеть не должен.
    : > "$tmp/000000010000000000000003.00000028.backup"
    : > "$tmp/00000002.history"

    set +e
    out=$(chain_report "$tmp" 2>&1); rc=$?
    set -e
    [ "$rc" = 0 ] || { printf 'САМОПРОВЕРКА: целая цепочка объявлена рваной\n%s\n' "$out"; return 1; }
    grep -q 'разрывов нет' <<< "$out" || { printf 'САМОПРОВЕРКА: нет отчёта о целой цепочке\n%s\n' "$out"; return 1; }
    ok "целая цепочка из пяти сегментов принята"

    # Дыра посередине — тот самый случай из задачи 0083.
    rm "$tmp/$(seg_name 00000001 3).gz"
    set +e
    out=$(chain_report "$tmp" 2>&1); rc=$?
    set -e
    [ "$rc" != 0 ] || { printf 'САМОПРОВЕРКА: разрыв не пойман\n%s\n' "$out"; return 1; }
    grep -q 'РАЗРЫВ' <<< "$out" || { printf 'САМОПРОВЕРКА: разрыв не назван\n%s\n' "$out"; return 1; }
    grep -q 'НЕ ПОЗЖЕ' <<< "$out" || { printf 'САМОПРОВЕРКА: не назван доступный момент\n%s\n' "$out"; return 1; }
    grep -q "$(seg_name 00000001 2)" <<< "$out" \
        || { printf 'САМОПРОВЕРКА: назван не тот сегмент\n%s\n' "$out"; return 1; }
    ok "разрыв посередине пойман, момент назван"

    # Момент ПОСЛЕ дыры недостижим, момент ДО неё — достижим. Иначе сторож
    # запрещал бы возврат, который возможен, и авария кончалась бы отказом
    # вместо восстановления.
    set +e
    out=$(chain_report "$tmp" '2026-09-12 10:00:05' 2>&1); rc=$?
    set -e
    [ "$rc" != 0 ] || { printf 'САМОПРОВЕРКА: момент за разрывом объявлен достижимым\n%s\n' "$out"; return 1; }
    set +e
    out=$(chain_report "$tmp" '2026-09-12 10:00:01' 2>&1); rc=$?
    set -e
    [ "$rc" = 0 ] || { printf 'САМОПРОВЕРКА: момент до разрыва объявлен недостижимым\n%s\n' "$out"; return 1; }
    ok "момент до разрыва достижим, за разрывом — нет"

    # Разрыв РАНЬШЕ начала наката не должен запрещать возврат: те сегменты
    # базовой копии не нужны. Сторож, краснеющий здесь, отказывал бы
    # в восстановлении, которое получилось бы, — а это хуже, чем отсутствие
    # сторожа: в аварию его первым делом отключат, вместе с настоящей защитой.
    set +e
    out=$(chain_report "$tmp" "" "$(seg_name 00000001 4)" 2>&1); rc=$?
    set -e
    [ "$rc" = 0 ] || { printf 'САМОПРОВЕРКА: разрыв до начала наката запретил возврат\n%s\n' "$out"; return 1; }
    ok "разрыв раньше начального сегмента возврат не запрещает"

    # А отсутствие самого начального сегмента — запрещает: накатывать нечем.
    set +e
    out=$(chain_report "$tmp" "" "$(seg_name 00000001 3)" 2>&1); rc=$?
    set -e
    [ "$rc" != 0 ] || { printf 'САМОПРОВЕРКА: пропало начало наката, а сторож молчит\n%s\n' "$out"; return 1; }
    ok "пропавший начальный сегмент пойман"

    rm -rf "$tmp"; trap - EXIT
    printf '\n\033[1;32mСторож архива WAL проверен на себе.\033[0m\n'
}

if [ "$MODE" = "самопроверка" ]; then
    selftest
    exit 0
fi

# --- Окружение --------------------------------------------------------------
ENV_FILE="${ENV_FILE:-.env}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

BACKUPS="${BACKUP_DIR:-./backups/${APP_CELL:-cell01}}"
WAL_DIR="${WAL_ARCHIVE_DIR:-$BACKUPS/wal}"

if [ "$MODE" = "цепочка" ]; then
    printf 'Архив: %s\n' "$WAL_DIR"
    chain_report "$WAL_DIR" "${1:-}" "${2:-}"
    exit $?
fi

# --- Проверка для крона -----------------------------------------------------
#
# Замок на один прогон: задача стоит каждые пять минут, а офсайт архива
# на медленном канале идёт дольше — без замка прогоны наложатся, и rclone
# будет догонять сам себя, не доводя ни одного. mkdir, а не flock: он есть
# и на хосте ячейки, и на машине разработчика.
#
# Выход НУЛЁМ: «прошлый ещё идёт» — не отказ, и письмо от cron каждые пять
# минут научит не читать письма от cron.
LOCK="${TMPDIR:-/tmp}/partsflow-wal-${APP_CELL:-cell01}.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    printf 'Прошлый прогон ещё идёт (%s) — пропускаю.\n' "$LOCK"
    exit 0
fi
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

COMPOSE="docker compose -f docker-compose.prod.yml --env-file $ENV_FILE"
DB_USER="${DB_USER:?укажите DB_USER}"
PUSH="${PUSHGATEWAY_URL:-http://localhost:9091}/metrics/job/wal"

# Отметки уезжают одним куском: разными запросами pushgateway затирает
# предыдущие значения той же задачи, и в наблюдении оставалось бы одно
# последнее число.
METRICS=""
metric() { METRICS="$METRICS$1 $2"$'\n'; }

RC=0

step "Цепочка архива"
if chain_report "$WAL_DIR"; then
    metric partsflow_wal_archive_gaps 0
else
    metric partsflow_wal_archive_gaps "${CHAIN_GAPS:-1}"
    RC=1
fi

step "Архиватор базы"
# Спрашиваем саму базу, а не каталог: неудачи архивации видны только ей,
# и «в архиве всё подряд» ещё не значит «архиватор работает» — он мог
# встать минуту назад, и сегменты копятся в pg_wal.
if STAT=$($COMPOSE exec -T postgres psql -U "$DB_USER" -d parts -tAqc \
        "SELECT failed_count
              ||' '|| COALESCE(EXTRACT(EPOCH FROM last_archived_time)::bigint, 0)
              ||' '|| (SELECT count(*) FROM pg_ls_dir('pg_wal/archive_status') f
                        WHERE f LIKE '%.ready')
           FROM pg_stat_archiver" 2>/dev/null)
then
    set -- $STAT
    FAILED="$1"; LAST_ARCHIVED="$2"; PENDING="$3"
    metric partsflow_wal_archive_failed_count "$FAILED"
    metric partsflow_wal_archive_pending "$PENDING"
    [ "$LAST_ARCHIVED" != 0 ] && \
        metric partsflow_wal_archive_success_timestamp_seconds "$LAST_ARCHIVED"
    if [ "$PENDING" -gt 5 ]; then
        bad "в очереди на архивацию $PENDING сегментов — архиватор не справляется"
        RC=1
    else
        ok "в очереди $PENDING, отказов за жизнь базы $FAILED"
    fi
    [ "$LAST_ARCHIVED" != 0 ] && ok "последний сегмент уехал в архив $(human "$LAST_ARCHIVED")"
else
    bad "база не ответила — проверить архиватор нечем"
    RC=1
fi

step "Офсайт архива"
# Отдельно от ночного офсайта дампов, и это не дублирование: архив, уезжающий
# раз в сутки, даёт при гибели диска ту же потерянную смену, ради которой всё
# и делалось. Дампы датированы и за день не меняются, сегменты появляются
# каждые пять минут.
if [ -z "${OFFSITE_REMOTE:-}" ]; then
    warn "OFFSITE_REMOTE не задан — архив остаётся на диске ячейки"
    warn "гибель диска уносит и базу, и её точку возврата"
elif ! command -v rclone > /dev/null; then
    bad "OFFSITE_REMOTE задан, а rclone не установлен"
    RC=1
else
    rclone copy "$WAL_DIR" "$OFFSITE_REMOTE/wal" --transfers 8 --quiet
    # «Уехало» проверяется присутствием последнего сегмента на той стороне:
    # rclone отдаёт ноль и когда копировать было нечего.
    if [ -n "${CHAIN_LAST_NAME:-}" ] && \
       rclone lsf "$OFFSITE_REMOTE/wal" 2>/dev/null | grep -q "^$CHAIN_LAST_NAME.gz$"; then
        ok "архив уехал наружу: $OFFSITE_REMOTE/wal"
        metric partsflow_wal_offsite_success_timestamp_seconds "$(date -u +%s)"
    else
        bad "офсайт архива не подтверждён: $CHAIN_LAST_NAME.gz на той стороне нет"
        RC=1
    fi
fi

# Отметка о самом прогоне — последней, и она означает «сторож ходил»,
# а не «всё хорошо»: absent() по ней ловит ячейку, где эту задачу забыли
# поставить в cron. Состояние архива несут отдельные числа.
metric partsflow_wal_check_timestamp_seconds "$(date -u +%s)"

if ! printf '%s' "$METRICS" | curl -sf --max-time 10 --data-binary @- "$PUSH" > /dev/null; then
    warn "отметки в наблюдение не ушли — проверьте pushgateway"
fi

exit "$RC"
