import { request } from '../api/client';

/**
 * Печатные формы сделки (задача 0051).
 *
 * <p>Покупатель б/у запчасти уносит бумагу почти всегда: по ней он через две
 * недели приходит по гарантии. До этой задачи печати из сделки не было вовсе —
 * ни чека, ни накладной, ни счёта, — и единственное, что в системе печаталось,
 * это этикетки.
 *
 * <p>Свой файл, а не строки в `sales/sales.ts`: тот читают, когда правят
 * продажу, и он уже на тысячу триста строк.
 */

/** Чьи реквизиты стоят в шапке чека и накладной: того склада, с которого выдача. */
export interface PrintSeller {
  warehouseId: number | null;
  warehouseName: string | null;
  /** Многострочный блок «Название компании, адрес и контакты». */
  details: string | null;
  /**
   * Почему реквизитов нет — словами сервера, а не нашими: тот же текст читают
   * обе формы, и второе написание разошлось бы с первым. Пусто — всё на месте.
   */
  problem: string | null;
}

export interface PrintBuyer {
  name: string;
  phone: string | null;
  inn: string | null;
  companyName: string | null;
  /** Примечание клиенту (`publicNote`) — то самое, что обещано «при печати». */
  note: string | null;
}

/** Реквизиты организации: идут в счёт на юр. лицо, а не в чек. */
export interface PrintLegal {
  name: string | null;
  address: string | null;
  inn: string | null;
  kpp: string | null;
  bankName: string | null;
  bankBic: string | null;
  bankAccount: string | null;
  bankCorrAccount: string | null;
  director: string | null;
  chiefAccountant: string | null;
}

export interface PrintLine {
  title: string;
  /**
   * Числа строкой: `numeric` из Postgres Jackson отдаёт как есть, и объявленная
   * не тем типом цена уже роняла экран выгрузок на `.trim()`. Здесь они приходят
   * строками — такими же, как `price` и `quantity` в {@link DealItem}.
   */
  quantity: string;
  price: string;
  amount: string;
}

export interface DealPrintDoc {
  dealId: number;
  number: number | null;
  createdAt: string;
  issuedAt: string | null;
  seller: PrintSeller;
  buyer: PrintBuyer;
  legal: PrintLegal;
  /** Условия возврата и гарантии. Пусто — блока в документе нет. */
  extraText: string | null;
  /** Пусто — строки про НДС в документе нет вовсе, а не «НДС 0». */
  vatNote: string | null;
  clientSignature: boolean;
  issuerSignature: boolean;
  lines: PrintLine[];
  total: string;
  paid: string;
  debt: string;
}

export function dealPrintDoc(dealId: number): Promise<DealPrintDoc> {
  return request<DealPrintDoc>(`/api/deals/${dealId}/print`);
}

/**
 * Четыре формы меню «Печатать» — ровно те слова и ровно в том порядке,
 * что названы в критерии приёмки задачи 0051 и стоят у ориентира, из которого
 * приходят клиенты.
 *
 * <p>Список здесь, а не в разметке: по нему же идёт отрисовка самих
 * документов, и второй перечень разошёлся бы с первым на первой правке —
 * ровно как расходились словари состояний сделки.
 */
export const PRINT_FORMS = [
  { key: 'receipt', label: 'Товарный чек' },
  { key: 'waybill', label: 'Товарная накладная' },
  { key: 'both', label: 'Товарный чек и накладная' },
  { key: 'invoice', label: 'Счёт на юр. лицо' },
] as const satisfies readonly { key: string; label: string }[];

export type PrintForm = (typeof PRINT_FORMS)[number]['key'];

/**
 * Из каких документов состоит выбранная форма.
 *
 * <p>«Товарный чек и накладная» — **две страницы**, а не один лист с двумя
 * блоками: разведка этого у ориентира не видела, и выбор назван в PR. Довод
 * простой — чек и накладную подписывают по отдельности и хранят по отдельности,
 * а один лист пришлось бы резать ножницами.
 */
export function documentsOf(form: PrintForm): ('receipt' | 'waybill' | 'invoice')[] {
  if (form === 'both') {
    return ['receipt', 'waybill'];
  }
  return [form];
}

/** Ни одного заполненного поля: счёт печатать не из чего. */
export function legalIsEmpty(legal: PrintLegal): boolean {
  return Object.values(legal).every((value) => value === null || value.trim() === '');
}
