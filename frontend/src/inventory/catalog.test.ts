import { describe, expect, it } from 'vitest';
import { COLUMNS, DEFAULT_VISIBLE, loadVisible, type CatalogRow } from './catalog';

/**
 * Витрина склада: состав колонок и их значения.
 *
 * <p>Ошибки тут тихие: колонка, показывающая не то, читается как правда.
 * «Лев.» вместо «прав.» на витрине из тридцати пяти тысяч позиций заметят
 * тогда, когда деталь приедет покупателю не той стороной.
 */
function row(overrides: Partial<CatalogRow> = {}): CatalogRow {
  return {
    id: 1, code: 'A1', title: 'Фара', qualityGrade: null, condition: 'USED',
    brand: null, model: null, generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: null, donorCode: null,
    price: null, installationPrice: null, color: null, description: null, note: null,
    manufacturer: null, marking: null, section: null, sideLr: null, sideFr: null,
    qty: 1, oem: null, crosses: null, photoUrl: null, stock: {},
    ...overrides,
  };
}

function value(key: string, source: CatalogRow): string {
  const column = COLUMNS.find((c) => c.key === key);
  if (column === undefined) {
    throw new Error(`нет колонки ${key}`);
  }
  return column.value(source);
}

describe('колонки витрины', () => {
  it('стороны показаны словами, а не кодом', () => {
    // «LEFT» в таблице — это утечка внутреннего представления: кладовщик
    // читает «лев.» и «перед.», а не английские константы.
    expect(value('sideLr', row({ sideLr: 'LEFT' }))).toBe('лев.');
    expect(value('sideFr', row({ sideFr: 'REAR' }))).toBe('задн.');
  });

  it('пустая сторона даёт пустоту, а не «undefined»', () => {
    expect(value('sideLr', row())).toBe('');
  });

  it('состояние берётся из оценки, а при её отсутствии — из вида', () => {
    expect(value('quality', row({ qualityGrade: 'C' }))).toBe('C');
    expect(value('quality', row({ condition: 'NEW' }))).toBe('новая');
  });

  it('цена без значения — пусто, а не ноль', () => {
    // Ноль означает «отдаём даром», и на витрине это утверждение.
    expect(value('price', row())).toBe('');
    expect(value('price', row({ price: 0 }))).toBe('0');
  });

  it('по умолчанию показаны не все колонки', () => {
    // Двадцать три сразу — простыня, в которой не найти цену.
    expect(DEFAULT_VISIBLE.length).toBeLessThan(COLUMNS.length);
    expect(DEFAULT_VISIBLE).toContain('price');
    expect(loadVisible().length).toBeGreaterThan(0);
  });
});
