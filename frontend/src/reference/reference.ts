import { request } from '../api/client';
import { get, put, STORE_REFERENCE } from '../storage/db';

/** Ячейка хранения. Код печатают на этикетке и сканируют. */
export interface Cell {
  id: number;
  code: string;
  zone: string | null;
}

export interface Warehouse {
  id: number;
  name: string;
  cells: Cell[];
}

export interface SupplyRef {
  id: number;
  kind: string;
  number: string;
  supplierName: string | null;
  status: string;
  arrivedOn: string | null;
}

export interface DonorRef {
  id: number;
  publicCode: string;
  brand: string | null;
  model: string | null;
  year: number | null;
  vin: string | null;
  status: string;
  location: string | null;
}

/** Наименование арендатора. `matched` — сопоставлено ли с эталоном. */
export interface PartNameRef {
  id: number;
  name: string;
  matched: boolean;
  usageCount: number;
}

export interface Reference {
  loadedAt: string;
  warehouses: Warehouse[];
  supplies: SupplyRef[];
  donors: DonorRef[];
  partNames: PartNameRef[];
}

/** Единственный ключ: справочники хранятся одной записью и заменяются целиком. */
const KEY = 'current';

/**
 * Забирает справочники с сервера и кладёт в локальное хранилище.
 *
 * <p>Заменяются целиком, а не сливаются по записям. Слияние потребовало бы
 * знать, что удалено на сервере, — иначе закрытый склад останется в списке
 * навсегда. Целиком дешевле и честнее.
 */
export async function refreshReference(): Promise<Reference> {
  const loaded = await request<Reference>('/api/intake/reference');
  await put(STORE_REFERENCE, loaded, KEY);
  return loaded;
}

/** Справочники из локального хранилища. Пусто — значит их ещё не забирали. */
export function cachedReference(): Promise<Reference | undefined> {
  return get<Reference>(STORE_REFERENCE, KEY);
}

/**
 * Подсказки по написанию наименования.
 *
 * <p>Смысл не в удобстве. Приёмщик, которому предложили «фара левая» из уже
 * существующих, не заведёт «фара лев.» двадцать первым написанием — а список
 * нераспознанных растёт ровно из этого.
 *
 * <p>Поиск по вхождению, а не по началу строки: приёмщик пишет «фара», имея
 * в виду «Фара противотуманная», и поиск по началу такое не найдёт. Порядок:
 * сначала совпадения с начала, потом остальные, внутри — по частоте
 * использования, которая уже задана сервером.
 */
export function suggestNames(names: PartNameRef[], query: string, limit = 8): PartNameRef[] {
  const needle = query.trim().toLowerCase();
  if (needle.length < 2) {
    // На одной букве подсказки бесполезны: подойдёт половина справочника.
    return [];
  }

  const starts: PartNameRef[] = [];
  const contains: PartNameRef[] = [];

  for (const candidate of names) {
    const haystack = candidate.name.toLowerCase();
    const at = haystack.indexOf(needle);
    if (at === 0) {
      starts.push(candidate);
    } else if (at > 0) {
      contains.push(candidate);
    }
    if (starts.length >= limit) {
      break;
    }
  }
  return [...starts, ...contains].slice(0, limit);
}
