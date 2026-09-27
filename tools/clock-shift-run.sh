#!/usr/bin/env bash
# Тот же прогон, но с часами, переведёнными вперёд.
#
# Зачем. Календарная дата, зашитая в проверку, которая сравнивает её
# с «сейчас», — отложенное падение: 18 сентября 2026 тест ждал «до 15
# сентября», пятнадцатое стало позади, и красной стала `main` — то есть любой
# PR любой ветки, через четыре дня после того, как мину поставили.
# Литерал у поля, сравниваемого с «сейчас», ловит перебором
# `tools/date-mine-guard.py`. Но он ловит только литерал: зависимость
# от дня недели, от месяца и от полуночи по исходникам не видна вовсе,
# и установить её можно единственным способом — прогнать то же самое
# в другой день.
#
#   ./tools/clock-shift-run.sh                # +31 день, фронтенд и проверки
#   ./tools/clock-shift-run.sh --days 400     # и другой год заодно
#   ./tools/clock-shift-run.sh --java         # и бэкенд, если часы у JVM сдвигаются
#
# Задачей CI этот прогон НЕ заведён, и это решение исполнителя с названной
# ценой, а не забывчивость. Замерено: фронтенд со сдвигом — 14 с, столько же,
# сколько обычный; удвоенные самопроверки — около двух минут; бэкенд — 4 мин
# 09 с на 1101 тест (на linux-раннере faketime сдвигает и JVM, значит там
# он выполним, но ещё и медленнее: холодные ~/.m2 и выкачка образов).
# То есть цена — не «ещё двадцать минут», а примерно вторая задача «Бэкенд».
# Мнение исполнителя: заводить стоит хотя бы дешёвую половину (без --java) —
# мина, уронившая main 18 сентября, была во фронтендном тесте. Но это решение
# о стоимости прогона на каждый PR, то есть вопрос владельцу; он назван
# в ops/CLAUDE.md, раздел «Календарные даты в проверках ячейки».
#
# Три исхода, различимые кодом, — как у tools/head-green.sh:
#   0 — сдвиг ничего не поменял: всё, что прогнано, ответило как и без сдвига;
#   1 — исход поменялся: названо, что именно;
#   2 — часть не проверена. Это НЕ зелёный: «проверить не вышло» и «всё хорошо»
#       обязаны различаться, иначе отсутствие тревоги начнут читать как её
#       отсутствие по существу.
#
# Чего этот прогон не сдвигает, и знать это надо заранее:
#   • времена файлов. `touch -t` в фикстурах и `find -mtime` смотрят
#     на настоящую файловую систему;
#   • JVM на macOS: `faketime` подгружает свою библиотеку через
#     DYLD_INSERT_LIBRARIES, а подписанный java её не берёт — время остаётся
#     настоящим. Проверено тремя формами вызова; поэтому прогон бэкенда
#     не «пропускается молча», а называется непроверенным;
#   • `/bin/date` — по той же причине. Шеллу время подменяется своим `date`
#     в PATH, а не faketime.
set -u

DAYS=31
WITH_JAVA=0
while [ $# -gt 0 ]; do
    case "$1" in
        --days) DAYS="$2"; shift 2 ;;
        --java) WITH_JAVA=1; shift ;;
        -h|--help) sed -n '2,36p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) printf 'Не знаю аргумента: %s\n' "$1" >&2; exit 2 ;;
    esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

green() { printf '\033[1;32m%s\033[0m\n' "$1"; }
red()   { printf '\033[1;31m%s\033[0m\n' "$1"; }
warn()  { printf '\033[1;33m%s\033[0m\n' "$1"; }
step()  { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }

CHANGED=0
UNCHECKED=0
SHIFT_SECONDS=$(( DAYS * 86400 ))

printf 'Сдвиг: +%s дн. (%s с)\n' "$DAYS" "$SHIFT_SECONDS"

