#!/usr/bin/env bash
# Привести схемы ячейки к версии образа — одноразовым контейнером того же
# образа, который потом будет обслуживать людей.
#
#   ops/schema-sync.sh                                   # проверить: кто не на версии образа
#   ops/schema-sync.sh --apply                           # привести к версии образа
#   ops/schema-sync.sh --apply --to 132                  # опустить схемы до версии 132
#   ops/schema-sync.sh --apply green                     # назвать выкладываемую сборку явно
#   APP_IMAGE_TAG_GREEN=<sha> ops/schema-sync.sh --apply # версию задаёт тег этой сборки
#   ops/schema-sync.sh --selftest                        # проверка самого скрипта, без docker
#
# ЗАЧЕМ ОТДЕЛЬНО ОТ migrate-tenants.sh. Тот стучится в РАБОТАЮЩЕЕ приложение,
# а changelog лежит внутри jar: накатить новые changeset'ы может только новый
# артефакт — то есть тот, что уже подменил старый. Expand/contract требует
# обратного порядка (сначала схема, потом код), и выполнить его одним
# артефактом было нельзя вовсе: между шагами оставалось окно, где новый код
# работает на старых схемах. Здесь миграцию гонит контейнер нового образа,
# который ничего не обслуживает и порт не занимает, — и окно исчезает.
#
# КАКОЙ СБОРКОЙ. Их две (app-blue и app-green), у каждой свой тег образа,
# и берём мы ТУ, КОТОРУЮ ВЫКЛАДЫВАЕМ, — то есть НЕ ту, что под трафиком.
# Это противоположно migrate-tenants.sh, и намеренно: тот разговаривает
# с работающим приложением, а нам нужен changelog НОВОГО артефакта. Взяв
# сборку под трафиком, мы накатили бы changeset'ы прежней версии — то есть
# молча не сделали бы ничего ровно в тот шаг, ради которого всё написано.
#
# Имя активной сборки при этом спрашивается у ops/switch-build.sh --current:
# оно записано в одном месте на всю ячейку (ops/active-build.caddy), и второго
# источника заводить нельзя — разойдясь, они отправили бы трафик в одну
# сборку, а миграции в другую.
#
# ВЕРСИЯ — ЧИСЛО CHANGESET'ОВ набора арендатора, то же, что в отметке реестра
# и в ./db/rollback-cost.py. Без --to целью берётся версия самого образа:
# пара «код и схема» получается свойством построения, а не внимательностью
# выкладывающего.
#
# ЧЕГО ЭТОТ ОБРАЗ НЕ МОЖЕТ: снять changeset'ы, которых в нём нет. Тело
# --rollback лежит в файле changeset'а, то есть в чужом jar, — и rollbackCount
# их не видит вовсе, а снял бы вместо них последние СВОИ. Такая схема
# называется по имени и не трогается; опускает её образ той сборки, которая
# эти changeset'ы завела (её же тегом, с --to).
#
# И ВНИЗ НЕ БЕСКОНЕЧНО. У отката есть объявленная нижняя граница — она едет
# внутри образа (db/changelog/rollback-floor.properties), и --to ниже неё
# контейнер отбивает словами, не тронув ни одной схемы. Ниже границы откат
# схему НЕ ВОЗВРАЩАЕТ: одиннадцать выпущенных changeset'ов не отменяют того,
# что сделали, и проходит это молча — база объявила бы себя версией, которой
# не является. Чем это обосновано — db/CLAUDE.md, «Нижняя граница отката
# объявлена» (задача 0102); что теряется по дороге — ./db/rollback-cost.py,
# который ниже границы тоже отказывает.
set -euo pipefail
cd "$(dirname "$0")/.."

SELF="ops/$(basename "$0")"

red()  { printf '\033[1;31m%s\033[0m\n' "$1" >&2; }
fail() { red "$1"; exit 1; }

