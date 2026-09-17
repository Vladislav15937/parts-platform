#!/usr/bin/env bash
# Выкладка на стенд одной командой: база едет вместе со сборкой — всё новое
# или ничего.
#
#   ops/deploy.sh --tag <SHA>                          обычная выкладка
#   ops/deploy.sh --tag <SHA> --migrations не-трогать  копия без наката схемы
#   ops/deploy.sh --tag <SHA> --migrations только      привести схему НА МЕСТЕ и выйти
#   ops/deploy.sh --tag <SHA> --migrate-with none      база новее образа (откат приложения)
#   ops/deploy.sh --abort                              доделать откат оборвавшейся выкладки
#   ops/deploy.sh --tag <SHA> --dry-run                напечатать шаги, ничего не делая
#   ops/deploy.sh --selftest                           проверка самого порядка шагов
#
# Зовут её кнопкой в браузере (.github/workflows/deploy.yml) — сюда она
# приезжает по ssh с уже посчитанным планом. Руками на стенде работает так же.
#
# РЕШЕНИЕ ВЛАДЕЛЬЦА от 14 сентября 2026 (задача 0112), дословно: «база всегда
# соответствует сборке которая едет на деплой, а если что то пошло не так
# то всё откатывается. То есть у нас должна быть атомарность по принципу всё
# катим новое или ничего». И уточнение того же дня: откатывается только
# приложение, база чинится вперёд. Отсюда шаги:
#
#   1. заморозить запись в базе работающей сборки   (default_transaction_read_only)
#   2. снять с неё копию                              CREATE DATABASE … TEMPLATE
#   3. привести схему копии к версии образа           ops/schema-sync.sh
#   4. поднять новую сборку РЯДОМ, уже на копии
#   5. дождаться готовности                           /actuator/readiness
#   6. заморозить и копию — на время переключения
#   7. перевести трафик                               ops/switch-build.sh
#   8. пять самопроверок                              ops/deploy-checks.sh
#   9. ТОЧКА НЕВОЗВРАТА: открыть запись в копии, погасить старую сборку
#
# Упавший шаг 2–8 возвращает всё: трафик — на старую сборку, копию — удаляет,
# запись в старой базе — открывает. Старая база за это время не менялась
# вовсе: запись в неё заморожена, а схему накатывали на копию.
#
# ЗАМОРОЗКА — ЦЕНА «НИЧЕГО НЕ ПОТЕРЯНО». Копию снимают в момент T, а старая
# сборка продолжает принимать работу: переключись мы на копию без заморозки,
# всё записанное после T исчезло бы молча — база цела, сходится сама с собой,
# просто в ней нет вечера. Поэтому с шага 1 запись не принимает никто: чтение
# работает, запись отвечает 503 «идёт обновление», офлайн-очередь приёмки
# повторяет сама. Копия замораживается тоже (шаг 6) — затем, чтобы запись
# не легла в базу, которую самопроверки ещё могут велеть удалить.
# Замораживает база, а не приложение: забытый в коде путь записи заморозку
# не обходит, а громко падает.
#
# ОТКАТ ПОСЛЕ ТОЧКИ НЕВОЗВРАТА — это выкладка прежнего образа тем же путём,
# без изменения схемы (--migrate-with none: база новее приложения — нормальное
# состояние, совместимость держит сторож 0116). Миграции вниз в выкладке нет:
# она уносит записанное людьми. Возврат к старой замороженной базе —
# инструмент первых минут, а не способ отменить релиз; она живёт до следующей
# выкладки в ту же сборку.
#
# ЧЕГО ЗДЕСЬ НЕТ. Репликации: копия — снимок, и запись стоит всё время
# подготовки (минуты), а не секунды переключения. Это шаг первый из двух,
# шаг второй назван в задаче 0112 вместе с пятью условиями надёжности.

set -uo pipefail
cd "$(dirname "$0")/.."

COMPOSE="${COMPOSE:-docker compose -f docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"
export ENV_FILE
HISTORY="${DEPLOY_HISTORY:-deploy-history.log}"
SWITCH="${DEPLOY_SWITCH:-ops/switch-build.sh}"
# Сколько ждать, пока новая сборка станет healthy. Холодный старт с накатом
# общей схемы на пустой ячейке — минуты, поэтому не секунды.
HEALTH_WAIT="${DEPLOY_HEALTH_WAIT:-300}"
# Сколько ждать, пока оборванные соединения базы действительно уйдут.
KICK_WAIT="${DEPLOY_KICK_WAIT:-30}"

red()   { printf '\033[1;31m%s\033[0m\n' "$1" >&2; }
green() { printf '\033[1;32m%s\033[0m\n' "$1"; }
step()  { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
fail()  { red "$1"; exit 1; }

TAG=""
MIGRATIONS=накатить
MIGRATE_WITH=target
DATA="не трогать"
DRY=""
ABORT=""
WHO="${DEPLOY_WHO:-$(id -un 2>/dev/null || echo неизвестно)}"
STAND="${DEPLOY_STAND:-—}"

usage() {
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

# Всё, что трогает ячейку, идёт через run: при --dry-run шаги печатаются,
# а не выполняются. Так проверяется ПОРЯДОК — главное свойство этой выкладки,
# — без стенда и без docker.
#
# DEPLOY_DRY_FAIL подделывает отказ первого шага, в строке которого встретился
# образец, — и только при --dry-run. Им самопроверка проходит все ветки отката:
# «что будет, если упадёт копия», «если упадут самопроверки».
run() {
    if [ -n "$DRY" ]; then
        printf 'ШАГ: %s\n' "$*"
        if [ -n "${DEPLOY_DRY_FAIL:-}" ] && [ -z "${DRY_FAILED:-}" ]; then
            case "$*" in
                *"$DEPLOY_DRY_FAIL"*)
                    DRY_FAILED=yes
                    printf 'ОТКАЗ (подделан): %s\n' "$*"
                    return 1 ;;
            esac
        fi
        return 0
    fi
    "$@"
}

# ────────────────────────── правка окружения ячейки ──────────────────────────
#
# .env стенда — состояние, а не настройка из репозитория: в нём записано,
# каким тегом поднята каждая сборка и на какой базе она работает. Пишем
# на месте, сохраняя комментарии: файл читают руками, когда разбираются,
# что на стенде стоит.
set_env() {  # ключ, значение
    local key="$1" value="$2"
    [ -f "$ENV_FILE" ] || fail "Нет $ENV_FILE — это не каталог ячейки"
    if grep -q "^#\{0,1\}${key}=" "$ENV_FILE"; then
        python3 - "$ENV_FILE" "$key" "$value" <<'PY'
import re, sys
path, key, value = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, encoding="utf-8").read()
# Закомментированный ключ раскомментируется: в .env.example APP_IMAGE_TAG_GREEN
# лежит под решёткой, и дописанный вторым он разъехался бы с первым.
text = re.sub(r"(?m)^#?" + re.escape(key) + r"=.*$", lambda _: key + "=" + value, text, count=1)
open(path, "w", encoding="utf-8").write(text)
PY
    else
        printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
    fi
}

read_env() {  # ключ
    [ -f "$ENV_FILE" ] || return 0
    sed -n "s/^${1}=//p" "$ENV_FILE" | tail -n1
}

upper() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]'; }

