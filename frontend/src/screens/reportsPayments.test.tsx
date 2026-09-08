import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { ReportsScreen } from './ReportsScreen';

/**
 * Платежи по источникам на экране отчётов — задача 0046.
 *
 * <p><b>Зачем.</b> Источник у платежа сохранялся с задачи 0024 и не читался
 * нигде: «сколько прошло наличными, сколько картой, сколько осталось в долг»
 * владелец мог узнать, только подняв каждую сделку.
 *
 * <p>Стережёт здесь то, чего не видит сервер: <b>экран не прячет строк</b>.
 * Сумма показанных «Приходов» обязана сойтись с итогом в подвале, а итог
 * сервер считает по всем платежам месяца независимо от строк. Спрячь экран
 * строку без названия («пустое имя — нечего показывать» выглядит невинно),
 * и подвал перестанет сходиться с тем, что видно глазами, — ровно та ошибка,
 * ради которой отчёт и заводился.
 */
describe('платежи по источникам на экране отчётов', () => {
  /** То, что бывает у клиента: две карты разных банков, архив и безымянный. */
  const FULL = {
    month: '2026-08',
    rows: [
      {
        sourceId: 2, sourceName: 'Карта Сбер', sourceType: 'BANK_ACCOUNT',
        archived: false, payments: 34, incoming: 402000, outgoing: 0, total: 402000,
      },
      {
        sourceId: 1, sourceName: 'ККМ', sourceType: 'CASH',
        archived: false, payments: 112, incoming: 315400, outgoing: 12500, total: 302900,
      },
      {
        sourceId: 3, sourceName: 'Карта Т-Банк', sourceType: 'BANK_ACCOUNT',
        archived: false, payments: 9, incoming: 78000, outgoing: 0, total: 78000,
      },
      {
        sourceId: 4, sourceName: 'Старая касса', sourceType: 'CASH',
        archived: true, payments: 2, incoming: 5000, outgoing: 0, total: 5000,
      },
      {
        sourceId: null, sourceName: null, sourceType: null,
        archived: false, payments: 7, incoming: 9600, outgoing: 0, total: 9600,
      },
    ],
    totals: { payments: 164, incoming: 810000, outgoing: 12500, total: 797500 },
  };

  let report: unknown = FULL;

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      // Список машин приезжает массивом: подсунуть объект значит уронить
      // экран там, где в жизни он работает.
      if (url.includes('/intake/donors')) {
        return json([]);
      }
      if (url.includes('/reports/payments')) {
        return json(report);
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
      return json({ month: '2026-08', rows: [] });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    report = FULL;
  });

  it('показывает все строки, и сумма приходов сходится с итогом в подвале', async () => {
    render(<ReportsScreen canRead />);

    await waitFor(() => expect(screen.getByText('Платежи по источникам')).toBeTruthy());

    // 1. Показаны все пять строк, включая безымянную и архивную. Пропавшая
    //    строка — это часть денег, которой на экране нет вовсе.
    expect(incoming()).toHaveLength(5);

    // 2. Главное. Сумма показанных приходов равна итогу, который сервер
    //    посчитал по всем платежам месяца. Спрячь экран любую строку —
    //    здесь останется 800 400 при подвале 810 000.
    expect(incoming().reduce((sum, value) => sum + value, 0))
      .toEqual(810000);
    expect(footer()).toContain('приход810000₽');

    // 3. Платёж без источника назван словами и виден отдельной строкой:
    //    это незаполненное поле, а не «прочее».
    expect(cells('источник не указан')).toEqual(['7', '9600₽', '—', '9600₽']);

    // 4. Две карты разных банков — две строки, хотя тип у них один.
    //    Схлопни их отчёт по типу, здесь стояла бы одна на 480 000.
    expect(cells('Карта Сбер')[1]).toEqual('402000₽');
    expect(cells('Карта Т-Банк')[1]).toEqual('78000₽');

    // 5. Архивный источник остаётся, и сказано, что он в архиве: иначе
    //    владелец пойдёт искать его в справочнике и не найдёт.
    expect(rowText('Старая касса')).toContain('в архиве');
  });

  it('месяц без платежей говорит об этом словами, а не пустым местом', async () => {
    report = { month: '2026-08', rows: [], totals: { payments: 0, incoming: 0, outgoing: 0, total: 0 } };
    render(<ReportsScreen canRead />);

    await waitFor(() => expect(screen.getByText('За этот месяц платежей не было.')).toBeTruthy());
  });
});

/** Числа колонки «Приход» — так, как их видит человек. */
function incoming(): number[] {
  return paymentRows().map((row) => {
    const value = row.querySelectorAll('td')[2]?.textContent ?? '';
    return Number(value.replace(/[^\d-]/g, ''));
  });
}

/** Строки таблицы платежей: у неё пять колонок, и первая — «Источник». */
function paymentRows(): HTMLTableRowElement[] {
  const head = screen.getByText('Источник').closest('table');
  if (head === null) {
    throw new Error('таблицы платежей по источникам на экране нет');
  }
  return Array.from(head.querySelectorAll('tbody tr'));
}

function rowText(label: string): string {
  const row = paymentRows().find((r) => (r.textContent ?? '').includes(label));
  if (row === undefined) {
    throw new Error(`строки «${label}» на экране нет`);
  }
  return row.textContent ?? '';
}

/** Значения строки без подписи и без пробелов: разряды разделяет неразрывный. */
function cells(label: string): string[] {
  const row = paymentRows().find((r) => (r.textContent ?? '').includes(label));
  if (row === undefined) {
    throw new Error(`строки «${label}» на экране нет`);
  }
  return Array.from(row.querySelectorAll('td'))
    .slice(1)
    .map((td) => (td.textContent ?? '').replace(/\s/g, ''));
}

function footer(): string {
  return (screen.getByText(/Всего за месяц/).textContent ?? '').replace(/\s/g, '');
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
