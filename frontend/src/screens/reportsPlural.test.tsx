import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { ReportsScreen } from './ReportsScreen';

/**
 * Числа на экране денег склоняют существительное.
 *
 * <p>«Деньги не сходятся — 1 расхождений» и «у 1 клиентов» стоят рядом
 * с суммами, которым владелец должен верить: на экране, где он сверяет
 * расчёты с клиентами, небрежность в тексте подрывает доверие ко всему
 * остальному. Одно расхождение — обычное число: их и бывает одно.
 */
describe('склонение на экране отчётов', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      // Список машин для разреза по машине и партии приезжает массивом:
      // подсунуть объект значит уронить экран там, где в жизни он работает.
      if (url.includes('/intake/donors')) {
        return json([]);
      }
      if (url.includes('/reports/customers')) {
        return json({
          totals: {
            advances: 1400, withAdvance: 1, debts: 6500, withDebt: 1,
            problems: [{ problem: 'сделка отменена, оплата не возвращена',
                         dealId: 8, amount: 500, customerId: 1 }],
          },
          rows: [],
        });
      }
      if (url.includes('/reports/managers')) {
        return json({ month: '2026-08', rows: [] });
      }
      if (url.includes('/reports/sources')) {
        return json({ month: '2026-08', rows: [] });
      }
      // Платежи по источникам едут с итогом — общий ответ ниже его не несёт.
      if (url.includes('/reports/payments')) {
        return json({
          month: '2026-08', rows: [],
          totals: { payments: 0, incoming: 0, outgoing: 0, total: 0 },
        });
      }
      if (url.includes('/reports/summary')) {
        return json({
          parts: { qty: 0, amount: 0 },
          wheels: { qty: 0, amount: 0 },
          deals: { count: 0, amount: 0, prepaid: 0 },
        });
      }
      // Склады для отбора проданных позиций приезжают массивом:
      // подсунуть объект значит уронить экран там, где он работает.
      if (url.includes('/organization/warehouses')) {
        return json([]);
      }
      // Проданные позиции: свой итог и свой список продавцов. Общий ответ
      // ниже отдаёт строки окупаемости машин, и экран падал бы на «managers
      // не массив» — из чужого файла и с чужим стеком.
      if (url.includes('/reports/sold-items')) {
        return json({
          rows: [], nextAfter: null, managers: [],
          totals: { items: 0, quantity: 0, revenue: 0, cost: 0, profit: 0, withoutCost: 0 },
        });
      }
      return json({ totals: { donors: 0, totalCost: 0, revenue: 0, stockValue: 0 }, rows: [] });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('одно расхождение и один клиент склоняются верно', async () => {
    render(<ReportsScreen canRead />);

    await waitFor(() => expect(screen.getByText(/Деньги не сходятся/)).toBeTruthy());
    expect(screen.getByText(/Деньги не сходятся/).textContent)
      .toContain('1 расхождение');
    expect(screen.getByText(/Авансов/).textContent).toContain('у 1 клиента');
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
