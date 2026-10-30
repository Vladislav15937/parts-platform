#!/usr/bin/env bash
# Поднимает всё до заведённой компании — одной командой.
#
#   ./tools/dev-up.sh
#   ./tools/dev-up.sh --selftest   # краснеют ли проверки подъёма на сломанном подъёме
#
# Повторяем: Docker, Postgres, приложение и Vite, уже поднятые, не трогает
# и не пересоздаёт; компанию, уже заведённую с тем же паролем владельца,
# только проверяет входом. База живёт в томе Docker и переживает перезапуск;
# чтобы начать с чистого листа — docker compose down -v, и тогда компания
# заведётся заново.
#
# Компания, заведённая с другим паролем, повтором не чинится: вход не подходит,
# а заводить её второй раз провижининг отказывается (409). Скрипт говорит,
# какой командой выйти, и завершается кодом 3 (tasks/0385).
#
# Коды выхода: 0 — поднято; 3 — компания есть, пароль к ней другой (подсказка
# напечатана, подъём не завершён); 1 — всё остальное, причина напечатана.
#
# Порядок шагов не случаен: приложение не поднимется без Postgres, компанию
# не завести без приложения, а PWA бесполезна без компании.
set -u

# ${VAR} в фигурных скобках всюду, где рядом стоит не-ASCII: bash 3.2 из macOS
# прихватывает байты кавычки-ёлочки в имя переменной и падает с «unbound».

cd "$(dirname "$0")/.."
[ "${1:-}" = "--selftest" ] && exec python3 -I tools/tools-selftest.py dev-up

COMPANY="${COMPANY:-proba}"
OWNER_LOGIN="${OWNER_LOGIN:-vladelec}"
OWNER_PASSWORD="${OWNER_PASSWORD:-проба-владелец}"
# Пустой секрет выключает провижининг — это верное умолчание для боя,
# но здесь без него не завести компанию.
export APP_PROVISIONING_TOKEN="${APP_PROVISIONING_TOKEN:-local-dev-token}"

say() { printf '  %s\n' "$*"; }

echo "1. Docker"
if ! docker info >/dev/null 2>&1; then
  say "демон не отвечает — запускаю Docker Desktop"
  open -a Docker 2>/dev/null || { echo "не смог запустить Docker"; exit 1; }
  for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 5; done
fi
docker info >/dev/null 2>&1 || { echo "Docker так и не поднялся"; exit 1; }
say "есть"

echo "2. Postgres, MinIO, Kafka"
# Отказ подъёма глушился целиком (`>/dev/null 2>&1`), а после цикла ожидания
# не было ветки отказа вовсе: не поднявшийся Postgres ничем не отличался
# от поднявшегося, и скрипт шёл дальше к «Готово». Шаги 1 и 3 устроены
# правильно — отказ там назван и валит подъём; этот отставал от них.
if ! docker compose up -d >/dev/null 2>&1; then
  # Повторяем без глушения: `up -d` идемпотентен, зато человек увидит причину.
  docker compose up -d || { echo "  docker compose up не прошёл — причина выше"; exit 1; }
fi
for _ in $(seq 1 60); do
  docker compose ps --format '{{.Service}} {{.Status}}' 2>/dev/null | grep -q '^postgres Up.*healthy' && break
  sleep 2
done
docker compose ps --format '{{.Service}} {{.Status}}' 2>/dev/null | grep -q '^postgres Up.*healthy' \
  || { echo "  postgres не стал healthy за 120 с — docker compose ps, docker compose logs postgres"; exit 1; }
say "$(docker compose ps --format '{{.Service}}' | tr '\n' ' ')"

echo "3. Приложение"
if curl -s -o /dev/null --max-time 2 http://localhost:8080/api/auth/csrf; then
  say "уже поднято на :8080"
else
  say "запускаю, лог — /tmp/partsflow-app.log"
  nohup ./mvnw spring-boot:run > /tmp/partsflow-app.log 2>&1 &
  for _ in $(seq 1 120); do
    grep -q "Started PartsPlatformApplication" /tmp/partsflow-app.log 2>/dev/null && break
    sleep 2
  done
  grep -q "Started PartsPlatformApplication" /tmp/partsflow-app.log 2>/dev/null \
    || { echo "  не поднялось, смотрите /tmp/partsflow-app.log"; exit 1; }
  say "поднялось"
fi

# «Started» в логе и отвечающий порт не значат «общая схема накатана»:
# CatalogMigrations — ApplicationRunner, Spring зовёт его после этой строки.
# На чистом томе провижининг в этом окне отвечал 500 — реестра или его
# колонок ещё нет (tasks/0381), а повтор проходил, и это принимали за флак.
# Ждём проверку catalog в /actuator/readiness, а не "ready":true: в разработке
# приложение ходит в базу владельцем схем, журналы изменяемы, и как только
# компания есть, ready навсегда false — ожидание его повесило бы подъём.
for _ in $(seq 1 90); do
  curl -s --max-time 2 http://localhost:8080/actuator/readiness 2>/dev/null \
    | grep -q '"check":"catalog","ok":true' && break
  sleep 2
done
if curl -s --max-time 2 http://localhost:8080/actuator/readiness 2>/dev/null \
    | grep -q '"check":"catalog","ok":true'; then
  say "общая схема накатана"
