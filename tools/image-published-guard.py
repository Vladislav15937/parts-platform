#!/usr/bin/env python3
"""На теге лежит ГОДНЫЙ образ — спрошено протоколом, и решение записано самим
сторожем, а не вычитано шеллом из кода возврата.

    ./tools/image-published-guard.py ghcr.io/owner/repo:<sha>
    ./tools/image-published-guard.py --selftest

КАК ЭТО РАБОТАЕТ В ПРОГОНЕ. Если задана переменная `GITHUB_OUTPUT` (а в задаче
прогона она задана всегда), сторож сам дописывает туда `skip=true` либо
`skip=false` и выходит **нулём**: решение принято и записано, шагу можно идти
дальше. Не удалось узнать — он не пишет **ничего** и выходит ненулевым, то есть
шаг краснеет. Это ровно та семантика, которой наследуемый `-e` и ждёт, поэтому
шагу прогона нечего разбирать: ни `set +e`, ни `rc=$?`, ни `case`.

Вне прогона (`GITHUB_OUTPUT` не задан) коды возврата остаются человеческими
и различимыми: 0 — на теге годный образ, 1 — годного нет, 2 — спросить
не удалось.

ПОЧЕМУ УСТРОЕНО ТАК, А НЕ ПРАВИЛОМ ПРО `set +e` (решение сессии-координатора
от 1 октября 2026, записано комментарием в PR #334; это не решение владельца
продукта — выбор не касается ни одной поверхности клиента, это способ вызвать
сторож внутри задачи CI, и владелец отменяет его одним словом).

Первая редакция этой проверки читала три кода возврата в самом шаге прогона,
а шаг идёт под `bash -e {0}`: наследуемый `-e` убивал его на законном исходе
«годного образа нет» ещё до разбора (прогон 36867959464). Починка добавила
`set +e` — и тогда понадобился сторож, который следит, что `set +e` не потеряли.
Этот сторож сопоставлял текст (`строка.endswith("=$?")`) и был обойдён
**четырежды подряд**, последний раз просто кавычками: `rc="$?"` при той же
семантике уводил шаг из перебора целиком, и самопроверка печатала
«защищены (1)» и «Сторож проверен» с кодом 0. Расширять сопоставление
бессмысленно: следом пройдут `rc=${?}`, промежуточная переменная, функция, —
это ровно та болезнь, которую в этой же ветке лечили у `ops/verify-backup.sh`
(он спрашивал грепом по тексту вместо следа).

Поэтому опасность **убрана по построению**, а не поставлена под охрану:
конструкции, которую надо стеречь, в шаге больше нет. Та же форма защиты, что
у адреса образа в одном месте (`ops/images.yml`), у единственного хука
`ui/useMounted.ts` вместо правила «пиши `mounted.current = true`» и у границы
отката внутри образа: нечего стеречь — нечего обойти.

ЧТО ПРИ ЭТОМ УДАЛЕНО, И ЭТО НАЗВАНО ВСЛУХ (критерий 7 задачи 0241). Вместе
с текстовым правилом ушли: перебор шагов, снимающих `$?`, возврат дефекта
по каждому такому шагу и разбор форм входа у `ci.yml` — включая состояние
«ни один шаг не снимает `$?`», которое разбор справедливо отметил как
смягчённое с красного. Убрано не потому, что мешало, а потому, что отвечало
на вопрос «читает ли шаг код возврата» сопоставлением строк и было
обойдено измеренным способом; стеречь им больше нечего.

ЧЕГО ЭТА ПРАВКА НЕ ДЕЛАЕТ. Шаг «Зеркало на месте?» по-прежнему читает три кода
`tools/mirror-images.sh --проверить` через `set +e` плюс `rc=$?`. Там это
работает и проверено, но **стеречься оно теперь не стережётся ничем**: потеряй
кто-нибудь `set +e` — и законный исход «зеркала нет» станет красным прогоном.
Смешанная правка не проверяется, поэтому здесь это не чинится, а названо:
подробности и формулировка для разведки — в `ops/CLAUDE.md`.

ПОЧЕМУ ПРОТОКОЛОМ, А НЕ DOCKER'ОМ. `docker manifest inspect` и `docker buildx
imagetools inspect` при containerd-хранилище отвечают из ЛОКАЛЬНОГО индекса:
образ из кэша выглядит у них лежащим в реестре (замерено 26 сентября 2026,
`tools/mirror-images.sh`, `ops/CLAUDE.md`). Проверка «мимо кэша», сделанная
docker'ом, повторила бы ту ловушку, из-за которой поломку MinIO не замечали
двенадцать месяцев.

ЧЕГО ОН НАМЕРЕННО НЕ ДЕЛАЕТ — не сверяет digest реестра с digest'ом собранного
образа. Сборка не побайтово повторяема (время внутри jar), поэтому повторный
прогон того же SHA даёт ДРУГОЙ digest: сверка объявила бы расхождение
и переопубликовала бы тег, а прежний образ — тот, что уже стоит на стенде, —
остался бы висеть без имени. Спрашивается поэтому «годен ли тот, что лежит».
"""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

