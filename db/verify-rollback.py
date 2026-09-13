#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Откат по шагам: каждый changeset обязан возвращать схему ровно к «как было».

  ./db/verify-rollback.py              полный прогон по схеме арендатора
  ./db/verify-rollback.py --selftest   проверка самого сторожа
  ./db/verify-rollback.py --steps N    первые N changeset'ов (для разбора)

ЧЕМ ЭТО ОТЛИЧАЕТСЯ ОТ `db/verify.sh`, ШАГ 7. Тот откатывает схему целиком
и проверяет, что таблиц не осталось, — то есть утверждение «всё откатывается
до пустоты». Выкладке нужно другое: «откатится ровно эта разница, и схема
станет в точности такой, какой была». Отличаются они не строгостью, а смыслом:
откат до нуля проходит и при неверном откате отдельного changeset'а — следующий
за ним снесёт таблицу целиком и спрячет разницу. Пример из свежего:
`064-part-number` откатывается как DROP INDEX → DROP COLUMN → DROP SEQUENCE;
переставь порядок, и DROP SEQUENCE упрётся в зависимость от DEFAULT на колонке,
а до нуля этого не видно — к моменту проверки таблицы `part` уже нет.

ЧТО ДЕЛАЕТСЯ, по каждому changeset'у механически:

  1. накат до него, снимок «после N» (pg_dump --schema-only);
  2. откат ровно на один changeset;
  3. сверка снимка с тем, что был «после N−1», — не глазами, а объект в объект;
  4. накат обратно и сверка с «после N».

Расхождение — это либо неверный `--rollback`, либо changeset, который
откатывается не полностью (забытый индекс, ограничение, комментарий,
последовательность). И то и другое проходит проверку «до нуля».

ПОЧЕМУ SQL ПРОИГРЫВАЕТСЯ, А НЕ ВЫЗЫВАЕТСЯ `liquibase rollback-count 1`.
Замерено на этой же базе: один вызов Liquibase — 2,7 с, из них почти всё
старт JVM. Пять шагов на changeset × 138 changeset'ов арендатора — это
275 вызовов и 12–18 минут, то есть проверка, которую не гоняют локально
и которая упирается в предел задачи CI. Поэтому Liquibase зовётся дважды:
`update-sql` и `rollback-count-sql` отдают ровно тот SQL, который он бы
и выполнил, вместе с разметкой по changeset'ам, — а проигрывает его psql
по шагам, 0,35 с на шаг. Весь прогон укладывается в 2–3 минуты.

Цена названа честно: здесь проверяется правильность самих откатов, а не то,
что Liquibase умеет их выполнять. Второе проверяет `db/verify.sh`, шаг 7 —
настоящий `rollback-count` на всю цепочку, — и эти две проверки дополняют
друг друга, а не заменяют.

ПЕРВЫЙ CHANGESET НЕ ОТКАТЫВАЕТСЯ. Его `--rollback` делает DROP SCHEMA CASCADE
и сносит вместе со схемой сам DATABASECHANGELOG. Это уже учтено в verify.sh,
учтено и здесь: цепочка идёт до второго changeset'а.

ПОРЯДОК ОТКАТА — ОБРАТНЫЙ ПОРЯДКУ НАКАТА, А НЕ ИМЁН ФАЙЛОВ. `009-views.sql`
стоит в манифесте последним намеренно (вьюхи читают колонки из 010), и
откатывается он первым. Поэтому шаги берутся из разметки самого Liquibase,
а не из имён файлов: сортировка по имени дала бы не ту цепочку.

И ГЛАВНОЕ, РАДИ ЧЕГО ЗДЕСЬ ЕСТЬ ГРАНИЦА (задача 0102). Двенадцать расхождений
этот перебор нашёл и назвать может, а починить — нет: changeset'ы выпущены
и неизменяемы. Пока откат вниз был путём, которым не ходят, их хватало списком;
с кнопкой раскатки (0075) это путь, которым идёт выкладка, и молчаливый
«успех» на схеме, где не вернулись двадцать один триггер и десять функций,
стал дороже отказа. Поэтому граница объявлена в
`db/changelog/rollback-floor.properties`, ниже неё инструменты отказывают,
а этот сторож стережёт само объявление: ВЫШЕ ГРАНИЦЫ У НЕГО НЕ ДОЛЖНО БЫТЬ
НИ ОДНОГО РАЗРЕШЁННОГО РАСХОЖДЕНИЯ. Граница обязана стоять ровно там, где
кончается доказанный откат, — не выше (это молча отняло бы у выкладки
возможность вернуться) и не ниже (это и есть тот самый молчаливый «успех»).
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

import rollback_floor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB = os.path.join(ROOT, "db")

TENANT_CHANGELOG = "changelog/db.changelog-tenant.xml"
CATALOG_CHANGELOG = "changelog/db.changelog-catalog.xml"
SCHEMA = "t_000042"

# Расхождения, которые разобраны и оставлены намеренно. Ключ — идентификатор
# changeset'а, чей откат уводит схему в сторону; значение — (что именно
# позволено разойтись, почему). «Что именно» сверяется по именам объектов:
# разрешение выдано названным объектам, а не changeset'у навсегда — иначе
# первая же настоящая дыра в том же changeset'е проехала бы молча.
#
# Пометка без причины не принимается: причина и есть то, что отличает разбор
# от отписки (то же правило, что у разрешённых пар в tools/test-schema-guard.py).
#
# Обе пометки означают расхождение НИЖЕ ОБЪЯВЛЕННОЙ ГРАНИЦЫ ОТКАТА и разнятся
# только причиной: РАЗОБРАНО — так задумано, ГРАНИЦА — откат схему не
# возвращает, и потому туда не пускают. Выше границы разрешений нет вовсе:
# там откат обязан сходиться объект в объект, и это проверяется каждым
# прогоном. Самая младшая версия из разрешений и есть граница — сверяется
# с объявлением в db/changelog/rollback-floor.properties (см. floor_problems).
РАЗОБРАНО = "разобрано"     # так и задумано, и вот почему
ГРАНИЦА = "граница"         # откат схему не возвращает — ниже не пускаем

