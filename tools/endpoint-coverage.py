#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сверка эндпоинтов бэкенда с тем, что зовёт фронтенд.

«Эндпоинт без экрана — это отсутствующая возможность» записано в корневом
CLAUDE.md, и найдено оно восемь раз подряд: сотрудники, склады, цена детали,
кабинет площадки, ссылка на прайс, заказ без клиента, правка всего отбора,
ключ кабинета. Каждый раз одинаково — не чтением кода, а попыткой пройти
сценарий и упереться в то, что кнопки нет. Написанный и покрытый тестами
эндпоинт при этом выглядит работающей возможностью в любом отчёте.

Здесь это ищется перебором: все пути контроллеров против всех путей,
встречающихся во фронтенде. Ниже — разбор каждого несовпадения с причиной;
незнакомое несовпадение валит проверку. Смысл не в том, чтобы список был
пустым, а в том, чтобы новый эндпоинт нельзя было завести молча: либо у него
есть экран, либо здесь написано, почему его нет.

  ./tools/endpoint-coverage.py [--list]
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
# Третье — не оправдание, а очередь работы: список обязан пустеть.
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
    "GET /feeds/drom/{company}/{token}/photo/{photoId}.jpg":
        "существу: туда же ходит площадка за снимком объявления",
    "POST /api/deals/{dealId}/returns/{returnId}/cancel":
        "существу: завершённый возврат он отклоняет, и экран продавца поэтому "
        "кнопки отмены не показывает вовсе — исправление это встречная продажа",

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

    "POST /api/intake/donors/{id}/location":
        "ПРОБЕЛ: где стоит машина, знает только база. Поле donor.location "
        "заполняется этим путём и не показывается ни на одном экране — "
        "на площадке с полусотней машин это единственный способ её найти",
    "POST /api/intake/supplies/{id}/arrived":
        "ПРОБЕЛ: отметить, что контейнер приехал, нечем. Дата прихода "
        "объявлена типом во фронтенде (reference.ts) и не показывается нигде",
    "GET /api/intake/supplies/{id}/donors":
        "ПРОБЕЛ: «какие машины пришли этой партией» — вопрос, ради которого "
        "поставки и заведены; ответить на него с экрана нельзя",
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


def called():
    """Пути, встречающиеся во фронтенде: строкой, шаблоном или склейкой."""
    out = set()
    for base_dir, _, files in os.walk(FRONT):
        for name in files:
            if not name.endswith((".ts", ".tsx")):
                continue
            text = open(os.path.join(base_dir, name), encoding="utf-8").read()
            for m in re.finditer(r'[\'"`](/(?:api|feeds)/[^\'"`]*)[\'"`]', text):
                out.add(normalize(m.group(1)))
            # '/api/parts/' + id — путь, собранный склейкой
            for m in re.finditer(r'[\'"](/(?:api|feeds)/[^\'"]*)[\'"]\s*\+', text):
                out.add(normalize(m.group(1)) + "/{}")
    return out


def key_of(line):
    """Ключ сравнения: глагол и путь без имён переменных.

    В KNOWN пути записаны с настоящими именами — `{dealId}` читается,
    а `{}` нет, — поэтому обезличиваются они только на сравнении.
    """
    verb, path = line.split(" ", 1)
    return f"{verb} {normalize(path)}"


def main():
    found = endpoints()
    calls = called()
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
        problems.append(f"{key}\n      объявлен в {src}, а фронтенд его не зовёт.\n"
                        f"      Либо у возможности нет экрана — тогда это пробел,\n"
                        f"      либо она не для браузера — тогда напишите почему\n"
                        f"      в KNOWN внутри tools/endpoint-coverage.py.")
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
