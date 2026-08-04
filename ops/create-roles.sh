#!/usr/bin/env bash
# Заводит две роли ячейки: владельца схем и рабочую.
#
#   ops/create-roles.sh
#
# Пока приложение ходит в базу одной ролью, оно владелец всех таблиц,
# а владелец возвращает себе любое право одной командой: журнал правится
# прямым SQL, и REVOKE этого не отбивает. Разделение ролей — единственное,
# что закрывает эту дыру.
#
# Владелец (DB_USER) уже есть — его создаёт сам Postgres при первом запуске.
# Скрипт заводит рабочую роль и отдаёт ей права на общую схему; права
# на схемы арендаторов выдаёт приложение при провижининге и при накате
# миграций (SchemaGrants).
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-.env}"
[ -f "$ENV_FILE" ] || { echo "Нет файла окружения $ENV_FILE"; exit 1; }
set -a; . "./$ENV_FILE"; set +a

: "${DB_USER:?укажите DB_USER}"
: "${APP_RUNTIME_ROLE:?укажите APP_RUNTIME_ROLE — имя рабочей роли}"
: "${APP_RUNTIME_PASSWORD:?укажите APP_RUNTIME_PASSWORD}"

COMPOSE="docker compose -f docker-compose.prod.yml"
PSQL="$COMPOSE exec -T postgres psql -U $DB_USER -d parts -v ON_ERROR_STOP=1"

echo "==> Заводим рабочую роль $APP_RUNTIME_ROLE"
$PSQL <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$APP_RUNTIME_ROLE') THEN
        CREATE ROLE $APP_RUNTIME_ROLE LOGIN PASSWORD '$APP_RUNTIME_PASSWORD';
    ELSE
        ALTER ROLE $APP_RUNTIME_ROLE LOGIN PASSWORD '$APP_RUNTIME_PASSWORD';
    END IF;
END \$\$;

GRANT CONNECT ON DATABASE parts TO $APP_RUNTIME_ROLE;

-- Общая схема ячейки: справочники читают все, пишет их миграция.
GRANT USAGE ON SCHEMA catalog TO $APP_RUNTIME_ROLE;
GRANT SELECT ON ALL TABLES IN SCHEMA catalog TO $APP_RUNTIME_ROLE;
ALTER DEFAULT PRIVILEGES IN SCHEMA catalog GRANT SELECT ON TABLES TO $APP_RUNTIME_ROLE;

-- Реестр арендаторов и служебные таблицы в public: рабочая роль читает
-- реестр при входе и пишет отметки ShedLock.
GRANT USAGE ON SCHEMA public TO $APP_RUNTIME_ROLE;
GRANT SELECT ON public.tenant_registry TO $APP_RUNTIME_ROLE;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.shedlock TO $APP_RUNTIME_ROLE;
SQL

echo "==> Права на схемы арендаторов"
echo "    Их выдаёт приложение: при провижининге нового клиента и при накате"
echo "    миграций (ops/migrate-tenants.sh). Для уже заведённых достаточно"
echo "    один раз прогнать накат."
echo
echo "==> Готово. В .env должно быть:"
echo "    APP_RUNTIME_ROLE=$APP_RUNTIME_ROLE"
echo "    DB_URL_RUNTIME=jdbc:postgresql://postgres:5432/parts"
echo "    Приложение подключается рабочей ролью, а DDL — владельцем ($DB_USER)."