# ──────────────────────────── разбор аргументов ──────────────────────────────
while [ $# -gt 0 ]; do
    case "$1" in
        --tag) TAG="${2:?нужен SHA образа}"; shift 2 ;;
        --tag=*) TAG="${1#--tag=}"; shift ;;
        --migrations) MIGRATIONS="${2:?}"; shift 2 ;;
        --migrations=*) MIGRATIONS="${1#--migrations=}"; shift ;;
        --migrate-with) MIGRATE_WITH="${2:?}"; shift 2 ;;
        --migrate-with=*) MIGRATE_WITH="${1#--migrate-with=}"; shift ;;
        --to|--to=*)
            red "--to больше не принимается: миграции вниз в выкладке нет (задача 0112)."
            red "Откатывается только приложение, база чинится вперёд: --migrate-with none."
            fail "Опустить схему на стенде — отдельным инструментом: ops/schema-sync.sh --apply --to <версия> <сборка>" ;;
        --data) DATA="${2:?}"; shift 2 ;;
        --data=*) DATA="${1#--data=}"; shift ;;
        --dry-run) DRY=yes; shift ;;
        --abort) ABORT=yes; shift ;;
        --selftest) selftest_requested=yes; shift ;;
        -h|--help) usage ;;
        *) red "Непонятный аргумент: $1"; usage ;;
    esac
done

# Значения формы приезжают словами владельца — «накатить», «не трогать»,
# «только миграции», — и приводятся к одному виду здесь, в одном месте.
case "$MIGRATIONS" in
    накатить|apply) MIGRATIONS=apply ;;
    "не трогать"|не-трогать|skip) MIGRATIONS=skip ;;
    "только миграции"|только|only) MIGRATIONS=only ;;
    *) red "Непонятно, что делать с миграциями: «${MIGRATIONS}»"
       fail "Годятся: накатить | не трогать | только миграции" ;;
esac

case "$MIGRATE_WITH" in
    target|none) ;;
    current)
        red "--migrate-with current больше не принимается: миграции вниз в выкладке нет."
        red "Решение владельца от 14 сентября 2026 (задача 0112): откатывается только приложение,"
        red "база чинится вперёд — прежний образ ставится на ту же базу (--migrate-with none)."
        fail "Опустить схему на стенде — ops/schema-sync.sh --apply --to <версия> <сборка>, отдельно от выкладки" ;;
    *) fail "--migrate-with бывает target (накатить образом выкладываемой сборки) или none (база новее образа — не трогать)" ;;
esac

# ─────────────────────────── данные: честный отказ ───────────────────────────
#
# Поле формы названо владельцем дословно, и двух его значений в системе
# сегодня нет: обезличивания боевого слепка не написано, эталонного набора
# тоже. Молча ничего не сделать — худший исход: тестировщик выберет
# «обезличенный слепок прома», увидит зелёную выкладку и будет смотреть
# вчерашние данные, считая их сегодняшним слепком.
data_gate() {
    case "$DATA" in
        "не трогать"|не-трогать|none) return 0 ;;
        "обезличенный слепок прома"|prod-snapshot)
            red "Слепка прома нет: обезличивания в системе не написано вовсе."
            red "Ни один файл ячейки не умеет снять с ПРОМ слепок без персональных данных,"
            red "а возить его как есть нельзя — это данные клиента на стенде тестировщика."
            fail "Выкладка остановлена ДО единого изменения. Выберите «не трогать»." ;;
        "эталонный набор"|reference)
            red "Эталонного набора нет: db/seed наполняет справочники (марки, модели,"
            red "виды деталей), но склада, сделок и денег в нём нет — это не тот набор,"
            red "на котором проверяют сценарии."
            fail "Выкладка остановлена ДО единого изменения. Выберите «не трогать»." ;;
        *) fail "Непонятно, что делать с данными: «${DATA}»" ;;
    esac
}

# Kafka и копия базы вместе не проверены, и молча выкладывать такое нельзя.
# Офсеты потребителей живут в брокере, а не в базе: сборка на копии прочтёт
# событие и отметит это в брокере, а при откате копию удалят — и для старой
# базы событие окажется прочитанным, но не обработанным. Потеря тихая.
transport_gate() {
    case ",$(read_env SPRING_PROFILES)," in
        *,kafka,*)
            red "На ячейке включён транспорт Kafka (SPRING_PROFILES=kafka)."
            red "Копия базы с ним не проверена: офсеты потребителей общие у двух баз,"
            red "и событие, прочитанное сборкой на копии, для старой базы при откате потеряется."
            fail "Выкладка остановлена ДО единого изменения." ;;
    esac
}

# Обратно словами владельца — для строки, которую читает человек. Отдельной
# функцией, а не `case` внутри подстановки: многострочный `case` в $( ) bash
# разбирает по-своему и печатает свой же текст кусками. Поймано живым
# прогоном, а не чтением.
migrations_word() {
    case "$MIGRATIONS" in
        apply) printf 'накатить' ;;
        skip)  printf 'не трогать' ;;
        only)  printf 'только миграции' ;;
    esac
}

# ──────────────────────────── кто сейчас под трафиком ────────────────────────
active_build() {
    if [ -n "${DEPLOY_ACTIVE:-}" ]; then
        printf '%s\n' "$DEPLOY_ACTIVE"
        return 0
    fi
    "$SWITCH" --current
}

other_of() {  # app-blue → app-green
    case "$1" in
        app-blue) printf 'app-green\n' ;;
        app-green) printf 'app-blue\n' ;;
        *) return 1 ;;
    esac
}

# База сборки — у ops/switch-build.sh, и только у него: он читает её так же,
# как compose. Второе место, знающее, где искать имя базы, однажды разъехалось
# бы с первым, и выкладка заморозила бы не ту базу, в которой люди.
db_of() { "$SWITCH" --db "$1"; }

# ─────────────────────────────── операции с базой ────────────────────────────
#
# Все идут владельцем схем в служебную базу postgres: в замороженную базу
# ходить нельзя (там всё только на чтение), а базу, с которой снимают копию,
# Postgres требует оставить без единого соединения.
pg() {
    $COMPOSE exec -T postgres psql -U "${PG_USER:?}" -d postgres -v ON_ERROR_STOP=1 -tAq "$@"
}

pg_in() {  # база, SQL — спросить внутри самой базы
    $COMPOSE exec -T postgres psql -U "${PG_USER:?}" -d "$1" -v ON_ERROR_STOP=1 -tAqc "$2"
}

