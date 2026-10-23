#!/usr/bin/env bash
# Сквозной прогон на своей компании: приёмка → продажа → деньги → возврат →
# пересчёт → списание → выгрузка. После каждого шага сверяется не «ответ 200»,
# а то, что данные означают.
#
#   ./tools/smoke.sh
#   ./tools/smoke.sh --selftest   # краснеет ли прогон на подделках (см. tools/tools-selftest.py)
#
# Компания заводится своя (код smoke) и переиспользуется: проверки относительные,
# от того, что было до шага. Чистый лист — docker compose down -v.
#
# Зачем это отдельно от тестов. Тесты отвечают, работает ли код; здесь
# проверяется, что система как целое ведёт себя так, как обещано человеку:
# переплата уходит на счёт, возврат ставит деталь обратно, проданное уезжает
# в прайс недоступным. Шесть из девяти ошибок ночного прогона 3 сентября 2026
# нашлись именно так.
set -u
cd "$(dirname "$0")/.."
[ "${1:-}" = "--selftest" ] && exec python3 -I tools/tools-selftest.py smoke

COMPANY=smoke
OWNER_LOGIN=vladelec
OWNER_PASSWORD='проба-владелец'
export APP_PROVISIONING_TOKEN="${APP_PROVISIONING_TOKEN:-local-dev-token}"

. tools/api.sh
FAILED=0
# Отметка отказа лежит в файле, а не только в FAILED: `bad` из подоболочки
# (`$(...)` внутри аргументов) поднимает счётчик в копии, и родитель его не
# видит. Файл виден всем.
FAIL_MARK=$(mktemp)
trap 'rm -f "$FAIL_MARK"' EXIT
ok()  { printf '  ✓ %s\n' "$*"; }
bad() { printf '  ✗ %s\n' "$*"; FAILED=1; echo x >> "$FAIL_MARK"; }
same() { [ "$2" = "$3" ] && ok "$1: $2" || bad "$1: ожидалось $3, получилось $2"; }
# Деньги приезжают числом JSON, а не строкой: «1000.0» и «1000.00» — одно
# и то же, и сравнивать их текстом значит ловить собственное написание.
samenum() { python3 -c "import sys;sys.exit(0 if float(sys.argv[1])==float(sys.argv[2]) else 1)" "$2" "$3" \
              && ok "$1: $2" || bad "$1: ожидалось $3, получилось $2"; }
num() { python3 -c "import json,sys;d=json.load(open('/tmp/last.json'));print(eval('d'+sys.argv[1]))" "$1"; }

# Упавшая команда — не пройденный раздел. Раздел, оборвавшийся на curl, python3
# или psql, раньше ничего не писал в FAILED, и подвал говорил «прошёл» над
# непосчитанным. Приём тот же, что в tools/checks.sh — второго способа
# объявлять обрыв не заводим (tasks/0177).
trap 'bad "проверка оборвалась на строке ${LINENO} — раздел не посчитан"' ERR
set -E

echo "0. Компания ${COMPANY}"
if [ "$(login ${COMPANY} ${OWNER_LOGIN} "${OWNER_PASSWORD}")" != "200" ]; then
  curl -s -X POST "${API_BASE}/api/provisioning/tenants" -H 'Content-Type: application/json' \
    -d "{\"token\":\"${APP_PROVISIONING_TOKEN}\",\"companyCode\":\"${COMPANY}\",
         \"companyName\":\"Прогон\",\"ownerLogin\":\"${OWNER_LOGIN}\",
         \"ownerPassword\":\"${OWNER_PASSWORD}\",\"ownerName\":\"Прогон\"}" -o /tmp/last.json >/dev/null
  [ "$(login ${COMPANY} ${OWNER_LOGIN} "${OWNER_PASSWORD}")" = "200" ] \
    || { echo "  не завелась: $(head -c 200 /tmp/last.json)"; exit 1; }
  ok "заведена"
else ok "уже есть"; fi

RUN=$(date +%H%M%S)
gc /api/organization/warehouses >/dev/null; WH=$(num "[0]['id']")