ALLOWED = {
    # Мост отката (db/CLAUDE.md, «Чинится это мостом»). Вперёд не делает
    # ничего, а его --rollback возвращает функцию лицевого счёта и три
    # генерируемые колонки, которых ждут откаты 051, 045 и 012. Срабатывает
    # он на своей позиции (122): откат ДО 122 его не трогает и сходится
    # объект в объект, а откат до 121 уже получает обратно то, чего код
    # не ждёт. Поэтому граница отката стоит ровно здесь — это самая младшая
    # версия, до которой схема возвращается прежней (замерено, задача 0102).
    "tenant-054-rollback-bridge": (
        ("customer_balance_apply", "normalized", "part_oem_trgm", "part_oem_uk",
         "search_vector", "part_search_gin",
         "qty_available", "part_stock_available_ix"),
        РАЗОБРАНО,
        "мост отката tenant/054: его --rollback намеренно возвращает функцию "
        "лицевого счёта и генерируемые колонки, чтобы откаты 051, 045 и 012 "
        "нашли то, чего ждут. Цена названа в db/CLAUDE.md: при откате ровно "
        "на один шаг схема получает их обратно. Разъезд закрывается откатом "
        "051 — он и снимал эти объекты, а его собственный --rollback пуст."),
}

# Волна «логика переезжает в Java» (changeset'ы 046–050, docs/triggers-to-java.md).
# Все они сняли триггеры и функции, и у всех --rollback объявлен пустым
# (`SELECT 1`) — намеренно: правило «логики в базе нет» сильнее возврата,
# а вперёд эти changeset'ы больше не накатывают.
#
# Следствие, которого до 13 сентября 2026 не знал никто: откат ниже 050 даёт
# схему, на которой прежний код работать не может. Он рассчитывал, что остаток
# разносит триггер, `updated_at` ставит триггер, журнал изменений пишет триггер,
# а неизменяемость журналов держит база. После такого отката всё это молчит,
# и молчит тихо: движения записываются, а остаток не меняется.
#
# Почему это ГРАНИЦА, а не ПРОБЕЛ в очереди работы (задача 0102). Правкой самих
# changeset'ов расхождение не закрывается — они выпущены и неизменяемы
# (чек-сумма). Оставался второй мост отката рядом с tenant/054, и от него
# отказались с доводом, а не по лени: мост срабатывает на СВОЕЙ позиции
# в манифесте, а дописать его можно только в конец, — значит откат на один-два
# шага с вершины воскрешал бы `stock_movement_apply`, `audit_trigger`
# и ещё двадцать триггеров поверх работающего Java-кода, который делает ровно
# то же самое. Двойное списание остатка на пути, которым идёт каждая выкладка,
# дороже отказа на пути, которым не ходят. Довод целиком — в db/CLAUDE.md.
ВОЛНА = (
    "откат объявлен пустым (`--rollback SELECT 1`) намеренно: вперёд правило "
    "«логики в базе нет» сильнее возврата. Но схема после такого отката "
    "не та, какой была, а прежний код рассчитывал на снятое. Правкой "
    "выпущенного не чинится (чек-сумма), вторым мостом отката — не чинится "
    "тоже (он воскресил бы триггеры на каждом откате с вершины), поэтому "
    "лежит ниже объявленной границы отката.")

