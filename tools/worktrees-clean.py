#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Уборка брошенных рабочих деревьев (git worktree).

    ./tools/worktrees-clean.py                показать, что снял бы
    ./tools/worktrees-clean.py --снять        снять названное
    ./tools/worktrees-clean.py --давно 5      порог «агента давно нет», суток
    ./tools/worktrees-clean.py --свежие 24    не трогать ничего моложе, часов
    ./tools/worktrees-clean.py --самопроверка только проверить саму проверку

Показ — умолчание, и это не вежливость: снятое дерево не возвращается.
Снимает только `--снять`, и только то, что перед этим было названо.

**Зачем.** 12 сентября 2026 `git worktree list` дал 41 запись — десятки
от агентов, давно закончивших работу. Это не беспорядок: дерево держит
ветку, и пока оно живо, ветку нельзя ни взять, ни удалить. Одно такое
помешало доделке задачи 0061 — исполнитель упёрся в «is already used by
worktree», — и дирижёр снял его руками. Каждая копия — ещё и полный
рабочий каталог с `target/` и `node_modules`, то есть гигабайты.

Разовая уборка вернёт то же через неделю: копию заводит каждый агент,
а убирает за собой не всякий — особенно оборванный лимитом, а таких
за неделю было больше десятка. Поэтому инструмент, а не разовый проход.

**Чего он не трогает вовсе — и это главное правило.** Копию, в которой
есть незакоммиченные правки. Там может лежать несделанная работа
оборванного исполнителя: дважды за эти дни в брошенных деревьях нашлась
готовая невыложенная работа, и снеси её уборка — делать пришлось бы
заново. Такие копии инструмент только называет, с числом файлов, чтобы
человек сходил и посмотрел сам. Незакоммиченным считается и неотслеженный
файл: `.gitignore` уже убрал из счёта `target/` и `node_modules`, а всё,
что осталось, — чья-то работа, пока не доказано обратное.

**Незакоммиченным правкам работа не равна, и разбор нашёл два способа
её потерять при чистом дереве.** Оба воспроизведены, оба закрыты.

*Остановленный `rebase`.* У копии, где `rebase -i` ждёт человека,
`git status --porcelain` пуст, HEAD отцеплен и ветки нет вовсе — то есть
все прежние признаки говорили «пустая копия, снимай», а `git worktree
remove` отвечал нулём и уносил `rebase-merge` со всем прогрессом. Работа
тут лежит **состоянием операции**, а не файлами. Теперь копия с любой
незавершённой операцией (`rebase`, `am`, `merge`, `cherry-pick`, `revert`,
`bisect`, очередь `sequencer`) только называется — см. `OPERATIONS`.

*Отцепленный HEAD с коммитами.* После коммита дерево чистое, а держит эти
коммиты **только сама копия**: ветки у неё нет, и снятие оставляет их
сиротами до ближайшего `git gc`. Инструмент при этом сам печатал «ветка
останется» — оставаться было нечему. Теперь такая копия не снимается,
а в причине написано, чем её спасти: `git branch <имя> <sha>`.

**Второе правило — свежесть.** Рядом работают другие агенты, каждый
в своей копии, и снесённое чужое дерево — это потерянная чужая волна.
Копию, в которой что-то происходило за последние `--свежие` часов,
инструмент не трогает независимо от всего прочего: там может сидеть
живой исполнитель, который просто ещё ничего не записал.

**Почему свежесть, а не pid из замка.** Замок, который ставит на копию
harness, называет процесс: `locked claude agent agent-… (pid 67832 …)`.
Признак выглядел точным ровно до замера: 12 сентября 2026 все 13 замков
несли **один и тот же** pid — процесс сессии, живой с 6 сентября, —
а живых агентов было три. То есть «pid работает» не значит «агент жив»,
и решать по нему нельзя. Обратное («процесса нет» ⇒ агента нет) верно,
но за неделю не случилось ни разу. Остаётся время последней работы
с копией, и оно же честнее: оно про копию, а не про процесс.

**Что снимается.** Копия не своя, не главная, не грязная, не свежая — и:

  ветка слита в `main`     — работа уехала, держать копию незачем;
                             такая ветка после снятия удаляется —
                             `update-ref -d`, а не `git branch -d`:
                             почему именно так, написано у `drop_branch`;
  агента давно нет         — старше `--давно` суток. Ветка при этом
                             **остаётся**: коммиты, которых нет в `main`,
                             это чья-то работа, и снимаем мы каталог,
                             а не её.