else
  echo "  общая схема так и не накатилась за 180 с — компанию заводить рано."
  echo "  Что не так: curl -s http://localhost:8080/actuator/readiness (проверка catalog);"
  echo "  накат — CatalogMigrations в /tmp/partsflow-app.log"
  exit 1
fi

echo "4. Компания «${COMPANY}»"
# Провижининг сам отбивает повтор («код занят»), но отличить занятость
# от настоящей ошибки по коду ответа нельзя — поэтому сначала спрашиваем вход.
. tools/api.sh
if [ "$(login "$COMPANY" "$OWNER_LOGIN" "$OWNER_PASSWORD")" = "200" ]; then
  say "уже заведена, вход работает"
else
  code=$(curl -s -X POST http://localhost:8080/api/provisioning/tenants \
    -H 'Content-Type: application/json' \
    -d "{\"token\":\"$APP_PROVISIONING_TOKEN\",\"companyCode\":\"$COMPANY\",
         \"companyName\":\"Проба\",\"ownerLogin\":\"$OWNER_LOGIN\",
         \"ownerPassword\":\"$OWNER_PASSWORD\",\"ownerName\":\"Владислав\"}" \
    -o /tmp/last.json -w '%{http_code}')
  say "провижининг ответил $code: $(head -c 120 /tmp/last.json)"
  if [ "$code" = "409" ]; then
    # Вход не подошёл, а код занят — значит компания есть и пароль к ней
    # другой: так бывает, когда её заводили руками под своим паролем.
    # Отдельный код выхода, а не 1: «подсказали, как выйти» — не то же,
    # что «провижининг упал», и вызывающему надо их различать.
    # Следующий код компании: хвостовые цифры +1 (proba → proba2 → proba3).
    base="${COMPANY%"${COMPANY##*[!0-9]}"}"; num="${COMPANY#"$base"}"
    next="${base}$(( 10#${num:-1} + 1 ))"   # 10#: proba09 не восьмеричное
    cat <<TXT

  Компания «${COMPANY}» уже заведена, но вход ${OWNER_LOGIN} с этим паролем
  к ней не подходит — её заводили с другим паролем. Выходов три:

    свой пароль:      OWNER_LOGIN=${OWNER_LOGIN} OWNER_PASSWORD='…' ./tools/dev-up.sh
    другая компания:  COMPANY=${next} ./tools/dev-up.sh
    с чистого листа:  docker compose down -v && ./tools/dev-up.sh
                      (сотрёт базу целиком: все компании и их данные)
TXT
    exit 3
  fi
  [ "$code" = "201" ] || [ "$code" = "200" ] || exit 1
fi

echo "5. PWA"
if curl -s -o /dev/null --max-time 2 http://localhost:5173/; then
  say "уже отдаётся на :5173"
else
  # nvm подключается из ~/.zshrc или ~/.bashrc, а скрипт идёт в неинтерактивной
  # bash: npm в PATH нет, nohup пишет «npm: No such file or directory» в лог,
  # и подъём ждал тридцать секунд, чтобы отослать к этой строке (tasks/0385).
  if ! command -v npm >/dev/null 2>&1; then
    nvm_sh="${NVM_DIR:-$HOME/.nvm}/nvm.sh"
    # shellcheck disable=SC1090
    [ -s "$nvm_sh" ] && . "$nvm_sh" >/dev/null 2>&1
  fi
  if ! command -v npm >/dev/null 2>&1; then
    echo "  npm не найден (node, судя по всему, поставлен через nvm?). Подключите node"
    echo "  в этой оболочке или запустите Vite вручную: cd frontend && npm run dev"
    exit 1
  fi
  say "запускаю Vite, лог — /tmp/partsflow-vite.log"
  ( cd frontend && exec nohup npm run dev > /tmp/partsflow-vite.log 2>&1 ) &
  vite_pid=$!
  # Умерший процесс ждать незачем: порт уже не откроется, а причина в логе.
  for _ in $(seq 1 30); do
    curl -s -o /dev/null --max-time 1 http://localhost:5173/ && break
    kill -0 "$vite_pid" 2>/dev/null || break
    sleep 1
  done
  # После цикла проверки не было: не поднявшийся Vite не отличался
  # от поднявшегося, а ниже безусловно печаталось «Готово» с адресом,
  # по которому ничего не отвечает. Открывать надо именно :5173 —
  # сессия и CSRF из cookie работают только на одном источнике.
  if ! curl -s -o /dev/null --max-time 2 http://localhost:5173/; then
    if kill -0 "$vite_pid" 2>/dev/null; then
      echo "  Vite не поднялся за 30 с. Последние строки /tmp/partsflow-vite.log:"
    else
      echo "  Vite не поднялся: процесс завершился сразу. Последние строки /tmp/partsflow-vite.log:"
    fi
    tail -n 15 /tmp/partsflow-vite.log 2>/dev/null | sed 's/^/    | /'
    exit 1
  fi
  say "поднялось"
fi

cat <<TXT

Готово. Открывать — http://localhost:5173 (не :8080: сессия в cookie и CSRF
из cookie работают только на одном источнике, а /api проксирует Vite).

  компания ${COMPANY} · ${OWNER_LOGIN} · ${OWNER_PASSWORD}

Дальше: . tools/api.sh — сессия к API; ./tools/checks.sh — живые сверки.
TXT
