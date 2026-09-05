import { request } from '../api/client';
import { count, goods } from '../ui/plural';

/**
 * Отчёты владельца.
 *
 * <p>Считает их база: правила расчёта записаны в представлениях, и главное
 * из них — выручка идёт по статусу позиции, а не только документа. При
 * частичном возврате сделка остаётся выданной, и без этого условия менеджеру
 * капала бы премия за товар, привезённый обратно.
 *
 * <p>Ни кэша, ни очереди: цифры смотрят за столом, а закэшированная зарплата
 * хуже её отсутствия.
 */

export interface ManagerRow {
  /** Пусто у сделок, оформленных до появления учёта продавцов. */
  managerId: number | null;
  displayName: string | null;
  dealsCount: number;
  revenue: string | null;
  /**
   * По снимку себестоимости на момент продажи, а не по нынешней цене закупки.
   * Пусто, когда себестоимости не было ни у одной позиции: ноль вместо неё
   * читался бы как «продали в ноль», а это другое утверждение.
   */
  margin: string | null;
  /** Сколько позиций в наценку не вошли: у склада из чужой таблицы закупки нет. */
  itemsWithoutCost: number;
}

export interface ManagerReport {
  month: string;
  rows: ManagerRow[];
}

export interface DonorRow {
  donorId: number;
  publicCode: string | null;
  /** Номер машины в предыдущей системе: переехавший клиент зовёт её так. */
  legacyCode: string | null;
  /** Марка и модель, пока каталог не сопоставлен. */
  note: string | null;
  vin: string | null;
  year: number | null;
  totalCost: string;
  revenue: string;
  profit: string;
  partsTotal: number;
  partsSold: number;
  /** Во сколько оценено то, что ещё не продано: свежая машина иначе выглядит убыточной. */
  stockValue: string;
}

export interface DonorReport {
  rows: DonorRow[];
  totals: {
    donors: number;
    totalCost: string;
    revenue: string;
    stockValue: string;
  };
}

/**
 * Строка отчёта по каналам.
 *
 * <p>{@code sourceName} пустой — источник у сделки не указан. Не «прочее»:
 * это не канал, а незаполненное поле, и лечится оно привычкой продавца,
 * а не переименованием строки в отчёте.
 */
export interface SourceRow {
  sourceId: number | null;
  sourceName: string | null;
  dealsCount: number;
  revenue: string | null;
  margin: string | null;
  itemsWithoutCost: number;
}

export interface SourceReport {
  month: string;
  rows: SourceRow[];
}

export function salesBySource(month: string): Promise<SourceReport> {
  return request<SourceReport>(`/api/reports/sources?month=${month}`);
}

/**
 * Какая доля выручки пришла без указанного источника.
 *
 * <p>Пока она велика, остальным строкам отчёта верить нельзя: «Дром принёс
 * мало» и «продавцы не отмечают Дром» с экрана выглядят одинаково.
 */
export function unknownShare(report: SourceReport): number {
  const total = report.rows.reduce((sum, row) => sum + Number(row.revenue ?? 0), 0);
  if (total === 0) {
    return 0;
  }
  const unknown = report.rows
    .filter((row) => row.sourceId === null)
    .reduce((sum, row) => sum + Number(row.revenue ?? 0), 0);
  return unknown / total;
}

export function managerSales(month: string): Promise<ManagerReport> {
  return request<ManagerReport>(`/api/reports/managers?month=${month}`);
}

export function donorProfitability(): Promise<DonorReport> {
  return request<DonorReport>('/api/reports/donors');
}

