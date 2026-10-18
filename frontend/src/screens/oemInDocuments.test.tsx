import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { DealPrintMenu } from './DealPrintMenu';
import { InventoryReconcile } from './InventoryReconcile';
import { InventoryScreen } from './InventoryScreen';
import { ReportsScreen } from './ReportsScreen';
import { SharedDealScreen } from './SharedDealScreen';
import { StockMovesScreen } from './StockMovesScreen';
import { forgetSession } from '../inventory/inventory';
import { closeBrowser, measurePage, openBrowser } from '../test/narrowViewport';

/**
 * Основной номер производителя там, куда 0327 его не донесла (задача 0329,
 * ответ владельца продукта 18.10.2026 «Всё да»): страница сделки по ссылке,
 * печать сделки, лист обхода, сведение пересчёта, состав перевозки, отчёты.
 *
 * <p><b>Как это выглядело для человека.</b> Продавец в выдаче видел «Фара ·
 * контрактная · 84140-12530», а покупатель в чеке и по ссылке, кладовщик
 * в листе обхода, владелец в отчёте — ту же позицию без номера: две «Фары
 * левые Toyota Camry · б/у» в чеке не различить.
 *
 * <p>Тем же словом, что у продавца (`OemWord`): « · » и номер, как хранится.
 * Нет номера — ничего, без висящего « · ». В строках-списках номер идёт
 * за словом состояния, в таблицах — в клетке наименования (столбец
 * «Состояние» стоит следом своей клеткой). На бумаге номер не обрезается:
 * подсказки у неё нет, длинный переносится в клетке.
 */
const RAW = '84140-12530';
const LONG = `${'8414012530'.repeat(10)}`;

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('страница сделки по ссылке', () => {
  it('покупатель видит номер за словом состояния; без номера и у услуги — ничего', async () => {
    stubShared();
    render(<SharedDealScreen company="razborka" token="abc" />);
    await waitFor(() => expect(document.querySelectorAll('li').length).toBe(4));

    const lines = [...document.querySelectorAll('li')].map((li) => plain(li.textContent));
    expect(lines[0], 'номера производителя у покупателя нет')
      .toBe(`Фара левая · б/у · ${RAW} — 1 шт. × 5 000 ₽`);
    expect(lines[1], 'без номера осталось висящее « · »').toBe('Фара правая · новая — 1 шт. × 4 000 ₽');
    expect(lines[2], 'без состояния номер ушёл не на своё место')
      .toBe(`Фара задняя · ${RAW} — 1 шт. × 3 000 ₽`);
    expect(lines[3], 'у услуги появился номер').toBe('Доставка — 1 шт. × 500 ₽');
  });
});

describe('печать сделки', () => {
  it.each([
    ['Товарный чек'],
    ['Товарная накладная'],
    ['Счёт на юр. лицо'],
  ])('%s: номер за наименованием, без номера — без « · », длинный не обрезан', async (form) => {
    stubPrint();
    render(<DealPrintMenu dealId={20} />);
    fireEvent.click(button('Печатать')!);
    fireEvent.click(button(form)!);
    await waitFor(() => expect(document.querySelector('.print-table')).toBeTruthy());

    const titles = printTitles();
    expect(titles[0], `в «${form}» номера производителя нет`).toBe(`Фара левая · ${RAW}`);
    expect(titles[1], `в «${form}» без номера осталось висящее « · »`).toBe('Фара правая');
    expect(titles[2], `в «${form}» длинный номер обрезан — на бумаге его не дочитать`)
      .toBe(`Бампер · ${LONG}`);
    expect(titles[3], `у услуги в «${form}» появился номер`).toBe('Доставка');
  });
});

