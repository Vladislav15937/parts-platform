#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сверка эндпоинтов бэкенда с тем, что зовёт фронтенд.

«Эндпоинт без экрана — это отсутствующая возможность» записано в корневом
CLAUDE.md, и найдено оно восемь раз подряд: сотрудники, склады, цена детали,
кабинет площадки, ссылка на прайс, заказ без клиента, правка всего отбора,
ключ кабинета. Каждый раз одинаково — не чтением кода, а попыткой пройти
сценарий и упереться в то, что кнопки нет. Написанный и покрытый тестами
эндпоинт при этом выглядит работающей возможностью в любом отчёте.

Здесь это ищется перебором: все пути контроллеров против тех, до которых
дотягивается экран. Ниже — разбор каждого несовпадения с причиной; незнакомое
несовпадение валит проверку. Смысл не в том, чтобы список был пустым, а в том,
чтобы новый эндпоинт нельзя было завести молча: либо у него есть экран, либо
здесь написано, почему его нет.

«До которых дотягивается экран», а не «которые встречаются во фронтенде»:
второе засчитывало вызовом сам клиент API, из литералов он и состоит. Как
считается достижимость — в комментарии перед `called` ниже.

  ./tools/endpoint-coverage.py [--list] [--selftest]
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java")
FRONT = os.path.join(ROOT, "frontend", "src")

# Путь -> почему у него нет и не должно быть экрана. Метка в начале строки:
#   существу — фронтенд его и не должен звать;
#   устарел  — заменён другим путём, зовущих нет вовсе;
#   ПРОБЕЛ   — возможность есть в коде и недоступна человеку.
# Третье — не оправдание, а очередь работы: список обязан пустеть. Три пробела
# (место машины, приход партии и её состав) закрыты экранами 3 сентября 2026,
# четвёртый раз подряд оказавшись «написано, покрыто тестами, недоступно».
# Два новых, стоящие ниже, нашлись 9 сентября 2026 — не обходом, а тем, что
# проверка научилась отличать обёртку от экрана (задача 0055): до этого они
# считались вызванными и в отчётах выглядели работающими.
KNOWN = {
    "POST /api/provisioning/tenants":
        "существу: управляющий контур, ходят скрипты с токеном, не браузер",
    "GET /api/provisioning/migrations":
        "существу: то же, шаг развёртывания ops/migrate-tenants.sh",
    "POST /api/provisioning/migrations":
        "существу: то же",
    "GET /api/provisioning/load":
        "существу: то же, замер ячейки",
    "GET /feeds/drom/{company}/{token}.xml":
        "существу: забирает площадка по постоянной ссылке, у неё нет сессии",
    "GET /feeds/drom/{company}/{token}/{file}.xml":
        "существу: тот же прайс по адресу с читаемым именем файла — его "
        "прописывает у себя площадка; экран показывает этот адрес ссылкой, "
        "а зовёт его не фронтенд",
    "GET /feeds/drom/{company}/{token}/photo/{photoId}.jpg":
        "существу: туда же ходит площадка за снимком объявления",
    "POST /api/deals/{dealId}/returns/{returnId}/cancel":
        "существу: завершённый возврат он отклоняет, и экран продавца поэтому "
        "кнопки отмены не показывает вовсе — исправление это встречная продажа",

    "GET /api/deals/expired-reservations":
        "ПРОБЕЛ: просроченные резервы списком человеку не показаны. Обёртка "
        "expiredReservations в sales/sales.ts написана, а зовущего экрана нет; "
        "доска сделок по состояниям заводится задачей 0052",
    "POST /api/part-names/{id}/unmatch":
        "ПРОБЕЛ: снять ошибочное сопоставление написания с экрана нельзя. "
        "Сопоставить можно (matchName зовёт «Наименования»), отменить — нет, "
        "хотя после переезда клиента таких сопоставлений сотни и ошибка "
        "в них — обычное дело. Задачи на экран пока нет",

    "GET /api/catalog/brands":
        "устарел: справочник машин едет одним запросом /api/catalog/vehicles, "
        "по марке и модели это четыре с половиной тысячи запросов",
    "GET /api/catalog/brands/{brandId}/models":
        "устарел: то же",
    "GET /api/catalog/models/{modelId}/generations":
        "устарел: то же",
    "GET /api/parts/search":
        "устарел: продавец ищет /api/parts/stock — там свободный остаток, "
        "а не общий; зовёт этот путь только AuthenticationTest как пробу входа",
    "GET /api/parts/by-oem/{number}":
        "устарел: номер производителя ищется общим поиском продавца и витрины",
    "POST /api/parts/publication":
        "устарел: «Выгружать» правят карточка и правка списком, у которой "
        "отбор шире; этот путь старше их обоих и экрана у него нет",

}

