# Сессия к API для прогона — то же, что делает браузер.
#
# Зачем файлом: без CSRF-токена ни один изменяющий запрос не пройдёт, а токен
# ленивый — его надо сначала запросить. Каждый, кто проверяет систему руками,
# собирал эти тридцать строк заново; теперь они одни.
#
#   . tools/api.sh
#   login proba vladelec 'проба-владелец'   # печатает код ответа
#   g  /api/parts/catalog?size=5            # GET, тело в stdout
#   gc /api/parts/catalog?size=5            # GET, код ответа в stdout, тело в /tmp/last.json
#   p  /api/deals '{"customerId":1,...}'    # POST телом
#   pc /api/deals '{...}'                   # POST, код ответа в stdout
#   METHOD=PUT pc /api/parts/2 '{...}'      # другой метод
#
# Тело последнего ответа всегда лежит в /tmp/last.json — его удобно разбирать
# питоном, а не глазами.

API_BASE="${API_BASE:-http://localhost:8080}"
API_JAR="${API_JAR:-/tmp/partsflow-cookies.txt}"

# Токен читается из cookie: приложение кладёт его туда, а в заголовок
# перекладывает клиент. Здесь клиент — мы.
_token() { grep XSRF-TOKEN "$API_JAR" 2>/dev/null | awk '{print $7}'; }

login() {
  rm -f "$API_JAR"
  # Токен берётся заново, а не «если его нет»: cookie переживает сессию,
  # которой принадлежал, и вчерашний токен даёт 401 — неотличимый от неверного
  # пароля. Это записано в корневом CLAUDE.md и стоило полутора часов на пилоте.
  curl -s -c "$API_JAR" -b "$API_JAR" "$API_BASE/api/auth/csrf" >/dev/null
  curl -s -c "$API_JAR" -b "$API_JAR" -X POST "$API_BASE/api/auth/login" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(_token)" \
    -d "{\"company\":\"$1\",\"login\":\"$2\",\"password\":\"$3\"}" \
    -o /tmp/last.json -w '%{http_code}'
}

g()  { curl -s -b "$API_JAR" -c "$API_JAR" "$API_BASE$1"; }
gc() { curl -s -b "$API_JAR" -c "$API_JAR" -o /tmp/last.json -w '%{http_code}' "$API_BASE$1"; }

p()  { curl -s -b "$API_JAR" -c "$API_JAR" -X "${METHOD:-POST}" "$API_BASE$1" \
         -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(_token)" ${2:+-d "$2"}; }
pc() { curl -s -b "$API_JAR" -c "$API_JAR" -X "${METHOD:-POST}" "$API_BASE$1" \
         -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(_token)" ${2:+-d "$2"} \
         -o /tmp/last.json -w '%{http_code}'; }

# Файлы формой: перенос и загрузка из таблицы принимают только её.
pf() { local path="$1"; shift
       curl -s -b "$API_JAR" -X POST "$API_BASE$path" -H "X-XSRF-TOKEN: $(_token)" \
         "$@" -o /tmp/last.json -w '%{http_code}'; }

# Разобрать последний ответ питоном: j '.total' не годится, jq есть не везде.
j() { python3 -c "import json,sys;d=json.load(open('/tmp/last.json'));print(eval('d'+sys.argv[1]))" "$1"; }
