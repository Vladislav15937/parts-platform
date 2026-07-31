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

: "${APP_URL:=http://localhost:8080}"
: "${PROVISIONING_TOKEN:?нужен секрет: export PROVISIONING_TOKEN=...}"

APPLY="${1:-}"

fail() { printf '\033[1;31m%s\033[0m\n' "$1" >&2; exit 1; }

# Секрет не уходит ни в адрес, ни в аргументы curl'а: адреса пишут access-лог
# терминатора и логи промежуточных прокси, а аргументы видит в ps любой,
# у кого есть учётка на машине. Остаются файлы в каталоге, закрытом от чужих
# и убираемом по выходу — в том числе по ошибке, отсюда trap.
SECRETS=$(mktemp -d)
trap 'rm -rf "$SECRETS"' EXIT
chmod 700 "$SECRETS"
printf 'X-Provisioning-Token: %s\n' "$PROVISIONING_TOKEN" > "$SECRETS/header"
printf '{"token":"%s"}' "$PROVISIONING_TOKEN" > "$SECRETS/body"

# Сколько элементов в списке ответа: с ключом, которого нет, — ноль.
count() {
    python3 -c "import sys, json; print(len(json.load(sys.stdin).get('$1', [])))"
}

if [ "$APPLY" = "--apply" ]; then
    echo "==> Накатываем миграции арендаторов"
    RESPONSE=$(curl -sS -X POST "$APP_URL/api/provisioning/migrations" \
        -H 'Content-Type: application/json' \
        --data "@$SECRETS/body")
    echo "$RESPONSE" | python3 -m json.tool

    # Сорвавшиеся не роняют проход — но роняют шаг развёртывания: релиз,
    # оставивший клиента на старой схеме, обязан быть заметен сразу.
    FAILED=$(echo "$RESPONSE" | count failures)
    [ "$FAILED" = "0" ] || fail "Не мигрировано схем: $FAILED — разбирать руками"
fi

# deep=true: спрашиваем каждую схему, а не верим отметке в реестре. Отметка
# врёт, если в схему лазили руками — а перед выкладкой кода, рассчитывающего
# на новую схему, ошибаться в эту сторону нельзя.
echo "==> Кто отстал"
STATUS=$(curl -sS "$APP_URL/api/provisioning/migrations?deep=true" -H "@$SECRETS/header")
echo "$STATUS" | python3 -m json.tool

BEHIND=$(echo "$STATUS" | count behind)

if [ "$BEHIND" != "0" ]; then
    fail "Отставших схем: $BEHIND. Код, рассчитывающий на новую схему, выкладывать нельзя"
fi

printf '\033[1;32mВсе схемы на поставляемой версии\033[0m\n'
