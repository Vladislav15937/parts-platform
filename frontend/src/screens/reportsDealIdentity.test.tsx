import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { ReportsScreen } from './ReportsScreen';

/**
 * Сверка расхождений называет сделку её номером, а не номером строки базы
 * (задача 0067).
 *
 * <p><b>Как это выглядело.</b> Блок «Деньги не сходятся» в отчёте «Расчёты
 * с клиентами» писал «· сделка 118», где 118 — `deal.id`. Это отчёт про
 * деньги: владелец, увидев расхождение, идёт искать **ту самую** сделку —
 * а по номеру строки базы её не найти ни поиском реестра, ни в разговоре
 * с продавцом. У сделки при этом свой номер есть с самого начала
 * (`deal.number`, им её и зовут), и в ответе его просто не было.
 *
 * <p><b>Почему проверка через экран, а не через тип.</b> Та же причина, что
 * у `reportsCustomerIdentity.test.tsx` (задача 0065): поле в ответе ничего
 * не обещает, пока экран его не читает, — а читал он соседнее. Поэтому
 * сравнивается текст строки целиком.
 *
 * <p><b>Номер и `id` в фикстуре разведены намеренно.</b> Совпади они —
 * проверка прошла бы и на старом коде, подставляющем `dealId`: строка
 * отдавала бы верное число по неверной причине. Тот же приём, которым
 * `PartNumberTest` сдвигает `part_number_seq`, а `OriginReportTest` —
 * номер позиции против её `id`.
 */
describe('отчёт про деньги называет сделку её номером', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('расхождение показывает «сделка №1274», а не номер строки базы', async () => {
    stubReports();

    render(<ReportsScreen canRead />);
    await waitFor(() => expect(screen.getByText(/Деньги не сходятся/)).toBeTruthy());

    const line = await waitFor(() => {
      const found = [...document.querySelectorAll('li')]
        .map((node) => (node.textContent ?? '').replace(/\s+/g, ' ').trim())
        .find((text) => text.includes('оплата не возвращена'));
      expect(found, 'строки расхождения на экране нет — блок не дорисовался')
        .toBeTypeOf('string');
      return found as string;
    });

    expect(line, 'отчёт не назвал сделку её номером')
      .toContain('сделка №1274');
    // Отрицательное утверждение обязательно: «номер назван» прошло бы
    // и тогда, когда рядом остался номер строки базы.
    expect(line, 'в отчёте остался внутренний номер строки базы (deal.id)')
      .not.toContain('сделка 8');
  });
});

/**
 * Ответы всем блокам экрана: отчёт тянет их разом, и блок, форму ответа
 * которому не описали, роняет экран целиком — вместе с проверяемым.
 */
function stubReports(): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/reports/customers')) {
      return json({
        totals: {
          advances: 0, withAdvance: 0, debts: 500, withDebt: 1, customers: 1,
          // dealId и dealNumber разные: см. javadoc.
          problems: [{
            customerId: 1, entryId: null, dealId: 8, dealNumber: 1274,
            problem: 'сделка отменена, оплата не возвращена', amount: 500,
          }],
        },
        rows: [],
      });
    }
    if (url.includes('/reports/managers') || url.includes('/reports/sources')
        || url.includes('/reports/payments')) {
      return json({
        month: '2026-09', rows: [],
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
    if (url.includes('/reports/sold-items')) {
      return json({
        rows: [], nextAfter: null, managers: [],
        totals: { items: 0, quantity: 0, revenue: 0, cost: 0, profit: 0, withoutCost: 0 },
      });
    }
    if (url.includes('/intake/donors') || url.includes('/organization/warehouses')) {
      return json([]);
    }
    return json({ totals: { donors: 0, totalCost: 0, revenue: 0, stockValue: 0 }, rows: [] });
  }));
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
