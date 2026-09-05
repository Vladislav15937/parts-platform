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

/**
 * Настройки сборки прайса — не отбора.
 *
 * <p>Отбор говорит, какой товар уедет в этот прайс-лист; настройки — каким
 * он уедет. Площадка берёт комиссию, и продавцы закладывают её в цену
 * объявления: у живого клиента на прайсе Авито стоит −20 %. Цена на складе,
 * на витрине и у продавца при этом не меняется — иначе комиссия площадки
 * поднимет цену тому же товару в зале и по телефону.
 *
 * <p><b>Следующая настройка добавляется сюда полем</b> и полем на экране;
 * сервер разбирает и пишет их по составу этой же записи, и путь до сборки
 * прайса уже проложен.
 *
 * <p>Числами, а не строками: на сервере это `numeric`, и Jackson отдаёт его
 * числом JSON. Тип, объявленный строкой, компилятор не поправит — он верит
 * объявлению, — а первый же `.trim()` упадёт уже в браузере. Ровно так
 * молча ломались кнопки отбора.
 */
export interface FeedSettings {
  /** Плюс — наценка, минус — скидка; пусто или ноль — цена уезжает как есть. */
  pricePercent: number | null;
  /** Шаг округления результата; пусто или ноль — не округлять. */
  priceRounding: number | null;
  /**
   * Сколько снимков уходит в объявление.
   *
   * <p>`null` — не задано, и уезжают прежние десять: настройка появилась
   * позже прайсов, и молча изменить их она не должна. Ноль — без
   * ограничения, уедут все снимки позиции.
   */
  photoLimit: number | null;
}

/** «Округлять до» — те же шаги, что у системы, с которой переходят клиенты. */
export const ROUNDING_STEPS = ['0.01', '0.1', '10', '100'] as const;

