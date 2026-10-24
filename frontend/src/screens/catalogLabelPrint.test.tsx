import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { forgetCatalog } from '../access/rights';
import { rightsAs, rightsWith } from '../test/rights';
import { CatalogScreen } from './CatalogScreen';
import { LabelsScreen } from './LabelsScreen';

/**
 * Этикетки из списка склада: отметить строки → «Напечатать этикетки» → режим
 * (задача 0371).
 *
 * <p><b>Зачем.</b> Этикетки печатают пачкой при приходе контейнера, а печатать
 * их можно было только на экране «Этикетки» — поиском по одной позиции и по
 * одной наклейке на карточку. Пять одинаковых деталей в остатке — это пять
 * наклеек, а задать их число было нечем.
 *
 * <p>Число этикеток считается по нарисованному листу (`article.label`), а не
 * по подписи: подпись и лист собираются разными строками кода, и разойдись
 * они — человек прочёл бы «8», а принтер выдал бы три.
 */

/** Три позиции с остатком 1, 2 и 5 — из критерия приёмки дословно. */
const ROWS = [
  row(1, 'AAA111', 'Фара левая', 1),
  row(2, 'BBB222', 'Фонарь задний', 2),
  row(3, 'CCC333', 'Зеркало правое', 5),
];

let catalog: (url: URL) => unknown;
let requested: URL[];

