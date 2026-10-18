#!/usr/bin/env python3
"""Справочник применимости Bazon: снимок → CSV → changeset (задача 0341).

Две команды, потому что у них разные входы, и в репозитории лежит только
середина:

  python3 -I build_bazon_applicability.py csv <каталог снимка>
      читает marks.json и mark-<id>-<имя>.json (ответы getModels,
      getGenerations, getBodies, getMotors редактора применимости Bazon,
      снятые 19.10.2026), сверяет связи и пишет bazon_*.csv сюда;
  python3 -I build_bazon_applicability.py sql
      читает bazon_*.csv и пишет 026-bazon-applicability.sql.

Сырые JSON в репозиторий не кладутся (решение 0341): снимок — их ответ
сервера целиком, а нужна выжимка. Следующий снимок сверяется с этим
по идентификаторам Bazon, они в CSV стоят как есть.

Значения переносятся дословно — с пробелами по краям, длинным тире
и «...» в годах: строку «Подходит» собирают из них «до знака», и правка
здесь стала бы догадкой о том, как их печатает Bazon. Исключений четыре,
все — «пусто это NULL, а не значение»: пустая платформа, пустые код
и описание кузова и нулевой объём двигателя (электро и три газовых).
В CSV пустое остаётся пустым, NULL из него делает сборка SQL.

Сопоставление с нашим справочником (марка и модель Дрома) ищется здесь же
и пишется слагом: марка — по названию без регистра, модель — по названию
без регистра, затем без пробелов, дефисов и точек; только однозначное.
Не нашедшееся печатается списком и остаётся пустым в CSV — то есть видно
в самом файле, а не теряется молча. Поколение не сопоставляется вовсе:
у нас оно — диапазон лет по записям Дрома, и выбор между двумя поколениями
Bazon, перекрывающимися годами, — решение кода, а не наполнения.
"""
from __future__ import annotations

import csv
import glob
import json
import os
import re
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_SQL = os.path.join(os.path.dirname(HERE), "changelog", "catalog", "026-bazon-applicability.sql")
FUELS = ("gasoline", "diesel", "hybrid", "electric", "gas")

FILES = {
    "mark": ["id", "name", "brand_slug"],
    "model": ["id", "mark_id", "name", "model_slug"],
    "generation": ["id", "mark_id", "model_id", "name", "years", "series"],
    "body": ["id", "model_id", "generation", "name", "description"],
    "motor": ["id", "mark_id", "name", "volume_cc", "fuel"],
    "generation_motor": ["generation_id", "motor_id"],
    "body_motor": ["body_id", "motor_id"],
}


def path(name: str) -> str:
    return os.path.join(HERE, f"bazon_{name}.csv")


