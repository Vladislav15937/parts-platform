import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';
import { dateInput } from '../sales/sales';

/**
 * Предзаказ у продавца (задача 0170): ожидаемая деталь находится поиском,
 * кладётся в сделку отдельным движением, и срок называет сам продавец.
 *
 * <p><b>Как это выглядело для человека.</b> По объявлению с площадки звонят
 * именно по такой детали, а продавец своего же товара не находил: поиск
 * показывал только то, что лежит на складе. «Вижу, но отложить не могу» —
 * половина ответа покупателю, который готов ждать контейнер; владелец
 * выбрал вариант «видит и откладывает».
 *
 * <p>Сроки здесь считаются от «сейчас», а не зашиты числом календаря: зашитая
 * дата в проверке, которая сравнивает её с сегодняшним числом, — отложенное
 * падение (см. `dealCardStage.test.tsx`).
 */
const DAY_MS = 86_400_000;

function iso(daysFromNow: number): string {
  return dateInput(new Date(Date.now() + daysFromNow * DAY_MS));
}

const EXPECTED_ON = iso(20);
const EXPECTED_LABEL = new Date(`${EXPECTED_ON}T12:00:00`)
  .toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });

function expectedRow(over: Record<string, unknown> = {}) {
  return {
    partId: 9, number: 410, publicCode: 'F00DBA5E0001',
    title: 'Фара Toyota Camry 2007 лев.', price: '9000', status: 'DRAFT',
    warehouseId: 2, warehouseName: 'Ткацкая', cellCode: null,
    qty: '0', qtyReserved: '0', qtyAvailable: '2',
    expected: true, expectedOn: EXPECTED_ON,
    ...over,
  };
}

