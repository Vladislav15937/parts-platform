import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PaymentsScreen } from './PaymentsScreen';

/**
 * Реестр платежей — раздел «Платежи» (задача 0045).
 *
 * <p><b>Что стережёт.</b> Знак у строки. Сумма платежа в базе всегда
 * положительная, направление несёт отдельное поле — и стоит строке расхода
 * попасть в колонку «Приход», как экран покажет кассу больше, чем было,
 * а подвал (его считает сервер по всей выборке) останется прежним. Поэтому
 * проверка не смотрит на числа глазами, а **складывает показанное**: приход
 * минус расход по строкам обязан дать тот итог, который написан в подвале.
 * Проверено откатом — поменяв колонки местами в `Row`, получаем 8 000 против
 * 4 000 в подвале.
 *
 * <p>Второе: подвал считает по **всей выборке отбора**, а не по показанной
 * странице, и говорит об обрезке отдельной строкой. Число под списком,
 * посчитанное по видимым строкам, врало бы ровно на то, чего не видно.
 */
describe('реестр платежей', () => {
  /**
   * Три платежа, которые обязана показать касса, — те самые, что названы
   * в критерии приёмки: принятая оплата, возврат денег и пополнение
   * лицевого счёта. Плюс платёж без источника: до задачи 0024 способ
   * не писали вовсе, и у переехавшего клиента такова вся история.
   */
  const PAGE = {
    total: 3,
    income: 6000,
    expense: 2000,
    net: 4000,
    items: [
      {
        id: 3, paidAt: '2026-09-08T09:00:00Z', direction: 'IN', amount: 1000,
        comment: null, dealId: null, dealNumber: null,
        customerId: 5, customerName: 'Автосервис на Русской',
        sourceId: 1, sourceName: 'ККМ',
      },
      {
        id: 2, paidAt: '2026-09-08T08:30:00Z', direction: 'OUT', amount: 2000,
        comment: 'Возврат 4', dealId: 12, dealNumber: 8,
        customerId: 5, customerName: 'Автосервис на Русской',
        sourceId: 1, sourceName: 'ККМ',
      },
      {
        id: 1, paidAt: '2026-09-08T08:00:00Z', direction: 'IN', amount: 5000,
        comment: null, dealId: 12, dealNumber: 8,
        customerId: 5, customerName: 'Автосервис на Русской',
        sourceId: null, sourceName: null,
      },
    ],
  };

  let page: unknown = PAGE;
  let asked: string[] = [];

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      asked.push(String(input));
      return new Response(JSON.stringify(page), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    page = PAGE;
    asked = [];
  });

  it('приход и расход стоят каждый в своей колонке, и сумма сходится с итогом', async () => {
    render(<PaymentsScreen onOpenDeal={() => {}} />);

    await waitFor(() => expect(rows()).toHaveLength(3));

    // 1. Главное: сложенное по строкам сходится с итогом, который сервер
    //    посчитал своим запросом. Расход в колонке прихода даёт 8 000.
    expect(column(5) - column(6)).toEqual(4000);
    expect(footer()).toContain('итого4000₽');

    // 2. И сами колонки не переставлены местами: 6 000 принято, 2 000 отдано.
    expect(column(5)).toEqual(6000);
    expect(column(6)).toEqual(2000);
    expect(footer()).toContain('приход6000₽');
    expect(footer()).toContain('расход2000₽');

    // 3. Платёж без сделки — это пополнение счёта, и в колонке «По сделке»
    //    у него прочерк, а не чужой номер.
    expect(cells(0)[2]).toEqual('—');
    expect(cells(2)[2]).toEqual('8');

    // 4. Способ назван словом; не записанный — назван тоже, а не пустым
    //    местом: это незаполненное поле, а не «прочее».
    expect(cells(0)[3]).toEqual('ККМ');
    expect(cells(2)[3]).toEqual('Источникнеуказан');
  });

  it('подвал считает по всему отбору, а обрезка названа отдельной строкой', async () => {
    // Сервер нашёл 128 платежей, отдал три: подвал обязан говорить о всех.
    page = { ...PAGE, total: 128, income: 402000, expense: 12500, net: 389500 };
    render(<PaymentsScreen onOpenDeal={() => {}} />);

    await waitFor(() => expect(rows()).toHaveLength(3));

    expect(footer()).toContain('Платежей:128');
    expect(footer()).toContain('итого389500₽');
    expect(text()).toContain('Показаны первые 3 платежа из 128');
  });

  it('пустая выдача по отбору и пустая касса — разные слова', async () => {
    page = { total: 0, income: 0, expense: 0, net: 0, items: [] };
    render(<PaymentsScreen onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('Платежей ещё не было')).toBeTruthy());

    // Выбрана воронка — это уже отбор, и «платежей ещё не было» стало бы
    // неправдой: расходов нет, а приходы есть.
    fireEvent.click(screen.getByRole('button', { name: 'Расходные' }));
    await waitFor(() => expect(screen.getByText('По этому отбору платежей нет')).toBeTruthy());
    expect(asked.some((url) => url.includes('direction=OUT'))).toBe(true);
  });
});

function table(): HTMLTableElement {
  const found = screen.getByText('Способ оплаты').closest('table');
  if (found === null) {
    throw new Error('таблицы платежей на экране нет');
  }
  return found as HTMLTableElement;
}

function rows(): HTMLTableRowElement[] {
  return Array.from(table().querySelectorAll('tbody tr'));
}

/** Значения строки без пробелов: разряды разделяет неразрывный пробел. */
function cells(index: number): string[] {
  const row = rows()[index];
  if (row === undefined) {
    throw new Error(`строки ${index} на экране нет`);
  }
  return Array.from(row.querySelectorAll('td'))
    .map((td) => (td.textContent ?? '').replace(/\s/g, ''));
}

/** Сумма колонки так, как её сложил бы глазами человек. */
function column(index: number): number {
  return rows().reduce((sum, row) => {
    const value = row.querySelectorAll('td')[index]?.textContent ?? '';
    const digits = value.replace(/[^\d-]/g, '');
    return sum + (digits === '' ? 0 : Number(digits));
  }, 0);
}

function footer(): string {
  return (screen.getByText(/Платежей:/).textContent ?? '').replace(/\s/g, '');
}

function text(): string {
  return document.body.textContent ?? '';
}
