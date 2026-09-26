#!/usr/bin/env python3
"""Адрес зеркалённого образа записан ОДИН раз — и остаётся так.

    ./tools/image-pin-guard.py            # проверить дерево
    ./tools/image-pin-guard.py --selftest # проверить самого сторожа на подделках

ЗАЧЕМ. 26 сентября 2026 образ MinIO пропал из чужого реестра, и `main`
покраснела у всех. Правка была бы в пяти местах: три теста с настоящим
хранилищем и оба compose. Пять копий одного значения — тот класс, из-за
которого расходятся правила (`tasks/0177`): правишь четыре, пятая остаётся
жить, и расхождение вылезает там, где его труднее всего связать с причиной —
тест гоняет не то, что стоит в бою.

Поэтому адрес теперь лежит в `ops/images.yml`, а этот сторож следит за тем,
чтобы шестой копии не появилось: молчаливое возвращение литерала — ровно то,
что правило делает бесполезным через месяц.

Три вопроса, на которые он отвечает:

1. тег закреплён — плавающий `latest` в прогоне запрещён (корневой CLAUDE.md);
2. зеркало наше — адрес ведёт в наш GHCR, а не в чужой реестр, потому что
   чужой уже исчезал дважды;
3. копий нет — ни в compose (оба обязаны `include` фрагмент и не задавать
   `image` сами), ни в тестах (они берут адрес из support/TestImages).

Документы и файлы задач сторож не трогает: там адреса — история, а не
настройка. Разница простая: по `ops/images.yml` образ ТЯНУТ, по `docs/` —
вспоминают, почему он такой.
"""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PIN = "ops/images.yml"

# Наш реестр. Зеркало обязано лежать здесь: и quay, и Docker Hub уже уносили
# образ из-под ног, и решение владельца от 26.09.2026 — «своё зеркало в GHCR».
OUR_REGISTRY = "ghcr.io/vladislav15937/parts-platform/"

# Сервисы, чей образ зеркалим. Ключ — имя сервиса в compose, оно же имя
# бинарника выпуска.
MIRRORED = ("minio", "mc")

# Оба compose обязаны включать фрагмент и не задавать образ сами.
COMPOSES = ("docker-compose.yml", "docker-compose.prod.yml")

# Тесты с настоящим хранилищем: адрес они берут из support/TestImages.
TESTS = (
    "src/test/java/ru/partsflow/inventory/PhotoServiceTest.java",
    "src/test/java/ru/partsflow/inventory/PhotoArchiveStreamTest.java",
    "src/test/java/ru/partsflow/migration/bazon/PhotoMigrationTest.java",
)

# Мёртвые адреса: если такой снова появится в настройке или в коде, это
# возврат к тому, из-за чего всё случилось.
DEAD = ("quay.io/minio/", "minio/minio:", "minio/mc:")

RED = "\033[1;31m%s\033[0m"
GREEN = "\033[1;32m%s\033[0m"


def red(msg: str) -> None:
    print(RED % msg, file=sys.stderr)


def pin_images(root: Path) -> dict[str, str]:
    """Адреса из единственного места. Разбор строчный, без YAML-библиотеки:
    у тестов её на пути может не быть вовсе, а формат фрагмента — наш."""
    text = (root / PIN).read_text(encoding="utf-8")
    found: dict[str, str] = {}
    service = None
    for line in text.splitlines():
        m = re.match(r"^  ([A-Za-z0-9_.-]+):\s*$", line)
        if m:
            service = m.group(1)
            continue
        m = re.match(r"^\s+image:\s*(\S+)\s*$", line)
        if m and service:
            found[service] = m.group(1)
    return found


