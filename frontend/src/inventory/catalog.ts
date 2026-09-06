import { request } from '../api/client';

/**
 * Витрина склада: таблица товаров для владельца.
 *
 * <p>Отдельно от поиска продавца: тот ищет, что можно продать прямо сейчас,
 * и ему нужны пять полей. Здесь склад целиком — двадцать с лишним колонок,
 * которые владелец включает и выключает под свою задачу.
 */

export interface Warehouse {
  id: number;
  name: string;
}

export interface CatalogRow {
  id: number;
  code: string | null;
  title: string;
  qualityGrade: string | null;
  condition: string | null;
  brand: string | null;
  model: string | null;
  generation: string | null;
  yearFrom: number | null;
  yearTo: number | null;
  body: string | null;
  engine: string | null;
  year: number | null;
  donorCode: string | null;
  price: number | null;
  installationPrice: number | null;
  color: string | null;
  description: string | null;
  note: string | null;
  manufacturer: string | null;
  marking: string | null;
  section: string | null;
  sideLr: string | null;
  sideFr: string | null;
  qty: number;
  oem: string | null;
  crosses: string | null;
  photoUrl: string | null;
  /** Поставка и комплектация показываются в карточке, но не в таблице. */
  supply: string | null;
  equipment: string | null;
  /**
   * Паритет с таблицей товаров прежней системы: у неё сорок две колонки,
   * и владелец переехавшего клиента ищет глазами те же, к которым привык.
   */
  partName: string | null;
  published: boolean | null;
  barcode: string | null;
  legacyCode: string | null;
  videoUrl: string | null;
  textBlock: string | null;
  weightKg: number | null;
  dimensions: string | null;
  packageDimensions: string | null;
  packageWeightKg: number | null;
  createdAt: string | null;
  updatedAt: string | null;
  updatedByName: string | null;
  priceChangedAt: string | null;
  priceChangedByName: string | null;
  photoCount: number;
  /** Остаток по складам: ключ — идентификатор склада, число колонок = число складов. */
  stock: Record<string, number>;
}

export interface CatalogPage {
  total: number;
  warehouses: Warehouse[];
  rows: CatalogRow[];
  /**
   * По каким колонкам сервер делает отбор.
   *
   * <p>Список приходит с сервера, а не повторяется здесь: разойдясь, они дали
   * бы колонку, по которой экран предлагает отбор, а сервер отвечает отказом.
   */
  filterable: string[];
}

/** Машина, к которой подбирают деталь. Пустая марка — подбора нет. */
export interface VehicleFilter {
  brandId: number | null;
  brandName: string;
  modelId: number | null;
  modelName: string;
  body: string;
  engine: string;
}

export const NO_VEHICLE: VehicleFilter = {
  brandId: null, brandName: '', modelId: null, modelName: '', body: '', engine: '',
};

/** Подбор словами — то, что видно на экране рядом с кнопкой. */
export function vehicleLabel(vehicle: VehicleFilter): string {
  return [vehicle.brandName, vehicle.modelName, vehicle.body, vehicle.engine]
    .filter((part) => part !== '')
    .join(' ');
}

/** Разобранная машина: марка, модель, кузов, двигатель и сколько от неё лежит. */
export interface VehicleOption {
  brandId: number;
  brand: string;
  modelId: number | null;
  model: string | null;
  body: string | null;
  engine: string | null;
  parts: number;
}

/** Снимок карточки: подписанная ссылка живёт минуты, поэтому берётся при показе. */
export interface PartPhoto {
  photoId: number;
  main: boolean;
  url: string;
}

/** Строка заявленной применимости: к какой машине деталь подходит. */
export interface Applicability {
  id: number;
  /** Подтверждено человеком, а не разобрано из наименования. */
  verified: boolean;
  brand: string;
  model: string | null;
  generation: string | null;
  yearFrom: number | null;
  yearTo: number | null;
}

/** Заявленная применимость позиции. Пустой список — «не задана». */
export function loadApplicability(partId: number): Promise<Applicability[]> {
  return request<Applicability[]>(`/api/parts/catalog/${partId}/applicability`);
}