# ──────────────────────────── подменный `date` ───────────────────────────────
#
# Шелл спрашивает время у `/bin/date`, а его faketime не сдвигает. Поэтому
# в PATH кладётся свой: спросили «сейчас» — отдаёт сдвинутое, спросили
# конкретный момент (`-r`, `-d`, `-j -f`) — отдаёт как есть. Второе
# обязательно: сдвинув ОБЕ стороны сравнения, прогон не показал бы ничего.
SHIM="$(mktemp -d)"
trap 'rm -rf "$SHIM"' EXIT
cat > "$SHIM/date" <<SHIM_END
#!/bin/sh
real=/bin/date
for a in "\$@"; do
    case "\$a" in
        -r|-d|-j|-f|--date=*|--reference=*) exec "\$real" "\$@" ;;
    esac
done
now=\$(( \$("\$real" +%s) + $SHIFT_SECONDS ))
utc=""; fmt=""
for a in "\$@"; do
    case "\$a" in
        -u) utc="-u" ;;
        +*) fmt="\$a" ;;
    esac
done
if [ -n "\$fmt" ]; then
    "\$real" \$utc -r "\$now" "\$fmt" 2>/dev/null || "\$real" \$utc -d "@\$now" "\$fmt"
else
    "\$real" \$utc -r "\$now" 2>/dev/null || "\$real" \$utc -d "@\$now"
fi
SHIM_END
chmod +x "$SHIM/date"

# Сам шим обязан работать, иначе «проверки прошли» означало бы, что часы
# никуда не двигались. Ровно та же болезнь, что у пробы, зеленеющей оттого,
# что сеть не ответила.
before="$(/bin/date -u +%Y-%m-%d)"
after="$(PATH="$SHIM:$PATH" date -u +%Y-%m-%d)"
if [ "$before" = "$after" ]; then
    red "Подменный date не сдвигает время ($before): проверки шли бы по настоящим часам."
    exit 2
fi
printf 'Шеллу «сегодня» показывается как %s вместо %s\n' "$after" "$before"

