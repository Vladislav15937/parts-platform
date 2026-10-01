#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Статическая проверка changelog'ов: то, чего не видит verify.sh.

verify.sh накатывает и откатывает — то есть проверяет поведение на чистой базе.
Здесь проверяется форма набора: не разъехались ли манифест и каталог файлов,
уникальны ли номера и не тронут ли уже выпущенный changeset. Всё три ломаются
ровно тогда, когда над схемой работают в несколько рук, и ни одно не видно
на чистой базе: она про то, как накатывается набор целиком, а не про то,
что случится у клиента, накатанного вчера.

Правка применённого changeset'а — самое дорогое из этого: чек-суммы строгие
намеренно, и деплой встанет у всех, кто уже накатан, а не у того, кто правил.
Поэтому «выпущенным» считается всё, что есть в базовой ветке.

  ./db/check-changelog.py [база]      по умолчанию origin/main
  ./db/check-changelog.py --strict    невыполненная сверка — красное (так зовёт CI)
  ./db/check-changelog.py --selftest  проверить сторожа на подделках

БЕЗ БАЗЫ СТОРОЖ БОЛЬШЕ НЕ ОТВЕЧАЕТ «ВЫПУЩЕННОЕ НЕ ТРОНУТО» (задача 0241).
Пункты про неизменяемость, порядок include'ов и запрет логики в базе сверяются
с точкой расхождения — и целиком лежали в ветке `else`: без `origin/main` они
не выполнялись вовсе, в `problems` не попадало ничего, а сторож печатал
«Changelog в порядке: … выпущенное не тронуто» и выходил нулём. То есть самое
дорогое правило набора — правка выпущенного changeset'а валит накат у ВСЕХ
накатанных клиентов — объявлялось проверенным по одной строке «проверка
неизменяемости пропущена», которую никто не читает.

Теперь исход «спросить не удалось» называется словами и перечисляет, что
именно не сверено, а зелёной строки про выпущенное в нём нет. Красит ли это
прогон — решено вслух: локально нет (сторож ходит и без сети, `./mvnw -o`
здесь не случайность, а проверка, роняющая прогон из-за неподтянутой ссылки,
живёт до первого такого утра), в прогоне да — `--strict`, и ссылку задача CI
подтягивает сама.
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHANGELOG = os.path.join(ROOT, "db", "changelog")
NS = {"lb": "http://www.liquibase.org/xml/ns/dbchangelog"}
MANIFESTS = ["db.changelog-catalog.xml", "db.changelog-tenant.xml"]

# Что считается логикой в базе. Процедуры тоже: pg_proc не различает их
# с функциями, и «у меня не функция, а процедура» — не исключение, а способ
# сказать то же самое другими словами. Правила (CREATE RULE) — третий способ:
# они переписывают запрос молча, и увидеть это можно только в схеме.
LOGIC_IN_DB = re.compile(
    r'\bCREATE\s+(?:OR\s+REPLACE\s+)?'
    r'(?:CONSTRAINT\s+)?(FUNCTION|PROCEDURE|TRIGGER|RULE)\b',
    re.IGNORECASE)

problems = []


def fail(msg):
    problems.append(msg)


def git(*args):
    r = subprocess.run(["git", "-C", ROOT] + list(args),
                       capture_output=True, text=True)
    return r.returncode, r.stdout


def includes(manifest):
    """Пути include'ов в порядке манифеста — порядок и есть порядок наката."""
    tree = ET.parse(os.path.join(CHANGELOG, manifest))
    out = []
    for el in tree.getroot().iter():
        if el.tag.endswith("}include") or el.tag == "include":
            out.append(el.get("file"))
    return out


ФЛАГИ = ("--strict", "--selftest")


def разобрать(args):
    """(база, строгий ли, самопроверка ли, непонятое). Неизвестный флаг — отказ.

    Приём взят у `tools/board.py`: молча отработать как полный прогон на чужом
    флаге значит ответить «да» на вопрос «а ты себя проверяешь?» видом успешной
    работы.
    """
    base, strict, selftest_, unknown = None, False, False, []
    for a in args:
        if a == "--strict":
            strict = True
        elif a == "--selftest":
            selftest_ = True
        elif a.startswith("--"):
            unknown.append(a)
        elif base is None:
            base = a
        else:
            unknown.append(a)
    return base or "origin/main", strict, selftest_, unknown


