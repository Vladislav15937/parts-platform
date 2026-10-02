import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Номер позиции в составе сделки и в выборе позиций на возврат (задача 0168).
 *
 * <p><b>Как это выглядело для человека.</b> Продавец открывает сделку, чтобы
 * отметить, что клиент привёз обратно, или назвать кладовщику, что снять
 * с полки, — а в строке стояло одно наименование. «Фара Toyota Camry 2007»
 * на живом складе это сотня одинаковых строк, и назвать нужную было нечем:
 * публичного кода в составе сделки нет вовсе, а номер, ради разговора
 * и заведённый (задача 0060), до этой правки сюда не доезжал.
 *
 * <p>Состав сделки и выбор позиций на возврат — <b>одни и те же строки</b>:
 * флажок возврата стоит в той же строке состава. Поэтому проверка одна,
 * и она про строку, а не про колонку: выдача здесь список.
 */
describe('состав сделки называет позицию номером', () => {
  beforeEach(() => {
    localStorage.clear();
    stubApi();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('строка состава сделки несёт номер позиции', async () => {
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    const row = [...document.querySelectorAll('.stock-info')]
      .find((node) => (node.textContent ?? '').includes('Фара передняя левая'))!;
    expect(row, 'строки состава сделки на экране нет вовсе').toBeTruthy();

    const line = plain(row.textContent);
    expect(
      line,
      `номера позиции в строке состава сделки нет: ${line}`,
    ).toContain('№ 347');

    // Наименование рядом осталось: номером деталь называют вслух,
    // а глазами выбирают по названию.
    expect(line, 'наименование пропало из строки').toContain('Фара передняя левая');
  });
});

/**
 * Текст строки с обычными пробелами: номер отделён неразрывным — он там ради
 * переноса, а не ради текста. Escape, а не сам символ.
 */
function plain(text: string | null): string {
  return (text ?? '').replace(/ /g, ' ');
}

/** Доходит до карточки отложенной сделки через клиента, как продавец. */
async function openDeal(): Promise<void> {
  fireEvent.click(findButtonBy((t) => t === 'Найти сделку клиента')!);

  const input = [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'имя или телефон')!;
  setNative(input, 'Иванов');

  await waitFor(() => expect(findButtonBy((t) => t.includes('Иванов'))).toBeTruthy());
  fireEvent.click(findButtonBy((t) => t.includes('Иванов'))!);

  await waitFor(() => expect(findButtonBy((t) => t.includes('№19'))).toBeTruthy());
  fireEvent.click(findButtonBy((t) => t.includes('№19'))!);

  await waitFor(() => expect(
    [...document.querySelectorAll('.stock-info')].length).toBeGreaterThan(0));
}

function findButtonBy(match: (text: string) => boolean): HTMLButtonElement | undefined {
  return [...document.querySelectorAll('button')]
    .find((b) => match((b.textContent ?? '').trim())) as HTMLButtonElement | undefined;
}

function setNative(input: HTMLInputElement, value: string): void {
  const setter = Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype, 'value')!.set!;
  setter.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function stubApi(): void {
  const deal = {
    id: 19, number: 19, customerId: 1, customerName: 'Иванов Пётр',
    managerId: null, managerName: null, stage: null,
    status: 'RESERVED', reservedUntil: null,
    totalAmount: '5200', paidAmount: '0', debt: '5200',
    createdAt: '2026-09-01T10:00:00Z', issuedAt: null,
    warehouseId: null,
    marketplace: null, externalOrderNo: null, replyDeadline: null,
    orderAcceptedAt: null, deliveryNote: null,
    items: [{
      id: 1, partId: 11, number: 347, title: 'Фара передняя левая',
      quantity: '1', price: '5200', discount: null,
      warehouseId: 2, status: 'RESERVED',
    }],
    services: [],
  };

  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/customers/1/account')) {
      return json({ customerId: 1, balance: 0, entries: [] });
    }
    if (url.includes('/api/customers?')) {
      return json([{
        id: 1, name: 'Иванов Пётр', phone: '+79990001122',
        email: null, customerType: 'PERSON',
      }]);
    }
    if (url.includes('/api/deals?customerId')) {
      return json([deal]);
    }
    if (url.includes('/api/deals/19')) {
      return json(deal);
    }
    return json([]);
  }));
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
