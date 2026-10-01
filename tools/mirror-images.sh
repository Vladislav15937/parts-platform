#!/usr/bin/env bash
# Своё зеркало образов хранилища в нашем GHCR.
#
#   tools/mirror-images.sh --проверить     что лежит в зеркале — мимо кэша
#   tools/mirror-images.sh --собрать       собрать локально, без реестра
#   tools/mirror-images.sh --опубликовать  собрать и положить в реестр
#   tools/mirror-images.sh --selftest      проверить сам скрипт на подделках
#
# У `--проверить` ТРИ ИСХОДА, И СВОДИТЬ ИХ К ДВУМ НЕЛЬЗЯ (задача 0241):
#   0 — зеркало полно: каждый тег лежит, тянется без входа, обе архитектуры;
#   1 — зеркало неполно: тега нет (404), пакет закрылся (401/403), однорукий
#       индекс. Это чинится публикацией, и шаг CI по этому коду собирает;
#   2 — СПРОСИТЬ НЕ ВЫШЛО: реестр молчит, ответил 5xx, не выдал анонимный токен
#       или прислал нечитаемое тело. Про зеркало не известно НИЧЕГО, и собирать
#       по такому ответу нечего — а до 0241 этот исход был неотличим от «неполно»
#       (оба давали 1), то есть прогон шёл наполнять зеркало по ответу, которого
#       не было. Код 2 отдаёт и обёртка на незнакомый код самой проверки: крах
#       внутри `verify()` — тоже «не спросили», а не «неполно».
#
# ЗАЧЕМ ОН ЕСТЬ. 26 сентября 2026 образ `quay.io/minio/minio` пропал целиком —
# не тег, а репозиторий, — и `main` покраснела у всех: три теста поднимают
# настоящий MinIO, и поднять его стало нечем. Годом раньше то же случилось
# на Docker Hub. Решение владельца того дня: «своё зеркало в GHCR».
#
# ПОЧЕМУ СКРИПТ, А НЕ «ЗАЛИТЬ РУКАМИ ОДИН РАЗ». Залитое руками нельзя
# повторить: следующий образ (а он будет — MinIO стал source-only, наш выпуск
# последний) поехал бы вручную и по памяти. Здесь весь путь записан: откуда
# берётся бинарник, чем сверяется, во что кладётся и куда пушится. Тот же
# скрипт зовёт задача CI «Зеркало образов», поэтому пропавшее зеркало
# восстанавливается прогоном, а не человеком с записками.
#
# ЧТО ИМЕННО ЗЕРКАЛИМ. Не чужой образ — его больше нет нигде, до чего мы
# дотягиваемся (quay: репозитория нет; Docker Hub: denied; ghcr.io/minio:
# нет; dl.min.io: 410 на все версии). Живым остался единственный официальный
# источник — файлы выпуска на GitHub, и сумма sha256 к ним опубликована там
# же. Значит зеркалим ВЫПУСК: бинарник той же версии, сверенный по сумме,
# в тонком образе (tools/mirror/*.Dockerfile). Версия в бою не меняется.
#
# Локальный кэш источником быть не мог: у разработчика лежит только arm64,
# а CI и ячейка — amd64. Именно поэтому образ собирается на две архитектуры.
#
# ДВА СПОСОБА ЗЕРКАЛИТЬ, И ВЫБИРАЕТ ИХ НЕ ЭТОТ СКРИПТ. У minio и mc официального
# образа не осталось нигде — их мы СОБИРАЕМ из файлов выпуска. У postgres
# и liquibase автор жив и раздаёт образ — их мы КОПИРУЕМ регистр в регистр,
# не пересобирая: база данных, собранная нами из чужих кусков, была бы другим
# продуктом, а не тем, что стоит в бою. Что чем зеркалится, записано там же,
# где адреса, — списком `x-mirror-sources` в ops/images.yml: есть источник,
# значит копируем.
#
# ЗАЧЕМ КОПИРОВАТЬ ТО, ЧТО И ТАК РАЗДАЮТ (задача 0095). 13 сентября 2026 задача
# CI «Миграции на чистой базе» упала на выкачке `liquibase/liquibase:4.27`:
# «read: connection reset by peer» от registry-1.docker.io. Образ никуда
# не девался — чужой реестр не ответил в конкретную минуту. Красный CI без
# дефекта стоит дороже, чем выглядит: исполнитель ищет причину у себя,
# проверяющий рискует вынести вердикт по ложному красному.
#
# ТЕГ КОПИИ НЕСЁТ СУММУ ИСТОЧНИКА, И НА ЭТОМ ДЕРЖИТСЯ ОБНОВЛЕНИЕ ЗЕРКАЛА.
# Задача CI не копирует ничего, пока тег в реестре есть, — значит источник,
# сменённый без смены тега, остался бы только в файле: «зеркало на месте»
# отвечало бы про прежние байты. Тег назван суммой, поэтому новый источник —
# это всегда новый тег, и зеркало наполняется само.
set -euo pipefail

