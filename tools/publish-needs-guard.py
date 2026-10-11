#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сторож поимённого `needs` публикации: каждая задача CI ждётся публикацией.

  ./tools/publish-needs-guard.py                     сторож (самопроверка первой)
  ./tools/publish-needs-guard.py --selftest          только самопроверка
  ./tools/publish-needs-guard.py --workflow ФАЙЛ     проверить другой файл
                                                     (например, прежнюю версию
                                                     из `git show`)

ЗАЧЕМ. Задача `publish` в `.github/workflows/ci.yml` кладёт образ в GHCR
с тегом по SHA, и ждать она обязана ВСЕХ проверок: образ в реестре — уже
не черновик, по тегу за ним придут на выкладке. Список `needs` у неё
поимённый, и забытое в нём имя не ломает ни одного прогона — красная
проверка просто перестаёт останавливать публикацию, молча.

Правило «новую задачу вписывать в `needs` публикации» было записано прозой
в самом списке трижды (у `rollback` после разбора PR #197, у `mirror`,
у `clock-shift`) — и нарушено в четвёртый раз: `date-mines` («Календарные
даты в проверках») в списке не стояла, хотя стережёт класс, который уже
ронял чистую `main` (18 сентября 2026). Задача 0220. Правило, нарушенное
после трёх записей, держит перебор, а не четвёртая запись.

ЧТО ПРОВЕРЯЕТСЯ.
  1. Каждый `job id` рабочего процесса стоит в `needs` у `publish` — либо
     назван в EXEMPT с причиной.
  2. Пометка в EXEMPT без причины не принимается.
  3. Пометка в EXEMPT про задачу, которой в файле нет, — устаревшая: её
     надо снять, иначе перечень исключений перестаёт описывать файл.

