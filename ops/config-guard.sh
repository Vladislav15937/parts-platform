#!/usr/bin/env bash
# Настройки ячейки на месте — и это файлы, а не каталоги.
#
#   ops/config-guard.sh             # проверить перед подъёмом ячейки
#   ops/config-guard.sh --selftest  # проверить самого сторожа на подделках
#
# Зачем он есть. Docker на месте ОТСУТСТВУЮЩЕГО файла bind-mount'а создаёт
# пустой КАТАЛОГ и подключает его в контейнер — молча, с кодом возврата ноль.
# До задачи 0074 рядом с ячейкой всегда лежал `git pull`, то есть настройки
# приезжали вместе с кодом; теперь код приезжает образом, а каталог `ops/`
# на машине может быть неполным законно — после частичного rsync, после
# выкладки из чужого каталога, после ручного переноса. Дыра появилась
# следствием правильного изменения.
#
# Хуже всего то, что пропажа не видна. Наблюдалось в чистом прогоне:
# `ops/archive-wal.sh` стал каталогом, Postgres поднялся, клиенты работают,
# а `archive_command` отвечает 126 на каждый сегмент — WAL копится в pg_wal
# и никуда не уезжает. Снаружи всё в порядке: приложение отвечает, очередь
# событий пуста, дампы снимаются. Выясняется это при первой попытке
# вернуться к моменту времени, то есть в аварию.
#
# ПОЧЕМУ ЭТО СТОРОЖ ПЕРЕД ПОДЪЁМОМ, А НЕ ТРЕВОГА (решение исполнителя,
# задача 0101). Четыре из семи монтируемых файлов — это САМ механизм тревог:
# `prometheus.yml`, `alerts.yml`, `alertmanager.yml` и файл с токеном бота.
# Пропади любой — ячейка останется без единой тревоги, и сообщить об этом
# будет некому: тревогой нельзя защитить то, чем тревоги подаются. Поэтому
# состав настроек проверяется ДО подъёма, отдельно от наблюдения.
#
# И почему не падением при старте. Ячейка, не поднявшаяся из-за пропавшего
# `alertmanager.yml`, — это простой живого клиента: продавать он в этот
# момент может, а вот «настройка тревог на месте» его смены не стоит.
# Отказ здесь — это отказ КОМАНДЫ, которую человек только что набрал,
# ещё до того, как что-нибудь поднято и что-нибудь погашено. Работающую
# ячейку этот сторож не трогает вовсе.
#
# Цена решения названа вслух: `docker compose up -d`, набранный руками мимо
# инструкции, сторожа не зовёт. Вторая половина задачи закрывает ровно этот
# случай для архива — `ops/wal-archive.sh` спрашивает у базы, отбит ли
# архиватор ПРЯМО СЕЙЧАС, и это уже тревога (`АрхиваторWALОтбит`). Для
# остальных шести пропаж такой второй половины нет: ячейка, у которой
# отняли наблюдение, о себе не сообщит ничем.
#
# Спрашиваем сам compose, а не читаем YAML глазами — по тому же доводу, что
# и ops/builds-guard.sh: какие пути монтируются и во что они разворачиваются,
# считает он, по своим правилам интерполяции.
set -euo pipefail

SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
# Корень переопределяется только самопроверкой: ей нужно прогнать сторожа
# по подделанному дереву, не трогая настоящее.
ROOT="${CONFIG_GUARD_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$ROOT"

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
DC="${DC:-docker compose}"

red()   { printf '\033[1;31m%s\033[0m\n' "$1" >&2; }
green() { printf '\033[1;32m%s\033[0m\n' "$1"; }

