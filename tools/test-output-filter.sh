#!/usr/bin/env bash
# Хук Claude Code: обрезает шумный вывод тестовых команд ДО того, как он
# попадёт в контекст агента, и сам живёт в репозитории, а не в ~/.claude —
# чтобы ехать вместе с проектом и быть виден в ревью.
#
#   (вызывается харнессом автоматически как PreToolUse-хук — см. .claude/settings.json)
#   ./tools/test-output-filter.sh --selftest        # проверка самого фильтра
#   ./tools/test-output-filter.sh --run-filtered     # внутренний режим, не звать руками
#
# ЗАЧЕМ. `./mvnw test` отдаёт в контекст тысячи строк Spring/Liquibase-шума,
# из которых агенту нужны отказы и итог. Документ Anthropic про расход токенов
# советует ровно этот приём — хук фильтрует вывод инструмента.
#
# ГЛАВНАЯ ОПАСНОСТЬ, из-за которой половина этого файла — не про фильтрацию,
# а про код возврата. Образцовый хук из документации дописывает пайп к команде
# (`cmd | grep … | head`), а пайп отдаёт код возврата ПОСЛЕДНЕЙ команды
# конвейера — ровно та ловушка, из-за которой `tools/head-green.sh` зовут без
# пайпа (см. комментарий там же). Хук, молча приписавший пайп, превратил бы
# красный прогон в зелёный, а это и есть класс 16 из `docs/defect-classes.md`:
# операция отчитывается успехом, не сделав того, за чем её звали.
#
# КАК УСТРОЕНО. У хуков Claude Code нет поля «заменить то, что уже показано
# модели» — PostToolUse умеет только ДОБАВИТЬ `additionalContext`, а исходный
# вывод инструмента всё равно уедет в контекст целиком. Единственный
# документированный способ изменить то, что увидит модель как результат
# Bash-инструмента, — PreToolUse с `hookSpecificOutput.updatedInput`:
# ПОДМЕНИТЬ КОМАНДУ ДО её исполнения, а не вывод после. Поэтому этот файл
# работает в двух ролях одного скрипта:
#
#   1. Как PreToolUse-хук (без аргументов, JSON на stdin от харнесса):
#      если команда входит в белый список — отвечает JSON с `updatedInput`,
#      где вместо исходной команды подставлена обёртка «выполни оригинал,
#      поймай его код возврата, отфильтруй его вывод, выйди ЕГО кодом»;
#      всё остальное (не Bash, не из белого списка) проходит НЕ ТРОНУТЫМ —
#      хук выходит нулём и не печатает ни байта, то есть харнесс выполняет
#      исходную команду как если бы хука не было вовсе.
#   2. Как сама обёртка (`--run-filtered`, оригинальная команда приходит
#      в переменной окружения TOF_CMD_B64 — base64, чтобы не экранировать
#      кавычки оригинальной команды дважды): исполняет оригинал БУКВАЛЬНО,
#      перехватывает весь его вывод во временный файл, печатает фильтрат,
#      а финальным `exit "$code"` — отдаёт РОВНО тот код возврата, с которым
#      завершился оригинал. Значит выполняется не пайп, а одна команда
#      (сама обёртка), и её код возврата — это код возврата оригинала,
#      явно пронесённый через `$?` и `exit`, а не унаследованный от хвоста
#      конвейера.
#
# ЧТО ЛОВИТ. Только четыре буквальные команды (БЕЛЫЙ список, посимвольно,
# после обрезки пробелов по краям): `./mvnw test`, `mvn test`, `npm test`,
# `npm run test`. Никаких масок и префиксов: `./mvnw -o test` или
# `cd frontend && npm test` в список НЕ входят и идут нетронутыми — названы
# только эти четыре, как велела задача, расширять список своей волей нельзя.
#
# ЧТО ОСТАВЛЯЕТ В ФИЛЬТРАТЕ: строки Maven с `[ERROR]`, `<<< FAILURE`,
# `<<< ERROR`, `BUILD FAILURE`/`BUILD SUCCESS`, `Results:`, `Tests run: …
# Failures: … Errors: …` (и по каждому классу, и итоговую), `[INFO] Running …`
# (какой класс шёл), `[INFO] Total time`/`Finished at`, строки стектрейса
# (`\tat …`), `Caused by:`, `AssertionError`/`AssertionFailedError`; у vitest —
# `FAIL`, `Test Files`, `Tests`, `Duration`, `AssertionError`. Вокруг каждого
# триггера отказа держит окно в 25 строк дальше: само сообщение исключения
# и стектрейс почти всегда идут следом, а не на той же строке.
#
# ЧЕГО НЕ ЛОВИТ, и это сказано прямо, а не умолчанием:
#   - не ловит отказ, у которого при ненулевом коде возврата в фильтрате
#     не нашлось ни одного из маркеров выше (битый отчёт, незнакомый
#     формат вывода). Страховка есть: тогда к фильтрату дописываются
#     последние 60 строк НЕОТФИЛЬТРОВАННОГО вывода целиком — отказ лучше
#     показать избыточно, чем потерять;
#   - не ловит команды вне белого списка вовсе — `./mvnw -o test` (реальная
#     команда из `docs/agent-workflow.md`, «Полный набор») идёт нетронутой,
#     и это сознательное решение исполнителя: задача требовала фильтровать
#     РОВНО названное, а расширение списка — решение о продукте
#     (сколько доверять автоматике), не исполнителя;
#   - не ловит вывод, который долетел бы по стандартному потоку МИМО этого
#     механизма — если харнесс когда-нибудь станет исполнять Bash-команды
#     не через то же `tool_input.command`, подмена перестанет действовать,
#     и это будет видно по тому, что вывод снова станет полным;
#   - число 25 (окно после триггера) и 60 (строк страховки) — решение
#     исполнителя, не измеренный предел: на живом прогоне (1101 тест)
#     ни разу не потребовалось резать стектрейс по этой границе, но
#     экзотически длинный стек Spring (десятки `Caused by:` подряд) её
#     превысит и будет обрезан.
set -u
# НЕ `cd "$(dirname "$0")/.."`, как у соседних сторожей: тем это нужно, чтобы
# читать репозиторий из любого катaлога, а здесь ровно наоборот — обёрнутая
# команда (`--run-filtered`) обязана исполниться в ТОМ каталоге, где её
# запустил харнесс («npm test» ждёт cwd=frontend, «./mvnw test» — корень),
# а не там, где лежит этот скрипт. Подмена cwd на корень репозитория здесь
# была бы тем самым классом ошибок, которого боится вся задача: `npm test`
# молча превращался бы в «ENOENT: нет package.json» под собственной личиной
# хука — найдено живым прогоном, а не рассуждением (`.scratch/real-run-npm-report.txt`
# при первой попытке). `hook_main` ниже сам резолвит свой абсолютный путь
# через `BASH_SOURCE`, cwd ему для этого не нужен.

