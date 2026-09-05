import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Склад возврата подставляется тем, откуда деталь выдали.
 *
 * <p><b>Зачем.</b> Умолчанием стоял первый склад ответа, а список складов
 * отсортирован по названию: сделка, выданная с «Основного», открывала форму
 * возврата с «Дальним». Продавец жмёт «Оформить» не глядя — поле заполнено
 * и выглядит осмысленно, — и деталь встаёт на чужую полку. В возврате это
 * хуже, чем в приёмке: деталь уже была на учёте, и искать её будут
 * по прежнему адресу.
 *
 * <p>Позиции с двух складов умолчания не дают вовсе: пусто честнее, чем
 * наугад, — угадав, мы промахнёмся ровно так же тихо.
 */
describe('склад возврата', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  beforeEach(() => stubApi([issuedItem(1, 2), issuedItem(2, 2)]));

  it('подставлен склад выдачи, а не первый из списка', async () => {
    render(<SellerScreen canSell role="SELLER" />);
    await openDeal();

    // «Дальний» приходит первым — он и подставлялся до правки, при том что
    // деталь ушла с «Основного».
    await waitFor(() => expect(warehouseSelect().value).toBe('2'));
    expect(chosenWarehouseName()).toBe('Основной');
  });

  it('продавец меняет склад руками — остаётся выбранный', async () => {
    render(<SellerScreen canSell role="SELLER" />);
    await openDeal();
    await waitFor(() => expect(warehouseSelect().value).toBe('2'));

    // Клиент привёз туда, куда ему удобно: подстановка не запрет.
    fireEvent.change(warehouseSelect(), { target: { value: '5' } });
    expect(warehouseSelect().value).toBe('5');
  });

  it('подсказка про несовпадение со складом выдачи осталась', async () => {
    render(<SellerScreen canSell role="SELLER" />);
    await openDeal();

    await waitFor(() => expect(notes().some((t) => t.includes('Не обязан совпадать')))
      .toBe(true));
  });

  it('позиции с двух складов — умолчания нет, и сказано почему', async () => {
    stubApi([issuedItem(1, 2), issuedItem(2, 5)]);
    render(<SellerScreen canSell role="SELLER" />);
    await openDeal();

    // Угадать нельзя: пусто честнее.
    await waitFor(() => expect(warehouseSelect().value).toBe(''));

    // Отмечаем позицию — кнопка всё равно неактивна, и рядом сказано почему.
    fireEvent.click(document.querySelector('.stock-row input[type=checkbox]') as HTMLElement);
    await waitFor(() => expect(returnButton()?.textContent).toContain('Оформить возврат'));
    expect(returnButton()!.disabled).toBe(true);
    expect(notes().some((t) => t.includes('Склад не подставлен'))).toBe(true);
  });
});

/** Доходит до карточки выданной сделки: через клиента, как и продавец. */
async function openDeal(): Promise<void> {
  fireEvent.click(findButton('Найти сделку клиента')!);

  const input = [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'имя или телефон')!;
  setNative(input, 'Иванов');

  await waitFor(() => expect(findButtonBy((t) => t.includes('Иванов'))).toBeTruthy());
  fireEvent.click(findButtonBy((t) => t.includes('Иванов'))!);

  await waitFor(() => expect(findButtonBy((t) => t.includes('№19'))).toBeTruthy());
  fireEvent.click(findButtonBy((t) => t.includes('№19'))!);

  await waitFor(() => expect(warehouseSelect()).toBeTruthy());
}

function warehouseSelect(): HTMLSelectElement {
  return [...document.querySelectorAll('label')]
    .find((l) => (l.textContent ?? '').startsWith('Склад возврата'))!
    .querySelector('select') as HTMLSelectElement;
}

function chosenWarehouseName(): string | undefined {
  const select = warehouseSelect();
  return [...select.options].find((o) => o.value === select.value)?.textContent?.trim();
}

function returnButton(): HTMLButtonElement | undefined {
  return [...document.querySelectorAll('button')]
    .find((b) => (b.textContent ?? '').includes('Оформить возврат')) as HTMLButtonElement;
}

function notes(): string[] {
  return [...document.querySelectorAll('p')].map((p) => p.textContent ?? '');
}

function findButton(text: string): HTMLButtonElement | undefined {
  return findButtonBy((t) => t === text);
}

function findButtonBy(match: (text: string) => boolean): HTMLButtonElement | undefined {
  return [...document.querySelectorAll('button')]
    .find((b) => match((b.textContent ?? '').trim())) as HTMLButtonElement | undefined;
}

/**
 * Склады приходят так, как их отдаёт сервер: по названию филиала и склада.
 * «Дальний» впереди «Основного» — на этом и ловилась подстановка первым.
 */
function stubApi(items: unknown[]): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/organization/warehouses')) {
      return json([
        { id: 5, branchId: 1, name: 'Дальний', branchName: 'Разборка', cells: 0 },
        { id: 2, branchId: 1, name: 'Основной', branchName: 'Разборка', cells: 0 },
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
      return json([deal(items)]);
    }
    return json([]);
  }));
}

function deal(items: unknown[]) {
  return {
    id: 19, number: 19, customerId: 1, managerId: null, status: 'ISSUED',
    reservedUntil: null, totalAmount: '7000', paidAmount: '7000', debt: '0',
    createdAt: '2026-09-01T10:00:00Z', issuedAt: '2026-09-01T11:00:00Z',
    // Склад выдачи самой сделки: колонка есть, но заполняет её пока никто.
    warehouseId: null,
    marketplace: null, externalOrderNo: null, replyDeadline: null,
    orderAcceptedAt: null, deliveryNote: null, items, services: [],
  };
}

function issuedItem(id: number, warehouseId: number) {
  return {
    id, partId: id, title: `Фара ${id}`, quantity: '1', price: '3500',
    discount: null, warehouseId, status: 'ISSUED',
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