Порог «давно» — двое суток, и это выбор исполнителя, а не замер: волны
идут по нескольку в день, копия, к которой не прикасались двое суток,
заведомо ничья. Меняется флагом, если окажется мал.

Проверка самой проверки (`selftest`) идёт **перед каждым прогоном**:
она заводит настоящий временный репозиторий с настоящими копиями —
грязной, слитой, брошенной, свежей, с остановленным `rebase`
и с отцепленным HEAD — и прогоняет через них решение **и** снятие
целиком, после чего смотрит на файлы и ветки на диске. Инструмент,
который сносит лишнее, ошибается ровно один раз, и убедиться
в обратном рассуждением нельзя.
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FRESH_HOURS = 12      # моложе — не трогаю вовсе: там может сидеть живой агент
STALE_DAYS = 2        # старше — «агента давно нет»

KEEP, TAKE = "оставить", "снять"

# Причины, по которым копия остаётся. Текст важен: по нему человек решает,
# идти ли смотреть содержимое руками.
WHY_DIRTY = "незакоммиченные правки"
WHY_BUSY = "незавершённая операция git"
WHY_ORPHAN = "коммиты, которых не держит ни одна ветка"


class GitError(RuntimeError):
    pass


def git(*args, cwd=None, check=True):
    """Вызов git. При отказе возвращает **причину**, а не пустую строку.

    Причину отказа git пишет в stderr: «worktree contains modified files»,
    «branch is not fully merged». Возвращая один `stdout`, мы теряли её ровно
    в аварийном случае — отчёт говорил «НЕ снял — git отказался:» и обрывался
    на двоеточии.
    """
    p = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if check and p.returncode:
        raise GitError(f"git {' '.join(args)}: {(p.stderr or p.stdout).strip()}")
    if p.returncode:
        return p.returncode, (p.stderr.strip() or p.stdout.strip())
    return p.returncode, p.stdout


def git_ok(*args, **kw):
    return git(*args, check=False, **kw)[0] == 0


def worktrees(repo):
    """Разбор `git worktree list --porcelain`. Первая запись — главная копия."""
    _, out = git("worktree", "list", "--porcelain", cwd=repo)
    items, cur = [], None
    for line in out.splitlines():
        if line.startswith("worktree "):
            cur = {"path": line[len("worktree "):], "branch": None, "head": None,
                   "locked": None, "prunable": None, "main": not items}
            items.append(cur)
        elif cur is None:
            continue
        elif line.startswith("HEAD "):
            cur["head"] = line[len("HEAD "):]
        elif line.startswith("branch "):
            cur["branch"] = line[len("branch "):].replace("refs/heads/", "", 1)
        elif line == "detached":
            cur["branch"] = None
        elif line.startswith("locked"):
            cur["locked"] = line[len("locked"):].strip() or "без причины"
        elif line.startswith("prunable"):
            cur["prunable"] = line[len("prunable"):].strip() or "каталога нет"
    return items


def gitdir_of(path):
    """Служебный каталог копии: `.git` в ней — файл со ссылкой, а не каталог."""
    dot = os.path.join(path, ".git")
    if os.path.isdir(dot):
        return dot
    try:
        text = open(dot, encoding="utf-8").read().strip()
    except OSError:
        return None
    return text[len("gitdir:"):].strip() if text.startswith("gitdir:") else None


def last_touched(path):
    """Когда с копией последний раз работали.

    Смотрим служебный каталог (`HEAD`, `index`, `logs/` — их пишет любая
    операция git) и корень самой копии. Содержимое вглубь не обходим:
    сорок деревьев с `node_modules` — это минуты на один ответ.
    """
    times = []
    gitdir = gitdir_of(path)
    if gitdir and os.path.isdir(gitdir):
        times.append(os.path.getmtime(gitdir))
        for name in os.listdir(gitdir):
            try:
                times.append(os.path.getmtime(os.path.join(gitdir, name)))
            except OSError:
                pass
    if os.path.isdir(path):
        try:
            times.append(os.path.getmtime(path))
        except OSError:
            pass
    return max(times) if times else 0.0


def dirty_files(path):
    """Незакоммиченное: изменённое, добавленное и неотслеженное.

    Игнорируемое (`target/`, `node_modules`) git сюда не кладёт — иначе
    грязной была бы каждая собранная копия, и уборка не сняла бы ничего.

    `--no-optional-locks` обязателен: обычный `status` освежает `index`
    на диске, то есть **сам** делает копию свежей. С ним первый же прогон
    переводил все сорок копий в «работали 0 мин назад», и второй прогон
    не снимал уже ничего — инструмент стирал признак, по которому решает.
    """
    rc, out = git("--no-optional-locks", "status", "--porcelain", cwd=path, check=False)
    if rc:
        return None                       # каталога нет или он не репозиторий
    return [ln[3:] or ln for ln in out.splitlines()]


