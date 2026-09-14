#!/usr/bin/env bash
# Выкладка на стенд одной командой: схема, новая сборка рядом, переключение,
# самопроверки — и только потом гаснет старая.
#
#   ops/deploy.sh --tag <SHA>                          обычная выкладка
#   ops/deploy.sh --tag <SHA> --migrations не-трогать  выложить код, схему не двигать
#   ops/deploy.sh --tag <SHA> --migrations только      привести схему и выйти
#   ops/deploy.sh --tag <SHA> --migrate-with current --to 134   откат вниз
#   ops/deploy.sh --tag <SHA> --dry-run                напечатать шаги, ничего не делая
#   ops/deploy.sh --selftest                           проверка самого порядка шагов
#
# Зовут её кнопкой в браузере (.github/workflows/deploy.yml) — сюда она
# приезжает по ssh с уже посчитанным планом. Руками на стенде работает так же.
#
# ПОРЯДОК — решение владельца от 12 сентября 2026, дословно: «Прогоняется
# миграция, база поднялась без проблем, ошибок нет, само приложение встало
# штатно, всё в порядке и только тогда можно убирать старую сборку и ставить
# новую и красить её в зелёный». Отсюда шаги:
#
#   1. привести схему      ops/schema-sync.sh  (задача 0073)
#   2. поднять новую РЯДОМ со старой, не подменяя её
#   3. дождаться готовности (/actuator/readiness, задача 0078)
#   4. перевести трафик     ops/switch-build.sh (задача 0079) — caddy reload
#   5. четыре самопроверки  ops/deploy-checks.sh — уже на переключённой
#   6. и только теперь погасить старую
#
# Упавший шаг 3 или 5 возвращает трафик на старую сборку — она ещё работает,
# возврат мгновенный. Это и есть причина, по которой её не гасят раньше.
#
# ЧЕМ МИГРИРОВАТЬ — ЗНАЮТ ОБЕ ВЕРСИИ, А НЕ ОДНА. Образ версии 100, встретив
# схему 103, опустить её не может физически: тела --rollback для changeset'ов
# 101–103 лежат в чужом jar. Поэтому откат вниз мигрирует образом ТОЙ СБОРКИ,
# ЧТО СЕЙЧАС РАЗВЁРНУТА (--migrate-with current --to 100), и только потом
# переводит трафик на младший образ. Считает это tools/deploy-plan.py, у него
# же есть git и обе версии; сюда план приезжает готовым.
#
# ЦЕНУ ОТКАТА печатает ./db/rollback-cost.py, и печатает ДО нажатия — читать
# её обязан человек. Структурно верный откат теряет данные молча.
#
# ЧЕГО ЗДЕСЬ НЕТ. Выкладка вниз на короткое время оставляет старшую сборку
# (она ещё под трафиком) на опущенной схеме — это минуты между шагом 1
# и шагом 4, и в эти минуты она отвечает людям на схеме, которой ей не хватает.
# Порядок задан владельцем именно так; уменьшить окно можно было бы, опуская
# схему ПОСЛЕ переключения, но это решение не исполнителя.

set -uo pipefail
cd "$(dirname "$0")/.."

COMPOSE="${COMPOSE:-docker compose -f docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"
HISTORY="${DEPLOY_HISTORY:-deploy-history.log}"
# Сколько ждать, пока новая сборка станет healthy. Холодный старт с накатом
# общей схемы на пустой ячейке — минуты, поэтому не секунды.
HEALTH_WAIT="${DEPLOY_HEALTH_WAIT:-300}"

