#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Цена отката: чем обойдётся возврат схемы вниз — до того, как его сделали.

  ./db/rollback-cost.py                    сторож: у каждой потери названа цена
  ./db/rollback-cost.py --from 138 --to 135   что снимет откат между версиями
  ./db/rollback-cost.py --count 3          то же, но «на три changeset'а вниз»
  ./db/rollback-cost.py --selftest         проверка самого сторожа

ЧЕГО НЕ ЗАКРЫВАЕТ МЕХАНИКА. `db/verify-rollback.py` (задача 0081) отвечает
на вопрос «вернётся ли схема той же». Структурно верный откат при этом может
потерять данные, и потеря невидима: колонка на месте, тип тот же, индекс тот же.

Пример не выдуманный. Откат `064-part-number` снимает `part.number` —
порядковые номера позиций. Повторный накат раздаёт их заново,
`row_number() OVER (ORDER BY id)`, и если между откатом и накатом удалена
хоть одна позиция, номера **сдвинутся**: «посмотри позицию 347» станет другой
деталью, а сверка схемы этого не увидит. Тот же класс у `066-company-settings`:
откат снимает таблицу вместе с заданным владельцем сроком резерва, и повторный
накат вернёт умолчание — три дня. Внешне всё цело, настройка клиента молча
заменена.

ЧТО СДЕЛАНО. Определить потерю проверка не может, но может **потребовать
пометки** у подозрительного отката — того, где стоит `DROP TABLE`,
`DROP COLUMN`, `DELETE` или `UPDATE`. Ненаписанная причина — отказ, как
у разрешённых пар в стороже схем. А собрав пометки по changeset'ам между
двумя версиями, шаг выкладки печатает цену отката сам, а не человек
её вспоминает.

ГДЕ ЖИВЁТ ПОМЕТКА — И ПОЧЕМУ В ДВУХ МЕСТАХ. У нового changeset'а она стоит
рядом с `--rollback`, потому что знает о потере только его автор:

    --rollback-теряет порядковые номера позиций: раздаются заново
    --rollback ALTER TABLE ${tenant.schema}.part DROP COLUMN number;

Liquibase такую строку не читает как откат — проверено прогоном: в выводе
`rollback-count-sql` остаётся ровно настоящая инструкция, а накат и откат
проходят как раньше.

А у 66 выпущенных changeset'ов пометке внутри взяться неоткуда: **их нельзя
трогать вообще**. Текст отката и комментарии входят в чек-сумму, и правка
валит накат у всех уже заведённых арендаторов — это измерено на живых схемах
(db/CLAUDE.md). Поэтому их цена лежит здесь, в `ПОТЕРИ`, — одним списком,
заполненным перебором всех 138 changeset'ов схемы арендатора. Дописывать
сюда новое **нельзя**: новый changeset обязан нести пометку внутри, и сторож
этого требует отдельно.

ЧЕГО ПРОВЕРКА НЕ СМОТРИТ — и это названо, а не умолчано. Только схема
арендатора. В общей схеме `catalog` рискованных откатов ещё 28, список для них
не написан, и молчание тут читалось бы как «там терять нечего».

Двадцать шесть из них — справочники: марки, модели, поколения, виды деталей,
написания. Их и правда собирают заново из `db/seed`, то есть цена отката
там — время пересборки, а не потеря.

**Но два оставшихся под это не подпадают, и обобщать на все 28 неверно:**

  • `catalog-050-tenant-registry` — `DROP TABLE public.tenant_registry`.
    Это единственное место, где вообще записано, какие арендаторы есть
    в ячейке: номер, имя схемы, название компании, статус, версия схемы.
    В `db/seed` про реестр нет ни строки, и собрать его заново неоткуда —
    теряется знание о клиентах всей ячейки разом.

  • `catalog-008-tenant-login-code` — откат снимает `tenant_registry.code`,
    а повторный накат проставляет `'c' || tenant_id` всем, у кого он пуст.
    Код входа не сеется: его присваивает провижининг индивидуально
    (`TenantProvisioning.reserve`), и клиент его набирает руками, а позже
    он станет поддоменом. То есть болезнь ровно та же, что у
    `066-company-settings`: внешне всё цело, а значение молча заменено
    на выведенное из номера.
