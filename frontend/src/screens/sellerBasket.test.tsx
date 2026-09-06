import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Взятое в корзину не выдаётся за отсутствующее.
 *
 * <p><b>Зачем.</b> Кнопка гасла по одному условию «свободного не осталось»,
 * а свободное считается за вычетом того, что уже лежит в корзине. На складе
 * б/у запчастей остаток почти всегда единица — и колёса тоже поштучно, —
 * поэтому после первого же нажатия продавец видел в одной строке «свободно 1»
 * и «нет свободных». Поймано живым прогоном на шине: положил в сделку
 * и прочитал, что её нет.
 *
 * <p>Разница не в словах: «нет свободных» продавец говорит покупателю
 * по телефону, а «уже в сделке» означает «посмотри в корзину».
 */
describe('корзина продавца', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/customers?')) {
        return json([{ id: 1, name: 'Иванов Пётр', phone: '+79990001122' }]);
      }
      if (url.includes('/api/deals')) {
        return json(deal());
      }
      if (url.includes('/api/parts/stock')) {
        return json({
          total: 5,
          rows: [
            // Единственная свободная: после нажатия свободного не остаётся.
            row(1, 'Шина 225/55 R18 Bridgestone', '1', '0'),
            // Вся отложена другому клиенту — вот это «нет свободных».
            row(2, 'Шина 195/65 R15 Dunlop', '0', '1'),
          ],
        });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('после нажатия говорит «уже в сделке», а не «нет свободных»', async () => {
    render(<SellerScreen canSell role="SELLER" company="test" memberId={1} />);
    await search();

    const buttons = () => [...document.querySelectorAll('.stock-row button')]
      .map((b) => b.textContent);
    await waitFor(() => expect(buttons()).toEqual(['в сделку', 'нет свободных']));

    fireEvent.click(document.querySelector('.stock-row button') as HTMLElement);

    await waitFor(() => expect(buttons()[0]).toBe('уже в сделке'));
    // Позиция, у которой свободного не было изначально, говорит прежнее:
    // её продавец обещать не может вовсе.
    expect(buttons()[1]).toBe('нет свободных');
  });

  /**
   * Подпись про обрезку исчезает вместе со списком.
   *
   * <p>После оформления сделки список убирают: остаток изменился,
   * и показанное уже врёт. А счётчик найденного оставался, и подпись
   * превращалась в «Показаны первые 0 из 17» — при пустом экране. Поймано
   * живым прогоном на продаже шины.
   */
  it('не пишет «показаны первые 0» при пустом списке', async () => {
    render(<SellerScreen canSell role="SELLER" company="test" memberId={1} />);
    await search();

    const notice = () => [...document.querySelectorAll('p')]
      .map((p) => p.textContent ?? '')
      .find((t) => t.includes('Показаны первые'));
    // Найдено больше, чем показано, — подпись на месте.
    await waitFor(() => expect(notice()).toContain('Показаны первые 2 из 5'));

    // Оформляем сделку: после неё список убирают, потому что остаток
    // изменился и показанное уже врёт.
    fireEvent.click(document.querySelector('.stock-row button') as HTMLElement);
    const customer = [...document.querySelectorAll('input')]
      .find((i) => i.placeholder === 'имя или телефон')!;
    setNative(customer, 'Иванов');
    await waitFor(() => expect([...document.querySelectorAll('button')]
      .some((b) => (b.textContent ?? '').includes('Иванов'))).toBe(true));
    fireEvent.click([...document.querySelectorAll('button')]
      .find((b) => (b.textContent ?? '').includes('Иванов'))!);
    fireEvent.click([...document.querySelectorAll('button')]
      .find((b) => b.textContent === 'Оформить и отложить')!);

    await waitFor(() => expect(document.querySelector('.stock-row')).toBeNull());
    expect(notice(), 'подпись пережила список и говорит «первые 0»').toBeUndefined();
  });

  async function search(): Promise<void> {
    const input = document.querySelector('input') as HTMLInputElement;
    setNative(input, 'bridgestone');
    fireEvent.click([...document.querySelectorAll('button')]
      .find((b) => b.textContent === 'Найти')!);
    await waitFor(() => expect(document.querySelector('.stock-row')).toBeTruthy());
  }
});

function deal() {
  return {
    id: 19, number: 19, customerId: 1, managerId: null, status: 'RESERVED',
    reservedUntil: null, totalAmount: '6500', paidAmount: '0', debt: '6500',
    createdAt: '2026-08-08T10:00:00Z', issuedAt: null, marketplace: null,
    externalOrderNo: null, replyDeadline: null, orderAcceptedAt: null,
    deliveryNote: null, items: [], services: [],
  };
}

function row(partId: number, title: string, available: string, reserved: string) {
  return {
    partId, publicCode: `A-${partId}`, title, price: '6500', status: 'IN_STOCK',
    warehouseId: 2, warehouseName: 'Ткацкая', cellCode: null,
    qty: String(Number(available) + Number(reserved)),
    qtyReserved: reserved, qtyAvailable: available,
  };
}

/**
 * React слушает нативный сеттер: присвоение value напрямую он не заметит,
 * и поле останется пустым при внешне набранном тексте.
 */
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
