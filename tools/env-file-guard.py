#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Путь к файлу окружения ячейки разрешается в ОДНОМ месте.

  ./tools/env-file-guard.py             сторож (самопроверка идёт первой)
  ./tools/env-file-guard.py --list      что сторож видит в каждом скрипте
  ./tools/env-file-guard.py --selftest  только самопроверка

ЗАЧЕМ. Правило ровно одно — «абсолютный путь берём как есть, относительный
разрешаем от корня репозитория», — и к 2 октября 2026 копий его было ЧЕТЫРЕ,
из них три поломанных. Проверка шла по непрефиксованному пути
(`[ -f "$ENV_FILE" ]`), а чтение — по пути с «./» впереди, при том что скрипт
выше уже сделал `cd` в корень репозитория: `ENV_FILE=/srv/parts/.env` —
ровно так файл и лежит на ячейке — проверку ПРОХОДИЛ, а источался как
«.//srv/parts/.env».

Чинилось это по одному месту за раз (0243 — накат схем, 0197 — признак
управляющего контура), и хвост нашёлся шире: самопроверки выкладки, дымовой
прогон и сторож канала тревог. У всех трёх `set -uo pipefail` БЕЗ `-e`,
поэтому отказ чтения печатал одну строку в stderr, а прогон продолжался
с умолчаниями — как если бы файла не было вовсе, — и отказ называл СОСЕДНЮЮ
причину: «нет ни SMOKE_URL, ни APP_DOMAIN в <файл>» про файл, в котором
они записаны. Человек идёт смотреть файл, видит там обе строки и не понимает
ответа.

Правило, переписанное руками в пятый раз, ошибётся в пятый раз. Поэтому оно
живёт в `ops/env-file.sh`, а этот сторож следит, чтобы копия не завелась
молча: либо путь разрешается общим местом, либо у места НАПИСАНО, почему оно
своё.

ЧТО ПРОВЕРЯЕТСЯ. Три правила.

  1. НИ ОДНОГО пути, собранного литералом «./» перед переменной, в команде
     сорсинга. Это сама форма дефекта: проверка и чтение расходятся,
     и абсолютный путь не открывается вовсе.

  2. Скрипт, который ЧИТАЕТ файл окружения, либо берёт путь из общего места,
     либо назван в ALLOWED с причиной. Пометка без причины не принимается:
     иначе её начнут писать не думая.

  3. Общее место на месте и несёт обе функции. Сторож, у которого отняли
     предмет проверки, молчал бы ровно там, где нужен (урок 0181).

ЧЕГО ЭТОТ СТОРОЖ НЕ ЛОВИТ — часть договора, а не оговорка: названный предел,
которого нет, хуже неназванного.

  * чтение файла окружения НЕ сорсингом: `grep`/`sed` по `.env` (так делает
    `ops/deploy.sh`, и у него свой разбор) или `--env-file` у compose (так
    делают `ops/config-guard.sh` и `ops/apply-config.sh`). Пути там не
    собирают и «./» им не нужно;
  * подделку смыслом: общее место позвали, а ответ его выбросили. Силуэта
    у такого нет, это предмет самопроверок самих скриптов (возврат дефекта
    у каждого из трёх);
  * Java, TS и python: там путей к `.env` ячейки нет вовсе.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULE = "ops/env-file.sh"
NEEDED = ("resolve_env_file", "env_file_state", "env_file_source")

# Места, которые разрешают путь СВОИМ кодом, — с причиной у каждого.
# Пометка — это очередь работы, а не разрешение: у всех девяти абсолютный
# путь сегодня работает, но три состояния файла («нет» · «есть, а прочитать
# не удалось» · «прочитан») различает только общее место.
ALLOWED = {
    "ops/control-plane.sh":
        "читает файл в ПОДОБОЛОЧКЕ намеренно (0197): иначе десятки чужих "
        "переменных ячейки, включая секреты, попали бы в окружение скрипта "
        "и его детей — а в `ps` их аргументов. Общий env_file_source сорсит "
        "в текущую оболочку, то есть переезд сменил бы это свойство. Своя "
        "ветка пути там уже верная — её нашла его же самопроверка.",
    "ops/create-roles.sh":
        "своя форма «путь со слэшем не префиксуем» (`*/*`): абсолютный путь "
        "работает. ENV_FILE у него local внутри main, и переезд требует "
        "правки его самопроверки — десять случаев, шаг CI. Очередь работы.",
    # Семь мест одной формы: сорсят НЕПРЕФИКСОВАННЫЙ путь.
    "ops/backup.sh": "сорсит непрефиксованный путь: абсолютный работает. "
                     "Другая половина (три состояния файла, каталог вместо "
                     "файла по 0101) — отдельная ветка; названа в 0250.",
    "ops/basebackup.sh": "то же, что у ops/backup.sh: непрефиксованный путь.",
    "ops/restore-cell.sh": "то же: непрефиксованный путь, аварийный путь "
                           "трогается отдельной веткой.",
    "ops/restore-pitr.sh": "то же: непрефиксованный путь.",
    "ops/restore-tenant.sh": "то же: непрефиксованный путь.",
    "ops/verify-backup.sh": "то же: непрефиксованный путь.",
    "ops/wal-archive.sh": "то же: непрефиксованный путь.",
}

