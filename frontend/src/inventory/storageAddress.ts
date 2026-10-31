/**
 * Адрес хранения из пяти частей «склад-зона-ряд-секция-полка» — `01-01-03-01-02`
 * (задача 0368).
 *
 * <p>Правило записи то же, что у сервера (`inventory/StorageAddress.java`):
 * пять чисел по одной-две цифры через дефис, короткая часть дополняется нулём —
 * «01-01-3-1-2» это та же полка `01-01-03-01-02`, а не новая. Разошлись бы
 * они — экран пропускал бы то, что сервер отобьёт, или помечал бы «не по схеме»
 * то, что сервер принял. Заранее полки не заводятся (решение владельца
 * 19.10.2026 «Всё как у bazon», раздел «Ответ владельца» задачи 0368):
 * адрес рождается этикеткой и привязкой позиции.
 */

/** Пример, которым экран объясняет схему. */
export const ADDRESS_EXAMPLE = '01-01-03-01-02';

/** Слова схемы — одни на подсказку, пометку и отказ. */
export const ADDRESS_SCHEME = 'склад-зона-ряд-секция-полка';

const FORM = /^\s*(\d{1,2})\s*-\s*(\d{1,2})\s*-\s*(\d{1,2})\s*-\s*(\d{1,2})\s*-\s*(\d{1,2})\s*$/;

const two = (n: number): string => String(n).padStart(2, '0');

/**
 * Адрес в записи `NN-NN-NN-NN-NN` или `null`, если это не адрес по схеме.
 *
 * <p>Длинное тире и минус приводятся к дефису: их подставляет автозамена
 * телефона, а сканер отдаёт то, что напечатано, — дефис.
 */
export function normalizeAddress(text: string | null | undefined): string | null {
  if (text === null || text === undefined) return null;
  const m = FORM.exec(text.replace(/[–—−]/g, '-'));
  if (m === null) return null;
  return m.slice(1, 6).map((part) => two(Number(part))).join('-');
}

/**
 * Записан ли адрес по схеме. Прежняя ячейка со свободным названием
 * («А-01-1») остаётся как есть и называется «не по схеме» — её не переименовывают
 * молча (задача 0368, пункт 1).
 */
export function bySchema(text: string | null | undefined): boolean {
  return normalizeAddress(text) !== null;
}

/** Номер склада в адресе — его номер в системе, двумя цифрами, как у ориентира («Склад А» = 01). */
export function warehousePart(warehouseId: number): string {
  return two(warehouseId);
}

/**
 * Поля окна «Маркировка полок склада»: всё строками, как набрано.
 */
export interface MarkingForm {
  zone: string;
  row: string;
  sectionFrom: string;
  sectionTo: string;
  shelfFrom: string;
  shelfTo: string;
}

/** Части адреса — от 1 до 99: в адресе на каждую две цифры. */
const PART_MAX = 99;

/**
 * Что не так с набранным; `null` — можно печатать.
 *
 * <p>Одно выражение на кнопку и на подпись под ней: разойдясь, кнопка гасла бы
 * молча или подпись называла бы не ту причину (тот же приём, что `copiesProblem`).
 */
export function markingProblem(form: MarkingForm): string | null {
  const fields: [string, string][] = [
    ['Зона на складе', form.zone],
    ['Ряд на складе', form.row],
    ['Маркируемые секции: с', form.sectionFrom],
    ['Маркируемые секции: по', form.sectionTo],
    ['Полки в каждой секции: с', form.shelfFrom],
    ['Полки в каждой секции: по', form.shelfTo],
  ];
  for (const [name, value] of fields) {
    const text = value.trim();
    if (!/^\d+$/.test(text) || Number(text) < 1 || Number(text) > PART_MAX) {
      return `«${name}» — целое число от 1 до ${PART_MAX}: в адресе на него две цифры.`;
    }
  }
  if (Number(form.sectionTo) < Number(form.sectionFrom)) {
    return 'Секции: «по» меньше, чем «с».';
  }
  if (Number(form.shelfTo) < Number(form.shelfFrom)) {
    return 'Полки: «по» меньше, чем «с».';
  }
  return null;
}

/**
 * Адреса диапазона в порядке обхода стеллажа: секция за секцией, в каждой
 * полки снизу вверх по номерам — так этикетки и сходят с рулона, и клеить
 * их можно подряд, не перебирая пачку.
 */
export function markingAddresses(warehouseId: number, form: MarkingForm): string[] {
  if (markingProblem(form) !== null) return [];
  const head = `${warehousePart(warehouseId)}-${two(Number(form.zone))}-${two(Number(form.row))}`;
  const out: string[] = [];
  for (let s = Number(form.sectionFrom); s <= Number(form.sectionTo); s += 1) {
    for (let p = Number(form.shelfFrom); p <= Number(form.shelfTo); p += 1) {
      out.push(`${head}-${two(s)}-${two(p)}`);
    }
  }
  return out;
}