# Простыми переменными, а не массивом: ${#TO[@]} на пустом массиве под set -u
# валит bash 3.2, который стоит на macOS у разработчика. Пробелов ни в одном
# из значений нет по построению.
#
# Разбор отделён в функцию задачей 0243: самопроверке иначе нечего спросить —
# «как ты понял этот аргумент». Печатает четыре поля через табуляцию
# (режим, цель, сборка, действие), потому что проверять надо именно то,
# ВО ЧТО превратился аргумент, а не то, что потом случилось.
parse_args() {
    local mode=--check to= build= action=run
    while [ $# -gt 0 ]; do
        case "$1" in
            --apply)            mode=;            shift ;;
            --check)            mode=--check;     shift ;;
            --to)               to="--to=${2:?укажите версию числом}"; shift 2 ;;
            --to=*)             to="$1";          shift ;;
            blue|green)         build="app-$1";   shift ;;
            app-blue|app-green) build="$1";       shift ;;
            --selftest)         action=selftest;  shift ;;
            -h|--help)          action=help;      shift ;;
            *)
                red "Непонятный аргумент: $1"
                red "  Их пять: --apply, --check, --to <версия>, blue|green, --selftest."
                return 2 ;;
        esac
    done
    printf '%s\t%s\t%s\t%s\n' "$mode" "$to" "$build" "$action"
}

# Сборка, которую выкладываем, — та, что НЕ под трафиком. Отделено функцией
# по тому же доводу, что и разбор: «из blue получается green» без docker
# иначе не проверить, а ошибка здесь означает накат не тем образом и молча.
flip_build() {   # имя сборки под трафиком
    case "$1" in
        app-blue)  printf 'app-green\n' ;;
        app-green) printf 'app-blue\n' ;;
        *) red "Непонятное имя активной сборки: $1"; return 1 ;;
    esac
}

# Какой образ у выкладываемой сборки — исходов четыре, а не один (задача 0148).
#
# Прежняя редакция глушила обе половины запроса (`2>/dev/null`) и на ЛЮБОЙ отказ
# печатала одно: «Не удалось разобрать образ сборки app-green — проверьте
# APP_IMAGE_TAG в .env». В эту строку сливались четыре разных беды: незаданный
# тег (чинится там, куда посылает совет), docker не отвечает, нет python3
# и «в разобранной конфигурации нет такого сервиса» — а про последнее python
# ниже говорит своими словами, и эти слова выбрасывались. На трёх бедах из
# четырёх человек шёл править файл, в котором всё верно, и шёл по совету шага,
# который этого не проверял.
#
# Отдельной функцией — чтобы самопроверка смотрела на САМО сообщение, а не
# на его половину (тот же приём, что у `no_base_text` в ops/restore-pitr.sh).
verdict_image() {   # код, образ, первые строки отказа, сборка, файл .env
    local rc="$1" image="$2" err="$3" build="$4" envfile="$5"
    # Признак «ответ получен и он непуст» — одной строкой: возврат дефекта
    # ставится ровно сюда (случай «прежняя редакция» в самопроверке).
    local asked=yes
    { [ "$rc" = 0 ] && [ -n "$image" ]; } || asked=no
    [ "$asked" = no ] || return 0
    case "$err" in
        *"в конфигурации нет сервиса"*)
            red "В разобранной конфигурации нет сервиса $build."
            red "Это не про APP_IMAGE_TAG: сверьте имя сборки и профиль (COMPOSE_PROFILES)."
            return 1 ;;
    esac
    if [ "$rc" = 0 ]; then
        red "Образ сборки $build разобрать не удалось: ответ ПУСТОЙ при нулевом коде."
        red "Сверять нечего, и тег в $envfile при этом может быть цел."
        return 1
    fi
    red "Образ сборки $build СПРОСИТЬ НЕ ВЫШЛО: код ${rc}${err:+ — }${err}"
    red "Сказано выше про APP_IMAGE_TAG — задайте его в $envfile; сказано"
    red "про docker или python3 — чинить надо их, а файл цел."
    return 1
}

