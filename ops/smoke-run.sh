#!/usr/bin/env bash
# Дымовой прогон СЦЕНАРИЕМ: доезжает ли эта сборка на этом стенде до денег.
#
#   ops/smoke-run.sh              режим по стенду (DEPLOY_STAND)
#   ops/smoke-run.sh --read-only  только читающие шаги
#   ops/smoke-run.sh --write      читающие плюс продажа (ИФТ, ПСИ, свой подъём)
#   ops/smoke-run.sh --selftest   проверка самих проверок, без ячейки
#
# ЗАЧЕМ ОН ЕСТЬ. Четыре самопроверки выкладки (ops/deploy-checks.sh) отвечают
# на вопрос «поднялось ли»: корень отдаёт 200, журналы защищены, схемы
# не отстали, прайс не пуст. Три из четырёх — про инфраструктуру, и выкладка
# может быть зелёной при сломанной продаже. Это не теория: сторож
# размонтирования, не возвращаемый в true, убил экран пересчёта целиком
# и сутки прожил в main; оценка состояния отвечала «Запрос не разобран»
# и уносила всю правку карточки; перегрузка без @Transactional давала
# пятисотку на витрине при целых данных. Ни одно из трёх не тронуло бы
# ни одной из четырёх проверок.
#
# ЧЕМ ОН НЕ ЯВЛЯЕТСЯ. Не заменой тестам. Тесты проверяют, что код делает
# обещанное; этот прогон — что ИМЕННО ЭТА сборка на ИМЕННО ЭТОМ стенде
# с ЕГО данными доезжает до конца. Разница та же, что между MockMvc
# и настоящим контейнером, и в этом проекте она уже дорого оплачена.
#
# ЧТО ПРОВЕРЯЕТСЯ — СМЫСЛ, А НЕ КОД ОТВЕТА. «Сделка создалась» — это не 201,
# а отложенная деталь: свободный остаток обязан стать нулём. «Выдали» — это
# не 200, а списанный остаток. Шаг, отвечающий 200 и молча не делающий
# своего дела, — ровно тот случай, ради которого прогон и написан.
#
# ГДЕ ОН ПИШЕТ, А ГДЕ НЕТ. На ИФТ и ПСИ — целиком: заводит позицию, продаёт
# её, принимает деньги, выдаёт и убирает за собой. На ПРОМ там живые данные
# клиента, и дымовая сделка — это сделка в его учёте: остаются только
# читающие шаги (витрина отдаёт страницу, поиск находит, карточка
# открывается). Оформление продажи проверено на ПСИ тем же артефактом —
# в этом и смысл продвижения одного образа по стендам.
#
# ЗАПРЕТ ЗАПИСИ НА ПРОМ СТОИТ ДВАЖДЫ, и это не перестраховка: переменная
# окружения — вещь, которую однажды забудут передать или передадут не ту.
#   1. режим считается по стенду (DEPLOY_STAND=prom — только чтение);
#   2. имя хоста с меткой prom/prod переводит в чтение ДАЖЕ при
#      DEPLOY_STAND=ift — то же правило, что у tools/ift-etalon.py;
#   3. явный --write на таком стенде — отказ словами, а не тихий пропуск.
# Проверяется это самопроверкой не рассуждением, а перебором запросов:
# в режиме чтения ни одного не-GET, кроме входа и выхода, не уходит вовсе.
#
# ПРАЙСА ЗДЕСЬ НЕТ НАМЕРЕННО. «Число <offer> не упало до нуля» — четвёртая
# самопроверка, у неё свои ссылки со своими токенами. Второе место, считающее
# объявления, разошлось бы с первым.
#
# КУДА НАЦЕЛЕН. Умолчание — своя ячейка через терминатор, как ходит человек.
#
#   SMOKE_URL=…            адрес целиком (свой подъём: http://localhost:8080)
#   DEPLOY_CURL=host       ходить с хоста, а не изнутри контейнера терминатора
#   DEPLOY_STAND=ift|psi|prom  какой это стенд — от него режим
#   SMOKE_COMPANY/_LOGIN/_PASSWORD   чем входить
#   SMOKE_CRED_FILE=.deploy-smoke    файл с тремя строками: компания, логин, пароль

set -uo pipefail
cd "$(dirname "$0")/.."

COMPOSE="${COMPOSE:-docker compose -f docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"
TIMEOUT="${SMOKE_TIMEOUT:-20}"
CRED_FILE="${SMOKE_CRED_FILE:-.deploy-smoke}"
# Банка cookie: у режима «изнутри терминатора» она лежит в его контейнере,
# у режима «с хоста» — на хосте. Путь один, файловые системы разные.
JAR="${SMOKE_JAR:-/tmp/partsflow-smoke-cookies}"

red()   { printf '\033[1;31m%s\033[0m\n' "$1" >&2; }
green() { printf '\033[1;32m%s\033[0m\n' "$1"; }
ok()    { printf '  \033[1;32m✓\033[0m %s\n' "$1"; }
note()  { printf '  \033[1;33m—\033[0m %s\n' "$1"; }
bad()   { printf '  \033[1;31m✗ %s\033[0m\n' "$1" >&2; FAILED="$FAILED$2, "; }