MAPPING = re.compile(r'@(Get|Post|Put|Delete|Patch|Request)Mapping\s*(?:\(([^)]*)\))?')


def literal(arg):
    if not arg:
        return ""
    m = re.search(r'(?:value\s*=\s*)?"([^"]*)"', arg)
    return m.group(1) if m else ""


def normalize(url):
    """Путь без запроса и без имён переменных: {id} и ${dealId} — одно и то же."""
    url = url.split("?")[0].rstrip("/")
    return re.sub(r'\$?\{[^{}]*\}', '{}', url)


def endpoints():
    out = {}
    for base_dir, _, files in os.walk(JAVA):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(base_dir, name)
            text = open(path, encoding="utf-8").read()
            if "Mapping" not in text:
                continue
            head = text.split(" class ")[0]
            prefix = ""
            m = re.search(r'@RequestMapping\s*\(([^)]*)\)', head)
            if m:
                prefix = literal(m.group(1))
            for mm in MAPPING.finditer(text):
                if mm.start() < len(head):
                    continue                     # это класс-уровневый префикс
                verb = mm.group(1).upper()
                if verb == "REQUEST":
                    v = re.search(r'method\s*=\s*RequestMethod\.(\w+)', mm.group(2) or "")
                    verb = v.group(1) if v else "ANY"
                full = (prefix + literal(mm.group(2))) or prefix
                out[f"{verb} {normalize(full)}"] = os.path.relpath(path, ROOT)
    return out


# --- Что здесь считается вызовом -------------------------------------------
#
# Простой перебор литералов «/api/…» по frontend/src отвечал не на тот вопрос.
# Слой клиента API (sales/sales.ts, inventory/inventory.ts, catalog/partNames.ts)
# состоит ровно из таких литералов — это его работа. Значит эндпоинт, которому
# написали обёртку, считался вызванным, даже если обёртку не зовёт ни один
# экран: проверка спрашивала «есть ли обёртка», а обещала «есть ли экран».
# Три возможности проехали мимо неё молча (09.09.2026, задача 0055).
#
# Поэтому литерал привязывается к объявлению, внутри которого он стоит,
# а объявления связываются в граф по импортам и по упоминанию имени. Вызванным
# считается путь, до объявления с которым дотягивается **экран** — файл `.tsx`
# вне тестов. Тесты не корни намеренно: заглушка `fetch`, отвечающая по адресу,
# — это описание сервера, а не кнопка у человека.
#
# Разбор нарочно приблизительный: регулярные выражения вместо разбора TypeScript.
# Ошибается он в безопасную сторону — упоминание имени засчитывается за вызов,
# то есть лишнего красного не даёт, — и стоит ноль зависимостей: проверка должна
# идти на голом раннере, без npm install.
DECL = re.compile(
    r'^(?:export\s+)?(?:default\s+)?(?:async\s+)?'
    r'(?:function|const|let|var|class|interface|type|enum)\s+([A-Za-z_$][\w$]*)')
IMPORT = re.compile(r'import\s+(?:type\s+)?([^;]*?)\s+from\s+[\'"]([^\'"]+)[\'"]', re.S)
NAMESPACE = re.compile(r'^\*\s+as\s+([A-Za-z_$][\w$]*)$')
IDENT = re.compile(r'[A-Za-z_$][\w$]*')
MEMBER = re.compile(r'([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)')
LITERAL = re.compile(r'[\'"`](/(?:api|feeds)/[^\'"`]*)[\'"`]')
CONCAT = re.compile(r'[\'"](/(?:api|feeds)/[^\'"]*)[\'"]\s*\+')

