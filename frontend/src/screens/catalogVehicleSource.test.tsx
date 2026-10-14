import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';

/**
 * Клетка машины на складе говорит, откуда значение (задача 0290, п. 2).
 *
 * <p>У позиции без донора «Марка» и «Модель» — её своя машина из
 * применимости, и разобранное автоматически не должно читаться как
 * подтверждённый факт. Подсказка стоит на самой клетке — по наведению,
 * как её и видит владелец.
 */
describe('подсказка источника машины на складе', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/catalog/vehicles') || url.includes('/api/intake/donors')
          || url.includes('/values')) {
        return json([]);
      }
      return json({
        total: 2,
        warehouses: [],
        filterable: ['brand', 'model'],
        rows: [
          { id: 1, number: 1, code: 'A-1', title: 'Карданный вал Toyota Land Cruiser Prado 120',
            brand: 'Toyota', model: 'Land Cruiser Prado', vehicleSource: 'PARSED',
            vehicleSpans: 1, price: 1000, installationPrice: null, stock: {}, photoCount: 0 },
          { id: 2, number: 2, code: 'A-2', title: 'Фара Toyota Corolla',
            brand: 'Toyota', model: 'Corolla', vehicleSource: 'DONOR',
            price: 1000, installationPrice: null, stock: {}, photoCount: 0 },
        ],
      });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('применимость и донор подписаны по-разному', async () => {
    render(<CatalogScreen role="OWNER" />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(2));

    const cell = (text: string) => [...document.querySelectorAll('tbody td')]
      .find((td) => td.textContent === text);
    expect(cell('Land Cruiser Prado')?.getAttribute('title'))
      .toBe('По применимости, разобрана автоматически');
    expect(cell('Corolla')?.getAttribute('title')).toBe('По донору');
    // Клетки не про машину подсказки не получают.
    expect(cell('Фара Toyota Corolla')?.hasAttribute('title')).toBe(false);
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