ALLOWED.update({
    "tenant-050-inline-public-code": (
        ("gen_public_code", "public_code"), ГРАНИЦА,
        "не возвращается функция `gen_public_code()`, а умолчание "
        "`part.public_code` и `donor.public_code` остаётся встроенным "
        "выражением. " + ВОЛНА),
    "tenant-049-drop-audit-triggers": (
        ("audit_trigger", "_audit"), ГРАНИЦА,
        "не возвращаются `audit_trigger()` и пять триггеров журнала "
        "изменений: после отката журнал не пишется вовсе. " + ВОЛНА),
    "tenant-049-drop-immutability-triggers": (
        ("_immutable", "stock_movement_no_update"), ГРАНИЦА,
        "не возвращаются `stock_movement_immutable()` и три триггера "
        "неизменяемости журналов. " + ВОЛНА),
    "tenant-049-drop-touch-triggers": (
        ("touch_updated_at", "_touch"), ГРАНИЦА,
        "не возвращаются `touch_updated_at()` и семь триггеров отметки "
        "времени. " + ВОЛНА),
    "tenant-048-drop-stock-apply": (
        ("stock_movement_apply",), ГРАНИЦА,
        "не возвращаются `stock_movement_apply()` и триггер разнесения "
        "остатка — самое дорогое место списка: движения пишутся, остаток "
        "стоит. " + ВОЛНА),
    "tenant-047-drop-reserve-functions": (
        ("reserve_stock", "release_stock"), ГРАНИЦА,
        "не возвращаются `reserve_stock` и `release_stock` — те самые, что "
        "проверяли и меняли остаток одной инструкцией. " + ВОЛНА),
    "tenant-047-drop-reserved-guard": (
        ("part_stock_check_reserved", "part_stock_reserved_guard"), ГРАНИЦА,
        "не возвращаются `part_stock_check_reserved()` и триггер-сторож "
        "резерва. " + ВОЛНА),
    "tenant-046-drop-feed-dirty-triggers": (
        ("feed_mark_dirty", "_feed_dirty"), ГРАНИЦА,
        "не возвращаются `feed_mark_dirty()` и пять триггеров отметки "
        "об изменении позиции. " + ВОЛНА),

    # Порода другая: здесь откат не забыл вернуть объект, а вернул половину.
    "tenant-046-rename-feed-dirty": (
        ("feed_dirty_pk", "part_change_pk", "feed_dirty_part_fk",
         "part_change_part_fk", "feed_dirty_pending_ix", "part_change_pending_ix"),
        ГРАНИЦА,
        "откат переименовывает таблицу `part_change` обратно в `feed_dirty`, "
        "а три имени, переименованных тем же changeset'ом, — первичный ключ, "
        "внешний ключ и индекс — остаются `part_change_*`. Схема после отката "
        "работает, но следующий changeset, который сошлётся на `feed_dirty_pk` "
        "по имени, встанет на ней, а на свежей — нет. Правило общее: "
        "переименовав объект, перечислите в откате ВСЕ имена, которые тронули. "
        "Лежит ниже объявленной границы отката (0102)."),

    # И третья порода: сужение, которое откат не вернул.
    "tenant-220-donor-brand-nullable": (
        ("brand_id",), ГРАНИЦА,
        "откат снимает два внешних ключа, но не возвращает `donor.brand_id "
        "NOT NULL`. Безусловно вернуть его и нельзя — NULL'ы в колонке "
        "появляются тем же changeset'ом (`brand_id = 0` переведён в NULL), "
        "и `ADD CONSTRAINT` ответил бы «is violated by some row», — а вернуть "
        "с переводом строк обратно в 0 значит вернуть ссылку на марку, "
        "которой нет. То есть решение не механическое — и это второй довод "
        "за границу: механикой такое не закрывается вовсе. Это ровно тот "
        "класс, что описан в db/CLAUDE.md про сужение CHECK: спрашивать надо "
        "не «встанет ли откат на чистой базе»."),
    "tenant-108-return-document": (
        ("part_id", "quantity"), ГРАНИЦА,
        "откат снимает добавленные колонки документа возврата, но не "
        "возвращает `deal_return.part_id NOT NULL` и `quantity NOT NULL`. "
        "Та же природа, что у `donor.brand_id`: строки без позиции "
        "и количества создаёт сам новый документ, и безусловный возврат "
        "ограничения на них упрётся. Лежит ниже объявленной границы отката."),
})


def floor_problems(allowed=None, sets=None):
    """Сторожит само объявление границы (задача 0102).

    Три утверждения, и каждое ломается по-своему:

      1. объявление разбирается и сходится с changelog'ом (это делает
         `rollback_floor.resolve`);
      2. ВЫШЕ границы разрешённых расхождений нет ни одного — иначе граница
         обещает больше, чем доказано, и кнопка раскатки уведёт схему туда,
         где откат молчаливо не сработал;
      3. граница стоит не выше, чем нужно: самая младшая версия из разрешений
         и есть она. Завышенная граница молча отнимает у выкладки возможность
         вернуться на прежнюю сборку, и заметить это нечем — отказ выглядит
         одинаково законным на любой высоте.

    Отсюда и способ добавить новое разрешение: либо починить откат новым
    changeset'ом, либо поднять границу — и тогда объявление придётся править
    руками, назвав, что именно выкладка потеряла. Молча это не делается.
    """
    allowed = ALLOWED if allowed is None else allowed
    sets = sets if sets is not None else rollback_floor.changesets()
    problems = []
    try:
        number, cid = rollback_floor.resolve(sets=sets)
    except rollback_floor.Разъехалось as e:
        return [str(e)], None

    number_of = {c[2]: c[0] for c in sets}
    above, unknown, top = [], [], 0
    for key in allowed:
        if key not in number_of:
            unknown.append(key)
            continue
        at = number_of[key]
        top = max(top, at)
        if at > number:
            above.append(f"{key} (версия {at})")

    if unknown:
        problems.append(
            "в ALLOWED записаны changeset'ы, которых нет в наборе арендатора: "
            + ", ".join(sorted(unknown))
            + ". Разрешение, выданное несуществующему changeset'у, не молчит "
              "ни о чём — оно просто не работает")
    if above:
        problems.append(
            "разрешённое расхождение ВЫШЕ границы отката "
            f"{number}/{cid}: " + ", ".join(sorted(above)) + ".\n"
            "      Граница обещает, что до неё откат возвращает схему, —"
            " а здесь сказано, что не возвращает.\n"
            "      Либо чините откат новым changeset'ом, либо поднимайте "
            "границу в db/changelog/rollback-floor.properties,\n"
            "      и второе — решение: каждый шаг вверх отнимает у выкладки "
            "возможность вернуться на ту сборку.")
    if top and top < number and not above:
        problems.append(
            f"граница отката объявлена версией {number}, а самое позднее "
            f"расхождение стоит на {top}.\n"
            f"      Значит откат сходится и ниже объявленного, а граница "
            f"запрещает то, что работает: опустите её до {top}.")
    return problems, number


# ─────────────────────────── база под прогон ────────────────────────────

def project_name(suffix=""):
    """Имя проекта compose — своё у каждой рабочей копии.

    Общее имя делает проверку общим ресурсом: два прогона из разных копий
    берут одни и те же контейнеры, и `down -v` второго сносит базу под
    первым посреди наката (db/CLAUDE.md, задача 0094). Детерминировано:
    повторный прогон из той же копии подбирает свои же контейнеры.
    """
    slug = re.sub(r"[^a-z0-9]", "-", os.path.basename(ROOT).lower())[:20]
    tail = hashlib.md5(ROOT.encode()).hexdigest()[:8]
    return f"rollback-{slug}-{tail}{suffix}"


