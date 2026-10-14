import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { WheelsScreen } from './WheelsScreen';
import type { Wheel } from '../inventory/wheels';

/**
 * «Штук в комплекте» на вкладке «Шины и диски» (задача 0291).
 *
 * <p>Перенесённый с Дрома комплект — одна позиция, продаваемая только целиком,
 * и цена у неё названа за все колёса: 45 000 — это за четыре шины, а не за одну.
 * Число штук — единственное, по чему владелец это видит, поэтому проверяется
 * то, что видно ему: колонка в составе по умолчанию с числом под заголовком
 * и строка в карточке. Ищется по заголовку, а не по тексту где-нибудь
 * на странице: «4» на вкладке стоит и в остатке, и в диаметре.
 */
describe('штук в комплекте на вкладке «Шины и диски»', () => {
  function wheel(): Wheel {
    return {
      id: 7, number: 347, publicCode: 'W1A2B3C4D5E6',
      title: 'Автошина Dunlop Grandtrek PT5 235/55 R19',
      price: 45000, status: 'IN_STOCK', qty: 1,
      kind: 'TYRE', setNo: null, setPieces: 3, diameter: 19, tyreWidth: 265, tyreHeight: 65,
      construction: 'R', tyreType: 'Обычная', season: 'SUMMER', wearMm: 7.6,
      madeYear: 2023, discType: null, discWidth: null, offsetMm: null,
      boltPattern: null, hubBore: null,
      brand: 'Dunlop', model: 'Grandtrek PT3', discBrand: null, discModel: null,
      markingType: 'METRIC', treadType: null, runFlat: null, lightTruck: null,
      speedIndex: null, loadIndex: null,
      partName: '', condition: 'USED', supply: null, donorCode: null, oem: null,
      description: null, note: null, section: null, published: true,
      barcode: null, legacyCode: null, photoCount: 0,
      createdAt: null, updatedAt: null, updatedByName: null,
      priceChangedAt: null, priceChangedByName: null, stock: { '1': 1 },
    };
  }

  beforeEach(() => {
    // Выбор колонок запоминается; тест смотрит состав по умолчанию.
    try { localStorage.clear(); } catch { /* нет хранилища — и так умолчание */ }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/wheels')) {
        return json({
          total: 1,
          warehouses: [{ id: 1, name: 'Исакова' }],
          filterable: [],
          rows: [{ wheel: wheel(), photoUrl: null }],
        });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('колонка «Штук в комплекте» видна по умолчанию, и под ней число штук этой позиции', async () => {
    render(<WheelsScreen canIntake={false} role="OWNER" />);
    await waitFor(() => expect(screen.getByText('W1A2B3C4D5E6')).toBeTruthy());

    const headers = Array.from(document.querySelectorAll('thead th'))
      .map((cell) => (cell.textContent ?? '').trim());
    const at = headers.findIndex((title) => title.startsWith('Штук в комплекте'));
    expect(at, `колонки «Штук в комплекте» на вкладке нет: ${headers.join(' · ')}`)
      .toBeGreaterThanOrEqual(0);

    const cells = Array.from(document.querySelectorAll('tbody tr td'))
      .map((cell) => (cell.textContent ?? '').trim());
    expect(cells[at], 'в колонке «Штук в комплекте» не число штук позиции').toBe('3');
  });

  it('в карточке строка «Штук в комплекте» — «3 шт»', async () => {
    render(<WheelsScreen canIntake={false} role="OWNER" />);
    await waitFor(() => expect(screen.getByText('W1A2B3C4D5E6')).toBeTruthy());

    fireEvent.click(screen.getByText('W1A2B3C4D5E6').closest('tr') as HTMLElement);
    await waitFor(() => expect(document.querySelector('dl')).not.toBeNull());

    const pairs = Array.from(document.querySelectorAll('dt')).map((dt) => [
      (dt.textContent ?? '').trim(),
      (dt.nextElementSibling?.textContent ?? '').trim(),
    ]);
    const found = pairs.find(([title]) => title === 'Штук в комплекте');
    expect(found, `строки «Штук в комплекте» в карточке нет: ${pairs.map(([t]) => t).join(' · ')}`)
      .toBeDefined();
    expect(found?.[1], 'в строке «Штук в комплекте» не число штук позиции').toBe('3 шт');
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
