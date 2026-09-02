#!/usr/bin/env bash
# Накат миграций на схемы уже заведённых арендаторов ячейки.
#
#   ./migrate-tenants.sh                 # проверить, кто отстал
#   ./migrate-tenants.sh --apply         # накатить
#
# Шаг развёртывания, а не старт приложения: пятьсот схем на подъёме — это
# минуты недоступности всей ячейки, и падение одной миграции не дало бы
# подняться остальным клиентам.
#
# Порядок выкладки — expand/contract, и он важнее самого скрипта:
#   1. миграция, совместимая со старым кодом (добавить колонку, не удалять)
#   2. этот скрипт с --apply
#   3. проверка без --apply: behind пуст
#   4. и только теперь код, который на новую схему рассчитывает
# Обратный порядок оставляет окно, в котором новый код ходит в старую схему,
# и никакой оркестратор от этого не спасает.

set -euo pipefail
cd "$(dirname "$0")/.."

APPLY="${1:-}"

fail() { printf '\033[1;31m%s\033[0m\n' "$1" >&2; exit 1; }

# Секрет берётся из файла окружения ячейки, как у бэкапа и заведения ролей.
# Пока он требовался переменной с другим именем (PROVISIONING_TOKEN против
# APP_PROVISIONING_TOKEN в .env), шаг развёртывания начинался с ручного
# экспорта — а забытый экспорт роняет накат ровно в день выкладки.
ENV_FILE="${ENV_FILE:-.env}"
if [ -z "${PROVISIONING_TOKEN:-}" ] && [ -f "$ENV_FILE" ]; then
    set -a; . "./$ENV_FILE"; set +a
    PROVISIONING_TOKEN="${APP_PROVISIONING_TOKEN:-}"
fi
: "${PROVISIONING_TOKEN:?нужен секрет: APP_PROVISIONING_TOKEN в $ENV_FILE}"

# Куда стучаться. Порт приложения у ячейки намеренно не опубликован — наружу
# смотрит только терминатор, — поэтому умолчание «localhost:8080» указывало
# мимо ячейки: в лучшем случае в никуда, в худшем в другое приложение,
# поднятое на той же машине. Ходим внутрь сети compose, а заданный руками
# APP_URL означает «оператор знает, куда», и тогда идём с хоста.
COMPOSE="docker compose -f docker-compose.prod.yml"
if [ -n "${APP_URL:-}" ]; then
    IN_CELL=no
else
    APP_URL=http://app:8080
    IN_CELL=yes
fi

# Секрет не уходит ни в адрес, ни в аргументы curl'а: адреса пишут access-лог
# терминатора и логи промежуточных прокси, а аргументы видит в ps любой,
# у кого есть учётка на машине. Остаются файлы в каталоге, закрытом от чужих
# и убираемом по выходу — в том числе по ошибке, отсюда trap.
SECRETS=$(mktemp -d)
chmod 700 "$SECRETS"
printf 'X-Provisioning-Token: %s\n' "$PROVISIONING_TOKEN" > "$SECRETS/header"
printf '{"token":"%s"}' "$PROVISIONING_TOKEN" > "$SECRETS/body"

if [ "$IN_CELL" = yes ]; then
    # Файлы кладём внутрь того контейнера, из которого зовём: содержимое
    # уходит потоком, а не аргументом, — иначе секрет виден в ps.
    SEC=/tmp/pf-migrate
    $COMPOSE exec -T caddy sh -c "mkdir -p $SEC && chmod 700 $SEC && cat > $SEC/header" \
        < "$SECRETS/header"
    $COMPOSE exec -T caddy sh -c "cat > $SEC/body" < "$SECRETS/body"
    trap 'rm -rf "$SECRETS"; $COMPOSE exec -T caddy rm -rf "$SEC" >/dev/null 2>&1 || true' EXIT
    api() { $COMPOSE exec -T caddy curl -sS -m 900 -w '\n%{http_code}' "$@"; }
else
    SEC="$SECRETS"
    trap 'rm -rf "$SECRETS"' EXIT
    api() { curl -sS -m 900 -w '\n%{http_code}' "$@"; }
fi

# Ответ и код порознь: без кода отказ приложения неотличим от ответа по делу.
body() { printf '%s\n' "$1" | sed '$d'; }
code() { printf '%s\n' "$1" | tail -n1; }

expect_ok() {
    [ "$(code "$1")" = "200" ] || fail "Приложение ответило $(code "$1"): $(body "$1")"
}

# Сколько элементов в списке ответа. Ключа нет — это не ноль, а чужой ответ:
# «Недостаточно прав» без ключа behind читалось как «отставших нет», и шаг
# развёртывания зеленел, не мигрировав ничего.
count() {
    python3 -c "
import sys, json
key = '$1'
try:
    answer = json.load(sys.stdin)
except ValueError:
    sys.exit('ответ не JSON')
if not isinstance(answer, dict) or key not in answer:
    sys.exit('в ответе нет ключа ' + key)
print(len(answer[key]))"
}

if [ "$APPLY" = "--apply" ]; then
    echo "==> Накатываем миграции арендаторов"
    ANSWER=$(api -X POST "$APP_URL/api/provisioning/migrations" \
        -H 'Content-Type: application/json' \
        --data "@$SEC/body")
    expect_ok "$ANSWER"
    RESPONSE=$(body "$ANSWER")
    echo "$RESPONSE" | python3 -m json.tool

    # Сорвавшиеся не роняют проход — но роняют шаг развёртывания: релиз,
    # оставивший клиента на старой схеме, обязан быть заметен сразу.
    FAILED=$(echo "$RESPONSE" | count failures) || fail "Накат ответил не тем: $RESPONSE"
    [ "$FAILED" = "0" ] || fail "Не мигрировано схем: $FAILED — разбирать руками"
fi

# deep=true: спрашиваем каждую схему, а не верим отметке в реестре. Отметка
# врёт, если в схему лазили руками — а перед выкладкой кода, рассчитывающего
# на новую схему, ошибаться в эту сторону нельзя.
echo "==> Кто отстал"
ANSWER=$(api "$APP_URL/api/provisioning/migrations?deep=true" -H "@$SEC/header")
expect_ok "$ANSWER"
STATUS=$(body "$ANSWER")
echo "$STATUS" | python3 -m json.tool

BEHIND=$(echo "$STATUS" | count behind) || fail "Проверка ответила не тем: $STATUS"

if [ "$BEHIND" != "0" ]; then
    fail "Отставших схем: $BEHIND. Код, рассчитывающий на новую схему, выкладывать нельзя"
fi

printf '\033[1;32mВсе схемы на поставляемой версии\033[0m\n'
