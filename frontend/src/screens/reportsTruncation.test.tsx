import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { ReportsScreen } from './ReportsScreen';

/**
 * Обрезанный список отчёта говорит, что он обрезан.
 *
 * <p><b>Зачем.</b> У живого клиента 441 машина, а таблица окупаемости
 * показывает полсотни строк, и прямо над ней стоит «Машин: 441» — глаз
 * читает это как полноту и строки не пересчитывает. Сортировка идёт
 * от убыточных, поэтому окупившиеся машины не видны вовсе: владелец,
 * не найдя свою, решит, что её нет в системе. Та же болезнь, что была
 * у поиска продавца, где полсотни строк из 741 читались как «нет такого».
 *
 * <p>Расчёты с клиентами обрезаны тем же способом и молчали так же.
 */
describe('обрезанные списки отчётов', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/reports/customers')) {
        return json({
          totals: { advances: 0, withAdvance: 0, debts: 0, withDebt: 0,
                    customers: 137, problems: [] },
          rows: rows(100, (i) => ({ customerId: i, customerName: `Клиент ${i}`,
                                    phone: null, accountBalance: 0, debt: 10, unpaidDeals: 1 })),
        });
      }
      if (url.includes('/reports/managers') || url.includes('/reports/sources')) {
        return json({ month: '2026-08', rows: [] });
      }
      return json({
        totals: { donors: 441, totalCost: 230000, revenue: 6500, stockValue: 96308477 },
        rows: rows(50, (i) => ({ donorId: i, publicCode: `D${i}`, legacyCode: String(i),
                                 note: null, vin: null, year: 2007, totalCost: 0,
                                 revenue: 0, profit: 0, partsTotal: 10, partsSold: 0,
                                 stockValue: 1000 })),
      });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('окупаемость машин называет, сколько показано из скольких', async () => {
    render(<ReportsScreen canRead />);

    // Слово при первом числе: после «из» русский требует родительного
    // падежа от всего сочетания («из 441 машины», но «из 445 машин»),
    // и склонение по трём формам такого не знает.
    await waitFor(() => expect(
      screen.getByText(/Показаны 50 машин из 441/)).toBeTruthy());
    // И чем обрезано: без этого порядок строк выглядит случайным.
    expect(screen.getByText(/самые убыточные/)).toBeTruthy();
  });

  it('расчёты с клиентами — тоже', async () => {
    render(<ReportsScreen canRead />);

    await waitFor(() => expect(
      screen.getByText(/Показаны 100 клиентов из 137/)).toBeTruthy());
    expect(screen.getByText(/самые должные/)).toBeTruthy();
  });
});

function rows<T>(n: number, make: (i: number) => T): T[] {
  return Array.from({ length: n }, (_, i) => make(i + 1));
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
