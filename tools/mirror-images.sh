#!/usr/bin/env bash
# Своё зеркало образов хранилища в нашем GHCR.
#
#   tools/mirror-images.sh --проверить     что лежит в зеркале — мимо кэша
#   tools/mirror-images.sh --собрать       собрать локально, без реестра
#   tools/mirror-images.sh --опубликовать  собрать и положить в реестр
#   tools/mirror-images.sh --selftest      проверить сам скрипт на подделках
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
set -euo pipefail

ROOT="${MIRROR_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
IMAGES_FILE="${IMAGES_FILE:-$ROOT/ops/images.yml}"
# База выпусков. Переопределяется только самопроверкой — ей в сеть не надо.
RELEASES="${MIRROR_RELEASES:-https://github.com/minio}"
BUILDER="${MIRROR_BUILDER:-partsflow-mirror}"
PLATFORMS="${MIRROR_PLATFORMS:-linux/amd64,linux/arm64}"
SERVICES="minio mc"

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

tag_of()  { printf '%s' "${1##*:}"; }
path_of() { local p="${1%:*}"; printf '%s' "${p#ghcr.io/}"; }

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Зеркало обязано быть нашим и закреплённым. Проверяется здесь, а не только
# сторожем: скрипт кладёт образ в реестр, и положить его по чужому адресу
# или с плавающим тегом — та же поломка, из-за которой всё началось.
check_ref() {  # адрес
    local ref="$1" tag
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
    check_ref "$ref"
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
        docker buildx inspect "$BUILDER" >/dev/null 2>&1 \
            || docker buildx create --name "$BUILDER" --driver docker-container >/dev/null
        docker buildx build --builder "$BUILDER" --platform "$PLATFORMS" \
            -f "$ROOT/tools/mirror/$svc.Dockerfile" \
            --build-arg "VERSION=$tag" \
            -t "$ref" --push "$dir" \
            || fail "не удалось положить $ref в реестр (вход есть? нужен write:packages)"
        printf '  ✓ уехало: %s (%s)\n' "$ref" "$PLATFORMS"
    else
        # Локально — своя архитектура: многоархитектурный образ в кэш
        # не загрузить, а проверить рецепт надо запуском, а не сборкой.
        docker buildx build -f "$ROOT/tools/mirror/$svc.Dockerfile" \
            --build-arg "VERSION=$tag" \
            -t "$ref" --load "$dir" \
            || fail "образ $svc не собрался"
        printf '  ✓ собран локально: %s\n' "$ref"
        docker run --rm "$ref" --version | sed 's/^/      /'
        docker run --rm "$ref" --version | grep -q "$tag" \
            || fail "$svc в образе не той версии: в адресе $tag, а бинарник говорит другое"
        printf '  ✓ версия в образе совпадает с тегом: %s\n' "$tag"
    fi
}

verify() {
    local svc ref tag path token code bad=0
    # НЕ local: уборка висит на RETURN, а к тому моменту область видимости
    # функции уже закрыта — `set -u` свалился бы в самой уборке, поверх
    # настоящего ответа. Ровно на этом спотыкалась самопроверка ниже.
    TMPJSON="$(mktemp)"
    trap 'rm -f "$TMPJSON"' RETURN
    for svc in $SERVICES; do
        ref="$(pin "$svc")"
        check_ref "$ref"
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
        token="$(curl -s "https://ghcr.io/token?service=ghcr.io&scope=repository:${path}:pull" \
            | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token",""))')"
        code="$(curl -s -o "$TMPJSON" -w '%{http_code}' \
            -H "Authorization: Bearer ${token}" \
            -H 'Accept: application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json' \
            "https://ghcr.io/v2/${path}/manifests/${tag}")"
        case "$code" in
            200)
                printf '  ✓ лежит в реестре и тянется без входа\n'
                python3 - "$TMPJSON" <<'PY' || exit 1
import json, sys
d = json.load(open(sys.argv[1]))
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
                ;;
            404)
                red "  ✗ в реестре нет: ${ref}"
                red "      Положить: tools/mirror-images.sh --опубликовать"
                bad=1 ;;
            401|403)
                red "  ✗ анонимно не тянется (ответ ${code}) — пакет приватный"
                red "      Тогда токен понадобится прогону, разработчику и ячейке,"
                red "      то есть новый секрет в .env (и строка в ops/config-guard.sh)."
                red "      Публичность: Packages → parts-platform/${svc} → Change visibility."
                bad=1 ;;
            *)
                red "  ✗ реестр ответил ${code} на ${ref}"
                bad=1 ;;
        esac
    done
    return $bad
}

# --- проверка самого скрипта -------------------------------------------------
#
# Проверяются ОТКАЗЫ: скрипт кладёт образ в реестр, и молчаливое согласие
# положить его не туда или с плавающим тегом повторило бы ровно ту поломку,
# ради которой он написан. Сети, docker и реестра для этого не нужно.
selftest() {
    local bad=0 out rc
    echo "Самопроверка tools/mirror-images.sh"
    # Каталог НЕ local: уборка висит на EXIT, а к тому моменту функция уже
    # вернулась — из её области видимости переменную не достать, и `set -u`
    # валит скрипт в самой уборке, поверх настоящего результата.
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT

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

    local svc
    for svc in $SERVICES; do
        if [ -f "$ROOT/tools/mirror/$svc.Dockerfile" ]; then
            printf '  ✓ рецепт на месте: tools/mirror/%s.Dockerfile\n' "$svc"
        else
            red "  ✗ нет tools/mirror/$svc.Dockerfile — собирать нечем"
            bad=1
        fi
    done

    return $bad
}

case "${1:---проверить}" in
    --проверить)
        echo "Что лежит в зеркале (мимо локального кэша)"
        verify && green "Зеркало на месте." || fail "Зеркало неполно — смотрите строки выше."
        ;;
    --собрать)
        for s in $SERVICES; do build_one "$s" load; done
        green "Собрано локально. В реестр это не уехало — для этого --опубликовать."
        ;;
    --опубликовать)
        for s in $SERVICES; do build_one "$s" push; done
        green "Зеркало обновлено."
        ;;
    # Служебный режим самопроверки: только разбор адресов, без сети и docker.
    --selftest-pins)
        for s in $SERVICES; do ref="$(pin "$s")"; check_ref "$ref"; done
        ;;
    --selftest)
        selftest && green "Скрипт проверен." || fail "Самопроверка не прошла."
        ;;
    *)
        fail "не знаю режима «${1}». Есть: --проверить, --собрать, --опубликовать, --selftest"
        ;;
esac
