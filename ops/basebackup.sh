#!/usr/bin/env bash
# Базовая копия кластера — то, от чего накатывается архив WAL.
#
#   ops/basebackup.sh [файл-окружения]
#   ops/basebackup.sh --selftest    # проверка самих проверок, без docker
#
# Зачем она рядом с дампами по схемам. Дамп отвечает на вопрос «вернуть
# одного клиента», и ради этого выбрана схема-на-арендатора. Но дамп — снимок
# на момент снятия, и догнать его до «минуту назад» нечем: журнал WAL пишется
# кластером целиком, накатывается на физическую копию и ни про какие схемы
# не знает. Поэтому механизма два:
#
#   ops/backup.sh      — ночные дампы по схемам: вернуть ОДНОГО клиента;
#   ops/basebackup.sh  — физическая копия кластера: основание для точки
#                        возврата на любой момент, покрытый архивом WAL.
#
# Заменить одно другим нельзя. Без базовой копии архив WAL не накатывается
# ни на что — это журнал изменений без начального состояния, то есть ноль
# пользы при полном виде защищённости. Без дампов возврат одного клиента
# означает поднять всех, то есть остановить ячейку.
#
# Раз в неделю, а не ночью: копия физическая, размером со всю базу, и её
# снятие читает весь кластер. Точку возврата это не грубит — архив WAL
# догоняет от любой базовой копии, лишь бы в нём не было разрыва; частота
# базовой копии задаёт только ДЛИНУ наката (а с ней — время восстановления),
# и раз в неделю это часы WAL, а не дни.
#
# Файл окружения выбирается переменной ENV_FILE — одинаково у всех скриптов
# ops/.
set -euo pipefail
cd "$(dirname "$0")/.."

# Разбор флагов (задача 0237): прежде `--selftest` или `--apply` молча
# становились ИМЕНЕМ файла окружения, и шло настоящее снятие физической копии.
# Образец — ops/restore-pitr.sh.
SELFTEST=0
case "${1:-}" in
    --selftest) SELFTEST=1; shift ;;
    -*) printf 'Неизвестный флаг: %s\nИспользование: %s [файл-окружения] | --selftest\n' "$1" "$0" >&2
        exit 2 ;;
esac
if [ "$#" -gt 1 ]; then
    printf 'Лишние аргументы: %s\nИспользование: %s [файл-окружения] | --selftest\n' "$*" "$0" >&2
    exit 2
fi

# Проверки по следу: размер копии, граница и итог уборки архива WAL.
. ops/backup-checks.sh

