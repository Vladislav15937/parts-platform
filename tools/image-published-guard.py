#!/usr/bin/env python3
"""На теге лежит ГОДНЫЙ образ — спрошено протоколом, а не «реестр ответил».

    ./tools/image-published-guard.py ghcr.io/owner/repo:<sha>
    ./tools/image-published-guard.py --selftest

Коды возврата, и сводить их к двум нельзя:

    0 — на теге лежит годный образ: публикацию можно пропустить;
    1 — годного образа на теге нет: публиковать (сказано, чего именно нет);
    2 — спросить не удалось: реестр не ответил либо ответил непонятным.

ЗАЧЕМ (задача 0241). Шаг прогона «Этот SHA уже опубликован?» спрашивал
`docker manifest inspect … >/dev/null 2>&1` и считал нулевой код доказательством
того, что образ опубликован: `skip=true`, публикация пропускается. А ноль там
означает ровно одно — на тег ЧТО-ТО ответило. Годность — что это образ,
что он для архитектуры ячейки, что у него есть слои и конфигурация — не
спрашивалась вовсе, хотя у зеркала это делает `tools/mirror-images.sh
--проверить`. Цена: пропущенная публикация выглядит успехом прогона, а
выкладка потом отвечает «no matching manifest» или тянет пустоту — то есть
отказ приходит в минуту, когда за образом пришли.

ПОЧЕМУ ПРОТОКОЛОМ, А НЕ DOCKER'ОМ. `docker manifest inspect` и `docker buildx
imagetools inspect` при containerd-хранилище отвечают из ЛОКАЛЬНОГО индекса:
образ, лежащий в кэше, выглядит у них как лежащий в реестре (замерено
26 сентября 2026, записано в tools/mirror-images.sh и ops/CLAUDE.md). Сегодня
в задаче публикации локального кэша нет — копию образа скачивают ПОСЛЕ этого
шага, — но держаться это должно не на порядке шагов: переставь их, и проверка
начнёт отвечать про нашу машину, молча. Тот же довод у tools/alert-image-guard.py.

ЧЕГО ОН НАМЕРЕННО НЕ ДЕЛАЕТ — не сверяет digest реестра с digest'ом собранного
образа. Задача предлагала это вторым способом, и он ломает смысл самого шага:
сборка не побайтово повторяема (время внутри jar), поэтому повторный прогон
того же SHA даёт ДРУГОЙ digest, сверка объявила бы расхождение и переопубликовала
бы тег — то есть прежний образ, тот, что уже стоит на стенде, остался бы висеть
без имени. Ровно этого шаг и избегает («Тег по SHA неизменен по смыслу»).
Поэтому здесь спрашивается не «тот ли это байт в байт образ», а «годен ли тот,
что лежит»: манифест образа, слои на месте, архитектура — linux/amd64.
"""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

# Архитектура ячейки и раннера. Образ, лежащий на теге под другой архитектурой,
# это не «почти годный»: выкладка на нём не поднимется вовсе.
NEED_OS = "linux"
NEED_ARCH = "amd64"

ACCEPT = ", ".join((
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
))

RED = "\033[1;31m%s\033[0m"
GREEN = "\033[1;32m%s\033[0m"

# Швы для самопроверки: ей нужен реестр, которого нет в сети. Подменяется
# только адрес — ветки ответов, разбор и сообщения остаются теми же, иначе
# проверялся бы не сторож, а его копия.
TOKEN_BASE = os.environ.get("IMAGE_PUBLISHED_TOKEN_BASE", "")
REGISTRY_BASE = os.environ.get("IMAGE_PUBLISHED_REGISTRY_BASE", "")
# «пользователь:токен» для реестра. GHCR отвечает 403 и на приватный пакет,
# и на несуществующий (замер 26 сентября 2026, tools/mirror-images.sh), поэтому
# в прогоне сторож спрашивает с учётной записью: тогда 404 означает «тега нет»,
# а не «нам не показали».
AUTH = os.environ.get("IMAGE_PUBLISHED_AUTH", "")

