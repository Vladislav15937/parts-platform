import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { ReportsScreen } from './ReportsScreen';

/**
 * Проданные позиции строками.
 *
 * <p>Стережёт то же, что и блок «Платежи по источникам»: <b>экран прячет
 * строки не хуже запроса</b>. Подвал сервер считает независимо от строк —
 * по всему отбору, — и сложенная колонка «Цена продажи» обязана дать то же
 * число. Спрячь экран строку без себестоимости («нечего показывать»
 * выглядит невинной правкой) — и глазами видно 2 400 при подвале 3 200,
 * а сервер этого не заметит вовсе: он отдал все три строки.
 *
 * <p>Вторая проверка — <b>скидка видна</b>. Прежняя цена рисуется
 * зачёркнутой рядом с ценой продажи, и только когда она отличается:
 * зачёркнутое равное число читается как ошибка.
 */
describe('проданные позиции на экране отчётов', () => {
  const SOLD = {
    rows: [
      {
        itemId: 1, soldAt: '2026-09-05T10:00:00Z', dealId: 9, dealNumber: 1274,
        partId: 11, publicCode: 'A1B2C3', title: 'Фара', condition: 'б/у',
        price: 1200, listPrice: 1500, quantity: 1, costPrice: 1000, profit: 200,
        warehouse: 'Ткацкая', manager: 'Иван Продавцов',
        supplyNumber: 'К-9', donorCode: '500',
      },
      {
        itemId: 2, soldAt: '2026-09-05T11:00:00Z', dealId: 10, dealNumber: 1275,
        partId: 12, publicCode: 'D4E5F6', title: 'Дверь', condition: 'б/у',
        price: 1200, listPrice: 1200, quantity: 1, costPrice: 700, profit: 500,
        warehouse: 'Ткацкая', manager: 'Иван Продавцов',
        supplyNumber: null, donorCode: '350',
      },
      // Без закупочной цены: так приезжает склад из чужой таблицы.
      {
        itemId: 3, soldAt: '2026-09-05T12:00:00Z', dealId: 11, dealNumber: 1276,
        partId: 13, publicCode: 'G7H8I9', title: 'Стекло', condition: 'б/у',
        price: 800, listPrice: 800, quantity: 1, costPrice: null, profit: null,
        warehouse: 'Дальний', manager: 'Пётр Сменщиков',
        supplyNumber: null, donorCode: '500',
      },
    ],
    totals: {
      items: 3, quantity: 3, revenue: 3200, cost: 1700, profit: 700, withoutCost: 1,
    },
    nextAfter: null,
    managers: [{ id: 1, name: 'Иван Продавцов' }, { id: 2, name: 'Пётр Сменщиков' }],
  };

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/intake/donors')) {
        return json([]);
      }
      if (url.includes('/organization/warehouses')) {
        return json([]);
      }
      if (url.includes('/reports/sold-items')) {
        return json(SOLD);
      }
      if (url.includes('/reports/summary')) {
        return json({
          parts: { qty: 0, amount: 0 },
          wheels: { qty: 0, amount: 0 },
          deals: { count: 0, amount: 0, prepaid: 0 },
        });
      }
      if (url.includes('/reports/customers')) {
        return json({
          totals: { advances: 0, withAdvance: 0, debts: 0, withDebt: 0,
                    customers: 0, problems: [] },
          rows: [],
        });
      }
      if (url.includes('/reports/donors')) {
        return json({ totals: { donors: 0, totalCost: 0, revenue: 0, stockValue: 0 }, rows: [] });
      }
      if (url.includes('/reports/payments')) {
        return json({
          month: '2026-08', rows: [],
          totals: { payments: 0, incoming: 0, outgoing: 0, total: 0 },
        });
      }
      return json({ month: '2026-08', rows: [] });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('сложенная колонка «Цена продажи» даёт выручку подвала', async () => {
    render(<ReportsScreen canRead />);
    await waitFor(() => expect(screen.getByText('Фара')).toBeTruthy());

    const shown = prices().reduce((sum, value) => sum + value, 0);

    // Подвал сервер посчитал независимо от строк — по всему отбору.
    // Разойдись они, отчёт отвечает на один вопрос двумя числами,
    // и какое верное, по экрану не понять.
    expect(shown, 'сумма показанных цен не сходится с подвалом').toBe(3200);
    expect(footer()).toContain('проданона3200');
    expect(footer()).toContain('3товара(3шт.)');
    expect(footer()).toContain('себестоимость1700');
    expect(footer()).toContain('выгода700');
  });

  it('у проданного со скидкой прежняя цена зачёркнута, у остального её нет',
    async () => {
      render(<ReportsScreen canRead />);
      await waitFor(() => expect(screen.getByText('Фара')).toBeTruthy());

      // «Продали за 1 200» и «продали за 1 500 со скидкой 300» без второго
      // числа с экрана выглядят одинаково.
      const struck = Array.from(document.querySelectorAll('s'))
        .map((node) => (node.textContent ?? '').replace(/\s/g, ''));
      expect(struck, 'прежней цены на экране нет — скидку не увидеть')
        .toContain('1500₽');
      // Ровно одна: у двух других строк цена не менялась, и зачёркнутое
      // равное число читается как ошибка.
      expect(struck).toHaveLength(1);
    });

  it('строка без закупки показывает прочерк, и подвал говорит, сколько таких',
    async () => {
      render(<ReportsScreen canRead />);
      await waitFor(() => expect(screen.getByText('Стекло')).toBeTruthy());

      const glass = screen.getByText('Стекло').closest('tr')!;
      const cells = Array.from(glass.querySelectorAll('td'))
        .map((cell) => (cell.textContent ?? '').trim());
      // Прочерк, а не ноль: «закупки не было» и «продали в ноль» —
      // разные утверждения, и второе владелец читает как убыток.
      expect(cells).toContain('—');
      expect(cells).not.toContain('0 ₽');

      expect(screen.getByText(/Позиций без закупочной цены: 1/)).toBeTruthy();
    });
});

/**
 * Цены продажи из колонки — числами.
 *
 * <p>Берётся первый узел клетки: под ценой лежит зачёркнутая прежняя,
 * и текст клетки целиком дал бы два числа слитно.
 */
function prices(): number[] {
  const table = screen.getByText('Цена продажи').closest('table')!;
  const at = Array.from(table.querySelectorAll('thead th'))
    .findIndex((cell) => cell.textContent === 'Цена продажи');
  return Array.from(table.querySelectorAll('tbody tr')).map((row) => {
    const cell = row.querySelectorAll('td')[at]!;
    return Number((cell.firstChild?.textContent ?? '').replace(/\D/g, ''));
  });
}

/** Подвал без пробелов: разряды разделяет неразрывный пробел. */
function footer(): string {
  return (screen.getByText(/продано на/).textContent ?? '').replace(/\s/g, '');
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