# Архитектура ячейки и раннера. Образ на теге под другой архитектурой — это
# не «почти годный»: выкладка на нём не поднимется вовсе.
NEED_OS = "linux"
NEED_ARCH = "amd64"

# Имя, под которым решение читает прогон: `steps.<id>.outputs.skip`.
OUTPUT_NAME = "skip"

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
# и на несуществующий (замер 26 сентября 2026), поэтому в прогоне сторож
# спрашивает с учётной записью: тогда 404 означает «тега нет», а не
# «нам не показали».
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

def split_ref(ref):
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


def endpoints(host, path):
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


def http(url, headers=None):
    """Один запрос. Транспортный отказ — это Unreachable, а не код ответа."""
    req = urllib.request.Request(url, headers=headers or {})
    last = None
    for _ in range(ATTEMPTS):
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()
        except Exception as e:  # noqa: BLE001 — сеть, DNS, таймаут, TLS
            last = e
    raise Unreachable(str(last))


def bearer(token_url):
    headers = {"Accept": ACCEPT}
    ask_headers = {}
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

def index_arches(doc):
    """Архитектуры индекса. Слои подписей идут платформой unknown — это не
    архитектура, и считать их за неё значит принять однорукий образ за годный."""
    found = set()
    for m in doc.get("manifests") or []:
        p = m.get("platform") or {}
        arch, os_name = p.get("architecture"), p.get("os")
        if arch and arch != "unknown" and os_name not in (None, "unknown"):
            found.add(f"{os_name}/{arch}")
    return found


def verdict(ref, code, body, blob):
    """Годен ли образ на теге. Чистая функция — её и гоняет самопроверка."""
    if code == 404:
        print(f"  · на теге образа нет (404): {ref}")
        return ABSENT
    if code in (401, 403):
        # Анонимно у GHCR эти состояния неразличимы, и врать про них нельзя.
        # В прогоне сторож спрашивает с учётной записью, поэтому здесь это
        # «спросить не удалось», а не «публикуй».
        red(f"  ✗ реестр ответил {code} на {ref}: спросить не удалось")
        red("      Пакет закрыт либо учётной записи не хватает прав на чтение —")
        red("      нам не показали. «Опубликован» и «нам не показали» — разные")
        red("      ответы, и выдавать второе за первое нельзя.")
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
        arches = index_arches(doc)
        print(f"  · индекс, архитектуры: {', '.join(sorted(arches)) or 'ни одной'}")
        if f"{NEED_OS}/{NEED_ARCH}" not in arches:
            red(f"  ✗ в индексе {ref} нет {NEED_OS}/{NEED_ARCH}")
            red("      Это архитектура ячейки и раннера: образ на теге есть,")
            red("      а выкладка на нём не поднимется. Публиковать.")
            return ABSENT
        return GOOD

    # Одиночный манифест — ровно то, что кладёт `docker build` плюс `docker push`.
    # Годность двусоставная: слои и конфигурация на месте, и конфигурация
    # называет нашу архитектуру. Второе спрашивается у блоба конфигурации:
    # платформы в самом манифесте нет вовсе.
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