FAILED=""
MODE=""
CSRF=""
ROUTE=""
BASE=""
RESOLVE_HOST=""
# Чем входить — заполняется при запуске из файла или переменных. Пустые
# значения объявлены здесь, а не только там: при set -u самопроверка,
# до нацеливания не доходящая, падала бы на «unbound variable» —
# и падала бы не на том, что проверяет.
SMOKE_COMPANY="${SMOKE_COMPANY:-}"
SMOKE_LOGIN="${SMOKE_LOGIN:-}"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-}"
# Ключ приёмки: свой у каждого прогона, чтобы повтор не вернул позицию
# прошлой выкладки. Значение по умолчанию — ради самопроверки, которая
# до нацеливания не доходит.
RUN_ID="${RUN_ID:-selftest}"
# Что прогон узнал по дороге: номер позиции, которой он торгует, и документы.
PART_ID=""; PART_NUMBER=""; PART_CODE=""; WAREHOUSE_ID=""
DEAL_ID=""; ITEM_ID=""; DEAL_TOTAL=""

# ───────────────────────────── разбор ответов ────────────────────────────────
#
# Тело и код приезжают одной строкой-ответом: код последней строкой (curl -w).
body_of() { printf '%s' "$1" | sed '$d'; }
code_of() { printf '%s' "$1" | tail -n 1 | tr -dc '0-9' | tail -c 3; }

# Значение из тела ответа: val "$body" "d['items'][0]['status']".
# Пусто — если тело не разобралось или ключа нет. Пустое значение и есть
# признак «ответ не тот», и обрабатывают его вердикты, а не эта функция.
val() {
    printf '%s' "$1" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
try:
    v = eval(sys.argv[1])
except Exception:
    sys.exit(1)
print('' if v is None else v)
" "$2" 2>/dev/null
}

# Деньги приезжают числом JSON: «5000.0» и «5000.00» — одно и то же,
# и сравнивать их текстом значит ловить собственное написание.
same_money() { python3 -c "
import sys
try:
    sys.exit(0 if float(sys.argv[1]) == float(sys.argv[2]) else 1)
except ValueError:
    sys.exit(1)" "$1" "$2"; }

# ─────────────────────────── как ходить за ответом ───────────────────────────
#
# Изнутри контейнера терминатора: порты сборок наружу не опубликованы,
# у caddy есть curl, и он в той же сети — тем же доводом живёт
# ops/deploy-checks.sh. С хоста ходят там, где прогон нацелили на чужую
# машину или на свой подъём.
run_there() {
    if [ "$ROUTE" = host ]; then "$@"; else $COMPOSE exec -T caddy "$@"; fi
}

# МЕТОД ПУТЬ [ТЕЛО] → тело ответа, последней строкой код.
#
# Тело уезжает потоком, а не аргументом: в нём пароль, а аргументы команды
# видит в `ps` любой, у кого есть учётка на машине (тем же доводом живёт
# ops/migrate-tenants.sh).
request() {
    local method="$1" path="$2" body="${3-}"
    set -- -sS -m "$TIMEOUT" -X "$method" -b "$JAR" -c "$JAR" \
           -H 'Content-Type: application/json' -w '\n%{http_code}'
    [ -n "$CSRF" ] && set -- "$@" -H "X-XSRF-TOKEN: $CSRF"
    [ -n "$RESOLVE_HOST" ] && set -- "$@" --resolve "$RESOLVE_HOST:443:127.0.0.1"
    if [ -n "$body" ]; then
        printf '%s' "$body" | run_there curl "$@" --data-binary @- "$BASE$path" 2>&1
    else
        run_there curl "$@" "$BASE$path" 2>&1
    fi
}

# Токен CSRF приложение кладёт в cookie, а в заголовок его перекладывает
# клиент. Здесь клиент — мы, и банка лежит там же, откуда ходит curl.
read_csrf() { run_there awk '/XSRF-TOKEN/ {print $7}' "$JAR" 2>/dev/null | tail -n 1; }

# ───────────────────────────── куда и в каком режиме ─────────────────────────
#
# Метка хоста сильнее переменной намеренно: DEPLOY_STAND передаёт кнопка,
# а переменную однажды забудут или передадут не ту — и тогда «дымовой прогон»
# оформит сделку в учёте живого клиента. Обратной ошибки (лишнее чтение
# на ИФТ) не существует.
host_label() {  # адрес → prom, ift, psi, local либо пусто
    printf '%s' "$1" | python3 -c "
import re, sys, urllib.parse
host = (urllib.parse.urlsplit(sys.stdin.read().strip()).hostname or '').lower()
if host in ('localhost', '127.0.0.1', '::1'):
    print('local')
else:
    labels = set(re.split(r'[.\-]', host))
    for label, name in (('prom', 'prom'), ('prod', 'prom'), ('psi', 'psi'), ('ift', 'ift')):
        if label in labels:
            print(name)
            break
" 2>/dev/null
}

# стенд, метка хоста, затребованный флаг → «write», «read-only» либо отказ
# строкой, начинающейся с «отказ:».
mode_of() {
    local stand="$1" label="$2" want="$3" allowed

    case "$label" in
        prom) allowed="read-only" ;;
        *)
            case "$stand" in
                prom) allowed="read-only" ;;
                ift|psi) allowed="write" ;;
                *)
                    # Локальный подъём — это машина разработчика, а не стенд:
                    # там писать можно, не называя стенда.
                    if [ "$label" = local ]; then allowed="write"
                    else allowed="отказ: стенд не назван (DEPLOY_STAND=ift|psi|prom) и по имени хоста не узнаётся — не зная, ПРОМ это или нет, прогон не пишет ничего"; fi ;;
            esac ;;
    esac

    case "$want" in
        # Просить меньше можно всегда: лишнее чтение не портит ничего.
        read-only) [ "${allowed#отказ:}" = "$allowed" ] && echo "read-only" || echo "$allowed" ;;
        write)
            if [ "$allowed" = write ]; then echo write
            elif [ "$allowed" = read-only ]; then
                echo "отказ: это ПРОМ — там живые данные клиента, и дымовая сделка была бы сделкой в его учёте. Продажу репетируют на ПСИ тем же образом"
            else echo "$allowed"; fi ;;
        *) echo "$allowed" ;;
    esac
}

