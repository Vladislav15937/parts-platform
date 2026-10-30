import { rightsAs } from '../test/rights';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { OrdersScreen } from './OrdersScreen';
import { SellerScreen } from './SellerScreen';
import { closeBrowser, measurePage, measureWidths, openBrowser } from '../test/narrowViewport';

/**
 * Основной номер производителя стоит в строках списков продажи за словом
 * состояния (задача 0327, решение владельца продукта 18.10.2026 «7 - а»):
 * «Фара · контрактная · 84140-12530 · № 347 · 1 шт».
 *
 * <p><b>Как это выглядело для человека.</b> Отказ называл «Фара · б/у ·
 * 84140-12530» (0320), а выдача, корзина, состав сделки, выбор на возврат
 * и заказ с площадки — только «Фара · б/у · № 347»: двух одинаковых фар
 * в списке было не различить, а по номеру деталь и опознают.
 *
 * <p>Номер — как хранится («84140-12530»), без приведения. Нет номера — нет
 * и « · »; нет состояния — «Фара · 84140-12530», без пустого слова.
 */
const RAW = '84140-12530';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('выдача продавца и корзина', () => {
  it('строка выдачи: наименование, состояние, номер производителя, № позиции', async () => {
    stubSeller([
      stock(1, 'CONTRACT', RAW),
      stock(2, 'NEW', null),
      stock(3, null, '84140-12531'),
    ]);
    await search();

    const rows = [...document.querySelectorAll('.stock-row .stock-info')]
      .map((node) => plain(node.textContent));
    expect(rows[0], 'номера производителя в выдаче нет')
      .toMatch(/^Фара · контрактная · 84140-12530 · № 1(?!\d)(?! · )/);
    expect(rows[1], 'без номера осталось висящее « · »').toMatch(/^Фара · новая · № 2(?!\d)(?! · )/);
    expect(rows[2], 'без состояния номер ушёл не на своё место')
      .toMatch(/^Фара · 84140-12531 · № 3(?!\d)(?! · )/);
  });

  it('корзина называет номер производителя', async () => {
    stubSeller([stock(1, 'CONTRACT', RAW)]);
    await search();
    fireEvent.click([...document.querySelectorAll('button')]
      .find((b) => b.textContent === 'в сделку')!);
    const basket = await screen.findByText('В сделку');
    expect(plain(basket.nextElementSibling?.textContent ?? ''), 'в корзине номера нет')
      .toContain(`Фара · контрактная · ${RAW} · № 1 · 1 шт`);
  });

  it('длинный номер обрезан «…», полный — в подсказке; настоящий не обрезан', async () => {
    const long = 'A'.repeat(1000);
    stubSeller([stock(1, 'USED', long), stock(2, 'NEW', RAW)]);
    await search();
    const [cut, whole] = [...document.querySelectorAll('.stock-info .oem-word')];
    expect(cut?.textContent, 'номер на 1000 знаков занял строку целиком')
      .toBe(`${'A'.repeat(40)}…`);
    expect(cut?.getAttribute('title'), 'полного номера нет в подсказке').toBe(long);
    expect(whole?.textContent, 'настоящий номер обрезан').toBe(RAW);
  });
});

describe('состав сделки и выбор на возврат', () => {
  it('каждая строка состава называет основной номер', async () => {
    stubDeal();
    render(<SellerScreen canSell rights={rightsAs('SELLER')} company="t_1" memberId={7} />);
    await openDeal();

    expect(lineOf('Фара с номером')).toMatch(
      new RegExp(`^Фара с номером · контрактная · ${RAW} · № 1 · 1 шт`));
    expect(lineOf('Фара без номера'), 'без номера осталось висящее « · »')
      .toMatch(/^Фара без номера · б\/у · № 2 · 1 шт/);
    expect(lineOf('Фара без состояния'), 'без состояния — пустое слово или лишний « · »')
      .toMatch(new RegExp(`^Фара без состояния · ${RAW} · № 3 · 1 шт`));
  });
});

