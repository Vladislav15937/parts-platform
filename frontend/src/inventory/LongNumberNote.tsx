import { longNumberWarning, numberTooLong } from './oemLength';

/**
 * Предупреждение о необычно длинном номере (задача 0310): сохранение не гасит,
 * только говорит. Одно на карточку и приёмку — пороги и счёт длины в `oemLength.ts`.
 */
export function LongNumberNote({ field, value }: { field: 'oem' | 'crosses'; value: string }) {
  // Сверх предела хранилища — не предупреждение, а запрет (задача 0312):
  // другим уровнем, и «Сохранить» рядом погашена.
  const refusal = numberTooLong(field, value);
  if (refusal !== null) {
    return <span className="note note--error long-number-note" role="alert">{refusal}</span>;
  }
  const warning = longNumberWarning(field, value);
  return warning === null
    ? null
    : <span className="note long-number-note" role="status">{warning}</span>;
}
