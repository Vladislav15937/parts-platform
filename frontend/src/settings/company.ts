import { request } from '../api/client';
import { plural } from '../ui/plural';

/**
 * Настройки компании — то, что владелец ставит один раз, а видят все.
 *
 * <p>Свой файл, а не строчки в `sales/sales.ts`: настройка живёт на экране
 * «Настройки» и не относится к работе продавца, а тот файл читают, когда
 * правят продажу.
 */

export interface CompanySettings {
  /** На сколько дней откладывается товар в новой сделке. */
  reservationDays: number;
}

/** Границы те же, что у сервера и у `CHECK` в схеме: расходиться им нельзя. */
export const MIN_RESERVATION_DAYS = 1;

export const MAX_RESERVATION_DAYS = 365;

export function companySettings(): Promise<CompanySettings> {
  return request<CompanySettings>('/api/company/settings');
}

export function saveCompanySettings(reservationDays: number): Promise<CompanySettings> {
  return request<CompanySettings>('/api/company/settings', {
    method: 'PUT',
    body: { reservationDays },
  });
}

/**
 * «3 дня», «1 день», «11 дней».
 *
 * <p>Единицу пишем словом, а не «дн.»: у ориентира в этом пункте стоит
 * «3 дня», и клиент переходит оттуда. Склонение — общим `plural`, а не своим
 * условием: вторая десятка («11 дней», а не «11 день») ловится только
 * проверкой хвоста, и написанное на месте разошлось бы с остальным экраном.
 */
export function daysLabel(days: number): string {
  return `${days} ${plural(days, 'день', 'дня', 'дней')}`;
}