# ─────────────────────────────── вердикты ────────────────────────────────────
#
# Разбор отделён от запроса намеренно: краснеть проверка обязана на дефекте,
# а установить это можно, только подсунув ей ответ. Ниже так у всех шагов.

verdict_login() {  # код, тело
    case "$1" in
        200) ;;
        401|403) bad "вход не прошёл ($1): проверьте SMOKE_COMPANY/SMOKE_LOGIN/SMOKE_PASSWORD" "вход"; return 1 ;;
        *) bad "вход отвечает $1, а не 200: $(printf '%s' "$2" | tr '\n' ' ' | cut -c1-120)" "вход"; return 1 ;;
    esac
    local role
    role=$(val "$2" "d['role']")
    if [ -z "$role" ]; then
        bad "вход ответил 200, но без роли — это не ответ входа: $(printf '%s' "$2" | tr '\n' ' ' | cut -c1-120)" "вход"
        return 1
    fi
    ok "вошли: $(val "$2" "d['displayName']") ($role)"
}

verdict_catalog() {  # код, тело
    if [ "$1" != 200 ]; then
        bad "витрина отвечает $1, а не 200 — склад не открывается вовсе" "витрина"
        return 1
    fi
    local total rows
    total=$(val "$2" "d['total']")
    rows=$(val "$2" "len(d['rows'])")
    if [ -z "$total" ] || [ -z "$rows" ]; then
        bad "витрина ответила 200, но это не страница склада: $(printf '%s' "$2" | tr '\n' ' ' | cut -c1-120)" "витрина"
        return 1
    fi
    # Пустая страница при непустом складе — то самое «200 с нулём байт»:
    # ответ есть, товара на экране нет.
    if [ "$total" -gt 0 ] && [ "$rows" = 0 ]; then
        bad "витрина говорит «позиций ${total}», а строк не отдала ни одной" "витрина"
        return 1
    fi
    ok "витрина отдаёт страницу: позиций $total, строк на странице $rows"
}

verdict_number_search() {  # номер, ожидаемый partId, код, тело
    if [ "$3" != 200 ]; then
        bad "поиск по номеру отвечает $3, а не 200" "поиск по номеру"
        return 1
    fi
    local found
    found=$(val "$4" "[r['partId'] for r in d['rows'] if str(r.get('number')) == '$1']")
    if [ -z "$found" ] || [ "$found" = "[]" ]; then
        bad "по номеру $1 не находится ничего — а позиция с этим номером на складе есть" "поиск по номеру"
        return 1
    fi
    case "$found" in
        *"$2"*) ok "поиск по номеру $1 находит свою позицию" ;;
        *) bad "по номеру $1 нашлась чужая позиция ($found вместо $2)" "поиск по номеру"; return 1 ;;
    esac
}

verdict_card() {  # код, тело
    if [ "$1" != 200 ]; then
        bad "карточка позиции отвечает $1, а не 200" "карточка"
        return 1
    fi
    if [ -z "$(val "$2" "1")" ]; then
        bad "карточка ответила 200, но тело не разобралось: $(printf '%s' "$2" | tr '\n' ' ' | cut -c1-120)" "карточка"
        return 1
    fi
    ok "карточка открывается"
}

verdict_intake() {  # код, тело
    if [ "$1" != 201 ]; then
        bad "приёмка отвечает $1, а не 201: $(printf '%s' "$2" | tr '\n' ' ' | cut -c1-140)" "приёмка"
        return 1
    fi
    if [ -z "$(val "$2" "d['parts'][0]['id']")" ]; then
        bad "приёмка ответила 201, но позиции в ответе нет — принимать было нечего" "приёмка"
        return 1
    fi
    ok "приёмка завела позицию $(val "$2" "d['parts'][0]['publicCode']")"
}

verdict_reserved() {  # код, тело сделки, свободный остаток после
    if [ "$1" != 201 ]; then
        bad "оформление сделки отвечает $1, а не 201: $(printf '%s' "$2" | tr '\n' ' ' | cut -c1-140)" "оформление сделки"
        return 1
    fi
    local status
    status=$(val "$2" "d['items'][0]['status']")
    if [ "$status" != RESERVED ]; then
        bad "сделка создана, но позиция в ней не отложена (статус «${status}», ждали RESERVED)" "оформление сделки"
        return 1
    fi
    # Главное здесь: не ответ, а полка. Сделка, не снявшая свободный остаток,
    # обещает одну деталь двум покупателям — и отвечает при этом 201.
    if [ "$3" != 0 ]; then
        bad "сделка оформлена, а деталь не отложена: свободный остаток $3 вместо 0" "оформление сделки"
        return 1
    fi
    ok "сделка $(val "$2" "d['number']") оформлена, деталь отложена (свободный остаток 0)"
}

