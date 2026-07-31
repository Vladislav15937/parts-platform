import { request } from '../api/client';
import { get, put, STORE_REFERENCE } from '../storage/db';

/**
 * Справочник машин на телефоне.
 *
 * <p><b>Кэшируется отдельно от справочников приёмки и надолго.</b> Наполняется
 * он миграцией, то есть меняется при обновлении системы, а не в течение дня —
 * в отличие от поставок и доноров, которые заводят прямо сейчас. Класть его
 * в тот же кэш значит перекачивать 850 КБ при каждом тихом обновлении списка
 * машин на разборе.
 *
 * <p><b>Модели и поколения качаются целиком, а не по мере выбора марки.</b>
 * Марку выбирают в ангаре, где связи нет, и подгрузка «по клику» превратила бы
 * экран в неработающий ровно там, где он нужен. Восемьсот килобайт — цена
 * одного снимка, который приёмщик и так делает десятками.
 */

export interface Brand {
  id: number;
  slug: string;
  name: string;
  nameRu: string | null;
}

export interface Model {
  id: number;
  slug: string;
  name: string;
  brandId: number;
}

export interface Generation {
  id: number;
  modelId: number;
  name: string;
  yearFrom: number | null;
  yearTo: number | null;
}

export interface VehicleCatalog {
  loadedAt: string;
  brands: Brand[];
  models: Model[];
  generations: Generation[];
}

const KEY = 'vehicles';

/** Сколько кэш считается свежим. Справочник меняется с релизами, не за день. */
const FRESH_FOR_MS = 7 * 24 * 60 * 60 * 1000;

export async function loadCached(): Promise<VehicleCatalog | null> {
  return (await get<VehicleCatalog>(STORE_REFERENCE, KEY)) ?? null;
}

export function isStale(catalog: VehicleCatalog | null): boolean {
  if (catalog === null) {
    return true;
  }
  return Date.now() - new Date(catalog.loadedAt).getTime() > FRESH_FOR_MS;
}

/**
 * Забирает справочник целиком, одним запросом.
 *
 * <p>Не по марке и не по модели: это четыре с половиной тысячи запросов,
 * из которых по плохой связи оборвётся любой, а из наполовину заполненного
 * кэша непонятно, можно ли работать. То же правило, что у справочников
 * приёмки.
 */
export async function refresh(): Promise<VehicleCatalog> {
  const loaded = await request<{
    brands: Brand[];
    models: Model[];
    generations: Generation[];
  }>('/api/catalog/vehicles');

  const catalog: VehicleCatalog = {
    loadedAt: new Date().toISOString(),
    brands: loaded.brands,
    models: loaded.models,
    generations: loaded.generations,
  };
  await put(STORE_REFERENCE, catalog, KEY);
  return catalog;
}

/**
 * Подсказки по марке.
 *
 * <p>Ищет в обоих написаниях: приёмщик набирает «тойо» в русской раскладке
 * не потому, что не знает латиницы, а потому что переключать её на телефоне —
 * лишнее действие, а машин за смену десятки.
 */
export function suggestBrands(brands: Brand[], input: string, limit = 8): Brand[] {
  const term = input.trim().toLowerCase();
  if (term === '') {
    return [];
  }
  const matches = brands.filter(
    (b) => b.name.toLowerCase().includes(term) || (b.nameRu ?? '').toLowerCase().includes(term),
  );
  // Совпадение с начала выше совпадения в середине: «мазда» обязана дать
  // Mazda первой строкой.
  return matches
    .sort((a, b) => Number(startsWith(b, term)) - Number(startsWith(a, term)))
    .slice(0, limit);
}

function startsWith(brand: Brand, term: string): boolean {
  return (
    brand.name.toLowerCase().startsWith(term) ||
    (brand.nameRu ?? '').toLowerCase().startsWith(term)
  );
}

export function modelsOf(catalog: VehicleCatalog, brandId: number | null): Model[] {
  if (brandId === null) {
    return [];
  }
  return catalog.models
    .filter((m) => m.brandId === brandId)
    .sort((a, b) => a.name.localeCompare(b.name, 'ru'));
}

/** Поколения модели, свежие сверху: такие машины и приезжают на разбор. */
export function generationsOf(catalog: VehicleCatalog, modelId: number | null): Generation[] {
  if (modelId === null) {
    return [];
  }
  return catalog.generations
    .filter((g) => g.modelId === modelId)
    .sort((a, b) => (b.yearFrom ?? 0) - (a.yearFrom ?? 0));
}

/**
 * Поколение по году машины.
 *
 * <p>Ради этого и собирались годы: год приёмщик знает из документов, а какое
 * это поколение — вопрос, на который он отвечать не должен. Ошибка тут тихая:
 * деталь уедет в объявление с чужой применимостью.
 *
 * <p>У последнего поколения года окончания нет — модель ещё выпускается,
 * и всё, что позже его начала, относится к нему.
 */
export function generationForYear(
  generations: Generation[],
  year: number | null,
): Generation | null {
  if (year === null || Number.isNaN(year)) {
    return null;
  }
  const found = generations.filter(
    (g) =>
      g.yearFrom !== null && year >= g.yearFrom && (g.yearTo === null || year <= g.yearTo),
  );
  // Несколько подошедших — берём самое позднее: диапазоны не должны
  // пересекаться, но справочник живой, и полагаться на это нельзя.
  return found.sort((a, b) => (b.yearFrom ?? 0) - (a.yearFrom ?? 0))[0] ?? null;
}

/** VIN — 17 символов без I, O и Q. Проверяем до отправки: сервер его не сверяет. */
export function isValidVin(vin: string): boolean {
  return /^[A-HJ-NPR-Z0-9]{17}$/.test(vin.trim().toUpperCase());
}