/** Добавляет машину в применимость позиции. Отметка подтверждения ставится. */
export function addApplicability(
  partId: number, brandId: number, modelId: number | null,
): Promise<Applicability[]> {
  return request<Applicability[]>(`/api/parts/catalog/${partId}/applicability`, {
    method: 'POST',
    body: { brandId, modelId },
  });
}

export function removeApplicability(partId: number, id: number): Promise<Applicability[]> {
  return request<Applicability[]>(`/api/parts/catalog/${partId}/applicability/${id}`, {
    method: 'DELETE',
  });
}

/** Все снимки позиции — для развёрнутой строки склада. */
export function loadPhotos(partId: number): Promise<PartPhoto[]> {
  return request<PartPhoto[]>(`/api/parts/${partId}/photos`);
}

/**
 * Машина, с которой снята позиция.
 *
 * <p>Значения приходят уже словами: «Правый руль», «АКПП, U340E-05A»,
 * «Серебро (1D9)». Раскладывать коды по словарям на клиенте значит держать
 * второй словарь, который разойдётся с серверным на первой же правке.
 */
export interface PartDonor {
  id: number;
  /** Номер, которым машину зовёт клиент. */
  code: string;
  status: string | null;
  supply: string | null;
  brand: string | null;
  model: string | null;
  generation: string | null;
  bodyCode: string | null;
  engineCode: string | null;
  year: number | null;
  steering: string | null;
  transmission: string | null;
  driveType: string | null;
  color: string | null;
  equipmentCode: string | null;
  mileageKm: number | null;
  vin: string | null;
  location: string | null;
  note: string | null;
  /** Сколько деталей снято с этой машины. */
  partsCount: number;
}

/** Машина позиции; ошибка — донор не задан. */
export function loadDonor(partId: number): Promise<PartDonor> {
  return request<PartDonor>(`/api/parts/${partId}/donor`);
}

/** Одно изменённое поле в правке. `null` — поле было или стало пустым. */
export interface HistoryField {
  label: string;
  before: string | null;
  after: string | null;
}

/**
 * Правка карточки. `author` пустой у всего, что приехало переносом
 * и что правилось до того, как приложение начало подписывать изменения.
 */
export interface HistoryChange {
  at: string;
  author: string | null;
  /** Заполнено у событий без полей: «Товар создан». */
  action: string | null;
  fields: HistoryField[];
}

/** Движение остатка. `document` пустой у перенесённого склада: документа не было. */
export interface HistoryMovement {
  at: string;
  type: string;
  qty: number;
  document: string | null;
  status: string | null;
  warehouse: string | null;
  reason: string | null;
  author: string | null;
}

export interface PartHistory {
  changes: HistoryChange[];
  movements: HistoryMovement[];
}

/**
 * История позиции одним запросом, а не двумя на вкладку.
 *
 * <p>Обе ленты нужны в одном разбирательстве: «остаток не сходится» кончается
 * либо движением, либо правкой, и второй запрос по нажатию вкладки — это
 * ожидание ровно в тот момент, когда человек уже нашёл, куда смотреть.
 */
export function loadHistory(partId: number): Promise<PartHistory> {
  return request<PartHistory>(`/api/parts/${partId}/history`);
}

export function loadVehicleOptions(): Promise<VehicleOption[]> {
  return request<VehicleOption[]>('/api/parts/catalog/vehicles');
}

export interface CatalogQuery {
  /** Номер товара последней строки предыдущей страницы — курсор соседней. */
  after?: string | undefined;
  q: string;
  vehicle: VehicleFilter;
  reserved: boolean;
  missing: boolean;
  warehouses: number[];
  /** Выбранное из списка значений колонки: ищется точным равенством. */
  columns: Record<string, string>;
  /** Вбитое в колонку руками: ищется вхождением. */
  words: Record<string, string>;
  sort: string;
  desc: boolean;
  page: number;
  size: number;
}