verdict_paid() {  # код оплаты, тело сделки после оплаты
    if [ "$1" != 201 ]; then
        bad "оплата отвечает $1, а не 201: $(printf '%s' "$2" | tr '\n' ' ' | cut -c1-140)" "оплата"
        return 1
    fi
    local paid total debt
    paid=$(val "$2" "d['paidAmount']"); total=$(val "$2" "d['totalAmount']"); debt=$(val "$2" "d['debt']")
    if [ -z "$paid" ] || [ -z "$total" ]; then
        bad "после оплаты сделка не читается: $(printf '%s' "$2" | tr '\n' ' ' | cut -c1-120)" "оплата"
        return 1
    fi
    if ! same_money "$paid" "$total"; then
        bad "оплата принята, а долг не закрылся: оплачено $paid из $total" "оплата"
        return 1
    fi
    ok "оплата принята полностью: $paid из $total, долг $debt"
}

verdict_issued() {  # код выдачи, тело сделки, остаток после
    if [ "$1" != 200 ]; then
        bad "выдача отвечает $1, а не 200: $(printf '%s' "$2" | tr '\n' ' ' | cut -c1-140)" "выдача"
        return 1
    fi
    local status
    status=$(val "$2" "d['status']")
    if [ "$status" != ISSUED ]; then
        bad "выдача прошла, а сделка осталась в статусе «${status}» вместо ISSUED" "выдача"
        return 1
    fi
    # Выданное — это списанное с полки. Выдача, ответившая 200 и не тронувшая
    # остаток, оставляет проданную деталь в продаже.
    if [ "$3" != 0 ]; then
        bad "сделка выдана, а остаток не списан: на складе осталось $3" "выдача"
        return 1
    fi
    ok "выдача закрыла сделку и списала остаток"
}

verdict_cleanup() {  # код возврата, тело, остаток после
    if [ "$1" != 201 ]; then
        bad "убрать за собой не вышло: возврат отвечает $1 — на стенде осталась дымовая продажа" "уборка"
        return 1
    fi
    local status
    status=$(val "$2" "d['status']")
    if [ "$status" != DONE ]; then
        bad "возврат создан в статусе «${status}» вместо DONE — дымовая продажа осталась на стенде" "уборка"
        return 1
    fi
    if [ "$3" != 0 ]; then
        bad "уборка вернула дымовую деталь на полку (остаток $3) — она будет продаваться людям" "уборка"
        return 1
    fi
    ok "убрано за собой: остаток и касса вернулись к прежним"
}

# ─────────────────────────────── шаги ────────────────────────────────────────

step_login() {
    request GET /api/auth/csrf >/dev/null
    CSRF=$(read_csrf)
    local answer
    answer=$(request POST /api/auth/login \
        "{\"company\":\"$SMOKE_COMPANY\",\"login\":\"$SMOKE_LOGIN\",\"password\":\"$SMOKE_PASSWORD\"}")
    # Вход меняет токен: после него CSRF старой сессии уже не годится.
    CSRF=$(read_csrf)
    verdict_login "$(code_of "$answer")" "$(body_of "$answer")"
}

step_catalog() {
    local answer body
    answer=$(request GET '/api/parts/catalog?size=1')
    body=$(body_of "$answer")
    verdict_catalog "$(code_of "$answer")" "$body" || return 1
    # Позиция, на которой дальше проверяются поиск и карточка. В режиме
    # записи её подменит только что принятая — своя, а не чужая.
    PART_ID=$(val "$body" "d['rows'][0]['id']")
    PART_NUMBER=$(val "$body" "d['rows'][0]['number']")
}

step_warehouse() {
    local answer
    answer=$(request GET /api/organization/warehouses)
    WAREHOUSE_ID=$(val "$(body_of "$answer")" "d[0]['id']")
    if [ -z "$WAREHOUSE_ID" ]; then
        bad "складов у компании нет — принимать некуда" "приёмка"
        return 1
    fi
}

step_intake() {
    local answer body
    # Ключ запроса свой у каждого прогона: повтор тем же ключом вернул бы
    # позицию прошлой выкладки, и прогон проверял бы вчерашний день.
    answer=$(request POST /api/intake/receipts "{\"warehouseId\":$WAREHOUSE_ID,
        \"requestId\":\"smoke-$RUN_ID\",
        \"items\":[{\"rawName\":\"дымовой прогон выкладки\",\"quantity\":1,
                    \"price\":1000,\"costPrice\":100,\"condition\":\"USED\"}]}")
    body=$(body_of "$answer")
    verdict_intake "$(code_of "$answer")" "$body" || return 1
    PART_ID=$(val "$body" "d['parts'][0]['id']")
    PART_CODE=$(val "$body" "d['parts'][0]['publicCode']")
    # Номер позиции приёмка не отдаёт — его показывают витрина и выдача
    # продавцу. Спрашиваем тем же путём, которым его узнаёт человек.
    local found
    found=$(body_of "$(request GET "/api/parts/stock?q=$PART_CODE")")
    PART_NUMBER=$(val "$found" "[r['number'] for r in d['rows'] if r['partId'] == $PART_ID][0]")
    if [ -z "$PART_NUMBER" ]; then
        bad "принятая позиция $PART_CODE не находится по своему коду — продавать её нечем" "поиск по номеру"
        return 1
    fi
}

