#!/usr/bin/env bash
# Живые сверки на поднятой системе: то, что обязано сходиться всегда.
#
#   ./tools/checks.sh
#
# Это не замена тестам. Тесты отвечают на вопрос «работает ли код», а эти
# проверки — на вопрос «сходятся ли данные и говорят ли поверхности одно и то
# же». Три из девяти ошибок ночного прогона 3 сентября 2026 нашлись именно так,
# и ни одна из них не видна тестам: там склад всегда маленький и свежий.
#
# Падает — значит либо в системе расхождение, либо проверка устарела; и то
# и другое надо разобрать, а не пропустить.
set -u
cd "$(dirname "$0")/.."

COMPANY="${COMPANY:-proba}"
OWNER_LOGIN="${OWNER_LOGIN:-vladelec}"
OWNER_PASSWORD="${OWNER_PASSWORD:-проба-владелец}"

. tools/api.sh
FAILED=0
ok()   { printf '  ✓ %s\n' "$*"; }
bad()  { printf '  ✗ %s\n' "$*"; FAILED=1; }

[ "$(login "${COMPANY}" "${OWNER_LOGIN}" "${OWNER_PASSWORD}")" = "200" ] \
  || { echo "не вошёл в ${COMPANY}: система поднята? ./tools/dev-up.sh"; exit 1; }
SCHEMA=$(g /api/auth/me | python3 -c 'import json,sys;print(json.load(sys.stdin)["companySchema"])')
PG=$(docker compose ps -q postgres)
# search_path через PGOPTIONS, а не отдельным SET: тот печатает «SET» в вывод,
# и число, которое мы читаем, приезжает вместе с ним.
psql() { docker exec -i -e PGOPTIONS="--search_path=${SCHEMA}" "$PG" \
           psql -U app -d parts -t -A -c "$1"; }

echo "Сверки схемы ${SCHEMA}"

# 1. Три вьюхи-сверки. Пустые они не «обычно», а всегда: непустая означает,
#    что журнал и остаток разъехались, обещанное не отложено или деньги клиента
#    не сходятся с их движением.
echo "1. Расхождения в данных"
for pair in "v_stock_discrepancy:остаток" "v_reservation_discrepancy:резерв" \
            "v_account_discrepancy:деньги"; do
  view="${pair%%:*}"; name="${pair##*:}"
  n=$(psql "SELECT count(*) FROM ${view};")
  [ "$n" = "0" ] && ok "${name}: расхождений нет" || bad "${name}: ${n} расхождений в ${view}"
done

# 2. Владелец и продавец ищут по одному складу. Расходились они дважды, и оба
#    раза неправ был тот, о ком не спрашивали: сначала витрина искала только
#    подстрокой, потом продавец не знал ни номеров, ни размеров колёс.
#
#    У владельца поверхностей две — склад и колёса, — и это не дублирование:
#    у колеса пятнадцать своих полей, которых у фары нет. Поэтому его сторона
#    складывается, а сравнивается с одним поиском продавца, который знает обе
#    линии товара. Сравнив только витрину, получишь ложную тревогу на любом
#    колёсном запросе — проверено.
echo "2. Владелец и продавец находят одно и то же"
for word in фара камри 225 бриджстоун бампер; do
  q=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$word")
  gc "/api/parts/catalog?q=${q}&size=1" >/dev/null
  parts=$(python3 -c 'import json;print(json.load(open("/tmp/last.json"))["total"])')
  gc "/api/wheels?q=${q}&size=1" >/dev/null
  wheels=$(python3 -c 'import json;print(json.load(open("/tmp/last.json"))["total"])')
  gc "/api/parts/stock?q=${q}" >/dev/null
  seller=$(python3 -c 'import json;print(json.load(open("/tmp/last.json"))["total"])')
  owner=$(( parts + wheels ))
  if [ "$owner" = "$seller" ]; then ok "«${word}»: ${owner} и там и там"
  else bad "«${word}»: у владельца ${parts}+${wheels}=${owner}, у продавца ${seller}"; fi
done

# 3. Счётчик обязан считать тем же условием, что и генератор. Врал уже дважды:
#    считал колёса в прайсе запчастей и запчасти в прайсе колёс.
echo "3. Счётчик выгрузки против числа объявлений в файле"
gc /api/marketplace-accounts >/dev/null
python3 - <<'PY' > /tmp/accounts.txt
import json
for a in json.load(open('/tmp/last.json')):
    print(a['id'], a['productLine'], a['title'].replace(' ', '_'))
PY
while read -r id line title; do
  pc /api/marketplace-accounts/filter/count "{\"productLine\":\"${line}\"}" >/dev/null
  promised=$(python3 -c 'import json;print(json.load(open("/tmp/last.json"))["parts"])')
  url=$(g "/api/marketplace-accounts/${id}/feed-url" \
        | python3 -c 'import json,sys
d=json.load(sys.stdin); print(d.get("url") or "")')
  if [ -z "$url" ]; then ok "${title}: ссылки нет, сравнивать нечего"; continue; fi
  actual=$(curl -s "$url" | grep -o '<offer>' | wc -l | tr -d ' ')
  [ "$promised" = "$actual" ] && ok "${title}: обещано ${promised}, в файле ${actual}" \
    || bad "${title}: обещано ${promised}, а в файле ${actual}"
done < /tmp/accounts.txt

# 4. Страницы не теряют строк. Все колонки витрины, кроме номера товара,
#    неуникальны: без вторичного ключа часть позиций попадает на две страницы,
#    ровно столько же — никуда, и по экрану этого не увидеть.
echo "4. Страницы витрины целы"
python3 - <<'PY'
import json, subprocess
def page(sort, n):
    subprocess.run(['bash','-c',
        f'. tools/api.sh; gc "/api/parts/catalog?sort={sort}&page={n}&size=25" >/dev/null'],
        capture_output=True)
    return json.load(open('/tmp/last.json'))
bad = []
for sort in ('code', 'price', 'title', 'created'):
    total = page(sort, 0)['total']
    seen, n = [], 0
    while len(seen) < total and n < 100:
        rows = page(sort, n)['rows']
        if not rows: break
        seen += [r['code'] for r in rows]; n += 1
    dup, lost = len(seen) - len(set(seen)), total - len(set(seen))
    mark = '✓' if dup == 0 and lost == 0 else '✗'
    print(f"  {mark} сортировка «{sort}»: {total} строк, повторов {dup}, потеряно {lost}")
    if dup or lost: bad.append(sort)
raise SystemExit(1 if bad else 0)
PY
[ $? -eq 0 ] || FAILED=1

# 5. Скачанный файл обязан совпасть с тем, что владелец видел: ради этой
#    сверки он его и качает.
echo "5. Выгрузка витрины совпадает с экраном"
gc '/api/parts/catalog?size=1' >/dev/null
screen=$(python3 -c 'import json;print(json.load(open("/tmp/last.json"))["total"])')
curl -s -b "$API_JAR" "${API_BASE}/api/parts/catalog/export" -o /tmp/checks-export.csv
file=$(( $(wc -l < /tmp/checks-export.csv) - 1 ))
[ "$screen" = "$file" ] && ok "на экране ${screen}, в файле ${file}" \
  || bad "на экране ${screen}, а в файле ${file}"

echo
[ $FAILED -eq 0 ] && echo "Всё сходится." || echo "Есть расхождения — разберите до правок."
exit $FAILED