/**
 * Адрес скачивания — с теми же отбором и сортировкой, что на экране.
 *
 * <p>Обычной ссылкой, а не запросом с последующим сохранением: браузер сам
 * умеет качать двенадцать мегабайт, показывая ход, а собранный в памяти
 * страницы файл такого размера её и уронит.
 */
export function exportUrl(query: CatalogQuery): string {
  return `/api/parts/catalog/export?${paramsOf(query).toString()}`;
}

/**
 * Общие параметры страницы и выгрузки.
 *
 * <p>Одни на оба: скачанный файл обязан совпасть с тем, что на экране, —
 * ради этой сверки его и качают, и разъехавшиеся наборы параметров
 * разошлись бы молча.
 */
function paramsOf(query: CatalogQuery): URLSearchParams {
  const params = new URLSearchParams({
    reserved: String(query.reserved),
    missing: String(query.missing),
    sort: query.sort,
    desc: String(query.desc),
  });
  if (query.q.trim() !== '') {
    params.set('q', query.q.trim());
  }
  for (const id of query.warehouses) {
    params.append('warehouses', String(id));
  }
  // Пустое по умолчанию: состояние, оставшееся от прежней версии кода,
  // не должно ронять экран целиком — выкат случается, пока вкладка открыта.
  for (const [column, value] of Object.entries(query.columns ?? {})) {
    params.append('filter', `${column}:${value}`);
  }
  for (const [column, value] of Object.entries(query.words ?? {})) {
    if (value.trim() !== '') params.append('find', `${column}:${value.trim()}`);
  }
  if (query.vehicle.brandId !== null) {
    params.set('brandId', String(query.vehicle.brandId));
    if (query.vehicle.modelId !== null) {
      params.set('modelId', String(query.vehicle.modelId));
    }
    if (query.vehicle.body !== '') {
      params.set('body', query.vehicle.body);
    }
    if (query.vehicle.engine !== '') {
      params.set('engine', query.vehicle.engine);
    }
  }
  return params;
}

export function loadCatalog(query: CatalogQuery): Promise<CatalogPage> {
  const params = paramsOf(query);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  // Курсор соседней страницы: сервер берёт её от последней строки предыдущей,
  // а не отступом. На складе в тридцать пять тысяч позиций семисотая страница
  // стоила 748 мс против 82 мс на первой — замерено нагрузочной пробой.
  // Работает только для порядка по умолчанию, поэтому и передаётся не всегда.
  if (query.after) params.set('after', query.after);
  return request<CatalogPage>(`/api/parts/catalog?${params.toString()}`);
}

/**
 * Колонки витрины — в том же составе и порядке, что у прежней системы клиента.
 *
 * <p>`sort` пусто там, где сортировать нечем: по кросс-номерам и фотографии
 * не сортируют, и стрелка на такой колонке обманывала бы.
 */
export interface Column {
  key: string;
  title: string;
  sort?: string;
  /** Числовые прижимаются вправо: так столбец цен читается взглядом сверху вниз. */
  numeric?: boolean;
  value: (row: CatalogRow) => string;

  /**
   * Колонку нельзя отключить. Так в кабинете, и это верно: снимок —
   * не сведение о детали, а способ её узнать. Спрятанный, он превращает
   * настройку таблицы в способ случайно остаться без картинок и не понять,
   * куда они делись.
   */
  fixed?: boolean;

  /**
   * Ссылка на снимок, если колонка показывает картинку, а не текст.
   * Подписанная и короткоживущая — поэтому берётся со страницей,
   * а не хранится.
   */
  image?: (row: CatalogRow) => string | null;
}

const SIDE_LR: Record<string, string> = { LEFT: 'лев.', RIGHT: 'прав.' };
const SIDE_FR: Record<string, string> = { FRONT: 'перед.', REAR: 'задн.' };
export const CONDITION: Record<string, string> = {
  NEW: 'новая',
  USED: 'б/у',
  REFURBISHED: 'восстановленная',
};

function text(value: string | number | null): string {
  return value === null || value === undefined ? '' : String(value);
}

function money(value: number | null): string {
  return value === null ? '' : value.toLocaleString('ru-RU');
}

