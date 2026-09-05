#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Доска задач одним взглядом.

    ./tools/board.py            всё
    ./tools/board.py новая      только с этим статусом
    ./tools/board.py --свободные  что можно раздавать прямо сейчас

Читает `tasks/*.md`. Одна задача — один файл: пять агентов, правящих общий
список параллельно, дерутся на слиянии, а свой файл каждый трогает один.

Отдельно проверяет то, из-за чего доска начинает врать: задачу, отмеченную
слитой, но без номера PR, и задачу в работе без исполнителя. Доска, которая
обгоняет систему, хуже отсутствующей: по ней раздают следующее, считая, что
предыдущее работает.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TASKS = os.path.join(ROOT, "tasks")
ORDER = ["новая", "назначена", "в работе", "ждёт решения", "слита", "отклонена"]


def read(path):
    text = open(path, encoding="utf-8").read()
    head = re.match(r"---\n(.*?)\n---", text, re.S)
    if not head:
        return None
    fields = dict(re.findall(r"^([a-z]+): (.*)$", head.group(1), re.M))
    fields["file"] = os.path.basename(path)
    return fields


def main():
    args = [a for a in sys.argv[1:]]
    free_only = "--свободные" in args
    wanted = [a for a in args if not a.startswith("--")]

    tasks, broken = [], []
    for name in sorted(os.listdir(TASKS)):
        if not name.endswith(".md") or name == "README.md":
            continue
        t = read(os.path.join(TASKS, name))
        if t is None:
            broken.append(f"{name}: нет заголовка")
            continue
        tasks.append(t)
        # То, из-за чего доска начинает врать.
        if t.get("status") == "слита" and t.get("pr", "—") in ("—", ""):
            broken.append(f"{name}: помечена слитой, а номера PR нет")
        if t.get("status") in ("назначена", "в работе") and t.get("assignee", "—") == "—":
            broken.append(f"{name}: {t['status']}, а исполнитель не назван")
        if t.get("status") not in ORDER:
            broken.append(f"{name}: неизвестный статус «{t.get('status')}»")

    shown = 0
    for status in ORDER:
        rows = [t for t in tasks if t.get("status") == status]
        if wanted and status not in wanted:
            continue
        # «Свободные» — то, что можно раздать сейчас: новая и никому не отдана.
        if free_only and status != "новая":
            continue
        if not rows:
            continue
        print(f"\n{status.upper()}  ({len(rows)})")
        for t in rows:
            who = t.get("assignee", "—")
            pr = t.get("pr", "—")
            tail = f"  → {who}" if who != "—" else ""
            tail += f"  PR #{pr}" if pr not in ("—", "") else ""
            print(f"  {t['id']}  {t['title']}{tail}")
            shown += 1

    if not shown:
        print("\nПодходящих задач нет.")

    if broken:
        print("\nДоска врёт:")
        for b in broken:
            print(f"  • {b}")
        return 1

    left = [t for t in tasks if t.get("status") in ("новая", "назначена", "в работе")]
    waiting = [t for t in tasks if t.get("status") == "ждёт решения"]
    print(f"\nВсего {len(tasks)}: в работе и впереди {len(left)}, "
          f"ждут решения человека {len(waiting)}.")
    if not left:
        print("Раздавать нечего — круг закрыт до следующей разведки.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