step_number_search() {
    if [ -z "$PART_NUMBER" ]; then
        note "на складе нет ни одной позиции — искать нечего (пустой стенд)"
        return 0
    fi
    local answer
    answer=$(request GET "/api/parts/stock?q=$PART_NUMBER")
    verdict_number_search "$PART_NUMBER" "$PART_ID" "$(code_of "$answer")" "$(body_of "$answer")"
}

step_card() {
    if [ -z "$PART_ID" ]; then
        note "на складе нет ни одной позиции — карточку открывать нечем (пустой стенд)"
        return 0
    fi
    local answer
    answer=$(request GET "/api/parts/$PART_ID/history")
    verdict_card "$(code_of "$answer")" "$(body_of "$answer")"
}

# Свободный остаток позиции по её номеру: 0, если строки нет вовсе
# (выданная деталь с полки уходит).
available_now() {
    local found
    found=$(body_of "$(request GET "/api/parts/stock?q=$PART_NUMBER")")
    val "$found" "([r['$1'] for r in d['rows'] if r['partId'] == $PART_ID] or [0])[0]" | sed 's/\.0*$//'
}

step_deal() {
    local answer body
    answer=$(request POST /api/deals "{\"items\":[{\"partId\":$PART_ID,\"quantity\":1,
        \"warehouseId\":$WAREHOUSE_ID}]}")
    body=$(body_of "$answer")
    DEAL_ID=$(val "$body" "d['id']")
    ITEM_ID=$(val "$body" "d['items'][0]['id']")
    DEAL_TOTAL=$(val "$body" "d['totalAmount']")
    verdict_reserved "$(code_of "$answer")" "$body" "$(available_now qtyAvailable)"
}

step_pay() {
    [ -n "$DEAL_ID" ] || return 1
    local answer code
    answer=$(request POST "/api/deals/$DEAL_ID/payments" "{\"amount\":$DEAL_TOTAL}")
    code=$(code_of "$answer")
    verdict_paid "$code" "$(body_of "$(request GET "/api/deals/$DEAL_ID")")"
}

step_issue() {
    [ -n "$DEAL_ID" ] || return 1
    local answer
    answer=$(request POST "/api/deals/$DEAL_ID/issue")
    verdict_issued "$(code_of "$answer")" "$(body_of "$answer")" "$(available_now qty)"
}

step_cleanup() {
    [ -n "$DEAL_ID" ] && [ -n "$ITEM_ID" ] || return 1
    # Возврат БЕЗ постановки на полку и деньгами из кассы: остаток стенда
    # и лицевой счёт клиента возвращаются к тому, что было до прогона.
    # Полностью стереть след нельзя и не нужно: документы и журналы
    # неизменяемы по построению — на них стоит вся доказательность склада.
    local answer
    answer=$(request POST "/api/deals/$DEAL_ID/returns" "{\"warehouseId\":$WAREHOUSE_ID,
        \"reason\":\"дымовой прогон выкладки — уборка за собой\",
        \"refundToAccount\":false,
        \"items\":[{\"dealItemId\":$ITEM_ID,\"quantity\":1,\"restocked\":false}]}")
    verdict_cleanup "$(code_of "$answer")" "$(body_of "$answer")" "$(available_now qty)"
}

step_logout() {
    request POST /api/auth/logout >/dev/null 2>&1
}

scenario() {
    step_login || return 1
    step_catalog
    if [ "$MODE" = write ]; then
        step_warehouse && step_intake
    fi
    step_number_search
    step_card
    if [ "$MODE" = write ]; then
        step_deal && step_pay && step_issue
        # Уборка идёт даже если продажа сломалась на середине: оставленная
        # на стенде дымовая сделка — это работа для человека.
        [ -n "$DEAL_ID" ] && step_cleanup
    else
        note "продажу не оформляем: на ПРОМ дымовая сделка была бы сделкой в учёте клиента"
    fi
    step_logout
    [ -z "$FAILED" ]
}

