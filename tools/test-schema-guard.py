#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сверка того, что два тестовых класса не заводят одну схему арендатора.

«Схему на два теста не делить» записано в корневом CLAUDE.md, повторено
в комментариях самих тестов — и нарушено четырежды за три дня, каждый раз
новым способом:

  данные      — SellerSearchTest и BazonWheelImportTest делили t_000106,
                и перенос колёс стал считать шесть остатков вместо пяти;
  имя владельца — LoginSessionTest и SoldItemsReportTest делили t_000124
                и оба заводили логин vladelec: владельца назвал тот класс,
                что отработал первым;
  имя продавца — то же самое этажом ниже;
  пароль      — и снова то же, номер схемы взяли по номеру задачи.

Каждый раз локально было зелено, а краснел CI: поодиночке класс заводит
фикстуру сам, сталкиваются они только в общем прогоне и только в том порядке,
где чужой класс идёт первым. Правило, нарушенное столько раз, держится
не внимательностью — его должен держать перебор, как tools/endpoint-coverage.py
держит правило «эндпоинт без экрана».

Что считается занятием схемы: вызов provisionTenants(...) — единственный способ,
которым тест создаёт схему арендатора и накатывает на неё миграции. Именно
вызов, а не упоминание строки: `"t_000066/parts/1.jpg"` — ключ в хранилище,
`{@code t_000124}` в комментарии — история, а login("t_000042") в TenantFilterTest
— проверка фильтра, которая в базу не ходит вовсе. Считать их занятием значило бы
краснеть там, где столкнуться нечему, и такую проверку отключили бы в первый
же день. По той же причине перед разбором гасятся комментарии и текст строк
(`code_only`): закомментированный вызов и `{@code provisionTenants(TENANT)}`
в javadoc — пример, а не занятие.

**Свободен «здесь» и свободен «в main» — разные вопросы, и сторож отвечал
на первый.** Пока PR задачи 0049 ждал вердикта разбора, в `main` уехала 0064
и заняла `t_000153` — тот же номер, что взяла себе ждавшая ветка. В рабочей
копии столкновения нет вовсе: чужой файл появился в `main` после ответвления.
Локально зелено, краснеет CI — после двадцати минут прогона, не тем местом
и не у того, кто занял номер первым. Причём наказывает это ровно за то
поведение, которого мы добиваемся: чем дольше ветка ждёт настоящего разбора,
тем вероятнее, что её номер заняли.

Поэтому сторож сверяется не только с рабочей копией, но и с `origin/main`,
и складывает не два дерева, а **то, что получится после слияния**: у файла,
который ветка правит, побеждает версия ветки; у файла, который она не трогала,
— версия `origin/main`; файл, удалённый веткой, не считается вовсе. Иначе
сторож краснел бы на собственной правке ветки — а ложная тревога здесь дороже
пропуска: на неё натыкается каждый прогон, и кончается это тем, что проверку
отключают.

Разбор идёт по дереву, которое уже есть локально; `git fetch` сторож
не делает и без `origin/main` **не падает** — говорит, что сверка не выполнена,
и проверяет рабочую копию, как раньше. Сеть у него не всегда есть (`./mvnw -o`
здесь не случайность), а сторож, роняющий прогон из-за неподтянутой ссылки,
живёт до первого такого утра.

Ниже — список пар, делящих схему намеренно, с причиной у каждой. Пометка
без причины не принимается: причина и есть то, что отличает разбор от отписки.

Чего проверка не видит — два места, и оба названы нарочно.

**Запись в `public.tenant_registry`** — второй общий ресурс: тест, чистящий
чужую строку реестра, отключает соседу вход. Здесь она не проверяется, потому
что номер записи по соглашению равен номеру схемы (`t_000144` — `tenant_id =
144`), а номер схемы стережётся. Пара ExportRoleTest и OrganizationAuditTest
делила ровно то и другое разом, и разошлись они одним движением. Заводя схему,
поставьте тот же номер и в реестр — иначе столкновение переедет туда, куда
сторож не смотрит.

