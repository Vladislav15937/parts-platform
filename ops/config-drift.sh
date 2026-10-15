#!/usr/bin/env bash
# Настройки на машине — те самые, что лежат в выложенном коммите, поимённо.
#
#   ops/config-drift.sh                        # сверить ops/ и docker-compose.prod.yml с манифестом
#   ops/config-drift.sh --write [--repo DIR] [--commit SHA] [--out ФАЙЛ]
#                                              # снять манифест С КОММИТА (кнопка раскатки, ручной путь)
#   ops/config-drift.sh --selftest             # проверить самого сторожа на подделках
#
# ЗАЧЕМ ОН ЕСТЬ (задача 0215). Требование владельца от 28 сентября 2026:
# «всё что есть в ветке которую я деплою на стенд появлялось на стенде,
# а не работало на старых настройках». Доставка настроек работает (0111), а
# ответа «та ли редакция лежит на ячейке» не было вовсе: ни один сторож
# версии каталога `ops/` не считал, а единственная похожая проверка —
# `git rev-parse HEAD` из ручного шага 0 — на ячейке, которую выкладывают
# кнопкой, не может пройти по построению (кнопка кладёт `ops/` rsync'ом и git
# на машине не трогает). 28 сентября на боевой ячейке расхождение простояло
# девять суток и нашлось тем, что владелец прочёл файлы глазами.
#
# ТОЧКА ОТСЧЁТА — ВЫЛОЖЕННЫЙ SHA, А НЕ origin/main. Отставание ячейки от
# `main` ЗАКОННО: выкладка кладёт `ops/` тем SHA, которым собран образ, —
# значит любой файл, уехавший в `main` после последней выкладки, расходится
# с `main` сам по себе. Признак «входит ли машина в origin/main» уже дважды
# дал неверный вердикт (`ops/CLAUDE.md`, «норму нельзя записывать
# утверждением, которого не измеряли»). Поэтому имя коммита берётся у самой
# машины — `APP_IMAGE_TAG` из `.env`, который пишет `ops/deploy.sh`, — а при
# выкладке, пока тег ещё прежний, выкладка называет его сама
# (`CONFIG_DRIFT_EXPECT`).
#
# ЧЕМ СВЕРЯЕМ — МАНИФЕСТОМ СУММ, А НЕ ПРОГОНОМ СО СТОРОНЫ КОММИТА (решение
# исполнителя, цена названа). Ячейке по построению не нужны ни клон, ни сеть
# (`docs/deploy-button.md`), значит источник правды обязан приехать вместе
# с настройками: файл `ops/config-manifest.sha256` снимается С КОММИТА
# (`--write` читает объекты git, а не рабочее дерево — иначе правка дерева
# попала бы и в «эталон») и едет тем же rsync'ом. Сверка идёт на машине без
# сети и без репозитория.
#   Чем платим: манифест — ещё один файл, который кто-то обязан положить.
#   Не положили — сверка отвечает «НЕ ВЫПОЛНЕНА» (код 2), а не «сходится».
#   Прогон со стороны коммита (CI заходит по ssh и сверяет сам) не требует
#   файла, но требует доступа с раннера и работает только когда кто-то
#   нажал кнопку: дежурный на машине ответа не получил бы. А манифест
#   отвечает и в три ночи, и после ручной правки, и после обрыва выкладки.
#   Что манифест НЕ доказывает: он честен, пока его снимали с коммита и
#   положили вместе с файлами. Подменить файл И манифест разом он не поймает —
#   это защита от ошибки, а не от злоумышленника с доступом к машине.
#
# СОСТОЯНИЕ ОТЛИЧАЕТСЯ ОТ НАСТРОЙКИ ПРИЗНАКОМ В САМОМ ФАЙЛЕ, А НЕ ИМЕНЕМ.
# Файл, который пишет на месте скрипт ячейки (сегодня один —
# `ops/active-build.caddy`, его пишет `ops/switch-build.sh`), несёт в шапке
# (первые 15 строк) строку, начинающуюся с `# STATE-FILE`. Манифест такие
# файлы не включает, а лишний файл с признаком сверка не называет. Признак
# стоит в файле, поэтому переживает перезапись (`write_active` пишет его
# сам, это проверяет самопроверка) и работает для файла, которого сегодня
# нет. Завтрашний файл состояния получит исключение, поставив одну строку, а
# не правку списка в двух местах. Ложная тревога дороже пропуска: сверка,
# краснеющая на каждом переключении трафика, была бы отключена в первый день.
# Чего нет в манифесте и не отмечено признаком (`ops/secrets/`, чужие
# файлы), — «лишний файл»: жёлтая строка, код возврата не меняет (следующий
# rsync с `--delete` его уберёт, а смонтированные файлы известны compose).
#
# ТРИ ИСХОДА, РАЗЛИЧИМЫЕ КОДОМ (образец — tools/head-green.sh): 0 — сходится;
# 1 — РАЗОШЛОСЬ (названо поимённо, с суммой «должен» и «оказался», либо
# настройки от другого коммита, чем образ); 2 — СПРОСИТЬ НЕ УДАЛОСЬ (нет
# манифеста, нет чем считать сумму, нечем назвать выложенный SHA). «Не
# удалось спросить» не есть «сходится». Ячейка между подъёмом и первой
# выкладкой манифеста не имеет — это законное «не спрошено», а не красное;
# после первой выкладки кнопкой он есть всегда.
#
# ЧИТАЮЩИЙ: ничего не пишет, не трогает контейнеры. Привести дерево к
# репозиторию — не его дело (`git checkout`, `git pull`, `git stash` на
# ячейке запрещены: файл состояния держит имя сборки под трафиком).
#
# ГДЕ ЕГО ЗОВУТ. `ops/config-guard.sh` — шаг 0а `ops/deploy.sh`, второй шаг
# «Как поднять» и шаг CI: место, куда человек и так заходит (вывод 0198:
# скрипт, который никто не зовёт, повторяет «спросить нечем»). Манифест
# снимает шаг кнопки «Манифест настроек» (`--write`) перед rsync'ом.
#
#   ENV_FILE=…               файл окружения ячейки (умолчание .env)
#   CONFIG_DRIFT_EXPECT=…    какой SHA ожидать вместо APP_IMAGE_TAG (выкладка:
#                            тег в .env ещё прежний, привозится новый)
#   CONFIG_DRIFT_MANIFEST=…  путь манифеста (умолчание ops/config-manifest.sha256)
set -uo pipefail

SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
# Корень переопределяют только самопроверка и config-guard.sh (его корень
# подменяется своей самопроверкой).
ROOT="${CONFIG_DRIFT_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
ENV_FILE="${ENV_FILE:-.env}"
MANIFEST_REL="ops/config-manifest.sha256"
MANIFEST="${CONFIG_DRIFT_MANIFEST:-$MANIFEST_REL}"

red()    { printf '\033[1;31m%s\033[0m\n' "$1" >&2; }
green()  { printf '\033[1;32m%s\033[0m\n' "$1"; }
yellow() { printf '\033[1;33m%s\033[0m\n' "$1"; }

TMP=$(mktemp -d) || exit 2
trap 'rm -rf "$TMP"' EXIT

# Два имени: на ячейке sha256sum, у разработчика на macOS shasum. Не нашлось
# ни одного — «спросить не удалось», а не «сходится».
hash_stdin() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 | cut -d' ' -f1
    else
        return 1
    fi
}

# ПРИЗНАК СОСТОЯНИЯ. Единственное место, где он описан: и снятие манифеста,
# и сверка спрашивают здесь. Строка 'STATE-FILE' в шапке, а не в любом месте
# файла: упоминание слова в комментарии скрипта состоянием его не делает.
is_state() {  # файл
    head -n 15 "$1" 2>/dev/null | grep -aq '^# STATE-FILE'
}

