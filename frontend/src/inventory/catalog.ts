import { request } from '../api/client';
import { count, goods, plural } from '../ui/plural';
import type { NotSavedField } from './notSaved';
import type { BazonSelection } from '../catalog/bazonVehicles';

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
  /** Номер объявления Дрома, только цифры: клиент сверяет по нему кабинет. */
  dromAdNumber: string | null;
  /**
   * Оригинальность словом площадки, дословно («Оригинал»): приезжает файлом
   * переноса с Дрома (задача 0277). Необязательное в типе, потому что строку
   * собирают и экраны, у которых её нет (колесо), и фикстуры тестов; сервер
   * присылает поле всегда — читать через `?? null`.
   */
  authenticity?: string | null;
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
  /**
   * Откуда машина в колонках «Марка», «Модель», «Поколение» (задача 0290):
   * `DONOR` — донор позиции; `VERIFIED` и `PARSED` — своя машина позиции без
   * донора из её применимости, подтверждённая человеком или разобранная
   * автоматически; пусто — машины нет. Необязательное в типе: строку собирают
   * и экраны без машины (колесо), и фикстуры; сервер присылает поле всегда.
   */
  vehicleSource?: VehicleSource | null;
  /** Поколений у своей машины в применимости; больше одного — поколение не показано. */
  vehicleSpans?: number | null;
  /**
   * Номер кузова из наименования («120») и номера кузовов единственного
   * поколения блока («150, 151»), когда поколение не показано потому, что
   * номер ими не подтверждён (задачи 0294, 0299); иначе пусто. Номер есть,
   * а кузовов нет — блок кузова не назвал, сверить было нечем.
   */
  vehicleTitleBody?: string | null;
  vehicleBlockBodies?: string | null;
  /**
   * Коды кузова, по которым поколение своей машины определено нами, а не
   * названо клиентом в «Подходит» («GRJ120, KDJ120», задача 0295); иначе пусто.
   */
  vehicleGenerationBodies?: string | null;
  /**
   * Поколение в колонке — поколение Дрома целиком («4 поколение»), собранное
   * из нескольких строк справочника: рестайлинги, которые наш справочник
   * держит отдельно (задача 0297). Годы клетки — от первого до последнего.
   */
  vehicleGenerationMerged?: boolean | null;
  /**
   * Откуда «Кузов» и «Двигатель» (задача 0296) — у каждой колонки своё: у позиции
   * без донора это коды и двигатели её своей машины из блока «Подходит».
   */
  vehicleBodySource?: VehicleSource | null;
  vehicleEngineSource?: VehicleSource | null;
  /** Номер кузова из наименования («120»), до ряда которого сужены кузова своей машины. */
  vehicleBodySeries?: string | null;
  /**
   * Двигателя у своей машины нет, а блок «Подходит» их называет: машин в блоке
   * несколько, и строка «Для двигателей» — для всего блока.
   */
  vehicleEnginesForBlock?: boolean | null;
  /** Остаток по складам: ключ — идентификатор склада, число колонок = число складов. */
  stock: Record<string, number>;
  /**
   * Отложенное под клиента по складам (задача 0367): часть `stock`, которую
   * продать нельзя. Свободно — `stock - reserved`. Необязательное в типе:
   * строку собирают и фикстуры; сервер присылает поле всегда.
   */
  reserved?: Record<string, number>;
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

export type VehicleSource = 'MANUAL' | 'DONOR' | 'VERIFIED' | 'PARSED';

/**
 * Подсказка клетки машины: откуда значение (задача 0290).
 *
 * <p>Колонки «Марка», «Модель», «Поколение» у позиции без донора показывают
 * её машину из применимости, и разобранное автоматически не должно читаться
 * как подтверждённый факт. Пусто — подсказки нет: машины нет вовсе.
 */