red()   { printf '\033[1;31m%s\033[0m\n' "$1" >&2; }
green() { printf '\033[1;32m%s\033[0m\n' "$1"; }
step()  { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
fail()  { red "$1"; exit 1; }

TAG=""
MIGRATIONS=накатить
MIGRATE_WITH=target
TO=""
DATA="не трогать"
DRY=""
WHO="${DEPLOY_WHO:-$(id -un 2>/dev/null || echo неизвестно)}"
STAND="${DEPLOY_STAND:-—}"

usage() {
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

# Всё, что трогает ячейку, идёт через run: при --dry-run шаги печатаются,
# а не выполняются. Так проверяется ПОРЯДОК — главное свойство этой выкладки,
# — без стенда и без docker.
run() {
    if [ -n "$DRY" ]; then
        printf 'ШАГ: %s\n' "$*"
        return 0
    fi
    "$@"
}

# ────────────────────────── правка окружения ячейки ──────────────────────────
#
# .env стенда — состояние, а не настройка из репозитория: в нём записано,
# каким тегом поднята каждая сборка. Пишем на месте, сохраняя комментарии:
# файл читают руками, когда разбираются, что на стенде стоит.
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
text = re.sub(r"(?m)^#?" + re.escape(key) + r"=.*$", key + "=" + value, text, count=1)
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

# ──────────────────────────── разбор аргументов ──────────────────────────────
while [ $# -gt 0 ]; do
    case "$1" in
        --tag) TAG="${2:?нужен SHA образа}"; shift 2 ;;
        --tag=*) TAG="${1#--tag=}"; shift ;;
        --migrations) MIGRATIONS="${2:?}"; shift 2 ;;
        --migrations=*) MIGRATIONS="${1#--migrations=}"; shift ;;
        --migrate-with) MIGRATE_WITH="${2:?}"; shift 2 ;;
        --migrate-with=*) MIGRATE_WITH="${1#--migrate-with=}"; shift ;;
        --to) TO="${2:?}"; shift 2 ;;
        --to=*) TO="${1#--to=}"; shift ;;
        --data) DATA="${2:?}"; shift 2 ;;
        --data=*) DATA="${1#--data=}"; shift ;;
        --dry-run) DRY=yes; shift ;;
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
    target|current) ;;
    *) fail "--migrate-with бывает target (выкладываемой сборкой) или current (развёрнутой сейчас)" ;;
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
    ops/switch-build.sh --current
}

other_of() {  # app-blue → app-green
    case "$1" in
        app-blue) printf 'app-green\n' ;;
        app-green) printf 'app-blue\n' ;;
        *) return 1 ;;
    esac
}

