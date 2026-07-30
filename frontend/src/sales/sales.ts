import { request } from '../api/client';

/**
 * Продажи: работа продавца за столом.
 *
 * <p><b>Ни очереди, ни кэша — и это главное отличие от приёмки.</b> Там всё
 * построено вокруг того, что связи нет: экран кладёт запись в IndexedDB
 * и считает работу сделанной. Здесь ровно наоборот.
 *
 * <p>Кэшировать остаток нельзя: закэшированный ответ склада — это деталь,
 * которой уже нет, у продавца на экране. Он пообещает её клиенту по телефону,
 * а она продана десять минут назад.
 *
 * <p>Ставить сделку в очередь нельзя: резерв обещает товар конкретному
 * клиенту, и обещание, лежащее в телефоне продавца, ничего не резервирует.
 * Двое продавцов офлайн продадут одну деталь дважды и узнают об этом, когда
 * оба клиента приедут. Нет связи — продавец должен это видеть, а не думать,
 * что сделка оформлена.
 */

/** Деталь на конкретном складе. Свободный остаток, а не статус карточки. */
export interface StockRow {
  partId: number;
  publicCode: string | null;
  title: string;
  price: string | null;
  status: string;
  warehouseId: number;
  warehouseName: string;
  cellCode: string | null;
  qty: string;
  qtyReserved: string;
  qtyAvailable: string;
}

export interface Customer {
  id: number;
  name: string | null;
  phone: string | null;
  email: string | null;
  customerType: string;
}

export interface DealItem {
  id: number;
  partId: number;
  /** Пусто, если карточку удалили: строка сделки переживает запчасть. */
  title: string | null;
  quantity: string;
  price: string;
  discount: string | null;
  warehouseId: number;
  status: string;
}

export interface Deal {
  id: number;
  number: number | null;
  customerId: number;
  managerId: number | null;
  status: string;
  reservedUntil: string | null;
  totalAmount: string;
  paidAmount: string;
  debt: string;
  createdAt: string;
  issuedAt: string | null;
  items: DealItem[];
}

export interface HistoryEntry {
  eventType: string;
  message: string;
  authorId: number | null;
  createdAt: string;
}

export function searchStock(query: string): Promise<StockRow[]> {
  return request<StockRow[]>(`/api/parts/stock?q=${encodeURIComponent(query)}`);
}

export function searchCustomers(query: string): Promise<Customer[]> {
  return request<Customer[]>(`/api/customers?q=${encodeURIComponent(query)}`);
}

export function createCustomer(name: string, phone: string): Promise<Customer> {
  return request<Customer>('/api/customers', { method: 'POST', body: { name, phone } });
}

export interface BasketLine {
  row: StockRow;
  quantity: number;
  /** Цена может отличаться от карточной: скидку дают в разговоре. */
  price: string;
}

export function createDeal(customerId: number, lines: BasketLine[]): Promise<Deal> {
  return request<Deal>('/api/deals', {
    method: 'POST',
    body: {
      customerId,
      items: lines.map((line) => ({
        partId: line.row.partId,
        quantity: line.quantity,
        price: line.price === '' ? null : line.price,
        warehouseId: line.row.warehouseId,
      })),
    },
  });
}

export function dealsOf(customerId: number): Promise<Deal[]> {
  return request<Deal[]>(`/api/deals?customerId=${customerId}`);
}

export function deal(dealId: number): Promise<Deal> {
  return request<Deal>(`/api/deals/${dealId}`);
}

export function issueDeal(dealId: number): Promise<Deal> {
  return request<Deal>(`/api/deals/${dealId}/issue`, { method: 'POST' });
}

export function cancelDeal(dealId: number, reason: string): Promise<Deal> {
  return request<Deal>(`/api/deals/${dealId}/cancel`, { method: 'POST', body: { reason } });
}

export function payDeal(dealId: number, amount: string): Promise<unknown> {
  return request(`/api/deals/${dealId}/payments`, { method: 'POST', body: { amount } });
}

