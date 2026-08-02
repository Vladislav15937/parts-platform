import { request } from '../api/client';
import type { CatalogRow } from './catalog';

/**
 * Шины и диски.
 *
 * <p>Это товар с типом, а не отдельная сущность: склад, резерв и продажа
 * у них общие с запчастями. Отличается набор свойств — и то, что заводят
 * их комплектом, а продают поштучно: на разборке снимают четыре колеса
 * разом, но покупатель берёт и одну запаску.
 */

export const SEASONS = [
  { code: 'SUMMER', name: 'летняя' },
  { code: 'WINTER', name: 'зимняя' },
  { code: 'ALL_SEASON', name: 'всесезонная' },
] as const;

export interface Wheel {
  id: number;
  publicCode: string | null;
  title: string;
  price: number | null;
  status: string;
  qty: number;
  kind: string;
  /** Пусто — колесо заведено поштучно, а не комплектом. */
  setNo: number | null;
  diameter: number | null;
  tyreWidth: number | null;
  tyreHeight: number | null;
  construction: string | null;
  tyreType: string | null;
  season: string | null;
  wearMm: number | null;
  madeYear: number | null;
  discType: string | null;
  discWidth: number | null;
  offsetMm: number | null;
  boltPattern: string | null;
  hubBore: number | null;
  brand: string | null;
  model: string | null;
  markingType: string | null;
  treadType: string | null;
  runFlat: boolean | null;
  lightTruck: boolean | null;
  speedIndex: string | null;
  loadIndex: number | null;
  partName: string | null;
  condition: string | null;
  supply: string | null;
  donorCode: string | null;
  oem: string | null;
  description: string | null;
  note: string | null;
  section: string | null;
  published: boolean;
  barcode: string | null;
  legacyCode: string | null;
  photoCount: number;
  createdAt: string | null;
  updatedAt: string | null;
  updatedByName: string | null;
  priceChangedAt: string | null;
  priceChangedByName: string | null;
  /** Остаток по складам: ключ — идентификатор склада. */
  stock: Record<string, number>;
}

/** Строка витрины: свойства колеса плюс подписанная ссылка на превью. */
export interface WheelRow {
  wheel: Wheel;
  photoUrl: string | null;
}

export interface WheelWarehouse {
  id: number;
  name: string;
}

export interface WheelPage {
  warehouses: WheelWarehouse[];
  rows: WheelRow[];
}

/**
 * Отбор вкладки колёс.
 *
 * <p>Поиск идёт по номеру товара и заголовку разом: размер попадает в него
 * сам, потому что собран в заголовок («Шина 195/65 R15 …»), а покупатель
 * называет именно размер.
 */
export interface WheelQuery {
  q: string;
  /** Пусто — оба вида: у шины и диска половина колонок разная. */
  kind: '' | 'TYRE' | 'DISC';
  missing: boolean;
  /**
   * Свойства, по которым колесо подбирают. Покупатель звонит и называет
   * размер целиком — «195/65 R15, лето» — или сверловку, если ему нужны
   * диски; остальные сорок свойств он не назовёт, и фильтров по ним нет.
   *
   * <p>Строки, а не числа: поле ввода отдаёт строку, и держать в состоянии
   * число значит терять набранное на каждом промежуточном «1».
   */
  diameter: string;
  tyreWidth: string;
  tyreHeight: string;
  season: string;
  /** Остаток протектора не меньше стольки миллиметров. */
  wearFrom: string;
  boltPattern: string;
  brand: string;
  priceFrom: string;
  priceTo: string;
  sort: string;
  desc: boolean;
}

export const EMPTY_WHEEL_QUERY: WheelQuery = {
  q: '', kind: '', missing: false,
  diameter: '', tyreWidth: '', tyreHeight: '', season: '', wearFrom: '',
  boltPattern: '', brand: '', priceFrom: '', priceTo: '',
  sort: 'set', desc: true,
};