export function vehicleHint(row: CatalogRow): string | undefined {
  const source = row.vehicleSource ?? null;
  if (source === null) return undefined;
  if (source === 'DONOR') return 'По донору';
  // Вписано в карточке позиции руками (задача 0302): побеждает и донора,
  // и применимость.
  if (source === 'MANUAL') return 'Вписано у позиции руками';
  const base = source === 'VERIFIED'
    ? 'По применимости, подтверждена человеком'
    : 'По применимости, разобрана автоматически';
  // Поколение не подтверждено номером кузова из наименования (0294, 0299):
  // кузова другого ряда либо их нет вовсе. Подсказка не утверждает, что
  // поколения нет, — только что сверить его не удалось.
  if (row.vehicleTitleBody) {
    return `${base}. Поколение не определено: в наименовании ${row.vehicleTitleBody}, `
      + (row.vehicleBlockBodies
        ? `а «Подходит» называет кузова ${row.vehicleBlockBodies}`
        : 'а «Подходит» не называет кузова');
  }
  // Рестайлинги одного поколения Дрома показаны поколением целиком (0297):
  // годы шире, чем у любой одной строки, и человек должен знать почему.
  // Поколение по коду кузова (0295) — наш вывод, а не слова клиента, и у
  // рестайлингов тоже: «по блоку «Подходит»» тут было бы неправдой.
  const byBody = generationByBody(row);
  if (row.vehicleGenerationMerged) {
    return `${base}. Поколение определено по ${byBody ?? 'блоку «Подходит»'}: одно поколение Дрома, `
      + 'годы — от первого рестайлинга до последнего';
  }
  const spans = row.vehicleSpans ?? 0;
  if (spans > 1) {
    return `${base}. Поколений у этой машины ${spans} — какое у детали, не указано; все — в карточке`;
  }
  return byBody ? `${base}. Поколение определено по ${byBody}` : base;
}

/**
 * Подсказка клетки «Кузов» (задача 0296): откуда коды и весь их список — клетка
 * обрезается по ширине, а кодов у своей машины бывает и тридцать.
 */
export function bodyHint(row: CatalogRow): string | undefined {
  const base = codeSourceWords(row.vehicleBodySource ?? null);
  if (base === undefined || !row.body) return undefined;
  const series = row.vehicleBodySource !== 'DONOR' && row.vehicleBodySource !== 'MANUAL'
    && row.vehicleBodySeries
    ? `. Кузова ряда ${row.vehicleBodySeries} — по номеру в наименовании`
    : '';
  return `${base}${series}: ${row.body}`;
}

/**
 * Подсказка клетки «Двигатель» (задача 0296). Пустая клетка у позиции, чей блок
 * двигатели называет, — не «не заполнено»: строка «Для двигателей» одна на все
 * машины блока, и чьи они, не сказано.
 */
export function engineHint(row: CatalogRow): string | undefined {
  if (!row.engine) {
    return row.vehicleEnginesForBlock
      ? 'Двигатели названы для всего блока, а не для этой машины: в блоке «Подходит» машин несколько'
      : undefined;
  }
  const base = codeSourceWords(row.vehicleEngineSource ?? null);
  return base === undefined ? undefined : `${base}: ${row.engine}`;
}

function codeSourceWords(source: VehicleSource | null): string | undefined {
  if (source === null) return undefined;
  if (source === 'DONOR') return 'По донору';
  if (source === 'MANUAL') return 'Вписано у позиции руками';
  return source === 'VERIFIED'
    ? 'По применимости, из блока «Подходит», подтверждена человеком'
    : 'По применимости, из блока «Подходит», разобрана автоматически';
}

/**
 * «коду кузова GRJ120» — когда поколение в колонке выведено нами по кузову,
 * а не названо клиентом (задача 0295): владелец должен отличать заявление
 * клиента от нашего вывода. Только при показанном поколении — без него
 * говорить не о чем.
 */
function generationByBody(row: CatalogRow): string | undefined {
  const codes = (row.vehicleGenerationBodies ?? '').split(', ').filter((c) => c !== '');
  if (!row.generation || codes.length === 0) return undefined;
  const head = codes.slice(0, 3).join(', ');
  const rest = codes.length > 3 ? ` и ещё ${codes.length - 3}` : '';
  return codes.length === 1 ? `коду кузова ${head}` : `кодам кузова ${head}${rest}`;
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
  /**
   * Позиций у марки целиком. Не сумма строк: деталь, подходящая к Prado,
   * 4Runner и Hilux Surf, — одна позиция Toyota, а не три (задача 0290).
   */
  brandParts: number;
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
  /** Кто установил; null — неизвестно (строка лежала до задачи 0363, перенос, разбор). */
  setBy?: string | null;
  /** Когда установлена (ISO); null — неизвестно. */
  setAt?: string | null;
}

/** «Двигатель важен» позиции и пояснение, почему применимость не поставилась сама. */
export interface FitState {
  engineMatters: boolean;
  note: string | null;
}

