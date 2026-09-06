import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Обрезанный список говорит о том, что он обрезан.
 *
 * <p>Поиск продавца отдаёт полсотни строк, и на живом складе «фара» находит
 * семьсот сорок одну. Пока экран об этом молчал, продавец смотрел пятьдесят
 * и отвечал покупателю «нет такого» — с той же уверенностью, что и на пустой
 * выдаче, только тут он ещё и думал, что посмотрел всё.
 */
describe('обрезанная выдача продавца', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/parts/stock')) {
        return json({
          total: 741,
          rows: Array.from({ length: 50 }, (_, i) => ({
            partId: i + 1, publicCode: `A-${i}`, title: `Фара номер ${i}`, price: '1000',
            status: 'IN_STOCK', warehouseId: 2, warehouseName: 'Ткацкая',
            cellCode: null, qty: '1', qtyReserved: '0', qtyAvailable: '1',
          })),
        });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('называет, сколько осталось за списком', async () => {
    render(<SellerScreen canSell role="SELLER" company="test" memberId={1} />);

    const input = document.querySelector('input') as HTMLInputElement;
    setNative(input, 'фара');
    const search = [...document.querySelectorAll('button')]
      .find((b) => b.textContent === 'Найти');
    fireEvent.click(search!);

    await waitFor(() => expect(document.querySelectorAll('.stock-row').length).toBeGreaterThan(0));
    expect(screen.getByText(/Показаны первые 50 из 741/),
      'список обрезан, а экран об этом молчит').toBeTruthy();
  });
});

/** React слушает нативный сеттер, а не присваивание value. */
function setNative(input: HTMLInputElement, value: string): void {
  const setter = Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype, 'value')!.set!;
  setter.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