/** Позиция, которую возвращают. */
export interface ReturnLine {
  dealItemId: number;
  /**
   * Сколько возвращают. Пусто — вся строка целиком: у б/у детали количество
   * почти всегда единица, и заставлять продавца писать «1» незачем.
   */
  quantity?: string;
  /**
   * Снимают для брака: деньги клиенту вернуть, а в остаток не ставить.
   * Продать сломанное второй раз нельзя, и висеть на складе оно не должно.
   */
  restocked: boolean;
}

export interface ReturnDoc {
  id: number;
  number: number | null;
  dealId: number;
  warehouseId: number;
  status: string;
  amount: string;
  reason: string | null;
  createdAt: string;
}

/**
 * Возврат выданного товара.
 *
 * <p>Отдельный документ со своим номером, а не отмена сделки: деталь у клиента,
 * деньги в кассе, и оба факта надо отразить. Выданную сделку не отменяют
 * вовсе — отменять уже нечего.
 *
 * <p>Возврат проводится сразу и обратно не отыгрывается: деталь принята
 * на склад, деньги отданы. Поэтому экран спрашивает подтверждение, а кнопки
 * «отменить возврат» у него нет — сервер такую отмену отклонит.
 *
 * @param warehouseId склад возврата. Не обязан совпадать со складом выдачи:
 *                    клиент приезжает туда, куда ему удобно, а деталь встаёт
 *                    на ту полку, где он её оставил
 */
export function registerReturn(
  dealId: number,
  warehouseId: number,
  items: ReturnLine[],
  reason: string,
): Promise<ReturnDoc> {
  return request<ReturnDoc>(`/api/deals/${dealId}/returns`, {
    method: 'POST',
    body: { warehouseId, items, reason, refundToAccount: false },
  });
}

export function returnsOf(dealId: number): Promise<ReturnDoc[]> {
  return request<ReturnDoc[]>(`/api/deals/${dealId}/returns`);
}

/**
 * Переносит позиции в новую сделку.
 *
 * <p>Клиент забирает половину сейчас, остальное оставляет на потом. Резерв
 * при этом не снимается — товар просто меняет документ, и вторая половина
 * остаётся обещанной тому же клиенту.
 */
export function transferItems(dealId: number, itemIds: number[]): Promise<Deal> {
  return request<Deal>(`/api/deals/${dealId}/transfer`, {
    method: 'POST',
    body: { itemIds },
  });
}

/**
 * Что можно перенести: только отложенное.
 *
 * <p>Выданное переносить нечего — оно у клиента; возвращённое и снятое тем
 * более. Иначе продавец отметит уже возвращённую строку, а откажет ему сервер.
 */
export function transferable(deal: Deal): DealItem[] {
  return deal.items.filter((item) => item.status === 'RESERVED');
}

/**
 * Что можно вернуть: только выданное.
 *
 * <p><b>Возвращённое сюда не попадает.</b> При частичном возврате сделка
 * остаётся выданной, и в ней лежат строки обоих видов; предложи мы вернуть
 * возвращённое — деталь встала бы на склад дважды, а деньги ушли бы клиенту
 * второй раз.
 */
export function returnable(deal: Deal): DealItem[] {
  return deal.items.filter((item) => item.status === 'ISSUED');
}

export function historyOf(dealId: number): Promise<HistoryEntry[]> {
  return request<HistoryEntry[]>(`/api/deals/${dealId}/history`);
}

export function expiredReservations(): Promise<Deal[]> {
  return request<Deal[]>('/api/deals/expired-reservations');
}

/** Итого по корзине — то же число, что посчитает сервер. */
export function basketTotal(lines: BasketLine[]): number {
  return lines.reduce((sum, line) => sum + Number(line.price || 0) * line.quantity, 0);
}

/**
 * Сколько ещё можно положить в корзину.
 *
 * <p>Свободный остаток минус уже отложенное в этой же корзине: сервер
 * отклонит перебор, но узнать об этом в момент нажатия лучше, чем при
 * оформлении, когда клиент уже ждёт на линии.
 */
export function roomFor(row: StockRow, lines: BasketLine[]): number {
  const taken = lines
    .filter((line) => line.row.partId === row.partId && line.row.warehouseId === row.warehouseId)
    .reduce((sum, line) => sum + line.quantity, 0);
  return Math.max(0, Number(row.qtyAvailable) - taken);
}
