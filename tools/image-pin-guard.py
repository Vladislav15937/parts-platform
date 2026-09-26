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

Пять вопросов, на которые он отвечает:

1. тег закреплён — плавающий `latest` в прогоне запрещён (корневой CLAUDE.md);
2. зеркало наше — адрес ведёт в наш GHCR, а не в чужой реестр, потому что
   чужой уже исчезал дважды;
3. копий нет — ни в compose (оба обязаны `include` фрагмент и не задавать
   `image` сами), ни в тестах (они берут адрес из support/TestImages);
4. у копии тег назван суммой источника, а источник закреплён суммой, а не
   тегом (задача 0095). Иначе обновление зеркала молча не случается: задача CI
   не копирует, пока тег в реестре есть, — то есть сменённый источник остался
   бы только в файле, а в реестре лежали бы прежние байты;
5. **перебор**: каждый образ, который дерево откуда-то тянет, либо наш, либо
   назван в перечне с причиной. Это про то, чем болели обе задачи: 12 сентября
   2026 и 26 сентября образ уносили из чужого реестра, 13 сентября чужой реестр
   просто не ответил, — и каждый раз выяснялось, что образов у нас больше,
   чем помнилось. Перечень с причинами — очередь работы, а не разрешение.

Документы и файлы задач сторож не трогает: там адреса — история, а не
настройка. Разница простая: по `ops/images.yml` образ ТЯНУТ, по `docs/` —
вспоминают, почему он такой.
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PIN = "ops/images.yml"

# Наш реестр. Зеркало обязано лежать здесь: и quay, и Docker Hub уже уносили
# образ из-под ног, и решение владельца от 26.09.2026 — «своё зеркало в GHCR».
OUR_REGISTRY = "ghcr.io/vladislav15937/parts-platform/"

# Сервисы, чей образ зеркалим. Ключ — имя сервиса в compose; у minio и mc это
# же имя бинарника выпуска (их мы собираем), у postgres и liquibase образ живой
# и копируется из источника — см. `x-mirror-sources` в самом фрагменте.
MIRRORED = ("minio", "mc", "postgres", "liquibase")

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

# Все файлы, которые сторож читает. Список один на два места намеренно: по нему
# идёт и проверка мёртвых адресов, и сборка временного дерева в самопроверке.
# Два списка одного и того же — ровно тот дефект, который эта задача и чинит.
LOOKED = (
    PIN,
    *COMPOSES,
    *TESTS,
    "tools/mirror/minio.Dockerfile",
    "tools/mirror/mc.Dockerfile",
    "src/test/java/ru/partsflow/support/TestImages.java",
)

# Переехавший файл списка — одно сообщение на обе половины сторожа, и это
# не косметика (задача 0181). До неё половины расходились: `_plant()` падал
# `FileNotFoundError`, а `check()` такой файл ТИХО ПРОПУСКАЛ, — то есть сторож
# молчал ровно на том изменении, которое делает его слепым. Переименовали тест
# или рецепт зеркала — копия адреса в нём больше не проверяется никем, а прогон
# при этом зелёный. Именно в этом единственном случае копия адреса и могла
# уехать незамеченной, так что молчать здесь нельзя ни одной половине.
GONE_MSG = "файл из списка не найден, сторож неполон"


class GuardIncomplete(RuntimeError):
    """Список сторожа разошёлся с деревом: собирать самопроверку не из чего."""


def looked_missing(root: Path) -> list[str]:
    """Файлы списка, которых в дереве нет. Пусто — список и дерево сходятся."""
    return [rel for rel in LOOKED if not (root / rel).is_file()]