"""
import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHANGELOG = os.path.join(ROOT, "db", "changelog")
MANIFEST = "db.changelog-tenant.xml"

# Что считается подозрительным откатом. Список ровно из задачи 0082: операции,
# после которых данных может не стать. Проверка не решает, есть потеря или нет,
# — она требует, чтобы на этот вопрос ответил человек.
РИСК = re.compile(r'\b(DROP\s+TABLE|DROP\s+COLUMN|DROP\s+SCHEMA|DELETE\s+FROM'
                  r'|UPDATE|TRUNCATE)\b', re.IGNORECASE)

МЕТКА = "--rollback-теряет"

# Цена отката выпущенных changeset'ов. Заполнено перебором: 81 откат из 138
# несёт одну из рискованных операций, и у каждого написано, что именно теряет
# ПОЛЬЗОВАТЕЛЬ, а не какой объект исчезает из схемы. «Снимается таблица
# part_stock» человеку не говорит ничего; «раскладка остатка по складам»
# говорит.
#
# Пустая строка не принимается: причина и есть то, что отличает разбор
# от отписки.
ПОТЕРИ = {
    "tenant-001-schema":
        "всю схему арендатора целиком — склад, продажи, журналы, ссылки "
        "на снимки. Этот changeset не откатывают вовсе: и db/verify.sh, "
        "и db/verify-rollback.py останавливаются на нём",
    "tenant-010-branch": "справочник филиалов со всеми заведёнными филиалами",
    "tenant-011-tenant-member":
        "всех сотрудников компании вместе с ролями — после отката в кабинет "
        "не войдёт никто",
    "tenant-012-warehouse": "склады компании; позициям негде будет лежать",
    "tenant-013-storage-cell":
        "ячейки хранения — адреса полок, по которым ищут деталь и печатают "
        "этикетки",
    "tenant-020-donor": "все машины-доноры: VIN, марку, модель, номер кузова",
    "tenant-021-donor-cost":
        "затраты по машине — то, из чего считается её окупаемость",
    "tenant-030-part":
        "весь склад: номенклатуру целиком с ценами, наименованиями "
        "и публичными кодами",
    "tenant-031-part-oem":
        "номера производителя у позиций — по ним ищут деталь и сводят кроссы",
    "tenant-032-part-applicability": "применимость позиций к машинам",
    "tenant-033-part-photo":
        "ссылки на снимки позиций. Сами файлы останутся в хранилище, но какой "
        "чей — будет неизвестно",
    "tenant-040-stock-movement":
        "журнал движений склада — документ, которым отвечают на «кто унёс "
        "деталь»",
    "tenant-045-inventory": "все пересчёты вместе с их листами обхода",
    "tenant-050-customer":
        "справочник покупателей: имена, телефоны, остатки лицевых счетов",
    "tenant-051-deal":
        "все сделки вместе с их номерами. Повторный накат начнёт нумерацию "
        "заново — «сделка №20» станет другой сделкой",
    "tenant-052-deal-item": "состав сделок: что именно продано",
    "tenant-053-payment": "все платежи компании — кассу целиком",
    "tenant-054-deal-return": "возвраты",
    "tenant-055-reservation": "резервы товара под клиентов",
    "tenant-060-marketplace-account":
        "выгрузки на площадки: отбор, ссылки, токены. Ссылку прописывает "
        "у себя техспециалист площадки руками, и новая будет другой",
    "tenant-061-listing": "объявления, связывающие позицию с площадкой",
    "tenant-062-publication-log": "журнал отправок на площадку",
    "tenant-063-price-rule": "правила цены",
    "tenant-070-outbox":
        "неотправленные события: то, что ещё не уехало на площадку, "
        "не уедет вовсе",
    "tenant-071-audit-log":
        "журнал изменений — на «кто уронил цену» отвечать будет нечем",
    "tenant-090-supply": "поставки и партии со своими номерами",
    "tenant-091-donor-logistics":
        "логистические поля машины: комплектацию, цвет, коробку, привод, руль",
    "tenant-092-part-stock":
        "раскладку остатка по складам: сколько чего и где лежит",
    "tenant-093-stock-movement-warehouse":
        "склад отправления и назначения у движений и ссылку на документ — "
        "журнал перестанет отвечать, куда именно уехала деталь",
    "tenant-094-stock-document":
        "складские документы (приёмки, перевозки, списания) с номерами "
        "и составом",
    "tenant-096-part-name":
        "справочник наименований и сопоставления написаний — после переезда "
        "клиента их сотни",
    "tenant-097-part-attributes":
        "стороны позиции, старые данные переезда, автора и момент правки цены",
    "tenant-100-deal-source": "справочник источников сделок",
    "tenant-101-payment-source":
        "справочник способов оплаты. Платежи останутся без записанного "
        "способа, и «сколько прошло наличными» перестанет считаться",
    "tenant-102-customer-account":
        "журнал лицевого счёта клиента и реквизиты покупателя (ИНН, название, "
        "заметки); остаток счёта пересчитывать будет не из чего",
    "tenant-105-deal-fields":
        "срок резерва, внесённую оплату, склад и момент выдачи у сделок; "
        "перечень статусов сужается обратно",
    "tenant-106-deal-item-status": "статус строки сделки и склад выдачи по ней",
    "tenant-107-payment-document":
        "направление платежа (приход или расход), способ оплаты, комментарий "
        "и привязку к клиенту: расходные платежи станут неотличимы "
        "от приходных",
    "tenant-108-return-document":
        "документы возврата — номер, клиента, склад возврата, статус "
        "и состав; возврат снова станет одной строкой в сделке",
    "tenant-109-document-event":
        "историю документа: кто и что делал со сделкой",
    "tenant-110-part-stock-available":
        "ничего: снимается генерируемая колонка свободного остатка, "
        "вычислявшаяся из qty и qty_reserved",
    "tenant-140-member-credentials":
        "логины и пароли сотрудников — после отката вход закрыт для всех",
    "tenant-150-client-request-id":
        "ключи идемпотентности приёмки и запроса ссылки на снимок: повтор "
        "из офлайн-очереди заведёт вторую позицию",
    "tenant-160-processed-event":
        "отметки об уже обработанных событиях — обработчики повторят их",
    "tenant-161-event-dead-letter":
        "разбор недоставленных событий вместе с тем, что ждёт человека",
    "tenant-170-feed-token":
        "токены выгрузок: постоянные ссылки, прописанные у площадки, "
        "перестанут работать",
    "tenant-190-import-run": "журнал переездов и импортов",
    "tenant-200-dead-letter-retry":
        "расписание повторов и отметки о разборе недоставленных событий",
    "tenant-210-donor-legacy-code":
        "старый код машины из прежней системы — то, чем сверяют переехавшее",
    "tenant-230-deal-source-seed":
        "заведённые источники сделок («Дром», «Авито», «Звонок»…). Если "
        "по ним уже есть сделки, откат упрётся во внешний ключ и не пройдёт",
    "tenant-231-deal-external-order":
        "заказы с площадок: номер заказа, площадку, срок ответа — "
        "идемпотентность по номеру заказа пропадёт",
    "tenant-240-service": "справочник услуг",
    "tenant-241-deal-service": "услуги, добавленные в сделки",
    "tenant-260-feed-filters": "отбор выгрузки: цену, состояния, склады",
    "tenant-270-deal-version":
        "счётчик версии сделки — защиту от одновременной правки",
    "tenant-280-feed-lists":
        "списки марок и видов деталей в отборе выгрузки",
    "tenant-290-deal-share":
        "ссылки, которыми сделку показывают покупателю",
    "tenant-300-product-line":
        "линию товара — запчасть это или колесо; вкладка «Шины и диски» "
        "опустеет",
    "tenant-301-part-wheel":
        "шинные и дисковые характеристики позиций и номера комплектов",
    "tenant-310-donor-body-engine": "код кузова и двигателя машины",
    "tenant-033-photo-import":
        "очередь переноса фотографий из прежней системы",
    "tenant-037-inventory-line-applied":
        "отметку о проведении строки пересчёта: проведённый пересчёт станет "
        "неотличим от непроведённого",
    "tenant-039-part-catalog-parity":
        "ссылку на ролик, текстовый блок и автора последней правки позиции",
    "tenant-041-wheel-parity":
        "шинные признаки: шипы, сезон, run-flat, тип протектора, маркировку",
    "tenant-042-wheel-brands": "марку и модель диска",
    "tenant-043-outbox-claim":
        "отметку о взятии события в работу — релей может отправить одно "
        "событие дважды",
    "tenant-044-feed-dirty":
        "очередь отметок об изменении позиций: площадка не узнает о правках, "
        "сделанных до отката",
    "tenant-052-account-product-line":
        "линию товара у выгрузки — колёсный прайс станет обычным",
    "tenant-054-rollback-bridge":
        "ничего: вперёд мост не делает ничего, а его откат возвращает снятое "
        "волной 045–051. Приведённые номера производителя пересчитываются "
        "той же генерируемой колонкой из тех же исходных",
    "tenant-056-deal-item-draft":
        "черновые строки сделки становятся отложенными: набранное в корзину "
        "и не оформленное после отката выглядит обещанным покупателю",
    "tenant-057-feed-column-filters": "отбор выгрузки по колонкам и словам",
    "tenant-058-feed-download-mark":
        "отметку о том, когда площадка последний раз забирала прайс — "
        "на вопрос «уехало ли» отвечать будет нечем",
    "tenant-059-feed-file-name":
        "имя файла прайса: постоянная ссылка, прописанная у площадки, "
        "перестанет работать",
    "tenant-060-inventory-session-note": "комментарии к пересчётам",
    "tenant-061-feed-off-and-deleted":
        "отметку об удалении выгрузки: удалённые прайс-листы вернутся "
        "в список и снова начнут отдаваться площадке. А если название "
        "удалённой уже занято живой, откат упрётся в уникальность",
    "tenant-062-auditor-role":
        "роль «Ревизор» у сотрудников: они станут «Просмотром», доступ "
        "к журналу потеряется и сам обратно не вернётся",
    "tenant-062-audit-log-role":
        "роль автора в журнале изменений — записи останутся без ответа "
        "на «кем он тогда был»",
    "tenant-063-login-session":
        "журнал входов: кто, когда и с какого устройства заходил",
    "tenant-064-part-number":
        "порядковые номера позиций. Повторный накат раздаёт их заново "
        "(row_number по id), и если между откатом и накатом удалена хоть "
        "одна позиция — номера сдвинутся: «посмотри позицию 347» станет "
        "другой деталью",
    "tenant-065-member-settings":
        "память витрины за каждым сотрудником: порядок, отборы, состав "
        "колонок и набранный поиск",
    "tenant-066-company-settings":
        "срок резервирования, заданный владельцем: таблица снимается, "
        "и повторный накат ставит умолчание — три дня. Внешне всё цело, "
        "а настройка клиента молча заменена",
}


# ───────────────────────────── разбор changelog'а ─────────────────────────────

class Changeset:
    def __init__(self, number, path, cid, rollback, mark):
        self.number = number        # порядковый: он же версия схемы
        self.path = path
        self.id = cid
        self.rollback = rollback    # строки --rollback, без приставки
        self.mark = mark            # текст --rollback-теряет, если стоит

    @property
    def risky(self):
        return [l for l in self.rollback if РИСК.search(l)]

    def price(self, registry=None):
        registry = ПОТЕРИ if registry is None else registry
        if self.mark and self.mark.strip():
            return self.mark.strip()
        text = registry.get(self.id)
        return text.strip() if text and text.strip() else None


def includes(manifest):
    tree = ET.parse(os.path.join(CHANGELOG, manifest))
    return [el.get("file") for el in tree.getroot().iter()
            if el.tag.endswith("}include") or el.tag == "include"]


def read(root=CHANGELOG, manifest=MANIFEST):
    """Все changeset'ы набора по порядку наката.

    Порядок — это порядок манифеста, а не имён файлов: `009-views.sql` включён
    последним намеренно. Номер changeset'а в этом порядке и есть версия схемы:
    отметка в реестре арендаторов — «число косая идентификатор»
    (`TenantSchemaMigrator.expectedVersion`), и число там ровно это.
    """
    out = []
    for path in includes(manifest) if root == CHANGELOG else includes_in(root, manifest):
        full = os.path.join(root, path)
        text = open(full, encoding="utf-8").read()
        parts = re.split(r'^(--changeset\s+\S+)', text, flags=re.M)
        for i in range(1, len(parts), 2):
            cid = parts[i].split(":", 1)[1].strip() if ":" in parts[i] else parts[i]
            body = parts[i + 1]
            rollback, mark = [], None
            for line in body.splitlines():
                bare = line.strip()
                if bare.startswith(МЕТКА):
                    mark = bare[len(МЕТКА):].strip()
                elif bare.startswith("--rollback "):
                    rollback.append(bare[len("--rollback"):].strip())
            out.append(Changeset(len(out) + 1, path, cid, rollback, mark))
    return out


def includes_in(root, manifest):
    tree = ET.parse(os.path.join(root, manifest))
    return [el.get("file") for el in tree.getroot().iter()
            if el.tag.endswith("}include") or el.tag == "include"]


def released(paths):
    """Пути, которые уже есть в origin/main, — их пометке внутри взяться неоткуда.

    Без ссылки на origin/main сторож не падает, а считает выпущенным всё:
    требовать пометку внутри выпущенного он права не имеет, а сеть есть
    не всегда.
    """
    code = subprocess.run(["git", "-C", ROOT, "rev-parse", "--verify", "-q",
                           "origin/main"], capture_output=True, text=True).returncode
    if code != 0:
        return set(paths), False
    mb = subprocess.run(["git", "-C", ROOT, "merge-base", "HEAD", "origin/main"],
                        capture_output=True, text=True).stdout.strip()
    out = set()
    for path in paths:
        rel = f"db/changelog/{path}"
        r = subprocess.run(["git", "-C", ROOT, "cat-file", "-e", f"{mb}:{rel}"],
                           capture_output=True)
        if r.returncode == 0:
            out.add(path)
    return out, True


# ──────────────────────────────── сторож ────────────────────────────────

def check(sets, old_paths, registry=None):
    """Что не так с пометками. Пустой список — всё названо."""
    registry = ПОТЕРИ if registry is None else registry
    problems = []
    for cs in sets:
        if not cs.risky:
            if cs.mark is not None:
                problems.append(
                    f"{cs.path}::{cs.id}: пометка «{МЕТКА}» стоит там, где "
                    f"откат ничего не теряет. Либо она лишняя, либо потеря "
                    f"есть, и тогда её надо назвать в самом откате")
            continue
        if cs.mark is not None and not cs.mark.strip():
            problems.append(
                f"{cs.path}::{cs.id}: пометка «{МЕТКА}» пуста. "
                f"Ненаписанная причина — это отказ: пустая пометка станет "
                f"способом отключить проверку, а не разбором")
            continue
        if cs.price(registry):
            if cs.path not in old_paths and cs.mark is None:
                problems.append(
                    f"{cs.path}::{cs.id}: цена отката названа в db/rollback-cost.py, "
                    f"а changeset новый — пометка обязана стоять рядом "
                    f"с `--rollback`, там её и ищет тот, кто её читает")
            continue
        op = РИСК.search(cs.risky[0]).group(1).upper()
        problems.append(
            f"{cs.path}::{cs.id}: откат делает {op}, а чем это обойдётся "
            f"человеку — не сказано. Допишите рядом с `--rollback` строку\n"
            f"      {МЕТКА} что именно теряется и что будет при повторном накате\n"
            f"      Проверка не умеет определять потерю сама — это знает "
            f"только автор changeset'а.")
    return problems


# ─────────────────────────── печать цены отката ───────────────────────────

def parse_version(value, sets):
    """«138», «138/tenant-066-company-settings» или идентификатор changeset'а."""
    value = str(value).strip()
    head = value.split("/")[0]
    if head.isdigit():
        return int(head)
    for cs in sets:
        if cs.id == value:
            return cs.number
    raise SystemExit(f"не разобрал версию «{value}»: нужно число, "
                     f"«число/идентификатор» или идентификатор changeset'а")


