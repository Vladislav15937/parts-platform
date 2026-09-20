#!/usr/bin/env bash
# Сторож канала тревог: уходят ли они вообще — и если нет, сказать об этом
# ВТОРЫМ путём, мимо самого канала.
#
#   ops/alert-channel-check.sh            # один проход (так его зовут руками)
#   ops/alert-channel-check.sh --loop     # проход раз в ALERT_CHANNEL_INTERVAL
#                                         # (так его держит контейнер alert-watch)
#   ops/alert-channel-check.sh --probe    # есть ли у диспетчера дорога до Telegram
#   ops/alert-channel-check.sh --selftest # проверка самого сторожа на подделках
#
# ЗАЧЕМ ОН ЕСТЬ. 19 сентября 2026 в 02:52 UTC доставка тревог боевой ячейки
# оборвалась, и до 20 сентября наружу не ушло НИ ОДНОГО сообщения: 2731 отказ
# в логе диспетчера, шесть горящих тревог, и ни одной строки человеку.
# Диспетчер честно писал о беде в свой лог — то есть сообщал о ней тому,
# кто и так смотрит в лог контейнера наблюдения, и молчал для всех, кто
# на наблюдение полагается. Сторож доставки, сообщающий через отказавший
# канал, бесполезен по построению: именно это и случилось.
#
# ПОЧЕМУ ВТОРОЙ ПУТЬ — ЭТО СЕТЬ ХОСТА, А НЕ ВТОРОЙ ПОЛУЧАТЕЛЬ. В тот день
# IPv4 к Telegram был закрыт, а IPv6 открыт — и с ХОСТА ячейки сообщение
# уходило (замер владельца: 302), а из контейнера диспетчера нет: сети docker
# по умолчанию без IPv6, и дорога у него оставалась одна и не та. Поэтому
# сторож ходит стеком хоста (`network_mode: host` у контейнера alert-watch),
# а диспетчер — своей сетью с IPv6. Дороги разные, и отказ одной не глушит
# другую. Второй получатель внутри того же диспетчера этого НЕ даёт: у него
# та же сеть, тот же процесс и тот же отказ.
#
# ЧЕГО ОН НЕ ЛОВИТ, И ЭТО НАДО ЗНАТЬ. Он видит «диспетчер не смог отправить»
# и «диспетчер не отвечает». Он НЕ видит «отправлено в чужой чат»: для
# Telegram это успех, и отличить его нечем, кроме как спросить человека.
# И он не спасёт, если Telegram недоступен с машины целиком — тогда молчат
# оба пути, и это свойство единственного канала, а не сторожа.
#
# ОКНО — ПЯТЬ МИНУТ, И ВОТ ПОЧЕМУ ИМЕННО ОНО. Диспетчер отменяет повторы
# после четырёх попыток (в логе ячейки: «notify retry canceled after
# 4 attempts»), то есть сообщение теряется НАВСЕГДА за минуты — ждать часами
# нечего. Обход каждые пять минут ловит первый же отказ; будит при этом
# не чаще раза в час (ALERT_CHANNEL_REPEAT), как и сам диспетчер повторяет
# тревогу раз в час: сторож, будящий каждые пять минут, будет отключён
# первым — вместе с защитой.
#
# ПОЧЕМУ НЕ CRON. Расписание ячейки с 10 сентября 2026 не отрабатывало вовсе
# (задача 0152): пять задач стояли в crontab и не запускались ни разу.
# Сторож доставки, поставленный в то же расписание, молчал бы вместе с ним —
# и молчал бы ровно те девять дней, когда он был нужен. Поэтому он держится
# контейнером ячейки со своим `restart: unless-stopped`, а не строкой cron.
set -uo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-.env}"

MODE=проход
case "${1:-}" in
    --loop)     MODE=цикл ;;
    --probe)    MODE=проба ;;
    --selftest) MODE=самопроверка ;;
    -h|--help)  sed -n '2,50p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    "")         ;;
    *)          printf 'Непонятный аргумент: %s\n' "$1" >&2; exit 2 ;;
esac