describe('лист обхода пересчёта', () => {
  it('строка листа называет номер; лист без поля и строка без номера — без « · »', async () => {
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
          { partId: 1, number: 347, title: 'Фара левая', condition: 'CONTRACT', primaryOem: RAW,
            cellId: 10, cellCode: 'А-01-1', qtyExpected: '1', qtyCounted: null },
          // Строка листа, скачанного до правки: поля нет вовсе.
          { partId: 2, number: 348, title: 'Фара правая', condition: 'USED',
            cellId: 10, cellCode: 'А-01-1', qtyExpected: '1', qtyCounted: null },
        ]);
      }
      return json([]);
    }));
    render(<InventoryScreen reference={inventoryReference()} onCount={vi.fn()} />);
    fireEvent.change(await waitFor(() => document.querySelector('select') as HTMLSelectElement),
      { target: { value: '2' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть новую' }));

    const left = plain((await screen.findByText('Фара левая')).closest('li')!.textContent);
    expect(left, `в листе обхода номера нет: ${left}`)
      .toContain(`Фара левая · контрактная · ${RAW} · № 347`);
    const right = plain(screen.getByText('Фара правая').closest('li')!.textContent);
    expect(right, 'без номера осталось висящее « · »').toContain('Фара правая · б/у · № 348');
  });
});

describe('сведение расхождений', () => {
  it('номер в клетке «Детали», столбец «Состояние» на месте', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/discrepancies')) {
        return json([
          discrepancy(1, 'Фара левая', RAW),
          discrepancy(2, 'Фара правая', null),
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
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBeGreaterThan(1));

    expectTitleCell('Фара левая', 'Деталь', `Фара левая · ${RAW}`, 'б/у');
    expectTitleCell('Фара правая', 'Деталь', 'Фара правая', 'б/у');
  });
});

