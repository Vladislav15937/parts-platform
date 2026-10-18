import { count, plural } from '../ui/plural';

/**
 * Предупреждение о необычно длинном номере производителя и кросс-номерах
 * (задача 0310, решение владельца продукта 16.10.2026: «длина как сейчас,
 * но с предупреждением»).
 *
 * <p>Предупреждение сохранение не гасит: склад личный (0302). Длинное значение
 * чаще всего — вставка не из того поля, и уезжает оно в прайс Дрома дважды,
 * поэтому экран говорит об этом рядом с полем.
 *
 * <p>Запрет — только сверх {@link OEM_STORE_LIMIT} (задача 0312, решение
 * владельца 17.10.2026: «отказывай словами»): такой номер не помещается
 * в хранилище, и «Сохранить» гаснет, пока его не укоротят.
 *
 * <p>Пороги — одним местом, правятся одной строкой (предложены разведкой).
 */
export const LONG_NUMBER_LIMITS = { oem: 64, crosses: 1000 } as const;

/**
 * Подсказка у поля «Номер производителя» — на карточке и на приёмке одна
 * (задача 0323, решение владельца 18.10.2026: «у детали только один номер
 * производителя — остальное — кросс номера»). Слова исполнителя, названы в PR.
 */
export const OEM_FIELD_NOTE = 'Один номер; если впишете несколько через запятую,'
  + ' первый останется номером производителя, остальные станут кросс-номерами';

/**
 * Самый длинный номер, который можно сохранить, в знаках приведённого вида —
 * копия `OemNumbers.MAX_NORMALIZED_LENGTH`; сверяет их `OemLengthClientTest`.
 * Каждый номер отдельно: суммарного предела у кросс-номеров нет.
 */
export const OEM_STORE_LIMIT = 1000;

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

/**
 * Длина самого длинного номера в поле «Номер производителя». Сервер делит его
 * теми же разделителями (`OemNumbers.primary`, задача 0323): первый номер —
 * основной, остальные уходят в кросс-номера, и предел у каждого свой, а не
 * у строки «A, B» целиком.
 */
export function longestOemLength(raw: string): number {
  return raw.split(SEPARATORS).reduce((max, piece) => Math.max(max, oemLength(piece)), 0);
}

/** Текст предупреждения у поля или `null`, если значение обычной длины. */
export function longNumberWarning(field: 'oem' | 'crosses', raw: string): string | null {
  const length = field === 'oem' ? longestOemLength(raw) : crossesLength(raw);
  if (length <= LONG_NUMBER_LIMITS[field]) return null;
  const what = field === 'oem' ? 'Необычно длинный номер производителя'
                               : 'Необычно длинные кросс-номера';
  const signs = plural(length, 'знак', 'знака', 'знаков');
  return `${what} — ${count(length)} ${signs}: проверьте, не вставили ли лишнее.`
    + ' Сохранить всё равно можно';
}

/**
 * Длина номера так, как её меряет сервер (`OemNumbers.normalize`): латиница
 * и цифры, плюс русские буквы-двойники, которые сервер читает латиницей.
 * Остальное (разделители, прочие русские буквы) в номер не идёт. Двойник
 * в слове с недвойником сервер оставляет русским и вырезает — здесь он
 * засчитывается, то есть экран может отказать чуть раньше сервера, но не позже.
 */
export function normalizedLength(raw: string): number {
  return (raw.match(/[A-Za-z0-9АВЕКМНОРСТХаеорсху]/g) ?? []).length;
}

/**
 * Почему значение нельзя сохранить (задача 0312), или `null`. Слова — те же,
 * что ответит сервер (`OemNumbers.tooLong`), чтобы экран и сервер говорили одно.
 */
export function numberTooLong(field: 'oem' | 'crosses', raw: string): string | null {
  const tail = ` длиннее ${OEM_STORE_LIMIT} знаков: сохранить нельзя.`
    + ' Похоже, вставлено не то — проверьте номер';
  if (field === 'oem') {
    // Каждый номер поля отдельно, как `OemNumbers.primaryTooLong` (задача 0323).
    return raw.split(SEPARATORS).some((piece) => normalizedLength(piece) > OEM_STORE_LIMIT)
      ? `Номер производителя${tail}` : null;
  }
  for (const piece of raw.split(SEPARATORS)) {
    if (normalizedLength(piece) > OEM_STORE_LIMIT) {
      const shown = piece.replace(EDGE_SPACES, '');
      return `Кросс-номер «${shown.length > 20 ? `${shown.slice(0, 20)}…` : shown}»${tail}`;
    }
  }
  return null;
}