red()   { printf '\033[1;31m%s\033[0m\n' "$1" >&2; }
green() { printf '\033[1;32m%s\033[0m\n' "$1"; }
warn()  { printf '\033[1;33m%s\033[0m\n' "$1"; }

# Окружение ячейки — оттуда имя ячейки, номер чата и путь к токену. Нет файла:
# значит сторожа нацелили переменными снаружи (контейнер, самопроверка), и это
# законно.
if [ "$MODE" != самопроверка ] && [ -f "$ENV_FILE" ]; then
    set -a; . "./$ENV_FILE"; set +a
fi

CELL="${APP_CELL:-cell01}"
METRICS_URL="${ALERT_CHANNEL_METRICS_URL:-http://127.0.0.1:${ALERTMANAGER_PORT:-9093}/metrics}"
STATE="${ALERT_CHANNEL_STATE:-/var/lib/alert-channel/state}"
INTERVAL="${ALERT_CHANNEL_INTERVAL:-300}"
REPEAT="${ALERT_CHANNEL_REPEAT:-3600}"
TOKEN_FILE="${ALERT_CHANNEL_TOKEN_FILE:-${ALERT_TELEGRAM_TOKEN_FILE:-./ops/secrets/telegram-token}}"
CHAT_ID="${ALERT_TELEGRAM_CHAT_ID:-}"
PUSH_URL="${PUSHGATEWAY_URL:-http://localhost:9091}"
# Сеть с IPv6, которой диспетчер ходит наружу (docker-compose.prod.yml).
# Имя проекта у боевого compose — `partsflow-<ячейка>` (строка `name:` там же),
# docker приписывает его к имени сети. Умолчание собрано ровно по этому правилу:
# ошибись в нём — и проба скажет «сети нет» на исправной ячейке.
IPV6_NET="${ALERT_IPV6_NETWORK:-partsflow-${CELL}_alerts6}"
PROBE_IMAGE="${ALERT_PROBE_IMAGE:-curlimages/curl:8.11.1}"

# Швы для самопроверки: чем спросить метрики и чем отправить сообщение.
# Подменяются только ею — сторож, проверенный на своих же функциях, не
# проверен на том, что делает запуск.
FETCH_CMD="${ALERT_CHECK_FETCH:-}"
SEND_CMD="${ALERT_CHECK_SEND:-}"

now() { date -u +%s; }

# ─────────────────────────── чем спрашиваем диспетчера ───────────────────────
fetch_metrics() {
    if [ -n "$FETCH_CMD" ]; then
        $FETCH_CMD
        return $?
    fi
    curl -sf --max-time 10 "$METRICS_URL"
}

# Сумма отказов по всем причинам: диспетчер раскладывает их по reason
# (clientError, serverError, contextDeadlineExceeded, other), и на боевой
# ячейке отказ сети лёг в «other». Складывать надо все — сторож, смотрящий
# на одну причину, промолчит на следующей.
sum_failed() {  # метрики на stdin
    awk '/^alertmanager_notifications_failed_total\{integration="telegram"/ {
             n = split($0, p, " "); s += p[n]
         }
         END { printf "%d", s + 0 }'
}

sum_total() {
    awk '/^alertmanager_notifications_total\{integration="telegram"/ {
             n = split($0, p, " "); s += p[n]
         }
         END { printf "%d", s + 0 }'
}

# ─────────────────────────── второй путь: прямо в Telegram ───────────────────
#
# Отдельным `curl` с хоста, а не через диспетчера: в этом вся задача. Токен
# читается из файла (в переменной он виден в `docker inspect` любому, кто
# дошёл до хоста) — тот же файл и тот же довод, что у alertmanager.
send_second_path() {  # текст сообщения
    local text="$1" token reply
    if [ -n "$SEND_CMD" ]; then
        printf '%s' "$text" | $SEND_CMD
        return $?
    fi
    [ -n "$CHAT_ID" ] || { red "  Некуда слать: ALERT_TELEGRAM_CHAT_ID пуст"; return 1; }
    [ -f "$TOKEN_FILE" ] || { red "  Нечем слать: нет файла токена ${TOKEN_FILE}"; return 1; }
    token=$(cat "$TOKEN_FILE") || return 1
    reply=$(curl -sS --max-time 20 \
        --data-urlencode "chat_id=${CHAT_ID}" \
        --data-urlencode "text=${text}" \
        "https://api.telegram.org/bot${token}/sendMessage" 2>&1) || {
        red "  Второй путь тоже молчит: ${reply}"
        return 1
    }
    case "$reply" in
        *'"ok":true'*) return 0 ;;
        *) red "  Telegram не принял сообщение: ${reply}"; return 1 ;;
    esac
}