# Чем ячейка платит за пропажу каждого файла. Ключ — сервис и путь ВНУТРИ
# контейнера: путь на хосте зависит от каталога и от .env, а этот не зависит
# ни от чего и читается человеком.
#
# Причина у каждого своя не для красоты: «файла нет» ничего не говорит тому,
# кто читает отказ в четыре утра, а «WAL не уезжает, точки возврата нет» —
# говорит, что делать дальше.
KIND=""
REASON=""
expect() {  # сервис:путь-в-контейнере → KIND (файл|каталог|неизвестно) и REASON
    # Ответ кладётся в переменные, а НЕ печатается наружу. `kind=$(expect …)`
    # — это подстановка команды, то есть подоболочка: имя вида доезжало бы,
    # а причина терялась, и отказ печатался бы с пустым «чем платим». Поймано
    # живым прогоном — ровно на том, ради чего причины и заведены.
    KIND=неизвестно
    REASON=""
    case "$1" in
        caddy:/etc/caddy/Caddyfile)
            KIND=файл
            REASON="терминатор не поднимется: снаружи 502 всем, камера в браузере не работает" ;;
        caddy:/etc/caddy/active-build.caddy)
            KIND=файл
            REASON="Caddy не импортирует каталог — тот же 502, и переключать трафик между сборками нечем" ;;
        postgres:/usr/local/bin/archive-wal.sh)
            KIND=файл
            REASON="archive_command отвечает 126 при РАБОТАЮЩЕЙ базе: WAL копится в pg_wal, точка возврата стоит на месте — и всё это молча" ;;
        prometheus:/etc/prometheus/prometheus.yml)
            KIND=файл
            REASON="сборщик метрик не поднимется — у ячейки не останется НИ ОДНОЙ тревоги" ;;
        prometheus:/etc/prometheus/alerts.yml)
            KIND=файл
            REASON="правила не загрузятся — у ячейки не останется НИ ОДНОЙ тревоги" ;;
        alertmanager:/etc/alertmanager/alertmanager.yml)
            KIND=файл
            REASON="тревоги некуда слать: они будут считаться, но не уедут" ;;
        alertmanager:/etc/alertmanager/telegram-token)
            KIND=файл
            REASON="тревоги не уйдут в Telegram — молча, механизм при этом выглядит работающим" ;;
        alert-watch:/usr/local/bin/alert-channel-check.sh)
            KIND=файл
            REASON="за каналом тревог никто не смотрит: отбитую доставку опять будет видно только в логе диспетчера (так ячейка молчала сутки 19 сентября 2026)" ;;
        alert-watch:/etc/alert-channel/telegram-token)
            KIND=файл
            REASON="второму пути нечем слать: сторож увидит немой канал и не сможет о нём сказать" ;;
        # Архив WAL — законный каталог: его готовит разовый контейнер
        # wal-archive-init, и docker создаёт его сам, что здесь правильно.
        *:/wal-archive)
            KIND=каталог
            REASON="каталог архива WAL, его готовит wal-archive-init" ;;
    esac
}

# Те, без которых ячейка не ячейка. Список проверяется в обе стороны:
# пропавший на хосте файл — отказ, и пропавшее из compose монтирование —
# тоже отказ. Иначе сторож молча перестал бы смотреть за тем, что у него
# из-под ног убрали.
NUZHNY="caddy:/etc/caddy/Caddyfile
caddy:/etc/caddy/active-build.caddy
postgres:/usr/local/bin/archive-wal.sh
prometheus:/etc/prometheus/prometheus.yml
prometheus:/etc/prometheus/alerts.yml
alertmanager:/etc/alertmanager/alertmanager.yml
alertmanager:/etc/alertmanager/telegram-token
alert-watch:/usr/local/bin/alert-channel-check.sh
alert-watch:/etc/alert-channel/telegram-token"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Все bind-монтирования боевого compose, с уже подставленными переменными.
# Профили названы все до одного: `config` печатает только сервисы включённых
# профилей, а сборки и служебные контейнеры стоят под своими — без этого
# половина монтирований не проверялась бы вовсе.
mounts() {
    $DC -f "$COMPOSE_FILE" --project-directory . --env-file "$ENV_FILE" \
        --profile blue --profile green --profile kafka --profile backup \
        config --format json 2>"$TMP/err" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for name, svc in sorted((d.get("services") or {}).items()):
    for v in svc.get("volumes") or []:
        if v.get("type") != "bind":
            continue
        print("\t".join([name, v.get("target", ""), v.get("source", "")]))
'
}