def check(root: Path) -> int:
    bad = 0
    pin_path = root / PIN
    if not pin_path.is_file():
        red(f"  ✗ {PIN} — нет вовсе, а это единственное место, где записан адрес образа")
        return 1

    images = pin_images(root)
    for service in MIRRORED:
        ref = images.get(service)
        if not ref:
            red(f"  ✗ в {PIN} нет образа для сервиса «{service}»")
            bad = 1
            continue

        if ":" not in ref.rsplit("/", 1)[-1]:
            red(f"  ✗ {service}: адрес без тега — «{ref}»")
            red("      Без тега docker берёт latest, то есть «неизвестно что».")
            bad = 1
            continue

        tag = ref.rsplit(":", 1)[1]
        if tag in ("latest", "main", "edge"):
            red(f"  ✗ {service}: плавающий тег «{tag}» — образ сменится под ногами")
            red("      Плавающий тег в прогоне запрещён: вчера прогон шёл на одном")
            red("      образе, сегодня на другом, и разница видна только по красному CI.")
            bad = 1
        if not ref.startswith(OUR_REGISTRY):
            red(f"  ✗ {service}: образ не из нашего реестра — «{ref}»")
            red(f"      Зеркало обязано лежать в {OUR_REGISTRY}: чужие реестры уносили")
            red("      этот образ дважды (Docker Hub, затем quay.io).")
            bad = 1
        if not bad:
            print(f"  ✓ {service}: {ref}")

    # Копии адреса в compose: фрагмент включён, своего image нет.
    for name in COMPOSES:
        path = root / name
        if not path.is_file():
            red(f"  ✗ {name} — нет вовсе")
            bad = 1
            continue
        text = path.read_text(encoding="utf-8")
        if PIN not in text:
            red(f"  ✗ {name} не включает {PIN} — образ он берёт откуда-то ещё")
            bad = 1
        for service in MIRRORED:
            # Строка вида «image: …minio…» рядом с сервисом — это копия адреса.
            for line in text.splitlines():
                m = re.match(r"^\s+image:\s*(\S+)\s*$", line)
                if m and service in m.group(1) and "ghcr.io" in m.group(1):
                    red(f"  ✗ {name} задаёт образ сам: «{m.group(1).strip()}»")
                    red(f"      Адрес живёт в {PIN}; здесь он станет второй копией.")
                    bad = 1
        if PIN in text:
            print(f"  ✓ {name} берёт образ из {PIN}")

    # Копии адреса в тестах.
    for name in TESTS:
        path = root / name
        if not path.is_file():
            red(f"  ✗ {name} — нет вовсе")
            bad = 1
            continue
        text = path.read_text(encoding="utf-8")
        literal = re.search(r'"(?:ghcr\.io|quay\.io|docker\.io)/\S+"', text)
        if literal:
            red(f"  ✗ {name} несёт адрес образа литералом: {literal.group(0)}")
            red("      Тест обязан брать его из support/TestImages, иначе копий снова пять.")
            bad = 1
        elif "TestImages" not in text:
            red(f"  ✗ {name} не зовёт TestImages — откуда он берёт хранилище?")
            bad = 1
        else:
            print(f"  ✓ {name} берёт образ из TestImages")

    # Мёртвые адреса в настройках и в коде. Документы и задачи — мимо:
    # там это история, и запрещать её значит запрещать объяснение.
    looked = list(COMPOSES) + list(TESTS) + [
        PIN,
        "tools/mirror/minio.Dockerfile",
        "tools/mirror/mc.Dockerfile",
        "src/test/java/ru/partsflow/support/TestImages.java",
    ]
    for name in looked:
        path = root / name
        if not path.is_file():
            continue
        # Комментарии — мимо: в них мёртвый адрес объясняет историю («образ
        # убрали оттуда-то»), и запрещать её значит запрещать объяснение.
        # Сторож смотрит настроечные строки — те, по которым образ ТЯНУТ.
        text = "\n".join(
            line for line in path.read_text(encoding="utf-8").splitlines()
            if not line.lstrip().startswith(("#", "//", "*", "/*"))
        )
        for dead in DEAD:
            # `minio/minio` без тега — это имя репозитория выпусков, а не образ:
            # из него мы берём бинарник. Ловим только адреса образов, с тегом.
            if dead in text:
                red(f"  ✗ {name} снова тянет мёртвый адрес «{dead}…»")
                red("      Оттуда образ убрали: quay — 26.09.2026, Docker Hub — годом раньше.")
                bad = 1

    return bad