class Cell:
    """Поднятая база и два контейнера рядом: postgres и liquibase."""

    def __init__(self, project, quiet=False):
        self.project = project
        self.quiet = quiet
        self.pg = None
        self.lb = None

    def compose(self, *args, **kw):
        return run(["docker", "compose", "-p", self.project] + list(args),
                   cwd=DB, **kw)

    def up(self):
        self.compose("down", "-v", check=False, quiet=True)
        self.compose("up", "-d", quiet=self.quiet)
        self.pg = self.compose("ps", "-q", "postgres", capture=True).strip()
        self.lb = self.compose("ps", "-q", "liquibase", capture=True).strip()
        for _ in range(60):
            r = run(["docker", "exec", "-i", self.pg, "pg_isready", "-U", "app",
                     "-d", "parts"], check=False, capture=True, quiet=True)
            if r is not None and "accepting" in r:
                return
            time.sleep(1)
        raise SystemExit("postgres не поднялся")

    def down(self):
        self.compose("down", "-v", check=False, quiet=True)

    def psql(self, sql=None, file=None, capture=False):
        cmd = ["docker", "exec", "-i", self.pg, "psql", "-U", "app", "-d", "parts",
               "-v", "ON_ERROR_STOP=1", "-q"]
        if sql is not None:
            cmd += ["-c", sql]
            return run(cmd, capture=capture, quiet=True)
        with open(file, "rb") as fh:
            return run(cmd + ["-f", "-"], stdin=fh, capture=capture, quiet=True)

    def liquibase(self, changelog, schema, command, *extra, capture=False):
        """Вызов Liquibase в его контейнере.

        Порядок аргументов не свободен: глобальные идут ДО имени команды,
        а `-D` и `--count` — после. Liquibase 4.x на перепутанном молча
        не спотыкается, он отвечает отказом разбора.
        """
        cmd = ["docker", "exec", "-i", "-w", "/liquibase/workspace", self.lb,
               "liquibase", "--log-level=severe",
               f"--changelog-file={changelog}",
               "--username=app", "--password=app",
               "--url=jdbc:postgresql://postgres:5432/parts"]
        cmd += [f"--default-schema-name={schema}",
                f"--liquibase-schema-name={schema}"] if schema else \
               ["--liquibase-schema-name=public"]
        cmd += [command] + list(extra)
        if schema:
            cmd += [f"-Dtenant.schema={schema}"]
        return run(cmd, capture=capture, quiet=True)

    def dump(self, schema):
        return run(["docker", "exec", "-i", self.pg, "pg_dump", "-U", "app",
                    "-d", "parts", "-n", schema, "--schema-only",
                    "--no-owner", "--no-acl"], capture=True, quiet=True)


def run(cmd, cwd=None, check=True, capture=False, quiet=False, stdin=None):
    out = subprocess.PIPE if (capture or quiet) else None
    r = subprocess.run(cmd, cwd=cwd, stdout=out, stderr=out, stdin=stdin,
                       text=True)
    if check and r.returncode != 0:
        text = ""
        if r.stdout:
            text += r.stdout if isinstance(r.stdout, str) else r.stdout.decode()
        if r.stderr:
            text += r.stderr if isinstance(r.stderr, str) else r.stderr.decode()
        raise Failed(" ".join(cmd[:6]) + " …\n" + text[-4000:])
    if capture:
        return r.stdout if isinstance(r.stdout, str) else (r.stdout or b"").decode()
    return None


class Failed(Exception):
    pass


# ───────────────────────── разметка SQL Liquibase ─────────────────────────

FORWARD = re.compile(r"^-- Changeset (\S+?)::(\S+?)::(\S+)\s*$")
BACKWARD = re.compile(r"^-- Rolling Back ChangeSet: (\S+?)::(\S+?)::(\S+)\s*$")


def split_by_changeset(sql, marker):
    """Режет вывод *-sql на куски по одному changeset'у, сохраняя порядок.

    Первый кусок — преамбула Liquibase (таблицы журнала и захват замка);
    он идёт до первого маркера и к changeset'ам отношения не имеет.
    """
    head, chunks = [], []
    current = None
    for line in sql.splitlines():
        m = marker.match(line)
        if m:
            current = {"file": m.group(1), "id": m.group(2), "author": m.group(3),
                       "sql": []}
            chunks.append(current)
            continue
        (current["sql"] if current else head).append(line)
    for c in chunks:
        c["sql"] = "\n".join(c["sql"]).strip() + "\n"
    return "\n".join(head), chunks


# ──────────────────────────── снимок схемы ────────────────────────────

HEADER = re.compile(r"^-- Name: (.+); Type: (.+); Schema: (.+); Owner:")


