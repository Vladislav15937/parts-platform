import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { DealsScreen } from './DealsScreen';

/**
 * Список сделок продавца (задача 0014).
 *
 * <p>Проверяется то, что видит человек: какая воронка открыта при входе, что
 * уходит в запрос при её смене и при поиске, как выглядит строка и чем «пусто
 * по отбору» отличается от «пусто вообще».
 *
 * <p><b>Отбор проверяется по отправленному запросу, а не по показанным
 * строкам.</b> Заглушка отвечает одним и тем же на любой адрес — значит
 * утверждение «в «Выданных» видно выданную» прошло бы и на экране, который
 * воронку не отправляет вовсе. Настоящее место отбора здесь — параметры
 * запроса, а сам отбор стерегут тесты сервера.
 */
describe('список сделок продавца', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('открывается на отложенных и спрашивает у сервера именно их', async () => {
    const fetch = stubApi({ items: [row()], total: 1 });
    render(<DealsScreen onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('Автосервис на Русской')).toBeTruthy());

    const asked = new URL(String(fetch.mock.calls[0]?.[0]), 'http://x');
    expect(asked.searchParams.getAll('status')).toEqual(['RESERVED']);
    // «Мои» не подставляется: продавцу нужно и чужое — возвращают не тому,
    // кто продавал.
    expect(asked.searchParams.get('mine')).toBeNull();
  });

  it('строка несёт номер, клиента, суммы, состояние со сроком и ответственного', async () => {
    const until = new Date(Date.now() + 5 * 24 * 3600 * 1000);
    stubApi({
      items: [row({ reservedUntil: until.toISOString() })],
      total: 1,
    });

    render(<DealsScreen onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('№8')).toBeTruthy());
    expect(screen.getByText('05 сен 26')).toBeTruthy();
    expect(screen.getByText('Автосервис на Русской')).toBeTruthy();
    expect(screen.getByText('34 500 ₽')).toBeTruthy();
    expect(screen.getByText('12 000 ₽')).toBeTruthy();
    expect(screen.getByText('Отложена')).toBeTruthy();
    expect(screen.getByText('Владимир Петров')).toBeTruthy();
    // Срок резерва словом, а не ISO: до какого числа держим.
    const day = until.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
    expect(screen.getByText(`до ${day}`)).toBeTruthy();
  });

  /**
   * У живого клиента просрочена больше половины отложенных сделок, и это
   * не срок, а очередь на обзвон: вчерашнее число рядом со словом «отложена»
   * продавец прочтёт как обещание.
   */
  it('просроченный резерв говорит «срок истёк», а не вчерашним числом', async () => {
    const past = new Date(Date.now() - 3 * 24 * 3600 * 1000);
    stubApi({ items: [row({ reservedUntil: past.toISOString() })], total: 1 });

    render(<DealsScreen onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('срок истёк')).toBeTruthy());
    const day = past.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
    expect(screen.queryByText(`до ${day}`)).toBeNull();
  });

  it('у выданной сделки срока нет вовсе', async () => {
    stubApi({
      items: [row({ status: 'ISSUED', reservedUntil: '2026-09-12T20:59:59Z' })],
      total: 1,
    });

    render(<DealsScreen onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('Выдана')).toBeTruthy());
    expect(screen.queryByText('срок истёк')).toBeNull();
    expect(screen.queryByText(/^до /)).toBeNull();
  });

  it('поиск по коду детали уходит на сервер, ничего не найдено — словами', async () => {
    const fetch = stubApi({ items: [], total: 0 });
    render(<DealsScreen onOpenDeal={() => {}} />);

    // Пусто вообще и пусто по отбору — разные утверждения.
    await waitFor(() => expect(screen.getByText('Сделок в этом состоянии нет')).toBeTruthy());

    fireEvent.change(screen.getByPlaceholderText('Номер сделки, клиент или код детали'), {
      target: { value: '55747F0F91CD' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Найти' }));

    await waitFor(() => expect(screen.getByText('Ничего не найдено')).toBeTruthy());
    expect(screen.queryByText('Сделок в этом состоянии нет')).toBeNull();

    const last = new URL(String(fetch.mock.calls.at(-1)?.[0]), 'http://x');
    expect(last.searchParams.get('q')).toBe('55747F0F91CD');
  });

  it('воронка «Выданные» и отбор «мои» уходят в запрос', async () => {
    const fetch = stubApi({ items: [], total: 0 });
    render(<DealsScreen onOpenDeal={() => {}} />);
    await waitFor(() => expect(screen.getByText('Сделок в этом состоянии нет')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Выданные' }));
    await waitFor(() => {
      const asked = new URL(String(fetch.mock.calls.at(-1)?.[0]), 'http://x');
      expect(asked.searchParams.getAll('status')).toEqual(['ISSUED']);
    });

    fireEvent.change(screen.getByLabelText('Чьи'), { target: { value: 'mine' } });
    await waitFor(() => {
      const asked = new URL(String(fetch.mock.calls.at(-1)?.[0]), 'http://x');
      expect(asked.searchParams.get('mine')).toBe('true');
      expect(asked.searchParams.getAll('status')).toEqual(['ISSUED']);
    });

    // «Все» снимает отбор по состоянию целиком, а не подставляет четвёртый.
    fireEvent.click(screen.getByRole('button', { name: 'Все' }));
    await waitFor(() => {
      const asked = new URL(String(fetch.mock.calls.at(-1)?.[0]), 'http://x');
      expect(asked.searchParams.getAll('status')).toEqual([]);
    });
  });

  it('нажатие на строку открывает эту сделку', async () => {
    stubApi({ items: [row({ id: 41 })], total: 1 });
    const onOpenDeal = vi.fn();

    render(<DealsScreen onOpenDeal={onOpenDeal} />);

    fireEvent.click(await screen.findByText('Автосервис на Русской'));
    expect(onOpenDeal).toHaveBeenCalledWith(41);
  });

  /**
   * Обрезанный список обязан говорить, что он обрезан: полсотни строк
   * из семисот читаются как «столько и есть».
   */
  it('обрезанный список называет, сколько показано из скольких', async () => {
    stubApi({ items: [row()], total: 74 });

    render(<DealsScreen onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('Сделок: 74')).toBeTruthy());
    expect(screen.getByText(/Показаны первые 1 сделка из 74/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Показать ещё' })).toBeTruthy();
  });

  it('отказ сервера не выдаётся за пустой список', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"message":"Нет связи"}', {
      status: 503, headers: { 'Content-Type': 'application/json' },
    })));

    render(<DealsScreen onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('Нет связи')).toBeTruthy());
    expect(screen.queryByText('Сделок в этом состоянии нет')).toBeNull();
    expect(screen.queryByText('Загружаем…')).toBeNull();
  });
});

interface Row {
  id: number;
  number: number | null;
  createdAt: string;
  customerId: number | null;
  customerName: string | null;
  totalAmount: string;
  paidAmount: string;
  status: string;
  reservedUntil: string | null;
  managerId: number | null;
  managerName: string | null;
}

function row(overrides: Partial<Row> = {}): Row {
  return {
    id: 8, number: 8, createdAt: '2026-09-05T12:00:00Z',
    customerId: 3, customerName: 'Автосервис на Русской',
    totalAmount: '34500.00', paidAmount: '12000.00',
    status: 'RESERVED', reservedUntil: null,
    managerId: 7, managerName: 'Владимир Петров',
    ...overrides,
  };
}

function stubApi(page: { items: Row[]; total: number }) {
  // Параметр объявлен, хотя ответ от него не зависит: без него `mock.calls`
  // выводится как массив пустых кортежей, и `calls[0][0]` не собирается —
  // а именно адрес запроса здесь и проверяется.
  const fetch = vi.fn(async (_input: RequestInfo | URL) => new Response(JSON.stringify(page), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  }));
  vi.stubGlobal('fetch', fetch);
  return fetch;
}