def base_point(base):
    """(видна ли база, точка расхождения, причина).

    «Ссылки нет» и «общей истории нет» различаются словами: первое чинится
    `git fetch`, второе означает, что сравнивать не с чем по существу.
    Одно место на весь файл — до задачи 0241 этот вопрос задавался дважды,
    и ответ на него молча терялся в обеих ветках.
    """
    code, _ = git("rev-parse", "--verify", "-q", base)
    if code != 0:
        return False, "", f"ссылки {base} в этой копии нет"
    code, out = git("merge-base", "HEAD", base)
    mb = out.strip()
    if code != 0 or not mb:
        return False, "", f"у HEAD и {base} нет общей истории"
    return True, mb, ""


НЕ_СВЕРЕНО = (
    "  СВЕРКА С БАЗОЙ НЕ ВЫПОЛНЕНА: {почему}.\n"
    "  НЕ ПРОВЕРЕНО: неизменяемость выпущенных changeset'ов, сохранность\n"
    "  порядка include'ов и запрет логики в базе у новых changeset'ов —\n"
    "  все три сверяются с точкой расхождения, а её нет.\n"
    "  Подтяните ссылку: git fetch --no-tags origin main:refs/remotes/origin/main"
)


def main():
    base, strict, want_selftest, unknown = разобрать(sys.argv[1:])
    if unknown:
        print(f"Неизвестный аргумент: {', '.join(unknown)}")
        print(f"Есть: [база] (по умолчанию origin/main), {', '.join(ФЛАГИ)}.")
        return 2
    if want_selftest:
        return selftest()

    base_seen, mb, почему = base_point(base)

    # 1. Манифест и каталог файлов обязаны совпадать в обе стороны.
    #    Файл без include не накатится ни у кого — и это заметят через недели,
    #    когда запрос упрётся в несуществующую колонку. Include без файла валит
    #    накат целиком, то есть заведение любого нового клиента.
    listed = {}
    for m in MANIFESTS:
        for path in includes(m):
            if path in listed:
                fail(f"{path} включён дважды: в {listed[path]} и в {m}")
            listed[path] = m
            if not os.path.exists(os.path.join(CHANGELOG, path)):
                fail(f"{m} включает {path}, а файла нет")

    on_disk = set()
    for group in ("catalog", "tenant"):
        d = os.path.join(CHANGELOG, group)
        for name in sorted(os.listdir(d)):
            if name.endswith(".sql"):
                on_disk.add(f"{group}/{name}")
    for path in sorted(on_disk - set(listed)):
        fail(f"{path} лежит в каталоге, но не включён ни одним манифестом")

    # 2. Номер уникален внутри группы. Это и есть столкновение двух рук:
    #    оба взяли следующий свободный номер, git слил обе строки без конфликта.
    seen = {}
    for path in sorted(on_disk):
        group, name = path.split("/")
        m = re.match(r"^(\d+)-", name)
        if not m:
            fail(f"{path}: имя обязано начинаться с номера")
            continue
        key = (group, m.group(1))
        if key in seen:
            fail(f"номер {m.group(1)} занят дважды: {seen[key]} и {path}")
        seen[key] = path

    # 3. Идентификатор changeset уникален по всему набору, и файл объявлен
    #    форматом Liquibase — без первой строки он молча накатывается как один
    #    безымянный changeset, и откат такого не разрежешь.
    ids = {}
    for path in sorted(on_disk):
        text = open(os.path.join(CHANGELOG, path), encoding="utf-8").read()
        if not text.startswith("--liquibase formatted sql"):
            fail(f"{path}: нет строки «--liquibase formatted sql» в начале")
        found = re.findall(r"^--changeset\s+(\S+:\S+)", text, re.M)
        if not found:
            fail(f"{path}: нет ни одного --changeset")
        for cid in found:
            if cid in ids:
                fail(f"changeset {cid} объявлен дважды: {ids[cid]} и {path}")
            ids[cid] = path

    # 3б. Логики в базе не заводить. Правило записано в корневом CLAUDE.md,
    #     и до сих пор его держал только db/verify.sh — то есть полный прогон
    #     с Docker, отвечающий про конечное состояние схемы, а не про то,
    #     какой changeset его испортил. Здесь то же правило ловится статически
    #     и называет файл.
    #
    #     Смотрим только НОВЫЕ changeset'ы и только прямой ход: исторические
    #     заводили триггеры законно (их сняли tenant/049 и /051), а строки
    #     --rollback возвращают прежнее состояние и обязаны их содержать —
    #     мост отката tenant/054 весь из них и состоит.
    if base_seen:
        for path in sorted(on_disk):
            rel = f"db/changelog/{path}"
            code, _ = git("cat-file", "-e", f"{mb}:{rel}")
            if code == 0:
                continue                      # выпущенный: историю не судим
            for i, line in enumerate(open(os.path.join(CHANGELOG, path),
                                          encoding="utf-8"), 1):
                bare = line.strip()
                if bare.startswith("--rollback") or bare.startswith("--comment"):
                    continue
                m = LOGIC_IN_DB.search(bare)
                if m:
                    fail(f"{path}:{i}: {m.group(1).upper()} — логика в базе. "
                         f"Она живёт в Java: триггер невидим оттуда, откуда "
                         f"наблюдают его последствия, отладчик через него "
                         f"не проходит, тест на сервис его не покрывает. "
                         f"Причины и цена переноса — docs/triggers-to-java.md")

    # 4. Выпущенное не трогать. Liquibase считает чек-сумму по содержимому,
    #    включая текст отката и комментарии, — правка валит накат у всех,
    #    кто уже накатан, и чинится только новым changeset'ом.
    if not base_seen:
        print(НЕ_СВЕРЕНО.format(почему=почему))
    else:
        code, out = git("diff", "--name-only", mb, "--", "db/changelog")
        for rel in out.split():
            name = os.path.basename(rel)
            if name in MANIFESTS:
                continue          # манифест дополняют — это и есть его работа
            code, _ = git("cat-file", "-e", f"{mb}:{rel}")
            if code == 0:
                fail(f"{rel}: изменён уже выпущенный changeset — "
                     f"исправление только новым")

        # 5. Порядок выпущенных include'ов не переставлен и ни один не убран.
        #    Порядок в манифесте и есть порядок наката: переставив его, мы даём
        #    новому клиенту не ту схему, которую получили старые, а убрав —
        #    схему без объекта, который у них есть.
        for m in MANIFESTS:
            code, old = git("show", f"{mb}:db/changelog/{m}")
            if code != 0:
                continue
            was = re.findall(r'<include\s+file="([^"]+)"', old)
            now = includes(m)
            # Ищем каждый выпущенный include правее предыдущего: не нашли —
            # он либо убран, либо переставлен назад. Указатель при промахе
            # не двигаем, иначе одна убранная строка утащит за собой весь
            # хвост и в отчёте окажется десяток мнимых нарушений.
            pos = 0
            for path in was:
                try:
                    pos = now.index(path, pos) + 1
                except ValueError:
                    fail(f"{m}: выпущенный include {path} убран или "
                         f"переставлен — новый клиент получит не ту схему, "
                         f"что уже накатанные")

    if problems:
        print("Changelog: проверка не прошла\n")
        for p in problems:
            print("  •", p)
        print("\nПравила — в db/CLAUDE.md.")
        return 1
    n = len(on_disk)
    if base_seen:
        print(f"Changelog в порядке: {n} changeset'ов, "
              f"номера уникальны, манифесты сходятся, выпущенное не тронуто.")
        return 0
    # Зелёной строки про выпущенное здесь нет намеренно: её и читали как
    # доказательство того, чего не проверяли.
    print(f"Changelog: проверено частично — {n} changeset'ов, номера уникальны, "
          f"манифесты сходятся.")
    print("НЕ СВЕРЕНО с базой: неизменяемость выпущенного, порядок include'ов, "
          "запрет логики в базе.")
    if strict:
        print("С --strict это красное: прогон обязан сверять, ссылка там есть "
              "всегда.")
        return 1
    return 0