TIMEOUT = 15
ATTEMPTS = 3

GOOD, ABSENT, UNKNOWN = 0, 1, 2


class Unreachable(RuntimeError):
    """Реестр не ответил вовсе: сеть, DNS, таймаут, TLS.

    Это не «образа нет»: первое чинят повтором прогона или реестром, второе —
    публикацией. Сведение их в один исход и есть дефект, ради которого сторож
    написан.
    """


def red(msg: str) -> None:
    print(RED % msg, file=sys.stderr)


# --- как спрашиваем реестр ----------------------------------------------------

def split_ref(ref: str) -> tuple[str, str, str]:
    """адрес → (хост реестра, путь репозитория, тег)."""
    name, _, tag = ref.rpartition(":")
    if not name or not tag:
        raise ValueError(f"адрес без тега: {ref}")
    head = name.split("/")[0]
    if "." in head or ":" in head or head == "localhost":
        host = head
        path = name.split("/", 1)[1] if "/" in name else ""
    else:
        host = "docker.io"
        path = name if "/" in name else f"library/{name}"
    if not path:
        raise ValueError(f"в адресе нет пути репозитория: {ref}")
    return host, path, tag


def endpoints(host: str, path: str) -> tuple[str, str, str]:
    """Адреса токена, манифестов и блобов. Подменяются самопроверкой целиком."""
    if TOKEN_BASE and REGISTRY_BASE:
        return (f"{TOKEN_BASE}/token?scope=repository:{path}:pull",
                f"{REGISTRY_BASE}/v2/{path}/manifests",
                f"{REGISTRY_BASE}/v2/{path}/blobs")
    if host == "docker.io":
        return (f"https://auth.docker.io/token?service=registry.docker.io"
                f"&scope=repository:{path}:pull",
                f"https://registry-1.docker.io/v2/{path}/manifests",
                f"https://registry-1.docker.io/v2/{path}/blobs")
    return (f"https://{host}/token?service={host}&scope=repository:{path}:pull",
            f"https://{host}/v2/{path}/manifests",
            f"https://{host}/v2/{path}/blobs")


def http(url: str, headers: dict[str, str] | None = None) -> tuple[int, bytes]:
    """Один запрос. Транспортный отказ — это Unreachable, а не код ответа."""
    req = urllib.request.Request(url, headers=headers or {})
    last: Exception | None = None
    for _ in range(ATTEMPTS):
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()
        except Exception as e:  # noqa: BLE001 — сеть, DNS, таймаут, TLS
            last = e
    raise Unreachable(str(last))


def bearer(token_url: str) -> dict[str, str]:
    headers = {"Accept": ACCEPT}
    ask_headers: dict[str, str] = {}
    if AUTH:
        ask_headers["Authorization"] = "Basic " + base64.b64encode(
            AUTH.encode("utf-8")).decode("ascii")
    code, body = http(token_url, ask_headers)
    if code == 200:
        try:
            token = json.loads(body or b"{}").get("token", "")
        except ValueError:
            token = ""
        if token:
            headers["Authorization"] = f"Bearer {token}"
    return headers


# --- годность -----------------------------------------------------------------

def index_arches(doc: dict) -> set:
    """Архитектуры индекса. Слои подписей идут платформой unknown — это не
    архитектура, и считать их за неё значит принять однорукий образ за годный
    (tools/mirror-images.sh)."""
    found = set()
    for m in doc.get("manifests") or []:
        p = m.get("platform") or {}
        arch, os_name = p.get("architecture"), p.get("os")
        if arch and arch != "unknown" and os_name not in (None, "unknown"):
            found.add(f"{os_name}/{arch}")
    return found


