import { rightsAs } from '../test/rights';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ExpectedGoods } from './ExpectedGoods';
import { IntakeScreen } from './IntakeScreen';
import { InventoryReconcile } from './InventoryReconcile';
import { InventoryScreen } from './InventoryScreen';
import { OrdersScreen } from './OrdersScreen';
import { SellerScreen } from './SellerScreen';
import { SharedDealScreen } from './SharedDealScreen';
import { StockMovesScreen } from './StockMovesScreen';
import { titleWithCondition } from '../inventory/catalog';
import { countLabel, forgetSession, type InventoryLine } from '../inventory/inventory';

/**
 * Состояние позиции видно словом и там, где его несла только пометка
 * в наименовании (задача 0318).
 *
 * <p><b>Как это выглядело для человека.</b> 0314 показала состояние отдельно
 * на складе, у продавца, на этикетке и в печати сделки. Остальные места
 * печатали одно наименование, и «новая» против «б/у» там читалась только
 * из хвоста строки — «Фара … (б/у)». Пометка из наименования уходит
 * (0315 перестаёт её дописывать, 0316 снимет с хранимого), и у каждой новой
 * позиции продавец в составе сделки, покупатель на странице по ссылке,
 * кладовщик на обходе и в перевозке, приёмщик с ожидаемым увидели бы
 * «Фара Toyota Camry 2007» — без слова, какая из сотни.
 *
 * <p>Наименования в заглушках заведены <b>без</b> пометки: состояние обязано
 * прийти полем. Строки-списки несут слово через « · » (как `ConditionWord`
 * у продавца), таблицы — столбцом «Состояние» (как «Проданные позиции»
 * и печать сделки). Пустое состояние не печатает ничего, а незнакомый код
 * не печатается кодом.
 */
const CONDITIONS: Array<[string, string]> = [
  ['NEW', 'новая'],
  ['USED', 'б/у'],
  ['REFURBISHED', 'восстановленная'],
  ['CONTRACT', 'контрактная'],
];

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('состав сделки у продавца', () => {
  beforeEach(() => stubDeal());

  it('каждая строка состава называет своё состояние, без состояния — ничего', async () => {
    render(<SellerScreen canSell rights={rightsAs('SELLER')} company="t_1" memberId={7} />);
    await openDeal();

    CONDITIONS.forEach(([, word], at) => {
      expect(lineOf(`Фара ${at + 1} `), `в составе сделки состояние «${word}» не названо`)
        .toContain(`Фара ${at + 1} · ${word} · № ${at + 1}`);
    });
    expect(lineOf('Фара без состояния'), 'пустое состояние напечатало что-то лишнее')
      .toMatch(/^\s*Фара без состояния · № 5/);
    expect(lineOf('Фара чужого кода'), 'незнакомый код уехал на экран кодом')
      .not.toContain('BROKEN');
  });
});

describe('заказы с площадок', () => {
  it('строка заказа называет состояние', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) =>
      String(input).includes('awaiting-reply') ? json([order()]) : json([])));
    render(<OrdersScreen canSell />);
    await waitFor(() => expect(document.querySelectorAll('.card').length).toBe(1));

    const lines = [...document.querySelectorAll('.card .counts li')]
      .map((li) => plain(li.textContent));
    expect(lines, 'в заказе с площадки состояние не названо').toEqual([
      'Тросик багажника · контрактная · № 17 — 1 шт.',
      'Тросик капота · № 18 — 1 шт.',
    ]);
  });
});