# --- перебор образов ---------------------------------------------------------
#
# Чужой образ, не названный здесь, — красное. Не потому, что чужой плох,
# а потому, что про каждый надо один раз ответить: остановит ли его реестр наш
# прогон, и если да, то почему мы это терпим. Трижды подряд оказывалось, что
# образов больше, чем помнилось (задачи 0063, 0180, 0095).
#
# Пометка — это очередь работы, а не разрешение: там, где написано «очередь»,
# зеркала нет и прогон/ячейка от чужого реестра зависят.
EXCUSED = {
    "postgres:16-alpine": (
        "очередь: ячейка и точка возврата",
        "compose разработки, боевой compose (база и wal-archive-init) и "
        "ops/restore-pitr.sh. Прогон CI берёт зеркало (ops/images.yml), а здесь тот "
        "же образ поднимает живую базу клиента, её точку возврата и физическую "
        "копию: смена адреса пересоздаёт контейнер базы, и PITR обязан идти тем же "
        "образом, что и кластер. Отдельная задача с репетицией восстановления."),
    "apache/kafka:3.8.1": (
        "прогон её не поднимает",
        "Kafka стоит под профилем compose, ни одна задача CI её не тянет — "
        "недоступность Docker Hub на прогон не влияет. Ячейке образ нужен один раз, "
        "при подъёме."),
    "caddy:2-alpine": (
        "очередь: терминатор",
        "терминатор ячейки и шаг CI «Конфигурация терминатора» (caddy validate). "
        "Прогон от Docker Hub тут зависит, и это очередь работы: через этот "
        "контейнер идёт весь трафик ячейки, пересоздание — отдельная задача."),
    "prom/prometheus:v2.53.0": (
        "очередь: наблюдение",
        "сборщик метрик ячейки и два шага CI с promtool. Та же очередь, что "
        "у терминатора: прогон от Docker Hub зависит."),
    "prom/pushgateway:v1.9.0": (
        "прогон её не тянет",
        "принимает отметки бэкапа; в CI по нему идёт только `compose config`, "
        "то есть разбор текста без выкачки."),
    "quay.io/prometheus/alertmanager:v0.27.0": (
        "не Docker Hub",
        "лежит на quay, и CI его не тянет — только `compose config`. Что quay тоже "
        "кончается, мы знаем (26.09.2026): это очередь, но не этой задачи."),
    "curlimages/curl:8.11.1": (
        "доступность проверяется мимо кэша (0155)",
        "сторож канала тревог (alert-watch) и проба `--probe`. Зеркала у него нет, "
        "и это названное решение, а не забывчивость: доступность спрашивает шаг CI "
        "«Образ канала тревог доступен мимо кэша» (tools/alert-image-guard.py) — "
        "протоколом, у самого реестра. Значит про пропажу человек узнаёт ДО выкладки, "
        "а не в её минуту; пережить пропажу это не помогает — зеркало остаётся "
        "очередью (задача 0185). Довод и цена — ops/CLAUDE.md, «Образ канала тревог: "
        "проверка вместо зеркала»."),
    "node:22-alpine": (
        "очередь: сборка артефакта",
        "сборка боевого образа (Dockerfile). Это провенанс того самого файла, "
        "который уезжает клиенту, и менять его источник надо отдельной задачей."),
    "maven:3.9-eclipse-temurin-21": (
        "очередь: сборка артефакта",
        "там же, в Dockerfile."),
    "eclipse-temurin:21-jre-alpine": (
        "очередь: сборка артефакта",
        "там же: основа боевого образа."),
    "alpine:3.22": (
        "круг",
        "основа рецептов зеркала (tools/mirror/*.Dockerfile). Зеркалить её в то же "
        "зеркало — круг: собирать minio было бы нечем ровно в ту минуту, когда "
        "Docker Hub недоступен. Нужен второй источник, а не своё зеркало."),
}

# Где перебор ищет. Список свой, а не LOOKED: тот собран под другой вопрос
# (копии адреса зеркалённого образа), и склеивать два разных вопроса в один
# список значит однажды ответить не на тот.
SWEPT_YAML = (
    "docker-compose.yml",
    "docker-compose.prod.yml",
    "db/docker-compose.yml",
    ".github/workflows/ci.yml",
    ".github/workflows/deploy.yml",
)
SWEPT_DOCKERFILES = (
    "Dockerfile",
    "tools/mirror/minio.Dockerfile",
    "tools/mirror/mc.Dockerfile",
)
SWEPT_GLOBS = ("ops/*.sh", "tools/*.sh")

# Адрес образа: [реестр/][путь/]имя:тег. Тег обязателен — без него docker берёт
# latest, и такой адрес отбивает отдельное правило выше.
IMAGE_RE = re.compile(
    r"(?<![\w$/:.\-])"
    r"((?:[a-z0-9][a-z0-9._\-]*(?:\.[a-z]{2,})?(?::\d+)?/)*"
    r"[a-z][a-z0-9._\-]*)"
    r":([A-Za-z0-9][A-Za-z0-9._\-]*)"
    r"(?![\w/:.\-])"
)