# ─────────────────────────── отметки в наблюдение ────────────────────────────
#
# Чтобы отказ доставки был виден не только в логе диспетчера. Сам по себе
# push ничего не будит — будит второй путь выше; отметка отвечает на вопрос
# «когда канал последний раз был жив» тому, кто смотрит в наблюдение.
mark() {  # $1 — 1 если канал жив, 0 если отбит; $2 — сколько отказов насчитали
    local ok="$1" failed="$2"
    printf '# TYPE partsflow_alert_channel_ok gauge
partsflow_alert_channel_ok %s
# TYPE partsflow_alert_channel_check_timestamp_seconds gauge
partsflow_alert_channel_check_timestamp_seconds %s
# TYPE partsflow_alert_notifications_failed_total gauge
partsflow_alert_notifications_failed_total %s
' "$ok" "$(now)" "$failed" \
        | curl -sf --max-time 10 --data-binary @- "$PUSH_URL/metrics/job/alert-channel" >/dev/null \
        || warn "  Отметка в наблюдение не ушла — проверьте pushgateway"
}

# ─────────────────────────── состояние между проходами ───────────────────────
read_state() {  # → PREV_FAILED PREV_TOTAL LAST_SHOUT
    PREV_FAILED=""; PREV_TOTAL=""; LAST_SHOUT=0
    [ -f "$STATE" ] || return 0
    # shellcheck disable=SC1090
    . "$STATE" 2>/dev/null || return 0
}

write_state() {  # $1 — отказов, $2 — отправок, $3 — когда будили
    mkdir -p "$(dirname "$STATE")" 2>/dev/null || true
    printf 'PREV_FAILED=%s\nPREV_TOTAL=%s\nLAST_SHOUT=%s\n' "$1" "$2" "$3" > "$STATE" \
        || warn "  Состояние не записать в ${STATE} — повтор будет будить каждый проход"
}

# ─────────────────────────── один проход ─────────────────────────────────────
#
# Возвращает: 0 — канал жив, 1 — канал отбит (и об этом сказано вторым путём
# либо намеренно промолчали по выдержке), 2 — отбит и сказать НЕ УДАЛОСЬ.
# Третий код отдельный не для красоты: «сообщили» и «не смогли сообщить» —
# разные вещи, и выдать второе за первое значит повторить саму поломку.
pass() {
    local metrics failed total delta text shout_age rc=0
    read_state

    if ! metrics=$(fetch_metrics); then
        broken "диспетчер тревог не отвечает (${METRICS_URL})" \
            "Alertmanager не отвечает на ${METRICS_URL}. Тревоги ячейки сейчас не уходят никому." \
            "${PREV_FAILED:-0}"
        return $?
    fi

    failed=$(printf '%s' "$metrics" | sum_failed)
    total=$(printf '%s' "$metrics" | sum_total)

    # Ответ не от диспетчера (не туда нацелили, чужой порт, страница-заглушка).
    # Без этой ветки сторож был бы вечно зелёным на пустом ответе — то есть
    # зелёным ровно тогда, когда смотреть перестал.
    case "$metrics" in
        *alertmanager_notifications_total*) ;;
        *) broken "ответ не похож на метрики диспетчера (${METRICS_URL})" \
               "Сторож канала не видит метрик Alertmanager по ${METRICS_URL} — проверить некому и нечем." \
               "${PREV_FAILED:-0}"
           return $? ;;
    esac

    if [ -z "${PREV_FAILED:-}" ]; then
        # Первый проход: отказы в счётчике — история (диспетчер считает их
        # за всю жизнь процесса). Разбудить на ней значит будить при каждом
        # перезапуске ячейки.
        write_state "$failed" "$total" "${LAST_SHOUT:-0}"
        mark 1 "$failed"
        green "Канал: первый проход, запомнили (отказов за жизнь диспетчера ${failed}, отправок ${total})"
        return 0
    fi

    if [ "$failed" -lt "$PREV_FAILED" ]; then
        # Счётчик уехал вниз — диспетчера перезапустили. Это не «стало лучше»
        # и не повод будить: просто начинаем считать заново.
        write_state "$failed" "$total" "${LAST_SHOUT:-0}"
        mark 1 "$failed"
        green "Канал: счётчики диспетчера сброшены (перезапуск), считаем заново"
        return 0
    fi

    delta=$((failed - PREV_FAILED))
    if [ "$delta" -gt 0 ]; then
        broken "диспетчер не доставил ${delta} сообщений за последние $((INTERVAL / 60)) мин" \
            "Тревоги ячейки НЕ УХОДЯТ: диспетчер не смог доставить ${delta} сообщений за последние $((INTERVAL / 60)) мин (отказов всего ${failed}). Повторы он отменяет после четырёх попыток — эти сообщения потеряны." \
            "$failed"
        rc=$?
        write_state "$failed" "$total" "$LAST_SHOUT_NEW"
        return $rc
    fi

    write_state "$failed" "$total" "${LAST_SHOUT:-0}"
    mark 1 "$failed"
    green "Канал жив: новых отказов нет (отправок ${total}, отказов за жизнь диспетчера ${failed})"
    return 0
}

