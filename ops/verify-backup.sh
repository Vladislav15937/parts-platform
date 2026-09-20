#!/usr/bin/env bash
# Проверка бэкапа восстановлением.
#
#   ops/verify-backup.sh [каталог-набора] [файл-окружения]
#   ops/verify-backup.sh --selftest        # проверка самой проверки, без docker
#
# «Дамп снялся» — это не бэкап. Бэкап — это когда из него поднялся арендатор
# и в нём тот же склад. Проверяется именно это: набор разворачивается
# в отдельную базу, и агрегаты сверяются с живой.
#
# Вместе с базой проверяются фотографии: каждая карточка ссылается на объект
# в хранилище, и восстановленный склад без снимков — это не восстановленный
# клиент, а список наименований. Сверяются не количества файлов, а сами
# ключи: совпадение чисел ничего не значит, если файлы другие.
#
# Восстановление идёт в ОТДЕЛЬНУЮ базу, а не в ту же под другим именем схемы:
# pg_restore кладёт схему туда, откуда её сняли, переименовать на лету нечем.
# Заодно это ближе к настоящему восстановлению — на чистый кластер.
#
# ОТКАЗ ВОССТАНОВЛЕНИЯ НЕ ГЛУШИТСЯ (задача 0157). До неё обе строки разворота
# кончались `>/dev/null 2>&1 || true`, и проверка жаловалась на СЛЕДСТВИЕ —
# «relation "t_000042.part" does not exist», — потому что таблиц не было вовсе.
# Человек из-за этого полчаса искал не там. Инструмент, называющий причину,
# которой он не проверял, хуже молчащего: настоящая причина лежала в выводе
# pg_restore, который выбрасывался.
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

