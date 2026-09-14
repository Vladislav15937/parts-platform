#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Эталонный набор стенда ИФТ: разложить, вернуть как было, проверить пригодность.

  ./tools/ift-etalon.py разложить  [--адрес URL]   компания с известным набором
  ./tools/ift-etalon.py вернуть    [--адрес URL]   снести её и разложить заново
  ./tools/ift-etalon.py проверить  [--адрес URL] [--снимок ФАЙЛ]
  ./tools/ift-etalon.py сценарии   [--адрес URL]   три сценария walkthrough по набору
  ./tools/ift-etalon.py --selftest                 правило «это ИФТ» без сети

Задача 0076. Постоянный стенд тестировщика без двух вещей бесполезен: без
входов под все шесть ролей и без способа вернуть его в известное состояние.
Через неделю это склад, заваленный следами проверок, и про дефект нельзя
сказать, воспроизводится он или так и было.

**Набор раскладывается через API, а не вставками в базу.** Набор, разложенный
SQL, проверяет базу, а не приложение: приёмка, резерв, оплата и перевозка идут
теми же путями, что у человека, с их проверками и журналами.

**Набор обязан быть пригоден для проверки, а не только для показа.** Показ
требует красивого, проверка — краевого: розница на «Частном лице», позиция
без снимков, позиция на двух складах, частично оплаченная сделка, просроченный
резерв, нераспознанное написание. Ровно эти случаи в проекте ломались чаще
всего. Они разложены отдельной функцией (`edges`), и `проверить` называет
каждый по имени: снятый краевой случай валит проверку, а сценарии при этом
проходят — так и доказывается, что проверка не декоративная. Набор, который
сполз в «красиво для показа», краснеет здесь, а не у тестировщика.

**Пароли набора — не секрет, и поэтому скрипт отказывается работать не на ИФТ.**
Стенд ИФТ не несёт данных клиента, а тестировщик, ищущий входы в переписке,
теряет полдня. Обратная сторона: этим скриптом нельзя пользоваться на ПСИ
и ПРОМ. Отказ идёт по имени хоста, до единого запроса: ИФТ — это хост с меткой
`ift` (`ift.example.ru`, `parts-ift.example.ru`) либо локальный подъём
(`localhost`). Переопределить это переменной нельзя намеренно: переключатель
«на всякий случай» и есть способ однажды разложить входы с паролями
из репозитория на ПРОМ.

**Второй запуск «разложить» не накладывает набор поверх** — отказывается
словами и называет «вернуть». Два набора в одной компании — уже не известное
состояние.

**«Вернуть как было» — это снос арендатора и раскладка заново, а не откат
базы.** Правило «откатывается только приложение, база чинится вперёд»
(задача 0112) про выкладку версии и схему; здесь схема та же, что стоит,
а сносятся данные одной эталонной компании. С правилом это не пересекается.

Снос идёт в базу напрямую (`COMPOSE ... exec postgres psql`): удаления
арендатора в приложении нет и заводить его ради стенда в боевой сборке
не стали. Сносится только компания с кодом `etalon` **и** названием
«Эталон ИФТ» — компания с тем же кодом, но другим названием набором
не считается и не трогается, — и только если приложение по адресу видит
ту же схему, что база, куда смотрит COMPOSE: иначе можно снести локальную
компанию, а раскладывать на стенде.

Переменные:
  APP_PROVISIONING_TOKEN  секрет провижининга; локально по умолчанию local-dev-token
  COMPOSE                 как дотянуться до базы стенда (по умолчанию «docker compose»)
  DB_USER                 владелец схем (по умолчанию app)
