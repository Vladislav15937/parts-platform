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
                             такая ветка после снятия удаляется (`-d`);
  агента давно нет         — старше `--давно` суток. Ветка при этом
                             **остаётся**: коммиты, которых нет в `main`,
                             это чья-то работа, и снимаем мы каталог,
                             а не её.

Порог «давно» — двое суток, и это выбор исполнителя, а не замер: волны
идут по нескольку в день, копия, к которой не прикасались двое суток,
заведомо ничья. Меняется флагом, если окажется мал.

Проверка самой проверки (`selftest`) идёт **перед каждым прогоном**:
она заводит настоящий временный репозиторий с настоящими копиями —
грязной, слитой, брошенной, свежей — и прогоняет через них решение
и снятие целиком. Инструмент, который сносит лишнее, ошибается ровно
один раз, и убедиться в обратном рассуждением нельзя.
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


class GitError(RuntimeError):
    pass


def git(*args, cwd=None, check=True):
    p = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if check and p.returncode:
        raise GitError(f"git {' '.join(args)}: {(p.stderr or p.stdout).strip()}")
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


def main_refs(repo):
    """Чем считать `main`: местной веткой, ссылкой на origin — чем есть."""
    refs = [r for r in ("main", "origin/main")
            if git_ok("rev-parse", "--verify", "--quiet", r + "^{commit}", cwd=repo)]
    if not refs:
        raise GitError("не нашёл ни main, ни origin/main — не с чем сверять слитость")
    return refs


def merged(repo, head, refs):
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

    Порядок проверок и есть договор: грязь перебивает всё остальное,
    свежесть — всё, кроме грязи.
    """
    now = time.time() if now is None else now
    cwd = os.getcwd() if cwd is None else cwd
    refs = main_refs(repo)
    out = []

    for wt in worktrees(repo):
        r = dict(wt, decision=KEEP, why="", dirty=[], age=None,
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


def remove(repo, r):
    """Снять одну копию. Возвращает строки отчёта; ничего не делает молча.

    Грязь перепроверяется **прямо перед снятием**: между показом и запуском
    со `--снять` проходят минуты, и за них в копию мог кто-то сесть.
    """
    done = []
    if r["prunable"]:
        git("worktree", "prune", cwd=repo)
        return [f"{r['name']}: запись о пропавшем каталоге убрана"]

    again = dirty_files(r["path"])
    if again:
        return [f"{r['name']}: НЕ снял — появились незакоммиченные правки "
                f"({len(again)} файл(ов))"]
    if r["locked"]:
        git("worktree", "unlock", r["path"], cwd=repo, check=False)
    rc, out = git("worktree", "remove", r["path"], cwd=repo, check=False)
    if rc:
        return [f"{r['name']}: НЕ снял — git отказался: {out.strip()}"]
    done.append(f"{r['name']}: снята")

    if r["drop_branch"] and r["branch"]:
        # Только -d: он отказывается удалять неслитое. -D тут означал бы
        # «снести чужую работу, если я ошибся веткой».
        rc, out = git("branch", "-d", r["branch"], cwd=repo, check=False)
        done.append(f"    ветка {r['branch']} " +
                    ("удалена" if rc == 0 else f"осталась: {out.strip()}"))
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

    if dirty:
        print(f"\nС незакоммиченными правками: {len(dirty)}. Инструмент их "
              f"не снимает никогда —\nсходите и посмотрите сами: там дважды "
              f"находилась готовая невыложенная работа.")
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

        # Снятие целиком: решение может быть верным, а руки — нет.
        for r in [rows[n] for n in sorted(take)]:
            remove(repo, r)

        if not os.path.exists(os.path.join(dirty_wt, "b.txt")):
            fails.append("снята копия с незакоммиченными правками")
        if not os.path.exists(os.path.join(new_wt, "chernovik.txt")):
            fails.append("снята копия с неотслеженным файлом")
        if os.path.exists(merged_wt):
            fails.append("слитая копия осталась на месте: снятия не произошло")
        if not os.path.exists(fresh_wt):
            fails.append("снята свежая копия — так теряют чужую волну")

        # «*» — текущая ветка, «+» — занятая другой копией.
        left = {ln.strip().lstrip("*+ ") for ln in git("branch", cwd=repo)[1].splitlines()}
        if "feature/slitaya" in left:
            fails.append("слитая ветка не удалена")
        if "feature/broshennaya" not in left:
            fails.append("удалена неслитая ветка — коммиты, которых нет в main, "
                         "это чья-то работа")
        if "feature/gryaznaya" not in left:
            fails.append("удалена ветка копии с незакоммиченными правками")
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
        print("Грязные копии не снимаются, свежие не трогаются, "
              "слитые уходят вместе с ветками.")
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
