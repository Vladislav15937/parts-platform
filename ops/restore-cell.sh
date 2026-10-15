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
#
#   ops/restore-cell.sh --selftest     # без docker и ячейки: заглушка docker
#
# ЧЕМ ДОКАЗАН ВОЗВРАТ (задача 0238): расширения — ответом базы, а не списком,
# напечатанным после psql с выводом в /dev/null; каждая схема арендатора —
# сверкой пяти агрегатов с описью набора (те же числа и тот же вердикт, что
# у ops/verify-backup.sh). Код 0 — сошлось; 1 — не вернулось, и сказано что;
# 2 — вызов неправильный.
set -euo pipefail
case "$0" in
    /*) SELF="$0" ;;
    *)  SELF="$PWD/$0" ;;
esac
cd "$(dirname "$0")/.."
. ops/restore-checks.sh
. ops/backup-aggregates.sh

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    %s\033[0m\n' "$1"; }
red()  { printf '\033[1;31m    %s\033[0m\n' "$1"; }
usage() {
    printf 'Использование: %s [КАТАЛОГ-НАБОРА] [ФАЙЛ-ОКРУЖЕНИЯ]\n' "$0" >&2
    printf '               %s --selftest\n' "$0" >&2
    exit 2
}

# Разбор аргументов (задача 0238). Прежде его не было вовсе, и `--selftest`
# становился каталогом набора — «Набор не найден: --selftest»: отказ был,
# но случайный. Образец — цикл while/case у ops/restore-pitr.sh.
SELFTEST=0
POS=0
ARG_SET=""
ARG_ENV=""
while [ $# -gt 0 ]; do
    case "$1" in
        --selftest) SELFTEST=1 ;;
        -*) printf 'Незнакомый флаг: %s — набор и окружение называют без флагов\n' "$1" >&2; usage ;;   # 0238-флаг
        *)  POS=$((POS + 1))
            case "$POS" in
                1) ARG_SET="$1" ;;
                2) ARG_ENV="$1" ;;
                *) printf 'Лишний аргумент: %s\n' "$1" >&2; usage ;;
            esac ;;
    esac
    shift
done

# --- Самопроверка ------------------------------------------------------------
#
# Весь путь на заглушке docker (ops/restore-checks.sh). Обратный край первым:
# проверка, краснеющая на исправном возврате, будет снята в первую же аварию.
restore_cell_selftest() {
    local tmp bad=0 rc out set
    tmp="$(mktemp -d)"
    restore_stub_docker "$tmp/bin"
    mkdir -p "$tmp/stub" "$tmp/backups/20261015T030000Z"
    set="$tmp/backups/20261015T030000Z"
    printf 'DB_USER=app\nBACKUP_DIR=%s\n' "$tmp/backups" > "$tmp/env"
    printf 'дамп\n' > "$set/shared.dump"
    printf 'дамп\n' > "$set/t_000042.dump"

    _ok()  { printf '  ✓ %s\n' "$1"; }
    _bad() { printf '\033[1;31m  ✗ %s\033[0m\n' "$1"; printf '%s\n' "$out" | sed 's/^/      /'; bad=1; }
    says() { printf '%s' "$out" | grep -qF -- "$1"; }
    healthy() {
        rm -f "$tmp/stub/"*
        {
            echo "снято: 20261015T030000Z"
            echo "база: parts"
            echo "арендаторов: 1"
            echo "1 t_000042 demo ACTIVE"
            agg_line t_000042 part 10 10
            agg_line t_000042 part_stock_qty 7.000 7.000
            agg_line t_000042 stock_movement 5 5
            agg_line t_000042 deal 2 2
            agg_line t_000042 tenant_member 3 3
        } > "$set/manifest.txt"
        {
            printf '%s\t%s\n' "SELECT count(*) FROM pg_database WHERE datname = 'parts'" 1
            printf '%s\t%s\n' "$EXT_QUERY" 'ltree\npg_trgm\npgcrypto\nplpgsql'
            printf '%s\t%s\n' "$(agg_sql part t_000042)" 10
            printf '%s\t%s\n' "$(agg_sql part_stock_qty t_000042)" 7.000
            printf '%s\t%s\n' "$(agg_sql stock_movement t_000042)" 5
            printf '%s\t%s\n' "$(agg_sql deal t_000042)" 2
            printf '%s\t%s\n' "$(agg_sql tenant_member t_000042)" 3
            printf '%s\t%s\n' "SELECT count(*) FROM public.tenant_registry WHERE status IN ('ACTIVE','SUSPENDED')" 1
            printf '%s\t%s\n' 'SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE '"'"'t\_%'"'" 1
        } > "$tmp/stub/answers"
    }
    answer() {   # SQL ответ — подменить один ответ базы
        Q="$1" A="$2" awk -F'\t' 'BEGIN { OFS = "\t" } $1 == ENVIRON["Q"] { $2 = ENVIRON["A"] } { print }' \
            "$tmp/stub/answers" > "$tmp/stub/answers.new"
        mv "$tmp/stub/answers.new" "$tmp/stub/answers"
    }
    run() {
        set +e
        out="$(PATH="$tmp/bin:$PATH" STUB="$tmp/stub" bash "$SELF" "$@" 2>&1 < /dev/null)"
        rc=$?
        set -e
    }

    # 1. ОБРАТНЫЙ КРАЙ: здоровый набор — код 0, расширения названы ответом базы,
    #    содержимое схемы сошлось с описью.
    healthy
    run "$set" "$tmp/env"
    if [ "$rc" = 0 ] && says 'в базе: ltree pg_trgm pgcrypto' \
        && says 't_000042 — сошлось с описью набора' && says 'Ячейка восстановлена' \
        && ! says 'ОТКАЗ'; then
        _ok 'здоровый набор: код 0, расширения спрошены у базы, содержимое сошлось с описью'
    else
        _bad "здоровый набор обязан проходить нулём (код $rc)"
    fi

    # 2. Расширение не встало: прежде печаталось «pg_trgm, ltree, pgcrypto»
    #    жёстко вписанным текстом. Шаг краснеет и называет КАКОЕ.
    healthy
    answer "$EXT_QUERY" 'pg_trgm\npgcrypto\nplpgsql'
    run "$set" "$tmp/env"
    if [ "$rc" = 1 ] && says 'в базе нет расширений: ltree' && ! says 'Ячейка восстановлена' \
        && ! grep -q pg_restore "$tmp/stub/calls"; then
        _ok 'не вставшее расширение: код 1, названо по имени, до разворота не дошло'
    else
        _bad "не вставшее расширение обязано краснеть и называться (код $rc)"
    fi

    # 3. Дамп без движений склада: реестр и схемы сходятся, а журнала нет.
    healthy
    answer "$(agg_sql stock_movement t_000042)" 0
    run "$set" "$tmp/env"
    if [ "$rc" = 1 ] && says 'РАСХОЖДЕНИЕ: t_000042.stock_movement' && ! says 'Ячейка восстановлена'; then
        _ok 'дамп без движений склада: код 1, расхождение названо по схеме и таблице'
    else
        _bad "дамп без движений склада обязан краснеть (код $rc)"
    fi

    # 4. Чужой флаг и лишний аргумент — код 2, до docker не доходит.
    healthy
    run --bogus "$tmp/env"
    if [ "$rc" = 2 ] && says 'Незнакомый флаг: --bogus' && ! [ -s "$tmp/stub/calls" ]; then
        _ok 'чужой флаг: код 2, до docker дело не дошло'
    else
        _bad "чужой флаг обязан отбиваться кодом 2 (код $rc)"
    fi
    run "$set" "$tmp/env" lishniy
    if [ "$rc" = 2 ] && says 'Лишний аргумент: lishniy'; then
        _ok 'лишний аргумент: код 2'
    else
        _bad "лишний аргумент обязан отбиваться кодом 2 (код $rc)"
    fi

    # 5. ВОЗВРАТ ДЕФЕКТА: копия в .scratch/, подделка по метке строки.
    if [ -z "${RESTORE_SELFTEST_CHILD:-}" ]; then
        mkdir -p .scratch
        local fake copy
        for fake in флаг расширения содержимое; do
            copy=".scratch/restore-cell-fake-${fake}-0238.sh"
            case "$fake" in
                флаг)       sed '/# 0238-флаг$/d' "$SELF" > "$copy" ;;
                расширения) sed 's|^if ! extensions_present_check .*# 0238-расширения$|if false; then|' "$SELF" > "$copy" ;;
                содержимое) sed 's|^    agg_check_schema .*# 0238-содержимое$|    CONTENT=0|' "$SELF" > "$copy" ;;
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
    printf '\n\033[1;34m==> Самопроверка ops/restore-cell.sh (заглушка docker)\033[0m\n'
    if restore_cell_selftest; then
        printf '\033[1;32mСамопроверка пройдена: путь возврата ячейки на заглушке. Ячейка не трогалась.\033[0m\n'
        exit 0
    fi
    printf '\033[1;31mСамопроверка не прошла\033[0m\n'
    exit 1
fi

SET_DIR="$ARG_SET"
ENV_FILE="${ARG_ENV:-${ENV_FILE:-.env}}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

: "${DB_USER:?укажите DB_USER}"
BACKUPS="${BACKUP_DIR:-./backups/${APP_CELL:-cell01}}"
[ -n "$SET_DIR" ] || SET_DIR=$(find "$BACKUPS" -maxdepth 1 -type d -name '20*' | sort | tail -1)
[ -d "$SET_DIR" ] || { echo "Набор не найден: $SET_DIR"; exit 1; }

COMPOSE="docker compose -f docker-compose.prod.yml"

# Разворачивать — в базу сборки под трафиком (задача 0112): её имя записано
# в .env и с первой выкладки копией уже не «parts». На новой машине такой базы
# ещё нет — Postgres при первом запуске заводит только «parts», — поэтому она
# создаётся здесь: развёрнутая в «parts», ячейка поднялась бы пустой, потому
# что сборка смотрит в базу по имени из .env.
DB_NAME=$(ENV_FILE="$ENV_FILE" ops/switch-build.sh --current-db) \
    || { echo "Не узнать базу сборки под трафиком (ops/switch-build.sh --current-db)"; exit 1; }

STARTED=$(date +%s)
step "Набор $SET_DIR"
[ -f "$SET_DIR/manifest.txt" ] && head -3 "$SET_DIR/manifest.txt" | sed 's/^/    /'

step "База $DB_NAME"
if [ "$($COMPOSE exec -T postgres psql -U "$DB_USER" -d postgres -tAc \
        "SELECT count(*) FROM pg_database WHERE datname = '$DB_NAME'")" = 0 ]; then
    $COMPOSE exec -T postgres psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 \
        -c "CREATE DATABASE \"$DB_NAME\"" >/dev/null
    ok "создана: её называет .env, а Postgres при первом запуске заводит только «parts»"
else
    ok "есть"
fi

step "Расширения Postgres"
# pg_dump выгружает схемы, а расширения принадлежат базе — в дампе схем
# их нет. Без них разворот арендатора падает на первой же таблице:
# «function public.gen_random_bytes(integer) does not exist», потому что
# public_code выдаётся умолчанием колонки через pgcrypto. Найдено репетицией
# восстановления: до неё ячейка после потери диска просто не поднималась,
# и узналось бы это в тот единственный день, когда это нужно.
#
# Список переехал в ops/restore-extensions.sql (задача 0157): тем же списком
# теперь пользуются восстановление одного клиента и еженедельная проверка
# бэкапа, а два списка расширений разошлись бы молча.
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 \
    -f /dev/stdin < "$EXT_SQL" >/dev/null
# Встали ли — спрашиваем у базы (задача 0238). Прежде следующей строкой
# печаталось «pg_trgm, ltree, pgcrypto» жёстко вписанным текстом: вывод psql
# уходил в /dev/null, и три имени, которых никто не спрашивал, стояли на месте
# ответа. Запрос к pg_extension один на проект (EXT_QUERY).
cq() { $COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1"; }
EXT_NOW="$(cq "$EXT_QUERY" 2>/dev/null)" || EXT_NOW=""
if ! extensions_present_check "$EXT_NOW" "$(ours_extensions)"; then   # 0238-расширения
    red "ОТКАЗ: $EXT_WHY"
    red "дальше идти нельзя: без них разворот упадёт на первой же таблице"
    exit 1
fi
ok "в базе: $EXT_FOUND"

step "Общие схемы"
# --clean не нужен: разворот идёт в пустую базу. Отказ «schema public
# already exists» безобиден — эта схема есть в любой базе с рождения,
# поэтому ошибки разбираем по итогу, а не по коду возврата.
# `|| true` глушил ВЕСЬ код возврата, а комментарий выше объясняет отбрасывание
# ровно ОДНОЙ безобидной ошибки — и следом печаталось «реестр и справочники
# подняты» безусловно. Это дословно дефект задачи 0157, вернувшийся на аварийном
# пути: там такое же глушение спрятало 13 настоящих ошибок (тип ltree не встал,
# `catalog.part_category` не создалась вовсе, индексов вышло 31 вместо 37), и всё
# это выглядело успехом. Разбираем по итогу, как и обещано, но теперь по-настоящему.
SHARED_ERR=$($COMPOSE exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner \
    < "$SET_DIR/shared.dump" 2>&1 \
    | grep -E 'error:|ERROR:' \
    | grep -v 'schema "public" already exists' \
    | grep -v 'CREATE SCHEMA public' || true)
if [ -n "$SHARED_ERR" ]; then
    printf '\033[1;31m    ОТКАЗ: общие схемы развернулись с ошибками — ячейка НЕ восстановлена\033[0m\n'
    printf '%s\n' "$SHARED_ERR" | head -8 | sed 's/^/      /'
    printf '\033[1;31m    дальше идти нельзя: без реестра и справочников дамп арендатора не развернуть\033[0m\n'
    exit 1
fi
ok "реестр и справочники подняты"

step "Арендаторы"
COUNT=0
# Содержимое каждой схемы — с описью набора (задача 0238): тот же оракул, что
# чуть ниже сверяет число арендаторов, распространённый на то, что в схемах
# лежит. Прежде схема считалась вернувшейся, раз pg_restore вышел нулём, —
# дамп без журнала движений давал ту же строку «t_000042».
CONTENT_FAILED=""
CONTENT_UNVERIFIED=""
AGG_LOG="$(mktemp)"
for dump in "$SET_DIR"/t_*.dump; do
    [ -e "$dump" ] || break
    schema=$(basename "$dump" .dump)
    $COMPOSE exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner < "$dump"
    COUNT=$((COUNT + 1))
    CONTENT=0
    agg_check_schema "$SET_DIR/manifest.txt" "$schema" cq > "$AGG_LOG" || CONTENT=$?   # 0238-содержимое
    case "$CONTENT" in
        0) ok "$schema — сошлось с описью набора: $AGG_SUMMARY" ;;
        2) printf '\033[1;33m    %s — содержимое НЕ СВЕРЕНО: в описи набора нет чисел\033[0m\n' "$schema"
           CONTENT_UNVERIFIED="$CONTENT_UNVERIFIED $schema" ;;
        *) printf '\033[1;31m%s\033[0m\n' "$(sed 's/^/    /' "$AGG_LOG")"
           CONTENT_FAILED="$CONTENT_FAILED $schema" ;;
    esac
done
rm -f "$AGG_LOG"
# «Восстановлено арендаторов: 0» с последующим «Ячейка восстановлена» — ровно
# искомый класс: число печаталось и ни с чем не сверялось. Оракул лежит рядом,
# в описи самого набора (её пишет ops/backup.sh строкой «арендаторов: N»).
# Сверять надо с ней, а не с нулём: пустой набор законен у ячейки без клиентов,
# а набор, в котором дампов меньше, чем было снято, — это потеря, и сказать
# о ней надо до того, как человек объявит аварию закрытой.
WANT=$(sed -n 's/^арендаторов: \([0-9]\{1,\}\)$/\1/p' "$SET_DIR/manifest.txt" 2>/dev/null | head -1)
if [ -z "$WANT" ]; then
    ok "восстановлено арендаторов: $COUNT (сверить не с чем: описи набора нет)"
elif [ "$COUNT" = "$WANT" ]; then
    ok "восстановлено арендаторов: $COUNT — сошлось с описью набора"
else
    printf '\033[1;31m    ОТКАЗ: развёрнуто арендаторов %s, а в описи набора %s\033[0m\n' \
        "$COUNT" "$WANT"
    printf '\033[1;31m    ячейка НЕ восстановлена целиком: в наборе не хватает дампов схем\033[0m\n'
    exit 1
fi

step "Фотографии"
MIRROR="$BACKUPS/photos"
if [ -d "$MIRROR" ]; then
    # «Снимки возвращены из зеркала» печаталось БЕЗУСЛОВНО, то есть при отказе
    # человек видел рядом две строки: «зеркало не поднялось» и «снимки
    # возвращены». Вторая читается как итог и отменяет первую.
    if $COMPOSE exec -T mc sh -c "mc mirror --overwrite /backup/photos local/${S3_BUCKET:-parts-photos}"; then
        ok "снимки возвращены из зеркала"
    else
        printf '\033[1;31m    зеркало не поднялось — снимки НЕ возвращены (проверьте mc и путь %s)\033[0m\n' \
            "$MIRROR"
        printf '\033[1;31m    склад со ссылками в никуда — это не возвращённый клиент\033[0m\n'
        PHOTOS_FAILED=1
    fi
else
    echo "    зеркала нет: склад поднимется без фотографий"
fi

step "Права рабочей роли"
if [ -n "${APP_RUNTIME_ROLE:-}" ]; then
    echo "    выдаст приведение схем к версии образа: ops/schema-sync.sh --apply"
else
    echo "    разделения ролей нет — пропускаем"
fi

step "Проверка"
# Оба числа печатались и НЕ сравнивались ни с чем — расхождение «в реестре 42,
# схем 7» проходило зелёным, при том что обе цифры уже стояли на экране. Реестр
# называет клиентов ячейки, схемы — то, что действительно поднялось; сверка
# и есть ответ на вопрос «ячейка восстановлена целиком?».
#
# Статусы взяты ровно те, что выгружает ops/backup.sh: клиент, помеченный
# удалённым, дампа не имеет законно, и требовать под него схему значило бы
# краснеть на исправном восстановлении.
REG=$($COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "SELECT count(*) FROM public.tenant_registry WHERE status IN ('ACTIVE','SUSPENDED')" \
    | tr -d '[:space:]')
SCH=$($COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE 't\_%'" \
    | tr -d '[:space:]')
printf '    арендаторов в реестре (ACTIVE/SUSPENDED): %s\n' "$REG"
printf '    схем в базе: %s\n' "$SCH"
if [ "$REG" != "$SCH" ]; then
    printf '\033[1;31m    РАСХОЖДЕНИЕ: в реестре %s, а схем %s — часть клиентов не развёрнута\033[0m\n' \
        "$REG" "$SCH"
    printf '\033[1;31m    ячейка НЕ восстановлена целиком; ищите недостающие дампы в наборе\033[0m\n'
    exit 1
fi
ok "реестр и схемы сошлись: $REG"
# Число схем сошлось — а что в них лежит, сверено выше по описи. Не сошлось
# хоть по одной — ячейка НЕ восстановлена, и зелёной строки ниже быть не должно.
if [ -n "$CONTENT_FAILED" ]; then
    red "ОТКАЗ: содержимое не сошлось с описью набора:$CONTENT_FAILED"
    red "ячейка НЕ восстановлена целиком: схемы есть, но в них не то, что снимали"
    exit 1
fi
if [ -n "$CONTENT_UNVERIFIED" ]; then
    printf '\033[1;33m    содержимое НЕ СВЕРЕНО (в описи нет чисел):%s\033[0m\n' "$CONTENT_UNVERIFIED"
fi

printf '\n\033[1;32mЯчейка восстановлена за %d с.\033[0m\n' "$(( $(date +%s) - STARTED ))"

# Снимки — отдельное утверждение, и красный код возврата обязан переживать
# зелёную строку выше: база восстановлена, а склад без фотографий продавать
# нельзя. Молча выйти нулём значило бы объявить аварию закрытой.
if [ "${PHOTOS_FAILED:-0}" = 1 ]; then
    printf '\033[1;31mНо снимки не возвращены — см. шаг «Фотографии» выше.\033[0m\n'
    exit 1
fi

# ЭТО ПОСЛЕДНИЕ СТРОКИ ПУТИ, КОТОРЫЙ ПРОХОДЯТ В АВАРИЮ И С ОДНОЙ ПОПЫТКИ,
# и до задачи 0210 они советовали ops/migrate-tenants.sh — путь, требующий
# APP_PROVISIONING_TOKEN безусловно (он падает на пустом секрете раньше, чем
# успевает что-нибудь спросить). А docs/onboarding.md (раздел 2) велит секрет
# выключать сразу после подключения клиента: значит на правильно настроенной
# ячейке совет был невыполним, и отказ говорил про переменную окружения
# человеку, который только что вернул кластер из копии и ищет, что он сделал
# не так. Команда лежит одним местом — TenantMigrations.MIGRATE_COMMAND
# (задача 0204); шелл её оттуда прочитать не может, поэтому здесь копия
# словами, а сверяет их с тем местом MigrateCommandAdviceTest.
echo "Дальше:"
echo "  1. ops/schema-sync.sh --apply — приведёт схемы арендаторов к версии"
echo "     образа и выдаст права рабочей роли. Это одноразовый контейнер того"
echo "     же образа: работающее приложение ему не нужно, секрет провижининга"
echo "     (APP_PROVISIONING_TOKEN) тоже — выключенным он и должен быть"
echo "     (docs/onboarding.md, раздел 2)."
echo "  2. поднять приложение и спросить /actuator/readiness: journals"
echo "     «защищены», schemas — без отставших."