describe('печать этикеток из списка склада', () => {
  beforeEach(() => {
    forgetCatalog();
    requested = [];
    catalog = () => ({ total: ROWS.length, warehouses: [], rows: ROWS, filterable: [] });
    vi.stubGlobal('print', vi.fn());
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), 'http://localhost');
      if (url.pathname === '/api/parts/catalog') {
        requested.push(url);
        return json(catalog(url));
      }
      if (url.pathname === '/api/parts/stock') {
        return json({
          total: 2,
          rows: [stockRow(1, 'AAA111'), stockRow(2, 'BBB222')],
        });
      }
      if (url.pathname.includes('/warehouses')) return json([]);
      if (url.pathname.startsWith('/api/me/settings')) return json({ value: null });
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('три строки с остатком 1, 2, 5: по одной — 3, по остатку — 8, по две — 6', async () => {
    render(<CatalogScreen rights={rightsAs('OWNER')} />);
    await markAll();

    expect(await printWith('По одной этикетке на товар'), 'по одной на товар').toBe(3);
    expect(window.print, 'печать не отправлена').toHaveBeenCalledTimes(1);
    expect(await printWith('По количеству товара в остатке'), 'по остатку').toBe(8);
    expect(await printWith('Указанное количество на товар', '2'), 'по две на товар').toBe(6);
  });

  it('окно называет число товаров и этикеток до отправки', async () => {
    render(<CatalogScreen rights={rightsAs('OWNER')} />);
    await markAll();
    fireEvent.click(button('Напечатать этикетки')!);

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Печать этикеток для 3 товаров')).toBeTruthy();
    // Умолчание ориентира — «по одной».
    expect((within(dialog).getByLabelText('По одной этикетке на товар') as HTMLInputElement)
      .checked).toBe(true);
    expect(within(dialog).getByText(/К печати: 3 этикетки/)).toBeTruthy();

    fireEvent.click(within(dialog).getByLabelText('По количеству товара в остатке'));
    expect(within(dialog).getByText(/К печати: 8 этикеток/)).toBeTruthy();

    // Поле числа появляется по выбору режима, а неверное число гасит кнопку
    // и называет причину.
    expect(within(dialog).queryByLabelText('Количество на товар')).toBeNull();
    fireEvent.click(within(dialog).getByLabelText('Указанное количество на товар'));
    fireEvent.change(within(dialog).getByLabelText('Количество на товар'),
      { target: { value: '0' } });
    expect(within(dialog).getByText('«Количество на товар» — целое число от 1.')).toBeTruthy();
    expect((within(dialog).getByRole('button', { name: 'Отправить в печать' }) as HTMLButtonElement)
      .disabled).toBe(true);

    fireEvent.click(within(dialog).getByRole('button', { name: 'Отменить' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(window.print).not.toHaveBeenCalled();
  });

  it('«Отменить выделение» снимает отметку, и печатать становится нечего', async () => {
    render(<CatalogScreen rights={rightsAs('OWNER')} />);
    await markAll();
    expect(screen.getByText('Выбрано 3')).toBeTruthy();

    fireEvent.click(button('Отменить выделение')!);

    expect(screen.getByText('Отметьте позиции')).toBeTruthy();
    expect(document.querySelectorAll('tbody input:checked').length).toBe(0);
    expect((button('Напечатать этикетки') as HTMLButtonElement).disabled,
      'печатать нечего, а кнопка жива').toBe(true);
  });

  it('позиция без номера товара и без остатка — названы, а не потеряны молча', async () => {
    catalog = () => ({
      total: 3, warehouses: [], filterable: [],
      rows: [ROWS[0], row(4, null, 'Без кода', 3), row(5, 'EEE555', 'Продано', 0)],
    });
    render(<CatalogScreen rights={rightsAs('OWNER')} />);
    await markAll();
    fireEvent.click(button('Напечатать этикетки')!);
    const dialog = screen.getByRole('dialog');

    expect(within(dialog).getByText(/У 1 позиции нет\s+номера товара/)).toBeTruthy();
    expect(within(dialog).getByText(/К печати: 2 этикетки/)).toBeTruthy();
    fireEvent.click(within(dialog).getByLabelText('По количеству товара в остатке'));
    expect(within(dialog).getByText(/У 1 позиции остатка\s+нет/)).toBeTruthy();
    expect(within(dialog).getByText(/К печати: 1 этикетка/)).toBeTruthy();
  });

  it('отметка с соседней страницы уходит в печать вместе с текущей', async () => {
    catalog = (url) => ({
      total: 60, warehouses: [], filterable: [],
      rows: url.searchParams.get('page') === '1' ? [ROWS[2]] : [ROWS[0], ROWS[1]],
    });
    render(<CatalogScreen rights={rightsAs('OWNER')} />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(2));
    fireEvent.click(button('Правка списком')!);
    fireEvent.click(document.querySelectorAll('tbody tr')[0]!);

    fireEvent.click(button('→')!);
    await waitFor(() => expect(screen.getByText('Зеркало правое')).toBeTruthy());
    fireEvent.click(document.querySelectorAll('tbody tr')[0]!);

    expect(await printWith('По количеству товара в остатке'),
      'отмеченное на первой странице выпало из печати').toBe(6);
  });

  it('весь отбор догружается страницами и печатается целиком', async () => {
    catalog = (url) => ({
      total: 3, warehouses: [], filterable: [],
      // Видна одна страница из двух строк — третья только в догрузке.
      rows: url.searchParams.get('size') === '200' ? ROWS : [ROWS[0], ROWS[1]],
    });
    render(<CatalogScreen rights={rightsAs('OWNER')} />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(2));
    fireEvent.click(button('Правка списком')!);
    fireEvent.click(button('Выбрать весь отбор (3)')!);

    expect(await printWith('По количеству товара в остатке')).toBe(8);
    expect(requested.some((url) => url.searchParams.get('size') === '200'),
      'весь отбор не догружен — печатается одна страница').toBe(true);
  });

  it('отбор больше предела — кнопка гаснет и просит сузить', async () => {
    catalog = () => ({ total: 1001, warehouses: [], filterable: [], rows: ROWS });
    render(<CatalogScreen rights={rightsAs('OWNER')} />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(3));
    fireEvent.click(button('Правка списком')!);
    fireEvent.click(button('Выбрать весь отбор (1 001)')!);

    expect((button('Напечатать этикетки') as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/Этикетки печатаются пачкой до 1.000 товаров — сузьте отбор/))
      .toBeTruthy();
  });

  it('кладовщик с правом этикеток отмечает строки и печатает без правки', async () => {
    render(<CatalogScreen rights={rightsWith('part.cell')} />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(3));
    fireEvent.click(button('Отметить позиции')!);
    for (const tr of document.querySelectorAll('tbody tr')) fireEvent.click(tr);

    expect(button('Изменить'), 'правка без права').toBeUndefined();
    expect(await printWith('По одной этикетке на товар')).toBe(3);
  });

  it('без права этикеток кнопка погашена и называет причину', async () => {
    render(<CatalogScreen rights={rightsWith('part.edit')} />);
    await markAll();
    const locked = button('Напечатать этикетки') as HTMLButtonElement;
    expect(locked.disabled).toBe(true);
    expect(locked.closest('.locked-action')?.textContent).toMatch(/Нет права/);
  });

  it('экран «Этикетки»: каждая этикетка печатается заданным числом копий', async () => {
    render(<LabelsScreen canPrint />);
    fireEvent.click(screen.getByRole('button', { name: 'Детали' }));
    fireEvent.change(screen.getByPlaceholderText('фара камри, № 347, бампер приора'),
      { target: { value: 'фара' } });
    fireEvent.click(screen.getByRole('button', { name: 'Найти' }));
    await waitFor(() => expect(button('Печать (2)')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Копий каждой этикетки'), { target: { value: '3' } });

    expect(button('Печать (6)'), 'число копий не дошло до печати').toBeTruthy();
    expect(document.querySelectorAll('article.label').length).toBe(6);
  });
});

/** Включить отметку и отметить все строки страницы. */
async function markAll(): Promise<void> {
  await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBeGreaterThan(0));
  fireEvent.click(button('Правка списком')!);
  for (const tr of document.querySelectorAll('tbody tr')) fireEvent.click(tr);
}

/**
 * Открыть окно, выбрать режим, отправить — и вернуть, сколько этикеток
 * на листе. Возвращается к складу, чтобы следующий режим шёл по тем же
 * отметкам.
 */
async function printWith(mode: string, given?: string): Promise<number> {
  fireEvent.click(button('Напечатать этикетки')!);
  const dialog = await waitFor(() => screen.getByRole('dialog'));
  fireEvent.click(within(dialog).getByLabelText(mode));
  if (given !== undefined) {
    fireEvent.change(within(dialog).getByLabelText('Количество на товар'),
      { target: { value: given } });
  }
  await waitFor(() => expect((within(dialog)
    .getByRole('button', { name: 'Отправить в печать' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(within(dialog).getByRole('button', { name: 'Отправить в печать' }));
  await waitFor(() => expect(button('Вернуться к складу')).toBeTruthy());
  const printed = document.querySelectorAll('article.label').length;
  fireEvent.click(button('Вернуться к складу')!);
  await waitFor(() => expect(document.querySelector('tbody tr')).toBeTruthy());
  return printed;
}

function row(id: number, code: string | null, title: string, qty: number) {
  return {
    id, number: 100 + id, code, title, qty, price: 1500, condition: 'USED',
    stock: { 1: qty }, reserved: {}, photoCount: 0,
  };
}

function stockRow(partId: number, publicCode: string) {
  return {
    partId, number: partId, publicCode, title: `Фара ${partId}`, price: 1000,
    status: 'IN_STOCK', warehouseId: 1, warehouseName: 'Ткацкая', cellCode: null,
    qty: 1, qtyReserved: 0, qtyAvailable: 1, expected: false,
  };
}

function button(text: string): HTMLElement | undefined {
  return [...document.querySelectorAll('button')].find((b) => b.textContent === text);
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
