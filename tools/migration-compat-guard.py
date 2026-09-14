#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сторож совместимости миграций: новый changeset не ломает предыдущую версию кода.

  ./tools/migration-compat-guard.py             сторож (самопроверка идёт первой)
  ./tools/migration-compat-guard.py --selftest  только самопроверка
  ./tools/migration-compat-guard.py --list      что сужала история до 0116

ЗАЧЕМ. Решение владельца от 14 сентября 2026 (tasks/0112, раздел «Решение
владельца о смысле отката»): откатывается только приложение, базу чинят
вперёд. База может быть новее приложения, но не старее: приложение 100
на базе 103 — нормальное состояние после отката. Это правило держится ровно
на одном — каждая миграция совместима с кодом ПРЕДЫДУЩЕЙ версии. До этого
файла оно было дисциплиной, записанной словами; здесь оно проверка.

ЧТО СЧИТАЕТСЯ ЛОМАЮЩИМ (задача 0116). Changeset, после которого код прежней
версии перестаёт работать:

  • DROP COLUMN, DROP TABLE, DROP VIEW — и остальные DROP объекта, на который
    код может смотреть: последовательность (`nextval('wheel_set_no_seq')`
    зовётся из Java напрямую), функция, тип, схема, триггер;
  • вьюха, пересозданная через DROP без какой-то из прежних колонок;
  • переименование таблицы, колонки, вьюхи, индекса, ограничения;
  • смена типа колонки (кроме расширения: длиннее varchar, varchar → text,
    int → bigint);
  • SET NOT NULL и новая колонка NOT NULL без умолчания, DROP DEFAULT
    у обязательной — старый код вставит строку без неё и получит отказ;
  • новое ограничение на существующую таблицу: CHECK (кроме расширения
    перечня `IN (…)` под тем же именем), внешний ключ, исключение;
  • новая уникальность на данных, которые старый код может писать дублями,
    и снятая уникальность, на которую опирается `ON CONFLICT`.

Внешний ключ и DROP DEFAULT в списке задачи не названы, и это расширение
осознанное: оба — ровно та порода «старый код запишет то, что теперь
запрещено». `tenant/023` — живой пример первого: импорт писал brand_id = 0,
а внешний ключ на марку такую запись отбивает.

ЧТО НЕ СЧИТАЕТСЯ. Всё, что трогает объект, которого прежний код не знает:
таблицу, колонку или вьюху, заведённые в том же changeset'е или в ещё
не выпущенных (нет в точке расхождения с origin/main). Отсюда `tenant/064`
молчит: колонка `number` новая, умолчание `nextval` поставлено до
`SET NOT NULL`, уникальность на ней не столкнётся со старой вставкой.

РАЗРЕШЁННОЕ СУЖЕНИЕ — ТОЛЬКО ПОМЕТКОЙ С ПРИЧИНОЙ. Удаление старого законно
на последнем шаге expand/contract. Changeset объявляет его строкой рядом
с инструкцией:

    --сужение part.side: код перестал читать и писать колонку в 1a2b3c4d,
    --сужение            ...

то есть `--сужение <объект>: <релиз> <почему это безопасно>`. Объект —
ровно тот, что назвал отказ сторожа. Релиз — SHA сборки, в которой код
перестал объект использовать; сторож требует, чтобы этот коммит уже был
в main ДО ветки, где объект удаляют, — иначе отказ от объекта и его удаление
уезжают одной выкладкой, и откат на прежний образ падает. Сколько релизов
должно пройти (N) и почему именно столько — db/CLAUDE.md; сторож проверяет
нижнюю границу, а не счёт выкладок на ПРОМ: журнал выкладок живёт на машине
ячейки, не в репозитории. Пометка без причины не принимается, пометка
на том, что не сужается, — тоже: иначе её начнут писать не думая.

ИСТОРИЯ. Changeset'ы неизменяемы, и сужения в выпущенных есть. У них свой
список `ИСТОРИЯ` с причиной «до 0116», и он заморожен потолком номеров
файлов (`ПОТОЛОК_ИСТОРИИ`): changeset новее потолка в список не попадает,
новый обязан нести пометку внутри.

ЧЕГО СТОРОЖ НЕ ВИДИТ — названо, а не умолчано.
  • Смысла. Колонку, которую прежний код не читал, удалить безопасно, но
    сторож этого не знает — для того пометка и существует. Сверка «читает
    ли старый код колонку» по исходникам возможна, это вторая ступень.
  • Расширения, которое старый код не переварит при ЧТЕНИИ: DROP NOT NULL,
    новое значение перечня, которое новый код запишет, а старый не знает.
    Запись старым кодом это не ломает, а читающий старый код может упасть
    на незнакомом значении — по форме этого не отличить.
  • DDL внутри DO-блоков и функций: тело в долларовых кавычках не разбирается.
    Сегодня таких changeset'ов вне отката нет, а логики в базе не заводят.
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHANGELOG = os.path.join(ROOT, "db", "changelog")
MANIFESTS = ("db.changelog-catalog.xml", "db.changelog-tenant.xml")

МЕТКА = "--сужение"

# Последний номер файла, выпущенный до сторожа, по каждой группе. Список
# истории принимает только changeset'ы из файлов не новее этого номера:
# так «новый в него не попадает» держится числом, а не памятью, и без git.
ПОТОЛОК_ИСТОРИИ = {"catalog": 21, "tenant": 66}

