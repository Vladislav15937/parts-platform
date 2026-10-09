#!/usr/bin/env python3
"""Замер поиска продавца: выдача, счёт и списки значений — по редакциям запроса.

    # данные: 50 300 позиций, 150 предзаказов, 40 000 закрытых строк сделок
    docker exec -i <контейнер> psql -U app -d parts -v schema=t_bench \\
        < tools/bench/seller-search-seed.sql
    # замер
    tools/bench/seller_search_bench.py --psql "docker exec -i <контейнер> psql -U app -d parts" \\
        --variants v0,code --runs 15
    tools/bench/seller_search_bench.py ... --plan code     # планы EXPLAIN (ANALYZE, BUFFERS)
    tools/bench/seller_search_bench.py ... --check         # выдача и счёт те же, что у v0 и у 0170

Схема — накатанная целиком (db/verify.sh или провижининг) с данными из
seller-search-seed.sql. Контейнер Postgres свой, а не общий: замер на занятой
базе врёт, и compose проверки миграций (db/docker-compose.yml) для этого годится.

Редакции (--variants через запятую):
  v0         — до 0170: JOIN part_stock s ON s.part_id = p.id AND s.qty > 0
  code       — запрос из PartService.java рабочего дерева: константы
               STOCK_BRANCH, EXPECTED_BRANCH, STOCK_FROM, EXPECTED_FROM,
               EXPECTED_WHERE, DEFAULT_STOCK_ORDER читаются из исходника, а не
               копируются (копия разошлась бы с кодом, и замер мерил бы не то,
               что едет в бой); склейка кусков повторена здесь и сверена с SQL,
               который JdbcTemplate пишет в лог (см. PR 0261)
  src:<rev>  — прежний вид, STOCK_SOURCE после «FROM part p», из PartService.java
               на ревизии <rev>: 0170 (ee44a6d) и первая редакция 0261 (c351827)

Что меряется — Execution Time из EXPLAIN (ANALYZE, FORMAT JSON), медиана и p90
по --runs прогонам. Редакции идут вперемешку по кругу, чтобы шум машины
(нагрузка соседей) доставался всем поровну, а не тому, кто шёл последним.
Запрос «модель96» — узкий (503 находки), «фара» — широкий (5 563). Параметры
подставляются литералами по порядку, как их отдаёт JdbcTemplate.

Первое число во всех колонках — первая из названных редакций; отношение к ней
печатается справа.
"""
import argparse
import json
import re
import statistics
import subprocess
import sys

PART_SERVICE = "src/main/java/ru/partsflow/inventory/PartService.java"
NUMBER_BRANCH = "\n                          UNION SELECT id FROM part WHERE number = ?"

# ---------------------------------------------------------------- прежний вид

MATCH = """
 WHERE p.id IN (
         SELECT id FROM part WHERE public_code ILIKE ?
          UNION SELECT id FROM part WHERE title ILIKE ?
          UNION SELECT id FROM part
                 WHERE to_tsvector('russian', coalesce(title, '') || ' '
                           || coalesce(description, '') || ' '
                           || coalesce(marking, ''))
                       @@ plainto_tsquery('russian', ?)
          UNION SELECT part_id FROM part_oem WHERE raw_number ILIKE ?)"""

V0 = "JOIN part_stock s ON s.part_id = p.id AND s.qty > 0"

OLD_ROWS = """
SELECT p.id, p.number, p.public_code, p.title, p.price, p.status,
       w.id AS warehouse_id, w.name AS warehouse_name, c.code AS cell_code,
       s.qty, s.qty_reserved, s.qty - s.qty_reserved AS qty_available,
       {expected} AS expected, sup.expected_on
  FROM part p
 {source}
  JOIN warehouse w ON w.id = s.warehouse_id
  LEFT JOIN storage_cell c ON c.id = s.cell_id
  LEFT JOIN supply sup ON sup.id = p.supply_id
 {match}
 ORDER BY (s.qty - s.qty_reserved > 0) DESC,
          ts_rank(to_tsvector('russian', coalesce(p.title, '') || ' '
              || coalesce(p.description, '') || ' ' || coalesce(p.marking, '')),
              plainto_tsquery('russian', ?)) DESC,
          p.id
 LIMIT {limit}"""