/** Дата без времени: в таблице на тридцать пять тысяч строк время — шум. */
function day(value: string | null): string {
  return value === null ? '' : new Date(value).toLocaleDateString('ru-RU');
}

export const COLUMNS: Column[] = [
  { key: 'code', title: 'Номер товара', sort: 'code', value: (r) => text(r.code) },
  // Вторым столбцом, как в кабинете: по снимку деталь узнают быстрее,
  // чем по наименованию, — особенно когда наименований на складе тысяча.
  { key: 'photo', title: 'Превью', value: () => '', image: (r) => r.photoUrl, fixed: true },
  { key: 'title', title: 'Запчасть', sort: 'title', value: (r) => r.title },
  { key: 'quality', title: 'Оценка состояния',
    value: (r) => text(r.qualityGrade) || CONDITION[r.condition ?? ''] || '' },
  { key: 'brand', title: 'Марка', sort: 'brand', value: (r) => text(r.brand) },
  { key: 'model', title: 'Модель', sort: 'model', value: (r) => text(r.model) },
  { key: 'generation', title: 'Поколение донора', value: (r) => text(r.generation) },
  {
    key: 'restyling',
    title: 'Рестайлинг донора',
    /*
     * Данных о рестайлинге у нас нет, и колонка говорит об этом прочерком.
     *
     * Раньше она показывала `год_с—год_по`, то есть **тот же диапазон**, что
     * и соседняя «Поколение донора»: имя поколения в поставляемом справочнике
     * и есть диапазон лет («1986—1990» у всех 12 430 записей). Две колонки
     * показывали одно и то же в каждой строке, и владелец читал их как два
     * разных факта.
     *
     * Признак в схеме есть — `catalog.generation.is_restyling`, — но он
     * `false` у всех записей, и не пишет его никто: при сборе каталога
     * рестайлинги схлопнуты по году начала, иначе приёмщик видел бы четыре
     * одинаковых «1982—1983», различимых только идентификатором Дрома.
     * Появятся данные — сюда придёт этот признак, и колонка оживёт сама.
     *
     * Прочерк, а не пустая клетка: пустых в таблице на сорок шесть колонок
     * и без того половина, и на их фоне «не знаем» не читается никак.
     */
    value: () => '—',
  },
  { key: 'body', title: 'Кузов', value: (r) => text(r.body) },
  { key: 'engine', title: 'Двигатель', value: (r) => text(r.engine) },
  { key: 'year', title: 'Год выпуска', sort: 'year', numeric: true, value: (r) => text(r.year) },
  { key: 'sideFr', title: 'Передний / Задний', value: (r) => SIDE_FR[r.sideFr ?? ''] ?? '' },
  { key: 'sideLr', title: 'Левый / Правый', value: (r) => SIDE_LR[r.sideLr ?? ''] ?? '' },
  { key: 'donor', title: 'Номер донора', value: (r) => text(r.donorCode) },
  { key: 'price', title: 'Цена', sort: 'price', numeric: true, value: (r) => money(r.price) },
  { key: 'installation', title: 'Установка', numeric: true,
    value: (r) => money(r.installationPrice) },
  { key: 'color', title: 'Цвет', value: (r) => text(r.color) },
  { key: 'description', title: 'Комментарий', value: (r) => text(r.description) },
  { key: 'manufacturer', title: 'Производитель', sort: 'manufacturer',
    value: (r) => text(r.manufacturer) },
  { key: 'oem', title: 'Номер производителя', value: (r) => text(r.oem) },
  { key: 'crosses', title: 'Кросс-номера', value: (r) => text(r.crosses) },
  { key: 'note', title: 'Заметка', value: (r) => text(r.note) },
  { key: 'marking', title: 'Маркировка', value: (r) => text(r.marking) },
  { key: 'section', title: 'Секция', sort: 'section', value: (r) => text(r.section) },
  // Дальше — то, чего не хватало до паритета с прежней системой. Сверено
  // с живым каталогом клиента: сорок две колонки против наших двадцати
  // четырёх.
  //
  // «Наименование» и «Запчасть» — разные вещи, и в кабинете это две колонки:
  // первое собранный заголовок, второе написание вида детали, по которому
  // разбирают нераспознанные.
  { key: 'partName', title: 'Наименование', value: (r) => text(r.partName) },
  { key: 'condition', title: 'Состояние',
    value: (r) => CONDITION[r.condition ?? ''] ?? '' },
  { key: 'supply', title: 'Поставка', value: (r) => text(r.supply) },
  { key: 'equipment', title: 'Комплектация', value: (r) => text(r.equipment) },
  // «Выгружать» — то, из-за чего у переехавшего клиента прайс уезжал пустым:
  // в чужой выгрузке колонку не включили, и все позиции приехали
  // без разрешения на публикацию.
  { key: 'published', title: 'Выгружать',
    value: (r) => (r.published === null ? '' : r.published ? 'Везде' : 'Нет') },
  { key: 'photoCount', title: 'Количество фото', numeric: true,
    value: (r) => (r.photoCount === 0 ? '' : String(r.photoCount)) },
  { key: 'textBlock', title: 'Текстовый блок', value: (r) => text(r.textBlock) },
  { key: 'video', title: 'Видео', value: (r) => text(r.videoUrl) },
  { key: 'weight', title: 'Вес товара', numeric: true,
    value: (r) => (r.weightKg === null ? '' : `${r.weightKg} кг`) },
  // Габариты одной колонкой: по отдельности «длина 120» не отвечает
  // ни на один вопрос, а вместе отвечают на единственный — влезет ли.
  { key: 'dimensions', title: 'Габариты товара', value: (r) => text(r.dimensions) },
  { key: 'packageDimensions', title: 'Габариты товара в упаковке',
    value: (r) => text(r.packageDimensions) },
  { key: 'packageWeight', title: 'Вес в упаковке', numeric: true,
    value: (r) => (r.packageWeightKg === null ? '' : `${r.packageWeightKg} кг`) },
  { key: 'barcode', title: 'Ст. баркод', value: (r) => text(r.barcode) },
  // «Старые данные» — номер товара в прежней системе. Переехавший клиент
  // помнит деталь по нему, а не по нашему коду. Сырые данные переезда
  // наружу не идут: это внутреннее представление.
  { key: 'legacy', title: 'Старые данные', value: (r) => text(r.legacyCode) },
  { key: 'createdAt', title: 'Создан', value: (r) => day(r.createdAt) },
  { key: 'updatedAt', title: 'Изменён', value: (r) => day(r.updatedAt) },
  { key: 'updatedBy', title: 'Кто изменил', value: (r) => text(r.updatedByName) },
  { key: 'priceChangedAt', title: 'Цена изменена в', value: (r) => day(r.priceChangedAt) },
  { key: 'priceChangedBy', title: 'Кто изменил цену',
    value: (r) => text(r.priceChangedByName) },
];

