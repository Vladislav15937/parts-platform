import { request } from '../api/client';
import type { PrintLegal } from '../sales/dealPrint';

/**
 * Настройки печатных форм — то, что владелец задаёт один раз (задача 0051).
 *
 * <p>Свой файл рядом с `settings/company.ts`, по той же причине, по которой
 * тот не живёт в `sales/sales.ts`: это экран владельца, а не работа продавца.
 *
 * <p>Тип реквизитов организации берётся из `sales/dealPrint` — тот же,
 * которым они приезжают в документ. Второе объявление разошлось бы с первым
 * молча, и расхождение увидел бы не компилятор, а владелец, не нашедший
 * в счёте КПП.
 */

/** Блок реквизитов одного склада: кто продал. */
export interface WarehousePrintDetails {
  id: number;
  name: string;
  /** Пусто — реквизиты не заданы, и документ печатается без продавца. */
  details: string | null;
}

export interface PrintSettings {
  /** Условия возврата и гарантии: печатается в конце чека и накладной. */
  extraText: string | null;
  /** Пусто — строки про НДС в документе нет вовсе. */
  vatNote: string | null;
  clientSignature: boolean;
  issuerSignature: boolean;
  legal: PrintLegal;
  warehouses: WarehousePrintDetails[];
}

export function printSettings(): Promise<PrintSettings> {
  return request<PrintSettings>('/api/company/print-settings');
}

/**
 * Пишет настройки целиком — и блоки складов, и реквизиты организации.
 *
 * <p>Одной формой и одним запросом: наполовину сохранённая настройка оставила
 * бы владельца уверенным, что реквизиты заданы, — а узнал бы он правду
 * по напечатанному чеку без продавца.
 */
export function savePrintSettings(settings: PrintSettings): Promise<PrintSettings> {
  return request<PrintSettings>('/api/company/print-settings', {
    method: 'PUT',
    body: settings,
  });
}

/**
 * Подписи полей реквизитов организации — дословно те, что стоят у ориентира
 * (§7 `docs/bazon-parity.md`, обход 29–30.07.2026), и в том же порядке.
 *
 * <p>Список один на форму и на документ: разойдясь, экран настройки
 * и напечатанный счёт называли бы одно поле по-разному, а сверить их человеку
 * нечем — счёт он смотрит глазами.
 */
export const LEGAL_FIELDS: ReadonlyArray<{
  key: keyof PrintLegal; label: string; placeholder: string;
}> = [
  { key: 'name', label: 'Юридическое название организации', placeholder: 'Введите название организации' },
  {
    key: 'address',
    label: 'Юридический адрес',
    placeholder: '000000, г. Москва, Ленинский проспект 1, офис 10',
  },
  { key: 'inn', label: 'ИНН', placeholder: '' },
  { key: 'kpp', label: 'КПП', placeholder: '' },
  { key: 'bankName', label: 'Название банка', placeholder: '' },
  { key: 'bankBic', label: 'БИК банка', placeholder: '' },
  { key: 'bankAccount', label: 'Расчётный счёт', placeholder: '' },
  { key: 'bankCorrAccount', label: 'Корреспондентский счёт', placeholder: '' },
  { key: 'director', label: 'Руководитель', placeholder: 'Ф.И.О. руководителя' },
  { key: 'chiefAccountant', label: 'Главный бухгалтер', placeholder: 'Ф.И.О. главного бухгалтера' },
];
