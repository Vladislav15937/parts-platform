import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Выбор источника платежа в карточке сделки.
 *
 * <p><b>Зачем.</b> Поле {@code paymentSourceId} принимают
 * {@code SalesController.PaymentRequest} и другие запросы с самого начала,
 * а фронтенд не отправлял его никогда: {@code sales.ts} слал только
 * {@code {amount}}. Ровно та ловушка из корневого CLAUDE.md — написано,
 * покрыто, недоступно.
 *
 * <p>Умолчание — источник прошлой оплаты этого продавца (ключ на компанию
 * и на сотрудника в localStorage), при первой оплате — первый неархивный
 * по алфавиту.
 */
describe('источник платежа в карточке сделки', () => {
  let paid: unknown = null;

  beforeEach(() => {
    localStorage.clear();
    paid = null;
    stubApi((body) => {
      paid = body;
    });
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('выбранный источник уходит в оплату и запоминается', async () => {
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    // Источники приходят по алфавиту («Карта Сбер» раньше «ККМ»), и первая
    // оплата в жизни продавца подставляет именно первый по алфавиту.
    await waitFor(() => expect(paymentSelect().value).toBe('2'));

    fireEvent.change(paymentSelect(), { target: { value: '1' } });
    fireEvent.change(amountInput(), { target: { value: '1000' } });
    fireEvent.click(payButton());

    await waitFor(() => expect(paid).toEqual({ amount: '1000', paymentSourceId: 1 }));
    expect(localStorage.getItem('partsflow.lastPaymentSource.t_1.7')).toBe('1');
  });

  it('следующая оплата этого продавца подставляет прошлый выбор', async () => {
    localStorage.setItem('partsflow.lastPaymentSource.t_1.7', '1');

    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    // Без единого нажатия по списку — умолчание уже «ККМ» (id 1), а не
    // первый по алфавиту: девять оплат из десяти идут одним способом.
    await waitFor(() => expect(paymentSelect().value).toBe('1'));
  });

  it('другой продавец на том же устройстве не видит чужого умолчания', async () => {
    localStorage.setItem('partsflow.lastPaymentSource.t_1.7', '1');

    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={9} />);
    await openDeal();

    await waitFor(() => expect(paymentSelect().value).toBe('2'));
  });

  it('кнопка «Оплата» не гаснет из-за источника: он не обязателен', async () => {
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    fireEvent.change(paymentSelect(), { target: { value: '' } });
    fireEvent.change(amountInput(), { target: { value: '500' } });
    expect(payButton().disabled).toBe(false);

    fireEvent.click(payButton());
    await waitFor(() => expect(paid).toEqual({ amount: '500', paymentSourceId: null }));
  });
});

/** Доходит до карточки отложенной сделки через клиента, как продавец. */
async function openDeal(): Promise<void> {
  fireEvent.click(findButton('Найти сделку клиента')!);

  const input = [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'имя или телефон')!;
  setNative(input, 'Иванов');

  await waitFor(() => expect(findButtonBy((t) => t.includes('Иванов'))).toBeTruthy());
  fireEvent.click(findButtonBy((t) => t.includes('Иванов'))!);

  await waitFor(() => expect(findButtonBy((t) => t.includes('№19'))).toBeTruthy());
  fireEvent.click(findButtonBy((t) => t.includes('№19'))!);

  await waitFor(() => expect(amountInput()).toBeTruthy());
}

function amountInput(): HTMLInputElement {
  return [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'принять оплату') as HTMLInputElement;
}

function paymentSelect(): HTMLSelectElement {
  return document.querySelector('select[aria-label="Источник платежа"]') as HTMLSelectElement;
}

function payButton(): HTMLButtonElement {
  return [...document.querySelectorAll('button')]
    .find((b) => (b.textContent ?? '').trim() === 'Оплата') as HTMLButtonElement;
}

function findButton(text: string): HTMLButtonElement | undefined {
  return findButtonBy((t) => t === text);
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

function stubApi(onPaid: (body: unknown) => void): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';

    if (url.includes('/api/payment-sources')) {
      return json([
        { id: 1, name: 'ККМ', sourceType: 'CASH', archived: false },
        { id: 2, name: 'Карта Сбер', sourceType: 'BANK_ACCOUNT', archived: false },
      ]);
    }
    if (url.includes('/api/customers/1/account')) {
      return json({ customerId: 1, balance: 0, entries: [] });
    }
    if (url.includes('/api/customers?')) {
      return json([{ id: 1, name: 'Иванов Пётр', phone: '+79990001122',
        email: null, customerType: 'PERSON' }]);
    }
    if (url.includes('/api/deals?customerId')) {
      return json([deal()]);
    }
    if (url.includes('/api/deals/19/payments') && method === 'POST') {
      onPaid(JSON.parse(String(init?.body)));
      return json({ id: 1, dealId: 19, amount: '1000', paidAt: '2026-09-05T10:00:00Z' });
    }
    return json([]);
  }));
}

function deal() {
  return {
    id: 19, number: 19, customerId: 1, managerId: null, status: 'RESERVED',
    reservedUntil: null, totalAmount: '3500', paidAmount: '0', debt: '3500',
    createdAt: '2026-09-01T10:00:00Z', issuedAt: null,
    warehouseId: null,
    marketplace: null, externalOrderNo: null, replyDeadline: null,
    orderAcceptedAt: null, deliveryNote: null,
    items: [{ id: 1, partId: 1, title: 'Фара', quantity: '1', price: '3500',
              discount: null, warehouseId: 2, status: 'RESERVED' }],
    services: [],
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
