import { longNumberWarning } from './oemLength';

/**
 * Предупреждение о необычно длинном номере (задача 0310): сохранение не гасит,
 * только говорит. Одно на карточку и приёмку — пороги и счёт длины в `oemLength.ts`.
 */
export function LongNumberNote({ field, value }: { field: 'oem' | 'crosses'; value: string }) {
  const warning = longNumberWarning(field, value);
  return warning === null
    ? null
    : <span className="note long-number-note" role="status">{warning}</span>;
}
