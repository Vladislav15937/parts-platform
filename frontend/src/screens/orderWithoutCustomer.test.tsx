import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Заказ с площадки принимается без клиента.
 *
 * <p>Покупателя площадка не называет: у всех заказов в базе `customer_id`
 * пуст, и сервер его не требует. А кнопка «Принять заказ» была заблокирована,
 * пока клиент не выбран, — то есть принять заказ с экрана было нельзя вовсе.
 * Продавец заводил фиктивного клиента, чтобы кнопка ожила, и в справочнике
 * появлялся «Дром»: мусор в данных и вранья в отчёте по клиентам.
 *
 * <p>Обычной продаже клиент по-прежнему нужен — она ведётся с человеком,
 * который стоит у прилавка, и без него некому выдавать товар.
 */
describe('заказ с площадки', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/parts/stock')) {
        // Выдача едет вместе с числом найденного: список обрезан
        // на полусотне, и экран обязан сказать, если за ним что-то осталось.
        return json({ total: 1, rows: [
          { partId: 1, publicCode: 'A-1', title: 'Бампер', price: '10000',
            status: 'IN_STOCK', warehouseId: 2, warehouseName: 'Ткацкая',
            cellCode: null, qty: '1', qtyReserved: '0', qtyAvailable: '1' }] });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('кнопка оживает от номера заказа, а не от клиента', async () => {
    const { container } = render(<SellerScreen canSell role="OWNER" />);

    const search = container.querySelector('input')!;
    fireEvent.input(search, { target: { value: 'бампер' } });
    fireEvent.click(findButton(container, 'Найти')!);

    await waitFor(() => expect(findButton(container, 'в сделку')).toBeTruthy());
    fireEvent.click(findButton(container, 'в сделку')!);

    // Обычная продажа без клиента невозможна — так и было задумано.
    await waitFor(() => expect(findButton(container, 'Оформить и отложить')).toBeTruthy());
    expect(findButton(container, 'Оформить и отложить')!.disabled).toBe(true);

    // Заказ с площадки — другое дело: клиента у него нет.
    const marketplace = [...container.querySelectorAll('select')]
      .find((s) => [...s.options].some((o) => o.textContent === 'нет, обычная продажа'))!;
    fireEvent.change(marketplace, { target: { value: 'DROM' } });

    await waitFor(() => expect(findButton(container, 'Принять заказ')).toBeTruthy());
    expect(findButton(container, 'Принять заказ')!.disabled)
      .toBe(true); // номера ещё нет

    const orderNo = [...container.querySelectorAll('label')]
      .find((l) => l.textContent?.startsWith('Номер заказа'))!
      .querySelector('input')!;
    fireEvent.input(orderNo, { target: { value: '301-000-11' } });

    await waitFor(() => expect(findButton(container, 'Принять заказ')!.disabled)
      .toBe(false));
  });
});

function findButton(root: HTMLElement, text: string): HTMLButtonElement | undefined {
  return [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === text);
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