def verdict(ref, code, body, blob) -> int:
    """Годен ли образ на теге. Чистая функция — её и гоняет самопроверка."""
    if code == 404:
        print(f"  · на теге образа нет (404): {ref}")
        return ABSENT
    if code in (401, 403):
        # Анонимно у GHCR эти два состояния неразличимы, и врать про них нельзя.
        # В прогоне сторож спрашивает с учётной записью, поэтому здесь это
        # «спросить не удалось», а не «публикуй»: опубликовав поверх закрытого
        # пакета, мы получили бы отказ push'а без объяснения.
        red(f"  ✗ реестр ответил {code} на {ref}: спросить не удалось")
        red("      Пакет закрыт либо учётной записи не хватает прав на чтение —")
        red("      нам не показали. «Опубликован» и «нам не показали» — разные")
        red("      ответы, и выдавать второе за первое нельзя ни в какую сторону.")
        return UNKNOWN
    if code != 200:
        red(f"  ✗ реестр ответил {code} на {ref}: спросить не удалось")
        return UNKNOWN

    try:
        doc = json.loads(body or b"{}")
    except ValueError:
        red(f"  ✗ на теге {ref} лежит не манифест: ответ не разбирается как JSON")
        red("      Реестр ответил 200, и прежняя проверка сочла бы это публикацией.")
        return ABSENT
    if not isinstance(doc, dict):
        red(f"  ✗ на теге {ref} лежит не манифест образа")
        return ABSENT

    if doc.get("manifests") is not None:
        # Индекс: архитектуру видно прямо в нём.
        arches = index_arches(doc)
        print(f"  · индекс, архитектуры: {', '.join(sorted(arches)) or 'ни одной'}")
        if f"{NEED_OS}/{NEED_ARCH}" not in arches:
            red(f"  ✗ в индексе {ref} нет {NEED_OS}/{NEED_ARCH}")
            red("      Это архитектура ячейки и раннера: образ на теге есть,")
            red("      а выкладка на нём не поднимется. Публиковать.")
            return ABSENT
        return GOOD

    # Одиночный манифест — ровно то, что кладёт `docker build` плюс `docker push`.
    # Годность у него двусоставная: слои и конфигурация на месте, и конфигурация
    # называет нашу архитектуру. Второе спрашивается у блоба конфигурации:
    # в самом манифесте платформы нет вовсе, и «манифест есть» про архитектуру
    # не говорит ничего.
    config = (doc.get("config") or {}).get("digest")
    layers = doc.get("layers") or []
    if not config or not layers:
        red(f"  ✗ на теге {ref} лежит не образ: конфигурация "
            f"{'есть' if config else 'отсутствует'}, слоёв {len(layers)}")
        red("      Так выглядит тег, на котором остался след неудавшейся")
        red("      публикации или одни подписи. Реестр отвечает 200, образа нет.")
        return ABSENT

    try:
        bcode, bbody = blob(config)
    except Unreachable as e:
        red(f"  ✗ конфигурацию образа не прочитать ({e}): спросить не удалось")
        return UNKNOWN
    if bcode != 200:
        red(f"  ✗ реестр ответил {bcode} на конфигурацию образа {config[:19]}…")
        red("      Манифест есть, а конфигурации по нему нет — такой образ")
        red("      не вытянет ни ячейка, ни стенд.")
        return ABSENT
    try:
        cfg = json.loads(bbody or b"{}")
    except ValueError:
        red("  ✗ конфигурация образа не разбирается как JSON")
        return ABSENT
    got = f"{cfg.get('os')}/{cfg.get('architecture')}"
    print(f"  · одиночный манифест, слоёв {len(layers)}, платформа {got}")
    if got != f"{NEED_OS}/{NEED_ARCH}":
        red(f"  ✗ образ на теге {ref} собран под {got}, "
            f"а нужен {NEED_OS}/{NEED_ARCH}")
        red("      Это архитектура ячейки и раннера. Публиковать.")
        return ABSENT
    return GOOD