env_value() {
    local line
    line=$(grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -n 1) || true
    line=${line#*=}
    line=${line%\"}; line=${line#\"}
    line=${line%\'}; line=${line#\'}
    printf '%s' "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

short() { printf '%.12s' "$1"; }

# ───────────────────────── снять манифест с коммита ─────────────────────────
write_manifest() {  # репозиторий, коммит, куда
    local repo="$1" rev="$2" out="$3" commit path sum count=0
    commit=$(git -C "$repo" rev-parse --verify "$rev^{commit}" 2>/dev/null) \
        || { red "Нет коммита $rev в $repo — манифест не снят"; return 2; }
    : | hash_stdin >/dev/null || { red "Нечем считать сумму (ни sha256sum, ни shasum) — манифест не снят"; return 2; }
    {
        echo "# config-manifest v1"
        echo "# commit $commit"
        # Объекты git, а не рабочее дерево: «эталон» не должен зависеть от того,
        # что лежит на диске у того, кто его снимает.
        while IFS= read -r -d '' path; do
            [ "$path" = "$MANIFEST_REL" ] && continue
            git -C "$repo" cat-file blob "$commit:$path" > "$TMP/blob" 2>/dev/null \
                || { echo "ОШИБКА: не прочитать $path" >&2; exit 1; }
            is_state "$TMP/blob" && continue
            sum=$(hash_stdin < "$TMP/blob") || exit 1
            printf '%s  %s\n' "$sum" "$path"
        done < <(git -C "$repo" ls-tree -r -z --name-only "$commit" -- ops docker-compose.prod.yml)
    } > "$TMP/manifest" || { red "Манифест не снят: чтение объектов git оборвалось"; return 2; }
    count=$(grep -vc '^#' "$TMP/manifest" || true)
    [ "${count:-0}" -gt 0 ] || { red "В коммите $(short "$commit") нет ни ops/, ни docker-compose.prod.yml — манифест пуст, не пишу"; return 2; }
    mkdir -p "$(dirname "$out")"
    # Атомарно: оборванная запись не должна оставить половину манифеста,
    # которую сверка приняла бы за целый.
    cp "$TMP/manifest" "$out.tmp.$$" && mv "$out.tmp.$$" "$out" \
        || { red "Не записать $out"; return 2; }
    printf 'Манифест настроек: %s файлов с коммита %s → %s\n' "$count" "$(short "$commit")" "$out"
}

# ───────────────────────────────── сверка ───────────────────────────────────
check() {
    cd "$ROOT" || return 2
    local bad=0 unknown=0 commit expected line want path have n=0 nbad=0

    if [ ! -f "$MANIFEST" ]; then
        yellow "  ! СВЕРКА НЕ ВЫПОЛНЕНА: нет $MANIFEST — неизвестно, та ли редакция настроек лежит на ячейке"
        yellow "    Манифест кладёт выкладка кнопкой; у ячейки, которой кнопкой ещё не выкладывали, его нет."
        yellow "    Снять с коммита вручную: ops/config-drift.sh --write --commit <SHA> (из клона репозитория)"
        return 2
    fi
    commit=$(sed -n 's/^# commit //p' "$MANIFEST" | head -n 1)
    case "$commit" in
        *[!0-9a-f]*|"") yellow "  ! СВЕРКА НЕ ВЫПОЛНЕНА: в $MANIFEST нет строки «# commit <SHA>» — манифест не читается"; return 2 ;;
    esac
    : | hash_stdin >/dev/null || { yellow "  ! СВЕРКА НЕ ВЫПОЛНЕНА: нечем считать сумму (ни sha256sum, ни shasum)"; return 2; }

    # Какой коммит выложен. НЕ origin/main: отставание от него законно, и
    # признак через него уже дважды врал.
    expected="${CONFIG_DRIFT_EXPECT:-$(env_value APP_IMAGE_TAG)}"
    if [ -z "$expected" ]; then
        yellow "  ! выложенный SHA не назван: в $ENV_FILE нет APP_IMAGE_TAG — сверить манифест с образом нечем"
        unknown=1
    elif [ "$expected" != "$commit" ]; then
        red "  ✗ настройки сняты с коммита $(short "$commit"), а образ выложен с $(short "$expected") — это настройки ДРУГОЙ версии"
        bad=1
    fi

    : > "$TMP/listed"
    while IFS= read -r line; do
        case "$line" in ''|'#'*) continue ;; esac
        want=${line%%  *}
        path=${line#*  }
        printf '%s\n' "$path" >> "$TMP/listed"
        n=$((n + 1))
        if [ -d "$path" ]; then
            red "  ✗ $path — КАТАЛОГ вместо файла"
            nbad=$((nbad + 1)); continue
        elif [ ! -f "$path" ]; then
            red "  ✗ $path — нет на машине (должен быть: ${want:0:12})"
            nbad=$((nbad + 1)); continue
        fi
        have=$(hash_stdin < "$path") || { unknown=1; continue; }
        if [ "$have" != "$want" ]; then
            red "  ✗ $path — РАЗОШЁЛСЯ: должен быть ${want:0:12}, оказался ${have:0:12}"
            nbad=$((nbad + 1))
        fi
    done < "$MANIFEST"
    [ "$nbad" = 0 ] || bad=1

    # Лишние файлы: известного манифесту нет, и признака состояния нет.
    local extra=0
    if [ -d ops ]; then
        while IFS= read -r path; do
            [ "$path" = "$MANIFEST_REL" ] && continue
            grep -Fxq -- "$path" "$TMP/listed" && continue
            is_state "$path" && continue
            yellow "  ! лишний файл $path — не из выкладки $(short "$commit") (ops/secrets/ не считается)"
            extra=$((extra + 1))
        done < <(find ops -type f ! -path 'ops/secrets/*' | LC_ALL=C sort)
    fi

    if [ "$bad" = 0 ] && [ "$unknown" = 0 ]; then
        printf '  ✓ %s файлов совпадают с коммитом %s\n' "$n" "$(short "$commit")"
        return 0
    fi
    if [ "$bad" != 0 ]; then
        local named="не назван"
        [ -z "$expected" ] || named=$(short "$expected")
        red "  Разошлись: файлов $nbad из $n, выложенный коммит: $named"
        return 1
    fi
    yellow "  ! СВЕРКА НЕ ВЫПОЛНЕНА до конца: файлы сходятся, но проверить всё не удалось"
    return 2
}

# ─────────────────────────────── самопроверка ───────────────────────────────
#
# Сторож, не краснеющий на дефекте, хуже отсутствующего. Настоящий репозиторий
# с настоящей историей и «машина» без .git: на словах установить, какую
# редакцию файла выберет сверка, нельзя.
selftest() {
    local bad=0 out rc R M other c1 c2 fake
    echo "Самопроверка ops/config-drift.sh"
    R="$TMP/repo"; M="$TMP/machine"
    mkdir -p "$R/ops" "$M"
    git -C "$R" init -q . 2>/dev/null || { red "  ✗ git init не удался"; exit 1; }
    git -C "$R" config user.email t@example.com; git -C "$R" config user.name t
    printf 'echo a\n' > "$R/ops/a.sh"
    printf 'groups: []\n' > "$R/ops/alerts.yml"
    printf 'services: {}\n' > "$R/docker-compose.prod.yml"
    { echo '# STATE-FILE: ячейка-состояние (selftest)'; echo 'reverse_proxy app-blue:8080'; } > "$R/ops/active-build.caddy"
    git -C "$R" add -A; git -C "$R" commit -q -m c1
    c1=$(git -C "$R" rev-parse HEAD)

    # Машина — распакованный коммит БЕЗ .git, как после rsync кнопки.
    git -C "$R" archive "$c1" | tar -x -C "$M"
    bash "$SELF" --write --repo "$R" --commit "$c1" --out "$M/ops/config-manifest.sha256" >/dev/null \
        || { red "  ✗ манифест не снялся"; exit 1; }

    probe() {  # корень, ожидаемый SHA ('-' — не задавать)
        if [ "$2" = "-" ]; then
            out=$(cd "$1" && env -u CONFIG_DRIFT_EXPECT CONFIG_DRIFT_ROOT="$1" ENV_FILE="$1/нет.env" bash "${PROBE_SCRIPT:-$SELF}" 2>&1); rc=$?
        else
            out=$(cd "$1" && CONFIG_DRIFT_EXPECT="$2" CONFIG_DRIFT_ROOT="$1" ENV_FILE="$1/нет.env" bash "${PROBE_SCRIPT:-$SELF}" 2>&1); rc=$?
        fi
    }

    probe "$M" "$c1"
    if [ $rc = 0 ] && ! grep -q 'a.sh\|alerts.yml' <<< "$out"; then
        printf '  ✓ ячейка с тем же коммитом сходится молча (без .git, без сети)\n'
    else
        red "  ✗ исправная ячейка обязана проходить (код $rc)"; printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # Подделка 1: ОДИН файл подменён мимо выкладки — назван именно он, чужие нет.
    cp "$M/ops/alerts.yml" "$TMP/alerts.keep"
    printf '# правка руками\n' >> "$M/ops/alerts.yml"
    probe "$M" "$c1"
    if [ $rc = 1 ] && grep -q 'ops/alerts.yml.*РАЗОШЁЛСЯ.*должен.*оказался' <<< "$out" && ! grep -q 'ops/a.sh' <<< "$out"; then
        printf '  ✓ подменённый ops/alerts.yml — красное, назван один он, с суммами «должен» и «оказался»\n'
    else
        red "  ✗ подмена одного файла обязана краснеть и называть его одного (код $rc)"; printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi
    cp "$TMP/alerts.keep" "$M/ops/alerts.yml"
    probe "$M" "$c1"
    [ $rc = 0 ] && printf '  ✓ вернули файл — снова молчит\n' || { red "  ✗ после возврата файла сверка обязана молчать (код $rc)"; bad=1; }

    # Подделка 2: файл пропал / стал каталогом.
    rm -f "$M/ops/a.sh"
    probe "$M" "$c1"
    if [ $rc = 1 ] && grep -q 'ops/a.sh.*нет на машине' <<< "$out"; then
        printf '  ✓ пропавший ops/a.sh — красное, назван\n'
    else
        red "  ✗ пропавший файл обязан краснеть (код $rc)"; bad=1
    fi
    mkdir "$M/ops/a.sh"
    probe "$M" "$c1"
    if [ $rc = 1 ] && grep -q 'ops/a.sh.*КАТАЛОГ' <<< "$out"; then
        printf '  ✓ каталог на месте файла — красное\n'
    else
        red "  ✗ каталог вместо файла обязан краснеть (код $rc)"; bad=1
    fi
    rmdir "$M/ops/a.sh"; git -C "$R" show "$c1:ops/a.sh" > "$M/ops/a.sh"

    # Случай, на котором ломались прежние признаки: переключение трафика.
    # Пишет настоящая write_active из ops/switch-build.sh, а не её копия в
    # тексте самопроверки: переписав функцию без признака, сверка покраснела бы
    # на каждом переключении.
    # Через файл, а не `source <(…)`: bash 3.2 с macOS подстановку процесса не сорсит.
    sed -n '/^write_active() {/,/^}/p' "$ROOT/ops/switch-build.sh" > "$TMP/write_active.sh"
    ACTIVE_FILE="$M/ops/active-build.caddy" bash -c 'source "$1"; write_active app-green' _ "$TMP/write_active.sh"
    probe "$M" "$c1"
    if [ $rc = 0 ] && ! grep -q 'active-build' <<< "$out" && grep -q 'app-green' "$M/ops/active-build.caddy"; then
        printf '  ✓ переключение трафика (настоящий write_active) — молчит: файл состояния расходиться обязан\n'
    else
        red "  ✗ перезапись файла состояния переключателем не должна ни краснеть, ни упоминаться (код $rc)"; printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi
    # Файл состояния со старой шапкой (до признака) — жёлтая строка, не красное.
    printf '# Активная сборка ячейки.\nreverse_proxy app-green:8080\n' > "$M/ops/active-build.caddy"
    probe "$M" "$c1"
    if [ $rc = 0 ] && grep -q 'лишний файл ops/active-build.caddy' <<< "$out"; then
        printf '  ✓ файл состояния без признака — жёлтая строка, код возврата прежний\n'
    else
        red "  ✗ лишний файл не должен краснить, но обязан называться (код $rc)"; bad=1
    fi
    { echo '# STATE-FILE: ячейка-состояние (selftest)'; echo 'reverse_proxy app-blue:8080'; } > "$M/ops/active-build.caddy"

    # Отставание от main законно: в репозитории уехал следующий коммит,
    # машина стоит на выложенном — сходится. Именно на этом ломался признак
    # «входит ли в origin/main».
    printf 'groups: [x]\n' > "$R/ops/alerts.yml"; git -C "$R" commit -qam c2
    c2=$(git -C "$R" rev-parse HEAD)
    probe "$M" "$c1"
    [ $rc = 0 ] && printf '  ✓ main ушёл вперёд (%s), ячейка на выложенном — сходится\n' "$(short "$c2")" \
        || { red "  ✗ отставание от main законно и краснить не должно (код $rc)"; bad=1; }
    # А смешанное дерево (точечная правка файлом из ДРУГОГО коммита) названо.
    git -C "$R" show "$c2:ops/alerts.yml" > "$M/ops/alerts.yml"
    probe "$M" "$c1"
    [ $rc = 1 ] && grep -q 'ops/alerts.yml' <<< "$out" \
        && printf '  ✓ файл из чужого коммита (смешанное дерево) — назван\n' \
        || { red "  ✗ файл другого коммита обязан называться (код $rc)"; bad=1; }
    git -C "$R" show "$c1:ops/alerts.yml" > "$M/ops/alerts.yml"
    # Точечная правка файлом ВЫЛОЖЕННОГО коммита (`git show <SHA>:файл > файл`) — норма.
    probe "$M" "$c1"
    [ $rc = 0 ] || { red "  ✗ точечная правка выложенной редакцией не должна краснить (код $rc)"; bad=1; }

    # Образ другой версии, чем настройки.
    probe "$M" "$c2"
    if [ $rc = 1 ] && grep -q 'настройки сняты с коммита.*ДРУГОЙ версии' <<< "$out"; then
        printf '  ✓ манифест от одного коммита, образ от другого — красное словами\n'
    else
        red "  ✗ расхождение манифеста и образа обязано краснеть (код $rc)"; bad=1
    fi

    # Три исхода различимы: «спросить не удалось» — не «сходится».
    probe "$M" "-"
    if [ $rc = 2 ] && grep -q 'НЕ ВЫПОЛНЕНА' <<< "$out"; then
        printf '  ✓ выложенный SHA не назван — код 2 «не спрошено», а не 0\n'
    else
        red "  ✗ без названного SHA обязан быть код 2 (вышел $rc)"; bad=1
    fi
    mv "$M/ops/config-manifest.sha256" "$TMP/m.keep"
    probe "$M" "$c1"
    if [ $rc = 2 ] && grep -q 'НЕ ВЫПОЛНЕНА.*нет ops/config-manifest' <<< "$out"; then
        printf '  ✓ манифеста нет — код 2 «не спрошено», а не 0\n'
    else
        red "  ✗ без манифеста обязан быть код 2 (вышел $rc)"; bad=1
    fi
    mv "$TMP/m.keep" "$M/ops/config-manifest.sha256"

    # Соглашения репозитория: признак стоит там, где на него рассчитывают.
    if is_state "$ROOT/ops/active-build.caddy"; then
        printf '  ✓ настоящий ops/active-build.caddy несёт признак состояния\n'
    else
        red "  ✗ в ops/active-build.caddy нет «# STATE-FILE» в шапке — сверка краснела бы на каждом переключении"; bad=1
    fi
    if [ -f "$ROOT/.github/workflows/deploy.yml" ]; then
        # Всё, что кнопка исключает из rsync внутри ops/ (кроме secrets/),
        # обязано быть состоянием по признаку: иначе список исключений кнопки
        # и признак разойдутся молча.
        local ex missing=""
        for ex in $(grep -o "exclude '[^']*'" "$ROOT/.github/workflows/deploy.yml" | sed "s/exclude '//; s/'$//" | sort -u); do
            case "$ex" in secrets/|secrets) continue ;; esac
            if [ -f "$ROOT/ops/$ex" ] && ! is_state "$ROOT/ops/$ex"; then missing="$missing ops/$ex"; fi
        done
        if [ -z "$missing" ]; then
            printf '  ✓ всё, что кнопка не привозит, несёт признак состояния\n'
        else
            red "  ✗ кнопка исключает из rsync файл без признака «# STATE-FILE»:$missing"; bad=1
        fi
        # Манифест снимается ДО rsync'а настроек и настоящей строкой `run`, а не
        # упоминанием в комментарии: «вызов есть в тексте» и «вызов исполняется»
        # — разные утверждения (урок 0248). Снять шаг — оставить ячейки одно
        # «не спрошено» молча.
        local wl rl
        wl=$(grep -n '^          \./ops/config-drift\.sh --write ' "$ROOT/.github/workflows/deploy.yml" | head -n 1 | cut -d: -f1)
        rl=$(grep -n '^          rsync -az --delete ' "$ROOT/.github/workflows/deploy.yml" | head -n 1 | cut -d: -f1)
        if [ -n "$wl" ] && [ -n "$rl" ] && [ "$wl" -lt "$rl" ]; then
            printf '  ✓ кнопка снимает манифест раньше, чем привозит настройки\n'
        else
            red "  ✗ в deploy.yml нет исполняемой строки «config-drift.sh --write» раньше rsync настроек (манифест ${wl:-нет}, rsync ${rl:-нет})"; bad=1
        fi
    else
        yellow "  · .github рядом нет (ячейка) — соответствие исключений кнопки признаку не проверено"
    fi

    # ВОЗВРАТ ДЕФЕКТА. Три подделки самого сторожа; каждая обязана валить
    # самопроверку. Копия лежит вне ops/, корень ей задаёт переменная; целость
    # подделки проверяется ДО запуска (урок 0198).
    if [ -z "${CONFIG_DRIFT_SELFTEST_FAKE:-}" ]; then
        fake_case() {  # описание, образец sed
            fake="$TMP/fake-drift.sh"
            sed "$2" "$SELF" > "$fake"
            if cmp -s "$SELF" "$fake"; then
                red "  ✗ подделка «$1» не изменила скрипт — образец устарел"; bad=1; return
            fi
            if ! bash -n "$fake" 2>/dev/null; then
                red "  ✗ подделка «$1» не разбирается bash -n — красное пришло бы от неё, а не от снятой проверки"; bad=1; return
            fi
            if CONFIG_DRIFT_SELFTEST_FAKE=yes CONFIG_DRIFT_ROOT="$ROOT" bash "$fake" --selftest >/dev/null 2>&1; then
                red "  ✗ ВОЗВРАТ ДЕФЕКТА: «$1» самопроверку НЕ валит — она не утверждает ничего"; bad=1
            else
                printf '  ✓ возврат дефекта: «%s» валит самопроверку\n' "$1"
            fi
        }
        fake_case "сверка суммы снята" 's/if \[ "\$have" != "\$want" \]; then/if false; then/'
        fake_case "признак состояния снят" "s/grep -aq '\^# STATE-FILE'/grep -aq 'никогда-не-встретится-0215'/"
        fake_case "точка отсчёта — любой SHA" 's/elif \[ "\$expected" != "\$commit" \]; then/elif false; then/'
    fi

    [ $bad = 0 ] || { red "Самопроверка не прошла"; exit 1; }
    green "Самопроверка пройдена"
}

# ───────────────────────────────── разбор ───────────────────────────────────
case "${1:-}" in
    --selftest) selftest; exit 0 ;;
    --write)
        shift
        REPO="$ROOT"; REV=HEAD; OUT=""
        while [ $# -gt 0 ]; do
            case "$1" in
                --repo) REPO="${2:?нужен каталог}"; shift 2 ;;
                --commit) REV="${2:?нужен SHA}"; shift 2 ;;
                --out) OUT="${2:?нужен путь}"; shift 2 ;;
                *) red "Непонятный аргумент: $1"; exit 2 ;;
            esac
        done
        [ -n "$OUT" ] || OUT="$REPO/$MANIFEST_REL"
        write_manifest "$REPO" "$REV" "$OUT"; exit $? ;;
    "") ;;
    *) red "Непонятный аргумент: $1"; exit 2 ;;
esac

check
exit $?