# Список расширений — один на восстановление ячейки, восстановление одного
# клиента и эту проверку. Почему их ставит восстанавливающий, а не дамп
# и не приложение — в самом файле.
EXT_SQL="ops/restore-extensions.sql"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    OK: %s\033[0m\n' "$1"; }
fail() { printf '\033[1;31m    ПРОВАЛ: %s\033[0m\n' "$1"; exit 1; }

# --- разбор вывода pg_restore ------------------------------------------------
#
# Вынесено отдельными функциями не ради красоты: так их можно прогнать
# на подложенных выводах без docker, базы и набора (--selftest ниже).
# Проверка, которая не краснеет на дефекте, хуже отсутствующей, а установить
# это можно только попыткой.

# Настоящие ошибки разворота. Безобидна ровно одна: схема public есть в любой
# базе с рождения, и pg_restore спотыкается о неё при развороте shared.dump
# в чистую базу. Так же её отбрасывает ops/restore-cell.sh.
restore_errors() {
    grep -E 'error:|ERROR:' "$1" 2>/dev/null \
        | grep -v 'schema "public" already exists' \
        | grep -v 'CREATE SCHEMA public' || true
}

# Годен ли разворот: код возврата, файл с выводом, что разворачивали.
# Печатает НАСТОЯЩУЮ причину и сам вывод pg_restore — то, что раньше
# выбрасывалось в /dev/null.
restore_verdict() {
    local rc="$1" out="$2" what="$3" errs total
    errs="$(restore_errors "$out")"
    # Ненулевой код при отсутствии настоящих ошибок — это «schema public
    # already exists» и ничего больше. Красить проверку этим значило бы
    # краснеть каждую неделю на безобидном, а такую проверку отключают.
    if [ -z "${errs}" ]; then
        return 0
    fi
    total="$(printf '%s\n' "${errs}" | grep -c . || true)"
    printf 'ОТКАЗ ВОССТАНОВЛЕНИЯ: %s (код pg_restore: %s, ошибок: %s)\n' \
        "${what}" "${rc}" "${total}"
    printf '%s\n' "${errs}" | head -8 | sed 's/^/      /'
    [ "${total}" -gt 8 ] && printf '      … и ещё %s\n' "$(( total - 8 ))"
    # Причина называется только тогда, когда она ВИДНА в выводе. Иначе
    # повторилась бы та самая болезнь: подсказка про то, чего не проверяли.
    if printf '%s' "${errs}" | grep -qE 'gen_random_bytes|gin_trgm_ops|ltree|pg_trgm|pgcrypto'; then
        printf 'ПРИЧИНА: в базе нет расширений Postgres.\n'
        printf '      gen_random_bytes — это pgcrypto, gin_trgm_ops — pg_trgm, тип ltree — ltree.\n'
        printf '      В дампах схем их нет по построению; ставит их %s.\n' "${EXT_SQL}"
    fi
    return 1
}

# Расширения, которые ставит восстанавливающий.
ours_extensions() {
    grep -oE 'CREATE EXTENSION IF NOT EXISTS [a-z_0-9]+' "$EXT_SQL" \
        | awk '{print $NF}' | sort
}

# Дрейф: в живой базе завелось расширение, которого путь восстановления
# не знает. Список лежит в ops/, а схемы растут в db/changelog — разойтись
# они могут молча, и узнать об этом в день аварии дороже всего. plpgsql
# не считается: он есть в любой базе с рождения, и красное на нём означало бы
# красную проверку на исправной ячейке каждую неделю.
extensions_verdict() {
    local live="$1" ours="$2" missing
    missing="$(comm -23 \
        <(printf '%s\n' "${live}" | sed 's/[[:space:]]//g' | grep -v '^plpgsql$' | grep -v '^$' | sort) \
        <(printf '%s\n' "${ours}" | sed 's/[[:space:]]//g' | grep -v '^$' | sort) || true)"
    [ -z "${missing}" ] && return 0
    printf 'РАСШИРЕНИЯ РАЗОШЛИСЬ: в живой базе есть то, чего путь восстановления не ставит: %s\n' \
        "$(printf '%s' "${missing}" | tr '\n' ' ')"
    printf '      Восстановленный клиент будет без них. Впишите их в %s.\n' "${EXT_SQL}"
    return 1
}

# --- проверка самой проверки -------------------------------------------------
selftest() {
    local bad=0 out rc fake
    # tmp НЕ local: ловушка EXIT срабатывает, когда функция уже завершилась,
    # и локальной переменной к этому моменту нет — под `set -u` это
    # «tmp: unbound variable» и код возврата 1 на ПРОЙДЕННОЙ самопроверке.
    # Своя же ловушка красила бы CI и обесценивала случай 9: ребёнок падал бы
    # по ней, а не из-за подделки.
    tmp="$(mktemp -d)"
    trap 'rm -rf "${tmp:-}"' EXIT
    echo "Самопроверка ops/verify-backup.sh"

    says()     { printf '%s' "$1" | grep -qF -- "$2"; }
    says_not() { ! printf '%s' "$1" | grep -qF -- "$2"; }
    verdict() { set +e; out="$(restore_verdict "$1" "$2" "$3")"; rc=$?; set -e; }

    # 1. Главный случай задачи: разворот упал на расширениях. Проверка обязана
    #    назвать РАСШИРЕНИЯ и показать вывод pg_restore, а не молчать и не
    #    жаловаться на отсутствующую таблицу — это следствие, и именно оно
    #    увело человека на полчаса.
    cat > "$tmp/ext.out" <<'EOF'
pg_restore: error: could not execute query: ERROR:  function public.gen_random_bytes(integer) does not exist
pg_restore: error: could not execute query: ERROR:  relation "t_000042.donor" does not exist
EOF
    verdict 1 "$tmp/ext.out" "t_000042"
    if [ "$rc" != 0 ] && says "$out" 'gen_random_bytes' && says "$out" 'ПРИЧИНА' \
       && says "$out" 'pgcrypto' && says "$out" 'ОТКАЗ ВОССТАНОВЛЕНИЯ'; then
        printf '  ✓ отказ по pgcrypto назван расширениями, вывод pg_restore показан\n'
    else
        printf '\033[1;31m  ✗ отказ по gen_random_bytes обязан называть расширения и показывать вывод\033[0m\n'
        printf '%s\n' "$out" | sed 's/^/      /'; bad=1
    fi

    # 2. Второй замер задачи: расширения частично есть (pgcrypto поставили,
    #    pg_trgm нет) — падают индексы.
    cat > "$tmp/trgm.out" <<'EOF'
pg_restore: error: could not execute query: ERROR:  operator class "public.gin_trgm_ops" does not exist for access method "gin"
EOF
    verdict 1 "$tmp/trgm.out" "t_000042"
    if [ "$rc" != 0 ] && says "$out" 'gin_trgm_ops' && says "$out" 'pg_trgm'; then
        printf '  ✓ отказ по gin_trgm_ops назван расширениями\n'
    else
        printf '\033[1;31m  ✗ отказ по gin_trgm_ops обязан называть pg_trgm\033[0m\n'
        printf '%s\n' "$out" | sed 's/^/      /'; bad=1
    fi

    # 3. Обратный край: «schema public already exists» — единственный
    #    безобидный отказ разворота в чистую базу. Проверка, краснеющая
    #    на нём, будет краснеть каждую неделю, и её отключат вместе с защитой.
    cat > "$tmp/benign.out" <<'EOF'
pg_restore: error: could not execute query: ERROR:  schema "public" already exists
Command was: CREATE SCHEMA public;
EOF
    verdict 1 "$tmp/benign.out" "общие схемы"
    if [ "$rc" = 0 ]; then
        printf '  ✓ «schema public already exists» проверку не красит\n'
    else
        printf '\033[1;31m  ✗ безобидный отказ обязан проходить\033[0m\n'; bad=1
    fi

    # 4. Чистый разворот.
    : > "$tmp/clean.out"
    verdict 0 "$tmp/clean.out" "t_000042"
    [ "$rc" = 0 ] && printf '  ✓ чистый разворот проходит\n' \
        || { printf '\033[1;31m  ✗ чистый разворот обязан проходить\033[0m\n'; bad=1; }

    # 5. Незнакомая беда. Причину выдумывать нельзя, но вывод обязан доехать
    #    до человека — иначе он опять останется с одним следствием.
    cat > "$tmp/other.out" <<'EOF'
pg_restore: error: could not open input file "/backups/t_000042.dump": No such file or directory
EOF
    verdict 1 "$tmp/other.out" "t_000042"
    if [ "$rc" != 0 ] && says "$out" 'could not open input file' && says_not "$out" 'ПРИЧИНА'; then
        printf '  ✓ незнакомый отказ показан дословно и причина не выдумана\n'
    else
        printf '\033[1;31m  ✗ незнакомый отказ обязан доезжать выводом и без выдуманной причины\033[0m\n'
        printf '%s\n' "$out" | sed 's/^/      /'; bad=1
    fi

    # 6. Дрейф списка расширений.
    set +e
    out="$(extensions_verdict "ltree
pg_trgm
pgcrypto
plpgsql
postgis" "$(ours_extensions)")"; rc=$?
    set -e
    if [ "$rc" != 0 ] && says "$out" 'postgis'; then
        printf '  ✓ незнакомое расширение живой базы названо по имени\n'
    else
        printf '\033[1;31m  ✗ расширение живой базы, которого нет в списке, обязано красить проверку\033[0m\n'
        printf '%s\n' "$out" | sed 's/^/      /'; bad=1
    fi

    # 7. Обратный край дрейфа: ровно наши три плюс plpgsql — молчит.
    set +e
    out="$(extensions_verdict "$(ours_extensions)
plpgsql" "$(ours_extensions)")"; rc=$?
    set -e
    [ "$rc" = 0 ] && printf '  ✓ исправная ячейка (наши три плюс plpgsql) не краснеет\n' \
        || { printf '\033[1;31m  ✗ исправная ячейка обязана проходить молча\033[0m\n'
             printf '%s\n' "$out" | sed 's/^/      /'; bad=1; }

    # 8. Список не пуст. Потерянный или переименованный файл расширений — это
    #    молча вернувшаяся дыра задачи 0157.
    if [ "$(ours_extensions | grep -c .)" -ge 3 ]; then
        printf '  ✓ список расширений читается: %s\n' "$(ours_extensions | tr '\n' ' ')"
    else
        printf '\033[1;31m  ✗ %s не читается или пуст\033[0m\n' "$EXT_SQL"; bad=1
    fi

    # 9. ВОЗВРАТ ДЕФЕКТА. Копия проверки, где вердикт всегда «годен», — это
    #    ровно прежняя редакция с `|| true`. Её самопроверка ОБЯЗАНА упасть:
    #    иначе краснеет не сторож, а что-то другое, и все восемь случаев выше
    #    ничего не утверждают. Ребёнку случай 9 выключен, иначе рекурсия.
    if [ -z "${VERIFY_BACKUP_SELFTEST_CHILD:-}" ]; then
        fake="$tmp/fake.sh"
        python3 - "$0" "$fake" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
t = open(src, encoding="utf-8").read()
old = "restore_verdict() {\n    local rc=\"$1\""
new = "restore_verdict() {\n    return 0\n    local rc=\"$1\""
assert old in t, "подделка не легла — изменилась сигнатура restore_verdict"
open(dst, "w", encoding="utf-8").write(t.replace(old, new, 1))
PY
        set +e
        VERIFY_BACKUP_SELFTEST_CHILD=1 ENV_FILE=/dev/null bash "$fake" --selftest >"$tmp/fake.log" 2>&1
        rc=$?
        set -e
        if [ "$rc" != 0 ] && grep -q 'gen_random_bytes' "$tmp/fake.log"; then
            printf '  ✓ возврат дефекта: с заглушённым вердиктом самопроверка падает\n'
        else
            printf '\033[1;31m  ✗ подделка «вердикт всегда годен» обязана валить самопроверку\033[0m\n'
            sed 's/^/      /' "$tmp/fake.log"; bad=1
        fi
    fi

    [ $bad = 0 ] || { printf '\033[1;31mСамопроверка не прошла\033[0m\n'; exit 1; }
    printf '\033[1;32mСамопроверка пройдена\033[0m\n'
}

# Самопроверка идёт ДО чтения .env и до любого обращения к docker: ей не нужны
# ни ячейка, ни набор. И отрезана она от .env машины намеренно — файл ячейки
# старше окружения, и проба мерила бы чужие настройки (находка задачи 0153).
case "${1:-}" in
    --selftest) selftest; exit 0 ;;