export function loadFitState(partId: number): Promise<FitState> {
  return request<FitState>(`/api/parts/catalog/${partId}/applicability/state`);
}

export function setEngineMatters(partId: number, matters: boolean): Promise<FitState> {
  return request<FitState>(`/api/parts/catalog/${partId}/engine-matters`, {
    method: 'PUT',
    body: { matters },
  });
}

/** Строка списка «Применимость подробно»: машина применимости позиции либо пустая. */
export interface FitListRow {
  partId: number;
  number: number;
  title: string;
  engineMatters: boolean;
  brand: string | null;
  model: string | null;
  generation: string | null;
  yearFrom: number | null;
  yearTo: number | null;
  setBy: string | null;
  setAt: string | null;
}

export interface FitListPage {
  rows: FitListRow[];
  total: number;
  size: number;
}

export function loadFitList(page: number): Promise<FitListPage> {
  return request<FitListPage>(`/api/parts/catalog/applicability-list?page=${page}`);
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

/**
 * Перевязывает позицию на другой номер донора (задача 0365): у позиции
 * с машиной-донором поля машины под замком, и меняет их только это.
 * Марку, модель, название и применимость сервер ставит с новой машины.
 */
export function changeDonor(partId: number, donorId: number): Promise<unknown> {
  return request<unknown>(`/api/parts/${partId}/donor`, {
    method: 'PUT',
    body: { donorId },
  });
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
  /**
   * Правка была частью массовой (задача 0373): сколько позиций она изменила
   * и чем они выбраны — отбором витрины или отметкой строк. `null` — правка
   * одной карточки либо сделанная до того, как массовая начала себя помечать.
   * Необязательное — фикстуры и ответ сборки старше этой задачи.
   */
  bulk?: HistoryBulk | null;
}

/** Объём массовой правки. Слова ставит `bulkCaption`, а не сервер. */
export interface HistoryBulk {
  size: number;
  scope: 'FILTER' | 'SELECTED';
}

/**
 * Подпись массовой правки под её полями: «всего были отредактированы
 * 40 товаров по фильтру» — слова Bazon, откуда приходят клиенты. У правки
 * отмеченных строк подписи у Bazon не видели; «по отмеченным» — слово
 * исполнителя из PR 491, подтверждённое владельцем 23.10.2026 (задача 0373,
 * пункт 11).
 */
export function bulkCaption(bulk: HistoryBulk): string {
  const how = bulk.scope === 'FILTER' ? 'по фильтру' : 'по отмеченным';
  return `всего были отредактированы ${goods(bulk.size)} ${how}`;
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
  /**
   * Строка не двигала остаток, а держит его или ждёт (резерв, черновик
   * сделки, задача 0367): количество без знака. Необязательное — фикстуры.
   */
  held?: boolean;
  /** Цена строки сделки; у складских документов пусто, а не ноль. */
  price?: number | null;
  /** Документ строки удалён — «Выполнен (Удалён)» красным (задача 0367). */
  deleted?: boolean;
  /**
   * Документ, который вошедший может удалить; пусто — нельзя. Признак ставит
   * сервер по своему праву, своего списка ролей у экрана нет.
   */
  deletableDocumentId?: number | null;
  /**
   * Право, которого не хватает, чтобы удалить документ строки (задача 0044):
   * «Удалить» погашена с причиной, а не спрятана. Пусто — удалить можно
   * либо нечего.
   */
  deleteNeeds?: string | null;
}

export interface PartHistory {
  changes: HistoryChange[];
  movements: HistoryMovement[];
  /** Номер поступления, которым позиция пришла, — колонка «Поступление». */
  intake?: string | null;
  /** Поставка словами — колонка «Поставка». */
  supply?: string | null;
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
 * <p>`sort` — имя сортировки на сервере, и стоит оно у каждой колонки
 * (задача 0367, решение владельца продукта 23.10.2026: «сортировка
 * по колонкам должна быть на каждую колонку»). Пусто только у «Рестайлинга»:
 * в ней прочерк у всех строк, сортировать нечего, и стрелка обещала бы
 * действие, которого нет. Незнакомое имя сервер молча заменяет умолчанием —
 * поэтому каждое имя отсюда сверяет с белым списком сервера
 * `CatalogSortTest.everyScreenSortIsKnownToTheServer`.
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

  /** Подсказка клетки по наведению: откуда значение. */
  hint?: (row: CatalogRow) => string | undefined;
}

/**
 * Стороны установки словом — словарь один на весь фронтенд: клетка витрины,
 * карточка и форма правки.
 *
 * <p>Слова — сервера (`shared/ValueWords`), с заглавной: решение владельца
 * продукта 24.10.2026 (`tasks/0044`, пункт 4) — «Перед.», «Задн.», «Лев.»,
 * «Прав.» в карточке, в названии позиции и в файле склада одинаково. До этого
 * клетка писала «перед.», а файл того же склада — «Перед.», и копий было три
 * (здесь, `partCard.ts`, `PartEditForm.tsx`). Сверяет с сервером
 * `ValueWordsTest`: он же требует, чтобы своей копии больше нигде
 * не было.
 */
export const SIDE_LR: Record<string, string> = { LEFT: 'Лев.', RIGHT: 'Прав.', BOTH: 'Лев., Прав.' };
export const SIDE_FR: Record<string, string> = { FRONT: 'Перед.', REAR: 'Задн.', BOTH: 'Перед., Задн.' };
/**
 * Состояние детали — словарь один на весь фронтенд.
 *
 * <p>Копий было две (здесь и во `wheels.ts`), и сверялись они с сервером
 * только тем, что их писал один человек в один день. Сверяет их теперь
 * `WordingConsistencyTest` перебором по перечислению `PartCondition`:
 * новое состояние попадает в проверку в тот же момент, когда его дописали
 * в `enum`. Слово, поправленное с одной стороны, иначе разошлось бы
 * молча — и выбранное из списка перестало бы находиться (задача 0039).
 */
export const CONDITION: Record<string, string> = {
  NEW: 'новая',
  USED: 'б/у',
  REFURBISHED: 'восстановленная',
  CONTRACT: 'контрактная',
};

/**
 * Пункт выбора «состояние не выбрано» (задача 0322): владелец заводит
 * ожидаемую позицию, не зная, какой она приедет. Не пятое состояние,
 * а его отсутствие — поэтому не в `CONDITION`: слово не печатается рядом
 * с наименованием, и в отборе склада такая позиция — «—пусто—».
 * Одно на формы заведения ожидаемого и правки карточки.
 */
export const CONDITION_UNSET = 'Не указано';

/**
 * То же списком — для выбора в форме правки карточки.
 *
 * <p>Порядок тот же, что в перечислении на сервере: «новая · б/у ·
 * восстановленная · контрактная».
 */
export const CONDITIONS: Array<{ key: string; title: string }> = Object.entries(CONDITION)
  .map(([key, title]) => ({ key, title }));

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
  { key: 'photo', title: 'Превью', sort: 'photo', value: () => '', image: (r) => r.photoUrl, fixed: true },
  { key: 'title', title: 'Запчасть', sort: 'title', value: (r) => r.title },
  // Сразу за «Запчастью» (задача 0314): состояние до неё читали из пометки
  // в хвосте наименования, и стоять оно обязано там же, где его искали, —
  // в той же строке и рядом, без прокрутки вбок. Раньше колонка стояла
  // в хвосте таблицы, за «Наименованием».
  { key: 'condition', title: 'Состояние', sort: 'condition', value: (r) => conditionTitle(r.condition) ?? '' },
  // Оценка, а не состояние: «б/у» здесь стояло у всего склада — оценка
  // не совпадала с сервером ни одним значением, и колонка молча показывала
  // соседнее поле (задача 0033).
  { key: 'quality', title: 'Оценка состояния', sort: 'quality', value: (r) => qualityTitle(r.qualityGrade) },
  // У позиции без донора — её своя машина из применимости (задача 0290),
  // откуда значение — в подсказке клетки.
  { key: 'brand', title: 'Марка', sort: 'brand', value: (r) => text(r.brand), hint: vehicleHint },
  { key: 'model', title: 'Модель', sort: 'model', value: (r) => text(r.model), hint: vehicleHint },
  // «Поколение» и «Рестайлинг», а не «… донора», как у прежней системы:
  // у позиции без донора там своя машина из применимости, и «донора»
  // читалось бы как «машина, с которой снято». Решение владельца продукта
  // 14.10.2026 («да, переименовать»; tasks/0290, раздел «Вопрос владельцу —
  // как подписать колонки, которые про донора»).
  { key: 'generation', title: 'Поколение', sort: 'generation', value: (r) => text(r.generation), hint: vehicleHint },
  {
    key: 'restyling',
    title: 'Рестайлинг',
    /*
     * Данных о рестайлинге у нас нет, и колонка говорит об этом прочерком.
     *
     * Раньше она показывала `год_с—год_по`, то есть **тот же диапазон**, что
     * и соседняя «Поколение»: имя поколения в поставляемом справочнике
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
  // У позиции без донора — коды и двигатели её своей машины из «Подходит»
  // (задача 0296), весь список: ответ владельца 14.10.2026 «весь». Клетка
  // обрезается по ширине, целиком список — в подсказке.
  { key: 'body', title: 'Кузов', sort: 'body', value: (r) => text(r.body), hint: bodyHint },
  { key: 'engine', title: 'Двигатель', sort: 'engine', value: (r) => text(r.engine), hint: engineHint },
  { key: 'year', title: 'Год выпуска', sort: 'year', numeric: true, value: (r) => text(r.year) },
  { key: 'sideFr', title: 'Передний / Задний', sort: 'sideFr', value: (r) => SIDE_FR[r.sideFr ?? ''] ?? '' },
  { key: 'sideLr', title: 'Левый / Правый', sort: 'sideLr', value: (r) => SIDE_LR[r.sideLr ?? ''] ?? '' },
  { key: 'donor', title: 'Номер донора', sort: 'donor', value: (r) => text(r.donorCode) },
  { key: 'price', title: 'Цена', sort: 'price', numeric: true, value: (r) => money(r.price) },
  { key: 'installation', title: 'Установка', sort: 'installation', numeric: true,
    value: (r) => money(r.installationPrice) },
  { key: 'color', title: 'Цвет', sort: 'color', value: (r) => text(r.color) },
  { key: 'description', title: 'Комментарий', sort: 'description', value: (r) => text(r.description) },
  { key: 'manufacturer', title: 'Производитель', sort: 'manufacturer',
    value: (r) => text(r.manufacturer) },
  { key: 'oem', title: 'Номер производителя', sort: 'oem', value: (r) => text(r.oem) },
  { key: 'crosses', title: 'Кросс-номера', sort: 'crosses', value: (r) => text(r.crosses) },
  { key: 'note', title: 'Заметка', sort: 'note', value: (r) => text(r.note) },
  { key: 'marking', title: 'Маркировка', sort: 'marking', value: (r) => text(r.marking) },
  { key: 'section', title: 'Секция', sort: 'section', value: (r) => text(r.section) },
  // Рядом с «Секцией», а не вместо неё: это разные поля. «Секция» — адрес
  // в нумерации клиента, набранный руками, и у переехавшего заполнен именно
  // он; «Ячейка» заводится складом, печатается на этикетке и сканируется.
  // До этого код ячейки не показывался нигде, кроме ленты правок: узнать,
  // где деталь лежит сейчас, можно было только если её когда-то переставляли.
  { key: 'cell', title: 'Ячейка', sort: 'cell', value: (r) => text(r.cellCode) },
  // Дальше — то, чего не хватало до паритета с прежней системой. Сверено
  // с живым каталогом клиента: сорок две колонки против наших двадцати
  // четырёх.
  //
  // «Наименование» и «Запчасть» — разные вещи, и в кабинете это две колонки:
  // первое собранный заголовок, второе написание вида детали, по которому
  // разбирают нераспознанные.
  { key: 'partName', title: 'Наименование', sort: 'partName', value: (r) => text(r.partName) },
  { key: 'supply', title: 'Поставка', sort: 'supply', value: (r) => text(r.supply) },
  { key: 'equipment', title: 'Комплектация', sort: 'equipment', value: (r) => text(r.equipment) },
  // «Выгружать» — то, из-за чего у переехавшего клиента прайс уезжал пустым:
  // в чужой выгрузке колонку не включили, и все позиции приехали
  // без разрешения на публикацию.
  { key: 'published', title: 'Выгружать', sort: 'published',
    value: (r) => (r.published === null ? '' : r.published ? 'Везде' : 'Нет') },
  { key: 'photoCount', title: 'Количество фото', sort: 'photoCount', numeric: true,
    value: (r) => (r.photoCount === 0 ? '' : String(r.photoCount)) },
  { key: 'textBlock', title: 'Текстовый блок', sort: 'textBlock', value: (r) => text(r.textBlock) },
  { key: 'video', title: 'Видео', sort: 'video', value: (r) => text(r.videoUrl) },
  { key: 'weight', title: 'Вес товара', sort: 'weight', numeric: true,
    value: (r) => (r.weightKg === null ? '' : `${r.weightKg} кг`) },
  // Габариты одной колонкой: по отдельности «длина 120» не отвечает
  // ни на один вопрос, а вместе отвечают на единственный — влезет ли.
  { key: 'dimensions', title: 'Габариты товара', sort: 'dimensions', value: (r) => text(r.dimensions) },
  { key: 'packageDimensions', title: 'Габариты товара в упаковке', sort: 'packageDimensions',
    value: (r) => text(r.packageDimensions) },
  { key: 'packageWeight', title: 'Вес в упаковке', sort: 'packageWeight', numeric: true,
    value: (r) => (r.packageWeightKg === null ? '' : `${r.packageWeightKg} кг`) },
  { key: 'barcode', title: 'Ст. баркод', sort: 'barcode', value: (r) => text(r.barcode) },
  // «Старые данные» — номер товара в прежней системе. Переехавший клиент
  // помнит деталь по нему, а не по нашему коду. Сырые данные переезда
  // наружу не идут: это внутреннее представление.
  { key: 'legacy', title: 'Старые данные', sort: 'legacy', value: (r) => text(r.legacyCode) },
  { key: 'createdAt', title: 'Создан', sort: 'created', value: (r) => day(r.createdAt) },
  { key: 'updatedAt', title: 'Изменён', sort: 'updated', value: (r) => day(r.updatedAt) },
  { key: 'updatedBy', title: 'Кто изменил', sort: 'updatedBy', value: (r) => text(r.updatedByName) },
  { key: 'priceChangedAt', title: 'Цена изменена в', sort: 'priceChanged', value: (r) => day(r.priceChangedAt) },
  { key: 'priceChangedBy', title: 'Кто изменил цену', sort: 'priceChangedBy',
    value: (r) => text(r.priceChangedByName) },
];

/**
 * Имя сортировки по колонке склада (задача 0367).
 *
 * <p>Колонки складов не фиксированы — у одного клиента два склада, у другого
 * пять, — поэтому имя собирается из номера склада. Сервер сортирует такую
 * колонку по свободному остатку (`qty - qty_reserved`), то есть по крупному
 * числу клетки «0 (1)», а не по полному.
 */
export function warehouseSort(warehouseId: number): string {
  return `warehouse:${warehouseId}`;
}

/**
 * Имя сортировки по ключу колонки — обычной или колонки склада; у колонки
 * без сортировки — `undefined`.
 */
export function sortOfColumn(key: string): string | undefined {
  if (key.startsWith('warehouse:')) return key;
  return COLUMNS.find((c) => c.key === key)?.sort;
}

/**
 * Колонки, видимые по умолчанию.
 *
 * <p>Все двадцать три сразу — это горизонтальная простыня, в которой
 * не найти цену. Показываем то, по чему деталь узнают, остальное владелец
 * включает сам и выбор запоминается.
 *
 * <p>«Состояние» сразу за «Запчастью» (задача 0314): новая и б/у — разный
 * товар и разная цена, и до этой задачи продавец читал состояние только
 * из пометки в хвосте наименования. Пометка из наименования уходит (0315,
 * 0316), а после смены состояния она и сегодня врёт (0304).
 */
export const DEFAULT_VISIBLE = [
  'number', 'code', 'photo', 'title', 'condition', 'brand', 'model', 'year', 'sideFr',
  'sideLr', 'price', 'section',
];

/**
 * Состояние словом рядом с наименованием в клетке «Запчасть» — или ничего.
 *
 * <p>Нужно тем, у кого раскладка столбцов сохранена до задачи 0314: колонки
 * «Состояние» в ней нет, а память витрины молча её не добавит (и не должна —
 * это настройка человека). Именно эти люди сегодня читают состояние из пометки
 * в наименовании, и когда пометка уйдёт (0315), они бы его потеряли.
 * Колонка включена — слово не повторяется: два раза одно в строке — шум.
 *
 * <p>Отдельным элементом, а не дописанной строкой: сортировка и отбор
 * по «Запчасти» идут на сервере по наименованию и от этого не меняются,
 * а при копировании названия из клетки слово не прихватывается.
 */
export function conditionAside(row: CatalogRow, visible: readonly string[]): string | null {
  return visible.includes('condition') ? null : conditionTitle(row.condition);
}

/**
 * Слово состояния по коду («USED» → «б/у»); незнакомый или пустой код — ничего.
 *
 * <p>Одно на склад, продавца и этикетку (задача 0314): незнакомый код
 * не печатается как есть — «REFURBISHED» на этикетке это внутреннее
 * представление, а не слово.
 */
export function conditionTitle(code: string | null | undefined): string | null {
  return code === null || code === undefined ? null : CONDITION[code] ?? null;
}

/**
 * Наименование со словом состояния одной строкой: «Фара Toyota Camry 2007 · б/у».
 *
 * <p>Для подписей, которые собираются текстом, а не разметкой (задача 0318):
 * запись очереди отправки на телефоне — «Фара … · б/у · 1 шт», — где
 * `ConditionWord` поставить некуда. Без состояния — наименование как есть.
 * Слово — тем же {@link conditionTitle}: своего словаря подпись не держит.
 */
export function titleWithCondition(title: string, code: string | null | undefined): string {
  const word = conditionTitle(code);
  return word === null ? title : `${title} · ${word}`;
}

/**
 * Порядок по умолчанию — номер позиции по убыванию, новые сверху.
 *
 * <p>Решение владельца продукта 19.10.2026 «всё как у bazon» (задача 0372):
 * у Bazon списки открываются «Номером товара» по убыванию, и у клиента
 * с 35 тысячами позиций первая страница — это то, с чем работают сегодня,
 * а не позиции трёхлетней давности. Запомненная сортировка человека
 * не трогается (`viewOf` берёт её как есть); «Сбросить сортировку» ведёт сюда.
 * Умолчание одно с сервером (`CatalogController`, страница и выгрузка).
 *
 * <p>До задачи 0060 здесь стояло `sort: 'code', desc: true`, а `code` —
 * это `public_code`, шесть случайных байт. То есть владелец, открывавший
 * главный свой экран, видел **случайную полусотню** из тридцати пяти тысяч
 * позиций, и завтра другую: позиция, стоявшая вчера второй сверху, сегодня
 * не видна вовсе — и это читается как «пропала».
 *
 * <p>Решение владельца продукта от 11 сентября 2026 «сортировать витрину
 * по порядковому номеру 1. 2. 3. и тд» (задача 0060) дало номер, но его
 * направление («по возрастанию») отменено 19.10.2026.
 */
export const DEFAULT_SORT = 'number';
export const DEFAULT_DESC = true;

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
 * <p>Заголовка и стороны тут нет: заголовок собирается из них справочником,
 * и правка руками разошлась бы с ним при первом же пересопоставлении
 * наименований. **Состояние с задачи 0039 правится** — пометку в заголовке
 * двигает сервер, — и пустое значение здесь означает «не трогать»,
 * а не «очистить»: состояния «не заполнено» у детали не бывает.
 */
export interface PartEdit {
  price: number | null;
  minPrice: number | null;
  costPrice: number | null;
  installationPrice: number | null;
  condition: string | null;
  qualityGrade: string | null;
  description: string | null;
  note: string | null;
  textBlock: string | null;
  /** Выбранный именованный текстовый блок описания (задача 0337); null — не выбран. */
  textBlockId?: number | null;
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
  /**
   * Блоки карточки Дрома — тексты клиента дословно (задача 0285). С сервера
   * приходят `null`, когда блока нет; уезжают строкой, пустой при очистке:
   * `null` или отсутствующий ключ сервер читает как «не трогать» (так вкладка,
   * открытая до выкладки, не стирает то, о чём её форма не знает).
   */
  warranty: string | null;
  pickup: string | null;
  deliveryPayment: string | null;
  /**
   * Вид детали и раздел как у клиента на Дроме (задача 0286): строки дословно,
   * те же два способа «пусто», что у блоков выше. Не `part_kind` и не «Секция».
   */
  dromKind: string | null;
  dromSection: string | null;
  /**
   * «Подходит» как у клиента на Дроме (задача 0287): блоки текстом, строка на пункт,
   * части через « | ». Те же два способа «пусто». Не справочная применимость.
   */
  applicability: string | null;
  /**
   * «Характеристики» — «Прочие поля» объявления Дрома (задача 0292): строки
   * «Подпись [ключ]: значение» как пришли. Те же два способа «пусто».
   */
  characteristics: string | null;
  /**
   * «Может подойти» — второй блок применимости объявления Дрома (задача 0293):
   * догадка самого Дрома, строка на машину «Марка Модель — КОД, КОД…», как пришла.
   * Только текст, не справочная применимость. Те же два способа «пусто».
   */
  mayFit: string | null;
  /**
   * «Дата» объявления Дрома (задача 0298): ГГГГ-ММ-ДД текстом, как пришла. Не «Создан» позиции.
   * Те же два способа «пусто».
   */
  adDate: string | null;
  /**
   * Задача 0302 — поля, стоявшие с замком. С сервера приходит то, что лежит
   * у позиции; уезжают только раскрытые строки, остальных ключей нет («не
   * трогать»), пустая строка — очистить. У машины (`donorCode` … `equipment`)
   * с сервера приходит только вписанное у позиции руками: пусто — у позиции
   * своего нет, и показывается донор или применимость из строки витрины.
   */
  /**
   * № позиции. Уезжает строкой цифр, а не числом (задача 0303): число
   * в браузере точно только до 2^53 − 1, и вписанное больше уехало бы
   * округлённым — сервер принял бы не тот номер. Приходит числом.
   */
  number?: number | string | null | undefined;
  code?: string | null | undefined;
  title?: string | null | undefined;
  sideLr?: string | null | undefined;
  sideFr?: string | null | undefined;
  authenticity?: string | null | undefined;
  oem?: string | null | undefined;
  crosses?: string | null | undefined;
  legacyCode?: string | null | undefined;
  dromAdNumber?: string | null | undefined;
  donorCode?: string | null | undefined;
  brand?: string | null | undefined;
  model?: string | null | undefined;
  generation?: string | null | undefined;
  body?: string | null | undefined;
  engine?: string | null | undefined;
  year?: string | null | undefined;
  equipment?: string | null | undefined;
  /** Машина из общего справочника (задача 0377): ссылки на записи; нет ключа — не трогать. */
  bazonVehicle?: BazonSelection | null | undefined;
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
 * <p>Только изменённые поля (задача 0307, решение владельца продукта
 * 16.10.2026: «1-б»): ключа нет — сервер поле не трогает, ключ есть и пуст —
 * очищает. До неё форма уезжала целиком, и вторая вкладка, сохраняя заметку,
 * откатывала цену, которую успела поменять первая.
 *
 * <p>`seen` — что человек видел у этих полей при открытии формы, как пришло
 * с сервера. Разошлось с базой — поле успел изменить другой, и сервер его
 * не сохраняет, а остальное сохраняет (задача 0313): ответ 200, несохранённое —
 * в `notSaved`. Занято всё присланное — 409 с названием поля, и не сохранено
 * ничего.
 */
export function savePart(
  partId: number,
  edit: Partial<PartEdit>,
  priceOp: PriceOperation = 'SET',
  seen?: Partial<PartEdit>,
): Promise<SavedPart> {
  return request<SavedPart>(`/api/parts/${partId}`, {
    method: 'PUT',
    // При арифметике `price` несёт не новую цену, а значение операции —
    // процент, сумму или шаг. Считает сервер: тот же расчёт нужен правке
    // списком, и две копии разошлись бы на первом округлении. Операция
    // уезжает только вместе с ценой: без цены ей не к чему относиться.
    body: {
      ...edit,
      ...('price' in edit ? { priceOp } : {}),
      ...(seen === undefined ? {} : { seen }),
    } as unknown as Record<string, unknown>,
  });
}

/**
 * Ответ сохранения карточки.
 *
 * <p>`notSaved` и `form` необязательны: сервер прежней версии (выкладка идёт,
 * вкладка новая) их не шлёт, и отсутствие читается как «сохранено всё».
 */
export interface SavedPart {
  price: number | null;
  /** Что не сохранилось из-за чужой правки; пусто — сохранено всё (задача 0313). */
  notSaved?: NotSavedField[];
  /** Форма после записи — тем же видом, что `loadEditable`. */
  form?: PartEdit;
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
  source: BulkSource = 'LIST',
): Promise<BulkResult> {
  return request<BulkResult>('/api/parts/bulk', {
    method: 'POST',
    body: { partIds, changes, operations, source },
  });
}

/**
 * Откуда правка (задача 0373). «Правка списком» подписывается в истории
 * позиции всегда, и при одной отмеченной строке: «клиент должен явно
 * понимать что правит не одну позицию а выбранные» (решение владельца
 * 23.10.2026). `CARD` — правка из карточки тем же путём (оценка состояния),
 * она подписи не получает. По числу позиций эти два не различить.
 */
export type BulkSource = 'LIST' | 'CARD';

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


