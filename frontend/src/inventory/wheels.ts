import { request } from '../api/client';

/**
 * Шины и диски.
 *
 * <p>Это товар с типом, а не отдельная сущность: склад, резерв и продажа
 * у них общие с запчастями. Отличается набор свойств — и то, что заводят
 * их комплектом, а продают поштучно: на разборке снимают четыре колеса
 * разом, но покупатель берёт и одну запаску.
 */

export const SEASONS = [
  { code: 'SUMMER', name: 'летняя' },
  { code: 'WINTER', name: 'зимняя' },
  { code: 'ALL_SEASON', name: 'всесезонная' },
] as const;

export interface Wheel {
  id: number;
  publicCode: string | null;
  title: string;
  price: number | null;
  status: string;
  qty: number;
  kind: string;
  /** Пусто — колесо заведено поштучно, а не комплектом. */
  setNo: number | null;
  diameter: number | null;
  tyreWidth: number | null;
  tyreHeight: number | null;
  construction: string | null;
  tyreType: string | null;
  season: string | null;
  wearMm: number | null;
  madeYear: number | null;
  discType: string | null;
  discWidth: number | null;
  offsetMm: number | null;
  boltPattern: string | null;
  hubBore: number | null;
  brand: string | null;
  model: string | null;
}

export function listWheels(): Promise<Wheel[]> {
  return request<Wheel[]>('/api/wheels');
}

export interface SetRequest {
  kind: 'TYRE' | 'DISC';
  warehouseId: number;
  quantity: number;
  diameter: string | null;
  tyreWidth: string | null;
  tyreHeight: string | null;
  season: string | null;
  wearMm: string | null;
  madeYear: string | null;
  discType: string | null;
  discWidth: string | null;
  offsetMm: string | null;
  boltPattern: string | null;
  hubBore: string | null;
  brand: string | null;
  model: string | null;
  price: string | null;
}

export function createSet(
  request_: SetRequest,
): Promise<{ setNo: number | null; title: string; partIds: number[] }> {
  return request('/api/wheels/sets', { method: 'POST', body: request_ });
}

/**
 * Размер строкой: «195/65 R15» для шины, «6.0x15 5x100 ET45» для диска.
 *
 * <p>В списке это первое, на что смотрят: покупатель называет размер,
 * а не название модели.
 */
export function sizeOf(wheel: Wheel): string {
  if (wheel.kind === 'TYRE') {
    const profile =
      wheel.tyreWidth !== null && wheel.tyreHeight !== null
        ? `${wheel.tyreWidth}/${wheel.tyreHeight}`
        : '';
    const rim = wheel.diameter === null ? '' : `${wheel.construction ?? 'R'}${wheel.diameter}`;
    return [profile, rim].filter((part) => part !== '').join(' ');
  }

  const size =
    wheel.discWidth !== null && wheel.diameter !== null
      ? `${wheel.discWidth}x${wheel.diameter}`
      : '';
  return [size, wheel.boltPattern, wheel.offsetMm === null ? '' : `ET${wheel.offsetMm}`]
    .filter((part) => part !== null && part !== '')
    .join(' ');
}
