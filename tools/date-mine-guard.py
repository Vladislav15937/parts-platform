#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Календарная дата, зашитая в проверку, которая сравнивает её с «сейчас».

18 сентября 2026 волна нашла красную `main`, о которой никто не знал: тест
ждал «до 15 сентября» там, где экран — совершенно правильно — писал «срок
истёк». Падало на чистой `main`, без всяких ветвей, и красило любой PR.
Проверки на `main` последний раз шли 14 сентября, то есть мина спала четыре
дня и сработала не у того, кто её поставил, и не в тот день.

Это класс, а не случай. Дата, зашитая в проверку, — бомба с часовым
механизмом: зелёная сегодня, красная через месяц, причём красная не там,
где ошибка. Хуже, что такая проверка ещё и **перестаёт проверять**
задуманное, оставаясь зелёной: фикстура замера ширины экрана с 13 сентября
2026 показывала ВСЕ карточки доски сделок просроченными — то есть мерила
короткое «срок истёк» вместо длинного «до 12 сентября», ради которого
в неё и положили сделку. Об этом не сообщает никто и никогда.

Отсюда правило: **время в проверке задаётся относительно «сейчас» либо
замораживается явно.**

  ./tools/date-mine-guard.py [--list] [--selftest]

Что именно ищется — и почему не «любая дата в тесте».
--------------------------------------------------------------------------
Литералов даты в фикстурах больше полутора сотен, и почти все безобидны:
`createdAt: '2026-09-05T12:00:00Z'` уезжает на экран строкой и будет верен
всегда. Сторож, краснеющий на каждом, потребовал бы полутора сотен пометок
и был бы отключён в первую же неделю — ложная тревога здесь дороже
пропуска, на неё натыкается каждый прогон.

Мина — это литерал даты у **поля, которое продукт сравнивает с «сейчас»**.
Список таких полей сторож не держит списком, а **выводит из исходников
продукта**: поле, попавшее в выражение с `Date.now()` / `Instant.now()`
и со сравнением или вычитанием. Поэтому новое поле такого рода попадает под
защиту само, без правки сторожа, — а если вывод сломается, сторож скажет
об этом (см. `FLOOR` ниже), вместо того чтобы молча позеленеть.

Вторая половина — **проверки ячейки**: у шелла полей нет, и там ищется
литерал даты в той же функции, где спрашивают настоящее время
(`date +%s`, `time()`). Латентные случаи этого рода — проверка, зависящая
от дня недели или от полуночи, — по исходникам не ловятся вовсе: их ловит
прогон со сдвинутыми часами, `tools/clock-shift-run.sh`. Это названо
нарочно, чтобы на сторожа не полагались шире, чем он есть.

Чего сторож не видит, и это надо знать заранее.
--------------------------------------------------------------------------
1. «Заморожено явно» определяется **по файлу**, а не по отдельному `it`:
   файл, где рядом объявлен `const now = Date.parse('…')` и передаётся
   аргументом, считается замороженным целиком. Файл, замораживающий время
   в одном тесте и сравнивающий с настоящим в другом, сторож пропустит.
   Выбор в пользу пропуска, а не ложной тревоги, — сознательный.
2. Дата, приезжающая в проверку не литералом (из файла, из переменной
   окружения), не видна.
3. Зависимость от дня недели, месяца и полуночи — это `clock-shift-run.sh`.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Где живёт продукт: из него выводятся поля, сравниваемые с «сейчас».
TS_SOURCES = os.path.join("frontend", "src")
JAVA_SOURCES = os.path.join("src", "main", "java")

# Где живут проверки: в них и ищется литерал.
TS_TESTS = os.path.join("frontend", "src")
JAVA_TESTS = os.path.join("src", "test", "java")
SHELL_DIRS = ["ops", "tools", "db"]

# Дата календаря: ISO в строке, `LocalDate.of(2026, 7, 1)`, `YearMonth.of`.
ISO = re.compile(r"""['"](\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2})?[^'"]*)?)['"]""")
JAVA_OF = re.compile(r"\b(?:LocalDate|LocalDateTime|YearMonth)\.of\(\s*(\d{4})\s*,\s*\d+")
SQL_DATE = re.compile(r"\b(?:DATE|TIMESTAMP)\s+'(\d{4}-\d{2}-\d{2}[^']*)'")

# «Сейчас» в исходниках.
TS_NOW = re.compile(r"Date\.now\(\)|new Date\(\s*\)")
JAVA_NOW = re.compile(r"\b(?:Instant|LocalDate|LocalDateTime|ZonedDateTime|YearMonth)\.now\(\)")
# Сравнение или вычитание: display-код дат не сравнивает, он их печатает.
TS_COMPARE = re.compile(r"[<>]=?|(?<![-+])-(?!-)")
JAVA_COMPARE = re.compile(r"\bisBefore\b|\bisAfter\b|\bcompareTo\b|[<>]=?|\bDuration\.between\b")