# Оборвать соединения базы, открытые раньше момента. «Раньше момента»,
# а не «все»: пул приложения переоткрывает соединения сразу после обрыва,
# и новые — уже с нужным умолчанием. Требовать нуля значило бы ждать вечно.
kick_db() {  # база, момент по часам сервера
    local db="$1" since="$2" left deadline
    deadline=$(( $(date +%s) + KICK_WAIT ))
    while :; do
        left=$(pg -c "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity
                       WHERE datname = '$db' AND pid <> pg_backend_pid()
                         AND backend_start < '$since'::timestamptz") || return 1
        [ "$left" = 0 ] && return 0
        [ "$(date +%s)" -lt "$deadline" ] || {
            red "  $db: соединения, открытые до заморозки, не уходят за ${KICK_WAIT}с ($left)"
            return 1
        }
        sleep 1
    done
}

# Заморозить запись. Умолчание сессии — только для новых соединений, поэтому
# прежние обрываются, а результат проверяется попыткой: свежее соединение
# обязано ответить «только чтение». Верить команде без проверки нельзя —
# настройка роли старше настройки базы и отменила бы заморозку молча.
# Настройка роли старше настройки базы: заданное роли «read_only=off» молча
# отменило бы заморозку для рабочей роли, а проверка заморозки идёт владельцем,
# у которого своей настройки нет, — и ответила бы «заморожено». Спрашивается
# дважды: до метки (отказ там никого не задевает) и в самой заморозке.
freezable() {  # база
    local overrides
    overrides=$(pg -c "SELECT string_agg(DISTINCT pg_get_userbyid(s.setrole), ', ')
                         FROM pg_db_role_setting s, unnest(s.setconfig) c
                        WHERE s.setrole <> 0
                          AND c LIKE 'default\_transaction\_read\_only=%'
                          AND (s.setdatabase = 0
                               OR s.setdatabase = (SELECT oid FROM pg_database WHERE datname = '$1'))") || return 1
    if [ -n "$overrides" ]; then
        red "  $1: у ролей ($overrides) своя настройка default_transaction_read_only — она старше"
        red "  настройки базы и отменила бы заморозку. Снимите: ALTER ROLE … RESET default_transaction_read_only"
        return 1
    fi
}

freeze_db() {  # база
    local db="$1" since ro
    freezable "$db" || return 1
    pg -c "ALTER DATABASE \"$db\" SET default_transaction_read_only = on" >/dev/null || return 1
    since=$(pg -c "SELECT clock_timestamp()") || return 1
    kick_db "$db" "$since" || return 1
    ro=$(pg_in "$db" "SHOW transaction_read_only") || return 1
    if [ "$ro" != on ]; then
        red "  $db: заморозка поставлена, а новое соединение пишет (transaction_read_only=$ro)"
        return 1
    fi
    printf '  %s: запись заморожена — новые соединения только на чтение, прежние оборваны\n' "$db"
}

# Открыть запись. Тоже с обрывом: соединение, открытое во время заморозки,
# так и осталось бы «только на чтение» до конца своей жизни в пуле.
unfreeze_db() {  # база
    local db="$1" since ro
    pg -c "ALTER DATABASE \"$db\" ALLOW_CONNECTIONS true" >/dev/null || return 1
    pg -c "ALTER DATABASE \"$db\" RESET default_transaction_read_only" >/dev/null || return 1
    since=$(pg -c "SELECT clock_timestamp()") || return 1
    kick_db "$db" "$since" || return 1
    ro=$(pg_in "$db" "SHOW transaction_read_only") || return 1
    if [ "$ro" != off ]; then
        red "  $db: заморозка снята, а новое соединение всё ещё только на чтение ($ro)"
        return 1
    fi
    printf '  %s: запись открыта\n' "$db"
}

unfreeze_if_frozen() {  # база
    local db="$1" frozen allowed
    frozen=$(pg -c "SELECT count(*) FROM pg_db_role_setting s
                      JOIN pg_database d ON d.oid = s.setdatabase, unnest(s.setconfig) c
                     WHERE d.datname = '$db' AND s.setrole = 0
                       AND c = 'default_transaction_read_only=on'") || return 1
    allowed=$(pg -c "SELECT datallowconn FROM pg_database WHERE datname = '$db'") || return 1
    if [ "$frozen" = 0 ] && [ "$allowed" = t ]; then
        printf '  %s: не заморожена — делать нечего\n' "$db"
        return 0
    fi
    unfreeze_db "$db"
}

# Хватит ли места и не помешает ли что-нибудь — ДО заморозки: отказ здесь
# никого не задевает, отказ посреди копии задевает всех.
check_copy_room() {  # источник, копия
    local src="$1" dst="$2" exists dumps size free need
    exists=$(pg -c "SELECT count(*) FROM pg_database WHERE datname = '$src'") || return 1
    [ "$exists" = 1 ] || { red "  Базы $src нет — непонятно, с чего снимать копию"; return 1; }
    exists=$(pg -c "SELECT count(*) FROM pg_database WHERE datname = '$dst'") || return 1
    [ "$exists" = 0 ] || {
        red "  База $dst уже есть — копию прошлой выкладки не убрали. ops/deploy.sh --abort"
        return 1
    }
    # Копия требует базу без единого соединения, то есть оборвала бы идущий
    # дамп — ночной бэкап отчитался бы провалом, а утром никто не понял бы почему.
    dumps=$(pg -c "SELECT count(*) FROM pg_stat_activity
                    WHERE datname = '$src' AND application_name IN ('pg_dump', 'pg_restore')") || return 1
    [ "$dumps" = 0 ] || { red "  С базы $src сейчас снимают дамп ($dumps) — копия его оборвала бы. Повторите после бэкапа"; return 1; }
    freezable "$src" || return 1
    size=$(pg -c "SELECT pg_database_size('$src')") || return 1
    free=$($COMPOSE exec -T postgres df -Pk /var/lib/postgresql/data | awk 'NR == 2 { printf "%.0f", $4 * 1024 }') || return 1
    # Пятая часть сверху — под WAL, который пишется рядом всё время выкладки,
    # и под новые changeset'ы на копии.
    need=$(( size + size / 5 ))
    if [ "${free:-0}" -lt "$need" ]; then
        red "  Места под копию нет: база $src — $(( size / 1048576 )) МБ, нужно $(( need / 1048576 )) МБ, свободно $(( ${free:-0} / 1048576 )) МБ"
        return 1
    fi
    printf '  база %s — %s МБ, свободно %s МБ\n' "$src" "$(( size / 1048576 ))" "$(( free / 1048576 ))"
}

# Снять копию. Шаблоном, а не дампом: физическая копия файлов переносит всё —
# схемы, права, последовательности на их нынешнем значении, — и не зависит
# от того, всё ли умеет разворот. Цена — источник на время копирования
# остаётся без соединений (чтение ждёт секунды), поэтому снимается она
# с уже замороженной базы, и отказ на любом месте открывает соединения обратно.
# FILE_COPY, а не WAL_LOG: второй пишет всю базу в журнал, то есть в архив
# точки возврата на каждой выкладке.
copy_db() {  # источник, копия
    local src="$1" dst="$2" owner rc=0 started kill_all
    owner=$(pg -c "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = '$src'") || return 1
    [ -n "$owner" ] || { red "  Базы $src нет"; return 1; }
    started=$(date +%s)
    pg -c "ALTER DATABASE \"$src\" ALLOW_CONNECTIONS false" >/dev/null || return 1
    kill_all=$(pg -c "SELECT clock_timestamp() + interval '1 day'") || rc=1
    [ "$rc" = 0 ] && { kick_db "$src" "$kill_all" || rc=1; }
    if [ "$rc" = 0 ]; then
        pg -c "CREATE DATABASE \"$dst\" WITH TEMPLATE \"$src\" OWNER \"$owner\" STRATEGY FILE_COPY" >/dev/null || rc=1
    fi
    pg -c "ALTER DATABASE \"$src\" ALLOW_CONNECTIONS true" >/dev/null || {
        red "  $src: соединения обратно не открылись — ячейка не читает. ops/deploy.sh --abort"
        rc=1
    }
    [ "$rc" = 0 ] || return 1

    # Права на базу и её настройки не едут с шаблоном — они живут в общем
    # каталоге кластера. Без права CONNECT рабочая роль не вошла бы в копию
    # вовсе. Заморозку источника не переносим: копия замораживается сама,
    # своим шагом.
    $COMPOSE exec -T postgres psql -U "$PG_USER" -d postgres -v ON_ERROR_STOP=1 -q \
        -v src="$src" -v dst="$dst" >/dev/null <<'SQL' || return 1
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'dst') \gexec
SELECT format('GRANT %s ON DATABASE %I TO %s', a.privilege_type, :'dst',
              CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(a.grantee)) END)
  FROM pg_database d, aclexplode(COALESCE(d.datacl, acldefault('d', d.datdba))) a
 WHERE d.datname = :'src' AND a.grantee <> d.datdba \gexec
SELECT CASE WHEN s.setrole = 0
            THEN format('ALTER DATABASE %I SET %s', :'dst', c)
            ELSE format('ALTER ROLE %I IN DATABASE %I SET %s', pg_get_userbyid(s.setrole), :'dst', c)
       END
  FROM pg_db_role_setting s JOIN pg_database d ON d.oid = s.setdatabase, unnest(s.setconfig) AS c
 WHERE d.datname = :'src' AND c NOT LIKE 'default\_transaction\_read\_only=%' \gexec
SQL
    printf '  %s → %s за %s с, соединения источника открыты\n' "$src" "$dst" "$(( $(date +%s) - started ))"
}

# Удалять выкладка вправе только не свою рабочую базу. Проверка отдельной
# функцией — её самопроверка зовёт напрямую, без docker.
drop_guard() {  # база, база под трафиком
    if [ "$1" = "$2" ]; then
        red "  ОТКАЗ: $1 — база сборки под трафиком, в ней люди. Удалять её выкладка не вправе"
        return 1
    fi
}

drop_db() {  # база
    local db="$1" active
    active=$("$SWITCH" --current-db) || { red "  Не узнать базу под трафиком — ничего не удаляю"; return 1; }
    drop_guard "$db" "$active" || return 1
    pg -c "DROP DATABASE IF EXISTS \"$db\" WITH (FORCE)" >/dev/null || return 1
    printf '  %s удалена\n' "$db"
}

# ─────────────────────────── откат до точки невозврата ───────────────────────
#
# Порядок не случайный. Трафик возвращается первым — людей обслуживает
# старая сборка, и остальное делается уже без них. Копия удаляется только
# после того, как трафик ушёл: проверка «не база ли это под трафиком» стоит
# в drop_db и отказывает, если возврат не удался. Запись в старой базе
# открывается последней, а метка «выкладка не закончена» снимается только
# после всех шагов — упавший откат повторяет ops/deploy.sh --abort.
undo_copy() {
    local bad=0
    step "Откатываем: трафик и запись возвращаются на $SOURCE_DB"
    if ! run env SWITCH_FORCE=yes "$SWITCH" "$ACTIVE_COLOR"; then
        red "Вернуть трафик на $ACTIVE не вышло. Копию не трогаю: люди могут быть на ней."
        red "Разберитесь с терминатором и повторите: ops/deploy.sh --abort"
        return 1
    fi
    run $COMPOSE stop "$TARGET" || bad=1
    run drop_db "$COPY_DB" || bad=1
    run set_env "APP_DB_$(upper "$COLOR")" "$PREV_TARGET_DB" || bad=1
    # Профили — обратно на одну сборку. Оставленные «blue,green», они
    # подняли бы неудавшуюся сборку первым же `up -d` без имени сервиса.
    run set_env COMPOSE_PROFILES "$ACTIVE_COLOR" || bad=1
    run unfreeze_db "$SOURCE_DB" || bad=1
    if [ "$bad" != 0 ]; then
        red "Откат прошёл не целиком — метку незаконченной выкладки оставляю: ops/deploy.sh --abort"
        return 1
    fi
    clear_marker
}

set_marker() {
    run set_env DEPLOY_COPY_DB "$COPY_DB" &&
    run set_env DEPLOY_COPY_FROM "$SOURCE_DB" &&
    run set_env DEPLOY_COPY_BUILD "$TARGET" &&
    run set_env DEPLOY_COPY_PREV "$PREV_TARGET_DB"
}

clear_marker() {
    run set_env DEPLOY_COPY_DB "" &&
    run set_env DEPLOY_COPY_FROM "" &&
    run set_env DEPLOY_COPY_BUILD "" &&
    run set_env DEPLOY_COPY_PREV ""
}

# Отказ после заморозки: всё назад, запись в историю, красный код.
abandon() {  # причина
    red "$1"
    if undo_copy; then
        freeze_report
        record "выкладка" "$1; откат: трафик и база прежние"
        fail "Выкладка красная. Ячейка на прежней сборке и прежней базе, запись открыта"
    fi
    record "выкладка" "$1; откат НЕ закончен"
    fail "Выкладка красная, и откат не закончен — ops/deploy.sh --abort"
}

freeze_report() {
    [ -n "${FROZEN_AT:-}" ] || return 0
    printf 'Запись была заморожена %s с\n' "$(( $(date +%s) - FROZEN_AT ))"
}

# ─────────────────────────────── сама выкладка ───────────────────────────────
deploy() {
    [ -n "$TAG" ] || fail "Нужен --tag: полный SHA коммита, собранного CI (сорок символов)"
    case "$TAG" in
        *[!0-9a-f]*|"") fail "Тег «${TAG}» не похож на SHA: в реестре лежат полные SHA, сорок символов" ;;
    esac
    [ "${#TAG}" = 40 ] || fail "Тег «${TAG}» длиной ${#TAG}: короткого SHA в реестре нет, pull ответит manifest unknown"

    data_gate
    transport_gate

    if [ -n "$(read_env DEPLOY_COPY_DB)" ]; then
        red "Прошлая выкладка не закончена: в $ENV_FILE стоит DEPLOY_COPY_DB=$(read_env DEPLOY_COPY_DB)."
        red "Запись в базе, возможно, ещё заморожена, а копия не убрана."
        fail "Сначала доделайте откат: ops/deploy.sh --abort. Выкладка остановлена ДО единого изменения."
    fi

    ACTIVE=$(active_build) || fail "Не удалось узнать сборку под трафиком (ops/active-build.caddy)"
    TARGET=$(other_of "$ACTIVE") || fail "Непонятное имя активной сборки: $ACTIVE"
    COLOR="${TARGET#app-}"
    ACTIVE_COLOR="${ACTIVE#app-}"
    SOURCE_DB=$(db_of "$ACTIVE") || fail "Не узнать базу сборки $ACTIVE"
    PREV_TARGET_DB=$(db_of "$TARGET") || fail "Не узнать базу сборки $TARGET"
    # Имя копии называет сборку и версию: «какой сборке эта база» читается
    # в списке баз без расспросов. Цвет в имени — от повторной выкладки
    # того же SHA: в сборку другого цвета, то есть в другую базу.
    COPY_DB="parts_${COLOR}_$(printf '%s' "$TAG" | cut -c1-12)"
    PG_USER="${DB_USER:-$(read_env DB_USER)}"

    printf 'Стенд:        %s\n' "$STAND"
    printf 'Выкладываем:  %s\n' "$TAG"
    printf 'Сборка:       %s (сейчас под трафиком %s)\n' "$TARGET" "$ACTIVE"
    if [ "$MIGRATIONS" = only ]; then
        printf 'База:         %s — на месте, без копии\n' "$SOURCE_DB"
    else
        printf 'База:         %s → копия %s\n' "$SOURCE_DB" "$COPY_DB"
    fi
    printf 'Миграции:     %s%s\n' "$(migrations_word)" \
        "$([ "$MIGRATE_WITH" = none ] && printf ' — база новее образа, схему не трогаем')"
    printf 'Данные:       %s\n' "$DATA"
    printf 'Нажал:        %s\n' "$WHO"

    # Тег новой сборки — в .env ДО всего остального: и pull, и одноразовый
    # контейнер миграции берут образ оттуда же, откуда его возьмёт compose.
    step "Записываем тег выкладываемой сборки"
    run set_env "APP_IMAGE_TAG_$(upper "$COLOR")" "$TAG"
    # Обе сборки обязаны быть подняты одновременно — иначе «рядом» не выйдет.
    run set_env COMPOSE_PROFILES "blue,green"

    step "Забираем образ из реестра"
    run $COMPOSE --profile "$COLOR" pull "$TARGET" \
        || fail "Образа $TAG в реестре нет (или реестр недоступен). Ячейка ничего не собирает — выкладывать нечего"

    # ── «только миграции»: на месте, без копии ─────────────────────────────
    #
    # Поле формы названо владельцем: «привести схему и выйти». Сборку при
    # этом не выкладывают, значит и копии, на которую переключиться, нет —
    # схема приводится у работающей базы. Годится это только для расширяющих
    # миграций (старый код на новой схеме работает, сторож — 0116), и не даёт
    # «всё или ничего»: сорвавшийся changeset оставит схему на полпути.
    if [ "$MIGRATIONS" = only ]; then
        if [ "$MIGRATE_WITH" = none ]; then
            green "База новее выкладываемого образа — приводить нечего. Схему не трогали."
            record "только миграции" "успех: база новее образа"
            return 0
        fi
        step "Приводим схему НА МЕСТЕ — у базы $SOURCE_DB, под работающей сборкой"
        printf 'Без копии: это расширяющий шаг, старая сборка обязана работать на новой схеме.\n'
        run env "APP_DB_$(upper "$COLOR")=$SOURCE_DB" ops/schema-sync.sh --apply "$COLOR" \
            || fail "Схему привести не вышло — сборку не поднимали, трафик на $ACTIVE"
        green "Схема приведена. Сборку не поднимали и трафик не трогали — выбрано «только миграции»."
        record "только миграции" "успех"
        return 0
    fi

    # ── место и прежняя база выкладываемой сборки ───────────────────────────
    #
    # У выкладываемой сборки своя база — копия, снятая прошлой выкладкой
    # в этот цвет: замороженный слепок на момент того переключения. Её место
    # занимает новая копия. Та же база, что под трафиком (ячейка, ещё
    # не выкладывавшаяся с копией: обе сборки на «parts»), не трогается ни при
    # каких обстоятельствах — проверка стоит и здесь, и в самом удалении.
    step "Прежняя база сборки $TARGET"
    if [ "$PREV_TARGET_DB" = "$SOURCE_DB" ]; then
        printf '  %s — это база сборки под трафиком: не трогаем\n' "$PREV_TARGET_DB"
    else
        run $COMPOSE stop "$TARGET" || fail "Не остановить $TARGET — прежнюю базу не удаляю"
        run drop_db "$PREV_TARGET_DB" \
            || fail "Прежнюю базу $PREV_TARGET_DB удалить не вышло — выкладка остановлена до заморозки"
    fi

    step "Место под копию"
    run check_copy_room "$SOURCE_DB" "$COPY_DB" \
        || fail "Копию снимать нельзя — выкладка остановлена ДО заморозки, люди работают как работали"

    # Метка — до заморозки: если выкладку оборвут посреди, --abort по ней
    # узнает, какую базу открыть и какую копию удалить.
    set_marker || fail "Не записать метку выкладки в $ENV_FILE — останавливаюсь до заморозки"

    # ── 1–2. заморозка и копия ─────────────────────────────────────────────
    step "Замораживаем запись в $SOURCE_DB"
    FROZEN_AT=$(date +%s)
    run freeze_db "$SOURCE_DB" || abandon "Заморозить запись в $SOURCE_DB не вышло"

    step "Снимаем копию $SOURCE_DB → $COPY_DB"
    run copy_db "$SOURCE_DB" "$COPY_DB" || abandon "Копия $COPY_DB не снялась"

    # Сборка настроена на копию ДО того, как её поднимут, и тем более до
    # трафика. Базу работающей сборки не трогает ни одна строка ниже: старая
    # на копию не смотрит никогда.
    run set_env "APP_DB_$(upper "$COLOR")" "$COPY_DB" || abandon "Не записать базу сборки $TARGET в $ENV_FILE"

    # ── 3. схема копии ──────────────────────────────────────────────────────
    if [ "$MIGRATE_WITH" = none ]; then
        step "Схему не трогаем: база новее образа"
        printf 'Откатывается только приложение, база чинится вперёд (задача 0112).\n'
        printf 'Готовность новой сборки покраснеет сама, если схема окажется ПОЗАДИ неё.\n'
    elif [ "$MIGRATIONS" = skip ]; then
        step "Схему не трогаем (выбрано «не трогать»)"
        printf 'Пара «код и схема» при этом может не совпасть — готовность новой сборки\n'
        printf 'покраснеет сама, если схема окажется ПОЗАДИ неё.\n'
    else
        step "Приводим схему КОПИИ к версии образа"
        run ops/schema-sync.sh --apply "$COLOR" \
            || abandon "Схему копии привести не вышло"
    fi

    # ── 4–5. новая сборка рядом, на копии ──────────────────────────────────
    step "Поднимаем $TARGET РЯДОМ со старой — на копии $COPY_DB"
    run $COMPOSE --profile blue --profile green up -d "$TARGET" \
        || abandon "Не поднялась $TARGET"

    step "Ждём готовности $TARGET"
    run wait_healthy "$TARGET" || abandon "Сборка $TARGET не стала здоровой за ${HEALTH_WAIT}с"

    # ── 6. копия замораживается на время переключения ─────────────────────
    #
    # До точки невозврата в копию не должна лечь ни одна запись людей: упади
    # самопроверки — копию удалят, и записанное туда исчезло бы. Так же
    # делает эталон (blue/green у Amazon RDS): при переключении запись
    # заблокирована на обеих базах.
    step "Замораживаем и копию — до конца самопроверок"
    run freeze_db "$COPY_DB" || abandon "Заморозить копию $COPY_DB не вышло"

    # ── 7. трафик ───────────────────────────────────────────────────────────
    step "Переводим трафик на $TARGET"
    # Копия заморожена намеренно (шаг 6), и переключатель на замороженную
    # базу без разрешения не пускает: там обычно лежит вчерашний слепок.
    run env SWITCH_FROZEN_OK=yes "$SWITCH" "$COLOR" || abandon "Переключение не прошло"

    # ── 8. самопроверки ─────────────────────────────────────────────────────
    step "Пять самопроверок на переключённой сборке"
    run env DEPLOY_BUILD="$TARGET" ops/deploy-checks.sh || abandon "Самопроверки не прошли"

    # ── 9. точка невозврата ────────────────────────────────────────────────
    #
    # Метка снимается ДО того, как открывается запись: оборвись выкладка
    # между двумя шагами, --abort без метки не удалит копию, в которую уже
    # пишут, а только снимет с неё заморозку.
    step "Точка невозврата: открываем запись в $COPY_DB"
    clear_marker || fail "Не снять метку выкладки в $ENV_FILE — трафик на $TARGET, запись заморожена. ops/deploy.sh --abort"
    run unfreeze_db "$COPY_DB" || {
        record "выкладка" "трафик на новой сборке, запись в копии не открылась"
        fail "Трафик на $TARGET, но запись в $COPY_DB не открылась — ops/deploy.sh --abort снимет заморозку"
    }
    freeze_report

    step "Гасим старую сборку $ACTIVE"
    # Не погасла — выкладка всё равно состоялась (трафик и запись уже
    # на новой), но сказать надо: старая так и работает на замороженном
    # слепке, и её готовность зелёная. Переключатель на неё людей
    # не переведёт, а фоновые обходы будут сыпать отказами записи.
    run $COMPOSE stop "$ACTIVE" || {
        red "Старая сборка $ACTIVE не погасла — она работает на замороженной $SOURCE_DB."
        red "Погасите руками: $COMPOSE stop $ACTIVE"
        record "выкладка" "успех; старая сборка не погасла"
    }

    # Тег ячейки переносим после успеха: до него «версия ячейки» — прежняя,
    # и это верно, потому что вернуться можно было в любую минуту.
    run set_env APP_IMAGE_TAG "$TAG"
    run set_env COMPOSE_PROFILES "$COLOR"

    record "выкладка" "успех"
    green "Готово. Под трафиком $TARGET, версия $TAG, база $COPY_DB"
    printf 'Прежняя база %s оставлена замороженной — инструмент первых минут, а не отмена\n' "$SOURCE_DB"
    printf 'релиза: всё записанное с этой минуты живёт только в %s. Её место займёт\n' "$COPY_DB"
    printf 'следующая выкладка в сборку %s.\n' "$ACTIVE"
    return 0
}

# ───────────────────────── доделать откат оборвавшейся ──────────────────────
#
# Выкладка, оборванная посреди (упал ssh, перезагрузили машину), оставляет
# ячейку с замороженной записью. По метке в .env видно, что откатывать: пока
# метка стоит, точки невозврата не было — трафик возвращается на прежнюю
# сборку, копия удаляется, запись открывается. Метки нет — выкладка либо
# не начиналась, либо прошла точку невозврата; тогда снимается только
# заморозка с базы под трафиком, если она осталась.
abort() {
    PG_USER="${DB_USER:-$(read_env DB_USER)}"
    COPY_DB=$(read_env DEPLOY_COPY_DB)
    if [ -z "$COPY_DB" ]; then
        ACTIVE=$(active_build) || fail "Не удалось узнать сборку под трафиком"
        SOURCE_DB=$(db_of "$ACTIVE") || fail "Не узнать базу сборки $ACTIVE"
        step "Незаконченной выкладки нет — снимаем заморозку с $SOURCE_DB, если осталась"
        run unfreeze_if_frozen "$SOURCE_DB" || fail "Заморозку снять не вышло"
        green "Запись в базе под трафиком открыта"
        return 0
    fi
    SOURCE_DB=$(read_env DEPLOY_COPY_FROM)
    TARGET=$(read_env DEPLOY_COPY_BUILD)
    PREV_TARGET_DB=$(read_env DEPLOY_COPY_PREV)
    ACTIVE=$(other_of "$TARGET") || fail "В метке выкладки непонятная сборка: «${TARGET}»"
    COLOR="${TARGET#app-}"
    ACTIVE_COLOR="${ACTIVE#app-}"
    TAG=$(read_env "APP_IMAGE_TAG_$(upper "$COLOR")")
    [ -n "$SOURCE_DB" ] && [ -n "$PREV_TARGET_DB" ] || fail "Метка выкладки неполная — разбирайтесь руками по $ENV_FILE"
    printf 'Незаконченная выкладка: сборка %s на копии %s, прежняя база %s\n' "$TARGET" "$COPY_DB" "$SOURCE_DB"
    undo_copy || fail "Откат не закончен — причина выше; повторите ops/deploy.sh --abort"
    record "откат оборвавшейся выкладки" "успех"
    green "Откат доделан: трафик на $ACTIVE, база $SOURCE_DB, запись открыта"
}

wait_healthy() {  # имя сервиса
    local name="$1" id status deadline
    deadline=$(( $(date +%s) + HEALTH_WAIT ))
    while :; do
        id=$($COMPOSE ps -q "$name" 2>/dev/null | tail -n1)
        if [ -n "$id" ]; then
            # Нет healthcheck'а — нет и ответа: пустую строку принимать
            # за здоровье нельзя, иначе выкладка переключит трафик
            # на не поднявшееся приложение.
            status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}нет проверки{{end}}' \
                "$id" 2>/dev/null)
            case "$status" in
                healthy) printf '  %s: healthy\n' "$name"; return 0 ;;
                "нет проверки") red "У $name нет healthcheck'а — ждать нечего"; return 1 ;;
            esac
        fi
        [ "$(date +%s)" -lt "$deadline" ] || { red "  $name: ${status:-не поднялась}"; return 1; }
        sleep 5
    done
}