def read_csv(name: str) -> list[dict]:
    with open(path(name), encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def write_csv(name: str, rows: list[list]) -> None:
    with open(path(name), "w", encoding="utf-8", newline="") as handle:
        w = csv.writer(handle, lineterminator="\n")
        w.writerow(FILES[name])
        w.writerows(rows)


def loose(name: str) -> str:
    return re.sub(r"[\s.\-]+", "", name.lower())


def build_csv(src: str) -> int:
    marks_meta = json.load(open(os.path.join(src, "marks.json"), encoding="utf-8"))
    files = sorted(glob.glob(os.path.join(src, "mark-*.json")))

    brands = list(csv.DictReader(open(os.path.join(HERE, "brands.csv"), encoding="utf-8")))
    models = list(csv.DictReader(open(os.path.join(HERE, "models.csv"), encoding="utf-8")))
    brand_by_name = defaultdict(set)
    for b in brands:
        brand_by_name[b["name"].lower()].add(b["slug"])
    model_exact = defaultdict(set)
    model_loose = defaultdict(set)
    for m in models:
        model_exact[(m["brand_slug"], m["name"].lower())].add(m["slug"])
        model_loose[(m["brand_slug"], loose(m["name"]))].add(m["slug"])

    out = {k: [] for k in FILES}
    problems: list[str] = []
    unmatched_marks: list[str] = []
    unmatched_models: list[str] = []
    seen_ids = {k: set() for k in ("mark", "model", "generation", "body", "motor")}

    for f in files:
        d = json.load(open(f, encoding="utf-8"))
        mk = d["mark"]
        mark_id = mk["id"]
        if mark_id in seen_ids["mark"]:
            problems.append(f"марка {mark_id} дважды")
        seen_ids["mark"].add(mark_id)
        slugs = brand_by_name.get(mk["name"].lower(), set())
        brand_slug = next(iter(slugs)) if len(slugs) == 1 else ""
        if not brand_slug:
            unmatched_marks.append(mk["name"])
        out["mark"].append([mark_id, mk["name"], brand_slug])

        model_ids = {m["id"] for m in d["models"]}
        gens = {g["id"]: g for g in d["generations"]}
        bodies = {b["id"]: b for b in d["bodies"]}
        motors = {m["id"]: m for m in d["motors"]}

        for m in d["models"]:
            slug = ""
            if brand_slug:
                hit = model_exact.get((brand_slug, m["name"].lower())) \
                    or model_loose.get((brand_slug, loose(m["name"])), set())
                slug = next(iter(hit)) if len(hit) == 1 else ""
                if not slug:
                    unmatched_models.append(f"{mk['name']} {m['name']}")
            out["model"].append([m["id"], mark_id, m["name"], slug])

        gen_motor = set()
        for g in d["generations"]:
            if g["modelID"] not in model_ids:
                problems.append(f"поколение {g['id']}: нет модели {g['modelID']}")
            if not re.fullmatch(r"\d{4}(—(\d{4}|\.\.\.))?", g["years"]):
                problems.append(f"поколение {g['id']}: годы {g['years']!r}")
            out["generation"].append([g["id"], mark_id, g["modelID"], g["name"],
                                      g["years"], g["series"]])
            for b in g["bodies"]:
                if b not in bodies or bodies[b]["generation"] != g["id"]:
                    problems.append(f"поколение {g['id']}: кузов {b} не его")
            for m in g["motors"]:
                if m not in motors:
                    problems.append(f"поколение {g['id']}: нет двигателя {m}")
                gen_motor.add((g["id"], m))

        motor_gen = set()
        for m in d["motors"]:
            if m["fuel"] not in FUELS:
                problems.append(f"двигатель {m['id']}: топливо {m['fuel']!r}")
            out["motor"].append([m["id"], mark_id, m["name"],
                                 m["volume"] or "", m["fuel"]])
            for g in m["generations_ids"]:
                motor_gen.add((g, m["id"]))
        # generations_ids двигателя — та же связь, что motors поколения:
        # расхождение — в отчёт, в CSV идёт связь поколения.
        for pair in sorted(gen_motor ^ motor_gen):
            problems.append(f"связь поколение–двигатель {pair} только с одной стороны")
        out["generation_motor"].extend(sorted(gen_motor))

        for b in d["bodies"]:
            if b["generation"] not in gens or gens[b["generation"]]["modelID"] != b["modelID"]:
                problems.append(f"кузов {b['id']}: поколение {b['generation']} не его модели")
            out["body"].append([b["id"], b["modelID"], b["generation"], b["name"], b["description"]])
            for m in b["motors"]:
                if (b["generation"], m) not in gen_motor:
                    problems.append(f"кузов {b['id']}: двигатель {m} не из его поколения")
                out["body_motor"].append([b["id"], m])

        for kind, items in (("model", model_ids), ("generation", gens), ("body", bodies), ("motor", motors)):
            dup = seen_ids[kind] & set(items)
            if dup:
                problems.append(f"{kind}: идентификаторы в двух марках {sorted(dup)[:5]}")
            seen_ids[kind] |= set(items)

    listed = {(m["id"], m["name"]) for m in marks_meta["marks"]}
    taken = {(r[0], r[1]) for r in out["mark"]}
    if listed != taken:
        problems.append(f"marks.json и файлы марок расходятся: {sorted(listed ^ taken)[:5]}")

    for name, rows in out.items():
        rows.sort(key=tuple if name in ("generation_motor", "body_motor") else (lambda r: r[0]))
        write_csv(name, rows)

    print(f"снимок {marks_meta.get('snapshot', '?')}: " + ", ".join(
        f"{k} {len(v)}" for k, v in out.items()))
    print(f"марок без нашей марки: {len(unmatched_marks)} — {', '.join(unmatched_marks)}")
    print(f"моделей без нашей модели: {len(unmatched_models)}")
    for line in unmatched_models:
        print("  " + line)
    if problems:
        print(f"РАСХОЖДЕНИЯ: {len(problems)}")
        for p in problems[:50]:
            print("  " + p)
        return 1
    print("расхождений в связях нет")
    return 0


def q(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def q_or_null(value: str) -> str:
    return q(value) if value != "" else "NULL"


def num_or_null(value: str) -> str:
    return value if value != "" else "NULL"


def years(text: str) -> tuple[str, str]:
    parts = text.split("—")
    start = parts[0]
    if len(parts) == 1:
        return start, start
    return start, ("NULL" if parts[1] == "..." else parts[1])


def chunks(items: list, size: int = 1000):
    for i in range(0, len(items), size):
        yield items[i:i + size]


HEADER = """--liquibase formatted sql

--changeset platform:catalog-026-bazon-applicability
--comment Справочник применимости Bazon: поколение, платформа, кузова, двигатели (задача 0341).
--
-- Из этого справочника Bazon собирает строку «Подходит» объявления Дрома:
-- «Nissan X-Trail 2 поколение 2007–2015 T31 (T31, TNT31) двигатель QR25DE (2.5 Б)».
-- Решение владельца продукта 19.10.2026 (tasks/0341, «Ответ владельца — 19 октября
-- 2026 (2)»): снять все марки и хранить в общей служебной схеме, то есть здесь,
-- в catalog, а не в схеме арендатора. Наполнение — следующим changeset'ом из db/seed;
-- к Bazon в работе никто не ходит.
--
-- Это отдельная сущность, а не колонки catalog.generation: наше поколение —
-- диапазон лет по записям Дрома (у X-Trail их 12), у Bazon — поколение с номером,
-- платформой и годами (у X-Trail 4), и годы соседних перекрываются. Связь с нашим
-- справочником — марка и модель (brand_id, model_id; NULL — не нашлось однозначного
-- соответствия по названию, список в выводе сборщика), поколение — по годам,
-- и это решает код: year_from/year_to разобраны из years ровно для такого запроса.
--
-- Кузов принадлежит ровно одному поколению (в снимке так у всех 10 722), поэтому
-- ссылка, а не таблица связи. Двигатель принадлежит марке и связан с поколениями
-- и кузовами многие-ко-многим. bazon_id — идентификатор Bazon как есть: по нему
-- сверяется следующий снимок, наши ключи свои.
--
-- Тексты дословно, с пробелами по краям, если они есть в источнике: строку собирают
-- «до знака». NULL вместо пустого — у платформы, кода и описания кузова и объёма (0 в
-- источнике: электро и три газовых). Логики нет: ни триггеров, ни функций.
CREATE TABLE catalog.bazon_mark (
    id        bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bazon_id  integer NOT NULL,
    name      text    NOT NULL,
    brand_id  bigint  REFERENCES catalog.brand,
    CONSTRAINT bazon_mark_bazon_uk UNIQUE (bazon_id),
    CONSTRAINT bazon_mark_name_ck CHECK (name ~ '\\S')
);
CREATE INDEX bazon_mark_brand_ix ON catalog.bazon_mark (brand_id);

CREATE TABLE catalog.bazon_model (
    id             bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bazon_id       integer NOT NULL,
    bazon_mark_id  bigint  NOT NULL REFERENCES catalog.bazon_mark,
    name           text    NOT NULL,
    model_id       bigint  REFERENCES catalog.model,
    CONSTRAINT bazon_model_bazon_uk UNIQUE (bazon_id),
    CONSTRAINT bazon_model_name_ck CHECK (name ~ '\\S')
);
CREATE INDEX bazon_model_mark_ix ON catalog.bazon_model (bazon_mark_id);
CREATE INDEX bazon_model_model_ix ON catalog.bazon_model (model_id);

-- name — «2 поколение» как у Bazon; years — «2007—2015», «2022—...» или «1996»
-- как у Bazon; series — платформа («T31»), NULL у 956 поколений из 3876.
CREATE TABLE catalog.bazon_generation (
    id              bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bazon_id        integer  NOT NULL,
    bazon_model_id  bigint   NOT NULL REFERENCES catalog.bazon_model,
    name            text     NOT NULL,
    years           text     NOT NULL,
    year_from       smallint NOT NULL,
    year_to         smallint,
    series          text,
    CONSTRAINT bazon_generation_bazon_uk UNIQUE (bazon_id),
    CONSTRAINT bazon_generation_name_ck CHECK (name ~ '\\S'),
    CONSTRAINT bazon_generation_years_ck CHECK (years ~ '\\S'),
    CONSTRAINT bazon_generation_series_ck CHECK (series ~ '\\S'),
    CONSTRAINT bazon_generation_year_range_ck CHECK (year_to IS NULL OR year_to >= year_from)
);
CREATE INDEX bazon_generation_model_ix ON catalog.bazon_generation (bazon_model_id);

-- name — код кузова («TNT31»), NULL у 437 кузовов без кода и без пометки (так
-- в источнике, двигатели у них есть, 1005 связей); description — пометка вроде
-- «Пикап», NULL если нет.
CREATE TABLE catalog.bazon_body (
    id                   bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bazon_id             integer NOT NULL,
    bazon_generation_id  bigint  NOT NULL REFERENCES catalog.bazon_generation,
    name                 text,
    description          text,
    CONSTRAINT bazon_body_bazon_uk UNIQUE (bazon_id),
    CONSTRAINT bazon_body_name_ck CHECK (name ~ '\\S'),
    CONSTRAINT bazon_body_description_ck CHECK (description ~ '\\S')
);
CREATE INDEX bazon_body_generation_ix ON catalog.bazon_body (bazon_generation_id);

-- name — код двигателя («QR25DE»); volume_cc — объём в куб. см (2500), NULL — объёма
-- нет; fuel — как у Bazon. Как «gas», «hybrid» и «electric» пишутся в строке
-- «Подходит», по файлам выгрузки не известно — это вопрос сборщика строки, не схемы.
CREATE TABLE catalog.bazon_motor (
    id             bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bazon_id       integer NOT NULL,
    bazon_mark_id  bigint  NOT NULL REFERENCES catalog.bazon_mark,
    name           text    NOT NULL,
    volume_cc      integer,
    fuel           text    NOT NULL,
    CONSTRAINT bazon_motor_bazon_uk UNIQUE (bazon_id),
    CONSTRAINT bazon_motor_name_ck CHECK (name ~ '\\S'),
    CONSTRAINT bazon_motor_volume_ck CHECK (volume_cc > 0),
    CONSTRAINT bazon_motor_fuel_ck CHECK (fuel IN ('gasoline', 'diesel', 'hybrid', 'electric', 'gas'))
);
CREATE INDEX bazon_motor_mark_ix ON catalog.bazon_motor (bazon_mark_id);

CREATE TABLE catalog.bazon_generation_motor (
    bazon_generation_id  bigint NOT NULL REFERENCES catalog.bazon_generation,
    bazon_motor_id       bigint NOT NULL REFERENCES catalog.bazon_motor,
    PRIMARY KEY (bazon_generation_id, bazon_motor_id)
);
CREATE INDEX bazon_generation_motor_motor_ix ON catalog.bazon_generation_motor (bazon_motor_id);

CREATE TABLE catalog.bazon_body_motor (
    bazon_body_id   bigint NOT NULL REFERENCES catalog.bazon_body,
    bazon_motor_id  bigint NOT NULL REFERENCES catalog.bazon_motor,
    PRIMARY KEY (bazon_body_id, bazon_motor_id)
);
CREATE INDEX bazon_body_motor_motor_ix ON catalog.bazon_body_motor (bazon_motor_id);
--rollback DROP TABLE catalog.bazon_body_motor;
--rollback DROP TABLE catalog.bazon_generation_motor;
--rollback DROP TABLE catalog.bazon_motor;
--rollback DROP TABLE catalog.bazon_body;
--rollback DROP TABLE catalog.bazon_generation;
--rollback DROP TABLE catalog.bazon_model;
--rollback DROP TABLE catalog.bazon_mark;
"""


def build_sql() -> int:
    data = {k: read_csv(k) for k in FILES}
    out = [HEADER]
    counts = ", ".join(f"{k} {len(v)}" for k, v in data.items())
    out.append(f"""
--changeset platform:catalog-026-bazon-applicability-seed
--comment Наполнение справочника применимости Bazon из db/seed/bazon_*.csv (задача 0341).
--
-- Снимок 19.10.2026, 149 марок: {counts}.
-- Собран db/seed/build_bazon_applicability.py; связи в снимке сверены сборщиком
-- (поколение–кузов, поколение–двигатель с обеих сторон, кузов–двигатель внутри
-- двигателей поколения), расхождений нет. Следующий снимок — новым changeset'ом,
-- сверка по bazon_id. Откат снимает только это наполнение; пересобирается из db/seed.""")

    for part in chunks(data["mark"]):
        values = ",\n       ".join(f"({r['id']}, {q(r['name'])}, {q_or_null(r['brand_slug'])})" for r in part)
        out.append(f"""
INSERT INTO catalog.bazon_mark (bazon_id, name, brand_id)
SELECT v.bazon_id, v.name, b.id
  FROM (VALUES {values}) AS v(bazon_id, name, brand_slug)
  LEFT JOIN catalog.brand b ON b.slug = v.brand_slug;""")

    marks = {r["id"]: r["brand_slug"] for r in data["mark"]}
    for part in chunks(data["model"]):
        values = ",\n       ".join(
            f"({r['id']}, {r['mark_id']}, {q(r['name'])}, {q_or_null(marks[r['mark_id']])}, {q_or_null(r['model_slug'])})"
            for r in part)
        out.append(f"""
INSERT INTO catalog.bazon_model (bazon_id, bazon_mark_id, name, model_id)
SELECT v.bazon_id, mk.id, v.name, m.id
  FROM (VALUES {values}) AS v(bazon_id, mark_bazon_id, name, brand_slug, model_slug)
  JOIN catalog.bazon_mark mk ON mk.bazon_id = v.mark_bazon_id
  LEFT JOIN catalog.brand b ON b.slug = v.brand_slug
  LEFT JOIN catalog.model m ON m.brand_id = b.id AND m.slug = v.model_slug;""")

    for part in chunks(data["generation"]):
        rows = []
        for r in part:
            y_from, y_to = years(r["years"])
            rows.append(f"({r['id']}, {r['model_id']}, {q(r['name'])}, {q(r['years'])}, "
                        f"{y_from}, {y_to}, {q_or_null(r['series'])})")
        values = ",\n       ".join(rows)
        out.append(f"""
INSERT INTO catalog.bazon_generation (bazon_id, bazon_model_id, name, years, year_from, year_to, series)
SELECT v.bazon_id, m.id, v.name, v.years, v.year_from, v.year_to, v.series
  FROM (VALUES {values}) AS v(bazon_id, model_bazon_id, name, years, year_from, year_to, series)
  JOIN catalog.bazon_model m ON m.bazon_id = v.model_bazon_id;""")

    for part in chunks(data["body"]):
        values = ",\n       ".join(
            f"({r['id']}, {r['generation']}, {q_or_null(r['name'])}, {q_or_null(r['description'])})" for r in part)
        out.append(f"""
INSERT INTO catalog.bazon_body (bazon_id, bazon_generation_id, name, description)
SELECT v.bazon_id, g.id, v.name, v.description
  FROM (VALUES {values}) AS v(bazon_id, generation_bazon_id, name, description)
  JOIN catalog.bazon_generation g ON g.bazon_id = v.generation_bazon_id;""")

    for part in chunks(data["motor"]):
        values = ",\n       ".join(
            f"({r['id']}, {r['mark_id']}, {q(r['name'])}, {num_or_null(r['volume_cc'])}, {q(r['fuel'])})"
            for r in part)
        out.append(f"""
INSERT INTO catalog.bazon_motor (bazon_id, bazon_mark_id, name, volume_cc, fuel)
SELECT v.bazon_id, mk.id, v.name, v.volume_cc, v.fuel
  FROM (VALUES {values}) AS v(bazon_id, mark_bazon_id, name, volume_cc, fuel)
  JOIN catalog.bazon_mark mk ON mk.bazon_id = v.mark_bazon_id;""")

    for part in chunks(data["generation_motor"], 2000):
        values = ",".join(f"({r['generation_id']},{r['motor_id']})" for r in part)
        out.append(f"""
INSERT INTO catalog.bazon_generation_motor (bazon_generation_id, bazon_motor_id)
SELECT g.id, m.id
  FROM (VALUES {values}) AS v(generation_bazon_id, motor_bazon_id)
  JOIN catalog.bazon_generation g ON g.bazon_id = v.generation_bazon_id
  JOIN catalog.bazon_motor m ON m.bazon_id = v.motor_bazon_id;""")

    for part in chunks(data["body_motor"], 2000):
        values = ",".join(f"({r['body_id']},{r['motor_id']})" for r in part)
        out.append(f"""
INSERT INTO catalog.bazon_body_motor (bazon_body_id, bazon_motor_id)
SELECT b.id, m.id
  FROM (VALUES {values}) AS v(body_bazon_id, motor_bazon_id)
  JOIN catalog.bazon_body b ON b.bazon_id = v.body_bazon_id
  JOIN catalog.bazon_motor m ON m.bazon_id = v.motor_bazon_id;""")

    out.append("""
--rollback DELETE FROM catalog.bazon_body_motor;
--rollback DELETE FROM catalog.bazon_generation_motor;
--rollback DELETE FROM catalog.bazon_motor;
--rollback DELETE FROM catalog.bazon_body;
--rollback DELETE FROM catalog.bazon_generation;
--rollback DELETE FROM catalog.bazon_model;
--rollback DELETE FROM catalog.bazon_mark;
""")
    text = "\n".join(out)
    with open(OUT_SQL, "w", encoding="utf-8") as handle:
        handle.write(text)
    print(f"написано {OUT_SQL}: {len(text.encode('utf-8'))} байт; {counts}")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[0] == "csv":
        return build_csv(argv[1])
    if len(argv) == 1 and argv[0] == "sql":
        return build_sql()
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
