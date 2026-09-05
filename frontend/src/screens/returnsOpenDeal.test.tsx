import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Сделка, найденная в реестре возвратов, открывается на экране продажи.
 *
 * <p><b>Зачем.</b> Задача 0021: нажатие на номер сделки в колонке «По
 * сделке» обязано открыть ровно ту сделку, а не просто перевести
 * на вкладку продажи. `HomeScreen` передаёт найденный номер через
 * `openDealId`, а `SellerScreen` обязан по нему сходить за сделкой сам —
 * ссылку в реестре ведёт номер, а не полный объект.
 */
describe('открытие сделки из реестра возвратов', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('openDealId открывает карточку именно этой сделки', async () => {
    const onDealOpened = vi.fn();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/deals/77')) {
        return json({
          id: 77, number: 77, customerId: 1, managerId: 1, status: 'ISSUED',
          reservedUntil: null, totalAmount: '4000.00', paidAmount: '0',
          debt: '4000.00', createdAt: '2026-09-05T10:00:00Z', issuedAt: '2026-09-05T10:05:00Z',
          warehouseId: null, marketplace: null, externalOrderNo: null,
          replyDeadline: null, orderAcceptedAt: null, deliveryNote: null,
          items: [{ id: 1, partId: 1, title: 'Фара', quantity: '1', price: '4000.00',
                    discount: '0', warehouseId: 1, status: 'ISSUED' }],
          services: [],
        });
      }
      // Справочники, которые SellerScreen тянет при монтировании.
      return json([]);
    }));

    render(
      <SellerScreen canSell role="SELLER" openDealId={77} onDealOpened={onDealOpened} />,
    );

    await waitFor(() => expect(screen.getByText(/Сделка №77/)).toBeTruthy());
    expect(onDealOpened).toHaveBeenCalled();
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
