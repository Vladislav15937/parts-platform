import { request } from '../api/client';

/**
 * Общий справочник машин (задача 0377): марка → модель → кузов, двигатели —
 * списком марки. Один на всех клиентов, на сервере в общей схеме; у позиции
 * хранится только ссылка на запись.
 */

export interface BazonMark { id: number; name: string }
export interface BazonModel { id: number; name: string }
export interface BazonBody {
  id: number;
  /** Код кузова («TNT31»); у части записей справочника пуст. */
  name: string | null;
  /** Пометка вроде «Пикап»; подпись, если кода нет. */
  description: string | null;
  generation: string;
  years: string;
}
export interface BazonMotor {
  id: number;
  name: string;
  volumeCc: number | null;
  fuel: string;
}

/** Выбранная машина: ссылки на записи справочника; все поля необязательны. */
export interface BazonSelection {
  markId: number | null;
  modelId: number | null;
  bodyId: number | null;
  motorId: number | null;
}

export const NO_BAZON_VEHICLE: BazonSelection =
  { markId: null, modelId: null, bodyId: null, motorId: null };

export function isBazonEmpty(s: BazonSelection | null | undefined): boolean {
  return s == null
    || (s.markId == null && s.modelId == null && s.bodyId == null && s.motorId == null);
}

export function listBazonMarks(): Promise<BazonMark[]> {
  return request<BazonMark[]>('/api/catalog/bazon/marks');
}

export function listBazonModels(markId: number): Promise<BazonModel[]> {
  return request<BazonModel[]>(`/api/catalog/bazon/marks/${markId}/models`);
}

export function listBazonMotors(markId: number): Promise<BazonMotor[]> {
  return request<BazonMotor[]>(`/api/catalog/bazon/marks/${markId}/motors`);
}

export function listBazonBodies(modelId: number): Promise<BazonBody[]> {
  return request<BazonBody[]>(`/api/catalog/bazon/models/${modelId}/bodies`);
}

/** Подпись кузова в списке: код и поколение, а без кода — пометка. */
export function bodyLabel(b: BazonBody): string {
  const head = b.name ?? b.description ?? 'без кода';
  return `${head} · ${b.generation} ${b.years}`;
}

/** Подпись двигателя: код, объём, если он известен. */
export function motorLabel(m: BazonMotor): string {
  return m.volumeCc == null ? m.name : `${m.name} · ${(m.volumeCc / 1000).toFixed(1)} л`;
}