/** Задан ли хоть один отбор: по этому экран решает, показывать ли «Сбросить». */
export function hasWheelFilters(query: WheelQuery): boolean {
  return FILTER_KEYS.some((key) => query[key].trim() !== '')
    || query.kind !== '' || query.missing;
}

const FILTER_KEYS = [
  'q', 'diameter', 'tyreWidth', 'tyreHeight', 'season', 'wearFrom',
  'boltPattern', 'brand', 'priceFrom', 'priceTo',
] as const;

/** Параметры отбора одни и у страницы, и у выгрузки: файл обязан совпасть. */
export function wheelParams(query: WheelQuery): URLSearchParams {
  const params = new URLSearchParams();
  // Пустое поле — «без ограничения», а не «ничего»: незаполненный отбор
  // обязан отдавать весь склад.
  for (const key of FILTER_KEYS) {
    const value = query[key].trim();
    if (value !== '') params.set(key, value);
  }
  if (query.kind !== '') params.set('kind', query.kind);
  if (query.missing) params.set('missing', 'true');
  params.set('sort', query.sort);
  params.set('desc', String(query.desc));
  return params;
}

export function listWheels(query: WheelQuery = EMPTY_WHEEL_QUERY): Promise<WheelPage> {
  const params = wheelParams(query);
  params.set('limit', '500');
  return request<WheelPage>(`/api/wheels?${params.toString()}`);
}

/**
 * Ссылка на выгрузку, а не запрос из скрипта: файл качает браузер, показывая
 * ход, и вкладка при этом жива.
 */
export function wheelExportUrl(query: WheelQuery): string {
  return `/api/wheels/export?${wheelParams(query).toString()}`;
}

export interface SetRequest {
  kind: 'TYRE' | 'DISC';
  warehouseId: number;
  quantity: number;
  diameter: string | null;
  tyreWidth: string | null;
  tyreHeight: string | null;
  season: string | null;
  wearMm: string | null;
  madeYear: string | null;
  discType: string | null;
  discWidth: string | null;
  offsetMm: string | null;
  boltPattern: string | null;
  hubBore: string | null;
  brand: string | null;
  model: string | null;
  tyreType: string | null;
  construction: string | null;
  markingType: string | null;
  treadType: string | null;
  runFlat: boolean | null;
  lightTruck: boolean | null;
  speedIndex: string | null;
  loadIndex: string | null;
  price: string | null;
}

export function createSet(
  request_: SetRequest,
): Promise<{ setNo: number | null; title: string; partIds: number[] }> {
  return request('/api/wheels/sets', { method: 'POST', body: request_ });
}

/**
 * Размер строкой: «195/65 R15» для шины, «6.0x15 5x100 ET45» для диска.
 *
 * <p>В списке это первое, на что смотрят: покупатель называет размер,
 * а не название модели.
 */
export function sizeOf(wheel: Wheel): string {
  if (wheel.kind === 'TYRE') {
    const profile =
      wheel.tyreWidth !== null && wheel.tyreHeight !== null
        ? `${wheel.tyreWidth}/${wheel.tyreHeight}`
        : '';
    const rim = wheel.diameter === null ? '' : `${wheel.construction ?? 'R'}${wheel.diameter}`;
    return [profile, rim].filter((part) => part !== '').join(' ');
  }

  const size =
    wheel.discWidth !== null && wheel.diameter !== null
      ? `${wheel.discWidth}x${wheel.diameter}`
      : '';
  return [size, wheel.boltPattern, wheel.offsetMm === null ? '' : `ET${wheel.offsetMm}`]
    .filter((part) => part !== null && part !== '')
    .join(' ');
}

/**
 * Колонки вкладки «Шины и диски».
 *
 * <p>Список и порядок сняты с кабинета клиента (221 товар, 45 колонок).
 * Порядок не случайный: сначала то, по чему шину подбирают — размер, сезон,
 * износ, — и только потом служебное. Половина колонок относится либо
 * к шине, либо к диску, и у второго товара они честно пусты: одна таблица
 * на оба вида — это решение кабинета, и покупатель ищет колесо целиком,
 * а не отдельно резину и отдельно железо.
 */
