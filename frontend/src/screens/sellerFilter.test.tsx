import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Продавец сужает найденное отбором, и отбор уходит в базу.
 *
 * <p><b>Что было.</b> «Фара» на живом складе — 181 позиция, показано
 * пятьдесят, и все пятьдесят строк одинаковы на вид: «Фара Toyota Camry
 * 2007 (б/у)» по 1 500 ₽, отличаются только кодом. Уточнять нечем: поле
 * одно, отбора нет, сортировки нет. Продавец, которому по телефону сказали
 * «фара на Ниссан», либо угадывает, какими словами это записано на складе,
 * либо листает.
 *
 * <p><b>Что проверяется.</b> Не «выбор в списке меняет строки», а то, что
 * отбор доходит до сервера: ниссановская фара, которой в первых показанных
 * строках нет вовсе, обязана найтись. Отбор, применённый к показанному,
 * ответил бы пустотой при полной полке ниссановских фар — и это ровно
 * та ошибка, ради которой задача заведена.
 */
describe('отбор в поиске продавца', () => {
  /** Куда сходил экран — по этому и видно, сузил он в базе или у себя. */
  let asked: string[] = [];

  beforeEach(() => {
    asked = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/parts/stock')) {
        asked.push(url);
        const params = new URLSearchParams(url.slice(url.indexOf('?')));
        return json(answer(params));
      }
      if (url.includes('/api/organization/warehouses')) {
        return json([{
          id: 2, branchId: 1, name: 'Ткацкая', branchName: 'Филиал', cells: 3,
        }]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('находит марку, которой нет в показанных строках', async () => {
    render(<SellerScreen canSell role="SELLER" company="test" memberId={1} />);
    await search();

    // До отбора видно только тойотовские: список обрезан, ниссановская
    // осталась за ним.
    expect(screen.getByText(/Показаны первые 2 из 3/)).toBeTruthy();
    expect(screen.queryByText(/Фара Nissan Almera/),
      'проверка бессмысленна, если искомое и так показано').toBeNull();

    fireEvent.change(screen.getByLabelText('Марка'), { target: { value: 'Nissan' } });

    await waitFor(() => expect(screen.getByText(/Фара Nissan Almera/)).toBeTruthy());
    expect(asked.some((url) => url.includes('brand=Nissan')),
      'отбор не дошёл до сервера: экран сузил показанные строки, '
      + 'и ниссановская фара осталась за списком').toBe(true);
    // Счётчик говорит про новую выдачу, а не про прежнюю: «первые 50 из 181»
    // после отбора — это обещание, что за списком ещё что-то есть.
    expect(screen.getByText('Найдено 1')).toBeTruthy();
  });

  it('снимает отбор одним действием', async () => {
    render(<SellerScreen canSell role="SELLER" company="test" memberId={1} />);
    await search();

    fireEvent.change(screen.getByLabelText('Марка'), { target: { value: 'Nissan' } });
    await waitFor(() => expect(screen.getByText(/Фара Nissan Almera/)).toBeTruthy());

    fireEvent.click(screen.getByText('Сбросить отбор'));

    await waitFor(() => expect(screen.getByText(/Показаны первые 2 из 3/)).toBeTruthy());
  });

  /**
   * Отбор, под который ничего не подходит, объясняется словами — и сам
   * остаётся на экране.
   *
   * <p>Спрятанный вместе со строками, он запирает продавца: выдача пуста,
   * почему — не написано, а снять отбор нечем. Ровно это уже случилось
   * на витрине склада, где отборы снимались только в шапке таблицы.
   */
  it('пустую выдачу объясняет словами и оставляет отбор снимаемым', async () => {
    render(<SellerScreen canSell role="SELLER" company="test" memberId={1} />);
    await search();

    fireEvent.change(screen.getByLabelText('Сторона'), { target: { value: 'RIGHT' } });

    await waitFor(() => expect(screen.getByText('Ничего не найдено')).toBeTruthy());
    expect(screen.getByText('Сбросить отбор'),
      'снять отбор при пустой выдаче нечем — выйти можно только перезагрузкой')
      .toBeTruthy();
  });

  async function search(): Promise<void> {
    const input = document.querySelector('input') as HTMLInputElement;
    setNative(input, 'фара');
    fireEvent.click([...document.querySelectorAll('button')]
      .find((b) => b.textContent === 'Найти')!);
    await waitFor(() => expect(document.querySelectorAll('.stock-row').length)
      .toBeGreaterThan(0));
  }
});

/**
 * Сервер, отбирающий по-настоящему: ниссановская фара за пределом выдачи,
 * и достать её можно только запросом с отбором.
 */
function answer(params: URLSearchParams): unknown {
  const facets = {
    vehicles: [
      { brand: 'Nissan', model: 'Almera' },
      { brand: 'Toyota', model: 'Camry' },
    ],
    grades: ['б/у', 'отличное'],
  };
  if (params.get('brand') === 'Nissan') {
    return { total: 1, facets, rows: [row(3, 'Фара Nissan Almera 2011 прав.')] };
  }
  if (params.get('side') === 'RIGHT') {
    return { total: 0, facets, rows: [] };
  }
  return {
    total: 3,
    facets,
    rows: [row(1, 'Фара Toyota Camry 2007 лев.'), row(2, 'Фара Toyota Camry 2010 лев.')],
  };
}

function row(id: number, title: string): unknown {
  return {
    partId: id, publicCode: `A-${id}`, title, price: '1500', status: 'IN_STOCK',
    warehouseId: 2, warehouseName: 'Ткацкая', cellCode: null,
    qty: '1', qtyReserved: '0', qtyAvailable: '1',
  };
}

/** React слушает нативный сеттер, а не присваивание value. */
function setNative(input: HTMLInputElement, value: string): void {
  const setter = Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype, 'value')!.set!;
  setter.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