ROOT="${MIRROR_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
# Путь к себе — абсолютным: самопроверка читает себя этим путём, кладёт этим же
# путём подделку и этим же путём запускает детей. Относительный `$0` зависит
# от того, из какого каталога скрипт позвали (урок задачи 0200).
case "$0" in
    /*) SELF="$0" ;;
    *)  SELF="$PWD/$0" ;;
esac
IMAGES_FILE="${IMAGES_FILE:-$ROOT/ops/images.yml}"
# База выпусков. Переопределяется только самопроверкой — ей в сеть не надо.
RELEASES="${MIRROR_RELEASES:-https://github.com/minio}"
BUILDER="${MIRROR_BUILDER:-partsflow-mirror}"
PLATFORMS="${MIRROR_PLATFORMS:-linux/amd64,linux/arm64}"
# Все сервисы зеркала. Второго списка — «эти собираем, эти копируем» — здесь
# намеренно нет: он выводится из самого фрагмента, где записаны источники.
# Два списка одного и того же расходятся молча, и правка трогает один из них.
SERVICES="minio mc postgres liquibase"

# ДВА ШВА ДЛЯ САМОПРОВЕРКИ, И БОЛЬШЕ НИ ДЛЯ ЧЕГО. Реестр и docker — единственное,
# чего у самопроверки нет и быть не может (сети нет, образ класть некуда), а режимы
# `--проверить`, `--собрать` и `--опубликовать` без них не проверяются вовсе
# (задача 0241). Подменяется ровно транспорт: адрес реестра, разбор ответов, ветки
# и слова остаются теми же — иначе проверялся бы не скрипт, а его копия.
# Третий шов уже был: MIRROR_RELEASES выше, по нему файлы выпуска берутся по file://.
MIRROR_CURL="${MIRROR_CURL:-curl}"
MIRROR_DOCKER="${MIRROR_DOCKER:-docker}"

# Сколько раз спрашивать реестр, прежде чем назвать молчание молчанием, и сколько
# ждать между попытками. Три — как у tools/alert-image-guard.py: рябь (5xx, обрыв
# соединения, невыданный токен) не должна красить прогон, а четвёртая попытка уже
# не про рябь, а про минуты ожидания в задаче CI. Повторяется ТОЛЬКО неоднозначное:
# 200, 404 и 401/403 — это ответ реестра по существу, и спрашивать его дважды незачем.
REGISTRY_ATTEMPTS="${MIRROR_ATTEMPTS:-3}"
REGISTRY_RETRY_SLEEP="${MIRROR_RETRY_SLEEP:-2}"

red()   { printf '\033[1;31m%s\033[0m\n' "$1" >&2; }
green() { printf '\033[1;32m%s\033[0m\n' "$1"; }
step()  { printf '\n\033[1m%s\033[0m\n' "$1"; }
fail()  { red "$1"; exit 1; }

# Адрес образа из единственного места, где он записан. Разбор строчный:
# формат фрагмента наш, и стережёт его tools/image-pin-guard.py.
pin() {  # сервис → адрес
    python3 - "$IMAGES_FILE" "$1" <<'PY'
import re, sys
path, want = sys.argv[1], sys.argv[2]
service = None
for line in open(path, encoding='utf-8'):
    m = re.match(r'^ {2}([A-Za-z0-9_.-]+):\s*$', line)
    if m:
        service = m.group(1)
        continue
    m = re.match(r'^\s+image:\s*(\S+)\s*$', line)
    if m and service == want:
        print(m.group(1))
        sys.exit(0)
sys.exit(f'в {path} нет образа для сервиса «{want}»')
PY
}

# Источник копии — из того же фрагмента. Пусто означает «этот образ мы
# собираем сами»: развилка одна на весь скрипт и стоит в одном месте.
source_of() {  # сервис → адрес источника с суммой, либо пусто
    python3 - "$IMAGES_FILE" "$1" <<'PY'
import re, sys
path, want = sys.argv[1], sys.argv[2]
inside = False
for line in open(path, encoding='utf-8'):
    if re.match(r'^x-mirror-sources:\s*$', line):
        inside = True
        continue
    if inside:
        # Блок кончается первой строкой без отступа (`services:`). Комментарии
        # внутри блока идут с отступом и его не прерывают.
        if line[:1] not in (' ', '\t', '\n'):
            break
        m = re.match(r'^ {2}([A-Za-z0-9_.-]+):\s*(\S+)\s*$', line)
        if m and m.group(1) == want:
            print(m.group(2))
            break
PY
}

tag_of()  { printf '%s' "${1##*:}"; }
path_of() { local p="${1%:*}"; printf '%s' "${p#ghcr.io/}"; }

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Зеркало обязано быть нашим и закреплённым. Проверяется здесь, а не только
# сторожем: скрипт кладёт образ в реестр, и положить его по чужому адресу
# или с плавающим тегом — та же поломка, из-за которой всё началось.
check_ref() {  # сервис адрес
    local svc="$1" ref="$2" tag src digest want
    case "$ref" in *:*) : ;; *) fail "адрес без тега: $ref" ;; esac
    tag="$(tag_of "$ref")"
    case "$tag" in
        # Фигурные скобки не для красоты: bash 3.2 из macOS прихватывает
        # многобайтовую кавычку в имя переменной и валится «tag»: unbound
        # variable» — на этом уже спотыкались (docs/agent-workflow.md).
        latest|main|edge) fail "плавающий тег «${tag}» в прогоне запрещён: ${ref}" ;;
    esac
    case "$ref" in
        ghcr.io/*) : ;;
        *) fail "зеркало обязано лежать в нашем GHCR, а это чужой реестр: $ref" ;;
    esac

    # У копии тег обязан быть назван суммой источника, а источник — закреплён
    # суммой, а не тегом. Иначе обновление зеркала молча не случается: задача
    # CI не копирует, пока тег в реестре есть.
    src="$(source_of "$svc")"
    if [ -n "$src" ]; then
        case "$src" in
            *@sha256:*) : ;;
            *) fail "источник $svc закреплён тегом, а не суммой: $src — тег у автора плывёт, и копия перестала бы быть повторяемой" ;;
        esac
        digest="${src##*@sha256:}"
        want="$(printf '%s' "$digest" | cut -c1-12)"
        case "$tag" in
            *-"$want") : ;;
            # Фигурные скобки обязательны — та же ловушка, что отмечена выше
            # у плавающего тега: bash 3.2 из macOS прихватывает многобайтовую
            # кавычку в имя переменной, и вместо отказа по существу приходит
            # «tag»: unbound variable». Поймано собственной самопроверкой:
            # случай краснел, но не тем сообщением, которого ждал.
            *) fail "тег зеркала ${svc} не назван суммой источника: тег «${tag}», а сумма начинается на «${want}». Сменив источник, смените и тег — иначе «зеркало на месте» ответит про прежние байты" ;;
        esac
    fi
}

# Бинарник выпуска + его опубликованная сумма. Сумма сверяется ВСЕГДА:
# зеркало без сверки — это «мы положили что-то похожее».
fetch_binaries() {  # сервис тег каталог
    local svc="$1" tag="$2" dir="$3" arch asset want got
    for arch in amd64 arm64; do
        asset="$svc.linux-$arch.$tag"
        curl -fsSL -o "$dir/$svc-$arch" \
            "$RELEASES/$svc/releases/download/$tag/$asset" \
            || fail "не скачался $asset — выпуск снят или сети нет"
        curl -fsSL -o "$dir/$svc-$arch.sha256" \
            "$RELEASES/$svc/releases/download/$tag/$asset.sha256sum" \
            || fail "не скачалась сумма для $asset"
        want="$(cut -d' ' -f1 "$dir/$svc-$arch.sha256")"
        got="$(sha256_of "$dir/$svc-$arch")"
        [ "$want" = "$got" ] || fail "sha256 не сошлась у $asset: ждали $want, получили $got"
        printf '  ✓ %s — sha256 сошлась (%s)\n' "$asset" "$got"
    done
}

build_one() {  # сервис режим(load|push)
    local svc="$1" mode="$2" ref tag dir
    ref="$(pin "$svc")"
    check_ref "$svc" "$ref"
    tag="$(tag_of "$ref")"
    dir="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$dir'" RETURN

    step "$svc: $ref"
    fetch_binaries "$svc" "$tag" "$dir"

    if [ "$mode" = push ]; then
        # Многоархитектурная сборка требует сборщика с драйвером
        # docker-container: у драйвера `docker` его умеет не всякая машина,
        # и в прогоне это выясняется отказом посередине.
        $MIRROR_DOCKER buildx inspect "$BUILDER" >/dev/null 2>&1 \
            || $MIRROR_DOCKER buildx create --name "$BUILDER" --driver docker-container >/dev/null
        $MIRROR_DOCKER buildx build --builder "$BUILDER" --platform "$PLATFORMS" \
            -f "$ROOT/tools/mirror/$svc.Dockerfile" \
            --build-arg "VERSION=$tag" \
            -t "$ref" --push "$dir" \
            || fail "не удалось положить $ref в реестр (вход есть? нужен write:packages)"
        printf '  ✓ уехало: %s (%s)\n' "$ref" "$PLATFORMS"
    else
        # Локально — своя архитектура: многоархитектурный образ в кэш
        # не загрузить, а проверить рецепт надо запуском, а не сборкой.
        $MIRROR_DOCKER buildx build -f "$ROOT/tools/mirror/$svc.Dockerfile" \
            --build-arg "VERSION=$tag" \
            -t "$ref" --load "$dir" \
            || fail "образ $svc не собрался"
        printf '  ✓ собран локально: %s\n' "$ref"
        $MIRROR_DOCKER run --rm "$ref" --version | sed 's/^/      /'
        $MIRROR_DOCKER run --rm "$ref" --version | grep -q "$tag" \
            || fail "$svc в образе не той версии: в адресе $tag, а бинарник говорит другое"
        printf '  ✓ версия в образе совпадает с тегом: %s\n' "$tag"
    fi
}

# Копия живого чужого образа: регистр в регистр, без пересборки.
copy_one() {  # сервис режим(load|push)
    local svc="$1" mode="$2" ref src
    ref="$(pin "$svc")"
    check_ref "$svc" "$ref"
    src="$(source_of "$svc")"

    step "$svc: $ref"
    printf '  источник: %s\n' "$src"

    if [ "$mode" != push ]; then
        # Рецепта у копии нет вовсе, и проверять локальной сборкой нечего:
        # «собрать» для неё означало бы скачать чужой образ себе в кэш, то есть
        # ровно ту проверку, которая ничего не доказывает.
        printf '  ✓ собирать нечего: это копия чужого образа, а не наш рецепт\n'
        printf '    Положить в реестр: tools/mirror-images.sh --опубликовать\n'
        return 0
    fi

    # imagetools собирает индекс ИЗ РЕЕСТРА ИСТОЧНИКА, а не из локального кэша:
    # у разработчика лежит только arm64, а прогону и ячейке нужен amd64.
    # Копируется индекс целиком, со всеми платформами, какие есть у автора, —
    # выбирать из них незачем: слои адресуются суммой, и лишние архитектуры
    # ничего не стоят ни прогону, ни ячейке.
    #
    # Источник закреплён суммой, поэтому «скопировали» и «скопировали то самое»
    # — одно утверждение, а не два: плывущий тег автора тут ни при чём.
    $MIRROR_DOCKER buildx imagetools create --tag "$ref" "$src" \
        || fail "не удалось скопировать $src в $ref (вход в реестр есть? нужен write:packages; источник ещё раздают?)"
    printf '  ✓ уехало: %s\n' "$ref"
}

# Тело ответа на stdin → токен или пустая строка. Своей функцией, потому что
# `json.load` на пустом вводе падает, а под `set -euo pipefail` упавший разбор
# убивал бы скрипт на молчании реестра — то есть ровно там, где он обязан
# отвечать «спросить не вышло» (задача 0241).
json_token() {
    python3 -c 'import json, sys
try:
    print(json.load(sys.stdin).get("token", "") or "")
except Exception:
    print("")'
}

# Спросить реестр про один тег. Печатает ПАРУ «код ответа» и «выдан ли токен»
# (код пуст — curl не ответил вовсе), тело кладёт в файл $3.
#
# Пара строкой, а не через глобальную переменную: функцию зовут из `$( )`, то есть
# из подоболочки, и присваивание оттуда не доезжает вовсе — под `set -u` это давало
# «unbound variable» и код 1, то есть ровно тот исход, который задача 0241 и разводит
# с «неполно». Тот же урок уже оплачен в ops/smoke-run.sh (задача 0151), и поймала
# его здесь своя же самопроверка, а не чтение.
#
# Пустой токен означает «спросить не вышло», а не «пакет приватный»: без токена
# реестр отвечает 401 на что угодно, и до 0241 это состояние приезжало к человеку
# советом менять видимость пакета, которого никто не спрашивал.
#
# Место одно на весь скрипт: адрес реестра и разбор ответа здесь, а in_registry
# и verify() спрашивают через него. Второе такое место разошлось бы с первым молча.
ask_manifest() {  # путь тег файл-ответа → «код токен-выдан»
    local path="$1" tag="$2" out="$3" try=1 token code token_ok=0
    while :; do
        : > "$out"
        token=""
        token="$($MIRROR_CURL -s \
            "https://ghcr.io/token?service=ghcr.io&scope=repository:${path}:pull" \
            | json_token)" || token=""
        code=""
        code="$($MIRROR_CURL -s -o "$out" -w '%{http_code}' \
            -H "Authorization: Bearer ${token}" \
            -H 'Accept: application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json' \
            "https://ghcr.io/v2/${path}/manifests/${tag}")" || code=""
        if [ -n "$token" ]; then
            token_ok=1
            case "$code" in 200|404|401|403) break ;; esac
        else
            token_ok=0
        fi
        [ "$try" -lt "$REGISTRY_ATTEMPTS" ] || break
        try=$(( try + 1 ))
        [ "$REGISTRY_RETRY_SLEEP" = 0 ] || sleep "$REGISTRY_RETRY_SLEEP"
    done
    printf '%s %s' "$code" "$token_ok"
}

# Лежит ли этот тег в реестре. Спрашиваем протоколом — по той же причине, что
# и verify(): docker при containerd-хранилище отвечает из локального индекса.
#
# Молчание реестра здесь читается как «образа нет» — так было и раньше, — но
# теперь это СКАЗАНО словами: по такому ответу `--опубликовать` пересоберёт
# собираемый образ под тем же тегом (цена названа у mirror_one ниже), и человек
# обязан видеть, что решение принято на неответе, а не на ответе.
in_registry() {  # адрес → 0, если лежит
    local ref="$1" path tag answer code
    path="$(path_of "$ref")"
    tag="$(tag_of "$ref")"
    answer="$(ask_manifest "$path" "$tag" /dev/null)"
    code="${answer%% *}"
    case "$code" in
        200) return 0 ;;
        404|401|403) return 1 ;;
    esac
    printf '  · спросить реестр про %s не вышло (ответ «%s») — считаем, что образа там нет\n' \
        "$ref" "${code:-нет ответа}"
    return 1
}

# Развилка одна на весь скрипт: есть источник — копируем, нет — собираем.
#
# И КЛАДЁМ ТОЛЬКО ТО, ЧЕГО НЕТ. Задача CI зовёт `--опубликовать`, когда зеркало
# неполно, — то есть при добавлении ЧЕТВЁРТОГО образа она перекладывала бы
# и три готовых. Для собираемых это не пустая трата, а подмена: тег остаётся
# тем же, а образ пересобирается заново, и цифровой отпечаток у него уже другой,
# — при том что про версию MinIO в ops/CLAUDE.md записано «бит в бит та,
# на которой прогон был зелёным». Заодно `--опубликовать`, набранный руками,
# перестаёт быть опасным действием.
mirror_one() {  # сервис режим(load|push)
    local svc="$1" mode="$2" ref
    ref="$(pin "$svc")"
    check_ref "$svc" "$ref"

    if [ "$mode" = push ] && [ -z "${MIRROR_FORCE:-}" ] && in_registry "$ref"; then
        step "$svc: $ref"
        printf '  ✓ уже в реестре — не перекладываем\n'
        printf '    Перезаписать тот же тег: MIRROR_FORCE=1 tools/mirror-images.sh --опубликовать\n'
        return 0
    fi

    if [ -n "$(source_of "$svc")" ]; then
        copy_one "$svc" "$mode"
    else
        build_one "$svc" "$mode"
    fi
}

verify() {
    local svc ref tag path answer code token_ok arch_rc bad=0 unknown=0
    # НЕ local: уборка висит на RETURN, а к тому моменту область видимости
    # функции уже закрыта — `set -u` свалился бы в самой уборке, поверх
    # настоящего ответа. Ровно на этом спотыкалась самопроверка ниже.
    TMPJSON="$(mktemp)"
    trap 'rm -f "$TMPJSON"' RETURN
    for svc in $SERVICES; do
        ref="$(pin "$svc")"
        check_ref "$svc" "$ref"
        tag="$(tag_of "$ref")"
        path="$(path_of "$ref")"
        step "$svc: $ref"

        # СПРАШИВАЕМ ПРОТОКОЛОМ, А НЕ DOCKER'ОМ, и это не педантичность.
        # `docker manifest inspect` и `docker buildx imagetools inspect` при
        # containerd-хранилище образов отвечают из ЛОКАЛЬНОГО индекса: образ,
        # собранный тут же (`--собрать`), выглядит у них как лежащий в реестре.
        # Проверено 26 сентября 2026 — оба показали полный индекс для тега,
        # который в реестр никогда не уезжал. То есть проверка «мимо кэша»,
        # сделанная docker'ом, повторила бы ровно ту ловушку, из-за которой
        # поломку не замечали двенадцать месяцев. (На мёртвом чужом теге они
        # в реестр всё же идут — локально его нет, — и этим можно обмануться.)
        #
        # Анонимный токен отвечает заодно на второй вопрос: тянется ли пакет
        # БЕЗ входа в реестр. Приватный означает токен у прогона, у
        # разработчика и в `.env` ячейки — три новых секрета вокруг образа,
        # в котором нет ничего нашего.
        answer="$(ask_manifest "$path" "$tag" "$TMPJSON")"
        code="${answer%% *}"
        token_ok="${answer##* }"

        # Токен спрашивается первым вопросом: без него реестр отвечает 401 на что
        # угодно, и прежняя редакция объявляла такой ответ приватным пакетом —
        # то есть посылала человека менять видимость того, про что не спросила.
        if [ "$token_ok" != 1 ]; then
            red "  ✗ анонимный токен не выдан — СПРОСИТЬ про ${ref} НЕ ВЫШЛО"
            red "      Это не «пакета нет» и не «пакет закрыт»: без токена реестр"
            red "      отвечает 401 на любой тег. Повторите прогон."
            unknown=1
            continue
        fi

        case "$code" in
            200)
                printf '  ✓ лежит в реестре и тянется без входа\n'
                set +e
                python3 - "$TMPJSON" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception as e:
    # Ответ есть, а годность по нему не установить. Это «спросить не вышло»,
    # а не «зеркало неполно»: собирать по нечитаемому ответу нечего.
    print(f"  ✗ ответ реестра не разобрать ({e}) — про архитектуры НЕ СПРОШЕНО")
    sys.exit(2)
# Слои подписей (attestation) идут платформой unknown/unknown — это не
# архитектура, и считать их за неё значит принять однорукий образ за годный.
real = [m for m in (d.get('manifests') or [])
        if (m.get('platform') or {}).get('architecture') not in (None, 'unknown')]
for m in real:
    p = m['platform']
    print(f"  ✓ {p.get('os')}/{p.get('architecture')} — {m.get('digest')}")
need = {'amd64', 'arm64'}
have = {(m['platform'] or {}).get('architecture') for m in real}
if not need <= have:
    print(f"  ✗ не хватает архитектур: {', '.join(sorted(need - have))} "
          f"(amd64 — прогон и ячейка, arm64 — разработка)")
    sys.exit(1)
PY
                arch_rc=$?
                set -e
                # Раньше здесь стоял `|| exit 1`: однорукий индекс убивал скрипт
                # на середине цикла, то есть про остальные теги не спрашивали
                # вовсе, а наружу уезжал тот же код, что у «зеркала неполно».
                case "$arch_rc" in
                    0) : ;;
                    1) bad=1 ;;
                    *) unknown=1 ;;
                esac
                ;;
            404)
                red "  ✗ в реестре нет: ${ref}"
                red "      Положить: tools/mirror-images.sh --опубликовать"
                bad=1 ;;
            401|403)
                # Анонимно эти два состояния неразличимы, и врать про них нельзя:
                # GHCR отвечает 403 и на приватный пакет, и на НЕСУЩЕСТВУЮЩИЙ
                # (404 приходит только там, где пакет есть и открыт). Проверено
                # 26 сентября 2026 на свежих postgres и liquibase: пакета ещё
                # не было вовсе, а прежняя редакция этой строки объявляла его
                # приватным — то есть посылала человека менять видимость того,
                # чего нет.
                red "  ✗ анонимно не тянется (ответ ${code}): пакета либо нет вовсе, либо он приватный"
                red "      Если ещё не кладён: tools/mirror-images.sh --опубликовать"
                red "      (в прогоне это делает задача CI «Зеркало образов» сама)."
                red "      Если приватный — токен понадобится прогону, разработчику"
                red "      и ячейке, то есть новый секрет в .env и строка"
                red "      в ops/config-guard.sh. Открыть: Packages →"
                red "      parts-platform/${svc} → Change visibility."
                bad=1 ;;
            "")
                red "  ✗ реестр НЕ ОТВЕТИЛ про ${ref} (попыток: ${REGISTRY_ATTEMPTS})"
                red "      Про зеркало не известно ничего — это не «зеркала нет»."
                unknown=1 ;;
            *)
                # 5xx, 429 и всё прочее: ответ есть, ответа по существу нет.
                red "  ✗ реестр ответил ${code} на ${ref} — СПРОСИТЬ НЕ ВЫШЛО"
                red "      Коды 200, 404 и 401/403 — ответ по существу, остальные нет."
                unknown=1 ;;
        esac
    done

    # «Неполно» сильнее «не спросили»: если хоть один тег отсутствует, зеркало
    # надо наполнять независимо от того, про остальные мы спросили или нет.
    if [ "$bad" = 1 ]; then return 1; fi
    if [ "$unknown" = 1 ]; then return 2; fi
    return 0
}

# --- проверка самого скрипта -------------------------------------------------
#
# Проверяются ОТКАЗЫ: скрипт кладёт образ в реестр, и молчаливое согласие
# положить его не туда или с плавающим тегом повторило бы ровно ту поломку,
# ради которой он написан. Сети, docker и реестра для этого не нужно.
selftest() {
    local bad=0 out rc tries want good one_arm src arch tag ref
    echo "Самопроверка tools/mirror-images.sh"
    # Каталог НЕ local: уборка висит на EXIT, а к тому моменту функция уже
    # вернулась — из её области видимости переменную не достать, и `set -u`
    # валит скрипт в самой уборке, поверх настоящего результата.
    tmp="$(mktemp -d)"
    # FAKE не local по той же причине, и пустой — чтобы уборка не упала
    # под `set -u`, когда до подделки дело не дошло.
    FAKE=""
    trap 'rm -rf "${tmp:-}"; rm -f "${FAKE:-}"' EXIT

    probe() {  # файл-фрагмент → rc, out
        set +e
        out="$(IMAGES_FILE="$1" bash "$0" --selftest-pins 2>&1)"; rc=$?
        set -e
    }

    probe "$IMAGES_FILE"
    if [ "$rc" = 0 ]; then
        printf '  ✓ настоящий ops/images.yml принимается\n'
    else
        red "  ✗ настоящий ops/images.yml не принимается:"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        bad=1
    fi

    sed 's/:RELEASE\.[^ ]*$/:latest/' "$IMAGES_FILE" > "$tmp/floating.yml"
    probe "$tmp/floating.yml"
    if [ "$rc" != 0 ] && printf '%s' "$out" | grep -q 'плавающий тег'; then
        printf '  ✓ плавающий тег отбит словами\n'
    else
        red "  ✗ плавающий тег обязан отбиваться: rc=$rc"
        bad=1
    fi

    sed 's#ghcr\.io/vladislav15937/parts-platform/#quay.io/minio/#' "$IMAGES_FILE" > "$tmp/foreign.yml"
    probe "$tmp/foreign.yml"
    if [ "$rc" != 0 ] && printf '%s' "$out" | grep -q 'чужой реестр'; then
        printf '  ✓ чужой реестр отбит словами\n'
    else
        red "  ✗ чужой реестр обязан отбиваться: rc=$rc"
        bad=1
    fi

    grep -v 'image:' "$IMAGES_FILE" > "$tmp/empty.yml"
    probe "$tmp/empty.yml"
    if [ "$rc" != 0 ] && printf '%s' "$out" | grep -q 'нет образа для сервиса'; then
        printf '  ✓ фрагмент без адреса отбит словами\n'
    else
        red "  ✗ фрагмент без адреса обязан отбиваться: rc=$rc"
        bad=1
    fi

    # Тег копии обязан быть назван суммой источника: иначе сменённый источник
    # не доедет до реестра вовсе — задача CI не копирует, пока тег там есть.
    sed 's/:16-alpine-721873c34ceb/:16-alpine/' "$IMAGES_FILE" > "$tmp/untagged.yml"
    probe "$tmp/untagged.yml"
    if [ "$rc" != 0 ] && printf '%s' "$out" | grep -q 'не назван суммой источника'; then
        printf '  ✓ тег копии, не названный суммой источника, отбит словами\n'
    else
        red "  ✗ тег копии обязан нести сумму источника: rc=$rc"
        bad=1
    fi

    # Источник, закреплённый тегом вместо суммы: `16-alpine` у автора плывёт,
    # и копия перестала бы быть повторяемой — молча.
    sed 's/@sha256:[0-9a-f]*$//' "$IMAGES_FILE" > "$tmp/loose.yml"
    probe "$tmp/loose.yml"
    if [ "$rc" != 0 ] && printf '%s' "$out" | grep -q 'закреплён тегом, а не суммой'; then
        printf '  ✓ источник без суммы отбит словами\n'
    else
        red "  ✗ источник обязан быть закреплён суммой: rc=$rc"
        bad=1
    fi

    # Рецепт нужен ровно тем, кого мы собираем: у копии его нет и быть не должно.
    local svc
    for svc in $SERVICES; do
        if [ -n "$(source_of "$svc")" ]; then
            printf '  ✓ %s копируется из источника — рецепт ему не нужен\n' "$svc"
        elif [ -f "$ROOT/tools/mirror/$svc.Dockerfile" ]; then
            printf '  ✓ рецепт на месте: tools/mirror/%s.Dockerfile\n' "$svc"
        else
            red "  ✗ нет tools/mirror/$svc.Dockerfile — собирать нечем"
            bad=1
        fi
    done

    # --- режимы, а не только разбор адресов (задача 0241) ---------------------
    #
    # До неё самопроверка целиком стояла на probe(): все шесть случаев выше зовут
    # `--selftest-pins`, то есть проверяют РАЗБОР АДРЕСОВ. Режимы `--проверить`,
    # `--собрать` и `--опубликовать` — то, чем скрипт кладёт образ в реестр и чем
    # прогон решает, наполнять ли зеркало, — не были покрыты ни одной подделкой;
    # собственный комментарий у `--selftest-pins` это и признавал.
    #
    # Сети, docker и реестра по-прежнему не нужно: реестр подменяется заглушкой
    # curl (MIRROR_CURL), docker — заглушкой, пишущей свой argv в журнал
    # (MIRROR_DOCKER), файлы выпуска берутся по file:// через MIRROR_RELEASES.
    # Поэтому проверяется не только код возврата, но и ПЕРЕБОР ОТПРАВЛЕННОГО:
    # «режим посчитан верно» и «в реестр ничего не ушло» — разные утверждения.

    says() { printf '%s' "$1" | grep -qF -- "$2"; }

    # Заглушка реестра: отвечает фикстурой из окружения и пишет каждый спрошенный
    # адрес в журнал — по нему видно, сколько раз спросили на неоднозначном ответе.
    cat > "$tmp/curl" <<'SH'
#!/bin/sh
out=/dev/null; url=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -w|-H) shift 2 ;;
    http://*|https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done
printf '%s\n' "$url" >> "$STUB_CURL_LOG"
case "$url" in
  */token*) printf '%s' "$STUB_TOKEN"; exit 0 ;;