MODULE = "<модуль>"      # литерал вне объявлений: верхний уровень файла


def blank(text, strings=True):
    """Строки и комментарии — пробелами той же длины, смещения сохраняются.

    Нужно, чтобы `/api/deals` в комментарии не считался вызовом, а слово
    внутри строки — упоминанием имени.
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if strings and c in "'\"`":
            j, keep = i + 1, []
            while j < n and text[j] != c:
                if text[j] == "\\":
                    j += 2
                    continue
                # ${...} внутри шаблона — это код, а не текст: вызов, стоящий
                # только там, иначе выглядел бы никем не сделанным
                if c == "`" and text[j:j + 2] == "${":
                    depth, start = 1, j + 2
                    j += 2
                    while j < n and depth:
                        depth += (text[j] == "{") - (text[j] == "}")
                        j += 1
                    # закрывающая скобка гасится вместе с открывающей: счёт
                    # глубины в declarations() иначе разъедется
                    keep.append((start, j - 1))
                    continue
                j += 1
            for k in range(i + 1, min(j, n)):
                if out[k] != "\n" and not any(a <= k < b for a, b in keep):
                    out[k] = " "
            i = j + 1
            continue
        if c == "/" and text[i:i + 2] in ("//", "/*"):
            if text[i + 1] == "/":
                end = text.find("\n", i)
            else:
                end = text.find("*/", i + 2)
                end = end + 2 if end >= 0 else -1
            end = n if end < 0 else end       # незакрытый комментарий — до конца
            for k in range(i, end):
                if out[k] != "\n":
                    out[k] = " "
            i = end
            continue
        i += 1
    return "".join(out)


def frontend_files(root):
    """Файлы фронтенда без тестов: тест — не экран, а описание сервера."""
    for base_dir, _, names in os.walk(root):
        for name in sorted(names):
            if not name.endswith((".ts", ".tsx")) or ".test." in name:
                continue
            path = os.path.join(base_dir, name)
            if os.sep + "test" + os.sep not in path:
                yield path


def resolve(from_file, spec):
    """'../api/client' -> файл. Пакеты из node_modules нас не интересуют."""
    if not spec.startswith("."):
        return None
    base = os.path.normpath(os.path.join(os.path.dirname(from_file), spec))
    for candidate in (base + ".ts", base + ".tsx",
                      os.path.join(base, "index.ts"), os.path.join(base, "index.tsx")):
        if os.path.isfile(candidate):
            return candidate
    return None


def declarations(code):
    """Объявления верхнего уровня и их границы: (имя, начало, конец).

    Глубина считается по скобкам, поэтому объявлением считается только то,
    что начинается с первой колонки вне всяких скобок.
    """
    spans, depth, pos = [], 0, 0
    for line in code.split("\n"):
        if depth == 0:
            m = DECL.match(line)
            if m:
                spans.append([m.group(1), pos, None])
        depth = max(0, depth + line.count("{") + line.count("(") + line.count("[")
                    - line.count("}") - line.count(")") - line.count("]"))
        pos += len(line) + 1
    for i, span in enumerate(spans):
        span[2] = spans[i + 1][1] if i + 1 < len(spans) else len(code)
    return [tuple(s) for s in spans]


def frontend_graph(root):
    """Объявления фронтенда: какие пути внутри и до кого дотягиваются."""
    modules = {}
    for path in frontend_files(root):
        text = open(path, encoding="utf-8").read()
        modules[path] = (text, blank(text), blank(text, strings=False),
                         declarations(blank(text)))

    paths, uses = {}, {}
    for path, (text, code, quoted, spans) in modules.items():
        for name, _, _ in spans:
            paths.setdefault((path, name), set())
        paths.setdefault((path, MODULE), set())

        # что этот файл притащил из соседних
        names, namespaces = {}, {}
        for m in IMPORT.finditer(quoted):
            target = resolve(path, m.group(2))
            if target not in modules:
                continue
            clause = m.group(1).strip()
            ns = NAMESPACE.match(clause)
            if ns:
                namespaces[ns.group(1)] = target
                continue
            for part in re.sub(r'^\{|\}$', ' ', clause.strip()).split(","):
                mm = re.match(r'([A-Za-z_$][\w$]*)(?:\s+as\s+([A-Za-z_$][\w$]*))?$',
                              part.strip())
                if mm:
                    names[mm.group(2) or mm.group(1)] = (target, mm.group(1))

        # литерал -> объявление, внутри которого он стоит
        literals = [(m.start(), normalize(m.group(1))) for m in LITERAL.finditer(text)]
        # '/api/parts/' + id — путь, собранный склейкой
        literals += [(m.start(), normalize(m.group(1)) + "/{}")
                     for m in CONCAT.finditer(text)]
        for start, url in literals:
            owner = MODULE
            for name, begin, end in spans:
                if begin <= start < end:
                    owner = name
            paths[(path, owner)].add(url)

        # Тело верхнего уровня — то, что осталось от файла без объявлений
        # и без строк импорта: импорт не вызов, иначе живым оказалось бы всё,
        # что модуль к себе притащил.
        top = list(code)
        for _, begin, end in spans:
            top[begin:end] = " " * (end - begin)
        for m in IMPORT.finditer(quoted):
            top[m.start():m.end()] = " " * (m.end() - m.start())
        top = "".join(top)

        # рёбра: имя, упомянутое в теле объявления, — это вызов
        own = {name for name, _, _ in spans}
        for name, body in ([(n, code[b:e]) for n, b, e in spans]
                           + [(MODULE, top)]):
            edges = set()
            for m in IDENT.finditer(body):
                ident = m.group(0)
                if ident in names:
                    edges.add(names[ident])
                elif ident in own and ident != name:
                    edges.add((path, ident))
            for m in MEMBER.finditer(body):
                if m.group(1) in namespaces:
                    edges.add((namespaces[m.group(1)], m.group(2)))
            # верхний уровень файла исполняется при импорте: он жив вместе
            # с любым своим объявлением
            if name != MODULE:
                edges.add((path, MODULE))
            uses[(path, name)] = edges

    return modules, paths, uses


def called(root=FRONT):
    """Пути, до которых человек дотягивается с экрана, и осиротевшие обёртки.

    Возвращает (пути, сироты): вторая половина — объявления с путём, до которых
    не дотягивается ни один экран. Именно они и есть тот класс, ради которого
    проверка переписана: обёртка написана, а нажать её негде.
    """
    modules, paths, uses = frontend_graph(root)

    live, stack = set(), [key for key in paths
                          if key[0].endswith(".tsx")]      # экран — это .tsx
    while stack:
        key = stack.pop()
        if key in live:
            continue
        live.add(key)
        stack.extend(edge for edge in uses.get(key, ()) if edge in paths)

    reachable = set()
    for key in live:
        reachable |= paths.get(key, set())
    orphans = {key: urls for key, urls in paths.items()
               if urls and key not in live}
    return reachable, orphans


# Проверка самой проверки. Прежняя редакция считала вызовом любое упоминание
# пути и потому не краснела ни на одном дефекте своего класса — а зелёный цвет
# такой проверки считают доказательством. Здесь три случая, на которых она
# обязана отличаться от прежней; идут они перед каждым прогоном, потому что
# рассуждением это не устанавливается, а стоит миллисекунды.
FIXTURE = {
    "screens/Screen.tsx":
        "import { listThings } from '../api/things';\n"
        "export function Screen() { return listThings(); }\n",
    "api/things.ts":
        "import { request } from './client';\n"
        "export function listThings() { return request('/api/things'); }\n"
        "export function forgottenThing() { return request('/api/things/forgotten'); }\n",
    "screens/thing.test.tsx":
        "const stub = '/api/things/only-in-test';\n",
}


def selftest():
    import shutil
    import tempfile

    root = tempfile.mkdtemp(prefix="endpoint-coverage-")
    try:
        for name, body in FIXTURE.items():
            path = os.path.join(root, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, "w", encoding="utf-8").write(body)
        calls, orphans = called(root)
        wrappers = {name for _, name in orphans}

        failures = []
        if "/api/things" not in calls:
            failures.append("обёртка, которую зовёт экран, не засчитана вызовом")
        if "/api/things/forgotten" in calls:
            failures.append("обёртка без зовущего экрана засчитана вызовом — "
                            "это и есть тот промах, ради которого проверку "
                            "переписали")
        if "forgottenThing" not in wrappers:
            failures.append("обёртка без зовущего экрана не названа сиротой")
        if "/api/things/only-in-test" in calls:
            failures.append("путь, упомянутый только в тесте, засчитан вызовом")
        return failures
    finally:
        shutil.rmtree(root, ignore_errors=True)


def key_of(line):
    """Ключ сравнения: глагол и путь без имён переменных.

    В KNOWN пути записаны с настоящими именами — `{dealId}` читается,
    а `{}` нет, — поэтому обезличиваются они только на сравнении.
    """
    verb, path = line.split(" ", 1)
    return f"{verb} {normalize(path)}"


def main():
    broken = selftest()
    if broken:
        print("Проверка сломана и потому ничего не доказывает:\n")
        for line in broken:
            print("  •", line)
        return 1
    if "--selftest" in sys.argv:
        print("Проверка отличает вызов экраном от упоминания в клиенте API.")
        return 0

    found = endpoints()
    calls, orphans = called()
    listed = {key_of(k): k for k in KNOWN}

    unknown, resolved = [], []
    for key, src in sorted(found.items()):
        verb, path = key.split(" ", 1)
        if path in calls:
            if key in listed:
                # Экран появился — строку пора убрать. Именно pop: оставленная
                # в списке, она следом отчиталась бы ещё и как устаревшая,
                # то есть на одно нарушение пришлось бы два сообщения.
                resolved.append(listed.pop(key))
            continue
        if key in listed:
            listed.pop(key)
            continue
        unknown.append((key, src))

    if "--list" in sys.argv:
        for key, why in sorted(KNOWN.items()):
            print(f"{key}\n    {why}\n")

    problems = []
    for key, src in unknown:
        wrappers = sorted(f"{name} ({os.path.relpath(path, ROOT)})"
                          for (path, name), urls in orphans.items()
                          if key.split(" ", 1)[1] in urls)
        wrote = (f"\n      Обёртка есть — {', '.join(wrappers)}, — но её\n"
                 f"      не зовёт ни один экран." if wrappers else "")
        problems.append(f"{key}\n      объявлен в {src}, а экран его не зовёт.{wrote}\n"
                        f"      Либо у возможности нет экрана — тогда это пробел,\n"
                        f"      либо она не для браузера — тогда напишите почему\n"
                        f"      в KNOWN внутри tools/endpoint-coverage.py.")
    # Обёртка, чей путь зовут откуда-то ещё, эндпоинт не прячет — но она сама
    # мёртвый код, и следующий примет её за рабочий путь к возможности.
    # Эндпоинт, до которого экран не дотягивается вовсе, уже назван выше или
    # разобран в KNOWN: на одно нарушение — одно сообщение.
    said = {key.split(" ", 1)[1] for key in found} - calls
    for (path, name), urls in sorted(orphans.items()):
        if name == MODULE or urls & said:
            continue
        problems.append(f"{name} ({os.path.relpath(path, ROOT)})\n"
                        f"      обёртка над {', '.join(sorted(urls))}, и её не зовёт\n"
                        f"      ни один экран. Либо позовите её, либо уберите:\n"
                        f"      мёртвая обёртка читается следующим как путь\n"
                        f"      к работающей возможности.")
    for key in sorted(listed.values()):
        problems.append(f"{key}\n      числится в KNOWN, а такого эндпоинта нет. "
                        f"Список устарел.")
    for key in sorted(resolved):
        problems.append(f"{key}\n      числится в KNOWN как недоступный, а экран "
                        f"у него уже есть. Уберите строку.")

    if problems:
        print("Эндпоинты и экраны: проверка не прошла\n")
        for p in problems:
            print("  •", p)
        return 1

    gaps = [k for k, v in KNOWN.items() if v.startswith("ПРОБЕЛ")]
    print(f"Эндпоинтов {len(found)}, без экрана {len(KNOWN)} — все разобраны.")
    if gaps:
        print(f"Из них {len(gaps)} — незакрытые пробелы, возможность есть "
              f"в коде и недоступна человеку:")
        for g in sorted(gaps):
            print(f"  {g}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
