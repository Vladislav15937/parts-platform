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
  /**
   * Склад выдачи сделки. Пусто — его никто не проставил: колонка в схеме есть
   * с самого начала, а пишущего в неё кода нет, и у всех сделок она `null`.
   * Откуда ушёл товар, в таком случае знают сами позиции.
   */
  warehouseId: number | null;
  /** Площадка, если сделка пришла заказом: DROM или AVITO. Иначе пусто. */
  marketplace: string | null;
  /** Номер заказа у площадки — тот, который называет покупатель. */
  externalOrderNo: string | null;
  /** Срок ответа площадке. У Дрома пропущенный — возврат денег покупателю. */
  replyDeadline: string | null;
  orderAcceptedAt: string | null;
  deliveryNote: string | null;
  items: DealItem[];
  /** Доставка и упаковка: деньги сделки, а не примечание к ней. */
  services: DealServiceLine[];
}

/** Услуга в сделке. Склад не двигает — у неё нет детали. */
export interface DealServiceLine {
  id: number;
  serviceId: number;
  /** Название услуги: без него строка — это номер, как было у запчастей. */
  name: string | null;
  quantity: string;
  price: string;
}

/** Строка справочника услуг. Цена — подсказка, а не тариф. */
export interface ServiceKind {
  id: number;
  name: string;
  price: string | null;
}

/** Строка справочника источников: откуда пришла продажа. */
export interface DealSource {
  id: number;
  name: string;
}

export function dealSources(): Promise<DealSource[]> {
  return request<DealSource[]>('/api/deals/sources');
}

export function serviceKinds(): Promise<ServiceKind[]> {
  return request<ServiceKind[]>('/api/deals/services');
}

/**
 * Услуга, добавляемая в сделку.
 *
 * <p>Цена вводится в строке, а не берётся из справочника: доставка до Надыма
 * и до соседней улицы стоит по-разному.
 */
export interface ServiceLine {
  kind: ServiceKind;
  price: string;
}

export interface HistoryEntry {
  eventType: string;
  message: string;
  authorId: number | null;
  /**
   * Пусто — действие сделала система либо сотрудника удалили. «Автор 3»
   * вместо имени не говорит ничего: историю разбирают через недели, когда
   * по номеру никто никого не вспомнит.
   */
  authorName: string | null;
  createdAt: string;
}

/**
 * Выдача вместе с числом найденного.
 *
 * <p>Список обрезан на полусотне, и молча этого делать нельзя: продавец
 * видел пятьдесят строк из семисот сорока одной и не знал об этом ничего.
 * Ответить «нет такого», глядя на обрезанный список, — то же, что ответить
 * так на пустой, только тут продавец уверен, что посмотрел всё.
 */
export interface StockSearch {
  rows: StockRow[];
  total: number;
}

