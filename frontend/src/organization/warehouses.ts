import { request } from '../api/client';

/**
 * Склады арендатора.
 *
 * <p>Берутся с сервера, а не из справочников приёмки: приёмка живёт офлайн
 * и держит свою копию в IndexedDB, а продажа работает только при связи.
 * Возврат оформляют на тот склад, где клиент оставил деталь, и список складов
 * тут должен быть сегодняшний, а не тот, что скачали в понедельник.
 */
export interface Warehouse {
  id: number;
  branchId: number | null;
  name: string;
  branchName: string | null;
  cells: number;
}

export function listWarehouses(): Promise<Warehouse[]> {
  return request<Warehouse[]>('/api/organization/warehouses');
}

/** Филиал: физический адрес, за которым стоят склады. */
export interface Branch {
  id: number;
  name: string;
}

/** Ячейка хранения — адрес полки, который печатают на этикетке. */
export interface Cell {
  id: number;
  code: string;
  zone: string | null;
  active: boolean;
}

export function listBranches(): Promise<Branch[]> {
  return request<Branch[]>('/api/organization/branches');
}

export function createBranch(name: string): Promise<Branch> {
  return request<Branch>('/api/organization/branches', { method: 'POST', body: { name } });
}

export function createWarehouse(name: string, branchId: number | null): Promise<Warehouse> {
  return request<Warehouse>('/api/organization/warehouses', {
    method: 'POST',
    body: { name, branchId },
  });
}

export function listCells(warehouseId: number): Promise<Cell[]> {
  return request<Cell[]>(`/api/organization/warehouses/${warehouseId}/cells`);
}

/**
 * Заводит ячейки списком.
 *
 * <p>Стеллаж — это два-три десятка адресов подряд, и по одному их не заведёт
 * никто: коды уедут в примечание, а поиск детали на полке вернётся к памяти
 * кладовщика. Уже существующие пропускаются, а не ломают запрос целиком.
 */
export function createCells(
  warehouseId: number, codes: string[], zone: string | null,
): Promise<Cell[]> {
  return request<Cell[]>(`/api/organization/warehouses/${warehouseId}/cells`, {
    method: 'POST',
    body: { codes, zone },
  });
}

/**
 * Буквы, которых нет в Code128 и у которых нет латинского двойника.
 *
 * <p>«А», «В», «Е», «К» и прочие похожие сканер сводит к латинице, а «Б»,
 * «Г», «Д» не отсканируются никогда. Подставить похожую нельзя: «Б-02-1»,
 * напечатанная как «B-02-1», сольётся с настоящей «В-02-1», и деталь ляжет
 * на другой стеллаж. Поэтому такие адреса лучше не заводить вовсе, и экран
 * предупреждает об этом до, а не на печати этикеток.
 */
const UNPRINTABLE = /[БГДЖЗИЙЛПФЦЧШЩЪЫЬЭЮЯбгджзийлмнптфцчшщъыьэюя]/;

export function unprintableCells(codes: string[]): string[] {
  return codes.filter((code) => UNPRINTABLE.test(code));
}
