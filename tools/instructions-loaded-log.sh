#!/usr/bin/env bash
# Логирует, какие CLAUDE.md и правила .claude/rules/ подхватились, когда и почему.
#
#   Зовётся хуком InstructionsLoaded из .claude/settings.json, на вход — JSON.
#   У этого хука НЕТ управления решением (документация: «observational only»,
#   коды возврата и JSON-вывод отбрасываются), поэтому он ничего не блокирует
#   и не может сломать загрузку правил. Его единственная работа — ответить
#   на вопрос «подхватилось ли правило с paths: или оно молчит».
#
#   ./tools/instructions-loaded-log.sh --selftest   проверка на поддельном входе
#
# ПОЛЕЗНАЯ НАГРУЗКА ИДЁТ ЧЕРЕЗ ОКРУЖЕНИЕ, А НЕ ЧЕРЕЗ stdin ПИТОНА — и это
# не стиль, а починка: первая редакция звала `python3 - "$LOG" <<'PY'`, то есть
# подавала программу heredoc'ом в тот же stdin, которым приходит JSON. Питон
# читал программу, `sys.stdin.read()` отдавал пустоту, и журнал наполнялся
# строками «?» при зелёном на вид прогоне. Поймала это самопроверка.
#
# Путь журнала намеренно ВНЕ репозитория: файл растёт, а отслеживаемому дереву
# расти незачем. Прокрутка — как у журнала ячейки: 5 МиБ, пять копий.
set -u
LOG="${INSTRUCTIONS_LOG:-$HOME/.claude/rules-loaded.log}"

write_line() {
    PAYLOAD="$(cat)" LOGPATH="$LOG" python3 <<'PY'
import json,os,datetime
raw=os.environ.get("PAYLOAD","")
log=os.environ["LOGPATH"]
try:
    d=json.loads(raw) if raw.strip() else {"_разбор":"вход пуст"}
except Exception:
    d={"_разбор":"вход не JSON","_сырое":raw[:200]}
row=[datetime.datetime.now().isoformat(timespec='seconds'),
     d.get("load_reason", d.get("_разбор","?")),
     d.get("memory_type","?"), d.get("file_path","?"),
     ",".join(d.get("globs") or []), d.get("trigger_file_path","")]
os.makedirs(os.path.dirname(log) or ".", exist_ok=True)
if os.path.exists(log) and os.path.getsize(log) > 5*1024*1024:
    for i in range(4,0,-1):
        a,b=f"{log}.{i}",f"{log}.{i+1}"
        if os.path.exists(a): os.replace(a,b)
    os.replace(log,f"{log}.1")
with open(log,"a") as f: f.write("\t".join(row)+"\n")
PY
}

if [ "${1:-}" = "--selftest" ]; then
    tmp=$(mktemp -d); rc=0
    LOG="$tmp/log"
    echo "Самопроверка tools/instructions-loaded-log.sh"
    printf '%s' '{"hook_event_name":"InstructionsLoaded","file_path":"/p/.claude/rules/ops-bekap.md","memory_type":"Project","load_reason":"path_glob_match","globs":["ops/backup*.sh"],"trigger_file_path":"/p/ops/backup.sh"}' | write_line
    if grep -q 'path_glob_match' "$LOG" && grep -q 'ops-bekap.md' "$LOG" && grep -q 'ops/backup\*.sh' "$LOG"; then
        echo "  ✓ подхват правила записан: причина, файл правила и шаблон"
    else echo "  ✗ запись подхвата неполна"; rc=1; fi
    if grep -q 'ops/backup.sh' "$LOG"; then
        echo "  ✓ назван файл, чьё чтение вызвало подхват"
    else echo "  ✗ вызвавший файл не записан"; rc=1; fi
    printf '%s' 'это не json' | write_line
    grep -q 'вход не JSON' "$LOG" && echo "  ✓ не-JSON назван, а не проглочен" || { echo "  ✗ не-JSON проглочен"; rc=1; }
    printf '' | write_line
    grep -q 'вход пуст' "$LOG" && echo "  ✓ пустой вход назван отдельно от не-JSON" || { echo "  ✗ пустой вход не отличён"; rc=1; }
    printf '%s' '{"load_reason":"session_start","file_path":"/p/CLAUDE.md","memory_type":"Project"}' | write_line
    grep -q 'session_start' "$LOG" && echo "  ✓ загрузка на старте записана" || { echo "  ✗ старт не записан"; rc=1; }
    printf '%s' '{"load_reason":"nested_traversal","file_path":"/p/ops/CLAUDE.md","memory_type":"Project","trigger_file_path":"/p/ops/deploy.sh"}' | write_line
    grep -q 'nested_traversal' "$LOG" && echo "  ✓ подхват вложенной памятки записан" || { echo "  ✗ вложенная памятка не записана"; rc=1; }
    # Пять вызовов выше — пять записей. Первая редакция ждала пять при четырёх
    # вызовах: врало ожидание, не запись, и подгонять число было бы худшим из
    # двух лечений.
    n=$(wc -l < "$LOG" | tr -d ' ')
    [ "$n" = 5 ] && echo "  ✓ записей 5 при 5 вызовах" || { echo "  ✗ записей $n при 5 вызовах"; rc=1; }
    rm -rf "$tmp"
    [ $rc = 0 ] && echo "Самопроверка пройдена: подхват, вызвавший файл, не-JSON и пустой вход различимы." \
                || echo "Самопроверка НЕ пройдена."
    exit $rc
fi
write_line