export interface Feed {
  id: number;
  marketplace: string;
  title: string;
  status: string;
  hasCredentials: boolean;
  plaintextSecret: boolean;
  hasFeed: boolean;
  /**
   * Читаемое имя файла в конце ссылки — «drom-parts.xml».
   *
   * <p>`null` — имени не задавали, и ссылка кончается токеном. Это рабочее
   * состояние, а не незаполненное поле: имя нужно тому, кто переносит адрес
   * в кабинет площадки руками.
   */
  feedFileName: string | null;
  /** Что уезжает: «PART» — запчасти, «WHEEL» — шины и диски. */
  productLine: 'PART' | 'WHEEL';
  /** Как собирается файл: наценка на прайс-лист и округление. */
  settings: FeedSettings;
  lastError: string | null;
  /**
   * Когда площадка последний раз забрала прайс по постоянной ссылке.
   * `null` — не забирала ни разу.
   *
   * <p>Это не то же самое, что отправленная дельта: «мы отправили»
   * и «у нас забрали» — разные события, и на вопрос «прайс вообще уехал?»
   * отвечает только второе.
   */
  lastDownloadAt: string | null;
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
  /**
   * Свои условия владельца: «колонка → значение», точное равенство.
   *
   * <p>Шесть зашитых условий (цена, состояние, склады, наименования, марки)
   * покрывают не всё, а каждое седьмое означало бы релиз. Витрина склада
   * к этому времени отбирает по двадцати девяти колонкам, и выгрузка берёт
   * тот же механизм: список колонок закрыт сервером, неизвестное имя
   * отвергается.
   */
  filterColumns: Record<string, string>;
  /** То же вхождением: «Nok» находит Nokian. */
  filterWords: Record<string, string>;
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
  columns: Record<string, string>;
  words: Record<string, string>;
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

/**
 * По каким колонкам сервер умеет отбирать — списком с самого сервера.
 *
 * <p>Повторённый на клиенте, список разошёлся бы с ним на первой же новой
 * колонке, и экран предлагал бы условие, которого нет: сервер отвечает
 * «по этой колонке отбор не делается», а владелец видит прежний прайс
 * и решает, что отбор сломан. Ровно этим болело меню колонки на витрине.
 *
 * <p>Страницей в одну строку, а не своим эндпоинтом: список едет вместе
 * с выдачей склада, и второй источник того же знания — это то, что здесь
 * и избегается.
 */
export function filterableColumns(line: 'PART' | 'WHEEL'): Promise<string[]> {
  const path = line === 'WHEEL'
    ? '/api/wheels?page=0&size=1'
    : '/api/parts/catalog?page=0&size=1';
  // Ответ без ожидаемого ключа — это чужой ответ, а не пустой список:
  // разбирать его дальше значит уронить экран целиком на `undefined.length`.
  return request<{ filterable?: string[] }>(path).then((page) => page.filterable ?? []);
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

/**
 * Сохраняет настройки сборки прайса.
 *
 * <p>Отдельным запросом от отбора, потому что это разные решения: отбор
 * меняет состав прайса, наценка — цену в нём. Сервер кладёт настройки
 * слиянием, поэтому номер прайс-листа в кабинете площадки остаётся на месте.
 *
 * <p>Пустое поле уезжает как `null` — «не задано». Ноль тут значил бы то же
 * самое, но отличить «стёрли» от «поставили ноль» на сервере было бы нечем.
 */
export function setSettings(id: number, settings: FeedSettingsInput): Promise<Feed> {
  return request<Feed>(`/api/marketplace-accounts/${id}/settings`, {
    method: 'PUT',
    body: settings,
  });
}

/**
 * То же, но как его набирают: строками.
 *
 * <p>Отправляем строками, как и границы цены в отборе: сервер разберёт их
 * в `numeric` сам, а превращать «-20» в число на клиенте значит завести
 * второе место, где решается, что считать пустым.
 */
export interface FeedSettingsInput {
  pricePercent: string | null;
  priceRounding: string | null;
  photoLimit: string | null;
}

/** Запятая в поле — та же десятичная точка: на телефоне её и набирают. */
export function decimalOrNull(typed: string): string | null {
  const clean = typed.trim().replace(',', '.');
  return clean === '' ? null : clean;
}

/**
 * Целое из поля — или «не задано».
 *
 * <p>Отдельно от `decimalOrNull`, потому что запятую тут исправлять нечего:
 * снимков бывает три, а не три с половиной, и «3,5» должно уехать на сервер
 * как есть и получить отказ, а не превратиться в число, которого владелец
 * не набирал.
 */
export function wholeOrNull(typed: string): string | null {
  const clean = typed.trim();
  return clean === '' ? null : clean;
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

/**
 * Задаёт имя файла прайса — читаемый хвост ссылки.
 *
 * <p>Адрес прописывает в кабинете площадки её техспециалист руками, и хвост
 * из сорока случайных символов он переносит с ошибками — а ошибку видно
 * только по тому, что объявления не появились. Секрет при этом остаётся
 * на месте: смены ссылки тут не происходит, прежний адрес продолжает
 * работать.
 *
 * <p>Пустое поле снимает имя. Уезжает оно пустой строкой, а сервер пишет
 * `NULL`: две выгрузки без имени не должны сталкиваться в уникальном индексе.
 */
export function setFeedFileName(id: number, fileName: string): Promise<Feed> {
  return request<Feed>(`/api/marketplace-accounts/${id}/feed-file`, {
    method: 'PUT',
    body: { fileName },
  });
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

  // Свои условия названы числом, а не перечислены: колонок бывает
  // несколько, и подпись под заголовком должна оставаться в одну строку.
  const own = Object.keys(feed.filterColumns ?? {}).length
      + Object.keys(feed.filterWords ?? {}).length;
  if (own > 0) {
    parts.push(`своих условий: ${own}`);
  }

  // Не «фильтров нет»: пустой отбор — это осмысленное состояние, весь склад.
  return parts.length === 0 ? 'весь склад' : parts.join(' · ');
}

function money(value: number): string {
  return value.toLocaleString('ru-RU');
}

/**
 * Когда площадка последний раз забирала прайс.
 *
 * <p>Первый вопрос при подключении клиента и первый, когда объявления
 * пропали: «прайс вообще уехал?». До этой отметки на него отвечал
 * разработчик по логам приложения — то есть клиент ждал человека, чтобы
 * узнать факт, который система знает.
 *
 * <p><b>«Не забирали» — это ответ, а не пустое место.</b> У новой выгрузки
 * так и есть: ссылку только что выдали и техспециалист площадки ещё
 * не прописал её в кабинете. Пустая клетка на этом месте читалась бы как
 * «экран не знает», а «01.01.1970» — как поломка.
 */
export function downloadMark(feed: Feed): string {
  return feed.lastDownloadAt === null
    ? 'Прайс не забирали'
    : `Скачан ${moment(feed.lastDownloadAt)}`;
}

/**
 * Месяц словом и своей таблицей, а не через `Intl`.
 *
 * <p>Короткое имя месяца в `ru-RU` зависит от сборки ICU — «сент.» против
 * «сен», — и экран показывал бы то, чего у него не просили, по-разному
 * в браузере и в тестах. Число месяца при этом не годится вовсе: «04.09»
 * рядом со временем читается как ещё одно время.
 */
const MONTHS = ['янв', 'фев', 'мар', 'апр', 'мая', 'июн',
  'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];

/** Время местное — забор идёт ночью, и час здесь смысловой. */
function moment(iso: string): string {
  const at = new Date(iso);
  const day = String(at.getDate()).padStart(2, '0');
  const hours = String(at.getHours()).padStart(2, '0');
  const minutes = String(at.getMinutes()).padStart(2, '0');
  return `${day} ${MONTHS[at.getMonth()]}, ${hours}:${minutes}`;
}
