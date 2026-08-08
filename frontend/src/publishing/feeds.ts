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
  /** Что уезжает: «PART» — запчасти, «WHEEL» — шины и диски. */
  productLine: 'PART' | 'WHEEL';
  lastError: string | null;
  /**
   * Цена приходит числом, а не строкой: на сервере это {@code numeric},
   * и Jackson отдаёт его числом JSON. Тип, объявленный строкой, компилятор
   * не поправит — он верит объявлению, — а первый же {@code .trim()} упадёт
   * уже в браузере. Так и вышло: кнопки отбора молча отвечали «не удалось»,
   * потому что падали до запроса.
   */
  priceFrom: number | null;
  priceTo: number | null;
  conditions: string[];
  warehouseIds: number[];
  kindIds: number[];
  kindsExcluded: boolean;
  brandIds: number[];
  brandsExcluded: boolean;
}

/** Отправляем строками: сервер разберёт их в numeric сам. */
export interface FeedFilter {
  priceFrom: string | null;
  priceTo: string | null;
  conditions: string[];
  warehouseIds: number[];
  kindIds: number[];
  kindsExcluded: boolean;
  brandIds: number[];
  brandsExcluded: boolean;
}

/**
 * Сколько позиций попадёт в выгрузку с таким отбором.
 *
 * <p>Ради этого числа запрос и существует. Список по видам деталей
 * на неразобранном справочнике даёт пустой прайс: у только что переехавшего
 * клиента вид не заполнен ни у одной позиции, пока наименования
 * не сопоставлены. Площадка пустой прайс примет молча, и объявления пропадут
 * вместе с просмотрами — узнают об этом через сутки.
 */
export function countMatching(
  filter: FeedFilter,
  productLine: 'PART' | 'WHEEL',
): Promise<{ parts: number }> {
  // Линия обязательна: без неё счётчик считал запчасти всегда и у выгрузки
  // колёс обещал 35 835 позиций там, где уезжало 60.
  return request<{ parts: number }>('/api/marketplace-accounts/filter/count', {
    method: 'POST',
    body: { ...filter, productLine },
  });
}

export function listFeeds(): Promise<Feed[]> {
  return request<Feed[]>('/api/marketplace-accounts');
}

/**
 * Заводит кабинет площадки.
 *
 * <p>До этого экран писал «кабинет площадки заводит владелец» и не давал
 * этого сделать: эндпоинт был, звать его было некому, и новый клиент
 * оставался без прайса вовсе — то есть без того, ради чего переезжал.
 *
 * @param packetId номер прайс-листа из кабинета площадки; нужен дельтам
 *                 по API, постоянная ссылка работает и без него
 */
export function createFeed(
  title: string,
  packetId: string,
  productLine: 'PART' | 'WHEEL',
): Promise<Feed> {
  return request<Feed>('/api/marketplace-accounts', {
    method: 'POST',
    body: {
      marketplace: 'DROM',
      title,
      settings: packetId.trim() === '' ? null : JSON.stringify({ packetId: packetId.trim() }),
      productLine,
    },
  });
}

export function setFilter(id: number, filter: FeedFilter): Promise<Feed> {
  return request<Feed>(`/api/marketplace-accounts/${id}/filter`, {
    method: 'PUT',
    body: filter,
  });
}

/**
 * Ссылка на прайс: путь и полный адрес.
 *
 * <p>Полный отдаёт сервер из `app.public-url` — его и передают
 * техспециалисту площадки. По одному пути тот не сходит никуда,
 * а владелец дописывал домен руками.
 */
export interface FeedLink {
  path: string | null;
  url: string | null;
}

export function feedUrl(id: number): Promise<FeedLink> {
  return request<FeedLink>(`/api/marketplace-accounts/${id}/feed-url`);
}

export function rotateFeedUrl(id: number): Promise<FeedLink> {
  return request<FeedLink>(`/api/marketplace-accounts/${id}/feed-url`, {
    method: 'POST',
  });
}

/**
 * Записывает ключ синхронизации кабинета площадки.
 *
 * <p>Только запись: прочитать ключ нельзя ни одним эндпоинтом, и это часть
 * защиты — иначе право смотреть настройки превращается в доступ к кабинету
 * клиента. Хранится он зашифрованным (AES-GCM), ключ шифрования живёт вне
 * базы.
 *
 * <p>Без него дельты по API не уходят вовсе: цена, остаток и снятие
 * с продажи доезжают до площадки только с полным прайсом, а его забирают
 * раз в трое суток.
 */
export function setCredentials(id: number, secret: string): Promise<void> {
  return request<void>(`/api/marketplace-accounts/${id}/credentials`, {
    method: 'PUT',
    body: { secret },
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

  // Направление списка называется словом: «наименований: 3» не отвечает
  // на вопрос, выгружаются они или наоборот исключены, а решения
  // это противоположные.
  if (feed.kindIds.length > 0) {
    parts.push(`${feed.kindsExcluded ? 'кроме' : 'только'} наименований: ${feed.kindIds.length}`);
  }
  if (feed.brandIds.length > 0) {
    parts.push(`${feed.brandsExcluded ? 'кроме' : 'только'} марок: ${feed.brandIds.length}`);
  }

  // Не «фильтров нет»: пустой отбор — это осмысленное состояние, весь склад.
  return parts.length === 0 ? 'весь склад' : parts.join(' · ');
}

function money(value: number): string {
  return value.toLocaleString('ru-RU');
}
