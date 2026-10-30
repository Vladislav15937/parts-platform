#!/usr/bin/env python3
"""Самопроверка инструментов разработчика: smoke.sh, checks.sh, dev-up.sh.

    ./tools/smoke.sh --selftest
    ./tools/checks.sh --selftest
    ./tools/dev-up.sh --selftest

Вопрос, на который это отвечает: краснеет ли инструмент на сломанной системе
или печатает «прошёл». Этих трёх нет в CI, второго читателя у их вывода нет,
и зелёный ответ стоит ровно столько, сколько доказана способность дать
красный (tasks/0242, класс 16 из docs/defect-classes.md).

Как устроено. Настоящий скрипт копируется в песочницу (с подменой `/tmp/` на
каталог песочницы, чтобы не затереть файлы живого прогона разработчика) и
запускается против подделанной среды:
  * smoke.sh и checks.sh ходят настоящим curl в поддельный API (поток внутри
    этого процесса), который умеет обрывать соединение и отвечать пустым
    телом; `docker` — рукописная заглушка (база недоступна / есть расхождения);
  * dev-up.sh привязан к портам 8080 и 5173 литералами, поэтому `curl`,
    `docker`, `sleep`, `npm` и `./mvnw` подменены заглушками по сценарию.
Ничего живого не трогается: ни Docker, ни порты, ни сеть, ни /tmp.

Каждый случай сверяет: код выхода, своё сообщение и то, что подвал не
объявил «прошёл». Обратный край — случай «исправная система»: зелёный и
без ✗, иначе инструмент перестанут запускать.

Доказательство самой самопроверки: `TOOLS_SELFTEST_DIR=<каталог с копиями
скриптов>` берёт скрипты оттуда — копия со снятой ловушкой обязана
провалить свои случаи (подделки живут в .scratch/).
"""
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.environ.get("TOOLS_SELFTEST_DIR") or HERE
FILES = {"smoke": "smoke.sh", "checks": "checks.sh", "dev-up": "dev-up.sh"}


# --------------------------------------------------------------------------
# Поддельный API (smoke.sh и checks.sh)
# --------------------------------------------------------------------------
class Fake:
    """Состояние и режим поддельного сервера: меняется между случаями."""
    drop = []      # подстроки пути: соединение обрывается без ответа
    empty = []     # подстроки пути: 200 с пустым телом
    base = ""
    returned = False
    applied = 0


# Проданная (№ 7) в файле не стоит — её нет, как у Bazon (задача 0339);
# в файле одна оставшаяся позиция со словом «В наличии».
FEED = ('<?xml version="1.0" encoding="UTF-8" ?>\n<parts>\n<part>\n<id>8</id>\n'
        "<state>В наличии</state>\n</part>\n</parts>")


