import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ReportsScreen } from './ReportsScreen';

/**
 * Графики окупаемости во времени: окно поверх разреза по машине.
 *
 * <p>Стережёт три вещи, и все три — про то, что владелец читает глазами.
 *
 * <p>Первая: <b>окно вообще открывается и не уводит с экрана</b>. Разрез
 * с выбранной машиной обязан остаться под ним — за графиком приходят,
 * чтобы вернуться к позициям.
 *
 * <p>Вторая: <b>ряды названы дословно</b>. «Планируемая сумма», «Продано
 * на сумму», «Себестоимость проданных», «Себестоимость всех» — слова
 * из критерия приёмки; переименуй любой, и владелец, пришедший от
 * ориентира, перестанет узнавать свой график.
 *
 * <p>Третья, и она про правду экрана: <b>машина без продаж рисует график,
 * а не «данных нет»</b>. Вложено-то в неё было, и линия вложенного —
 * это ровно то, ради чего свежую машину и открывают.
 */
describe('графики окупаемости на экране отчётов', () => {
  /** Продано в марте на 60 000 и в апреле ещё на 60 000 при затратах 100 000. */
  const CHART = {
    points: [
      {
        month: '2026-03',
        planned: 140000,
        revenue: 60000,
        soldCost: 700,
        totalCost: 100000,
        monthRevenue: 60000,
      },
      {
        month: '2026-04',
        planned: 140000,
        revenue: 120000,
        soldCost: 1400,
        totalCost: 100000,
        monthRevenue: 60000,
      },
    ],
  };

  /** Машина, в которую вложили и с которой ещё ничего не продали. */
  const FRESH = {
    points: [{
      month: '2026-09',
      planned: 500,
      revenue: 0,
      soldCost: 0,
      totalCost: 30000,
      monthRevenue: 0,
    }],
  };

  let chart = CHART;

  beforeEach(() => {
    chart = CHART;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/intake/donors')) {
        return json([{
          id: 7, code: '418', brand: 'Toyota', model: 'Camry', year: 2007,
          vin: null, status: 'DISMANTLING', note: null, location: null,
        }]);
      }
      if (url.includes('/reports/donors/7/chart')) {
        return json(chart);
      }
      if (url.includes('/reports/donors/7/items')) {
        return json({
          rows: [], totals: { items: 0, quantity: 0, amount: 0 }, nextAfter: null,
        });
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
      if (url.includes('/reports/payments')) {
        return json({
          month: '2026-09', rows: [],
          totals: { payments: 0, incoming: 0, outgoing: 0, total: 0 },
        });
      }
      if (url.includes('/organization/warehouses')) {
        return json([]);
      }
      if (url.includes('/reports/sold-items')) {
        return json({
          rows: [], nextAfter: null, managers: [],
          totals: { items: 0, quantity: 0, revenue: 0, cost: 0, profit: 0, withoutCost: 0 },
        });
      }
      return json({ month: '2026-09', rows: [] });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('«Графики» открывают окно с двумя графиками, не уводя с разреза', async () => {
    render(<ReportsScreen canRead />);
    await pickMachine();

    fireEvent.click(await screen.findByText('Графики'));

    // Два графика, сверху вниз, названные так же, как у ориентира.
    await waitFor(() => expect(screen.getByText('Выручка')).toBeTruthy());
    expect(screen.getByText('Продажи по месяцам')).toBeTruthy();

    // Разрез остался под окном: за графиком приходят, чтобы вернуться
    // к позициям, а не вместо них.
    expect(screen.getByText('Что поступило с машины и с поставки')).toBeTruthy();
  });

  it('ряды названы теми же словами, что у ориентира', async () => {
    render(<ReportsScreen canRead />);
    await pickMachine();
    fireEvent.click(await screen.findByText('Графики'));

    await waitFor(() => expect(screen.getByText('Планируемая сумма')).toBeTruthy());
    expect(screen.getByText('Себестоимость проданных')).toBeTruthy();
    expect(screen.getByText('Себестоимость всех')).toBeTruthy();
    // «Продано на сумму» стоит дважды: в легенде выручки и у столбцов.
    expect(screen.getAllByText('Продано на сумму').length).toBe(2);
  });

  it('момент окупаемости назван словами, а не только линиями', async () => {
    render(<ReportsScreen canRead />);
    await pickMachine();
    fireEvent.click(await screen.findByText('Графики'));

    // 60 000 в марте при вложенных 100 000 — ещё нет; 120 000 в апреле —
    // уже да. Месяц перехода и есть ответ, за которым сюда приходят.
    await waitFor(() => expect(text()).toContain('окупиласьвапр26'));
    expect(text()).toContain('Вложено100000₽·проданона120000₽');
  });

  it('машина без продаж рисует график, а не «данных нет»', async () => {
    chart = FRESH;
    render(<ReportsScreen canRead />);
    await pickMachine();
    fireEvent.click(await screen.findByText('Графики'));

    // Сначала дожидаемся причины — иначе проверка отсутствия проходит
    // на первом же тике, когда на экране ещё «Загружаем…».
    await waitFor(() => expect(screen.getByText('Выручка')).toBeTruthy());

    expect(text()).not.toContain('рисоватьнечего');
    // Вложено же — и это то, что владелец обязан увидеть.
    expect(text()).toContain('Вложено30000₽·проданона0₽·ещёнеокупилась');
  });

  async function pickMachine() {
    const machines = await screen.findAllByLabelText('Машина');
    const machine = machines[machines.length - 1]!;
    await waitFor(() => expect(machine.querySelectorAll('option')).toHaveLength(2));
    fireEvent.change(machine, { target: { value: '7' } });
  }
});

/** Весь текст экрана без пробелов: разряды разделяет неразрывный. */
function text(): string {
  return (document.body.textContent ?? '').replace(/\s/g, '');
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
