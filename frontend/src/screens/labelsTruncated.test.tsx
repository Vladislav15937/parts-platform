import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { LabelsScreen } from './LabelsScreen';

/**
 * Обрезанная выдача этикеток говорит, что она обрезана.
 *
 * <p><b>Зачем.</b> Поиск отдаёт полсотни строк, а «фара» на живом складе
 * находит 745. Экран брал первые пятьдесят и выбрасывал число найденного —
 * то самое, которое сервер отдаёт как раз для этого. Владелец печатал
 * пятьдесят этикеток и уходил к стеллажу уверенным, что промаркировал все
 * фары; о нехватке он узнавал у полки, с пачкой наклеек в руках.
 *
 * <p>Та же болезнь, что у поиска продавца («показаны первые 50 из 741»)
 * и в отчётах («50 машин из 441»), и лечится так же — числом на экране.
 */
describe('этикетки деталей', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/parts/stock')) {
        return json({
          total: 745,
          rows: Array.from({ length: 50 }, (_, i) => ({
            partId: i + 1,
            publicCode: `КОД${i + 1}`,
            title: `Фара № ${i + 1}`,
            price: 1000,
            status: 'IN_STOCK',
            warehouseId: 2,
            warehouseName: 'Ткацкая',
            cellCode: null,
            qty: 1,
            qtyReserved: 0,
            qtyAvailable: 1,
          })),
        });
      }
      if (url.includes('/warehouses')) {
        return json([{ id: 2, branchId: 1, name: 'Ткацкая', isActive: true }]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('говорит, сколько нашлось и сколько пойдёт в печать', async () => {
    render(<LabelsScreen canPrint />);

    fireEvent.click(screen.getByRole('button', { name: 'Детали' }));
    const field = await waitFor(() =>
      screen.getByPlaceholderText('фара камри, бампер приора'));
    fireEvent.change(field, { target: { value: 'фара' } });
    fireEvent.click(screen.getByRole('button', { name: 'Найти' }));

    await waitFor(() => expect(screen.getByText(/К печати: 50/)).toBeTruthy());
    // Пятьдесят этикеток из 745 — и об этом сказано до того, как человек
    // нажмёт «Печать».
    expect(screen.getByText(/Найдено 745, а на печать пойдут первые 50/),
      'экран молчит о том, что напечатает лишь часть').toBeTruthy();
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