# Поле — операнд сравнения с «сейчас», а не просто слово в той же строке.
#
# Первая редакция брала все свойства строки и пересекала их с именами полей
# всего проекта — и набрала `id`, `qty`, `status`, `key`, `stock`: в строке
# со сравнением они стоят рядом случайно. Дальше сторож покраснел на дате
# поставки в CatalogServiceTest, то есть на литерале, который только печатают.
# Ложная тревога на первом же прогоне — это отключённый сторож, поэтому
# операнд извлекается прицельно, тремя формами, и других не бывает:
#
#   new Date(deal.replyDeadline).getTime() - now      — дата из свойства;
#   Date.now() - payload.countedAt                    — свойство рядом с «сейчас»;
#   until.getTime() < now, где until = new Date(deal.reservedUntil)
#                                                     — через локальную переменную.
TS_IN_DATE = re.compile(r"(?:new Date|Date\.parse)\(\s*[\w.]*?\.([A-Za-z_]\w*)")
TS_NEXT_TO_NOW = re.compile(
    r"\.([A-Za-z_]\w*)\s*(?:[<>]=?|-)\s*(?:Date\.now\(\)|\bnow\b)"
    r"|(?:Date\.now\(\)|\bnow\b)\s*(?:[<>]=?|-)\s*[\w.]*?\.([A-Za-z_]\w*)")
TS_VIA_LOCAL = re.compile(
    r"\b([A-Za-z_]\w*)\s*\.(?:getTime|valueOf)\(\s*\)\s*(?:[<>]=?|-)\s*(?:Date\.now\(\)|\bnow\b)"
    r"|(?:Date\.now\(\)|\bnow\b)\s*(?:[<>]=?|-)\s*\b([A-Za-z_]\w*)\s*\.(?:getTime|valueOf)\(")
# Локальная переменная, получившая дату из свойства: одна ступень вывода,
# без неё не находится `reservationTerm` — там сравнивают `until`, а поле
# зовётся `reservedUntil` и стоит строкой выше.
TS_LOCAL = re.compile(r"\b(?:const|let|var)\s+(\w+)\s*=\s*(?:new Date|Date\.parse)\(\s*[\w.]*\.([A-Za-z_]\w*)")
# Поле временного типа. Именно типа, а не любое поле: в Java сравнивают само
# поле, и отбор «любое имя поля проекта» давал `id` и `qty`.
JAVA_FIELD = re.compile(
    r"\bprivate\s+(?:final\s+)?(?:java\.(?:time|sql)\.)?"
    r"(?:Instant|LocalDate|LocalDateTime|OffsetDateTime|ZonedDateTime|YearMonth|Timestamp)"
    r"\s+([a-z]\w*)\s*[;=]")
# Операнд сравнения в Java: `reservedUntil.isBefore(now)`,
# `deal.getShareExpires().isBefore(Instant.now())`.
JAVA_OPERAND = re.compile(r"([\w.]*?[A-Za-z_]\w*(?:\(\s*\))?)\s*\.(?:isBefore|isAfter|compareTo)\s*\(")
JAVA_GETTER = re.compile(r"\bget([A-Z]\w*)\s*\(\s*\)")

# Дата, доехавшая до поля через именованную константу:
#
#   const RESERVED_UNTIL = '2026-09-15T12:00:00Z';
#   …
#   reservedUntil: RESERVED_UNTIL,
#
# Это форма **той самой мины**, которая уронила `main` 18 сентября 2026,
# и первая редакция сторожа её не видела: на строке поля литерала нет,
# а на строке литерала нет имени поля. Проверено посадкой — сторож молчал,
# сдвинутый прогон краснел. Сторож, не находящий случай, ради которого
# он заведён, бесполезен ровно там, где нужен.
CONST_DATE = re.compile(
    r"\b(?:const|let|var|(?:static\s+)?final)\s+(?:[\w.<>\[\]]+\s+)?([A-Za-z_]\w*)"
    r"\s*(?::\s*[\w.<>\[\]|\s]+)?=\s*['\"](\d{4}-\d{2}-\d{2}[^'\"]*)['\"]")

# Время, замороженное явно: это разрешённый способ, а не мина.
FROZEN = re.compile(
    r"vi\.(?:useFakeTimers|setSystemTime)"
    r"|(?:const|let|var|final)\s+\w*[Nn]ow\w*\s*=\s*(?:Date\.parse|new Date|Instant\.parse)\(\s*['\"]"
)
# Относительная форма — то, к чему сторож и ведёт.
RELATIVE = re.compile(r"Date\.now\(\)|Instant\.now\(\)|LocalDate\.now\(\)|new Date\(\s*\)")

