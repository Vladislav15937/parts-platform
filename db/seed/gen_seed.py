#!/usr/bin/env python3
"""Превращает собранные CSV в changeset Liquibase."""
from __future__ import annotations

import csv
import sys


def q(value: str) -> str:
    return "'" + (value or "").replace("'", "''") + "'"


def num(value) -> str:
    text = str(value).strip()
    return text if text else "NULL"


def rows(path: str) -> list[dict]:
    with open(path, encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def chunks(items: list, size: int):
    for i in range(0, len(items), size):
        yield items[i:i + size]


def main() -> int:
    brands = rows("brands.csv")
    models = rows("models.csv")
    generations = rows("generations.csv")

    out = ["""--liquibase formatted sql

--changeset platform:catalog-020-generation-source
--comment Откуда взята запись поколения.
--comment
--comment Нужно двум вещам. Первая — идемпотентность наполнения: без внешнего
--comment ключа повторный прогон завёл бы вторую копию каждого поколения,
--comment потому что своей уникальности у него нет — модель и год начала
--comment совпадают у поколения и его рестайлинга.
--comment Вторая — обновление: справочник живой, машины выходят, и без ссылки
--comment на источник новые записи не отличить от уже загруженных.
ALTER TABLE catalog.generation ADD COLUMN source_ref text;
CREATE UNIQUE INDEX generation_source_uk ON catalog.generation (model_id, source_ref)
    WHERE source_ref IS NOT NULL;
--rollback DROP INDEX catalog.generation_source_uk;
--rollback ALTER TABLE catalog.generation DROP COLUMN source_ref;

--changeset platform:catalog-021-brand-alias
--comment Русское написание марки: приёмщик ищет «тойота», а не «toyota».
--comment
--comment Отдельной колонкой, а не строкой поиска: раскладка на телефоне
--comment переключается медленно, и заставлять переключать её ради каждой
--comment машины — это секунды на каждой детали и сотни за смену.
ALTER TABLE catalog.brand ADD COLUMN name_ru text;
CREATE INDEX brand_name_ru_ix ON catalog.brand (lower(name_ru));
--rollback DROP INDEX catalog.brand_name_ru_ix;
--rollback ALTER TABLE catalog.brand DROP COLUMN name_ru;
"""]

    out.append(f"""
--changeset platform:catalog-022-seed-brands
--comment Марки из каталога Дрома ({len(brands)} шт).
--comment
--comment Источник выбран не случайно: выгрузка идёт на Дром, и справочник,
--comment собранный по чужому дереву, дал бы модели, которых площадка не знает.
--comment Структура взята из sitemap'ов, которые Дром публикует сам, названия —
--comment со страниц марок. ON CONFLICT делает наполнение повторяемым:
--comment changeset помечен runOnChange, и обновлённый справочник накатывается
--comment поверх, не трогая уже заведённое.""")

    for part in chunks(brands, 200):
        values = ",\n       ".join(
            f"({q(b['slug'])}, {q(b['name'])}, {q(b['name_ru']) if b['name_ru'] else 'NULL'})"
            for b in part)
        out.append(f"""
INSERT INTO catalog.brand (slug, name, name_ru)
VALUES {values}
ON CONFLICT (slug) DO UPDATE SET name = excluded.name, name_ru = excluded.name_ru;""")

    out.append("""
--rollback DELETE FROM catalog.brand;

--changeset platform:catalog-023-seed-models
--comment Модели из каталога Дрома (%d шт). Марка ищется по слагу: своих
--comment идентификаторов у нас в CSV нет и быть не должно — они назначаются
--comment базой при вставке марок выше.""" % len(models))

    for part in chunks(models, 400):
        values = ",\n       ".join(
            f"({q(m['brand_slug'])}, {q(m['slug'])}, {q(m['name'])})" for m in part)
        out.append(f"""
INSERT INTO catalog.model (brand_id, slug, name)
SELECT b.id, v.slug, v.name
  FROM (VALUES {values}) AS v(brand_slug, slug, name)
  JOIN catalog.brand b ON b.slug = v.brand_slug
ON CONFLICT (brand_id, slug) DO UPDATE SET name = excluded.name;""")

    out.append("""
--rollback DELETE FROM catalog.model;

--changeset platform:catalog-024-seed-generations
--comment Поколения из каталога Дрома (%d шт).
--comment
--comment Название — диапазон лет, а не заводской код: код Дром в адресе
--comment не отдаёт, а год начала отдаёт. Год окончания выведен как начало
--comment следующего поколения минус год; у последнего его нет, и это верно —
--comment модель ещё выпускается.
--comment
--comment Пустые поколения без модели отброшены при сборе: модель могла быть
--comment снята с публикации, а страницы поколений остаться.""" % len(generations))

    for part in chunks(generations, 400):
        values = ",\n       ".join(
            "({}, {}, {}, {}, {})".format(
                q(g["brand_slug"]), q(g["model_slug"]),
                num(g["year_from"]), num(g["year_to"]), q(g["drom_id"]))
            for g in part)
        out.append(f"""
INSERT INTO catalog.generation (model_id, name, year_from, year_to, source_ref)
SELECT m.id,
       CASE WHEN v.year_to IS NULL THEN v.year_from || '—н.в.'
            ELSE v.year_from || '—' || v.year_to END,
       v.year_from, v.year_to, v.source_ref
  FROM (VALUES {values}) AS v(brand_slug, model_slug, year_from, year_to, source_ref)
  JOIN catalog.brand b ON b.slug = v.brand_slug
  JOIN catalog.model m ON m.brand_id = b.id AND m.slug = v.model_slug
-- Условие повторяет частичный индекс: без него Postgres не сопоставляет
-- ON CONFLICT с индексом и отвергает запрос целиком.
ON CONFLICT (model_id, source_ref) WHERE source_ref IS NOT NULL DO UPDATE
   SET year_to = excluded.year_to, name = excluded.name;""")

    out.append("\n--rollback DELETE FROM catalog.generation;\n")

    text = "\n".join(out)
    open("009-vehicle-seed.sql", "w", encoding="utf-8").write(text)
    print(f"написано: {len(text)} байт, марок {len(brands)}, "
          f"моделей {len(models)}, поколений {len(generations)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
