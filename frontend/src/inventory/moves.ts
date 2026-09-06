import { request } from '../api/client';

/**
 * Журнал перевозок между складами.
 *
 * <p>Список — по документам, а не по строкам: у переехавшего клиента
 * документ на семьдесят позиций, и построчный отчёт ориентира удобен
 * для выгрузки в таблицу и неудобен для чтения. Состав документа читается
 * отдельным запросом, по нажатию на строку.
 */
export interface MoveDocument {
  id: number;
  number: number;
  createdAt: string;
  fromWarehouse: string;
  toWarehouse: string;
  lines: number;
  note: string | null;
  /** Пусто у переноса из прежней системы — там вошедшего нет и быть не может. */
  author: string | null;
}

export interface MoveLine {
  partId: number;
  publicCode: string;
  title: string;
  qty: number;
}

export function loadMoveJournal(): Promise<MoveDocument[]> {
  return request<MoveDocument[]>('/api/stock/moves');
}

/** Состав документа — только по нажатию: список журнала от него не тяжелеет. */
export function loadMoveLines(documentId: number): Promise<MoveLine[]> {
  return request<MoveLine[]>(`/api/stock/moves/${documentId}/lines`);
}
