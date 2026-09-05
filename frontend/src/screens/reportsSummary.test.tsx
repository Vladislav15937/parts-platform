import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { ReportsScreen } from './ReportsScreen';

/**
 * Сводка владельца: что лежит на складе и что висит в незакрытых сделках.
 *
 * <p><b>Зачем.</b> «Сколько у меня сейчас на складе в деньгах» — первый вопрос
 * владельца разборки, и до этого блока на него не отвечал ни один экран: все
 * четыре отчёта про прошлое.
 *
 * <p>Стережёт тут две вещи. Первая — колёса не сложены с запчастями: они
 * продаются сезоном, и владелец смотрит на них отдельно; сложенные, они
 * дают правдоподобное число, по которому ошибку не заметить. Вторая —
 * у пустого арендатора на экране нули, а не пустое место: только что
 * заведённая компания не должна выглядеть сломанной.
 */
describe('сводка на экране отчётов', () => {
  let summary: unknown = {
    parts: { qty: 35773, amount: 125622846 },
    wheels: { qty: 213, amount: 1374000 },
    deals: { count: 74, amount: 3873287, prepaid: 102510 },
  };

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/reports/summary')) {
        return json(summary);
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
    summary = {
      parts: { qty: 35773, amount: 125622846 },
      wheels: { qty: 213, amount: 1374000 },
      deals: { count: 74, amount: 3873287, prepaid: 102510 },
    };
  });

  it('склад и сделки в работе стоят шестью числами, колёса — своей строкой', async () => {
    render(<ReportsScreen canRead />);

    await waitFor(() => expect(screen.getByText('Остаток товара')).toBeTruthy());

    // Запчасти и колёса разными строками. Сложи их — вышло бы 35 986 штук
    // и 126 996 846 ₽: число выглядит правдоподобно, и ошибку в нём
    // не заметит никто.
    expect(cells('Запчасти')).toEqual(['35773шт.', '125622846₽']);
    expect(cells('Шины и диски')).toEqual(['213шт.', '1374000₽']);

    // Сделки в работе: сколько их, на сколько и сколько уже внесено.
    // Предоплаты отдельно от суммы — владелец смотрит на разницу.
    expect(cells('Количество')).toEqual(['74шт.']);
    expect(cells('На сумму')).toEqual(['3873287₽']);
    expect(cells('Сумма предоплат')).toEqual(['102510₽']);
  });

  it('у пустого арендатора это нули, а не пустое место', async () => {
    summary = {
      parts: { qty: 0, amount: 0 },
      wheels: { qty: 0, amount: 0 },
      deals: { count: 0, amount: 0, prepaid: 0 },
    };
    render(<ReportsScreen canRead />);

    await waitFor(() => expect(screen.getByText('Остаток товара')).toBeTruthy());

    // Только что заведённая компания: склад пуст, и это надо сказать нулём.
    // Пустое место владелец читает как поломку и идёт спрашивать, почему
    // отчёт не работает.
    expect(cells('Запчасти')).toEqual(['0шт.', '0₽']);
    expect(cells('Шины и диски')).toEqual(['0шт.', '0₽']);
    expect(cells('Количество')).toEqual(['0шт.']);
  });
});

/**
 * Значения строки таблицы, без подписи и без пробелов.
 *
 * <p>Пробелы срезаются намеренно: разряды разделяет неразрывный пробел,
 * и сравнение с обычным развалилось бы, ничего не сказав по делу.
 *
 * <p>Подпись ищется в ячейке, а не где придётся: «Количество» стоит и
 * заголовком колонки в остатке, и подписью строки в сделках — так эти два
 * блока названы у ориентира, и различать их обязана проверка, а не экран.
 */
function cells(label: string): string[] {
  const row = screen.getAllByText(label)
    .find((element) => element.tagName === 'TD')
    ?.closest('tr');
  if (row === null || row === undefined) {
    throw new Error(`строки «${label}» на экране нет`);
  }
  return Array.from(row.querySelectorAll('td'))
    .slice(1)
    .map((td) => (td.textContent ?? '').replace(/\s/g, ''));
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
