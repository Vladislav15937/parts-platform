#!/usr/bin/env bash
# Полнота шаблона окружения живого пилота — сторож `ops/env.pilot.example`.
#
#   ./ops/env-template-guard.sh             # проверить шаблон
#   ./ops/env-template-guard.sh --selftest  # проверить самого сторожа
#
# У общего `.env.example` проверка есть в задаче CI «Развёртывание»: забытая
# обязательная переменная валит `compose config`. У шаблона пилота её не было
# (задача 0108), а он несёт то, что человек задаёт в день подключения клиента:
# домены, почту сертификатов, офсайт-копию, тег образа. Пропавший
# APP_IMAGE_TAG нашёлся перебором (0100), а не проверкой.
#
# Что делает. Шаблон заполняется заглушкой в каждой пустой переменной, КРОМЕ
# тех, что до подключения пусты законно (OPTIONAL_EMPTY), и compose разбирает
# боевой файл обеими сборками. Отказ — переменная, которую compose требует,
# а в шаблоне её нет или она вычеркнута. Остающиеся пустыми не заполняются
# намеренно: сторож, требующий лишнего, отключают в первый же день.
#
# Заглушка `ci-placeholder` живёт только во временном .env и в шаблон не
# попадает: пример с правдоподобным значением однажды уехал бы в бой как есть.

set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
TEMPLATE="${TEMPLATE:-ops/env.pilot.example}"
DC="${DC:-docker compose}"

# Пусты до подключения законно: секрет создания арендаторов (выключен), почта
# оператора (договор — человек), офсайт (после rclone config), роли базы
# (заполнит ops/create-roles.sh). Самопроверка требует, чтобы каждая из них
# в шаблоне была пуста: пометка про заполненную переменную устарела бы молча.
OPTIONAL_EMPTY="APP_PROVISIONING_TOKEN APP_MAIL_API_KEY APP_MAIL_BASE_URL APP_MAIL_FROM APP_MAIL_WEBHOOK_SECRET OFFSITE_REMOTE APP_RUNTIME_ROLE APP_RUNTIME_PASSWORD APP_DDL_USERNAME APP_DDL_PASSWORD"
# Compose требует их через `:?` — самопроверка вычёркивает каждую по очереди.
REQUIRED="APP_IMAGE_TAG DB_PASSWORD APP_CRYPTO_KEY S3_SECRET_KEY ACME_EMAIL ALERT_TELEGRAM_CHAT_ID APP_DOMAIN S3_DOMAIN PUBLIC_ORIGIN S3_PUBLIC_ENDPOINT"

red()   { printf '\033[1;31m%s\033[0m\n' "$1" >&2; }
green() { printf '\033[1;32m%s\033[0m\n' "$1"; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Выход 0 — compose разобрал; иначе его отказ в stderr. Переменные оболочки
# у compose старше файла — их снимаем, иначе унаследованная подменит ответ.
check_file() {  # $1 — шаблон
    local skip="" v
    local unset_args=()
    for v in $OPTIONAL_EMPTY; do skip="$skip|$v"; done
    skip="${skip#|}"
    # Пустая строка `NAME=` → заглушка, кроме законно пустых.
    sed -E "/^(${skip})=\$/! s/^([A-Z0-9_]+)=\$/\\1=ci-placeholder/" "$1" > "$TMP/env"
    while IFS= read -r v; do unset_args+=(-u "$v"); done \
        < <(grep -oE '^[A-Z0-9_]+=' "$TMP/env" | tr -d '=')
    env "${unset_args[@]}" $DC -f "$COMPOSE_FILE" --project-directory . \
        --env-file "$TMP/env" --profile blue --profile green config >/dev/null
}

if [ "${1:-}" != "--selftest" ]; then
    if err=$(check_file "$TEMPLATE" 2>&1); then
        green "Шаблон $TEMPLATE полон: compose разбирает обе сборки."
        exit 0
    fi
    red "Шаблон $TEMPLATE неполон — compose отказал:"
    # Предупреждения compose о незаданных переменных идут первыми и отказа
    # не называют — показываем строки отказа, а при их отсутствии начало.
    { printf '%s\n' "$err" | grep -i 'error\|required' || printf '%s\n' "$err" | sed -n '1,3p'; } >&2
    exit 1
fi

fail=0
note() { red "САМОПРОВЕРКА: $1"; fail=1; }

# 1. Настоящий шаблон обязан проходить, оставив законно пустые пустыми.
check_file "$TEMPLATE" 2>"$TMP/err" || note "настоящий шаблон не проходит: $(head -n1 "$TMP/err")"
for v in $OPTIONAL_EMPTY; do
    grep -qE "^$v=\$" "$TEMPLATE" || note "$v названа законно пустой, а в шаблоне она не пуста или её нет"
done

# 2. Вычеркнутая обязательная переменная валит проверку и названа в отказе.
for v in $REQUIRED; do
    grep -vE "^$v=" "$TEMPLATE" > "$TMP/cut" || true
    if check_file "$TMP/cut" 2>"$TMP/err"; then
        note "без $v проверка зелёная — сторож ничего не стережёт"
    elif ! grep -q "$v" "$TMP/err"; then
        note "без $v проверка красная, но отказ её не называет: $(head -n1 "$TMP/err")"
    fi
done

# 3. Заглушка не просачивается в шаблон.
if grep -q 'ci-placeholder' "$TEMPLATE"; then note "в шаблоне лежит заглушка ci-placeholder"; fi

[ "$fail" = 0 ] || exit 1
green "Самопроверка: шаблон проходит, каждая вычеркнутая обязательная валит проверку и названа."