OLD_COUNT = "SELECT count(*) FROM part p {source} {match}"

OLD_FACETS = """
SELECT DISTINCT b.name AS brand, m.name AS model, {grade} AS grade
  FROM part p
 {source}
  LEFT JOIN donor d ON d.id = p.donor_id
  LEFT JOIN catalog.brand b ON b.id = d.brand_id
  LEFT JOIN catalog.model m ON m.id = d.model_id
 {match}
 ORDER BY 1, 2, 3"""

# Тем же выражением, что CatalogService.QUALITY_GRADE (QualityGrade.sqlLabel).
GRADE = ("CASE p.quality_grade WHEN 'AS_NEW' THEN 'Как новая' "
         "WHEN 'NO_DEFECTS' THEN 'Без дефектов' WHEN 'WITH_DEFECTS' THEN 'С дефектами' "
         "WHEN 'NEEDS_REPAIR' THEN 'Требует ремонт' END")

KINDS = ("выдача", "счёт", "фасеты")
WORDS = ("фара", "модель96")
JIT_OFF = False
METRIC_TOTAL = False


# ------------------------------------------------------------- чтение исходника

def java_text(repo, rev):
    if rev:
        return subprocess.run(["git", "-C", repo, "show", f"{rev}:{PART_SERVICE}"],
                              capture_output=True, text=True, check=True).stdout
    return open(f"{repo}/{PART_SERVICE}", encoding="utf-8").read()


def java_const(java, name):
    """Значение строковой константы: текстовые блоки и литералы через «+»."""
    m = re.search(r"\b" + name + r"\s*=\s*", java)
    if not m:
        sys.exit(f"{name} не найден в PartService.java")
    rest = java[m.end():]
    out = []
    i = 0
    while True:
        rest_i = rest[i:].lstrip()
        i = len(rest) - len(rest_i)
        if rest_i.startswith('"""'):
            end = rest_i.index('"""', 3)
            block = rest_i[3:end]
            lines = block.split("\n")[1:]  # первая строка после открывающих кавычек пуста
            # Отступ срезается по самой левой непустой строке и по закрывающей:
            # SQL от этого не меняется, но склейка читается так же, как в Java.
            indents = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
            cut = min(indents + [len(lines[-1]) - len(lines[-1].lstrip())]) if lines else 0
            out.append("\n".join(l[cut:].rstrip() if l.strip() else "" for l in lines))
            i += end + 3
        elif rest_i.startswith('"'):
            end = 1
            while rest_i[end] != '"' or rest_i[end - 1] == "\\":
                end += 1
            out.append(rest_i[1:end].replace("\\n", "\n").replace("\\'", "'"))
            i += end + 1
        else:
            sys.exit(f"{name}: не разобрал значение у {rest_i[:30]!r}")
        tail = rest[i:].lstrip()
        if tail.startswith("+"):
            i = len(rest) - len(tail) + 1
            continue
        if tail.startswith(";"):
            return "".join(out)
        sys.exit(f"{name}: неожиданное продолжение {tail[:30]!r}")


def literal(value):
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def inline(sql, args):
    parts = sql.split("?")
    if len(parts) - 1 != len(args):
        sys.exit(f"в запросе {len(parts) - 1} параметров, а подано {len(args)}")
    return "".join(p + (literal(args[i]) if i < len(args) else "") for i, p in enumerate(parts))


# -------------------------------------------------------------- сборка запросов

def text_match(expected_only, predicate):
    """Повтор PartService.textMatch (без ветки номера: замер ищет словами)."""
    only = " AND " + predicate if expected_only else ""
    oem = ("SELECT o.part_id FROM part_oem o JOIN part e ON e.id = o.part_id"
           " WHERE o.raw_number ILIKE ? AND e.expected_origin AND e.status = 'DRAFT'"
           if expected_only else "SELECT part_id FROM part_oem WHERE raw_number ILIKE ?")
    return ("p.id IN (\n"
            "         SELECT id FROM part WHERE public_code ILIKE ?" + only + "\n"
            "          UNION SELECT id FROM part WHERE title ILIKE ?" + only + "\n"
            "          UNION SELECT id FROM part\n"
            "                 WHERE to_tsvector('russian', coalesce(title, '') || ' '\n"
            "                           || coalesce(description, '') || ' '\n"
            "                           || coalesce(marking, ''))\n"
            "                       @@ plainto_tsquery('russian', ?)" + only + "\n"
            "          UNION " + oem + ")")


