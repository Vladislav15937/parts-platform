import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

import { InventoryScreen } from './InventoryScreen';

/**
 * Пересчёт не начинается без склада, и это видно по кнопкам.
 *
 * <p>Склад читался из разметки через `getElementById`, поэтому погасить
 * кнопки до выбора было нечем: «Продолжить начатую» и «Открыть новую»
 * выглядели нажимаемыми, хотя нажатие только показывало упрёк. В соседнем
 * блоке того же экрана — «Свести расхождения» — кнопка при этом гасла,
 * и два блока вели себя по-разному.
 *
 * <p>Цена ошибки не в лишнем нажатии: пересчёт не того склада — это обход,
 * который кладовщик сделает целиком, а проведение спишет недостачу там,
 * где ничего не считали.
 */
describe('склад пересчёта', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('[]', {
      status: 200, headers: { 'Content-Type': 'application/json' },
    })));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('кнопки погашены, пока склад не выбран', async () => {
    render(<InventoryScreen reference={reference()} onCount={vi.fn()} />);

    await waitFor(() => expect(document.querySelector('select')).toBeTruthy());

    const select = document.querySelector('select') as HTMLSelectElement;
    expect(select.value, 'склад подставился сам').toBe('');

    const buttons = [...document.querySelectorAll('button')]
      .filter((b) => /Продолжить начатую|Открыть новую/.test(b.textContent ?? ''));
    expect(buttons).toHaveLength(2);
    for (const button of buttons) {
      expect(button.hasAttribute('disabled'), `«${button.textContent}» нажимается без склада`)
        .toBe(true);
    }
  });
});

function reference() {
  return {
    loadedAt: new Date().toISOString(),
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }, { id: 3, name: '54 YARD', cells: [] }],
    supplies: [],
    donors: [],
    partNames: [],
  } as never;
}
