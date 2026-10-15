#!/usr/bin/env bash
# Восстановление одного арендатора.
#
#   ops/restore-tenant.sh t_000042 [каталог-набора] [файл-окружения]
#   ops/restore-tenant.sh --selftest     # без docker и ячейки: заглушка docker
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
# ЧЕМ ДОКАЗАН ВОЗВРАТ (задача 0238). Не кодом возврата и не числом, которое
# ни с чем не сравнили: расширения — ответом базы, снимки — счётом объектов
# клиента в хранилище после зеркала, содержимое — сверкой пяти агрегатов
# с описью набора (те же числа и тот же вердикт, что у ops/verify-backup.sh).
# Код 0 — всё сошлось; 1 — что-то не вернулось, и сказано что; 2 — вызов
# неправильный.
set -euo pipefail
case "$0" in
    /*) SELF="$0" ;;
    *)  SELF="$PWD/$0" ;;
esac
cd "$(dirname "$0")/.."
# Проверки по следу (расширения у базы, снимки на стороне хранилища)
# и оракул содержимого — опись набора с числами, снятыми вокруг дампа.
. ops/restore-checks.sh
. ops/backup-aggregates.sh

red()    { printf '\033[1;31m%s\033[0m\n' "$1"; }
yellow() { printf '\033[1;33m%s\033[0m\n' "$1"; }
usage() {
    printf 'Использование: %s СХЕМА [КАТАЛОГ-НАБОРА] [ФАЙЛ-ОКРУЖЕНИЯ]\n' "$0" >&2
    printf '               %s --selftest\n' "$0" >&2
    exit 2
}

# Разбор аргументов (задача 0238). Прежде его не было вовсе: первый аргумент
# становился именем схемы как есть, и `--selftest` искал дамп
# «…/--selftest.dump» — отказ был, но случайный, а не разбор. Образец —
# цикл while/case у ops/restore-pitr.sh: незнакомый флаг — код 2.
SELFTEST=0
POS=0
ARG_SCHEMA=""
ARG_SET=""
ARG_ENV=""
while [ $# -gt 0 ]; do
    case "$1" in
        --selftest) SELFTEST=1 ;;
        -*) printf 'Незнакомый флаг: %s — схему, набор и окружение называют без флагов\n' "$1" >&2; usage ;;   # 0238-флаг
        *)  POS=$((POS + 1))
            case "$POS" in
                1) ARG_SCHEMA="$1" ;;
                2) ARG_SET="$1" ;;
                3) ARG_ENV="$1" ;;
                *) printf 'Лишний аргумент: %s\n' "$1" >&2; usage ;;
            esac ;;
    esac
    shift
done

# --- Самопроверка ------------------------------------------------------------
#
# Весь путь скрипта на заглушке docker (ops/restore-checks.sh): набор,
# подтверждение, расширения, разворот, снимки, сверка. Каждый случай
# утверждает и код возврата, и слова — красное «не по той причине» здесь
# не засчитывается. Обратный край стоит первым: проверка, краснеющая
# на исправном возврате, будет снята в первую же аварию вместе с защитой.
restore_tenant_selftest() {
    local tmp bad=0 rc out set target="$SELF"
    tmp="$(mktemp -d)"
    restore_stub_docker "$tmp/bin"
    mkdir -p "$tmp/stub" "$tmp/photos/t_000042/parts" "$tmp/sets/20261015T030000Z"
    set="$tmp/sets/20261015T030000Z"
    printf 'DB_USER=app\nS3_BUCKET=parts-photos\n' > "$tmp/env"
    printf 'дамп\n' > "$set/t_000042.dump"
    printf 'jpg\n' > "$tmp/photos/t_000042/parts/a.jpg"
    printf 'jpg\n' > "$tmp/photos/t_000042/parts/b.jpg"

    _ok()  { printf '  ✓ %s\n' "$1"; }
    _bad() { printf '\033[1;31m  ✗ %s\033[0m\n' "$1"; printf '%s\n' "$out" | sed 's/^/      /'; bad=1; }
    says() { printf '%s' "$out" | grep -qF -- "$1"; }

    # Опись: те же строки, что пишет ops/backup.sh (agg_line).
    manifest() {   # $1 — с числами (yes/no)
        {
            echo "снято: 20261015T030000Z"
            echo "база: parts"
            echo "арендаторов: 1"
            echo "1 t_000042 demo ACTIVE"
            if [ "$1" = yes ]; then
                agg_line t_000042 part 10 10
                agg_line t_000042 part_stock_qty 7.000 7.000
                agg_line t_000042 stock_movement 5 5
                agg_line t_000042 deal 2 2
                agg_line t_000042 tenant_member 3 3
            fi
        } > "$set/manifest.txt"
    }
    # Здоровая ячейка: расширения стоят, числа развёрнутой схемы те же, что
    # в описи, в хранилище оба снимка. Случаи ниже портят по одной вещи.
    healthy() {
        rm -f "$tmp/stub/"*
        manifest yes
        {
            printf '%s\t%s\n' "$EXT_QUERY" 'ltree\npg_trgm\npgcrypto\nplpgsql'
            printf '%s\t%s\n' "$(agg_sql part t_000042)" 10
            printf '%s\t%s\n' "$(agg_sql part_stock_qty t_000042)" 7.000
            printf '%s\t%s\n' "$(agg_sql stock_movement t_000042)" 5
            printf '%s\t%s\n' "$(agg_sql deal t_000042)" 2
            printf '%s\t%s\n' "$(agg_sql tenant_member t_000042)" 3
        } > "$tmp/stub/answers"
        printf 't_000042/parts/a.jpg\nt_000042/parts/b.jpg\n' > "$tmp/stub/ls"
    }
    answer() {   # SQL ответ — подменить один ответ базы
        Q="$1" A="$2" awk -F'\t' 'BEGIN { OFS = "\t" } $1 == ENVIRON["Q"] { $2 = ENVIRON["A"] } { print }' \
            "$tmp/stub/answers" > "$tmp/stub/answers.new"
        mv "$tmp/stub/answers.new" "$tmp/stub/answers"
    }
    run() {   # аргументы скрипта; подтверждение — имя схемы
        set +e
        out="$(printf 't_000042\n' | PATH="$tmp/bin:$PATH" STUB="$tmp/stub" PHOTO_DIR="$tmp/photos" \
            bash "$target" "$@" 2>&1)"
        rc=$?
        set -e
    }

    # 1. ОБРАТНЫЙ КРАЙ: здоровый набор молчит и отвечает нулём.
    healthy
    run t_000042 "$set" "$tmp/env"
    if [ "$rc" = 0 ] && says 'содержимое сошлось с описью набора' \
        && says 'Снимков у клиента в хранилище: 2' && says 'ltree pg_trgm pgcrypto' \
        && ! says 'ОТКАЗ' && ! says 'НЕ '; then
        _ok 'здоровый набор: код 0, содержимое сошлось с описью, снимки посчитаны в хранилище'
    else
        _bad "здоровый набор обязан проходить молча и нулём (код $rc)"
    fi

    # 2. Дамп без движений склада: позиции на месте, журнала нет. Прежняя
    #    редакция печатала «позиций 10» зелёным и выходила нулём.
    healthy
    answer "$(agg_sql stock_movement t_000042)" 0
    run t_000042 "$set" "$tmp/env"
    if [ "$rc" = 1 ] && says 'РАСХОЖДЕНИЕ: t_000042.stock_movement' && says 'НЕ восстановлен'; then
        _ok 'дамп без движений склада: код 1, расхождение названо по таблице'
    else
        _bad "дамп без движений склада обязан краснеть (код $rc)"
    fi

    # 3. Таблицы в развёрнутой схеме нет вовсе: отказ запроса — потеря,
    #    а не «сверить не с чем».
    healthy
    answer "$(agg_sql deal t_000042)" '!ERR'
    run t_000042 "$set" "$tmp/env"
    if [ "$rc" = 1 ] && says 'НЕ СПРОСИТЬ: t_000042.deal' && ! says 'НЕ СВЕРЕНО'; then
        _ok 'развёрнутая схема без таблицы сделок: код 1, это потеря, а не «не сверено»'
    else
        _bad "таблица, о которой знает опись, обязана быть в развёрнутой схеме (код $rc)"
    fi

    # 4. Хранилище отнято: прежний счёт по каталогу на хосте не менялся вовсе.
    healthy
    : > "$tmp/stub/ls_fail"
    run t_000042 "$set" "$tmp/env"
    if [ "$rc" = 1 ] && says 'хранилище не ответило' && ! says 'Снимков у клиента в хранилище: 2'; then
        _ok 'хранилище отнято: код 1, число снимков перестало быть зелёным'
    else
        _bad "недоступное хранилище обязано краснеть (код $rc)"
    fi

    # 4б. Хранилище ответило, но объектов меньше, чем отправляли.
    healthy
    printf 't_000042/parts/a.jpg\n' > "$tmp/stub/ls"
    run t_000042 "$set" "$tmp/env"
    if [ "$rc" = 1 ] && says 'вернулись не все'; then
        _ok 'в хранилище меньше, чем отправляли: код 1, «вернулись не все»'
    else
        _bad "недоехавшие снимки обязаны краснеть (код $rc)"
    fi

    # 4в. Зеркало отказало — снимки не объявляются возвращёнными.
    healthy
    : > "$tmp/stub/mirror_fail"
    run t_000042 "$set" "$tmp/env"
    if [ "$rc" = 1 ] && says 'снимки НЕ возвращены'; then
        _ok 'отказ зеркала: код 1, снимки названы невозвращёнными'
    else
        _bad "отказ зеркала обязан краснеть (код $rc)"
    fi

    # 5. Расширения: одно не встало — шаг краснеет и называет КАКОЕ, а схема
    #    при этом НЕ удалена (проверка стоит до DROP SCHEMA).
    healthy
    answer "$EXT_QUERY" 'pg_trgm\npgcrypto\nplpgsql'
    run t_000042 "$set" "$tmp/env"
    if [ "$rc" = 1 ] && says 'в базе нет расширений: ltree' \
        && ! grep -q 'DROP SCHEMA' "$tmp/stub/calls"; then
        _ok 'не вставшее расширение: код 1, названо по имени, схема клиента не тронута'
    else
        _bad "не вставшее расширение обязано краснеть до удаления схемы (код $rc)"
    fi

    # 6. Третий исход: в описи нет чисел — не зелень и не красное, а слова.
    healthy
    manifest no
    run t_000042 "$set" "$tmp/env"
    if [ "$rc" = 0 ] && says 'НЕ СВЕРЕНО' && ! says 'содержимое сошлось'; then
        _ok 'опись без чисел: «не сверено» словами, не выдано за сошедшееся'
    else
        _bad "набор без чисел обязан называться несверенным (код $rc)"
    fi

    # 7. Чужой флаг отбивается кодом 2, а не становится именем схемы.
    healthy
    run --bogus "$set" "$tmp/env"
    if [ "$rc" = 2 ] && says 'Незнакомый флаг: --bogus' && ! [ -s "$tmp/stub/calls" ]; then
        _ok 'чужой флаг: код 2, до docker дело не дошло'
    else
        _bad "чужой флаг обязан отбиваться кодом 2 (код $rc)"
    fi
    run t_000042 "$set" "$tmp/env" lishniy
    if [ "$rc" = 2 ] && says 'Лишний аргумент: lishniy'; then
        _ok 'лишний аргумент: код 2'
    else
        _bad "лишний аргумент обязан отбиваться кодом 2 (код $rc)"
    fi

    # 8. ВОЗВРАТ ДЕФЕКТА: по подделке на находку. Копия — в .scratch/ (тот же
    #    уровень, что ops/, корень репозитория разрешается туда же), подделка
    #    ставится по метке строки, а не по номеру. Каждая обязана валить
    #    самопроверку; ребёнку случай выключен — рекурсии нет.
    if [ -z "${RESTORE_SELFTEST_CHILD:-}" ]; then
        mkdir -p .scratch
        local fake copy
        for fake in флаг расширения снимки содержимое; do
            copy=".scratch/restore-tenant-fake-${fake}-0238.sh"
            case "$fake" in
                флаг)       sed '/# 0238-флаг$/d' "$SELF" > "$copy" ;;
                расширения) sed 's|^if ! extensions_present_check .*# 0238-расширения$|if false; then|' "$SELF" > "$copy" ;;
                снимки)     sed 's|^        if photos_back_check .*# 0238-снимки$|        GOT="$SENT"; if true; then|' "$SELF" > "$copy" ;;
                содержимое) sed 's|^agg_check_schema .*# 0238-содержимое$|AGG_SUMMARY="позиций $(tq "$(agg_sql part "$SCHEMA")")"|' "$SELF" > "$copy" ;;
            esac
            if cmp -s "$SELF" "$copy"; then
                printf '\033[1;31m  ✗ подделку «%s» негде поставить — метка строки потерялась\033[0m\n' "$fake"; bad=1
            elif ! bash -n "$copy" 2>/dev/null; then
                printf '\033[1;31m  ✗ подделка «%s» сломала синтаксис — её красное скажет не о том\033[0m\n' "$fake"; bad=1
            elif RESTORE_SELFTEST_CHILD=1 bash "$copy" --selftest > /dev/null 2>&1; then
                printf '\033[1;31m  ✗ ВОЗВРАТ ДЕФЕКТА «%s»: прежнее поведение самопроверку НЕ валит\033[0m\n' "$fake"; bad=1
            else
                _ok "возврат дефекта «${fake}»: прежнее поведение валит самопроверку"
            fi
            rm -f "$copy"
        done
    fi

    rm -rf "$tmp"
    [ "$bad" = 0 ]
}

if [ "$SELFTEST" = 1 ]; then
    [ "$POS" = 0 ] || usage
    printf '\n\033[1;34m==> Самопроверка ops/restore-tenant.sh (заглушка docker)\033[0m\n'
    if restore_tenant_selftest; then
        printf '\033[1;32mСамопроверка пройдена: путь возврата клиента на заглушке. Ячейка не трогалась.\033[0m\n'
        exit 0
    fi
    red 'Самопроверка не прошла'
    exit 1
fi

SCHEMA="${ARG_SCHEMA:?укажите схему, например t_000042}"
ENV_FILE="${ARG_ENV:-${ENV_FILE:-.env}}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

# После чтения окружения: APP_CELL приезжает оттуда.
SET_DIR="${ARG_SET:-$(find "${BACKUP_DIR:-./backups/${APP_CELL:-cell01}}" -maxdepth 1 -type d -name '20*' | sort | tail -1)}"

COMPOSE="docker compose -f docker-compose.prod.yml --env-file $ENV_FILE"
DB_USER="${DB_USER:?укажите DB_USER}"
DUMP="$SET_DIR/$SCHEMA.dump"

[ -f "$DUMP" ] || { printf '\033[1;31mНет дампа: %s\033[0m\n' "$DUMP"; exit 1; }

# Разворачивать — в базу сборки под трафиком (задача 0112): с первой выкладки
# копией у ячейки баз несколько, и литерал «parts» вернул бы клиента в прежнюю
# замороженную, где его не видит ни одна работающая сборка.
DB_NAME=$(ENV_FILE="$ENV_FILE" ops/switch-build.sh --current-db) \
    || { printf '\033[1;31mНе узнать базу сборки под трафиком\033[0m\n'; exit 1; }

# Спросить развёрнутую базу: им же сверяется содержимое (agg_check_schema).
tq() { $COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1"; }

printf 'Схема:  %s\n' "$SCHEMA"
printf 'База:   %s (сборки под трафиком)\n' "$DB_NAME"
printf 'Набор:  %s (снят %s)\n' "$SET_DIR" "$(basename "$SET_DIR")"
printf '\n\033[1;31mСхема %s будет удалена и восстановлена из набора.\033[0m\n' "$SCHEMA"
printf 'Всё, что клиент завёл после снятия набора, пропадёт.\n'
printf 'Введите имя схемы для подтверждения: '
read -r CONFIRM
[ "$CONFIRM" = "$SCHEMA" ] || { echo "Отменено."; exit 1; }

# Расширения — ДО разворота и ДО удаления схемы. В дампе схемы их нет
# по построению, а ставит их тот, кто восстанавливает: почему не дамп и не
# приложение, расписано в ops/restore-extensions.sql. На живой базе они уже
# стоят, и шаг ничего не делает; на свежей (новая машина, база заведена
# руками) без них разворот падает на первой же таблице — `gen_random_bytes
# does not exist`.
#
# Встали ли они — спрашивается у базы (задача 0238): нулевой код psql значит
# «команды выполнились», а не «расширения есть». И спрашивается ДО DROP SCHEMA:
# отказ здесь оставляет клиента с его прежней схемой, а не без схемы вовсе.
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 \
    -f /dev/stdin < "$EXT_SQL" >/dev/null
EXT_NOW="$(tq "$EXT_QUERY" 2>/dev/null)" || EXT_NOW=""
if ! extensions_present_check "$EXT_NOW" "$(ours_extensions)"; then   # 0238-расширения
    red "ОТКАЗ: $EXT_WHY"
    red "Схема $SCHEMA НЕ тронута: без расширений разворот упадёт на первой же таблице."
    exit 1
fi
printf 'Расширения в базе: %s\n' "$EXT_FOUND"

# Пробел после "$DB_NAME" обязателен, и его тут не было с задачи 0112:
# заменив литерал «parts» переменной, склеили аргументы — `-d parts-v`
# и `-d parts--no-owner`. Оба инструмента читают это как ИМЯ БАЗЫ и отвечают
# «database "parts-v" does not exist», то есть скрипт умирал на первой же
# команде. Проверено живым прогоном, обе строки.
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 \
    -c "DROP SCHEMA IF EXISTS $SCHEMA CASCADE" >/dev/null

$COMPOSE exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner < "$DUMP"

# Фотографии возвращаются вместе с базой. Схема-на-арендатора и префикс
# арендатора в ключе S3 выбирались ради одного и того же — вернуть одного
# клиента, — и восстановленный склад со ссылками в никуда этого не делает:
# карточки на месте, а смотреть покупателю не на что.
#
# Ключи неизменяемы, поэтому копируется только недостающее, и без --remove:
# снимки, появившиеся после снятия набора, останутся. Их карточек в схеме
# уже нет, то есть это мусор на диске, — а удаление означало бы потерю
# того, чего в наборе не было и восстановить нечем.
#
# «Снимков возвращено: N» считалось по каталогу-ИСТОЧНИКУ на хосте (задача
# 0238), то есть называло, что собирались отправить: отнятое хранилище числа
# не меняло вовсе. Теперь считаем объекты клиента в хранилище ПОСЛЕ зеркала,
# а отказ зеркала больше не роняет скрипт молча на set -e — он назван и даёт
# ненулевой код в конце, как у ops/restore-cell.sh (PHOTOS_FAILED): база при
# этом уже развёрнута, и сверить её надо всё равно.
PHOTOS="${PHOTO_DIR:-$(dirname "$SET_DIR")/photos}/$SCHEMA"
PHOTOS_FAILED=0
if [ -d "$PHOTOS" ]; then
    SENT=$(find "$PHOTOS" -type f | wc -l | tr -d ' ')
    if ! $COMPOSE run --rm -v "$(cd "$PHOTOS" && pwd):/restore" mc \
        mirror --overwrite --quiet /restore "cell/${S3_BUCKET:-parts-photos}/$SCHEMA" > /dev/null; then
        red "Зеркало не поднялось — снимки НЕ возвращены (mc, бакет ${S3_BUCKET:-parts-photos})"
        PHOTOS_FAILED=1
    else
        GOT=$($COMPOSE run --rm mc ls --recursive "cell/${S3_BUCKET:-parts-photos}/$SCHEMA/" 2>/dev/null \
            | wc -l | tr -d ' ') || GOT=""
        if photos_back_check "$SENT" "$GOT"; then   # 0238-снимки
            printf 'Снимков у клиента в хранилище: %s (из копии отправлено %s)\n' "$GOT" "$SENT"
        else
            red "Снимки НЕ подтверждены: $PHOTOS_BACK_WHY"
            PHOTOS_FAILED=1
        fi
    fi
else
    yellow "Снимков в копии нет: $PHOTOS — карточки будут без фотографий"
fi

# Содержимое — с описью набора (задача 0238). Прежде файл кончался строкой
# «Восстановлено: …, позиций N»: count(*) по ОДНОЙ таблице, напечатанный
# зелёным и не сравненный ни с чем, — дамп без журнала движений давал ту же
# строку. Оракул тот же, что у еженедельной проверки бэкапа (числа, снятые
# вокруг дампа), а не живая база: схему мы только что пересоздали, и спросить
# «как было» больше не у кого.
CONTENT=0
AGG_LOG="$(mktemp)"
agg_check_schema "$SET_DIR/manifest.txt" "$SCHEMA" tq > "$AGG_LOG" || CONTENT=$?   # 0238-содержимое
case "$CONTENT" in
    0) printf '\n\033[1;32mВосстановлено: %s — содержимое сошлось с описью набора: %s\033[0m\n' \
           "$SCHEMA" "$AGG_SUMMARY" ;;
    2) sed 's/^/    /' "$AGG_LOG"
       printf '\n\033[1;33mСхема %s развёрнута, но содержимое НЕ СВЕРЕНО: сравнить не с чем.\033[0m\n' "$SCHEMA" ;;
    *) printf '\033[1;31m%s\033[0m\n' "$(sed 's/^/    /' "$AGG_LOG")"
       printf '\n\033[1;31mСхема %s развёрнута, но клиент НЕ восстановлен: содержимое не сошлось с описью набора.\033[0m\n' "$SCHEMA" ;;
esac
rm -f "$AGG_LOG"
printf 'Остальные арендаторы не затронуты.\n'

# Красное по снимкам переживает зелёную строку про базу: склад без
# фотографий продавать нельзя, и выйти нулём значило бы объявить возврат
# законченным (тот же довод, что у ops/restore-cell.sh).
if [ "$PHOTOS_FAILED" = 1 ]; then
    red 'Но снимки не возвращены — см. выше.'
fi
[ "$CONTENT" != 1 ] && [ "$PHOTOS_FAILED" = 0 ] || exit 1
