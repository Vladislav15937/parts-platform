import { request } from '../api/client';
import { get, put, STORE_INVENTORY } from '../storage/db';
import { normalizeCode } from '../scan/codes';

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

/**
 * Статус пересчёта — ровно те четыре значения, что живут в базе и в Java
 * ({@code InventorySession.SessionStatus}, ограничение
 * `inventory_session_status_ck`).
 *
 * <p>Тип, а не `string`: пока статус был строкой, показ его человеку
 * приходилось страховать запасным `?? session.status`, то есть внутренним
 * написанием «COUNTED» вместо «Подсчёт завершён». С union'ом
 * {@link SESSION_STATUS_LABEL} покрывает все случаи, и запасной путь
 * не нужен вовсе — а новый статус, если он появится, уронит сборку здесь,
 * а не покажет своё имя кладовщику.
 */
export type SessionStatus = 'OPEN' | 'COUNTED' | 'APPLIED' | 'CANCELLED';

export interface InventorySession {
  id: number;
  warehouseId: number;
  status: SessionStatus;
  startedAt: string;
  lines: number;
  counted: number;
  /** Комментарий человека или `null`. Пустой строки не бывает — сервер её стирает. */
  note: string | null;
}

/**
 * Строка списка пересчётов — журнал, а не поиск по складу.
 *
 * <p>До этого закрытый пересчёт нельзя было найти вовсе: экран умел искать
 * только открытую сессию по складу, а журнал склада на пересчёт ссылается
 * («Пересчёт №4») и после проведения, и после отмены.
 */
export interface SessionSummary {
  id: number;
  warehouseId: number;
  warehouseName: string;
  /** Склад и ячейка словами: «Основной · A-01-03» или «Основной · весь склад». */
  selection: string;
  status: SessionStatus;
  startedAt: string;
  appliedAt: string | null;
  lines: number;
  counted: number;
  /**
   * Комментарий человека — то, ради чего в журнал заходят: «83619 не найден»,
   * «Не сканировали». `null` — не писали вовсе.
   */
  note: string | null;
}

/** Пункты воронки слева, в порядке, заданном задачей. */
export const SESSION_FUNNEL = [
  { key: 'OPEN', label: 'В работе' },
  { key: 'COUNTED', label: 'Выполненные' },
  { key: 'CANCELLED', label: 'Отменённые' },
  { key: 'ALL', label: 'Все пересчёты' },
] as const;

export type SessionFunnelKey = (typeof SESSION_FUNNEL)[number]['key'];

export const SESSION_STATUS_LABEL: Record<SessionStatus, string> = {
  OPEN: 'Идёт подсчёт',
  COUNTED: 'Подсчёт завершён',
  APPLIED: 'Проведён',
  CANCELLED: 'Отменён',
};

/**
 * Правится ли комментарий: закрытый пересчёт показывает его текстом.
 *
 * <p>То же условие, что на сервере ({@code InventorySession.changeNote}):
 * проведение записало корректировки в журнал, отмена выбросила лист обхода,
 * и приписка задним числом объясняла бы случившееся не тем, что видел
 * писавший. Кнопка, которую сервер отобьёт, хуже отсутствующей.
 */
export function noteEditable(status: SessionStatus): boolean {
  return status !== 'APPLIED' && status !== 'CANCELLED';
}

/**
 * Пишет комментарий к пересчёту.
 *
 * <p>Пустая строка стирает его: сервер приводит её к `NULL`, и «не заполнено»
 * не выдаёт себя за ответ человека.
 */
export function saveSessionNote(sessionId: number, note: string): Promise<InventorySession> {
  return request<InventorySession>(`/api/inventory/sessions/${sessionId}/note`, {
    method: 'POST',
    body: { note },
  });
}

/** Список пересчётов по воронке — «Все пересчёты» шлёт запрос без фильтра. */
export function listSessions(funnel: SessionFunnelKey): Promise<SessionSummary[]> {
  const query = funnel === 'ALL' ? '' : `?status=${funnel}`;
  return request<SessionSummary[]>(`/api/inventory/sessions${query}`);
}

