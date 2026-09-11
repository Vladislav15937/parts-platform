import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Розничная продажа оформляется без заведения клиента (задача 0011).
 *
 * <p>Порядок до правки был такой: добавить деталь, прокрутить вниз, **набрать
 * имя клиента** — иначе никак, — нажать «Завести клиента «Иванов Иван»»
 * и только потом «Оформить и отложить». Пока клиент не выбран, кнопка была
 * серой и молчала: нажатие не давало ни подсказки, ни подсветки поля.
 *
 * <p>Половина продаж на разборке — человек с улицы, которому нечего заводить
 * в справочник; заводя его, продавец мусорил базу именами вроде «мужик
 * на приоре».
 */
describe('розничная продажа', () => {
  let sentBodies: string[] = [];

  beforeEach(() => {
    sentBodies = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/customers/retail')) {
        return json({
          id: 42, name: 'Частное лицо', phone: null, email: null, customerType: 'PERSON',
        });
      }
      if (url.includes('/api/parts/stock')) {
        return json({ total: 1, rows: [
          { partId: 7, publicCode: 'A-1', title: 'Бампер', price: '10000',
            status: 'IN_STOCK', warehouseId: 2, warehouseName: 'Ткацкая',
            cellCode: null, qty: '1', qtyReserved: '0', qtyAvailable: '1' }] });
      }
      if (url.endsWith('/api/deals') && init?.method === 'POST') {
        sentBodies.push(String(init.body));
        return json(deal());
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('клиент подставлен, и сделка оформляется без единого слова о нём', async () => {
    const { container } = render(
      <SellerScreen canSell role="OWNER" company="test" memberId={1} />,
    );

    const search = container.querySelector('input')!;
    fireEvent.input(search, { target: { value: 'бампер' } });
    fireEvent.click(findButton(container, 'Найти')!);

    await waitFor(() => expect(findButton(container, 'в сделку')).toBeTruthy());
    fireEvent.click(findButton(container, 'в сделку')!);

    // Клиент в форме оформления уже стоит: набирать имя не нужно.
    await waitFor(() => expect(container.textContent).toContain('Клиент: Частное лицо'));

    // Главное: кнопка жива при непустой корзине — ничего не вводя.
    await waitFor(() => expect(findButton(container, 'Оформить и отложить')).toBeTruthy());
    expect(findButton(container, 'Оформить и отложить')!.disabled).toBe(false);

    fireEvent.click(findButton(container, 'Оформить и отложить')!);
    await waitFor(() => expect(sentBodies).toHaveLength(1));
    expect(JSON.parse(sentBodies[0] ?? '{}').customerId).toBe(42);
  });

  it('очищенное руками поле клиента объясняет серую кнопку словами', async () => {
    const { container } = render(
      <SellerScreen canSell role="OWNER" company="test" memberId={1} />,
    );

    const search = container.querySelector('input')!;
    fireEvent.input(search, { target: { value: 'бампер' } });
    fireEvent.click(findButton(container, 'Найти')!);
    await waitFor(() => expect(findButton(container, 'в сделку')).toBeTruthy());
    fireEvent.click(findButton(container, 'в сделку')!);

    await waitFor(() => expect(container.textContent).toContain('Клиент: Частное лицо'));
    await waitFor(() => expect(findButton(container, 'Изменить')).toBeTruthy());
    fireEvent.click(findButton(container, 'Изменить')!);

    // Кнопка гаснет — и обязана сказать, почему: молчащая серая кнопка
    // и есть то, с чем пришла задача.
    await waitFor(() =>
      expect(findButton(container, 'Оформить и отложить')!.disabled).toBe(true));
    expect(container.textContent).toContain('оформлять не на кого');
  });
});

function findButton(root: HTMLElement, text: string): HTMLButtonElement | undefined {
  return [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === text);
}

function deal() {
  return {
    id: 5,
    number: 5,
    customerId: 42,
    customerName: 'Частное лицо',
    managerId: 1,
    managerName: 'Хозяин',
    stage: 'NEW',
    status: 'RESERVED',
    reservedUntil: null,
    totalAmount: '10000',
    paidAmount: '0',
    debt: '10000',
    createdAt: '2026-09-11T10:00:00Z',
    issuedAt: null,
    dealSourceId: null,
    warehouseId: null,
    marketplace: null,
    externalOrderNo: null,
    replyDeadline: null,
    orderAcceptedAt: null,
    deliveryNote: null,
    items: [],
    services: [],
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
