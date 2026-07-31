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
  /** Остаток по складам: ключ — идентификатор склада, число колонок = число складов. */
  stock: Record<string, number>;
}

export interface CatalogPage {
  total: number;
  warehouses: Warehouse[];
  rows: CatalogRow[];
}

export interface CatalogQuery {
  q: string;
  reserved: boolean;
  missing: boolean;
  warehouses: number[];
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
  return `/api/parts/catalog/export?${params.toString()}`;
}

export function loadCatalog(query: CatalogQuery): Promise<CatalogPage> {
  const params = new URLSearchParams({
    reserved: String(query.reserved),
    missing: String(query.missing),
    sort: query.sort,
    desc: String(query.desc),
    page: String(query.page),
    size: String(query.size),
  });
  if (query.q.trim() !== '') {
    params.set('q', query.q.trim());
  }
  for (const id of query.warehouses) {
    params.append('warehouses', String(id));
  }
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
}

const SIDE_LR: Record<string, string> = { LEFT: 'лев.', RIGHT: 'прав.' };
const SIDE_FR: Record<string, string> = { FRONT: 'перед.', REAR: 'задн.' };
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

export const COLUMNS: Column[] = [
  { key: 'code', title: 'Номер товара', sort: 'code', value: (r) => text(r.code) },
  { key: 'title', title: 'Запчасть', sort: 'title', value: (r) => r.title },
  { key: 'quality', title: 'Оценка состояния',
    value: (r) => text(r.qualityGrade) || CONDITION[r.condition ?? ''] || '' },
  { key: 'brand', title: 'Марка', sort: 'brand', value: (r) => text(r.brand) },
  { key: 'model', title: 'Модель', sort: 'model', value: (r) => text(r.model) },
  { key: 'generation', title: 'Поколение донора', value: (r) => text(r.generation) },
  { key: 'restyling', title: 'Рестайлинг донора',
    // Поколение у нас — диапазон лет, а не тип кузова: так его и показываем.
    value: (r) => (r.yearFrom === null ? '' : `${r.yearFrom}—${text(r.yearTo)}`) },
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
];

/**
 * Колонки, видимые по умолчанию.
 *
 * <p>Все двадцать три сразу — это горизонтальная простыня, в которой
 * не найти цену. Показываем то, по чему деталь узнают, остальное владелец
 * включает сам и выбор запоминается.
 */
export const DEFAULT_VISIBLE = [
  'code', 'title', 'brand', 'model', 'year', 'sideFr', 'sideLr', 'price', 'section',
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