def ask(ref: str) -> int:
    host, path, tag = split_ref(ref)
    token_url, manifests_url, blobs_url = endpoints(host, path)
    try:
        headers = bearer(token_url)
        code, body = http(f"{manifests_url}/{tag}", headers)
    except Unreachable as e:
        red(f"  ✗ реестр не ответил про {ref}: {e}")
        red("      Это «спросить не удалось», а не «публикуй» и не «пропусти»:")
        red("      повторите прогон — не воспроизвелось, значит реестр молчал")
        red("      в ту минуту.")
        return UNKNOWN

    def blob(digest):
        head = dict(headers)
        head["Accept"] = "application/json"
        return http(f"{blobs_url}/{digest}", head)

    return verdict(ref, code, body, blob)


# --- доживает ли зовущий до разбора моих кодов --------------------------------
#
# Три исхода не стоят ничего, если тот, кто их читает, до чтения не доживает.
# Шаг прогона идёт под `bash -e {0}` (GitHub задаёт shell так, это видно
# в логе каждого шага), и `set -uo pipefail` внутри скрипта шага наследуемый
# `-e` НЕ СНИМАЕТ: он убивает шаг на первом же ненулевом коде, то есть
# на законном исходе «годного образа нет — публикуем». Замерено прогоном
# 36867959464: сторож напечатал «на теге образа нет (404)» и вернул 1, шаг
# ответил «exit code 1», а `::notice::` в логе нет вовсе — `case` не выполнялся.
#
# Это класс 16 внутри правки против класса 16, поэтому проверка живёт здесь,
# со стороны того, чьи коды читают: договор пиннит тот, кто его объявляет
# (приём `ReadinessContractTest`). Смотрится при этом ВЕСЬ `ci.yml`, а не один
# свой шаг: подвержен тому же любой шаг, снимающий `$?`.

CI_YML = ".github/workflows/ci.yml"


def rc_steps(text):
    """[(задача, индекс шага, имя, защищён ли, чем)] по шагам, снимающим `$?`.

    `${PIPESTATUS[0]}` намеренно не считается захватом: там статус конвейера
    принадлежит последней команде (`| tee` отдаёт ноль), и `-e` не срабатывает.
    """
    import yaml

    doc = yaml.safe_load(text)
    top = ((doc.get("defaults") or {}).get("run") or {}).get("shell")
    out = []
    for job_id, job in (doc.get("jobs") or {}).items():
        job_shell = ((job.get("defaults") or {}).get("run") or {}).get("shell")
        for index, step in enumerate(job.get("steps") or []):
            run = step.get("run")
            if not run:
                continue
            lines = run.splitlines()
            first = None
            for i, line in enumerate(lines):
                if line.strip().endswith("=$?"):
                    first = i
                    break
            if first is None:
                continue
            name = step.get("name") or step.get("uses") or "(без имени)"
            shell = step.get("shell") or job_shell or top
            if shell and "-e" not in shell:
                out.append((job_id, index, name, True, f"свой shell: {shell}"))
                continue
            before = [l.strip() for l in lines[:first]]
            if any(l == "set +e" or l.startswith("set +e ") for l in before):
                out.append((job_id, index, name, True, "set +e перед захватом"))
            else:
                out.append((job_id, index, name, False,
                            "шаг снимает код возврата, но наследует -e "
                            "от `bash -e {0}` — на ненулевом коде он умрёт "
                            "до разбора, и законный исход станет красным"))
    return out


def without_set_plus_e(text, job_id, index):
    """Подделка: у названного шага снято `set +e`. Возврат дефекта 0241."""
    import yaml

    doc = yaml.safe_load(text)
    step = doc["jobs"][job_id]["steps"][index]
    step["run"] = "\n".join(l for l in step["run"].splitlines()
                            if l.strip() != "set +e")
    return yaml.safe_dump(doc)


SYNTHETIC = """
on: push
jobs:
  proba:
    steps:
      - name: свой shell без -e
        shell: bash {0}
        run: |
          ./cmd
          rc=$?
      - name: кода возврата не читает
        run: |
          echo всё хорошо
      - name: конвейер и PIPESTATUS
        run: |
          ./cmd | tee /tmp/log
          code=${PIPESTATUS[0]}
"""