def route(method, path, body):
    p, _, q = path.partition("?")
    S = Fake
    if p == "/api/auth/csrf":
        return 200, {}
    if p == "/api/auth/login":
        return 200, {"ok": 1}
    if p == "/api/auth/me":
        return 200, {"companySchema": "t_000999"}
    if p == "/api/organization/warehouses":
        return 200, [{"id": 1}]
    if p == "/api/intake/receipts":
        return 200, {"parts": [{"id": 7}]}
    if p == "/api/customers":
        return 200, {"id": 3}
    if p == "/api/deals":
        return 200, {"id": 9, "items": [{"id": 11, "status": "RESERVED"}]}
    if p == "/api/deals/9":
        return 200, {"id": 9, "status": "ISSUED", "items": [{"id": 11, "number": 7}]}
    if p == "/api/deals/9/payments":
        return (409 if S.returned else 200), {}
    if p == "/api/deals/9/issue":
        return 200, {"status": "ISSUED"}
    if p == "/api/deals/9/returns":
        S.returned = True
        return 200, {"status": "DONE"}
    if p == "/api/customers/3/account":
        return 200, {"balance": 6000.0 if S.returned else 1000.0}
    if p == "/api/parts/stock":
        return 200, {"rows": [{"partId": 7, "qty": 1.0, "qtyAvailable": 0.0}],
                     "total": 3}
    if p == "/api/marketplace-accounts" and method == "POST":
        return 200, {"id": 5}
    if p == "/api/marketplace-accounts":
        return 200, [{"id": 5, "productLine": "PART", "title": "Прогон",
                      "status": "ACTIVE"}]
    if p == "/api/marketplace-accounts/5/feed-url":
        return 200, {"url": S.base + "/feed/5.xml"}
    if p == "/api/marketplace-accounts/filter/count":
        return 200, {"parts": 1}
    if p == "/feed/5.xml":
        return 200, FEED
    if p == "/api/inventory/sessions":
        return 200, {"id": 2}
    if p == "/api/inventory/sessions/2/counts":
        return 200, {}
    if p == "/api/inventory/sessions/2/finish":
        return 200, {}
    if p == "/api/inventory/sessions/2/discrepancies":
        return 200, [{"delta": -1}]
    if p == "/api/inventory/sessions/2/apply":
        S.applied += 1
        return (200, {"adjusted": 1}) if S.applied == 1 else (409, {})
    if p == "/api/parts/catalog/export":
        return 200, "code\na\nb\nc\n"
    if p == "/api/parts/catalog":
        m = re.search(r"page=(\d+)", q)
        rows = [{"code": "a"}, {"code": "b"}, {"code": "c"}]
        return 200, {"total": 3, "rows": rows if not m or m.group(1) == "0" else []}
    if p == "/api/wheels":
        return 200, {"total": 0, "rows": []}
    return 404, {}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _go(self, method):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else b""
        if any(s in self.path for s in Fake.drop):
            return  # закрыть соединение молча: curl ответит «empty reply»
        status, obj = route(method, self.path, body)
        if any(s in self.path for s in Fake.empty):
            data = b""
        elif isinstance(obj, str):
            data = obj.encode()
        else:
            data = json.dumps(obj).encode()
        self.send_response(status)
        if self.path.startswith("/api/auth/csrf"):
            self.send_header("Set-Cookie", "XSRF-TOKEN=tok; Path=/")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._go("GET")

    def do_POST(self):
        self._go("POST")


# --------------------------------------------------------------------------
# Песочница
# --------------------------------------------------------------------------
DOCKER = r"""#!/usr/bin/env bash
# Заглушка docker по сценарию из окружения (SB_*).
S="$SB_STATE"
case "$1 $2" in
  "info "*) exit 0 ;;
  "compose up") [ "${SB_COMPOSE:-ok}" = fail ] && { echo "boom" >&2; exit 1; }; exit 0 ;;
  "compose ps")
    case "$*" in
      *-q*) echo pgid; exit 0 ;;
      *Status*)
        n=$(cat "$S/ps" 2>/dev/null || echo 0); echo $((n+1)) > "$S/ps"
        case "${SB_PG:-ok}" in
          ok) echo "postgres Up 1 minute (healthy)" ;;
          drops) [ "$n" = 0 ] && echo "postgres Up 1 minute (healthy)" || echo "postgres Exited (1)" ;;
          never) echo "postgres Up 1 second (health: starting)" ;;
        esac; exit 0 ;;
      *) echo postgres; exit 0 ;;
    esac ;;
  "exec "*)
    case "${SB_DB:-ok}" in
      down) echo "could not connect" >&2; exit 1 ;;
      diff) echo 2 ;;
      *) echo 0 ;;
    esac; exit 0 ;;
esac
exit 0
"""

SLEEP = "#!/usr/bin/env bash\n/bin/sleep 0.02\n"

# SB_PWA: ok — Vite отдаёт порт; closed — процесс молча умер, порт закрыт;
# crash — умер сразу с причиной в выводе (как «порт занят»); hang — жив,
# но порт так и не открылся. Пишет stdout в лог — так, как его направил подъём.
NPM = r"""#!/usr/bin/env bash
case "${SB_PWA:-ok}" in
  ok) touch "$SB_STATE/pwa" ;;
  crash) echo "error when starting dev server:"
         echo "Error: Port 5173 is already in use (подделка)"; exit 1 ;;
  hang) echo "VITE-HANG: жду и не слушаю порт (подделка)"; exec /bin/sleep 8 ;;
esac
exit 0
"""