# Сужения, выпущенные до сторожа. Идентификатор changeset'а -> (объекты,
# причина). Объекты перечислены поимённо: запись разрешает ровно их, и если
# changeset вдруг сузит ещё что-то (правило сторожа поменялось), это всплывёт,
# а не спрячется под старой записью. Причина обязана начинаться с «до 0116».
#
# Заполнено перебором всех 174 changeset'ов обеих групп. Почти все записи —
# волна «логика переезжает в Java» (044–051, docs/triggers-to-java.md) и схема
# до запуска проекта (файл tenant/010, «данных на этот момент нет»). Сборки
# старше волны на нынешней схеме не работают, и поднимать их никто не станет:
# ниже 122-го changeset'а не опускается и сама схема (db/CLAUDE.md, граница
# отката). Список — не разрешение на такой откат, а честная опись того, что
# прошло до сторожа.
ИСТОРИЯ = {
    "catalog-008-tenant-login-code": (
        ("tenant_registry.code",),
        "до 0116: код входа компании стал обязательным; пустые заполнены тем же "
        "changeset'ом, провижининг с тех пор присваивает код сам"),
    "catalog-014-part-kind-name-unique": (
        ("part_kind_name_uq",),
        "до 0116: имя эталона вида детали уникально без учёта регистра "
        "и пробелов; синонимы сведены тем же changeset'ом"),
    "catalog-017-part-kind-search": (
        ("part_kind.search_vector", "join_text"),
        "до 0116: вектор поиска по видам деталей и его функция сняты — "
        "их не читал никто, полнотекстового поиска по видам в приложении нет"),
    "tenant-094-stock-document": (
        ("stock_movement_document_fk",),
        "до 0116: движение ссылается на складской документ; схема до запуска "
        "проекта, данных не было"),
    "tenant-098-part-side-drop": (
        ("part.side",),
        "до 0116: свободное текстовое side заменено тремя осями; проект ещё "
        "не был запущен, данных не было"),
    "tenant-099-part-quality-grade": (
        ("part_quality_grade_ck",),
        "до 0116: оценка состояния — четыре значения, которые маппятся "
        "на площадки; схема до запуска проекта"),
    "tenant-105-deal-fields": (
        ("deal_status_ck",),
        "до 0116: перечень статусов сделки сменён вместе с кодом "
        "(PAID, SHIPPED, COMPLETED ушли, READY и ISSUED пришли)"),
    "tenant-111-deal-item-warehouse-required": (
        ("deal_item_warehouse_ck",),
        "до 0116: позиция сделки обязана знать склад — резерв ставится "
        "на конкретный остаток"),
    "tenant-122-part-status-drop-reserved": (
        ("part_status_ck",),
        "до 0116: RESERVED убран из статусов товара — резерв следует "
        "из part_stock.qty_reserved, а не из карточки"),
    "tenant-220-donor-brand-nullable": (
        ("donor_brand_fk", "donor_model_fk"),
        "до 0116: марка и модель машины ссылаются на справочник; импорт писал "
        "brand_id = 0, тот же changeset перевёл такие строки в NULL"),
    "tenant-034-applicability-unique": (
        ("part_applicability_uq",),
        "до 0116: применимость не дублируется при повторе приёмки"),
    "tenant-045-drop-customer-balance-trigger": (
        ("customer_balance_apply_trg", "customer_balance_apply"),
        "до 0116: волна «логика в Java», пункт 3 — остаток лицевого счёта "
        "SalesService считал по журналу ещё до снятия триггера"),
    "tenant-045-drop-customer-balance-column": (
        ("customer.balance",),
        "до 0116: колонка остатка без триггера замерла бы на случайном "
        "значении; остаток выводится из журнала"),
    "tenant-045-drop-customer-reserved-amount": (
        ("customer.reserved_amount",),
        "до 0116: колонку не писал и не читал никто — ни триггер, ни код, "
        "ни вьюха"),
    "tenant-046-drop-feed-dirty-triggers": (
        ("part_feed_dirty", "part_stock_feed_dirty", "part_wheel_feed_dirty",
         "part_oem_feed_dirty", "part_photo_feed_dirty", "feed_mark_dirty"),
        "до 0116: волна «логика в Java», пункт 4 — отметку об изменении "
        "позиции ставит PartChangeLog"),
    "tenant-046-rename-feed-dirty": (
        ("feed_dirty", "feed_dirty_pk", "feed_dirty_part_fk", "feed_dirty_pending_ix"),
        "до 0116: очередь отметок переехала из publishing в inventory "
        "и сменила имя на part_change вместе с кодом"),
    "tenant-047-drop-reserved-guard": (
        ("part_stock_reserved_guard", "part_stock_check_reserved"),
        "до 0116: страж резерва дублировал CHECK part_stock_reserved_ck, "
        "стоящий в схеме с самого начала"),
    "tenant-047-drop-reserve-functions": (
        ("reserve_stock", "release_stock"),
        "до 0116: волна «логика в Java», пункт 2 — резерв делает "
        "StockReservationRepository одним UPDATE с условием в WHERE"),
    "tenant-048-drop-stock-apply": (
        ("stock_movement_apply_trg", "stock_movement_apply"),
        "до 0116: волна «логика в Java», пункт 1 — остаток и статус ведёт "
        "StockLedger"),
    "tenant-049-drop-touch-triggers": (
        ("customer_touch", "deal_touch", "donor_touch", "part_touch",
         "part_name_touch", "stock_document_touch", "supply_touch", "touch_updated_at"),
        "до 0116: момент последней правки ставит приложение (@PreUpdate)"),
    "tenant-049-drop-immutability-triggers": (
        ("stock_movement_no_update", "customer_account_entry_immutable",
         "document_event_immutable", "stock_movement_immutable"),
        "до 0116: неизменяемость журналов держат суженные репозитории "
        "и @Immutable, с 5 августа 2026 — и права рабочей роли"),
    "tenant-049-drop-audit-triggers": (
        ("part_audit", "deal_audit", "deal_item_audit", "payment_audit",
         "donor_cost_audit", "audit_trigger"),
        "до 0116: журнал изменений пишет AuditLogListener, формат снимка "
        "сохранён"),
    "tenant-050-inline-public-code": (
        ("gen_public_code",),
        "до 0116: публичный код генерирует приложение; функция была "
        "умолчанием колонки"),
    "tenant-051-search-vector-expression": (
        ("part.search_vector",),
        "до 0116: вектор поиска — выражение в запросе с GIN-индексом, "
        "а не колонка"),
    "tenant-051-qty-available-expression": (
        ("part_stock.qty_available",),
        "до 0116: свободный остаток считается выражением qty - qty_reserved "
        "там, где спрашивают"),
    "tenant-061-feed-off-and-deleted": (
        ("marketplace_account_uk", "marketplace_account_feed_file_uk"),
        "до 0116: уникальность названия и имени файла выгрузки сужена "
        "до живых выгрузок — удалённая освобождает их"),
}


# ───────────────────────────── разбор changeset'ов ─────────────────────────────

class Changeset:
    def __init__(self, path, cid, sql, marks=None):
        self.path = path            # «tenant/064-part-number.sql»
        self.id = cid               # «tenant-064-part-number»
        self.sql = sql              # тело без строки --changeset
        self.marks = marks or []    # [(номер строки, объект или None, текст)]

    @property
    def group(self):
        return self.path.split("/", 1)[0]

    @property
    def file_number(self):
        m = re.match(r"^(\d+)-", os.path.basename(self.path))
        return int(m.group(1)) if m else None

    @property
    def name(self):
        return f"{self.path}::{self.id}"


def includes(manifest, root=CHANGELOG):
    tree = ET.parse(os.path.join(root, manifest))
    return [el.get("file") for el in tree.getroot().iter()
            if el.tag.endswith("}include") or el.tag == "include"]


def parse_file(path, text):
    """Файл formatted SQL -> changeset'ы. Хвост строки --changeset (атрибуты
    вроде runOnChange) отрезается: иначе он приклеится к первой инструкции,
    и она перестанет узнаваться."""
    out = []
    parts = re.split(r"^--changeset[ \t]+(\S+)[^\n]*$", text, flags=re.M)
    for i in range(1, len(parts), 2):
        cid = parts[i].split(":", 1)[1] if ":" in parts[i] else parts[i]
        body = parts[i + 1]
        marks = []
        for n, line in enumerate(body.splitlines(), 1):
            bare = line.strip()
            if not bare.startswith(МЕТКА):
                continue
            rest = bare[len(МЕТКА):]
            if rest and not rest[0].isspace() and rest[0] != ":":
                continue                      # --сужениеXYZ — не наша строка
            m = re.match(r"^\s*([^\s:]+)\s*:\s*(.*)$", rest)
            if m:
                marks.append((n, m.group(1).strip().lower(), m.group(2).strip()))
            else:
                marks.append((n, None, rest.strip()))
        out.append(Changeset(path, cid, body, marks))
    return out


def read(root=CHANGELOG):
    """Все changeset'ы в порядке наката, каждой группы отдельно."""
    sets = []
    for manifest in MANIFESTS:
        for path in includes(manifest, root):
            text = open(os.path.join(root, path), encoding="utf-8").read()
            sets.extend(parse_file(path, text))
    return sets


# ─────────────────────────────── разбор SQL ───────────────────────────────