def build_code(java, kind, word, limit):
    c = lambda n: java_const(java, n)  # noqa: E731
    like, text = "%" + word + "%", word
    pred = c("EXPECTED_PREDICATE")
    stock_cond, exp_cond = text_match(False, pred), text_match(True, pred)
    args = [like, like, text, like]
    if kind == "выдача":
        sql = ("SELECT r.id, r.number, r.public_code, r.title, r.price, r.status,\n"
               "       r.warehouse_id, r.warehouse_name, r.cell_code,\n"
               "       r.qty, r.qty_reserved, r.qty - r.qty_reserved AS qty_available,\n"
               "       r.expected, r.expected_on\n"
               "  FROM (\n"
               + c("STOCK_BRANCH") + " WHERE " + stock_cond + "\n"
               + "UNION ALL\n"
               + c("EXPECTED_BRANCH") + " WHERE " + c("EXPECTED_WHERE") + " AND " + exp_cond + "\n"
               + ") r\n ORDER BY " + c("DEFAULT_STOCK_ORDER") + "\n LIMIT ?")
        return inline(sql, args + args + [text, limit])
    if kind == "счёт":
        sql = ("SELECT (SELECT count(*) " + c("STOCK_FROM") + " WHERE " + stock_cond + ")\n"
               "     + (SELECT count(*) " + c("EXPECTED_FROM") + " WHERE " + c("EXPECTED_WHERE")
               + " AND " + exp_cond + ")")
        return inline(sql, args + args)
    values = "SELECT b.name AS brand, m.name AS model, " + GRADE + " AS grade\n"
    donors = ("\n  LEFT JOIN donor d ON d.id = p.donor_id"
              "\n  LEFT JOIN catalog.brand b ON b.id = d.brand_id"
              "\n  LEFT JOIN catalog.model m ON m.id = d.model_id\n")
    sql = (values + "  " + c("STOCK_FROM") + donors + " WHERE " + stock_cond + "\nUNION\n"
           + values + "  " + c("EXPECTED_FROM") + donors
           + " WHERE " + c("EXPECTED_WHERE") + " AND " + exp_cond + "\n ORDER BY 1, 2, 3")
    return inline(sql, args + args)


def build_old(source, variant, kind, word, limit):
    like = "%" + word + "%"
    args = [like, like, word, like]
    expected = "false" if variant == "v0" else "s.expected"
    if kind == "выдача":
        sql = OLD_ROWS.format(source=source, match=MATCH, expected=expected, limit=limit)
        return inline(sql, args + [word])
    if kind == "счёт":
        return inline(OLD_COUNT.format(source=source, match=MATCH), args)
    return inline(OLD_FACETS.format(source=source, match=MATCH, grade=GRADE), args)


class Variant:
    def __init__(self, name, repo):
        self.name = name
        if name == "v0":
            self.source, self.java = V0, None
        elif name == "code":
            self.source, self.java = None, java_text(repo, None)
        elif name.startswith("src:"):
            java = java_text(repo, name[4:])
            m = re.search(r'STOCK_SOURCE = """\n(.*?)"""', java, re.S)
            if not m:
                sys.exit(f"STOCK_SOURCE не найден на {name[4:]}")
            self.source, self.java = m.group(1), None
        else:
            sys.exit(f"неизвестная редакция {name}")

    def sql(self, kind, word, limit=50):
        if self.java is not None:
            return build_code(self.java, kind, word, limit)
        return build_old(self.source, self.name, kind, word, limit)


# ------------------------------------------------------------------ исполнение

def run(psql, schema, sql, explain=None):
    head = f"SET search_path = {schema}, public;\n" + ("SET jit = off;\n" if JIT_OFF else "")
    p = subprocess.run(psql + ["-At", "-v", "ON_ERROR_STOP=1"], capture_output=True, text=True,
                       input=head + (explain or "") + sql + ";")
    if p.returncode != 0:
        sys.exit(p.stderr)
    return p.stdout