# --- проверка самого сторожа --------------------------------------------------

FIXTURES = {
    # Годный индекс: обе архитектуры плюс подписи платформой unknown.
    "index-good": (200, {"manifests": [
        {"platform": {"os": "linux", "architecture": "amd64"}, "digest": "sha256:a"},
        {"platform": {"os": "linux", "architecture": "arm64"}, "digest": "sha256:b"},
        {"platform": {"os": "unknown", "architecture": "unknown"}, "digest": "sha256:c"},
    ]}),
    # Индекс без архитектуры ячейки — главная подделка критерия 3 задачи 0241.
    "index-no-amd64": (200, {"manifests": [
        {"platform": {"os": "linux", "architecture": "arm64"}, "digest": "sha256:b"},
    ]}),
    # Только подписи: платформа unknown/unknown. Образа нет, 200 есть.
    "index-attest-only": (200, {"manifests": [
        {"platform": {"os": "unknown", "architecture": "unknown"}, "digest": "sha256:c"},
    ]}),
    # Одиночный манифест — то, что кладёт docker build + docker push.
    "single-good": (200, {
        "mediaType": "application/vnd.oci.image.manifest.v1+json",
        "config": {"digest": "sha256:cfg-amd64"},
        "layers": [{"digest": "sha256:l1"}, {"digest": "sha256:l2"}],
    }),
    # Тот же манифест, но конфигурация называет чужую архитектуру.
    "single-arm": (200, {
        "config": {"digest": "sha256:cfg-arm64"},
        "layers": [{"digest": "sha256:l1"}],
    }),
    # След неудавшейся публикации: слоёв нет.
    "single-no-layers": (200, {"config": {"digest": "sha256:cfg-amd64"},
                               "layers": []}),
    "garbage": (200, "это не json"),
    "gone": (404, {"errors": [{"code": "MANIFEST_UNKNOWN"}]}),
    "server-error": (500, {"errors": [{"code": "UNKNOWN"}]}),
    "forbidden": (403, {"errors": [{"code": "DENIED"}]}),
}

# Блобы конфигурации: по digest'у из фикстуры манифеста.
BLOBS = {
    "sha256:cfg-amd64": (200, {"os": "linux", "architecture": "amd64"}),
    "sha256:cfg-arm64": (200, {"os": "linux", "architecture": "arm64"}),
}

SERVER = r'''
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
code, body, blobs = int(sys.argv[1]), sys.argv[2].encode(), json.loads(sys.argv[3])
class H(BaseHTTPRequestHandler):
    def do_GET(self):
        if "/token" in self.path:
            out, st = json.dumps({"token": "t"}).encode(), 200
        elif "/blobs/" in self.path:
            got = blobs.get(self.path.rsplit("/", 1)[1])
            if got is None:
                out, st = b'{"errors":[]}', 404
            else:
                out, st = json.dumps(got[1]).encode(), got[0]
        else:
            out, st = body, code
        self.send_response(st)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)
    def log_message(self, *a): pass
srv = HTTPServer(("127.0.0.1", 0), H)
print(srv.server_port, flush=True)
srv.serve_forever()
'''

REF = "ghcr.io/proba/parts-platform:0241deadbeef"


