import { request } from '../api/client';
import { count, plural } from '../ui/plural';

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
  /**
   * Порядковый номер позиции — тот, который называют вслух: «посмотри
   * позицию 347». Не `id`: тот внутренний и человеку не показывается.
   * Не `code`: тот шесть случайных байт, он про этикетку и сканер.
   */
  number: number;
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
  /**
   * Код ячейки хранения — не то же, что `section`. Ту клиент пишет руками
   * своей нумерацией полок, а ячейку заводят складом, печатают на этикетке
   * и сканируют. У позиции на двух складах адрес свой на каждом, и здесь
   * стоит тот, который поставили последним; по складам его показывает
   * карточка (`GET /api/parts/{id}/cells`).
   */
  cellCode: string | null;
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
  /**
   * Насколько подвинулись деньги: «−10 %». Считает сервер по «было»
   * и «стало» — не пересчитывать здесь: два счёта разойдутся на округлении.
   * `null` — поле не денежное либо считать не от чего.
   */
  delta: string | null;
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

/**
 * Оценка состояния: четыре градации с пояснением у каждой.
 *
 * <p>Список один на весь фронтенд — карточка, колонка витрины, форма правки.
 * До задачи 0033 их было три и все разные: форма предлагала
 * `EXCELLENT/GOOD/FAIR/POOR`, которых сервер не знает вовсе (и правка
 * отваливалась целиком ответом «Запрос не разобран»), а карточка и колонка
 * печатали `NO_DEFECTS` как есть.
 *
 * <p>Названия взяты дословно из системы, из которой приходят клиенты: они
 * их узнаю́т, и деталь, переехавшая «Без дефектов», не должна называться
 * «отличное». Сверяет их с сервером `WordingConsistencyTest` — слово,
 * поправленное с одной стороны, иначе разошлось бы молча, и выбранное
 * из списка перестало бы находиться.
 *
 * <p>Пояснения — наши слова, а не их текст: чужую формулировку мы
 * не переносим, а признак выбора назвать надо, иначе приёмщик ставит
 * оценку наугад.
 */
export const QUALITY_GRADES: Array<{ key: string; title: string; hint: string }> = [
  { key: 'AS_NEW', title: 'Как новая',
    hint: 'Полностью исправна, следов работы почти нет.' },
  { key: 'NO_DEFECTS', title: 'Без дефектов',
    hint: 'Износ минимальный, повреждений нет, ресурса осталось много.' },
  { key: 'WITH_DEFECTS', title: 'С дефектами',
    hint: 'Рабочая, но износ заметен: люфты, потёртости, сколы.' },
  { key: 'NEEDS_REPAIR', title: 'Требует ремонт',
    hint: 'Сломана или сильно повреждена: под ремонт или на запчасти.' },
];

/** То же словарём «код → слово»: по нему печатают уже поставленную оценку. */
export const QUALITY: Record<string, string> = Object.fromEntries(
  QUALITY_GRADES.map((grade) => [grade.key, grade.title]),
);

/**
 * Оценка словом. Пусто — это «не оценена», и подставлять сюда состояние
 * нельзя: «б/у» отвечает на другой вопрос (задача 0033).
 */