# nvm.sh из песочницы: как настоящий, дописывает каталог с npm в PATH.
NVM_SH = 'export PATH="$SB_ROOT/nvmbin:$PATH"\n'

# Без них песочница «без npm» не была бы такой: npm разработчика лежит
# в одном из каталогов PATH рядом с утилитами, которые нужны подъёму.
NODE_TOOLS = ("npm", "npx", "node")

MVNW = ('#!/usr/bin/env bash\ntouch "$SB_STATE/app"\n'
        '[ "${SB_BANNER:-yes}" = yes ] && echo "Started PartsPlatformApplication in 3s"\n'
        'exit 0\n')

# curl для dev-up.sh: порты зашиты литералами, настоящий сюда не годится.
CURL = r"""#!/usr/bin/env bash
S="$SB_STATE"; out=""; fmt=""; url=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift ;; -w) fmt="$2"; shift ;;
    -X|-d|-H|-b|-c|--max-time) shift ;;
    http*) url="$1" ;;
  esac; shift
done
code=200; rc=0; body='{}'
# Принята ли общая схема — одно состояние на готовность и на провижининг.
# SB_READY: ok — принята; never — не принята никогда; late — не принята,
# пока готовность не спросили трижды; journals — принята, но ready:false
# из-за журналов (так в разработке всегда, когда компания уже есть:
# приложение ходит в базу владельцем схем).
n=$(cat "$S/ready" 2>/dev/null || echo 0)
cat_ok=true
case "${SB_READY:-ok}" in
  never) cat_ok=false ;;
  late) [ "$n" -lt 3 ] && cat_ok=false ;;
esac
case "$url" in
  *:8080/api/auth/csrf) [ -e "$S/app" ] || rc=7 ;;
  *:8080/api/auth/login) code="${SB_LOGIN:-401}" ;;
  *:8080/api/provisioning/tenants)
    # Вслух, в stderr: случай «схема не накатана» проверяет, что до заведения
    # компании подъём не дошёл вовсе, а не только что он не напечатал «Готово».
    echo "ПОДДЕЛКА-CURL: POST /api/provisioning/tenants" >&2
    # Как настоящее приложение: в окне наката провижининг падает пятисоткой.
    code="${SB_PROV:-201}"; [ "$cat_ok" = true ] || code=500 ;;
  *:8080/actuator/readiness)
    echo $((n+1)) > "$S/ready"
    ready=true
    [ "${SB_READY:-ok}" = journals ] && ready=false
    [ "$cat_ok" = false ] && ready=false
    [ "$ready" = true ] || code=503
    body='{"ready":'$ready',"checks":[{"check":"database","ok":true,"detail":"База отвечает","known":true},{"check":"catalog","ok":'$cat_ok',"detail":"Общая схема catalog принята не целиком (подделка)","known":true}]}' ;;
  *:5173/*) [ -e "$S/pwa" ] || rc=7 ;;
esac
[ $rc -ne 0 ] && exit $rc
if [ -n "$out" ]; then
  printf '%s' "$body" > "$out"
elif [ "$body" != '{}' ]; then
  printf '%s' "$body"   # тело в stdout — только у адреса, который его отдаёт
fi
[ -n "$fmt" ] && printf '%s' "$code"
exit 0
"""