# «Сейчас» в шелле и в питоньих проверках — и только оно.
#
# `date -d "@$epoch"`, `date -r`, `date -j -f`, `--date=` спрашивают про
# НАЗВАННЫЙ момент, а не про сегодняшний день. Ровно это различие делает
# и подменный `date` в tools/clock-shift-run.sh: сдвигать ответ на «сейчас»
# и не трогать ответ про конкретный момент.
#
# Первая редакция их не различала — считала «сейчас» любой вызов `date`
# с флагом, — и на слиянии с задачей 0096 покраснела на трёх фикстурах
# `ops/restore-pitr.sh`, где момент зашит с ОБЕИХ сторон сравнения, то есть
# на совершенно исправном коде, да ещё в чужом файле. Это и есть та ложная
# тревога, которая дороже пропуска: сторож, краснеющий на зелёном чужом
# файле, будет отключён вместе с настоящей защитой. Поймано швом, а не
# чтением: поодиночке обе ветки были зелёные.
SHELL_NOW_OTHER = re.compile(r"\btime\(\)|\bdatetime\.now\b|\btime\.time\b")
SHELL_DATE_TOKEN = re.compile(r"\bdate\b")


def date_calls(code):
    """Вызовы `date` по одному.

    Границей служит следующий `date`, а не только `)` и конец строки:
    `date +%s || date -r "$f"` — это ДВА вызова, и жадный разбор до скобки
    склеивал их в один. У склеенного находился `-r`, то есть он выглядел
    «названным моментом», и стоящий рядом `date +%s` переставал считаться
    часами — то есть настоящая мина становилась невидимой. Найдено попыткой
    воспроизвести откат: подделка, которая обязана была покраснеть, молчала.
    """
    starts = [m.start() for m in SHELL_DATE_TOKEN.finditer(code)]
    for index, start in enumerate(starts):
        end = starts[index + 1] if index + 1 < len(starts) else len(code)
        chunk = code[start:end]
        cuts = [chunk.find(ch) for ch in ")`\n" if chunk.find(ch) != -1]
        yield chunk[:min(cuts)] if cuts else chunk

# Флаг, подающий момент извне: `-r <эпоха|файл>`, `-j -f <формат> <строка>`,
# `--reference=<файл>`. Такой вызов часов не спрашивает вовсе.
SHELL_MOMENT_FLAG = re.compile(r"(?:^|\s)(?:-r|-j|--reference=)")

# А `-d`/`--date=` двулик, и на этом различии держится вся честность правила:
#
#   date -d "@$epoch"              — названный момент (эпоха);
#   date -d "2026-09-12 13:59:00"  — названный момент (строкой);
#   date -d now                    — ТЕ ЖЕ ЧАСЫ;
#   date -d yesterday              — те же часы со сдвигом;
#   date -d '+1 day'               — то же.
#
# Считать «названным моментом» любой `-d` значило бы ослабить сторожа под свой
# случай: проверка, зашившая дату рядом с `date -d "+1 day"`, — настоящая мина,
# и пропускать её нельзя. Поэтому смотрим на АРГУМЕНТ.
SHELL_DATE_ARG = re.compile(r"(?:^|\s)(?:-d|--date=)[= ]*['\"]?([^'\"]*)")
SHELL_CLOCK_WORD = re.compile(
    r"^\s*(?:now|today|yesterday|tomorrow|[-+]\d|next\b|last\b)|\bago\b", re.I)


def asks_now(code):
    """Спрашивает ли этот код настоящее время — а не про названный момент.

    Чего этот разбор не может: `date -d "$WHEN"`, где переменная в момент
    прогона содержит `now`. Статически такое неразрешимо, и признано
    пропуском сознательно — назвать часами всякую переменную значило бы
    краснеть на `moment.sh` и `restore-pitr.sh`, где в переменной лежит
    именно названный оператором момент.
    """
    if SHELL_NOW_OTHER.search(code):
        return True
    for call in date_calls(code):
        argument = SHELL_DATE_ARG.search(call)
        if argument:
            if SHELL_CLOCK_WORD.search(argument.group(1)):
                return True     # -d now, -d '+1 day' — это часы
            continue            # -d "@$epoch", -d "2026-…" — названный момент
        if SHELL_MOMENT_FLAG.search(call):
            continue            # -r, -j -f, --reference= — момент извне
        return True             # просто `date +%s` — часы
    return False

# Поля, которые вывод обязан находить. Это не рабочий список — рабочий
# выводится из исходников, — а пол под ним: вывод, перестав находить эти
# четыре, сломан, и сторож обязан сказать это словами, а не позеленеть.
# Ровно та же болезнь, ради которой у сторожа эндпоинтов есть самопроверка.
FLOOR = {"reservedUntil", "replyDeadline", "loadedAt"}

# Литерал даты у поля, сравниваемого с «сейчас», разобранный и оставленный.
# Ключ — «путь::поле::литерал» (у проверок ячейки поле зовётся «функция»),
# например:
#
#   "frontend/src/screens/dealsScreen.test.tsx::reservedUntil::2026-09-12T20:59:59Z":
#       "почему эта дата обязана быть календарной, а не относительной",
#
# Причина обязательна: пометка без причины не принимается, иначе список станет
# способом отключить сторожа, а не разбором. Литерал входит в ключ нарочно —
# поменяв дату, пометку придётся подтвердить заново; отказ в безопасную сторону.
#
# Сегодня список пуст, и это не пробел: все шесть найденных перебором мест
# переведены на относительное время, разбирать было нечего.
ALLOWED = {}