esac

ENV_FILE="${2:-${ENV_FILE:-.env}}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

# Каталог набора берётся ПОСЛЕ чтения окружения: APP_CELL приезжает оттуда,
# и посчитанный раньше путь указывал бы на набор чужой ячейки.
SET_DIR="${1:-$(find "${BACKUP_DIR:-./backups/${APP_CELL:-cell01}}" -maxdepth 1 -type d -name '20*' | sort | tail -1)}"

COMPOSE="docker compose -f docker-compose.prod.yml --env-file $ENV_FILE"
DB_USER="${DB_USER:?укажите DB_USER}"
CHECK_DB="parts_verify"

[ -d "$SET_DIR" ] || fail "набор не найден: $SET_DIR"
[ -f "$SET_DIR/shared.dump" ] || fail "в наборе нет shared.dump"
[ -f "$EXT_SQL" ] || fail "нет $EXT_SQL — без расширений схему клиента не развернуть"

# Живая база — база сборки под трафиком (задача 0112), а не литерал «parts»:
# сверка с замороженной прежней базой зеленела бы на бэкапе, снятом не с той.
LIVE_DB=$(ENV_FILE="$ENV_FILE" ops/switch-build.sh --current-db) \
    || fail "не узнать базу сборки под трафиком (ops/switch-build.sh --current-db)"
