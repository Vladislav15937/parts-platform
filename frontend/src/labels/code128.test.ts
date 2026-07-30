import { BitArray, Code128Reader } from '@zxing/library';
import { describe, expect, it } from 'vitest';
import { code128, QUIET_ZONE } from './code128';
import { scannable } from './labels';

/**
 * Кодировщик проверяется тем же ZXing, который читает этикетки в сканере.
 *
 * <p>Обратная проверка, а не сверка с эталонными байтами: таблица ширин набрана
 * руками, и опечатка в одной строке даёт код, который печатается красиво
 * и не читается вовсе — а узнают об этом на складе, где уже наклеена тысяча
 * этикеток.
 *
 * <p>Растр не нужен: читателю Code128 отдаётся ряд модулей напрямую, тот же,
 * что уходит в SVG.
 */
function decode(value: string): string {
  const drawn = code128(value);

  const row = new BitArray(drawn.width);
  for (const bar of drawn.bars) {
    for (let x = bar.x; x < bar.x + bar.width; x++) {
      row.set(x);
    }
  }

  return new Code128Reader().decodeRow(0, row, new Map()).getText();
}

describe('Code128', () => {
  it('код ячейки читается обратно', () => {
    expect(decode('A-01-1')).toBe('A-01-1');
  });

  it('код детали читается обратно', () => {
    expect(decode('070CE888B9A6')).toBe('070CE888B9A6');
  });

  it('весь набор печатных ASCII кодируется без потерь', () => {
    // Ловит опечатку в любой строке таблицы ширин, а не только в тех
    // символах, что попались в примерах.
    const all = Array.from({ length: 95 }, (_, i) => String.fromCharCode(32 + i)).join('');
    for (let at = 0; at < all.length; at += 12) {
      const chunk = all.slice(at, at + 12);
      expect(decode(chunk)).toBe(chunk);
    }
  });

  it('зона покоя входит в ширину с обеих сторон', () => {
    const drawn = code128('A');
    expect(drawn.bars[0]!.x).toBe(QUIET_ZONE);
    const last = drawn.bars[drawn.bars.length - 1]!;
    // Без зоны покоя сканер не находит начало кода, и это выглядит как
    // «этикетка не читается», а не как «слишком узкое поле».
    expect(drawn.width - (last.x + last.width)).toBeGreaterThanOrEqual(QUIET_ZONE);
  });

  it('кириллица отвергается, а не кодируется наугад', () => {
    // «А-01-1» с кириллической А приходит из базы: её вводил человек
    // в русской раскладке. Напечатать такую этикетку нельзя — Code128
    // кириллицу не кодирует вовсе.
    expect(() => code128('А-01-1')).toThrow(/Code128/);
  });

  it('пустой код отвергается', () => {
    expect(() => code128('')).toThrow();
  });
});

describe('что можно напечатать', () => {
  it('адрес с латинским двойником кодируется', () => {
    // «А-01-1» в базе набрана кириллицей, а на этикетке будет латинская A:
    // сканер сводит их к одной букве, поэтому этикетка рабочая.
    expect(scannable('А-01-1')).toBe(true);
  });

  it('адрес без двойника не кодируется', () => {
    // У «Б» латинского двойника нет, и такой адрес не отсканируется никогда.
    // Раньше исключение из кодировщика прилетало прямо в render, и экран
    // печати уходил в белый лист на первой же такой ячейке.
    expect(scannable('Б-02-1')).toBe(false);
  });

  it('код детали кодируется', () => {
    expect(scannable('070CE888B9A6')).toBe(true);
  });
});