run_checks() {
    local bad=0 seen="" svc target source kind
    local list
    if ! list=$(mounts); then
        red "Не удалось спросить compose про монтирования:"
        sed 's/^/    /' "$TMP/err" >&2
        return 1
    fi

    while IFS=$'\t' read -r svc target source; do
        [ -n "$svc" ] || continue
        expect "$svc:$target"
        case "$KIND" in
            файл)
                seen="$seen$svc:$target"$'\n'
                if [ -f "$source" ]; then
                    printf '  ✓ %s\n' "$source"
                elif [ -d "$source" ]; then
                    red "  ✗ $source — КАТАЛОГ вместо файла (его создал docker на месте пропавшего)"
                    red "      $REASON"
                    bad=1
                else
                    red "  ✗ $source — нет вовсе; docker создаст на его месте пустой каталог"
                    red "      $REASON"
                    bad=1
                fi ;;
            каталог)
                if [ -d "$source" ] || [ ! -e "$source" ]; then
                    printf '  · %s — %s\n' "$source" "$REASON"
                else
                    red "  ✗ $source — файл вместо каталога ($REASON)"
                    bad=1
                fi ;;
            *)
                red "  ✗ $svc монтирует $target, а сторож про этот путь не знает"
                red "      Впишите его в ops/config-guard.sh: файл это или каталог и чем ячейка платит за пропажу"
                bad=1 ;;
        esac
    done < <(printf '%s\n' "$list")

    local need
    while IFS= read -r need; do
        [ -n "$need" ] || continue
        case "$seen" in
            *"$need"$'\n'*) : ;;
            *) red "  ✗ $need больше не монтируется в $COMPOSE_FILE — сторож смотрел за тем, чего нет"
               bad=1 ;;
        esac
    done <<< "$NUZHNY"

    return $bad
}

