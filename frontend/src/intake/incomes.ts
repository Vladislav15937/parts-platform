import { request } from '../api/client';

/**
 * Раздел «Поступления» (задача 0360): документ поступления с черновиком.
 *
 * <p>Решение владельца 19.10.2026 «Всё как у bazon»: поступление создают
 * черновиком, набирают в него позиции и нажимают «Провести документ» — только
 * тогда остаток растёт. Каждое изменяющее действие отвечает документом целиком:
 * экран показывает и позиции, и историю без второго запроса.
 */

/** Код статуса документа; слово ставит {@link incomeStatusWord}. */
export type IncomeStatus = 'DRAFT' | 'DONE' | 'CANCELLED';

/** Вкладка списка — слева, как у ориентира. */
export type IncomeTab = 'drafts' | 'done' | 'cancelled' | 'all';

export interface IncomeRow {
  id: number;
  number: number;
  status: IncomeStatus;
  /** Выполненный документ удалён (задача 0367) — остаётся в «Выполненных» с отметкой. */
  deleted: boolean;
  createdAt: string;
  completedAt: string | null;
  supply: string | null;
  warehouse: string;
  /** Закупочная сумма; пусто — у вошедшего нет права «Смотреть закупочную цену» (part.money.view). */
  amount: number | null;
  note: string | null;
  lines: number;
}

export interface IncomeCounts {
  drafts: number;
  done: number;
  cancelled: number;
  all: number;
}

export interface IncomePage {
  rows: IncomeRow[];
  counts: IncomeCounts;
  /** Есть следующая страница: экран говорит, что показано не всё. */
  more: boolean;
}

export interface IncomeLine {
  partId: number;
  number: number;
  publicCode: string;
  title: string;
  condition: string | null;
  qty: number;
  cost: number | null;
}

export interface IncomeHistoryEntry {
  at: string;
  author: string | null;
  text: string;
  partId: number | null;
  partTitle: string | null;
}

export interface Income {
  id: number;
  number: number;
  status: IncomeStatus;
  deleted: boolean;
  createdAt: string;
  completedAt: string | null;
  supplyId: number | null;
  supply: string | null;
  warehouseId: number;
  warehouse: string;
  note: string | null;
  /** Закупочная сумма; пусто — у вошедшего нет права «Смотреть закупочную цену» (part.money.view). */
  amount: number | null;
  lines: IncomeLine[];
  history: IncomeHistoryEntry[];
}

/** Вкладки в порядке ориентира; «Черновики» — со счётчиком. */
export const INCOME_TABS: readonly { key: IncomeTab; label: string }[] = [
  { key: 'drafts', label: 'Черновики' },
  { key: 'done', label: 'Выполненные' },
  { key: 'cancelled', label: 'Отменённые' },
  { key: 'all', label: 'Все поступления' },
];

/** Статус словом ориентира: «Черновик», «Выполнен», «Отменён». */
export function incomeStatusWord(status: IncomeStatus, deleted = false): string {
  switch (status) {
    case 'DRAFT': return 'Черновик';
    case 'DONE': return deleted ? 'Выполнен (Удалён)' : 'Выполнен';
    case 'CANCELLED': return 'Отменён';
  }
}

export function loadIncomes(tab: IncomeTab, before?: number): Promise<IncomePage> {
  const query = new URLSearchParams({ tab });
  if (before !== undefined) query.set('before', String(before));
  return request<IncomePage>(`/api/intake/incomes?${query.toString()}`);
}

export function loadIncome(id: number): Promise<Income> {
  return request<Income>(`/api/intake/incomes/${id}`);
}

/**
 * «Создать поступление». Ключ формы заводится при её открытии и не меняется
 * при повторе: второе нажатие после потерянного ответа отдаёт тот же черновик.
 */
export function createIncome(body: {
  warehouseId: number | null;
  supplyId: number | null;
  note: string | null;
  requestId: string;
}): Promise<Income> {
  return request<Income>('/api/intake/incomes', { method: 'POST', body });
}

export interface IncomeItem {
  rawName: string;
  quantity: number;
  price: number | null;
  costPrice: number | null;
  note: string | null;
  requestId: string;
}

/** «+ Добавить»: позиция в черновик — карточка без остатка и строка документа. */
export function addIncomeItem(id: number, item: IncomeItem): Promise<Income> {
  return request<Income>(`/api/intake/incomes/${id}/items`, { method: 'POST', body: item });
}

export function postIncome(id: number): Promise<Income> {
  return request<Income>(`/api/intake/incomes/${id}/post`, { method: 'POST' });
}

export function unpostIncome(id: number): Promise<Income> {
  return request<Income>(`/api/intake/incomes/${id}/unpost`, { method: 'POST' });
}

export function cancelIncome(id: number): Promise<Income> {
  return request<Income>(`/api/intake/incomes/${id}/cancel`, { method: 'POST' });
}

/**
 * «+ Новая поставка» из формы поступления: окно ориентира спрашивает только
 * «Название» и «Описание», поэтому вид — «прочая» (OTHER), а название — номер.
 */
export function addSupply(name: string, note: string | null): Promise<{ id: number; number: string }> {
  return request<{ id: number; number: string }>('/api/intake/supplies', {
    method: 'POST',
    body: { kind: 'OTHER', number: name, note },
  });
}

/** Ключ повтора: `crypto.randomUUID` есть только на защищённом адресе. */
export function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}
