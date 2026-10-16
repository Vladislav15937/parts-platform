import { count, plural } from '../ui/plural';

/**
 * Предупреждение о необычно длинном номере производителя и кросс-номерах
 * (задача 0310, решение владельца продукта 16.10.2026: «длина как сейчас,
 * но с предупреждением»).
 *
 * <p>Предела нет ни на экране, ни на сервере: склад личный, сохранить можно
 * любое (0302). Длинное значение чаще всего — вставка не из того поля,
 * и уезжает оно в прайс Дрома дважды, поэтому экран говорит об этом рядом
 * с полем и сохранение не гасит.
 *
 * <p>Пороги — одним местом, правятся одной строкой (предложены разведкой).
 */
export const LONG_NUMBER_LIMITS = { oem: 64, crosses: 1000 } as const;

/**
 * Чем делит кросс-номера сервер — `OemNumbers.SEPARATORS`. Копия, потому что
 * длину надо знать до сохранения; сверяет их `OemLengthClientTest`.
 */
const SEPARATORS = /[,;\n\r\t]+/;

/**
 * Края без любых пробелов — как `OemNumbers.stripSpaces`: `\s` в JS покрывает
 * обычный, неразрывный, узкие и U+FEFF, а нулевой ширины (U+200B, U+2060) — нет.
 */
const EDGE_SPACES = /^[\s​⁠]+|[\s​⁠]+$/g;

/**
 * Длина номера, каким его сохранит сервер (`OemNumbers.clean`): срезанные края
 * не засчитываются. Замена русских двойников латиницей длину не меняет.
 */
export function oemLength(raw: string): number {
  return raw.replace(EDGE_SPACES, '').length;
}

/**
 * Длина кросс-номеров в сумме, как их сохранит сервер (`OemNumbers.list`):
 * разделители и пробелы по краям каждого номера не засчитываются.
 */
export function crossesLength(raw: string): number {
  return raw.split(SEPARATORS).reduce((sum, piece) => sum + oemLength(piece), 0);
}

/** Текст предупреждения у поля или `null`, если значение обычной длины. */
export function longNumberWarning(field: 'oem' | 'crosses', raw: string): string | null {
  const length = field === 'oem' ? oemLength(raw) : crossesLength(raw);
  if (length <= LONG_NUMBER_LIMITS[field]) return null;
  const what = field === 'oem' ? 'Необычно длинный номер производителя'
                               : 'Необычно длинные кросс-номера';
  const signs = plural(length, 'знак', 'знака', 'знаков');
  return `${what} — ${count(length)} ${signs}: проверьте, не вставили ли лишнее.`
    + ' Сохранить всё равно можно';
}
