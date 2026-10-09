import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { DealsScreen } from './DealsScreen';
import { SellerScreen } from './SellerScreen';
import { dateInput } from '../sales/sales';

/**
 * Сдвиг ожидаемой даты виден продавцу на экране (задача 0170, ответ владельца
 * 30.09.2026: «подтвердить, но чтобы продавец видел сдвиг, а не узнавал из
 * истории»).
 *
 * <p><b>Почему на всех четырёх поверхностях сразу.</b> Срок резерва живёт в
 * карточке сделки, в строке списка сделок клиента, в реестре списком и на
 * доске по статусам; пометка, поставленная в одном месте, оставляла бы
 * продавца в неведении в трёх остальных (в проекте уже оплачено расхождение
 * написаний одного и того же — «Частное лицо» в четырёх видах). Проверка
 * идёт перебором по всем четырём.
 *
 * <p>И предзаказ нигде не «просрочен»: срок у него есть, а резерва склада
 * нет, и «подержите ещё» не про что — пока деталь не пришла.
 */
const DAY_MS = 86_400_000;
const PAST = new Date(Date.now() - 2 * DAY_MS).toISOString();
const FROM = dateInput(new Date(Date.now() + 10 * DAY_MS));
const TO = dateInput(new Date(Date.now() + 20 * DAY_MS));
const NOTE = `Дата прихода сдвинулась: было ${label(FROM)}, стало ${label(TO)}`;

function label(day: string): string {
  return new Date(`${day}T12:00:00`).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
}

describe('пометка о сдвиге ожидаемой даты и предзаказ без «срока истёк»', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('карточка: ожидается поставка, сдвиг назван и гаснет по «Клиенту сообщил»', async () => {
    const fetch = stubSeller();
    render(<SellerScreen canSell role="SELLER" company="t" memberId={1} />);
    await openDeal('№30');

    const text = plain(document.body.textContent);
    expect(text).toContain('Ожидается поставка · приход');
    expect(text).toContain(NOTE);
    // Срок резерва в прошлом, но у предзаказа он не «истёк».
    expect(text).not.toContain('срок истёк');

    fireEvent.click(screen.getByRole('button', { name: 'Клиенту сообщил' }));
    await waitFor(() => expect(plain(document.body.textContent)).not.toContain(NOTE));
    expect(fetch.mock.calls.some(([url, init]) =>
      String(url).endsWith('/api/deals/30/shift-seen')
      && (init as RequestInit | undefined)?.method === 'POST')).toBe(true);
    // Сам предзаказ остался: гаснет пометка, а не состояние.
    expect(plain(document.body.textContent)).toContain('Ожидается поставка');
  });

  it('список сделок клиента: тот же предзаказ и тот же сдвиг, без «срока истёк»', async () => {
    stubSeller();
    render(<SellerScreen canSell role="SELLER" company="t" memberId={1} />);
    await openCustomer();

    const lines = finderLines();
    expect(lines).toHaveLength(2);
    const preorder = lines.find((line) => line.startsWith('№30'))!;
    expect(preorder).toContain('ожидается поставка');
    expect(preorder).toContain(NOTE);
    expect(preorder).not.toContain('срок истёк');
    // Контроль: обычная просроченная сделка по-прежнему «срок истёк». Без
    // него проверка прошла бы и тогда, когда «срок истёк» не показывается
    // нигде вовсе.
    const ordinary = lines.find((line) => line.startsWith('№31'))!;
    expect(ordinary).toContain('срок истёк');
    expect(ordinary).not.toContain('ожидается поставка');
  });

  it('реестр списком: ожидается поставка, сдвиг назван, срока истёк нет', async () => {
    stubDeals();
    render(<DealsScreen onOpenDeal={() => {}} />);

    const row = await screen.findByText('№30').then((cell) => cell.closest('tr')!);
    const text = plain(row.textContent);
    expect(text).toContain('ожидается поставка');
    expect(text).toContain(NOTE);
    expect(text).not.toContain('срок истёк');

    const ordinary = screen.getByText('№31').closest('tr')!;
    expect(plain(ordinary.textContent)).toContain('срок истёк');
    expect(plain(ordinary.textContent)).not.toContain('ожидается поставка');
  });

  it('доска по статусам: то же на карточке', async () => {
    stubDeals();
    render(<DealsScreen onOpenDeal={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'По статусам' }));

    const preorder = await screen.findByText('№30').then((node) => node.closest('button')!);
    const text = plain(preorder.textContent);
    expect(text).toContain('ожидается поставка');
    expect(text).toContain(NOTE);
    expect(text).not.toContain('срок истёк');

    const ordinary = screen.getByText('№31').closest('button')!;
    expect(within(ordinary).getByText('срок истёк')).toBeTruthy();
  });
});

