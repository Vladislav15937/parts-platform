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
ORDER = ["новая", "назначена", "в работе", "готово", "ждёт решения",
         "слита", "отклонена"]

# «готово» и «ждёт решения» — разные вещи, и путать их дорого. «Готово»
# означает: работа сделана, PR зелёный, открыта только кнопка слияния.
# «Ждёт решения» означает: открыт вопрос, ответить на который может
# только владелец продукта, и до ответа делать нечего. Пока статус был
# один, четверо в одинаковом положении написали разное — трое «ждёт
# решения», один «в работе», — и по доске нельзя было отличить очередь
# на кнопку от очереди на ответ.


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
        # «Готово» без номера PR — та же ложь, что и «слита» без него:
        # статус обещает зелёный PR, по которому человеку осталось нажать
        # кнопку, а нажимать нечего.
        if t.get("status") == "готово" and t.get("pr", "—") in ("—", ""):
            broken.append(f"{name}: помечена готовой, а номера PR нет")
        if t.get("status") in ("назначена", "в работе", "готово") and t.get("assignee", "—") == "—":
            broken.append(f"{name}: {t['status']}, а исполнитель не назван")
        if t.get("status") not in ORDER:
            broken.append(f"{name}: неизвестный статус «{t.get('status')}»")

    # Работа, которой нет на доске. Ветка `feature/0002` при задаче 0002
    # в состоянии «новая» означает одно из двух: исполнитель ещё не отметился
    # (тогда это вопрос времени) или задачу взяли мимо дирижёра (тогда её
    # выдадут второму). Отличить снаружи нельзя, поэтому предупреждение,
    # а не ошибка: доска не врёт, но и не знает.
    warnings = []
    try:
        import subprocess
        out = subprocess.run(["git", "-C", ROOT, "branch", "-a", "--format=%(refname:short)"],
                             capture_output=True, text=True, timeout=10).stdout
        # Только точный номер задачи с нулями: «feature/0002» — про задачу,
        # а «feature/2» — старая ветка обычной работы. Без этого совпадали
        # все подряд: feature/4 против задачи 0004, и предупреждение
        # превращалось в шум, который перестают читать.
        branches = {b.strip().split("/")[-1] for b in out.splitlines()}
        for t in tasks:
            if t.get("status") == "новая" and t["id"] in branches:
                warnings.append(f"{t['id']}: на доске «новая», "
                                f"а ветка «{t['id']}» уже есть")
    except Exception:
        pass                      # без git обходимся: доска важнее подсказки

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

    if warnings:
        print("\nДоска не знает:")
        for w in warnings:
            print(f"  • {w}")

    if broken:
        print("\nДоска врёт:")
        for b in broken:
            print(f"  • {b}")
        return 1

    left = [t for t in tasks if t.get("status") in ("новая", "назначена", "в работе")]
    ready = [t for t in tasks if t.get("status") == "готово"]
    waiting = [t for t in tasks if t.get("status") == "ждёт решения"]
    print(f"\nВсего {len(tasks)}: в работе и впереди {len(left)}, "
          f"ждут кнопки человека {len(ready)}, ждут ответа человека {len(waiting)}.")
    if not left:
        print("Раздавать нечего — круг закрыт до следующей разведки.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