def read(path):
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def walk(root, suffixes):
    for base, _, names in os.walk(root):
        if "node_modules" in base or "/target/" in base or "/.git" in base:
            continue
        for name in sorted(names):
            if name.endswith(tuple(suffixes)):
                yield os.path.join(base, name)


def is_test(path):
    return ".test." in os.path.basename(path)


def statements(text):
    """Строки без комментариев — по одной, но с приклеенным продолжением.

    Выражение, разложенное на две строки, иначе разорвалось бы между
    «сейчас» и полем, и находка терялась бы именно там, где перенос строки
    поставил её автор.
    """
    out = []
    buffer = ""
    for raw in text.splitlines():
        line = re.sub(r"//.*$", "", raw)
        line = re.sub(r"^\s*[*#].*$", "", line)
        buffer = (buffer + " " + line).strip() if buffer else line.strip()
        if line.rstrip().endswith((",", "(", "+", "-", "&&", "||", "=")) and len(buffer) < 400:
            continue
        out.append(buffer)
        buffer = ""
    if buffer:
        out.append(buffer)
    return out


def ts_now_tokens(text):
    """Что в этом файле означает «сейчас».

    Кроме `Date.now()` — имя параметра `now`, если он объявлен со значением
    по умолчанию `Date.now()`: так написаны `reservationTerm`
    и `hoursUntilDeadline`, то есть ровно те две функции, вокруг которых
    и случилась первая мина.
    """
    tokens = [TS_NOW]
    if re.search(r"\bnow\b\s*[:=][^;\n]*Date\.now\(\)", text):
        tokens.append(re.compile(r"\bnow\b"))
    return tokens


def fields_compared_with_now_ts(root):
    """Поля, которые фронтенд сравнивает с «сейчас»."""
    found = {}
    for path in walk(root, (".ts", ".tsx")):
        if is_test(path):
            continue
        text = read(path)
        locals_ = dict(TS_LOCAL.findall(text))
        tokens = ts_now_tokens(text)
        for line in statements(text):
            if not any(token.search(line) for token in tokens):
                continue
            if not TS_COMPARE.search(line):
                continue
            names = set()
            names.update(TS_IN_DATE.findall(line))
            for left, right in TS_NEXT_TO_NOW.findall(line):
                names.add(left or right)
            for left, right in TS_VIA_LOCAL.findall(line):
                local = left or right
                if local in locals_:
                    names.add(locals_[local])
            for name in names:
                if not name or name in {"now", "getTime", "valueOf", "length"}:
                    continue
                found.setdefault(name, os.path.relpath(path, ROOT))
    return found


def fields_compared_with_now_java(root):
    """Поля, которые бэкенд сравнивает с «сейчас».

    В Java сравнивают не `x.prop`, а само поле или геттер, поэтому имена
    отбираются по объявлениям полей: без этого в набор попадал бы каждый
    локальный `until` и каждое слово строки.
    """
    texts = {path: read(path) for path in walk(root, (".java",))}
    fields = set()
    for text in texts.values():
        fields.update(JAVA_FIELD.findall(text))

    found = {}
    for path, text in texts.items():
        for line in statements(text):
            if not JAVA_NOW.search(line) and not re.search(r"\bnow\b", line):
                continue
            if not JAVA_COMPARE.search(line):
                continue
            names = set()
            for operand in JAVA_OPERAND.findall(line):
                getter = JAVA_GETTER.search(operand)
                if getter:
                    names.add(getter.group(1)[0].lower() + getter.group(1)[1:])
                else:
                    names.add(operand.rstrip("()").split(".")[-1])
            for name in names & fields:
                found.setdefault(name, os.path.relpath(path, ROOT))
    return found


def snake(name):
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def literals_of(line):
    """Календарные даты этой строки — в любом из трёх написаний."""
    out = [value for value in ISO.findall(line)]
    out += [value for value in SQL_DATE.findall(line)]
    out += ["%s-%s" % (year, "of") for year in JAVA_OF.findall(line)]
    return out