export function qualityTitle(grade: string | null): string {
  return grade === null || grade === '' ? '' : QUALITY[grade] ?? grade;
}

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
  // Первой колонкой и по ней же порядок по умолчанию: «посмотри позицию 347»
  // — это то, чем на разборке называют деталь в разговоре друг с другом.
  // Публичный код рядом остаётся: он про этикетку и сканер, Code128 кодирует
  // его, а «347» — нет.
  { key: 'number', title: '№ позиции', sort: 'number', numeric: true,
    value: (r) => text(r.number) },
  { key: 'code', title: 'Номер товара', sort: 'code', value: (r) => text(r.code) },
  // Вторым столбцом, как в кабинете: по снимку деталь узнают быстрее,
  // чем по наименованию, — особенно когда наименований на складе тысяча.
  { key: 'photo', title: 'Превью', value: () => '', image: (r) => r.photoUrl, fixed: true },
  { key: 'title', title: 'Запчасть', sort: 'title', value: (r) => r.title },
  // Оценка, а не состояние: «б/у» здесь стояло у всего склада — оценка
  // не совпадала с сервером ни одним значением, и колонка молча показывала
  // соседнее поле (задача 0033).
  { key: 'quality', title: 'Оценка состояния', value: (r) => qualityTitle(r.qualityGrade) },
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
  // Рядом с «Секцией», а не вместо неё: это разные поля. «Секция» — адрес
  // в нумерации клиента, набранный руками, и у переехавшего заполнен именно
  // он; «Ячейка» заводится складом, печатается на этикетке и сканируется.
  // До этого код ячейки не показывался нигде, кроме ленты правок: узнать,
  // где деталь лежит сейчас, можно было только если её когда-то переставляли.
  { key: 'cell', title: 'Ячейка', value: (r) => text(r.cellCode) },
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
  'number', 'code', 'photo', 'title', 'brand', 'model', 'year', 'sideFr', 'sideLr',
  'price', 'section',
];

/**
 * Порядок по умолчанию — номер позиции по возрастанию.
 *
 * <p>До задачи 0060 здесь стояло `sort: 'code', desc: true`, а `code` —
 * это `public_code`, шесть случайных байт. То есть владелец, открывавший
 * главный свой экран, видел **случайную полусотню** из тридцати пяти тысяч
 * позиций, и завтра другую: позиция, стоявшая вчера второй сверху, сегодня
 * не видна вовсе — и это читается как «пропала».
 *
 * <p>Решение владельца продукта от 11 сентября 2026: «сортировать витрину
 * по порядковому номеру 1. 2. 3. и тд».
 */
export const DEFAULT_SORT = 'number';
export const DEFAULT_DESC = false;

/** Витрина при первом открытии: ни отборов, ни набранного поиска. */
export function defaultQuery(size: number): CatalogQuery {
  return {
    q: '',
    vehicle: NO_VEHICLE,
    reserved: true,
    missing: false,
    warehouses: [],
    columns: {},
    words: {},
    sort: DEFAULT_SORT,
    desc: DEFAULT_DESC,
    page: 0,
    size,
  };
}

/**
 * Отбор снят целиком — включая набранный поиск.
 *
 * <p>Решение владельца продукта от 11 сентября 2026, дословно: «сбрасывать:
 * нажимающий „сбросить“ хочет увидеть весь склад». Полумера здесь хуже
 * отсутствия кнопки: человек нажал «сбросить», по-прежнему видит полсотни
 * строк вместо склада и не понимает, что ещё держит выдачу.
 *
 * <p>Сортировку кнопка не трогает — это другое желание и другая кнопка.
 */
export function withoutFilters(query: CatalogQuery): CatalogQuery {
  const clean = defaultQuery(query.size);
  return { ...clean, sort: query.sort, desc: query.desc };
}

/** Порядок вернулся к умолчанию, отборы остались как были. */
export function withDefaultSort(query: CatalogQuery): CatalogQuery {
  return { ...query, sort: DEFAULT_SORT, desc: DEFAULT_DESC, after: undefined, page: 0 };
}

/**
 * Есть ли что сбрасывать.
 *
 * <p>Кнопки показываются ровно тогда, когда им есть что сделать: кнопка,
 * которая ничего не меняет, хуже отсутствующей. А при пустой выдаче отбор
 * заведомо задан — значит кнопка на экране есть, и это главное: таблицы
 * в этот момент нет вовсе, а её шапка — единственное место, где отбор
 * до сих пор снимался.
 */
export function hasFilters(query: CatalogQuery): boolean {
  return query.q.trim() !== ''
    || query.vehicle.brandId !== null
    || query.reserved !== true
    || query.missing !== false
    || query.warehouses.length > 0
    || Object.keys(query.columns ?? {}).length > 0
    || Object.keys(query.words ?? {}).length > 0;
}

