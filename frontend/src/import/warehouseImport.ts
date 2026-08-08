import { request, upload } from '../api/client';

/**
 * Перенос склада из таблицы клиента.
 *
 * <p>Первое, что делает клиент при переходе: склад в три тысячи позиций
 * не перезаносят руками, а в пятьдесят — тем более. Пока экрана не было,
 * загрузку приходилось делать за клиента запросом к API, и дальше первого
 * клиента это не масштабируется.
 *
 * <p><b>Два шага, а не один.</b> Сначала разбор, потом запись. Сопоставление
 * колонок — догадка сервера по заголовкам чужой таблицы, а ошибка в ней
 * тихая: перепутанные цена и количество дают склад, где всё по три рубля,
 * и замечают это на первой продаже.
 */

/** Поля, которые импорт умеет распознавать. Имена совпадают с серверными. */
export const FIELDS = [
  { key: 'NAME', label: 'Наименование', required: true },
  { key: 'QUANTITY', label: 'Количество', required: true },
  { key: 'PRICE', label: 'Цена', required: false },
  { key: 'CELL', label: 'Ячейка', required: false },
  { key: 'OEM', label: 'Номер детали', required: false },
  { key: 'NOTE', label: 'Примечание', required: false },
] as const;

export type FieldKey = (typeof FIELDS)[number]['key'];

export interface Preview {
  header: string[];
  /** Что сервер распознал сам: поле → номер колонки. */
  detected: Partial<Record<FieldKey, number>>;
  missingRequired: FieldKey[];
  rows: string[][];
}

export interface Report {
  imported: number;
  skipped: { row: number; reason: string }[];
}

export function previewFile(file: File): Promise<Preview> {
  const form = new FormData();
  form.append('file', file);
  return upload<Preview>('/api/import/excel/preview', form);
}

/**
 * Заливает склад.
 *
 * <p>Сопоставление уходит целиком, а не поправками к догадке сервера: иначе
 * непонятно, что человек подтвердил, а что просто не заметил.
 */
export function importFile(
  file: File,
  warehouseId: number,
  columns: Partial<Record<FieldKey, number>>,
  requestId: string,
): Promise<Report> {
  const form = new FormData();
  form.append('file', file);
  form.append('warehouseId', String(warehouseId));
  // Ключ не меняется при повторах — в этом весь смысл. Ошибка на ответе
  // выглядит для владельца как «не загрузилось», он нажмёт ещё раз,
  // и без ключа получит склад в двух экземплярах. Так и случилось
  // при первой проверке.
  form.append('requestId', requestId);
  for (const [field, index] of Object.entries(columns)) {
    if (index !== undefined && index >= 0) {
      form.append(field, String(index));
    }
  }
  return upload<Report>('/api/import/excel', form);
}

/**
 * Чего не хватает для запуска.
 *
 * <p>Наименование и количество обязательны: без первого нечего заводить,
 * без второго непонятно, сколько. Цена необязательна — её проставляют потом,
 * и склад без цен всё равно лучше склада в тетради.
 */
export function missingRequired(columns: Partial<Record<FieldKey, number>>): string[] {
  return FIELDS.filter((f) => f.required && columns[f.key] === undefined).map((f) => f.label);
}

/**
 * Колонки, назначенные больше одного раза.
 *
 * <p>Одна колонка на два поля — это гарантированно ошибка: цена и количество,
 * взятые из одного столбца, дадут склад, где всё стоит столько, сколько его
 * лежит. Сервер такое примет молча, потому что синтаксически это допустимо.
 */
export function duplicateColumns(columns: Partial<Record<FieldKey, number>>): number[] {
  const used = Object.values(columns).filter((i): i is number => i !== undefined);
  return [...new Set(used.filter((i, at) => used.indexOf(i) !== at))];
}

/**
 * Перенос склада из выгрузки предыдущей системы.
 *
 * <p>Два файла, а не один: выгрузка машин и выгрузка товаров — разные таблицы
 * в чужом кабинете, и склеить их за клиента нельзя. Деталь ссылается на машину
 * номером, а поставка приезжает только с машиной.
 *
 * <p>Ключа идемпотентности тут нет, в отличие от Excel: импортёр узнаёт уже
 * загруженное по номерам из самой выгрузки. Повтор — обычное действие,
 * а не авария.
 */
export interface BazonProblem {
  line: number;
  message: string;
}

export interface BazonResult {
  /** Что и сколько перенесено. Ключи приходят с сервера словами. */
  loaded: Record<string, number>;
  problems: BazonProblem[];
  problemCount: number;
}

export function importBazon(donors: File, catalog: File): Promise<BazonResult> {
  const form = new FormData();
  form.append('donors', donors);
  form.append('catalog', catalog);
  return upload<BazonResult>('/api/import/bazon', form);
}

/**
 * Итог переноса шин и дисков.
 *
 * @param created карточек: комплект из четырёх — это четыре
 * @param sets    строк файла, ставших комплектами
 * @param skipped уже перенесённых раньше — повтор безопасен
 */
export interface WheelImportResult {
  created: number;
  sets: number;
  skipped: number;
  photos: number;
  problems: string[];
}

/**
 * Колёса переезжают отдельным файлом: у Bazon они на своей вкладке
 * и в выгрузку товаров не попадают вовсе.
 */
export function importWheels(file: File, warehouseId: number): Promise<WheelImportResult> {
  const form = new FormData();
  form.append('wheels', file);
  form.append('warehouseId', String(warehouseId));
  return upload<WheelImportResult>('/api/import/bazon/wheels', form);
}

/** Сколько фотографий перенесено, сколько не вышло и сколько ждёт. */
export interface PhotoProgress {
  done: number;
  failed: number;
  pending: number;
  total: number;
  broken: number;
}

export function photoStatus(): Promise<PhotoProgress> {
  return request<PhotoProgress>('/api/import/bazon/photos');
}

export function migratePhotos(limit = 200): Promise<PhotoProgress> {
  return request<PhotoProgress>(`/api/import/bazon/photos?limit=${limit}`, { method: 'POST' });
}

export function retryPhotos(): Promise<PhotoProgress> {
  return request<PhotoProgress>('/api/import/bazon/photos/retry', { method: 'POST' });
}

/** @param parts позиций, назвавших машину в заголовке; added — строк добавлено */
export interface ParsedApplicability {
  parts: number;
  added: number;
}

/**
 * Проставляет применимость по машинам из наименований.
 *
 * <p>У переехавшего клиента четверть склада без донора — это детали,
 * подходящие к нескольким машинам, и машины названы прямо в наименовании.
 * Без этого прохода подбор по машине их не находит вовсе.
 */
export function applicabilityFromTitles(): Promise<ParsedApplicability> {
  return request<ParsedApplicability>('/api/parts/catalog/applicability/from-titles', {
    method: 'POST',
  });
}