# --- проверка самих проверок (задача 0237) -----------------------------------
# Без docker и ячейки: архив WAL — временный каталог с пустыми файлами нужных
# имён, а `pg_archivecleanup` изображает функция, убирающая имена ниже границы.
# Каждый случай утверждает КРАСНОЕ на подделке, а не только зелёное на здоровом.
SELF="$0"
selftest() {
    local bad=0 out rc d
    tmp="$(mktemp -d)"
    # FAKE не local: ловушка EXIT срабатывает, когда функции уже нет (урок 0151).
    FAKE=""
    trap 'rm -rf "${tmp:-}"; rm -f "${FAKE:-}"' EXIT
    echo "Самопроверка ops/basebackup.sh"

    pass() { printf '  ✓ %s\n' "$1"; }
    flunk() { printf '\033[1;31m  ✗ %s\033[0m\n' "$1"; bad=1; }
    says() { printf '%s' "$1" | grep -qF -- "$2"; }

    # Имена сегментов: линия времени 1, логический файл 0, номера 01..0A.
    S1=000000010000000000000001; S3=000000010000000000000003
    S5=000000010000000000000005; S9=000000010000000000000009
    mkwal() {   # $1 — каталог, остальное — номера сегментов
        local dir="$1" n; shift
        rm -rf "$dir"; mkdir -p "$dir"
        for n in "$@"; do : > "$dir/$(printf '00000001%08X%08X' 0 "$n").gz"; done
        : > "$dir/00000001.history"                          # файлы истории в счёт не идут
        : > "$dir/000000010000000000000003.00000028.backup.gz"
    }
    # Изображает pg_archivecleanup -x .gz: убирает сегменты ниже границы.
    fake_cleanup() {   # $1 — каталог, $2 — граница
        local f n
        for f in "$1"/*.gz; do
            n=$(basename "$f" .gz)
            printf '%s' "$n" | grep -Eq '^[0-9A-F]{24}$' || continue
            if wal_lt "$n" "$2"; then rm -f "$f"; fi
        done
    }

    # 1. Размер: пустой файл, обрывок и «заголовок gzip» краснеют; пустой
    #    кластер (3 666 141 байт, замер 15 октября 2026) и больше — нет.
    if base_size_check 3666141 && base_size_check 398000000; then
        pass "копия пустого кластера (3,6 МБ) и большая проходят"
    else flunk "здоровый размер отбит: $BASE_WHY"; fi
    for d in 0 20 1048575; do
        if ! base_size_check "$d" && says "$BASE_WHY" 'меньше'; then
            pass "копия в $d байт — красная"
        else flunk "копия в $d байт обязана краснеть (получено: $BASE_WHY)"; fi
    done
    if ! base_size_check "" && says "$BASE_WHY" 'не прочитан'; then
        pass "размер не прочитан — красный, а не «0 = всё хорошо»"
    else flunk "непрочитанный размер обязан краснеть (получено: $BASE_WHY)"; fi

    # 2. Граница ДО уборки: выше начала хранимой копии — красная; равная и
    #    ниже — зелёная; не имя сегмента — красная.
    if wal_boundary_check "$S3" "$S3" "$S5" && wal_boundary_check "$S1" "$S3" "$S5"; then
        pass "граница, равная началу старейшей копии, и ниже неё — проходят"
    else flunk "здоровая граница отбита: $WAL_WHY"; fi
    if ! wal_boundary_check "$S5" "$S3" "$S5" && says "$WAL_WHY" 'выше начала хранимой копии'; then
        pass "граница выше начала копии — красная: уборка снесла бы нужное"
    else flunk "граница выше начала копии обязана краснеть (получено: $WAL_WHY)"; fi
    if ! wal_boundary_check "" "$S3" && ! wal_boundary_check "garbage" "$S3"; then
        pass "пустая и не-сегментная граница — красные"
    else flunk "мусорная граница обязана краснеть"; fi

    # 3. Итог уборки по множествам. Здоровая — молчит (обратный край).
    mkwal "$tmp/w" 1 2 3 4 5 6; B=$(wal_names "$tmp/w")
    fake_cleanup "$tmp/w" "$S3"; A=$(wal_names "$tmp/w")
    if wal_after_check "$S3" "$B" "$A"; then
        pass "здоровая уборка по границе — молчит"
    else flunk "здоровая уборка отбита: $WAL_WHY"; fi
    # Пока шла уборка, база положила новый сегмент: после БОЛЬШЕ, чем до.
    mkwal "$tmp/w" 1 2 3; B=$(wal_names "$tmp/w")
    fake_cleanup "$tmp/w" "$S3"; : > "$tmp/w/00000001$(printf '%08X%08X' 0 9).gz"; A=$(wal_names "$tmp/w")
    if wal_after_check "$S3" "$B" "$A"; then
        pass "новый сегмент, пришедший во время уборки, не краснит (после > до)"
    else flunk "законный приход сегмента во время уборки отбит: $WAL_WHY"; fi
    # Подделка: убрано больше границы — граница, заявленная проверке, 3,
    # а «уборка» отработала по 5.
    mkwal "$tmp/w" 1 2 3 4 5 6; B=$(wal_names "$tmp/w")
    fake_cleanup "$tmp/w" "$S5"; A=$(wal_names "$tmp/w")
    if ! wal_after_check "$S3" "$B" "$A" && says "$WAL_WHY" 'убрано нужное'; then
        pass "убран нужный сегмент — красный: $WAL_WHY"
    else flunk "убранный нужный сегмент обязан краснеть (получено: $WAL_WHY)"; fi
    # Подделка: уборка ничего не убрала (права, том только на чтение).
    mkwal "$tmp/w" 1 2 3 4 5 6; B=$(wal_names "$tmp/w"); A="$B"
    if ! wal_after_check "$S3" "$B" "$A" && says "$WAL_WHY" 'уборка не сработала'; then
        pass "ниже границы осталось — красный: $WAL_WHY"
    else flunk "несработавшая уборка обязана краснеть (получено: $WAL_WHY)"; fi
    # Подделка: архив опустел вовсе.
    mkwal "$tmp/w" 1 2 3; B=$(wal_names "$tmp/w"); rm -f "$tmp"/w/*.gz; A=$(wal_names "$tmp/w")
    if ! wal_after_check "$S9" "$B" "$A" && says "$WAL_WHY" 'пуст'; then
        pass "архив опустел — красный"
    else flunk "опустевший архив обязан краснеть (получено: $WAL_WHY)"; fi

    # 4. Чужой флаг отбивается словами и кодом 2, а не становится именем файла.
    for d in --apply --dry-run -x; do
        set +e; out=$(ENV_FILE=/dev/null bash "$SELF" "$d" 2>&1); rc=$?; set -e
        if [ "$rc" = 2 ] && says "$out" 'Неизвестный флаг'; then
            pass "$d отбит: код 2, слова"
        else flunk "$d обязан давать код 2 и «Неизвестный флаг» (код $rc): $(printf '%s' "$out" | head -1)"; fi
    done
    set +e; out=$(ENV_FILE=/dev/null bash "$SELF" a b 2>&1); rc=$?; set -e
    if [ "$rc" = 2 ] && says "$out" 'Лишние аргументы'; then
        pass "лишние аргументы отбиты: код 2"
    else flunk "два аргумента обязаны давать код 2 (код $rc)"; fi

    # 5. Проводка: проверки ЗВАНЫ. Рядом возврат дефекта — копия скрипта без
    #    вызова обязана валить свою самопроверку. Копия живёт в .scratch/
    #    (единственное место подделок, docs/agent-workflow.md) на той же глубине,
    #    что ops/: `cd "$(dirname "$0")/.."` уводит её в корень репозитория.
    if [ "${BASEBACKUP_SELFTEST_CHILD:-}" != 1 ]; then
        plant() {   # $1 — что заменить, $2 — на что
            mkdir -p .scratch
            FAKE=".scratch/basebackup-fake.sh"
            python3 - "$SELF" "$FAKE" "$1" "$2" <<'PY'
import sys
src, dst, old, new = sys.argv[1:5]
t = open(src, encoding="utf-8").read()
# Подделка ложится только в код скрипта вне самопроверки: тексты, которые она
# ищет, стоят и внутри самой самопроверки, и первая замена попала бы туда.
m1 = "\n" + "# --- проверка самих проверок"
m2 = "\n" + "if [ \"$SELFTEST\" = 1 ]; then selftest"
a, b = t.index(m1), t.index(m2)
pre, mid, post = t[:a], t[a:b], t[b:]
if old in pre:
    pre = pre.replace(old, new, 1)
else:
    assert old in post, "подделка не легла — изменилось место: " + old[:60]
    post = post.replace(old, new, 1)
open(dst, "w", encoding="utf-8").write(pre + mid + post)
PY
            bash -n "$FAKE"
        }
        forge() {   # $1 — название, $2 — что заменить, $3 — на что, $4 — слово в отказе
            plant "$2" "$3"
            set +e
            BASEBACKUP_SELFTEST_CHILD=1 ENV_FILE=/dev/null bash "$FAKE" --selftest >"$tmp/fake.log" 2>&1
            rc=$?
            set -e
            if [ "$rc" != 0 ] && grep -qF -- "$4" "$tmp/fake.log"; then
                pass "подделка «$1» валит самопроверку"
            else
                flunk "подделка «$1» не уронила самопроверку (код $rc, ждали «$4»)"
                sed 's/^/      /' "$tmp/fake.log" | tail -5
            fi
        }
        forge "размер копии не проверяется" \
            'base_size_check "$SIZE_BYTES" || fail "base.tar.gz негодна: $BASE_WHY"' 'true' \
            'не зовёт base_size_check'
        forge "граница уборки не утверждается" \
            'wal_boundary_check "$OLDEST_WAL" $KEPT_STARTS || fail "граница уборки неверна: $WAL_WHY"' 'true' \
            'не зовёт wal_boundary_check'
        forge "итог уборки только печатается" \
            'wal_after_check "$OLDEST_WAL" "$NAMES_BEFORE" "$NAMES_AFTER" || fail "уборка архива WAL: $WAL_WHY"' 'true' \
            'не зовёт wal_after_check'
        forge "флаг снова становится именем файла" \
            '-*) printf '"'"'Неизвестный флаг' '--zzz) printf '"'"'Неизвестный флаг' \
            'обязан давать код 2'
    fi

    # 6. Проводка в самом скрипте: нужные вызовы стоят — и ДО удаления, а не
    #    после. Смотрим на тело после самопроверки: внутри неё те же строки
    #    стоят образцами и нашлись бы всегда.
    BODY=$(sed -n '/^if \[ "\$SELFTEST" = 1 \]/,$p' "$SELF")
    if printf '%s' "$BODY" | grep -qF 'base_size_check "$SIZE_BYTES"'; then
        pass "размер копии проверяется числом"
    else flunk "ops/basebackup.sh не зовёт base_size_check"; fi
    if printf '%s' "$BODY" | grep -qF 'wal_boundary_check "$OLDEST_WAL"'; then
        pass "граница уборки утверждается"
    else flunk "ops/basebackup.sh не зовёт wal_boundary_check"; fi
    if printf '%s' "$BODY" | grep -qF 'wal_after_check "$OLDEST_WAL"'; then
        pass "итог уборки утверждается"
    else flunk "ops/basebackup.sh не зовёт wal_after_check"; fi
    pre=$(printf '%s' "$BODY" | grep -nF 'wal_boundary_check "$OLDEST_WAL"' | head -1 | cut -d: -f1)
    cut_at=$(printf '%s' "$BODY" | grep -nF 'pg_archivecleanup -x' | head -1 | cut -d: -f1)
    if [ -n "$pre" ] && [ -n "$cut_at" ] && [ "$pre" -lt "$cut_at" ]; then
        pass "граница проверяется ДО удаления"
    else flunk "проверка границы обязана стоять до pg_archivecleanup"; fi

    if [ "$bad" != 0 ]; then
        printf '\033[1;31mСамопроверка ops/basebackup.sh: ЕСТЬ ОШИБКИ\033[0m\n'
        return 1
    fi
    printf '\033[1;32mСамопроверка ops/basebackup.sh: всё сошлось\033[0m\n'
}
if [ "$SELFTEST" = 1 ]; then selftest; exit $?; fi

ENV_FILE="${1:-${ENV_FILE:-.env}}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

COMPOSE="docker compose -f docker-compose.prod.yml --env-file $ENV_FILE"
DB_USER="${DB_USER:?укажите DB_USER}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUPS="${BACKUP_DIR:-./backups/${APP_CELL:-cell01}}"
WAL_DIR="${WAL_ARCHIVE_DIR:-$BACKUPS/wal}"
BASES="$BACKUPS/base"
OUT="$BASES/$STAMP"
# Сколько базовых копий держим. Две, а не одна: копия, на которой оборвалось
# снятие или которая не разворачивается, оставляет ячейку без точки возврата
# вовсе — а узнают об этом в аварию. Глубина архива WAL считается от САМОЙ
# СТАРОЙ хранимой копии, поэтому число задаёт и окно возврата, и место
# на диске.
KEEP="${BASEBACKUP_KEEP:-2}"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    %s\033[0m\n' "$1"; }
warn() { printf '\033[1;33m    %s\033[0m\n' "$1"; }
fail() { printf '\033[1;31m    ОШИБКА: %s\033[0m\n' "$1"; exit 1; }

# Служебная база, а не «parts»: всё, что здесь спрашивается, — про кластер
# (архивация, сегмент WAL), а имя рабочей базы с задачи 0112 меняется
# с каждой выкладкой, и «parts» после второй выкладки копией не существует.
psql_() { $COMPOSE exec -T postgres psql -U "$DB_USER" -d postgres -tAqc "$1"; }

mkdir -p "$OUT"
# Незаконченная копия не должна остаться в каталоге выглядящей годной:
# восстановление возьмёт самую свежую, а в ней обрезанный tar.
trap '[ -f "$OUT/READY" ] || { printf "\033[1;31mКопия не снялась, убираю %s\033[0m\n" "$OUT"; rm -rf "$OUT"; }' EXIT

step "Архивация WAL включена?"
# Без неё физическая копия бессмысленна: догонять её будет нечем, а выглядит
# она при этом так же. Проверяем у самой базы, а не по compose: настройку
# могли переопределить на месте.
ARCHIVE_MODE=$(psql_ "SHOW archive_mode")
[ "$ARCHIVE_MODE" = "on" ] || fail "archive_mode=$ARCHIVE_MODE — архива WAL нет, точка возврата не появится"
ok "archive_mode=on"

step "С какого сегмента WAL понадобится накат"
# Записываем ДО снятия: настоящий backup_start_lsn будет не раньше этого
# момента, значит сегмент не позже. Ошибка в эту сторону безопасна — лишний
# сегмент в архиве, — а в обратную означала бы уборку того, без чего копия
# не накатывается.
START_WAL=$(psql_ "SELECT pg_walfile_name(pg_current_wal_lsn())")
printf '%s\n' "$START_WAL" > "$OUT/START_WAL"
ok "$START_WAL"

step "Физическая копия кластера"
STARTED=$(date +%s)
# -Ft -D - : поток tar в stdout, дальше сжатие на хосте. Через контейнер
# копия уезжает прямо в каталог бэкапов — монтировать его в базу незачем.
# -X fetch: нужные копии сегменты кладутся внутрь tar, и копия разворачивается
# даже если архив успели подрезать.
# --checkpoint=fast: иначе pg_basebackup ждёт естественной контрольной точки
# и стоит до пяти минут, ничего не делая.
$COMPOSE exec -T postgres pg_basebackup -U "$DB_USER" -D - -Ft -X fetch \
    --checkpoint=fast --no-password | gzip -1 > "$OUT/base.tar.gz"
SIZE=$(du -h "$OUT/base.tar.gz" | cut -f1)
SIZE_BYTES=$(wc -c < "$OUT/base.tar.gz" | tr -d ' ')
ok "base.tar.gz — $SIZE, $(( $(date +%s) - STARTED )) с"

step "Копия не пустая?"
# Нижняя граница названа числом и обоснована в ops/backup-checks.sh. Отказ не
# оставляет копию: READY ещё не стоит, ловушка выше уберёт каталог.
base_size_check "$SIZE_BYTES" || fail "base.tar.gz негодна: $BASE_WHY"
ok "$SIZE_BYTES байт (граница $BASE_MIN_BYTES)"

step "Копия читается целиком?"
# Именно целиком: обрезанный на середине tar открывается и распаковывается
# до места обрыва, то есть выглядит как копия. Стоит это чтения всего файла,
# и это единственное, что отличает «файл есть» от «копия есть» до того дня,
# когда из неё надо подняться.
gzip -t "$OUT/base.tar.gz" || fail "base.tar.gz не читается — копия негодна"
ok "поток целый"

{
    echo "снято: $STAMP"
    echo "начальный сегмент WAL: $START_WAL"
    echo "размер: $SIZE"
    echo "разворот: ops/restore-pitr.sh --to 'ГГГГ-ММ-ДД ЧЧ:ММ:СС ПОЯС'  (пояс обязателен: MSK, UTC, +03:00, local)"
} > "$OUT/manifest.txt"
: > "$OUT/READY"

step "Старые базовые копии (держим $KEEP)"
# Считаем от самой старой ХРАНИМОЙ копии — она и задаёт, как далеко назад
# можно вернуться.
# Без mapfile: на машине разработчика bash 3.2, и скрипт обязан работать
# и там — разбирают аварию тем, что под рукой.
bases() { find "$BASES" -maxdepth 1 -type d -name '20*' | sort; }
TOTAL=$(bases | grep -c . || true)
if [ "$TOTAL" -gt "$KEEP" ]; then
    bases | head -n "$(( TOTAL - KEEP ))" | while read -r old; do
        rm -rf "$old"
        ok "убрана $old"
    done
fi
OLDEST=$(bases | head -1)
ok "копий: $(bases | grep -c . || true), самая старая $(basename "$OLDEST")"

# Копия с ЧУЖОЙ линии времени к этому архиву уже не подходит: после
# переключения ячейки на восстановленный кластер линия меняется, и накатить
# новые сегменты на старую копию нельзя. Молчать тут нельзя — «окно возврата
# от 4 сентября» читалось бы как обещание, которого нет. Поймано репетицией
# переключения, а не рассуждением.
WINDOW_FROM="$OLDEST"
while [ -n "$WINDOW_FROM" ] && [ "$(cut -c1-8 "$WINDOW_FROM/START_WAL")" != "${START_WAL:0:8}" ]; do
    warn "копия $(basename "$WINDOW_FROM") снята на линии времени $(cut -c1-8 "$WINDOW_FROM/START_WAL") — к текущему архиву не подходит"
    WINDOW_FROM=$(bases | awk -v cur="$WINDOW_FROM" 'p==1 {print; exit} $0==cur {p=1}')
done
[ -n "$WINDOW_FROM" ] || WINDOW_FROM="$OUT"

step "Архив WAL доступен пользователю ячейки?"
# Нужны ЧТЕНИЕ и ВХОД, и нужны они не самой уборке, а ЭТОМУ СКРИПТУ.
# Разобрано прогоном (разбор PR #251, 19 сентября 2026) — первая редакция
# этой проверки объясняла себя неверно, хотя краснела в нужную минуту.
#
#   • `pg_archivecleanup` ниже идёт `docker compose exec` ВНУТРЬ контейнера,
#     а у образа postgres:16-alpine `Config.User` пуст — exec заходит
#     root'ом, и права хоста ему не указ. Проверено: на каталоге 750
#     `postgres:postgres` удаление сегмента прошло с кодом 0. То есть
#     автоматическая уборка от прав группы не зависела никогда.
#   • Ломалось другое: строка `BEFORE=$(find …)` на нечитаемом каталоге
#     даёт код 1, и `set -euo pipefail` роняет скрипт ЗДЕСЬ — до вызова
#     очистки. Отсюда «Permission denied» на живой ячейке и невыполненная
#     уборка: не потому, что удалять нечем, а потому, что до удаления
#     не дошли.
#
# Поэтому чтение и вход — отказ, а запись — предупреждение: без неё
# автоматическая уборка работает, а дежурный руками (снять застрявший
# `.tmp`, подрезать архив) — нет. Красное на работающей ячейке отключают
# первым делом, вместе с настоящей защитой.
#
# Отказ громкий и с ненулевым кодом, хотя копия уже снята и лежит годной:
# до уборки скрипт всё равно не дойдёт, а архив, который перестали
# подрезать, растёт до конца диска (96 ГБ) — кончившийся диск
# останавливает запись в базу. Отметка в наблюдение при этом не ставится
# намеренно: «бэкап есть, но точка возврата зарастает» это не успех.
NEDOSTAET=""
[ -r "$WAL_DIR" ] || NEDOSTAET="$NEDOSTAET чтение;"
[ -x "$WAL_DIR" ] || NEDOSTAET="$NEDOSTAET вход;"
if [ -n "$NEDOSTAET" ]; then
    warn "$(ls -ld "$WAL_DIR" 2>/dev/null || printf 'права не прочитать')"
    warn "поднимите wal-archive-init (docker compose up wal-archive-init);"
    warn "группа задаётся WAL_ARCHIVE_GID в .env — группа пользователя ячейки (id -g)"
    fail "каталогу архива $WAL_DIR не хватает прав:$NEDOSTAET — до уборки скрипт не дойдёт"
fi
if [ ! -w "$WAL_DIR" ]; then
    warn "в каталог архива нельзя писать: сама уборка ниже от этого не пострадает"
    warn "(она идёт root'ом внутри контейнера), а руками — снять застрявший .tmp,"
    warn "подрезать архив — дежурный не сможет"
fi
ok "читается и входит; уборка ниже идёт внутри контейнера и прав хоста не спрашивает"

step "Архив WAL: убираем то, что уже не нужно ни одной копии"
# Единственное безопасное правило: не старше начального сегмента самой старой
# хранимой копии. Убрать больше — оставить копию без наката, то есть без
# точки возврата; убрать меньше — забить диск (сегмент каждые пять минут,
# и диск на ячейке кончается раньше памяти).
OLDEST_WAL=$(cat "$OLDEST/START_WAL")
# Граница утверждается ДО удаления (задача 0237): уборка «не туда» — это
# снесённая точка возврата всех арендаторов ячейки, и после неё возвращать
# нечего. Начала берутся у всех хранимых копий, а не у одной.
KEPT_STARTS=$(bases | while read -r b; do cat "$b/START_WAL"; done)
# shellcheck disable=SC2086  # список имён сегментов без пробелов
wal_boundary_check "$OLDEST_WAL" $KEPT_STARTS || fail "граница уборки неверна: $WAL_WHY"
NAMES_BEFORE=$(wal_names "$WAL_DIR")
BEFORE=$(find "$WAL_DIR" -maxdepth 1 -type f -name '*.gz' | wc -l | tr -d ' ')
$COMPOSE exec -T postgres pg_archivecleanup -x .gz /wal-archive "$OLDEST_WAL"
AFTER=$(find "$WAL_DIR" -maxdepth 1 -type f -name '*.gz' | wc -l | tr -d ' ')
NAMES_AFTER=$(wal_names "$WAL_DIR")
# ПОСЛЕ: нужное на месте, лишнего ниже границы нет. Число «было/стало» не
# сравнивается — пока идёт уборка, база кладёт новый сегмент (ops/backup-checks.sh).
wal_after_check "$OLDEST_WAL" "$NAMES_BEFORE" "$NAMES_AFTER" || fail "уборка архива WAL: $WAL_WHY"
ok "сегментов было $BEFORE, осталось $AFTER (граница $OLDEST_WAL; нужное на месте, ниже границы пусто)"

# Отметка ПОСЛЕ всех шагов: поставленная раньше, она означала бы
# «запускался», а знать надо «получилось». Своя, а не общая с дампами:
# дампы могут сниматься исправно при мёртвой базовой копии, и тогда точки
# возврата нет, а тревога молчит.
if ! printf '# TYPE partsflow_basebackup_success_timestamp_seconds gauge\npartsflow_basebackup_success_timestamp_seconds %s\n' \
        "$(date -u +%s)" \
    | curl -sf --max-time 10 --data-binary @- \
        "${PUSHGATEWAY_URL:-http://localhost:9091}/metrics/job/backup" > /dev/null
then
    warn "отметка в наблюдение не ушла — проверьте pushgateway"
fi

printf '\n\033[1;32mБазовая копия снята: %s\033[0m\n' "$OUT"
printf 'Окно возврата: от %s (копия %s) до последнего сегмента в архиве.\n' \
    "$(cat "$WINDOW_FROM/START_WAL")" "$(basename "$WINDOW_FROM")"
printf 'Проверить цепочку: ops/wal-archive.sh --chain\n'
printf 'Вернуться к моменту: ops/restore-pitr.sh --to "ГГГГ-ММ-ДД ЧЧ:ММ:СС ПОЯС"\n'
printf 'Часовой пояс обязателен — MSK, UTC, +03:00 или local (пояс этой машины).\n'