def statements(sql):
    """Тело changeset'а -> инструкции: без комментариев, в нижнем регистре вне
    строковых литералов, с одним пробелом вместо любых. Тело в долларовых
    кавычках заменяется на `$$`: DDL внутри него сторож не разбирает (см. шапку).

    Комментарий `--` уносит с собой и строки `--rollback`, и `--comment`:
    откат обязан содержать DROP, и судить его здесь нельзя."""
    text = re.sub(r"\$\{[^}]*\}\.", "", sql)          # ${tenant.schema}.part
    text = re.sub(r"\$\{([^}]*)\}", r"\1", text)
    out, cur, i, n = [], [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "-" and text.startswith("--", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            cur.append(" ")
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if text[j] == "'":
                    if j + 1 < n and text[j + 1] == "'":
                        j += 2
                        continue
                    break
                j += 1
            cur.append(text[i:j + 1])
            i = j + 1
            continue
        if c == '"':
            j = text.find('"', i + 1)
            j = n - 1 if j < 0 else j
            cur.append(text[i + 1:j].lower())
            i = j + 1
            continue
        if c == "$":
            m = re.match(r"\$([A-Za-z_][A-Za-z_0-9]*)?\$", text[i:])
            if m:
                j = text.find(m.group(0), i + len(m.group(0)))
                i = n if j < 0 else j + len(m.group(0))
                cur.append("$$")
                continue
        if c == ";":
            out.append("".join(cur))
            cur = []
            i += 1
            continue
        cur.append(c.lower())
        i += 1
    out.append("".join(cur))
    result = []
    for s in out:
        s = re.sub(r"\s+", " ", s).strip()
        s = re.sub(r"\s*\(\s*", " (", s)
        s = re.sub(r"\s*\)", ")", s)
        s = re.sub(r"\s*,\s*", ", ", s)
        s = s.strip()
        if s:
            result.append(s)
    return result


def split_top(s, sep=","):
    """Разрезать по разделителю верхнего уровня: вне скобок и литералов."""
    parts, cur, depth, quote = [], [], 0, False
    for ch in s:
        if quote:
            cur.append(ch)
            if ch == "'":
                quote = False
            continue
        if ch == "'":
            quote = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == sep and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
            continue
        cur.append(ch)
    parts.append("".join(cur).strip())
    return [p for p in parts if p]


def find_top(s, word, start=0):
    """Позиция слова верхнего уровня (вне скобок и литералов) или -1."""
    depth, quote, i, w = 0, False, start, len(word)
    while i < len(s):
        ch = s[i]
        if quote:
            if ch == "'":
                quote = False
        elif ch == "'":
            quote = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif depth == 0 and s.startswith(word, i):
            before = s[i - 1] if i else " "
            after = s[i + w] if i + w < len(s) else " "
            if not (before.isalnum() or before == "_") and \
                    not (after.isalnum() or after == "_"):
                return i
        i += 1
    return -1


def paren(s, i):
    """Содержимое скобки, открытой в позиции i, и позиция за закрывающей."""
    depth, quote = 0, False
    for j in range(i, len(s)):
        ch = s[j]
        if quote:
            if ch == "'":
                quote = False
        elif ch == "'":
            quote = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return s[i + 1:j], j + 1
    return s[i + 1:], len(s)


def ident(token):
    return token.strip().strip('"').split(".")[-1]


IDENT = r'[\w."]+'


# ─────────────────────────────── модель схемы ───────────────────────────────

class Obj:
    """Объект схемы и то, откуда он взялся. Новым для прежнего кода считается
    объект, заведённый в текущем changeset'е или в ещё не выпущенном."""
    def __init__(self, origin, fresh, **kw):
        self.origin = origin
        self.fresh = fresh
        self.__dict__.update(kw)


class Schema:
    def __init__(self):
        self.tables = {}        # имя -> Obj(cols={имя: Obj(notnull, default, type)})
        self.views = {}         # имя -> Obj(cols=set или None)
        self.indexes = {}       # имя -> Obj(table, unique)
        self.constraints = {}   # имя -> Obj(table, kind, expr)
        self.other = {}         # (вид, имя) -> Obj()


class Finding:
    def __init__(self, cs, kind, obj, detail=""):
        self.cs = cs
        self.kind = kind
        self.obj = obj
        self.detail = detail

    def __repr__(self):
        return f"{self.kind}:{self.obj}"


ЧТО = {
    "drop-column": ("DROP COLUMN",
                    "код прежней версии, читающий или пишущий колонку, получит "
                    "«column does not exist»"),
    "drop-table": ("DROP TABLE",
                   "код прежней версии, обращающийся к таблице, получит "
                   "«relation does not exist»"),
    "drop-view": ("DROP VIEW",
                  "отчёт прежней версии, читающий вьюху, получит «relation does "
                  "not exist»"),
    "view-columns": ("вьюха пересоздана без прежних колонок",
                     "код прежней версии читает колонку, которой во вьюхе больше нет"),
    "view-unparsed": ("вьюха пересоздана, а сравнить колонки сторож не смог",
                      "без сравнения неизвестно, не пропала ли колонка, которую "
                      "читает прежний код"),
    "drop-object": ("DROP",
                    "код прежней версии, обращающийся к объекту, получит отказ"),
    "rename": ("переименование",
               "код прежней версии ищет объект по прежнему имени"),
    "type": ("смена типа колонки",
             "код прежней версии пишет и читает прежний тип"),
    "set-not-null": ("SET NOT NULL без умолчания",
                     "код прежней версии вставит строку без этой колонки и получит "
                     "«null value violates not-null constraint»"),
    "add-not-null": ("новая колонка NOT NULL без умолчания",
                     "код прежней версии вставит строку без этой колонки и получит "
                     "«null value violates not-null constraint»"),
    "drop-default": ("DROP DEFAULT у обязательной колонки",
                     "код прежней версии вставляет строку без этой колонки "
                     "и держится на умолчании"),
    "check": ("новое ограничение CHECK",
              "код прежней версии запишет то, что теперь запрещено"),
    "foreign-key": ("новый внешний ключ",
                    "код прежней версии запишет ссылку, которую ключ отобьёт"),
    "exclude": ("новое ограничение-исключение",
                "код прежней версии запишет то, что теперь запрещено"),
    "unique": ("новая уникальность",
               "код прежней версии может писать эти значения дублями"),
    "drop-unique": ("снятие уникальности",
                    "код прежней версии может опираться на неё в ON CONFLICT"),
}

# Что делать вместо — у обязательности своя дорога, у удаления своя.
НЕ_ПУСТО = ("Заведите колонку с умолчанием либо NULL; обязательной без умолчания "
            "она становится,\n      когда код, пишущий её сам, простоял релиз "
            "(порядок и N — db/CLAUDE.md).")
КАК = {
    "": ("Сужайте по expand/contract: сначала релиз, где код перестал это "
         "использовать,\n      удаление — не раньше, чем написано в db/CLAUDE.md."),
    "add-not-null": НЕ_ПУСТО,
    "set-not-null": НЕ_ПУСТО,
    "drop-default": НЕ_ПУСТО,
}

WIDER_INT = {"smallint": 1, "int2": 1, "integer": 2, "int": 2, "int4": 2,
             "bigint": 3, "int8": 3}


def widens(old, new):
    """Смена типа, которую прежний код переживает: только явные расширения."""
    if old is None:
        return False
    old, new = old.strip(), new.strip()
    if old == new:
        return True
    vc = r"^(?:varchar|character varying)\s*\((\d+)\)$"
    mo, mn = re.match(vc, old), re.match(vc, new)
    if mo and mn:
        return int(mn.group(1)) >= int(mo.group(1))
    if (mo or old in ("varchar", "character varying")) and new == "text":
        return True
    if old in WIDER_INT and new in WIDER_INT:
        return WIDER_INT[new] >= WIDER_INT[old]
    return False


def constant(expr):
    """Умолчание-константа: одинаковое у всех вставленных строк."""
    return expr is not None and "(" not in expr and not expr.startswith(
        ("current_", "localtime"))


COL_KEYWORDS = (" not null", " null", " default ", " constraint ", " check ",
                " references ", " unique", " primary key", " generated ",
                " collate ")


def column_def(el, origin, fresh):
    head, _, rest = el.partition(" ")
    rest = " " + rest
    cut = min([rest.find(k) for k in COL_KEYWORDS if rest.find(k) >= 0] or [len(rest)])
    ctype = rest[:cut].strip()
    notnull = bool(re.search(r" not null\b| primary key\b", rest))
    dm = re.search(r" default (.+?)(?= not null\b| null\b| constraint\b| check\b"
                   r"| references\b| unique\b| primary key\b| generated\b"
                   r"| collate\b|$)", rest)
    default = dm.group(1).strip() if dm else None
    if re.match(r"^(small|big)?serial\b", ctype) or \
            re.search(r" generated (always|by default) as identity", rest):
        default = "nextval()"
    return ident(head), Obj(origin, fresh, notnull=notnull, default=default,
                            type=ctype)


def in_list(expr):
    """`(col IN ('a', 'b'))` -> (col, {'a', 'b'}, NULL разрешён) либо None."""
    e = expr.strip()
    while e.startswith("(") and e.endswith(")"):
        inner, end = paren(e, 0)
        if end != len(e):
            break
        e = inner.strip()
    nullable = False
    nm = re.match(r"^([\w.]+) is null or (.*)$", e)
    if nm:
        nullable, e = True, nm.group(2).strip()
        if e.startswith("(") and e.endswith(")") and paren(e, 0)[1] == len(e):
            e = e[1:-1].strip()
    m = re.match(r"^([\w.]+) in \((.*)\)$", e)
    if not m or (nm and ident(nm.group(1)) != ident(m.group(1))):
        return None
    return ident(m.group(1)), {v.strip() for v in split_top(m.group(2))}, nullable


def select_columns(query):
    """Имена колонок, которые отдаёт SELECT, или None, если не разобрать.

    Разбор нарочно узкий: алиас после AS, `таблица.колонка`, имя функции.
    Всё остальное (`*`, выражение без алиаса) — None, и сторож скажет, что
    сравнить не смог, а не сочтёт вьюху совместимой."""
    q = query.strip()
    if q.startswith("with "):
        i = len("with ")
        if q.startswith("recursive ", i):
            i += len("recursive ")
        while True:
            m = re.match(r"[\w\"]+(?: \([^)]*\))? as (?:not )?(?:materialized )?\(", q[i:])
            if not m:
                return None
            _, end = paren(q, i + m.end() - 1)
            i = end
            rest = q[i:].lstrip()
            if rest.startswith(","):
                i = len(q) - len(rest) + 1
                while i < len(q) and q[i] == " ":
                    i += 1
                continue
            q = rest
            break
    while q.startswith("(") :
        q = paren(q, 0)[0].strip()
    if not q.startswith("select "):
        return None
    body = q[len("select "):]
    if body.startswith("distinct on "):
        _, end = paren(body, len("distinct on "))
        body = body[end:].strip()
    elif body.startswith("distinct "):
        body = body[len("distinct "):]
    end = find_top(body, "from")
    items = split_top(body[:end] if end >= 0 else body)
    cols = set()
    for item in items:
        item = item.strip()
        m = re.search(r" as ([\w\"]+)$", item)
        if m and find_top(item, "as", len(item) - len(m.group(0))) >= 0:
            cols.add(ident(m.group(1)))
            continue
        bare = re.sub(r"::[\w\s\[\]()]+$", "", item).strip()
        if re.match(r"^[\w.\"]+$", bare) and not bare.endswith("*"):
            cols.add(ident(bare))
            continue
        fm = re.match(r"^(\w+) ?\(", bare)
        if fm:
            cols.add(fm.group(1))
            continue
        return None
    return cols


def judge(changesets, fresh_paths):
    """Что сужает каждый changeset: {cs.id: [Finding]}.

    `fresh_paths` — файлы, которых нет в точке расхождения с origin/main.
    Объект, заведённый в них, прежнему коду неизвестен, и трогать его можно."""
    findings = {}
    schemas = {}
    for cs in changesets:
        schema = schemas.setdefault(cs.group, Schema())
        fresh = cs.path in fresh_paths
        found = findings.setdefault(cs.id, [])
        Judge(schema, cs, fresh, found).run()
    return findings


class Judge:
    def __init__(self, schema, cs, fresh, found):
        self.s = schema
        self.cs = cs
        self.fresh = fresh
        self.found = found
        self.dropped_views = {}     # имя -> Obj прежней вьюхи
        self.dropped_checks = {}    # имя -> (таблица, выражение)
        self.dropped_uniques = set()  # (таблица, колонки) снятой уникальности

    # новое для прежнего кода: заведено здесь или в невыпущенном
    def new(self, obj):
        return obj is not None and (obj.fresh or obj.origin == self.cs.id)

    def add(self, kind, obj, detail=""):
        if not any(f.kind == kind and f.obj == obj for f in self.found):
            self.found.append(Finding(self.cs, kind, obj, detail))

    def table(self, name):
        t = self.s.tables.get(name)
        if t is None:
            # Таблица, заведённая не этим набором (или не разобранная), для
            # прежнего кода существующая — сомнение трактуется строго.
            t = self.s.tables[name] = Obj("", False, cols={}, known=False)
        return t

    def run(self):
        for st in statements(self.cs.sql):
            self.statement(st)
        for name, old in self.dropped_views.items():
            if not self.new(old):
                self.add("drop-view", name)
            self.s.views.pop(name, None)

    def statement(self, st):
        o, f = self.cs.id, self.fresh

        m = re.match(r"^create (?:(?:global|local) )?(?:(?:temporary|temp|unlogged) )?"
                     r"table (?:if not exists )?(" + IDENT + r") \(", st)
        if m:
            name = ident(m.group(1))
            body, _ = paren(st, m.end() - 1)
            t = self.s.tables[name] = Obj(o, f, cols={}, known=True)
            for el in split_top(body):
                if re.match(r"^(constraint |primary key|unique|check|foreign key"
                            r"|exclude|like )", el):
                    self.table_constraint(name, el, create=True)
                    continue
                cname, col = column_def(el, o, f)
                t.cols[cname] = col
                self.inline_constraints(name, cname, el)
            return

        m = re.match(r"^alter table (?:if exists )?(?:only )?(" + IDENT + r") (.*)$", st)
        if m:
            name = ident(m.group(1))
            for action in split_top(m.group(2)):
                name = self.alter_table(name, action)
            return

        m = re.match(r"^create (unique )?index (?:concurrently )?(?:if not exists )?"
                     r"(?:(" + IDENT + r") )?on (?:only )?(" + IDENT + r")"
                     r"(?: using \w+)? \(", st)
        if m:
            unique = bool(m.group(1))
            iname = ident(m.group(2)) if m.group(2) else ""
            tname = ident(m.group(3))
            body, _ = paren(st, m.end() - 1)
            t = self.table(tname)
            if iname:
                self.s.indexes[iname] = Obj(o, f, table=tname, unique=unique, cols=body)
            if unique and not self.new(t) and (tname, body) not in self.dropped_uniques:
                cols = [c for c in re.findall(r"[a-z_][a-z0-9_]*", body) if c in t.cols]
                strict = "nulls not distinct" in st
                if not any(self.safe_unique_col(t.cols[c], strict) for c in cols):
                    self.add("unique", iname or tname,
                             f"индекс на {tname} ({body})")
            return

        m = re.match(r"^create (?:or replace )?(?:(?:temp|temporary) )?(?:recursive )?"
                     r"(materialized )?view (?:if not exists )?(" + IDENT + r")"
                     r"(?: \(([^)]*)\))? as (.*)$", st)
        if m:
            vname = ident(m.group(2))
            cols = ({ident(c) for c in split_top(m.group(3))} if m.group(3)
                    else select_columns(m.group(4)))
            old = self.dropped_views.pop(vname, None)
            if old is not None and not self.new(old):
                if old.cols is None or cols is None:
                    self.add("view-unparsed", vname)
                elif not old.cols <= cols:
                    gone = ", ".join(sorted(old.cols - cols))
                    self.add("view-columns", vname, f"пропали: {gone}")
            origin = old if old is not None else self.s.views.get(vname)
            if origin is not None and self.new(origin):
                origin = None
            self.s.views[vname] = (Obj(origin.origin, origin.fresh, cols=cols)
                                   if origin is not None else Obj(o, f, cols=cols))
            return

        m = re.match(r"^drop (table|materialized view|view|sequence|function|procedure"
                     r"|trigger|type|domain|schema|rule|index|extension) "
                     r"(?:concurrently )?(if exists )?(.*?)(?: cascade| restrict)?$", st)
        if m:
            self.drop(m.group(1), m.group(3), bool(m.group(2)))
            return

        m = re.match(r"^alter (index|sequence|materialized view|view|type|function"
                     r"|procedure|schema|trigger|domain) (?:if exists )?(" + IDENT + r")"
                     r"(?: on (" + IDENT + r"))? (.*)$", st)
        if m:
            kind, oname, rest = m.group(1), ident(m.group(2)), m.group(4)
            if rest.startswith("rename") or rest.startswith("set schema"):
                obj = self.lookup(kind, oname)
                if not self.new(obj):
                    self.add("rename", oname, f"{kind} {oname}: {rest}")
                nm = re.match(r"^rename to (" + IDENT + r")$", rest)
                if nm:
                    self.move(kind, oname, ident(nm.group(1)))
            return

        m = re.match(r"^create (?:or replace )?(sequence|type|domain|schema|function"
                     r"|procedure|trigger|rule) (?:if not exists )?(" + IDENT + r")", st)
        if m:
            self.s.other[(m.group(1), ident(m.group(2)))] = Obj(o, f)
            return

    # ─── ALTER TABLE ───

    def alter_table(self, name, a):
        o, f = self.cs.id, self.fresh
        t = self.table(name)
        tnew = self.new(t)

        m = re.match(r"^rename to (" + IDENT + r")$", a)
        if m:
            new_name = ident(m.group(1))
            if not tnew:
                self.add("rename", name, f"таблица {name} → {new_name}")
            self.s.tables[new_name] = self.s.tables.pop(name)
            return new_name

        m = re.match(r"^rename constraint (" + IDENT + r") to (" + IDENT + r")$", a)
        if m:
            old, new = ident(m.group(1)), ident(m.group(2))
            c = self.s.constraints.get(old)
            if not tnew and not self.new(c):
                self.add("rename", old, f"ограничение {old} → {new}")
            if c is not None:
                self.s.constraints[new] = self.s.constraints.pop(old)
            return name

        m = re.match(r"^rename (?:column )?(" + IDENT + r") to (" + IDENT + r")$", a)
        if m:
            old, new = ident(m.group(1)), ident(m.group(2))
            col = t.cols.get(old)
            if not tnew and not self.new(col):
                self.add("rename", f"{name}.{old}", f"колонка {name}.{old} → {new}")
            if col is not None:
                t.cols[new] = t.cols.pop(old)
            return name

        if a.startswith("set schema "):
            if not tnew:
                self.add("rename", name, f"таблица {name}: {a}")
            return name

        m = re.match(r"^add (?:constraint (" + IDENT + r") )?(check|foreign key|unique"
                     r"|primary key|exclude)\b(.*)$", a)
        if m:
            self.add_constraint(name, t, ident(m.group(1)) if m.group(1) else "",
                                m.group(2), m.group(3).strip())
            return name

        m = re.match(r"^add (?:column )?(?:if not exists )?(.*)$", a)
        if m:
            cname, col = column_def(m.group(1), o, f)
            t.cols[cname] = col
            if not tnew and col.notnull and col.default is None:
                self.add("add-not-null", f"{name}.{cname}")
            self.inline_constraints(name, cname, m.group(1))
            return name

        m = re.match(r"^drop constraint (?:if exists )?(" + IDENT + r")", a)
        if m:
            cname = ident(m.group(1))
            c = self.s.constraints.pop(cname, None)
            kind = c.kind if c is not None else (
                "unique" if re.search(r"_(uk|pk|pkey|key|unique)$", cname) else "")
            if kind in ("unique", "primary key", "exclude") and not tnew \
                    and not self.new(c):
                self.add("drop-unique", cname, f"ограничение на {name}")
            if c is not None and kind == "check":
                self.dropped_checks[cname] = (c.table, c.expr)
            if c is not None and getattr(c, "cols", None):
                self.dropped_uniques.add((name, c.cols))
            return name

        m = re.match(r"^drop (?:column )?(?:if exists )?(" + IDENT + r")"
                     r"(?: cascade| restrict)?$", a)
        if m:
            cname = ident(m.group(1))
            col = t.cols.pop(cname, None)
            if not tnew and not self.new(col):
                self.add("drop-column", f"{name}.{cname}")
            return name

        m = re.match(r"^alter (?:column )?(" + IDENT + r") (.*)$", a)
        if m:
            self.alter_column(name, t, ident(m.group(1)), m.group(2))
        return name

    def alter_column(self, tname, t, cname, rest):
        col = t.cols.get(cname)
        if col is None:
            col = t.cols[cname] = Obj("", False, notnull=None, default=None, type=None)
        tnew = self.new(t)
        obj = f"{tname}.{cname}"

        m = re.match(r"^(?:set data )?type (.*?)(?: using .*)?(?: collate .*)?$", rest)
        if m:
            if not tnew and not self.new(col) and not widens(col.type, m.group(1)):
                self.add("type", obj, f"{col.type or 'прежний тип'} → {m.group(1)}")
            col.type = m.group(1)
            return
        if rest.startswith("set default "):
            col.default = rest[len("set default "):]
            return
        if rest == "drop default" or rest.startswith("drop identity"):
            if not tnew and col.notnull is not False:
                self.add("drop-default", obj)
            col.default = None
            return
        if rest == "set not null":
            if not tnew and col.default is None:
                self.add("set-not-null", obj)
            col.notnull = True
            return
        if rest == "drop not null":
            col.notnull = False
            return

    def safe_unique_col(self, col, strict=False):
        """Уникальность, в которую входит такая колонка, не столкнётся со вставкой
        прежнего кода: тот оставит колонку пустой (NULL не сталкиваются) или
        получит неодинаковое умолчание. Хватает одной такой колонки в наборе.
        Одинаковое умолчание столкнётся со второй же строкой, а при NULLS NOT
        DISTINCT — и пустота."""
        if not self.new(col):
            return False
        if strict:
            return col.default is not None and not constant(col.default)
        return not constant(col.default)

    def add_constraint(self, tname, t, cname, kind, rest):
        o, f = self.cs.id, self.fresh
        expr = paren(rest, rest.find("("))[0] if "(" in rest else rest
        if cname:
            self.s.constraints[cname] = Obj(o, f, table=tname, kind=kind, expr=expr,
                                            cols=expr if kind != "check" else None)
        if self.new(t):
            return
        label = cname or tname
        if kind == "check":
            now = in_list(expr)
            for table, before in self.dropped_checks.values():
                was = in_list(before) if table == tname else None
                if was and now and was[0] == now[0] and was[1] <= now[1] \
                        and (now[2] or not was[2]):
                    return                   # перечень расширен, а не сужен
            refs = [c for c in re.findall(r"[a-z_][a-z0-9_]*", expr) if c in t.cols]
            if refs and all(self.new(t.cols[c]) for c in refs):
                return                       # только новые колонки
            self.add("check", label, f"на {tname}: {expr}")
        elif kind == "foreign key":
            cols = [ident(c) for c in split_top(expr)]
            if cols and all(self.new(t.cols.get(c)) for c in cols):
                return
            self.add("foreign-key", label, f"на {tname} ({expr})")
        elif kind in ("unique", "primary key"):
            cols = [ident(c) for c in split_top(expr)] if "(" in rest else []
            if (tname, expr) in self.dropped_uniques:
                return                       # та же уникальность, заменённая
            if any(c in t.cols and self.safe_unique_col(t.cols[c]) for c in cols):
                return
            self.add("unique", label, f"на {tname} ({expr})")
        else:
            self.add("exclude", label, f"на {tname}")

    def inline_constraints(self, tname, cname, el):
        """CHECK, UNIQUE и PRIMARY KEY в определении колонки. Безымянный CHECK
        Postgres называет `<таблица>_<колонка>_check` — под этим именем его
        потом и снимают."""
        o, f = self.cs.id, self.fresh
        for m in re.finditer(r"(?:constraint (\w+) )?(primary key|unique|check)\b", el):
            kind = m.group(2)
            if kind == "check":
                start = el.find("(", m.end())
                expr = paren(el, start)[0] if start >= 0 else ""
                name = m.group(1) or f"{tname}_{cname}_check"
                self.s.constraints[name] = Obj(o, f, table=tname, kind=kind, expr=expr)
            elif m.group(1):
                self.s.constraints[m.group(1)] = Obj(o, f, table=tname, kind=kind,
                                                     expr=cname, cols=cname)

    def table_constraint(self, tname, el, create):
        o, f = self.cs.id, self.fresh
        m = re.match(r"^(?:constraint (" + IDENT + r") )?(check|foreign key|unique"
                     r"|primary key|exclude)\b(.*)$", el)
        if m and m.group(1):
            rest = m.group(3).strip()
            expr = paren(rest, rest.find("("))[0] if "(" in rest else rest
            self.s.constraints[ident(m.group(1))] = Obj(
                o, f, table=tname, kind=m.group(2), expr=expr, cols=expr)

    # ─── DROP и поиск объектов ───

    def lookup(self, kind, name):
        if kind == "index":
            return self.s.indexes.get(name) or self.s.constraints.get(name)
        if kind in ("view", "materialized view"):
            return self.s.views.get(name)
        return self.s.other.get((kind, name))

    def lookup_any(self, kind, name):
        if kind == "table":
            return self.s.tables.get(name)
        return self.lookup(kind, name)

    def move(self, kind, old, new):
        for store in (self.s.indexes, self.s.views):
            if old in store:
                store[new] = store.pop(old)
        if (kind, old) in self.s.other:
            self.s.other[(kind, new)] = self.s.other.pop((kind, old))

    def drop(self, kind, names, if_exists=False):
        """DROP … IF EXISTS того, чего набор не заводил, — пустая операция:
        так пишут перед первым CREATE (tenant/044 снимает триггеры, которых
        ещё нет). Без IF EXISTS неизвестный объект судится строго: значит,
        его завели способом, который сторож не разобрал."""
        if kind in ("trigger", "rule"):
            m = re.match(r"^(" + IDENT + r") on (" + IDENT + r")$", names)
            if m:
                tname, oname = ident(m.group(2)), ident(m.group(1))
                obj = self.s.other.pop((kind, oname), None)
                if obj is None and if_exists:
                    return
                if not self.new(self.table(tname)) and not self.new(obj):
                    self.add("drop-object", oname, f"{kind.upper()} {oname} на {tname}")
            return
        for raw in split_top(names):
            oname = ident(raw.split(" (")[0])
            if if_exists and self.lookup_any(kind, oname) is None:
                continue
            if kind == "table":
                t = self.s.tables.pop(oname, None)
                if not self.new(t):
                    self.add("drop-table", oname)
                for iname in [k for k, v in self.s.indexes.items() if v.table == oname]:
                    self.s.indexes.pop(iname)
            elif kind in ("view", "materialized view"):
                v = self.s.views.get(oname)
                if v is not None:          # IF EXISTS несуществующей — ничего
                    self.dropped_views[oname] = v
            elif kind == "index":
                ix = self.s.indexes.pop(oname, None)
                if ix is not None and ix.unique:
                    self.dropped_uniques.add((ix.table, ix.cols))
                unique = ix.unique if ix is not None else bool(
                    re.search(r"_(uk|pk|pkey|key|unique)$", oname))
                if unique and not self.new(ix) and \
                        not self.new(self.s.tables.get(ix.table) if ix else None):
                    self.add("drop-unique", oname, "уникальный индекс")
            else:
                obj = self.s.other.pop((kind, oname), None)
                if not self.new(obj):
                    self.add("drop-object", oname, f"{kind.upper()} {oname}")


# ──────────────────────────────── сторож ────────────────────────────────

SHA = re.compile(r"\b[0-9a-f]{7,40}\b")


class Git:
    """Точка расхождения с origin/main: что выпущено и что уже слито."""
    def __init__(self, root=ROOT):
        self.root = root
        self.mb = None
        if self.run("rev-parse", "--verify", "-q", "origin/main").returncode == 0:
            self.mb = self.run("merge-base", "HEAD", "origin/main").stdout.strip() or None

    def run(self, *args):
        return subprocess.run(["git", "-C", self.root] + list(args),
                              capture_output=True, text=True)

    def fresh(self, paths):
        """Файлы, которых нет в точке расхождения. Без origin/main — ни одного:
        тогда каждый changeset судится как свой собственный релиз, то есть
        строже, а не мягче."""
        if self.mb is None:
            return set()
        return {p for p in paths
                if self.run("cat-file", "-e", f"{self.mb}:db/changelog/{p}").returncode != 0}

    def merged_before(self, sha):
        """None — сверить не с чем; иначе (есть ли коммит, слит ли до ветки)."""
        if self.mb is None:
            return None
        if self.run("rev-parse", "--verify", "-q", f"{sha}^{{commit}}").returncode != 0:
            return (False, False)
        return (True, self.run("merge-base", "--is-ancestor", sha, self.mb).returncode == 0)


def check_mark(cs, text, git):
    """Что не так с текстом пометки; None — годится."""
    shas = SHA.findall(text)
    words = re.findall(r"[а-яёa-z]{3,}", SHA.sub(" ", text.lower()))
    if not shas:
        return ("не назван релиз, в котором код перестал использовать объект: "
                "нужен SHA сборки (тег образа), и он уже должен быть в main")
    if len(words) < 3:
        return ("причина не написана — одного SHA мало: скажите, почему прежний "
                "код объект больше не трогает")
    verdicts = [git.merged_before(s) for s in shas] if git else [None]
    if all(v is None for v in verdicts):
        return None
    if any(v == (True, True) for v in verdicts):
        return None
    if all(v in ((False, False), None) for v in verdicts):
        return f"коммита {shas[0]} нет в репозитории"
    return (f"коммит {shas[0]} не слит в main до этой ветки: отказ от объекта "
            f"и его удаление уедут одной выкладкой, и откат на прежний образ "
            f"упадёт. Сначала релиз, где код перестал его использовать")


def check(changesets, fresh_paths, history=None, ceiling=None, git=None):
    """Нарушения. Пустой список — все сужения либо помечены, либо в истории."""
    history = ИСТОРИЯ if history is None else history
    ceiling = ПОТОЛОК_ИСТОРИИ if ceiling is None else ceiling
    findings = judge(changesets, fresh_paths)
    by_id = {cs.id: cs for cs in changesets}
    problems = []

    for cid, entry in history.items():
        cs = by_id.get(cid)
        objects, why = entry
        if cs is None:
            problems.append(f"ИСТОРИЯ: changeset'а {cid} в наборе нет — запись "
                            f"устарела или идентификатор с опечаткой")
            continue
        if not why or not why.strip().startswith("до 0116"):
            problems.append(f"ИСТОРИЯ: {cs.name} — причина обязана начинаться "
                            f"с «до 0116» и что-то объяснять; пометка без причины "
                            f"не принимается")
        elif len(why.strip()) < len("до 0116") + 10:
            problems.append(f"ИСТОРИЯ: {cs.name} — за «до 0116» не написано ничего; "
                            f"пометка без причины не принимается")
        if cs.path in fresh_paths or (cs.file_number or 0) > ceiling.get(cs.group, 0):
            problems.append(f"ИСТОРИЯ: {cs.name} — changeset новее сторожа, в список "
                            f"он не попадает. Пометка «{МЕТКА}» ставится в самом "
                            f"changeset'е, рядом с инструкцией")
        found = {f.obj for f in findings.get(cid, [])}
        for obj in objects:
            if obj not in found:
                problems.append(f"ИСТОРИЯ: {cs.name} — «{obj}» записан сужением, "
                                f"а сторож его там не находит: запись устарела")

    for cs in changesets:
        found = findings.get(cs.id, [])
        marks = {}
        for n, obj, text in cs.marks:
            if obj is None or not text:
                problems.append(f"{cs.name}: пометка «{МЕТКА}» без объекта или без "
                                f"причины. Форма: «{МЕТКА} <объект>: <SHA релиза> "
                                f"<почему прежний код его не трогает>»")
                continue
            marks[obj] = text
        allowed = set(history.get(cs.id, ((), ""))[0])
        for obj, text in marks.items():
            if not any(f.obj == obj for f in found):
                problems.append(f"{cs.name}: пометка «{МЕТКА} {obj}» стоит там, где "
                                f"сторож сужения не находит. Либо она лишняя, либо "
                                f"объект назван не так, как в отказе")
                continue
            bad = check_mark(cs, text, git)
            if bad:
                problems.append(f"{cs.name}: пометка «{МЕТКА} {obj}» не принята — {bad}")
        for f in found:
            if f.obj in marks or f.obj in allowed:
                continue
            title, why = ЧТО[f.kind]
            detail = f" ({f.detail})" if f.detail else ""
            problems.append(
                f"{cs.name}: {title} {f.obj}{detail} — {why}.\n"
                f"      Откат приложения на прежний образ после этой миграции "
                f"не пройдёт. {КАК.get(f.kind, КАК[''])}\n"
                f"      Тогда допишите в changeset\n"
                f"      {МЕТКА} {f.obj}: <SHA того релиза> <почему прежний код "
                f"это больше не трогает>")
    return problems, findings


# ────────────────────────────── самопроверка ──────────────────────────────

class FakeGit:
    def __init__(self, merged=(), unmerged=()):
        self.merged, self.unmerged = set(merged), set(unmerged)

    def merged_before(self, sha):
        if sha in self.merged:
            return (True, True)
        if sha in self.unmerged:
            return (True, False)
        return (False, False)


def selftest():
    """Краснеет ли сторож на дефекте — и краснеет ли той частью, ради которой
    написан, а не любой. Выдуманные changeset'ы, без git и без базы."""
    failures = []
    base = Changeset("tenant/001-base.sql", "t-001", """
        CREATE TABLE ${tenant.schema}.part (
            id bigserial PRIMARY KEY,
            name text NOT NULL,
            side text,
            status text NOT NULL DEFAULT 'IN_STOCK'
                CONSTRAINT part_status_check CHECK (status IN ('IN_STOCK', 'SOLD')),
            price numeric(12,2) NOT NULL DEFAULT 0,
            code varchar(20)
        );
        CREATE VIEW ${tenant.schema}.v_stock AS
        SELECT p.id, p.name AS title, count(*) AS qty FROM ${tenant.schema}.part p GROUP BY p.id;
        CREATE SEQUENCE ${tenant.schema}.wheel_set_no_seq;
    """)
    ceiling = {"tenant": 1}

    def run(sql, marks=(), fresh=True, history=None, git=None, extra=()):
        cs = Changeset("tenant/002-x.sql", "t-002", sql, list(marks))
        sets = [base] + list(extra) + [cs]
        paths = {c.path for c in sets if c is not base} if fresh else set()
        problems, _ = check(sets, paths, history=history or {}, ceiling=ceiling,
                            git=git or FakeGit(merged={"1a2b3c4d"}))
        return problems

    def red(label, sql, expect, **kw):
        problems = run(sql, **kw)
        text = "\n".join(problems)
        if not problems:
            failures.append(f"{label}: принято — а это ровно то, ради чего сторож написан")
        elif expect not in text or "t-002" not in text:
            failures.append(f"{label}: отказ есть, но не называет changeset и "
                            f"операцию «{expect}»: {text[:200]}")

    def silent(label, sql, **kw):
        problems = run(sql, **kw)
        if problems:
            failures.append(f"{label}: объявлено нарушением — {'; '.join(problems)[:300]}")

    # Три случая, названные задачей 0116 дословно.
    red("DROP COLUMN без пометки",
        "ALTER TABLE ${tenant.schema}.part DROP COLUMN side;", "DROP COLUMN part.side")
    red("SET NOT NULL без умолчания",
        "ALTER TABLE ${tenant.schema}.part ALTER COLUMN side SET NOT NULL;",
        "SET NOT NULL без умолчания part.side")
    red("переименование колонки",
        "ALTER TABLE ${tenant.schema}.part RENAME COLUMN side TO position;",
        "переименование part.side")
    red("переименование таблицы",
        "ALTER TABLE ${tenant.schema}.part RENAME TO item;", "переименование part")
    silent("добавление колонки с NULL",
           "ALTER TABLE ${tenant.schema}.part ADD COLUMN note text;")

    # Остальные формы из задачи.
    red("новая обязательная колонка без умолчания",
        "ALTER TABLE ${tenant.schema}.part ADD COLUMN probe text NOT NULL;",
        "новая колонка NOT NULL без умолчания part.probe")
    silent("новая обязательная колонка с умолчанием",
           "ALTER TABLE ${tenant.schema}.part ADD COLUMN probe text NOT NULL DEFAULT '';")
    red("DROP TABLE", "DROP TABLE IF EXISTS ${tenant.schema}.part CASCADE;",
        "DROP TABLE part")
    red("DROP SEQUENCE, которую код зовёт напрямую",
        "DROP SEQUENCE ${tenant.schema}.wheel_set_no_seq;", "wheel_set_no_seq")
    red("смена типа", "ALTER TABLE ${tenant.schema}.part ALTER COLUMN side TYPE integer "
        "USING side::integer;", "смена типа колонки part.side")
    silent("расширение типа", "ALTER TABLE ${tenant.schema}.part ALTER COLUMN code "
           "TYPE varchar(40);")
    red("сужение CHECK",
        "ALTER TABLE ${tenant.schema}.part DROP CONSTRAINT part_status_check;\n"
        "ALTER TABLE ${tenant.schema}.part ADD CONSTRAINT part_status_check "
        "CHECK (status IN ('IN_STOCK'));", "новое ограничение CHECK part_status_check")
    silent("расширение перечня CHECK под тем же именем",
           "ALTER TABLE ${tenant.schema}.part DROP CONSTRAINT part_status_check;\n"
           "ALTER TABLE ${tenant.schema}.part ADD CONSTRAINT part_status_check "
           "CHECK (status IN ('IN_STOCK', 'SOLD', 'RESERVED'));")
    red("уникальность на старых данных",
        "CREATE UNIQUE INDEX part_name_uk ON ${tenant.schema}.part (name);",
        "новая уникальность part_name_uk")
    red("DROP DEFAULT у обязательной колонки",
        "ALTER TABLE ${tenant.schema}.part ALTER COLUMN price DROP DEFAULT;",
        "DROP DEFAULT у обязательной колонки part.price")
    red("вьюха пересоздана без колонки",
        "DROP VIEW IF EXISTS ${tenant.schema}.v_stock;\n"
        "CREATE VIEW ${tenant.schema}.v_stock AS SELECT p.id, count(*) AS qty "
        "FROM ${tenant.schema}.part p GROUP BY p.id;", "пропали: title")
    silent("вьюха пересоздана с новой колонкой",
           "DROP VIEW IF EXISTS ${tenant.schema}.v_stock;\n"
           "CREATE VIEW ${tenant.schema}.v_stock AS SELECT p.id, p.name AS title, "
           "coalesce(p.side, '') AS side, count(*) AS qty FROM ${tenant.schema}.part p "
           "GROUP BY p.id;")
    red("вьюха снята и не пересоздана",
        "DROP VIEW ${tenant.schema}.v_stock;", "DROP VIEW v_stock")
    silent("DROP IF EXISTS того, чего набор не заводил (как перед первым CREATE)",
           "DROP TRIGGER IF EXISTS part_touch ON ${tenant.schema}.part;\n"
           "DROP TABLE IF EXISTS ${tenant.schema}.nothing_here;\n"
           "DROP VIEW IF EXISTS ${tenant.schema}.v_nothing;")
    red("DROP IF EXISTS того, что есть",
        "DROP SEQUENCE IF EXISTS ${tenant.schema}.wheel_set_no_seq;", "wheel_set_no_seq")

    # Образец tenant/064: колонка новая, умолчание раньше NOT NULL, уникальность
    # на ней — прежний код ничего из этого не заденет.
    silent("expand по образцу tenant/064",
           "CREATE SEQUENCE ${tenant.schema}.part_number_seq;\n"
           "ALTER TABLE ${tenant.schema}.part ADD COLUMN number bigint;\n"
           "UPDATE ${tenant.schema}.part SET number = id;\n"
           "ALTER TABLE ${tenant.schema}.part ALTER COLUMN number SET DEFAULT "
           "nextval('${tenant.schema}.part_number_seq');\n"
           "ALTER TABLE ${tenant.schema}.part ALTER COLUMN number SET NOT NULL;\n"
           "CREATE UNIQUE INDEX part_number_uk ON ${tenant.schema}.part (number);")
    red("064 без умолчания",
        "ALTER TABLE ${tenant.schema}.part ADD COLUMN number bigint;\n"
        "ALTER TABLE ${tenant.schema}.part ALTER COLUMN number SET NOT NULL;",
        "SET NOT NULL без умолчания part.number")

    # Комментарии, откат и литералы — не инструкции.
    silent("DROP COLUMN в комментарии, откате и строке",
           "-- ALTER TABLE part DROP COLUMN side;\n"
           "--comment потом DROP COLUMN side\n"
           "COMMENT ON COLUMN ${tenant.schema}.part.side IS 'не DROP COLUMN; ни за что';\n"
           "/* DROP TABLE part; */\n"
           "--rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN side;")

    # Объект, которого прежний код не знает: таблица из невыпущенного changeset'а.
    new_table = Changeset("tenant/002-a.sql", "t-002a",
                          "CREATE TABLE ${tenant.schema}.company_setting (id int);")
    problems, _ = check([base, new_table,
                         Changeset("tenant/002-b.sql", "t-002b",
                                   "ALTER TABLE ${tenant.schema}.company_setting "
                                   "ADD COLUMN days int NOT NULL;")],
                        {"tenant/002-a.sql", "tenant/002-b.sql"}, history={},
                        ceiling=ceiling, git=FakeGit())
    if problems:
        failures.append("обязательная колонка в таблице из невыпущенного changeset'а "
                        "объявлена нарушением: прежний код этой таблицы не знает")
    problems, _ = check([base, new_table,
                         Changeset("tenant/002-b.sql", "t-002b",
                                   "ALTER TABLE ${tenant.schema}.company_setting "
                                   "ADD COLUMN days int NOT NULL;")],
                        {"tenant/002-b.sql"}, history={}, ceiling=ceiling, git=FakeGit())
    if not problems:
        failures.append("таблица уже выпущена, а обязательная колонка без умолчания "
                        "в неё принята: выпущенное считается новым")

    # Пометка: принимается с релизом и причиной, и только с ними.
    drop = "ALTER TABLE ${tenant.schema}.part DROP COLUMN side;"
    good = "код перестал читать и писать колонку в 1a2b3c4d, стороны живут в part_side"
    silent("пометка с релизом и причиной", drop,
           marks=[(1, "part.side", good)])
    red("пометка без причины", drop, "пометка «--сужение part.side» не принята",
        marks=[(1, "part.side", "1a2b3c4d")])
    red("пометка без релиза", drop, "не назван релиз",
        marks=[(1, "part.side", "код давно не читает эту колонку")])
    red("пометка пустая", drop, "без объекта или без причины",
        marks=[(1, None, "")])
    red("пометка на релиз, не слитый до ветки", drop, "не слит в main до этой ветки",
        marks=[(1, "part.side", "код перестал читать колонку в 9f9f9f9f, в этой же ветке")],
        git=FakeGit(merged={"1a2b3c4d"}, unmerged={"9f9f9f9f"}))
    red("пометка не на тот объект", drop, "сторож сужения не находит",
        marks=[(1, "part.side", good), (2, "part.name", good)])
    problems = run("ALTER TABLE ${tenant.schema}.part ADD COLUMN note text;",
                   marks=[(1, "part.note", good)])
    if not problems:
        failures.append("пометка на том, что не сужается, принята — её начнут "
                        "писать не думая")

    # История: закрывает выпущенное до сторожа, и только его.
    old_drop = Changeset("tenant/001-old.sql", "t-001-old", drop)
    reason = "до 0116: сторона переехала в part_side, код её не читал"
    problems, _ = check([base, old_drop], set(),
                        history={"t-001-old": (("part.side",), reason)},
                        ceiling=ceiling, git=FakeGit())
    if problems:
        failures.append("выпущенное сужение из списка истории объявлено нарушением: "
                        + "; ".join(problems))
    problems, _ = check([base, old_drop], set(),
                        history={"t-001-old": (("part.side",), "до 0116")},
                        ceiling=ceiling, git=FakeGit())
    if not problems:
        failures.append("запись истории без причины принята")
    problems, _ = check([base, old_drop], set(),
                        history={"t-001-old": (("part.side",), "   ")},
                        ceiling=ceiling, git=FakeGit())
    if not problems:
        failures.append("запись истории с пустой причиной принята")
    new_drop = Changeset("tenant/002-x.sql", "t-002", drop)
    problems, _ = check([base, new_drop], set(),
                        history={"t-002": (("part.side",), reason)},
                        ceiling=ceiling, git=FakeGit())
    if not any("новее сторожа" in p for p in problems):
        failures.append("changeset новее потолка ушёл в список истории — список "
                        "станет способом отключить сторож")
    problems, _ = check([base, old_drop], set(),
                        history={"t-001-old": (("part.side", "part.name"), reason)},
                        ceiling=ceiling, git=FakeGit())
    if not any("устарела" in p for p in problems):
        failures.append("запись истории, которой нечего разрешать, не названа "
                        "устаревшей")
    return failures


# ───────────────────────────────── main ─────────────────────────────────

def main():
    broken = selftest()
    if broken:
        print("Самопроверка не прошла — сторож ничего не доказывает:\n")
        for b in broken:
            print("  •", b)
        return 1
    if "--selftest" in sys.argv:
        print("Самопроверка пройдена: DROP COLUMN, SET NOT NULL без умолчания, "
              "переименование,\nобязательная колонка без умолчания, смена типа, "
              "сужение CHECK, уникальность,\nвьюха без колонки — отбиты; колонка "
              "с NULL, расширение типа и перечня, expand\nпо образцу tenant/064 "
              "и объект из невыпущенного changeset'а — приняты.\nПометка без "
              "релиза, без причины, на несуженном и на неслитом релизе —\n"
              "не принята; история закрывает только выпущенное до потолка.")
        return 0

    sets = read()
    if "--list" in sys.argv:
        for cid, (objects, why) in ИСТОРИЯ.items():
            print(f"{cid}: {', '.join(objects)}\n    {why}\n")
        return 0

    git = Git()
    fresh = git.fresh({cs.path for cs in sets})
    problems, findings = check(sets, fresh, git=git)
    if problems:
        print("Совместимость миграций с прежней версией кода: проверка не прошла\n")
        for p in problems:
            print("  •", p)
        print("\nПравило и порядок удаления — db/CLAUDE.md, раздел "
              "«Миграция совместима с прежней версией кода».")
        return 1
    marked = sum(1 for cs in sets for f in findings.get(cs.id, []) if cs.id not in ИСТОРИЯ)
    listed = sum(len(objs) for objs, _ in ИСТОРИЯ.values())
    print(f"Миграции совместимы с прежней версией кода: {len(sets)} changeset'ов, "
          f"из них не выпущено {len(fresh)} файл(ов).\n"
          f"Сужений в истории до 0116: {listed} списком; пометкой в самом "
          f"changeset'е: {marked}.")
    if git.mb is None:
        print("  origin/main недоступен: каждый changeset судился как отдельный "
              "релиз, релизы в пометках\n  со слитым не сверены.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