# ─────────────────────────────── самопроверка ────────────────────────────────
#
# Без docker и без ячейки: разбор аргументов и выбор сборки — чистые функции.
# Последние случаи — возврат дефекта: копия с вернувшейся поломкой обязана
# валить самопроверку, иначе остальные не утверждают ничего (урок 0142).
# Целость копии проверяется ДО запуска (bash -n): красное от сломанного
# синтаксиса не говорило бы ни о чём (урок 0198).
selftest() {
    local bad=0 out rc
    echo "Самопроверка $SELF"

    parsed_is() {  # имя, ожидаемая строка «режим|цель|сборка|действие», аргументы…
        local name="$1" want="$2"; shift 2
        local seen
        set +e
        seen=$(parse_args "$@" 2>/dev/null | tr '\t' '|'); rc=$?
        set -e
        if [ "$rc" != 0 ] || [ "$seen" != "$want" ]; then
            red "  ✗ ${name}: ожидалось «${want}», вышло «${seen}» (код ${rc})"
            bad=$((bad + 1)); return
        fi
        printf '  ✓ %s\n' "$name"
    }

    refuses() {  # имя, аргументы…
        local name="$1"; shift
        set +e
        out=$(parse_args "$@" 2>&1); rc=$?
        set -e
        if [ "$rc" = 0 ]; then
            red "  ✗ ${name}: принято молча («${out}»)"
            bad=$((bad + 1)); return
        fi
        printf '  ✓ %s\n' "$name"
    }

    # Что аргумент превратился в то, чем его запустят у клиента.
    parsed_is "без аргументов — только проверка" '--check|||run'
    parsed_is "--check — только проверка" '--check|||run' --check
    parsed_is "--apply — приведение (режим пуст)" '|||run' --apply
    parsed_is "--to 132 — цель названа" '--check|--to=132||run' --to 132
    parsed_is "--to=132 — та же цель" '--check|--to=132||run' --to=132
    parsed_is "blue — сборка названа полным именем" '--check||app-blue|run' blue
    parsed_is "green — сборка названа полным именем" '--check||app-green|run' green
    parsed_is "app-green — принимается как есть" '--check||app-green|run' app-green
    parsed_is "--apply --to 132 green — три поля разом" '|--to=132|app-green|run' --apply --to 132 green
    # --selftest обязан быть СВОИМ аргументом, а не «непонятным»: до задачи
    # 0243 его съедала ветка `*)`, то есть самопроверки у скрипта не было
    # и быть не могло.
    parsed_is "--selftest не съедается разбором" '--check|||selftest' --selftest
    refuses "непонятный аргумент отбит" --aply
    refuses "--to без значения отбито" --to

    # Выбор сборки: выкладываем ту, что НЕ под трафиком.
    local got
    got=$(flip_build app-blue) || got=ОТКАЗ
    if [ "$got" = app-green ]; then
        printf '  ✓ под трафиком blue — выкладываем green\n'
    else
        red "  ✗ из app-blue вышло «${got}», а не app-green"; bad=$((bad + 1))
    fi
    got=$(flip_build app-green) || got=ОТКАЗ
    if [ "$got" = app-blue ]; then
        printf '  ✓ под трафиком green — выкладываем blue\n'
    else
        red "  ✗ из app-green вышло «${got}», а не app-blue"; bad=$((bad + 1))
    fi
    set +e
    out=$(flip_build app-chuzhaya 2>&1); rc=$?
    set -e
    if [ "$rc" != 0 ]; then
        printf '  ✓ непонятное имя активной сборки — отказ, а не угаданная сборка\n'
    else
        red "  ✗ из «app-chuzhaya» вышло «${out}» вместо отказа"; bad=$((bad + 1))
    fi

    # ОБРАЗ ВЫКЛАДЫВАЕМОЙ СБОРКИ: исходов четыре, а не один (задача 0148),
    # и проверяются они ПРОГОНОМ ВСЕГО ПУТИ, а не вызовом вердикта: отказ надо
    # ещё донести до вердикта, а прежняя редакция глушила его на месте вызова
    # (`2>/dev/null`) — проба, зовущая функцию, этого не видит (урок 0144).
    # Ни docker, ни ячейки не нужно: `$COMPOSE` подменяется заглушкой.
    local sdir stub
    sdir="$(mktemp -d)"
    stub="$sdir/compose"
    cat > "$stub" <<'STUB'
#!/bin/sh
case " $* " in
    *" config "*)
        case "${STUB_IMAGE:-ok}" in
            ok)    printf '{"services":{"app-green":{"image":"ghcr.io/x/partsflow:abc123"}}}\n'; exit 0 ;;
            nosvc) printf '{"services":{"app-blue":{"image":"ghcr.io/x/partsflow:abc123"}}}\n'; exit 0 ;;
            pusto) printf '{"services":{"app-green":{"image":""}}}\n'; exit 0 ;;
            down)  printf 'cannot connect to the Docker daemon at unix:///var/run/docker.sock\n' >&2
                   exit 14 ;;
        esac ;;
