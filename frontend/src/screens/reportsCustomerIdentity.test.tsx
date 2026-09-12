import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { ReportsScreen } from './ReportsScreen';
import { NO_CUSTOMER_NAME } from '../sales/dealStatus';

/**
 * Отчёт «Расчёты с клиентами» называет покупателя словом, а не номером
 * строки в базе (задача 0065).
 *
 * <p><b>Как это выглядело.</b> Клиенту без имени экран писал «клиент 42»,
 * где 42 — `customer.id`. Это отчёт про деньги: строка «клиент 42 — 18 500 ₽»
 * не отвечает на вопрос, ради которого отчёт открыли. Найти по этому номеру
 * нельзя никого — ни поиском, ни в разговоре; он меняется при переносе
 * и не переживает восстановление в другую схему.
 *
 * <p>Имя у контрагента пустое законно: `customer.name` в схеме
 * {@code NULL}-уемая, и такая строка приезжает из `v_customer_settlement`
 * как есть.
 *
 * <p><b>Почему проверка через экран, а не через функцию.</b> Та же причина,
 * что у `emptyCustomerWording.test.tsx`: разошлись экраны, каждый своим
 * `??`, — и пятым оказался этот, потому что написан он был третьим словом
 * и в перебор задачи 0062 по двум словам не попал. Поэтому проверка
 * сравнивает **клетку целиком** и называет экран: по упавшей проверке
 * обязано быть видно, куда идти.
 */
describe('отчёт «Расчёты с клиентами» не показывает номер строки базы', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('клиент без имени назван «Частным лицом», а не «клиент 42»', async () => {
    stubReports({
      customerId: 42, customerName: null, phone: null,
      accountBalance: 0, debt: 18500, unpaidDeals: 1, retail: false,
    });

    render(<ReportsScreen canRead />);
    await waitFor(() => expect(screen.getByText('Расчёты с клиентами')).toBeTruthy());
    await waitFor(() => expect(settlementCell()).not.toBe(''));

    expect(settlementCell(),
      'отчёт «Расчёты с клиентами»: клиент без имени назван не «Частным лицом»')
      .toBe(NO_CUSTOMER_NAME);
  });
});

/**
 * Первая клетка первой строки таблицы расчётов с клиентами.
 *
 * <p>Таблиц на экране несколько, и отличать их по порядку — значит ломать
 * проверку следующим блоком отчётов. Поэтому таблица ищется по своей шапке.
 * Пробелов между колонками в ней нет вовсе: перенос строки в JSX текстовым
 * узлом не становится, и `textContent` шапки склеен подряд.
 */
function settlementCell(): string {
  const table = [...document.querySelectorAll('table')]
    .find((node) => clean(node.querySelector('thead')?.textContent).replace(/\s/g, '')
      === 'КлиентАвансДолгСделок');
  if (table === undefined) {
    throw new Error('таблицы расчётов с клиентами на экране нет — блок не дорисовался');
  }
  return clean(table.querySelector('tbody tr td')?.textContent);
}

function clean(text: string | null | undefined): string {
  return (text ?? '').replace(/\s+/g, ' ').trim();
}

/**
 * Ответы сервера всем блокам экрана: отчёт тянет их разом, и блок, форму
 * ответа которому не описали, роняет экран целиком — вместе с проверяемым.
 */
function stubReports(row: unknown): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/reports/customers')) {
      return json({
        totals: { advances: 0, withAdvance: 0, debts: 18500, withDebt: 1,
                  customers: 1, problems: [] },
        rows: [row],
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
    // Машины и склады приезжают массивом: подсунуть объект значит уронить
    // экран там, где в жизни он работает.
    if (url.includes('/intake/donors') || url.includes('/organization/warehouses')) {
      return json([]);
    }
    return json({
      totals: { donors: 0, totalCost: 0, revenue: 0, stockValue: 0 },
      rows: [],
    });
  }));
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