def snapshot(dump_text, schema):
    """Дамп → {объект: его определение}.

    Сверять построчно нельзя: pg_dump раскладывает объекты в порядке OID,
    а после отката и повторного наката OID'ы другие — тот же самый набор
    объектов дал бы разницу в каждой строке. Поэтому дамп разбирается
    на объекты по его же заголовкам «-- Name: … ; Type: …», и сверяются
    объект с объектом. Заодно это и есть ответ на вопрос «что именно
    осталось в схеме»: имя и тип, а не номер строки.

    Имя схемы вычищается: снимки снимаются с одной схемы, но самопроверка
    гоняет свою — сравнивать надо форму, а не адрес.
    """
    objects, key, body = {}, None, []

    def flush():
        if key:
            text = "\n".join(body).strip()
            text = text.replace(schema + ".", "«схема».")
            # Запятая в конце строки говорит только о том, что колонка
            # не последняя. Вернувшаяся на своё место колонка сдвигает эту
            # запятую у соседней — и без нормализации сосед, к которому
            # никто не притрагивался, числился бы изменённым.
            text = "\n".join(row.rstrip().rstrip(",") for row in text.splitlines())
            if key.startswith("FUNCTION:"):
                # Тело функции pg_dump печатает так, как его записал автор,
                # вместе с переносами строк. Перенос строки — не схема:
                # та же функция, записанная мостом отката в одну строку,
                # отличалась бы от исходной семью строками подряд, ничего
                # на самом деле не меняя. Пробелы схлопываются, разница
                # по существу остаётся.
                text = " ".join(text.split())
            objects[key] = text

    for line in dump_text.splitlines():
        if line.startswith("\\restrict") or line.startswith("\\unrestrict"):
            continue                       # случайный ключ, свой у каждого дампа
        m = HEADER.match(line)
        if m:
            flush()
            name, kind = m.group(1), m.group(2)
            key = f"{kind}: {name}"
            if key in objects:             # два объекта с одним именем и типом
                key += " (2)"
            body = []
            continue
        if line.startswith("--"):
            continue
        if line.startswith("SET ") or line.startswith("SELECT pg_catalog.set_config"):
            continue
        body.append(line)
    flush()
    # Служебные таблицы Liquibase к схеме арендатора не относятся: их форма
    # одна и та же на любом шаге, а строки внутри сверять нечем — дамп
    # схемный.
    return {k: v for k, v in objects.items()
            if "databasechangelog" not in k.lower()}


def compare(expected, actual):
    """Что разошлось: (лишнее, пропавшее, изменившееся)."""
    extra = sorted(set(actual) - set(expected))
    missing = sorted(set(expected) - set(actual))
    changed = sorted(k for k in set(expected) & set(actual)
                     if expected[k] != actual[k])
    return extra, missing, changed


def allowed_for(changeset_id, detail):
    """Разобранное расхождение: названо всё, что разошлось, и причина непуста.

    `detail` — {объект: строки, которыми он разошёлся}. Сверяется каждая
    строка, а не имя объекта целиком: разрешение «таблица part может
    отличаться» накрыло бы любую правку этой таблицы, а разрешение
    «строкой про search_vector» — только ту, которая разобрана.
    """
    entry = ALLOWED.get(changeset_id)
    if not entry:
        return None
    markers, kind, reason = entry
    if not markers or not reason or not reason.strip():
        return None                        # пометка без причины не принимается
    for name, (_, rows) in detail.items():
        for row in rows:
            if not any(m in name or m in row for m in markers):
                return None
    return (kind, reason)


# ──────────────────────────────── прогон ────────────────────────────────

def walk(cell, changelog, schema, limit=None, report=print, known=None):
    """Накат по шагам, откат по шагам, накат обратно. Возвращает список бед."""
    problems = []
    known = known if known is not None else []
    tmp = tempfile.mkdtemp(prefix="rollback-sql-")
    try:
        cell.psql(sql=f"DROP SCHEMA IF EXISTS {schema} CASCADE; CREATE SCHEMA {schema};")

        forward_sql = cell.liquibase(changelog, schema, "update-sql", capture=True)
        preamble, forward = split_by_changeset(forward_sql, FORWARD)
        if not forward:
            raise Failed("Liquibase не отдал ни одного changeset'а — "
                         "проверять нечего")
        if limit:
            forward = forward[:limit]

        # ── вверх: снимок после каждого changeset'а
        write_and_play(cell, tmp, "preamble", preamble, schema)
        snaps = [snapshot(cell.dump(schema), schema)]      # «до первого»
        for i, c in enumerate(forward):
            write_and_play(cell, tmp, f"up-{i}", c["sql"], schema)
            snaps.append(snapshot(cell.dump(schema), schema))
        report(f"    накат: {len(forward)} changeset'ов, "
               f"объектов в схеме {len(snaps[-1])}")

        # ── вниз: откат ровно на один и сверка с «как было»
        #
        # Первый changeset не откатываем: его rollback делает DROP SCHEMA
        # CASCADE и сносит сам DATABASECHANGELOG.
        count = len(forward) - 1
        back_sql = cell.liquibase(changelog, schema, "rollback-count-sql",
                                  f"--count={count}", capture=True)
        head, backward = split_by_changeset(back_sql, BACKWARD)
        if len(backward) != count:
            problems.append(
                f"Liquibase собрался откатывать {len(backward)} changeset'ов "
                f"вместо {count}: цепочка отката не та, что цепочка наката")
            return problems
        write_and_play(cell, tmp, "back-preamble", head, schema)

        # Разъехавшись один раз, схема остаётся разъехавшейся до конца цепочки:
        # объект, не вернувшийся на шаге 050, не вернётся и на всех, что ниже.
        # Поэтому сообщается только НОВОЕ расхождение — то, которого не было
        # шагом раньше. Иначе один недочёт даёт сто двадцать сообщений, и найти
        # среди них второй недочёт нельзя (docs/defect-classes.md: список,
        # в котором всё красное, читается как «сломано вообще всё»).
        down_drift = {}
        up_drift = {}

        for n, c in enumerate(backward):
            i = len(forward) - 1 - n       # индекс откатываемого changeset'а
            try:
                write_and_play(cell, tmp, f"down-{i}", c["sql"], schema)
            except Failed as e:
                problems.append(
                    f"{c['file']}::{c['id']}: откат не выполнился вовсе — "
                    f"{short(e)}")
                return problems
            got = snapshot(cell.dump(schema), schema)
            problems += diff_report(c, got, snaps[i], "после отката",
                                    "как было до наката", drift=down_drift,
                                    known=known)

        # ── обратно вверх: та же схема, что была после N
        for i, c in enumerate(forward):
            if i == 0:
                continue                   # первый и не откатывался
            try:
                write_and_play(cell, tmp, f"re-{i}", c["sql"], schema)
            except Failed as e:
                problems.append(
                    f"{c['file']}::{c['id']}: повторный накат после отката "
                    f"не прошёл — {short(e)}")
                return problems
            got = snapshot(cell.dump(schema), schema)
            problems += diff_report(c, got, snaps[i + 1], "после повторного наката",
                                    "как было после наката", drift=up_drift,
                                    known=known, reapply=True)
        return problems
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def diff_report(chunk, got, want, got_name, want_name, drift, known,
                reapply=False):
    """Сообщает только то, что разошлось ИМЕННО НА ЭТОМ шаге.

    `drift` — расхождение, с которым шаг начался: оно уже названо выше
    по цепочке, и повторять его на каждом следующем шаге значит утопить
    в нём вторую находку.
    """
    extra, missing, changed = compare(want, got)

    # Расхождение помнится построчно, а не объектом целиком: у таблицы, которая
    # разошлась одной колонкой, тело меняется на каждом следующем шаге вниз —
    # и объект целиком считался бы новой находкой два десятка раз подряд.
    state = {}
    for name in extra:
        state[(name, "")] = "осталось лишним"
    for name in missing:
        state[(name, "")] = "не вернулось"
    for name in changed:
        for row in line_diff(want[name], got[name]):
            state[(name, row)] = "стало другим"

    fresh = {k: v for k, v in state.items() if k not in drift}
    drift.clear()
    drift.update(state)
    if not fresh:
        return []

    detail = {}
    for (name, row), kind in fresh.items():
        detail.setdefault(name, (kind, []))[1].append(row or name)

    if not reapply:
        entry = allowed_for(chunk["id"], detail)
        if entry:
            known.append((chunk["file"], chunk["id"]) + entry)
            return []

    lines = [f"{chunk['file']}::{chunk['id']}: {got_name} схема не сходится "
             f"с тем, {want_name}"]
    for name in sorted(detail):
        kind, rows = detail[name]
        lines.append(f"      {kind}: {name}")
        if kind == "стало другим":
            for row in sorted(rows)[:8]:
                lines.append(f"          {row}")
    return ["\n".join(lines)]


