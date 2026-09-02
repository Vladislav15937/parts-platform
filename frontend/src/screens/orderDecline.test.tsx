import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { OrdersScreen } from './OrdersScreen';

/**
 * Отклонить можно любой заказ, а не только необеспеченный.
 *
 * <p><b>Зачем.</b> Кнопка отказа показывалась только черновику — заказу,
 * который нечем закрыть. А отказываются и от обеспеченных: покупатель
 * передумал, деталь при осмотре оказалась хуже объявления, её не нашли
 * на полке. По защищённой сделке продавец обязан ответить площадке,
 * и если ответить нечем, он делает это в кабинете Дрома — а у нас заказ
 * остаётся в очереди «ждут ответа» навсегда и держит резерв, то есть
 * деталь заблокирована для продажи неизвестно насколько.
 *
 * <p>Половину этой болезни уже лечили: экран называл отказ («заказ придётся
 * отклонить») и не давал его сделать. Вторая половина осталась.
 */
describe('отказ по заказу с площадки', () => {
  let cancelled: Array<{ url: string; body: string }>;

  beforeEach(() => {
    cancelled = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/cancel')) {
        cancelled.push({ url, body: String(init?.body ?? '') });
        return json({});
      }
      if (url.includes('awaiting-reply')) {
        return json([order(1, 'RESERVED', 'СНК-1'), order(2, 'DRAFT', 'СНК-2')]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('обеспеченный заказ тоже можно отклонить', async () => {
    render(<OrdersScreen canSell />);
    await waitFor(() => expect(document.querySelectorAll('.card').length).toBe(2));

    const buttons = [...document.querySelectorAll('.card')]
      .map((card) => [...card.querySelectorAll('button')].map((b) => b.textContent));
    // У обеспеченного: подтвердить и отклонить. Отказ вторым и приглушённой
    // кнопкой — обычное действие тут подтверждение.
    expect(buttons[0], 'обеспеченный заказ отклонить нечем')
      .toEqual(['Подтвердил площадке', 'Отклонить заказ']);
    expect(buttons[1]).toEqual(['Отклонить заказ']);

    const first = document.querySelectorAll('.card')[0]!;
    const decline = [...first.querySelectorAll('button')]
      .find((b) => b.textContent === 'Отклонить заказ')!;
    // Второе нажатие вместо окна: заказ оплачен покупателем, и отказ
    // возвращает ему деньги.
    fireEvent.click(decline);
    await waitFor(() => expect(decline.textContent).toBe('Точно отклонить?'));
    fireEvent.click(decline);

    await waitFor(() => expect(cancelled).toHaveLength(1));
    // Причина уезжает в историю документа: «нечем обеспечить» и «покупатель
    // отказался» — разные вещи, а разбирают историю через недели.
    expect(cancelled[0]!.body).toContain('Отказ по заказу с площадки');
    expect(cancelled[0]!.body).not.toContain('Обеспечить нечем');
  });
});

function order(id: number, status: string, no: string) {
  return {
    id, number: id, customerId: null, managerId: null, status,
    reservedUntil: null, totalAmount: '850', paidAmount: '0', debt: '850',
    createdAt: '2026-08-08T10:00:00Z', issuedAt: null, marketplace: 'DROM',
    externalOrderNo: no, replyDeadline: '2026-08-11T10:00:00Z',
    orderAcceptedAt: null, deliveryNote: null,
    items: [{ id, partId: 5, title: 'Тросик багажника', quantity: '1',
              price: '850', status: 'RESERVED', warehouseId: 2 }],
    services: [],
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
