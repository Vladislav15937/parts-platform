#!/usr/bin/env python3
"""Образ канала тревог есть в реестре — спрошено ПРОТОКОЛОМ, мимо кэша.

    ./tools/alert-image-guard.py             # спросить реестр
    ./tools/alert-image-guard.py --strict    # без сети — красное (так зовёт CI)
    ./tools/alert-image-guard.py --selftest  # проверить самого сторожа на подделках

ЗАЧЕМ. `curlimages/curl:8.11.1` нужен ДВУМ вещам сразу: контейнеру
`alert-watch`, который сообщает об отбитой доставке тревог вторым путём,
и пробе `--probe`, которой спрашивают, дотянется ли диспетчер до Telegram.
То есть Docker Hub стал условием того, что ячейка может позвать человека.

А доступность этого образа мимо кэша не проверял ни один шаг прогона: был
только `docker compose config`, то есть разбор текста. Урок MinIO применён
наполовину — версия закреплена (это обязательно), а «образ ещё раздают»
не спрашивал никто. Цена выше, чем была у MinIO: там ломался прогон, здесь
ломается путь, которым ячейка зовёт человека, и виден отказ станет
в момент выкладки, а не заранее (задача 0155).

ПОЧЕМУ ПРОТОКОЛОМ, А НЕ DOCKER'ОМ. `docker manifest inspect` и `docker buildx
imagetools inspect` при containerd-хранилище отвечают из ЛОКАЛЬНОГО индекса:
образ, собранный тут же, выглядит у них как лежащий в реестре (проверено
26 сентября 2026, tools/mirror-images.sh). То есть проверка «мимо кэша»,
сделанная docker'ом, повторила бы ровно ту ловушку, из-за которой поломку
не замечали двенадцать месяцев. Анонимный токен отвечает заодно на второй
вопрос: тянется ли образ БЕЗ входа в реестр — именно так его тянет ячейка.

ЧЕГО ОН НЕ ДЕЛАЕТ. Он не заводит зеркала: образ по-прежнему чужой, и это
названное решение, а не забывчивость — довод и цена в ops/CLAUDE.md,
«Образ канала тревог: проверка вместо зеркала». Он отвечает на «узнаю ли
я о пропаже заранее», а не на «переживу ли я её».
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(os.environ.get("ALERT_IMAGE_GUARD_ROOT", Path(__file__).resolve().parent.parent))

# Оба места, где адрес объявлен. Их ровно два, и сверять их обязательно:
# проба, тянущая один образ, ничего не говорит про сторожа, который держит
# другой, — а «проба прошла» читают как «канал проверен».
COMPOSE = "docker-compose.prod.yml"
COMPOSE_SERVICE = "alert-watch"
PROBE = "ops/alert-channel-check.sh"

LOOKED = (COMPOSE, PROBE)
GONE_MSG = "файл из списка не найден, сторож неполон"

# Архитектура ячейки и раннера. Без неё канал тревог не поднимется вовсе.
NEED_ARCH = "amd64"
# Архитектура машины разработчика: по инструкциям 0153 и 0158 человек тянет
# этот образ себе и гоняет пробу руками. Её пропажа канал не ломает, поэтому
# отдельная строка, а не красное: сторож, красный на работающей ячейке,
# будет отключён первым — вместе с защитой.
NICE_ARCH = "arm64"

ACCEPT = ", ".join((
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
))

RED = "\033[1;31m%s\033[0m"
GREEN = "\033[1;32m%s\033[0m"

# Швы для самопроверки: ей нужен реестр, которого нет в сети. Подменяется
# только адрес — путь разбора, ветки ответов и сообщения остаются теми же,
# иначе проверялся бы не сторож, а его копия.
TOKEN_BASE = os.environ.get("ALERT_IMAGE_GUARD_TOKEN_BASE", "")
REGISTRY_BASE = os.environ.get("ALERT_IMAGE_GUARD_REGISTRY_BASE", "")

TIMEOUT = 15
ATTEMPTS = 3


class Unreachable(RuntimeError):
    """Реестр не ответил вовсе: сеть, DNS, таймаут. Не то же, что «образа нет»."""


def red(msg: str) -> None:
    print(RED % msg, file=sys.stderr)


def looked_missing() -> list[str]:
    return [rel for rel in LOOKED if not (ROOT / rel).is_file()]


# --- откуда берётся адрес -----------------------------------------------------

def compose_image() -> str | None:
    """Образ сервиса alert-watch из боевого compose. Разбор строчный: YAML-
    библиотеки на пути может не быть вовсе, а формат нашего файла наш."""
    text = (ROOT / COMPOSE).read_text(encoding="utf-8")
    service = None
    for line in text.splitlines():
        m = re.match(r"^  ([A-Za-z0-9_.-]+):\s*$", line)
        if m:
            service = m.group(1)
            continue
        m = re.match(r"^\s+image:\s*(\S+)\s*$", line)
        if m and service == COMPOSE_SERVICE:
            return m.group(1)
    return None


def probe_image() -> str | None:
    """Умолчание PROBE_IMAGE из сторожа канала. Берём умолчание, а не значение
    переменной: именно оно уезжает на ячейку и именно его тянет проба."""
    text = (ROOT / PROBE).read_text(encoding="utf-8")
    m = re.search(r'^PROBE_IMAGE="\$\{ALERT_PROBE_IMAGE:-([^}"]+)\}"', text, re.M)
    return m.group(1) if m else None


# --- как спрашиваем реестр ----------------------------------------------------

def split_ref(ref: str) -> tuple[str, str, str]:
    """адрес → (хост реестра, путь репозитория, тег)."""
    name, _, tag = ref.rpartition(":")
    head = name.split("/")[0]
    if "." in head or ":" in head or head == "localhost":
        host, path = head, name.split("/", 1)[1]
    else:
        host = "docker.io"
        # У Docker Hub односложное имя живёт в library/: `postgres` — это
        # `library/postgres`, и без приставки реестр отвечает 401, а не 404.
        path = name if "/" in name else f"library/{name}"
    return host, path, tag


def endpoints(host: str, path: str) -> tuple[str, str]:
    """Адреса токена и манифестов. Подменяются самопроверкой целиком."""
    if TOKEN_BASE and REGISTRY_BASE:
        return (f"{TOKEN_BASE}/token?scope=repository:{path}:pull",
                f"{REGISTRY_BASE}/v2/{path}/manifests")
    if host == "docker.io":
        return (f"https://auth.docker.io/token?service=registry.docker.io"
                f"&scope=repository:{path}:pull",
                f"https://registry-1.docker.io/v2/{path}/manifests")
    return (f"https://{host}/token?service={host}&scope=repository:{path}:pull",
            f"https://{host}/v2/{path}/manifests")


def http(url: str, headers: dict[str, str] | None = None) -> tuple[int, bytes]:
    """Один запрос. Транспортный отказ — это Unreachable, а не код ответа:
    «реестр не ответил» и «образа там нет» чинят по-разному, и путать их нельзя."""
    req = urllib.request.Request(url, headers=headers or {})
    last: Exception | None = None
    for _ in range(ATTEMPTS):
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            # Код ответа — это ответ реестра, а не отказ связи.
            return e.code, e.read()
        except Exception as e:  # noqa: BLE001 — сеть, DNS, таймаут, TLS
            last = e
    raise Unreachable(str(last))


def manifest(ref: str) -> tuple[int, bytes]:
    host, path, tag = split_ref(ref)
    token_url, manifests_url = endpoints(host, path)
    headers = {"Accept": ACCEPT}
    try:
        code, body = http(token_url)
        if code == 200:
            token = json.loads(body or b"{}").get("token", "")
            if token:
                headers["Authorization"] = f"Bearer {token}"
    except Unreachable:
        raise
    except Exception:  # токен не разобрался — спросим без него, ответ скажет сам
        pass
    return http(f"{manifests_url}/{tag}", headers)


def arches(body: bytes) -> tuple[set[str], bool]:
    """Архитектуры индекса и признак «это одиночный манифест».

    Слои подписей идут платформой unknown — это не архитектура, и считать их
    за неё значит принять однорукий образ за годный (tools/mirror-images.sh)."""
    try:
        d = json.loads(body or b"{}")
    except ValueError:
        return set(), False
    ms = d.get("manifests")
    if not ms:
        return set(), True
    found = set()
    for m in ms:
        arch = (m.get("platform") or {}).get("architecture")
        if arch and arch != "unknown":
            found.add(arch)
    return found, False


# --- проверка -----------------------------------------------------------------

def check(strict: bool) -> int:
    bad = 0
    for rel in looked_missing():
        red(f"  ✗ {rel} — файла нет, а он в списке сторожа: {GONE_MSG}")
        red("      Файл переехал или переименован — поправьте LOOKED в этом стороже,")
        red("      иначе адрес образа канала тревог не проверяет больше никто.")
        bad = 1
    if bad:
        return bad

    declared = {COMPOSE: compose_image(), PROBE: probe_image()}
    for where, ref in declared.items():
        if not ref:
            red(f"  ✗ {where} не называет образ — сторож не знает, что проверять")
            red(f"      Ждали образ сервиса «{COMPOSE_SERVICE}» в {COMPOSE}")
            red(f"      и умолчание PROBE_IMAGE в {PROBE}.")
            bad = 1
    if bad:
        return bad

    refs = set(declared.values())
    if len(refs) != 1:
        red("  ✗ сторож и проба тянут РАЗНЫЕ образы:")
        for where, ref in declared.items():
            red(f"      {where}: {ref}")
        red("      Контейнер alert-watch держит один, проба спрашивает про другой —")
        red("      «проба прошла» перестаёт что-либо говорить про сторожа канала.")
        return 1

    ref = refs.pop()
    tag = ref.rpartition(":")[2]
    if tag in ("latest", "main", "edge"):
        red(f"  ✗ плавающий тег «{tag}»: {ref}")
        red("      Плавающий тег запрещён корневым CLAUDE.md: образ сменится")
        red("      под ногами, и разница будет видна только по отказу.")
        return 1
    print(f"  ✓ образ назван одинаково в обоих местах: {ref}")

    try:
        code, body = manifest(ref)
    except Unreachable as e:
        if strict:
            red(f"  ✗ реестр не ответил про {ref}: {e}")
            red("      Это и есть то, о чём задача 0155: канал тревог зависит от")
            red("      чужого реестра. Повторите прогон — не воспроизвелось, значит")
            red("      реестр не ответил в ту минуту; воспроизвелось — образа нет.")
            return 1
        print(f"  · реестр не спрошен ({e}) — сверка не выполнена")
        print("    Это не «образ на месте»: без сети ответа нет. Настоящий ответ")
        print("    даёт CI, где сеть есть всегда (там сторож идёт с --strict).")
        return 0

    if code == 404:
        red(f"  ✗ в реестре НЕТ образа {ref} (ответ 404)")
        red("      Это образ контейнера alert-watch и пробы --probe: без него")
        red("      ячейка не сможет ни сообщить об отбитой доставке тревог,")
        red("      ни проверить дорогу до Telegram. `docker compose up` откажет")
        red("      в момент выкладки. Выходы: закрепить доступную версию либо")
        red("      завести зеркало (ops/images.yml, tools/mirror-images.sh).")
        return 1
    if code in (401, 403):
        red(f"  ✗ анонимно не тянется (ответ {code}): {ref}")
        red("      Ячейка тянет этот образ БЕЗ входа в реестр — значит либо")
        red("      репозитория больше нет, либо он закрылся. Анонимно эти два")
        red("      состояния неразличимы, и врать про них нельзя.")
        return 1
    if code != 200:
        red(f"  ✗ реестр ответил {code} на {ref}")
        return 1

    found, single = arches(body)
    if single:
        print("  ✓ лежит в реестре и тянется без входа (одиночный манифест,")
        print("    архитектуру по индексу не спросить)")
        return 0
    print("  ✓ лежит в реестре и тянется без входа")
    print(f"    архитектуры: {', '.join(sorted(found)) or 'ни одной'}")
    if NEED_ARCH not in found:
        red(f"  ✗ нет linux/{NEED_ARCH} — а это архитектура ячейки и раннера")
        red("      Образ есть, но канал тревог на нём не поднимется.")
        return 1
    if NICE_ARCH not in found:
        print(f"  · нет linux/{NICE_ARCH}: ячейке это ничем не грозит, а человек")
        print("    по инструкциям 0153 и 0158 тянет образ себе и гоняет пробу руками")
    return bad


# --- проверка самого сторожа --------------------------------------------------
#
# Сторож, не краснеющий на дефекте, хуже отсутствующего, и установить это можно
# только попыткой. Реестр для этого поднимается свой: самопроверка обязана
# работать без сети, иначе она молчит ровно тогда, когда сеть и подводит.

FIXTURES = {
    "good": (200, {"manifests": [
        {"platform": {"os": "linux", "architecture": "amd64"}, "digest": "sha256:a"},
        {"platform": {"os": "linux", "architecture": "arm64"}, "digest": "sha256:b"},
        {"platform": {"os": "unknown", "architecture": "unknown"}, "digest": "sha256:c"},
    ]}),
    "gone": (404, {"errors": [{"code": "MANIFEST_UNKNOWN"}]}),
    "one-arm": (200, {"manifests": [
        {"platform": {"os": "linux", "architecture": "arm64"}, "digest": "sha256:b"},
    ]}),
}

SERVER = r'''
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
code, body = int(sys.argv[2]), sys.argv[3].encode()
class H(BaseHTTPRequestHandler):
    def do_GET(self):
        if "/token" in self.path:
            out, st = json.dumps({"token": "t"}).encode(), 200
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


def _plant(dst: Path) -> None:
    """Дерево из тех файлов, которые сторож читает — по именам, а не `cp -R`.

    Рядом с рабочим каталогом лежат `.git`, `target/`, `node_modules` и сотня
    брошенных рабочих деревьев на десятки гигабайт (`tasks/0171`): копирование
    корня шло бы минутами и валилось нечитаемым traceback'ом вместо отчёта —
    самопроверка не работала бы ровно там, где её запускают руками (`tasks/0181`).
    """
    gone = looked_missing()
    if gone:
        raise RuntimeError(f"{GONE_MSG}: {', '.join(gone)}")
    for rel in LOOKED:
        target = dst / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / rel, target)


def selftest() -> int:
    bad = 0
    me = Path(__file__).resolve()
    print("Самопроверка tools/alert-image-guard.py")

    gone = looked_missing()
    if gone:
        red(f"  ✗ {GONE_MSG}: {', '.join(gone)}")
        return 1
    print(f"  ✓ список сторожа полон: файлов в нём {len(LOOKED)}")

    servers: list[subprocess.Popen] = []

    def registry(kind: str) -> str:
        code, doc = FIXTURES[kind]
        p = subprocess.Popen(
            [sys.executable, "-c", SERVER, kind, str(code), json.dumps(doc)],
            stdout=subprocess.PIPE, text=True)
        servers.append(p)
        port = p.stdout.readline().strip()
        return f"http://127.0.0.1:{port}"

    def probe(root: Path, base: str | None, strict: bool = False) -> tuple[int, str]:
        env = {"PATH": "/usr/bin:/bin", "ALERT_IMAGE_GUARD_ROOT": str(root)}
        if base:
            env["ALERT_IMAGE_GUARD_TOKEN_BASE"] = base
            env["ALERT_IMAGE_GUARD_REGISTRY_BASE"] = base
        else:
            # Закрытый порт: транспортного ответа не будет вовсе.
            env["ALERT_IMAGE_GUARD_TOKEN_BASE"] = "http://127.0.0.1:1"
            env["ALERT_IMAGE_GUARD_REGISTRY_BASE"] = "http://127.0.0.1:1"
        cmd = [sys.executable, str(me)] + (["--strict"] if strict else [])
        out = subprocess.run(cmd, capture_output=True, text=True, env=env)
        return out.returncode, out.stdout + out.stderr

    try:
        with tempfile.TemporaryDirectory() as tmp:
            good = registry("good")

            # Настоящее дерево обязано проходить: сторож, краснеющий на исправном
            # дереве, отключат в первый же день.
            real = Path(tmp) / "real"
            _plant(real)
            rc, out = probe(real, good)
            if rc == 0:
                print("  ✓ настоящее дерево на годном реестре проходит")
            else:
                red("  ✗ настоящее дерево обязано проходить")
                print("\n".join("      " + l for l in out.splitlines()), file=sys.stderr)
                bad = 1

            # Тега в реестре нет — ровно то, что случилось с MinIO дважды.
            rc, out = probe(real, registry("gone"))
            if rc != 0 and "в реестре НЕТ образа" in out:
                print("  ✓ пропавший тег — красное, и сказано, что именно пропало")
            else:
                red("  ✗ пропавший тег обязан валить сторожа словами «в реестре НЕТ образа»")
                bad = 1

            # Индекс без amd64: образ есть, а ячейка на нём не поднимется.
            rc, out = probe(real, registry("one-arm"))
            if rc != 0 and f"нет linux/{NEED_ARCH}" in out:
                print("  ✓ индекс без amd64 — красное")
            else:
                red(f"  ✗ индекс без {NEED_ARCH} обязан валить сторожа")
                bad = 1

            # Подделки ставятся по ЖИВОМУ адресу, а не по вписанному сюда: тег
            # однажды поднимут, и подделка, привязанная к строке «8.11.1», молча
            # перестала бы ставиться — самопроверка осталась бы зелёной, ничего
            # не проверяя. Поэтому образ читается из дерева, а «подделку негде
            # поставить» — отдельное красное.
            live = probe_image() or ""
            name, _, _ = live.rpartition(":")

            def plant(label: str, where: tuple[str, ...], new: str) -> Path:
                root = Path(tmp) / label
                _plant(root)
                for rel in where:
                    p = root / rel
                    t = p.read_text(encoding="utf-8")
                    if live not in t:
                        red(f"  ✗ подделку негде поставить: в {rel} нет «{live}»")
                        return root
                    p.write_text(t.replace(live, new), encoding="utf-8")
                return root

            # Два места разошлись: проба проверяет не тот образ, что держит
            # контейнер, — и «проба прошла» перестаёт что-либо значить.
            rc, out = probe(plant("drift", (PROBE,), f"{name}:0.0.0-drift"), good)
            if rc != 0 and "РАЗНЫЕ образы" in out:
                print("  ✓ разошедшиеся адреса — красное")
            else:
                red("  ✗ разные образы у сторожа и пробы обязаны валить прогон")
                bad = 1

            # Плавающий тег: запрет корневого CLAUDE.md.
            rc, out = probe(plant("floating", LOOKED, f"{name}:latest"), good)
            if rc != 0 and "плавающий тег" in out:
                print("  ✓ плавающий тег — красное")
            else:
                red("  ✗ плавающий тег обязан валить сторожа")
                bad = 1

            # Реестр не ответил. Две стороны, и обе нужны: у себя без сети сторож
            # не имеет права краснеть (иначе его отключат), в CI — обязан.
            rc, out = probe(real, None)
            if rc == 0 and "сверка не выполнена" in out:
                print("  ✓ без сети сторож не краснеет и говорит, что не сверял")
            else:
                red("  ✗ без сети сторож обязан выходить нулём со словами «сверка не выполнена»")
                bad = 1
            rc, out = probe(real, None, strict=True)
            if rc != 0 and "реестр не ответил" in out:
                print("  ✓ с --strict молчание реестра — красное")
            else:
                red("  ✗ с --strict молчание реестра обязано быть красным")
                bad = 1

            # Переехавший файл: обе половины сторожа обязаны говорить одно
            # и то же (`tasks/0181` — там расхождение половин стоило задачи).
            moved = Path(tmp) / "moved"
            _plant(moved)
            (moved / PROBE).rename(moved / (PROBE + ".moved"))
            rc, out = probe(moved, good)
            if rc != 0 and GONE_MSG in out:
                print("  ✓ переехавший файл списка — красное, и сказано, что сторож неполон")
            else:
                red(f"  ✗ переехавший файл обязан валить сторожа словами «{GONE_MSG}»")
                bad = 1
    finally:
        for p in servers:
            p.terminate()

    return bad


def main() -> int:
    if "--selftest" in sys.argv:
        rc = selftest()
        print(GREEN % "Сторож проверен." if rc == 0 else RED % "Самопроверка не прошла.")
        return rc

    strict = "--strict" in sys.argv
    print("Образ канала тревог: есть ли он в реестре (спрошено протоколом)")
    rc = check(strict)
    if rc == 0:
        print(GREEN % "Образ канала тревог доступен.")
    else:
        red("Канал тревог остался без образа — смотрите строки выше.")
    return rc


if __name__ == "__main__":
    sys.exit(main())