**Схема, заведённая мимо `provisionTenants`**, — например прямым
`jdbc.execute("CREATE SCHEMA IF NOT EXISTS t_000089")`, — проходит молча:
в хозяева такой класс не попадает, в неразобранное тоже. Сегодня так не делает
никто (единственный `CREATE SCHEMA` живёт в `PostgresTestBase`, и зовут его
из `provisionTenants`), и держится это тем, что схему без миграций всё равно
не на что использовать. Но если такой способ заведётся, сторож о нём не узнает:
считать `CREATE SCHEMA` он не умеет, и это ограничение, а не недосмотр —
ловить занятие схемы надо там, где оно одно, а не в каждом способе его
написать. Появится второй способ — учить сторожа придётся вместе с ним.

  ./tools/test-schema-guard.py [--list] [--selftest]
"""
import hashlib
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TESTS = os.path.join(ROOT, "src", "test", "java")

# Приставка к пути файла, чья версия взята из origin/main, а не из рабочей
# копии. Она же отличает такого хозяина схемы в сообщении: «столкнулся сам
# с собой» и «номер занят в main, пока ветка ждала» — разные новости.
REMOTE = "origin/main:"

# Схема -> (классы, которым позволено её делить, почему это не столкновение).
# Классы перечислены поимённо: третий класс, пришедший к той же схеме, обязан
# разбираться заново — разрешение выдано конкретной паре, а не номеру навсегда.
ALLOWED = {
    "t_000117": (
        ("CustomerControllerTest", "FeedLifecycleTest"),
        "разобрано при заведении сторожа (задача 0059). Общих ключей у них нет: "
        "CustomerControllerTest живёт на сотрудниках и покупателях, "
        "FeedLifecycleTest — на выгрузках, остатке и отметках о заборе прайса, "
        "и обе фикстуры пересоздают своё в @BeforeEach, а не достраивают "
        "найденное — то есть не зависят от того, кто отработал первым. Общая "
        "у них одна вещь, запись реестра 117: каждая переписывает её своим "
        "кодом там же, в @BeforeEach. Прогнано в обоих порядках, включая "
        "reversealphabetical, — зелено. Разводить нечего, а лишняя схема "
        "стоит наката миграций",
    ),
}

CONST = re.compile(r'static\s+final\s+String\s+([A-Za-z_$][\w$]*)\s*=\s*"(t_\d{6})"')
SCHEMA = re.compile(r't_\d{6}')
CALL = "provisionTenants"


def java_files(root):
    for base, _, names in os.walk(root):
        for name in sorted(names):
            if name.endswith(".java"):
                yield os.path.join(base, name)


def code_only(text):
    """Файл без комментариев и без текста строк — пробелами той же длины.

    Убирается всё, где `provisionTenants(...)` может стоять примером, а не
    вызовом: закомментированный вызов, `{@code provisionTenants(TENANT)}`
    в javadoc, строка подсказки. Ложное срабатывание, а не пропуск, — но
    сторож, краснеющий на строке из документации, кончает тем, что его
    отключают.

    Строка, которая целиком является именем схемы (`"t_000042"`), остаётся:
    ею зовут `provisionTenants` напрямую, и это настоящее занятие. Всё
    остальное содержимое кавычек гасится — вместе с `"t_000066/parts/1.jpg"`,
    где номер схемы это ключ в хранилище.

    Длина сохраняется, чтобы смещения не разъезжались; текстовые блоки Java
    разбираются отдельно — внутри `\"\"\"…\"\"\"` двойной слэш это текст.
    """
    out, i, n = list(text), 0, len(text)

    def blank(start, end):
        for k in range(start, min(end, n)):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        if text[i:i + 3] == '"""':
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            blank(i + 3, end - 3)
            i = end
            continue
        quote = text[i]
        if quote in "\"'":
            j = i + 1
            while j < n and text[j] != quote:
                j += 2 if text[j] == "\\" else 1
            if not SCHEMA.fullmatch(text[i + 1:j]):
                blank(i + 1, j)
            i = j + 1
            continue
        if text[i:i + 2] in ("//", "/*"):
            if text[i + 1] == "/":
                end = text.find("\n", i)
            else:
                end = text.find("*/", i + 2)
                end = end + 2 if end >= 0 else -1
            end = n if end < 0 else end
            blank(i, end)
            i = end
            continue
        i += 1
    return "".join(out)