/**
 * Колонки, видимые по умолчанию.
 *
 * <p>Все двадцать три сразу — это горизонтальная простыня, в которой
 * не найти цену. Показываем то, по чему деталь узнают, остальное владелец
 * включает сам и выбор запоминается.
 */
export const DEFAULT_VISIBLE = [
  'code', 'photo', 'title', 'brand', 'model', 'year', 'sideFr', 'sideLr', 'price', 'section',
];

const STORAGE_KEY = 'catalog-columns';

/**
 * Выбор колонок переживает перезагрузку.
 *
 * <p>Настройка таблицы — работа на несколько минут, и терять её при каждом
 * заходе значит не дать ею пользоваться вовсе.
 */
export function loadVisible(): string[] {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === null) {
      return DEFAULT_VISIBLE;
    }
    const parsed: unknown = JSON.parse(saved);
    return Array.isArray(parsed) && parsed.every((k) => typeof k === 'string')
      ? (parsed as string[])
      : DEFAULT_VISIBLE;
  } catch {
    // Испорченная запись — не повод показать пустую таблицу.
    return DEFAULT_VISIBLE;
  }
}

export function saveVisible(keys: string[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(keys));
  } catch {
    // Приватный режим браузера запрещает запись. Настройка не сохранится,
    // но таблица работать не перестанет.
  }
}