# ───────────────────────────────── фронтенд ──────────────────────────────────
#
# node в PATH есть не всегда: локально он из nvm, а версия закреплена
# в `frontend/.nvmrc` — той же, которую берёт CI. Не нашёлся — прогон
# называется непроверенным, а не пропускается молча.
if ! command -v node > /dev/null 2>&1; then
    want="$(cat frontend/.nvmrc 2>/dev/null || true)"
    for dir in "$HOME/.nvm/versions/node/v${want}"* "$HOME/.nvm/versions/node"/*; do
        if [ -x "$dir/bin/node" ]; then
            PATH="$dir/bin:$PATH"
            export PATH
            break
        fi
    done
fi

step "Фронтенд: тот же набор со сдвинутыми часами"
if ! command -v node > /dev/null 2>&1; then
    warn "  НЕ ПРОВЕРЕНО: node не найден ни в PATH, ни в nvm"
    UNCHECKED=1
elif [ ! -x "$ROOT/frontend/node_modules/.bin/vitest" ]; then
    warn "  НЕ ПРОВЕРЕНО: нет frontend/node_modules — сделайте npm ci"
    UNCHECKED=1
elif (
        cd frontend \
        && CLOCK_SHIFT_DAYS="$DAYS" ./node_modules/.bin/vitest run \
            --config vitest.shifted.config.ts > "$SHIM/front.log" 2>&1
    ); then
    green "  ✓ исход не поменялся: $(grep -oE 'Tests +[0-9]+ passed' "$SHIM/front.log" | tail -1)"
else
    red "  ✗ со сдвинутыми часами прогон падает — значит какая-то проверка"
    red "    держится на календаре. Упавшее:"
    grep -E '^ *(FAIL|✗|×)' "$SHIM/front.log" | head -20 | sed 's/^/      /'
    CHANGED=1
fi

# ────────────────────────── проверки ячейки и сторожа ────────────────────────
#
# Список не переписывается рядом, а собирается: сторож, заведённый после
# правки этого файла, иначе остался бы непроверенным — и молча.
#
# Пропуск — по имени и с причиной, как разрешённые пары у сторожа схем.
# Молчаливый пропуск съел бы и настоящую проверку, а пустой список сделал бы
# прогон многоминутным: самопроверка, поднимающая Postgres, идёт здесь дважды.
skip_why() {
    case "$1" in
        db/verify-rollback.py)
            printf 'поднимает Postgres и гоняет Liquibase по одному changeset’у — минуты, и дважды; календарных дат в нём нет' ;;
        *) printf '' ;;
    esac
}

# Предел на одну самопроверку: зависшая не должна вешать весь прогон, и «висит»
# обязано отличаться от «прошла». На ubuntu это `timeout`, на macOS —
# `gtimeout` из coreutils; нет ни того, ни другого — гоняем без предела
# и говорим об этом.
LIMIT=""
for candidate in timeout gtimeout; do
    command -v "$candidate" > /dev/null 2>&1 && LIMIT="$candidate 180" && break
done
[ -n "$LIMIT" ] || warn "ни timeout, ни gtimeout — самопроверки идут без предела по времени"

step "Проверки и сторожа: свои самопроверки со сдвинутыми часами"
for file in ops/*.sh tools/*.sh tools/*.py db/*.py; do
    [ -f "$file" ] || continue
    [ "$file" = "tools/clock-shift-run.sh" ] && continue
    why="$(skip_why "$file")"
    if [ -n "$why" ]; then
        warn "  ~ $file — пропущено: $why"
        continue
    fi
    flag=""
    grep -q -- '--selftest' "$file" && flag="--selftest"
    grep -q -- '--самопроверка' "$file" && flag="--самопроверка"
    [ -n "$flag" ] || continue
    [ -x "$file" ] || continue

    $LIMIT "./$file" "$flag" > "$SHIM/plain.log" 2>&1; plain=$?
    PATH="$SHIM:$PATH" $LIMIT "./$file" "$flag" > "$SHIM/shifted.log" 2>&1; shifted=$?

    if [ "$plain" = "$shifted" ]; then
        if [ "$plain" = 0 ]; then
            green "  ✓ $file — исход тот же"
        else
            warn  "  ~ $file — падает и без сдвига (код $plain), к часам отношения не имеет"
        fi
        continue
    fi
    red "  ✗ $file — исход поменялся: без сдвига $plain, со сдвигом $shifted"
    diff "$SHIM/plain.log" "$SHIM/shifted.log" | head -12 | sed 's/^/      /'
    CHANGED=1
done

# ────────────────────────────────── бэкенд ──────────────────────────────────
step "Бэкенд"
JVM_SHIFTS=0
if command -v faketime > /dev/null 2>&1; then
    probe="$(mktemp -d)"
    printf 'public class T { public static void main(String[] a){ System.out.println(java.time.LocalDate.now()); } }\n' \
        > "$probe/T.java"
    real_day="$(java "$probe/T.java" 2>/dev/null)"
    fake_day="$(faketime -f "+${DAYS}d" java "$probe/T.java" 2>/dev/null)"
    rm -rf "$probe"
    [ -n "$fake_day" ] && [ "$real_day" != "$fake_day" ] && JVM_SHIFTS=1
fi

if [ "$JVM_SHIFTS" = 0 ]; then
    warn "  НЕ ПРОВЕРЕНО: часы этой JVM не сдвигаются (faketime до неё не доходит)."
    warn "  На macOS подписанный java не подгружает библиотеку faketime — это"
    warn "  свойство машины, а не прогона; на linux-раннере сдвиг работает."
    warn "  Статически бэкенд проверен: ./tools/date-mine-guard.py."
    UNCHECKED=1
elif [ "$WITH_JAVA" = 0 ]; then
    warn "  не запускалось: нужен --java (полный прогон это ~20 минут)"
    UNCHECKED=1
else
    if faketime -f "+${DAYS}d" ./mvnw -o -B -ntp test > "$SHIM/back.log" 2>&1; then
        green "  ✓ исход не поменялся"
    else
        red "  ✗ со сдвинутыми часами падает:"
        grep -E '^\[ERROR\].*Test|Tests run' "$SHIM/back.log" | head -20 | sed 's/^/      /'
        CHANGED=1
    fi
fi

step "Итог"
if [ "$CHANGED" = 1 ]; then
    red "Сдвиг часов поменял исход: в проверках есть календарная мина (см. выше)."
    exit 1
fi
if [ "$UNCHECKED" = 1 ]; then
    warn "Прогнанное сдвиг не поменял, но часть не проверена (названа выше)."
    warn "Это не зелёный: «проверить не вышло» и «всё хорошо» — разные ответы."
    exit 2
fi
green "Часы вперёд на $DAYS дн. — ни одна проверка не поменяла исход."
exit 0
