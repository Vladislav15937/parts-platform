import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Сумма к оплате подставлена долгом, а серая кнопка объясняет себя.
 *
 * <p><b>Зачем.</b> Полная оплата — самый частый случай на разборке: человек
 * приехал и забрал. Поле «принять оплату» при этом было пустым, и продавец
 * каждый раз набирал руками те же пять цифр, которые строкой выше уже
 * прочитал («долг 5 200 ₽»); опечатка в них становится недоплатой или
 * переплатой на лицевой счёт. Кнопка «Оплата» рядом была серой и молчала
 * о том, почему.
 *
 * <p>Проверяется здесь то, что видит продавец: что стоит в поле, нажимается
 * ли кнопка и что написано рядом, — а не то, какое состояние завёл компонент.
 * Переплата действительно доезжает до лицевого счёта проверкой на сервере
 * ({@code SalesControllerTest.overpaymentGoesToAccount}); здесь — что о ней
 * сказано <b>до</b> нажатия.
 */
describe('сумма оплаты подставлена долгом', () => {
  let sent: Sent;

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  beforeEach(() => {
    localStorage.clear();
  });

  it('в поле стоит долг, кнопка активна, одно нажатие его закрывает', async () => {
    sent = stubApi({ total: '5200', paid: '0' });
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    await waitFor(() => expect(amountInput().value).toBe('5200'));
    expect(payButton().disabled).toBe(false);

    fireEvent.click(payButton());
    await waitFor(() => expect(sent.payment).toMatchObject({ amount: '5200' }));
  });

  it('после частичной оплаты в поле встаёт остаток долга, а не сумма сделки', async () => {
    sent = stubApi({ total: '5200', paid: '0' });
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    await waitFor(() => expect(amountInput().value).toBe('5200'));
    fireEvent.change(amountInput(), { target: { value: '2000' } });
    fireEvent.click(payButton());

    // Главная проверка задачи: подставляется долг, а не сумма сделки. Заодно
    // это защита от второй оплаты — останься в поле внесённая тысяча,
    // следующее нажатие приняло бы её второй раз.
    await waitFor(() => expect(amountInput().value).toBe('3200'));
  });

  it('у полностью оплаченной поле пусто, кнопка серая и сказано почему', async () => {
    sent = stubApi({ total: '5200', paid: '5200' });
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    await waitFor(() => expect(amountInput().value).toBe(''));
    expect(payButton().disabled).toBe(true);
    expect(document.body.textContent).toContain('Долг закрыт');
  });

  it('стёртое поле гасит кнопку и объясняет это словами', async () => {
    sent = stubApi({ total: '5200', paid: '0' });
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    await waitFor(() => expect(amountInput().value).toBe('5200'));
    fireEvent.change(amountInput(), { target: { value: '' } });

    expect(payButton().disabled).toBe(true);
    expect(document.body.textContent).toContain('Впишите сумму');
  });

  it('сумма больше долга предупреждает про лицевой счёт до нажатия', async () => {
    sent = stubApi({ total: '5200', paid: '0' });
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    await waitFor(() => expect(amountInput().value).toBe('5200'));
    fireEvent.change(amountInput(), { target: { value: '6000' } });

    // Кнопка при этом рабочая: переплата — законная операция, о ней просто
    // надо сказать заранее.
    expect(payButton().disabled).toBe(false);
    expect(document.body.textContent).toContain('лицевой счёт');
    expect(document.body.textContent).toContain('800');
    expect(sent.payment).toBeNull();
  });

  it('ноль в поле — отказ словами, а не оплата на ноль', async () => {
    sent = stubApi({ total: '5200', paid: '0' });
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    await waitFor(() => expect(amountInput().value).toBe('5200'));
    fireEvent.change(amountInput(), { target: { value: '0' } });

    expect(payButton().disabled).toBe(true);
    expect(document.body.textContent).toContain('больше нуля');
  });

  // Закрытая сделка приходит с нулевым долгом (`Deal.debt()` отдаёт ноль
  // у отменённой и возвращённой: товара у клиента нет, платить не за что),
  // то есть кнопка гаснет и без отдельной ветки. Проверяется здесь не серость
  // кнопки, а **какими словами** она объяснена: «Долг закрыт» у сделки,
  // за которую никто не платил, — неправда, и продавец по ней пойдёт искать
  // несуществующий платёж. Оба статуса вместе: ветка одна, а слово берётся
  // из статуса, и опечатка во втором иначе осталась бы незамеченной.
  it('у закрытой сделки причина названа закрытием, а не закрытым долгом', async () => {
    const closed = [['CANCELLED', 'отменена'], ['RETURNED', 'возвращена']] as const;

    for (const [status, word] of closed) {
      sent = stubApi({ total: '5200', paid: '0', status });
      render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
      await openDeal();

      expect(amountInput().value).toBe('');
      expect(payButton().disabled).toBe(true);
      expect(document.body.textContent)
        .toContain(`Сделка ${word} — платить по ней не за что.`);
      expect(document.body.textContent).not.toContain('Долг закрыт');
      expect(sent.payment).toBeNull();

      cleanup();
      localStorage.clear();
    }
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

interface Sent {
  payment: { amount: string } | null;
}

/**
 * Заглушка сервера, помнящая оплаченное: карточка после оплаты перечитывает
 * сделку, и без настоящего пересчёта долга проверка «в поле встал остаток»
 * зеленела бы на любой подставленной сумме.
 */
function stubApi(
  { total, paid, status = 'RESERVED' }:
    { total: string; paid: string; status?: string },
): Sent {
  const sent: Sent = { payment: null };
  const state = { paid: Number(paid) };
  // Долг закрытой сделки сервер отдаёт нулём (`Deal.debt()`): товара
  // у клиента нет, платить не за что. Заглушка обязана это повторять —
  // иначе проверка на отменённой сделке шла бы по данным, которых
  // не бывает, и падала бы не на том утверждении.
  const closed = status === 'CANCELLED' || status === 'RETURNED';
  const dealOf = () => ({
    id: 19, number: 19, customerId: 1, managerId: null, status,
    reservedUntil: null,
    totalAmount: total,
    paidAmount: String(state.paid),
    debt: String(closed ? 0 : Math.max(Number(total) - state.paid, 0)),
    createdAt: '2026-09-01T10:00:00Z',
    issuedAt: null,
    warehouseId: null,
    marketplace: null, externalOrderNo: null, replyDeadline: null,
    orderAcceptedAt: null, deliveryNote: null,
    items: [{ id: 1, partId: 1, title: 'Фара', quantity: '1', price: total,
              discount: null, warehouseId: 2, status: 'RESERVED' }],
    services: [],
  });

  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';

    if (url.includes('/api/customers/1/account')) {
      return json({ customerId: 1, balance: 0, entries: [] });
    }
    if (url.includes('/api/customers?')) {
      return json([{ id: 1, name: 'Иванов Пётр', phone: '+79990001122',
        email: null, customerType: 'PERSON' }]);
    }
    if (url.includes('/api/deals?customerId')) {
      return json([dealOf()]);
    }
    if (url.includes('/api/deals/19/payments') && method === 'POST') {
      const body = JSON.parse(String(init?.body)) as { amount: string };
      sent.payment = body;
      // Сервер кладёт на сделку не больше долга, остальное на лицевой счёт.
      state.paid = Math.min(state.paid + Number(body.amount), Number(total));
      return json({ id: 1, dealId: 19, amount: body.amount,
        paidAt: '2026-09-05T10:00:00Z' });
    }
    if (url.includes('/api/deals/19')) {
      return json(dealOf());
    }
    return json([]);
  }));

  return sent;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