# Канал отбит: отметить, сказать вторым путём (не чаще REPEAT) и вернуть код.
broken() {  # $1 — строка человеку в лог, $2 — текст сообщения, $3 — счётчик отказов
    local short="$1" body="$2" failed="$3" age
    LAST_SHOUT_NEW="${LAST_SHOUT:-0}"
    red "КАНАЛ ТРЕВОГ ОТБИТ: ${short}"
    mark 0 "$failed"

    age=$(( $(now) - ${LAST_SHOUT:-0} ))
    if [ "${LAST_SHOUT:-0}" -gt 0 ] && [ "$age" -lt "$REPEAT" ]; then
        warn "  Будили $((age / 60)) мин назад — молчим до $((REPEAT / 60)) мин (как и сам диспетчер повторяет раз в час)"
        write_state "${PREV_FAILED:-$failed}" "${PREV_TOTAL:-0}" "${LAST_SHOUT:-0}"
        return 1
    fi

    if send_second_path "[${CELL}] ${body}

Это сообщение пришло ВТОРЫМ путём — прямо с машины ячейки, мимо диспетчера. Значит сам диспетчер до Telegram не дотянулся.

Что смотреть: docker compose -f docker-compose.prod.yml logs --tail 50 alertmanager, затем ops/alert-channel-check.sh --probe"
    then
        LAST_SHOUT_NEW=$(now)
        green "  Сказали вторым путём (прямо с хоста, мимо диспетчера)"
        write_state "${PREV_FAILED:-$failed}" "${PREV_TOTAL:-0}" "$LAST_SHOUT_NEW"
        return 1
    fi

    red "  СООБЩИТЬ НЕ УДАЛОСЬ НИ ОДНИМ ПУТЁМ — ячейка сейчас нема"
    write_state "${PREV_FAILED:-$failed}" "${PREV_TOTAL:-0}" "${LAST_SHOUT:-0}"
    return 2
}

