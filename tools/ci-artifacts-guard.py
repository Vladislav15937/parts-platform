#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сторож копий прогона: у каждой выгруженной копии есть тот, кто её снимет.

  ./tools/ci-artifacts-guard.py             сторож (самопроверка идёт первой)
  ./tools/ci-artifacts-guard.py --selftest  только самопроверка

ЗАЧЕМ. 18 сентября 2026 волна встала целиком: последний шаг проверок падал
на ВСЕХ трёх PR разом с `Artifact storage quota has been hit`. Внутри самих
задач всё было зелёное — падала выгрузка образа файлом. В аккаунте лежали
176 просроченных копий на 23,9 ГБ.

Ловушка в том, что **истёкший срок хранения места не освобождает**: копия
помечается просроченной, скачать её уже нельзя, а квоту она занимает
по-прежнему — пока кто-нибудь не удалит её руками. `retention-days: 1`
выглядит как «живёт сутки», а означает «недоступна через сутки». Каждая
ветка оставляет такую копию, они накапливаются, и однажды квота кончается —
сразу для всех PR и в шаге, к коду отношения не имеющем. Красный без дефекта
приучает не верить проверкам, а повторный прогон тут не помогает вовсе.

ЧТО ПРОВЕРЯЕТСЯ. Четыре правила, и все четыре — про один и тот же класс:
копия, за которой некому прийти.

  1. У выгрузки стоит `retention-days`. Без него копия живёт 90 дней,
     то есть три месяца держит место образом, нужным пятнадцать минут.

  2. Копию, которую забирает только `push`-задача, не выгружают
     на `pull_request`. Публикация образа идёт только на push (у события
     pull_request `github.sha` — эфемерный коммит слияния, образ с таким
     именем ничей), значит копия, созданная на PR, не нужна НИКОМУ
     и ни минуты. Это половина всего мусора: пуш в ветку с открытым PR
     заводит два прогона, а копию оставлял каждый.

  3. У каждой копии есть тот, кто снимет её в том же прогоне, — либо
     запись в ALLOWED с причиной. Мусор лучше не выгребать, а не создавать:
     удаление через API не зависит ни от срока хранения, ни от уборки
     по расписанию (планировщика в проекте нет по решению владельца).

  4. Уборка снимает копию при ЛЮБОМ исходе (`always()`) и выгребает
     просроченные. Первое — потому что копию оставляет и красный прогон:
     упала любая проверка, публикация пропущена, а файл уже выгружен.
     Второе — потому что прогон, отменённый новым пушем (`cancel-in-progress`),
     до своей уборки не доходит, и у разрешённых исключений (см. ALLOWED)
     просроченные копии иначе копятся теми же 176 штуками.

