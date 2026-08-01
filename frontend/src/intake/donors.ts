import { request } from '../api/client';

/**
 * Машины арендатора целиком — в отличие от справочника приёмки, который
 * отдаёт только те, что в разборе.
 *
 * <p>Заведённая машина обязана быть видна тому, кто её завёл. Пока списка
 * не было, купленная машина исчезала из системы до тех пор, пока кто-то
 * не поставит её в разбор запросом к API: на приёмке её нет, на экране
 * машин её нет, и понять это по интерфейсу нельзя.
 */
export interface DonorEntry {
  id: number;
  publicCode: string;
  brand: string | null;
  model: string | null;
  year: number | null;
  vin: string | null;
  status: DonorStatus;
  note: string | null;
}

export type DonorStatus = 'PURCHASED' | 'DISMANTLING' | 'DISMANTLED' | 'SCRAPPED';

const STATUS_TITLES: Record<string, string> = {
  PURCHASED: 'куплена',
  DISMANTLING: 'в разборе',
  DISMANTLED: 'разобрана',
  SCRAPPED: 'сдана в лом',
};

export function statusTitle(status: string): string {
  return STATUS_TITLES[status] ?? status;
}

/** Как машину называет владелец: код, марка, модель, год. */
export function donorTitle(donor: DonorEntry): string {
  const parts = [donor.publicCode, donor.brand, donor.model, donor.year]
    .filter((value) => value !== null && value !== '');
  return parts.join(' ');
}

export function listDonors(): Promise<DonorEntry[]> {
  return request<DonorEntry[]>('/api/intake/donors');
}

/**
 * Машина попадает в приёмку, только когда поставлена в разбор: деталь,
 * снятая с машины, которую ещё везут, — ошибка выбора, а не работа.
 */
export function startDismantling(id: number): Promise<unknown> {
  return request<unknown>(`/api/intake/donors/${id}/dismantling`, { method: 'POST' });
}