CTX_AFTER=25     # строк контекста ПОСЛЕ триггера отказа
SAFETY_TAIL=60   # строк исходного вывода, если отказ не распознан триггерами

# --- белый список: буквальное совпадение после обрезки пробелов по краям ---
is_whitelisted() {
  local cmd="$1"
  cmd="${cmd#"${cmd%%[![:space:]]*}"}"   # срезать пробелы слева
  cmd="${cmd%"${cmd##*[![:space:]]}"}"   # срезать пробелы справа
  case "$cmd" in
    "./mvnw test"|"mvn test"|"npm test"|"npm run test") return 0 ;;
    *) return 1 ;;
  esac
}

# --- сам фильтр: один проход awk, окно контекста после триггера ---
# Печатает отфильтрованные строки и строку "… N строк пропущено …" на разрывах.
filter_stream() {
  awk -v ctx="$CTX_AFTER" '
    # ЖЁСТКИЙ триггер открывает новое окно из холода: по нему строка
    # попадает в фильтрат всегда, а следующие ctx строк — вместе с ней.
    function is_hard_trigger(line) {
      if (line ~ /^\[ERROR\]/) return 1
      if (line ~ /<<< FAILURE/) return 1
      if (line ~ /<<< ERROR/) return 1
      if (line ~ /BUILD FAILURE/) return 1
      if (line ~ /BUILD SUCCESS/) return 1
      if (line ~ /Results:/) return 1
      if (line ~ /Tests run:.*Failures:.*Errors:/) return 1
      if (line ~ /^\[INFO\] Running /) return 1
      if (line ~ /^\[INFO\] Total time/) return 1
      if (line ~ /^\[INFO\] Finished at/) return 1
      if (line ~ /^[[:space:]]*FAIL[[:space:]]/) return 1
      if (line ~ /Test Files[[:space:]]/) return 1
      if (line ~ /^[[:space:]]*Tests[[:space:]]/) return 1
      if (line ~ /Duration[[:space:]]/) return 1
      return 0
    }
    # МЯГКИЙ маркер (строка стектрейса, "Caused by:", текст исключения) САМ
    # окно не открывает — он есть и в тестах, упавших внутрь ожидаемого
    # исключения (этот проект нарочно гоняет такие пути, см. корневой
    # CLAUDE.md про идемпотентность и уникальные индексы), и живой прогон
    # (1164 теста, все зелёные) печатает 5618 таких строк, ни одна из которых
    # не отказ. Сделав их жёсткими триггерами, фильтр открывал бы своё окно
    # на КАЖДОЙ — 9494 строки фильтрата вместо нескольких сотен. Внутри уже
    # открытого окна мягкий маркер его ПРОДЛЕВАЕТ (а не просто расходует) —
    # это даёт длинному стеку настоящего отказа дожить до своего конца, даже
    # если он длиннее ctx строк подряд.
    function is_soft_marker(line) {
      if (line ~ /^[[:space:]]+at [A-Za-z_$]/) return 1
      if (line ~ /Caused by:/) return 1
      if (line ~ /AssertionError/) return 1
      if (line ~ /AssertionFailedError/) return 1
      return 0
    }
    {
      h = is_hard_trigger($0)
      if (h) {
        extend = ctx
        keep = 1
      } else if (extend > 0) {
        keep = 1
        if (is_soft_marker($0)) extend = ctx
        else extend--
      } else {
        keep = 0
      }
      if (keep) {
        if (prev > 0 && NR > prev + 1) print "  … " (NR - prev - 1) " строк пропущено …"
        print $0
        prev = NR
      }
    }
  '
}

