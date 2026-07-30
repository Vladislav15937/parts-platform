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

if [ "$APPLY" = "--apply" ]; then
    echo "==> Накатываем миграции арендаторов"
    RESPONSE=$(curl -sS -X POST "$APP_URL/api/provisioning/migrations" \
        -H 'Content-Type: application/json' \
        -d "{\"token\":\"$PROVISIONING_TOKEN\"}")
    echo "$RESPONSE" | python3 -m json.tool

    # Сорвавшиеся не роняют проход — но роняют шаг развёртывания: релиз,
    # оставивший клиента на старой схеме, обязан быть заметен сразу.
    FAILED=$(echo "$RESPONSE" | python3 -c \
        'import sys,json; print(len(json.load(sys.stdin).get("failures", [])))')
    [ "$FAILED" = "0" ] || fail "Не мигрировано схем: $FAILED — разбирать руками"
fi

echo "==> Кто отстал"
STATUS=$(curl -sS "$APP_URL/api/provisioning/migrations?token=$PROVISIONING_TOKEN")
echo "$STATUS" | python3 -m json.tool

BEHIND=$(echo "$STATUS" | python3 -c \
    'import sys,json; print(len(json.load(sys.stdin).get("behind", [])))')

if [ "$BEHIND" != "0" ]; then
    fail "Отставших схем: $BEHIND. Код, рассчитывающий на новую схему, выкладывать нельзя"
fi

printf '\033[1;32mВсе схемы на поставляемой версии\033[0m\n'
