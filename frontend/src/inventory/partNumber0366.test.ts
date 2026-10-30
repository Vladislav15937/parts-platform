import { afterEach, describe, expect, it, vi } from 'vitest';

import { COLUMNS, DEFAULT_VISIBLE, loadView, saveView } from './catalog';
import { resolvePartScan, type WarehouseCode } from './inventory';
import { WHEEL_COLUMNS, WHEEL_DEFAULT_VISIBLE, loadWheelVisible } from './wheels';
import { partLabel } from '../labels/labels';

/**
 * Номер товара — порядковый номер, как у ориентира (задача 0366, решение
 * владельца продукта 19.10.2026 «Всё как у bazon»).
 *
 * <p>До неё «Номером товара» на экране звался шестнадцатеричный публичный
 * код, а порядковый номер стоял рядом колонкой «№ позиции»: переехавший
 * клиент видел два номера там, где привык к одному, и строку случайных байт
 * под названием, которое у ориентира значит порядковый номер.
 */
describe('номер товара на складе и на колёсах', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('по умолчанию одна колонка «Номер товара» — порядковый номер разрядами', () => {
    const number = COLUMNS.find((c) => c.key === 'number')!;
    expect(number.title).toBe('Номер товара');
    expect(number.value({ number: 300001 } as never).replace(/ /g, ' ')).toBe('300 001');
    expect(DEFAULT_VISIBLE, 'публичный код виден по умолчанию').not.toContain('code');
    expect(DEFAULT_VISIBLE[0]).toBe('number');
    // Колонка с кодом осталась в «Настроить таблицу» — под своим именем.
    expect(COLUMNS.find((c) => c.key === 'code')?.title).toBe('Публичный код');
    expect(COLUMNS.filter((c) => c.title === 'Номер товара').length).toBe(1);
  });

  it('на колёсах то же самое', () => {
    expect(WHEEL_COLUMNS.find((c) => c.key === 'number')?.title).toBe('Номер товара');
    expect(WHEEL_COLUMNS.find((c) => c.key === 'code')?.title).toBe('Публичный код');
    expect(WHEEL_DEFAULT_VISIBLE).not.toContain('code');
  });

  /**
   * Раскладка, запомненная до 0366, держит `code` включённым — тогда это и был
   * «Номер товара» в умолчании. Без снятия у всех, кто хоть раз трогал склад,
   * строка случайных байт осталась бы на экране, то есть критерий 1 был бы
   * выполнен только у новых сотрудников.
   */
  it('запомненная до 0366 раскладка теряет публичный код, а запомненная после — нет', async () => {
    let saved: Record<string, unknown> = { visible: ['number', 'code', 'title'] };
    vi.stubGlobal('fetch', vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      if ((init?.method ?? 'GET') === 'PUT') {
        saved = JSON.parse(String(init?.body)) as Record<string, unknown>;
        return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      return new Response(JSON.stringify({ value: saved }),
        { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));

    const before = await loadView(50);
    expect(before.visible).toEqual(['number', 'title']);

    // Включил сам после 0366 — его выбор, он переживает следующее открытие.
    await saveView({ ...before, visible: ['number', 'code', 'title'] });
    const after = await loadView(50);
    expect(after.visible).toEqual(['number', 'code', 'title']);
  });

  it('колёса: прежняя запись теряет публичный код, новая — нет', () => {
    localStorage.setItem('wheel-columns', JSON.stringify(['number', 'code', 'photo']));
    expect(loadWheelVisible()).toEqual(['number', 'photo']);
    localStorage.setItem('wheel-columns-0366', JSON.stringify(['number', 'code']));
    expect(loadWheelVisible()).toEqual(['number', 'code']);
  });
});

describe('этикетка и сканер', () => {
  it('этикетка кодирует номер товара цифрами и печатает его разрядами (вариант «а»)', () => {
    const label = partLabel(300001, 'Фара левая', 12500, 'USED');
    expect(label.code, 'штрихкод кодирует не номер товара').toBe('300001');
    expect(label.number.replace(/ /g, ' ')).toBe('300 001');
  });

  const codes: WarehouseCode[] = [
    { partId: 11, number: 300001, title: 'Фара', publicCode: '7584A8FEAE3D', barcode: null,
      cellId: null, cellCode: null },
    { partId: 12, number: 300002, title: 'Бампер', publicCode: '55747F0F91CD', barcode: '4600001',
      cellId: null, cellCode: null },
  ];

  it('сканер находит позицию по номеру товара — новой этикетке', () => {
    expect(resolvePartScan(codes, [], '300002'))
      .toEqual({ kind: 'unlisted', code: codes[1] });
  });

  it('и по публичному коду — этикетке, напечатанной до 0366', () => {
    expect(resolvePartScan(codes, [], '7584a8feae3d'))
      .toEqual({ kind: 'unlisted', code: codes[0] });
    expect(resolvePartScan(codes, [], '4600001'))
      .toEqual({ kind: 'unlisted', code: codes[1] });
    expect(resolvePartScan(codes, [], '999')).toEqual({ kind: 'not-found' });
  });
});