def provisioned(raw):
    """Схемы, которые этот файл заводит: аргументы provisionTenants(...).

    Константы разворачиваются по объявлениям того же файла — почти все вызовы
    написаны как provisionTenants(TENANT). Неразвернувшийся аргумент возвращается
    отдельно: молча пропущенный, он означал бы схему, которой сторож не видит.
    """
    text = code_only(raw)
    consts = dict(CONST.findall(text))
    schemas, unresolved = set(), set()
    for m in re.finditer(re.escape(CALL) + r'\s*\(', text):
        depth, i = 1, m.end()
        while i < len(text) and depth:
            depth += (text[i] == "(") - (text[i] == ")")
            i += 1
        for arg in text[m.end():i - 1].split(","):
            arg = arg.strip()
            if not arg or arg.startswith("String..."):     # само объявление метода
                continue
            literal = re.fullmatch(r'"(t_\d{6})"', arg)
            name = literal.group(1) if literal else consts.get(arg)
            if name:
                schemas.add(name)
            else:
                unresolved.add(arg)
    return schemas, unresolved


def survey(root=TESTS, remote=None):
    """Кто какую схему заводит, какие номера вообще встречаются, что не разобрано.

    `remote` — файлы, чья версия из origin/main окажется в main после слияния:
    {абсолютный путь: (как называть, содержимое)}. Местная копия такого файла
    пропускается, иначе один и тот же класс числился бы хозяином дважды —
    с устаревшим номером из ветки и с новым из main, — и сторож краснел бы
    на столкновении файла с самим собой.

    Неразобранный аргумент `provisionTenants(...)` с той стороны не считается:
    это забота прогона на main, а не ветки, которая файла даже не трогала.
    """
    remote = remote or {}
    owners, mentioned, unresolved = {}, set(), []
    for path in java_files(root):
        if path in remote:
            continue
        text = open(path, encoding="utf-8").read()
        mentioned |= set(SCHEMA.findall(text))
        taken, unknown = provisioned(text)
        for schema in taken:
            owners.setdefault(schema, []).append(path)
        for arg in sorted(unknown):
            unresolved.append((path, arg))
    for _, (name, text) in sorted(remote.items()):
        mentioned |= set(SCHEMA.findall(text))
        taken, _ = provisioned(text)
        for schema in taken:
            owners.setdefault(schema, []).append(name)
    return owners, mentioned, unresolved


def free_numbers(mentioned, count=8):
    """Свободные номера — чтобы сообщение об ошибке говорило, что делать.

    Занятым считается любой номер, встретившийся в тестах хоть как-нибудь,
    включая комментарии и ключи хранилища: сторож их не считает занятием схемы,
    но новый тест, взявший такой номер, будет читаться как второй хозяин.
    """
    busy = {int(s[2:]) for s in mentioned}
    return [f"t_{n:06d}" for n in range(1, 1000) if n not in busy][:count]


def classes_of(paths):
    return tuple(sorted(os.path.basename(p)[:-len(".java")] for p in paths))


def where(path):
    """Путь от корня репозитория — но только если он внутри него.

    Проверка самой проверки работает на временном каталоге, и `relpath` выдал бы
    там дорожку из двух десятков `..`, в которой имя файла не найти. Путь
    с приставкой `origin/main:` — уже от корня и на диске не лежит вовсе.
    """
    if path.startswith(REMOTE):
        return path
    inside = os.path.relpath(path, ROOT)
    return path if inside.startswith("..") else inside