def mines_in_tests(root, suffixes, fields, java=False, base=None):
    """Литерал даты у поля, которое сравнивают с «сейчас».

    `base` — от чего считать путь в сообщении. По умолчанию корень
    репозитория; самопроверка передаёт свой каталог, иначе ключ пометки
    собирался бы из `../../private/tmp/…` и не совпал бы никогда — то есть
    половина сторожа, отвечающая за разбор, не проверялась бы вовсе.
    """
    problems = []
    checked = 0
    for path in walk(root, suffixes):
        if not java and not is_test(path):
            continue
        text = read(path)
        rel = os.path.relpath(path, base or ROOT)
        frozen = bool(FROZEN.search(text))
        consts = dict(CONST_DATE.findall(text))
        for number, raw in enumerate(text.splitlines(), start=1):
            line = re.sub(r"//.*$", "", raw)
            if re.match(r"\s*[*#]", line):
                continue
            # Литерал через константу: на этой строке стоит имя поля и имя
            # константы, а сама дата — выше.
            for name in sorted(fields):
                through = re.search(
                    r"\b%s\b\s*[:=]\s*([A-Za-z_]\w*)" % re.escape(name), line)
                if not through or through.group(1) not in consts:
                    continue
                checked += 1
                value = consts[through.group(1)]
                key = "%s::%s::%s" % (rel, name, value)
                if key in ALLOWED and ALLOWED[key].strip():
                    continue
                if key in ALLOWED:
                    problems.append(
                        "%s:%d — пометка без причины.\n"
                        "      Причина и есть то, что отличает разбор от отписки."
                        % (rel, number))
                    continue
                if frozen:
                    continue
                problems.append(
                    "%s:%d\n"
                    "      `%s` = %s, а в ней зашито «%s» — календарная дата\n"
                    "      у поля, которое сравнивают с «сейчас» (%s).\n"
                    "      Через константу она такая же мина, как на самой строке:\n"
                    "      ровно так была написана та, что уронила `main`\n"
                    "      18 сентября 2026. Считайте от «сейчас»."
                    % (rel, number, name, through.group(1), value, fields[name]))

        for number, raw in enumerate(text.splitlines(), start=1):
            line = re.sub(r"//.*$", "", raw)
            if re.match(r"\s*[*#]", line):
                continue
            values = literals_of(line)
            if not values:
                continue
            for name in sorted(fields):
                # В Java фикстура пишет SQL — `INSERT INTO deal (reserved_until)
                # VALUES ('2026-09-12 …')`, — и имя стоит в списке колонок,
                # то есть перед закрывающей скобкой, а не перед двоеточием.
                # Требовать там разделителя значит не находить ровно тот вид
                # фикстуры, которым в Java и заводят данные.
                pattern = (r"\b(?:%s|%s)\b" if java else r"\b(?:%s|%s)\b\s*(?:[:=]|,|\()") % (
                    re.escape(name), re.escape(snake(name)))
                if not re.search(pattern, line):
                    continue
                checked += 1
                value = values[0]
                key = "%s::%s::%s" % (rel, name, value)
                if key in ALLOWED and ALLOWED[key].strip():
                    continue
                if key in ALLOWED:
                    problems.append(
                        "%s:%d — пометка без причины.\n"
                        "      Причина и есть то, что отличает разбор от отписки."
                        % (rel, number))
                    continue
                if frozen:
                    continue
                problems.append(
                    "%s:%d\n"
                    "      `%s` = «%s» — календарная дата у поля, которое\n"
                    "      сравнивают с «сейчас» (%s). Зелёная сегодня, красная\n"
                    "      или молча бессмысленная через месяц.\n"
                    "      Считайте от «сейчас» (`Date.now() + 3 * DAY_MS`,\n"
                    "      `Instant.now().plus(...)`), заморозьте время явно —\n"
                    "      либо внесите в ALLOWED с причиной."
                    % (rel, number, name, value, fields[name]))
    return problems, checked


def functions_of(text):
    """Куски шелла и питона по границам функций — грубо, по объявлению."""
    bounds = [0]
    lines = text.splitlines()
    for number, line in enumerate(lines):
        if re.match(r"^\s*(?:function\s+\w+|\w+\s*\(\)\s*\{|def\s+\w+)", line):
            bounds.append(number)
    bounds.append(len(lines))
    for start, end in zip(bounds, bounds[1:]):
        yield start + 1, "\n".join(lines[start:end])


def mines_in_checks(dirs):
    """Литерал даты в той же функции, где спрашивают настоящее время."""
    problems = []
    checked = 0
    for name in dirs:
        root = os.path.join(ROOT, name)
        if not os.path.isdir(root):
            continue
        for path in walk(root, (".sh", ".py")):
            rel = os.path.relpath(path, ROOT)
            if rel == os.path.join("tools", "date-mine-guard.py"):
                continue
            for first, block in functions_of(read(path)):
                code = "\n".join(
                    line for line in block.splitlines()
                    if not re.match(r"\s*#", line))
                if not asks_now(code):
                    continue
                for offset, line in enumerate(code.splitlines()):
                    values = literals_of(line)
                    if not values:
                        continue
                    checked += 1
                    key = "%s::функция::%s" % (rel, values[0])
                    if key in ALLOWED and ALLOWED[key].strip():
                        continue
                    problems.append(
                        "%s:~%d\n"
                        "      «%s» — календарная дата в проверке, которая\n"
                        "      в той же функции спрашивает настоящее время.\n"
                        "      Либо считайте от «сейчас», либо внесите\n"
                        "      в ALLOWED с причиной."
                        % (rel, first + offset, values[0]))
    return problems, checked