ЧЕГО НЕ ПРОВЕРЯЕТСЯ. Другие рабочие процессы (`deploy.yml` и прочие): там
публикации образа нет и правило другое (задача 0220, «Чего делать нельзя»).
И не проверяется, что задача, стоящая в `needs`, действительно что-то
проверяет: имя в списке — необходимое условие, а не достаточное.
"""

import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("Нужен PyYAML: pip3 install -r tools/requirements-ci.txt")
    sys.exit(1)

ROOT = Path(__file__).resolve().parent.parent
CI = ROOT / ".github" / "workflows" / "ci.yml"
PUBLISH = "publish"

# Задачи, которых публикация НЕ ждёт, — с причиной у каждой. Пометка без
# причины не принимается: молчаливое исключение вернёт ту же дыру.
EXEMPT = {
    "publish":
        "сама публикация: ждать себя задача не может.",
    "image-cleanup":
        "уборка копии образа идёт ПОСЛЕ публикации (`needs: [deployment, "
        "publish]`) и проверкой не является. Вписанная сюда, она дала бы "
        "цикл зависимостей, и workflow стал бы негодным.",
}


def needs_of(job):
    """`needs` задачи списком: GitHub принимает и строку, и список."""
    raw = (job or {}).get("needs")
    if raw is None:
        return []
    if isinstance(raw, str):
        return [raw]
    return [str(x) for x in raw]


def check(workflow, exempt=None):
    """Список нарушений. Пусто — правило держится."""
    exempt = EXEMPT if exempt is None else exempt
    jobs = (workflow or {}).get("jobs") or {}
    problems = []

    if PUBLISH not in jobs:
        return [f"задачи «{PUBLISH}» в файле нет — сторожу нечего сверять. "
                f"Публикацию переименовали? Тогда и сторожа вместе с ней."]

    waited = set(needs_of(jobs[PUBLISH]))

    for job_id in jobs:
        if job_id in waited:
            continue
        if job_id in exempt:
            if not str(exempt[job_id]).strip():
                problems.append(
                    f"«{job_id}»: в EXEMPT пустая причина — пометка без "
                    f"причины не принимается.")
            continue
        problems.append(
            f"«{job_id}» не стоит в `needs` у «{PUBLISH}»: красная, она "
            f"не остановит публикацию образа. Впишите имя в список — либо "
            f"в EXEMPT этого сторожа с причиной, почему публикация может "
            f"её не ждать.")

    for job_id in exempt:
        if job_id not in jobs:
            problems.append(
                f"«{job_id}» названа в EXEMPT, а задачи с таким именем "
                f"в файле нет — снимите устаревшее исключение.")

    return problems


# ────────────────────────────── самопроверка ──────────────────────────────

def _drop_need_line(text, name):
    """Копия текста ci.yml со снятой строкой `- name` из `needs` публикации.
    Снимается ровно одна строка; если такая строка не одна (или её нет),
    подделку собрать нельзя — и это само по себе отказ самопроверки."""
    lines = text.splitlines(keepends=True)
    start = next((i for i, ln in enumerate(lines)
                  if re.match(rf"^  {PUBLISH}:\s*$", ln)), None)
    if start is None:
        return None
    end = next((i for i in range(start + 1, len(lines))
                if re.match(r"^  \S", lines[i])), len(lines))
    pattern = re.compile(rf"^\s+-\s+{re.escape(name)}\s*$")
    hits = [i for i in range(start, end) if pattern.match(lines[i])]
    if len(hits) != 1:
        return None
    del lines[hits[0]]
    return "".join(lines)


def selftest(path=CI):
    """Краснеет ли сторож на дефекте и тем ли словом. Подделки — копии
    настоящего ci.yml по одному признаку каждая; настоящий файл обязан
    молчать, иначе сторожа нечем снять и его отключат в первый же день."""
    failures = []
    if not Path(path).is_file():
        return [f"нет {path} — сторожу не на чем проверить себя"]
    text = Path(path).read_text(encoding="utf-8")
    real = yaml.safe_load(text)

    # 0. Обратный край: тот же файл, где публикация ждёт всех, кроме
    #    исключений, обязан молчать — иначе сторожа нечем снять. Сам
    #    настоящий файл здесь не судится: это делает основной прогон, и
    #    его отказ должен звучать как нарушение, а не как сломанный сторож.
    clean = yaml.safe_load(text)
    clean["jobs"][PUBLISH]["needs"] = [j for j in clean["jobs"]
                                       if j not in EXEMPT]
    problems = check(clean)
    if problems:
        failures.append("файл, где публикация ждёт всех, объявлен "
                        "нарушением: " + "; ".join(problems)[:300])

    # 1. Копия ci.yml со снятым из `needs` именем — по каждому имени списка.
    #    Отказ обязан назвать именно снятое имя.
    names = needs_of((real.get("jobs") or {}).get(PUBLISH))
    if not names:
        failures.append(f"у «{PUBLISH}» пустой `needs` — подделку собрать "
                        f"не из чего")
    for name in names:
        forged = _drop_need_line(text, name)
        if forged is None:
            failures.append(f"строку «- {name}» в `needs` публикации нельзя "
                            f"снять однозначно — подделка не собрана")
            continue
        got = check(yaml.safe_load(forged))
        if not any(f"«{name}» не стоит в `needs`" in p for p in got):
            failures.append(f"снято «{name}» из `needs`: сторож "
                            f"{'промолчал' if not got else 'назвал не то: ' + '; '.join(got)[:200]}")

    # 2. Новая задача, которую забыли вписать, — ровно случай date-mines.
    forged = yaml.safe_load(text)
    forged["jobs"]["fresh-check"] = {"runs-on": "ubuntu-latest",
                                     "steps": [{"run": "true"}]}
    if not any("«fresh-check» не стоит" in p for p in check(forged)):
        failures.append("новая задача без имени в `needs`: сторож промолчал")

    # 3. Пометка без причины.
    hollow = dict(EXEMPT, **{"fresh-check": "   "})
    if not any("пустая причина" in p for p in check(forged, hollow)):
        failures.append("пометка в EXEMPT без причины: принята")

    # 4. Устаревшая пометка.
    stale = dict(EXEMPT, **{"gone-job": "когда-то была"})
    if not any("«gone-job» названа в EXEMPT" in p
               for p in check(real, stale)):
        failures.append("пометка в EXEMPT про несуществующую задачу: принята")

    # 5. Пропавшая публикация.
    forged = yaml.safe_load(text)
    del forged["jobs"][PUBLISH]
    if not check(forged):
        failures.append(f"файл без «{PUBLISH}»: сторож промолчал")

    return failures


def main(argv):
    path = CI
    if "--workflow" in argv:
        i = argv.index("--workflow")
        if i + 1 >= len(argv):
            print("--workflow требует путь к файлу")
            return 2
        path = Path(argv[i + 1])

    broken = selftest()
    if broken:
        print("Проверка сломана и потому ничего не доказывает:\n")
        for line in broken:
            print("  •", line)
        return 1
    if "--selftest" in argv:
        print("Сторож краснеет на каждом имени, снятом из `needs` публикации\n"
              "в копии ci.yml, на новой задаче без имени в списке, на пометке\n"
              "без причины, на устаревшей пометке и на пропавшей публикации;\n"
              "молчит на файле, где публикация ждёт всех, кроме исключений.")
        return 0

    workflow = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    problems = check(workflow)
    if problems:
        print(f"Поимённый `needs` публикации ({path}): проверка не прошла\n")
        for p in problems:
            print("  •", p)
        print("\nЗабытое имя не ломает ни одного прогона — красная проверка "
              "просто\nперестаёт останавливать публикацию образа (задача 0220).")
        return 1

    jobs = (workflow.get("jobs") or {})
    print(f"Публикация ждёт все задачи CI: {len(needs_of(jobs[PUBLISH]))} "
          f"в `needs`, исключений {len(EXEMPT)}, все с причиной "
          f"({', '.join(EXEMPT)}).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