# ─────────────────────────────── сама выкладка ───────────────────────────────
deploy() {
    [ -n "$TAG" ] || fail "Нужен --tag: полный SHA коммита, собранного CI (сорок символов)"
    case "$TAG" in
        *[!0-9a-f]*|"") fail "Тег «${TAG}» не похож на SHA: в реестре лежат полные SHA, сорок символов" ;;
    esac
    [ "${#TAG}" = 40 ] || fail "Тег «${TAG}» длиной ${#TAG}: короткого SHA в реестре нет, pull ответит manifest unknown"

    data_gate

    local active target color active_color
    active=$(active_build) || fail "Не удалось узнать сборку под трафиком (ops/active-build.caddy)"
    target=$(other_of "$active") || fail "Непонятное имя активной сборки: $active"
    color="${target#app-}"
    active_color="${active#app-}"

    printf 'Стенд:        %s\n' "$STAND"
    printf 'Выкладываем:  %s\n' "$TAG"
    printf 'Сборка:       %s (сейчас под трафиком %s)\n' "$target" "$active"
    printf 'Миграции:     %s%s\n' "$(migrations_word)" \
        "$([ "$MIGRATE_WITH" = current ] && printf ' — образом развёрнутой сейчас сборки, до версии %s' "$TO")"
    printf 'Данные:       %s\n' "$DATA"
    printf 'Нажал:        %s\n' "$WHO"

    # Тег новой сборки — в .env ДО всего остального: и pull, и одноразовый
    # контейнер миграции берут образ оттуда же, откуда его возьмёт compose.
    step "Записываем тег выкладываемой сборки"
    run set_env "APP_IMAGE_TAG_$(printf '%s' "$color" | tr '[:lower:]' '[:upper:]')" "$TAG"
    # Обе сборки обязаны быть подняты одновременно — иначе «рядом» не выйдет.
    run set_env COMPOSE_PROFILES "blue,green"

    step "Забираем образ из реестра"
    run $COMPOSE --profile "$color" pull "$target" \
        || fail "Образа $TAG в реестре нет (или реестр недоступен). Ячейка ничего не собирает — выкладывать нечего"

    # ── 1. схема ────────────────────────────────────────────────────────────
    if [ "$MIGRATIONS" = skip ]; then
        step "Схему не трогаем (выбрано «не трогать»)"
        printf 'Пара «код и схема» при этом может не совпасть — это репетиция того окна,\n'
        printf 'которое иначе видно только на живом клиенте. Готовность новой сборки\n'
        printf 'покраснеет сама, если схема окажется ПОЗАДИ неё.\n'
    else
        step "Приводим схему к версии артефакта"
        if [ "$MIGRATE_WITH" = current ]; then
            [ -n "$TO" ] || fail "--migrate-with current без --to: непонятно, до какой версии опускать"
            printf 'Вниз мигрирует РАЗВЁРНУТАЯ СЕЙЧАС сборка %s: тела откатов есть только у неё.\n' "$active"
            printf 'Цену отката печатает ./db/rollback-cost.py — прочитайте её до нажатия.\n'
            run ops/schema-sync.sh --apply --to "$TO" "$active_color" \
                || fail "Схему опустить не вышло — новую сборку не поднимаем, трафик остаётся на $active"
        else
            run ops/schema-sync.sh --apply "$color" \
                || fail "Схему привести не вышло — новую сборку не поднимаем, трафик остаётся на $active"
        fi
    fi

    if [ "$MIGRATIONS" = only ]; then
        green "Схема приведена. Сборку не поднимали и трафик не трогали — выбрано «только миграции»."
        record "только миграции" "успех"
        return 0
    fi

    # ── 2–3. новая сборка рядом ─────────────────────────────────────────────
    step "Поднимаем $target РЯДОМ со старой"
    run $COMPOSE --profile blue --profile green up -d "$target" \
        || fail "Не поднялась $target — трафик остаётся на $active"

    step "Ждём готовности $target"
    run wait_healthy "$target" || {
        run $COMPOSE stop "$target"
        fail "Сборка $target не стала здоровой за ${HEALTH_WAIT}с — трафик остался на $active, люди работают"
    }

    # ── 4. трафик ───────────────────────────────────────────────────────────
    step "Переводим трафик на $target"
    run ops/switch-build.sh "$color" || {
        run $COMPOSE stop "$target"
        record "выкладка" "не переключилось"
        fail "Переключение не прошло — трафик остался на $active. Ячейка работает"
    }

    # ── 5. самопроверки ─────────────────────────────────────────────────────
    step "Четыре самопроверки на переключённой сборке"
    if ! run env DEPLOY_BUILD="$target" ops/deploy-checks.sh; then
        red "Самопроверки не прошли — ВОЗВРАЩАЕМ ТРАФИК на $active"
        # С принуждением: при выкладке вниз старшая сборка на опущенной схеме
        # готовностью не отвечает, а обслуживала людей минуту назад. Отказ
        # вернуть трафик оставил бы людей на сборке, которая только что
        # не прошла проверки, — худший из двух исходов.
        run env SWITCH_FORCE=yes ops/switch-build.sh "$active_color" \
            || red "Вернуть трафик не вышло — разбирайтесь руками: ops/switch-build.sh $active_color --force"
        run $COMPOSE stop "$target"
        record "выкладка" "самопроверки не прошли"
        fail "Выкладка красная. Что именно упало — сказано выше построчно"
    fi

    # ── 6. и только теперь гасим старую ─────────────────────────────────────
    step "Гасим старую сборку $active"
    run $COMPOSE stop "$active"

    # Тег ячейки переносим после успеха: до него «версия ячейки» — прежняя,
    # и это верно, потому что вернуться можно было в любую минуту.
    run set_env APP_IMAGE_TAG "$TAG"
    run set_env COMPOSE_PROFILES "$color"

    record "выкладка" "успех"
    green "Готово. Под трафиком $target, версия $TAG"
    return 0
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
    line=$(printf '%s\t%s\t%s\t%s\t%s\t%s\t%s' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$STAND" "$TAG" "$WHO" \
        "$1" "миграции: $MIGRATIONS$([ "$MIGRATE_WITH" = current ] && printf ' вниз до %s' "$TO")" "$2")
    if [ -n "$DRY" ]; then
        printf 'ЗАПИСЬ: %s\n' "$line"
    else
        printf '%s\n' "$line" >> "$HISTORY"
    fi
}