# Сорсинг пути, собранного литералом «./» перед переменной, — сама форма
# дефекта. Образец собирается на ходу: написанный литералом, он нашёлся бы
# в этом же файле, и сторож краснел бы на собственном тексте (уроки 0195, 0214).
DOTSLASH = re.compile(
    r'(?:^|[;&|]\s*|\s)(?:\.|source)\s+"?' + re.escape("./") + r"\$")

# Сорсинг чего-либо, начинающегося с подстановки переменной.
SOURCES_VAR = re.compile(r'(?:^|[;&|]\s*|\s)(?:\.|source)\s+"?\$')

# Имена, по которым видно, что сорсят именно файл окружения.
ENV_NAMES = re.compile(
    r'(?:^|[;&|]\s*|\s)(?:\.|source)\s+"?\$\{?'
    r"(?:ENV_FILE|ENV_PATH|env_path|env_file_path|src)\b")


def code_lines(text):
    """Строки без комментариев: правило ищется в КОДЕ, а не в прозе.

    Иначе сторож краснел бы на объяснениях — в том числе на том, что
    написано в самом ops/env-file.sh про починенный дефект."""
    out = []
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            continue
        out.append(line)
    return out


def reads_env(text):
    """Читает ли скрипт файл окружения сорсингом."""
    lines = code_lines(text)
    if any(ENV_NAMES.search(line) for line in lines):
        return True
    # Сорсинг по переменной в скрипте, который вообще знает про ENV_FILE:
    # имя переменной может быть любым, а завести копию правила молча нельзя.
    if "ENV_FILE" in text:
        return any(SOURCES_VAR.search(line) for line in lines)
    return False


def uses_module(text):
    """Берёт ли путь из общего места."""
    return "env-file.sh" in text and any(name in text for name in NEEDED)


def check(scripts, module_text, allowed):
    """Список нарушений. Пусто — правило держится.

    scripts: {путь: текст}, module_text: текст общего места или None.
    """
    problems = []

    if module_text is None:
        problems.append(
            f"нет общего места {MODULE} — сторожу не за чем следить: "
            "правило снова разойдётся по скриптам")
    else:
        missing = [name for name in NEEDED if f"{name}()" not in module_text]
        if missing:
            problems.append(
                f"{MODULE} не несёт {', '.join(missing)} — общее место есть, "
                "а правила в нём нет")

    for path in sorted(scripts):
        text = scripts[path]
        for number, line in enumerate(text.splitlines(), 1):
            if line.lstrip().startswith("#"):
                continue
            if DOTSLASH.search(line):
                problems.append(
                    f"{path}:{number} — путь к файлу окружения собран "
                    'литералом «./» перед переменной. Абсолютный путь так '
                    "не открывается вовсе: он превращается в «.//srv/…». "
                    f"Разрешайте путь общим местом ({MODULE})")

        if not reads_env(text):
            continue
        if uses_module(text):
            continue
        reason = allowed.get(path)
        if reason is None:
            problems.append(
                f"{path} читает файл окружения своим кодом. Либо путь "
                f"разрешается общим местом ({MODULE}), либо впишите место "
                "в ALLOWED внутри tools/env-file-guard.py и скажите, почему "
                "оно своё")
        elif not reason.strip():
            problems.append(
                f"{path} — пометка в ALLOWED без причины не принимается: "
                "напишите, почему путь разрешается своим кодом")
    return problems


def gather(root):
    """Скрипты, которые может касаться это правило, и текст общего места."""
    scripts = {}
    for directory in ("ops", "tools"):
        for path in sorted((root / directory).glob("*.sh")):
            rel = f"{directory}/{path.name}"
            if rel == MODULE:
                continue
            scripts[rel] = path.read_text(encoding="utf-8")
    module = root / MODULE
    module_text = module.read_text(encoding="utf-8") if module.is_file() else None
    return scripts, module_text