export interface WheelColumn {
  key: string;
  title: string;
  /** Имя сортировки на сервере. Нет — по этой колонке не сортируют. */
  sort?: string;
  numeric?: boolean;
  fixed?: boolean;
  value: (w: Wheel) => string;
  image?: (row: WheelRow) => string | null;
}

const MARKING: Record<string, string> = {
  METRIC: 'Метрическая',
  INCH: 'Дюймовая',
  FLOTATION: 'Флотационная',
};

const TREAD: Record<string, string> = {
  STANDARD: 'Стандартный',
  ASYMMETRIC: 'Асимметричный',
  DIRECTIONAL: 'Направленный',
};

const CONDITION: Record<string, string> = {
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

/** Дата без времени: время правки в таблице — шум. */
function day(value: string | null): string {
  return value === null ? '' : new Date(value).toLocaleDateString('ru-RU');
}

/** Пусто, а не «нет»: флажок сообщает что-то только когда он поднят. */
function flag(value: boolean | null): string {
  return value === true ? 'да' : '';
}

export const WHEEL_COLUMNS: WheelColumn[] = [
  { key: 'code', title: 'Номер товара', sort: 'code', value: (w) => text(w.publicCode) },
  // Вторым столбцом, как в кабинете: колесо узнают по картинке — диски
  // различаются только рисунком, и словами его не опишешь.
  { key: 'photo', title: 'Превью', fixed: true, value: () => '',
    image: (row) => row.photoUrl },
  { key: 'set', title: 'Номер комплекта', sort: 'set', numeric: true, value: (w) => text(w.setNo) },
  { key: 'kind', title: 'Товар', sort: 'kind', value: (w) => (w.kind === 'TYRE' ? 'Шина' : 'Диск') },
  { key: 'diameter', title: 'Диаметр', sort: 'diameter', numeric: true, value: (w) => text(w.diameter) },
  { key: 'tyreType', title: 'Тип шины', value: (w) => text(w.tyreType) },
  { key: 'tyreWidth', title: 'Ширина шины', sort: 'tyreWidth', numeric: true, value: (w) => text(w.tyreWidth) },
  { key: 'markingType', title: 'Тип маркировки',
    value: (w) => MARKING[w.markingType ?? ''] ?? text(w.markingType) },
  { key: 'treadType', title: 'Тип протектора',
    value: (w) => TREAD[w.treadType ?? ''] ?? text(w.treadType) },
  { key: 'construction', title: 'Тип конструкции', value: (w) => text(w.construction) },
  { key: 'tyreHeight', title: 'Высота шины', sort: 'tyreHeight', numeric: true, value: (w) => text(w.tyreHeight) },
  // Износ в миллиметрах остатка протектора: покупатель мерил глубиномером,
  // и «осталось 25 %» он пересчитывать не станет.
  { key: 'wear', title: 'Износ', sort: 'wear', numeric: true, value: (w) => text(w.wearMm) },
  { key: 'tyreBrand', title: 'Производитель шины', sort: 'tyreBrand',
    value: (w) => (w.kind === 'TYRE' ? text(w.brand) : '') },
  { key: 'tyreModel', title: 'Модель шины',
    value: (w) => (w.kind === 'TYRE' ? text(w.model) : '') },
  { key: 'season', title: 'Сезон', sort: 'season',
    value: (w) => SEASONS.find((s) => s.code === w.season)?.name ?? '' },
  { key: 'madeYear', title: 'Год производства', sort: 'madeYear', numeric: true, value: (w) => text(w.madeYear) },
  { key: 'discType', title: 'Тип диска', value: (w) => text(w.discType) },
  { key: 'discWidth', title: 'Ширина диска', numeric: true, value: (w) => text(w.discWidth) },
  { key: 'offset', title: 'Вылет', numeric: true, value: (w) => text(w.offsetMm) },
  { key: 'bolt', title: 'Сверловка', value: (w) => text(w.boltPattern) },
  { key: 'hub', title: 'Диаметр ЦО', numeric: true, value: (w) => text(w.hubBore) },
  { key: 'discBrand', title: 'Производитель диска', sort: 'discBrand',
    value: (w) => (w.kind === 'DISC' ? text(w.brand) : '') },
  { key: 'discModel', title: 'Модель диска',
    value: (w) => (w.kind === 'DISC' ? text(w.model) : '') },
  { key: 'oem', title: 'Номер производителя', value: (w) => text(w.oem) },
  { key: 'price', title: 'Цена', sort: 'price', numeric: true, value: (w) => money(w.price) },
  { key: 'description', title: 'Комментарий', value: (w) => text(w.description) },
  { key: 'note', title: 'Заметка', value: (w) => text(w.note) },
  { key: 'section', title: 'Секция', sort: 'section', value: (w) => text(w.section) },
  { key: 'created', title: 'Создан', sort: 'created', value: (w) => day(w.createdAt) },
  { key: 'updated', title: 'Изменён', value: (w) => day(w.updatedAt) },
  { key: 'updatedBy', title: 'Кто изменил', value: (w) => text(w.updatedByName) },
  { key: 'supply', title: 'Поставка', value: (w) => text(w.supply) },
  { key: 'partName', title: 'Наименование', value: (w) => text(w.partName) },
  { key: 'condition', title: 'Состояние',
    value: (w) => CONDITION[w.condition ?? ''] ?? text(w.condition) },
  // RunFlat дороже обычной вдвое, а LT от легковой того же размера
  // отличается нагрузкой: продать одно вместо другого — разные неприятности.
  { key: 'runFlat', title: 'RunFlat', value: (w) => flag(w.runFlat) },
  { key: 'lightTruck', title: 'Легкогрузовая (LT)', value: (w) => flag(w.lightTruck) },
  { key: 'speedIndex', title: 'Индекс скорости', value: (w) => text(w.speedIndex) },
  { key: 'loadIndex', title: 'Индекс нагрузки', numeric: true, value: (w) => text(w.loadIndex) },
  { key: 'donor', title: 'Номер донора', value: (w) => text(w.donorCode) },
  { key: 'published', title: 'Выгружать', value: (w) => (w.published ? 'Везде' : 'Нет') },
  { key: 'photoCount', title: 'Количество фото', numeric: true,
    value: (w) => (w.photoCount === 0 ? '' : String(w.photoCount)) },
  { key: 'legacy', title: 'Старые данные', value: (w) => text(w.legacyCode) },
  { key: 'barcode', title: 'Ст. баркод', value: (w) => text(w.barcode) },
  { key: 'priceChanged', title: 'Цена изменена в', value: (w) => day(w.priceChangedAt) },
  { key: 'priceChangedBy', title: 'Кто изменил цену', value: (w) => text(w.priceChangedByName) },
];

/**
 * Колонки, включённые по умолчанию.
 *
 * <p>Сорок пять колонок сразу — это таблица шириной в четыре экрана,
 * по которой ничего не найти. Показывается то, по чему колесо ищут глазами,
 * остальное владелец включает сам, и выбор запоминается.
 */
export const WHEEL_DEFAULT_VISIBLE = [
  'code', 'photo', 'set', 'kind', 'diameter', 'tyreWidth', 'tyreHeight',
  'season', 'wear', 'tyreBrand', 'tyreModel', 'price', 'section',
];

const WHEEL_STORAGE_KEY = 'wheel-columns';

export function loadWheelVisible(): string[] {
  try {
    const saved = localStorage.getItem(WHEEL_STORAGE_KEY);
    if (saved === null) {
      return WHEEL_DEFAULT_VISIBLE;
    }
    const parsed: unknown = JSON.parse(saved);
    return Array.isArray(parsed) && parsed.every((k) => typeof k === 'string')
      ? (parsed as string[])
      : WHEEL_DEFAULT_VISIBLE;
  } catch {
    // Испорченная запись — не повод показать пустую таблицу.
    return WHEEL_DEFAULT_VISIBLE;
  }
}

export function saveWheelVisible(keys: string[]): void {
  try {
    localStorage.setItem(WHEEL_STORAGE_KEY, JSON.stringify(keys));
  } catch {
    // Приватный режим браузера запрещает запись: настройка не сохранится,
    // но таблица работать не перестанет.
  }
}

/**
 * Колесо в виде строки витрины — чтобы открывалось той же карточкой.
 *
 * <p>Карточка одна на оба вида товара: цена, списание и перемещение написаны
 * на складе, а не на виде товара, и второй экран разошёлся бы с первым
 * на первой же правке. Свойства колеса едут отдельным списком
 * ({@link wheelFields}) — в строке запчасти для них полей нет и быть
 * не должно.
 */
export function rowOfWheel(row: WheelRow): CatalogRow {
  const w = row.wheel;
  return {
    id: w.id, code: w.publicCode, title: w.title,
    qualityGrade: null, condition: w.condition,
    brand: null, model: null, generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: null, donorCode: w.donorCode,
    price: w.price, installationPrice: null, color: null,
    description: w.description, note: w.note,
    // Производитель шины или диска — это и есть производитель товара.
    manufacturer: w.brand, marking: null, section: w.section,
    sideLr: null, sideFr: null, qty: Number(w.qty), oem: w.oem, crosses: null,
    photoUrl: row.photoUrl, supply: w.supply, equipment: null,
    partName: w.partName, published: w.published, barcode: w.barcode,
    legacyCode: w.legacyCode, videoUrl: null, textBlock: null,
    weightKg: null, dimensions: null, packageDimensions: null, packageWeightKg: null,
    createdAt: w.createdAt, updatedAt: w.updatedAt, updatedByName: w.updatedByName,
    priceChangedAt: w.priceChangedAt, priceChangedByName: w.priceChangedByName,
    photoCount: w.photoCount, stock: w.stock,
  };
}

/**
 * Свойства колеса для карточки — только заполненные, как и у запчасти.
 *
 * <p>Шинных полей и дисковых поровну, и показывать пустую половину значит
 * прятать заполненную среди прочерков.
 */
export function wheelFields(w: Wheel): Array<[string, string]> {
  const fields: Array<[string, string]> = [];
  const add = (title: string, value: string) => {
    if (value !== '') fields.push([title, value]);
  };

  add('Товар', w.kind === 'TYRE' ? 'Шина' : 'Диск');
  add('Номер комплекта', text(w.setNo));
  add('Размер', sizeOf(w));
  if (w.kind === 'TYRE') {
    add('Тип шины', text(w.tyreType));
    add('Тип маркировки', MARKING[w.markingType ?? ''] ?? text(w.markingType));
    add('Тип протектора', TREAD[w.treadType ?? ''] ?? text(w.treadType));
    add('Сезон', SEASONS.find((s) => s.code === w.season)?.name ?? '');
    // Миллиметры остатка протектора: покупатель мерил глубиномером.
    add('Износ', w.wearMm === null ? '' : `${w.wearMm} мм`);
    add('Год производства', text(w.madeYear));
    add('Индекс скорости', text(w.speedIndex));
    add('Индекс нагрузки', text(w.loadIndex));
    add('RunFlat', w.runFlat === true ? 'да' : '');
    add('Легкогрузовая (LT)', w.lightTruck === true ? 'да' : '');
  } else {
    add('Тип диска', text(w.discType));
    add('Сверловка', text(w.boltPattern));
    add('Вылет', w.offsetMm === null ? '' : `ET${w.offsetMm}`);
    add('Диаметр ЦО', text(w.hubBore));
  }
  add('Производитель', text(w.brand));
  add('Модель', text(w.model));
  return fields;
}