def plural(n):
    """«1 changeset», «2 changeset\u0027а», «5 changeset\u0027ов» — счётчик на экране
    человека склоняется, иначе его читают как машинный вывод."""
    tail = "ов"
    if n % 10 == 1 and n % 100 != 11:
        tail = ""
    elif n % 10 in (2, 3, 4) and n % 100 not in (12, 13, 14):
        tail = "а"
    return f"{n} changeset{chr(39)}{tail}" if tail else f"{n} changeset"


def cost(sets, frm, to, out=print):
    """Печатает, что снимет откат с версии frm до версии to."""
    if to > frm:
        raise SystemExit(f"откат идёт вниз: версия «до» ({to}) не может быть "
                         f"больше версии «с» ({frm})")
    steps = [cs for cs in sets if to < cs.number <= frm]
    steps.reverse()                       # откат идёт от новых к старым
    if not steps:
        out(f"Откат с версии {frm} до {to} не снимает ни одного changeset'а.")
        return []
    losing = [cs for cs in steps if cs.risky]
    out(f"Откат с версии {frm} до версии {to}: {plural(len(steps))}, "
        + (f"из них теряют данные {len(losing)}."
           if losing else "и ни один из них не теряет данных."))
    out("")
    unnamed = []
    for cs in steps:
        if not cs.risky:
            continue
        price = cs.price()
        name = f"{cs.path}::{cs.id}"
        if price:
            out(f"  • {name}")
            out(f"      теряется: {price}")
        else:
            unnamed.append(name)
            out(f"  • {name}")
            out(f"      теряется: ЦЕНА НЕ НАЗВАНА — смотрите `--rollback` "
                f"самого changeset'а")
    out("")
    out("Решение об откате остаётся за человеком; неизвестной цены у него "
        "больше нет.")
    out("На ПРОМ откат вниз делается ВОЗВРАТОМ ПАРНОГО СЛЕПКА, а не миграцией "
        "(docs/deployment.md).")
    return unnamed