describe('заказы с площадок', () => {
  it('строка заказа называет основной номер', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) =>
      String(input).includes('awaiting-reply') ? json([order()]) : json([])));
    render(<OrdersScreen canSell />);
    await waitFor(() => expect(document.querySelectorAll('.card').length).toBe(1));

    const lines = [...document.querySelectorAll('.card .counts li')]
      .map((li) => plain(li.textContent));
    expect(lines, 'в заказе с площадки номера производителя нет').toEqual([
      `Тросик багажника · контрактная · ${RAW} · № 17 — 1 шт.`,
      'Тросик капота · новая · № 18 — 1 шт.',
      `Тросик двери · ${RAW} · № 19 — 1 шт.`,
    ]);
  });
});

/**
 * Ширина: строка с номером читается на 360 px без прокрутки вбок, цена
 * и кнопка остаются в строке. Настоящий браузер (`narrowViewport`): jsdom
 * раскладку не считает.
 */
describe('на телефоне номер не выталкивает цену', () => {
  beforeAll(async () => {
    await openBrowser();
  }, 60_000);

  afterAll(async () => {
    await closeBrowser();
  });

  /**
   * Колонка наименования на телефоне узкая и до этой задачи (цена и кнопка
   * не сжимаются), поэтому номер обязан переноситься, а не тянуть строку:
   * «8414012530» без дефиса иначе вытолкнул бы цену. Меряется выдача, как она
   * открывается; набранную корзину и высоту строки на 360 и 390 меряет
   * `sellerBasketNarrow.test.tsx` (задача 0328).
   */
  it.each([
    ['номер с дефисом', RAW],
    ['номер без разделителей', '8414012530'],
    ['номер на пределе хранения без пробелов', 'A'.repeat(1000)],
  ])('%s: выдача на 360 px не шире экрана, цена и кнопка в строке', async (_name, number) => {
    stubSeller([
      { ...stock(1, 'REFURBISHED', number), title: 'Фара левая Toyota Camry 2007 ксенон' },
      { ...stock(2, 'USED', null), title: 'Фара левая Toyota Camry 2007' },
    ]);
    const { container } = render(<SellerScreen canSell rights={rightsAs('SELLER')} company="t" memberId={1} />);
    await search();
    const html = await settled(container);

    const { scrollWidth, clientWidth } = await measurePage(html, 360);
    expect(scrollWidth, `строка с номером уехала вбок: ${scrollWidth} при ${clientWidth}`)
      .toBeLessThanOrEqual(clientWidth);
    const actions = await measureWidths(html, '.stock-row .stock-action', 360);
    expect(actions[0], 'номер сжал цену и кнопку').toBe(actions[1]);
    const infos = await measureWidths(html, '.stock-row .stock-info', 360);
    const words = await measureWidths(html, '.stock-row .oem-word', 360);
    expect(words[0] ?? 0, 'номер шире своей строки').toBeLessThanOrEqual(infos[0] ?? 0);
  }, 30_000);
});

// ---------- заглушки ----------

function stock(partId: number, condition: string | null, primaryOem: string | null) {
  return {
    partId, number: partId, publicCode: `G${partId}`, title: 'Фара', price: '9000',
    status: 'IN_STOCK', warehouseId: 2, warehouseName: 'Ткацкая', cellCode: null,
    qty: '1', qtyReserved: '0', qtyAvailable: '1', expected: false, expectedOn: null,
    condition, primaryOem,
  };
}

function stubSeller(rows: ReturnType<typeof stock>[]): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/parts/stock')) {
      return json({ total: rows.length, facets: { vehicles: [], grades: [] }, rows });
    }
    if (url.includes('/warehouses')) {
      return json([{ id: 2, branchId: 1, name: 'Ткацкая', branchName: 'Филиал', cells: 0,
        isActive: true }]);
    }
    return json([]);
  }));
}

async function search(): Promise<void> {
  if (document.querySelector('input') === null) {
    render(<SellerScreen canSell rights={rightsAs('SELLER')} company="test" memberId={1} />);
  }
  const input = document.querySelector('input') as HTMLInputElement;
  fireEvent.change(input, { target: { value: 'фара' } });
  fireEvent.click(screen.getByRole('button', { name: 'Найти' }));
  await waitFor(() => expect(document.querySelectorAll('.stock-row').length).toBeGreaterThan(0));
}