/**
 * Списание: деталь ушла со склада, но не покупателю.
 *
 * <p>Причина обязательна — это единственная операция, уносящая товар без
 * покупателя и без денег, и «почему» через месяц не восстановить ни по
 * журналу, ни по документу.
 */
export function writeOffPart(
  warehouseId: number, partId: number, quantity: number, reason: string,
): Promise<unknown> {
  return request<unknown>('/api/stock/write-offs', {
    method: 'POST',
    body: { warehouseId, reason, items: [{ partId, quantity }] },
  });
}

/**
 * Перемещение между складами.
 *
 * <p>Создаётся и проводится одним запросом: черновик тут не нужен, в отличие
 * от приёмки — обходить со списком нечего, деталь уже посчитана, её просто
 * переносят. Промежуточное состояние означало бы деталь, которой нет ни
 * на одном складе.
 *
 * <p>Ячейка на складе-приёмнике необязательна, но без неё деталь ложится
 * на склад без адреса, и найти её можно только глазами.
 */
export function movePart(
  fromWarehouseId: number,
  toWarehouseId: number,
  partId: number,
  quantity: number,
  toCellId: number | null,
  note: string | null,
): Promise<{ number: number }> {
  return request<{ number: number }>('/api/stock/moves', {
    method: 'POST',
    body: {
      fromWarehouseId,
      toWarehouseId,
      note,
      items: [{ partId, quantity, toCellId }],
    },
  });
}

/** Позиция, которая не поехала пачкой: часть остатка обещана покупателю. */
export interface MoveSkipped {
  partId: number;
  publicCode: string;
}

export interface MoveBulkResult {
  number: number;
  /** Сколько строк реально вошло в документ. */
  items: number;
  notMoved: MoveSkipped[];
}

/**
 * Перевозка пачкой: весь остаток каждой отмеченной позиции со склада-
 * источника на склад-приёмник, одним документом.
 *
 * <p>Количество не спрашивается — пачкой везут всё, что лежит на складе-
 * источнике; частичную перевозку («две из пяти») по-прежнему делают
 * из карточки, через {@link movePart}.
 *
 * <p>Позиция, отложенная под клиента, в документ не попадает: сервер решает
 * это сам и возвращает список пропущенного в {@code notMoved} — тот же 409,
 * что и раньше, только не на весь документ, а по конкретным строкам.
 */
export function movePartsBulk(
  fromWarehouseId: number,
  toWarehouseId: number,
  items: Array<{ partId: number; quantity: number; toCellId: number | null }>,
  note: string | null,
): Promise<MoveBulkResult> {
  return request<MoveBulkResult>('/api/stock/moves', {
    method: 'POST',
    body: { fromWarehouseId, toWarehouseId, note, items },
  });
}

/**
 * Поля карточки, которые правит человек.
 *
 * <p>Заголовка, стороны и состояния тут нет: заголовок собирается из них
 * справочником, и правка руками разошлась бы с ним при первом же
 * пересопоставлении наименований.
 */
export interface PartEdit {
  price: number | null;
  minPrice: number | null;
  costPrice: number | null;
  installationPrice: number | null;
  qualityGrade: string | null;
  description: string | null;
  note: string | null;
  textBlock: string | null;
  videoUrl: string | null;
  marking: string | null;
  manufacturer: string | null;
  color: string | null;
  section: string | null;
  barcode: string | null;
  weightKg: number | null;
  lengthMm: number | null;
  widthMm: number | null;
  heightMm: number | null;
  packageLengthMm: number | null;
  packageWidthMm: number | null;
  packageHeightMm: number | null;
  packageWeightKg: number | null;
  storageCellId: number | null;
  published: boolean;
}

/**
 * Карточка для правки: все поля формы, включая те, которых нет на витрине.
 *
 * <p>Отдельным запросом, а не из уже загруженной строки: себестоимости
 * и минимальной цены на витрине нет — её читают все вошедшие, включая
 * продавца. Собери форму из строки — и сохранение стёрло бы закупочную цену,
 * которая снимком уходит в сделку и в отчёт окупаемости.
 */