"""

import argparse
import datetime
import email.utils
import hashlib
import http.cookiejar
import json
import os
import re
import shlex
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib

# ---------------------------------------------------------------- набор

COMPANY_CODE = "etalon"
COMPANY_NAME = "Эталон ИФТ"
RETAIL = "Частное лицо"  # shared/RetailCustomer.NAME

# Входы под все шесть ролей. Пароли латиницей: тестировщик набирает их
# на разных раскладках, и «проба-владелец» на английской — это полминуты
# на каждый вход.
OWNER = ("OWNER", "vladelec", "etalon-vladelec", "Ольга Владельцева")
MEMBERS = [
    ("MANAGER", "menedzher", "etalon-menedzher", "Мария Менеджерова"),
    ("SELLER", "prodavec", "etalon-prodavec", "Пётр Продавцов"),
    ("STOREKEEPER", "kladovshik", "etalon-kladovshik", "Константин Кладовщиков"),
    ("VIEWER", "prosmotr", "etalon-prosmotr", "Вера Смотрова"),
    ("AUDITOR", "revizor", "etalon-revizor", "Роман Ревизоров"),
]
EVERYONE = [OWNER] + MEMBERS

MAIN_WAREHOUSE = "Основной"  # заводит провижининг
SECOND_WAREHOUSE = "Второй склад"
CELLS = {
    MAIN_WAREHOUSE: ["А-01-1", "А-01-2", "А-02-1", "А-02-2"],
    SECOND_WAREHOUSE: ["В-01-1", "В-01-2"],
}
PAYMENT_SOURCES = [("Наличные", "CASH"), ("Карта", "ACQUIRING"),
                   ("Расчётный счёт", "BANK_ACCOUNT")]

# Позиции, которые покрывают сценарии: все со снимком и все с распознанным
# написанием — иначе краевой случай «нераспознанное» или «без снимков»
# оказался бы в наборе нечаянно, и снятый edges() его бы не снимал.
#
# Написание — название вида детали из справочника, а сторона — отдельным
# полем: «фара левая» справочник не узнаёт, сторону приёмщик выбирает
# кнопкой, и заголовок собирает её сам («Фара … перед. лев.»).
CORE_PARTS = [
    # метка, написание, цена, себестоимость, ячейка, слева/справа, спереди/сзади
    ("фара левая", "Фара", 12000, 3000, "А-01-1", "LEFT", "FRONT"),
    ("фара правая", "Фара", 12000, 3000, "А-01-1", "RIGHT", "FRONT"),
    ("бампер передний", "Бампер", 9000, 2000, "А-01-2", None, "FRONT"),
    ("зеркало левое", "Зеркало наружное", 4500, 800, "А-01-2", "LEFT", None),
    ("дверь передняя левая", "Дверь", 15000, 4000, "А-02-1", "LEFT", "FRONT"),
    ("стартер", "Стартер", 6000, 1500, "А-02-1", None, None),
    ("генератор", "Генератор", 7000, 1800, "А-02-2", None, None),
    ("крышка багажника", "Крышка багажника", 11000, 2500, "А-02-2", None, None),
    ("фонарь задний правый", "Фонарь задний", 3500, 700, "А-02-2", "RIGHT", "REAR"),
]

CUSTOMERS = [
    ("Иван Петров", "+7 914 000-00-01", "PERSON"),
    ("ООО «Автосервис Восток»", "+7 423 000-00-02", "COMPANY"),
]

UNMATCHED_NAME = "хреновина под капотом"

EXIT_FAILED, EXIT_NOT_IFT, EXIT_ALREADY = 1, 2, 3


# ---------------------------------------------------------------- стенд

LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}


def stand_of(url):
    """Куда нацелен скрипт: (название стенда, None) либо (None, отказ словами).

    Решается по имени хоста и только по нему — до единого запроса. Хост
    с меткой `psi`, `prom` или `prod` отбивается, даже если в нём есть `ift`:
    двусмысленное имя — не повод угадывать в сторону ИФТ.
    """
    parts = urllib.parse.urlsplit(url)
    host = (parts.hostname or "").lower()
    if parts.scheme not in ("http", "https") or not host:
        return None, "«%s» — не адрес стенда: нужен http(s)://хост" % url
    if host in LOCAL_HOSTS:
        return "локальный подъём", None
    labels = set(re.split(r"[.\-]", host))
    other = [name for label, name in (("psi", "ПСИ"), ("prom", "ПРОМ"), ("prod", "ПРОМ"))
             if label in labels]
    if other:
        return None, "%s — это %s, а не ИФТ" % (host, other[0])
    if "ift" in labels:
        return "ИФТ", None
    return None, "%s — не ИФТ: в имени хоста нет метки ift" % host


def refuse_not_ift(reason):
    print("Отказ: %s." % reason)
    print("Эталонный набор раскладывается только на стенде ИФТ (хост с меткой ift,")
    print("например ift.example.ru) или на локальном подъёме. Его пароли лежат")
    print("в репозитории, поэтому на ПСИ и ПРОМ этим скриптом пользоваться нельзя.")
    return EXIT_NOT_IFT


# ---------------------------------------------------------------- HTTP

class Refused(Exception):
    pass


class Api:
    """Сессия к API с CSRF — то же, что tools/api.sh, только для питона."""

    def __init__(self, base):
        self.base = base.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))
        self.server_date = None

    def _token(self):
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return cookie.value
        return None

    def call(self, method, path, body=None, expect=(200, 201, 204), headers=None,
             step=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        token = self._token()
        if token:
            request.add_header("X-XSRF-TOKEN", token)
        for name, value in (headers or {}).items():
            request.add_header(name, value)
        try:
            response = self.opener.open(request, timeout=120)
            code, raw, head = response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            code, raw, head = error.code, error.read(), error.headers
        except urllib.error.URLError as error:
            raise Refused("%s %s: стенд не отвечает (%s)" % (method, path, error.reason))
        if head.get("Date"):
            self.server_date = email.utils.parsedate_to_datetime(head["Date"])
        try:
            parsed = json.loads(raw.decode("utf-8")) if raw else None
        except (ValueError, UnicodeDecodeError):
            parsed = raw
        if expect and code not in expect:
            message = parsed.get("message") if isinstance(parsed, dict) else parsed
            raise Refused("%s%s %s ответил %s: %s" % (
                ("«%s»: " % step) if step else "", method, path, code, message))
        return code, parsed

    def get(self, path, **kw):
        return self.call("GET", path, **kw)[1]

    def post(self, path, body=None, **kw):
        return self.call("POST", path, body, **kw)[1]

    def login(self, company, login, password):
        # Токен берётся заново, а не «если его нет»: вчерашний даёт 401,
        # неотличимый от неверного пароля (tools/api.sh).
        self.jar.clear()
        self.call("GET", "/api/auth/csrf", expect=None)
        code, _ = self.call("POST", "/api/auth/login",
                            {"company": company, "login": login, "password": password},
                            expect=None)
        return code

    def server_now(self):
        """Время стенда, а не этой машины: срок резерва сверяет сервер."""
        if self.server_date is None:
            self.call("GET", "/api/auth/csrf", expect=None)
        return self.server_date or datetime.datetime.now(datetime.timezone.utc)


def iso(moment):
    return moment.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def say(text):
    print("  " + text, flush=True)


def logged_in(base, who):
    role, login, password, _ = who
    api = Api(base)
    code = api.login(COMPANY_CODE, login, password)
    if code != 200:
        raise Refused("вход %s (%s) ответил %s" % (login, role, code))
    return api


# ---------------------------------------------------------------- снимки

def png(width, height, rgb):
    """Однотонный PNG средствами стандартной библиотеки: у набора свои снимки,
    и тянуть их из сети или класть бинарники в репозиторий незачем."""
    row = b"\x00" + bytes(rgb) * width
    raw = row * height

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def attach_photo(api, part_id, ordinal):
    rgb = ((ordinal * 67) % 256, (ordinal * 131) % 256, (ordinal * 199) % 256)
    body = png(320, 240, rgb)
    upload = api.post("/api/parts/%d/photos/upload-url" % part_id,
                      {"contentType": "image/png", "requestId": "etalon-photo-%d" % part_id},
                      step="ссылка на снимок")
    put = urllib.request.Request(upload["uploadUrl"], data=body, method="PUT")
    put.add_header("Content-Type", "image/png")
    try:
        urllib.request.urlopen(put, timeout=60).read()
    except urllib.error.URLError as error:
        raise Refused("снимок не залился в хранилище (%s): %s"
                      % (upload["uploadUrl"].split("?")[0], error))
    api.call("POST", "/api/parts/%d/photos/%d/confirm" % (part_id, upload["photoId"]),
             {"width": 320, "height": 240}, step="подтверждение снимка")


# ---------------------------------------------------------------- раскладка

def provision(base, token):
    api = Api(base)
    code, answer = api.call("POST", "/api/provisioning/tenants", {
        "token": token, "companyCode": COMPANY_CODE, "companyName": COMPANY_NAME,
        "ownerLogin": OWNER[1], "ownerPassword": OWNER[2], "ownerName": OWNER[3],
    }, expect=None)
    message = answer.get("message", "") if isinstance(answer, dict) else ""
    if code == 409 and "занят" in message:
        return None
    if code not in (200, 201):
        raise Refused("провижининг ответил %s: %s" % (code, message or answer))
    return answer


def find_by_name(items, name, what):
    for item in items:
        if item.get("name") == name:
            return item
    raise Refused("%s «%s» не найден(а) в справочнике" % (what, name))


def receive(api, ctx, items, key):
    receipt = api.post("/api/intake/receipts", {
        "warehouseId": ctx["wh"][MAIN_WAREHOUSE], "supplyId": ctx["supply"],
        "donorId": ctx["donor"], "requestId": "etalon-%s" % key, "items": items,
    }, step="приёмка %s" % key)
    return receipt["parts"]


def part_item(raw, price, cost, cell, lr, fr, ctx, qty=1):
    return {"rawName": raw, "quantity": qty, "price": price, "costPrice": cost,
            "cellId": ctx["cells"][cell], "sideLr": lr, "sideFr": fr,
            "condition": "USED", "qualityGrade": "NO_DEFECTS"}


def core(base, ctx):
    """То, на чём проходят сценарии: без этого тестировщику нечего проверять."""
    owner = ctx["owner"]

    branch = owner.get("/api/organization/branches")[0]["id"]
    owner.post("/api/organization/warehouses", {"name": SECOND_WAREHOUSE, "branchId": branch},
               step="второй склад")
    ctx["wh"] = {w["name"]: w["id"] for w in owner.get("/api/organization/warehouses")}
    ctx["cells"] = {}
    for warehouse, codes in CELLS.items():
        owner.post("/api/organization/warehouses/%d/cells" % ctx["wh"][warehouse],
                   {"codes": codes}, step="ячейки")
        for cell in owner.get("/api/organization/warehouses/%d/cells" % ctx["wh"][warehouse]):
            ctx["cells"][cell["code"]] = cell["id"]
    say("склады и ячейки: %s" % ", ".join("%s (%d)" % (w, len(c)) for w, c in CELLS.items()))

    for role, login, password, name in MEMBERS:
        owner.post("/api/members", {"login": login, "password": password,
                                    "displayName": name, "role": role, "branchId": branch},
                   step="сотрудник %s" % login)
    say("сотрудники: шесть ролей")

    for name, kind in PAYMENT_SOURCES:
        owner.post("/api/payment-sources", {"name": name, "sourceType": kind},
                   step="источник платежа")
    ctx["pay"] = {s["name"]: s["id"] for s in owner.get("/api/payment-sources")}
    # Источники сделок («Дром», «Звонок», «Пришёл сам»…) компания получает
    # при заведении — заводить их второй раз значит получить отказ «уже заведён».
    say("справочники: источники платежей (источники сделок — от заведения компании)")

    brand = find_by_name(owner.get("/api/catalog/brands?q=Toyota"), "Toyota", "марка")
    model = find_by_name(owner.get("/api/catalog/brands/%d/models?q=Camry" % brand["id"]),
                         "Camry", "модель")
    supply = owner.post("/api/intake/supplies", {"kind": "CONTAINER", "number": "ЭТ-001",
                                                 "supplierName": "Аукцион в Японии"},
                        step="поставка")
    owner.post("/api/intake/supplies/%d/arrived" % supply["id"], step="поставка прибыла")
    ctx["supply"] = supply["id"]
    donor = owner.post("/api/intake/donors", {
        "brandId": brand["id"], "modelId": model["id"], "vin": "JTNBE40K003123456",
        "year": 2008, "color": "Серебристый", "bodyCode": "ACV40", "engineCode": "2AZ-FE",
        "mileageKm": 180000, "supplyId": supply["id"],
    }, step="донор")
    ctx["donor"] = donor["id"]
    owner.post("/api/intake/donors/%d/dismantling" % donor["id"], step="донор в разбор")
    for kind, amount, note in (("PURCHASE", 180000, "с аукциона"),
                               ("DELIVERY", 12000, "доставка до склада")):
        owner.post("/api/intake/donors/%d/costs" % donor["id"],
                   {"type": kind, "amount": amount, "note": note}, step="затрата")
    say("поставка ЭТ-001 и донор Toyota Camry 2008 в разборе, затраты 192 000")

    storekeeper = ctx["storekeeper"]
    parts = receive(storekeeper, ctx, [part_item(*p[1:], ctx=ctx) for p in CORE_PARTS], "core")
    unmatched = [p["title"] for p in parts if not p["nameMatched"]]
    if unmatched:
        raise Refused("основные позиции обязаны распознаваться, а не легли на эталон: %s"
                      % ", ".join(unmatched))
    ctx["parts"] = {spec[0]: part["id"] for spec, part in zip(CORE_PARTS, parts)}
    for ordinal, part in enumerate(parts, start=1):
        attach_photo(storekeeper, part["id"], ordinal)
    say("запчасти: %d, у каждой снимок" % len(parts))

    storekeeper.post("/api/wheels/sets", {
        "kind": "TYRE", "warehouseId": ctx["wh"][MAIN_WAREHOUSE], "quantity": 4,
        "diameter": 15, "tyreWidth": 195, "tyreHeight": 65, "season": "SUMMER",
        "wearMm": 6, "madeYear": 2021, "brand": "Goodyear", "model": "EfficientGrip",
        "price": 3000, "costPrice": 1000, "condition": "USED"}, step="комплект шин")
    storekeeper.post("/api/wheels/sets", {
        "kind": "DISC", "warehouseId": ctx["wh"][MAIN_WAREHOUSE], "quantity": 2,
        "diameter": 16, "discWidth": 6.5, "offsetMm": 45, "boltPattern": "5x114.3",
        "hubBore": 60.1, "discBrand": "Toyota", "price": 4000, "costPrice": 1500,
        "condition": "USED"}, step="диски")
    say("колёса: 4 шины и 2 диска")

    ctx["customers"] = {}
    for name, phone, kind in CUSTOMERS:
        created = owner.post("/api/customers", {"name": name, "phone": phone,
                                                "customerType": kind}, step="клиент")
        ctx["customers"][name] = created["id"]
    say("клиенты: %s" % ", ".join(n for n, _, _ in CUSTOMERS))

    seller = ctx["seller"]
    main = ctx["wh"][MAIN_WAREHOUSE]
    far = seller.server_now() + datetime.timedelta(days=365)
    seller.post("/api/deals", {
        "customerId": ctx["customers"]["Иван Петров"], "reservedUntil": iso(far),
        "items": [{"partId": ctx["parts"]["фара левая"], "quantity": 1, "warehouseId": main}],
    }, step="сделка отложенная")
    issued = seller.post("/api/deals", {
        "customerId": ctx["customers"]["ООО «Автосервис Восток»"],
        "items": [{"partId": ctx["parts"]["стартер"], "quantity": 1, "warehouseId": main}],
    }, step="сделка на выдачу")
    seller.post("/api/deals/%d/payments" % issued["id"],
                {"amount": issued["totalAmount"], "paymentSourceId": ctx["pay"]["Наличные"]},
                step="оплата")
    seller.post("/api/deals/%d/issue" % issued["id"], step="выдача")
    say("сделки: отложенная на год вперёд и выданная с оплатой")


def edges(base, ctx):
    """Краевые случаи. Каждый ломался в проекте, и каждый называет `проверить`."""
    edge_retail(ctx)
    edge_without_photos(ctx)
    edge_two_warehouses(ctx)
    edge_partly_paid(ctx)
    edge_unmatched_name(ctx)
    edge_expired_reservation(ctx)


def edge_retail(ctx):
    seller, main = ctx["seller"], ctx["wh"][MAIN_WAREHOUSE]
    deal = seller.post("/api/deals", {  # без customerId — «Частное лицо»
        "items": [{"partId": ctx["parts"]["зеркало левое"], "quantity": 1, "warehouseId": main}],
    }, step="розничная сделка")
    seller.post("/api/deals/%d/payments" % deal["id"],
                {"amount": deal["totalAmount"], "paymentSourceId": ctx["pay"]["Карта"]},
                step="розничная оплата")
    seller.post("/api/deals/%d/issue" % deal["id"], step="розничная выдача")
    say("край: розница на «%s»" % RETAIL)


def edge_without_photos(ctx):
    receive(ctx["storekeeper"], ctx,
            [part_item("Капот", 18000, 4000, "А-01-1", None, "FRONT", ctx)], "no-photo")
    say("край: позиция без снимков (капот)")


def edge_two_warehouses(ctx):
    storekeeper = ctx["storekeeper"]
    part = receive(storekeeper, ctx,
                   [part_item("Радиатор охлаждения", 5500, 1200, "А-02-1", None, None, ctx, qty=2)],
                   "two-warehouses")[0]
    attach_photo(storekeeper, part["id"], 20)
    storekeeper.post("/api/stock/moves", {
        "fromWarehouseId": ctx["wh"][MAIN_WAREHOUSE],
        "toWarehouseId": ctx["wh"][SECOND_WAREHOUSE],
        "items": [{"partId": part["id"], "quantity": 1, "toCellId": ctx["cells"]["В-01-1"]}],
        "note": "эталон: одна из двух на второй склад",
    }, step="перевозка")
    say("край: радиатор лежит на двух складах")


def edge_partly_paid(ctx):
    seller, main = ctx["seller"], ctx["wh"][MAIN_WAREHOUSE]
    deal = seller.post("/api/deals", {
        "customerId": ctx["customers"]["ООО «Автосервис Восток»"],
        "items": [{"partId": ctx["parts"]["дверь передняя левая"], "quantity": 1,
                   "warehouseId": main}],
    }, step="сделка с частичной оплатой")
    seller.post("/api/deals/%d/payments" % deal["id"],
                {"amount": 5000, "paymentSourceId": ctx["pay"]["Наличные"]},
                step="частичная оплата")
    say("край: частично оплаченная сделка (5 000 из 15 000)")


def edge_unmatched_name(ctx):
    part = receive(ctx["storekeeper"], ctx,
                   [part_item(UNMATCHED_NAME, 1500, 300, "А-02-2", None, None, ctx)],
                   "unmatched")[0]
    if part["nameMatched"]:
        raise Refused("«%s» легло на эталон — нужен другой край" % UNMATCHED_NAME)
    attach_photo(ctx["storekeeper"], part["id"], 30)
    say("край: нераспознанное написание «%s»" % UNMATCHED_NAME)


def edge_expired_reservation(ctx):
    """Срок резерва в прошлом через API не поставить (сервер требует будущее),
    поэтому резерв заводится на несколько секунд и доживает до истечения —
    тем же путём, каким истекает у человека. Ждём состояния, а не часов."""
    seller, main = ctx["seller"], ctx["wh"][MAIN_WAREHOUSE]
    until = seller.server_now() + datetime.timedelta(seconds=5)
    seller.post("/api/deals", {
        "customerId": ctx["customers"]["Иван Петров"], "reservedUntil": iso(until),
        "items": [{"partId": ctx["parts"]["бампер передний"], "quantity": 1,
                   "warehouseId": main}],
    }, step="сделка с коротким резервом")
    deadline = time.time() + 60
    while time.time() < deadline:
        if board_count(seller, "EXPIRED") > 0:
            say("край: просроченный резерв (бампер)")
            return
        time.sleep(1)
    raise Refused("резерв не истёк за минуту — часы стенда или доска сделок")


def export(base, ctx):
    """Выгрузка — последней: её прайс обязан видеть весь склад набора."""
    owner = ctx["owner"]
    account = owner.post("/api/marketplace-accounts", {
        "marketplace": "DROM", "title": "Дром — весь склад", "productLine": "PART"},
        step="выгрузка на Дром")
    url = owner.post("/api/marketplace-accounts/%d/feed-url" % account["id"],
                     step="постоянная ссылка")["url"]
    offers = offers_in(url)
    if offers == 0:
        raise Refused("прайс Дрома пуст: %s" % url)
    say("выгрузка на Дром: %d объявлений по постоянной ссылке" % offers)


def offers_in(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read().decode("utf-8").count("<offer>")


def seed(base, token):
    print("Раскладываю эталонный набор: компания %s" % COMPANY_CODE)
    if provision(base, token) is None:
        print("Отказ: компания «%s» уже разложена на этом стенде." % COMPANY_CODE)
        print("Поверх не раскладываю — два набора в одной компании уже не известное")
        print("состояние. Вернуть как было: ./tools/ift-etalon.py вернуть")
        return EXIT_ALREADY
    # Сотрудники заводятся в core(), а входить ими надо там же — поэтому
    # сессии кладовщика и продавца открываются лениво, при первом запросе.
    ctx = {"owner": logged_in(base, OWNER),
           "storekeeper": LazySession(base, MEMBERS[2]),
           "seller": LazySession(base, MEMBERS[1])}
    try:
        core(base, ctx)
        edges(base, ctx)
        export(base, ctx)
    except Refused as refused:
        print("  ✗ %s" % refused)
        print("Набор разложен не до конца. Вернуть как было: ./tools/ift-etalon.py вернуть")
        return EXIT_FAILED
    return 0


class LazySession:
    def __init__(self, base, who):
        self.base, self.who, self.api = base, who, None

    def __getattr__(self, name):
        if self.api is None:
            self.api = logged_in(self.base, self.who)
        return getattr(self.api, name)


# ---------------------------------------------------------------- снос

def psql(sql):
    compose = shlex.split(os.environ.get("COMPOSE", "docker compose"))
    command = compose + ["exec", "-T", "postgres", "psql", "-U",
                         os.environ.get("DB_USER", "app"), "-d", "parts",
                         "-v", "ON_ERROR_STOP=1", "-qtA", "-F", "\t", "-f", "-"]
    done = subprocess.run(command, input=sql.encode("utf-8"), capture_output=True)
    if done.returncode != 0:
        raise Refused("база стенда не ответила (%s): %s"
                      % (" ".join(command[:len(compose) + 3]),
                         done.stderr.decode("utf-8", "replace").strip()))
    return [line.split("\t") for line in done.stdout.decode("utf-8").splitlines() if line]


def demolish(base):
    """Сносит эталонную компанию. Ничего не нашёл — это не ошибка."""
    rows = psql("SELECT tenant_id, schema_name, company_name, status "
                "FROM public.tenant_registry WHERE code = '%s';" % COMPANY_CODE)
    if not rows:
        say("эталонной компании нет — сносить нечего")
        return
    tenant_id, schema, name, status = rows[0]
    if name != COMPANY_NAME:
        raise Refused("компания с кодом %s называется «%s», а не «%s» — это не эталонный "
                      "набор, сносить не буду" % (COMPANY_CODE, name, COMPANY_NAME))
    if not re.fullmatch(r"t_[0-9]{6,}", schema):
        raise Refused("имя схемы «%s» не похоже на схему арендатора" % schema)

    # Та ли это база, в которой живёт приложение по адресу? Иначе снос уйдёт
    # в одну ячейку, а раскладка — в другую. Спрашиваем приложение, в какую
    # схему оно пускает вход набора: отметка управляющего контура называет
    # только отставшие схемы, и свежий набор в ней не виден вовсе.
    seen = None
    for who in EVERYONE:
        probe = Api(base)
        if probe.login(COMPANY_CODE, who[1], who[2]) == 200:
            seen = probe.get("/api/auth/me").get("companySchema")
            break
    if seen is None:
        raise Refused("ни один вход набора не работает — сверить базу через COMPOSE "
                      "с приложением по адресу нечем, сносить вслепую не буду")
    if seen != schema:
        raise Refused("приложение по адресу %s пускает набор в %s, а база через COMPOSE "
                      "нашла %s — это разные ячейки" % (base, seen, schema))

    # Снимки удаляются через приложение, пока схема жива: иначе они остаются
    # в хранилище без карточек. Тестировщик мог сменить пароль владельца —
    # тогда снимки останутся мусором, а снос всё равно пройдёт.
    owner = Api(base)
    if owner.login(COMPANY_CODE, OWNER[1], OWNER[2]) == 200:
        removed = 0
        page = owner.get("/api/parts/catalog?size=500&missing=true")
        for row in page["rows"]:
            if row.get("photoCount"):
                for photo in owner.get("/api/parts/%d/photos" % row["id"]):
                    owner.call("DELETE", "/api/parts/%d/photos/%d"
                               % (row["id"], photo["photoId"]), expect=None)
                    removed += 1
        say("снимков убрано из хранилища: %d" % removed)
    else:
        say("владелец набора не входит (пароль меняли?) — снимки прежнего набора "
            "останутся в хранилище без карточек")

    # Сессии лежат в общей схеме и ищутся по «схема#сотрудник»: не снятые,
    # они пускали бы вчерашнюю cookie в компанию, которой больше нет.
    psql("SET lock_timeout = '30s';\n"
         "BEGIN;\n"
         "DELETE FROM public.spring_session WHERE principal_name LIKE '%s#%%';\n"
         "DROP SCHEMA %s CASCADE;\n"
         "DELETE FROM public.tenant_registry WHERE tenant_id = %d AND code = '%s';\n"
         "COMMIT;\n" % (schema, schema, int(tenant_id), COMPANY_CODE))
    say("снесена компания %s (%s, была в состоянии %s)" % (COMPANY_CODE, schema, status))


# ---------------------------------------------------------------- проверка

def board_count(api, key):
    for column in api.get("/api/deals/board")["columns"]:
        if column["key"] == key:
            return column["count"]
    raise Refused("на доске сделок нет колонки %s" % key)


def check(base, snapshot_path=None):
    """Пригодность набора: сначала то, без чего не пройти сценарий, потом краевые
    случаи — каждый своей строкой, чтобы снятый назывался по имени."""
    print("Проверяю набор компании %s" % COMPANY_CODE)
    failed = []

    def verdict(name, good, detail=""):
        print("  %s %s%s" % ("✓" if good else "✗", name, (" — " + detail) if detail else ""))
        if not good:
            failed.append(name)

    try:
        sessions = {}
        for who in EVERYONE:
            api = Api(base)
            code = api.login(COMPANY_CODE, who[1], who[2])
            role = api.get("/api/auth/me").get("role") if code == 200 else None
            sessions[who[0]] = api if code == 200 else None
            verdict("вход %s / %s" % (who[1], who[2]), code == 200 and role == who[0],
                    "ответ %s, роль %s" % (code, role) if role != who[0] else who[0])
        owner, seller = sessions["OWNER"], sessions["SELLER"]
        if owner is None or seller is None:
            print("Без владельца и продавца дальше проверять нечем.")
            return EXIT_FAILED

        warehouses = owner.get("/api/organization/warehouses")
        verdict("склады с ячейками", len(warehouses) >= 2 and all(w["cells"] for w in warehouses),
                ", ".join("%s: %d" % (w["name"], w["cells"]) for w in warehouses))
        verdict("источники платежей и сделок",
                bool(owner.get("/api/payment-sources")) and bool(owner.get("/api/deal-sources")))
        catalog = owner.get("/api/parts/catalog?size=500&missing=true")
        rows = catalog["rows"]
        # Список доноров поставку не называет — её видно по позициям: деталь
        # с машины в разборе, пришедшей этой поставкой.
        donors = [d for d in owner.get("/api/intake/donors") if d["status"] == "DISMANTLING"]
        supplies = sorted({r["supply"] for r in rows if r.get("supply")})
        verdict("поставка и донор в разборе", bool(donors) and bool(supplies),
                "доноров в разборе %d, поставки %s" % (len(donors), ", ".join(supplies) or "нет"))
        verdict("запчасти", len(rows) >= len(CORE_PARTS), "позиций %d" % len(rows))
        wheels = owner.get("/api/wheels?size=500")
        verdict("колёса", wheels["total"] >= 6, "колёс %d" % wheels["total"])
        customers = [c for c in owner.get("/api/customers?limit=50") if c["name"] != RETAIL]
        verdict("клиенты", len(customers) >= 2, ", ".join(c["name"] for c in customers))

        deals = deal_rows(seller)
        now = seller.server_now()
        reserved_live = [d for d in deals if d["status"] == "RESERVED"
                         and d.get("reservedUntil")
                         and parse_instant(d["reservedUntil"]) > now]
        verdict("сделка отложенная", bool(reserved_live))
        verdict("сделка выданная", any(d["status"] == "ISSUED" for d in deals))

        accounts = [a for a in owner.get("/api/marketplace-accounts")
                    if a["marketplace"] == "DROM" and a["hasFeed"]]
        offers = 0
        if accounts:
            url = owner.get("/api/marketplace-accounts/%d/feed-url" % accounts[0]["id"])["url"]
            offers = offers_in(url) if url else 0
        verdict("выгрузка на Дром с постоянной ссылкой", offers > 0, "объявлений %d" % offers)

        print("Краевые случаи")
        verdict("розница на «%s»" % RETAIL, any(d.get("customerName") == RETAIL for d in deals))
        verdict("позиция без снимков",
                any(r["photoCount"] == 0 for r in rows) and any(r["photoCount"] for r in rows))
        verdict("позиция на двух складах",
                any(sum(1 for q in (r.get("stock") or {}).values() if q and float(q) > 0) >= 2
                    for r in rows))
        verdict("частично оплаченная сделка", board_count(seller, "PARTLY_PAID") > 0)
        verdict("просроченный резерв", board_count(seller, "EXPIRED") > 0)
        unmatched = owner.get("/api/part-names/unmatched?size=50")
        verdict("нераспознанное написание", unmatched["total"] > 0)

        state = snapshot(owner, seller, warehouses, rows, wheels, deals)
    except Refused as refused:
        print("  ✗ %s" % refused)
        return EXIT_FAILED

    digest = hashlib.sha256(json.dumps(state, ensure_ascii=False, sort_keys=True)
                            .encode("utf-8")).hexdigest()[:16]
    print("Отпечаток состояния: %s" % digest)
    if snapshot_path:
        with open(snapshot_path, "w", encoding="utf-8") as out:
            json.dump(state, out, ensure_ascii=False, sort_keys=True, indent=1)
    if failed:
        print("Набор не пригоден: %s." % "; ".join(failed))
        return EXIT_FAILED
    print("Набор пригоден.")
    return 0


def parse_instant(text):
    return datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))


def deal_rows(api):
    # Без status реестр отдаёт все состояния: воронку задаёт экран.
    return api.get("/api/deals/registry?size=500")["items"]


# Поля, которые при каждой раскладке новые по построению: идентификаторы,
# случайные коды, время и подписанные ссылки. Всё прочее обязано совпасть.
VOLATILE = re.compile(r"(^id$|Id$|Ids$|^code$|Code$|At$|^reservedUntil$|^arrivedOn$"
                      r"|[uU]rl$|^photoKey$|^path$|^token$|^key$|^expires$)")


def clean(value):
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items() if not VOLATILE.search(k)}
    if isinstance(value, list):
        return [clean(v) for v in value]
    return value


def snapshot(owner, seller, warehouses, rows, wheels, deals):
    names = {w["id"]: w["name"] for w in warehouses}
    parts = []
    for row in sorted(rows, key=lambda r: r["number"]):
        stock = {names.get(int(k), k): v for k, v in (row.get("stock") or {}).items()}
        parts.append(dict(clean(row), stock=stock))
    return {
        "сотрудники": sorted((m["login"], m["role"], m["displayName"], m["active"])
                             for m in owner.get("/api/members")),
        "склады": sorted((w["name"], w["cells"]) for w in warehouses),
        "ячейки": sorted((w["name"], c["code"])
                         for w in warehouses
                         for c in owner.get("/api/organization/warehouses/%d/cells" % w["id"])),
        "источники платежей": sorted((s["name"], s.get("sourceType")) for s in
                                     owner.get("/api/payment-sources")),
        "источники сделок": sorted(s["name"] for s in owner.get("/api/deal-sources")),
        "доноры": clean(owner.get("/api/intake/donors")),
        "запчасти": parts,
        "колёса": clean(wheels["rows"]),
        "клиенты": sorted(c["name"] for c in owner.get("/api/customers?limit=50")),
        "сделки": sorted((clean(d) for d in deals), key=lambda d: d.get("number") or 0),
        "доска": [(c["key"], c["count"]) for c in seller.get("/api/deals/board")["columns"]],
        "выгрузки": sorted((a["marketplace"], a["title"], a["status"], a["hasFeed"])
                           for a in owner.get("/api/marketplace-accounts")),
        "нераспознанные": sorted(clean(n) for n in
                                 owner.get("/api/part-names/unmatched?size=50")["items"]),
    }


# ---------------------------------------------------------------- сценарии

def scenarios(base):
    """Три сценария walkthrough по набору — данные берутся только из него.

    Сценарий, которому не хватило данных, — это и есть ответ «набор непригоден
    для сценария». Пишут они в компанию настоящие документы, поэтому после них
    стенд уже не эталонный: вернуть как было."""
    print("Сценарии по набору %s" % COMPANY_CODE)
    failed = []

    def step(name, fn):
        try:
            print("  ✓ %s — %s" % (name, fn()))
        except (Refused, LookupError, ValueError) as error:
            print("  ✗ %s — %s" % (name, error))
            failed.append(name)

    storekeeper = logged_in(base, MEMBERS[2])
    seller = logged_in(base, MEMBERS[1])
    owner = logged_in(base, OWNER)

    def intake():  # walkthrough §4, приёмка с телефона
        warehouses = storekeeper.get("/api/organization/warehouses")
        warehouse = next(w for w in warehouses if w["cells"])
        cell = storekeeper.get("/api/organization/warehouses/%d/cells" % warehouse["id"])[0]
        donor = next(d for d in storekeeper.get("/api/intake/donors")
                     if d["status"] == "DISMANTLING")
        body = {"warehouseId": warehouse["id"], "donorId": donor["id"],
                "requestId": "scenario-%s" % uuid.uuid4(),
                "items": [{"rawName": "фара противотуманная левая", "quantity": 1,
                           "price": 2500, "cellId": cell["id"], "condition": "USED"}]}
        first = storekeeper.post("/api/intake/receipts", body, step="приёмка")["parts"][0]
        again = storekeeper.post("/api/intake/receipts", body, step="повтор приёмки")["parts"][0]
        if again["id"] != first["id"]:
            raise Refused("повтор офлайн-очереди завёл вторую деталь")
        return "«%s» на %s · %s, повтор вернул ту же" % (first["title"], warehouse["name"],
                                                         cell["code"])

    def sale():  # walkthrough §9, продажа с выдачей и оплатой
        query = urllib.parse.quote("фара")
        rows = seller.get("/api/parts/stock?q=%s" % query)["rows"]
        row = next(r for r in rows if float(r["qtyAvailable"]) > 0)
        customer = next(c for c in seller.get("/api/customers?limit=50") if c["name"] != RETAIL)
        source = seller.get("/api/payment-sources")[0]
        deal = seller.post("/api/deals", {"customerId": customer["id"], "items": [{
            "partId": row["partId"], "quantity": 1, "warehouseId": row["warehouseId"]}]},
            step="оформление")
        seller.post("/api/deals/%d/payments" % deal["id"],
                    {"amount": deal["totalAmount"], "paymentSourceId": source["id"]},
                    step="оплата")
        issued = seller.post("/api/deals/%d/issue" % deal["id"], step="выдача")
        if issued["status"] != "ISSUED":
            raise Refused("после выдачи сделка в состоянии %s" % issued["status"])
        return "сделка №%s: %s, %s оплачено «%s», выдана" % (
            issued["number"], customer["name"], issued["totalAmount"], source["name"])

    def feed():  # walkthrough §12, сборка прайса
        account = next(a for a in owner.get("/api/marketplace-accounts")
                       if a["marketplace"] == "DROM" and a["hasFeed"])
        url = owner.get("/api/marketplace-accounts/%d/feed-url" % account["id"])["url"]
        offers = offers_in(url)
        if offers == 0:
            raise Refused("прайс пуст")
        return "«%s»: %d объявлений" % (account["title"], offers)

    step("приёмка с телефона", intake)
    step("продажа с выдачей и оплатой", sale)
    step("сборка прайса", feed)
    if failed:
        print("Данных набора не хватило: %s." % "; ".join(failed))
        return EXIT_FAILED
    print("Сценарии прошли. Стенд больше не эталонный: ./tools/ift-etalon.py вернуть")
    return 0


# ---------------------------------------------------------------- самопроверка

def selftest():
    """Правило «это ИФТ» — единственное, что стоит между паролями из репозитория
    и ПРОМ. Проверка, не краснеющая на дефекте, хуже отсутствующей: здесь каждое
    обязательное «нет» названо отдельной строкой."""
    cases = [
        ("http://localhost:8080", True),
        ("http://127.0.0.1:8080", True),
        ("https://ift.plus-parts.ru", True),
        ("https://parts-ift.example.ru/", True),
        ("https://parts.plus-parts.ru", False),      # пилотная ячейка — ПРОМ
        ("https://psi.plus-parts.ru", False),
        ("https://ift-psi.example.ru", False),       # двусмысленное — не ИФТ
        ("https://prom.example.ru", False),
        ("https://shift.example.ru", False),         # «ift» внутри слова — не метка
        ("https://ift.example.ru.evil.com", True),   # метка есть — правило по имени
        ("ftp://ift.example.ru", False),
        ("ift.example.ru", False),                   # без схемы — не адрес
    ]
    broken = []
    for url, allowed in cases:
        stand, _ = stand_of(url)
        if (stand is not None) != allowed:
            broken.append("%s: ожидалось %s" % (url, "пустить" if allowed else "отказать"))
    return broken


# ---------------------------------------------------------------- вход

COMMANDS = {"разложить": "seed", "seed": "seed", "вернуть": "reset", "reset": "reset",
            "проверить": "check", "check": "check", "сценарии": "scenarios",
            "scenarios": "scenarios"}


def main():
    parser = argparse.ArgumentParser(
        description="Эталонный набор стенда ИФТ (задача 0076)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Входы набора и что в нём лежит — docs/ift-etalon.md")
    parser.add_argument("command", nargs="?", choices=sorted(COMMANDS))
    parser.add_argument("--адрес", "--url", dest="url",
                        default=os.environ.get("ETALON_URL", "http://localhost:8080"))
    parser.add_argument("--снимок", "--snapshot", dest="snapshot",
                        help="сохранить состояние набора без идентификаторов и времени")
    parser.add_argument("--самопроверка", "--selftest", dest="selftest", action="store_true")
    args = parser.parse_args()

    broken = selftest()
    if broken:
        print("Самопроверка правила «это ИФТ» не прошла — работать не буду:")
        for line in broken:
            print("  ✗ " + line)
        return EXIT_FAILED
    if args.selftest:
        print("Самопроверка прошла: правило «это ИФТ» пускает и отказывает, где должно.")
        return 0
    if not args.command:
        parser.print_help()
        return EXIT_FAILED

    stand, reason = stand_of(args.url)
    if stand is None:
        return refuse_not_ift(reason)
    print("Стенд: %s (%s)" % (stand, args.url))

    command = COMMANDS[args.command]
    if command == "check":
        return check(args.url, args.snapshot)
    if command == "scenarios":
        try:
            return scenarios(args.url)
        except Refused as refused:
            print("  ✗ %s" % refused)
            return EXIT_FAILED

    token = os.environ.get("APP_PROVISIONING_TOKEN")
    if not token and stand == "локальный подъём":
        token = "local-dev-token"
    if not token:
        print("Отказ: нужен секрет провижининга стенда — APP_PROVISIONING_TOKEN.")
        return EXIT_FAILED

    if command == "reset":
        print("Возвращаю как было: сношу эталонную компанию и раскладываю заново")
        try:
            demolish(args.url)
        except Refused as refused:
            print("  ✗ %s" % refused)
            return EXIT_FAILED
    code = seed(args.url, token)
    if code != 0:
        return code
    print()
    code = check(args.url, args.snapshot)
    print()
    print("Компания %s · входы:" % COMPANY_CODE)
    for role, login, password, name in EVERYONE:
        print("  %-11s %-11s %s" % (role, login, password))
    return code


if __name__ == "__main__":
    sys.exit(main())
