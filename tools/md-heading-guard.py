#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Заголовок markdown, прилипший к чужому абзацу, и брошенный маркер конфликта.

Правило записано в `docs/ops-history.md` («Маркер `=======` при сведении этого
файла нельзя просто удалять»): средний маркер конфликта стоит вплотную между
последней строкой раздела и заголовком следующего, и удалённый «как обычно» он
склеивает их. Там же названа команда-способ ловить («ловится командой, а не
глазами») с инвариантом «Пусто — склеек нет» — и её не гонял никто, а
29 сентября 2026 она отдавала три строки (задача 0222). Теперь её гоняет CI.

  ./tools/md-heading-guard.py [--list] [--selftest]

Что считается дефектом (вне блоков кода и вне YAML-шапки):
  * заголовок `##`..`######` с непустой строкой прямо над ним;
  * `##Заголовок` без пробела;
  * маркер конфликта: `<<<<<<< `, `>>>>>>> `, строка `=======`.

Блоки кода пропускаются по ограде (``` или ~~~), поэтому `# комментарий` внутри
bash-блока `docs/deployment.md` не срабатывает, и исключения для файла не
нужны: исходная записанная команда этого не умела, и запрет применять её к
`deployment.md` относился именно к ней. Заголовок первого уровня `#` не
проверяется — в начале файла он стоит без строки сверху законно, а склейки
первого уровня замером не найдены.

Не проверяются `tasks/`: файлы задач цитируют чужой текст и команды
дословно, а правят их доской, не сведением памяток.
"""
import re
import subprocess
import sys
from pathlib import Path

HEAD = re.compile(r"^#{2,6}[ \t]")
NOSPACE = re.compile(r"^#{2,6}[^#\s]")
FENCE = re.compile(r"^\s*(`{3,}|~{3,})")
EXCLUDED_PREFIXES = ("tasks/",)


def check(text):
    """Список (номер строки, причина, строка)."""
    out = []
    lines = text.split("\n")
    i = 0
    if lines and lines[0].strip() == "---":
        for j in range(1, len(lines)):
            if lines[j].strip() == "---":
                i = j + 1
                break
    fence = None
    prev = ""
    for n in range(i, len(lines)):
        line = lines[n]
        m = FENCE.match(line)
        if m:
            mark = m.group(1)
            if fence is None:
                fence = mark[0] * len(mark)
            elif mark[0] == fence[0] and len(mark) >= len(fence) and line.strip() == mark:
                fence = None
            prev = line
            continue
        if fence is None:
            if HEAD.match(line) and prev.strip() != "":
                out.append((n + 1, "заголовок вплотную к строке выше", line))
            elif NOSPACE.match(line):
                out.append((n + 1, "заголовок без пробела после #", line))
            if line.startswith(("<<<<<<< ", ">>>>>>> ")) or line.rstrip() == "=======":
                out.append((n + 1, "брошенный маркер конфликта", line))
        prev = line
    return out


def files():
    res = subprocess.run(["git", "ls-files", "*.md"], capture_output=True, text=True, check=True)
    return [f for f in res.stdout.split("\n") if f and not f.startswith(EXCLUDED_PREFIXES)]


def selftest():
    good = "# T\n\n## A\n\nтекст\n\n```bash\n# комментарий\n## тоже\n```\n\n### B\n"
    assert check(good) == [], check(good)
    glued = "# T\n\n## A\n\nтекст\n## B\n"
    assert [r[0] for r in check(glued)] == [6], check(glued)
    nospace = "текст\n\n##Заголовок\n"
    assert [r[1] for r in check(nospace)] == ["заголовок без пробела после #"], check(nospace)
    marker = "а\n\n=======\n\nб\n<<<<<<< HEAD\n"
    assert [r[0] for r in check(marker)] == [3, 6], check(marker)
    deep = "текст\n#### Глубокий\n"
    assert len(check(deep)) == 1
    fm = "---\nname: x\n# комментарий шапки\n---\n## A\n"
    assert check(fm) == [], check(fm)
    unclosed_wrong = "```\n## внутри\n~~~\n## всё ещё внутри\n```\n\n## снаружи\n"
    assert check(unclosed_wrong) == [], check(unclosed_wrong)
    print("md-heading-guard: самопроверка пройдена (5 дефектов краснеют, 3 нормы молчат)")


def main():
    if "--selftest" in sys.argv:
        selftest()
        return 0
    bad = 0
    for f in files():
        p = Path(f)
        if not p.is_file():
            continue
        for n, why, line in check(p.read_text(encoding="utf-8")):
            print(f"{f}:{n}: {why}: {line[:100]}")
            bad += 1
    if bad:
        print(f"\nmd-heading-guard: {bad} мест. Перед заголовком нужна пустая строка; "
              "средний маркер конфликта заменяют пустой строкой, а не удаляют.")
        return 1
    if "--list" in sys.argv:
        print("md-heading-guard: склеек нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