# Строки, в которых образ действительно ТЯНУТ. Без этого сужения перебор
# принимал бы за образ всякое `имя:число` — `localhost:8080` в первую очередь,
# — и его отключили бы в первый же день.
PULLS_RE = re.compile(r"^\s*image:\s|^\s*FROM\s|IMAGE=|docker\s+(run|build|pull)|imagetools\s+create")
CMD_RE = re.compile(r"docker\s+(run|build|pull)|imagetools\s+create")

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


def pin_sources(root: Path) -> dict[str, str]:
    """Источники копий — из того же файла, блок `x-mirror-sources`.

    Пусто у сервиса означает «этот образ мы собираем сами» (minio, mc)."""
    text = (root / PIN).read_text(encoding="utf-8")
    found: dict[str, str] = {}
    inside = False
    for line in text.splitlines():
        if re.match(r"^x-mirror-sources:\s*$", line):
            inside = True
            continue
        if inside:
            # Блок кончается первой строкой без отступа (`services:`).
            # Пустая строка и комментарий с отступом его не прерывают.
            if line[:1] not in (" ", "\t", ""):
                break
            m = re.match(r"^  ([A-Za-z0-9_.-]+):\s*(\S+)\s*$", line)
            if m:
                found[m.group(1)] = m.group(2)
    return found


def swept_files(root: Path) -> list[str]:
    names = list(SWEPT_YAML) + list(SWEPT_DOCKERFILES)
    for pattern in SWEPT_GLOBS:
        names += sorted(str(p.relative_to(root)) for p in root.glob(pattern))
    # Тесты: образ в тесте живёт там, где поднимают контейнер. Читать все
    # полторы тысячи файлов незачем — контейнер без этих классов не поднять.
    java = root / "src/test/java"
    if java.is_dir():
        for path in sorted(java.rglob("*.java")):
            text = path.read_text(encoding="utf-8", errors="replace")
            if "Container" in text or "DockerImageName" in text:
                names.append(str(path.relative_to(root)))
    return names


def images_pulled_by(path: Path) -> set[str]:
    """Адреса образов, которые этот файл тянет."""
    found: set[str] = set()
    in_cmd = False
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if line.startswith(("#", "//", "*", "/*")):
            in_cmd = False
            continue
        looks = in_cmd or bool(PULLS_RE.search(raw))
        # `${ПЕРЕМЕННАЯ:-образ:тег}` — обычная запись умолчания в shell, и без
        # этой замены дефис перед адресом уводил бы его от разбора: образ
        # `curlimages/curl:8.11.1` в ops/alert-channel-check.sh стоит именно так.
        probe = raw.replace(":-", " ")
        if looks:
            for name, tag in IMAGE_RE.findall(probe):
                # Имя без косой черты и тег, не начинающийся с цифры, — это
                # не образ, а `что-то:слово` (`jdbc:postgresql`, `partsflow:ci`).
                if "/" in name or tag[0].isdigit():
                    found.add(f"{name}:{tag}")
        # Команда docker продолжается, пока строка кончается обратной косой:
        # `caddy:2-alpine` в ci.yml стоит на четыре строки ниже `docker run`.
        in_cmd = (in_cmd or bool(CMD_RE.search(raw))) and raw.rstrip().endswith("\\")
    return found


def sweep(root: Path) -> int:
    """Перебор: каждый тянутый образ либо наш, либо назван в перечне."""
    bad = 0
    excused: dict[str, set[str]] = {}
    for name in swept_files(root):
        path = root / name
        if not path.is_file():
            continue
        for ref in sorted(images_pulled_by(path)):
            if ref.startswith(OUR_REGISTRY):
                # Адрес зеркала, выписанный литералом, — это шестая копия, и она
                # опаснее прочих: проверка выше смотрит только два compose и три
                # теста, а тянуть образ может и шаг прогона, и скрипт ячейки.
                red(f"  ✗ {name} выписывает адрес зеркала литералом: «{ref}»")
                red(f"      Адрес живёт в {PIN}, и берут его оттуда: compose —")
                red("      через `extends`, тесты — через support/TestImages.")
                bad = 1
                continue
            if ref in EXCUSED:
                excused.setdefault(ref, set()).add(name)
                continue
            red(f"  ✗ {name} тянет образ, которого нет ни в зеркале, ни в перечне: «{ref}»")
            red("      Либо зеркальте его (ops/images.yml + tools/mirror-images.sh),")
            red("      либо назовите в EXCUSED внутри этого сторожа — с причиной,")
            red("      отвечающей на вопрос «остановит ли его реестр наш прогон».")
            bad = 1
    # Своих адресов перебор не считает: они живут в одном ops/images.yml, а всякое
    # их появление в тянущем файле — копия, то есть красное выше.
    print(f"  ✓ перебор образов: чужих — {len(excused)}, у каждого названа причина")
    for ref in sorted(excused):
        print(f"      · {ref} — {EXCUSED[ref][0]}")
    return bad


