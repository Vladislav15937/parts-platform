#!/usr/bin/env bash
# Поднять ячейку (`docker compose up -d`) с умолчанием профиля по терминатору.
#
#   ./ops/up.sh                  # up -d; профиль — из .env, а нет строки — по терминатору
#   ./ops/up.sh app-green        # аргументы уходят в `up -d` как есть
#   ./ops/up.sh --profile        # только ответ: blue | green (слова — в stderr)
#
# Зачем (задача 0128). Задача 0110 сделала умолчанием «синюю»: нет строки
# COMPOSE_PROFILES в .env — поднимается прежняя сборка. Остался край: ячейка
# работала на ЗЕЛЁНОЙ, строку потеряли, `up -d` поднял синюю, а терминатор
# смотрит на зелёную — приложение не отвечает, тот же простой. Имя сборки под
# трафиком уже записано в одном месте, ops/active-build.caddy (0079), — отсюда
# и берётся умолчание. Второго источника нет намеренно.
#
# Почему скрипт, а не строка в compose: compose не читает чужих файлов, а
# профиль у него задаётся переменной окружения. Голый `docker compose up -d`
# по-прежнему поднимает синюю (пустой профиль у app-blue, 0110) — это страховка,
# а не путь; верный путь — этот скрипт.
#
# Не усложняет подъём: заданное (в оболочке или в .env) не трогается вовсе;
# файла нет или он нечитаем — поднимается синяя, и это сказано словами,
# а не падением. Строку в .env скрипт НЕ пишет: её ведёт ops/deploy.sh.

set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="${COMPOSE:-docker compose -f docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"
ACTIVE_FILE="${ACTIVE_FILE:-ops/active-build.caddy}"

say() { printf '%s\n' "$1" >&2; }

# Профиль, который задан явно: переменная оболочки старше файла (как у compose).
explicit_profiles() {
    if [ -n "${COMPOSE_PROFILES:-}" ]; then printf '%s\n' "$COMPOSE_PROFILES"; return 0; fi
    [ -f "$ENV_FILE" ] || return 0
    sed -n 's/^COMPOSE_PROFILES=//p' "$ENV_FILE" | tail -n1
}

resolve() {
    local by_hand build rc=0
    by_hand=$(explicit_profiles)
    if [ -n "$by_hand" ]; then
        printf '%s\n' "$by_hand"
        return 0
    fi
    build=$(ACTIVE_FILE="$ACTIVE_FILE" ENV_FILE="$ENV_FILE" bash ops/switch-build.sh --current 2>/dev/null) || rc=$?
    case "$rc:$build" in
        0:app-blue)
            say "Строки COMPOSE_PROFILES нет — поднимаю синюю: терминатор смотрит на неё ($ACTIVE_FILE)."
            printf 'blue\n' ;;
        0:app-green)
            say "Строки COMPOSE_PROFILES нет — поднимаю ЗЕЛЁНУЮ: терминатор смотрит на неё ($ACTIVE_FILE)."
            say "Впишите COMPOSE_PROFILES=green в $ENV_FILE, иначе голый «docker compose up -d» поднимет синюю."
            printf 'green\n' ;;
        *)
            say "Строки COMPOSE_PROFILES нет, а какая сборка под трафиком, прочитать не удалось ($ACTIVE_FILE)."
            say "Поднимаю синюю, как умолчание ячейки. Проверьте, на какую сборку смотрит терминатор."
            printf 'blue\n' ;;
    esac
}

case "${1:-}" in
    --profile) resolve; exit 0 ;;
esac

PROFILES=$(resolve)
# Умолчание применяется только к этому запуску; compose читает его из окружения.
export COMPOSE_PROFILES="$PROFILES"
# shellcheck disable=SC2086
exec $COMPOSE up -d "$@"