def line_diff(want, got):
    """Строки, которыми определения объекта разошлись, — с какой стороны."""
    a, b = want.splitlines(), got.splitlines()
    only_want = [x for x in a if x not in b]
    only_got = [x for x in b if x not in a]
    return ([f"было: {x.strip()}" for x in only_want]
            + [f"стало: {x.strip()}" for x in only_got])


def short(err):
    text = str(err).strip().splitlines()
    return " / ".join(t.strip() for t in text[-3:] if t.strip())[:300]


def write_and_play(cell, tmp, name, sql, schema=None):
    """Проигрывает кусок SQL одним сеансом psql.

    `search_path` выставляется перед каждым куском, и это не украшение:
    Liquibase держит его на весь свой сеанс (`--default-schema-name`),
    а здесь каждый шаг — свой сеанс psql. Без этого откат, написанный
    без имени схемы, не находит своей же таблицы: `tenant/056` откатывается
    как `UPDATE deal_item …`, и вне сеанса Liquibase это «relation does
    not exist» — отказ проверки на исправном changeset'е.
    """
    if not sql.strip():
        return
    path = os.path.join(tmp, name + ".sql")
    with open(path, "w", encoding="utf-8") as fh:
        if schema:
            fh.write(f'SET SEARCH_PATH TO {schema}, "$user","public";\n')
        fh.write(sql)
    cell.psql(file=path)


# ────────────────────────────── самопроверка ──────────────────────────────

SELFTEST_CHANGELOG = """<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
            http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.27.xsd">
    <property name="tenant.schema" value="t_000901" global="false"/>
    <include file="001-base.sql"  relativeToChangelogFile="true"/>
    <include file="002-index.sql" relativeToChangelogFile="true"/>
    <include file="003-note.sql"  relativeToChangelogFile="true"/>
</databaseChangeLog>
"""

SELFTEST_FILES = {
    "001-base.sql": """--liquibase formatted sql
--changeset selftest:001-base
CREATE TABLE ${tenant.schema}.thing (id bigserial PRIMARY KEY, code text NOT NULL);
--rollback DROP SCHEMA ${tenant.schema} CASCADE;
""",
    # Тот самый вид отката, ради которого сторож и заведён: две инструкции,
    # и потерять из них можно любую.
    #
    # IF NOT EXISTS в прямом ходе — не украшение, и на нём держится вся
    # самопроверка. Без него забытое снятие индекса ловится НЕ сверкой
    # дампов, а отказом повторного наката («relation already exists»):
    # подделка краснеет, а доказано этим совсем другое — что psql умеет
    # ругаться на дубль. Проверено: со снятой сверкой самопроверка
    # на прежней подделке проходила зелёной.
    "002-index.sql": """--liquibase formatted sql
--changeset selftest:002-index
CREATE UNIQUE INDEX IF NOT EXISTS thing_code_uk ON ${tenant.schema}.thing (code);
ALTER TABLE ${tenant.schema}.thing ADD COLUMN IF NOT EXISTS note text;
--rollback DROP INDEX IF EXISTS ${tenant.schema}.thing_code_uk;
--rollback ALTER TABLE ${tenant.schema}.thing DROP COLUMN IF EXISTS note;
""",
    "003-note.sql": """--liquibase formatted sql
--changeset selftest:003-note
COMMENT ON TABLE ${tenant.schema}.thing IS 'вещь';
--rollback COMMENT ON TABLE ${tenant.schema}.thing IS NULL;
""",
}