# ─────────────────────────────── самопроверка ────────────────────────────────
#
# Проверка, которая не краснеет на подделке, хуже отсутствующей: она создаёт
# уверенность. Поэтому самопроверка идёт ПЕРЕД каждым прогоном и проверяет
# обе стороны — и что сторож краснеет на мине, и что он молчит на безобидном
# литерале. Второе не менее важно первого: ложная тревога здесь дороже
# пропуска, потому что на неё натыкается каждый прогон.

SOURCE_TS = """
export function reservationTerm(
  deal: { status: string; reservedUntil: string | null }, now: number = Date.now(),
) {
  const until = new Date(deal.reservedUntil);
  return { expired: until.getTime() < now };
}
export function isStale(reference: { loadedAt: string }): boolean {
  const age = Date.now() - new Date(reference.loadedAt).getTime();
  return age > 12 * 3600 * 1000;
}
export function shortDate(row: { createdAt: string }): string {
  return new Date(row.createdAt).toLocaleDateString('ru-RU');
}
"""

SOURCE_JAVA = """
class Deal {
    private java.time.Instant reservedUntil;
    private java.time.Instant createdAt;
    boolean isReservationExpired(java.time.Instant now) {
        return reservedUntil != null && reservedUntil.isBefore(now);
    }
}
"""

MINE_TS = """
const DEALS = [
  { id: 8, status: 'RESERVED', reservedUntil: '2026-09-12T20:59:59Z',
    createdAt: '2026-09-05T12:00:00Z' },
];
"""

CLEAN_TS = """
const DAY_MS = 86_400_000;
const DEALS = [
  { id: 8, status: 'RESERVED',
    reservedUntil: new Date(Date.now() + 3 * DAY_MS).toISOString(),
    createdAt: '2026-09-05T12:00:00Z' },
];
"""

# Дата через константу — форма той самой мины, что уронила `main`.
MINE_CONST_TS = """
const RESERVED_UNTIL = '2026-09-15T12:00:00Z';
const DEALS = [
  { id: 11, status: 'RESERVED', reservedUntil: RESERVED_UNTIL },
];
"""

# И обратный край: та же константа, посчитанная от «сейчас», — правильная
# запись, и краснеть на ней нельзя. Именно так написаны фикстуры после правки,
# то есть без этого случая сторож ругался бы на починку.
CLEAN_CONST_TS = """
const DAY_MS = 86_400_000;
const RESERVED_UNTIL = new Date(Date.now() + 5 * DAY_MS).toISOString();
const DEALS = [
  { id: 11, status: 'RESERVED', reservedUntil: RESERVED_UNTIL },
];
"""

FROZEN_TS = """
const now = Date.parse('2026-07-31T12:00:00Z');
it('считает остаток', () => {
  const deal = dealWith();
  deal.reservedUntil = '2026-07-31T14:00:00Z';
  expect(reservationTerm(deal, now).expired).toBe(false);
});
"""

MINE_JAVA = """
class DealFixtureTest {
    void fixture() {
        jdbc.update("INSERT INTO deal (reserved_until) VALUES ('2026-09-12 20:59:59')");
    }
    void display() {
        jdbc.update("INSERT INTO supply (arrived_on) VALUES (DATE '2026-08-30')");
    }
}
"""

# Две функции, каждая из которых ОБЯЗАНА быть названа: литерал рядом
# с настоящими часами. Вторая — та самая, которую ослабленное правило
# пропустило бы: `-d` со сдвигом от «сейчас» это те же часы.
CHECK_MINE = """
selftest() {
    local now
    now=$(date +%s)
    out=$(report '2026-09-12 10:00:05')
}
relative_flag() {
    tomorrow=$(date -d "+1 day" '+%F')
    out=$(report '2026-09-12 10:00:05')
}
two_calls() {
    stamp=$(date +%s || date -r "$f" '+%s')
    out=$(report '2026-09-12 10:00:05')
}
"""

# И три, которые обязаны молчать: часов не спрашивают вовсе либо спрашивают
# про НАЗВАННЫЙ момент. Ровно так устроены `ops/moment.sh`
# и `ops/restore-pitr.sh`, где момент зашит с обеих сторон сравнения, —
# на них сторож краснел после слияния 0096, и это была ложная тревога.
CHECK_QUIET = """
inert() {
    out=$(report '2026-09-12 10:00:05')
}
epoch_moment() {
    here=$(date -d "@${MOMENT_EPOCH}" '+%H:%M:%S' || date -r "${MOMENT_EPOCH}" '+%H:%M:%S')
    out=$(report '2026-09-12 13:59:00 MSK')
}
named_absolute() {
    when=$(date -u -d "2026-09-12 13:59:00" +%s || date -u -j -f '%F %T' "2026-09-12 13:59:00" +%s)
    out=$(report '2026-09-12 19:20:00 MSK')
}
"""