export function searchStock(query: string): Promise<StockSearch> {
  return request<StockSearch>(`/api/parts/stock?q=${encodeURIComponent(query)}`);
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

export function createDeal(
  customerId: number,
  lines: BasketLine[],
  services: ServiceLine[] = [],
  dealSourceId: number | null = null,
): Promise<Deal> {
  return request<Deal>('/api/deals', {
    method: 'POST',
    body: {
      customerId,
      dealSourceId,
      items: lines.map((line) => ({
        partId: line.row.partId,
        quantity: line.quantity,
        price: line.price === '' ? null : line.price,
        warehouseId: line.row.warehouseId,
      })),
      services: servicesBody(services),
    },
  });
}

function servicesBody(services: ServiceLine[]) {
  return services
    // Нулевую и пустую не отправляем: строка «Доставка 0 ₽» в документе
    // означает, что доставку оказали бесплатно, а не что её не было.
    .filter((s) => s.price !== '' && Number(s.price) > 0)
    .map((s) => ({ serviceId: s.kind.id, quantity: 1, price: s.price }));
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
 * @param refundToAccount зачислить деньги на лицевой счёт вместо выдачи
 *                    из кассы. Запись о выдаче создаётся независимо от того,
 *                    есть ли в кассе деньги, — а утром её может не быть,
 *                    и тогда касса к вечеру не сойдётся на сумму возврата
 */
export function registerReturn(
  dealId: number,
  warehouseId: number,
  items: ReturnLine[],
  reason: string,
  refundToAccount = false,
): Promise<ReturnDoc> {
  return request<ReturnDoc>(`/api/deals/${dealId}/returns`, {
    method: 'POST',
    body: { warehouseId, items, reason, refundToAccount },
  });
}

export function returnsOf(dealId: number): Promise<ReturnDoc[]> {
  return request<ReturnDoc[]>(`/api/deals/${dealId}/returns`);
}

/**
 * Строка реестра возвратов: обзор без входа в сделку клиента.
 *
 * <p>Раньше единственным списком был {@link returnsOf} — по открытой сделке,
 * то есть только если уже известны клиент и его сделка. Продавец, сменившийся
 * со смены, свой же возврат найти не мог: искать разговором «кто вернул
 * деталь через неделю» не с чем.
 */
export interface ReturnListRow {
  id: number;
  number: number | null;
  createdAt: string;
  dealId: number;
  dealNumber: number | null;
  customerId: number | null;
  /** Пусто — у сделки нет клиента; экран показывает «Частное лицо». */
  customerName: string | null;
  warehouseId: number | null;
  /**
   * Склад, на который принят товар. У брака ({@code !restocked}) на склад
   * ничего не вставало, и экран показывает это словами, а не адресом.
   */
  warehouseName: string | null;
  restocked: boolean;
  status: string;
  amount: string;
  reason: string | null;
}

/**
 * @param total       сколько нашлось по отбору — список может быть обрезан пределом
 * @param totalAmount сумма найденного без отменённых возвратов
 */
export interface ReturnsPage {
  items: ReturnListRow[];
  total: number;
  totalAmount: string;
}

/**
 * Реестр возвратов.
 *
 * <p>Вместо курсора — растущий предел {@code size}: список читают с конца
 * и вглубь не листают, а строка вычисляется тем же условием, что и подвал
 * таблицы (количество и сумма), — иначе они разойдутся на первой же правке.
 *
 * @param query поиск: точное совпадение по номеру сделки, вхождение —
 *              по клиенту и по причине
 * @param from  начало периода (ISO-момент); пусто — с начала времён
 * @param to    конец периода (ISO-момент); пусто — по текущий момент
 * @param size  сколько строк вернуть
 */
export function listReturns(
  query: string,
  from: string,
  to: string,
  size = 50,
): Promise<ReturnsPage> {
  const params = new URLSearchParams();
  if (query.trim() !== '') {
    params.set('q', query.trim());
  }
  if (from !== '') {
    params.set('from', from);
  }
  if (to !== '') {
    params.set('to', to);
  }
  params.set('size', String(size));
  return request<ReturnsPage>(`/api/deals/returns?${params.toString()}`);
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

/**
 * Склад, который подставляется в возврат.
 *
 * <p><b>Тот, откуда деталь выдали.</b> Раньше подставлялся первый склад
 * из ответа сервера, а тот отсортирован по названию: сделка, выданная
 * с «Основного», открывала форму возврата с «Дальним». Поле заполнено
 * и выглядит осмысленно, поэтому в него не смотрят, — и деталь встаёт
 * на чужую полку. В возврате это хуже, чем в приёмке: деталь уже была
 * на учёте, и искать её будут по прежнему адресу.
 *
 * <p>Сначала склад выдачи самой сделки, потом — тот, с которого ушли
 * выданные позиции. Позиции с разных складов не дают ответа вовсе: пусто
 * честнее, чем наугад, потому что промах будет таким же тихим. Продавец
 * выберет сам — он и так меняет склад, когда клиент привёз на другой.
 */
export function returnWarehouseDefault(deal: Deal): number | null {
  if (deal.warehouseId != null) {
    return deal.warehouseId;
  }
  const sources = [...new Set(returnable(deal).map((item) => item.warehouseId))];
  return sources.length === 1 ? sources[0] ?? null : null;
}

/**
 * Ссылка на сделку для клиента.
 *
 * <p>Отправляет её продавец сам — в Telegram, WhatsApp или SMS со своего
 * телефона. Своего отправителя нет намеренно: это договор с провайдером
 * и деньги, а ссылка работает в любом канале и не требует ничего.
 *
 * <p>Повторный вызов возвращает прежнюю ссылку, пока она не просрочена:
 * продавец нажимает второй раз, потому что потерял её в переписке.
 */
export function shareDeal(dealId: number): Promise<{ path: string; expires: string }> {
  return request<{ path: string; expires: string }>(`/api/deals/${dealId}/share`, {
    method: 'POST',
  });
}

export function historyOf(dealId: number): Promise<HistoryEntry[]> {
  return request<HistoryEntry[]>(`/api/deals/${dealId}/history`);
}

export function expiredReservations(): Promise<Deal[]> {
  return request<Deal[]>('/api/deals/expired-reservations');
}

/**
 * Продление срока резерва: клиент позвонил и попросил подержать ещё.
 *
 * <p>Склад это не двигает — товар и так отложен под того же клиента.
 * Просроченный резерв продлевается тоже: у живого клиента просрочена
 * половина отложенных сделок, и звонок приходит как раз по ним.
 */
export function extendReservation(dealId: number, reservedUntil: string): Promise<Deal> {
  return request<Deal>(`/api/deals/${dealId}/reservation`, {
    method: 'POST',
    body: { reservedUntil },
  });
}

/**
 * Начало выбранного дня в местном времени.
 *
 * <p>Пара к {@link endOfDay}: отбор «с» по возвратам берёт полночь того же
 * дня по местному времени, а не по Гринвичу — иначе на востоке страны
 * начало периода сдвинулось бы на день назад.
 */
export function startOfDay(date: string): string {
  const [year, month, day] = date.split('-');
  return new Date(Number(year), Number(month) - 1, Number(day), 0, 0, 0).toISOString();
}

/**
 * Конец выбранного дня в местном времени.
 *
 * <p>Поле выбора даты отдаёт «2026-09-12» без времени, а держать товар
 * до полуночи этого дня — не то же самое, что до его начала: резерв «до
 * двенадцатого» в разговоре означает, что двенадцатого он ещё держится.
 * Местное время, а не UTC: `new Date('2026-09-12')` — это полночь по
 * Гринвичу, то есть на востоке страны уже тринадцатое.
 */
export function endOfDay(date: string): string {
  const [year, month, day] = date.split('-');
  return new Date(Number(year), Number(month) - 1, Number(day), 23, 59, 59).toISOString();
}

/**
 * Срок резерва для показа: до какого числа держим и не вышел ли срок.
 *
 * <p>Пусто у всего, кроме отложенной сделки: у выданной и отменённой срок
 * уже ни о чём — товар либо у клиента, либо снова на полке, — и дата рядом
 * с ними читается как обещание, которого никто не давал.
 *
 * <p>Просроченный не показывает вчерашнее число: «до 3 сентября» пятого
 * продавец прочтёт как срок, а это очередь на обзвон. Резерв при этом
 * не снимается сам — «до завтра» на разборке часто значит «до послезавтра».
 */
export function reservationTerm(
  deal: Deal, now: number = Date.now(),
): { day: string; expired: boolean } | null {
  if (deal.status !== 'RESERVED' || deal.reservedUntil === null) {
    return null;
  }
  const until = new Date(deal.reservedUntil);
  return {
    day: until.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' }),
    expired: until.getTime() < now,
  };
}

/**
 * Заказ, оформленный покупателем на площадке.
 *
 * <p>Заводится руками: продавец видит заказ в кабинете Дрома и переносит его
 * сюда. Ключ к API защищённых сделок Дром выдаёт по запросу; когда он появится,
 * поменяется только то, кто зовёт этот метод.
 *
 * <p>Повтор по тому же номеру не создаёт вторую сделку — сервер вернёт
 * прежнюю с {@code replayed}. Это не ошибка и показывать её как ошибку нельзя:
 * продавец мог завести заказ дважды, и правильный ответ ему — «этот заказ
 * уже заведён, вот он».
 */
export interface OrderResult {
  deal: Deal;
  replayed: boolean;
  /** Чего не хватило на складе. Непусто — товар не зарезервирован. */
  missing: string[];
}

export function receiveOrder(
  marketplace: string,
  orderNo: string,
  /**
   * Клиента у заказа с площадки может не быть: покупателя она не называет,
   * а выдумывать его значит засорять справочник и врать отчёту по клиентам.
   */
  customerId: number | null,
  lines: BasketLine[],
  replyDeadline: string | null,
  deliveryNote: string,
  services: ServiceLine[] = [],
  dealSourceId: number | null = null,
): Promise<OrderResult> {
  return request<OrderResult>('/api/deals/orders', {
    method: 'POST',
    body: {
      marketplace,
      orderNo,
      customerId,
      dealSourceId,
      replyDeadline,
      deliveryNote: deliveryNote === '' ? null : deliveryNote,
      items: lines.map((line) => ({
        partId: line.row.partId,
        quantity: line.quantity,
        price: line.price === '' ? null : line.price,
        warehouseId: line.row.warehouseId,
      })),
      services: servicesBody(services),
    },
  });
}

/** Заказы площадок, по которым продавец ещё не ответил. Горящие сверху. */
export function ordersAwaitingReply(): Promise<Deal[]> {
  return request<Deal[]>('/api/deals/orders/awaiting-reply');
}

export function acceptOrder(dealId: number): Promise<Deal> {
  return request<Deal>(`/api/deals/orders/${dealId}/accept`, { method: 'POST' });
}

/**
 * Сколько осталось до срока ответа площадке.
 *
 * <p>Отдельная функция, потому что показывать надо не дату, а остаток: «до
 * 4 авг» продавец сопоставит с сегодняшним числом сам и ошибётся, а «осталось
 * 2 часа» не требует считать. Отрицательное — срок уже прошёл, и деньги
 * покупателю площадка, скорее всего, вернула.
 */
export function hoursUntilDeadline(deal: Deal, now: number = Date.now()): number | null {
  if (!deal.replyDeadline) {
    return null;
  }
  return (new Date(deal.replyDeadline).getTime() - now) / 3_600_000;
}

/**
 * Итого по корзине — то же число, что посчитает сервер.
 *
 * <p>Услуги входят: продавец называет клиенту одну сумму, и она обязана
 * совпасть с той, что окажется в документе, — иначе разговор про доставку
 * начнётся после оплаты.
 */
export function basketTotal(lines: BasketLine[], services: ServiceLine[] = []): number {
  const goods = lines.reduce((sum, line) => sum + Number(line.price || 0) * line.quantity, 0);
  const work = services.reduce((sum, s) => sum + Number(s.price || 0), 0);
  return goods + work;
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

/**
 * Лицевой счёт клиента: остаток и журнал операций.
 *
 * <p>Переплата ложится на него сама, а увидеть её продавцу было негде —
 * и при следующем приезде про свою тысячу помнил только клиент.
 */
export interface AccountEntry {
  id: number;
  entryType: string;
  amount: number;
  signedAmount: number;
  comment: string | null;
  createdAt: string;
}

export interface CustomerAccount {
  customerId: number;
  balance: number;
  entries: AccountEntry[];
}

export function accountOf(customerId: number): Promise<CustomerAccount> {
  return request<CustomerAccount>(`/api/customers/${customerId}/account`);
}

/**
 * Зачёт с лицевого счёта в оплату сделки.
 *
 * <p>Отдельно от обычной оплаты: платежа в кассу не создаётся — деньги
 * получены раньше, тогда же записан приход. Второй платёж задвоил бы выручку.
 */
export function payDealFromAccount(dealId: number, amount: string): Promise<unknown> {
  return request(`/api/deals/${dealId}/payments/from-account`, {
    method: 'POST',
    body: { amount },
  });
}

/**
 * Выдача со счёта наличными.
 *
 * <p>В отличие от зачёта создаёт расход в кассе: там деньги остаются у нас
 * и меняют назначение, здесь уходят клиенту.
 */
export function withdrawFromAccount(customerId: number, amount: string): Promise<unknown> {
  return request(`/api/customers/${customerId}/account/withdraw`, {
    method: 'POST',
    body: { amount },
  });
}

/** Пополнение счёта: клиент оставил деньги авансом. */
export function topUpAccount(customerId: number, amount: string): Promise<unknown> {
  return request(`/api/customers/${customerId}/account/top-up`, {
    method: 'POST',
    body: { amount },
  });
}

/**
 * Ручная правка остатка — со знаком и с обязательной причиной.
 *
 * <p>Только для того, что случилось вне системы: деньги приняли мимо кассы,
 * старый долг простили, при переезде остаток приехал не тем. Расхождения,
 * растущие из самой системы, лечатся в ней.
 */
export function correctAccount(
  customerId: number, amount: string, reason: string,
): Promise<unknown> {
  return request(`/api/customers/${customerId}/account/correct`, {
    method: 'POST',
    body: { amount, reason },
  });
}