echo "1. Приёмка"
pc /api/intake/receipts "{\"warehouseId\":${WH},\"requestId\":\"smoke-${RUN}\",\"items\":[
  {\"rawName\":\"фара\",\"quantity\":1,\"price\":5000,\"costPrice\":1000,\"condition\":\"USED\"}]}" >/dev/null
PART=$(num "['parts'][0]['id']")
[ -n "$PART" ] && ok "деталь ${PART} принята" || bad "приёмка не прошла"

# Повтор с тем же ключом обязан вернуть ту же партию, а не завести вторую:
# офлайн-очередь повторяет при каждом обрыве связи.
pc /api/intake/receipts "{\"warehouseId\":${WH},\"requestId\":\"smoke-${RUN}\",\"items\":[
  {\"rawName\":\"фара\",\"quantity\":1,\"price\":5000,\"costPrice\":1000,\"condition\":\"USED\"}]}" >/dev/null
same "повтор приёмки вернул ту же деталь" "$(num "['parts'][0]['id']")" "${PART}"

echo "2. Продажа с переплатой"
pc /api/customers "{\"name\":\"Покупатель ${RUN}\"}" >/dev/null; CUST=$(num "['id']")
pc /api/deals "{\"customerId\":${CUST},\"items\":[{\"partId\":${PART},\"quantity\":1,\"warehouseId\":${WH}}]}" >/dev/null
DEAL=$(num "['id']")
same "сделка отложила деталь" "$(num "['items'][0]['status']")" "RESERVED"

gc "/api/parts/stock?q=$(python3 -c 'import urllib.parse;print(urllib.parse.quote("фара"))')" >/dev/null
same "продавцу видно, что деталь обещана" \
  "$(python3 -c "
import json;d=json.load(open('/tmp/last.json'))
r=[x for x in d['rows'] if x['partId']==${PART}][0]
print(f\"{r['qty']:.0f}/{r['qtyAvailable']:.0f}\")")" "1/0"

pc "/api/deals/${DEAL}/payments" '{"amount":6000}' >/dev/null
gc "/api/customers/${CUST}/account" >/dev/null
samenum "переплата ушла на лицевой счёт" "$(num "['balance']")" "1000.00"

pc "/api/deals/${DEAL}/issue" >/dev/null
same "выдача закрыла сделку" "$(num "['status']")" "ISSUED"

echo "3. Прайс"
pc /api/marketplace-accounts "{\"marketplace\":\"DROM\",\"title\":\"Прогон ${RUN}\",\"productLine\":\"PART\"}" >/dev/null
ACC=$(num "['id']")
pc "/api/marketplace-accounts/${ACC}/feed-url" >/dev/null; URL=$(num "['url']")
curl -s "${URL}" -o /tmp/smoke-feed.xml
# Позиция прайса запчастей — <part> (формат Bazon, задача 0333).
OFFERS=$(grep -o '<part>' /tmp/smoke-feed.xml | wc -l | tr -d ' ')
pc /api/marketplace-accounts/filter/count '{"productLine":"PART"}' >/dev/null
same "счётчик выгрузки сходится с файлом" "$(num "['parts']")" "${OFFERS}"
# Проданного в прайсе нет, а у оставшихся одно слово наличия — как у Bazon
# (задача 0339, решение владельца 19.10.2026): «недоступна» в формате Bazon
# не сказать, и проданное снимает с сайта полный забор.
# Вердикт идёт через `same`, а не печатается своим `print`. Раньше этот раздел
# печатал ✗ сам и выходил нулём: `FAILED` не трогался, и подвал говорил
# «Сквозной прогон прошёл» при видимом ✗ на экране — то есть единственный
# инвариант, ради которого раздел написан, на исход прогона не влиял вовсе.
gc "/api/deals/${DEAL}" >/dev/null; SOLD_NUMBER=$(num "['items'][0]['number']")
SOLD_GONE=$(SOLD_NUMBER="${SOLD_NUMBER}" python3 - <<'PY'
import os, re
x = open('/tmp/smoke-feed.xml', encoding='utf-8').read()
sold = '<id>%s</id>' % os.environ['SOLD_NUMBER'] in x
others = set(re.findall(r'<state>([^<]*)</state>', x)) - {'В наличии'}
print('нет' if not sold and not others else 'есть')
PY
)
same "проданного в прайсе нет, у остальных «В наличии»" "${SOLD_GONE}" "нет"

echo "4. Возврат"
gc "/api/deals/${DEAL}" >/dev/null; ITEM=$(num "['items'][0]['id']")
pc "/api/deals/${DEAL}/returns" "{\"warehouseId\":${WH},\"refundToAccount\":true,
  \"items\":[{\"dealItemId\":${ITEM},\"quantity\":1,\"restocked\":true}]}" >/dev/null
same "возврат выполнен" "$(num "['status']")" "DONE"
gc "/api/customers/${CUST}/account" >/dev/null
samenum "деньги вернулись на счёт" "$(num "['balance']")" "6000.00"
# По закрытой сделке долга нет, и платить по ней не за что: иначе продавец
# звонит клиенту за деньгами по товару, который тот сам и принёс обратно.
same "оплата возвращённой сделки отбита" "$(pc "/api/deals/${DEAL}/payments" '{"amount":100}')" "409"

echo "5. Пересчёт и списание"
pc /api/inventory/sessions "{\"warehouseId\":${WH}}" >/dev/null; SES=$(num "['id']")
pc "/api/inventory/sessions/${SES}/counts" "{\"partId\":${PART},\"qty\":0,\"countedAgoMs\":0}" >/dev/null
pc "/api/inventory/sessions/${SES}/finish" >/dev/null
gc "/api/inventory/sessions/${SES}/discrepancies" >/dev/null
samenum "недостача видна" "$(num "[0]['delta']")" "-1"
pc "/api/inventory/sessions/${SES}/apply" >/dev/null
same "недостача списана" "$(num "['adjusted']")" "1"
same "повтор проведения отбит" "$(pc "/api/inventory/sessions/${SES}/apply")" "409"

echo "6. Сверки"
SCHEMA=$(g /api/auth/me | python3 -c 'import json,sys;print(json.load(sys.stdin)["companySchema"])')
PG=$(docker compose ps -q postgres)
for pair in "v_stock_discrepancy:остаток" "v_reservation_discrepancy:резерв" \
            "v_account_discrepancy:деньги"; do
  view="${pair%%:*}"; name="${pair##*:}"
  n=$(docker exec -i -e PGOPTIONS="--search_path=${SCHEMA}" "$PG" \
        psql -U app -d parts -t -A -c "SELECT count(*) FROM ${view};")
  [ "$n" = "0" ] && ok "${name}: сходится" || bad "${name}: ${n} расхождений"
done

[ -s "$FAIL_MARK" ] && FAILED=1
echo
[ $FAILED -eq 0 ] && echo "Сквозной прогон прошёл." || echo "Прогон нашёл расхождения или не досчитал разделы."
exit $FAILED