export function loadEditable(partId: number): Promise<PartEdit> {
  return request<PartEdit>(`/api/parts/${partId}/editable`);
}

/**
 * Сохранение карточки.
 *
 * <p>Форма целиком, а не изменённые поля: пустое поле означает «очищено».
 * Иначе стереть заметку с экрана невозможно — пустое неотличимо
 * от непереданного.
 */
export function savePart(partId: number, edit: PartEdit): Promise<{ price: number | null }> {
  return request<{ price: number | null }>(`/api/parts/${partId}`, {
    method: 'PUT',
    body: edit as unknown as Record<string, unknown>,
  });
}

/**
 * Правка нескольких позиций разом.
 *
 * <p>Меняется только то, что владелец тронул: у выбранных позиций заметки
 * разные, и «пустое значит очистить» стёрло бы их все одним нажатием.
 * Поэтому карта «поле → значение», а не форма целиком, как у одной карточки.
 *
 * <p>Зачем: после переезда надо проставить секцию сотне позиций или снять
 * «Выгружать» у битых — по одной это день работы, и потому её не делают.
 */
export function savePartsBulk(
  partIds: number[],
  changes: Record<string, string | number | boolean | null>,
): Promise<{ changed: number }> {
  return request<{ changed: number }>('/api/parts/bulk', {
    method: 'POST',
    body: { partIds, changes },
  });
}

/**
 * Правка всего, что попало в отбор, — а не отмеченного на странице.
 *
 * <p>Отметить можно только видимое, а видно пятьдесят строк. После переезда
 * без колонки «Выгружать» включить публикацию надо всему складу: у живого
 * клиента это 35 841 позиция, то есть семьсот семнадцать страниц, причём
 * выделение сбрасывается на каждой. Прайс до тех пор уезжает пустым, и
 * площадка молча не заводит ни одного объявления.
 *
 * <p>Отбор уезжает теми же параметрами, что у страницы и у выгрузки
 * (`paramsOf`): владелец правит ровно то, что видел, — иначе разойтись
 * они могут молча.
 */
export function savePartsBulkByFilter(
  query: CatalogQuery,
  changes: Record<string, string | number | boolean | null>,
): Promise<{ changed: number }> {
  return request<{ changed: number }>(`/api/parts/catalog/bulk?${paramsOf(query).toString()}`, {
    method: 'POST',
    body: { changes },
  });
}

/** Поля, которые правятся списком. Заголовок, остаток и ячейка сюда не идут. */
export const BULK_FIELDS: Array<{ key: string; title: string; kind: 'money' | 'text' | 'flag' }> = [
  { key: 'price', title: 'Цена', kind: 'money' },
  { key: 'minPrice', title: 'Минимальная цена', kind: 'money' },
  { key: 'costPrice', title: 'Себестоимость', kind: 'money' },
  { key: 'installationPrice', title: 'Цена установки', kind: 'money' },
  { key: 'section', title: 'Секция', kind: 'text' },
  { key: 'manufacturer', title: 'Производитель', kind: 'text' },
  { key: 'marking', title: 'Маркировка', kind: 'text' },
  { key: 'color', title: 'Цвет', kind: 'text' },
  { key: 'description', title: 'Комментарий', kind: 'text' },
  { key: 'note', title: 'Заметка', kind: 'text' },
  { key: 'textBlock', title: 'Текстовый блок', kind: 'text' },
  { key: 'published', title: 'Выгружать', kind: 'flag' },
];


/** Незаполненное поле и «заполнено хоть чем-то» — тоже ответы на вопрос. */
export const FILTER_EMPTY = '\u2014пусто\u2014';
export const FILTER_PRESENT = '\u2014не пусто\u2014';

/** Значения колонки — по нажатию на стрелку, а не вместе со страницей. */
export function columnValues(column: string): Promise<string[]> {
  return request<string[]>(`/api/parts/catalog/values?column=${encodeURIComponent(column)}`);
}