def check(root=TESTS, allowed=ALLOWED, remote=None, extra_mentions=()):
    owners, mentioned, unresolved = survey(root, remote)
    problems = []
    free = ", ".join(free_numbers(mentioned | set(extra_mentions)))

    for path, arg in unresolved:
        problems.append(
            f"{where(path)}\n"
            f"      зовёт {CALL}({arg}), и сторож не понял, какая это схема.\n"
            f"      Схему, которой он не видит, он и не стережёт — напишите\n"
            f"      имя строкой или константой того же файла.")

    shared = {s: p for s, p in owners.items() if len(p) > 1}

    for schema in sorted(shared):
        paths = sorted(shared[schema])
        here = classes_of(paths)
        listed_at = "\n".join(f"        {where(p)}" for p in paths)
        record = allowed.get(schema)
        if record and tuple(sorted(record[0])) == here:
            if not (record[1] or "").strip():
                problems.append(
                    f"{schema}\n      помечен разрешённым без причины. Пометка без "
                    f"причины не принимается:\n      напишите, почему эти два класса "
                    f"не могут столкнуться.")
            continue
        if record:
            problems.append(
                f"{schema}\n      разрешён для {', '.join(record[0])}, а заводят её\n"
                f"{listed_at}\n"
                f"      Разрешение выдано паре, а не номеру: разберите заново\n"
                f"      или разведите. Свободные номера: {free}.")
            continue
        elsewhere = (
            "      Один из них — версия из origin/main: номер занят в main тем,\n"
            "      кто слился раньше, пока эта ветка ждала разбора. В рабочей\n"
            "      копии столкновения нет и не будет — оно появится в момент\n"
            "      слияния и покраснеет на main, не у того, кто занял номер\n"
            "      первым. Перенумеруйте свою схему, а не чужую.\n"
        ) if any(p.startswith(REMOTE) for p in paths) else ""
        problems.append(
            f"{schema}\n      заводят несколько классов разом:\n{listed_at}\n"
            f"{elsewhere}"
            f"      Схему на два теста не делить: фикстуры сталкиваются логинами,\n"
            f"      номерами и остатком, и красным это становится только в том\n"
            f"      порядке, где чужой класс идёт первым. Возьмите свободный номер:\n"
            f"        {free}\n"
            f"      Тот же номер поставьте и в public.tenant_registry, если класс\n"
            f"      туда пишет. Если деление намеренное — внесите схему в ALLOWED\n"
            f"      внутри tools/test-schema-guard.py с причиной и покажите прогон\n"
            f"      в обратном порядке:\n"
            f"      ./mvnw -o test -Dsurefire.runOrder=reversealphabetical -Dtest='A,B'")

    for schema in sorted(allowed):
        if schema not in shared:
            problems.append(
                f"{schema}\n      числится разрешённым к делению, а делить его больше\n"
                f"      некому. Список устарел — уберите строку.")

    return problems, owners, shared


