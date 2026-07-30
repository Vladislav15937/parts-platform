import { upload } from '../api/client';

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
