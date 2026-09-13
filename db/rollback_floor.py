#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Нижняя граница отката: одно объявление на все инструменты (задача 0102).

Граница живёт в `db/changelog/rollback-floor.properties` — рядом с самим
changelog'ом и внутри артефакта (pom кладёт `db/changelog` в jar). Значит
образ знает свою границу сам, а не получает её аргументом: у сборки версии 120
и у сборки версии 138 она может быть разной, и спрашивать надо у той, которая
откатывает.

Читают объявление трое, и намеренно врозь:

  • `db/verify-rollback.py` — проверяет, что выше границы у сторожа нет
    НИ ОДНОГО разрешённого расхождения: граница обязана стоять там, где
    кончается доказанный откат, а не там, где удобно;
  • `db/rollback-cost.py` — отказывается печатать цену отката ниже границы:
    у такого отката нет цены, он просто не делается;
  • `TenantSchemaMigrator` (Java) — разбирает ту же строку своим разбором
    и сверяет её с changelog'ом через Liquibase. Две независимые проверки
    одного объявления: разъехавшееся объявление обязано ловиться и без Docker,
    и без Python.

ПОЧЕМУ ПАРА «ЧИСЛО/ИДЕНТИФИКАТОР», А НЕ ЧИСЛО. Число сравнимо, идентификатор
читаем человеком — это тот же формат, что у отметки `schema_version` в реестре
и у `TenantSchemaMigrator.expectedVersion()`. И он самопроверяем: если пара
разъехалась с changelog'ом (кто-то вставил changeset в середину манифеста),
это видно сразу и обоим языкам, а голое число уехало бы молча.
"""
import os
import re
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHANGELOG = os.path.join(ROOT, "db", "changelog")
DECLARATION = os.path.join(CHANGELOG, "rollback-floor.properties")

MANIFESTS = {"tenant": "db.changelog-tenant.xml"}


class Разъехалось(Exception):
    """Объявление границы не сходится с changelog'ом."""


def includes(manifest):
    """Пути include'ов в порядке манифеста — он же порядок наката."""
    tree = ET.parse(os.path.join(CHANGELOG, manifest))
    out = []
    for el in tree.getroot().iter():
        if el.tag.endswith("}include") or el.tag == "include":
            out.append(el.get("file"))
    return out


def changesets(group="tenant"):
    """[(номер, путь, идентификатор)] в порядке наката, номер с единицы.

    Номер — это и есть версия схемы: «накатано N changeset'ов». Порядок берётся
    из манифеста, а не из имён файлов: `009-views.sql` включён последним
    намеренно, и по имени он оказался бы девятым.
    """
    out = []
    for path in includes(MANIFESTS[group]):
        text = open(os.path.join(CHANGELOG, path), encoding="utf-8").read()
        for cid in re.findall(r"^--changeset\s+\S+?:(\S+)", text, re.M):
            out.append((len(out) + 1, path, cid))
    return out


def declared(group="tenant"):
    """Сырая строка объявления, например «122/tenant-054-rollback-bridge»."""
    if not os.path.exists(DECLARATION):
        raise Разъехалось(
            f"нет файла {os.path.relpath(DECLARATION, ROOT)} — граница отката "
            f"не объявлена вовсе, а инструменты на неё опираются")
    for line in open(DECLARATION, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == group:
            return value.strip()
    raise Разъехалось(
        f"{os.path.relpath(DECLARATION, ROOT)}: нет строки «{group}=…» — "
        f"граница для этого набора не объявлена")


def resolve(group="tenant", sets=None):
    """(номер, идентификатор) — разобранное и сверенное с changelog'ом.

    Сверяется именно пара: число обязано указывать на changeset с этим
    идентификатором. Иначе объявление читалось бы двумя способами — «версия
    122» и «до моста отката», — и однажды это были бы разные места.
    """
    raw = declared(group)
    if "/" not in raw:
        raise Разъехалось(
            f"граница отката объявлена как «{raw}»: нужен формат "
            f"«число/идентификатор», как у отметки версии в реестре")
    number, cid = raw.split("/", 1)
    try:
        number = int(number.strip())
    except ValueError:
        raise Разъехалось(f"граница отката объявлена как «{raw}»: "
                          f"«{number}» — не число changeset'ов")
    sets = sets if sets is not None else changesets(group)
    if not 1 <= number <= len(sets):
        raise Разъехалось(
            f"граница отката объявлена версией {number}, а в наборе "
            f"{len(sets)} changeset'ов")
    at = sets[number - 1][2]
    if at != cid.strip():
        raise Разъехалось(
            f"граница отката объявлена как «{raw}», а {number}-й changeset "
            f"набора — «{at}». Либо число, либо идентификатор устарели: "
            f"порядок наката сдвинулся, и граница указывает не туда")
    return number, cid.strip()


def refusal(target, number, cid):
    """Отказ словами: почему нельзя и куда можно.

    Одно место на оба инструмента: два отказа, написанные врозь, объясняют
    одно и то же разными словами, и человек читает их как два разных запрета.
    """
    return (
        f"Откат до версии {target} не делается: ниже объявленной границы "
        f"{number}/{cid}.\n"
        f"Ниже неё откат схему НЕ ВОЗВРАЩАЕТ — одиннадцать выпущенных "
        f"changeset'ов не отменяют того,\nчто сделали (волна «логика в Java», "
        f"переименование ключей, два снятых NOT NULL), и накат\nна такую схему "
        f"проходит молча: код той сборки на ней работать не может.\n"
        f"Самая младшая версия, до которой откат возвращает схему объект "
        f"в объект, — {number}.\n"
        f"Чем это обосновано и что делать, если нужна версия ниже, — "
        f"db/changelog/rollback-floor.properties\nи db/CLAUDE.md, «Нижняя "
        f"граница отката объявлена». На ПРОМ откат вниз делается возвратом\n"
        f"парного слепка, а не миграцией.")
