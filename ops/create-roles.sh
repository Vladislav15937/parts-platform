#!/usr/bin/env bash
# Заводит две роли ячейки: владельца схем и рабочую.
#
#   ops/create-roles.sh            # завести рабочую роль и выдать ей права
#   ops/create-roles.sh --selftest # проверить сам скрипт: без docker и без ячейки
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
#
# ЧЕГО ОН НЕ ТРЕБУЕТ, И ПОЧЕМУ ЭТО ГЛАВНОЕ (задача 0107). До 27 сентября 2026
# скрипт падал на `: "${APP_RUNTIME_ROLE:?…}"` — то есть требовал в .env ровно
# то, что по документам выдаёт сам: и docs/onboarding.md, и ops/env.pilot.example,
# и .env.example говорят «эту секцию заполнит create-roles.sh, вставь, что
# напечатает», и имя роли в примере намеренно пустое. Буквальный проход
# по инструкции подключения спотыкался на шаге, названном обязательным,
# и спотыкался в день, когда клиент рядом.
#
# Выбор сделан в пользу скрипта: инструмент, который ЗАВОДИТ роль, сам
# и называет её. Имя по умолчанию — `partsflow_app`, то же, что стоит
# в docs/deployment.md, ops/CLAUDE.md и корневом CLAUDE.md («приложение
# работает ролью partsflow_app»): это перенос уже принятого имени, а не
# новое решение. Пароль, которого нет, скрипт генерирует и ПЕЧАТАЕТ —
# прежняя редакция советовала вставить в .env «пароль рабочей роли, тот же
# что здесь», не напечатав его нигде.
#
# ЗАДАННОЕ В .env СИЛЬНЕЕ УМОЛЧАНИЯ, и это не вкус. Скрипт прогоняют повторно
# на работающей ячейке (появилась таблица сессий, переставили ячейку), а
# CREATE/ALTER ROLE ниже пароль ПЕРЕЗАПИСЫВАЕТ. Генерируй он новый каждый раз —
# повтор менял бы пароль роли, под которой в эту минуту работает приложение,
# и следующий перезапуск сборки не пустил бы её в базу. Поэтому пароль из .env
# переиспользуется, а генерируется только отсутствующий.
#
# ПАРОЛЬ ГЕНЕРИРУЕТСЯ В base64 НАМЕРЕННО: в нём нет ни `$`, ни `'`. Первое
# важно для .env — compose подставляет в значения `${…}`, и пароль со знаком
# доллара доехал бы до базы другим; второе для SQL ниже, где пароль стоит
# в одинарных кавычках.
set -euo pipefail
cd "$(dirname "$0")/.."

red()   { printf '\033[1;31m%s\033[0m\n' "$1" >&2; }
green() { printf '\033[1;32m%s\033[0m\n' "$1"; }
fail()  { red "$1"; exit 1; }

# Имя рабочей роли по умолчанию. Одно место на скрипт: оно же печатается
# в совете для .env, и разойдись эти два — оператор завёл бы одну роль,
# а в .env вписал другую.
DEFAULT_ROLE=partsflow_app

