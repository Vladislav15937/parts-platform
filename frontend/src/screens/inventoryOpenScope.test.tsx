import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { InventoryScreen } from './InventoryScreen';

/**
 * Открытие пересчёта одной ячейки, а не всего склада.
 *
 * <p><b>Зачем.</b> У переезжающего клиента на складе 33 676 позиций, и один
 * документ на весь склад никто не закрывает: обход идёт неделями, а без
 * «Завершить подсчёт» нельзя провести расхождения. Задача 0019 даёт форме
 * открытия второе поле — ячейку, — и счётчик «Найдено товаров», который
 * пересчитывается при каждой смене склада или ячейки.
 *
 * <p>Проверяется то, что видит человек: счётчик меняется при выборе ячейки,
 * а запрос на открытие уходит с выбранным {@code cellId}. Разбор скана
 * детали и группировка строк по статусу проверены отдельно, в
 * {@code inventory/counts.test.ts} (чистые функции) и
 * {@code inventoryScanGroups.test.tsx} (экран).
 */
describe('выборка при открытии пересчёта', () => {
  let requests: { url: string; body: string }[];

  beforeEach(() => {
    requests = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requests.push({ url, body: String(init?.body ?? '') });

      if (url.startsWith('/api/inventory/count')) {
        // Ячейка А-01-1 — 5 позиций, весь склад — 312.
        const count = url.includes('cellId=10') ? 5 : 312;
        return json({ count });
      }
      if (url === '/api/inventory/sessions' && init?.method === 'POST') {
        return json({ id: 1, warehouseId: 2, status: 'OPEN', lines: 0, counted: 0 });
      }
      if (url.includes('/sessions/1/lines') || url.includes('/sessions/1/codes')) {
        return json([]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('счётчик меняется при выборе склада и ячейки', async () => {
    render(<InventoryScreen reference={reference()} onCount={vi.fn()} />);

    const selects = await waitFor(() => {
      const found = document.querySelectorAll('select');
      expect(found).toHaveLength(2);
      return found;
    });
    const warehouseSelect = selects[0] as HTMLSelectElement;
    const cellSelect = selects[1] as HTMLSelectElement;

    fireEvent.change(warehouseSelect, { target: { value: '2' } });
    await waitFor(() => expect(screen.getByText('Найдено товаров: 312')).toBeTruthy());

    fireEvent.change(cellSelect, { target: { value: '10' } });
    await waitFor(() => expect(screen.getByText('Найдено товаров: 5')).toBeTruthy());
  });

  it('«Открыть новую» уходит с выбранным cellId', async () => {
    render(<InventoryScreen reference={reference()} onCount={vi.fn()} />);

    const selects = await waitFor(() => {
      const found = document.querySelectorAll('select');
      expect(found).toHaveLength(2);
      return found;
    });
    fireEvent.change(selects[0]!, { target: { value: '2' } });
    fireEvent.change(selects[1]!, { target: { value: '10' } });
    await waitFor(() => expect(screen.getByText('Найдено товаров: 5')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Открыть новую' }));

    await waitFor(() => {
      const open = requests.find((r) => r.url === '/api/inventory/sessions');
      expect(open).toBeTruthy();
      expect(open?.body).toContain('"cellId":10');
    });
  });
});

function reference() {
  return {
    loadedAt: new Date().toISOString(),
    warehouses: [
      { id: 2, name: 'Ткацкая', cells: [{ id: 10, code: 'А-01-1', zone: null }] },
      { id: 3, name: '54 YARD', cells: [] },
    ],
    supplies: [],
    donors: [],
    partNames: [],
  } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