describe('страница сделки по ссылке', () => {
  it('покупатель видит состояние словом, а не кодом; у услуги его нет', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({
      number: 19, status: 'RESERVED', reservedUntil: null,
      total: '9500', paid: '0', debt: '9500',
      items: [
        { title: 'Фара левая', condition: 'USED', quantity: '1', price: '5000' },
        { title: 'Фара правая', condition: 'NEW', quantity: '1', price: '4000' },
        { title: 'Фара задняя', condition: 'BROKEN', quantity: '1', price: '0' },
        { title: 'Доставка', condition: null, quantity: '1', price: '500' },
      ],
    })));
    render(<SharedDealScreen company="razborka" token="abc" />);
    await waitFor(() => expect(document.querySelectorAll('li').length).toBe(4));

    const lines = [...document.querySelectorAll('li')].map((li) => plain(li.textContent));
    expect(lines[0], 'покупатель не видит, что деталь б/у').toBe('Фара левая · б/у — 1 шт. × 5 000 ₽');
    expect(lines[1]).toBe('Фара правая · новая — 1 шт. × 4 000 ₽');
    expect(lines[2], 'незнакомый код уехал покупателю как есть').toBe('Фара задняя — 1 шт. × 0 ₽');
    expect(lines[3], 'у услуги появилось состояние').toBe('Доставка — 1 шт. × 500 ₽');
    expect(document.body.textContent, 'код состояния виден покупателю').not.toMatch(/USED|NEW/);
  });
});

describe('лист обхода пересчёта', () => {
  beforeEach(async () => {
    await forgetSession();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/inventory/count' || url.startsWith('/api/inventory/count?')) {
        return json({ count: 2 });
      }
      if (url === '/api/inventory/sessions' && init?.method === 'POST') {
        return json({ id: 1, warehouseId: 2, status: 'OPEN', lines: 2, counted: 0 });
      }
      if (url.includes('/sessions/1/lines')) {
        return json([
          { partId: 1, number: 347, title: 'Фара левая', condition: 'CONTRACT',
            cellId: 10, cellCode: 'А-01-1', qtyExpected: '1', qtyCounted: null },
          // Строка листа, скачанного до правки: поля нет вовсе.
          { partId: 2, number: 348, title: 'Фара правая',
            cellId: 10, cellCode: 'А-01-1', qtyExpected: '1', qtyCounted: null },
        ]);
      }
      return json([]);
    }));
  });

  it('строка листа называет состояние; лист без поля не ломается', async () => {
    render(<InventoryScreen reference={inventoryReference()} onCount={vi.fn()} />);
    fireEvent.change(await waitFor(() => document.querySelector('select') as HTMLSelectElement),
      { target: { value: '2' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть новую' }));

    const contract = plain((await screen.findByText('Фара левая')).closest('li')!.textContent);
    expect(contract, `в листе обхода состояние не названо: ${contract}`)
      .toContain('Фара левая · контрактная · № 347');
    const old = plain(screen.getByText('Фара правая').closest('li')!.textContent);
    expect(old).toContain('Фара правая · № 348');
  });

  it.each(CONDITIONS)('подпись подсчёта в очереди отправки: %s — «%s»', (code, word) => {
    expect(countLabel(line(code), '2')).toBe(`Фара левая · ${word} · 2 шт`);
  });

  it('подпись подсчёта без состояния — как раньше', () => {
    expect(countLabel(line(null), '2')).toBe('Фара левая · 2 шт');
    expect(titleWithCondition('Фара', 'BROKEN')).toBe('Фара');
  });
});

describe('сведение расхождений пересчёта', () => {
  it('столбец «Состояние» за «Деталью», в нём слово', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/discrepancies')) {
        return json([
          { partId: 1, number: 347, title: 'Фара левая', condition: 'REFURBISHED',
            qtyExpectedAtOpen: 1, qtyExpectedAtCount: 1, qtyCounted: 0,
            delta: -1, shortage: true, applied: false },
          { partId: 2, number: 348, title: 'Фара правая', condition: null,
            qtyExpectedAtOpen: 1, qtyExpectedAtCount: 1, qtyCounted: 0,
            delta: -1, shortage: true, applied: false },
        ]);
      }
      if (/\/api\/inventory\/sessions\/\d+$/.test(url)) {
        return json(inventorySession());
      }
      if (url.includes('/api/inventory/sessions')) {
        return json({ rows: [inventorySession()], total: 1 });
      }
      return json([]);
    }));
    render(<InventoryReconcile reference={inventoryReference()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Выполненные' }));
    fireEvent.click(await screen.findByText('Подсчёт завершён'));
    await waitFor(() => expect(screen.getByText('Фара левая')).toBeTruthy());

    expectColumn('Фара левая', 'Деталь', 'восстановленная');
    expectColumn('Фара правая', 'Деталь', '');
  });
});