# ────────────────────────────── самопроверка ─────────────────────────────────
#
# Без ячейки и без docker. Проверяется три вещи, и третья — главная:
#   1. каждый вердикт краснеет на своём дефекте и называет свой шаг;
#   2. режим считается по стенду и по имени хоста, а ПРОМ не пишет;
#   3. В РЕЖИМЕ ЧТЕНИЯ НИ ОДНОГО ПИШУЩЕГО ЗАПРОСА НЕ УХОДИТ ВОВСЕ —
#      перебором отправленного, а не чтением кода.
selftest() {
    local broken=0
    expect() {  # имя, ожидаемый исход (ok|fail), шаг в списке упавших
        local name="$1" want="$2" mark="$3"
        shift 3
        FAILED=""
        # Через файл, а не $(...): подстановка команды — подоболочка,
        # и список упавших до нас бы не доехал. Самопроверка при этом молча
        # зеленела бы — ровно тот изъян, который она и ловит.
        local tmp="${TMPDIR:-/tmp}/smoke-selftest.$$"
        "$@" > "$tmp" 2>&1; local code=$?
        local out; out=$(cat "$tmp"); rm -f "$tmp"
        if [ "$want" = ok ] && [ $code != 0 ]; then
            red "  ✗ $name: ожидалось «проходит», а проверка покраснела: $out"
            broken=$((broken + 1)); return
        fi
        if [ "$want" = fail ]; then
            if [ $code = 0 ]; then
                red "  ✗ $name: ожидалось «краснеет», а проверка промолчала: $out"
                broken=$((broken + 1)); return
            fi
            case "$FAILED" in
                *"$mark"*) ;;
                *) red "  ✗ $name: покраснела, но не назвала «${mark}» (назвала «${FAILED}»)"
                   broken=$((broken + 1)); return ;;
            esac
        fi
        printf '  ✓ %s\n' "$name"
    }

    same() {  # имя, получилось, ожидалось
        if [ "$2" = "$3" ]; then printf '  ✓ %s\n' "$1"
        else red "  ✗ $1: получилось «$2», ждали «$3»"; broken=$((broken + 1)); fi
    }

    echo "Самопроверка ops/smoke-run.sh"

    # 1. Режим: стенд, имя хоста и явный флаг.
    same "режим: ИФТ пишет" "$(mode_of ift ift '')" "write"
    same "режим: ПСИ пишет" "$(mode_of psi psi '')" "write"
    same "режим: ПРОМ только читает" "$(mode_of prom prom '')" "read-only"
    same "режим: свой подъём пишет" "$(mode_of '' local '')" "write"
    same "режим: ХОСТ ПРОМА СИЛЬНЕЕ ПЕРЕМЕННОЙ" "$(mode_of ift prom '')" "read-only"
    same "режим: безымянный стенд — отказ" \
        "$(mode_of '' '' '' | cut -c1-6)" "отказ:"
    same "режим: --write на ПРОМ — отказ" \
        "$(mode_of prom prom write | cut -c1-6)" "отказ:"
    same "режим: --write на хосте прома — отказ" \
        "$(mode_of ift prom write | cut -c1-6)" "отказ:"
    same "режим: --read-only на ИФТ разрешён" "$(mode_of ift ift read-only)" "read-only"
    same "метка хоста: prom.example.ru" "$(host_label https://prom.example.ru/)" "prom"
    same "метка хоста: parts-prod.example.ru" "$(host_label https://parts-prod.example.ru/)" "prom"
    same "метка хоста: ift.example.ru" "$(host_label https://ift.example.ru/)" "ift"
    same "метка хоста: свой подъём" "$(host_label http://localhost:8080)" "local"

    # 2. Вердикты: годный ответ и сломанный. Сломанные — не выдуманные:
    #    это «200, а дела не сделано», то есть ровно то, чего не видят
    #    четыре инфраструктурные проверки.
    expect "вход: 200 с ролью проходит" ok "" \
        verdict_login 200 '{"login":"prodavec","displayName":"Продавец","role":"SELLER"}'
    expect "вход: 401 краснеет" fail "вход" verdict_login 401 '{"message":"Неверный пароль"}'
    expect "вход: 200 без роли краснеет" fail "вход" verdict_login 200 '{"ok":true}'

    expect "витрина: страница проходит" ok "" \
        verdict_catalog 200 '{"total":2,"rows":[{"id":7,"number":347}]}'
    expect "витрина: 500 краснеет" fail "витрина" verdict_catalog 500 'Internal Server Error'
    expect "витрина: ПОЗИЦИИ ЕСТЬ, СТРОК НЕТ — краснеет" fail "витрина" \
        verdict_catalog 200 '{"total":2,"rows":[]}'
    expect "витрина: пустой склад проходит" ok "" verdict_catalog 200 '{"total":0,"rows":[]}'

    expect "поиск по номеру: своя позиция проходит" ok "" \
        verdict_number_search 347 7 200 '{"rows":[{"partId":7,"number":347}]}'
    expect "поиск по номеру: не нашлось — краснеет" fail "поиск по номеру" \
        verdict_number_search 347 7 200 '{"rows":[]}'
    expect "поиск по номеру: нашлась чужая — краснеет" fail "поиск по номеру" \
        verdict_number_search 347 7 200 '{"rows":[{"partId":9,"number":347}]}'

    expect "карточка: открывается" ok "" verdict_card 200 '{"changes":[],"movements":[]}'
    expect "карточка: 500 краснеет" fail "карточка" verdict_card 500 'oops'
    expect "карточка: 200 не-JSON краснеет" fail "карточка" verdict_card 200 '<html>Ошибка</html>'

    expect "приёмка: позиция заведена" ok "" \
        verdict_intake 201 '{"documentId":1,"parts":[{"id":9,"publicCode":"AB12"}]}'
    expect "приёмка: 409 краснеет" fail "приёмка" verdict_intake 409 '{"message":"конфликт"}'
    expect "приёмка: 201 без позиции краснеет" fail "приёмка" verdict_intake 201 '{"parts":[]}'

    expect "сделка: деталь отложена" ok "" \
        verdict_reserved 201 '{"number":5,"items":[{"id":11,"status":"RESERVED"}]}' 0
    expect "сделка: 500 краснеет" fail "оформление сделки" verdict_reserved 500 'oops' 1
    expect "сделка: СТАТУС НЕ RESERVED — краснеет" fail "оформление сделки" \
        verdict_reserved 201 '{"number":5,"items":[{"id":11,"status":"DRAFT"}]}' 0
    expect "сделка: 201, А ОСТАТОК НЕ ОТЛОЖЕН — краснеет" fail "оформление сделки" \
        verdict_reserved 201 '{"number":5,"items":[{"id":11,"status":"RESERVED"}]}' 1

    expect "оплата: долг закрыт" ok "" \
        verdict_paid 201 '{"totalAmount":1000.0,"paidAmount":1000.00,"debt":0}'
    expect "оплата: 500 краснеет" fail "оплата" verdict_paid 500 'oops'
    expect "оплата: ПРИНЯТА, А ДОЛГ ОСТАЛСЯ — краснеет" fail "оплата" \
        verdict_paid 201 '{"totalAmount":1000,"paidAmount":0,"debt":1000}'

    expect "выдача: закрыла сделку и списала остаток" ok "" \
        verdict_issued 200 '{"status":"ISSUED"}' 0
    expect "выдача: 500 краснеет" fail "выдача" verdict_issued 500 'oops' 1
    expect "выдача: СТАТУС НЕ ИЗМЕНИЛСЯ — краснеет" fail "выдача" \
        verdict_issued 200 '{"status":"RESERVED"}' 0
    expect "выдача: 200, А ОСТАТОК НЕ СПИСАН — краснеет" fail "выдача" \
        verdict_issued 200 '{"status":"ISSUED"}' 1

    expect "уборка: за собой убрано" ok "" verdict_cleanup 201 '{"status":"DONE"}' 0
    expect "уборка: отказ краснеет" fail "уборка" verdict_cleanup 409 '{"message":"нельзя"}' 0
    expect "уборка: ДЕТАЛЬ ОСТАЛАСЬ НА ПОЛКЕ — краснеет" fail "уборка" \
        verdict_cleanup 201 '{"status":"DONE"}' 1

    # 3. Сценарий целиком на подставных ответах — и перебор отправленного.
    #    Тело check_* можно было бы заменить на `return 0`, и вердикты выше
    #    остались бы зелёными: подсовывая ответ прямо в verdict_*, мы минуем
    #    то, что этот ответ добывает.
    local real_request; real_request=$(declare -f request)
    STUB_LOG="${TMPDIR:-/tmp}/smoke-selftest-log.$$"
    request() {
        printf '%s %s\n' "$1" "${2%%\?*}" >> "$STUB_LOG"
        stub_answer "$1" "$2"
    }
    read_csrf() { echo "токен"; }

    local writes
    for want_mode in read-only write; do
        : > "$STUB_LOG"
        FAILED=""; MODE="$want_mode"; PART_ID=""; PART_NUMBER=""; DEAL_ID=""; ITEM_ID=""
        STUB_BREAK="" scenario > "$STUB_LOG.out" 2>&1
        [ -z "$FAILED" ] || {
            red "  ✗ сценарий ($want_mode) покраснел на исправных ответах: ${FAILED}"
            red "    $(tr '\n' ' ' < "$STUB_LOG.out" | cut -c1-200)"
            broken=$((broken + 1)); }
        # Пишущим считается всё, кроме GET и входа-выхода: вход оставляет
        # запись в журнале входов, и это ровно то, что о прогоне должно
        # быть видно. Сделок, платежей и позиций он на ПРОМ не создаёт.
        writes=$(grep -v '^GET ' "$STUB_LOG" | grep -v '^POST /api/auth/' | sort -u)
        if [ "$want_mode" = read-only ]; then
            if [ -n "$writes" ]; then
                red "  ✗ НА ПРОМ УШЛА ЗАПИСЬ: $(printf '%s' "$writes" | tr '\n' ' ')"
                broken=$((broken + 1))
            else
                printf '  ✓ режим чтения: пишущих запросов не ушло ни одного\n'
            fi
        else
            case "$writes" in
                *"POST /api/deals"*) printf '  ✓ режим записи: сделка всё-таки оформляется (проверка не пустая)\n' ;;
                *) red "  ✗ режим записи не оформил сделки — перебор запросов ничего не доказывает"
                   broken=$((broken + 1)) ;;
            esac
        fi
    done

    # И обвязка шагов: подделанный ответ обязан доехать до вердикта через
    # тот же путь, которым идёт живой прогон.
    for break_at in deal issue; do
        : > "$STUB_LOG"
        FAILED=""; MODE=write; PART_ID=""; PART_NUMBER=""; DEAL_ID=""; ITEM_ID=""
        STUB_BREAK="$break_at" scenario >/dev/null 2>&1
        local mark="оформление сделки"; [ "$break_at" = issue ] && mark="выдача"
        case "$FAILED" in
            *"$mark"*) printf '  ✓ обвязка: молча сломанный шаг «%s» краснеет и назван\n' "$mark" ;;
            *) red "  ✗ обвязка: сломанный «${break_at}» не назван (назвала «${FAILED}»)"
               broken=$((broken + 1)) ;;
        esac
    done
    rm -f "$STUB_LOG"
    eval "$real_request"

    if [ "$broken" != 0 ]; then
        red "Самопроверка не прошла: $broken"
        return 1
    fi
    green "Самопроверка пройдена"
    return 0
}