# --- режим обёртки: исполняет оригинал, фильтрует, несёт его код возврата ---
run_filtered() {
  : "${TOF_CMD_B64:?TOF_CMD_B64 не задана — этот режим не для ручного вызова}"
  local cmd raw filtered total kept code
  cmd="$(printf '%s' "$TOF_CMD_B64" | base64 -d)"
  raw="$(mktemp "${TMPDIR:-/tmp}/tof-raw.XXXXXX")"
  filtered="$(mktemp "${TMPDIR:-/tmp}/tof-filtered.XXXXXX")"

  # Исполняем ОРИГИНАЛЬНУЮ команду как есть — не пайпом, а отдельной
  # инструкцией с перенаправлением, поэтому $? ниже это код возврата
  # именно её, а не чего-либо другого.
  eval "$cmd" > "$raw" 2>&1
  code=$?

  filter_stream < "$raw" > "$filtered"
  total=$(wc -l < "$raw" | tr -d ' ')
  kept=$(wc -l < "$filtered" | tr -d ' ')

  cat "$filtered"
  printf '\n[tools/test-output-filter.sh: исходных строк %s, показано %s, код возврата %s]\n' \
    "$total" "$kept" "$code"

  # Страховка: отказ, которого фильтр не узнал ни одним триггером, не прячем —
  # показываем хвост исходного вывода целиком, а не молчим.
  if [ "$code" != "0" ] && ! grep -qE 'ERROR|FAIL|<<<|AssertionError|AssertionFailedError' "$filtered"; then
    printf '[фильтр не распознал отказ ни одним триггером — хвост исходного вывода (%s строк):]\n' "$SAFETY_TAIL"
    tail -n "$SAFETY_TAIL" "$raw"
  fi

  rm -f "$raw" "$filtered"
  # Код возврата ЭТОЙ функции (и всего скрипта) — явно код возврата
  # оригинальной команды, а не awk/cat/printf, выполнявшихся после неё.
  return "$code"
}

