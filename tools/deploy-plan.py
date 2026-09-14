#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Чем мигрировать при выкладке: план по двум версиям, а не по одной.

  ./tools/deploy-plan.py --current <SHA> --target <SHA>   что делать со схемой
  ./tools/deploy-plan.py --target <SHA> --current-unknown  первая выкладка
  ./tools/deploy-plan.py --selftest                        проверка самого плана

ЗАЧЕМ. Шаг миграции (`ops/schema-sync.sh`, задача 0073) знает только целевую
версию — «привести схемы к версии этого образа». Вверх этого хватает, вниз
нет: образ версии 100, встретив схему 103, опустить её не может физически.
Тела `--rollback` для changeset'ов 101–103 лежат в ЧУЖОМ jar, а
`rollbackCount` их не видит и снял бы вместо них последние СВОИ. Поэтому
0073 такую схему не трогает, называет по имени и возвращает ненулевой код —
поведение правильное, но требование владельца («не должно быть такого, чтобы
версия приложения была 100, а база соответствовала версии 103») само по себе
оно не выполняет.

Выполняется оно ПОРЯДКОМ ШАГОВ, и порядок считает этот файл: откат с 103
на 100 — это одноразовый контейнер ТОЙ СБОРКИ, ЧТО СЕЙЧАС РАЗВЁРНУТА (у неё
есть тела откатов), приводящий схему к 100, и только потом перевод трафика
на образ 100. То есть кнопка обязана знать ОБЕ версии: куда откатываемся
и что сейчас стоит. Мигрируем СТАРШИМ образом, разворачиваем МЛАДШИЙ.

ОТКУДА БЕРЁТСЯ ВЕРСИЯ. Версия образа — число changeset'ов набора арендатора
в том коммите, из которого он собран (`TenantSchemaMigrator.totalChangeSets`,
`db/rollback-cost.py`). Спрашивать её у самого образа значило бы запускать
контейнер с доступом к базе только затем, чтобы узнать число, — а git знает
его и так, по обоим SHA сразу, ДО того как что-нибудь запущено. Разбор
changelog'а при этом не свой: берётся `read()` из `db/rollback-cost.py`,
чтобы порядок наката считался одним выражением на весь проект.

