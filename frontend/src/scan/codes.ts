import type { Cell, DonorRef, Reference, Warehouse } from '../reference/reference';

/**
 * Что означает прочитанный код.
 *
 * <p><b>Разбирается локально, а не запросом на сервер.</b> Сканируют в ангаре,
 * где связи нет, и справочники для этого уже лежат в IndexedDB. Поход на сервер
 * за расшифровкой кода превратил бы главное ускорение приёмки в самое хрупкое
 * место экрана.
 */
export type ScanMatch =
  | { kind: 'cell'; cell: Cell; warehouse: Warehouse }
  | { kind: 'donor'; donor: DonorRef }
  /** Код прочитан, но такого нет в справочниках. */
  | { kind: 'unknown'; text: string }
  /** Под код подходит больше одной ячейки. Угадывать нельзя. */
  | { kind: 'ambiguous'; text: string };

/**
 * Кириллические буквы, неотличимые от латинских, и их латинские двойники.
 *
 * <p>Нужны не для красоты. Code128 кодирует только ASCII: кириллицу в него
 * не положить в принципе. Значит на этикетке ячейки «А-01-1» напечатана
 * латинская `A`, а в базе у той же ячейки стоит кириллическая «А» — их набирал
 * человек в русской раскладке. Без приведения к одному алфавиту сканер
 * не найдёт ни одной ячейки, и выглядеть это будет как «сканер не работает».
 *
 * <p>Приводим к латинице, а не наоборот: с этой стороны множество однозначно.
 */
const HOMOGLYPHS: Record<string, string> = {
  А: 'A',
  В: 'B',
  Е: 'E',
  К: 'K',
  М: 'M',
  Н: 'H',
  О: 'O',
  Р: 'P',
  С: 'C',
  Т: 'T',
  У: 'Y',
  Х: 'X',
};

/**
 * Приводит код к виду, в котором его можно сравнивать.
 *
 * <p>Регистр и пробелы убираем: сканер отдаёт то, что напечатано, а печатали
 * по-разному. Разделители оставляем — «А-01-1» и «А0-11» это разные адреса,
 * и склеивать их значит класть деталь не на ту полку.
 */
export function normalizeCode(raw: string): string {
  return Array.from(raw.trim().toUpperCase())
    .map((ch) => HOMOGLYPHS[ch] ?? ch)
    .join('')
    .replace(/\s+/g, '');
}

/** VIN — ровно 17 символов без I, O и Q. Дешёвый признак, чтобы не путать с ячейкой. */
function looksLikeVin(code: string): boolean {
  return /^[A-HJ-NPR-Z0-9]{17}$/.test(code);
}

/**
 * Разбирает прочитанный код.
 *
 * @param warehouseId склад, на который принимают. Ячейки ищутся сначала здесь:
 *                    коды полок на разных складах совпадают сплошь и рядом,
 *                    и «А-01-1» есть на каждом
 */
export function resolveScan(
  reference: Reference,
  warehouseId: number | null,
  text: string,
): ScanMatch {
  const code = normalizeCode(text);
  if (code === '') {
    return { kind: 'unknown', text };
  }

  if (looksLikeVin(code)) {
    const donor = reference.donors.find(
      (d) => d.vin !== null && normalizeCode(d.vin) === code,
    );
    if (donor !== undefined) {
      return { kind: 'donor', donor };
    }
  }

  const current = reference.warehouses.find((w) => w.id === warehouseId);
  if (current !== undefined) {
    const cell = current.cells.find((c) => normalizeCode(c.code) === code);
    if (cell !== undefined) {
      return { kind: 'cell', cell, warehouse: current };
    }
  }

  // На текущем складе не нашлось — смотрим остальные. Это подсказка приёмщику,
  // что он держит этикетку с чужого склада, а не повод молча промолчать.
  const elsewhere: { cell: Cell; warehouse: Warehouse }[] = [];
  for (const warehouse of reference.warehouses) {
    if (warehouse.id === warehouseId) {
      continue;
    }
    for (const cell of warehouse.cells) {
      if (normalizeCode(cell.code) === code) {
        elsewhere.push({ cell, warehouse });
      }
    }
  }

  if (elsewhere.length === 1) {
    const only = elsewhere[0];
    if (only !== undefined) {
      return { kind: 'cell', cell: only.cell, warehouse: only.warehouse };
    }
  }
  if (elsewhere.length > 1) {
    return { kind: 'ambiguous', text };
  }
  return { kind: 'unknown', text };
}