live()  { $COMPOSE exec -T postgres psql -U "$DB_USER" -d "$LIVE_DB" -tAc "$1"; }
check() { $COMPOSE exec -T postgres psql -U "$DB_USER" -d "$CHECK_DB" -tAc "$1"; }

# Проверочную базу убираем в любом случае. Оставленная после провала, она
# занимает место кластера и путает следующий запуск: увидев её, легко решить,
# что проверка идёт прямо сейчас.
MIRROR_LIST="$(mktemp)"
RESTORE_OUT="$(mktemp)"

cleanup() {
    $COMPOSE exec -T postgres psql -U "$DB_USER" -d postgres \
        -c "DROP DATABASE IF EXISTS $CHECK_DB" >/dev/null 2>&1 || true
    rm -f "$MIRROR_LIST" "$RESTORE_OUT"
}
trap cleanup EXIT

# Развернуть дамп в проверочную базу и разобрать, что вышло. Вывод больше
# не выбрасывается: в нём и лежит ответ на вопрос «почему не развернулось».
restore_dump() {   # $1 — файл дампа, $2 — что разворачиваем (для человека)
    local rc
    set +e
    $COMPOSE exec -T postgres pg_restore -U "$DB_USER" -d "$CHECK_DB" --no-owner \
        < "$1" > "$RESTORE_OUT" 2>&1
    rc=$?
    set -e
    restore_verdict "$rc" "$RESTORE_OUT" "$2"
}