describe('состав перевозки', () => {
  it('столбец «Состояние» за «Наименованием», в нём слово', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/stock/moves/4/lines')) {
        return json([
          { partId: 11, number: 347, publicCode: 'A1', title: 'Фара левая',
            condition: 'USED', qty: '1' },
          { partId: 12, number: 348, publicCode: 'A2', title: 'Фара правая',
            condition: 'NEW', qty: '1' },
        ]);
      }
      if (url.includes('/api/stock/moves')) {
        return json([{
          id: 4, number: 4, createdAt: '2026-09-05T20:01:00Z',
          fromWarehouse: 'Ткацкая', toWarehouse: 'Ангар',
          lines: 2, note: null, author: 'Иванов',
        }]);
      }
      return json([]);
    }));
    render(<StockMovesScreen rights={rightsAs('OWNER')} />);
    await waitFor(() => expect(screen.getByText('Ангар')).toBeTruthy());
    fireEvent.click(screen.getByText('Ангар').closest('tr')!);
    await waitFor(() => expect(screen.getByText('Фара левая')).toBeTruthy());

    expectColumn('Фара левая', 'Наименование', 'б/у');
    expectColumn('Фара правая', 'Наименование', 'новая');
  });
});

describe('ожидаемый товар', () => {
  it('у владельца в карточке поставки строка называет состояние', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/supplies/19/expected-parts')) {
        return json([
          { id: 5, number: 410, title: 'Фара левая', condition: 'NEW',
            quantity: 3, price: 9000, received: 0, preordered: 0 },
          { id: 6, number: 411, title: 'Фара правая', condition: 'CONTRACT',
            quantity: 1, price: 8000, received: 0, preordered: 0 },
        ]);
      }
      return json([]);
    }));
    render(<ExpectedGoods supply={{ id: 19, kind: 'CONTAINER', number: '18', supplierName: null,
      status: 'EXPECTED', arrivedOn: null, expectedOn: null } as never}
      online onChanged={() => {}} />);

    await waitFor(() => expect(screen.getByText(/№ 410/)).toBeTruthy());
    const lines = [...document.querySelectorAll('li')].map((li) => plain(li.textContent));
    expect(lines.find((l) => l.includes('№ 410')), 'у ожидаемой позиции состояние не названо')
      .toMatch(/^№ 410 · Фара левая · новая · обещано/);
    expect(lines.find((l) => l.includes('№ 411'))).toMatch(/^№ 411 · Фара правая · контрактная/);
  });

  it('у приёмщика «Ожидалось по этой поставке» и строка партии называют состояние', () => {
    render(<IntakeScreen reference={phoneReference()} onSend={vi.fn()} />);
    selectByOption('— выберите склад —', '2');
    selectByOption('не указана', '19');

    const offered = plain(screen.getByText('Фара левая Toyota Camry').closest('li')!.textContent);
    expect(offered, 'в «Ожидалось по этой поставке» состояние не названо')
      .toContain('Фара левая Toyota Camry · контрактная · ждём 3 шт');

    fireEvent.click(screen.getByRole('button', { name: 'Принять' }));
    const batch = [...document.querySelectorAll('li')]
      .map((li) => plain(li.textContent))
      .find((text) => text.includes('заведена заранее'));
    expect(batch, 'в строке партии состояние принятой позиции пропало')
      .toMatch(/^Фара левая Toyota Camry · контрактная · 9000 ₽/);
  });
});

// ---------- помощники ----------

/**
 * Клетка столбца «Состояние» в строке позиции — и столбец стоит сразу
 * за наименованием. По столбцу, а не по тексту строки: слово, уехавшее
 * в чужую клетку, тест обязан назвать.
 */
function expectColumn(title: string, after: string, word: string): void {
  const row = screen.getByText(title).closest('tr')!;
  const table = row.closest('table')!;
  const headers = [...table.querySelectorAll('thead th')].map((th) => (th.textContent ?? '').trim());
  const at = headers.indexOf('Состояние');
  expect(at, `столбца «Состояние» нет: ${headers.join(' · ')}`).toBeGreaterThanOrEqual(0);
  expect(headers[at - 1], `«Состояние» стоит не за «${after}»`).toBe(after);
  const cells = [...row.querySelectorAll('td')].map((td) => (td.textContent ?? '').trim());
  expect(cells[at], `в столбце «Состояние» у «${title}» не то`).toBe(word);
}

