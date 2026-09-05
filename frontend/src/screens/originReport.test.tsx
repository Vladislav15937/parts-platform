import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ReportsScreen } from './ReportsScreen';

/**
 * Разрез по машине: что поступило, что продано, что списано и что лежит.
 *
 * <p>Стережёт две вещи, и обе — про то, что владелец читает глазами.
 *
 * <p>Первая: <b>подвал вкладки</b>. Слова взяты у системы, с которой к нам
 * переходят, — «162 товара (162 шт.): розничная стоимость — 1 168 350», —
 * и на «Продано» второе слово другое: там цена продажи, а не прайс.
 * Показать «розничную стоимость» над суммой продаж значит назвать одно
 * другим на экране, где сравнивают деньги.
 *
 * <p>Вторая: <b>пустая вкладка говорит словами</b>. У свежей машины
 * «Продано» пусто — это обычное дело, а не поломка, и молчащий экран
 * владелец читает как «отчёт не работает».
 */
describe('разрез по машине на экране отчётов', () => {
  const RECEIVED = {
    rows: [{
      partId: 11,
      publicCode: 'A1B2C3',
      kind: 'Фара',
      title: 'Фара передняя левая',
      quantity: 1,
      price: 7500,
      costPrice: 700,
      supplyNumber: 'К-1',
      date: '2026-08-30',
    }],
    totals: { items: 162, quantity: 162, amount: 1168350 },
    nextAfter: null,
  };

  const SOLD = {
    rows: [{
      partId: 12,
      publicCode: 'D4E5F6',
      kind: 'Бампер',
      title: 'Бампер передний',
      quantity: 1,
      price: 9000,
      costPrice: 700,
      supplyNumber: null,
      date: '2026-08-31',
    }],
    totals: { items: 24, quantity: 24, amount: 331716 },
    nextAfter: null,
  };

  const EMPTY_TAB = {
    rows: [],
    totals: { items: 0, quantity: 0, amount: 0 },
    nextAfter: null,
  };

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/intake/donors')) {
        return json([{
          id: 7, code: '418', brand: 'Toyota', model: 'Camry', year: 2007,
          vin: null, status: 'DISMANTLING', note: null, location: null,
        }]);
      }
      if (url.includes('/reports/donors/7/items')) {
        if (url.includes('tab=received')) {
          return json(RECEIVED);
        }
        return json(url.includes('tab=sold') ? SOLD : EMPTY_TAB);
      }
      if (url.includes('/reports/donors')) {
        return json({ totals: { donors: 1, totalCost: 0, revenue: 0, stockValue: 0 }, rows: [] });
      }
      if (url.includes('/reports/customers')) {
        return json({
          totals: { advances: 0, withAdvance: 0, debts: 0, withDebt: 0,
                    customers: 0, problems: [] },
          rows: [],
        });
      }
      if (url.includes('/reports/summary')) {
        return json({
          parts: { qty: 0, amount: 0 },
          wheels: { qty: 0, amount: 0 },
          deals: { count: 0, amount: 0, prepaid: 0 },
        });
      }
      return json({ month: '2026-08', rows: [] });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('подвал вкладки называет товары, штуки и сумму теми же словами', async () => {
    render(<ReportsScreen canRead />);
    await pickMachine();

    await waitFor(() => expect(text()).toContain(
      '162товара(162шт.):розничнаястоимость—1168350'));

    // Строка позиции: номер с витрины, вид детали, дата днём, а не в ISO.
    expect(screen.getByText('Фара передняя левая')).toBeTruthy();
    expect(screen.getByText('A1B2C3')).toBeTruthy();
    expect(screen.getByText('30.08.2026')).toBeTruthy();
  });

  it('на вкладке «Продано» сумма названа продажей, а не розничной стоимостью',
    async () => {
      render(<ReportsScreen canRead />);
      await pickMachine();
      await waitFor(() => expect(text()).toContain('розничнаястоимость—1168350'));

      fireEvent.click(screen.getByText('Продано'));

      // Слово другое, потому что и величина другая: это цена продажи,
      // а не прайс. Назвать её «розничной стоимостью» значит назвать одно
      // другим на экране, где сравнивают деньги.
      await waitFor(() => expect(text()).toContain('24товара(24шт.):продано—331716'));
      expect(text()).not.toContain('розничнаястоимость—331716');
    });

  it('пустая вкладка говорит словами, а не молчит', async () => {
    render(<ReportsScreen canRead />);
    await pickMachine();
    await waitFor(() => expect(text()).toContain('розничнаястоимость—1168350'));

    fireEvent.click(screen.getByText('Списано'));

    // У машины, с которой ничего не списывали, вкладка пуста — это обычное
    // дело, а не поломка. Молчащий экран владелец читает как «не работает».
    await waitFor(() => expect(screen.getByText('Ничего не найдено')).toBeTruthy());
  });

  async function pickMachine() {
    const machine = await screen.findByLabelText(/Машина/);
    await waitFor(() => expect(machine.querySelectorAll('option')).toHaveLength(2));
    fireEvent.change(machine, { target: { value: '7' } });
  }
});

/**
 * Весь текст экрана без пробелов.
 *
 * <p>Пробелы срезаются намеренно: разряды разделяет неразрывный пробел,
 * а подвал собран из нескольких узлов — сравнение с обычным пробелом
 * развалилось бы, ничего не сказав по делу.
 */
function text(): string {
  return (document.body.textContent ?? '').replace(/\s/g, '');
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
