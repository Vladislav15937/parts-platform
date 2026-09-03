#!/usr/bin/env bash
# Поднимает всё до заведённой компании — одной командой.
#
#   ./tools/dev-up.sh
#
# Идемпотентен: уже поднятое не трогает и ничего не пересоздаёт. База живёт
# в томе Docker и переживает перезапуск; чтобы начать с чистого листа —
# docker compose down -v, и тогда компания заведётся заново.
#
# Порядок шагов не случаен: приложение не поднимется без Postgres, компанию
# не завести без приложения, а PWA бесполезна без компании.
set -u

# ${VAR} в фигурных скобках всюду, где рядом стоит не-ASCII: bash 3.2 из macOS
# прихватывает байты кавычки-ёлочки в имя переменной и падает с «unbound».

cd "$(dirname "$0")/.."

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
docker compose up -d >/dev/null 2>&1
for _ in $(seq 1 60); do
  docker compose ps --format '{{.Service}} {{.Status}}' 2>/dev/null | grep -q '^postgres Up.*healthy' && break
  sleep 2
done
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
  [ "$code" = "201" ] || [ "$code" = "200" ] || exit 1
fi

echo "5. PWA"
if curl -s -o /dev/null --max-time 2 http://localhost:5173/; then
  say "уже отдаётся на :5173"
else
  say "запускаю Vite, лог — /tmp/partsflow-vite.log"
  ( cd frontend && nohup npm run dev > /tmp/partsflow-vite.log 2>&1 & )
  for _ in $(seq 1 30); do curl -s -o /dev/null --max-time 1 http://localhost:5173/ && break; sleep 1; done
fi

cat <<TXT

Готово. Открывать — http://localhost:5173 (не :8080: сессия в cookie и CSRF
из cookie работают только на одном источнике, а /api проксирует Vite).

  компания ${COMPANY} · ${OWNER_LOGIN} · ${OWNER_PASSWORD}

Дальше: . tools/api.sh — сессия к API; ./tools/checks.sh — живые сверки.
TXT