esac
if [ "$STUB_CODE" = "молчит" ]; then exit 7; fi
printf '%s' "$STUB_BODY" > "$out"
printf '%s' "$STUB_CODE"
SH
    chmod +x "$tmp/curl"

    # Заглушка docker: пишет argv в журнал и отказывает там, где велено.
    cat > "$tmp/docker" <<'SH'
#!/bin/sh
printf '%s\n' "$*" >> "$STUB_DOCKER_LOG"
case "$*" in
  *imagetools*create*)
      if [ -n "${STUB_PUSH_FAIL:-}" ]; then exit 1; fi ;;
  *buildx*build*)
      if [ -n "${STUB_BUILD_FAIL:-}" ]; then exit 1; fi
      case "$*" in
        *--push*) if [ -n "${STUB_PUSH_FAIL:-}" ]; then exit 1; fi ;;
      esac ;;
  run*)
      ref=""
      for a in "$@"; do
        case "$a" in *:*) ref="$a" ;; esac
      done
      tag="${ref##*:}"
      if [ -n "${STUB_VERSION_BAD:-}" ]; then tag="НЕ-ТА-ВЕРСИЯ"; fi
      printf 'stub version %s\n' "$tag" ;;
esac
exit 0
SH
    chmod +x "$tmp/docker"

    # Файлы выпуска кладутся рядом и берутся по file://. Теги — из живого
    # ops/images.yml, а не вписаны сюда: вписанный однажды сменят, и фикстура
    # молча перестанет совпадать с рецептом (урок подделок по живому адресу).
    for svc in $SERVICES; do
        [ -z "$(source_of "$svc")" ] || continue
        ref="$(pin "$svc")"; tag="$(tag_of "$ref")"
        mkdir -p "$tmp/releases/$svc/releases/download/$tag"
        for arch in amd64 arm64; do
            printf 'stub binary %s %s\n' "$svc" "$arch" \
                > "$tmp/releases/$svc/releases/download/$tag/$svc.linux-$arch.$tag"
            printf '%s  %s\n' \
                "$(sha256_of "$tmp/releases/$svc/releases/download/$tag/$svc.linux-$arch.$tag")" \
                "$svc.linux-$arch.$tag" \
                > "$tmp/releases/$svc/releases/download/$tag/$svc.linux-$arch.$tag.sha256sum"
        done
    done
    cp -R "$tmp/releases" "$tmp/rel-gone"
    find "$tmp/rel-gone" -name '*.linux-amd64.*' ! -name '*.sha256sum' -exec rm -f {} +
    cp -R "$tmp/releases" "$tmp/rel-badsum"
    find "$tmp/rel-badsum" -name '*.sha256sum' | while read -r f; do
        printf '%s  %s\n' \
            '0000000000000000000000000000000000000000000000000000000000000000' \
            "$(basename "${f%.sha256sum}")" > "$f"
    done

    good='{"manifests":[{"platform":{"os":"linux","architecture":"amd64"},"digest":"sha256:a"},{"platform":{"os":"linux","architecture":"arm64"},"digest":"sha256:b"},{"platform":{"os":"unknown","architecture":"unknown"},"digest":"sha256:c"}]}'
    one_arm='{"manifests":[{"platform":{"os":"linux","architecture":"arm64"},"digest":"sha256:b"}]}'

    mode_probe() {   # режим [присваивания окружения…] → rc, out
        local mode="$1"; shift
        : > "$tmp/curl.log"; : > "$tmp/docker.log"
        set +e
        out="$(env MIRROR_SELFTEST_CHILD=1 \
            MIRROR_CURL="$tmp/curl" MIRROR_DOCKER="$tmp/docker" \
            MIRROR_RELEASES="file://$tmp/releases" MIRROR_RETRY_SLEEP=0 \
            STUB_CURL_LOG="$tmp/curl.log" STUB_DOCKER_LOG="$tmp/docker.log" \
            STUB_TOKEN='{"token":"t"}' STUB_CODE=200 STUB_BODY="$good" \
            "$@" bash "$SELF" "$mode" 2>&1)"
        rc=$?
        set -e
    }

    # --проверить: зеркало полно.
    mode_probe --проверить
    if [ "$rc" = 0 ] && says "$out" 'Зеркало на месте'; then
        printf '  ✓ --проверить: полное зеркало — код 0 и «Зеркало на месте»\n'
    else
        red "  ✗ полное зеркало обязано давать код 0: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # --проверить: тега нет. Чинится публикацией, и сказать это обязано словами.
    mode_probe --проверить STUB_CODE=404 STUB_BODY='{"errors":[{"code":"MANIFEST_UNKNOWN"}]}'
    if [ "$rc" = 1 ] && says "$out" 'в реестре нет' && says "$out" '--опубликовать'; then
        printf '  ✓ --проверить: пропавший тег — код 1 и названо, что положить\n'
    else
        red "  ✗ пропавший тег обязан давать код 1 со словами «в реестре нет»: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # --проверить: однорукий индекс. Образ есть, а прогон на нём не поднимется.
    mode_probe --проверить STUB_BODY="$one_arm"
    if [ "$rc" = 1 ] && says "$out" 'не хватает архитектур' && says "$out" 'amd64'; then
        printf '  ✓ --проверить: индекс без amd64 — код 1, архитектура названа\n'
    else
        red "  ✗ индекс без amd64 обязан давать код 1: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # --проверить: 5xx. ГЛАВНЫЙ СЛУЧАЙ ЗАДАЧИ 0241 — «спросить не вышло» имеет
    # свой код, и шаг CI по нему не идёт собирать зеркало.
    mode_probe --проверить STUB_CODE=503 STUB_BODY='<html>упал</html>'
    if [ "$rc" = 2 ] && says "$out" 'СПРОСИТЬ НЕ ВЫШЛО'; then
        printf '  ✓ --проверить: 5xx — свой код 2 и свои слова\n'
    else
        red "  ✗ 5xx обязан давать код 2 со словами «СПРОСИТЬ НЕ ВЫШЛО»: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi
    if [ "$rc" = 1 ]; then
        red "  ✗ молчание реестра выдано за «зеркало неполно» — прогон пойдёт"
        red "      наполнять зеркало по ответу, которого не было (дефект 0241)"
        bad=1
    fi

    # …и спрошено на нём было столько раз, сколько объявлено: рябь реестра
    # не должна красить прогон. Проверяется перебором отправленного.
    tries="$(grep -c '/manifests/' "$tmp/curl.log" || true)"
    want=$(( REGISTRY_ATTEMPTS * $(printf '%s\n' $SERVICES | grep -c .) ))
    if [ "$tries" = "$want" ]; then
        printf '  ✓ --проверить: неоднозначный ответ переспрошен %s раза на сервис\n' \
            "$REGISTRY_ATTEMPTS"
    else
        red "  ✗ на 5xx обязано быть $want запросов манифеста, а было $tries"
        bad=1
    fi

    # --проверить: curl не ответил вовсе.
    mode_probe --проверить STUB_CODE=молчит
    if [ "$rc" = 2 ] && says "$out" 'НЕ ОТВЕТИЛ'; then
        printf '  ✓ --проверить: реестр не ответил — код 2, а не «неполно»\n'
    else
        red "  ✗ молчание curl обязано давать код 2: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # --проверить: анонимный токен не выдан. Прежняя редакция объявляла такой
    # ответ приватным пакетом — то есть посылала человека менять видимость того,
    # про что она не спросила.
    mode_probe --проверить STUB_TOKEN='{}' STUB_CODE=401
    if [ "$rc" = 2 ] && says "$out" 'токен не выдан'; then
        printf '  ✓ --проверить: пустой токен — код 2, а не «пакет закрыт»\n'
    else
        red "  ✗ пустой токен обязан давать код 2 со словами про токен: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # --проверить: ответ 200, а тело нечитаемое. Годность не установлена.
    mode_probe --проверить STUB_BODY='это не json'
    if [ "$rc" = 2 ] && says "$out" 'не разобрать'; then
        printf '  ✓ --проверить: нечитаемый ответ — код 2 и сказано, что не разобрали\n'
    else
        red "  ✗ нечитаемое тело при 200 обязано давать код 2: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # --собрать: рецепт проверяется сборкой, и в реестр при этом не уходит ничего.
    mode_probe --собрать
    if [ "$rc" = 0 ] && says "$out" 'Собрано локально' && says "$out" 'sha256 сошлась' \
       && grep -q -- '--load' "$tmp/docker.log" \
       && ! grep -q -- '--push' "$tmp/docker.log"; then
        printf '  ✓ --собрать: собирает локально и в реестр ничего не кладёт\n'
    else
        red "  ✗ --собрать обязан собрать локально и не пушить: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        sed 's/^/      docker /' "$tmp/docker.log" >&2; bad=1
    fi

    # --собрать: бинарника выпуска нет. Выпуск сняли — собирать нечего.
    mode_probe --собрать MIRROR_RELEASES="file://$tmp/rel-gone"
    if [ "$rc" != 0 ] && says "$out" 'не скачался'; then
        printf '  ✓ --собрать: пропавший файл выпуска отбит словами\n'
    else
        red "  ✗ пропавший файл выпуска обязан отбиваться словами «не скачался»: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # --собрать: сумма не сошлась. СЕРДЦЕ РЕЖИМА: зеркало без сверки суммы — это
    # «мы положили что-то похожее», и молчаливое согласие здесь хуже отсутствия
    # зеркала вовсе.
    mode_probe --собрать MIRROR_RELEASES="file://$tmp/rel-badsum"
    if [ "$rc" != 0 ] && says "$out" 'sha256 не сошлась'; then
        printf '  ✓ --собрать: несошедшаяся sha256 отбита словами\n'
    else
        red "  ✗ несошедшаяся sha256 обязана отбиваться словами: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # --собрать: docker отказал на сборке.
    mode_probe --собрать STUB_BUILD_FAIL=1
    if [ "$rc" != 0 ] && says "$out" 'не собрался'; then
        printf '  ✓ --собрать: отказ сборки отбит словами\n'
    else
        red "  ✗ отказ docker на сборке обязан отбиваться словами: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # --собрать: версия в образе не та, что в адресе. Проверка существует ровно
    # затем, чтобы зеркало не отвечало про другие байты под нашим тегом.
    mode_probe --собрать STUB_VERSION_BAD=1
    if [ "$rc" != 0 ] && says "$out" 'не той версии'; then
        printf '  ✓ --собрать: версия в образе сверяется с тегом\n'
    else
        red "  ✗ несовпадение версии в образе обязано отбиваться словами: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # --опубликовать: тег уже в реестре. Перекладывать нельзя — у собираемого
    # образа тот же тег получил бы другой отпечаток, и проверяется это перебором
    # отправленного, а не кодом возврата.
    mode_probe --опубликовать
    if [ "$rc" = 0 ] && says "$out" 'уже в реестре' \
       && ! grep -q -- '--push' "$tmp/docker.log" \
       && ! grep -q 'imagetools' "$tmp/docker.log"; then
        printf '  ✓ --опубликовать: лежащее в реестре не перекладывается вовсе\n'
    else
        red "  ✗ при образе в реестре не должно уйти ни push, ни imagetools: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        sed 's/^/      docker /' "$tmp/docker.log" >&2; bad=1
    fi

    # --опубликовать: тега нет. Собираемые уезжают сборкой, копируемые —
    # imagetools'ом И с источником из x-mirror-sources, а не пересборкой.
    src="$(source_of postgres)"
    mode_probe --опубликовать STUB_CODE=404 STUB_BODY='{}'
    if [ "$rc" = 0 ] && says "$out" 'Зеркало обновлено' \
       && grep -q 'imagetools create' "$tmp/docker.log" \
       && grep -qF -- "$src" "$tmp/docker.log" \
       && grep -q -- '--push' "$tmp/docker.log"; then
        printf '  ✓ --опубликовать: собираемые пушатся, копируемые копируются из источника\n'
    else
        red "  ✗ публикация обязана и собрать, и скопировать из источника: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2
        sed 's/^/      docker /' "$tmp/docker.log" >&2; bad=1
    fi

    # --опубликовать: реестр не принял. Молчаливый успех здесь означал бы
    # «зеркало обновлено» при пустом реестре.
    mode_probe --опубликовать STUB_CODE=404 STUB_BODY='{}' STUB_PUSH_FAIL=1
    if [ "$rc" != 0 ] && says "$out" 'не удалось'; then
        printf '  ✓ --опубликовать: отказ реестра отбит словами\n'
    else
        red "  ✗ отказ публикации обязан отбиваться словами: rc=$rc"
        printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # ВОЗВРАТ ДЕФЕКТА. Копия, в которой «спросить не вышло» снова сведено
    # к «зеркало неполно», обязана ВАЛИТЬ эту самопроверку — иначе случай 5xx
    # выше не утверждает ничего. Ребёнку он выключен: подделка заводила бы
    # подделку себя (урок 0195). Копия лежит в tools/, а не в /tmp: скрипт
    # считает корень от своего каталога (урок 0151).
    if [ -z "${MIRROR_SELFTEST_CHILD:-}" ]; then
        FAKE="$ROOT/tools/.mirror-images-fake.sh"
        python3 - "$SELF" "$FAKE" <<'PY'