# Порча, которую сторож обязан увидеть: убрано снятие индекса. Откат при этом
# проходит без единой ошибки, повторный накат — тоже, а до нуля схема сносится
# целиком. То есть ни «откат до пустоты», ни отказ команды этого не видят:
# увидеть может только сверка снимков.
BROKEN_002 = SELFTEST_FILES["002-index.sql"].replace(
    "--rollback DROP INDEX IF EXISTS ${tenant.schema}.thing_code_uk;\n", "")


def selftest():
    """Проверка самого сторожа — настоящей базой, а не рассуждением.

    Проверка, которая не краснеет на дефекте, хуже отсутствующей, и установить
    это можно только попыткой. Поэтому здесь заводится свой маленький changelog
    из трёх changeset'ов, и он гоняется дважды: целым (обязан молчать)
    и с испорченным откатом (обязан покраснеть и назвать оставшийся индекс).
    """
    failures = []
    cell = Cell(project_name("-selftest"), quiet=True)
    print("  поднимаем базу под самопроверку…")
    cell.up()
    # Рабочий каталог Liquibase — это сам `db/`, смонтированный в контейнер.
    # Значит подделку видно и с хоста; каталог свой на процесс и снимается
    # в finally, иначе первый же прерванный прогон оставил бы её в репозитории.
    work = os.path.join(DB, f".selftest-{os.getpid()}")
    try:
        def build(files):
            shutil.rmtree(work, ignore_errors=True)
            os.makedirs(work)
            with open(os.path.join(work, "selftest.xml"), "w",
                      encoding="utf-8") as fh:
                fh.write(SELFTEST_CHANGELOG)
            for name, body in files.items():
                with open(os.path.join(work, name), "w",
                          encoding="utf-8") as fh:
                    fh.write(body)

        changelog = f"{os.path.basename(work)}/selftest.xml"
        quiet = lambda *a, **k: None

        # 1. Целый набор: сторож обязан молчать. Иначе он не сторож, а помеха —
        #    краснеющий на исправном коде отключают в первый же день.
        build(SELFTEST_FILES)
        problems = walk(cell, changelog, "t_000901", report=quiet)
        if problems:
            failures.append("исправный набор объявлен сломанным: "
                            + " | ".join(problems)[:600])

        # 2. Испорченный откат: покраснеть и назвать, что именно осталось.
        broken = dict(SELFTEST_FILES, **{"002-index.sql": BROKEN_002})
        build(broken)
        problems = walk(cell, changelog, "t_000901", report=quiet)
        text = "\n".join(problems)
        if not problems:
            failures.append(
                "откат, забывший снять индекс, прошёл молча — это ровно тот "
                "дефект, ради которого сторож заведён, и «откат до нуля» "
                "не видит его по своему устройству")
        else:
            if "thing_code_uk" not in text:
                failures.append(
                    "расхождение названо, а что именно осталось в схеме — нет: "
                    "искать руками придётся то, что сторож уже знает.\n"
                    + text[:600])
            if "002-index" not in text:
                failures.append("не назван changeset, чей откат неверен: "
                                "искать его придётся перебором.\n" + text[:600])
            # Краснеть обязана СВЕРКА СНИМКОВ, а не отказ команды: дефект,
            # пойманный «relation already exists», доказывает, что psql умеет
            # ругаться на дубль, и ничего не говорит о самой проверке.
            if "схема не сходится" not in text:
                failures.append(
                    "подделку поймала не сверка снимков, а отказ SQL — то есть "
                    "сама сверка не проверена вовсе.\n" + text[:600])

        # 3. Пометка без причины не принимается: иначе список разрешённых
        #    расхождений станет способом отключить сторожа, а не разбором.
        saved = dict(ALLOWED)
        try:
            ALLOWED["002-index"] = (("thing_code_uk",), РАЗОБРАНО, "   ")
            one = {"INDEX: thing_code_uk": ("не вернулось", ["INDEX: thing_code_uk"])}
            two = dict(one, **{"TABLE: other": ("не вернулось", ["TABLE: other"])})
            if allowed_for("002-index", one):
                failures.append("пометка без причины принята")
            ALLOWED["002-index"] = (("thing_code_uk",), РАЗОБРАНО, "причина")
            if not allowed_for("002-index", one):
                failures.append("пометка с причиной не принята — разобранное "
                                "расхождение будет красить прогон вечно")
            if allowed_for("002-index", two):
                failures.append("разрешение, выданное названному объекту, "
                                "накрыло соседний: тогда первая же настоящая "
                                "дыра в том же changeset'е проедет молча")
        finally:
            ALLOWED.clear()
            ALLOWED.update(saved)

        # 4. Сторож самой границы (задача 0102). Проверяется он на выдуманном
        #    наборе, потому что настоящий обязан быть зелёным: подделывать
        #    в нём нечего, а спросить надо именно «покраснеет ли».
        сеты = [(1, "t/1.sql", "a"), (2, "t/2.sql", "b"), (3, "t/3.sql", "c")]
        saved_declared = rollback_floor.declared
        try:
            def объявить(версия):
                rollback_floor.declared = lambda group="tenant": версия

            объявить("2/b")
            problems, floor = floor_problems({"a": ()}, сеты)
            if floor != 2:
                failures.append("граница не разобралась на исправном "
                                "объявлении")
            if not problems:
                failures.append(
                    "граница объявлена версией 2, а откат не возвращает схему "
                    "только на 1 — сторож принял границу выше доказанной, "
                    "то есть молча отнял у выкладки один шаг возврата")

            problems, _ = floor_problems({"b": ()}, сеты)
            if problems:
                failures.append("ровная граница объявлена нарушением: "
                                + " | ".join(problems)[:300])

            problems, _ = floor_problems({"b": (), "c": ()}, сеты)
            if not problems:
                failures.append(
                    "разрешённое расхождение ВЫШЕ границы принято — это тот "
                    "самый молчаливый успех, ради которого граница и заведена")
            elif "ВЫШЕ границы" not in " ".join(problems):
                failures.append("отказ не говорит, что расхождение выше "
                                "границы: искать придётся глазами")

            объявить("2/c")
            problems, _ = floor_problems({"b": ()}, сеты)
            if not problems:
                failures.append(
                    "объявление «2/c» принято, хотя второй changeset набора — "
                    "«b»: пара разъехалась с changelog'ом, и обе половины "
                    "читались бы как разные места")

            объявить("99/x")
            problems, _ = floor_problems({"b": ()}, сеты)
            if not problems:
                failures.append("граница за пределами набора принята")
        finally:
            rollback_floor.declared = saved_declared

        return failures
    finally:
        shutil.rmtree(work, ignore_errors=True)
        cell.down()