function stubDeal(): void {
  const item = (id: number, title: string, condition: string | null, primaryOem: string | null) => ({
    id, partId: 10 + id, number: id, title, condition, primaryOem,
    quantity: '1', price: '1000', discount: null, warehouseId: 2, status: 'RESERVED',
  });
  const deal = {
    id: 19, number: 19, customerId: 1, customerName: 'Иванов Пётр',
    managerId: null, managerName: null, stage: null,
    status: 'RESERVED', reservedUntil: null,
    totalAmount: '3000', paidAmount: '0', debt: '3000',
    createdAt: '2026-09-01T10:00:00Z', issuedAt: null, warehouseId: null,
    marketplace: null, externalOrderNo: null, replyDeadline: null,
    orderAcceptedAt: null, deliveryNote: null,
    items: [
      item(1, 'Фара с номером', 'CONTRACT', RAW),
      item(2, 'Фара без номера', 'USED', null),
      item(3, 'Фара без состояния', null, RAW),
    ],
    services: [],
  };
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/customers/1/account')) {
      return json({ customerId: 1, balance: 0, entries: [] });
    }
    if (url.includes('/api/customers?')) {
      return json([{ id: 1, name: 'Иванов Пётр', phone: '+79990001122',
        email: null, customerType: 'PERSON' }]);
    }
    if (url.includes('/api/deals?customerId')) {
      return json([deal]);
    }
    if (url.includes('/api/deals/19')) {
      return json(deal);
    }
    return json([]);
  }));
}

async function openDeal(): Promise<void> {
  fireEvent.click(buttonBy((t) => t === 'Найти сделку клиента')!);
  const input = [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'имя или телефон')!;
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')!.set!;
  setter.call(input, 'Иванов');
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await waitFor(() => expect(buttonBy((t) => t.includes('Иванов'))).toBeTruthy());
  fireEvent.click(buttonBy((t) => t.includes('Иванов'))!);
  await waitFor(() => expect(buttonBy((t) => t.includes('№19'))).toBeTruthy());
  fireEvent.click(buttonBy((t) => t.includes('№19'))!);
  await waitFor(() => expect(document.querySelectorAll('.stock-info').length).toBeGreaterThan(0));
}

function order() {
  const item = (id: number, number: number, title: string, condition: string | null,
    primaryOem: string | null) => ({
    id, partId: 4 + id, number, title, condition, primaryOem,
    quantity: '1', price: '850', status: 'RESERVED', warehouseId: 2,
  });
  return {
    id: 1, number: 1, customerId: null, managerId: null, status: 'RESERVED',
    reservedUntil: null, totalAmount: '2550', paidAmount: '0', debt: '2550',
    createdAt: '2026-08-08T10:00:00Z', issuedAt: null, marketplace: 'DROM',
    externalOrderNo: 'СНК-1',
    replyDeadline: new Date(Date.now() + 2 * 24 * 3600 * 1000).toISOString(),
    orderAcceptedAt: null, deliveryNote: null,
    items: [
      item(1, 17, 'Тросик багажника', 'CONTRACT', RAW),
      item(2, 18, 'Тросик капота', 'NEW', null),
      item(3, 19, 'Тросик двери', null, RAW),
    ],
    services: [],
  };
}

function buttonBy(match: (text: string) => boolean): HTMLButtonElement | undefined {
  return [...document.querySelectorAll('button')]
    .find((b) => match((b.textContent ?? '').trim())) as HTMLButtonElement | undefined;
}

function lineOf(title: string): string {
  const node = [...document.querySelectorAll('.stock-info')]
    .find((n) => (n.textContent ?? '').includes(title));
  expect(node, `строки «${title}» на экране нет`).toBeTruthy();
  return plain(node!.textContent);
}

/** Неразрывный пробел — обычным, лишние пробелы — одним. */
function plain(text: string | null): string {
  return (text ?? '').replace(/ /g, ' ').replace(/\s+/g, ' ').trim();
}

/** Разметка, когда экран догрузился: ждём, пока она перестанет меняться. */
async function settled(container: HTMLElement): Promise<string> {
  let previous = '';
  for (let step = 0; step < 40; step += 1) {
    await act(async () => {
      await new Promise((resolve) => { setTimeout(resolve, 10); });
    });
    const html = container.innerHTML;
    if (html !== '' && html === previous) return html;
    previous = html;
  }
  return container.innerHTML;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