# Незавершённая операция git: работа тут лежит **состоянием**, а не файлами.
# Остановленный `rebase -i` при чистом дереве не даёт `git status --porcelain`
# ни строчки, HEAD при этом отцеплен и ветки у копии нет вовсе — то есть все
# прочие признаки говорят «пустая копия, снимай», а `git worktree remove`
# отвечает нулём и уносит `rebase-merge` вместе со всем прогрессом.
OPERATIONS = [
    ("rebase-merge", "rebase остановлен (интерактивный или с конфликтом)"),
    ("rebase-apply", "идёт rebase или am"),
    ("sequencer", "очередь sequencer: rebase или cherry-pick списком"),
    ("MERGE_HEAD", "слияние не завершено"),
    ("CHERRY_PICK_HEAD", "cherry-pick не завершён"),
    ("REVERT_HEAD", "revert не завершён"),
    ("BISECT_LOG", "идёт bisect"),
]


def unfinished(path):
    """Название незавершённой операции git в этой копии — или None."""
    gitdir = gitdir_of(path)
    if not gitdir:
        return None
    for name, label in OPERATIONS:
        if os.path.exists(os.path.join(gitdir, name)):
            return label
    return None


def main_refs(repo):
    """Чем считать `main`: местной веткой, ссылкой на origin — чем есть."""
    refs = [r for r in ("main", "origin/main")
            if git_ok("rev-parse", "--verify", "--quiet", r + "^{commit}", cwd=repo)]
    if not refs:
        raise GitError("не нашёл ни main, ни origin/main — не с чем сверять слитость")
    return refs


def merged(repo, head, refs):
    """Слито ли — по предкам `main`.

    Squash-слияние так не опознаётся: в `main` уезжает новый коммит, а не
    предок ветки. Ошибка в безопасную сторону (копия останется), и подбирает
    такие копии порог давности — но ветку у них инструмент не удалит,
    и это правильно: доказать слитость squash'ем он не может.
    """
    return any(git_ok("merge-base", "--is-ancestor", head, r, cwd=repo) for r in refs)


def ahead(repo, head, refs):
    """Сколько коммитов ветки нет ни в одной из ссылок на main."""
    args = ["rev-list", "--count", head] + ["^" + r for r in refs]
    rc, out = git(*args, cwd=repo, check=False)
    return int(out.strip() or 0) if rc == 0 else 0


def inside(child, parent):
    child, parent = os.path.realpath(child), os.path.realpath(parent)
    return child == parent or child.startswith(parent + os.sep)


def plan(repo, cwd=None, now=None, fresh_hours=FRESH_HOURS, stale_days=STALE_DAYS):
    """Решение по каждой копии. Ничего не меняет — только смотрит.

    Порядок проверок и есть договор: незавершённая операция и грязь
    перебивают всё остальное, свежесть — всё, кроме них.
    """
    now = time.time() if now is None else now
    cwd = os.getcwd() if cwd is None else cwd
    refs = main_refs(repo)
    out = []

    for wt in worktrees(repo):
        r = dict(wt, decision=KEEP, why="", dirty=[], age=None, unfinished=None,
                 merged=False, ahead=0, drop_branch=False)
        r["name"] = os.path.basename(r["path"].rstrip(os.sep))
        out.append(r)

        if r["main"]:
            r["why"] = "главный рабочий каталог"
            continue
        if inside(cwd, r["path"]):
            r["why"] = "отсюда запущен инструмент"
            continue
        if r["prunable"]:
            r["decision"], r["why"] = TAKE, f"каталога нет ({r['prunable']}) — запись почистится"
            continue

        # Возраст снимается до любых обращений к копии: даже безобидное
        # чтение оставляет следы во времени файлов, а решаем мы по нему.
        r["age"] = (now - last_touched(r["path"])) / 3600.0

        # Раньше грязи: у остановленного rebase дерево бывает чистым,
        # и тогда все прочие признаки говорят «снимай».
        r["unfinished"] = unfinished(r["path"])
        if r["unfinished"]:
            r["why"] = (f"{WHY_BUSY}: {r['unfinished']} — работа тут "
                        "состоянием, а не файлами")
            continue

        r["dirty"] = dirty_files(r["path"])
        if r["dirty"] is None:
            r["why"] = "git не читает эту копию — разберитесь руками"
            continue
        if r["dirty"]:
            r["why"] = (f"{WHY_DIRTY}: {len(r['dirty'])} файл(ов) — "
                        "там может лежать несделанная работа")
            continue

        if r["age"] < fresh_hours:
            r["why"] = (f"работали {age_words(r['age'])} назад — "
                        "здесь может сидеть живой агент")
            continue

        r["merged"] = merged(repo, r["head"], refs)
        r["ahead"] = 0 if r["merged"] else ahead(repo, r["head"], refs)

        # Отцепленный HEAD с коммитами сверх main держит только сама копия:
        # ветки у неё нет, и снятая копия оставляет коммиты сиротами —
        # до ближайшего `git gc`, после которого их не вернуть ничем.
        # «Ветка останется» тут было прямой неправдой: оставаться нечему.
        if r["branch"] is None and not r["merged"]:
            r["why"] = (f"{WHY_ORPHAN}: HEAD отцеплен, коммитов сверх main "
                        f"{r['ahead']} — их не держит ни одна ветка. "
                        f"Сохранить: git branch <имя> {(r['head'] or '')[:8]}")
            continue

        if r["merged"]:
            r["decision"] = TAKE
            r["drop_branch"] = bool(r["branch"])
            r["why"] = "ветка слита в main"
        elif r["age"] >= stale_days * 24:
            r["decision"] = TAKE
            r["why"] = f"агента давно нет: работали {age_words(r['age'])} назад"
        else:
            r["why"] = (f"ветка не слита, работали {age_words(r['age'])} назад — "
                        "рано")
    return out