def write_exe(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    os.chmod(path, 0o755)


def no_node_path(root, path):
    """PATH без npm/npx/node: каталог ссылок на всё остальное из PATH.

    Убрать каталоги целиком нельзя — npm бывает и в /usr/bin, рядом с cat
    и grep, без которых подъём не дойдёт до шага 5."""
    sysbin = root + "/sysbin"
    os.makedirs(sysbin)
    for d in path.split(os.pathsep):
        if not os.path.isdir(d):
            continue
        for name in os.listdir(d):
            src, dst = os.path.join(d, name), os.path.join(sysbin, name)
            if name in NODE_TOOLS or os.path.lexists(dst):
                continue
            if os.path.isfile(src) and os.access(src, os.X_OK):
                os.symlink(src, dst)
    return sysbin


def build(tool, env_extra=None):
    env_extra = env_extra or {}
    root = tempfile.mkdtemp(prefix="tools-selftest-")
    os.makedirs(root + "/tools")
    os.makedirs(root + "/tmp")
    os.makedirs(root + "/state")
    os.makedirs(root + "/bin")
    os.makedirs(root + "/frontend")
    for name in (FILES[tool], "api.sh"):
        src = os.path.join(TOOLS, name)
        if not os.path.exists(src):
            src = os.path.join(HERE, name)
        text = open(src, encoding="utf-8").read().replace("/tmp/", root + "/tmp/")
        dst = root + "/tools/" + name
        with open(dst, "w", encoding="utf-8") as f:
            f.write(text)
        os.chmod(dst, 0o755)
    write_exe(root + "/bin/docker", DOCKER)
    write_exe(root + "/bin/sleep", SLEEP)
    if tool == "dev-up":
        write_exe(root + "/bin/curl", CURL)
        write_exe(root + "/mvnw", MVNW)
        # SB_NPM: path — npm в PATH; nvm — только через $NVM_DIR/nvm.sh;
        # none — нет нигде (tasks/0385).
        where = env_extra.get("SB_NPM", "path")
        if where == "path":
            write_exe(root + "/bin/npm", NPM)
        elif where == "nvm":
            os.makedirs(root + "/nvmbin")
            write_exe(root + "/nvmbin/npm", NPM)
            os.makedirs(root + "/nvm")
            write_exe(root + "/nvm/nvm.sh", NVM_SH)
    return root


def run(tool, env_extra, timeout=120):
    root = build(tool, env_extra)
    env = dict(os.environ)
    env.update(env_extra)
    path = env["PATH"]
    if tool == "dev-up":
        # nvm разработчика песочницу не касается: свой каталог, пустой,
        # пока случай не положит туда nvm.sh.
        env["NVM_DIR"] = root + "/nvm"
        env["SB_ROOT"] = root
        if env_extra.get("SB_NPM", "path") != "path":
            path = no_node_path(root, path)
    env["PATH"] = root + "/bin:" + path
    env["SB_STATE"] = root + "/state"
    # Пароль провижининга и компания — как у настоящих запусков.
    env["API_JAR"] = root + "/tmp/jar.txt"
    if tool != "dev-up":
        env["API_BASE"] = Fake.base
    try:
        r = subprocess.run(["bash", root + "/tools/" + FILES[tool]],
                           cwd=root, env=env, capture_output=True, text=True,
                           timeout=timeout)
        return r.returncode, r.stdout + r.stderr
    finally:
        shutil.rmtree(root, ignore_errors=True)


# --------------------------------------------------------------------------
# Случаи
# --------------------------------------------------------------------------
ABORT = "проверка оборвалась на строке"


def case(name, tool, *, env=None, drop=(), empty=(), exit_ok, must=(), must_not=(),
         section=None, exit_code=None):
    return dict(name=name, tool=tool, env=env or {}, drop=list(drop),
                empty=list(empty), exit_ok=exit_ok, must=list(must),
                must_not=list(must_not), section=section, exit_code=exit_code)


SMOKE_OK = "Сквозной прогон прошёл."
CASES = [
    # smoke.sh
    case("smoke: исправная система проходит без ✗", "smoke", exit_ok=True,
         must=[SMOKE_OK], must_not=["✗", ABORT]),
    case("smoke: обрыв curl посреди раздела 5 объявлен непосчитанным", "smoke",
         drop=["/inventory/sessions/2/finish"], exit_ok=False, must=[ABORT],
         must_not=[SMOKE_OK], section="5. Пересчёт"),
    case("smoke: пустой ответ там, где ждали данные (раздел 2)", "smoke",
         empty=["/api/customers"], exit_ok=False, must=[ABORT],
         must_not=[SMOKE_OK], section="2. Продажа"),
    case("smoke: база недоступна (раздел 6)", "smoke", env={"SB_DB": "down"},
         exit_ok=False, must=[ABORT], must_not=[SMOKE_OK], section="6. Сверки"),
    case("smoke: расхождение в данных — своё сообщение, не обрыв", "smoke",
         env={"SB_DB": "diff"}, exit_ok=False, must=["2 расхождений"],
         must_not=[SMOKE_OK, ABORT]),
    # checks.sh
    case("checks: исправная система проходит без ✗", "checks", exit_ok=True,
         must=["Всё сходится."], must_not=["✗", ABORT]),
    case("checks: обрыв curl в разделе 2", "checks", drop=["/api/wheels"],
         exit_ok=False, must=[ABORT], must_not=["Всё сходится."],
         section="2. Владелец"),
    case("checks: пустой ответ на список выгрузок (раздел 3)", "checks",
         empty=["/api/marketplace-accounts"], exit_ok=False, must=[ABORT],
         must_not=["Всё сходится."], section="3. Счётчик"),
    case("checks: база недоступна (раздел 1)", "checks", env={"SB_DB": "down"},
         exit_ok=False, must=[ABORT], must_not=["Всё сходится."],
         section="1. Расхождения"),
    case("checks: расхождение в данных — своё сообщение, не обрыв", "checks",
         env={"SB_DB": "diff"}, exit_ok=False, must=["2 расхождений"],
         must_not=["Всё сходится.", ABORT]),
    # dev-up.sh
    case("dev-up: исправный подъём доходит до «Готово»", "dev-up", exit_ok=True,
         must=["Готово.", "провижининг ответил 201"],
         must_not=["не поднял", "не стал healthy"]),
    case("dev-up: docker compose up не прошёл", "dev-up",
         env={"SB_COMPOSE": "fail"}, exit_ok=False,
         must=["docker compose up не прошёл"], must_not=["Готово."]),
    case("dev-up: Postgres погас между циклом ожидания и перепроверкой", "dev-up",
         env={"SB_PG": "drops"}, exit_ok=False,
         must=["postgres не стал healthy"], must_not=["Готово."]),
    case("dev-up: Postgres так и не стал healthy", "dev-up",
         env={"SB_PG": "never"}, exit_ok=False,
         must=["postgres не стал healthy"], must_not=["Готово."]),
    case("dev-up: в логе приложения нет «Started PartsPlatformApplication»",
         "dev-up", env={"SB_BANNER": "no"}, exit_ok=False,
         must=["не поднялось, смотрите"], must_not=["Готово."]),
    # Окно «порт отвечает и «Started» в логе, а общая схема ещё накатывается»
    # настоящее: CatalogMigrations — ApplicationRunner, он работает после
    # строки «Started». На чистом томе провижининг в этом окне отвечал 500
    # (tasks/0381). Ждать надо проверку catalog, а не ready: в разработке
    # ready:false навсегда, как только компания есть (журналы правятся
    # владельцем схем), — ожидание ready повесило бы каждый второй подъём.
    case("dev-up: общая схема не накатана, приложение уже отвечает", "dev-up",
         env={"SB_READY": "never"}, exit_ok=False,
         must=["общая схема так и не накатилась"],
         must_not=["Готово.", "провижининг ответил", "ПОДДЕЛКА-CURL: POST"]),
    case("dev-up: общая схема накатилась с опозданием — подъём её дождался",
         "dev-up", env={"SB_READY": "late"}, exit_ok=True,
         must=["общая схема накатана", "провижининг ответил 201", "Готово."],
         must_not=["так и не накатилась"]),
    case("dev-up: схема накатана, ready:false из-за журналов — подъём не ждёт",
         "dev-up", env={"SB_READY": "journals"}, exit_ok=True,
         must=["общая схема накатана", "провижининг ответил 201", "Готово."],
         must_not=["так и не накатилась"]),
    case("dev-up: провижининг ответил 500", "dev-up", env={"SB_PROV": "500"},
         exit_ok=False, must=["провижининг ответил 500"], must_not=["Готово."]),
    case("dev-up: порт PWA закрыт", "dev-up", env={"SB_PWA": "closed"},
         exit_ok=False, must=["Vite не поднялся"], must_not=["Готово."]),
    # tasks/0385. Компания заведена с другим паролем: вход 401, провижининг
    # 409. Подъём не валится молча, а говорит три выхода и отдельный код 3 —
    # «подсказали» не то же, что «провижининг упал» (выход 1).
    case("dev-up: компания есть, пароль другой — подсказка и код 3", "dev-up",
         env={"SB_PROV": "409"}, exit_ok=False, exit_code=3,
         must=["провижининг ответил 409", "уже заведена, но вход",
               "OWNER_PASSWORD=", "COMPANY=proba2 ./tools/dev-up.sh",
               "docker compose down -v"],
         must_not=["Готово.", "запускаю Vite"]),
    case("dev-up: компания есть, вход подходит — провижининг не зовётся",
         "dev-up", env={"SB_LOGIN": "200"}, exit_ok=True,
         must=["уже заведена, вход работает", "Готово."],
         must_not=["провижининг ответил", "ПОДДЕЛКА-CURL: POST"]),
    case("dev-up: npm только через nvm — подъём его находит", "dev-up",
         env={"SB_NPM": "nvm"}, exit_ok=True, must=["Готово."],
         must_not=["npm не найден", "Vite не поднялся"]),
    case("dev-up: npm нет нигде — сразу «npm не найден», Vite не запускается",
         "dev-up", env={"SB_NPM": "none"}, exit_ok=False,
         must=["npm не найден", "cd frontend && npm run dev"],
         must_not=["Готово.", "запускаю Vite", "Vite не поднялся"]),
    case("dev-up: Vite упал сразу — причина из лога в сообщении", "dev-up",
         env={"SB_PWA": "crash"}, exit_ok=False,
         must=["процесс завершился сразу", "Port 5173 is already in use"],
         must_not=["Готово.", "за 30 с"]),
    case("dev-up: Vite жив, порт не открылся — хвост лога в сообщении",
         "dev-up", env={"SB_PWA": "hang"}, exit_ok=False,
         must=["Vite не поднялся за 30 с", "VITE-HANG"],
         must_not=["Готово.", "завершился сразу"]),
]


def check(c, code, out):
    why = []
    if c["exit_ok"] and code != 0:
        why.append("ожидался выход 0, получен %d" % code)
    if not c["exit_ok"] and code == 0:
        why.append("ожидался ненулевой выход, получен 0 (ложное зелёное)")
    if c["exit_code"] is not None and code != c["exit_code"]:
        why.append("ожидался выход %d, получен %d" % (c["exit_code"], code))
    for s in c["must"]:
        if s not in out:
            why.append("нет сообщения «%s»" % s)
    for s in c["must_not"]:
        if s in out:
            why.append("есть лишнее «%s»" % s)
    if c["section"]:
        i = out.rfind(c["section"])
        if i < 0:
            why.append("нет раздела «%s»" % c["section"])
        elif ABORT not in out[i:]:
            why.append("обрыв объявлен не после раздела «%s»" % c["section"])
    return why


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    tools = args or list(FILES)
    for t in tools:
        if t not in FILES:
            sys.exit("неизвестный инструмент: %s (есть %s)" % (t, ", ".join(FILES)))
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    Fake.base = "http://127.0.0.1:%d" % srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    failed = 0
    for c in CASES:
        if c["tool"] not in tools:
            continue
        Fake.drop, Fake.empty = c["drop"], c["empty"]
        Fake.returned, Fake.applied = False, 0
        code, out = run(c["tool"], c["env"])
        why = check(c, code, out)
        if why:
            failed += 1
            print("  ✗ %s: %s" % (c["name"], "; ".join(why)))
            print("    --- вывод ---")
            for line in out.strip().splitlines()[-12:]:
                print("    " + line)
        else:
            print("  ✓ %s" % c["name"])
    srv.shutdown()
    print()
    print("Самопроверка прошла." if not failed else
          "Самопроверка провалена: %d случаев." % failed)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