# Роль проверяется у базы, а не по тому, что команды дошли до конца (задача
# 0240). GRANT отвечает «успешно» и на роль, которой потом нельзя войти
# напечатанным паролем, и на суперпользователя, которому REVOKE не указ, —
# а этот пароль оператор сейчас перенесёт в .env. Два вопроса:
#   1. входит ли роль ЭТИМ паролем (по сети, иначе пароль не проверяется);
#   2. что она МОЖЕТ — has_*_privilege спрашивает права, а не выводит их
#      из выполненных GRANT, и не суперпользователь ли она (тогда любая
#      строка REVOKE выше ничего не ограничивает: «неизменяемые» журналы
#      правятся прямым SQL; проверено опытом, корневой CLAUDE.md).
# Пустой ответ на второй вопрос — не «нет замечаний», а «не спросили».
verify_role() {  # роль пароль база psql psql_login
    local role="$1" password="$2" db="$3" psql="$4" psql_login="$5"
    local out rc=0 pairs i label expr
    echo "==> Проверяем у базы: вход напечатанным паролем и права"

    out=$(PGUSER="$role" PGPASSWORD="$password" $psql_login <<< "SELECT current_user" 2>&1) || rc=$?
    if [ "$rc" != 0 ] || [ "$(printf '%s' "$out" | tail -n1)" != "$role" ]; then
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        fail "Вход ролью $role напечатанным паролем НЕ ПРОШЁЛ — в $ENV_FILE его вставлять нельзя. Роль заведена, пароль у неё другой или база недоступна по сети compose; повторный запуск скрипта переустановит пароль."
    fi

    # has_table_privilege с перечнем «A, B» отвечает «да», если есть ЛЮБОЕ из
    # них, — для «есть все» привилегии спрашиваются по одной.
    all_of() {  # таблица привилегии…
        local t="$1" pr e="" sep=""
        shift
        for pr in "$@"; do e+="${sep}has_table_privilege('$role', '$t', '$pr')"; sep=" AND "; done
        printf '%s' "$e"
    }
    local -a checks=(
        "роль не суперпользователь|NOT (SELECT rolsuper FROM pg_roles WHERE rolname = '$role')"
        "роль может входить|(SELECT rolcanlogin FROM pg_roles WHERE rolname = '$role')"
        "CONNECT на базу|has_database_privilege('$role', '$db', 'CONNECT')"
        "USAGE на схему catalog|has_schema_privilege('$role', 'catalog', 'USAGE')"
        "USAGE на схему public|has_schema_privilege('$role', 'public', 'USAGE')"
        "SELECT на public.tenant_registry|has_table_privilege('$role', 'public.tenant_registry', 'SELECT')"
        "НЕТ записи в public.tenant_registry|NOT has_table_privilege('$role', 'public.tenant_registry', 'INSERT, UPDATE, DELETE')"
        "запись в public.shedlock|$(all_of public.shedlock SELECT INSERT UPDATE DELETE)"
        "запись в public.spring_session|$(all_of public.spring_session SELECT INSERT UPDATE DELETE)"
        "запись в public.spring_session_attributes|$(all_of public.spring_session_attributes SELECT INSERT UPDATE DELETE)"
        "запись в public.mail_message|$(all_of public.mail_message SELECT INSERT UPDATE DELETE)"
        "запись в public.mail_event_pending|$(all_of public.mail_event_pending SELECT INSERT UPDATE DELETE)"
        "чтение public.service_admin|has_table_privilege('$role', 'public.service_admin', 'SELECT')"
        "журнал допусков: чтение и добавление|$(all_of public.admin_access_grant SELECT INSERT)"
        "журнал допусков: НЕТ правки и стирания|NOT has_table_privilege('$role', 'public.admin_access_grant', 'UPDATE, DELETE')"
        "запросы доступа: чтение и добавление|$(all_of public.admin_access_request SELECT INSERT)"
        "запросы доступа: НЕТ правки и стирания|NOT has_table_privilege('$role', 'public.admin_access_request', 'UPDATE, DELETE')"
    )
    # Узкая правка колонок журналов: has_table_privilege её не видит (у таблицы
    # UPDATE нет — это проверено строкой выше), поэтому спрашивается колонка.
    checks+=(
        "допуск: правка closed_at разрешена|has_column_privilege('$role', 'public.admin_access_grant', 'closed_at', 'UPDATE')"
        "запрос: правка closed_at разрешена|has_column_privilege('$role', 'public.admin_access_request', 'closed_at', 'UPDATE')"
    )
    # Одна метка = одна строка вида `SELECT 'метка', (выражение)::text;` — по
    # тому же шаблону отвечает заглушка самопроверки.
    local sql=""
    for pairs in "${checks[@]}"; do
        label="${pairs%%|*}"; expr="${pairs#*|}"
        sql+="SELECT '$label', COALESCE(($expr)::text, 'null');"$'\n'
    done
    rc=0
    out=$($psql -tA -F'|' <<< "$sql" 2>&1) || rc=$?
    if [ "$rc" != 0 ]; then
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        fail "Права роли $role у базы спросить не вышло — «готово» говорить не на чем"
    fi
    local said bad=""
    said=$(printf '%s\n' "$out" | grep -c '|' || true)
    if [ "$said" != "${#checks[@]}" ]; then
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        fail "База ответила на $said вопросов из ${#checks[@]} — права роли $role НЕ проверены"
    fi
    while IFS= read -r label; do
        case "$label" in *'|true') ;; *) bad+="    ${label%|*}"$'\n' ;; esac
    done <<< "$out"
    if [ -n "$bad" ]; then
        red "У роли $role не те права, база ответила «нет»:"
        printf '%s' "$bad" >&2
        fail "Выданное и спрошенное у базы разошлись — «готово» говорить нельзя. Если в списке суперпользователь: REVOKE его не ограничивает, нужен ALTER ROLE $role NOSUPERUSER."
    fi
    green "    Вход этим паролем прошёл; прав проверено у базы: ${#checks[@]}"
}