def age_words(hours):
    if hours < 1:
        return f"{int(hours * 60)} мин"
    if hours < 48:
        return f"{int(hours)} ч"
    return f"{int(hours // 24)} сут"


def without_hint(text):
    """Отказ git без его же подсказки «run 'git branch -D …'».

    Подсказку git даёт из лучших побуждений, но попадала она в отчёт
    дословно и звала человека доломать ровно то, от чего инструмент его
    бережёт: `-D` сносит ветку, не спрашивая, слита ли она.

    Тот отказ приходил от `git branch -d`, а его здесь больше нет —
    ветку снимает `update-ref -d` (см. `drop_branch`). Фильтр оставлен
    **на будущее** и прогоняется через него всякий отказ git, который
    инструмент печатает: подсказка «сделай то же силой» — обычный жанр
    сообщений git, и появиться она может у любой следующей команды.
    """
    return " ".join(line for line in text.splitlines()
                    if "branch -D" not in line and "sure you want" not in line).strip()


def drop_branch(repo, branch):
    """Удалить ветку слитой копии. Возвращает строки отчёта.

    **Не `git branch -d`.** Тот сверяет слитость с HEAD **того места, откуда
    его зовут**, а зовут его из главного каталога, который стоит на чём
    угодно: в настоящем репозитории он в этот момент стоял на ветке доски.
    Ветка, слитая в `main`, получала «not fully merged» и оставалась
    навсегда — при том что отчёт заранее обещал «ветка уйдёт вместе
    с копией». Обещание и поведение обязаны сходиться, а раз слитость
    доказана нашей же `merged()` по `main`/`origin/main`, ссылка снимается
    прямо: `update-ref -d` со старым значением — атомарно и без оглядки
    на чужой HEAD.

    Ценой уходит то, что `-d` делал сам, — поэтому оба его отказа
    воспроизведены здесь явно: слитость сверяется заново, и отдельно
    проверяется, что ветку не заняла другая копия. `-D` не появляется
    нигде: он означал бы «снести, даже если я ошибся».

    Насколько это защищает от гонки — по-разному у двух проверок,
    и стоит знать, у какой как. **Сдвиг ветки** закрыт по-настоящему:
    старое значение передаётся самому `update-ref`, то есть сравнение
    и запись идут одной операцией, и ветка, обновлённая между проверкой
    и удалением, не удалится. **Занятость ветки чужой копией** — обычная
    проверка чтением: между ней и удалением кто-то может успеть завести
    копию на этой ветке. Ссылку в этом случае мы снимем, а его каталог
    останется на месте с отцепленным HEAD — то есть работа цела, а копия
    требует разбора руками. Гонка узкая и в один поток невозможна;
    закрывать её нечем, потому что атомарного «удали ссылку, если её
    никто не занял» у git нет.
    """
    ref = "refs/heads/" + branch
    rc, sha = git("rev-parse", "--verify", "--quiet", ref, cwd=repo, check=False)
    sha = sha.strip()
    if rc or not sha:
        return [f"    ветка {branch} уже удалена"]
    if not merged(repo, sha, main_refs(repo)):
        return [f"    ветка {branch} осталась: перед самым удалением она уже "
                f"не выглядит слитой в main"]
    busy = [w["path"] for w in worktrees(repo) if w["branch"] == branch]
    if busy:
        return [f"    ветка {branch} осталась: её занимает копия {busy[0]}"]
    rc, out = git("update-ref", "-d", ref, sha, cwd=repo, check=False)
    if rc:
        return [f"    ветка {branch} осталась: {without_hint(out)}"]
    return [f"    ветка {branch} удалена"]


