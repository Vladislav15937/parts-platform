import { request } from '../api/client';
import { get, put, STORE_INVENTORY } from '../storage/db';

/**
 * Инвентаризация с телефона.
 *
 * <p><b>Лист обхода скачивается целиком при открытии сессии.</b> Склад
 * на разборке — от трёх до пятидесяти тысяч позиций, это несколько мегабайт,
 * и качать их по мере обхода нельзя: полки стоят в ангаре, где связи нет,
 * и кладовщик упрётся в пустой экран ровно там, где работает. Открывают
 * сессию за столом, туда же и качают.
 *
 * <p><b>Подсчёты уходят по одному, а не пачкой.</b> В отличие от приёмки:
 * между позициями проходят минуты, обход длится часами, и терять его целиком
 * из-за одного отказа нельзя. Приёмка же собирается за минуты, и там пачка
 * означает одну повторяемую операцию вместо тридцати.
 */

export interface InventoryLine {
  partId: number;
  title: string;
  cellId: number | null;
  cellCode: string | null;
  qtyExpected: string;
  /** Пусто — до полки не дошли. Это не то же самое, что «не нашли». */
  qtyCounted: string | null;
}

export interface InventorySession {
  id: number;
  warehouseId: number;
  status: string;
  startedAt: string;
  lines: number;
  counted: number;
}

/**
 * Внесённый подсчёт, ещё не подтверждённый сервером.
 *
 * <p>Момент подсчёта хранится по часам устройства и на сервер как момент
 * не уходит: туда идёт давность. Часы телефона врут смещением — сброшенное
 * устройство приходит из ангара с датой другого года, — а смещение в разности
 * двух своих же отсчётов сокращается.
 */
export interface LocalCount {
  qty: string;
  countedAt: number;
}

const KEY_SESSION = 'session';
const KEY_LINES = 'lines';
const KEY_COUNTS = 'counts';

/** Открывает инвентаризацию склада. Только онлайн: снимок остатка делает сервер. */
export async function openSession(warehouseId: number): Promise<InventorySession> {
  const session = await request<InventorySession>('/api/inventory/sessions', {
    method: 'POST',
    body: { warehouseId },
  });
  await adopt(session);
  return session;
}

/** Подхватывает уже открытую сессию: обход мог начать другой кладовщик. */
export async function findOpenSession(warehouseId: number): Promise<InventorySession | null> {
  const session = await request<InventorySession | undefined>(
    `/api/inventory/sessions/open?warehouseId=${warehouseId}`,
  );
  if (session === undefined) {
    return null;
  }
  await adopt(session);
  return session;
}

/**
 * Забирает лист обхода и кладёт локально.
 *
 * <p>Подсчёты при этом сбрасываются: они относились к прежней сессии, и перенос
 * их в новую означал бы зачесть вчерашний обход за сегодняшний.
 */
async function adopt(session: InventorySession): Promise<void> {
  const stored = await get<InventorySession>(STORE_INVENTORY, KEY_SESSION);
  const lines = await request<InventoryLine[]>(`/api/inventory/sessions/${session.id}/lines`);

  await put(STORE_INVENTORY, session, KEY_SESSION);
  await put(STORE_INVENTORY, lines, KEY_LINES);
  if (stored?.id !== session.id) {
    await put(STORE_INVENTORY, {}, KEY_COUNTS);
  }
}

export async function loadLocal(): Promise<{
  session: InventorySession | null;
  lines: InventoryLine[];
  counts: Record<string, LocalCount>;
}> {
  const [session, lines, counts] = await Promise.all([
    get<InventorySession>(STORE_INVENTORY, KEY_SESSION),
    get<InventoryLine[]>(STORE_INVENTORY, KEY_LINES),
    get<Record<string, LocalCount>>(STORE_INVENTORY, KEY_COUNTS),
  ]);
  return {
    session: session ?? null,
    lines: lines ?? [],
    counts: counts ?? {},
  };
}

/** Запоминает подсчёт локально: экран обязан показывать пройденное сразу. */
export async function rememberCount(partId: number, qty: string): Promise<LocalCount> {
  const counts = (await get<Record<string, LocalCount>>(STORE_INVENTORY, KEY_COUNTS)) ?? {};
  const count: LocalCount = { qty, countedAt: Date.now() };
  counts[String(partId)] = count;
  await put(STORE_INVENTORY, counts, KEY_COUNTS);
  return count;
}

/** Забывает локальную сессию: обход завершён или отменён. */
export async function forgetSession(): Promise<void> {
  await put(STORE_INVENTORY, {}, KEY_COUNTS);
  await put(STORE_INVENTORY, [], KEY_LINES);
  await put(STORE_INVENTORY, undefined, KEY_SESSION);
}

/**
 * Строки одной ячейки.
 *
 * <p>Позиции без ячейки показываются отдельной группой, а не прячутся:
 * деталь без адреса всё равно лежит на складе и в пересчёт входит.
 */
export function linesOfCell(lines: InventoryLine[], cellId: number | null): InventoryLine[] {
  return lines.filter((line) => line.cellId === cellId);
}

/** Ячейки листа обхода в порядке кодов — маршрут кладовщика. */
export function cellsOf(lines: InventoryLine[]): { id: number | null; code: string }[] {
  const seen = new Map<number | null, string>();
  for (const line of lines) {
    if (!seen.has(line.cellId)) {
      seen.set(line.cellId, line.cellCode ?? 'без ячейки');
    }
  }
  return Array.from(seen, ([id, code]) => ({ id, code })).sort((a, b) =>
    a.code.localeCompare(b.code, 'ru'),
  );
}

/**
 * Сведение расхождений — не с телефона.
 *
 * <p>Кладовщик обходит полки, а завершает пересчёт и проводит его тот, кто
 * отвечает за склад: списанная недостача — это убыток. До появления этих
 * вызовов завершить и провести можно было только запросом к API, то есть
 * с разработчиком.
 */
export interface Discrepancy {
  partId: number;
  title: string | null;
  qtyExpectedAtOpen: number;
  qtyExpectedAtCount: number;
  qtyCounted: number;
  delta: number;
  shortage: boolean;
  /** Проведена ли строка: расхождение считается на момент подсчёта и после
   *  проведения никуда не девается. */
  applied: boolean;
}

export interface Applied {
  sessionId: number;
  adjusted: number;
  /** Что не проведено: недостача по детали, которую держит резерв. */
  blocked: string[];
}

export function finishCounting(sessionId: number): Promise<InventorySession> {
  return request<InventorySession>(`/api/inventory/sessions/${sessionId}/finish`, {
    method: 'POST',
  });
}

export function discrepanciesOf(sessionId: number): Promise<Discrepancy[]> {
  return request<Discrepancy[]>(`/api/inventory/sessions/${sessionId}/discrepancies`);
}

export function applySession(sessionId: number): Promise<Applied> {
  return request<Applied>(`/api/inventory/sessions/${sessionId}/apply`, { method: 'POST' });
}

/**
 * Отменяет сессию, не трогая склад.
 *
 * <p>Единственный выход из ошибочно открытой инвентаризации: вторую на том же
 * складе открыть нельзя — две дадут двойную корректировку, — и кладовщик,
 * выбравший не тот склад, запирал пересчёт на нём насовсем.
 */
export function cancelSession(sessionId: number): Promise<InventorySession> {
  return request<InventorySession>(`/api/inventory/sessions/${sessionId}/cancel`, {
    method: 'POST',
  });
}

export function linesOfSession(sessionId: number): Promise<InventoryLine[]> {
  return request<InventoryLine[]>(`/api/inventory/sessions/${sessionId}/lines`);
}