def selftest():
    import shutil
    import tempfile

    root = tempfile.mkdtemp(prefix="date-mine-guard-")
    failures = []
    try:
        def put(relative, body):
            path = os.path.join(root, relative)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(body)

        put("frontend/src/sales/sales.ts", SOURCE_TS)
        put("src/main/java/Deal.java", SOURCE_JAVA)
        put("frontend/src/screens/mine.test.tsx", MINE_TS)
        put("frontend/src/screens/clean.test.tsx", CLEAN_TS)
        put("frontend/src/screens/frozen.test.tsx", FROZEN_TS)
        put("frontend/src/screens/mineConst.test.tsx", MINE_CONST_TS)
        put("frontend/src/screens/cleanConst.test.tsx", CLEAN_CONST_TS)
        put("src/test/java/DealFixtureTest.java", MINE_JAVA)
        put("ops/check.sh", CHECK_MINE)
        put("ops/quiet.sh", CHECK_QUIET)

        # 1. Вывод полей: три находятся, `createdAt` — нет. Второе не менее
        #    важно: попади оно в набор, сторож покраснел бы на сотне фикстур.
        ts = fields_compared_with_now_ts(os.path.join(root, "frontend", "src"))
        if "reservedUntil" not in ts or "loadedAt" not in ts:
            failures.append("вывод не нашёл поля, которые продукт сравнивает "
                            "с «сейчас» (%s) — сторож стережёт пустоту"
                            % ", ".join(sorted(ts)))
        if "createdAt" in ts:
            failures.append("`createdAt` попал в набор сравниваемых с «сейчас»: "
                            "его только печатают. Сторож покраснеет на каждой "
                            "фикстуре подряд, и его отключат")

        java = fields_compared_with_now_java(os.path.join(root, "src", "main", "java"))
        if "reservedUntil" not in java:
            failures.append("в Java вывод не нашёл поле, сравниваемое с «сейчас»")
        if "createdAt" in java:
            failures.append("`createdAt` попал в набор и в Java")

        # 2. Мина названа, и названа с файлом и строкой.
        problems, _ = mines_in_tests(
            os.path.join(root, "frontend", "src"), (".ts", ".tsx"), ts, base=root)
        text = "\n".join(problems)
        if "mine.test.tsx" not in text:
            failures.append("зашитая дата у поля, сравниваемого с «сейчас», "
                            "не названа — это ровно тот дефект, ради которого "
                            "сторож заведён")
        if "mine.test.tsx:3" not in text and "mine.test.tsx:2" not in text:
            failures.append("файл назван, а строка — нет: искать придётся руками")

        # 3. Обратный край — на безобидном сторож молчит.
        if "clean.test.tsx" in text:
            failures.append("относительная дата названа нарушением: сторож "
                            "краснеет на правильной записи")
        if "createdAt" in text:
            failures.append("литерал у поля, которое только печатают, назван "
                            "нарушением — ложная тревога, которую отключат")
        if "frozen.test.tsx" in text:
            failures.append("явно замороженное время названо нарушением, "
                            "а это второй разрешённый способ")

        # 3б. Дата, доехавшая до поля через именованную константу. Это форма
        #     мины, уронившей `main` 18 сентября 2026, и первая редакция
        #     сторожа её не видела: на строке поля литерала нет, на строке
        #     литерала нет поля. Установлено посадкой, а не чтением.
        if "mineConst.test.tsx" not in text:
            failures.append("дата, доехавшая до поля через константу, не найдена — "
                            "а ровно так написана та мина, ради которой сторож "
                            "и заведён")
        if "cleanConst.test.tsx" in text:
            failures.append("константа, посчитанная от «сейчас», названа "
                            "нарушением: сторож краснеет на починке")

        # 4. Java: SQL-фикстура с `reserved_until` поймана, а `arrived_on` — нет.
        problems, _ = mines_in_tests(
            os.path.join(root, "src", "test", "java"), (".java",), java,
            java=True, base=root)
        text = "\n".join(problems)
        if "DealFixtureTest.java" not in text:
            failures.append("в Java зашитая дата у сравниваемого поля не найдена: "
                            "фикстуры там пишут SQL, и snake_case обязан считаться")
        if "arrived_on" in text or "supply" in text:
            failures.append("дата у поля, которое только печатают, названа "
                            "нарушением и в Java")

        # 5. Проверка ячейки: литерал рядом с настоящим временем — находка,
        #    тот же литерал без «сейчас» — нет.
        problems, _ = mines_in_checks_at(root, ["ops"])
        text = "\n".join(problems)
        if "check.sh" not in text:
            failures.append("в проверке ячейки литерал даты рядом с `date +%s` "
                            "не назван")
        if text.count("check.sh") != 3:
            failures.append(
                "не названы миной все три случая с настоящими часами: "
                "`date +%%s`, сдвиг от «сейчас» (`date -d \"+1 day\"` — это "
                "те же часы) и два вызова в одной строке (`date +%%s || "
                "date -r \"$f\"` — жадный разбор склеивал их в один, и часы "
                "прятались за `-r` соседа). Названо %d из 3"
                % text.count("check.sh"))
        if "quiet.sh" in text:
            failures.append(
                "назван нарушением литерал, который сравнивать с «сейчас» "
                "никто не собирается: либо функция вовсе не спрашивает время, "
                "либо спрашивает про НАЗВАННЫЙ момент — `date -d \"@$epoch\"`, "
                "`-d \"2026-09-12 13:59:00\"`, `-r`, `-j -f`. Так устроены "
                "`ops/moment.sh` и самопроверка `ops/restore-pitr.sh`, где "
                "момент зашит с ОБЕИХ сторон сравнения: на них сторож "
                "покраснел после слияния 0096, и это была ложная тревога")

        # 6. Пометка: с причиной — молчит, без причины — красное. Иначе список
        #    станет способом отключить сторожа, а не разбором.
        key = "frontend/src/screens/mine.test.tsx::reservedUntil::2026-09-12T20:59:59Z"
        saved = dict(ALLOWED)
        try:
            ALLOWED.clear()
            ALLOWED[key] = "разобрано: сделка выдана, срок не считается вовсе"
            problems, _ = mines_in_tests(
                os.path.join(root, "frontend", "src"), (".ts", ".tsx"), ts, base=root)
            if any("mine.test.tsx" in p for p in problems):
                failures.append("пометка с причиной не снимает находку")
            ALLOWED[key] = "   "
            problems, _ = mines_in_tests(
                os.path.join(root, "frontend", "src"), (".ts", ".tsx"), ts, base=root)
            if not any("без причины" in p for p in problems):
                failures.append("пометка без причины принята")
        finally:
            ALLOWED.clear()
            ALLOWED.update(saved)
    finally:
        shutil.rmtree(root, ignore_errors=True)
    return failures


