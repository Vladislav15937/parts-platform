#!/usr/bin/env bash
# Можно ли начинать работу.
#
#   ./tools/can-start.sh
#
# Запускается первым делом, до правок. Отвечает на один вопрос: не строим ли мы
# на сломанном. Красная main — это стоп для всех: следующая правка ляжет поверх
# поломки, её тесты пройдут на сломанном основании, и разбирать придётся
# две ошибки вместо одной. Ночью, когда никто не смотрит, так теряется вся смена.
#
# Код возврата 0 — можно начинать; 1 — нельзя, и написано почему.
set -u
cd "$(dirname "$0")/.."

BAD=0
say()  { printf '  %s\n' "$*"; }
ok()   { printf '  ✓ %s\n' "$*"; }
stop() { printf '  ✗ %s\n' "$*"; BAD=1; }

command -v gh >/dev/null 2>&1 || { say "gh не установлен — состояние main не спросить"; exit 1; }

echo "Можно ли начинать"

# 1. Последний ЗАВЕРШЁННЫЙ прогон main. Идущий сейчас ничего не говорит:
#    он может стать и зелёным, и красным.
read -r STATUS SHA TITLE <<<"$(gh run list --branch main --limit 10 \
  --json status,conclusion,headSha,displayTitle \
  --jq '[.[] | select(.status == "completed")][0] | "\(.conclusion) \(.headSha[0:7]) \(.displayTitle)"' 2>/dev/null)"

if [ -z "${STATUS:-}" ]; then
  stop "прогонов main не видно — проверьте доступ к GitHub"
elif [ "$STATUS" = "success" ]; then
  ok "main зелёная (${SHA}: ${TITLE})"
else
  stop "main ${STATUS} на ${SHA}: ${TITLE}"
  say "  Чинить это — первая задача, а не своя. Красную main не обходят:"
  say "  либо починка, либо откат слияния, которое её уронило."
fi

# 2. Идущий прогон — не поломка, но начинать поверх него не стоит: если он
#    покраснеет, работа окажется на сломанном основании и об этом узнают позже.
RUNNING=$(gh run list --branch main --limit 5 --json status \
  --jq '[.[] | select(.status != "completed")] | length' 2>/dev/null || echo 0)
[ "${RUNNING:-0}" = "0" ] || say "на main идёт прогон (${RUNNING}) — дождитесь, прежде чем ветвиться"

# 3. Своё дерево. Незакоммиченное чужой правки не переживёт, а ветка
#    от устаревшей main даёт конфликт там, где его могло не быть.
DIRTY=$(git status --porcelain | wc -l | tr -d ' ')
[ "$DIRTY" = "0" ] && ok "рабочее дерево чистое" || say "незакоммиченных файлов: ${DIRTY}"

git fetch -q origin main 2>/dev/null || true
BEHIND=$(git rev-list --count HEAD..origin/main 2>/dev/null || echo 0)
[ "${BEHIND:-0}" = "0" ] && ok "не отстаём от origin/main" \
  || say "отстаём от origin/main на ${BEHIND} — ветвитесь от свежей"

echo
if [ $BAD -eq 0 ]; then
  echo "Можно начинать."
else
  echo "Начинать нельзя. Сначала — зелёная main."
fi
exit $BAD