# --- проверка самого сторожа -------------------------------------------------
#
# Сторож, не краснеющий на дефекте, хуже отсутствующего, и установить это можно
# только попыткой. Подделки — ровно те способы, которыми правило перестаёт
# работать: вернули плавающий тег, вернули чужой реестр, вернули копию адреса
# в compose, вернули копию в тест. Настоящее дерево при этом обязано проходить:
# сторож, краснеющий на исправном дереве, отключат в первый же день.
def selftest() -> int:
    bad = 0
    print("Самопроверка tools/image-pin-guard.py")
    me = Path(__file__).resolve()

    def probe(root: Path) -> tuple[int, str]:
        out = subprocess.run(
            [sys.executable, str(me)],
            cwd=str(root), capture_output=True, text=True,
            env={"PATH": "/usr/bin:/bin", "IMAGE_PIN_GUARD_ROOT": str(root)},
        )
        return out.returncode, out.stdout + out.stderr

    rc, out = probe(ROOT)
    if rc == 0:
        print("  ✓ настоящее дерево проходит")
    else:
        red("  ✗ настоящее дерево не проходит — смотрите прогон без --selftest")
        print("\n".join("      " + line for line in out.splitlines()), file=sys.stderr)
        bad = 1

    with tempfile.TemporaryDirectory() as tmp:
        cases = (
            ("плавающий тег",
             lambda f: f.replace(":RELEASE.2025-09-07T16-13-09Z", ":latest"),
             PIN, "плавающий тег"),
            ("чужой реестр",
             lambda f: f.replace(OUR_REGISTRY, "quay.io/minio/"),
             PIN, "не из нашего реестра"),
        )
        for name, mangle, target, expect in cases:
            fake = Path(tmp) / name.replace(" ", "-")
            subprocess.run(["cp", "-R", str(ROOT), str(fake)], check=True,
                           capture_output=True)
            path = fake / target
            path.write_text(mangle(path.read_text(encoding="utf-8")), encoding="utf-8")
            rc, out = probe(fake)
            if rc != 0 and expect in out:
                print(f"  ✓ {name} — красное, и сказано, что именно не так")
            else:
                red(f"  ✗ {name} обязано валить сторожа со словами «{expect}»")
                bad = 1

        # Копия адреса вернулась в compose и в тест — по одному случаю на каждую
        # поверхность: пропусти сторож любую, и адрес снова живёт в двух местах.
        for target, needle, replacement, expect in (
            ("docker-compose.yml", "  minio:\n",
             "  minio:\n    image: ghcr.io/vladislav15937/parts-platform/minio:RELEASE.2025-09-07T16-13-09Z\n",
             "задаёт образ сам"),
            (TESTS[0], "TestImages.minio()",
             '(Object) new Object() /* "ghcr.io/vladislav15937/parts-platform/minio:X" */',
             "литералом"),
        ):
            fake = Path(tmp) / ("copy-" + Path(target).name)
            subprocess.run(["cp", "-R", str(ROOT), str(fake)], check=True,
                           capture_output=True)
            path = fake / target
            text = path.read_text(encoding="utf-8")
            if needle not in text:
                red(f"  ✗ подделку негде поставить: в {target} нет «{needle}»")
                bad = 1
                continue
            path.write_text(text.replace(needle, replacement, 1), encoding="utf-8")
            rc, out = probe(fake)
            if rc != 0 and expect in out:
                print(f"  ✓ копия адреса в {Path(target).name} — красное")
            else:
                red(f"  ✗ копия адреса в {target} обязана валить сторожа")
                bad = 1

    return bad


def main() -> int:
    if "--selftest" in sys.argv:
        rc = selftest()
        print(GREEN % "Сторож проверен." if rc == 0 else RED % "Самопроверка не прошла.")
        return rc

    import os
    root = Path(os.environ.get("IMAGE_PIN_GUARD_ROOT", ROOT))
    print("Адрес образа записан один раз")
    rc = check(root)
    if rc == 0:
        print(GREEN % "Копий адреса нет, тег закреплён, зеркало наше.")
    else:
        red("Адрес образа разъехался — правьте ops/images.yml и уберите копии.")
    return rc


if __name__ == "__main__":
    sys.exit(main())
