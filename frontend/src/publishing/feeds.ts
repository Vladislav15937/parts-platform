import { request } from '../api/client';

/**
 * Выгрузки на площадки.
 *
 * <p>Их бывает несколько на одну площадку, и различаются они отбором товара:
 * у живого клиента пять прайсов на Дром по ценовым диапазонам, у каждого свой
 * прайс-лист в кабинете площадки и своя цена размещения.
 *
 * <p><b>Пустое поле отбора — «без ограничения», а не «ничего».</b> Выгрузка,
 * у которой стёрли цену, обязана вернуться к отдаче всего склада: пустой прайс
 * площадка примет молча, и объявления пропадут вместе с накопленными
 * просмотрами, за которые и платят.
 */

/** Состояние детали. Значения те же, что в карточке. */
export const CONDITIONS = [
  { code: 'NEW', name: 'новые' },
  { code: 'USED', name: 'б/у' },
  { code: 'REFURBISHED', name: 'восстановленные' },
] as const;

export interface Feed {
  id: number;
  marketplace: string;
  title: string;
  status: string;
  hasCredentials: boolean;
  plaintextSecret: boolean;
  hasFeed: boolean;
  lastError: string | null;
  priceFrom: string | null;
  priceTo: string | null;
  conditions: string[];
  warehouseIds: number[];
}

export interface FeedFilter {
  priceFrom: string | null;
  priceTo: string | null;
  conditions: string[];
  warehouseIds: number[];
}

export function listFeeds(): Promise<Feed[]> {
  return request<Feed[]>('/api/marketplace-accounts');
}

export function setFilter(id: number, filter: FeedFilter): Promise<Feed> {
  return request<Feed>(`/api/marketplace-accounts/${id}/filter`, {
    method: 'PUT',
    body: filter,
  });
}

export function feedUrl(id: number): Promise<{ path: string | null }> {
  return request<{ path: string | null }>(`/api/marketplace-accounts/${id}/feed-url`);
}

export function rotateFeedUrl(id: number): Promise<{ path: string }> {
  return request<{ path: string }>(`/api/marketplace-accounts/${id}/feed-url`, {
    method: 'POST',
  });
}

/**
 * Отбор словами — то, что видно в списке без раскрытия карточки.
 *
 * <p>Владельцу с пятью выгрузками важно с первого взгляда понять, какая
 * из них какой кусок склада забирает: «до 1 000 ₽» и «от 50 000 ₽» различаются
 * мгновенно, а «есть фильтр» не значит ничего.
 */
export function filterSummary(feed: Feed): string {
  const parts: string[] = [];

  const from = feed.priceFrom === null ? null : Number(feed.priceFrom);
  const to = feed.priceTo === null ? null : Number(feed.priceTo);
  if (from !== null && to !== null) {
    parts.push(`${money(from)}—${money(to)} ₽`);
  } else if (from !== null) {
    parts.push(`от ${money(from)} ₽`);
  } else if (to !== null) {
    parts.push(`до ${money(to)} ₽`);
  }

  if (feed.conditions.length > 0) {
    parts.push(
      feed.conditions
        .map((code) => CONDITIONS.find((c) => c.code === code)?.name ?? code)
        .join(', '),
    );
  }

  if (feed.warehouseIds.length > 0) {
    parts.push(`складов: ${feed.warehouseIds.length}`);
  }

  // Не «фильтров нет»: пустой отбор — это осмысленное состояние, весь склад.
  return parts.length === 0 ? 'весь склад' : parts.join(' · ');
}

function money(value: number): string {
  return value.toLocaleString('ru-RU');
}