# ────────────────────────────── самопроверка ─────────────────────────────────
#
# Без ячейки и без docker. Проверяется то единственное, ради чего эта выкладка
# и написана: ПОРЯДОК ШАГОВ. Старая сборка обязана гаснуть последней, откат
# вниз — мигрировать образом развёрнутой сейчас сборки, «только миграции» —
# не доходить до трафика, а данные, которых нет, — останавливать выкладку
# до первого изменения.
selftest() {
    local bad=0 dir plan
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN

    printf 'COMPOSE_PROFILES=blue\nAPP_IMAGE_TAG=%s\n#APP_IMAGE_TAG_GREEN=\n' "$(printf 'a%.0s' $(seq 40))" > "$dir/env"
    local sha=b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1

    say()  { printf '  ✓ %s\n' "$1"; }
    nope() { red "  ✗ $1"; bad=$((bad + 1)); }

    # Порядок в плане: шаг A обязан стоять РАНЬШЕ шага B.
    before() {  # имя случая, план, образец A, образец B
        local a b
        a=$(printf '%s\n' "$2" | grep -n -- "$3" | head -n1 | cut -d: -f1)
        b=$(printf '%s\n' "$2" | grep -n -- "$4" | head -n1 | cut -d: -f1)
        if [ -z "$a" ]; then nope "$1: в плане нет «$3»"; return; fi
        if [ -z "$b" ]; then nope "$1: в плане нет «$4»"; return; fi
        if [ "$a" -lt "$b" ]; then say "$1"; else nope "$1: «$3» стоит после «$4»"; fi
    }

    absent() {  # имя случая, план, образец
        if printf '%s\n' "$2" | grep -q -- "$3"; then nope "$1: в плане есть «$3»"; else say "$1"; fi
    }

    echo "Самопроверка ops/deploy.sh"

    # 1. Обычная выкладка: схема → подъём рядом → готовность → трафик →
    #    самопроверки → и только потом остановка старой.
    plan=$(ENV_FILE="$dir/env" DEPLOY_ACTIVE=app-blue DEPLOY_HISTORY="$dir/h" \
        bash "$0" --tag "$sha" --dry-run 2>&1)
    before "порядок: схема раньше подъёма новой" "$plan" "schema-sync.sh" "up -d app-green"
    before "порядок: подъём раньше готовности" "$plan" "up -d app-green" "wait_healthy"
    before "порядок: готовность раньше переключения" "$plan" "wait_healthy" "switch-build.sh green"
    before "порядок: переключение раньше самопроверок" "$plan" "switch-build.sh green" "deploy-checks.sh"
    before "СТАРАЯ ГАСНЕТ ПОСЛЕДНЕЙ: самопроверки раньше остановки" "$plan" \
        "deploy-checks.sh" "stop app-blue"
    before "тег ячейки переносится после остановки старой" "$plan" \
        "stop app-blue" "set_env APP_IMAGE_TAG $sha"
    case "$plan" in
        *"ЗАПИСЬ:"*) say "выкладка оставляет запись" ;;
        *) nope "выкладка не оставляет записи" ;;
    esac

    # 2. Откат вниз мигрирует ОБРАЗОМ РАЗВЁРНУТОЙ СЕЙЧАС сборки. Это то самое,
    #    чего сегодня не умеет шаг миграции: младший образ тел откатов не видит.
    plan=$(ENV_FILE="$dir/env" DEPLOY_ACTIVE=app-blue DEPLOY_HISTORY="$dir/h" \
        bash "$0" --tag "$sha" --migrate-with current --to 134 --dry-run 2>&1)
    case "$plan" in
        *"schema-sync.sh --apply --to 134 blue"*)
            say "откат вниз: мигрирует blue (развёрнутая), а разворачивает green" ;;
        *) nope "откат вниз мигрирует не той сборкой: $(printf '%s' "$plan" | grep schema-sync)" ;;
    esac
    before "откат вниз: схема опускается раньше подъёма младшего образа" "$plan" \
        "schema-sync.sh --apply --to 134 blue" "up -d app-green"

    # 3. «Только миграции» не доходит до трафика вовсе.
    plan=$(ENV_FILE="$dir/env" DEPLOY_ACTIVE=app-blue DEPLOY_HISTORY="$dir/h" \
        bash "$0" --tag "$sha" --migrations "только миграции" --dry-run 2>&1)
    absent "только миграции: трафик не трогается" "$plan" "switch-build.sh green"
    absent "только миграции: старая не гаснет" "$plan" "stop app-blue"
    case "$plan" in
        *"schema-sync.sh"*) say "только миграции: схема всё же приводится" ;;
        *) nope "только миграции: схему не приводит" ;;
    esac

    # 4. «Не трогать» не зовёт миграцию, но выкладывает.
    plan=$(ENV_FILE="$dir/env" DEPLOY_ACTIVE=app-blue DEPLOY_HISTORY="$dir/h" \
        bash "$0" --tag "$sha" --migrations "не трогать" --dry-run 2>&1)
    absent "не трогать: миграции не зовутся" "$plan" "schema-sync.sh"
    case "$plan" in
        *"switch-build.sh green"*) say "не трогать: код всё же выкладывается" ;;
        *) nope "не трогать: код не выкладывается" ;;
    esac

    # 5. Цель — всегда ДРУГАЯ сборка. Зелёная под трафиком означает выкладку
    #    в синюю; перепутав, выкладка подменила бы работающее приложение.
    plan=$(ENV_FILE="$dir/env" DEPLOY_ACTIVE=app-green DEPLOY_HISTORY="$dir/h" \
        bash "$0" --tag "$sha" --dry-run 2>&1)
    before "под трафиком зелёная — выкладываем в синюю" "$plan" "up -d app-blue" "stop app-green"

    # 6. Данные, которых нет, останавливают выкладку ДО первого изменения.
    for choice in "обезличенный слепок прома" "эталонный набор"; do
        plan=$(ENV_FILE="$dir/env" DEPLOY_ACTIVE=app-blue DEPLOY_HISTORY="$dir/h" \
            bash "$0" --tag "$sha" --data "$choice" --dry-run 2>&1)
        if [ $? = 0 ]; then
            nope "«${choice}» обязано быть отказом"
        else
            absent "«${choice}»: остановлено до единого изменения" "$plan" "ШАГ:"
        fi
    done

    # 7. Мусор в аргументах — отказ, а не умолчание. Опечатка в «не трогать»
    #    не должна тихо превращаться в накат схемы.
    for args in "--tag $sha --migrations нетрогать" "--tag короткий" "--tag $sha --migrate-with чужой" \
                "--tag $sha --migrate-with current" "--tag $sha --data чужое"; do
        if ENV_FILE="$dir/env" DEPLOY_ACTIVE=app-blue DEPLOY_HISTORY="$dir/h" \
            bash "$0" $args --dry-run >/dev/null 2>&1; then
            nope "«${args}» обязано быть отказом"
        else
            say "«${args}» — отказ"
        fi
    done

    # 8. Правка .env: на месте, с раскомментированием и без потери соседей.
    ENV_FILE="$dir/env" set_env APP_IMAGE_TAG_GREEN "$sha"
    ENV_FILE="$dir/env" set_env DEPLOY_NEW "значение"
    if grep -q "^APP_IMAGE_TAG_GREEN=$sha$" "$dir/env" \
        && grep -q "^COMPOSE_PROFILES=blue$" "$dir/env" \
        && grep -q "^DEPLOY_NEW=значение$" "$dir/env" \
        && [ "$(grep -c APP_IMAGE_TAG_GREEN "$dir/env")" = 1 ]; then
        say ".env правится на месте: закомментированный ключ раскрыт, соседи целы"
    else
        nope ".env поправлен неверно: $(cat "$dir/env" | tr '\n' '|')"
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

deploy
