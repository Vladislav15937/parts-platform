import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { CustomersScreen } from './CustomersScreen';

/**
 * Раздел «Клиенты»: список с балансом и карточка.
 *
 * <p>Карточки клиента не было вовсе — клиент существовал только внутри
 * разговора о продаже. Здесь проверяются дословные требования задачи 0022:
 * порядок и умолчание вкладок, пустая выдача по отбору, цвет баланса
 * и то, что журнал счёта в карточке не режется до восьми записей, как
 * на экране продавца.
 */
describe('раздел «Клиенты»', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('пустой поиск говорит ровно то, что называет задача', async () => {
    stubApi({ customers: { items: [], total: 0 } });

    render(<CustomersScreen role="OWNER" company="t_1" memberId={7} onOpenDeal={() => {}} />);

    fireEvent.change(screen.getByPlaceholderText('Поиск клиента'), {
      target: { value: 'Иванов' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Найти' }));

    await waitFor(() =>
      expect(screen.getByText('Клиентов с таким именем или телефоном нет')).toBeTruthy());
  });

  it('подвал списка и цвет баланса — долг красным, ноль серым', async () => {
    stubApi({
      customers: {
        items: [
          customer({ id: 1, name: 'Должник', balance: -500 }),
          customer({ id: 2, name: 'Без движений', balance: 0 }),
        ],
        total: 2,
      },
    });

    render(<CustomersScreen role="OWNER" company="t_1" memberId={7} onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('Клиентов: 2')).toBeTruthy());

    const debt = screen.getByText('-500 ₽');
    expect(debt.className).toBe('note--error');

    const zero = screen.getByText('0');
    expect(zero.className).toBe('muted');
  });

  it('карточка открывается на вкладке «Клиент», вкладки в заданном порядке', async () => {
    stubApi({
      customers: { items: [customer({ id: 1, name: 'Автосервис' })], total: 1 },
      detail: customer({ id: 1, name: 'Автосервис' }),
      account: { customerId: 1, balance: 0, entries: [] },
    });

    render(<CustomersScreen role="OWNER" company="t_1" memberId={7} onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('Автосервис')).toBeTruthy());
    fireEvent.click(screen.getByText('Автосервис'));

    // Ждём саму подпись, а не берём её сразу: заглушка `fetch` разрешается
    // в том же тике, и синхронное чтение зеленело бы независимо от того,
    // дождался ли экран ответа. Та же порода, что уронила `main` 5 сентября.
    await screen.findByRole('heading', { name: 'Автосервис' });

    const tabs = await screen.findAllByRole('button', {
      name: /^(Клиент|Сделки|Возвраты|Платежи)$/,
    });
    expect(tabs.map((t) => t.textContent)).toEqual(['Клиент', 'Сделки', 'Возвраты', 'Платежи']);
    expect(screen.getByRole('button', { name: 'Клиент' }).className)
      .toContain('tab--active');

    // Примечание и заметка — подписи и подсказки под ними дословно из задачи.
    expect(screen.getByText('Выводится при печати накладных и других документов'))
      .toBeTruthy();
    expect(screen.getByText('Нигде не выводится. Доступна к просмотру только вам.'))
      .toBeTruthy();
  });

  it('движения по счёту показывают все записи, а не восемь последних', async () => {
    const entries = Array.from({ length: 10 }, (_, i) => ({
      id: i + 1,
      entryType: 'TOP_UP',
      amount: 100,
      signedAmount: 100,
      comment: null,
      createdAt: '2026-09-01T10:00:00Z',
      dealNumber: null,
      authorName: 'Хозяин',
    }));
    stubApi({
      customers: { items: [customer({ id: 1, name: 'Клиент с историей' })], total: 1 },
      detail: customer({ id: 1, name: 'Клиент с историей' }),
      account: { customerId: 1, balance: 1000, entries },
    });

    render(<CustomersScreen role="OWNER" company="t_1" memberId={7} onOpenDeal={() => {}} />);
    await waitFor(() => expect(screen.getByText('Клиент с историей')).toBeTruthy());
    fireEvent.click(screen.getByText('Клиент с историей'));

    fireEvent.click(await screen.findByRole('button', { name: 'Платежи' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Движения по счёту' }));

    await waitFor(() => expect(screen.getByText('Операций: 10')).toBeTruthy());
    expect(screen.getAllByText('Пополнение')).toHaveLength(10);
  });

  /**
   * Продавец карточку видит, но не правит (пункт 9 критерия приёмки):
   * телефон в чужой сделке поправить он не должен. Поля показаны текстом,
   * а кнопки «Сохранить» нет вовсе — иначе продавец нажмёт её и получит
   * отказ сервера на работу, которую экран ему предложил.
   */
  it('продавцу поля показаны текстом, а сохранения нет', async () => {
    stubApi({
      customers: { items: [customer({ id: 1, name: 'Клиент продавца' })], total: 1 },
      detail: customer({ id: 1, name: 'Клиент продавца', phone: '+79990001122' }),
      account: { customerId: 1, balance: 0, entries: [] },
    });

    render(<CustomersScreen role="SELLER" company="t_1" memberId={7} onOpenDeal={() => {}} />);
    await waitFor(() => expect(screen.getByText('Клиент продавца')).toBeTruthy());
    fireEvent.click(screen.getByText('Клиент продавца'));

    await screen.findByRole('heading', { name: 'Клиент продавца' });

    const phoneLabel = [...document.querySelectorAll('label')]
      .find((l) => (l.textContent ?? '').startsWith('Телефон'))!;
    expect(phoneLabel.querySelector('input')).toBeNull();
    expect(phoneLabel.textContent).toContain('+79990001122');

    expect([...document.querySelectorAll('button')]
      .some((b) => (b.textContent ?? '').trim() === 'Сохранить')).toBe(false);

    // Лицевой счёт продавцу доступен — деньги он принимает и выдаёт.
    expect(button('Пополнить / Списать')).toBeTruthy();
  });
});

/**
 * Источник платежа в карточке клиента.
 *
 * <p><b>Зачем отдельный набор.</b> Задача 0024 научила спрашивать способ
 * оплаты на трёх поверхностях продавца, а карточка клиента писалась
 * параллельно и звала {@code topUpAccount(customerId, amount)} — вторым
 * параметром по умолчанию уходит {@code null}. Компилируется молча, тесты
 * зелёные, а «сколько прошло наличными» у владельца расходится ровно
 * на пополнения из карточки. Проверка на то и заведена, чтобы снятый
 * третий параметр валил её.
 */
describe('источник платежа в карточке клиента', () => {
  let sent: Sent;

  beforeEach(() => {
    localStorage.clear();
    sent = { topUp: null, withdraw: null };
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('пополнение уходит с выбранным источником и запоминает его', async () => {
    await openAccountCard(sent, { balance: 1000 });

    // Умолчание — первый неархивный по алфавиту («Карта Сбер», id 2),
    // как и у продавца: тот же `defaultPaymentSource`.
    await waitFor(() => expect(paymentSelect().value).toBe('2'));

    fireEvent.change(paymentSelect(), { target: { value: '1' } });
    fireEvent.change(cashInput(), { target: { value: '700' } });
    fireEvent.click(button('Положить')!);

    await waitFor(() => expect(sent.topUp).toEqual({ amount: '700', paymentSourceId: 1 }));
    expect(localStorage.getItem('partsflow.lastPaymentSource.t_1.7')).toBe('1');
  });

  it('выдача уходит с выбранным источником', async () => {
    await openAccountCard(sent, { balance: 1000 });
    await waitFor(() => expect(paymentSelect()).toBeTruthy());

    fireEvent.change(paymentSelect(), { target: { value: '1' } });
    fireEvent.change(cashInput(), { target: { value: '400' } });
    fireEvent.click(button('Выдать')!);

    await waitFor(() => expect(sent.withdraw).toEqual({ amount: '400', paymentSourceId: 1 }));
  });

  it('умолчание — прошлый выбор этого сотрудника, а не первый по алфавиту', async () => {
    localStorage.setItem('partsflow.lastPaymentSource.t_1.7', '1');

    await openAccountCard(sent, { balance: 0 });

    await waitFor(() => expect(paymentSelect().value).toBe('1'));
  });

  it('правка остатка источник не запоминает: платежа при ней нет', async () => {
    await openAccountCard(sent, { balance: 1000 });
    await waitFor(() => expect(paymentSelect()).toBeTruthy());

    fireEvent.change(paymentSelect(), { target: { value: '1' } });
    fireEvent.click(button('Поправить остаток')!);
    fireEvent.change(input('+ или −'), { target: { value: '-100' } });
    fireEvent.change(input('почему правим'), { target: { value: 'приняли мимо кассы' } });
    fireEvent.click(button('Поправить')!);

    await waitFor(() => expect(button('Поправить остаток')).toBeTruthy());
    expect(localStorage.getItem('partsflow.lastPaymentSource.t_1.7')).toBeNull();
  });
});

/** Тела запросов по счёту, ушедшие на сервер. */
interface Sent {
  topUp: unknown;
  withdraw: unknown;
}

/** Доходит до карточки клиента и раскрывает ввод суммы «Пополнить / Списать». */
async function openAccountCard(sent: Sent, { balance }: { balance: number }): Promise<void> {
  stubApi({
    customers: { items: [customer({ id: 1, name: 'Автосервис' })], total: 1 },
    detail: customer({ id: 1, name: 'Автосервис' }),
    account: { customerId: 1, balance, entries: [] },
    sources: [
      { id: 1, name: 'ККМ', sourceType: 'CASH', archived: false },
      { id: 2, name: 'Карта Сбер', sourceType: 'BANK_ACCOUNT', archived: false },
    ],
    sent,
  });

  render(<CustomersScreen role="OWNER" company="t_1" memberId={7} onOpenDeal={() => {}} />);
  await waitFor(() => expect(screen.getByText('Автосервис')).toBeTruthy());
  fireEvent.click(screen.getByText('Автосервис'));

  fireEvent.click(await screen.findByRole('button', { name: 'Пополнить / Списать' }));
  await waitFor(() => expect(cashInput()).toBeTruthy());
}

function cashInput(): HTMLInputElement {
  return input('сумма');
}

function input(placeholder: string): HTMLInputElement {
  return [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === placeholder) as HTMLInputElement;
}

/** Список источников подписан невидимой меткой — как и у продавца. */
function paymentSelect(): HTMLSelectElement {
  return document.querySelector('select[aria-label="Источник платежа"]') as HTMLSelectElement;
}

function button(text: string): HTMLButtonElement | undefined {
  return [...document.querySelectorAll('button')]
    .find((b) => (b.textContent ?? '').trim() === text) as HTMLButtonElement | undefined;
}

interface Customer {
  id: number;
  name: string | null;
  phone: string | null;
  email: string | null;
  customerType: string;
  note: string | null;
  publicNote: string | null;
  inn: string | null;
  companyName: string | null;
  balance: number;
}

function customer(overrides: Partial<Customer> = {}): Customer {
  return {
    id: 1,
    name: 'Клиент',
    phone: null,
    email: null,
    customerType: 'PERSON',
    note: null,
    publicNote: null,
    inn: null,
    companyName: null,
    balance: 0,
    ...overrides,
  };
}

function stubApi(routes: {
  customers?: { items: Customer[]; total: number };
  detail?: Customer;
  account?: { customerId: number; balance: number; entries: unknown[] };
  sources?: unknown[];
  sent?: Sent;
}): void {
  vi.stubGlobal('fetch', vi.fn(async (request: RequestInfo | URL, init?: RequestInit) => {
    const url = String(request);
    const method = init?.method ?? 'GET';
    const body = () => JSON.parse(String(init?.body));

    if (url.includes('/api/payment-sources')) {
      return json(routes.sources ?? []);
    }
    if (url.includes('/api/customers/directory')) {
      return json(routes.customers ?? { items: [], total: 0 });
    }
    if (url.includes('/account/top-up') && method === 'POST') {
      if (routes.sent) {
        routes.sent.topUp = body();
      }
      return json(entry('TOP_UP', 700));
    }
    if (url.includes('/account/withdraw') && method === 'POST') {
      if (routes.sent) {
        routes.sent.withdraw = body();
      }
      return json(entry('WITHDRAW', -400));
    }
    if (url.includes('/account/correct') && method === 'POST') {
      return json(entry('CORRECTION', -100));
    }
    if (url.includes('/account')) {
      return json(routes.account ?? { customerId: 0, balance: 0, entries: [] });
    }
    if (url.includes('/api/customers/')) {
      return json(routes.detail ?? customer());
    }
    if (url.includes('/api/organization/warehouses')) {
      return json([]);
    }
    if (url.includes('/api/deals/returns')) {
      return json({ items: [], total: 0, totalAmount: '0' });
    }
    if (url.includes('/api/deals/payments')) {
      return json([]);
    }
    if (url.includes('/api/deals')) {
      return json([]);
    }
    return json({});
  }));
}

function entry(entryType: string, signedAmount: number) {
  return {
    id: 1,
    customerId: 1,
    entryType,
    amount: Math.abs(signedAmount),
    signedAmount,
    comment: null,
    createdAt: '2026-09-05T10:00:00Z',
    dealNumber: null,
    authorName: 'Хозяин',
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
