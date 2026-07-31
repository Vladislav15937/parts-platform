import { describe, expect, it } from 'vitest';
import type { Reference } from '../reference/reference';
import { normalizeCode, resolveScan } from './codes';

/**
 * Разбор прочитанного кода.
 *
 * <p>Ошибка здесь тихая и дорогая: деталь ляжет в чужую ячейку, а узнают
 * об этом, когда её пойдут искать по продаже. Поэтому проверяется и то,
 * что распознаётся, и то, что распознаваться не должно.
 */

const reference: Reference = {
  loadedAt: '2026-07-30T00:00:00Z',
  warehouses: [
    {
      id: 1,
      name: 'Основной',
      cells: [
        // В базе код набран кириллицей — так его вводил человек.
        { id: 11, code: 'А-01-1', zone: null },
        { id: 12, code: 'А-01-2', zone: null },
        { id: 13, code: 'Б-02-3', zone: null },
      ],
    },
    {
      id: 2,
      name: 'Контейнер',
      cells: [
        { id: 21, code: 'А-01-1', zone: null },
        { id: 22, code: 'К-09-9', zone: null },
      ],
    },
  ],
  supplies: [],
  donors: [
    {
      id: 100,
      publicCode: 'М-0042',
      brand: 'Toyota',
      model: 'Camry',
      year: 2012,
      vin: 'JTNBE46K073123456',
      status: 'DISMANTLING',
      location: null,
    },
  ],
  partNames: [],
};

describe('приведение кода', () => {
  it('кириллица и латиница сводятся к одному виду', () => {
    // Ради этого всё и затевалось: Code128 кириллицу не кодирует,
    // значит на этикетке латинская A, а в базе — кириллическая А.
    expect(normalizeCode('А-01-1')).toBe(normalizeCode('A-01-1'));
  });

  it('регистр и пробелы не мешают', () => {
    expect(normalizeCode(' а-01-1 ')).toBe('A-01-1');
  });

  it('разделители сохраняются', () => {
    // «А-01-1» и «А0-11» — разные полки. Склеить их значит потерять деталь.
    expect(normalizeCode('А-01-1')).not.toBe(normalizeCode('А0-11'));
  });

  it('буквы без латинских двойников не трогаются', () => {
    expect(normalizeCode('Б-02-3')).toBe('Б-02-3');
  });
});

describe('разбор прочитанного кода', () => {
  it('этикетка ячейки находит ячейку текущего склада', () => {
    const match = resolveScan(reference, 1, 'A-01-1');

    expect(match).toMatchObject({ kind: 'cell', cell: { id: 11 } });
  });

  it('свой склад имеет приоритет над чужим', () => {
    // «А-01-1» есть на обоих складах. Приёмщик стоит на первом.
    const match = resolveScan(reference, 1, 'А-01-1');
    expect(match).toMatchObject({ kind: 'cell', cell: { id: 11 } });

    const other = resolveScan(reference, 2, 'А-01-1');
    expect(other).toMatchObject({ kind: 'cell', cell: { id: 21 } });
  });

  it('одна ячейка на другом складе принимается вместе со складом', () => {
    // Этикетка из контейнера, а выбран основной склад: приёмщик перешёл,
    // а список не переключил. Этикетка в руках вернее списка.
    const match = resolveScan(reference, 1, 'К-09-9');

    expect(match).toMatchObject({
      kind: 'cell',
      cell: { id: 22 },
      warehouse: { id: 2 },
    });
  });

  it('код на нескольких чужих складах не выбирается наугад', () => {
    // Склад не выбран вовсе — «А-01-1» подходит и там, и там.
    const match = resolveScan(reference, null, 'А-01-1');

    expect(match.kind).toBe('ambiguous');
  });

  it('VIN находит машину', () => {
    const match = resolveScan(reference, 1, 'JTNBE46K073123456');

    expect(match).toMatchObject({ kind: 'donor', donor: { id: 100 } });
  });

  it('чужой VIN не подставляет случайную машину', () => {
    const match = resolveScan(reference, 1, 'WVWZZZ1JZXW000001');

    expect(match).toEqual({ kind: 'unknown', text: 'WVWZZZ1JZXW000001' });
  });

  it('заводской штрихкод детали не считается ячейкой', () => {
    const match = resolveScan(reference, 1, '4607091380019');

    expect(match.kind).toBe('unknown');
  });

  it('пустое чтение ничего не подставляет', () => {
    expect(resolveScan(reference, 1, '   ').kind).toBe('unknown');
  });
});