# ─────────────────────────── проба дороги ────────────────────────────────────
#
# Отвечает на вопрос, который нельзя вывести из конфигурации: дотягивается ли
# контейнер ДО Telegram по IPv6. Спрашивается это у самой сети — разовым
# контейнером в ней, — а не у хоста: у хоста IPv6 может работать при
# неработающем NAT66 в docker, и ровно на этой разнице стояла авария.
probe() {
    local code4 code6
    printf 'Проба дороги до Telegram (сеть %s)\n' "$IPV6_NET"

    if ! docker network inspect "$IPV6_NET" >/dev/null 2>&1; then
        red "  Сети ${IPV6_NET} нет — правка compose не применена (docker compose up -d alertmanager)"
        return 1
    fi
    case "$(docker network inspect -f '{{.EnableIPv6}}' "$IPV6_NET" 2>/dev/null)" in
        true) printf '  ✓ у сети %s включён IPv6\n' "$IPV6_NET" ;;
        *)    red "  ✗ у сети ${IPV6_NET} IPv6 ВЫКЛЮЧЕН — сеть создана до правки, пересоздайте её"; return 1 ;;
    esac

    code6=$(docker run --rm --network "$IPV6_NET" "$PROBE_IMAGE" \
        -6 -s -o /dev/null -w '%{http_code}' --max-time 10 https://api.telegram.org 2>/dev/null)
    code4=$(docker run --rm --network "$IPV6_NET" "$PROBE_IMAGE" \
        -4 -s -o /dev/null -w '%{http_code}' --max-time 10 https://api.telegram.org 2>/dev/null)

    printf '  IPv6 к api.telegram.org: %s\n' "${code6:-нет ответа}"
    printf '  IPv4 к api.telegram.org: %s (на боевой ячейке он закрыт — это норма)\n' "${code4:-нет ответа}"

    case "${code6:-000}" in
        000|"") red "  ✗ по IPv6 из этой сети до Telegram НЕ достучаться"
                red "    Если IPv6 работает с хоста (curl -6 https://api.telegram.org), значит docker не делает NAT66:"
                red "    смотрите ops/CLAUDE.md, «Если NAT66 на машине не работает» — там запасной путь без простоя"
                return 1 ;;
        *)      green "  ✓ дорога есть: диспетчеру из этой сети Telegram доступен" ;;
    esac
}