# --- режим хука: читает JSON PreToolUse со stdin, решает за харнесс ---
hook_main() {
  local input tool_name cmd b64 self_abs wrapped
  input="$(cat)"

  tool_name="$(printf '%s' "$input" | jq -r '.tool_name // ""' 2>/dev/null)" || tool_name=""
  if [ "$tool_name" != "Bash" ]; then
    exit 0   # не наш инструмент — не печатаем ничего, харнесс продолжает как обычно
  fi

  cmd="$(printf '%s' "$input" | jq -r '.tool_input.command // ""' 2>/dev/null)" || cmd=""
  if ! is_whitelisted "$cmd"; then
    exit 0   # вне белого списка — команда идёт нетронутой
  fi

  self_abs="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  b64="$(printf '%s' "$cmd" | base64 | tr -d '\n')"
  wrapped="TOF_CMD_B64='$b64' '$self_abs' --run-filtered"

  printf '%s' "$input" | jq -c --arg newcmd "$wrapped" '
    {
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        updatedInput: (.tool_input + {command: $newcmd})
      }
    }'
}

# ============================= САМОПРОВЕРКА =================================
# Без сети и без настоящего mvn/npm: подкладывает свои фикстуры. Проверяет
# ровно то, что названо в задаче опасным местом: белый список срабатывает,
# чёрный список не трогается, код возврата доезжает неизменным через обёртку,
# отказ остаётся читаемым в фильтрате.
selftest() {
  local fail=0
  local tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/tof-selftest.XXXXXX")"
  # Физический путь, а не логический: на macOS /tmp и /var/folders/… —
  # симлинки, bash их логически хранит ($PWD) как написано, а дочерний
  # процесс (npm в пункте 9) зовёт getcwd() и видит разрешённый
  # /private/var/…. Без этой строки сравнение "содержит ли вывод $tmp"
  # ломалось бы само — не из-за cwd обёртки, а из-за того, с чем сравниваем.
  tmp="$(cd "$tmp" && pwd -P)"
  trap 'rm -rf "$tmp"' RETURN

  # Абсолютный путь к себе — тем же способом, что hook_main. "$0" относительный
  # (его дал тот, кто позвал самопроверку) и перестаёт резолвиться, как только
  # проверка сама меняет cwd (пункты 8 и 9 ниже); без этого они проверяли бы
  # не cwd обёртки, а то, нашёлся ли сам файл.
  local self_abs
  self_abs="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"

  pass() { printf '  ✓ %s\n' "$1"; }
  bad()  { printf '  ✗ %s\n' "$1"; fail=1; }

  # 1. Белый список срабатывает: ровно четыре команды дают JSON с updatedInput.
  local c
  for c in "./mvnw test" "mvn test" "npm test" "npm run test" \
           "  npm test  "; do
    if is_whitelisted "$c"; then pass "белый список: '$c' распознана"
    else bad "белый список: '$c' должна распознаваться, не распозналась"; fi
  done

  # 2. Чёрный список не трогается: похожие, но другие команды — мимо.
  for c in "./mvnw -o test" "npm test -- --run" "cd frontend && npm test" \
           "npm run test:watch" "echo npm test" "mvn test -q" "npx vitest run"; do
    if is_whitelisted "$c"; then bad "чёрный список: '$c' не должна распознаваться, распозналась"
    else pass "чёрный список: '$c' не трогается"; fi
  done

  # 3. Хук отвечает корректным JSON с updatedInput для разрешённой команды
  #    и НИЧЕГО не печатает для команды вне списка — тест самого hook_main.
  local out
  out="$(printf '%s' '{"tool_name":"Bash","tool_input":{"command":"npm test"}}' \
    | bash "$0" 2>/dev/null)"
  if printf '%s' "$out" | jq -e '.hookSpecificOutput.updatedInput.command' >/dev/null 2>&1; then
    pass "хук отдаёт updatedInput для разрешённой команды"
  else
    bad "хук должен отдать updatedInput для «npm test», получено: $out"
  fi

  out="$(printf '%s' '{"tool_name":"Bash","tool_input":{"command":"./mvnw -o test"}}' \
    | bash "$0" 2>/dev/null)"
  if [ -z "$out" ]; then
    pass "хук молчит на команде вне белого списка (вывод пуст)"
  else
    bad "хук не должен печатать ничего на «./mvnw -o test», получено: $out"
  fi

  out="$(printf '%s' '{"tool_name":"Read","tool_input":{"file_path":"/x"}}' \
    | bash "$0" 2>/dev/null)"
  if [ -z "$out" ]; then
    pass "хук молчит на инструменте, который не Bash"
  else
    bad "хук не должен печатать ничего на tool_name=Read, получено: $out"
  fi

  # 4. ГЛАВНОЕ: код возврата доезжает неизменным через обёртку --run-filtered,
  #    а не становится кодом awk/cat/tee. Проверяется подряд для 0, 1 и 42 —
  #    число 42 поймало бы приписанный "|| true" или "exit 0" в конце обёртки,
  #    которые на коде 1 остались бы незамеченными. Фиктивная команда обёрнута
  #    в `bash -c '...'`: `exit` внутри голого `eval` завершил бы ВЕСЬ процесс
  #    `--run-filtered` раньше печати фильтрата (ровно это нашлось пунктом 7
  #    ниже при первом прогоне) — настоящие `mvnw`/`npm` этой ловушки не несут,
  #    это отдельный процесс, и здесь он сымитирован тем же способом.
  local want
  for want in 0 1 42; do
    local real_b64
    real_b64="$(printf '%s' "bash -c 'echo пример; exit $want'" | base64 | tr -d '\n')"
    TOF_CMD_B64="$real_b64" bash "$0" --run-filtered >/dev/null 2>&1
    local got=$?
    if [ "$got" = "$want" ]; then
      pass "код возврата $want доезжает через обёртку неизменным"
    else
      bad "код возврата должен быть $want, получен $got"
    fi
  done

  # 5. Обёртка сама не знает про белый список (его проверяет только
  #    hook_main) — проверяем, что она честно проносит код возврата
  #    независимо от содержимого команды: это граница ответственности,
  #    а не дырка.
  real_b64="$(printf '%s' "bash -c 'exit 7'" | base64 | tr -d '\n')"
  TOF_CMD_B64="$real_b64" bash "$0" --run-filtered >/dev/null 2>&1
  if [ "$?" = "7" ]; then
    pass "обёртка проносит код возврата независимо от содержимого команды"
  else
    bad "обёртка должна пронести код 7"
  fi

  # 6. Отказ остаётся читаемым: синтетический шумный вывод Maven с одним
  #    провалившимся тестом внутри тысяч строк шума — фильтрат обязан
  #    содержать имя упавшего теста, причину и итоговый BUILD FAILURE,
  #    а шум (строки Spring/Liquibase) должен быть вырезан.
  local noisy="$tmp/noisy.log"
  {
    local i
    for i in $(seq 1 500); do
      printf '2026-01-0%dT00:00:00  INFO %d --- [main] ru.partsflow.Something : шумная строка %d\n' "$((i % 9 + 1))" "$i" "$i"
    done
    printf '[INFO] Running ru.partsflow.inventory.StockLedgerTest\n'
    printf '[ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.5 s <<< FAILURE! -- in ru.partsflow.inventory.StockLedgerTest\n'
    printf '[ERROR] ru.partsflow.inventory.StockLedgerTest.reserveIsAtomic  Time elapsed: 0.1 s  <<< FAILURE!\n'
    printf 'org.opentest4j.AssertionFailedError: ожидали остаток 3, получили 4\n'
    printf '\tat ru.partsflow.inventory.StockLedgerTest.reserveIsAtomic(StockLedgerTest.java:88)\n'
    for i in $(seq 1 500); do
      printf '2026-01-0%dT00:00:01  INFO %d --- [main] liquibase.changelog : лишняя строка %d\n' "$((i % 9 + 1))" "$i" "$i"
    done
    printf '[INFO] Results:\n'
    printf '[ERROR] Failures:\n'
    printf '[ERROR]   StockLedgerTest.reserveIsAtomic:88 ожидали остаток 3, получили 4\n'
    printf '[ERROR] Tests run: 1101, Failures: 1, Errors: 0, Skipped: 0\n'
    printf '[INFO] BUILD FAILURE\n'
  } > "$noisy"

  local filtered_out
  filtered_out="$(filter_stream < "$noisy")"
  local raw_lines filt_lines
  raw_lines=$(wc -l < "$noisy" | tr -d ' ')
  filt_lines=$(printf '%s\n' "$filtered_out" | wc -l | tr -d ' ')

  if printf '%s' "$filtered_out" | grep -q "reserveIsAtomic"; then
    pass "фильтрат называет упавший тест (StockLedgerTest.reserveIsAtomic)"
  else
    bad "фильтрат должен называть упавший тест, не нашлось"
  fi
  if printf '%s' "$filtered_out" | grep -q "ожидали остаток 3"; then
    pass "фильтрат несёт причину отказа (AssertionFailedError)"
  else
    bad "фильтрат должен нести причину отказа, не нашлось"
  fi
  if printf '%s' "$filtered_out" | grep -q "BUILD FAILURE"; then
    pass "фильтрат несёт итог BUILD FAILURE"
  else
    bad "фильтрат должен нести BUILD FAILURE, не нашлось"
  fi
  if [ "$filt_lines" -lt $((raw_lines / 3)) ]; then
    pass "шум вырезан: $raw_lines строк сырых -> $filt_lines отфильтрованных"
  else
    bad "шум должен быть вырезан заметно, а не на треть: $raw_lines -> $filt_lines"
  fi

  # 7. Страховка: отказ без единого узнанного триггера — хвост сырого вывода
  #    всё равно печатается, а не проглатывается молча. И здесь тоже
  #    `bash -c '...'` — по той же причине, что в п.4: иначе `exit 3` убил бы
  #    процесс `--run-filtered` раньше печати фильтрата и страховки, и тест
  #    зеленел бы по коду возврата, не проверив ровно то, что назван ловить.
  local unknown="$tmp/unknown.log"
  for i in $(seq 1 100); do printf 'совершенно незнакомая строка %d\n' "$i"; done > "$unknown"
  real_b64="$(printf '%s' "bash -c \"cat '$unknown'; exit 3\"" | base64 | tr -d '\n')"
  local safety_out
  safety_out="$(TOF_CMD_B64="$real_b64" bash "$0" --run-filtered 2>&1)"
  if printf '%s' "$safety_out" | grep -q "совершенно незнакомая строка 100"; then
    pass "страховка: нераспознанный отказ всё равно виден (хвост сырого вывода)"
  else
    bad "страховка должна показать хвост сырого вывода на нераспознанном отказе"
  fi

  # 8. ГЛАВНАЯ ЖИВАЯ НАХОДКА (не рассуждение — живой прогон «npm test» нашёл
  #    это буквально при первой попытке, `.scratch/real-run-npm-report.txt`):
  #    обёртка обязана исполнить команду в ТОМ каталоге, в котором её вызвал
  #    харнесс, а не там, где лежит сам скрипт. «npm test» ждёт cwd=frontend;
  #    ранняя редакция начиналась с `cd "$(dirname "$0")/.."` и прыгала
  #    в корень репозитория — «npm test» там отвечал «ENOENT: нет
  #    package.json» под видом кода 254, то есть хук молча ломал ЛЮБУЮ
  #    команду не из корня. Проверяем настоящим `pwd`, а не инспекцией кода.
  # `exit 1` — иначе фильтр честно вырежет голый путь: он не похож ни
  # на один триггер отказа, и при коде 0 страховка молчит по праву.
  # Нужен именно нераспознанный отказ, чтобы страховка напечатала путь как
  # есть — тем же механизмом, что проверен пунктом 7.
  real_b64="$(printf '%s' "bash -c 'pwd; exit 1'" | base64 | tr -d '\n')"
  local cwd_out
  cwd_out="$(cd "$tmp" && TOF_CMD_B64="$real_b64" bash "$self_abs" --run-filtered 2>&1)"
  if printf '%s' "$cwd_out" | grep -qF "$tmp"; then
    pass "обёртка исполняет команду в cwd вызывающего, а не в каталоге скрипта"
  else
    bad "обёртка должна сохранить cwd вызывающего ($tmp), получено: $cwd_out"
  fi

  # 9. Весь путь от хука до исполнения, с тем же живым дефектом: hook_main
  #    из каталога $tmp должен построить команду, которая, выполненная
  #    ИМЕННО в $tmp, печатает путь $tmp, а не корень репозитория.
  local hook_json wrapped end_to_end_out
  hook_json="$(cd "$tmp" && printf '%s' '{"tool_name":"Bash","tool_input":{"command":"npm test"}}' \
    | bash "$self_abs" 2>/dev/null)"
  wrapped="$(printf '%s' "$hook_json" | jq -r '.hookSpecificOutput.updatedInput.command' 2>/dev/null)"
  if [ -n "$wrapped" ] && [ "$wrapped" != "null" ]; then
    # Сама строка $wrapped несёт свою TOF_CMD_B64 инлайном (`VAR=значение
    # команда`), поэтому её достаточно просто исполнить в нужном каталоге —
    # отдельно экспортировать переменную не нужно.
    end_to_end_out="$(cd "$tmp" && eval "$wrapped" 2>&1 || true)"
    # "npm test" в $tmp настоящего package.json не найдёт — это ожидаемо;
    # важно не "упало корректно", а "упало в ПРАВИЛЬНОМ каталоге": npm
    # обязан назвать путь внутри $tmp, а не внутри репозитория.
    if printf '%s' "$end_to_end_out" | grep -qF "$tmp"; then
      pass "цепочка хук -> обёртка исполняется в cwd вызывающего целиком"
    else
      bad "цепочка хук -> обёртка должна остаться в $tmp, получено: $end_to_end_out"
    fi
  else
    bad "цепочка хук -> обёртка: не удалось получить updatedInput для «npm test»"
  fi

  if [ "$fail" = "0" ]; then
    printf '\nСамопроверка пройдена.\n'
  else
    printf '\nСамопроверка НЕ пройдена.\n'
  fi
  return "$fail"
}

MODE="${1:-}"
case "$MODE" in
  --selftest)
    selftest
    exit $?
    ;;
  --run-filtered)
    run_filtered
    exit $?
    ;;
  "")
    hook_main
    exit 0
    ;;
  *)
    printf 'Неизвестный режим: %s\n' "$MODE" >&2
    printf 'Использование: %s [--selftest|--run-filtered]\n' "$0" >&2
    exit 2
    ;;
esac