def remove(repo, r):
    """Снять одну копию. Возвращает строки отчёта; ничего не делает молча.

    Грязь и незавершённая операция перепроверяются **прямо перед снятием**:
    между показом и запуском со `--снять` проходят минуты, и за них в копию
    мог кто-то сесть и начать rebase.
    """
    done = []
    if r["prunable"]:
        git("worktree", "prune", cwd=repo)
        return [f"{r['name']}: запись о пропавшем каталоге убрана"]

    busy = unfinished(r["path"])
    if busy:
        return [f"{r['name']}: НЕ снял — {WHY_BUSY}: {busy}"]
    again = dirty_files(r["path"])
    if again:
        return [f"{r['name']}: НЕ снял — появились незакоммиченные правки "
                f"({len(again)} файл(ов))"]
    if r["locked"]:
        git("worktree", "unlock", r["path"], cwd=repo, check=False)
    rc, out = git("worktree", "remove", r["path"], cwd=repo, check=False)
    if rc:
        return [f"{r['name']}: НЕ снял — git отказался: {without_hint(out)}"]
    done.append(f"{r['name']}: снята")

    if r["drop_branch"] and r["branch"]:
        done += drop_branch(repo, r["branch"])
    elif r["branch"]:
        done.append(f"    ветка {r['branch']} осталась"
                    + (f": в ней {r['ahead']} коммит(ов) сверх main" if r["ahead"] else ""))
    return done


def show(rows, fresh_hours, stale_days):
    take = [r for r in rows if r["decision"] == TAKE]
    keep = [r for r in rows if r["decision"] == KEEP]
    dirty = [r for r in keep if r["dirty"]]

    print(f"Рабочих деревьев: {len(rows)}\n")

    print(f"Снял бы ({len(take)}):")
    for r in sorted(take, key=lambda r: -(r["age"] or 0)):
        print(f"  {r['name']}  [{r['branch'] or 'без ветки'}]")
        print(f"      {r['why']}")
        if r["drop_branch"]:
            print(f"      ветка уйдёт вместе с копией — она в main")
        elif r["ahead"]:
            print(f"      ветка останется: в ней {r['ahead']} коммит(ов) сверх main")
    if not take:
        print("  — нечего")

    print(f"\nНе трогаю ({len(keep)}):")
    for r in keep:
        print(f"  {r['name']}  [{r['branch'] or 'без ветки'}]")
        print(f"      {r['why']}")
        for name in (r["dirty"] or [])[:5]:
            print(f"        {name}")
        if r["dirty"] and len(r["dirty"]) > 5:
            print(f"        … и ещё {len(r['dirty']) - 5}")

    unsaved = [r for r in keep
               if r["dirty"] or r["unfinished"] or WHY_ORPHAN in r["why"]]
    if unsaved:
        print(f"\nС несохранённой работой: {len(unsaved)} "
              f"(незакоммиченное — {len(dirty)}). Инструмент их не снимает "
              f"никогда —\nсходите и посмотрите сами: там дважды находилась "
              f"готовая невыложенная работа.")
    print(f"\nСвежее {fresh_hours} ч не трогаю вовсе; «давно» — от {stale_days} сут.")


# ---------------------------------------------------------------------------
# Проверка самой проверки: настоящий репозиторий, настоящие копии, настоящее
# снятие. Инструмент, который сносит лишнее, ошибается один раз.

def _wt(repo, root, name, branch, files=None, commit=True):
    path = os.path.join(root, name)
    git("worktree", "add", "-b", branch, path, "main", cwd=repo)
    for fname, body in (files or {}).items():
        open(os.path.join(path, fname), "w", encoding="utf-8").write(body)
    if files and commit:
        git("add", "-A", cwd=path)
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", name, cwd=path)
    return path


