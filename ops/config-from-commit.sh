#!/usr/bin/env bash
# Настройки ячейки — с коммита, руками, тем же способом, что кнопка.
#
#   ops/config-from-commit.sh <SHA>            # ЧИТАЮЩИЙ: что будет заменено, добавлено, удалено
#   ops/config-from-commit.sh <SHA> --apply    # сделать и проверить сделанное
#   ops/config-from-commit.sh --selftest       # репетиция на «ячейке» с историей кнопки
#
# Запускается из каталога ячейки (там, где docker-compose.prod.yml и ops/).
#
# ЗАЧЕМ ОН ЕСТЬ (задача 0217). Настройки на ячейку возили двумя способами:
# кнопка кладёт `ops/` и `docker-compose.prod.yml` rsync'ом с выкладываемого
# коммита и git на машине не трогает (0111), а ручной шаг 0 переводил дерево
# git переходом `git checkout --detach <SHA>` (0114). На машине, которую
# выкладывали кнопкой, второй способ не работает по построению: rsync
# переписал отслеживаемые файлы, и git отказывает «Your local changes …
# would be overwritten by checkout» — резервный путь закрыт, и узнают об этом
# в ту минуту, когда он понадобился. Заодно `git rev-parse HEAD` там отвечает
# SHA подъёма, а не выложенного коммита, и разбор строится на этом ответе.
#
# РЕШЕНИЕ: СПОСОБ ДОСТАВКИ ОДИН — ФАЙЛЫ КОММИТА ПОВЕРХ, С ИСКЛЮЧЕНИЯМИ КНОПКИ.
# Дерево git на ячейке — наследие подъёма и склад объектов (`git fetch`,
# `git show`, `git archive`), а не описание лежащих настроек: HEAD не двигает
# ни кнопка, ни этот скрипт. «Какой редакции настройки» спрашивают
# `ops/config-drift.sh` (манифест сумм, 0215), а не git.
#
# ЧТО ДЕЛАЕТ. Распаковывает `ops/` и `docker-compose.prod.yml` коммита из
# объектов git во временный каталог (`git archive`, а не checkout: рабочее
# дерево и индекс не трогаются вовсе), снимает манифест тем же инструментом,
# что кнопка, и кладёт тем же `rsync -a --delete` с теми же исключениями.
# Инструмент и список исключений берутся с `origin/main` (CONFIG_TOOL_REF), а
# не с выкладываемого коммита: так же кнопка берёт их со своей ветки — в
# старом коммите их может не быть вовсе. Список исключений один на два пути:
# `--exclude` шага rsync в `.github/workflows/deploy.yml`; не нашёлся — отказ,
# а не пустой список (пустой означал бы перезаписанный файл состояния).
#
# ЧЕГО НЕ ТРОГАЕТ. `ops/active-build.caddy` — имя сборки под трафиком; подмена
# его inode при живом терминаторе означает, что следующее переключение сборок
# «пройдёт» и не случится. `ops/secrets/`, `.env`, HEAD и индекс клона.
#
# ЧИТАЮЩИЙ ПО УМОЛЧАНИЮ. Без `--apply` печатает поимённо, что заменит,
# добавит и удалит, и не пишет ничего. С `--apply` после записи проверяет
# сделанное, а не код возврата rsync: каждый файл совпадает с коммитом,
# лишних нет, у файла состояния тот же inode и то же содержимое, сверка
# манифеста с этим SHA сходится.
#
# ПРИВЕЗТИ — ЕЩЁ НЕ ПРИМЕНИТЬ (0216): работающие контейнеры держат прежние
# inode смонтированных файлов. Применяет `ops/deploy.sh` своим шагом; при
# выкладке по шагам — `ops/apply-config.sh`.
#
# Три исхода: 0 — сделано или (без --apply) план напечатан; 1 — отказ после
# начала записи либо проверка сделанного красная (названо, что именно);
# 2 — спросить не удалось, НИЧЕГО НЕ ТРОНУТО (нет клона, нет коммита, нет
# origin/main, нет rsync, нет списка исключений).
#
#   CONFIG_TOOL_REF=…   откуда брать инструмент и список исключений (origin/main)
set -uo pipefail

SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
SRC="$(cd "$(dirname "$0")/.." && pwd)"
TOOL_REF="${CONFIG_TOOL_REF:-origin/main}"

red()    { printf '\033[1;31m%s\033[0m\n' "$1" >&2; }
green()  { printf '\033[1;32m%s\033[0m\n' "$1"; }
yellow() { printf '\033[1;33m%s\033[0m\n' "$1"; }
short()  { printf '%.12s' "$1"; }

TMP=$(mktemp -d) || exit 2
trap 'rm -rf "$TMP"' EXIT

# inode без `stat`: у GNU и BSD у него разные флаги.
inode() { ls -di "$1" 2>/dev/null | awk '{print $1}'; }

# Исключён ли путь (относительно ops/) правилами rsync кнопки. Шаблон без
# косой черты — имя файла на любой глубине, с чертой — каталог на любой
# глубине: ровно так rsync читает те два шаблона, что стоят в deploy.yml.
excluded() {
    local rel="$1" p base="${1##*/}"
    while IFS= read -r p; do
        case "$p" in
            */) case "/$rel" in */"${p%/}"/*) return 0 ;; esac ;;
            *)  [ "$base" = "$p" ] && return 0 ;;
        esac
    done < "$TMP/excludes"
    return 1
}

# План: чем каталог ячейки отличается от распакованного коммита. Одна функция
# на «что сделаю» и на «сделано ли» — иначе проверка и план разойдутся.
plan() {  # корень ячейки, распакованный коммит → строки «~ + -» в stdout
    local root="$1" stage="$2" p
    { (cd "$stage" && find ops -type f); echo docker-compose.prod.yml; } | LC_ALL=C sort -u > "$TMP/in-commit"
    while IFS= read -r p; do
        case "$p" in ops/*) excluded "${p#ops/}" && continue ;; esac
        if [ -d "$root/$p" ]; then
            echo "~ $p  (сейчас КАТАЛОГ на месте файла)"
        elif [ ! -e "$root/$p" ]; then
            echo "+ $p"
        elif ! cmp -s "$stage/$p" "$root/$p"; then
            echo "~ $p"
        fi
    done < "$TMP/in-commit"
    (cd "$root" && find ops -type f) | LC_ALL=C sort | while IFS= read -r p; do
        excluded "${p#ops/}" && continue
        grep -Fxq -- "$p" "$TMP/in-commit" || echo "- $p"
    done
}

run() {  # SHA, apply(0|1)
    local want="$1" apply="$2" root="$PWD" commit tool head manifest_was active n
    local stage="$TMP/stage" knob="$TMP/knob"

    [ -f "$root/docker-compose.prod.yml" ] && [ -d "$root/ops" ] || {
        red "Здесь не каталог ячейки ($root): нет docker-compose.prod.yml или ops/. Ничего не тронуто."
        return 2; }
    git -C "$root" rev-parse --git-dir >/dev/null 2>&1 || {
        red "В $root нет клона git: ручной путь берёт настройки из объектов git. Без клона выкладывают кнопкой. Ничего не тронуто."
        return 2; }
    command -v rsync >/dev/null 2>&1 || { red "Нет rsync — тот же, которым кладёт кнопка. Ничего не тронуто."; return 2; }
    commit=$(git -C "$root" rev-parse --verify -q "$want^{commit}") || {
        red "Коммита $want в клоне нет. Сначала: git fetch --no-tags origin main (или ветку, где он лежит). Ничего не тронуто."
        return 2; }
    tool=$(git -C "$root" rev-parse --verify -q "$TOOL_REF^{commit}") || {
        red "Нет $TOOL_REF, откуда берутся инструмент и исключения кнопки. Сначала: git fetch --no-tags origin main. Ничего не тронуто."
        return 2; }

    # Файла состояния нет — rsync его и не положит (он в исключениях), а
    # терминатор без него не поднимется. Так выглядит только ячейка старого
    # образца (до двух сборок); умолчание кладут руками, одним файлом.
    [ -f "$root/ops/active-build.caddy" ] || {
        red "Нет ops/active-build.caddy — файла состояния (какая сборка под трафиком). rsync кнопки его не кладёт."
        red "Ячейка старого образца: сначала git show $want:ops/active-build.caddy > ops/active-build.caddy. Ничего не тронуто."
        return 2; }

    # Исключения кнопки — из её deploy.yml, единственный источник.
    mkdir -p "$knob/ops" "$knob/.github/workflows" "$stage"
    git -C "$root" show "$tool:.github/workflows/deploy.yml" > "$knob/.github/workflows/deploy.yml" 2>/dev/null || {
        red "В $TOOL_REF нет .github/workflows/deploy.yml — неизвестно, что кнопка не привозит. Ничего не тронуто."
        return 2; }
    grep -o "exclude '[^']*'" "$knob/.github/workflows/deploy.yml" | sed "s/exclude '//; s/'\$//" | LC_ALL=C sort -u > "$TMP/excludes"
    grep -qx 'active-build.caddy' "$TMP/excludes" || {
        red "В deploy.yml ($TOOL_REF) не нашлось исключения active-build.caddy — класть нельзя: перезапись файла состояния уводит трафик. Ничего не тронуто."
        return 2; }

    # Коммит — из объектов git во временный каталог. Ни checkout, ни индекс.
    git -C "$root" archive "$commit" -- ops docker-compose.prod.yml 2>"$TMP/archive.err" | tar -x -C "$stage" 2>>"$TMP/archive.err"
    [ -f "$stage/docker-compose.prod.yml" ] && [ -d "$stage/ops" ] || {
        red "В коммите $(short "$commit") нет ops/ или docker-compose.prod.yml — это не версия ячейки. Ничего не тронуто."
        sed 's/^/    /' "$TMP/archive.err" >&2
        return 2; }

    # Манифест — тем же инструментом и с теми же исключениями, что у кнопки.
    if git -C "$root" show "$tool:ops/config-drift.sh" > "$knob/ops/config-drift.sh" 2>/dev/null; then
        CONFIG_DRIFT_ROOT="$knob" bash "$knob/ops/config-drift.sh" --write --repo "$root" --commit "$commit" \
            --out "$stage/ops/config-manifest.sha256" > "$TMP/manifest.out" 2>&1 || {
            red "Манифест настроек не снялся — класть без него значит оставить ячейку «не спрошено». Ничего не тронуто."
            sed 's/^/    /' "$TMP/manifest.out" >&2
            return 2; }
    else
        rm -f "$knob/ops/config-drift.sh"
        yellow "  ! в $TOOL_REF нет ops/config-drift.sh — манифест не снят, сверка после этого ответит «НЕ ВЫПОЛНЕНА»"
    fi

    head=$(git -C "$root" rev-parse -q --verify HEAD 2>/dev/null || echo '?')
    manifest_was=$(sed -n 's/^# commit //p' "$root/ops/config-manifest.sha256" 2>/dev/null | head -n 1)
    active=$(grep -m1 '^reverse_proxy' "$root/ops/active-build.caddy" 2>/dev/null | awk '{print $2}')

    plan "$root" "$stage" > "$TMP/plan"
    n=$(grep -c . "$TMP/plan" || true)
    echo "Настройки ячейки с коммита $(short "$commit") ($commit)"
    echo "  каталог:            $root"
    [ -n "$manifest_was" ] && manifest_was=$(short "$manifest_was") || manifest_was='манифеста нет'
    echo "  сейчас по манифесту: $manifest_was"
    echo "  инструмент и исключения: $TOOL_REF ($(short "$tool"))"
    if [ "${n:-0}" = 0 ]; then
        green "  ✓ файлы уже ровно с этого коммита — класть нечего"
    else
        echo "  будет сделано ($n): ~ заменить, + добавить, - удалить"
        sed 's/^/    /' "$TMP/plan"
    fi
    echo "  не трогает: ops/active-build.caddy (под трафиком: ${active:-не прочитан}), ops/secrets/, .env,"
    echo "              HEAD и индекс клона ($(short "$head") — о версии настроек он не говорит, задача 0217)"

    if [ "$apply" != 1 ]; then
        echo "Ничего не тронуто. Сделать: ops/config-from-commit.sh $commit --apply"
        return 0
    fi

    local ino_before sum_before ino_after sum_after p
    local excl=()
    while IFS= read -r p; do excl+=(--exclude "$p"); done < "$TMP/excludes"
    ino_before=$(inode "$root/ops/active-build.caddy")
    sum_before=$(cksum < "$root/ops/active-build.caddy" 2>/dev/null)

    # --checksum, а не сверка размера и времени: `git archive` ставит файлам
    # время коммита, и два коммита одной минуты с файлом той же длины rsync
    # счёл бы одинаковыми — проверка сделанного ниже это и поймала на репетиции.
    if [ "${n:-0}" != 0 ]; then
        rsync -a --checksum --delete ${excl[@]+"${excl[@]}"} "$stage/ops/" "$root/ops/" \
            && rsync -a --checksum "$stage/docker-compose.prod.yml" "$root/" || {
            red "rsync оборвался — дерево может быть смешанным. Повторите ту же команду: она кладёт заново и проверяет."
            return 1; }
    fi

    # Проверка СДЕЛАННОГО, а не кода возврата.
    local bad=0
    plan "$root" "$stage" > "$TMP/after"
    if [ -s "$TMP/after" ]; then
        red "  ✗ после записи файлы НЕ совпадают с коммитом $(short "$commit"):"
        sed 's/^/      /' "$TMP/after" >&2
        bad=1
    fi
    ino_after=$(inode "$root/ops/active-build.caddy")
    sum_after=$(cksum < "$root/ops/active-build.caddy" 2>/dev/null)
    if [ "$ino_before" != "$ino_after" ] || [ "$sum_before" != "$sum_after" ]; then
        red "  ✗ ops/active-build.caddy ПОДМЕНЁН (inode $ino_before → ${ino_after:-нет}): терминатор держит прежний файл,"
        red "    следующее переключение сборок «пройдёт» и не случится. Вернуть имя сборки под трафиком"
        red "    (было: ${active:-не прочитано}) и сразу: docker compose -f docker-compose.prod.yml up -d caddy"
        bad=1
    fi
    if [ -f "$knob/ops/config-drift.sh" ]; then
        if (cd "$root" && CONFIG_DRIFT_ROOT="$root" CONFIG_DRIFT_EXPECT="$commit" bash "$knob/ops/config-drift.sh") > "$TMP/drift" 2>&1; then
            :
        else
            red "  ✗ сверка манифеста с $(short "$commit") не сходится:"
            sed 's/^/      /' "$TMP/drift" >&2
            bad=1
        fi
    fi
    [ "$bad" = 0 ] || return 1
    green "  ✓ сделано: ops/ и docker-compose.prod.yml ровно с $(short "$commit"), файл состояния тот же (inode $ino_after), сверка манифеста сходится"
    echo "  Привезти — ещё не применить (0216): дальше ops/apply-config.sh или ops/deploy.sh --tag $commit"
    return 0
}

# ─────────────────────────────── самопроверка ───────────────────────────────
#
# Репетиция на стенде, устроенном как ячейка (критерий 0217): клон, заведённый
# подъёмом на коммите A0; кнопка положила rsync'ом настройки коммита A;
# трафик переведён на зелёную настоящей write_active; рядом секреты и .env.
# Затем ручной путь на коммит B. Настоящие config-drift.sh и deploy.yml
# лежат в истории репетиции — исключения и манифест берутся там, где их
# возьмёт ячейка.
selftest() {
    local bad=0 out rc S R c0 cA cB cell snap1 snap2
    echo "Самопроверка ops/config-from-commit.sh"
    command -v rsync >/dev/null 2>&1 || { red "  ✗ нет rsync — репетиция невозможна"; exit 1; }
    S="$TMP/st"; R="$S/repo"; mkdir -p "$R/ops" "$R/.github/workflows"
    # Одно время на все коммиты: «та же длина и то же время при другом
    # содержимом» случается всегда, а не когда коммиты попали в одну секунду.
    gitc() { GIT_AUTHOR_DATE=@1700000000 GIT_COMMITTER_DATE=@1700000000 git -C "$1" -c user.email=t@example.com -c user.name=t -c commit.gpgsign=false "${@:2}"; }
    git -C "$R" init -q . && git -C "$R" symbolic-ref HEAD refs/heads/main || { red "  ✗ git init не удался"; exit 1; }
    cp "$SRC/ops/config-drift.sh" "$R/ops/" && cp "$SRC/.github/workflows/deploy.yml" "$R/.github/workflows/" \
        && cp "$SRC/ops/active-build.caddy" "$R/ops/" || { red "  ✗ нет настоящих config-drift.sh / deploy.yml / active-build.caddy"; exit 1; }
    printf 'echo a0\n' > "$R/ops/a.sh"; printf 'v: 0\n' > "$R/ops/alerts.yml"
    printf 'echo gone\n' > "$R/ops/gone.sh"; printf 'services: {} # 0\n' > "$R/docker-compose.prod.yml"
    gitc "$R" add -A; gitc "$R" commit -q -m A0; c0=$(git -C "$R" rev-parse HEAD)
    printf 'echo a\n' > "$R/ops/a.sh"; printf 'v: A\n' > "$R/ops/alerts.yml"; printf 'services: {} # A\n' > "$R/docker-compose.prod.yml"
    gitc "$R" commit -q -am A; cA=$(git -C "$R" rev-parse HEAD)
    # B: правит те же файлы, удаляет один, добавляет один и — редкий случай
    # 0114 — правит шапку файла состояния.
    printf 'echo b\n' > "$R/ops/a.sh"; printf 'v: B\n' > "$R/ops/alerts.yml"; printf 'services: {} # B\n' > "$R/docker-compose.prod.yml"
    printf 'echo new\n' > "$R/ops/new.sh"; gitc "$R" rm -q ops/gone.sh
    { echo '# шапку правили'; cat "$SRC/ops/active-build.caddy"; } > "$R/ops/active-build.caddy"
    gitc "$R" add -A; gitc "$R" commit -q -m B; cB=$(git -C "$R" rev-parse HEAD)

    sed -n '/^write_active() {/,/^}/p' "$SRC/ops/switch-build.sh" > "$S/write_active.sh"
    make_cell() {  # каталог → ячейка: подъём на A0, кнопка положила A, трафик на зелёной
        local d="$1" st="$S/button-$RANDOM"
        git clone -q "$R" "$d" && git -C "$d" checkout -q --detach "$c0" || return 1
        mkdir -p "$st"; git -C "$R" archive "$cA" -- ops docker-compose.prod.yml | tar -x -C "$st"
        bash "$SRC/ops/config-drift.sh" --write --repo "$R" --commit "$cA" --out "$st/ops/config-manifest.sha256" >/dev/null || return 1
        rsync -a --delete --exclude 'active-build.caddy' --exclude 'secrets/' "$st/ops/" "$d/ops/" || return 1
        rsync -a "$st/docker-compose.prod.yml" "$d/" || return 1
        ACTIVE_FILE="$d/ops/active-build.caddy" bash -c 'source "$1"; write_active app-green' _ "$S/write_active.sh"
        mkdir -p "$d/ops/secrets"; echo 'секрет' > "$d/ops/secrets/token"
        printf 'APP_IMAGE_TAG=%s\n' "$cA" > "$d/.env"
    }
    snapshot() { (cd "$1" && find . -path ./.git -prune -o -type f -print | LC_ALL=C sort | while IFS= read -r f; do printf '%s %s\n' "$(cksum < "$f")" "$f"; done); }
    probe() { out=$(cd "$1" && bash "${PROBE_SCRIPT:-$SELF}" "${@:2}" 2>&1); rc=$?; }

    cell="$S/cell"; make_cell "$cell" || { red "  ✗ репетиционная ячейка не собралась"; exit 1; }

    # 0. Прежний шаг 0 на этой ячейке. Не проверка скрипта, а посылка задачи:
    # если git здесь проходит, репетиция устроена не как ячейка.
    out=$(git -C "$cell" checkout -q --detach "$cB" 2>&1); rc=$?
    if [ $rc != 0 ] && grep -q 'would be overwritten' <<< "$out"; then
        printf '  ✓ прежний шаг 0 (git checkout --detach) на ячейке после кнопки отказывает: «would be overwritten»\n'
    else
        red "  ✗ репетиция устроена не как ячейка: git checkout --detach прошёл (код $rc)"; bad=1
    fi
    if [ "$(git -C "$cell" rev-parse HEAD)" = "$c0" ]; then
        printf '  ✓ HEAD клона после кнопки — коммит подъёма, а не выложенный: git о настройках не говорит\n'
    else
        red "  ✗ HEAD сдвинулся — репетиция не та"; bad=1
    fi

    # 1. Читающий: план поимённо, ничего не тронуто.
    snap1=$(snapshot "$cell")
    probe "$cell" "$cB"
    snap2=$(snapshot "$cell")
    if [ $rc = 0 ] && [ "$snap1" = "$snap2" ] \
        && grep -q '~ ops/a.sh' <<< "$out" && grep -q '+ ops/new.sh' <<< "$out" && grep -q -- '- ops/gone.sh' <<< "$out" \
        && grep -q '~ docker-compose.prod.yml' <<< "$out" && grep -q 'не трогает: ops/active-build.caddy (под трафиком: app-green' <<< "$out" \
        && ! grep -q '[~+-] ops/active-build.caddy\|ops/secrets/token' <<< "$out"; then
        printf '  ✓ без --apply: план поимённо (~ a.sh, + new.sh, - gone.sh, ~ compose), файл состояния и секреты не в плане, ничего не тронуто\n'
    else
        red "  ✗ читающий запуск обязан назвать план и ничего не тронуть (код $rc)"; printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # 2. Неизвестный коммит — код 2, ничего не тронуто.
    probe "$cell" 0123456789abcdef0123456789abcdef01234567 --apply
    snap2=$(snapshot "$cell")
    if [ $rc = 2 ] && [ "$snap1" = "$snap2" ] && grep -q 'git fetch' <<< "$out"; then
        printf '  ✓ неизвестный SHA — код 2 и совет git fetch, ничего не тронуто\n'
    else
        red "  ✗ неизвестный SHA обязан отвечать кодом 2, не трогая ничего (код $rc)"; bad=1
    fi

    # 2а. Ячейка старого образца — без файла состояния: отказ, а не ячейка,
    # у которой терминатору нечего импортировать.
    local old="$S/cell-old"
    make_cell "$old" && rm -f "$old/ops/active-build.caddy"
    snap1=$(snapshot "$old"); probe "$old" "$cB" --apply; snap2=$(snapshot "$old")
    if [ $rc = 2 ] && [ "$snap1" = "$snap2" ] && grep -q 'git show .*ops/active-build.caddy' <<< "$out"; then
        printf '  ✓ нет файла состояния — код 2 и как положить умолчание, ничего не тронуто\n'
    else
        red "  ✗ без ops/active-build.caddy обязан отказывать кодом 2, не трогая ничего (код $rc)"; bad=1
    fi
    snap1=$(snapshot "$cell")

    # 3. Ручной путь на B — сделано и доказано содержимым.
    local ino0 ok=1 p
    ino0=$(inode "$cell/ops/active-build.caddy")
    probe "$cell" "$cB" --apply
    for p in ops/a.sh ops/alerts.yml ops/new.sh docker-compose.prod.yml ops/config-drift.sh; do
        git -C "$R" show "$cB:$p" | cmp -s - "$cell/$p" || { red "    $p не совпал с B"; ok=0; }
    done
    [ ! -e "$cell/ops/gone.sh" ] || { red "    ops/gone.sh не удалён"; ok=0; }
    [ "$(inode "$cell/ops/active-build.caddy")" = "$ino0" ] && grep -q '^reverse_proxy app-green' "$cell/ops/active-build.caddy" \
        || { red "    файл состояния подменён или переписан"; ok=0; }
    [ -f "$cell/ops/secrets/token" ] && grep -q "$cA" "$cell/.env" || { red "    секреты или .env тронуты"; ok=0; }
    grep -q "^# commit $cB" "$cell/ops/config-manifest.sha256" 2>/dev/null || { red "    манифест не с B"; ok=0; }
    [ "$(git -C "$cell" rev-parse HEAD)" = "$c0" ] || { red "    HEAD сдвинут — скрипт не должен трогать git"; ok=0; }
    if [ $rc = 0 ] && [ $ok = 1 ]; then
        printf '  ✓ --apply на B: файлы ровно с B, gone.sh удалён, active-build.caddy тот же inode и app-green, секреты и .env на месте, манифест с B\n'
    else
        red "  ✗ ручной путь на B обязан положить B и не тронуть состояние (код $rc)"; printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi
    out=$(cd "$cell" && CONFIG_DRIFT_ROOT="$cell" CONFIG_DRIFT_EXPECT="$cB" bash "$SRC/ops/config-drift.sh" 2>&1); rc=$?
    if [ $rc = 0 ]; then
        printf '  ✓ сверка настроек (ops/config-drift.sh) с B после ручного пути — сходится\n'
    else
        red "  ✗ после ручного пути сверка с B обязана сходиться (код $rc)"; printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
    fi

    # 4. Повтор — делать нечего, код 0.
    probe "$cell" "$cB" --apply
    if [ $rc = 0 ] && grep -q 'класть нечего' <<< "$out"; then
        printf '  ✓ повтор — «класть нечего», код 0\n'
    else
        red "  ✗ повтор обязан быть безвредным (код $rc)"; bad=1
    fi

    # Возврат дефекта: проверка сделанного обязана краснеть на поломке записи.
    fake() {  # имя, sed-выражение, что обязано прозвучать
        local f="$S/fake-$1.sh" d="$S/cell-$1"
        sed "$2" "$SELF" > "$f"
        if cmp -s "$f" "$SELF"; then red "  ✗ подделка «$1» не применилась — самопроверка устарела"; bad=1; return; fi
        make_cell "$d" || { red "  ✗ ячейка для подделки «$1» не собралась"; bad=1; return; }
        PROBE_SCRIPT="$f" probe "$d" "$cB" --apply
        if [ $rc = 1 ] && grep -q "$3" <<< "$out"; then
            printf '  ✓ подделка «%s» — красное, названо: %s\n' "$1" "$3"
        else
            red "  ✗ подделка «$1» обязана краснеть кодом 1 со словами «$3» (код $rc)"; printf '%s\n' "$out" | sed 's/^/      /' >&2; bad=1
        fi
    }
    fake 'исключения кнопки не переданы rsync' 's/excl+=(--exclude "\$p")/:/' 'active-build.caddy ПОДМЕНЁН'
    fake 'rsync без --delete' 's/rsync -a --checksum --delete /rsync -a --checksum /' 'ops/gone.sh'

    [ "$bad" = 0 ] && green "Самопроверка пройдена" || red "Самопроверка НЕ пройдена"
    return "$bad"
}

case "${1:-}" in
    --selftest) selftest; exit $? ;;
    ''|-h|--help) sed -n '2,8p' "$SELF" | sed 's/^# \{0,1\}//'; exit 2 ;;
    -*) red "Неизвестный флаг: $1"; exit 2 ;;
esac
SHA="$1"; shift
APPLY=0
case "${1:-}" in
    '') ;;
    --apply) APPLY=1 ;;
    *) red "Неизвестный аргумент: $1 (ждали --apply или ничего)"; exit 2 ;;
esac
run "$SHA" "$APPLY"
