import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { DealsScreen } from './DealsScreen';
import { PaymentsScreen } from './PaymentsScreen';
import { ReturnsScreen } from './ReturnsScreen';
import { SellerScreen } from './SellerScreen';
import { NO_CUSTOMER_NAME } from '../sales/dealStatus';

/**
 * Покупатель, которого продавец не проставил, зовётся одним словом —
 * «Частное лицо» (задача 0062, решение владельца продукта от 12 сентября
 * 2026).
 *
 * <p><b>Как это выглядело.</b> Четыре написания одного и того же: реестр
 * сделок и касса — «Без клиента», реестр возвратов — «Частное лицо»,
 * карточка сделки у продавца — «не указан» (четвёртое, «без клиента»
 * в кавычках, живёт в истории документа на сервере и проверяется
 * `RetailCustomerTest`). «Без клиента» читается как потерянные данные,
 * «Частное лицо» — как обычная розничная продажа; владелец, сводящий кассу,
 * видит в одном отчёте одно, в другом другое и идёт искать разницу,
 * которой нет.
 *
 * <p><b>Почему проверка по экранам, а не по функции.</b> Функция
 * (`dealStatus.customerName`) проверяется сама собой: она возвращает то,
 * что в ней написано. Разошлись же **экраны**, и разойтись они могут снова
 * — каждый своим `??`. Поэтому каждый случай здесь называет экран, а не
 * «текст не совпал»: по упавшей проверке обязано быть видно, куда идти.
 *
 * <p>Сравнивается **вся клетка целиком**, а не вхождение слова: проверка
 * «„Частное лицо“ где-то есть» прошла бы и рядом с оставшимся «Без
 * клиента» в соседней колонке.
 */
describe('сделка без покупателя всюду зовётся «Частным лицом»', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('реестр сделок', async () => {
    stub((url) => (url.includes('/api/deals/registry')
      ? { items: [dealRow()], total: 1 }
      : []));

    render(<DealsScreen onOpenDeal={() => {}} />);
    await waitFor(() => expect(screen.getByText('№8')).toBeTruthy());

    expect(cell(1), 'реестр сделок: сделка без покупателя названа не «Частным лицом»')
      .toBe(NO_CUSTOMER_NAME);
  });

  it('касса — раздел «Платежи»', async () => {
    stub(() => ({
      total: 1, income: 1000, expense: 0, net: 1000, items: [paymentRow()],
    }));

    render(<PaymentsScreen onOpenDeal={() => {}} />);
    await waitFor(() => expect(screen.getByText(/Платежей:/)).toBeTruthy());

    expect(cell(1), 'касса: платёж без клиента назван не «Частным лицом»')
      .toBe(NO_CUSTOMER_NAME);
  });

  it('реестр возвратов', async () => {
    stub((url) => (url.includes('/api/deals/returns')
      ? { items: [returnRow()], total: 1, totalAmount: '5000.00' }
      : []));

    render(<ReturnsScreen onOpenDeal={() => {}} />);
    await waitFor(() => expect(screen.getByText(/Возвратов:/)).toBeTruthy());

    expect(cell(1), 'реестр возвратов: возврат по сделке без покупателя'
      + ' назван не «Частным лицом»')
      .toBe(NO_CUSTOMER_NAME);
  });

  /**
   * Карточка сделки у продавца — то самое третье написание («не указан»),
   * которого не было в списке задачи: его нашёл перебор по всем местам, где
   * показывается клиент. Открывается заказ с площадки — сделка, у которой
   * покупателя нет по-настоящему.
   */
  it('карточка сделки на экране продавца', async () => {
    stub((url) => (url.includes('/api/deals/77') ? orderDeal() : []));

    render(
      <SellerScreen canSell role="SELLER" company="t_1" memberId={1}
                    openDealId={77} onDealOpened={() => {}} />,
    );
    await waitFor(() => expect(screen.getByText(/Сделка №77/)).toBeTruthy());

    expect(customerLine(), 'карточка сделки: покупатель заказа с площадки'
      + ' назван не «Частным лицом»')
      .toBe(`Клиент: ${NO_CUSTOMER_NAME}`);
  });
});

/** Клетка строки таблицы: во всех трёх реестрах клиент стоит второй. */
function cell(index: number): string {
  const row = document.querySelector('tbody tr');
  if (row === null) {
    throw new Error('строки в таблице нет вовсе — экран не дорисовался');
  }
  return clean(row.querySelectorAll('td')[index]?.textContent);
}

/**
 * Строка «Клиент: …» карточки сделки без кнопки «Изменить клиента»:
 * кнопка живёт в том же абзаце, и её текст к имени покупателя отношения
 * не имеет.
 */
function customerLine(): string {
  const line = [...document.querySelectorAll('p.note')]
    .find((node) => clean(node.textContent).startsWith('Клиент:'));
  if (line === undefined) {
    throw new Error('в карточке сделки нет строки о клиенте');
  }
  const button = clean(line.querySelector('button')?.textContent);
  return clean(clean(line.textContent).replace(button, ''));
}

function clean(text: string | null | undefined): string {
  return (text ?? '').replace(/\s+/g, ' ').trim();
}

function stub(body: (url: string) => unknown): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const answer = body(String(input));
    return new Response(JSON.stringify(answer), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }));
}

/** Розничная продажа времён до задачи 0011: контрагента у неё нет вовсе. */
function dealRow() {
  return {
    id: 8, number: 8, createdAt: '2026-09-05T12:00:00Z',
    customerId: null, customerName: null,
    totalAmount: '34500.00', paidAmount: '0.00',
    status: 'RESERVED', reservedUntil: null,
    managerId: 7, managerName: 'Владимир Петров',
  };
}

/** Возврат денег по заказу площадки: покупателя она не называет. */
function paymentRow() {
  return {
    id: 1, paidAt: '2026-09-08T09:00:00Z', direction: 'IN', amount: 1000,
    comment: null, dealId: null, dealNumber: null,
    customerId: null, customerName: null,
    sourceId: 1, sourceName: 'ККМ',
  };
}

function returnRow() {
  return {
    id: 11, number: 11, createdAt: '2026-09-05T12:00:00Z',
    dealId: 7, dealNumber: 42,
    customerId: null, customerName: null,
    warehouseId: 3, warehouseName: 'Основной',
    restocked: true, status: 'DONE', amount: '5000.00', reason: null,
  };
}

function orderDeal() {
  return {
    id: 77, number: 77, customerId: null, customerName: null,
    managerId: 1, managerName: 'Продавец', stage: 'AWAITING_PAYMENT',
    status: 'RESERVED', reservedUntil: null,
    totalAmount: '4000.00', paidAmount: '0.00', debt: '4000.00',
    createdAt: '2026-09-05T10:00:00Z', issuedAt: null,
    dealSourceId: null, warehouseId: null,
    marketplace: 'DROM', externalOrderNo: '301-000-11',
    replyDeadline: null, orderAcceptedAt: null, deliveryNote: null,
    items: [{ id: 1, partId: 1, title: 'Фара', quantity: '1', price: '4000.00',
              discount: '0', warehouseId: 1, status: 'RESERVED' }],
    services: [],
  };
}
