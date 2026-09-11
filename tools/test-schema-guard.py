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
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TESTS = os.path.join(ROOT, "src", "test", "java")

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


def survey(root=TESTS):
    """Кто какую схему заводит, какие номера вообще встречаются, что не разобрано."""
    owners, mentioned, unresolved = {}, set(), []
    for path in java_files(root):
        text = open(path, encoding="utf-8").read()
        mentioned |= set(SCHEMA.findall(text))
        taken, unknown = provisioned(text)
        for schema in taken:
            owners.setdefault(schema, []).append(path)
        for arg in sorted(unknown):
            unresolved.append((path, arg))
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
    там дорожку из двух десятков `..`, в которой имя файла не найти.
    """
    inside = os.path.relpath(path, ROOT)
    return path if inside.startswith("..") else inside


def check(root=TESTS, allowed=ALLOWED):
    owners, mentioned, unresolved = survey(root)
    problems = []
    free = ", ".join(free_numbers(mentioned))

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
        problems.append(
            f"{schema}\n      заводят несколько классов разом:\n{listed_at}\n"
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


def main():
    broken = selftest()
    if broken:
        print("Проверка сломана и потому ничего не доказывает:\n")
        for line in broken:
            print("  •", line)
        return 1
    if "--selftest" in sys.argv:
        print("Сторож краснеет на общей схеме и молчит на разрешённой паре.")
        return 0

    problems, owners, shared = check()

    if "--list" in sys.argv:
        for schema, (classes, why) in sorted(ALLOWED.items()):
            print(f"{schema}: {', '.join(classes)}\n    {why}\n")

    if problems:
        print("Схемы арендаторов в тестах: проверка не прошла\n")
        for p in problems:
            print("  •", p)
        return 1

    print(f"Схем в тестах {len(owners)}, у каждой один хозяин "
          f"(делят намеренно {len(shared)}, все разобраны).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