# --- проверка самого сторожа ------------------------------------------------
#
# Сторож, не краснеющий на дефекте, хуже отсутствующего, и установить это
# можно только попыткой. Подделки — ровно те три способа, которыми эта защита
# перестаёт работать: пропал файл архива (тот самый случай из задачи 0101),
# пропали правила тревог (ячейка без наблюдения) и укоротили список самого
# сторожа. Настоящее дерево при этом обязано проходить: сторож, краснеющий
# на исправной ячейке, отключат в первый же день вместе с защитой.
selftest() {
    local bad=0 fake out rc
    echo "Самопроверка ops/config-guard.sh"

    # Заглушки вместо секретов — как в задаче CI «Боевой compose». Файл
    # с токеном настоящий: он не из репозитория, и требовать его оттуда
    # сторож не должен, а вот проверить «файл на месте → зелено» обязан.
    printf 'stub\n' > "$TMP/telegram-token"
    sed -E 's/^([A-Z0-9_]+)=$/\1=ci-placeholder/' "$ROOT/.env.example" \
        | sed "s#^ALERT_TELEGRAM_TOKEN_FILE=.*#ALERT_TELEGRAM_TOKEN_FILE=$TMP/telegram-token#" \
        > "$TMP/env"

    probe() {  # корень (пусто — настоящий), имя случая
        set +e
        out=$(CONFIG_GUARD_ROOT="${1:-$ROOT}" ENV_FILE="$TMP/env" bash "$SELF" 2>&1); rc=$?
        set -e
    }

    probe "" "настоящее дерево"
    if [ "$rc" = 0 ]; then
        printf '  ✓ настоящее дерево проходит\n'
    else
        red "  ✗ настоящее дерево не проходит — смотрите прогон без --selftest"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        bad=1
    fi

    # Копия дерева: подделывать настоящее нельзя — рядом работает ячейка.
    fake="$TMP/fake"
    mkdir -p "$fake"
    cp "$ROOT/$COMPOSE_FILE" "$ROOT/.env.example" "$fake/"
    cp -R "$ROOT/ops" "$fake/ops"

    # Подделка 1: ровно то, что оставляет docker, — каталог на месте скрипта
    # архивации. Это и есть находка задачи 0101.
    rm -f "$fake/ops/archive-wal.sh"; mkdir -p "$fake/ops/archive-wal.sh"
    probe "$fake"
    # Причина проверяется наравне с именем: «файла нет» ничего не говорит
    # тому, кто читает отказ, и первая редакция печатала её пустой —
    # подстановка команды съедала вторую половину ответа.
    if [ "$rc" != 0 ] && grep -q 'archive-wal.sh' <<< "$out" \
       && grep -q 'КАТАЛОГ' <<< "$out" && grep -q 'точка возврата' <<< "$out"; then
        printf '  ✓ каталог на месте ops/archive-wal.sh — красное, назван и файл, и цена пропажи\n'
    else
        red "  ✗ подменённый каталогом archive-wal.sh обязан валить сторожа с именем файла и причиной"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        bad=1
    fi
    rmdir "$fake/ops/archive-wal.sh"; cp "$ROOT/ops/archive-wal.sh" "$fake/ops/archive-wal.sh"

    # Подделка 2: пропали правила тревог. Отдельным случаем, потому что это
    # та пропажа, о которой ячейка не может сообщить сама — тревогами.
    rm -f "$fake/ops/alerts.yml"
    probe "$fake"
    if [ "$rc" != 0 ] && grep -q 'alerts.yml' <<< "$out"; then
        printf '  ✓ пропавший ops/alerts.yml — красное (иначе ячейка осталась бы без тревог молча)\n'
    else
        red "  ✗ пропавший alerts.yml обязан валить сторожа"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        bad=1
    fi
    cp "$ROOT/ops/alerts.yml" "$fake/ops/alerts.yml"

    # Подделка 3: укоротили список самого сторожа. Без этого случая защиту
    # можно было бы снять одной строкой и не заметить: монтирование осталось,
    # смотреть за ним перестали.
    python3 - "$fake/ops/config-guard.sh" <<'PY'
import re, sys
p = sys.argv[1]
t = open(p, encoding="utf-8").read()
t = t.replace("        postgres:/usr/local/bin/archive-wal.sh)\n", "        postgres:/nikogda-ne-sovpadyot)\n", 1)
t = t.replace("postgres:/usr/local/bin/archive-wal.sh\n", "", 1)
open(p, "w", encoding="utf-8").write(t)
PY
    set +e
    out=$(CONFIG_GUARD_ROOT="$fake" ENV_FILE="$TMP/env" bash "$fake/ops/config-guard.sh" 2>&1); rc=$?
    set -e
    if [ "$rc" != 0 ]; then
        printf '  ✓ укороченный список валит сторожа: монтирование стало неизвестным\n'
    else
        red "  ✗ из списка убрали archive-wal.sh, а сторож промолчал — защиту можно снять незаметно"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        bad=1
    fi

    [ $bad = 0 ] || { red "Самопроверка не прошла"; exit 1; }
    green "Самопроверка пройдена"
}

ENV_FILE="${ENV_FILE:-.env}"

case "${1:-}" in
    --selftest) selftest; exit 0 ;;
    "") ;;
    *) red "Непонятный аргумент: $1"; exit 2 ;;
esac

[ -f "$ENV_FILE" ] || { red "Нет $ENV_FILE — это не каталог ячейки"; exit 1; }

echo "Настройки ячейки ($COMPOSE_FILE)"
if run_checks; then
    green "Все монтируемые настройки на месте и это файлы, а не каталоги"
else
    red "Ячейку в таком виде поднимать нельзя: docker подставит пустые каталоги молча"
    exit 1
fi
