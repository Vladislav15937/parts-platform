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
  /** Номер, которым машину зовёт клиент, а не наш внутренний. */
  code: string;
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

/**
 * То немногое, чем машина подписана в списке. Общее для списка машин
 * и справочника приёмки: две подписи разошлись бы на первой же правке.
 */
export interface DonorLabel {
  code: string;
  note: string | null;
  brand: string | null;
  model: string | null;
  year: number | null;
}

/**
 * Как машину называет владелец.
 *
 * <p>Марки, модели и года мало: у переехавшего клиента 200 машин из 442
 * совпадают по этой тройке, и выбирать между четырьмя «Toyota Camry 2007»
 * нечем. Ошибка тут не косметическая — деталь уедет с чужой применимостью,
 * то есть покупателю, которому не подойдёт, а затраты лягут на чужую машину.
 * Различают их номер клиента («350») и его же заметка («ACV40 2AZFE»,
 * «Синий маркер!!!»).
 *
 * <p>Начало заметки отбрасывается, если оно повторяет марку с моделью:
 * перенос складывает заметку как «Toyota Camry ACV40 2AZFE», и в строке
 * это выглядит заиканием, за которым теряется само отличие.
 */
export function donorTitle(donor: DonorLabel): string {
  const vehicle = [donor.brand, donor.model, donor.year].filter(Boolean).join(' ');
  return [donor.code, vehicle, noteTail(donor.note, [donor.brand, donor.model])]
    .filter((part) => part !== null && part !== '')
    .join(' · ');
}

function noteTail(note: string | null, prefix: (string | null)[]): string {
  let rest = (note ?? '').trim();
  for (const word of prefix) {
    if (word !== null && rest.toLowerCase().startsWith(word.toLowerCase())) {
      rest = rest.slice(word.length).trim();
    }
  }
  return rest;
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