def selftest() -> int:
    bad = 0
    me = os.path.abspath(__file__)
    print("Самопроверка tools/image-published-guard.py")
    servers = []

    def registry(kind):
        code, doc = FIXTURES[kind]
        payload = doc if isinstance(doc, str) else json.dumps(doc)
        p = subprocess.Popen(
            [sys.executable, "-c", SERVER, str(code), payload, json.dumps(BLOBS)],
            stdout=subprocess.PIPE, text=True)
        servers.append(p)
        return f"http://127.0.0.1:{p.stdout.readline().strip()}"

    def probe(kind, script=None):
        base = registry(kind) if kind else "http://127.0.0.1:1"
        env = {"PATH": "/usr/bin:/bin",
               "IMAGE_PUBLISHED_TOKEN_BASE": base,
               "IMAGE_PUBLISHED_REGISTRY_BASE": base}
        out = subprocess.run([sys.executable, script or me, REF],
                             capture_output=True, text=True, env=env)
        return out.returncode, out.stdout + out.stderr

    def case(name, kind, want, says="", script=None):
        nonlocal bad
        rc, out = probe(kind, script)
        if rc == want and (not says or says in out):
            print(f"  ✓ {name}")
            return
        red(f"  ✗ {name}: код {rc}, ждали {want}"
            + (f"; в выводе нет «{says}»" if says and says not in out else ""))
        print("\n".join("      " + l for l in out.splitlines()), file=sys.stderr)
        bad = 1

    try:
        # Обратные края. Сторож, краснеющий на годном образе, заставит прогон
        # публиковать поверх уже опубликованного — то есть сам сломает то,
        # ради чего шаг написан.
        case("годный индекс — образ опубликован", "index-good", GOOD)
        case("годный одиночный манифест — образ опубликован", "single-good", GOOD,
             "платформа linux/amd64")
        case("тега нет — публиковать", "gone", ABSENT, "на теге образа нет")

        # Главные подделки: реестр отвечает 200, а годного образа на теге нет.
        case("индекс без linux/amd64 — не считается опубликованным",
             "index-no-amd64", ABSENT, "нет linux/amd64")
        case("индекс из одних подписей — не считается опубликованным",
             "index-attest-only", ABSENT, "нет linux/amd64")
        case("манифест без слоёв — не считается опубликованным",
             "single-no-layers", ABSENT, "лежит не образ")
        case("образ чужой архитектуры — не считается опубликованным",
             "single-arm", ABSENT, "собран под linux/arm64")
        case("на теге не JSON — не считается опубликованным",
             "garbage", ABSENT, "лежит не манифест")

        # «Спросить не удалось» — третий исход, и он не имеет права стать
        # ни «опубликован», ни «публикуй».
        case("реестр не ответил — спросить не удалось", None, UNKNOWN,
             "реестр не ответил")
        case("реестр ответил 500 — спросить не удалось", "server-error", UNKNOWN,
             "спросить не удалось")
        case("реестр ответил 403 — спросить не удалось", "forbidden", UNKNOWN,
             "нам не показали")

        # ВОЗВРАТ ДЕФЕКТА. Прежний шаг считал опубликованным всё, на что реестр
        # ответил. Копия сторожа, вернувшая это правило, обязана объявить годным
        # индекс без amd64 — иначе случаи выше ничего не утверждают: красное
        # могло бы приходить от чего угодно.
        fake = os.path.join(os.path.dirname(me), ".image-published-fake.py")
        text = open(me, encoding="utf-8").read()
        needle = '    if doc.get("manifests") is not None:'
        if needle not in text:
            red("  ✗ подделку негде поставить: место разбора манифеста изменилось")
            bad = 1
        else:
            open(fake, "w", encoding="utf-8").write(text.replace(
                needle, "    if code == 200:\n        return GOOD\n" + needle, 1))
            try:
                compiled = subprocess.run(
                    [sys.executable, "-m", "py_compile", fake],
                    capture_output=True, text=True)
                if compiled.returncode != 0:
                    red("  ✗ подделка не компилируется — её красное говорило бы "
                        "о сломанной копии, а не о снятой проверке")
                    bad = 1
                else:
                    rc, _ = probe("index-no-amd64", fake)
                    if rc == GOOD:
                        print("  ✓ возврат дефекта: копия, считающая опубликованным "
                              "любой ответ 200, объявляет годным индекс без amd64")
                    else:
                        red(f"  ✗ подделка «любой 200 — это публикация» обязана "
                            f"объявить годным индекс без amd64, а ответила {rc}: "
                            f"значит красное выше приходит не от проверки годности")
                        bad = 1
            finally:
                if os.path.exists(fake):
                    os.remove(fake)
                cache = os.path.join(os.path.dirname(me), "__pycache__")
                if os.path.isdir(cache):
                    for f in os.listdir(cache):
                        if f.startswith(".image-published-fake"):
                            os.remove(os.path.join(cache, f))

        # --- зовущий доживает до разбора кодов -------------------------------
        ci_path = os.path.join(os.path.dirname(os.path.dirname(me)), CI_YML)
        try:
            ci_text = open(ci_path, encoding="utf-8").read()
        except OSError as e:
            red(f"  ✗ {CI_YML} не прочитать ({e}): «кто читает мои коды» "
                f"не проверено — это не «всё хорошо»")
            bad = 1
            ci_text = ""

        if ci_text:
            steps = rc_steps(ci_text)
            broken = [(j, n, why) for j, _, n, ok, why in steps if not ok]
            if not steps:
                red("  ✗ в ci.yml не нашлось ни одного шага, снимающего $? — "
                    "проверка выродилась: править её, а не ci.yml")
                bad = 1
            elif broken:
                for job_id, name, why in broken:
                    red(f"  ✗ {job_id} / {name}: {why}")
                bad = 1
            else:
                print(f"  ✓ шаги, читающие код возврата ({len(steps)}), "
                      f"защищены от наследуемого -e")

            # ВОЗВРАТ ДЕФЕКТА, и по КАЖДОМУ такому шагу, а не только по своему:
            # снятое `set +e` обязано краснеть. Так же будет пойман и шаг,
            # который заведут завтра.
            for job_id, index, name, ok, _ in steps:
                if not ok:
                    continue
                fake = without_set_plus_e(ci_text, job_id, index)
                after = [(j, n, w) for j, _, n, o, w in rc_steps(fake)
                         if not o and j == job_id and n == name]
                if after:
                    print(f"  ✓ возврат дефекта: «{name}» без set +e краснеет")
                else:
                    red(f"  ✗ «{name}» без set +e обязан краснеть — иначе "
                        f"проверка зовущего ничего не утверждает")
                    bad = 1

            # Обратные края: свой shell без `-e` защищён, а шаг, не читающий
            # код возврата, и конвейер с PIPESTATUS в перечень не попадают
            # вовсе — иначе сторож краснел бы на законных шагах.
            synthetic = rc_steps(SYNTHETIC)
            names = {n: ok for _, _, n, ok, _ in synthetic}
            if names == {"свой shell без -e": True}:
                print("  ✓ обратный край: свой shell без -e защищён, "
                      "а шаг без $? и конвейер с PIPESTATUS не судятся")
            else:
                red(f"  ✗ обратный край не сошёлся: {names}")
                bad = 1
    finally:
        for p in servers:
            p.terminate()
    return bad


def main() -> int:
    args = list(sys.argv[1:])
    if "--selftest" in args:
        rc = selftest()
        print(GREEN % "Сторож проверен: на подделках краснеет, на годном образе молчит."
              if rc == 0 else RED % "Самопроверка не прошла.")
        return rc

    unknown = [a for a in args if a.startswith("--")]
    if unknown:
        print(f"Неизвестный флаг: {', '.join(unknown)}. Есть только --selftest.")
        return UNKNOWN
    if len(args) != 1:
        print("Нужен один адрес образа: ghcr.io/owner/repo:<tag>")
        return UNKNOWN

    ref = args[0]
    print(f"Что лежит на теге {ref} (спрошено протоколом, мимо кэша)")
    try:
        rc = ask(ref)
    except ValueError as e:
        red(f"  ✗ {e}")
        return UNKNOWN
    if rc == GOOD:
        print(GREEN % "На теге годный образ — публикация не нужна.")
    elif rc == ABSENT:
        print("Годного образа на теге нет — публикуем.")
    else:
        red("Спросить реестр не удалось — это не «опубликовано» и не «публикуй».")
    return rc


if __name__ == "__main__":
    sys.exit(main())
