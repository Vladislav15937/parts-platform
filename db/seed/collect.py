#!/usr/bin/env python3
"""
Сборка справочника марок, моделей и поколений из каталога Дрома.

Структура берётся из sitemap'ов, которые Дром публикует сам (они перечислены
в его robots.txt) — это 28 файлов вместо десятков тысяч загрузок страниц.
Со страниц читаются только названия: слаг в URL их не даёт, а «mark_ii»
в карточке детали вместо «Mark II» приёмщик не узнает.

Одна страница на марку, пауза в секунду между запросами — как просит
Crawl-Delay в robots.txt. Скачанное кэшируется, повторный запуск сеть
не трогает.
"""
from __future__ import annotations

import csv
import html
import os
import re
import sys
import time
import urllib.request

UA = "partsflow-catalog-seed/0.1 (+seed for parts-platform)"
DELAY = 1.0
CACHE = "pages"

os.makedirs(CACHE, exist_ok=True)


def fetch(url: str, cache_name: str) -> str:
    path = os.path.join(CACHE, cache_name)
    if os.path.exists(path):
        raw = open(path, "rb").read()
    else:
        request = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
        open(path, "wb").write(raw)
        time.sleep(DELAY)

    encoding = "windows-1251"
    found = re.search(rb'charset=["\']?([\w-]+)', raw[:2000], re.I)
    if found:
        encoding = found.group(1).decode()
    return raw.decode(encoding, errors="replace")