function deal(id: number, over: Record<string, unknown>) {
  return {
    id, number: id, customerId: 1, customerName: 'Иванов Пётр', managerId: null,
    managerName: null, stage: 'AWAITING_PAYMENT', status: 'RESERVED', reservedUntil: PAST,
    totalAmount: '9000.00', paidAmount: '0.00', debt: '9000.00',
    createdAt: '2026-09-05T12:00:00Z', issuedAt: null, warehouseId: null,
    marketplace: null, externalOrderNo: null, replyDeadline: null, orderAcceptedAt: null,
    deliveryNote: null,
    items: [{ id, partId: id, number: id, title: 'Фара', quantity: '1', price: '9000.00',
      discount: null, warehouseId: 2, status: 'PREORDER' }],
    services: [],
    preorder: false, preorderOnly: false, expectedOn: null, shiftFrom: null, shiftTo: null,
    ...over,
  };
}

const PREORDER = () => deal(30, {
  preorder: true, preorderOnly: true, expectedOn: TO, shiftFrom: FROM, shiftTo: TO,
});
const ORDINARY = () => deal(31, {
  items: [{ id: 31, partId: 31, number: 31, title: 'Бампер', quantity: '1', price: '9000.00',
    discount: null, warehouseId: 2, status: 'RESERVED' }],
});

function stubSeller() {
  let preorder = PREORDER();
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith('/api/deals/30/shift-seen') && init?.method === 'POST') {
      preorder = { ...preorder, shiftFrom: null, shiftTo: null };
      return json(preorder);
    }
    if (url.includes('/api/customers/1/account')) {
      return json({ customerId: 1, balance: 0, entries: [] });
    }
    if (url.includes('/api/customers?')) {
      return json([{ id: 1, name: 'Иванов Пётр', phone: '+79990001122',
        email: null, customerType: 'PERSON' }]);
    }
    if (url.includes('/api/deals?customerId')) {
      return json([preorder, ORDINARY()]);
    }
    return json([]);
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

function stubDeals() {
  const row = (d: ReturnType<typeof deal>) => ({
    id: d.id, number: d.number, createdAt: d.createdAt, customerId: 1,
    customerName: 'Иванов Пётр', totalAmount: d.totalAmount, paidAmount: d.paidAmount,
    status: d.status, reservedUntil: d.reservedUntil, managerId: null, managerName: null,
    preorder: d.preorder, preorderOnly: d.preorderOnly, foundBy: [],
    shiftFrom: d.shiftFrom, shiftTo: d.shiftTo,
  });
  const card = (d: ReturnType<typeof deal>) => ({ ...row(d), stage: d.stage });
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/deals/registry')) {
      return json({ items: [row(PREORDER()), row(ORDINARY())], total: 2 });
    }
    if (url.includes('/api/deals/board')) {
      return json({
        columns: [
          { key: 'EXPIRED', title: 'Истек срок', count: 1, cards: [card(ORDINARY())] },
          { key: 'AWAITING_PAYMENT', title: 'Ждет оплаты', count: 1, cards: [card(PREORDER())] },
        ],
        warehouses: [], sources: [], managers: [],
      });
    }
    return json([]);
  }));
}

async function openCustomer(): Promise<void> {
  fireEvent.click(findButton((t) => t === 'Найти сделку клиента')!);
  const input = [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'имя или телефон')!;
  setNative(input, 'Иванов');
  await waitFor(() => expect(findButton((t) => t.includes('Иванов'))).toBeTruthy());
  fireEvent.click(findButton((t) => t.includes('Иванов'))!);
  await waitFor(() => expect(findButton((t) => t.startsWith('№'))).toBeTruthy());
}

async function openDeal(number: string): Promise<void> {
  await openCustomer();
  fireEvent.click(findButton((t) => t.startsWith(`${number} `))!);
  await waitFor(() => expect(document.querySelector('h3')).toBeTruthy());
}

function finderLines(): string[] {
  return [...document.querySelectorAll('ul.suggestions li button')]
    .map((node) => plain(node.textContent))
    .filter((text) => text.startsWith('№'));
}

function findButton(match: (text: string) => boolean): HTMLButtonElement | undefined {
  return [...document.querySelectorAll('button')]
    .find((b) => match(plain(b.textContent))) as HTMLButtonElement | undefined;
}

function plain(text: string | null | undefined): string {
  return (text ?? '').replace(/\s+/g, ' ').trim();
}

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