# Подставные ответы ячейки. Состояние берётся из перебора уже отправленного:
# после оформления сделки свободный остаток ноль, после выдачи полка пуста.
# STUB_BREAK подделывает МОЛЧА сломанный шаг — тот, что отвечает успехом
# и не делает дела.
stub_answer() {
    local method="$1" path="$2"
    case "$method $path" in
        "GET /api/auth/csrf") printf '\n204' ;;
        "POST /api/auth/login") printf '{"role":"SELLER","displayName":"Продавец"}\n200' ;;
        "POST /api/auth/logout") printf '\n204' ;;
        "GET /api/organization/warehouses") printf '[{"id":1,"name":"Основной"}]\n200' ;;
        # Строка витрины — та же позиция, что отдаёт поиск ниже: в режиме
        # чтения прогон берёт её с витрины и ищет по её же номеру.
        "GET /api/parts/catalog"*) printf '{"total":1,"rows":[{"id":9,"number":348}]}\n200' ;;
        "POST /api/intake/receipts") printf '{"parts":[{"id":9,"publicCode":"AB12"}]}\n201' ;;
        "GET /api/parts/stock"*)
            local qty=1 free=1
            grep -q '^POST /api/deals$' "$STUB_LOG" && free=0
            [ "${STUB_BREAK:-}" = deal ] && free=1
            if grep -q '/issue$' "$STUB_LOG"; then
                qty=0; free=0
                [ "${STUB_BREAK:-}" = issue ] && qty=1
            fi
            printf '{"rows":[{"partId":9,"number":348,"publicCode":"AB12","qty":%s,"qtyAvailable":%s}],"total":1}\n200' "$qty" "$free" ;;
        "GET /api/parts/"*"/history") printf '{"changes":[],"movements":[]}\n200' ;;
        "POST /api/deals") printf '{"id":5,"number":5,"totalAmount":1000,"items":[{"id":11,"status":"RESERVED"}]}\n201' ;;
        "POST /api/deals/5/payments") printf '{"id":3,"amount":1000}\n201' ;;
        "GET /api/deals/5") printf '{"id":5,"totalAmount":1000,"paidAmount":1000,"debt":0}\n200' ;;
        "POST /api/deals/5/issue")
            if [ "${STUB_BREAK:-}" = issue ]; then printf '{"id":5,"status":"ISSUED"}\n200'
            else printf '{"id":5,"status":"ISSUED"}\n200'; fi ;;
        "POST /api/deals/5/returns") printf '{"id":2,"number":2,"status":"DONE"}\n201' ;;
        *) printf '{"message":"подставного ответа на %s %s нет"}\n404' "$method" "$path" ;;
    esac
}

