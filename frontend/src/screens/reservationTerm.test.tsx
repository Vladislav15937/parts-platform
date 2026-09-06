import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Срок резерва виден продавцу и продлевается им же.
 *
 * <p><b>Зачем.</b> `deal.reserved_until` заполняется с самого начала, а на
 * экране продавца его не было ни в каком виде: карточка говорила «Сделка №8 ·
 * отложена» и всё. Продавец не знал, освободится деталь завтра или через
 * неделю, и клиенту ответить не мог — при том что число лежало в базе.
 *
 * <p>Просроченный показывается словами, а не вчерашним числом: у живого
 * клиента из 74 отложенных сделок просрочена больше половины, то есть это
 * обычное состояние половины списка, а не редкий случай.
 */
describe('срок резерва у продавца', () => {
  const DAY = 86_400_000;
  const soon = new Date(Date.now() + 3 * DAY);
  const past = new Date(Date.now() - 2 * DAY);
  let sent: { url: string; body: unknown }[] = [];

  beforeEach(() => {
    sent = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (init?.method === 'POST') {
        sent.push({ url, body: JSON.parse(String(init.body)) });
      }
      // Счёт проверяется раньше списка клиентов: его адрес начинается так же.
      if (url.includes('/account')) {
        return json({ customerId: 1, balance: 0, entries: [] });
      }
      if (url.includes('/api/customers')) {
        return json([{ id: 1, name: 'Иванов Пётр', phone: '+79990001122' }]);
      }
      if (url.includes('/returns')) {
        return json([]);
      }
      if (url.includes('/api/deals')) {
        return json([deal(19, soon), deal(20, past)]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('показывает срок в списке сделок клиента и в карточке', async () => {
    render(<SellerScreen canSell role="SELLER" company="test" memberId={1} />);
    await pickCustomer();

    const lines = () => [...document.querySelectorAll('.suggestions button')]
      .map((b) => b.textContent ?? '');
    await waitFor(() => expect(lines().length).toBe(2));

    expect(lines()[0], 'в списке нет срока — «отложена» без числа не говорит ничего')
      .toContain(`до ${dayOf(soon)}`);
    expect(lines()[1], 'просроченная сделка показывает вчерашнее число как срок')
      .toContain('срок истёк');

    // Карточка: срок стоит под номером сделки — там, где его ищут.
    fireEvent.click(document.querySelectorAll('.suggestions button')[0] as HTMLElement);
    await waitFor(() => expect(notes()).toContain(`Отложено до ${dayOf(soon)}`));
  });

  it('просроченный срок в карточке говорит словами и красным', async () => {
    render(<SellerScreen canSell role="SELLER" company="test" memberId={1} />);
    await pickCustomer();
    await waitFor(() => expect(document.querySelectorAll('.suggestions button').length).toBe(2));

    fireEvent.click(document.querySelectorAll('.suggestions button')[1] as HTMLElement);

    await waitFor(() => expect(notes()).toContain('Отложено · срок истёк'));
    expect(notes(), 'вчерашнее число выдаётся за срок')
      .not.toContain(`Отложено до ${dayOf(past)}`);
    expect(
      [...document.querySelectorAll('.note--error')].map((n) => n.textContent),
      'просроченный резерв ничем не выделен — в списке он теряется',
    ).toContain('Отложено · срок истёк');
  });

  it('продлевает срок из карточки', async () => {
    render(<SellerScreen canSell role="SELLER" company="test" memberId={1} />);
    await pickCustomer();
    await waitFor(() => expect(document.querySelectorAll('.suggestions button').length).toBe(2));
    fireEvent.click(document.querySelectorAll('.suggestions button')[0] as HTMLElement);

    const field = await waitFor(() => {
      const input = document.querySelector('input[type="date"]') as HTMLInputElement | null;
      expect(input, 'продлить резерв из карточки нечем').toBeTruthy();
      return input!;
    });

    const later = new Date(Date.now() + 9 * DAY);
    setNative(field, iso(later));
    fireEvent.click([...document.querySelectorAll('button')]
      .find((b) => b.textContent === 'Продлить')!);

    await waitFor(() => expect(sent.some((r) => r.url.includes('/api/deals/19/reservation')))
      .toBe(true));
    const request = sent.find((r) => r.url.includes('/reservation'))!;
    const asked = new Date((request.body as { reservedUntil: string }).reservedUntil);
    expect(dayOf(asked), 'на сервер уехало не то число, которое выбрал продавец')
      .toBe(dayOf(later));
  });

  async function pickCustomer(): Promise<void> {
    // До чужой сделки продавец добирается через клиента: номера документа
    // приезжающий через неделю не помнит.
    fireEvent.click([...document.querySelectorAll('button')]
      .find((b) => b.textContent === 'Найти сделку клиента')!);
    const input = [...document.querySelectorAll('input')]
      .find((i) => i.placeholder === 'имя или телефон')!;
    setNative(input, 'Иванов');
    await waitFor(() => expect([...document.querySelectorAll('button')]
      .some((b) => (b.textContent ?? '').includes('Иванов'))).toBe(true));
    fireEvent.click([...document.querySelectorAll('button')]
      .find((b) => (b.textContent ?? '').includes('Иванов'))!);
  }
});

function notes(): string[] {
  return [...document.querySelectorAll('p')].map((p) => p.textContent ?? '');
}

/**
 * Дата словами, посчитанная независимо от экрана.
 *
 * <p>Тем же `toLocaleDateString` проверка повторила бы ошибку слово в слово:
 * сломанный формат совпал бы сам с собой.
 */
function dayOf(date: Date): string {
  const months = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля',
    'августа', 'сентября', 'октября', 'ноября', 'декабря'];
  return `${date.getDate()} ${months[date.getMonth()]}`;
}

function iso(date: Date): string {
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0'),
  ].join('-');
}

function deal(id: number, reservedUntil: Date) {
  return {
    id, number: id, customerId: 1, managerId: null, status: 'RESERVED',
    reservedUntil: reservedUntil.toISOString(), totalAmount: '6500', paidAmount: '0',
    debt: '6500', createdAt: '2026-08-08T10:00:00Z', issuedAt: null, warehouseId: null,
    marketplace: null, externalOrderNo: null, replyDeadline: null, orderAcceptedAt: null,
    deliveryNote: null, items: [], services: [],
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