# ───────────────────────────────── main ─────────────────────────────────

def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--selftest", action="store_true",
                    help="проверить самого сторожа")
    ap.add_argument("--steps", type=int, default=None,
                    help="ограничить число changeset'ов (для разбора)")
    ap.add_argument("--keep", action="store_true",
                    help="не гасить базу после прогона")
    args = ap.parse_args()

    started = time.time()
    if args.selftest:
        print("Самопроверка сторожа отката")
        broken = selftest()
        if broken:
            print("\nСамопроверка не прошла:\n")
            for b in broken:
                print("  •", b)
            print("\nСторож, который не краснеет на дефекте, хуже отсутствующего.")
            return 1
        print(f"Самопроверка пройдена ({int(time.time() - started)} с): "
              f"исправный набор молчит, забытое снятие индекса названо "
              f"по имени,\nпометка без причины не принимается, а граница "
              f"отката не принимается ни завышенной,\nни заниженной, "
              f"ни разъехавшейся с changelog'ом.")
        return 0

    # Объявление границы сверяется ДО подъёма базы: оно про текст, Docker ему
    # не нужен, а разъехавшаяся граница обесценивает весь прогон ниже.
    broken, floor = floor_problems()
    if broken:
        print("Объявление границы отката не сходится:\n")
        for b in broken:
            print("  •", b)
        print("\nГраница живёт в db/changelog/rollback-floor.properties, "
              "довод — в db/CLAUDE.md.")
        return 1
    print(f"==> Граница отката: версия {floor} "
          f"(ниже неё инструменты отказывают — задача 0102)")

    cell = Cell(project_name())
    known = []
    print(f"==> Поднимаем чистую базу ({cell.project})")
    cell.up()
    try:
        print("==> Общая схема catalog")
        cell.liquibase(CATALOG_CHANGELOG, None, "update", capture=True)
        print(f"==> Откат по шагам, схема {SCHEMA}")
        problems = walk(cell, TENANT_CHANGELOG, SCHEMA, limit=args.steps,
                        known=known)
    except Failed as e:
        print(f"\nПрогон оборвался: {e}")
        return 1
    finally:
        if not args.keep:
            cell.down()

    took = int(time.time() - started)
    if problems:
        print(f"\nОткат по шагам: не сходится ({took} с)\n")
        for p in problems:
            print("  •", p)
        print("\nЭто либо неверный --rollback, либо changeset, который "
              "откатывается\nне полностью. Правила — в db/CLAUDE.md; "
              "разобранное расхождение\nзаносится в ALLOWED этого файла "
              "с причиной.")
        return 1

    print(f"\nОткат по шагам сходится ({took} с): каждый changeset возвращает "
          f"схему\nровно к тому виду, какой она имела до него, и повторный "
          f"накат даёт\nто же, что первый.")
    report_known(known, floor)
    return 0


def report_known(known, floor=None):
    """Известные расхождения печатаются даже на зелёном прогоне.

    Расхождение, о котором молчат, через месяц читается как отсутствующее:
    так `tools/endpoint-coverage.py` печатает свои ПРОБЕЛы и на зелёном.
    Разница с ним в том, что здесь это не очередь работы, а объяснение
    границы: каждая запись — причина, по которой ниже не пускают.
    """
    if not known:
        return
    holes = [k for k in known if k[2] == ГРАНИЦА]
    print(f"\nИзвестных расхождений: {len(known)} — "
          f"разобрано {len(known) - len(holes)}, держат границу {len(holes)}.")
    for _, cid, kind, reason in known:
        if kind == ГРАНИЦА:
            first = reason.split(". ")[0].rstrip(".")
            print(f"  ГРАНИЦА {cid}: {first}.")
    if holes:
        print(f"\nВсе они лежат ниже версии {floor} — объявленной границы "
              f"отката.\nВыше неё откат сходится объект в объект, ниже "
              f"инструменты отказывают\nи называют границу: ./db/rollback-cost.py "
              f"и ops/schema-sync.sh --to.\nПочему граница, а не второй мост "
              f"отката, — db/CLAUDE.md.")


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Failed as e:
        print(f"Прогон оборвался: {e}")
        sys.exit(1)
