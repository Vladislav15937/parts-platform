import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Выбор источника платежа — на всех трёх поверхностях, где продавец
 * трогает деньги.
 *
 * <p><b>Зачем.</b> Поле {@code paymentSourceId} принимают
 * {@code SalesController.PaymentRequest}, {@code ReturnDocRequest}
 * и {@code CustomerAccountController.TopUpRequest} с самого начала,
 * а фронтенд не отправлял его никогда: {@code sales.ts} слал только
 * {@code {amount}}. Ровно та ловушка из корневого CLAUDE.md — написано,
 * покрыто, недоступно.
 *
 * <p><b>Почему три describe, а не один.</b> Разбор поймал ровно это:
 * проверялась одна оплата сделки, и снятое с {@code registerReturn},
 * {@code topUpAccount} и {@code withdrawFromAccount} поле не роняло
 * ни одного теста. Три вызова — три отдельных тела запроса, и общего
 * кода, который поймал бы все три сразу, между ними нет.
 *
 * <p>Умолчание — источник прошлой оплаты этого продавца (ключ на компанию
 * и на сотрудника в localStorage), при первой оплате — первый неархивный
 * по алфавиту.
 */
describe('источник платежа в карточке сделки', () => {
  let sent: Sent;

  beforeEach(() => {
    localStorage.clear();
    sent = stubApi({ status: 'RESERVED' });
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

    await waitFor(() => expect(sent.payment).toEqual({ amount: '1000', paymentSourceId: 1 }));
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
    await waitFor(() => expect(sent.payment).toEqual({ amount: '500', paymentSourceId: null }));
  });
});

/**
 * Возврат денег из кассы — вторая поверхность.
 *
 * <p>Возврат уносит деньги из ящика так же, как оплата их туда кладёт,
 * и без способа владелец не сведёт кассу: приняли переводом, вернули
 * наличными — по журналу это будет неотличимо. На лицевой счёт возврат
 * платежа не создаёт вовсе, поэтому там списка нет и в теле уходит `null`.
 */