function lineOf(title: string): string {
  const node = [...document.querySelectorAll('.stock-info')]
    .find((n) => (n.textContent ?? '').includes(title));
  expect(node, `строки «${title}» на экране нет`).toBeTruthy();
  return plain(node!.textContent);
}

/** Неразрывный пробел (escape, а не сам символ) — обычным. */
function plain(text: string | null): string {
  return (text ?? '').replace(/ /g, ' ').replace(/ /g, ' ').trim();
}

function line(condition: string | null): InventoryLine {
  return {
    partId: 1, number: 347, title: 'Фара левая', condition,
    cellId: null, cellCode: null, qtyExpected: '1', qtyCounted: null,
  };
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

function buttonBy(match: (text: string) => boolean): HTMLButtonElement | undefined {
  return [...document.querySelectorAll('button')]
    .find((b) => match((b.textContent ?? '').trim())) as HTMLButtonElement | undefined;
}

function stubDeal(): void {
  const item = (id: number, title: string, condition: string | null) => ({
    id, partId: 10 + id, number: id, title, condition,
    quantity: '1', price: '1000', discount: null, warehouseId: 2, status: 'RESERVED',
  });
  const deal = {
    id: 19, number: 19, customerId: 1, customerName: 'Иванов Пётр',
    managerId: null, managerName: null, stage: null,
    status: 'RESERVED', reservedUntil: null,
    totalAmount: '6000', paidAmount: '0', debt: '6000',
    createdAt: '2026-09-01T10:00:00Z', issuedAt: null, warehouseId: null,
    marketplace: null, externalOrderNo: null, replyDeadline: null,
    orderAcceptedAt: null, deliveryNote: null,
    items: [
      ...CONDITIONS.map(([code], at) => item(at + 1, `Фара ${at + 1}`, code)),
      item(5, 'Фара без состояния', null),
      item(6, 'Фара чужого кода', 'BROKEN'),
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

function order() {
  return {
    id: 1, number: 1, customerId: null, managerId: null, status: 'RESERVED',
    reservedUntil: null, totalAmount: '1700', paidAmount: '0', debt: '1700',
    createdAt: '2026-08-08T10:00:00Z', issuedAt: null, marketplace: 'DROM',
    externalOrderNo: 'СНК-1',
    replyDeadline: new Date(Date.now() + 2 * 24 * 3600 * 1000).toISOString(),
    orderAcceptedAt: null, deliveryNote: null,
    items: [
      { id: 1, partId: 5, number: 17, title: 'Тросик багажника', condition: 'CONTRACT',
        quantity: '1', price: '850', status: 'RESERVED', warehouseId: 2 },
      { id: 2, partId: 6, number: 18, title: 'Тросик капота', condition: null,
        quantity: '1', price: '850', status: 'RESERVED', warehouseId: 2 },
    ],
    services: [],
  };
}

function inventorySession() {
  return {
    id: 8, warehouseId: 2, warehouseName: 'Ткацкая',
    selection: 'Ткацкая · весь склад', status: 'COUNTED',
    startedAt: '2026-09-05T10:00:00Z', appliedAt: null,
    lines: 2, counted: 2, note: null,
  };
}

function inventoryReference(): never {
  return {
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
    supplies: [], donors: [], cells: [], partNames: [],
  } as never;
}

function phoneReference() {
  return {
    loadedAt: new Date().toISOString(),
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
    supplies: [
      { id: 19, number: '18', supplierName: 'Иокогама', kind: 'CONTAINER',
        status: 'EXPECTED', arrivedOn: null, expectedOn: null },
    ],
    donors: [],
    partNames: [],
    expectedParts: [
      { id: 5, supplyId: 19, title: 'Фара левая Toyota Camry', condition: 'CONTRACT',
        price: 9000, remaining: 3 },
    ],
  } as never;
}

function selectByOption(optionText: string, value: string): void {
  const select = [...document.querySelectorAll('select')]
    .find((s) => [...s.options].some((o) => o.textContent === optionText))!;
  fireEvent.change(select, { target: { value } });
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