def check(root: Path) -> int:
    bad = 0
    pin_path = root / PIN
    if not pin_path.is_file():
        red(f"  ✗ {PIN} — нет вовсе, а это единственное место, где записан адрес образа")
        return 1

    # Сперва — сходится ли список сторожа с деревом. Молчаливый пропуск здесь
    # означает сторожа, ослепшего от переименования файла (см. GONE_MSG).
    for rel in looked_missing(root):
        red(f"  ✗ {rel} — файла нет, а он в списке сторожа: {GONE_MSG}")
        red("      Файл переехал или переименован — поправьте LOOKED в этом стороже,")
        red("      иначе копию адреса в нём не проверяет больше никто.")
        bad = 1

    images = pin_images(root)
    sources = pin_sources(root)
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

        # У копии тег обязан быть назван суммой источника, а источник —
        # закреплён суммой. Иначе зеркало не обновляется молча: задача CI
        # «Зеркало образов» не копирует, пока тег в реестре есть.
        src = sources.get(service)
        if src and "@sha256:" not in src:
            red(f"  ✗ {service}: источник закреплён тегом, а не суммой — «{src}»")
            red("      Тег у автора образа плывёт: под `16-alpine` лежит то одна")
            red("      сборка, то другая, и копия перестала бы быть повторяемой.")
            bad = 1
        elif src:
            want = src.split("@sha256:")[1][:12]
            if not tag.endswith("-" + want):
                red(f"  ✗ {service}: тег зеркала не назван суммой источника")
                red(f"      В теге «{tag}», а сумма источника начинается на «{want}».")
                red("      Сменив источник, смените и тег — иначе «зеркало на месте»")
                red("      ответит про прежние байты, и копия не поедет никогда.")
                bad = 1
        if not bad:
            print(f"  ✓ {service}: {ref}")

    # Копии адреса в compose: фрагмент включён, своего image нет.
    for name in COMPOSES:
        path = root / name
        if not path.is_file():
            continue  # о пропавшем файле списка сказано выше, одним сообщением
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
            continue  # о пропавшем файле списка сказано выше, одним сообщением
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
    for name in LOOKED:
        path = root / name
        if not path.is_file():
            continue  # о пропавшем файле списка сказано выше, одним сообщением
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

    if sweep(root):
        bad = 1

    return bad