def clean(text: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", text))).strip()


def locs(path: str) -> list[str]:
    return re.findall(r"<loc>([^<]+)</loc>", open(path, encoding="utf-8").read())


def brand_names(page: str, slug: str) -> tuple[str, str | None]:
    """
    Название марки и её русское написание.

    <p>Имя берётся из h1: там формат один на все марки — «Модельный ряд X:
    комплектации и цены». В title он плавает, и разбор по нему приносил
    в справочник строки вида «Qingling комплектации и цены. Технические
    характеристики Qingling».

    <p>Русское написание — из скобок в title («BMW (БМВ)»). Есть не у всех,
    и у части марок основное имя уже русское — тогда в скобках стоит второе
    название («Лада (ВАЗ)»), и оно тоже годится как синоним для поиска.
    """
    name = None
    h1 = re.search(r"<h1[^>]*>(.*?)</h1>", page, re.S)
    if h1:
        found = re.search(r"Модельный ряд\s+(.+?)\s*:", clean(h1.group(1)))
        if found:
            name = found.group(1).strip()
    if not name:
        name = slug.replace("-", " ").replace("_", " ").title()

    russian = None
    title = re.search(r"<title>(.*?)</title>", page, re.S)
    if title:
        paren = re.search(r"\(([^)]{2,40})\)", clean(title.group(1)))
        if paren and paren.group(1).strip().lower() != name.lower():
            russian = paren.group(1).strip()
    return name, russian


def models_of(page: str, slug: str) -> dict[str, str]:
    pattern = r'<a[^>]+href="(?:https://www\.drom\.ru)?/catalog/%s/([a-z0-9_-]+)/"[^>]*>(.*?)</a>' % re.escape(slug)
    found: dict[str, str] = {}
    for model_slug, text in re.findall(pattern, page, re.S):
        name = clean(text)
        # Служебные разделы марки — не модели.
        if not name or model_slug in {"engine", "frame", "reviews", "gallery"}:
            continue
        if len(name) > 60 or name.lower().startswith(("отзыв", "фото", "все ")):
            continue
        found.setdefault(model_slug, name)
    return found


def main() -> int:
    brand_slugs = [u.rstrip("/").rsplit("/", 1)[-1] for u in locs("firms.xml")]
    print(f"марок в sitemap: {len(brand_slugs)}", flush=True)

    brands: list[dict] = []
    models: list[dict] = []

    for index, slug in enumerate(brand_slugs, 1):
        try:
            page = fetch(f"https://www.drom.ru/catalog/{slug}/", f"brand_{slug}.html")
        except Exception as error:  # noqa: BLE001
            print(f"  {slug}: не скачалось ({error})", flush=True)
            continue

        latin, russian = brand_names(page, slug)
        brands.append({"slug": slug, "name": latin, "name_ru": russian or ""})
        for model_slug, name in models_of(page, slug).items():
            models.append({"brand_slug": slug, "slug": model_slug, "name": name})

        if index % 25 == 0:
            print(f"  {index}/{len(brand_slugs)} марок, моделей {len(models)}", flush=True)

    # Поколения — только из sitemap'ов: страниц моделей 4600, и ходить
    # за каждой ради года незачем, год есть прямо в адресе.
    generations: list[dict] = []
    for part in sorted(os.listdir("sitemaps")):
        if "generations" not in part:
            continue
        for url in locs(os.path.join("sitemaps", part)):
            found = re.search(r"/catalog/([a-z0-9_-]+)/([a-z0-9_-]+)/g_(\d{4})_(\d+)/", url)
            if found:
                generations.append({
                    "brand_slug": found.group(1),
                    "model_slug": found.group(2),
                    "year_from": int(found.group(3)),
                    "drom_id": found.group(4),
                })

    # Год окончания — начало следующего поколения минус год. Отдельно его
    # Дром в адресе не даёт, а без него поколение не выбрать по году машины.
    #
    # Считается по следующему СТРОГО большему году, а не по соседней записи:
    # у модели бывают два поколения с одним годом начала (поколение и его
    # рестайлинг), и «сосед минус год» давал год окончания раньше года начала —
    # то есть запись, которую база отвергает по CHECK.
    generations.sort(key=lambda g: (g["brand_slug"], g["model_slug"], g["year_from"]))
    by_model: dict = {}
    for generation in generations:
        key = (generation["brand_slug"], generation["model_slug"])
        by_model.setdefault(key, []).append(generation)

    for group in by_model.values():
        years = sorted({g["year_from"] for g in group})
        following = {year: next_year for year, next_year in zip(years, years[1:])}
        for generation in group:
            next_year = following.get(generation["year_from"])
            # У последнего поколения года окончания нет, и это верно:
            # модель ещё выпускается.
            generation["year_to"] = (next_year - 1) if next_year else ""

    known = {(m["brand_slug"], m["slug"]) for m in models}
    generations = [g for g in generations if (g["brand_slug"], g["model_slug"]) in known]

    # Схлопываем по году начала. У Дрома одно поколение разложено на записи
    # по типам кузова: у Camry 1982 года их четыре, и различаются они только
    # идентификатором. В нашем справочнике поколение — это диапазон лет,
    # и четыре одинаковых «1982—1983» в списке приёмщик не различит никак.
    # Кузова живут в catalog.body_type, и это отдельная задача.
    unique: dict = {}
    for generation in generations:
        key = (generation["brand_slug"], generation["model_slug"], generation["year_from"])
        current = unique.get(key)
        # Меньший идентификатор — запись, заведённая первой: как правило,
        # это базовый кузов, а не производный.
        if current is None or int(generation["drom_id"]) < int(current["drom_id"]):
            unique[key] = generation
    generations = sorted(unique.values(),
                         key=lambda g: (g["brand_slug"], g["model_slug"], g["year_from"]))

    write("brands.csv", ["slug", "name", "name_ru"], brands)
    write("models.csv", ["brand_slug", "slug", "name"], models)
    write("generations.csv",
          ["brand_slug", "model_slug", "year_from", "year_to", "drom_id"], generations)

    print(f"готово: марок {len(brands)}, моделей {len(models)}, "
          f"поколений {len(generations)}", flush=True)
    return 0


def write(path: str, fields: list[str], rows: list[dict]) -> None:
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    sys.exit(main())