def _age(path, days):
    """Состарить копию: сдвинуть время служебного каталога назад."""
    when = time.time() - days * 86400
    gitdir = gitdir_of(path)
    for base in (gitdir, path):
        if base and os.path.isdir(base):
            for name in os.listdir(base):
                try:
                    os.utime(os.path.join(base, name), (when, when))
                except OSError:
                    pass
            os.utime(base, (when, when))


def selftest():
    root = tempfile.mkdtemp(prefix="worktrees-clean-")
    fails = []
    try:
        repo = os.path.join(root, "repo")
        os.makedirs(repo)
        git("init", "-b", "main", "-q", repo)
        git("config", "user.email", "t@t", cwd=repo)
        git("config", "user.name", "t", cwd=repo)
        open(os.path.join(repo, "README"), "w").write("x\n")
        git("add", "-A", cwd=repo)
        git("commit", "-q", "-m", "первый", cwd=repo)
        first_sha = git("rev-parse", "HEAD", cwd=repo)[1].strip()

        trees = os.path.join(root, "trees")
        os.makedirs(trees)

        # Слитая и брошенная: работа уехала в main, копия никому не нужна.
        merged_wt = _wt(repo, trees, "slitaya", "feature/slitaya", {"a.txt": "a\n"})
        git("merge", "--no-ff", "-m", "слияние", "feature/slitaya", cwd=repo)
        _age(merged_wt, 5)

        # Слитая, но грязная — ровно тот случай, ради которого всё писалось.
        dirty_wt = _wt(repo, trees, "gryaznaya", "feature/gryaznaya", {"b.txt": "b\n"})
        git("merge", "--no-ff", "-m", "слияние 2", "feature/gryaznaya", cwd=repo)
        open(os.path.join(dirty_wt, "b.txt"), "a", encoding="utf-8").write("недоделано\n")
        _age(dirty_wt, 5)

        # Грязная только неотслеженным файлом: новый файл, который ещё
        # не добавляли, — самая обычная форма несделанной работы.
        new_wt = _wt(repo, trees, "novyy-fayl", "feature/novyy", {"c.txt": "c\n"})
        git("merge", "--no-ff", "-m", "слияние 3", "feature/novyy", cwd=repo)
        open(os.path.join(new_wt, "chernovik.txt"), "w", encoding="utf-8").write("я тут был\n")
        _age(new_wt, 5)

        # Брошенная с неслитой веткой: копию снимаем, ветку оставляем.
        stale_wt = _wt(repo, trees, "broshennaya", "feature/broshennaya", {"d.txt": "d\n"})
        _age(stale_wt, 5)

        # Свежая и слитая: тут может сидеть живой агент.
        fresh_wt = _wt(repo, trees, "svezhaya", "feature/svezhaya", {"e.txt": "e\n"})
        git("merge", "--no-ff", "-m", "слияние 4", "feature/svezhaya", cwd=repo)

        # Неслитая и не старая: ещё рано.
        young_wt = _wt(repo, trees, "molodaya", "feature/molodaya", {"f.txt": "f\n"})
        _age(young_wt, 1)

        # Остановленный rebase при ЧИСТОМ дереве. Останавливаем `--exec false`:
        # шаг применён, проверка провалилась, rebase ждёт человека. Ветки
        # у копии в этот момент нет — HEAD отцеплен, — а `git status` пуст.
        rebase_wt = _wt(repo, trees, "rebase-stoit", "feature/rebase", {"g.txt": "g\n"})
        git("rebase", "--exec", "false", "main", cwd=rebase_wt, check=False)
        rebase_marker = os.path.join(rebase_wt, "g.txt")
        if not unfinished(rebase_wt):
            fails.append("фикстура не завела остановленный rebase — "
                         "проверка ниже ничего не доказывает")
        if dirty_files(rebase_wt):
            fails.append("фикстура остановленного rebase оказалась грязной — "
                         "тогда её ловит прежнее правило, а не новое")
        _age(rebase_wt, 5)

        # Отцепленный HEAD с настоящим коммитом: дерево чистое, ветки нет,
        # и коммит не держит ничего, кроме самой копии.
        loose_wt = os.path.join(trees, "otcepleny")
        git("worktree", "add", "--detach", loose_wt, "main", cwd=repo)
        open(os.path.join(loose_wt, "h.txt"), "w", encoding="utf-8").write("h\n")
        git("add", "-A", cwd=loose_wt)
        git("-c", "user.email=t@t", "-c", "user.name=t",
            "commit", "-q", "-m", "работа без ветки", cwd=loose_wt)
        loose_sha = git("rev-parse", "HEAD", cwd=loose_wt)[1].strip()
        held = git("branch", "--all", "--contains", loose_sha, cwd=repo, check=False)[1]
        if held.strip():
            fails.append("фикстура отцепленной копии оказалась под веткой "
                         f"({held.split()[0]}) — терять там нечего, и проверка "
                         "ниже ничего не доказывает")
        _age(loose_wt, 5)

        # Идущий bisect — и он тут не для полноты списка операций, а потому
        # что остальные признаки на нём молчат **все**: дерево чистое, HEAD
        # отцеплен на коммите, который уже в main, своих коммитов нет. То есть
        # эту копию держит только сторож незавершённой операции, и снятие
        # уносит найденную половину поиска.
        bisect_wt = os.path.join(trees, "bisect-idet")
        git("worktree", "add", "--detach", bisect_wt, "main", cwd=repo)
        git("bisect", "start", cwd=bisect_wt, check=False)
        git("bisect", "bad", "HEAD", cwd=bisect_wt, check=False)
        git("bisect", "good", first_sha, cwd=bisect_wt, check=False)
        bisect_sha = git("rev-parse", "HEAD", cwd=bisect_wt)[1].strip()
        if not unfinished(bisect_wt):
            fails.append("фикстура не завела bisect — проверка ниже "
                         "ничего не доказывает")
        if dirty_files(bisect_wt) or not merged(repo, bisect_sha, main_refs(repo)):
            fails.append("фикстура bisect поймается другим правилом (грязь "
                         "или коммиты сверх main) — она обязана проверять "
                         "именно незавершённую операцию")
        _age(bisect_wt, 5)

        # Отцепленный HEAD без своих коммитов — просто стоит на main:
        # терять нечего, такую снимаем.
        plain_wt = os.path.join(trees, "otcepleny-pustoy")
        git("worktree", "add", "--detach", plain_wt, "main", cwd=repo)
        _age(plain_wt, 5)

        rows = {r["name"]: r for r in plan(repo, cwd=repo)}
        take = {n for n, r in rows.items() if r["decision"] == TAKE}

        def want_kept(name, word):
            if name in take:
                fails.append(f"копия «{name}» названа к снятию, а не должна быть")
            elif word not in rows[name]["why"]:
                fails.append(f"копия «{name}» оставлена, но причина не названа словами: "
                             f"«{rows[name]['why']}»")

        want_kept("gryaznaya", WHY_DIRTY)
        want_kept("novyy-fayl", WHY_DIRTY)
        want_kept("svezhaya", "живой агент")
        want_kept("molodaya", "не слита")
        want_kept("rebase-stoit", WHY_BUSY)
        want_kept("bisect-idet", WHY_BUSY)
        want_kept("otcepleny", WHY_ORPHAN)
        if "git branch" not in rows.get("otcepleny", {}).get("why", ""):
            fails.append("копия с осиротевшими коммитами оставлена, но чем "
                         "их спасти — не сказано")
        if "otcepleny-pustoy" not in take:
            fails.append("отцепленная копия без своих коммитов не названа "
                         "к снятию: терять в ней нечего, а место она держит")
        if "repo" in take:
            fails.append("главный рабочий каталог назван к снятию")
        if "slitaya" not in take:
            fails.append("слитая и брошенная копия не названа к снятию — "
                         "убирать тогда нечего")
        if "broshennaya" not in take:
            fails.append("брошенная копия с неслитой веткой не названа к снятию")
        if rows.get("broshennaya", {}).get("drop_branch"):
            fails.append("у неслитой ветки предложено удалить ветку — "
                         "это и есть потеря чужой работы")
        if not rows.get("slitaya", {}).get("drop_branch"):
            fails.append("слитая ветка остаётся после снятия копии — "
                         "ветка после слияния должна исчезать")

        # Главный каталог уводится с main — и это не выдумка ради полноты:
        # в настоящем репозитории он в этот момент стоял на ветке доски.
        # `git branch -d` сверяет слитость с HEAD того места, откуда его
        # зовут, и на этой ветке отказал бы «not fully merged» ветке,
        # которая в main слита. Дальше вся фаза снятия идёт отсюда.
        slitaya_sha = git("rev-parse", "refs/heads/feature/slitaya", cwd=repo)[1].strip()
        git("branch", "storonnyaya", first_sha, cwd=repo)
        git("switch", "-q", "storonnyaya", cwd=repo)
        if git_ok("merge-base", "--is-ancestor", slitaya_sha, "HEAD", cwd=repo):
            fails.append("фикстура «ROOT не на main» не получилась: слитая ветка "
                         "всё равно в HEAD главного каталога, и проверка ниже "
                         "ничего не доказывает")

        # Снятие целиком: решение может быть верным, а руки — нет.
        for r in [rows[n] for n in sorted(take)]:
            remove(repo, r)

        if not os.path.exists(os.path.join(dirty_wt, "b.txt")):
            fails.append("снята копия с незакоммиченными правками")
        if not os.path.exists(os.path.join(new_wt, "chernovik.txt")):
            fails.append("снята копия с неотслеженным файлом")
        if not os.path.exists(rebase_marker) or not unfinished(rebase_wt):
            fails.append("снята копия с остановленным rebase — вместе с ней "
                         "уходит весь прогресс операции")
        if not unfinished(bisect_wt):
            fails.append("снята копия с идущим bisect — найденная половина "
                         "поиска уходит вместе с ней")
        # Копия — единственное, что держит этот коммит (проверено при заводе
        # фикстуры: под веткой он не лежит). Уйдёт копия — коммит осиротеет
        # и пропадёт с ближайшим git gc, а вернуть его будет нечем.
        if not os.path.exists(os.path.join(loose_wt, "h.txt")):
            fails.append("снята копия с коммитами, которых не держит ни одна ветка")
        if os.path.exists(merged_wt):
            fails.append("слитая копия осталась на месте: снятия не произошло")
        if not os.path.exists(fresh_wt):
            fails.append("снята свежая копия — так теряют чужую волну")

        # «*» — текущая ветка, «+» — занятая другой копией.
        left = {ln.strip().lstrip("*+ ") for ln in git("branch", cwd=repo)[1].splitlines()}
        if "feature/slitaya" in left:
            fails.append("слитая ветка не удалена — а главный каталог стоит "
                         "не на main, и это ровно тот случай, в котором "
                         "`git branch -d` отвечает «not fully merged» ветке, "
                         "слитой в main")
        if "feature/broshennaya" not in left:
            fails.append("удалена неслитая ветка — коммиты, которых нет в main, "
                         "это чья-то работа")
        if "feature/gryaznaya" not in left:
            fails.append("удалена ветка копии с незакоммиченными правками")

        # Причина отказа git обязана доезжать до отчёта: без неё «НЕ снял —
        # git отказался:» обрывается на двоеточии ровно в аварийном случае.
        rc, out = git("branch", "-d", "feature/net-takoy", cwd=repo, check=False)
        if rc == 0 or not out.strip():
            fails.append("отказ git возвращается без причины — диагностика "
                         "пропадает там, где она и нужна")
    except GitError as e:
        fails.append(f"проверка не отработала: {e}")
    finally:
        # Ссылки на временные копии остаются в репозитории, а он тут же
        # и удаляется — чистим каталогом, а не git'ом.
        shutil.rmtree(root, ignore_errors=True)
    return fails