/** Одна сессия любого статуса — открывается нажатием на строку списка. */
export function sessionSummary(sessionId: number): Promise<SessionSummary> {
  return request<SessionSummary>(`/api/inventory/sessions/${sessionId}`);
}

/**
 * Сентинел «без адреса» — тот же, что на сервере ({@code InventoryService.NO_CELL}).
 * Настоящие ячейки нумеруются с единицы, поэтому ноль безопасно означает
 * «позиции без ячейки» и не путается с «любая» ({@code undefined}).
 */
export const NO_CELL_ID = 0;

/**
 * Код детали по всему складу сессии, а не только по её выборке.
 *
 * <p>Нужен сканеру детали: лист обхода при выборке по ячейке содержит только
 * её позиции, и без списка кодов всего склада нельзя отличить «деталь лежит
 * в другой ячейке» от «код не найден на этом складе».
 */
export interface WarehouseCode {
  partId: number;
  title: string;
  publicCode: string | null;
  barcode: string | null;
  cellId: number | null;
  cellCode: string | null;
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
const KEY_CODES = 'codes';

/**
 * Открывает инвентаризацию склада — целиком или одной ячейкой. Только
 * онлайн: снимок остатка делает сервер.
 *
 * @param cellId {@code undefined} — «Любая» (весь склад, как раньше);
 *               {@link NO_CELL_ID} — «Без адреса»; иначе — конкретная ячейка
 */
export async function openSession(
  warehouseId: number,
  cellId?: number,
): Promise<InventorySession> {
  const session = await request<InventorySession>('/api/inventory/sessions', {
    method: 'POST',
    body: { warehouseId, cellId },
  });
  await adopt(session);
  return session;
}

/** Подхватывает уже открытую сессию с той же выборкой: обход мог начать другой кладовщик. */
export async function findOpenSession(
  warehouseId: number,
  cellId?: number,
): Promise<InventorySession | null> {
  const query = cellId === undefined ? '' : `&cellId=${cellId}`;
  const session = await request<InventorySession | undefined>(
    `/api/inventory/sessions/open?warehouseId=${warehouseId}${query}`,
  );
  if (session === undefined) {
    return null;
  }
  await adopt(session);
  return session;
}

/** Сколько позиций попадёт в лист обхода при этой выборке — счётчик формы открытия. */
export async function countPositions(warehouseId: number, cellId?: number): Promise<number> {
  const query = cellId === undefined ? '' : `&cellId=${cellId}`;
  const result = await request<{ count: number }>(
    `/api/inventory/count?warehouseId=${warehouseId}${query}`,
  );
  return result.count;
}

/**
 * Забирает лист обхода и коды всего склада, кладёт локально.
 *
 * <p>Подсчёты при этом сбрасываются: они относились к прежней сессии, и перенос
 * их в новую означал бы зачесть вчерашний обход за сегодняшний.
 *
 * <p>Коды склада — не только выборки сессии — нужны сканеру детали, чтобы
 * отличить «деталь в другой ячейке» от «код не найден», и должны быть
 * под рукой офлайн, поэтому забираются вместе с листом, а не по запросу.
 */
async function adopt(session: InventorySession): Promise<void> {
  const stored = await get<InventorySession>(STORE_INVENTORY, KEY_SESSION);
  const [lines, codes] = await Promise.all([
    request<InventoryLine[]>(`/api/inventory/sessions/${session.id}/lines`),
    request<WarehouseCode[]>(`/api/inventory/sessions/${session.id}/codes`),
  ]);

  await put(STORE_INVENTORY, session, KEY_SESSION);
  await put(STORE_INVENTORY, lines, KEY_LINES);
  await put(STORE_INVENTORY, codes, KEY_CODES);
  if (stored?.id !== session.id) {
    await put(STORE_INVENTORY, {}, KEY_COUNTS);
  }
}

export async function loadLocal(): Promise<{
  session: InventorySession | null;
  lines: InventoryLine[];
  counts: Record<string, LocalCount>;
  codes: WarehouseCode[];
}> {
  const [session, lines, counts, codes] = await Promise.all([
    get<InventorySession>(STORE_INVENTORY, KEY_SESSION),
    get<InventoryLine[]>(STORE_INVENTORY, KEY_LINES),
    get<Record<string, LocalCount>>(STORE_INVENTORY, KEY_COUNTS),
    get<WarehouseCode[]>(STORE_INVENTORY, KEY_CODES),
  ]);
  return {
    session: session ?? null,
    lines: lines ?? [],
    counts: counts ?? {},
    codes: codes ?? [],
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

/**
 * Добавляет локально позицию, которой не было в листе, — найденный сканом
 * излишек («вне списка»).
 *
 * <p>Пишется в тот же список, что и лист обхода, а не только в состояние
 * экрана: иначе перезапуск приложения до ухода очереди на сервер потерял бы
 * строку, а с ней и то, что кладовщик уже прошёл эту деталь сканером.
 */
export async function rememberUnlistedLine(line: InventoryLine): Promise<void> {
  const lines = (await get<InventoryLine[]>(STORE_INVENTORY, KEY_LINES)) ?? [];
  if (lines.some((l) => l.partId === line.partId)) {
    return;
  }
  await put(STORE_INVENTORY, [...lines, line], KEY_LINES);
}

/** Забывает локальную сессию: обход завершён или отменён. */
export async function forgetSession(): Promise<void> {
  await put(STORE_INVENTORY, {}, KEY_COUNTS);
  await put(STORE_INVENTORY, [], KEY_LINES);
  await put(STORE_INVENTORY, [], KEY_CODES);
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
 * Три состояния строки пересчёта — три группы и статуса на экране.
 *
 * <p>«Вне списка» узнаётся по учётному нулю ({@code qtyExpected === 0}),
 * а не по отдельному флагу: строку с таким учётом заводит только скан детали,
 * которой не было в снимке при открытии ({@code open()} берёт только позиции
 * с {@code qty > 0}), — обычная строка листа обхода нуля в учёте не имеет
 * никогда.
 */
export type LineStatus = 'unscanned' | 'problem' | 'scanned';

export function statusOf(line: InventoryLine, counts: Record<string, LocalCount>): LineStatus {
  if (Number(line.qtyExpected) === 0) {
    return 'problem';
  }
  return counts[String(line.partId)] === undefined ? 'unscanned' : 'scanned';
}

/**
 * Разбирает скан штрихкода детали против кодов всего склада сессии.
 *
 * <p>Ищет и по коду товара (наши этикетки), и по владельческому штрихкоду
 * (этикетка производителя): на разборке нет единого источника, и продавец
 * либо кладовщик читает то, что наклеено на детали. Совпавшая деталь,
 * которой нет среди строк текущей выборки, — «вне списка», а не «неизвестно»:
 * она лежит на этом же складе, просто в другом месте.
 */
export type PartScanMatch =
  | { kind: 'listed'; partId: number }
  | { kind: 'unlisted'; code: WarehouseCode }
  | { kind: 'not-found' };

export function resolvePartScan(
  codes: WarehouseCode[],
  lines: InventoryLine[],
  text: string,
): PartScanMatch {
  const scanned = normalizeCode(text);
  if (scanned === '') {
    return { kind: 'not-found' };
  }
  const found = codes.find(
    (c) =>
      (c.publicCode !== null && normalizeCode(c.publicCode) === scanned) ||
      (c.barcode !== null && normalizeCode(c.barcode) === scanned),
  );
  if (found === undefined) {
    return { kind: 'not-found' };
  }
  return lines.some((line) => line.partId === found.partId)
    ? { kind: 'listed', partId: found.partId }
    : { kind: 'unlisted', code: found };
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