describe('состав перевозки', () => {
  it('номер в клетке «Наименования»', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/stock/moves/4/lines')) {
        return json([
          { partId: 11, number: 347, publicCode: 'A1', title: 'Фара левая',
            condition: 'USED', primaryOem: RAW, qty: '1' },
          { partId: 12, number: 348, publicCode: 'A2', title: 'Фара правая',
            condition: 'NEW', primaryOem: null, qty: '1' },
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
    render(<StockMovesScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Ангар')).toBeTruthy());
    fireEvent.click(screen.getByText('Ангар').closest('tr')!);
    await waitFor(() => expect(rowOf('Фара левая')).toBeTruthy());

    expectTitleCell('Фара левая', 'Наименование', `Фара левая · ${RAW}`, 'б/у');
    expectTitleCell('Фара правая', 'Наименование', 'Фара правая', 'новая');
  });
});

describe('отчёты владельца', () => {
  it('проданные позиции: номер в клетке наименования', async () => {
    stubReports();
    render(<ReportsScreen canRead />);
    await waitFor(() => expect(rowOf('Фара продана')).toBeTruthy());

    expectTitleCell('Фара продана', 'Наименование', `Фара продана · ${RAW}`, 'б/у');
    expectTitleCell('Дверь продана', 'Наименование', 'Дверь продана', 'б/у');
  });

  it('разрез по машине: номер в клетке наименования', async () => {
    stubReports();
    render(<ReportsScreen canRead />);
    const machines = await screen.findAllByLabelText('Машина');
    const machine = machines[machines.length - 1]!;
    await waitFor(() => expect(machine.querySelectorAll('option')).toHaveLength(2));
    fireEvent.change(machine, { target: { value: '7' } });
    await waitFor(() => expect(rowOf('Фара с машины')).toBeTruthy());

    expectTitleCell('Фара с машины', 'Наименование', `Фара с машины · ${RAW}`, 'контрактная');
    expectTitleCell('Бампер с машины', 'Наименование', 'Бампер с машины', '');
  });
});

/**
 * Настоящим браузером: страница по ссылке открывается у покупателя
 * на телефоне, лист обхода — у кладовщика на телефоне, бумага — A4.
 * Номер без разделителей переносится, а не растягивает строку.
 */
describe('ширина', () => {
  beforeAll(async () => { await openBrowser(); }, 30_000);
  afterAll(async () => { await closeBrowser(); });

  it.each([360, 390])('страница по ссылке на %i px с номером на 100 знаков не шире экрана',
    async (width) => {
      stubShared(LONG);
      const { container } = render(<SharedDealScreen company="razborka" token="abc" />);
      await waitFor(() => expect(document.querySelectorAll('li').length).toBe(4));
      const page = await measurePage(await settled(container), width);
      expect(page.scrollWidth, `страница по ссылке уехала вбок: ${page.scrollWidth} при ${width}`)
        .toBeLessThanOrEqual(page.clientWidth);
    }, 30_000);

  it('печать на ширине листа A4 с номером на 100 знаков не шире листа', async () => {
    stubPrint();
    const { container } = render(<DealPrintMenu dealId={20} />);
    fireEvent.click(button('Печатать')!);
    fireEvent.click(button('Товарная накладная')!);
    await waitFor(() => expect(document.querySelector('.print-table')).toBeTruthy());
    // 210 мм при 96 точках на дюйм.
    const page = await measurePage(await settled(container), 794);
    expect(page.scrollWidth, `документ шире листа: ${page.scrollWidth} при 794`)
      .toBeLessThanOrEqual(page.clientWidth);
  }, 30_000);
});

// ---------- помощники ----------

/**
 * Клетка наименования несёт номер, и столбец «Состояние» стоит сразу за ней
 * со своим словом: номер не уехал в чужую клетку и не сдвинул столбцы.
 */
function expectTitleCell(title: string, header: string, shown: string, word: string): void {
  const row = rowOf(title)!;
  const headers = [...row.closest('table')!.querySelectorAll('thead th')]
    .map((th) => plain(th.textContent));
  const at = headers.indexOf(header);
  expect(at, `столбца «${header}» нет: ${headers.join(' · ')}`).toBeGreaterThanOrEqual(0);
  expect(headers[at + 1], `за «${header}» стоит не «Состояние»`).toBe('Состояние');
  const cells = [...row.children].map((td) => plain(td.textContent));
  expect(cells[at], `в клетке «${header}» у «${title}» не то`).toBe(shown);
  expect(cells[at + 1], `в столбце «Состояние» у «${title}» не то`).toBe(word);
}

function rowOf(title: string): HTMLTableRowElement | undefined {
  return [...document.querySelectorAll('tbody tr')]
    // Свои клетки строки, а не вложенные: состав перевозки — таблица внутри строки.
    .find((tr) => [...tr.children]
      .some((td) => plain(td.textContent).startsWith(title))) as HTMLTableRowElement | undefined;
}

function printTitles(): string[] {
  const table = document.querySelector('.print-table')!;
  const headers = [...table.querySelectorAll('th')].map((th) => plain(th.textContent));
  const at = headers.indexOf('Наименование');
  return [...table.querySelectorAll('tbody tr')].map((tr) => plain(tr.children[at]?.textContent));
}

function button(label: string): HTMLButtonElement | undefined {
  return [...document.querySelectorAll('button')]
    .find((b) => plain(b.textContent) === label) as HTMLButtonElement | undefined;
}

/** Неразрывный пробел — обычным, лишние пробелы — одним. */
function plain(text: string | null | undefined): string {
  return (text ?? '').replace(/ /g, ' ').replace(/ /g, ' ').replace(/\s+/g, ' ').trim();
}

function stubShared(first = RAW): void {
  vi.stubGlobal('fetch', vi.fn(async () => json({
    number: 19, status: 'RESERVED', reservedUntil: null,
    total: '12500', paid: '0', debt: '12500',
    items: [
      { title: 'Фара левая', condition: 'USED', primaryOem: first, quantity: '1', price: '5000' },
      { title: 'Фара правая', condition: 'NEW', primaryOem: null, quantity: '1', price: '4000' },
      { title: 'Фара задняя', condition: null, primaryOem: RAW, quantity: '1', price: '3000' },
      { title: 'Доставка', condition: null, primaryOem: null, quantity: '1', price: '500' },
    ],
  })));
}

function stubPrint(): void {
  vi.stubGlobal('print', vi.fn());
  const line = (title: string, primaryOem: string | null, condition: string | null) => ({
    title, condition, primaryOem, quantity: '1', price: '1000.00', amount: '1000.00',
  });
  vi.stubGlobal('fetch', vi.fn(async () => json({
    dealId: 20, number: 20, createdAt: '2026-09-05T12:00:00Z', issuedAt: null,
    seller: { warehouseId: 2, warehouseName: 'Ткацкая', details: 'ИП Санин Д.В.', problem: null },
    buyer: { name: 'Частное лицо', phone: null, inn: null, companyName: null, note: null },
    legal: {
      name: 'ООО «Разборка»', address: null, inn: '7701234567', kpp: null, bankName: null,
      bankBic: null, bankAccount: null, bankCorrAccount: null, director: null,
      chiefAccountant: null,
    },
    extraText: null, vatNote: null, clientSignature: false, issuerSignature: false,
    lines: [
      line('Фара левая', RAW, 'б/у'),
      line('Фара правая', null, 'новая'),
      line('Бампер', LONG, 'б/у'),
      line('Доставка', null, null),
    ],
    total: '4000.00', paid: '0.00', debt: '4000.00',
  })));
}

function stubReports(): void {
  const sold = (itemId: number, title: string, primaryOem: string | null) => ({
    itemId, soldAt: '2026-09-05T10:00:00Z', dealId: 9, dealNumber: 1274 + itemId,
    partId: 10 + itemId, number: 346 + itemId, publicCode: `C${itemId}`, title,
    condition: 'б/у', primaryOem, price: 1200, listPrice: 1200, quantity: 1,
    costPrice: 700, profit: 500, warehouse: 'Ткацкая', manager: 'Иван Продавцов',
    supplyNumber: null, donorCode: '500',
  });
  const received = (partId: number, title: string, condition: string | null,
    primaryOem: string | null) => ({
    partId, number: 400 + partId, publicCode: `R${partId}`, kind: 'Фара', title, condition,
    primaryOem, quantity: 1, price: 7500, costPrice: 700, supplyNumber: 'К-1', date: '2026-08-30',
  });
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/intake/donors')) {
      return json([{
        id: 7, code: '418', brand: 'Toyota', model: 'Camry', year: 2007,
        vin: null, status: 'DISMANTLING', note: null, location: null,
      }]);
    }
    if (url.includes('/reports/donors/7/items')) {
      return json({
        rows: url.includes('tab=received')
          ? [received(1, 'Фара с машины', 'CONTRACT', RAW), received(2, 'Бампер с машины', null, null)]
          : [],
        totals: { items: 2, quantity: 2, amount: 15000 },
        nextAfter: null,
      });
    }
    if (url.includes('/reports/donors')) {
      return json({ totals: { donors: 1, totalCost: 0, revenue: 0, stockValue: 0 }, rows: [] });
    }
    if (url.includes('/reports/sold-items')) {
      return json({
        rows: [sold(1, 'Фара продана', RAW), sold(2, 'Дверь продана', null)],
        totals: { items: 2, quantity: 2, revenue: 2400, cost: 1400, profit: 1000, withoutCost: 0 },
        nextAfter: null, managers: [],
      });
    }
    if (url.includes('/reports/customers')) {
      return json({
        totals: { advances: 0, withAdvance: 0, debts: 0, withDebt: 0, customers: 0, problems: [] },
        rows: [],
      });
    }
    if (url.includes('/reports/summary')) {
      return json({
        parts: { qty: 0, amount: 0 }, wheels: { qty: 0, amount: 0 },
        deals: { count: 0, amount: 0, prepaid: 0 },
      });
    }
    if (url.includes('/reports/payments')) {
      return json({
        month: '2026-08', rows: [], totals: { payments: 0, incoming: 0, outgoing: 0, total: 0 },
      });
    }
    if (url.includes('/organization/warehouses')) {
      return json([]);
    }
    return json({ month: '2026-08', rows: [] });
  }));
}

function discrepancy(partId: number, title: string, primaryOem: string | null) {
  return {
    partId, number: 346 + partId, title, condition: 'USED', primaryOem,
    qtyExpectedAtOpen: 1, qtyExpectedAtCount: 1, qtyCounted: 0,
    delta: -1, shortage: true, applied: false,
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