# ───────────────────────── запись о выкладке ─────────────────────────────────
#
# «Что сейчас на ПСИ» обязано отвечаться без расспросов. Строка остаётся
# и у неудачной выкладки: разбираются как раз с ними.
record() {  # что делали, чем кончилось
    local line
    # Базы — в строке истории: имя боевой базы меняется с каждой выкладкой,
    # и возврат к моменту времени обязан знать, КАК база называлась тогда
    # (ops/restore-pitr.sh --db). Спросить это потом больше не у кого.
    line=$(printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$STAND" "${TAG:-—}" "$WHO" \
        "$1" "миграции: $MIGRATIONS$([ "$MIGRATE_WITH" = none ] && printf ', база новее образа')" \
        "база: ${SOURCE_DB:-—}$([ -n "${COPY_DB:-}" ] && [ "$MIGRATIONS" != only ] && printf ' → %s' "$COPY_DB")" "$2")
    if [ -n "$DRY" ]; then
        printf 'ЗАПИСЬ: %s\n' "$line"
    else
        printf '%s\n' "$line" >> "$HISTORY"
    fi
}

# ────────────────────────────── самопроверка ─────────────────────────────────
#
# Без ячейки и без docker. Проверяется то единственное, ради чего эта выкладка
# и написана: ПОРЯДОК ШАГОВ и ОТКАТ. Запись замораживается раньше копии,
# новая сборка смотрит на копию раньше трафика, старая на копию не смотрит
# никогда, копия открывается на запись только после самопроверок, а любой
# отказ до этого возвращает всё — и не открывает запись в копии, которую
# удаляет.
selftest() {
    local bad=0 dir plan code
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN

    local sha=b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1
    local copy=parts_green_b1b1b1b1b1b1
    local blue_db=parts_blue_cccccccccccc
    local green_db=parts_green_dddddddddddd

    fresh_env() {  # ячейка, выкладывавшаяся копией: у обеих сборок свои базы
        printf 'COMPOSE_PROFILES=blue\nAPP_IMAGE_TAG=%s\n#APP_IMAGE_TAG_GREEN=\nDB_USER=partsflow\nAPP_DB_BLUE=%s\nAPP_DB_GREEN=%s\n' \
            "$(printf 'a%.0s' $(seq 40))" "$blue_db" "$green_db" > "$dir/env"
    }

    say()  { printf '  ✓ %s\n' "$1"; }
    nope() { red "  ✗ $1"; bad=$((bad + 1)); }

    # Номер первой строки плана с образцом. Образец, начинающийся с «=», —
    # это ШАГ целиком: «снять метку» (set_env DEPLOY_COPY_DB с пустым
    # значением) иначе совпал бы с «поставить метку», стоящей раньше.
    line_of() {  # образец
        case "$1" in
            =*) printf '%s\n' "$plan" | grep -n -x -F -- "ШАГ: ${1#=}" | head -n1 | cut -d: -f1 ;;
            *)  printf '%s\n' "$plan" | grep -n -F -- "$1" | head -n1 | cut -d: -f1 ;;
        esac
    }

    # План выкладки: переменные оболочки про базы снимаются — у compose они
    # старше .env, и случайно заданная на машине испортила бы проверку.
    plan_of() {  # активная сборка, аргументы…
        local active="$1"; shift
        plan=$(env -u APP_DB_BLUE -u APP_DB_GREEN -u DB_USER ENV_FILE="$dir/env" \
            DEPLOY_ACTIVE="$active" DEPLOY_HISTORY="$dir/h" bash "$0" "$@" --dry-run 2>&1)
        code=$?
    }

    # Порядок в плане: шаг A обязан стоять РАНЬШЕ шага B.
    before() {  # имя случая, образец A, образец B
        local a b
        a=$(line_of "$2")
        b=$(line_of "$3")
        if [ -z "$a" ]; then nope "$1: в плане нет «$2»"; return; fi
        if [ -z "$b" ]; then nope "$1: в плане нет «$3»"; return; fi
        if [ "$a" -lt "$b" ]; then say "$1"; else nope "$1: «$2» стоит после «$3»"; fi
    }

    absent() {  # имя случая, образец
        if [ -n "$(line_of "$2")" ]; then nope "$1: в плане есть «$2»"; else say "$1"; fi
    }

    present() {  # имя случая, образец
        if [ -n "$(line_of "$2")" ]; then say "$1"; else nope "$1: в плане нет «$2»"; fi
    }

    refused_before_changes() {  # имя случая
        if [ "$code" = 0 ]; then
            nope "$1: обязано быть отказом"
        else
            absent "$1: остановлено до единого изменения" "ШАГ:"
        fi
    }

    echo "Самопроверка ops/deploy.sh"

    # 1. Обычная выкладка: заморозка → копия → схема копии → подъём →
    #    готовность → заморозка копии → трафик → самопроверки → точка
    #    невозврата → старая гаснет последней.
    fresh_env
    plan_of app-blue --tag "$sha"
    [ "$code" = 0 ] && say "обычная выкладка проходит" || nope "обычная выкладка упала: $plan"
    before "прежняя база зелёной удаляется ДО заморозки" "drop_db $green_db" "freeze_db $blue_db"
    before "место под копию проверяется ДО заморозки" "check_copy_room $blue_db $copy" "freeze_db $blue_db"
    before "метка выкладки ставится ДО заморозки" "set_env DEPLOY_COPY_DB $copy" "freeze_db $blue_db"
    before "ЗАПИСЬ ЗАМОРОЖЕНА РАНЬШЕ КОПИИ" "freeze_db $blue_db" "copy_db $blue_db $copy"
    before "новая сборка настроена на копию раньше наката" "set_env APP_DB_GREEN $copy" "schema-sync.sh --apply green"
    before "схема приводится на копии раньше подъёма" "schema-sync.sh --apply green" "up -d app-green"
    before "новая сборка настроена на копию раньше подъёма" "set_env APP_DB_GREEN $copy" "up -d app-green"
    before "подъём раньше готовности" "up -d app-green" "wait_healthy app-green"
    before "готовность раньше заморозки копии" "wait_healthy app-green" "freeze_db $copy"
    before "КОПИЯ ЗАМОРОЖЕНА РАНЬШЕ ТРАФИКА" "freeze_db $copy" "switch-build.sh green"
    before "трафик раньше самопроверок" "switch-build.sh green" "deploy-checks.sh"
    before "метка снимается после самопроверок" "deploy-checks.sh" "=set_env DEPLOY_COPY_DB "
    before "метка снимается ДО открытия записи в копии" "=set_env DEPLOY_COPY_DB " "unfreeze_db $copy"
    before "ЗАПИСЬ В КОПИИ ОТКРЫВАЕТСЯ ТОЛЬКО ПОСЛЕ САМОПРОВЕРОК" "deploy-checks.sh" "unfreeze_db $copy"
    before "СТАРАЯ ГАСНЕТ ПОСЛЕДНЕЙ: после открытия записи" "unfreeze_db $copy" "stop app-blue"
    before "тег ячейки переносится после остановки старой" "stop app-blue" "set_env APP_IMAGE_TAG $sha"
    absent "СТАРАЯ СБОРКА НА КОПИЮ НЕ СМОТРИТ: её база не переписывается" "set_env APP_DB_BLUE"
    absent "старая сборка не перезапускается" "up -d app-blue"
    absent "старая база остаётся замороженной — возврат первых минут" "unfreeze_db $blue_db"
    absent "удачная выкладка копию не удаляет" "drop_db $copy"
    case "$plan" in
        *"ЗАПИСЬ:"*) say "выкладка оставляет запись" ;;
        *) nope "выкладка не оставляет записи" ;;
    esac

    # 2. Любой отказ между заморозкой и точкой невозврата возвращает ВСЁ:
    #    трафик, запись в прежней базе, копию — удаляет. Запись в копии,
    #    которую удаляют, не открывается никогда: иначе люди успели бы
    #    записать туда то, что исчезнет.
    for broken in "copy_db" "schema-sync.sh" "up -d app-green" "wait_healthy" "freeze_db $copy" \
                  "switch-build.sh green" "deploy-checks.sh"; do
        fresh_env
        plan=$(env -u APP_DB_BLUE -u APP_DB_GREEN -u DB_USER ENV_FILE="$dir/env" DEPLOY_ACTIVE=app-blue \
            DEPLOY_HISTORY="$dir/h" DEPLOY_DRY_FAIL="$broken" bash "$0" --tag "$sha" --dry-run 2>&1)
        code=$?
        [ "$code" != 0 ] && say "отказ на «${broken}» красит выкладку" || nope "отказ на «${broken}» не красит выкладку"
        before "отказ на «${broken}»: трафик возвращается раньше удаления копии" \
            "switch-build.sh blue" "drop_db $copy"
        before "отказ на «${broken}»: новая сборка гаснет раньше удаления копии" \
            "stop app-green" "drop_db $copy"
        before "отказ на «${broken}»: копия удаляется раньше открытия записи в прежней базе" \
            "drop_db $copy" "unfreeze_db $blue_db"
        before "отказ на «${broken}»: метка снимается последней" \
            "unfreeze_db $blue_db" "=set_env DEPLOY_COPY_DB "
        present "отказ на «${broken}»: зелёной возвращается прежняя база" "set_env APP_DB_GREEN $green_db"
        before "отказ на «${broken}»: профили возвращаются на одну сборку" \
            "drop_db $copy" "=set_env COMPOSE_PROFILES blue"
        absent "отказ на «${broken}»: ЗАПИСЬ В УДАЛЯЕМОЙ КОПИИ НЕ ОТКРЫВАЕТСЯ" "unfreeze_db $copy"
        absent "отказ на «${broken}»: старая сборка не гаснет" "stop app-blue"
        absent "отказ на «${broken}»: тег ячейки не переносится" "set_env APP_IMAGE_TAG $sha"
    done

    # 3. Отказ ДО заморозки ничего не замораживает и ничего не откатывает.
    fresh_env
    plan=$(env -u APP_DB_BLUE -u APP_DB_GREEN -u DB_USER ENV_FILE="$dir/env" DEPLOY_ACTIVE=app-blue \
        DEPLOY_HISTORY="$dir/h" DEPLOY_DRY_FAIL="check_copy_room" bash "$0" --tag "$sha" --dry-run 2>&1)
    code=$?
    [ "$code" != 0 ] && say "нет места под копию — отказ" || nope "нет места под копию — не отказ"
    absent "нет места под копию: запись не замораживается" "freeze_db"
    absent "нет места под копию: метка не ставится" "set_env DEPLOY_COPY_DB $copy"

    # 4. Прежняя база выкладываемой сборки, совпадающая с базой под трафиком
    #    (ячейка, не выкладывавшаяся копией: обе на «parts»), не удаляется.
    printf 'COMPOSE_PROFILES=blue\nAPP_IMAGE_TAG=%s\nDB_USER=partsflow\n' "$(printf 'a%.0s' $(seq 40))" > "$dir/env"
    plan_of app-blue --tag "$sha"
    absent "первая выкладка копией: общая «parts» не удаляется" "drop_db parts"
    present "первая выкладка копией: копия снимается с «parts»" "copy_db parts $copy"

    # 5. Под трафиком зелёная — выкладываем в синюю, копию снимаем с базы зелёной.
    fresh_env
    plan_of app-green --tag "$sha"
    present "под трафиком зелёная — копия снимается с её базы" "copy_db $green_db parts_blue_b1b1b1b1b1b1"
    before "под трафиком зелёная — выкладываем в синюю" "up -d app-blue" "stop app-green"
    absent "под трафиком зелёная — её база не переписывается" "set_env APP_DB_GREEN"

    # 6. «Не трогать» и «база новее образа» не зовут миграцию, но копию и трафик ведут.
    fresh_env
    plan_of app-blue --tag "$sha" --migrations "не трогать"
    absent "не трогать: миграции не зовутся" "schema-sync.sh"
    present "не трогать: копия всё равно снимается" "copy_db $blue_db $copy"
    present "не трогать: код всё же выкладывается" "switch-build.sh green"
    fresh_env
    plan_of app-blue --tag "$sha" --migrate-with none
    absent "откат приложения: схема не опускается и не накатывается" "schema-sync.sh"
    present "откат приложения: тем же путём копии" "copy_db $blue_db $copy"

    # 7. «Только миграции» — на месте: без заморозки, без копии, без трафика,
    #    и образом выкладываемой сборки поверх базы работающей.
    fresh_env
    plan_of app-blue --tag "$sha" --migrations "только миграции"
    present "только миграции: схема базы под трафиком, образом выкладываемой" \
        "env APP_DB_GREEN=$blue_db ops/schema-sync.sh --apply green"
    absent "только миграции: запись не замораживается" "freeze_db"
    absent "только миграции: копии нет" "copy_db"
    absent "только миграции: трафик не трогается" "switch-build.sh green"
    absent "только миграции: старая не гаснет" "stop app-blue"

    # 8. Отказы до единого изменения.
    for choice in "обезличенный слепок прома" "эталонный набор"; do
        fresh_env
        plan_of app-blue --tag "$sha" --data "$choice"
        refused_before_changes "«${choice}»"
    done
    fresh_env
    printf 'SPRING_PROFILES=kafka\n' >> "$dir/env"
    plan_of app-blue --tag "$sha"
    refused_before_changes "транспорт Kafka"
    fresh_env
    printf 'DEPLOY_COPY_DB=%s\n' "$copy" >> "$dir/env"
    plan_of app-blue --tag "$sha"
    refused_before_changes "незаконченная прошлая выкладка"

    # 9. Мусор в аргументах — отказ, а не умолчание. Опечатка в «не трогать»
    #    не должна тихо превращаться в накат схемы, а упразднённый откат вниз —
    #    молча выполняться прежним путём.
    for args in "--tag $sha --migrations нетрогать" "--tag короткий" "--tag $sha --migrate-with чужой" \
                "--tag $sha --migrate-with current" "--tag $sha --to 134" "--tag $sha --data чужое"; do
        fresh_env
        # shellcheck disable=SC2086
        if env -u APP_DB_BLUE -u APP_DB_GREEN ENV_FILE="$dir/env" DEPLOY_ACTIVE=app-blue DEPLOY_HISTORY="$dir/h" \
            bash "$0" $args --dry-run >/dev/null 2>&1; then
            nope "«${args}» обязано быть отказом"
        else
            say "«${args}» — отказ"
        fi
    done

    # 10. --abort по метке доделывает откат; без метки — только снимает заморозку.
    fresh_env
    printf 'DEPLOY_COPY_DB=%s\nDEPLOY_COPY_FROM=%s\nDEPLOY_COPY_BUILD=app-green\nDEPLOY_COPY_PREV=%s\n' \
        "$copy" "$blue_db" "$green_db" >> "$dir/env"
    plan_of app-blue --abort
    [ "$code" = 0 ] && say "--abort по метке проходит" || nope "--abort по метке упал: $plan"
    before "--abort: трафик возвращается раньше удаления копии" "switch-build.sh blue" "drop_db $copy"
    before "--abort: копия удаляется раньше открытия записи" "drop_db $copy" "unfreeze_db $blue_db"
    absent "--abort: запись в копии не открывается" "unfreeze_db $copy"
    fresh_env
    plan_of app-blue --abort
    present "--abort без метки: снимается заморозка с базы под трафиком" "unfreeze_if_frozen $blue_db"
    absent "--abort без метки: ничего не удаляется" "drop_db"

    # 11. Удалять базу под трафиком выкладка не вправе — проверка сама по себе.
    if drop_guard parts_x parts_x 2>/dev/null; then
        nope "удаление базы под трафиком обязано быть отказом"
    else
        say "удаление базы под трафиком — отказ"
    fi
    drop_guard parts_x parts_y && say "удаление чужой базы — разрешено" || nope "удаление не своей базы запрещено зря"

    # 12. Правка .env: на месте, с раскомментированием и без потери соседей.
    fresh_env
    ENV_FILE="$dir/env" set_env APP_IMAGE_TAG_GREEN "$sha"
    ENV_FILE="$dir/env" set_env DEPLOY_NEW "значение"
    ENV_FILE="$dir/env" set_env DEPLOY_COPY_DB ""
    if grep -q "^APP_IMAGE_TAG_GREEN=$sha$" "$dir/env" \
        && grep -q "^COMPOSE_PROFILES=blue$" "$dir/env" \
        && grep -q "^DEPLOY_NEW=значение$" "$dir/env" \
        && grep -q "^DEPLOY_COPY_DB=$" "$dir/env" \
        && [ "$(grep -c APP_IMAGE_TAG_GREEN "$dir/env")" = 1 ]; then
        say ".env правится на месте: закомментированный ключ раскрыт, соседи целы, пустое значение пишется"
    else
        nope ".env поправлен неверно: $(tr '\n' '|' < "$dir/env")"
    fi

    if [ "$bad" != 0 ]; then
        red "Самопроверка не прошла: $bad"
        return 1
    fi
    green "Самопроверка пройдена"
    return 0
}

if [ -n "${selftest_requested:-}" ]; then
    selftest
    exit $?
fi

if [ -n "$ABORT" ]; then
    abort
    exit $?
fi

deploy