def selftest():
    """Проверка самого сторожа. Ни сети, ни docker, ни переменных окружения."""
    failures = []
    module_ok = "\n".join(f"{name}() {{ :; }}" for name in NEEDED)

    def red(label, scripts, module_text, expect, allowed=None):
        problems = check(scripts, module_text, ALLOWED if allowed is None else allowed)
        text = " ".join(problems)
        if not problems:
            failures.append(f"{label}: нарушение не объявлено нарушением")
        elif expect not in text:
            failures.append(
                f"{label}: краснеет не тем — ждали «{expect}», получили: {text[:200]}")

    def silent(label, scripts, module_text, allowed=None):
        problems = check(scripts, module_text, ALLOWED if allowed is None else allowed)
        if problems:
            failures.append(f"{label}: объявлено нарушением — {'; '.join(problems)[:200]}")

    # Сама форма дефекта. Строка собирается, а не пишется литералом: иначе
    # она нашлась бы в этом файле при расширении перебора на tools/*.py.
    bad = 'set -a; . "' + "./" + '$ENV_FILE"; set +a'
    red("«./» перед переменной",
        {"ops/novyy.sh": f'ENV_FILE="${{ENV_FILE:-.env}}"\n{bad}\n'},
        module_ok, "литералом «./»")

    # И она же в комментарии — это проза, а не код: сторож обязан молчать,
    # иначе он краснел бы на объяснении дефекта в самом общем месте.
    silent("та же строка в КОММЕНТАРИИ",
           {"ops/novyy.sh": f'ENV_FILE="x"\n# было так: {bad}\n. ops/env-file.sh\n'
                            "env_file_source \"$ENV_FILE\" || exit 1\n"},
           module_ok)

    # Новая копия правила: читает файл своим кодом, в ALLOWED его нет.
    red("новая копия правила",
        {"ops/novyy.sh": 'ENV_FILE="${ENV_FILE:-.env}"\n'
                         'case "$ENV_FILE" in /*) p="$ENV_FILE" ;; *) p="x" ;; esac\n'
                         'set -a; . "$p"; set +a\n'},
        module_ok, "читает файл окружения своим кодом")

    # Общее место — молчит.
    silent("путь из общего места",
           {"ops/novyy.sh": 'ENV_FILE="${ENV_FILE:-.env}"\n. ops/env-file.sh\n'
                            'env_file_source "$ENV_FILE" || exit 1\n'},
           module_ok)

    # Пометка без причины не принимается.
    red("пометка без причины",
        {"ops/svoy.sh": 'ENV_FILE="x"\nset -a; . "$ENV_FILE"; set +a\n'},
        module_ok, "без причины не принимается",
        allowed={"ops/svoy.sh": "   "})

    # С причиной — молчит.
    silent("пометка с причиной",
           {"ops/svoy.sh": 'ENV_FILE="x"\nset -a; . "$ENV_FILE"; set +a\n'},
           module_ok, allowed={"ops/svoy.sh": "читает в подоболочке, довод такой-то"})

    # Предмет проверки отняли.
    red("общего места нет вовсе", {}, None, "нет общего места")
    red("общее место без правила", {}, "# тут ничего нет\n", "не несёт")

    # И настоящее дерево: сторож, красный на исправном, отключат в первый день.
    scripts, module_text = gather(ROOT)
    if not scripts:
        failures.append("скриптов не нашлось — сторожу не на чём проверить себя")
    silent("настоящее дерево", scripts, module_text)
    return failures


def main():
    broken = selftest()
    if broken:
        print("Проверка сломана и потому ничего не доказывает:\n")
        for line in broken:
            print("  •", line)
        return 1
    if "--selftest" in sys.argv:
        print("Сторож краснеет на пути с литералом «./», на новой копии правила\n"
              "и на пометке без причины; молчит на общем месте, на той же строке\n"
              "в комментарии, на пометке с причиной и на настоящем дереве.")
        return 0

    scripts, module_text = gather(ROOT)

    if "--list" in sys.argv:
        for path in sorted(scripts):
            text = scripts[path]
            if uses_module(text):
                how = "общее место"
            elif reads_env(text):
                how = "свой код (в ALLOWED)" if path in ALLOWED else "свой код — БЕЗ ПРИЧИНЫ"
            else:
                how = "файл окружения не сорсит"
            print(f"  {path}: {how}")
        return 0

    problems = check(scripts, module_text, ALLOWED)
    if problems:
        print("Путь к файлу окружения: проверка не прошла\n")
        for problem in problems:
            print("  •", problem)
        print("\nПравило живёт в одном месте — ops/env-file.sh (задача 0250).\n"
              "Копия, заведённая молча, читает абсолютный ENV_FILE как "
              "«.//srv/parts/.env»\nи отвечает «нет такой переменной в <файл>» "
              "про файл, который не открывала.")
        return 1

    by_module = sum(1 for text in scripts.values() if uses_module(text))
    print(f"Путь к .env разрешается в одном месте ({MODULE}): им пользуются "
          f"{by_module} скрипта,\nсвоим кодом — {len(ALLOWED)}, и у каждого "
          f"написано, почему (пометка — очередь работы,\nа не разрешение).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