/** Месяц вида 2026-07 — тот же формат, что понимает сервер. */
export function monthOf(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

/**
 * Сдвиг месяца.
 *
 * <p>Через `Date` с первым числом: прибавление к «31 марта» одного месяца даёт
 * 31 апреля, то есть первое мая, и отчёт за апрель молча уехал бы на май.
 */
export function shiftMonth(month: string, by: number): string {
  const [year, index] = parse(month);
  return monthOf(new Date(year, index - 1 + by, 1));
}

const MONTHS = [
  'январь', 'февраль', 'март', 'апрель', 'май', 'июнь',
  'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь',
];

export function monthName(month: string): string {
  const [year, index] = parse(month);
  return `${MONTHS[index - 1] ?? month} ${year}`;
}

function parse(month: string): [number, number] {
  const parts = month.split('-');
  return [Number(parts[0]), Number(parts[1])];
}

/** Рубли без копеек: в отчёте важен порядок, а не последние два знака. */
export function money(value: string | number | null): string {
  return `${Math.round(Number(value ?? 0)).toLocaleString('ru-RU')} ₽`;
}

/**
 * Сводка: что лежит на складе и что висит в незакрытых сделках.
 *
 * <p>Первый вопрос владельца разборки — «сколько у меня сейчас на складе
 * в деньгах», и остальные четыре отчёта на него не отвечают: они все про
 * прошлое. Периода здесь нет и не должно быть: остаток и незакрытые сделки
 * существуют «сейчас».
 *
 * <p>Числа приходят числами, а не строками: `numeric` из Postgres Jackson
 * отдаёт числом JSON, и объявить их строкой значит получить падение
 * на первом же `.trim()` — так уже было у цены на экране выгрузок.
 */
export interface StockLine {
  /**
   * Сколько физически лежит на всех складах вместе — не свободный остаток.
   * Отложенная под клиента деталь с полки не делась, и вычесть её значило бы
   * показать владельцу недостачу ровно на объём отложенного.
   */
  qty: number;
  /** Во сколько это оценено по розничной цене. */
  amount: number;
}

export interface Summary {
  parts: StockLine;
  /** Колёса отдельной строкой: они продаются сезоном, и на них смотрят отдельно. */
  wheels: StockLine;
  deals: {
    count: number;
    amount: number;
    /** Сколько по этим сделкам уже внесено: разница — то, что ещё должны. */
    prepaid: number;
  };
}

export function summary(): Promise<Summary> {
  return request<Summary>('/api/reports/summary');
}

/** «35 773 шт.» — количество на складе с разделителем разрядов. */
export function pieces(value: number): string {
  return `${value.toLocaleString('ru-RU')} шт.`;
}

/**
 * Расчёты с клиентами: у кого наши деньги и кто должен нам.
 *
 * <p>Сверка едет вместе с итогом намеренно: число обязательств, рядом
 * с которым не сказано, сходится ли оно, — спокойствие без основания.
 * У склада такая сверка есть с самого начала, у денег не было ни одной.
 */
export interface SettlementRow {
  customerId: number;
  customerName: string | null;
  phone: string | null;
  accountBalance: number;
  debt: number;
  unpaidDeals: number;
}

export interface SettlementProblem {
  customerId: number | null;
  entryId: number | null;
  dealId: number | null;
  problem: string;
  amount: number;
}

export interface SettlementReport {
  rows: SettlementRow[];
  totals: {
    advances: number;
    debts: number;
    withAdvance: number;
    withDebt: number;
    /** Сколько клиентов в расчётах всего: список обрезан пределом. */
    customers: number;
    problems: SettlementProblem[];
  };
}

export function customerSettlements(): Promise<SettlementReport> {
  return request<SettlementReport>('/api/reports/customers');
}

/**
 * Что поступило с машины и с партии — позициями.
 *
 * <p>Числа по машине владелец видит и так: «продано на 331 716, лежит
 * на 835 600». Спросить «а что именно лежит» было нельзя — за этим он уходил
 * в склад и собирал отбор руками. По партии не было и чисел, а контейнер
 * из Японии окупается ровно так же, как машина.
 *
 * <p>Вкладки — не четыре независимых отбора, а разбиение: «Поступило»
 * складывается из остальных трёх до последней позиции. Разойдись они,
 * владелец получил бы два разных ответа на один вопрос.
 */
export type OriginTab = 'received' | 'sold' | 'written-off' | 'remaining';

export interface OriginItem {
  partId: number;
  /** Номер, по которому позицию видно на витрине; внутренний id не говорит ничего. */
  publicCode: string | null;
  /** Вид детали из справочника. Пусто — наименование не распознано. */
  kind: string | null;
  title: string;
  /** Смысл зависит от вкладки: принято, продано, списано или лежит. */
  quantity: number;
  price: number | null;
  costPrice: number | null;
  supplyNumber: string | null;
  /** День без времени: «2026-09-05». Разбирать его через `new Date` нельзя. */
  date: string | null;
}

export interface OriginTotals {
  /** Число позиций. Штук бывает больше: у позиции из четырёх колёс их четыре. */
  items: number;
  quantity: number;
  amount: number;
}

export interface OriginPage {
  rows: OriginItem[];
  totals: OriginTotals;
  /** С какой позиции продолжать. Пусто — показано всё. */
  nextAfter: number | null;
}

export interface SupplyOption {
  id: number;
  kind: string;
  number: string;
  supplierName: string | null;
  status: string;
  arrivedOn: string | null;
}

export function donorItems(
  donorId: number,
  tab: OriginTab,
  after: number | null,
): Promise<OriginPage> {
  return request<OriginPage>(`/api/reports/donors/${donorId}/items?tab=${tab}${from(after)}`);
}

/** `supplyId` пусто — товар без поставки: отдельный разрез, а не «все подряд». */
export function supplyItems(
  supplyId: number | null,
  tab: OriginTab,
  after: number | null,
): Promise<OriginPage> {
  const which = supplyId === null ? '' : `&supplyId=${supplyId}`;
  return request<OriginPage>(`/api/reports/supplies/items?tab=${tab}${which}${from(after)}`);
}

/** Партии для выбора — все, включая закрытые: про закрытую и спрашивают. */
export function reportSupplies(): Promise<{ rows: SupplyOption[] }> {
  return request<{ rows: SupplyOption[] }>('/api/reports/supplies');
}

function from(after: number | null): string {
  return after === null ? '' : `&after=${after}`;
}

/**
 * Подвал вкладки — теми же словами, что у системы, с которой к нам переходят:
 * «162 товара (162 шт.): розничная стоимость — 1 168 350».
 *
 * <p>У «Продано» сумма другая по смыслу — это цена продажи, а не прайс, —
 * и слово поэтому другое. Числа два, потому что вопроса тоже два: сколько
 * позиций и сколько штук; у позиции из четырёх колёс они не совпадают.
 */
export function originFooter(tab: OriginTab, totals: OriginTotals): string {
  const what = tab === 'sold' ? 'продано' : 'розничная стоимость';
  return `${goods(totals.items)} (${pieces(totals.quantity)}): `
    + `${what} — ${count(Math.round(totals.amount))}`;
}

/**
 * Дата как везде на экранах: «05.09.2026», а не «2026-09-05».
 *
 * <p>Разбирается строкой, а не через `new Date`: у даты без времени тот
 * читает её как полночь UTC, и западнее Гринвича показывает вчерашний день.
 */
export function day(iso: string | null): string {
  if (iso === null) {
    return '—';
  }
  const [year, month, date] = iso.split('-');
  return date === undefined ? iso : `${date}.${month}.${year}`;
}