# ─────────────────────────── проверка самого сторожа ─────────────────────────
#
# Сторож, не краснеющий на дефекте, хуже отсутствующего, и установить это
# можно только попыткой. Ячейка, docker и сеть для этого не нужны: подменяются
# оба шва — чем спрашиваем диспетчера и чем шлём сообщение.
selftest() {
    local bad=0 out rc
    # Каталог НЕ локальный: уборка висит на EXIT, а тот срабатывает уже вне
    # функции — с локальной переменной трап падал бы «tmp: unbound variable»
    # под `set -u`, то есть сразу после зелёной самопроверки.
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    echo "Самопроверка ops/alert-channel-check.sh"

    # Заглушка отправки: складывает сообщение в файл, чтобы проверить не только
    # «покраснел», но и «сказал ли и что именно».
    cat > "$tmp/send-ok" <<EOF
#!/bin/sh
cat >> "$tmp/sent.txt"
exit 0
EOF
    cat > "$tmp/send-fail" <<EOF
#!/bin/sh
cat >> "$tmp/sent.txt"
exit 1
EOF
    chmod +x "$tmp/send-ok" "$tmp/send-fail"

    metrics_file() {  # $1 — отказов, $2 — отправок
        printf 'alertmanager_notifications_total{integration="telegram"} %s\n' "$2" > "$tmp/metrics"
        printf 'alertmanager_notifications_failed_total{integration="telegram",reason="other"} %s\n' "$1" >> "$tmp/metrics"
        printf 'alertmanager_notifications_failed_total{integration="telegram",reason="serverError"} 0\n' >> "$tmp/metrics"
    }

    # Чем запускать себя же. В контейнере сторожа bash'а НЕТ (образ
    # curlimages/curl), и compose зовёт скрипт через /bin/sh — значит
    # самопроверка, намертво прибитая к bash, проверяла бы не тот
    # интерпретатор, под которым сторож работает у клиента. Поймано прогоном
    # самопроверки внутри самого образа: «bash: not found», девять случаев
    # из девяти.
    RUNNER="${ALERT_CHECK_SHELL:-$(command -v bash || command -v sh)}"

    run() {  # $1 — команда добычи метрик, $2 — отправщик
        : > "$tmp/sent.txt"
        set +e
        # ENV_FILE=/dev/null обязателен, и это не перестраховка. Скрипт
        # сорсит файл ячейки через `set -a`, то есть `.env` СТАРШЕ того,
        # что передал вызывающий, — и проба, запущенная там, где `.env`
        # лежит, меряет не сторожа, а чужой файл. Поймано красным CI:
        # задача «Развёртывание» шагом раньше пишет настоящий `.env`
        # (заглушки из `.env.example`), и его `ALERT_CHANNEL_REPEAT=3600`
        # отменял выдержку, заданную пробой, — у себя зелено, на раннере
        # красное. Тот же класс, что в задаче 0144.
        out=$(ALERT_CHECK_FETCH="$1" ALERT_CHECK_SEND="$2" \
              ALERT_CHANNEL_STATE="$tmp/state" ALERT_CHANNEL_REPEAT="${REPEAT_OVERRIDE:-3600}" \
              PUSHGATEWAY_URL="file:///dev/null" APP_CELL=cell01 ENV_FILE=/dev/null \
              "$RUNNER" "$SELF" 2>&1)
        rc=$?
        set -e
    }

    sent() { [ -s "$tmp/sent.txt" ]; }

    check() {  # $1 — имя, $2 — ожидаемый код, $3 — ожидание про отправку (шлём|молчим), $4 — слово в сообщении
        local name="$1" want_rc="$2" want_send="$3" word="${4:-}"
        if [ "$rc" != "$want_rc" ]; then
            red "  ✗ ${name}: код ${rc}, ожидался ${want_rc}"
            printf '%s\n' "$out" | sed 's/^/      /' >&2
            bad=1; return
        fi
        if [ "$want_send" = шлём ]; then
            if ! sent; then
                red "  ✗ ${name}: сообщение вторым путём НЕ ушло"
                bad=1; return
            fi
            if [ -n "$word" ] && ! grep -q "$word" "$tmp/sent.txt"; then
                red "  ✗ ${name}: в сообщении нет «${word}»"
                sed 's/^/      /' "$tmp/sent.txt" >&2
                bad=1; return
            fi
        else
            if sent; then
                red "  ✗ ${name}: сторож разбудил человека там, где должен молчать"
                sed 's/^/      /' "$tmp/sent.txt" >&2
                bad=1; return
            fi
        fi
        printf '  ✓ %s\n' "$name"
    }

    # 1. Первый проход: отказы в счётчике — история за жизнь диспетчера.
    #    Разбудить на ней значит будить при каждом перезапуске ячейки.
    rm -f "$tmp/state"
    metrics_file 2731 5000
    run "cat $tmp/metrics" "$tmp/send-ok"
    check "первый проход на старых отказах — молчит" 0 молчим

    # 2. Отказов не прибавилось — канал жив.
    run "cat $tmp/metrics" "$tmp/send-ok"
    check "новых отказов нет — молчит" 0 молчим

    # 3. Отказы растут — это и есть авария 19 сентября.
    metrics_file 2735 5000
    run "cat $tmp/metrics" "$tmp/send-ok"
    check "отказы растут — красное и сказано вторым путём" 1 шлём "ВТОРЫМ путём"

    # 4. Выдержка: будить каждые пять минут — значит быть отключённым.
    metrics_file 2740 5000
    run "cat $tmp/metrics" "$tmp/send-ok"
    check "повтор внутри часа — красное, но человека не будит" 1 молчим

    # 5. Час прошёл — будим снова.
    metrics_file 2750 5000
    REPEAT_OVERRIDE=0 run "cat $tmp/metrics" "$tmp/send-ok"
    check "выдержка вышла — будит снова" 1 шлём "НЕ УХОДЯТ"

    # 6. Диспетчер не отвечает вовсе.
    rm -f "$tmp/state"
    run "false" "$tmp/send-ok"
    check "первый проход, диспетчер молчит — красное" 1 шлём "не отвечает"

    # 7. Ответ не от диспетчера: не туда нацелили. Без этой ветки сторож был
    #    бы вечно зелёным на пустом ответе.
    rm -f "$tmp/state"
    printf 'какая-то страница\n' > "$tmp/chuzhoe"
    run "cat $tmp/chuzhoe" "$tmp/send-ok"
    check "ответ не похож на метрики — красное" 1 шлём "не видит метрик"

    # 8. Второй путь тоже отказал. «Сообщили» и «не смогли сообщить» — разные
    #    вещи, и выдать второе за первое значит повторить саму поломку.
    #
    #    Проверяются И код, И слова. Кода мало: подделка, убравшая строку
    #    «СООБЩИТЬ НЕ УДАЛОСЬ» и оставившая код 2, проходила эту пробу
    #    зелёной — то есть сторож молча переставал говорить человеку, что
    #    ячейка сейчас нема. Поймано возвратом дефекта, а не чтением.
    rm -f "$tmp/state"
    metrics_file 1 1
    run "cat $tmp/metrics" "$tmp/send-ok"     # первый проход — запомнили
    metrics_file 5 1
    run "cat $tmp/metrics" "$tmp/send-fail"
    check "второй путь отказал — код 2, а не «сообщено»" 2 шлём
    case "$out" in
        *"СООБЩИТЬ НЕ УДАЛОСЬ"*)
            printf '  ✓ и сказано словами: «ячейка сейчас нема»\n' ;;
        *)  red "  ✗ второй путь отказал, а сторож об этом не сказал — код есть, слов нет"
            printf '%s\n' "$out" | sed 's/^/      /' >&2
            bad=1 ;;
    esac

    # 9. Счётчики уехали вниз — диспетчера перезапустили, а не «стало лучше».
    rm -f "$tmp/state"
    metrics_file 100 100
    run "cat $tmp/metrics" "$tmp/send-ok"
    metrics_file 3 5
    run "cat $tmp/metrics" "$tmp/send-ok"
    check "перезапуск диспетчера — молчит" 0 молчим

    # 10. И сама проба не зависит от `.env`, лежащего рядом. Случай не
    #     умозрительный: ровно на нём покраснел CI — шаг «Боевой compose»
    #     пишет `.env` из заглушек, а `set -a; . ./.env` старше окружения,
    #     переданного пробой. Здесь это возвращается подделкой: рядом
    #     с копией сторожа кладётся `.env` с выдержкой в час, а проба
    #     требует, чтобы сторож всё равно разбудил — как ей и задано.
    #     Порядок шагов тут не случаен, и первая редакция случая была
    #     БЕСПОЛЕЗНА именно из-за него: пока сторож не будил ни разу,
    #     `LAST_SHOUT` равен нулю, ветка выдержки не выполняется вовсе —
    #     и подделка, вернувшая зависимость от `.env`, проходила зелёной.
    #     Значит будить надо ДВАЖДЫ: первый раз ставит отметку, и только
    #     второй проверяет, чья выдержка победила — файла или пробы.
    local tree="$tmp/derevo"
    mkdir -p "$tree/ops"
    cp "$SELF" "$tree/ops/alert-channel-check.sh"
    printf 'ALERT_CHANNEL_REPEAT=3600\nALERT_CHANNEL_INTERVAL=300\n' > "$tree/.env"
    rm -f "$tmp/state"
    metrics_file 1 1
    SELF_SAVED="$SELF"; SELF="$tree/ops/alert-channel-check.sh"
    run "cat $tmp/metrics" "$tmp/send-ok"      # первый проход — запомнили
    metrics_file 5 1
    run "cat $tmp/metrics" "$tmp/send-ok"      # разбудили, отметка поставлена
    metrics_file 9 1
    REPEAT_OVERRIDE=0 run "cat $tmp/metrics" "$tmp/send-ok"
    SELF="$SELF_SAVED"
    check "«.env» рядом не отменяет выдержки, заданной пробой" 1 шлём "НЕ УХОДЯТ"

    [ $bad = 0 ] || { red "Самопроверка не прошла"; exit 1; }
    green "Самопроверка пройдена"
}

SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
LAST_SHOUT_NEW=0

case "$MODE" in
    самопроверка) selftest; exit 0 ;;
    проба)        probe; exit $? ;;
    проход)       pass; exit $? ;;
    цикл)
        # Контейнер alert-watch: проход, пауза, проход. Код возврата здесь
        # никого не интересует — сторож обязан пережить и отбитый канал,
        # и недоступного диспетчера, иначе `restart: unless-stopped`
        # превратит его в круг перезапусков.
        while :; do
            printf '\n[%s] проход сторожа канала\n' "$(date -u '+%Y-%m-%d %H:%M:%SZ')"
            pass || true
            sleep "$INTERVAL"
        done ;;
esac