esac
exit 0
STUB
    chmod +x "$stub"
    sync_run() {  # STUB_IMAGE → вывод шага плюс строка «код=N»
        local out rc=0
        set +e
        out=$(STUB_IMAGE="$1" COMPOSE="$stub" ENV_FILE=/dev/null bash "$SELF" green 2>&1)
        rc=$?
        set -e
        printf '%s\nкод=%s\n' "$out" "$rc"
    }

    local sync_out
    sync_out=$(sync_run ok)
    case "$sync_out" in
        *"ghcr.io/x/partsflow:abc123"*"код=0"*)
            printf '  ✓ образ разобран — шаг идёт дальше и называет его\n' ;;
        *) red "  ✗ исправный разбор образа не прошёл: ${sync_out}"; bad=$((bad + 1)) ;;
    esac

    #     Главный случай: сервиса нет в разобранной конфигурации (не тот
    #     профиль). Прежняя редакция глотала слова самого python и посылала
    #     править APP_IMAGE_TAG в файле, где всё верно.
    sync_out=$(sync_run nosvc)
    case "$sync_out" in
        *"нет сервиса app-green"*) : ;;
        *) red "  ✗ «нет такого сервиса» не названо своими словами: ${sync_out}"; bad=$((bad + 1)) ;;
    esac
    case "$sync_out" in
        *"проверьте APP_IMAGE_TAG"*)
            red "  ✗ НЕИЗМЕРЕННОЕ: отсутствие сервиса названо незаданным APP_IMAGE_TAG"
            bad=$((bad + 1)) ;;
    esac
    case "$sync_out" in
        *"код=0"*) red "  ✗ отсутствие сервиса дало нулевой код"; bad=$((bad + 1)) ;;
        *) printf '  ✓ «нет такого сервиса» — своя причина, а не APP_IMAGE_TAG\n' ;;
    esac

    sync_out=$(sync_run down)
    case "$sync_out" in
        *"СПРОСИТЬ НЕ ВЫШЛО"*"Docker daemon"*)
            printf '  ✓ отказ docker назван отказом docker, и показан дословно\n' ;;
        *) red "  ✗ отказ docker не назван отказом: ${sync_out}"; bad=$((bad + 1)) ;;
    esac

    sync_out=$(sync_run pusto)
    case "$sync_out" in
        *"ответ ПУСТОЙ при нулевом коде"*)
            printf '  ✓ пустой образ при нулевом коде назван пустым ответом\n' ;;
        *) red "  ✗ пустой образ назван не тем: ${sync_out}"; bad=$((bad + 1)) ;;
    esac
    rm -rf "$sdir"

    # ── возврат дефекта ─────────────────────────────────────────────────────
    forgery() {  # имя, программа подделки для python
        local name="$1" program="$2"
        local fake="ops/.schema-sync-podelka.$$.sh"
        if ! printf '%s' "$program" | python3 - "$SELF" "$fake"; then
            red "  ✗ ${name}: подделку негде поставить — образец не нашёлся"
            bad=$((bad + 1)); rm -f "$fake"; return
        fi
        chmod +x "$fake"
        if ! bash -n "$fake" 2>/dev/null; then
            red "  ✗ ${name}: подделка сломала синтаксис — её красное ничего не значило бы"
            bad=$((bad + 1)); rm -f "$fake"; return
        fi
        set +e
        out=$(SCHEMA_SYNC_SELFTEST_FAKE=1 bash "$fake" --selftest 2>&1); rc=$?
        set -e
        rm -f "$fake"
        if [ "$rc" != 0 ]; then
            printf '  ✓ возврат дефекта: %s валит самопроверку\n' "$name"
        else
            red "  ✗ ${name}: копия с вернувшейся поломкой ПРОШЛА самопроверку"
            printf '%s\n' "$out" | sed 's/^/      /' >&2
            bad=$((bad + 1))
        fi
    }

    if [ -z "${SCHEMA_SYNC_SELFTEST_FAKE:-}" ]; then
        forgery "чужой аргумент снова проходит молча" '
import sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
obrazec = """            *)
                red "Непонятный аргумент: $1"
"""
assert obrazec in text, "образец ветки отказа не найден"
text = text.replace(obrazec, """            *)
                shift; continue
                red "Непонятный аргумент: $1"
""", 1)
open(dst, "w", encoding="utf-8").write(text)
'
        forgery "любой отказ разбора образа снова зовут править APP_IMAGE_TAG" '
import sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
obrazec = "verdict_image() {   # код, образ, первые строки отказа, сборка, файл .env"
assert obrazec in text, "заголовок вердикта не найден"
text = text.replace(obrazec, """verdict_image() {
    { [ "$1" = 0 ] && [ -n "$2" ]; } && return 0
    red "Не удалось разобрать образ сборки $4 — проверьте APP_IMAGE_TAG в $5"
    return 1
}
old_verdict_image() {""", 1)
open(dst, "w", encoding="utf-8").write(text)
'
        forgery "выкладываемой объявлена сборка ПОД ТРАФИКОМ" '
import sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
obrazec = """        app-blue)  printf \x27app-green\\n\x27 ;;"""
assert obrazec in text, "образец выбора сборки не найден"
text = text.replace(obrazec, """        app-blue)  printf \x27app-blue\\n\x27 ;;""", 1)
open(dst, "w", encoding="utf-8").write(text)
'
    fi

    if [ "$bad" != 0 ]; then
        red "Самопроверка не прошла: $bad"
        return 1
    fi
    printf '\033[1;32m%s\033[0m\n' "Самопроверка пройдена: разбор аргументов и выбор выкладываемой сборки."
    printf '    Схемы при этом не трогались — для этого запуск без --selftest.\n'
    return 0
}

# ─────────────────────────────── запуск ──────────────────────────────────────
PARSED=$(parse_args "$@") || exit $?
MODE=$(printf '%s' "$PARSED" | cut -f1)
TO=$(printf '%s' "$PARSED" | cut -f2)
BUILD=$(printf '%s' "$PARSED" | cut -f3)
ACTION=$(printf '%s' "$PARSED" | cut -f4)

case "$ACTION" in
    selftest) selftest; exit $? ;;
    help)
        sed -n '2,50p' "$0" | sed 's/^# \{0,1\}//'
        exit 0 ;;
esac

ENV_FILE="${ENV_FILE:-.env}"
# Переопределяется только самопроверкой (задача 0148): иначе путь запуска —
# тот, где глушился отказ разбора образа, — проверить без docker нечем,
# а проба, зовущая функцию, не проверяет того, что делает запуск (урок 0144).
COMPOSE="${COMPOSE:-docker compose -f docker-compose.prod.yml --env-file $ENV_FILE}"

# Имя активной сборки берём у switch-build.sh; не смог ответить (файла нет,
# ячейка ещё не переключалась) — спрашиваем человека, а не угадываем:
# угаданная сборка означает накат не тем образом, и молча.
if [ -z "$BUILD" ]; then
    CURRENT=$(ops/switch-build.sh --current) \
        || fail "Не удалось узнать активную сборку. Назовите выкладываемую явно: ops/schema-sync.sh --apply green"
    BUILD=$(flip_build "$CURRENT") || exit 1