ЧТО СЧИТАЕТСЯ НЕИЗВЕСТНЫМ. SHA, которого нет в репозитории, и «сейчас
не развёрнуто ничего» — это отказ и вопрос человеку, а не «наверное, вверх».
Угаданное направление здесь означает накат не тем образом, и молча.
"""
import argparse
import importlib.util
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_rollback_cost():
    """`db/rollback-cost.py` как модуль: имя с дефисом обычным import не берётся.

    Свой разбор changelog'а был бы вторым местом, знающим порядок наката,
    — и разъехался бы с первым на первой же правке манифеста.
    """
    path = os.path.join(ROOT, "db", "rollback-cost.py")
    spec = importlib.util.spec_from_file_location("rollback_cost", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def git(repo, *args):
    return subprocess.run(["git", "-C", repo, *args],
                          capture_output=True, text=True)


def git_bytes(repo, *args):
    """То же, но без раскодирования: `git archive` отдаёт tar, и прогнанный
    через текст он приезжает битым — заметно это не сразу, а на первом же
    файле с не-ASCII именем."""
    return subprocess.run(["git", "-C", repo, *args], capture_output=True)


def resolve(repo, ref):
    """Полный SHA или отказ. Сокращённого здесь не бывает: тег образа —
    сорок символов, и короткий в реестре не найдётся."""
    done = git(repo, "rev-parse", "--verify", f"{ref}^{{commit}}")
    if done.returncode != 0:
        raise SystemExit(f"В репозитории нет коммита «{ref}»: {done.stderr.strip()}")
    return done.stdout.strip()


def version_of(repo, sha, rc):
    """Версия схемы у сборки из этого коммита — число changeset'ов набора.

    Changelog достаётся из самого коммита (`git archive`), а не из рабочего
    дерева: считать версию ПРОШЛОЙ сборки по сегодняшнему дереву значило бы
    всегда получать сегодняшнее число, то есть никогда не видеть отката.
    """
    with tempfile.TemporaryDirectory() as tmp:
        archive = git_bytes(repo, "archive", sha, "db/changelog")
        if archive.returncode != 0:
            raise SystemExit(f"Не достать db/changelog из {sha[:12]}: "
                             f"{archive.stderr.decode(errors='replace').strip()}")
        done = subprocess.run(["tar", "-x", "-C", tmp], input=archive.stdout)
        if done.returncode != 0:
            raise SystemExit(f"Не развернуть db/changelog из {sha[:12]}")
        root = os.path.join(tmp, "db", "changelog")
        if not os.path.exists(os.path.join(root, rc.MANIFEST)):
            raise SystemExit(
                f"В коммите {sha[:12]} нет db/changelog/{rc.MANIFEST} — "
                "версию схемы этой сборки посчитать нечем")
        return len(rc.read(root=root))


def plan(current_version, target_version):
    """Чем мигрировать и до какой версии.

    Единственное место, где записано правило «старшим образом»: вниз
    мигрирует развёрнутая сейчас сборка (у неё есть тела откатов), вверх —
    выкладываемая. Равенство версий — обычный случай выкладки без новых
    changeset'ов, и мигрировать его можно чем угодно; берём выкладываемую,
    чтобы не запускать лишним образом то, что и так пустая операция.
    """
    if current_version is not None and target_version < current_version:
        return {
            "direction": "вниз",
            "migrate_with": "current",
            "to": str(target_version),
            "why": (f"схема идёт вниз {current_version} → {target_version}: "
                    f"тела откатов есть только у развёрнутой сейчас сборки, "
                    f"ею и опускаем — выкладываемая их не видит вовсе"),
        }
    if current_version is not None and target_version == current_version:
        return {
            "direction": "на месте",
            "migrate_with": "target",
            "to": "",
            "why": f"версия схемы не меняется ({target_version}) — накат будет пустой операцией",
        }
    return {
        "direction": "вверх",
        "migrate_with": "target",
        "to": "",
        "why": ("схема идёт вверх"
                + (f" {current_version} → {target_version}" if current_version is not None
                   else f" до {target_version}")
                + ": новые changeset'ы лежат только в выкладываемом образе, им и накатываем"),
    }


def rollback_price(repo, current_sha, current_version, target_version, rc, out=print):
    """Цена отката — до нажатия, а не после.

    Changeset'ы, которые снимут, есть ТОЛЬКО в старшем коммите: считать их
    по дереву младшего значило бы не увидеть ни одного из них.
    """
    with tempfile.TemporaryDirectory() as tmp:
        archive = git_bytes(repo, "archive", current_sha, "db/changelog")
        if archive.returncode != 0:
            out("Цену отката посчитать не вышло: не достать db/changelog "
                f"из {current_sha[:12]}")
            return
        subprocess.run(["tar", "-x", "-C", tmp], input=archive.stdout)
        sets = rc.read(root=os.path.join(tmp, "db", "changelog"))
        rc.cost(sets, current_version, target_version, out=out)


# ────────────────────────────── самопроверка ──────────────────────────────

def selftest():
    """Без сети и без стенда: настоящий git-репозиторий на три коммита.

    Проверка, которая не краснеет на дефекте, хуже отсутствующей, поэтому
    каждый случай здесь про свою ошибку, а не «разбор отработал».
    """
    ok = [0]

    def check(name, expected, actual):
        if expected == actual:
            print(f"  ✓ {name}")
        else:
            print(f"  \033[1;31m✗ {name}: ожидалось «{expected}», получено «{actual}»\033[0m",
                  file=sys.stderr)
            ok[0] = 1

    print("Самопроверка tools/deploy-plan.py")

    # 1–4. Правило «старшим образом» — на чистых числах, без git.
    check("вниз мигрирует развёрнутая сейчас сборка", "current",
          plan(103, 100)["migrate_with"])
    check("вниз называется целевая версия", "100", plan(103, 100)["to"])
    check("вверх мигрирует выкладываемая", "target", plan(100, 103)["migrate_with"])
    check("вверх целевой версии не называют", "", plan(100, 103)["to"])
    check("равенство версий — выкладываемой", "target", plan(103, 103)["migrate_with"])
    check("первая выкладка — выкладываемой", "target", plan(None, 103)["migrate_with"])

    # Разбор changelog'а берётся у `db/rollback-cost.py` (см. шапку), и берётся
    # importlib'ом из корня — то есть без `db/` в sys.path. Названный ✗ здесь
    # дешевле трассировки: шов между двумя задачами видно по имени, а не по
    # стеку чужого файла, и остальные случаи говорят, докуда дошла проверка.
    try:
        rc = load_rollback_cost()
        print("  ✓ db/rollback-cost.py грузится модулем со своими зависимостями")
    except Exception as e:
        print(f"  \033[1;31m✗ db/rollback-cost.py не грузится модулем: "
              f"{type(e).__name__}: {e}\033[0m", file=sys.stderr)
        print("\033[1;31mСамопроверка не прошла\033[0m", file=sys.stderr)
        return 1

    # 5. Версия считается ПО КОММИТУ, а не по рабочему дереву. Это и есть
    #    та ошибка, из-за которой откат был бы невидим: оба SHA дали бы
    #    сегодняшнее число, направление всегда получалось бы «вверх»,
    #    и вниз мигрировали бы младшим образом — то есть никак.
    repo = tempfile.mkdtemp()
    try:
        subprocess.run(["git", "-C", repo, "init", "-q"], check=True)
        subprocess.run(["git", "-C", repo, "config", "user.email", "ci@example.ru"], check=True)
        subprocess.run(["git", "-C", repo, "config", "user.name", "CI"], check=True)
        os.makedirs(os.path.join(repo, "db", "changelog", "tenant"))

        def commit(count, message):
            changelog = os.path.join(repo, "db", "changelog")
            includes = "\n".join(
                f'  <include file="tenant/{i:03d}-x.sql" relativeToChangelogFile="true"/>'
                for i in range(1, count + 1))
            with open(os.path.join(changelog, rc.MANIFEST), "w", encoding="utf-8") as f:
                f.write('<?xml version="1.0" encoding="UTF-8"?>\n'
                        '<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">\n'
                        f"{includes}\n</databaseChangeLog>\n")
            for i in range(1, count + 1):
                with open(os.path.join(changelog, "tenant", f"{i:03d}-x.sql"),
                          "w", encoding="utf-8") as f:
                    f.write(f"--liquibase formatted sql\n--changeset a:tenant-{i:03d}\n"
                            f"CREATE TABLE t{i}();\n--rollback DROP TABLE t{i};\n")
            subprocess.run(["git", "-C", repo, "add", "-A"], check=True)
            subprocess.run(["git", "-C", repo, "commit", "-qm", message], check=True)
            return git(repo, "rev-parse", "HEAD").stdout.strip()

        old = commit(2, "две миграции")
        new = commit(5, "пять миграций")

        check("версия старого коммита", 2, version_of(repo, old, rc))
        check("версия нового коммита", 5, version_of(repo, new, rc))

        # 6. Полный круг: откат с нового на старый обязан сказать «вниз,
        #    старшим образом, до версии 2».
        p = plan(version_of(repo, new, rc), version_of(repo, old, rc))
        check("откат: направление", "вниз", p["direction"])
        check("откат: чем мигрировать", "current", p["migrate_with"])
        check("откат: до какой версии", "2", p["to"])

        # 7. Неизвестный SHA — отказ, а не «наверное, вверх».
        try:
            resolve(repo, "0" * 40)
            print("  \033[1;31m✗ несуществующий SHA обязан быть отказом\033[0m",
                  file=sys.stderr)
            ok[0] = 1
        except SystemExit:
            print("  ✓ несуществующий SHA — отказ")

        # 8. Коммит без changelog'а — тоже отказ: посчитанная как ноль версия
        #    означала бы «всё откатить» на живой базе.
        subprocess.run(["git", "-C", repo, "rm", "-rq", "db"], check=True)
        subprocess.run(["git", "-C", repo, "commit", "-qm", "без changelog"], check=True)
        bare = git(repo, "rev-parse", "HEAD").stdout.strip()
        try:
            version_of(repo, bare, rc)
            print("  \033[1;31m✗ коммит без changelog обязан быть отказом\033[0m",
                  file=sys.stderr)
            ok[0] = 1
        except SystemExit:
            print("  ✓ коммит без changelog — отказ")
    finally:
        subprocess.run(["rm", "-rf", repo])

    if ok[0]:
        print("\033[1;31mСамопроверка не прошла\033[0m", file=sys.stderr)
        return 1
    print("\033[1;32mСамопроверка пройдена\033[0m")
    return 0


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--target", help="SHA или ветка выкладываемой версии")
    ap.add_argument("--current", help="SHA версии, которая сейчас под трафиком")
    ap.add_argument("--current-unknown", action="store_true",
                    help="на стенде ещё ничего не развёрнуто — первая выкладка")
    ap.add_argument("--repo", default=ROOT)
    ap.add_argument("--github-output", action="store_true",
                    help="дописать план в $GITHUB_OUTPUT для следующих шагов")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if not args.target:
        raise SystemExit("нужен --target: SHA или ветка выкладываемой версии")
    if not args.current and not args.current_unknown:
        raise SystemExit(
            "нужен --current (SHA под трафиком) или --current-unknown. "
            "Угаданное направление означает накат не тем образом, и молча")

    rc = load_rollback_cost()
    target_sha = resolve(args.repo, args.target)
    target_version = version_of(args.repo, target_sha, rc)

    current_sha = None
    current_version = None
    if args.current:
        current_sha = resolve(args.repo, args.current)
        current_version = version_of(args.repo, current_sha, rc)

    p = plan(current_version, target_version)

    print(f"Выкладываем:  {target_sha}  (версия схемы {target_version})")
    print("Сейчас стоит: "
          + (f"{current_sha}  (версия схемы {current_version})" if current_sha
             else "ничего — первая выкладка"))
    print(f"Направление:  {p['direction']} — {p['why']}")
    print("Мигрировать:  "
          + ("образом РАЗВЁРНУТОЙ СЕЙЧАС сборки" if p["migrate_with"] == "current"
             else "образом выкладываемой сборки")
          + (f", --to {p['to']}" if p["to"] else ""))

    if p["direction"] == "вниз":
        print()
        rollback_price(args.repo, current_sha, current_version, target_version, rc)

    if args.github_output and os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
            f.write(f"target_sha={target_sha}\n")
            f.write(f"target_version={target_version}\n")
            f.write(f"current_sha={current_sha or ''}\n")
            f.write(f"current_version={current_version if current_version is not None else ''}\n")
            f.write(f"direction={p['direction']}\n")
            f.write(f"migrate_with={p['migrate_with']}\n")
            f.write(f"to={p['to']}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