export function isSorted(query: CatalogQuery): boolean {
  return query.sort !== DEFAULT_SORT || query.desc !== DEFAULT_DESC;
}

/** Имя экрана в настройках сотрудника. */
const SCREEN = 'catalog';

/** Что витрина помнит за сотрудником: и отбор, и порядок, и колонки. */
export interface CatalogView {
  query: CatalogQuery;
  visible: string[];
}

/**
 * Настройка витрины живёт на сервере, а не в браузере.
 *
 * <p>Решение владельца продукта от 11 сентября 2026: помнить «для каждого
 * отдельного пользователя всегда. Даже если он закрыл браузер, выключил
 * компьютер, открыл в другом браузере или из другого места». `localStorage`
 * не даёт ни одного из трёх последних случаев — а состав колонок до этой
 * задачи хранился именно там.
 *
 * <p>Страницу не помним намеренно: «всё» сказано про то, как разложен экран,
 * а не про то, где человек остановился. Открыть склад на сорок второй
 * странице — это увидеть середину списка без объяснения, почему.
 */
export function loadView(size: number): Promise<CatalogView> {
  return request<{ value: Record<string, unknown> | null }>(`/api/me/settings/${SCREEN}`)
    .then((saved) => viewOf(saved.value, size))
    // Настройка не приехала — экран обязан открыться. Умолчание тут
    // не хуже того, что было до задачи: витрина при первом заходе
    // выглядит именно так.
    .catch(() => ({ query: defaultQuery(size), visible: DEFAULT_VISIBLE }));
}

/**
 * Что именно запоминается.
 *
 * <p>Страницы и курсора здесь нет намеренно (см. {@link loadView}), и это же
 * даёт второе: перелистывание не пишет ничего в базу, потому что запоминаемое
 * от него не меняется.
 */
function memorable(view: CatalogView): Record<string, unknown> {
  const { query } = view;
  return {
    visible: view.visible,
    sort: query.sort,
    desc: query.desc,
    q: query.q,
    reserved: query.reserved,
    missing: query.missing,
    warehouses: query.warehouses,
    columns: query.columns,
    words: query.words,
    vehicle: query.vehicle,
  };
}

/** Снимок запоминаемого строкой: по нему видно, менялось ли оно вообще. */
export function viewKey(view: CatalogView): string {
  return JSON.stringify(memorable(view));
}

export function saveView(view: CatalogView): Promise<unknown> {
  return request<unknown>(`/api/me/settings/${SCREEN}`, {
    method: 'PUT',
    body: memorable(view),
  // Отказ сохранения не должен ронять экран: настройка не применилась,
  // склад работает. Ровно так же прежняя запись в localStorage молчала
  // в приватном окне.
  }).catch(() => null);
}

/**
 * Разбор сохранённого — с недоверием к каждому полю.
 *
 * <p>Запись сделана прежней версией кода и пережила выкат: колонка могла
 * исчезнуть, поле поменять тип. Испорченная настройка не повод показать
 * пустую витрину — непонятое просто берётся умолчанием.
 */
function viewOf(saved: Record<string, unknown> | null, size: number): CatalogView {
  const base = defaultQuery(size);
  if (saved === null || typeof saved !== 'object') {
    return { query: base, visible: DEFAULT_VISIBLE };
  }
  const strings = (value: unknown): string[] =>
    Array.isArray(value) && value.every((item) => typeof item === 'string')
      ? (value as string[]) : [];
  const record = (value: unknown): Record<string, string> => {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return {};
    const pairs = Object.entries(value as Record<string, unknown>)
      .filter(([, item]) => typeof item === 'string') as Array<[string, string]>;
    return Object.fromEntries(pairs);
  };
  const visible = strings(saved.visible);

  return {
    visible: visible.length > 0 ? visible : DEFAULT_VISIBLE,
    query: {
      ...base,
      sort: typeof saved.sort === 'string' ? saved.sort : base.sort,
      desc: typeof saved.desc === 'boolean' ? saved.desc : base.desc,
      q: typeof saved.q === 'string' ? saved.q : base.q,
      reserved: typeof saved.reserved === 'boolean' ? saved.reserved : base.reserved,
      missing: typeof saved.missing === 'boolean' ? saved.missing : base.missing,
      warehouses: Array.isArray(saved.warehouses)
        ? saved.warehouses.filter((id): id is number => typeof id === 'number')
        : base.warehouses,
      columns: record(saved.columns),
      words: record(saved.words),
      vehicle: vehicleOf(saved.vehicle),
    },
  };
}