else
    CURRENT=$(ops/switch-build.sh --current 2>/dev/null || echo '—')
fi

PROFILE="${BUILD#app-}"

# Какой именно образ запускаем — печатаем ДО запуска, а не «доверьтесь
# умолчанию». Сборка выбрана по правилу, а правило можно применить не к тому
# дню: увидев чужой тег, выкладывающий остановится сам.
#
# Берётся из разобранной конфигурации по ИМЕНИ СЕРВИСА, а не из
# `config --images`: тот печатает заодно образы зависимостей (postgres, minio)
# и в непредсказуемом порядке — проверено прогоном, строки приезжают
# по-разному от запуска к запуску. Одна не та строка здесь означала бы
# напечатанный тег, которым ничего не запускали.
#
# Отказ НЕ глушится (задача 0148): его код и его stderr — это и есть разница
# между «не тот профиль», «docker не отвечает» и «забыт APP_IMAGE_TAG».
IMAGE_ERR_FILE="$(mktemp)"
IMAGE=""
IMAGE_RC=0
IMAGE=$($COMPOSE --profile "$PROFILE" config --format json 2>"$IMAGE_ERR_FILE" \
    | python3 -c "import json, sys
services = json.load(sys.stdin)['services']
if '$BUILD' not in services:
    sys.exit('в конфигурации нет сервиса $BUILD')
print(services['$BUILD']['image'])" 2>>"$IMAGE_ERR_FILE") || IMAGE_RC=$?
# Первые три строки отказа в одну: `cut -c` на не-ASCII режет байты на Linux
# и символы на macOS (урок 0085), а compose и python говорят и по-русски.
IMAGE_ERR=$(sed -n '1,3p' "$IMAGE_ERR_FILE" | tr '\n' ' ')
rm -f "$IMAGE_ERR_FILE"
verdict_image "$IMAGE_RC" "$IMAGE" "$IMAGE_ERR" "$BUILD" "$ENV_FILE" || exit 1

printf 'Сборка: \033[1m%s\033[0m (под трафиком сейчас %s)\n' "$BUILD" "$CURRENT"
printf 'Образ:  \033[1m%s\033[0m\n' "$IMAGE"

# Цену отката печатает человек и до запуска: структурно верный откат теряет
# данные молча, и что именно теряется, знает только пометка у changeset'а.
if [ -n "$TO" ] && [ -z "$MODE" ]; then
    printf '\033[1;33mОткат вниз. Цену печатает ./db/rollback-cost.py --from <версия образа> --to %s\033[0m\n' \
        "${TO#--to=}"
fi

# --rm: контейнер одноразовый. --no-deps: базу поднимает выкладка, а не эта
# команда — иначе проверка «кто отстал» подняла бы половину ячейки.
# -T: без псевдотерминала, чтобы код возврата доезжал до шага выкладки.
# --profile: у сборок профили (blue/green), и без него compose откажется
# запускать ту, которой сейчас нет в COMPOSE_PROFILES, — а её как раз
# и выкладывают.
# Окружение берётся у самого сервиса, то есть то же, что у боевого
# приложения: два места, знающие адрес базы, разъедутся на первой же правке.
set +e
$COMPOSE --profile "$PROFILE" run --rm --no-deps -T "$BUILD" --schema-sync $MODE $TO
CODE=$?
set -e

if [ "$CODE" = "0" ]; then
    printf '\033[1;32mВсе схемы на версии образа %s\033[0m\n' "$IMAGE"
else
    # Ненулевой код — это «выкладывать нельзя»: причина сказана выше строками
    # контейнера. Формулировка нарочно про шаг, а не про схемы: отказ бывает
    # и до того, как посмотрели хоть одну (цель ниже границы отката, чужие
    # changeset'ы, занятый замок), и строка «не все схемы на версии» была бы
    # тогда ответом не на тот вопрос.
    printf '\033[1;31mШаг не выполнен (код %s) — причина выше\033[0m\n' "$CODE" >&2
fi
exit "$CODE"