ПОЧЕМУ СТОРОЖЕМ, А НЕ ПАМЯТЬЮ. Правило «после себя убирать» невидимо: копия
не мешает ни одному прогону, пока квота не кончится, — а кончится она
у того, кто ни одной строки в ci.yml не менял. Ровно та порода, которую
в этом проекте держит перебор (`endpoint-coverage.py`, `test-schema-guard.py`),
а не внимательность.
"""

import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("Нужен PyYAML: pip3 install pyyaml")
    sys.exit(1)

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"

# Копии, которые НЕ снимаются в том же прогоне, — с причиной у каждой.
# Пометка без причины не принимается: иначе её начнут писать не думая.
ALLOWED = {
    "surefire-reports":
        "выгружается только при падении и нужна человеку ПОСЛЕ прогона: "
        "в логе job видно имя теста, а причина — в отчёте. Снять её в том же "
        "прогоне значит выбросить единственное, ради чего она и снималась. "
        "Живёт 7 дней, просроченную выгребает уборка (правило 4).",
}

PUSH_ONLY = "github.event_name == 'push'"


def load_workflows(directory):
    """Файлы прогона: имя → разобранный YAML и его же текст."""
    out = {}
    for path in sorted(Path(directory).glob("*.yml")):
        out[path.name] = (yaml.safe_load(path.read_text(encoding="utf-8")),
                          path.read_text(encoding="utf-8"))
    return out


def job_text(job):
    """Весь текст задачи одной строкой — по нему ищем удаление и имена копий."""
    return yaml.safe_dump(job, allow_unicode=True, default_flow_style=False)


def uses(step, action):
    ref = str(step.get("uses", ""))
    return ref.startswith(action + "@") or ref == action


def steps_of(job):
    return job.get("steps") or []


def condition(job, step):
    """Условие шага вместе с условием его задачи: пропущенная задача
    пропускает и шаг, и наоборот — шаг с условием может не сработать
    в задаче, которая идёт всегда."""
    return f"{job.get('if', '')} {step.get('if', '')}"


def check(workflows):
    """Список нарушений. Пусто — правило держится."""
    problems = []

    for fname, (wf, _raw) in workflows.items():
        jobs = (wf or {}).get("jobs") or {}

        uploads = []    # (имя копии, условие, есть ли retention-days, задача)
        downloads = {}  # имя копии → список условий задач, которые её забирают
        cleaners = []   # (задача, её условие, текст, снимаемые имена)

        for job_id, job in jobs.items():
            text = job_text(job)
            deletes = "-X DELETE" in text and "actions/artifacts" in text
            if deletes:
                cleaners.append((job_id, str(job.get("if", "")), text))

            for step in steps_of(job):
                if uses(step, "actions/upload-artifact"):
                    with_ = step.get("with") or {}
                    uploads.append((str(with_.get("name", "")),
                                    condition(job, step),
                                    "retention-days" in with_,
                                    job_id))
                if uses(step, "actions/download-artifact"):
                    with_ = step.get("with") or {}
                    downloads.setdefault(str(with_.get("name", "")), []) \
                             .append(condition(job, step))

        if not uploads:
            continue

        swept = any("expired" in text for _id, _if, text in cleaners)
        always = [c for c in cleaners if "always()" in c[1]]

        for name, cond, has_retention, job_id in uploads:
            where = f"{fname}, задача «{job_id}», копия «{name}»"

            # 1. Срок хранения.
            if not has_retention:
                problems.append(
                    f"{where}: нет retention-days — копия проживёт 90 дней "
                    f"и всё это время будет держать квоту аккаунта.")

            # 2. Копия, нужная только push'у, не создаётся на PR.
            takers = downloads.get(name)
            if takers and all(PUSH_ONLY in t for t in takers) \
                    and PUSH_ONLY not in cond:
                problems.append(
                    f"{where}: забирают её только на push, а выгружается она "
                    f"и на pull_request — такая копия не нужна никому "
                    f"и ни минуты. Поставьте шагу "
                    f"`if: {PUSH_ONLY}`.")

            # 3. Кто её снимет.
            if name in ALLOWED:
                if not str(ALLOWED[name]).strip():
                    problems.append(
                        f"{where}: в ALLOWED пустая причина — пометка без "
                        f"причины не принимается.")
                continue
            if not any(name in text for _id, _if, text in cleaners):
                problems.append(
                    f"{where}: снять её в этом прогоне некому. Либо уборка "
                    f"удаляет её через API, либо имя стоит в ALLOWED "
                    f"с причиной, почему копия обязана пережить прогон.")
            elif not any(name in text for _id, _if, text in always):
                problems.append(
                    f"{where}: уборка снимает её не при любом исходе — нет "
                    f"`if: always()`. Копия выгружена ДО того, как покраснела "
                    f"соседняя проверка, и остаётся после красного прогона.")

        # 4. Просроченные выгребаются.
        if cleaners and not swept:
            problems.append(
                f"{fname}: уборка не выгребает просроченные копии. Истёкший "
                f"срок места не освобождает, а до своей уборки не доходят "
                f"ни отменённый прогон, ни разрешённые исключения "
                f"({', '.join(sorted(ALLOWED))}).")

    return problems


# ────────────────────────────── самопроверка ──────────────────────────────

def _workflow(upload_if=PUSH_ONLY, retention=True, cleaner=True,
              cleaner_if="always()", swept=True, name="app-image",
              downloaded=True):
    """Выдуманный прогон той же формы, что настоящий: сборка выгружает копию,
    публикация её забирает только на push, уборка снимает. `downloaded=False` —
    копия, которую в прогоне не забирает никто (отчёты упавших тестов)."""
    with_ = {"name": name, "path": "/tmp/image.tar"}
    if retention:
        with_["retention-days"] = 1
    jobs = {
        "deployment": {"steps": [
            {"name": "Сборка", "run": "docker build -t x ."},
            {"uses": "actions/upload-artifact@v4", "if": upload_if, "with": with_},
        ]},
        "publish": {"if": PUSH_ONLY, "steps": [
            ({"uses": "actions/download-artifact@v4", "with": {"name": name}}
             if downloaded else {"name": "Публикация без копии", "run": "true"}),
            {"name": "Публикация", "run": "docker push x"},
        ]},
    }
    if cleaner:
        sweep = "gh api .../actions/artifacts --jq '.artifacts[] | select(.expired)'" \
            if swept else ""
        jobs["cleanup"] = {
            "if": cleaner_if,
            "needs": ["deployment", "publish"],
            "steps": [{"name": "Уборка",
                       "env": {"ARTIFACT": name},
                       "run": f"gh api -X DELETE "
                              f"/repos/x/actions/artifacts/$ID\n{sweep}"}],
        }
    return {"ci.yml": ({"jobs": jobs}, "")}


def selftest():
    """Краснеет ли сторож на дефекте — и той ли частью, ради которой написан.
    Четыре подделки по одному признаку каждая; настоящий ci.yml обязан
    молчать, иначе сторож нечем снять и его отключат в первый же день."""
    failures = []

    def red(label, workflows, expect):
        problems = check(workflows)
        text = "\n".join(problems)
        if not problems:
            failures.append(f"{label}: принято — а это ровно то, ради чего "
                            f"сторож написан")
        elif expect not in text:
            failures.append(f"{label}: отказ есть, но не про то — ждали "
                            f"«{expect}», получили: {text[:200]}")

    def silent(label, workflows):
        problems = check(workflows)
        if problems:
            failures.append(f"{label}: объявлено нарушением — "
                            f"{'; '.join(problems)[:300]}")

    silent("настоящая форма прогона", _workflow())

    red("выгрузка без срока хранения", _workflow(retention=False),
        "нет retention-days")
    red("копия для push выгружается и на PR", _workflow(upload_if=None),
        "не нужна никому")
    red("копию никто не снимает", _workflow(cleaner=False),
        "снять её в этом прогоне некому")
    red("уборка не при любом исходе", _workflow(cleaner_if=PUSH_ONLY),
        "не при любом исходе")
    red("просроченные не выгребаются", _workflow(swept=False),
        "не выгребает просроченные")

    # Разрешённое исключение молчит, но только с причиной.
    silent("копия с причиной в ALLOWED",
           _workflow(cleaner=False, name="surefire-reports",
                     upload_if="failure()", downloaded=False))
    saved = ALLOWED["surefire-reports"]
    try:
        ALLOWED["surefire-reports"] = "   "
        red("пометка без причины",
            _workflow(cleaner=False, name="surefire-reports",
                      upload_if="failure()", downloaded=False),
            "пометка без причины не принимается")
    finally:
        ALLOWED["surefire-reports"] = saved

    # Настоящий файл прогона — он же и есть предмет проверки.
    if not WORKFLOWS.is_dir():
        failures.append("каталога .github/workflows нет — сторожу не на чём "
                        "проверить себя")
    return failures


def main():
    broken = selftest()
    if broken:
        print("Проверка сломана и потому ничего не доказывает:\n")
        for line in broken:
            print("  •", line)
        return 1
    if "--selftest" in sys.argv:
        print("Сторож краснеет на копии без срока хранения, на копии, которую\n"
              "заводят на PR ради push-задачи, на копии, которую некому снять,\n"
              "на уборке не при любом исходе и на уборке, не выгребающей\n"
              "просроченные; молчит на настоящей форме прогона и на пометке\n"
              "с причиной.")
        return 0

    workflows = load_workflows(WORKFLOWS)
    problems = check(workflows)
    if problems:
        print("Копии прогона: проверка не прошла\n")
        for p in problems:
            print("  •", p)
        print("\nПросроченная копия места не освобождает — 18 сентября 2026 "
              "176 таких\nостановили все PR разом. Подробности — "
              "ops/CLAUDE.md, «Копии прогона».")
        return 1

    uploads = sum(1 for _f, (wf, _r) in workflows.items()
                  for job in ((wf or {}).get("jobs") or {}).values()
                  for step in (job.get("steps") or [])
                  if str(step.get("uses", "")).startswith("actions/upload-artifact@"))
    print(f"Копий прогона выгружается {uploads}, у каждой есть тот, кто её "
          f"снимет\n(разрешённых исключений {len(ALLOWED)}, все с причиной), "
          f"просроченные выгребаются.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
