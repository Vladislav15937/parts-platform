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