def ask(ref):
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


# --- решение записывает сторож ------------------------------------------------

def write_decision(rc):
    """Записать решение туда, где его читает прогон, и вернуть код ШАГА.

    Пишем **только** при заданной `GITHUB_OUTPUT`: самопроверка и ручной запуск
    идут вне прогона, где переменной нет, и безусловная запись уронила бы их
    там, где раньше всё работало. Это был бы третий случай одного класса
    за одну ветку — починка в одном месте ломает соседнее, которое никто
    не спросил, — поэтому у него есть свой случай самопроверки.

    «Спросить не удалось» не пишет НИЧЕГО: решения нет, и записать его значило
    бы дать прогону прочитать выдуманное. Шаг в этом случае краснеет сам,
    наследуемым `-e`, — ничего разбирать ему не надо.
    """
    out = os.environ.get("GITHUB_OUTPUT")
    if not out:
        return rc
    if rc == UNKNOWN:
        return UNKNOWN
    value = "true" if rc == GOOD else "false"
    try:
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"{OUTPUT_NAME}={value}\n")
    except OSError as e:
        red(f"  ✗ решение не записано в GITHUB_OUTPUT ({e})")
        red("      Продолжать шаг нельзя: прогон прочитал бы пустоту либо")
        red("      прежнее значение, то есть принял бы решение, которого нет.")
        return UNKNOWN
    print(f"  · для прогона записано: {OUTPUT_NAME}={value}")
    return GOOD


# --- проверка самого сторожа --------------------------------------------------

FIXTURES = {
    "index-good": (200, {"manifests": [
        {"platform": {"os": "linux", "architecture": "amd64"}, "digest": "sha256:a"},
        {"platform": {"os": "linux", "architecture": "arm64"}, "digest": "sha256:b"},
        {"platform": {"os": "unknown", "architecture": "unknown"}, "digest": "sha256:c"},
    ]}),
    "index-no-amd64": (200, {"manifests": [
        {"platform": {"os": "linux", "architecture": "arm64"}, "digest": "sha256:b"},
    ]}),
    "index-attest-only": (200, {"manifests": [
        {"platform": {"os": "unknown", "architecture": "unknown"}, "digest": "sha256:c"},
    ]}),
    "single-good": (200, {
        "mediaType": "application/vnd.oci.image.manifest.v1+json",
        "config": {"digest": "sha256:cfg-amd64"},
        "layers": [{"digest": "sha256:l1"}, {"digest": "sha256:l2"}],
    }),
    "single-arm": (200, {
        "config": {"digest": "sha256:cfg-arm64"},
        "layers": [{"digest": "sha256:l1"}],
    }),
    "single-no-layers": (200, {"config": {"digest": "sha256:cfg-amd64"},
                               "layers": []}),
    "garbage": (200, "это не json"),
    "gone": (404, {"errors": [{"code": "MANIFEST_UNKNOWN"}]}),
    "server-error": (500, {"errors": [{"code": "UNKNOWN"}]}),
    "forbidden": (403, {"errors": [{"code": "DENIED"}]}),
}

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