def main():
    ap = argparse.ArgumentParser(add_help=True, description=__doc__.splitlines()[0])
    ap.add_argument("--снять", "--remove", dest="remove", action="store_true",
                    help="снять названное (по умолчанию только показ)")
    ap.add_argument("--давно", dest="stale", type=float, default=STALE_DAYS,
                    metavar="СУТОК", help=f"порог «агента давно нет» (по умолчанию {STALE_DAYS})")
    ap.add_argument("--свежие", dest="fresh", type=float, default=FRESH_HOURS,
                    metavar="ЧАСОВ", help=f"не трогать ничего моложе (по умолчанию {FRESH_HOURS})")
    ap.add_argument("--самопроверка", "--selftest", dest="selftest", action="store_true",
                    help="только проверить саму проверку и выйти")
    args = ap.parse_args()

    broken = selftest()
    if broken:
        print("Проверка сломана и потому ничего не доказывает:\n")
        for line in broken:
            print("  •", line)
        return 1
    if args.selftest:
        print("Не снимаются: грязные, с остановленной операцией git, "
              "с коммитами без ветки и свежие.\nСлитые уходят вместе "
              "с ветками, брошенные — без них.")
        return 0

    try:
        rows = plan(ROOT, fresh_hours=args.fresh, stale_days=args.stale)
    except GitError as e:
        print(e)
        return 1

    show(rows, args.fresh, args.stale)

    if not args.remove:
        print("\nНичего не снято: это показ. Снять — "
              "./tools/worktrees-clean.py --снять")
        return 0

    print("\nСнимаю:")
    for r in [r for r in rows if r["decision"] == TAKE]:
        for line in remove(ROOT, r):
            print("  " + line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