def git(repo, *args, stdin=None, binary=False):
    """Вызов git, который никогда не роняет прогон: не вышло — вернулся None.

    Сюда попадают и «не репозиторий», и «нет такой ссылки», и отсутствующий
    сам git. Ни одно из этого не повод валить проверку схем: сеть у сторожа
    есть не всегда, а отключают его после первого утра, когда он покраснел
    не на дефекте.
    """
    try:
        done = subprocess.run(["git", "-C", repo] + list(args), input=stdin,
                              capture_output=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return None
    if done.returncode:
        return None
    return done.stdout if binary else done.stdout.decode("utf-8", "replace")


def tree(repo, ref, sub):
    """Пути и хэши блобов поддерева: {путь от корня репозитория: sha}."""
    out = git(repo, "ls-tree", "-r", "-z", ref, "--", sub)
    if out is None:
        return None
    files = {}
    for entry in out.split("\0"):
        if not entry:
            continue
        meta, _, path = entry.partition("\t")
        parts = meta.split()
        if len(parts) == 3 and parts[1] == "blob":
            files[path] = parts[2]
    return files


def blob_sha(path):
    """Хэш рабочей копии по правилам git — чтобы сравнивать с ls-tree.

    Считается здесь, а не вызовом `git hash-object` на каждый файл: их сотни.
    Перевод строк не нормализуется, и это осознанно — расхождение означает
    «файл считается тронутым», то есть версия ветки побеждает. Ошибка в эту
    сторону даёт пропуск, а не ложную тревогу.
    """
    data = open(path, "rb").read()
    digest = hashlib.sha1()
    digest.update(b"blob %d\0" % len(data))
    digest.update(data)
    return digest.hexdigest()


def blobs(repo, shas):
    """Содержимое перечисленных объектов — одним вызовом git, а не по одному.

    Ответ `cat-file --batch` идёт подряд: строка «sha blob размер», ровно
    столько байт и перевод строки. Разбор ведётся по размеру, а не по
    разделителю, — в исходниках есть и `\\n`, и что угодно ещё.
    """
    if not shas:
        return {}
    data = git(repo, "cat-file", "--batch",
               stdin=("\n".join(shas) + "\n").encode(), binary=True)
    if data is None:
        return {}
    at, bodies = 0, {}
    try:
        for sha in shas:
            nl = data.index(b"\n", at)
            header = data[at:nl].split()
            size = int(header[2])
            bodies[sha] = data[nl + 1:nl + 1 + size].decode("utf-8", "replace")
            at = nl + 1 + size + 1
    except (ValueError, IndexError):
        return {}
    return bodies


def from_main(repo, root):
    """Версии тестовых файлов, которые окажутся в main после слияния ветки.

    Возвращает ({абсолютный путь: (как называть, содержимое)}, упомянутые
    номера, причина отказа или None). Правило ровно то, по которому сливает
    сам git, и от него зависит, тревога настоящая или ложная:

      ветка правила файл            → побеждает версия ветки (её и так видно);
      ветка файл не трогала         → побеждает версия origin/main;
      ветка файл удалила            → после слияния его нет, считать нечего;
      файла в ветке нет и не было   → он появился в main после ответвления,
                                      и это ровно тот случай, ради которого
                                      сверка заведена.

    «Не трогала» определяется сравнением с точкой расхождения, а не с main:
    иначе всякий файл, который main поправил, считался бы тронутым веткой.
    """
    sub = os.path.relpath(root, repo)
    if git(repo, "rev-parse", "--git-dir") is None:
        return {}, set(), "рядом нет репозитория git"
    if git(repo, "rev-parse", "--verify", "--quiet", "origin/main^{commit}") is None:
        return {}, set(), ("ссылки origin/main нет — подтяните её: "
                           "git fetch --no-tags origin main:refs/remotes/origin/main")
    base = git(repo, "merge-base", "HEAD", "origin/main")
    if base is None:
        return {}, set(), "не нашлась точка расхождения с origin/main (мелкая копия?)"

    main_tree = tree(repo, "origin/main", sub)
    base_tree = tree(repo, base.strip(), sub)
    if main_tree is None or base_tree is None:
        return {}, set(), "не прочиталось дерево тестов origin/main"

    differing, winners = {}, {}
    for path, main_sha in main_tree.items():
        local = os.path.join(repo, path)
        here = blob_sha(local) if os.path.isfile(local) else None
        if here == main_sha:
            continue                       # одно и то же, разбирать нечего
        differing[path] = main_sha
        if here == base_tree.get(path):    # ветка файла не трогала (и не удаляла)
            winners[path] = main_sha

    bodies = blobs(repo, sorted(set(differing.values())))
    mentions = set()
    for sha in differing.values():
        mentions |= set(SCHEMA.findall(bodies.get(sha, "")))
    files = {os.path.join(repo, path): (REMOTE + path, bodies.get(sha, ""))
             for path, sha in winners.items()}
    return files, mentions, None


# Проверка самой проверки. Сторож, который не краснеет на своём же дефекте,
# хуже отсутствующего: его зелёный цвет предъявляют как доказательство.
# Установить это рассуждением нельзя, а стоит оно миллисекунды — поэтому
# идёт перед каждым прогоном.
FIXTURE = {
    "AlphaTest.java":
        'class AlphaTest {\n'
        '    private static final String TENANT = "t_000501";\n'
        '    static void setUp() { provisionTenants(TENANT); }\n'
        '}\n',
    "BetaTest.java":
        'class BetaTest {\n'
        '    private static final String TENANT = "t_000501";\n'
        '    static void setUp() { provisionTenants(TENANT); }\n'
        '}\n',
    "GammaTest.java":
        'class GammaTest {\n'
        '    private static final String TENANT = "t_000502";\n'
        '    static void setUp() { provisionTenants(TENANT); }\n'
        '}\n',
    # Ни одного provisionTenants: упоминание в комментарии, ключ в хранилище
    # и строка в утверждении. Столкнуться тут нечему, и краснеть сторож обязан
    # не на этом — иначе его отключат в первый же день.
    "MentionsTest.java":
        'class MentionsTest {\n'
        '    // была t_000502, разошлись\n'
        '    String key = "t_000502/parts/1/snimok.jpg";\n'
        '    void check() { assertThat(seen).isEqualTo("t_000502"); }\n'
        '}\n',
    # Вызов есть, но он закомментирован, и ещё один стоит примером в javadoc.
    # Схему такой класс не заводит — засчитанный, он дал бы ложного второго
    # хозяина у GammaTest.
    "ExampleTest.java":
        '/** Схему берут так: {@code provisionTenants(TENANT)}. */\n'
        'class ExampleTest {\n'
        '    private static final String TENANT = "t_000502";\n'
        '    // static void setUp() { provisionTenants(TENANT); }\n'
        '    String hint = "пример: provisionTenants(TENANT)";\n'
        '}\n',
}


def selftest():
    import shutil
    import tempfile

    root = tempfile.mkdtemp(prefix="test-schema-guard-")
    try:
        for name, body in FIXTURE.items():
            open(os.path.join(root, name), "w", encoding="utf-8").write(body)

        failures = []

        # 1. Два класса на одной схеме, которой нет в списке, — прогон падает,
        #    и в сообщении названы оба файла.
        problems, _, _ = check(root, allowed={})
        text = "\n".join(problems)
        if not problems:
            failures.append("два класса на одной схеме не названы нарушением — "
                            "это ровно тот дефект, ради которого сторож заведён")
        elif not ("AlphaTest.java" in text and "BetaTest.java" in text):
            failures.append("нарушение названо, а оба файла в сообщении — нет: "
                            "искать вручную придётся то, что сторож уже знает")
        if "t_000502" in text:
            failures.append("схема с одним хозяином названа нарушением")
        if "MentionsTest" in text:
            failures.append("упоминание схемы в комментарии, в ключе хранилища "
                            "или в утверждении засчитано занятием схемы")
        if "ExampleTest" in text:
            failures.append("закомментированный вызов или пример в javadoc "
                            "засчитан занятием схемы — сторож, краснеющий "
                            "на документации, будет отключён")

        # 2. Разрешённая пара прогон не роняет.
        allowed = {"t_000501": (("AlphaTest", "BetaTest"), "разобрано, общих ключей нет")}
        problems, _, _ = check(root, allowed=allowed)
        if problems:
            failures.append("разрешённая пара роняет прогон: " + "; ".join(problems))

        # 3. Пометка без причины не принимается.
        problems, _, _ = check(root, allowed={"t_000501": (("AlphaTest", "BetaTest"), "  ")})
        if not problems:
            failures.append("пометка без причины принята — тогда список станет "
                            "способом отключить сторож, а не разбором")

        # 4. Третий класс, пришедший к разрешённой паре, разбирается заново.
        problems, _, _ = check(root, allowed={"t_000501": (("AlphaTest",), "одна и одна")})
        if not problems:
            failures.append("разрешение, выданное паре, покрыло другой состав классов")

        # 5. Запись, которой больше нечего разрешать, названа устаревшей.
        problems, _, _ = check(root, allowed=dict(
            allowed, t_000502=(("GammaTest", "DeltaTest"), "её больше нет")))
        if not any("устарел" in p for p in problems):
            failures.append("устаревшая запись списка не названа — список "
                            "переживёт то, что он описывает")

        return failures
    finally:
        shutil.rmtree(root, ignore_errors=True)


def a_class(name, schema, extra=""):
    return (f'class {name} {{\n'
            f'    private static final String TENANT = "{schema}";\n'
            f'    static void setUp() {{ provisionTenants(TENANT); }}\n'
            f'{extra}}}\n')


def merge_selftest():
    """Проверка второй половины сторожа — той, что смотрит в origin/main.

    Заводится настоящий репозиторий с настоящей развилкой: на словах эту
    логику проверить нельзя, потому что вся она про то, какую версию файла
    выберет git при слиянии. Случаи подобраны так, чтобы поймать обе беды
    сразу — и пропуск (номер занят в main, а сторож молчит), и ложную
    тревогу (номер «занят» файлом, который правит сама ветка).
    """
    import shutil
    import tempfile

    repo = tempfile.mkdtemp(prefix="test-schema-guard-merge-")
    tests = os.path.join(repo, "src", "test", "java")
    os.makedirs(tests)

    def run(*args):
        subprocess.run(["git", "-C", repo] + list(args),
                       check=True, capture_output=True)

    def put(name, body):
        open(os.path.join(tests, name), "w", encoding="utf-8").write(body)

    def commit(message):
        run("add", "-A")
        run("-c", "user.email=guard@example", "-c", "user.name=guard",
            "-c", "commit.gpgsign=false",
            "commit", "-q", "--no-verify", "-m", message)
        return git(repo, "rev-parse", "HEAD").strip()

    try:
        run("init", "-q")
        run("symbolic-ref", "HEAD", "refs/heads/main")

        # Точка расхождения: то, что видели обе стороны.
        put("SharedTest.java", a_class("SharedTest", "t_000502"))
        put("GoneTest.java", a_class("GoneTest", "t_000504"))
        put("EpsilonTest.java", a_class("EpsilonTest", "t_000506"))
        put("ThetaTest.java", a_class("ThetaTest", "t_000510"))
        base = commit("основа")

        # Что уехало в main, пока ветка ждала разбора: новый класс занял
        # t_000503, а старый переехал с t_000506 на t_000507.
        put("NewMainTest.java", a_class("NewMainTest", "t_000503"))
        put("EpsilonTest.java", a_class("EpsilonTest", "t_000507"))
        commit("волна в main")
        run("update-ref", "refs/remotes/origin/main", "HEAD")

        # Ветка: ответвилась от основы, дальше правит рабочую копию.
        run("checkout", "-q", base)
        put("SharedTest.java", a_class("SharedTest", "t_000509"))  # перенумеровала своё
        put("ThetaTest.java", a_class("ThetaTest", "t_000510",
                                      "    // правка, номера не касающаяся\n"))
        os.remove(os.path.join(tests, "GoneTest.java"))
        put("BetaTest.java", a_class("BetaTest", "t_000503"))    # занято в main
        put("DeltaTest.java", a_class("DeltaTest", "t_000504"))  # освобождено веткой
        put("ZetaTest.java", a_class("ZetaTest", "t_000507"))    # занято в main
        put("EtaTest.java", a_class("EtaTest", "t_000506"))      # освобождено в main
        put("IotaTest.java", a_class("IotaTest", "t_000502"))    # освобождено веткой

        failures = []
        remote, mentions, note = from_main(repo, tests)
        if note:
            failures.append(f"сверка с origin/main не выполнилась там, где "
                            f"ссылка есть: {note}")
        problems, _, _ = check(tests, allowed={}, remote=remote,
                               extra_mentions=mentions)
        text = "\n".join(problems)

        for schema, mine, theirs in (("t_000503", "BetaTest", "NewMainTest"),
                                     ("t_000507", "ZetaTest", "EpsilonTest")):
            if schema not in text:
                failures.append(
                    f"{schema} занят в origin/main и взят веткой, а сторож молчит — "
                    f"это ровно тот дефект, ради которого сверка заведена: "
                    f"локально чисто, краснеет CI после слияния")
            elif not (mine in text and REMOTE + f"src/test/java/{theirs}.java" in text):
                failures.append(
                    f"{schema} назван, а кто с кем столкнулся — нет: нужны оба, "
                    f"и сторона main обязана быть подписана как origin/main")

        if "t_000502" in text:
            failures.append("номер, который ветка освободила, перенумеровав "
                            "свой же файл, засчитан занятым его прежней версией "
                            "из main — тогда красное не снимается ничем, и "
                            "перенумероваться в ответ на тревогу нельзя вовсе")
        if "t_000510" in text:
            failures.append("файл, который ветка правит, засчитан дважды — своей "
                            "версией и версией из main: столкновение с самим собой "
                            "на каждом прогоне")
        if "t_000504" in text:
            failures.append("номер файла, удалённого веткой, засчитан занятым: "
                            "после слияния этого файла не будет вовсе")
        if "t_000506" in text:
            failures.append("номер, освобождённый в main, засчитан занятым — "
                            "сторож сравнил ветку с устаревшей версией файла")

        # И главное: без ссылки на origin/main он не падает, а говорит словами.
        run("update-ref", "-d", "refs/remotes/origin/main")
        remote, mentions, note = from_main(repo, tests)
        if remote or not note:
            failures.append("без ссылки origin/main сверка не объявила себя "
                            "невыполненной — молчаливый пропуск читается "
                            "как проверенное")
        problems, _, _ = check(tests, allowed={}, remote=remote,
                               extra_mentions=mentions)
        if any(s in "\n".join(problems) for s in ("t_000503", "t_000507")):
            failures.append("без ссылки origin/main сторож всё равно ссылается "
                            "на её содержимое")

        return failures
    except (OSError, subprocess.SubprocessError) as e:
        # Самого git нет — тогда и сверять не с чем: сторож работает как
        # раньше, по рабочей копии, и валить прогон из-за этого нельзя.
        # Всё остальное (git есть, а фикстура не завелась) — настоящая
        # поломка проверки, и молчать о ней значит предъявлять зелёный цвет,
        # ничем не обеспеченный.
        if isinstance(e, FileNotFoundError):
            return []
        return [f"репозиторий для сверки с origin/main не завёлся ({e}) — "
                f"эта половина сторожа ничего не доказывает"]
    finally:
        shutil.rmtree(repo, ignore_errors=True)


def main():
    broken = selftest() + merge_selftest()
    if broken:
        print("Проверка сломана и потому ничего не доказывает:\n")
        for line in broken:
            print("  •", line)
        return 1
    if "--selftest" in sys.argv:
        print("Сторож краснеет на общей схеме и на номере, занятом в origin/main;\n"
              "молчит на разрешённой паре, на своём же файле и без ссылки на main.")
        return 0

    remote, mentions, note = from_main(ROOT, TESTS)
    problems, owners, shared = check(remote=remote, extra_mentions=mentions)

    if "--list" in sys.argv:
        for schema, (classes, why) in sorted(ALLOWED.items()):
            print(f"{schema}: {', '.join(classes)}\n    {why}\n")

    if note:
        print(f"Сверка с origin/main не выполнена: {note}.\n"
              f"Номер, занятый в main после ответвления, отсюда не виден — "
              f"проверена только рабочая копия.\n")

    if problems:
        print("Схемы арендаторов в тестах: проверка не прошла\n")
        for p in problems:
            print("  •", p)
        return 1

    seen = "" if note else f", сверено с origin/main (файлов оттуда: {len(remote)})"
    print(f"Схем в тестах {len(owners)}, у каждой один хозяин "
          f"(делят намеренно {len(shared)}, все разобраны){seen}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