def selftest():
    import shutil
    import tempfile

    bad = 0
    me = os.path.abspath(__file__)
    print("Самопроверка tools/image-published-guard.py")
    servers = []
    tmp = tempfile.mkdtemp(prefix="image-published-")

    def registry(kind):
        code, doc = FIXTURES[kind]
        payload = doc if isinstance(doc, str) else json.dumps(doc)
        p = subprocess.Popen(
            [sys.executable, "-c", SERVER, str(code), payload, json.dumps(BLOBS)],
            stdout=subprocess.PIPE, text=True)
        servers.append(p)
        return f"http://127.0.0.1:{p.stdout.readline().strip()}"

    def probe(kind, script=None, out=None):
        base = registry(kind) if kind else "http://127.0.0.1:1"
        env = {"PATH": "/usr/bin:/bin",
               "IMAGE_PUBLISHED_TOKEN_BASE": base,
               "IMAGE_PUBLISHED_REGISTRY_BASE": base}
        if out is not None:
            env["GITHUB_OUTPUT"] = out
        done = subprocess.run([sys.executable, script or me, REF],
                              capture_output=True, text=True, env=env)
        return done.returncode, done.stdout + done.stderr

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
        # --- годность образа: коды возврата вне прогона -----------------------
        #
        # Этот блок и есть требуемый случай «без GITHUB_OUTPUT сторож работает
        # и не падает»: переменная здесь не задана ни разу, и коды остаются
        # человеческими — 0, 1, 2.
        case("вне прогона: годный индекс — код 0", "index-good", GOOD)
        case("вне прогона: годный одиночный манифест — код 0", "single-good",
             GOOD, "платформа linux/amd64")
        case("вне прогона: тега нет — код 1", "gone", ABSENT, "на теге образа нет")
        case("индекс без linux/amd64 — годным не считается", "index-no-amd64",
             ABSENT, "нет linux/amd64")
        case("индекс из одних подписей — годным не считается",
             "index-attest-only", ABSENT, "нет linux/amd64")
        case("манифест без слоёв — годным не считается", "single-no-layers",
             ABSENT, "лежит не образ")
        case("образ чужой архитектуры — годным не считается", "single-arm",
             ABSENT, "собран под linux/arm64")
        case("на теге не JSON — годным не считается", "garbage", ABSENT,
             "лежит не манифест")
        case("реестр не ответил — спросить не удалось", None, UNKNOWN,
             "реестр не ответил")
        case("реестр ответил 500 — спросить не удалось", "server-error", UNKNOWN,
             "спросить не удалось")
        case("реестр ответил 403 — спросить не удалось", "forbidden", UNKNOWN,
             "нам не показали")

        # --- решение записано: спрашиваем СЛЕД, а не код возврата ------------
        #
        # Шаг прогона ничего не разбирает, значит единственное, чем он отличает
        # «пропустить публикацию» от «публиковать», — это строка, которую сторож
        # записал. Её и проверяем; «код возврата верный» тут ничего не стоит.
        def решение(name, kind, ждём_код, ждём_строку):
            nonlocal bad
            путь = os.path.join(tmp, f"out-{name}.txt")
            open(путь, "w", encoding="utf-8").close()
            rc, out = probe(kind, out=путь)
            записано = open(путь, encoding="utf-8").read()
            ок = rc == ждём_код and записано.strip() == (ждём_строку or "")
            if ок:
                print(f"  ✓ {name}")
                return
            red(f"  ✗ {name}: код {rc} (ждали {ждём_код}), "
                f"записано «{записано.strip()}» (ждали «{ждём_строку or ''}»)")
            print("\n".join("      " + l for l in out.splitlines()), file=sys.stderr)
            bad = 1

        решение("в прогоне: годный образ — записан skip=true, код 0",
                "index-good", GOOD, f"{OUTPUT_NAME}=true")
        решение("в прогоне: тега нет — записан skip=false, код 0",
                "gone", GOOD, f"{OUTPUT_NAME}=false")
        решение("в прогоне: индекс без amd64 — записан skip=false, код 0",
                "index-no-amd64", GOOD, f"{OUTPUT_NAME}=false")
        # Главные три: решения нет — и не записано НИЧЕГО, а шаг краснеет.
        решение("в прогоне: реестр не ответил — не записано ничего, код 2",
                None, UNKNOWN, "")
        решение("в прогоне: 500 — не записано ничего, код 2",
                "server-error", UNKNOWN, "")
        решение("в прогоне: 403 — не записано ничего, код 2",
                "forbidden", UNKNOWN, "")

        # Записать не удалось — это тоже «решения нет»: шаг не имеет права
        # продолжать, прочитав пустоту. Каталог вместо файла даёт настоящий
        # отказ записи, а не подменённый.
        каталог = os.path.join(tmp, "вместо-файла")
        os.makedirs(каталог, exist_ok=True)
        rc, out = probe("index-good", out=каталог)
        if rc == UNKNOWN and "не записано в GITHUB_OUTPUT" in out:
            print("  ✓ в прогоне: запись не удалась — код 2 и сказано словами")
        else:
            red(f"  ✗ при неудачной записи шаг обязан краснеть: код {rc}")
            bad = 1

        # --- возврат дефекта --------------------------------------------------
        #
        # Две подделки, и обе про то, чем эта правка живёт: «любой ответ 200 —
        # публикация» (прежний дефект годности) и «решение пишем всегда»
        # (новый: прогон прочитал бы выдуманное).
        def подделка(name, якорь, замена, kind, ждём_не):
            nonlocal bad
            копия = os.path.join(os.path.dirname(me), ".image-published-fake.py")
            текст = open(me, encoding="utf-8").read()
            if якорь not in текст:
                red(f"  ✗ подделку «{name}» негде поставить: место изменилось")
                bad = 1
                return
            open(копия, "w", encoding="utf-8").write(
                текст.replace(якорь, замена, 1))
            try:
                целость = subprocess.run(
                    [sys.executable, "-m", "py_compile", копия],
                    capture_output=True, text=True)
                if целость.returncode != 0:
                    red(f"  ✗ подделка «{name}» не компилируется — её красное "
                        f"говорило бы о копии, а не о проверке")
                    bad = 1
                    return
                путь = os.path.join(tmp, "fake-out.txt")
                open(путь, "w", encoding="utf-8").close()
                rc, _ = probe(kind, script=копия, out=путь)
                записано = open(путь, encoding="utf-8").read().strip()
                if (rc, записано) != ждём_не:
                    print(f"  ✓ возврат дефекта: {name}")
                else:
                    red(f"  ✗ подделка «{name}» обязана менять поведение, "
                        f"а дала то же: код {rc}, записано «{записано}»")
                    bad = 1
            finally:
                for junk in (копия, копия + "c"):
                    if os.path.exists(junk):
                        os.remove(junk)
                кэш = os.path.join(os.path.dirname(me), "__pycache__")
                if os.path.isdir(кэш):
                    for f in os.listdir(кэш):
                        if f.startswith(".image-published-fake"):
                            os.remove(os.path.join(кэш, f))

        # Копия, считающая опубликованным любой ответ 200, обязана записать
        # skip=true там, где настоящий сторож пишет skip=false.
        подделка("любой ответ 200 — публикация",
                 '    if doc.get("manifests") is not None:',
                 "    if code == 200:\n        return GOOD\n"
                 '    if doc.get("manifests") is not None:',
                 "index-no-amd64", (GOOD, f"{OUTPUT_NAME}=false"))
        # Копия, пишущая решение и на «спросить не удалось», обязана записать
        # что-нибудь там, где настоящий сторож не пишет ничего.
        подделка("решение пишется и без ответа",
                 "    if rc == UNKNOWN:\n        return UNKNOWN",
                 "    if False:\n        return UNKNOWN",
                 "server-error", (UNKNOWN, ""))
    finally:
        for p in servers:
            p.terminate()
        shutil.rmtree(tmp, ignore_errors=True)
    return bad


def main():
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
    return write_decision(rc)


if __name__ == "__main__":
    sys.exit(main())
