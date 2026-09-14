#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Чем мигрировать при выкладке: план по двум версиям, а не по одной.

  ./tools/deploy-plan.py --current <SHA> --target <SHA>   что делать со схемой
  ./tools/deploy-plan.py --target <SHA> --current-unknown  первая выкладка
  ./tools/deploy-plan.py --selftest                        проверка самого плана

ЗАЧЕМ ДВЕ ВЕРСИИ. Выкладка вверх и выкладка вниз устроены по-разному,
и различить их можно, только зная обе: что сейчас стоит и что выкладываем.

ВВЕРХ (100 → 103) схему копии накатывает ВЫКЛАДЫВАЕМЫЙ образ: новые
changeset'ы лежат только в нём.

ВНИЗ (103 → 100) схему НЕ ТРОГАЕТ НИКТО. Решение владельца от 14 сентября
2026 (задача 0112), дословно — «Да, согласен» на правило: откатывается только
приложение, базу при откате не откатывают — её чинят вперёд. Откат схемы
уносит то, что люди записали после выкладки; даже у эталона (blue/green
в Amazon RDS) кнопки отката базы после переключения нет. Правило парности
поэтому такое: база может быть НОВЕЕ приложения, но никогда не старее.
Приложение 100 на базе 103 — нормальное состояние после отката, если
миграции 101–103 совместимы с кодом 100 (дисциплина expand/contract, сторож —
задача 0116); готовность сборки это уже держит несимметрично (0078).

До этого решения вниз мигрировал образ РАЗВЁРНУТОЙ сейчас сборки с `--to`
(«мигрируем старшим, разворачиваем младший», задача 0075). Упразднено:
миграция вниз и возврат слепка остались инструментами стендов и аварий
(`ops/schema-sync.sh --apply --to`, `db/rollback-cost.py`), а не шагом кнопки.

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
    """Чем мигрировать.

    Единственное место, где записано правило: вверх схему копии накатывает
    выкладываемый образ, вниз схему не трогает никто — откатывается только
    приложение, база чинится вперёд (задача 0112). Равенство версий — обычный
    случай выкладки без новых changeset'ов: накат выкладываемым образом будет
    пустой операцией, и он же проверит, что пара «код и схема» сошлась.
    """
    if current_version is not None and target_version < current_version:
        return {
            "direction": "вниз",
            "migrate_with": "none",
            "to": "",
            "why": (f"выкладываем версию младше стоящей ({current_version} → {target_version}): "
                    f"схему не опускаем, база останется новее приложения. "
                    f"Это нормальное состояние после отката, если миграции "
                    f"{target_version + 1}–{current_version} совместимы с кодом "
                    f"{target_version} (сторож — задача 0116)"),
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

    # 1–4. Правило на чистых числах, без git. Вниз схему не трогает никто:
    #      откатывается только приложение (задача 0112). Прежнее «вниз
    #      мигрирует развёрнутая сборка» ops/deploy.sh отбивает словами —
    #      план, выдавший его, остановил бы каждую выкладку отката.
    check("вниз схему не трогает никто", "none", plan(103, 100)["migrate_with"])
    check("вниз целевой версии для миграции нет", "", plan(103, 100)["to"])
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
        #    схему не трогаем» — по версиям, посчитанным из самих коммитов.
        p = plan(version_of(repo, new, rc), version_of(repo, old, rc))
        check("откат: направление", "вниз", p["direction"])
        check("откат: схему не трогаем", "none", p["migrate_with"])
        check("откат: миграции вниз нет", "", p["to"])

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
          + ("НЕ МИГРИРОВАТЬ — база останется новее приложения" if p["migrate_with"] == "none"
             else "образом выкладываемой сборки, на копии базы"))

    if p["direction"] == "вниз":
        # Цена миграции вниз здесь не печатается, потому что миграции вниз
        # в выкладке нет. Сама механика не отменена: на стенде и в аварию
        # схему опускают отдельно — ops/schema-sync.sh --apply --to, а цену
        # до нажатия печатает ./db/rollback-cost.py.
        print()
        print("Схему не опускаем: откатывается только приложение, база чинится вперёд "
              "(задача 0112). Опустить схему на стенде — отдельно, "
              f"./db/rollback-cost.py --from {current_version} --to {target_version} "
              "и ops/schema-sync.sh --apply --to.")

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