# ─────────────────────────────── запуск ──────────────────────────────────────
WANT=""
case "${1:-}" in
    --selftest) selftest; exit $? ;;
    --read-only) WANT=read-only ;;
    --write) WANT=write ;;
    -h|--help) sed -n '2,55p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    "") ;;
    *) red "Непонятный аргумент: $1"; exit 2 ;;
esac

# Окружение ячейки — оттуда APP_DOMAIN. Нет файла — значит прогон нацелили
# переменными снаружи, и это законно.
if [ -f "$ENV_FILE" ]; then
    set -a; . "./$ENV_FILE"; set +a
fi

# Куда ходить.
RESOLVE_HOST=""
if [ -n "${SMOKE_URL:-}" ]; then
    BASE="${SMOKE_URL%/}"
    ROUTE="${DEPLOY_CURL:-host}"
elif [ -n "${APP_DOMAIN:-}" ]; then
    BASE="https://$APP_DOMAIN"
    # Адрес разрешаем сами: снаружи домен может указывать куда угодно,
    # а проверяем мы ЭТУ машину. То же делает первая самопроверка.
    RESOLVE_HOST="$APP_DOMAIN"
    ROUTE="${DEPLOY_CURL:-caddy}"
else
    red "Дымовой прогон не нацелен: нет ни SMOKE_URL, ни APP_DOMAIN в $ENV_FILE"
    exit 1
fi

# Чем входить. Файл — по той же причине, что и ссылки на прайсы: пароль,
# отданный аргументом команды, виден в `ps` любому на машине.
if [ -z "${SMOKE_LOGIN:-}" ] && [ -f "$CRED_FILE" ]; then
    SMOKE_COMPANY=$(sed -n '1p' "$CRED_FILE" | tr -d '\r')
    SMOKE_LOGIN=$(sed -n '2p' "$CRED_FILE" | tr -d '\r')
    SMOKE_PASSWORD=$(sed -n '3p' "$CRED_FILE" | tr -d '\r')
fi
SMOKE_COMPANY="${SMOKE_COMPANY:-}"; SMOKE_LOGIN="${SMOKE_LOGIN:-}"; SMOKE_PASSWORD="${SMOKE_PASSWORD:-}"
if [ -z "$SMOKE_COMPANY" ] || [ -z "$SMOKE_LOGIN" ] || [ -z "$SMOKE_PASSWORD" ]; then
    # Не «пропускаем»: прогон, молча не выполняющийся, хуже отсутствующего —
    # выкладку зеленят, ни разу не пройдя сценарий.
    red "Дымовой прогон некем пройти: не задан вход ($CRED_FILE — три строки: компания, логин, пароль продавца; либо SMOKE_COMPANY/SMOKE_LOGIN/SMOKE_PASSWORD)"
    exit 1
fi

MODE=$(mode_of "${DEPLOY_STAND:-}" "$(host_label "$BASE")" "$WANT")
case "$MODE" in
    "отказ:"*) red "Дымовой прогон ${MODE}"; exit 1 ;;
esac

RUN_ID="$(date +%Y%m%d-%H%M%S)-$$"
printf 'Дымовой прогон сценарием: %s, режим \033[1m%s\033[0m\n' "$BASE" \
    "$([ "$MODE" = write ] && echo 'продажа целиком' || echo 'только чтение')"

scenario
if [ -n "$FAILED" ]; then
    red "Дымовой прогон не прошёл: ${FAILED%, }"
    exit 1
fi
green "Дымовой прогон пройден: сценарий доезжает до конца"