# ────────────────────────────── самопроверка ──────────────────────────────

def selftest():
    """Проверка самого сторожа: краснеет ли он на дефекте.

    Проверка, которая не краснеет, хуже отсутствующей, и установить это можно
    только попыткой. Здесь она идёт на выдуманном наборе changeset'ов, без базы
    и без Docker: правила все до одного про текст.
    """
    failures = []

    def cs(number, cid, rollback, mark=None, path="tenant/900-x.sql"):
        return Changeset(number, path, cid, rollback, mark)

    old = {"tenant/900-x.sql"}

    # 1. DROP COLUMN без пометки — отказ. Это ровно тот случай, ради которого
    #    проверка написана: откат структурно верен, а данные пропали.
    problems = check([cs(1, "no-mark",
                         ["ALTER TABLE x DROP COLUMN number;"])], old, registry={})
    if not problems:
        failures.append("DROP COLUMN без пометки принят — а это ровно тот "
                        "случай, ради которого проверка написана")
    elif "DROP COLUMN" not in problems[0]:
        failures.append("отказ не называет операцию, из-за которой он вышел")

    # 2. Пометка с причиной — молчит.
    problems = check([cs(1, "marked", ["ALTER TABLE x DROP COLUMN number;"],
                         "порядковые номера: раздаются заново")], old, registry={})
    if problems:
        failures.append("пометка с причиной не принята: " + "; ".join(problems))

    # 3. Пустая пометка — отказ. Иначе она станет способом отключить проверку.
    for empty in ("", "   "):
        problems = check([cs(1, "empty", ["ALTER TABLE x DROP COLUMN number;"],
                             empty)], old, registry={})
        if not problems:
            failures.append(f"пустая пометка («{empty}») принята")

    # 4. Выпущенному changeset'у пометка внутри взяться неоткуда — цену
    #    называет список. Новому так нельзя: пометка обязана стоять рядом
    #    с откатом, там её и читают.
    problems = check([cs(1, "old-one", ["DROP TABLE x;"])], old,
                     registry={"old-one": "весь склад"})
    if problems:
        failures.append("выпущенный changeset с ценой в списке объявлен "
                        "нарушением: " + "; ".join(problems))
    problems = check([cs(1, "new-one", ["DROP TABLE x;"], path="tenant/901-y.sql")],
                     old, registry={"new-one": "весь склад"})
    if not problems:
        failures.append("новый changeset увёл цену в список вместо пометки "
                        "рядом с откатом — и проверка это приняла")

    # 5. Пустая строка в списке — не цена.
    problems = check([cs(1, "old-empty", ["DROP TABLE x;"])], old,
                     registry={"old-empty": "   "})
    if not problems:
        failures.append("пустая строка в списке принята за названную цену")

    # 6. Безобидный откат пометки не требует — и не терпит: сторож, требующий
    #    её везде, приучает писать пометку не думая.
    problems = check([cs(1, "safe", ["DROP INDEX x_ix;"])], old, registry={})
    if problems:
        failures.append("откат без потери объявлен нарушением: "
                        + "; ".join(problems))
    problems = check([cs(1, "extra", ["DROP INDEX x_ix;"], "что-то")], old,
                     registry={})
    if not problems:
        failures.append("пометка на откате, который ничего не теряет, принята")

    # 7. Печать цены называет то, что теряется, и не называет чужого.
    sets = [cs(1, "a", ["DROP TABLE a;"]), cs(2, "b", ["DROP TABLE b;"]),
            cs(3, "c", ["DROP INDEX c_ix;"])]
    for s, text in ((sets[0], "склад"), (sets[1], "кассу")):
        s.mark = text
    lines = []
    cost(sets, 3, 1, out=lines.append)
    text = "\n".join(lines)
    if "кассу" not in text:
        failures.append("цена отката не названа: changeset в промежутке есть, "
                        "а чем он обойдётся — не сказано")
    if "склад" in text:
        failures.append("напечатана цена changeset'а, которого откат "
                        "не касается: человек примет решение по чужой цене")

    # 8. Снятая пометка — цена пропадает из печати. Это и есть доказательство
    #    откатом, встроенное в самопроверку.
    sets[1].mark = None
    lines = []
    unnamed = cost(sets, 3, 1, out=lines.append)
    if "кассу" in "\n".join(lines):
        failures.append("пометку сняли, а цена всё ещё печатается — значит "
                        "печатается не она")
    if not unnamed:
        failures.append("пометку сняли, и печать промолчала о том, что цена "
                        "не названа: молчание читается как «терять нечего»")

    return failures