function vehicleOf(saved: unknown): VehicleFilter {
  if (saved === null || typeof saved !== 'object') {
    return NO_VEHICLE;
  }
  const value = saved as Record<string, unknown>;
  const brandId = typeof value.brandId === 'number' ? value.brandId : null;
  if (brandId === null) {
    return NO_VEHICLE;
  }
  const str = (key: string): string =>
    typeof value[key] === 'string' ? (value[key] as string) : '';
  return {
    brandId,
    brandName: str('brandName'),
    modelId: typeof value.modelId === 'number' ? value.modelId : null,
    modelName: str('modelName'),
    body: str('body'),
    engine: str('engine'),
  };
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

/**
 * Адрес позиции на одном складе.
 *
 * <p>`cellId` пусто — «без адреса»: у клиента без полок ячеек нет вовсе,
 * и это «не заведено», а не «не знаем».
 */
export interface PartCell {
  warehouseId: number;
  cellId: number | null;
  cellCode: string | null;
  qty: number;
}

/**
 * Где позиция лежит — по каждому складу, где она есть.
 *
 * <p>Своим запросом, а не строкой витрины: у позиции на двух складах две
 * полки, а строка про склады знает только остаток. Тем же запросом живёт
 * карточка колеса — она та же самая.
 */
export function loadPartCells(partId: number): Promise<PartCell[]> {
  return request<PartCell[]>(`/api/parts/${partId}/cells`);
}

/**
 * Переставляет позицию на другую полку того же склада.
 *
 * <p>Отдельно от правки карточки: переставляет кладовщик — деталь у него
 * в руках, — а в форме правки лежат себестоимость и минимальная цена,
 * которых ему видеть нельзя.
 *
 * <p>Склад передаётся явно: у позиции на двух складах адрес свой на каждом,
 * и менять их обоим разом значит соврать про ту полку, к которой никто
 * не подходил.
 */
export function changePartCell(
  partId: number, warehouseId: number, cellId: number | null,
): Promise<PartCell> {
  return request<PartCell>(`/api/parts/${partId}/cell`, {
    method: 'PUT',
    body: { warehouseId, cellId },
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
export function savePart(
  partId: number,
  edit: PartEdit,
  priceOp: PriceOperation = 'SET',
): Promise<{ price: number | null }> {
  return request<{ price: number | null }>(`/api/parts/${partId}`, {
    method: 'PUT',
    // При арифметике `price` несёт не новую цену, а значение операции —
    // процент, сумму или шаг. Считает сервер: тот же расчёт нужен правке
    // списком, и две копии разошлись бы на первом округлении.
    body: { ...edit, priceOp } as unknown as Record<string, unknown>,
  });
}

/**
 * Что сделать с ценой.
 *
 * <p>Шесть пунктов и умолчание «Изменить» — как в системе, из которой
 * приходят клиенты: торг на разборке идёт словами «минус десять» и «скинь
 * пятьсот», и считать это в уме — ошибка в разряде ценой в деталь.
 *
 * <p>Слова здесь и слова отказа на сервере ({@code PriceOperation}) —
 * об одном и том же; расходиться им нельзя, и держит это
 * {@code WordingConsistencyTest}.
 */
export type PriceOperation =
  | 'SET'
  | 'INCREASE_PERCENT'
  | 'DECREASE_PERCENT'
  | 'INCREASE_AMOUNT'
  | 'DECREASE_AMOUNT'
  | 'ROUND_TO';

export const PRICE_OPERATIONS: Array<{ key: PriceOperation; title: string; hint: string }> = [
  { key: 'SET', title: 'Изменить', hint: 'новая цена' },
  { key: 'INCREASE_PERCENT', title: 'Увеличить на %', hint: 'процент' },
  { key: 'DECREASE_PERCENT', title: 'Уменьшить на %', hint: 'процент' },
  { key: 'INCREASE_AMOUNT', title: 'Увеличить на сумму', hint: 'сумма' },
  { key: 'DECREASE_AMOUNT', title: 'Уменьшить на сумму', hint: 'сумма' },
  { key: 'ROUND_TO', title: 'Округлить до', hint: 'шаг: 100, 500, 1000' },
];

/** Подсказка в поле значения: что именно вводить при этой операции. */
export function priceOperationHint(op: PriceOperation): string {
  return PRICE_OPERATIONS.find((o) => o.key === op)?.hint ?? '';
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
  operations: Record<string, PriceOperation> = {},
): Promise<BulkResult> {
  return request<BulkResult>('/api/parts/bulk', {
    method: 'POST',
    body: { partIds, changes, operations },
  });
}

/**
 * Итог правки списком.
 *
 * `skipped` — у скольких позиций денежное поле было пустым: считать процент
 * не от чего, и они не тронуты. Молчать об этом нельзя — «изменено 40»
 * читается как «сделано всем».
 *
 * `rejected` — у скольких операция дала бы минус или ноль. Остальные при
 * этом изменены: одна деталь за сто рублей не отменяет переоценку склада.
 * `rejectedCodes` — первые из них поимённо (не все: их могут быть тысячи),
 * `rejectedReason` — чем ответил расчёт на первой.
 */
export interface BulkResult {
  changed: number;
  skipped: number;
  rejected: number;
  rejectedCodes: string[];
  rejectedReason: string | null;
}

/**
 * Что сказать после правки списком — одними словами на складе и на колёсах.
 *
 * <p>Про пропущенные молчать нельзя: «Изменено позиций: 40» владелец читает
 * как «сделано всем», а часть из них осталась с прежней ценой — потому что
 * считать процент было не от чего. Заметить это иначе можно только сверкой
 * склада вручную.
 *
 * <p>Про непрошедшие — тем более: у них операция дала бы минус или ноль.
 * Их называют и числом, и поимённо: число говорит размер беды, а коды —
 * куда идти смотреть. Всех не перечислить, поэтому хвост назван счётом.
 */
export function bulkNotice(result: BulkResult): string {
  const parts = [`Изменено позиций: ${count(result.changed)}`];
  // Не `skipped <= 0`: тип описывает, что обещал сервер, а не что пришло,
  // и на ответе без поля сравнение с undefined даёт ложь — на экран уехало
  // бы «у undefined позиций».
  if (result.skipped > 0) {
    parts.push(`у ${count(result.skipped)} `
      + `${plural(result.skipped, 'позиции', 'позиций', 'позиций')}`
      + ' поле не заполнено — считать проценты не от чего, они не тронуты');
  }
  if (result.rejected > 0) {
    parts.push(`не прошли ${count(result.rejected)} `
      + `${plural(result.rejected, 'позиция', 'позиции', 'позиций')}`
      + `${namedCodes(result.rejected, result.rejectedCodes ?? [])}`
      + ` — ${result.rejectedReason ?? 'операция дала бы минус или ноль'}`);
  }
  return parts.join(' · ');
}

/** «(A1, B2 и ещё 8)» — сколько поместилось, и сколько осталось за списком. */
function namedCodes(total: number, codes: string[]): string {
  if (codes.length === 0) {
    return '';
  }
  const rest = total - codes.length;
  return rest > 0 ? ` (${codes.join(', ')} и ещё ${count(rest)})` : ` (${codes.join(', ')})`;
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
  operations: Record<string, PriceOperation> = {},
): Promise<BulkResult> {
  return request<BulkResult>(`/api/parts/catalog/bulk?${paramsOf(query).toString()}`, {
    method: 'POST',
    body: { changes, operations },
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


