import { afterEach, describe, expect, it, vi } from 'vitest';
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

    render(<CustomersScreen role="OWNER" onOpenDeal={() => {}} />);

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

    render(<CustomersScreen role="OWNER" onOpenDeal={() => {}} />);

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

    render(<CustomersScreen role="OWNER" onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('Автосервис')).toBeTruthy());
    fireEvent.click(screen.getByText('Автосервис'));

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

    render(<CustomersScreen role="OWNER" onOpenDeal={() => {}} />);
    await waitFor(() => expect(screen.getByText('Клиент с историей')).toBeTruthy());
    fireEvent.click(screen.getByText('Клиент с историей'));

    fireEvent.click(await screen.findByRole('button', { name: 'Платежи' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Движения по счёту' }));

    await waitFor(() => expect(screen.getByText('Операций: 10')).toBeTruthy());
    expect(screen.getAllByText('Пополнение')).toHaveLength(10);
  });
});

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
}): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/customers/directory')) {
      return json(routes.customers ?? { items: [], total: 0 });
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

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