# ───────────────────────────────── main ─────────────────────────────────

def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--from", dest="frm", help="версия, с которой откатывают")
    ap.add_argument("--to", help="версия, до которой откатывают")
    ap.add_argument("--count", type=int, help="на сколько changeset'ов вниз")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        broken = selftest()
        if broken:
            print("Самопроверка не прошла:\n")
            for b in broken:
                print("  •", b)
            print("\nСторож, который не краснеет на дефекте, хуже "
                  "отсутствующего.")
            return 1
        print("Самопроверка пройдена: DROP COLUMN без пометки отбит, пометка "
              "с причиной принята,\nпустая — нет, у нового changeset'а цена "
              "обязана стоять рядом с откатом,\nа снятая пометка пропадает "
              "из печати цены.")
        return 0

    sets = read()

    if args.count or args.to or args.frm:
        frm = parse_version(args.frm, sets) if args.frm else len(sets)
        to = (frm - args.count) if args.count else parse_version(args.to, sets)
        cost(sets, frm, max(to, 0))
        return 0

    old, compared = released({cs.path for cs in sets})
    problems = check(sets, old)
    if problems:
        print("Цена отката названа не везде:\n")
        for p in problems:
            print("  •", p)
        print(f"\nПравило — в db/CLAUDE.md: откат, который теряет данные, "
              f"обязан это объявлять.")
        return 1
    risky = [cs for cs in sets if cs.risky]
    inline = [cs for cs in risky if cs.mark]
    print(f"Схема арендатора: цена отката названа у всех {len(risky)} "
          f"changeset'ов из {len(sets)}, чей откат теряет данные\n"
          f"({len(inline)} пометкой в самом changeset'е, "
          f"{len(risky) - len(inline)} — списком выпущенных).")
    if not compared:
        print("  origin/main недоступен — новые changeset'ы от выпущенных "
              "не отличались, требование\n  «пометка рядом с откатом» "
              "не проверено.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