main() {
    local ENV_FILE="${ENV_FILE:-.env}"
    [ -f "$ENV_FILE" ] || fail "Нет файла окружения $ENV_FILE"
    # Точка-источник без слэша ищет файл в PATH, поэтому у простого имени
    # нужен «./». А у пути со слэшем его добавлять нельзя: «.//tmp/env»
    # не откроется.
    local env_path="$ENV_FILE"
    case "$ENV_FILE" in */*) ;; *) env_path="./$ENV_FILE" ;; esac
    set -a; . "$env_path"; set +a

    : "${DB_USER:?укажите DB_USER}"

    local role password generated=нет
    role="${APP_RUNTIME_ROLE:-$DEFAULT_ROLE}"
    password="${APP_RUNTIME_PASSWORD:-}"
    if [ -z "$password" ]; then
        command -v openssl >/dev/null \
            || fail "Нет openssl — задайте APP_RUNTIME_PASSWORD в $ENV_FILE вручную"
        password=$(openssl rand -base64 24)
        generated=да
    fi

    # Имя роли уходит в SQL БЕЗ кавычек (идентификатор), пароль — в одинарных.
    # Строка из .env с кавычкой или точкой с запятой не должна становиться
    # частью команды: тот же довод, что у имени базы в ops/switch-build.sh.
    [[ $role =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] \
        || fail "Негодное имя роли «$role»: только латиница, цифры и _, не с цифры"
    case "$password" in
        *"'"*|*'\'*) fail "В пароле рабочей роли есть кавычка или обратный слэш — он уходит в SQL, замените" ;;
    esac
    case "$password" in
        *[$'\n\r']*) fail "В пароле рабочей роли есть перевод строки — в .env такое значение не положить" ;;
    esac

    local COMPOSE="${COMPOSE:-docker compose -f docker-compose.prod.yml}"
    # База сборки под трафиком (задача 0112): с первой выкладки копией это
    # не «parts». Право CONNECT выдаётся на базу, а не на кластер, и выданное
    # на прежнюю базу новую сборку не пустило бы. Копии выкладка переносит
    # права сама (ops/deploy.sh, copy_db).
    local DB_NAME
    DB_NAME=$(ENV_FILE="$ENV_FILE" ops/switch-build.sh --current-db)
    # Через переменную, чтобы самопроверка могла подменить psql заглушкой:
    # весь смысл этого скрипта, кроме SQL, — что он печатает человеку.
    local PSQL="${PSQL:-$COMPOSE exec -T postgres psql -U $DB_USER -d $DB_NAME -v ON_ERROR_STOP=1}"
    # Вход ролью — ОТДЕЛЬНАЯ команда, и `-h postgres` в ней не украшение.
    # Образ postgres пускает по сокету и по 127.0.0.1 БЕЗ пароля (trust):
    # `psql -U роль` изнутри контейнера скажет «вошёл» с любым паролем, в том
    # числе с неверным (замерено: пароль «wrong» пускает). Пароль проверяется
    # только соединением по адресу контейнера в сети compose — то есть именем
    # сервиса. Имя и пароль берутся из окружения вызова (PGUSER, PGPASSWORD),
    # а не из командной строки: пароль не должен попасть в список процессов.
    local PSQL_LOGIN="${PSQL_LOGIN:-$COMPOSE exec -T -e PGUSER -e PGPASSWORD postgres psql -h postgres -d $DB_NAME -v ON_ERROR_STOP=1 -tA}"

    echo "==> Заводим рабочую роль $role"
    $PSQL <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$role') THEN
        CREATE ROLE $role LOGIN PASSWORD '$password';
    ELSE
        ALTER ROLE $role LOGIN PASSWORD '$password';
    END IF;
END \$\$;

GRANT CONNECT ON DATABASE "$DB_NAME" TO $role;

-- Общая схема ячейки: справочники читают все, пишет их миграция.
GRANT USAGE ON SCHEMA catalog TO $role;
GRANT SELECT ON ALL TABLES IN SCHEMA catalog TO $role;
ALTER DEFAULT PRIVILEGES IN SCHEMA catalog GRANT SELECT ON TABLES TO $role;

-- Реестр арендаторов и служебные таблицы в public: рабочая роль читает
-- реестр при входе и пишет отметки ShedLock.
GRANT USAGE ON SCHEMA public TO $role;
GRANT SELECT ON public.tenant_registry TO $role;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.shedlock TO $role;

-- Хранилище сессий (catalog/021, задача 0080): состояние сессии живёт
-- в общей схеме ячейки, и рабочая роль пишет туда на каждом входе.
-- Без этих прав ячейка не пускает никого — сессию некуда записать.
-- Те же права выдаёт и накат общей схемы (SchemaGrants.applyCellTables):
-- скрипт заведения ролей на работающей ячейке после выкладки
-- не перезапускают, а таблица появилась позже него.
GRANT SELECT, INSERT, UPDATE, DELETE ON public.spring_session TO $role;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.spring_session_attributes
    TO $role;

-- Журнал исходящих писем (catalog/022, задача 0192): письмо ставится в очередь
-- и разбирается рабочей ролью. Те же права выдаёт накат общей схемы
-- (SchemaGrants.applyCellTables) — по тому же доводу, что для сессий выше.
GRANT SELECT, INSERT, UPDATE, DELETE ON public.mail_message TO $role;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.mail_event_pending TO $role;

-- Администратор сервиса (catalog/025, задача 0042): учётные записи рабочая
-- роль только читает — при входе; заводит их владелец схем через управляющий
-- контур. Разрешения владельцев разборок и запросы доступа — история
-- «кто и когда пускал администратора», устроенная как журнал: рабочая роль
-- читает, добавляет и правит только колонки закрытия, стереть не может.
-- REVOKE перед GRANT: скрипт прогоняют повторно, и узкий GRANT сам широких
-- прав прежнего прогона не отнимает.
-- Те же права выдаёт накат общей схемы (SchemaGrants.applyCellTables).
GRANT SELECT ON public.service_admin TO $role;
REVOKE UPDATE, DELETE ON public.admin_access_grant FROM $role;
GRANT SELECT, INSERT ON public.admin_access_grant TO $role;
GRANT UPDATE (closed_at, closed_by) ON public.admin_access_grant TO $role;
REVOKE UPDATE, DELETE ON public.admin_access_request FROM $role;
GRANT SELECT, INSERT ON public.admin_access_request TO $role;
GRANT UPDATE (closed_at, outcome) ON public.admin_access_request TO $role;
SQL

    verify_role "$role" "$password" "$DB_NAME" "$PSQL" "$PSQL_LOGIN"

    # ПОЧЕМУ ЗДЕСЬ НЕ ops/migrate-tenants.sh, который стоял тут до задачи 0210.
    # Тот ходит в управляющий контур по HTTP и требует APP_PROVISIONING_TOKEN
    # безусловно — падает на пустом секрете раньше, чем успевает что-нибудь
    # спросить, — а docs/onboarding.md (раздел 2) велит секрет выключать сразу
    # после подключения клиента. То есть совет был выполним только на ячейке,
    # нарушающей собственное правило безопасности, и читал его человек ровно
    # на шаге 4 «Как поднять»: рабочая роль ещё не дотягивается до схем
    # арендаторов, ячейка не работает вовсе. Тот же дефект у готовности закрыт
    # задачей 0204, и команда с тех пор лежит одним местом —
    # TenantMigrations.MIGRATE_COMMAND; шелл её оттуда прочитать не может,
    # поэтому здесь копия словами, а сверяет их с тем местом
    # MigrateCommandAdviceTest.
    echo "==> Права на схемы арендаторов"
    echo "    Их выдаёт приложение: при провижининге нового клиента и при"
    echo "    приведении схем к версии образа. Для уже заведённых прогоните"
    echo "    один раз:"
    echo
    echo "        ops/schema-sync.sh --apply"
    echo
    echo "    Это одноразовый контейнер того же образа, а не запрос"
    echo "    к работающему приложению: секрет провижининга"
    echo "    (APP_PROVISIONING_TOKEN) ему не нужен — выключенным он и должен"
    echo "    быть после подключения клиента (docs/onboarding.md, раздел 2)."
    echo "    Права он выдаёт и схемам, которым нечего накатывать."
    echo
    if [ "$generated" = да ]; then
        echo "==> Пароль рабочей роли СГЕНЕРИРОВАН (в $ENV_FILE его не было)."
        echo "    Он напечатан ниже и больше нигде не хранится: вставьте его в $ENV_FILE."
    else
        echo "==> Пароль рабочей роли взят из $ENV_FILE и переустановлен тот же."
    fi
    echo

    # Перечисляем ровно те переменные, которые читает compose. «DB_URL_RUNTIME»
    # стояла здесь и не читалась никем: оператор прописывал её, видел рабочую
    # роль в .env и считал разделение включённым — а приложение продолжало
    # ходить владельцем, то есть журналы оставались изменяемыми.
    #
    # Число в заголовке СЧИТАЕТСЯ по этому списку, а не написано словом:
    # до задачи 0107 здесь стояло «все четыре» при пяти напечатанных строках,
    # и оператор искал, какую из пяти не заполнять.
    local vars=(
        "APP_RUNTIME_ROLE=$role"
        "APP_RUNTIME_PASSWORD=$password"
        "APP_DDL_URL=jdbc:postgresql://postgres:5432/parts   (признак «владелец схем отдельный»; адрес compose строит на базу сборки)"
        "APP_DDL_USERNAME=$DB_USER"
        "APP_DDL_PASSWORD=<пароль владельца: значение DB_PASSWORD из этого же $ENV_FILE>"
    )
    printf '==> Готово. В %s должны быть заполнены все %d переменных:\n' "$ENV_FILE" "${#vars[@]}"
    local v
    for v in "${vars[@]}"; do printf '    %s\n' "$v"; done
    echo
    # Пароль владельца не печатается намеренно: он уже лежит в этом же файле
    # (DB_PASSWORD), и второй его копией в выводе терминала мы бы только
    # размножили самый дорогой секрет ячейки — владелец схем владеет журналами.
    echo "    Заполнять надо все разом: имя роли без пароля означает подключение"
    echo "    ролью, которой нет, — приложение не поднимется вовсе. Проверка —"
    echo "    строка в логе при старте: «Журналы защищены» против «Журналы"
    echo "    правятся прямым SQL»."
}

# ——— Самопроверка. Ни docker, ни ячейки, ни базы не нужно: psql подменяется
# заглушкой, а проверяется ровно то, из-за чего задача и появилась, — что
# скрипт не требует того, что сам выдаёт, и что число в сообщении считается.
selftest() {
    local bad=0 dir out rc
    dir="${TMPDIR:-/tmp}/create-roles-selftest.$$"
    mkdir -p "$dir"
    # shellcheck disable=SC2064
    trap "rm -rf '$dir'" EXIT
    echo "Самопроверка ops/create-roles.sh"

    # Заглушка владельца: SQL выбрасывает, а на вопросы про права (строки
    # `SELECT 'метка', …`) отвечает по режиму STUB_PRIV — все «true», одна
    # «false», пусто, меньше строк, чем спрошено.
    cat > "$dir/psql" <<'STUB'
#!/usr/bin/env bash
sql=$(cat)
case "$sql" in
    *"SELECT '"*)
        rows=$(printf '%s\n' "$sql" | sed -n "s/^SELECT '\([^']*\)'.*/\1|true/p")
        case "${STUB_PRIV:-ok}" in
            ok)    printf '%s\n' "$rows" ;;
            deny)  printf '%s\n' "$rows" | sed '1s/|true$/|false/' ;;
            short) printf '%s\n' "$rows" | sed -n '1p' ;;
            empty) : ;;
        esac ;;
