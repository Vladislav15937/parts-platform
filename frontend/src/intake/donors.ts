import { request } from '../api/client';

/**
 * Машины арендатора целиком — в отличие от справочника приёмки, который
 * отдаёт только те, с которых можно снимать: в разборе и разобранные.
 * Купленной и той, что в пути, там нет.
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
  /** Где машина стоит: ряд, площадка, бокс. Свободный текст владельца. */
  location: string | null;
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

/**
 * Заводит поставку — партию, к которой привязывают машины и детали.
 *
 * <p>Эндпоинт был написан с самого начала, и звать его было некому: список
 * поставок приезжал справочником, а новую завести было нельзя ниоткуда.
 * Следующий пришедший контейнер записать было не на что — приёмщик выбрал бы
 * «не указана», и связь детали с партией потерялась бы навсегда.
 */
export function registerSupply(
  number: string,
  kind: string,
  supplierName: string | null,
): Promise<{ id: number; number: string }> {
  return request<{ id: number; number: string }>('/api/intake/supplies', {
    method: 'POST',
    body: { number, kind, supplierName },
  });
}

/**
 * Переставляет машину: «где она стоит».
 *
 * <p><b>Зачем.</b> `POST /api/intake/donors/{id}/location` написан с самого
 * начала, поле приезжало в карточку — а звать этот путь было некому, и
 * показывать значение тоже негде. На площадке с полусотней машин «где она
 * стоит» это единственный способ её найти, и держалось оно в голове того,
 * кто её ставил. Найдено перебором эндпоинтов против того, что зовёт
 * фронтенд (`tools/endpoint-coverage.py`).
 *
 * <p>Пустая строка законна: машину увезли, и «неизвестно где» честнее
 * прежнего ряда, которого там уже нет.
 */
export function moveDonor(id: number, location: string): Promise<unknown> {
  return request<unknown>(
    `/api/intake/donors/${id}/location?location=${encodeURIComponent(location)}`,
    { method: 'POST' },
  );
}

/**
 * Отмечает, что партия приехала.
 *
 * <p>Дата приходит явно, а не подставляется сервером. Он умеет и без неё —
 * тогда встанет сегодняшняя, — но контейнер отмечают и задним числом,
 * а подставленное молча значение читается как факт: приёмщик его не видит
 * и не оспаривает. На экране оно стоит в поле перед нажатием.
 */
export function markSupplyArrived(id: number, on: string): Promise<unknown> {
  return request<unknown>(`/api/intake/supplies/${id}/arrived?on=${on}`, { method: 'POST' });
}

/**
 * Машины, пришедшие этой партией: «что было в контейнере».
 *
 * <p>Отдаёт тот же список, что и экран машин, — с номером клиента, маркой
 * и заметкой. Прежде этот путь возвращал внутренний код, которого владелец
 * никогда не видел: столбец шестнадцатеричных знаков вместо «350 · Toyota
 * Camry 2007». Ровно на этом краснел отчёт окупаемости.
 */
export function donorsOfSupply(id: number): Promise<DonorEntry[]> {
  return request<DonorEntry[]>(`/api/intake/supplies/${id}/donors`);
}