printf 'Набор: %s\n' "$SET_DIR"

step "Готовим чистую базу для проверки"
$COMPOSE exec -T postgres psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 \
    -c "DROP DATABASE IF EXISTS $CHECK_DB" -c "CREATE DATABASE $CHECK_DB" >/dev/null
ok "$CHECK_DB создана"

step "Расширения Postgres"
# Голой базы для схемы клиента мало, и это не особенность проверки, а свойство
# восстановления вообще: расширения принадлежат базе, в дампах схем их нет,
# а приложение их уже не поставит — отметка «catalog-001-extensions применён»
# лежит внутри shared.dump. Поэтому их ставит тот, кто восстанавливает,
# и ставит одним списком с ops/restore-cell.sh и ops/restore-tenant.sh.
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$CHECK_DB" -v ON_ERROR_STOP=1 \
    -f /dev/stdin < "$EXT_SQL" >/dev/null \
    || fail "расширения не поставились — дальше проверять нечего"
ok "поставлены: $(ours_extensions | tr '\n' ' ')"

# И сразу — не разошёлся ли список с живой базой. Дрейф обязан всплыть здесь,
# а не в день аварии: схемы растут в db/changelog, список живёт в ops/.
LIVE_EXT="$(live "SELECT extname FROM pg_extension ORDER BY extname")"
extensions_verdict "$LIVE_EXT" "$(ours_extensions)" \
    || fail "список расширений разошёлся с живой базой"
ok "с живой базой сходятся"

step "Восстанавливаем общие схемы"
restore_dump "$SET_DIR/shared.dump" "общие схемы (shared.dump)" \
    || fail "общие схемы не развернулись — см. вывод pg_restore выше"
BRANDS=$(check "SELECT count(*) FROM catalog.brand")
[ "$BRANDS" -gt 0 ] || fail "справочник марок пуст — общие схемы не развернулись"
ok "справочники на месте: марок $BRANDS"

step "Зеркало фотографий"
# Зеркало общее на все наборы и лежит рядом с ними, а не внутри: снимки
# копируются инкрементом, датировать их нечем и незачем.
MIRROR="${PHOTO_DIR:-$(dirname "$SET_DIR")/photos}"
[ -d "$MIRROR" ] || fail "зеркала фотографий нет: $MIRROR — снимите бэкап заново (ops/backup.sh)"
# Список строится один раз на все схемы: у клиента снимков бывает под сотню
# тысяч, и обходить каталог на каждого арендатора значит превратить проверку
# в час работы диска.
( cd "$MIRROR" && find . -type f ) | sed 's|^\./||' | sort > "$MIRROR_LIST"
ok "файлов в зеркале: $(grep -c . "$MIRROR_LIST" || true)"

step "Восстанавливаем арендаторов и сверяем"
FAILED=0
for dump in "$SET_DIR"/t_*.dump; do
    [ -e "$dump" ] || { ok "арендаторов в наборе нет"; break; }
    schema=$(basename "$dump" .dump)

    # Не развернулся — говорим об этом и идём к следующему. Сверять агрегаты
    # после неудачного разворота незачем: они ответят «relation does not
    # exist», то есть повторят следствие вместо причины. Ровно это и было.
    if ! restore_dump "$dump" "$schema"; then
        FAILED=1
        continue
    fi

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

    # Снимки. Карточки без фотографий — это не восстановленный клиент:
    # на разборке продаёт фотография, и половина склада без картинок
    # обнаружится в первый же рабочий день после возврата. Проверяется
    # не число файлов, а именно те ключи, на которые ссылаются карточки:
    # совпадение количеств ничего не значит, если это другие файлы.
    keys="$(check "SELECT s3_key FROM $schema.part_photo ORDER BY s3_key")"
    if [ -n "$keys" ]; then
        MISSING=$(comm -23 <(printf '%s\n' "$keys" | sed 's/^ *//;s/ *$//' | sort) \
                           "$MIRROR_LIST" | grep -c . || true)
        if [ "$MISSING" != 0 ]; then
            printf '\033[1;31m    %s: снимков нет в копии — %s\033[0m\n' "$schema" "$MISSING"
            FAILED=1
        else
            ok "$schema — снимков в копии: $(printf '%s\n' "$keys" | grep -c .)"
        fi
    fi

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