def exec_ms(psql, schema, sql):
    out = run(psql, schema, sql, "EXPLAIN (ANALYZE, FORMAT JSON) ")
    top = json.loads(out[out.index("["):])[0]
    return top["Execution Time"] + (top["Planning Time"] if METRIC_TOTAL else 0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--psql", required=True, help="команда psql, например «docker exec -i X psql -U app -d parts»")
    ap.add_argument("--schema", default="t_bench")
    ap.add_argument("--variants", default="v0,code")
    ap.add_argument("--runs", type=int, default=15)
    ap.add_argument("--repo", default=".")
    ap.add_argument("--plan", help="напечатать планы этой редакции и выйти")
    ap.add_argument("--print-sql", help="напечатать SQL этой редакции и выйти")
    ap.add_argument("--check", action="store_true", help="сверить результат с первой из редакций")
    ap.add_argument("--jit", choices=["on", "off"], default="on")
    ap.add_argument("--total", action="store_true",
                    help="к Execution Time прибавить Planning Time: так видит запрос тот, кто ждёт ответа")
    args = ap.parse_args()
    global JIT_OFF, METRIC_TOTAL
    JIT_OFF = args.jit == "off"
    METRIC_TOTAL = args.total
    psql = args.psql.split()
    variants = [Variant(n, args.repo) for n in args.variants.split(",")]

    if args.print_sql or args.plan:
        v = Variant(args.print_sql or args.plan, args.repo)
        for word in WORDS:
            for kind in KINDS:
                sql = v.sql(kind, word)
                if args.print_sql:
                    print(f"-- {word} / {kind}\n{sql};\n")
                else:
                    print(f"\n=== {v.name} / {word} / {kind} ===")
                    print(run(psql, args.schema, sql, "EXPLAIN (ANALYZE, BUFFERS) "))
        return

    if args.check:
        # Без LIMIT: сверяется весь результат, а не первая страница. Ключ —
        # позиция и склад, значение — всё, что видит продавец.
        def fetch(v, word):
            out = run(psql, args.schema, v.sql("выдача", word, 10 ** 6)).splitlines()
            rows = sorted(r for r in out if r and r != "SET")
            return rows, int(run(psql, args.schema, v.sql("счёт", word)).split()[-1])

        bad = 0
        for word in WORDS:
            ref_rows, ref_count = fetch(variants[0], word)
            print(f"  {word}: эталон {variants[0].name}: {len(ref_rows)} строк, счёт {ref_count}")
            for v in variants[1:]:
                rows, count = fetch(v, word)
                expected_n = sum(1 for r in rows if r.split("|")[12] == "t")
                if variants[0].name == "v0":
                    # v0 ожидаемых не знает: строки склада сверяются целиком
                    # (позиция, склад, остатки), ожидаемые считаются отдельно.
                    rows = [r for r in rows if r.split("|")[12] != "t"]
                    count -= expected_n
                ok = rows == ref_rows and count == ref_count
                bad |= not ok
                print(f"  {'совпало    ' if ok else 'РАСХОЖДЕНИЕ'} {word}: {v.name}: "
                      f"{len(rows)} строк склада, счёт {count}, ожидаемых {expected_n}")
        sys.exit(bad)

    print(f"{'запрос':<20}" + "".join(f"{v.name:>24}" for v in variants))
    for word in WORDS:
        for kind in KINDS:
            sqls = [v.sql(kind, word) for v in variants]
            exec_ms(psql, args.schema, sqls[0])  # прогрев кэша
            samples = [[] for _ in variants]
            for _ in range(args.runs):
                for i, sql in enumerate(sqls):
                    samples[i].append(exec_ms(psql, args.schema, sql))
            cells, med = [], []
            for xs in samples:
                xs = sorted(xs)
                med.append(statistics.median(xs))
                cells.append(f"{med[-1]:7.1f} (p90 {xs[max(int(len(xs) * .9) - 1, 0)]:5.1f})")
            ratio = "".join(f"  x{m / med[0]:.2f}" for m in med[1:])
            print(f"{word + ' / ' + kind:<20}" + "".join(f"{c:>24}" for c in cells) + ratio)


if __name__ == "__main__":
    main()