def mines_in_checks_at(root, dirs):
    """То же, что `mines_in_checks`, но с подменённым корнем — для самопроверки."""
    global ROOT
    real, ROOT = ROOT, root
    try:
        return mines_in_checks(dirs)
    finally:
        ROOT = real


def main():
    broken = selftest()
    if broken:
        print("Проверка сломана и потому ничего не доказывает:\n")
        for line in broken:
            print("  •", line)
        return 1
    if "--selftest" in sys.argv:
        print("Сторож краснеет на зашитой дате у поля, которое сравнивают\n"
              "с «сейчас», — в том числе доехавшей туда через именованную\n"
              "константу, как та мина, что уронила `main` 18 сентября 2026.\n"
              "И молчит на дате, которую только печатают, на относительной\n"
              "записи (включая относительную константу), на явно замороженном\n"
              "времени и на пометке с причиной. Пометку без причины\n"
              "не принимает.")
        return 0

    ts_fields = fields_compared_with_now_ts(os.path.join(ROOT, TS_SOURCES))
    java_fields = fields_compared_with_now_java(os.path.join(ROOT, JAVA_SOURCES))

    problems = []
    missing = FLOOR - set(ts_fields) - set(java_fields)
    if missing:
        problems.append(
            "вывод полей сломан: не нашлось %s.\n"
            "      Сторож стерёг бы пустоту и молча зеленел — а это хуже,\n"
            "      чем его отсутствие." % ", ".join(sorted(missing)))

    found_ts, seen_ts = mines_in_tests(
        os.path.join(ROOT, TS_TESTS), (".ts", ".tsx"), ts_fields)
    found_java, seen_java = mines_in_tests(
        os.path.join(ROOT, JAVA_TESTS), (".java",), java_fields, java=True)
    found_checks, seen_checks = mines_in_checks(SHELL_DIRS)
    problems += found_ts + found_java + found_checks

    if "--list" in sys.argv:
        print("Поля, которые продукт сравнивает с «сейчас» — выведено "
              "из исходников:\n")
        for name, where in sorted({**ts_fields, **java_fields}.items()):
            print("  %-18s %s" % (name, where))
        print()
        if ALLOWED:
            print("Разобранные литералы:\n")
            for key, why in sorted(ALLOWED.items()):
                print("  %s\n      %s\n" % (key, why))
        else:
            print("Разобранных литералов нет: все найденные переведены "
                  "на относительное время.\n")

    if problems:
        print("Календарные даты в проверках: проверка не прошла\n")
        for problem in problems:
            print("  •", problem)
        return 1

    print("Полей, сравниваемых с «сейчас», %d; литералов у них в проверках %d — "
          "все относительны либо разобраны.\n"
          "День недели, месяц и полночь по исходникам не ловятся — их проверяет "
          "./tools/clock-shift-run.sh."
          % (len({**ts_fields, **java_fields}), seen_ts + seen_java + seen_checks))
    return 0


if __name__ == "__main__":
    sys.exit(main())
