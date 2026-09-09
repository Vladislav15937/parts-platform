import { request } from '../api/client';
import { normalizeCode } from '../scan/codes';

/**
 * Этикетки для термопринтера.
 *
 * <p>Печатаются браузером, а не отдаются драйверу принтера командами ZPL или
 * TSPL: у первых клиентов принтеры разные и заранее неизвестные, а печать
 * из браузера работает с любым, у которого есть драйвер в системе. Ценой
 * тому — необходимость выставить размер этикетки в диалоге печати один раз.
 */

export interface Cell {
  id: number;
  code: string;
  zone: string | null;
  active: boolean;
}

export function cellsOf(warehouseId: number): Promise<Cell[]> {
  return request<Cell[]>(`/api/organization/warehouses/${warehouseId}/cells`);
}

/**
 * Что печатается на этикетке ячейки.
 *
 * <p>Код приводится к латинице тем же {@link normalizeCode}, которым сканер
 * разбирает прочитанное. Иначе кириллическая «А», введённая человеком
 * в русской раскладке, вовсе не закодируется — Code128 её не знает, —
 * а напечатанная латинская `A` не совпала бы с базой при сверке глазами.
 */
export interface CellLabel {
  kind: 'cell';
  /** Что кодируется в штрихкод: только латиница и цифры. */
  code: string;
  /** Что печатается крупно: то же, что видит кладовщик в системе. */
  title: string;
  note: string | null;
}

export function cellLabel(cell: Cell, warehouseName: string): CellLabel {
  return {
    kind: 'cell',
    code: normalizeCode(cell.code),
    title: cell.code,
    note: cell.zone === null ? warehouseName : `${warehouseName} · ${cell.zone}`,
  };
}

export interface PartLabel {
  kind: 'part';
  code: string;
  title: string;
  note: string | null;
}

/**
 * Этикетка детали.
 *
 * <p>Кодируется неугадываемый код карточки, а не её номер: этикетка попадает
 * к покупателю вместе с деталью, и порядковый номер сказал бы ему, сколько
 * позиций на складе.
 */
export function partLabel(
  publicCode: string,
  title: string,
  // Числом или строкой: витрина склада отдаёт цену числом, поиск продавца —
  // строкой. Приводит её всё равно `Number`, и одна этикетка на оба места
  // дороже, чем один лишний тип в подписи.
  price: string | number | null,
): PartLabel {
  return {
    kind: 'part',
    code: normalizeCode(publicCode),
    title,
    note: price === null ? null : `${Math.round(Number(price)).toLocaleString('ru-RU')} ₽`,
  };
}

export type Label = CellLabel | PartLabel;

/**
 * Кодируется ли адрес штрихкодом.
 *
 * <p>Code128 не знает кириллицы вовсе. Часть букв это переживает: «А», «В»,
 * «Е», «К» и прочие неотличимы от латинских, и {@link normalizeCode} сводит
 * их к латинице — той самой, что напечатана на этикетке. А «Б», «Г», «Д»
 * двойника не имеют, и такой адрес не отсканируется никогда: ни с нашей
 * этикетки, ни с чужой.
 *
 * <p>Подменять их похожими нельзя ни в коем случае: «Б-02-1», напечатанная
 * как «B-02-1», сольётся с настоящей «В-02-1» — и кладовщик положит деталь
 * на другой стеллаж, ничего не заметив.
 */
export function scannable(code: string): boolean {
  const normalized = normalizeCode(code);
  for (const char of normalized) {
    const point = char.codePointAt(0) ?? 0;
    if (point < 32 || point > 126) {
      return false;
    }
  }
  return normalized.length > 0;
}

/**
 * Размер этикетки в миллиметрах.
 *
 * <p>58×40 — самый ходовой рулон у термопринтеров этой цены. Ширину модуля
 * штрихкода из неё и считаем: код должен уместиться с зонами покоя, иначе
 * сканер не найдёт его начало.
 */
export const LABEL_WIDTH_MM = 58;
export const LABEL_HEIGHT_MM = 40;

/** Поля этикетки: у термопринтеров край печати неточен на пару миллиметров. */
export const LABEL_PADDING_MM = 3;

/**
 * Подпись про размер этикетки — одна на все места печати.
 *
 * <p>Печатает браузер, а не драйвер принтера, поэтому размер выставляет
 * человек в диалоге печати: не выставит — принтер ужмёт штрихкод под лист A4,
 * и сканер перестанет его брать. Печатают этикетки из двух мест (экран
 * «Этикетки» пачкой и карточка позиции по одной), а подпись обязана быть
 * той же: разойдясь, вторая начала бы называть другой размер.
 */
export const LABEL_SIZE_NOTE =
  `Размер этикетки — ${LABEL_WIDTH_MM}×${LABEL_HEIGHT_MM} мм; выставьте его`
  + ' в диалоге печати и уберите поля, иначе принтер ужмёт штрихкод'
  + ' и сканер перестанет его брать.';