describe('предзаказ в выдаче и в оформлении продавца', () => {
  let created: Record<string, unknown> | null;
  let expected: Record<string, unknown>;

  beforeEach(() => {
    created = null;
    expected = expectedRow();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/parts/stock')) {
        return json({
          total: 2,
          facets: { vehicles: [], grades: [] },
          rows: [expected, {
            partId: 1, number: 347, publicCode: '7584A8FEAE3D',
            title: 'Бампер Toyota Camry', price: '3000', status: 'IN_STOCK',
            warehouseId: 2, warehouseName: 'Ткацкая', cellCode: null,
            qty: '1', qtyReserved: '0', qtyAvailable: '1',
            expected: false, expectedOn: null,
          }],
        });
      }
      if (url.endsWith('/api/deals') && init?.method === 'POST') {
        created = JSON.parse(String(init.body));
        return json(dealView());
      }
      if (url.includes('/api/customers/retail')) {
        return json({ id: 1, name: 'Частное лицо', phone: null, email: null,
          customerType: 'PERSON' });
      }
      if (url.includes('/api/organization/warehouses')) {
        return json([{ id: 2, branchId: 1, name: 'Ткацкая', branchName: 'Филиал', cells: 0 }]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('ожидаемая строка называет дату и сколько можно отложить, а не склад', async () => {
    render(<SellerScreen canSell role="SELLER" company="t" memberId={1} />);
    await search();

    const info = screen.getByText('Фара Toyota Camry 2007 лев.').closest('.stock-info')!;
    const line = plain(info.textContent);
    expect(line).toContain(`Ожидается · приход ${EXPECTED_LABEL}`);
    expect(line).toContain('можно отложить 2');
    // Склад тут не показывается: деталь ляжет туда, куда её положит приёмщик,
    // и «свободно 2» на «Ткацкой» было бы утверждением о складе, которого нет.
    expect(line).not.toContain('Ткацкая');
    expect(line).not.toContain('свободно');
    expect(buttonOf('Фара Toyota Camry 2007 лев.')).toBe('отложить под клиента');
    // Обычная строка осталась прежней.
    expect(buttonOf('Бампер Toyota Camry')).toBe('в сделку');
  });

  it('когда всё отложено, строка остаётся и говорит об этом', async () => {
    expected = expectedRow({ qtyAvailable: '0' });
    render(<SellerScreen canSell role="SELLER" company="t" memberId={1} />);
    await search();

    expect(buttonOf('Фара Toyota Camry 2007 лев.')).toBe('всё отложено');
  });

  it('срок подставлен: ожидаемая дата и неделя на забор, и уходит на сервер', async () => {
    render(<SellerScreen canSell role="SELLER" company="t" memberId={1} />);
    await search();
    fireEvent.click(addButton('Фара Toyota Camry 2007 лев.'));

    const field = await termField();
    expect(field.value).toBe(iso(27));

    fireEvent.click(screen.getByRole('button', { name: 'Оформить и отложить' }));
    await waitFor(() => expect(created).not.toBeNull());
    // Срок уехал: без него сервер отказал бы, а настройка компании к
    // предзаказу не применяется.
    expect(typeof created!.reservedUntil).toBe('string');
    expect(new Date(String(created!.reservedUntil)).getTime())
      .toBeGreaterThan(Date.now() + 26 * DAY_MS);
  });

  it('срок раньше ожидаемой даты — предупреждение до нажатия, кнопка не гаснет', async () => {
    render(<SellerScreen canSell role="SELLER" company="t" memberId={1} />);
    await search();
    fireEvent.click(addButton('Фара Toyota Camry 2007 лев.'));

    const field = await termField();
    setNative(field, iso(5));

    await waitFor(() => expect(plain(document.body.textContent))
      .toContain(`Срок раньше ожидаемой даты прихода (${EXPECTED_LABEL})`));
    // Предупреждение, а не запрет: продавец мог договориться с покупателем
    // «если не приедет — снимем», и запретить это значило бы решить за него.
    const submit = screen.getByRole('button', { name: 'Оформить и отложить' }) as HTMLButtonElement;
    expect(submit.disabled).toBe(false);

    setNative(field, iso(27));
    await waitFor(() => expect(plain(document.body.textContent))
      .not.toContain('Срок раньше ожидаемой даты'));
  });

  it('дата поставки не названа: подставлять нечего, кнопка называет причину', async () => {
    expected = expectedRow({ expectedOn: null });
    render(<SellerScreen canSell role="SELLER" company="t" memberId={1} />);
    await search();
    fireEvent.click(addButton('Фара Toyota Camry 2007 лев.'));

    const field = await termField();
    expect(field.value).toBe('');
    const submit = screen.getByRole('button', { name: 'Оформить и отложить' }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    expect(plain(document.body.textContent)).toContain('Назовите срок');

    setNative(field, iso(40));
    await waitFor(() => expect(submit.disabled).toBe(false));
  });

  it('у обычной продажи поля срока нет и срок на сервер не уходит', async () => {
    render(<SellerScreen canSell role="SELLER" company="t" memberId={1} />);
    await search();
    fireEvent.click(addButton('Бампер Toyota Camry'));

    await waitFor(() => expect(screen.getByText('В сделку')).toBeTruthy());
    expect(document.querySelector('input[type="date"]')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Оформить и отложить' }));
    await waitFor(() => expect(created).not.toBeNull());
    expect(created!.reservedUntil).toBeNull();
  });

  it('заказ с площадки на ожидаемый товар не оформляется — и причина названа', async () => {
    render(<SellerScreen canSell role="SELLER" company="t" memberId={1} />);
    await search();
    fireEvent.click(addButton('Фара Toyota Camry 2007 лев.'));
    await termField();

    const select = screen.getByLabelText('Заказ с площадки') as HTMLSelectElement;
    fireEvent.change(select, { target: { value: 'DROM' } });

    await waitFor(() => expect(plain(document.body.textContent))
      .toContain('нельзя оформить на ожидаемый товар'));
    const submit = screen.getByRole('button', { name: 'Принять заказ' }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
  });
});

async function termField(): Promise<HTMLInputElement> {
  await waitFor(() => expect(document.querySelector('input[type="date"]')).toBeTruthy());
  return document.querySelector('input[type="date"]') as HTMLInputElement;
}

async function search(): Promise<void> {
  fireEvent.change(screen.getByPlaceholderText(/фара камри/), { target: { value: 'фара' } });
  fireEvent.click(screen.getByRole('button', { name: 'Найти' }));
  await waitFor(() => expect(screen.getByText('Бампер Toyota Camry')).toBeTruthy());
}

function rowOf(title: string): HTMLElement {
  return screen.getByText(title).closest('li') as HTMLElement;
}

function buttonOf(title: string): string {
  return rowOf(title).querySelector('button')!.textContent ?? '';
}

function addButton(title: string): HTMLButtonElement {
  return rowOf(title).querySelector('button') as HTMLButtonElement;
}

function dealView() {
  return {
    id: 5, number: 5, customerId: 1, customerName: 'Частное лицо', managerId: 1,
    managerName: 'Продавец', stage: 'AWAITING_PAYMENT', status: 'RESERVED',
    reservedUntil: new Date(Date.now() + 30 * DAY_MS).toISOString(),
    totalAmount: '9000', paidAmount: '0', debt: '9000',
    createdAt: new Date().toISOString(), issuedAt: null, warehouseId: null,
    marketplace: null, externalOrderNo: null, replyDeadline: null, orderAcceptedAt: null,
    deliveryNote: null, items: [], services: [],
    preorder: true, expectedOn: EXPECTED_ON, shiftFrom: null, shiftTo: null,
  };
}

function plain(text: string | null): string {
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