esac
STUB
    # Заглушка входа: «база», у роли которой пароль STUB_DB_PW (если задан).
    cat > "$dir/psql_login" <<'STUB'
#!/usr/bin/env bash
cat >/dev/null
if [ -n "${STUB_DB_PW:-}" ] && [ "$PGPASSWORD" != "$STUB_DB_PW" ]; then
    echo 'FATAL:  password authentication failed for user "'"$PGUSER"'"' >&2
    exit 2
fi
printf '%s\n' "$PGUSER"
STUB
    chmod +x "$dir/psql" "$dir/psql_login"

    run() {  # содержимое .env… → вывод скрипта, код возврата в rc
        local script="${SCRIPT:-$0}"
        printf '%s\n' "$@" > "$dir/env"
        set +e
        out=$(env -u APP_RUNTIME_ROLE -u APP_RUNTIME_PASSWORD \
                  -u APP_DB_BLUE -u APP_DB_GREEN \
                  PSQL="$dir/psql" PSQL_LOGIN="$dir/psql_login" ENV_FILE="$dir/env" bash "$script" 2>&1)
        rc=$?
        set -e
    }

    has() {  # имя случая, слово
        case "$out" in
            *"$2"*) ;;
            *) red "  ✗ $1: в выводе нет «$2»"
               printf '%s\n' "$out" | sed 's/^/      /' >&2
               bad=$((bad + 1)) ;;
        esac
    }

    # 1. Строки про рабочую роль в .env нет вовсе — ровно то состояние,
    #    в котором оператор оказывается, дойдя до этого шага по инструкции.
    run 'DB_USER=partsflow' 'DB_PASSWORD=vlad'
    if [ "$rc" != 0 ]; then
        red "  ✗ .env без APP_RUNTIME_ROLE: скрипт обязан работать, а он вышел с $rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        bad=$((bad + 1))
    else
        has ".env без APP_RUNTIME_ROLE" "APP_RUNTIME_ROLE=$DEFAULT_ROLE"
        printf '  ✓ .env без строки роли: имя предложено само (%s)\n' "$DEFAULT_ROLE"
    fi

    # 2. Пустое значение — так и стоит в .env.example и ops/env.pilot.example.
    #    Пустое перебивает окружение (файл читается через set -a), поэтому
    #    случай отдельный, а не тот же самый.
    run 'DB_USER=partsflow' 'APP_RUNTIME_ROLE=' 'APP_RUNTIME_PASSWORD='
    if [ "$rc" != 0 ]; then
        red "  ✗ пустые значения ролей: скрипт обязан работать, а он вышел с $rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        bad=$((bad + 1))
    else
        has "пустые значения ролей" "APP_RUNTIME_ROLE=$DEFAULT_ROLE"
        printf '  ✓ пустые значения в .env: скрипт не падает\n'
    fi

    # 3. Число в сообщении равно числу напечатанных строк. Именно равенство,
    #    а не «есть ли слово»: константа расходится с содержимым молча.
    local said printed
    said=$(printf '%s\n' "$out" \
        | sed -n 's/^==> Готово\..*заполнены все \([0-9]\{1,\}\) переменных.*$/\1/p' | tail -n1)
    printed=$(printf '%s\n' "$out" | grep -c '^    [A-Z_][A-Z_]*=' || true)
    if [ -z "$said" ]; then
        red "  ✗ число переменных не напечатано числом — похоже, стоит константой"
        bad=$((bad + 1))
    elif [ "$said" != "$printed" ]; then
        red "  ✗ сказано «все $said», а напечатано строк: $printed"
        bad=$((bad + 1))
    else
        printf '  ✓ число в сообщении считается: сказано %s, напечатано %s\n' "$said" "$printed"
    fi

    # 4. Пароль из .env переиспользуется. Повтор скрипта на работающей ячейке
    #    не имеет права сменить пароль роли, под которой работает приложение.
    run 'DB_USER=partsflow' 'APP_RUNTIME_ROLE=my_app' 'APP_RUNTIME_PASSWORD=parol-iz-env'
    has "пароль из .env" 'APP_RUNTIME_PASSWORD=parol-iz-env'
    has "пароль из .env" 'APP_RUNTIME_ROLE=my_app'
    has "пароль из .env" 'взят из'
    printf '  ✓ заданные имя и пароль сильнее умолчания\n'

    # 5. Отсутствующий пароль генерируется и ПЕЧАТАЕТСЯ — иначе вставлять
    #    в .env было бы нечего (прежняя редакция советовала «тот же что здесь»,
    #    не напечатав его нигде).
    local first second
    run 'DB_USER=partsflow'
    first=$(printf '%s\n' "$out" | sed -n 's/^    APP_RUNTIME_PASSWORD=//p')
    has "пароль сгенерирован" 'СГЕНЕРИРОВАН'
    run 'DB_USER=partsflow'
    second=$(printf '%s\n' "$out" | sed -n 's/^    APP_RUNTIME_PASSWORD=//p')
    if [ ${#first} -lt 16 ] || [ "$first" = "$second" ]; then
        red "  ✗ пароль не сгенерирован или повторяется между прогонами: «$first» и «$second»"
        bad=$((bad + 1))
    else
        printf '  ✓ пароля нет в .env — сгенерирован и напечатан (%d символов)\n' "${#first}"
    fi
    case "$first" in
        *'$'*|*"'"*) red "  ✗ в сгенерированном пароле есть \$ или кавычка"; bad=$((bad + 1)) ;;
        *) printf '  ✓ в сгенерированном пароле нет ни $, ни кавычки\n' ;;
    esac

    # 6–7. Значение из .env не становится частью команды. Имя роли уходит
    #      в SQL идентификатором, пароль — в кавычках.
    run 'DB_USER=partsflow' 'APP_RUNTIME_ROLE=app;DROP ROLE partsflow'
    if [ "$rc" = 0 ]; then
        red "  ✗ негодное имя роли обязано быть отказом"; bad=$((bad + 1))
    else
        printf '  ✓ имя роли с «;» — отказ, а не подстановка в SQL\n'
    fi
    run 'DB_USER=partsflow' "APP_RUNTIME_PASSWORD=pa'rol"
    if [ "$rc" = 0 ]; then
        red "  ✗ пароль с кавычкой обязан быть отказом"; bad=$((bad + 1))
    else
        printf '  ✓ пароль с кавычкой — отказ\n'
    fi

    # Подделки лежат в /tmp, а скрипт первым делом уходит в корень репозитория
    # от своего пути — копия ушла бы в родителя /tmp и «упала» бы на ненайденном
    # ops/switch-build.sh, то есть по причине, к дефекту не относящейся.
    # Поэтому `cd` в копии переписывается: проверка, зеленеющая от чужого
    # отказа, не стоит ничего — этим она и была до правки.
    fix_cd() { sed -e "s|^cd \"\$(dirname \"\$0\")/\.\.\"|cd '$PWD'|"; }

    # 8. Возврат дефекта. Копия, в которой число написано константой, обязана
    #    отработать до конца и разойтись с числом напечатанных строк — иначе
    #    случай 3 ничего не стережёт.
    sed 's/"${#vars\[@\]}"/4/' "$0" | fix_cd > "$dir/const.sh"
    SCRIPT="$dir/const.sh" run 'DB_USER=partsflow'
    said=$(printf '%s\n' "$out" \
        | sed -n 's/^==> Готово\..*заполнены все \([0-9]\{1,\}\) переменных.*$/\1/p' | tail -n1)
    printed=$(printf '%s\n' "$out" | grep -c '^    [A-Z_][A-Z_]*=' || true)
    if [ "$rc" != 0 ] || [ -z "$said" ]; then
        red "  ✗ возврат дефекта: копия с константой обязана дойти до сообщения, а вышла с $rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        bad=$((bad + 1))
    elif [ "$said" = "$printed" ]; then
        red "  ✗ возврат дефекта: копия с константой $said обязана разойтись с $printed строками"
        bad=$((bad + 1))
    else
        printf '  ✓ возврат дефекта: константа вместо счёта — расхождение видно (%s против %s)\n' \
            "$said" "$printed"
    fi

    # 9. Возврат прежнего дефекта задачи целиком: копия, требующая
    #    APP_RUNTIME_ROLE, обязана падать на случае 1 — и падать ИМЕННО
    #    на этой переменной, а не на чём-нибудь по дороге.
    #    Строка вставляется awk, а не `sed 's/…/&\n…/'`: перевод строки
    #    в правой части BSD sed не понимает, и подделка вышла бы другой
    #    на машине разработчика и на прогоне.
    awk '{print}
         /укажите DB_USER/ {print "    : \"${APP_RUNTIME_ROLE:?укажите APP_RUNTIME_ROLE}\""}' \
        "$0" | fix_cd > "$dir/req.sh"
    SCRIPT="$dir/req.sh" run 'DB_USER=partsflow'
    if [ "$rc" = 0 ]; then
        red "  ✗ возврат дефекта: копия, требующая APP_RUNTIME_ROLE, обязана падать на .env без неё"
        bad=$((bad + 1))
    else
        case "$out" in
            *APP_RUNTIME_ROLE*)
                printf '  ✓ возврат дефекта: требование APP_RUNTIME_ROLE снова валит проход по инструкции\n' ;;
            *) red "  ✗ возврат дефекта: копия упала, но не на APP_RUNTIME_ROLE:"
               printf '%s\n' "$out" | sed 's/^/      /' >&2
               bad=$((bad + 1)) ;;
        esac
    fi

    # 10. Совет про права на схемы арендаторов обязан быть выполним на ячейке
    #     с ВЫКЛЮЧЕННЫМ секретом провижининга (задача 0210): именно такой она
    #     и должна быть по docs/onboarding.md, раздел 2. Прежний совет — накат
    #     через управляющий контур — на ней падает до первого запроса, а читают
    #     эти строки на шаге 4 «Как поднять», где рабочая роль ещё
    #     не дотягивается до схем и ячейка не работает вовсе.
    #     Проверяется НАПЕЧАТАННОЕ, а не текст файла: объяснение «почему
    #     не тот путь» стоит рядом комментарием и называет его по имени
    #     законно. Текст restore-cell.sh сверяет MigrateCommandAdviceTest —
    #     у него самопроверки нет, до совета он разворачивает дампы docker'ом.
    run 'DB_USER=partsflow'
    has "совет про права на схемы" 'ops/schema-sync.sh --apply'
    case "$out" in
        *migrate-tenants*)
            red "  ✗ совет про права снова ведёт в управляющий контур: на ячейке с выключенным секретом он падает до первого запроса"
            printf '%s\n' "$out" | sed 's/^/      /' >&2
            bad=$((bad + 1)) ;;
        *) printf '  ✓ совет про права выполним при выключенном секрете\n' ;;
    esac

    # 11–14. Скрипт отчитывается результатом, а не запуском (задача 0240).
    #     «Готово» говорится только после того, как база подтвердила вход
    #     напечатанным паролем и права. Заглушка играет базу, где у роли
    #     ДРУГОЙ пароль / нет одного права / ответ пуст.
    run 'DB_USER=partsflow' 'APP_RUNTIME_ROLE=my_app' 'APP_RUNTIME_PASSWORD=parol-iz-env'
    has "проверка у базы" 'прав проверено у базы'
    printf '  ✓ здоровый путь: вход и права подтверждены базой, об этом сказано\n'

    STUB_DB_PW=drugoy-parol run 'DB_USER=partsflow' 'APP_RUNTIME_ROLE=my_app' 'APP_RUNTIME_PASSWORD=parol-iz-env'
    if [ "$rc" = 0 ] || [[ $out != *"НЕ ПРОШЁЛ"* ]] || [[ $out == *"==> Готово"* ]]; then
        red "  ✗ напечатанный пароль не подходит роли, а скрипт говорит «готово» (код $rc)"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        bad=$((bad + 1))
    else
        printf '  ✓ у роли другой пароль — отказ словами, «готово» не сказано\n'
    fi

    STUB_PRIV=deny run 'DB_USER=partsflow'
    if [ "$rc" = 0 ] || [[ $out != *"база ответила «нет»"* ]] || [[ $out == *"==> Готово"* ]]; then
        red "  ✗ база ответила «нет» на право, а скрипт говорит «готово» (код $rc)"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        bad=$((bad + 1))
    else
        printf '  ✓ база ответила «нет» на право — отказ с названием права\n'
    fi

    for mode in empty short; do
        STUB_PRIV=$mode run 'DB_USER=partsflow'
        if [ "$rc" = 0 ] || [[ $out != *"НЕ проверены"* ]]; then
            red "  ✗ ответ базы «$mode» принят за «замечаний нет» (код $rc)"
            printf '%s\n' "$out" | sed 's/^/      /' >&2
            bad=$((bad + 1))
        else
            printf '  ✓ ответ базы «%s» — это «не спросили», а не «всё в порядке»\n' "$mode"
        fi
    done

    # 15. Возврат дефекта: копия без вызова проверки обязана дойти до «готово»
    #     при чужом пароле и пустых правах — иначе случаи 11–14 ничего
    #     не стерегут. Строка вызова срезается по ЯКОРЮ, не по номеру.
    grep -v '^    verify_role "\$role"' "$0" | fix_cd > "$dir/noverify.sh"
    SCRIPT="$dir/noverify.sh" STUB_DB_PW=drugoy-parol STUB_PRIV=empty run 'DB_USER=partsflow'
    if [ "$rc" != 0 ] || [[ $out != *"==> Готово"* ]]; then
        red "  ✗ возврат дефекта: копия без проверки обязана сказать «готово» (код $rc)"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        bad=$((bad + 1))
    else
        printf '  ✓ возврат дефекта: без проверки у базы «готово» говорится при чужом пароле\n'
    fi

    [ "$bad" = 0 ] || fail "Самопроверка не прошла: $bad"
    # Сужено намеренно: `psql` здесь подменён заглушками, играющими базу
    # (весь SQL выбрасывают, на вопросы про вход и права отвечают по режиму), —
    # проверено только то, что скрипт ВЫПОЛНЯЕТ проверку и верит ей. Роль не
    # заведена, права не выданы, база не тронута; настоящий ответ базы
    # (в том числе что только соединение по сети проверяет пароль) стоит
    # в записи ops-roли-схемы и снят живым прогоном на postgres:16-alpine.
    green "Самопроверка пройдена: печать, отказы и проверка роли у базы. SQL в настоящую базу не уходил."
}

case "${1:-}" in
    "") main ;;
    --selftest) selftest ;;
    -h|--help)
        sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'
        exit 2 ;;
    *) fail "Неизвестный аргумент «$1». Их два: без аргументов и --selftest." ;;
esac