# --- проверка самого сторожа -------------------------------------------------
#
# Сторож, не краснеющий на дефекте, хуже отсутствующего, и установить это можно
# только попыткой. Подделки — ровно те способы, которыми правило перестаёт
# работать: вернули плавающий тег, вернули чужой реестр, вернули копию адреса
# в compose, вернули копию в тест. Настоящее дерево при этом обязано проходить:
# сторож, краснеющий на исправном дереве, отключат в первый же день.
def _plant(dst: Path) -> None:
    """Собрать временное дерево из тех файлов, которые сторож читает.

    **Не `cp -R` корня репозитория, и это цена живого отказа.** Рядом с рабочим
    каталогом лежат `.git`, `node_modules`, `target/` и — на машине
    разработчика — сотня брошенных рабочих деревьев на десятки гигабайт
    (`tasks/0171`). Копирование корня шло там минутами и валилось нечитаемым
    traceback'ом (`CalledProcessError` либо `FileNotFoundError`, смотря
    по состоянию дерева) вместо отчёта с ✓/✗: самопроверка не работала ровно
    там, где её запускают руками. У себя это не воспроизводилось — свежее
    изолированное дерево маленькое, — и на раннере тоже, потому что чекаут
    чистый. Тот самый класс «работает у меня, а не в среде, для которой
    писано», который эта задача и чинит.

    Файлы списка копируются по именам, поэтому время самопроверки больше
    не зависит от того, что лежит рядом. Сколько их — не написано здесь
    словом намеренно: написанное руками число разошлось со списком в первый
    же день (`tasks/0181`), поэтому его печатает самопроверка из `len(LOOKED)`.

    Переехавший файл — не `FileNotFoundError`, а `GuardIncomplete` теми же
    словами, какими о нём говорит `check()`: половины сторожа обязаны вести
    себя на этом одинаково.
    """
    gone = looked_missing(ROOT)
    if gone:
        raise GuardIncomplete(f"{GONE_MSG}: {', '.join(gone)}")
    for rel in LOOKED:
        target = dst / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / rel, target)


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

    # Число файлов печатается из самого списка, а не пишется словом: написанное
    # руками («восемь») разошлось со списком (их девять) в первый же день —
    # `tasks/0181`. По такому числу потом считают, и сверять его должна машина.
    gone = looked_missing(ROOT)
    if gone:
        red(f"  ✗ {GONE_MSG}: {', '.join(gone)}")
        red("      Дерево самопроверки собирают из этого списка — собирать не из чего.")
        return 1
    print(f"  ✓ список сторожа полон: файлов в нём {len(LOOKED)}")

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
            _plant(fake)
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
        #
        # Третий и четвёртый случаи — про задачу 0095. Тег копии, потерявший
        # сумму источника, означает зеркало, которое молча не обновляется;
        # чужой образ, не названный в перечне, — возвращённую зависимость
        # от чужого реестра, то есть ровно ту болезнь, из-за которой всё это есть.
        # Имя случая стоит в самом случае, а не собирается из имени файла:
        # два последних не про копию адреса вовсе, и подпись «копия адреса
        # в images.yml» описывала бы не то, что проверено.
        for label, target, needle, replacement, expect in (
            ("копия адреса в compose", "docker-compose.yml", "  minio:\n",
             "  minio:\n    image: ghcr.io/vladislav15937/parts-platform/minio:RELEASE.2025-09-07T16-13-09Z\n",
             "задаёт образ сам"),
            ("копия адреса в тесте", TESTS[0], "TestImages.minio()",
             '(Object) new Object() /* "ghcr.io/vladislav15937/parts-platform/minio:X" */',
             "литералом"),
            ("тег копии без суммы источника", PIN,
             "postgres:16-alpine-721873c34ceb", "postgres:16-alpine",
             "не назван суммой источника"),
            ("чужой образ вне перечня", "docker-compose.yml", "  kafka:\n",
             "  kafka:\n    image: redis:7-alpine\n",
             "ни в зеркале, ни в перечне"),
        ):
            # Каталог назван случаем, а не файлом: два случая правят один и тот
            # же docker-compose.yml, и общий каталог они бы затирали друг другу.
            fake = Path(tmp) / label.replace(" ", "-")
            _plant(fake)
            path = fake / target
            text = path.read_text(encoding="utf-8")
            if needle not in text:
                red(f"  ✗ подделку негде поставить: в {target} нет «{needle}»")
                bad = 1
                continue
            path.write_text(text.replace(needle, replacement, 1), encoding="utf-8")
            rc, out = probe(fake)
            if rc != 0 and expect in out:
                print(f"  ✓ {label} ({Path(target).name}) — красное")
            else:
                red(f"  ✗ {label} в {target} обязано валить сторожа словами «{expect}»")
                bad = 1

        # Седьмой случай — не подделка текста, а ПЕРЕЕХАВШИЙ файл, и он про саму
        # задачу 0181: до неё сторож проходил на этом зелёным (`check()` тихо
        # пропускал), а самопроверка падала traceback'ом. Дефект возвращается
        # переименованием, потому что установить это можно только попыткой.
        fake = Path(tmp) / "file-moved"
        _plant(fake)
        moved = fake / "tools/mirror/mc.Dockerfile"
        moved.rename(moved.parent / "mc.Dockerfile.moved")
        rc, out = probe(fake)
        if rc != 0 and GONE_MSG in out:
            print("  ✓ переехавший файл списка — красное, и сказано, что сторож неполон")
        else:
            red(f"  ✗ переехавший файл списка обязан валить сторожа словами «{GONE_MSG}»")
            bad = 1

    return bad


def main() -> int:
    if "--selftest" in sys.argv:
        try:
            rc = selftest()
        except GuardIncomplete as e:
            # Внятным сообщением, а не traceback'ом: самопроверка, падающая
            # стеком, не работает ровно там, где её запускают руками.
            red(f"  ✗ {e}")
            rc = 1
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