import sys
src, dst = sys.argv[1:3]
old = 'if [ "$unknown" = 1 ]; then return 2; fi'
new = 'if [ "$unknown" = 1 ]; then return 1; fi'
t = open(src, encoding="utf-8").read()
assert old in t, "подделка не легла — изменилось место: " + old
open(dst, "w", encoding="utf-8").write(t.replace(old, new, 1))
PY
        chmod +x "$FAKE"
        # Целость копии проверяется ДО запуска: красное от сломанного синтаксиса
        # говорило бы о подделке, а не о стороже (урок 0198).
        bash -n "$FAKE"
        set +e
        MIRROR_SELFTEST_CHILD=1 bash "$FAKE" --selftest >"$tmp/fake.log" 2>&1
        rc=$?
        set -e
        if [ "$rc" != 0 ] && grep -q 'выдано за «зеркало неполно»' "$tmp/fake.log"; then
            printf '  ✓ возврат дефекта: сведённые исходы валят самопроверку\n'
        else
            red "  ✗ подделка «спросить не вышло = неполно» обязана валить самопроверку"
            sed 's/^/      /' "$tmp/fake.log" >&2; bad=1
        fi
        rm -f "$FAKE"
        FAKE=""
    fi

    return $bad
}

case "${1:---проверить}" in
    --проверить)
        echo "Что лежит в зеркале (мимо локального кэша)"
        # Код читается, а не глотается `&&`/`||`: три исхода у этой проверки
        # (шапка файла), и шаг CI обязан различать «неполно» и «не спросили».
        set +e; verify; vrc=$?; set -e
        case "$vrc" in
            0) green "Зеркало на месте." ;;
            1) fail "Зеркало неполно — смотрите строки выше. Наполнить: tools/mirror-images.sh --опубликовать" ;;
            2) red "СПРОСИТЬ НЕ ВЫШЛО: про зеркало не известно ничего."
               red "      Это НЕ «зеркала нет»: собирать и публиковать по такому ответу нечего."
               red "      Повторите прогон; воспроизвелось — смотрите сеть и ghcr.io."
               exit 2 ;;
            *) red "Проверка ответила незнакомым кодом ${vrc} — считаем, что СПРОСИТЬ НЕ ВЫШЛО."
               red "      Это НЕ «зеркало неполно»: наполнять по такому ответу нечего."
               exit 2 ;;
        esac
        ;;
    --собрать)
        for s in $SERVICES; do mirror_one "$s" load; done
        green "Собрано локально. В реестр это не уехало — для этого --опубликовать."
        ;;
    --опубликовать)
        for s in $SERVICES; do mirror_one "$s" push; done
        green "Зеркало обновлено."
        ;;
    # Служебный режим самопроверки: только разбор адресов, без сети и docker.
    --selftest-pins)
        for s in $SERVICES; do ref="$(pin "$s")"; check_ref "$s" "$ref"; done
        ;;
    --selftest)
        selftest && green "Скрипт проверен." || fail "Самопроверка не прошла."
        ;;
    *)
        fail "не знаю режима «${1}». Есть: --проверить, --собрать, --опубликовать, --selftest"
        ;;
esac
