import { request } from '../api/client';

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
export function money(value: string | null): string {
  return `${Math.round(Number(value ?? 0)).toLocaleString('ru-RU')} ₽`;
}
