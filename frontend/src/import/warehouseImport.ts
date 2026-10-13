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

/**
 * Поля, которые импорт умеет распознавать. Имена совпадают с серверными.
 *
 * <p>Количество необязательно с задачи 0279: на карточке Дрома его нет вовсе,
 * и без колонки каждая позиция заводится по одной штуке — экран говорит
 * об этом до загрузки (`quantityNote`). «Наличие» — поле только файла
 * переноса с Дрома (`dromOnly`): правило «В наличии» дословно дромовское,
 * и предлагать его к обычной таблице значит дать пропустить весь склад.
 */
export const FIELDS = [
  { key: 'NAME', label: 'Наименование', required: true, dromOnly: false },
  { key: 'QUANTITY', label: 'Количество', required: false, dromOnly: false },
  { key: 'PRICE', label: 'Цена', required: false, dromOnly: false },
  { key: 'CELL', label: 'Ячейка', required: false, dromOnly: false },
  { key: 'OEM', label: 'Номер детали', required: false, dromOnly: false },
  { key: 'NOTE', label: 'Примечание', required: false, dromOnly: false },
  { key: 'AVAILABILITY', label: 'Наличие', required: false, dromOnly: true },
  // Ссылки на снимки, по одной на строку ячейки, главный первым. Сами файлы
  // качает отдельный проход «Фотографии» ниже на экране (задача 0273).
  { key: 'PHOTOS', label: 'Фото', required: false, dromOnly: false },
  { key: 'DROM_AD_NUMBER', label: 'Номер объявления Дрома', required: false, dromOnly: false },
  // Поля карточки Дрома (задача 0277). Сервер их по заголовку чужой таблицы
  // не угадывает — сопоставляются договором файла переноса или руками здесь.
  { key: 'CONDITION', label: 'Состояние', required: false, dromOnly: false },
  { key: 'MANUFACTURER', label: 'Производитель', required: false, dromOnly: false },
  { key: 'AUTHENTICITY', label: 'Оригинальность', required: false, dromOnly: false },
  { key: 'OEM_SUBSTITUTES', label: 'Номера замен', required: false, dromOnly: false },
  { key: 'SIDE_LR', label: 'Сторона', required: false, dromOnly: false },
  { key: 'SIDE_FR', label: 'Положение', required: false, dromOnly: false },
  { key: 'SIDE_UD', label: 'Верх/низ', required: false, dromOnly: false },
  // Текст объявления, который видит покупатель, — не «Примечание»: то
  // внутренний комментарий. Только у файла с Дрома (задача 0275): в чужой
  // таблице «Описание» по-прежнему угадывается примечанием, и выдать чужую
  // заметку покупателю хуже, чем спрятать её.
  { key: 'DESCRIPTION', label: 'Описание', required: false, dromOnly: true },
] as const;

export type FieldKey = (typeof FIELDS)[number]['key'];

export interface Preview {
  header: string[];
  /**
   * Что сервер распознал сам: поле → номер колонки. Только то, что импорт
   * читает: узнанная, но отбрасываемая колонка сюда не приходит (задача 0272).
   */
  detected: Partial<Record<FieldKey, number>>;
  missingRequired: FieldKey[];
  rows: string[][];
  /**
   * `DROM_TRANSFER` — файл переноса с Дрома (docs/drom-transfer-file.md):
   * колонки сопоставлены договором, а не догадкой. Ответ прежней версии
   * поля не несёт — читать через `?? null`.
   */
  format?: 'DROM_TRANSFER' | null;
}

export const DROM_TRANSFER = 'DROM_TRANSFER';

/**
 * Колонки файла, которые загрузка пропустит, — заголовками, как в файле.
 *
 * <p>Нужны затем, чтобы экран сказал это вслух. Колонка без поля не уедет
 * никуда, и молчание тут читается как «перенесётся»: у файла переноса с Дрома
 * так выглядели бы снимки, описание и номер объявления.
 */
export function notTransferred(
  header: string[],
  columns: Partial<Record<FieldKey, number>>,
): string[] {
  const used = new Set(Object.values(columns).filter((i): i is number => i !== undefined));
  return header
    .map((title, index) => ({ title: title === '' ? `колонка ${index + 1}` : title, index }))
    .filter(({ index }) => !used.has(index))
    .map(({ title }) => title);
}

export interface Report {
  imported: number;
  /**
   * `sheet` — лист, к которому относится строка; нет или `null` — первый.
   * Повреждения листа «Продолжение» (задача 0281) несут его имя: номер строки
   * у них — строка того листа.
   */
  skipped: { row: number; reason: string; sheet?: string | null }[];
  /**
   * Сколько позиций заведено по одной штуке, потому что количества в файле
   * не было. Ответ прежней версии поля не несёт — читать через `?? 0`.
   */
  quantityDefaulted?: number;
}

/**
 * Пропуски первого листа и повреждения остальных — порознь.
 *
 * <p>Первые экран показывает списком под пределом в двадцать строк, вторые —
 * отдельно и все: их дописывают после строк первого листа, и под общим
 * пределом у файла с Дрома, где «не в наличии» десятки, повреждение листа
 * «Продолжение» уходило бы за двадцатый пункт — значение обрезано, а экран
 * молчит.
 */
export function splitSkipped(skipped: Report['skipped']): {
  rows: Report['skipped'];
  sheets: Report['skipped'];
} {
  return {
    rows: skipped.filter((s) => s.sheet == null),
    sheets: skipped.filter((s) => s.sheet != null),
  };
}

/** Слова о количестве по умолчанию — дословно из критерия задачи 0279. */
export const NO_QUANTITY_COLUMN =
  'Количества в файле нет — каждая позиция заведётся по 1 шт.';

/**
 * Что экран обязан сказать о количестве до загрузки, или `null`.
 *
 * <p>Одна штука — догадка, а догадка показывается человеку, а не применяется
 * молча. Колонки нет — говорим это словами критерия. Колонка есть, но в первых
 * строках пуста у всех (так выглядит файл переноса с Дрома: на карточке
 * количества нет) — говорим про эти строки: обещать про весь файл по первым
 * пятнадцати нельзя.
 */
export function quantityNote(
  columns: Partial<Record<FieldKey, number>>,
  rows: string[][],
): string | null {
  const at = columns.QUANTITY;
  if (at === undefined) {
    return NO_QUANTITY_COLUMN;
  }
  if (rows.length > 0 && rows.every((row) => (row[at] ?? '').trim() === '')) {
    return 'В первых строках файла количество пустое — такие позиции заведутся по 1 шт.';
  }
  return null;
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
 * <p>Обязательно одно наименование: без него нечего заводить. Количества
 * может не быть — тогда по одной штуке (`quantityNote`). Цена необязательна —
 * её проставляют потом, и склад без цен всё равно лучше склада в тетради.
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