describe('источник платежа в возврате денег из кассы', () => {
  let sent: Sent;

  beforeEach(() => {
    localStorage.clear();
    sent = stubApi({ status: 'ISSUED' });
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('выбранный источник уходит в возврат и запоминается', async () => {
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openIssuedDeal();

    fireEvent.change(returnSourceSelect(), { target: { value: '1' } });
    await submitReturn();

    await waitFor(() => expect(sent.returnDoc).toEqual({
      warehouseId: 2,
      items: [{ dealItemId: 1, restocked: true }],
      reason: 'не подошла',
      refundToAccount: false,
      paymentSourceId: 1,
    }));
    expect(localStorage.getItem('partsflow.lastPaymentSource.t_1.7')).toBe('1');
  });

  it('деньги на лицевой счёт — источника нет вовсе', async () => {
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openIssuedDeal();

    fireEvent.click(checkbox('Деньги на лицевой счёт, а не из кассы'));
    // Списка нет: платежа при зачислении на счёт не создаётся, и спрашивать
    // способ, которым деньги не двигались, значит спрашивать про то,
    // чего не произойдёт.
    expect(returnSourceSelectOrNull()).toBeNull();

    await submitReturn();
    await waitFor(() => expect(sent.returnDoc).toMatchObject({
      refundToAccount: true,
      paymentSourceId: null,
    }));
  });
});

/**
 * Лицевой счёт — третья поверхность, и обе её кнопки создают платёж.
 *
 * <p>«Положить» и «Выдать» — приход и расход настоящих денег, в отличие
 * от зачёта в сделку: тот платежа не создаёт, и способа у него нет.
 */
describe('источник платежа в операциях по лицевому счёту', () => {
  let sent: Sent;

  beforeEach(() => {
    localStorage.clear();
    sent = stubApi({ status: 'RESERVED', balance: 1000 });
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('пополнение уходит с выбранным источником', async () => {
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openAccount();

    fireEvent.change(paymentSelect(), { target: { value: '1' } });
    fireEvent.change(cashInput(), { target: { value: '700' } });
    fireEvent.click(findButton('Положить')!);

    await waitFor(() => expect(sent.topUp).toEqual({ amount: '700', paymentSourceId: 1 }));
    expect(localStorage.getItem('partsflow.lastPaymentSource.t_1.7')).toBe('1');
  });

  it('выдача уходит с выбранным источником', async () => {
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openAccount();

    fireEvent.change(paymentSelect(), { target: { value: '1' } });
    fireEvent.change(cashInput(), { target: { value: '400' } });
    fireEvent.click(findButton('Выдать')!);

    await waitFor(() => expect(sent.withdraw).toEqual({ amount: '400', paymentSourceId: 1 }));
  });

  it('правка остатка источник не запоминает: платежа при ней нет', async () => {
    render(<SellerScreen canSell role="OWNER" company="t_1" memberId={7} />);
    await openAccount();

    fireEvent.change(paymentSelect(), { target: { value: '1' } });
    fireEvent.click(findButton('Поправить остаток')!);
    fireEvent.change(fixAmountInput(), { target: { value: '-100' } });
    fireEvent.change(fixReasonInput(), { target: { value: 'приняли мимо кассы' } });
    fireEvent.click(findButton('Поправить')!);

    // Деньги не двигались, платежа не создано — а умолчание оплаты
    // подставилось бы способом, которым продавец не платил.
    await waitFor(() => expect(findButton('Поправить остаток')).toBeTruthy());
    expect(localStorage.getItem('partsflow.lastPaymentSource.t_1.7')).toBeNull();
  });
});

/** Доходит до карточки отложенной сделки через клиента, как продавец. */
async function openDeal(): Promise<void> {
  await pickCustomer();

  await waitFor(() => expect(findButtonBy((t) => t.includes('№19'))).toBeTruthy());
  fireEvent.click(findButtonBy((t) => t.includes('№19'))!);

  await waitFor(() => expect(amountInput()).toBeTruthy());
}

/** То же, но до выданной сделки: у неё есть возврат и нет отмены. */
async function openIssuedDeal(): Promise<void> {
  await openDeal();
  await waitFor(() => expect(returnSourceSelectOrNull()).toBeTruthy());

  // Отмечаем позицию: возврат оформляют по отмеченным строкам.
  fireEvent.click(document.querySelector('.stock-row input[type=checkbox]') as HTMLElement);
  fireEvent.change(reasonInput(), { target: { value: 'не подошла' } });
}

/** Останавливается на карточке клиента: до сделки идти незачем. */
async function openAccount(): Promise<void> {
  await pickCustomer();
  await waitFor(() => expect(cashInput()).toBeTruthy());
}

async function pickCustomer(): Promise<void> {
  fireEvent.click(findButton('Найти сделку клиента')!);

  const input = [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'имя или телефон')!;
  setNative(input, 'Иванов');

  await waitFor(() => expect(findButtonBy((t) => t.includes('Иванов'))).toBeTruthy());
  fireEvent.click(findButtonBy((t) => t.includes('Иванов'))!);
}

/** Возврат идёт двумя нажатиями: он не отменяется, и экран это спрашивает. */
async function submitReturn(): Promise<void> {
  fireEvent.click(findButtonBy((t) => t.startsWith('Оформить возврат'))!);
  await waitFor(() => expect(findButtonBy((t) => t.startsWith('Да, оформить'))).toBeTruthy());
  fireEvent.click(findButtonBy((t) => t.startsWith('Да, оформить'))!);
}

function amountInput(): HTMLInputElement {
  return [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'принять оплату') as HTMLInputElement;
}

function cashInput(): HTMLInputElement {
  return [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'сумма') as HTMLInputElement;
}

function fixAmountInput(): HTMLInputElement {
  return [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === '+ или −') as HTMLInputElement;
}

function fixReasonInput(): HTMLInputElement {
  return [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'почему правим') as HTMLInputElement;
}

function reasonInput(): HTMLInputElement {
  return [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'не подошла, привёз обратно') as HTMLInputElement;
}

/** Список у оплаты и у лицевого счёта: он подписан невидимой меткой. */
function paymentSelect(): HTMLSelectElement {
  return document.querySelector('select[aria-label="Источник платежа"]') as HTMLSelectElement;
}

/** Список в возврате: он подписан видимой меткой, а не aria-label. */
function returnSourceSelectOrNull(): HTMLSelectElement | null {
  const label = [...document.querySelectorAll('label')]
    .find((l) => (l.textContent ?? '').trim().startsWith('Источник платежа'));
  return label?.querySelector('select') ?? null;
}

function returnSourceSelect(): HTMLSelectElement {
  return returnSourceSelectOrNull()!;
}

function checkbox(labelText: string): HTMLInputElement {
  return [...document.querySelectorAll('label')]
    .find((l) => (l.textContent ?? '').includes(labelText))!
    .querySelector('input[type=checkbox]') as HTMLInputElement;
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

/** Тела запросов, ушедших на сервер: по одному на каждую поверхность. */
interface Sent {
  payment: unknown;
  returnDoc: unknown;
  topUp: unknown;
  withdraw: unknown;
}

function stubApi({ status, balance = 0 }: { status: string; balance?: number }): Sent {
  const sent: Sent = { payment: null, returnDoc: null, topUp: null, withdraw: null };

  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    const body = () => JSON.parse(String(init?.body));

    if (url.includes('/api/payment-sources')) {
      return json([
        { id: 1, name: 'ККМ', sourceType: 'CASH', archived: false },
        { id: 2, name: 'Карта Сбер', sourceType: 'BANK_ACCOUNT', archived: false },
      ]);
    }
    if (url.includes('/api/organization/warehouses')) {
      return json([
        { id: 5, branchId: 1, name: 'Дальний', branchName: 'Разборка', cells: 0 },
        { id: 2, branchId: 1, name: 'Основной', branchName: 'Разборка', cells: 0 },
      ]);
    }
    if (url.includes('/api/customers/1/account/top-up') && method === 'POST') {
      sent.topUp = body();
      return json({ id: 1, customerId: 1, entryType: 'TOP_UP', amount: 700,
        signedAmount: 700, comment: null, createdAt: '2026-09-05T10:00:00Z' });
    }
    if (url.includes('/api/customers/1/account/withdraw') && method === 'POST') {
      sent.withdraw = body();
      return json({ id: 2, customerId: 1, entryType: 'WITHDRAW', amount: 400,
        signedAmount: -400, comment: null, createdAt: '2026-09-05T10:00:00Z' });
    }
    if (url.includes('/api/customers/1/account')) {
      return json({ customerId: 1, balance, entries: [] });
    }
    if (url.includes('/api/customers?')) {
      return json([{ id: 1, name: 'Иванов Пётр', phone: '+79990001122',
        email: null, customerType: 'PERSON' }]);
    }
    if (url.includes('/api/deals?customerId')) {
      return json([deal(status)]);
    }
    if (url.includes('/api/deals/19/payments') && method === 'POST') {
      sent.payment = body();
      return json({ id: 1, dealId: 19, amount: '1000', paidAt: '2026-09-05T10:00:00Z' });
    }
    if (url.includes('/api/deals/19/returns') && method === 'POST') {
      sent.returnDoc = body();
      return json({ id: 3, number: 3, dealId: 19, warehouseId: 2, status: 'COMPLETED',
        amount: '3500', reason: 'не подошла', createdAt: '2026-09-05T10:00:00Z' });
    }
    if (url.includes('/api/deals/19')) {
      return json(deal(status));
    }
    return json([]);
  }));

  return sent;
}

function deal(status: string) {
  const issued = status === 'ISSUED';
  return {
    id: 19, number: 19, customerId: 1, managerId: null, status,
    reservedUntil: null, totalAmount: '3500',
    paidAmount: issued ? '3500' : '0', debt: issued ? '0' : '3500',
    createdAt: '2026-09-01T10:00:00Z',
    issuedAt: issued ? '2026-09-01T11:00:00Z' : null,
    // Склад выдачи самой сделки: колонка есть, но заполняет её пока никто,
    // и умолчание возврата берётся со склада выданной позиции.
    warehouseId: null,
    marketplace: null, externalOrderNo: null, replyDeadline: null,
    orderAcceptedAt: null, deliveryNote: null,
    items: [{ id: 1, partId: 1, title: 'Фара', quantity: '1', price: '3500',
              discount: null, warehouseId: 2, status: issued ? 'ISSUED' : 'RESERVED' }],
    services: [],
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
