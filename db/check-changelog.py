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

  ./db/check-changelog.py [база]     по умолчанию origin/main
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


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "origin/main"

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
    code, _ = git("rev-parse", "--verify", "-q", base)
    if code == 0:
        _, mb = git("merge-base", "HEAD", base)
        mb = mb.strip()
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
    code, _ = git("rev-parse", "--verify", "-q", base)
    if code != 0:
        print(f"  база {base} недоступна — проверка неизменяемости пропущена")
    else:
        _, mb = git("merge-base", "HEAD", base)
        mb = mb.strip()
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
    print(f"Changelog в порядке: {n} changeset'ов, "
          f"номера уникальны, манифесты сходятся, выпущенное не тронуто.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