# ---------------------------------------------------------------- самопроверка
#
# Сторож, не краснеющий на дефекте, хуже отсутствующего, и установить это можно
# только попыткой. Репозиторий заводится НАСТОЯЩИЙ: вся правка задачи 0241 про
# то, видна ли точка расхождения и что сторож утверждает, когда её нет, —
# на словах это не проверяется, приём взят у `tools/board.py` и
# `tools/test-schema-guard.py`.
#
# Копия сторожа кладётся в `<тмп>/db/`, потому что он считает корень набора
# от своего пути: копия в другом месте судила бы НЕ ТОТ changelog (урок 0151).

МАНИФЕСТ = ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">\n'
            '{}</databaseChangeLog>\n')


def selftest():
    import shutil
    import tempfile

    me = os.path.abspath(__file__)
    сбои = []
    tmp = tempfile.mkdtemp(prefix="changelog-guard-")
    print("Самопроверка db/check-changelog.py")
    try:
        root = os.path.join(tmp, "repo")
        cl = os.path.join(root, "db", "changelog")
        os.makedirs(os.path.join(cl, "catalog"))
        os.makedirs(os.path.join(cl, "tenant"))
        копия = os.path.join(root, "db", "check-changelog.py")
        shutil.copyfile(me, копия)

        def sql(path, cid, body):
            open(os.path.join(cl, path), "w", encoding="utf-8").write(
                f"--liquibase formatted sql\n--changeset {cid}\n{body}\n")

        def manifest(group, paths):
            lines = "".join(
                f'    <include file="{p}" relativeToChangelogFile="true"/>\n'
                for p in paths)
            open(os.path.join(cl, f"db.changelog-{group}.xml"), "w",
                 encoding="utf-8").write(МАНИФЕСТ.format(lines))

        def g(*args):
            subprocess.run(["git", "-C", root] + list(args), check=True,
                           capture_output=True)

        def run(args, script=None):
            p = subprocess.run([sys.executable, script or копия] + list(args),
                               capture_output=True, text=True)
            return p.returncode, p.stdout + p.stderr

        def случай(имя, args, код, есть=(), нет=(), script=None):
            rc, out = run(args, script)
            беды = []
            if rc != код:
                беды.append(f"код возврата {rc}, ждали {код}")
            for s in есть:
                if s not in out:
                    беды.append(f"в выводе нет «{s}»")
            for s in нет:
                if s in out:
                    беды.append(f"в выводе ЕСТЬ «{s}», а его быть не должно")
            if беды:
                сбои.append(f"{имя}: " + "; ".join(беды))
                print(f"  ✗ {имя}: " + "; ".join(беды))
            else:
                print(f"  ✓ {имя}")

        ВЫПУЩЕНО = ["tenant/001-sklad.sql", "tenant/002-dvizheniya.sql"]
        sql("catalog/001-spravochniki.sql", "proba:c1",
            "CREATE TABLE brand (id bigint);")
        sql(ВЫПУЩЕНО[0], "proba:t1", "CREATE TABLE part (id bigint);")
        sql(ВЫПУЩЕНО[1], "proba:t2", "CREATE TABLE stock_movement (id bigint);")
        manifest("catalog", ["catalog/001-spravochniki.sql"])
        manifest("tenant", ВЫПУЩЕНО)

        g("init", "-q")
        g("symbolic-ref", "HEAD", "refs/heads/main")
        g("add", "-A")
        g("-c", "user.email=guard@example", "-c", "user.name=guard",
          "-c", "commit.gpgsign=false", "commit", "-q", "--no-verify",
          "-m", "выпущено")
        g("update-ref", "refs/remotes/origin/main", "HEAD")

        # Обратный край, и он главный: сторож, краснеющий на исправном наборе,
        # будет отключён в первый же день — вместе с защитой.
        случай("исправный набор проходит и говорит про выпущенное", [], 0,
               есть=("Changelog в порядке", "выпущенное не тронуто"))

        # 1. Правка выпущенного changeset'а — самое дорогое нарушение набора:
        #    чек-сумма разъезжается у всех накатанных клиентов.
        with open(os.path.join(cl, ВЫПУЩЕНО[0]), "a", encoding="utf-8") as f:
            f.write("ALTER TABLE part ADD COLUMN pozzhe text;\n")
        случай("правка выпущенного changeset'а краснеет", [], 1,
               есть=("изменён уже выпущенный changeset",))

        # 2. Сломанный порядок include'ов: новый клиент получит не ту схему,
        #    что уже накатанные.
        sql(ВЫПУЩЕНО[0], "proba:t1", "CREATE TABLE part (id bigint);")
        manifest("tenant", list(reversed(ВЫПУЩЕНО)))
        случай("переставленный порядок include'ов краснеет", [], 1,
               есть=("убран или переставлен",))
        manifest("tenant", ВЫПУЩЕНО)

        # 3. Логика в базе у НОВОГО changeset'а (пункт 3б) — он тоже сверяется
        #    с точкой расхождения, и без базы не выполняется вовсе.
        sql("tenant/003-trigger.sql", "proba:t3",
            "CREATE TRIGGER t AFTER INSERT ON part FOR EACH ROW "
            "EXECUTE FUNCTION f();")
        manifest("tenant", ВЫПУЩЕНО + ["tenant/003-trigger.sql"])
        случай("логика в базе у нового changeset'а краснеет", [], 1,
               есть=("логика в базе",))

        # 4. ГЛАВНЫЙ СЛУЧАЙ ЗАДАЧИ 0241: базы нет. В наборе при этом лежат ОБА
        #    нарушения — правленый выпущенный changeset и триггер, — то есть
        #    ровно то, чего сторож без базы увидеть не может. Он обязан сказать
        #    это словами и НЕ утверждать, что выпущенное не тронуто.
        with open(os.path.join(cl, ВЫПУЩЕНО[0]), "a", encoding="utf-8") as f:
            f.write("ALTER TABLE part ADD COLUMN pozzhe text;\n")
        g("update-ref", "-d", "refs/remotes/origin/main")
        случай("без базы сверка объявлена невыполненной, а не пройденной", [], 0,
               есть=("СВЕРКА С БАЗОЙ НЕ ВЫПОЛНЕНА", "НЕ ПРОВЕРЕНО",
                     "запрет логики в базе", "проверено частично"),
               нет=("Changelog в порядке", "выпущенное не тронуто"))
        случай("с --strict невыполненная сверка — красное", ["--strict"], 1,
               есть=("НЕ СВЕРЕНО",))

        # 5. ВОЗВРАТ ДЕФЕКТА. Копия, в которой зелёная строка печатается
        #    безусловно, — это прежняя редакция: на том же дереве она обязана
        #    объявить выпущенное непотронутым. Без этого случая все четыре выше
        #    ничего не утверждают: красное могло бы приходить от чего угодно.
        подделка = os.path.join(root, "db", "fake-check-changelog.py")
        текст = open(me, encoding="utf-8").read()
        якорь = '    if base_seen:\n        print(f"Changelog в порядке:'
        if якорь not in текст:
            сбои.append("подделку негде поставить: итоговая строка изменилась")
            print("  ✗ подделку негде поставить: итоговая строка изменилась")
        else:
            open(подделка, "w", encoding="utf-8").write(текст.replace(
                якорь, '    if True:\n        print(f"Changelog в порядке:', 1))
            # Целость копии — ДО запуска: красное от сломанного синтаксиса
            # говорило бы о подделке, а не о стороже (урок 0198).
            ок = subprocess.run([sys.executable, "-m", "py_compile", подделка],
                                capture_output=True, text=True)
            if ок.returncode != 0:
                сбои.append("подделка не компилируется")
                print("  ✗ подделка не компилируется — её красное не про сторожа")
            else:
                rc, out = run([], подделка)
                if rc == 0 and "выпущенное не тронуто" in out:
                    print("  ✓ возврат дефекта: копия с безусловной зелёной "
                          "строкой объявляет выпущенное непотронутым без базы")
                else:
                    сбои.append("подделка «зелёное безусловно» не воспроизвела "
                                f"дефект (код {rc})")
                    print("  ✗ подделка обязана воспроизвести дефект 0241: "
                          "иначе случай 4 ничего не утверждает")

        # 6. Неизвестный аргумент — отказ, а не полный прогон под видом успеха.
        случай("неизвестный флаг отбит кодом 2", ["--strickt"], 2,
               есть=("Неизвестный аргумент",))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if сбои:
        print("\nСамопроверка не прошла:")
        for s in сбои:
            print("  •", s)
        return 1
    print("\nСторож краснеет на правке выпущенного changeset'а, переставленном\n"
          "порядке include'ов и логике в базе; без точки расхождения называет\n"
          "сверку невыполненной и не утверждает, что выпущенное не тронуто\n"
          "(с --strict это красное). На исправном наборе молчит.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
