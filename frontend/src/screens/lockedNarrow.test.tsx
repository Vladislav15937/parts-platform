import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { forgetCatalog, rightsFrom, type Rights } from '../access/rights';
import { SessionProvider } from '../auth/SessionProvider';
import type { CatalogRow, Warehouse } from '../inventory/catalog';
import { rightsWith } from '../test/rights';
import {
  PHONE_WIDTH, closeBrowser, measureLayout, measureOverflow, measurePage, measureWidths, openBrowser,
} from '../test/narrowViewport';
import { CatalogScreen } from './CatalogScreen';
import { CustomersScreen } from './CustomersScreen';
import { FeedsScreen } from './FeedsScreen';
import { HomeScreen } from './HomeScreen';
import { PartCard } from './PartCard';
import { WheelsScreen } from './WheelsScreen';

/**
 * Погашенное с причиной помещается в телефон (задача 0044, разбор PR 508).
 *
 * <p>Сторож ширины (`narrowViewport.test.tsx`) меряет разделы глазами
 * владельца — у него всё открыто, и ни одной погашенной кнопки он не видит.
 * А причина у погашенной — видимый текст в строку длиной до сотни знаков
 * («Нет права «Заводить, выключать и удалять выгрузки» — его даёт владелец
 * на экране «Сотрудники»»), и стоит она в тех же рядах кнопок, которые
 * уже уводили страницу вбок (0027, 0031). Здесь те же экраны меряются
 * под правами, при которых погашено всё, что может быть погашено,
 * и со словами прав из справочника сервера — самыми длинными, какие есть.
 *
 * <p>Мерится и обратная сторона: причина переносится, а не сжимается
 * в столбик по букве (`overflow-wrap: anywhere` это позволяет). Пустая
 * выборка прошла бы любое утверждение о ширине, поэтому число причин
 * на экране проверяется отдельно.
 */
describe('погашенное на телефоне не уводит страницу вбок', () => {
  beforeAll(async () => {
    await openBrowser();
  }, 60_000);

  afterAll(async () => {
    await closeBrowser();
  });

  beforeEach(() => {
    forgetCatalog();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => respond(String(input))));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  /** Экран под правами, при которых погашено всё, что на нём гаснет. */
  const CASES: Array<[string, (rights: Rights) => Promise<void>, Rights, number]> = [
    ['склад', async (rights) => {
      render(<CatalogScreen rights={rights} />);
    }, rightsWith(), 3],
    ['колёса', async (rights) => {
      render(<WheelsScreen canIntake={false} rights={rights} />);
    }, rightsWith(), 2],
    ['выгрузки', async (rights) => {
      render(<FeedsScreen rights={rights} />);
    }, rightsWith('marketplace.view'), 3],
    ['карточка позиции', async (rights) => {
      render(<PartCard row={row()} warehouses={WAREHOUSES} rights={rights}
                       onClose={() => {}} onChanged={() => {}} />);
    }, rightsWith(), 4],
    ['лицевой счёт в карточке клиента', openAccount, rightsWith('customer.view'), 1],
  ];

  it.each(CASES)('%s: причины погашенного помещаются в экран 390', async (name, show, rights, count) => {
    await show(rights);
    await waitFor(() => expect(
      document.querySelectorAll('.locked-action__reason').length,
      `${name}: погашенных с причиной на экране меньше, чем ждали — мерить нечего`,
    ).toBe(count));
    // Слово права — из справочника: без него причина короче настоящей.
    await waitFor(() => expect(document.body.textContent).toContain('попросите владельца'));
    await waitFor(() => expect(document.body.textContent).not.toContain('нет права на это действие'));

    const html = await settled(document.body);
    // Мерится и сама причина, а не только её обёртка: в карточке позиции окно
    // обрезает содержимое (`overflow: hidden`), и страница там не уезжает,
    // даже когда текст причины вылез за край, — его просто не видно.
    const PARTS = ['.locked-action', '.locked-action button', '.locked-action__reason'];
    const { scrollWidth, clientWidth, boxes } = await measureLayout(html, PARTS);
    const outside = PARTS.flatMap((part) => (boxes[part] ?? [])
      .filter((box) => box.right > clientWidth || box.left < 0)
      .map((box) => `${part}: ${box.left}…${box.right}`));
    expect(
      outside,
      `${name}: погашенная кнопка с причиной вылезла за экран ${PHONE_WIDTH}`,
    ).toEqual([]);

    const known = KNOWN_WIDE[name];
    if (known === undefined) {
      expect(
        scrollWidth,
        `${name}: погашенное уводит страницу вбок на ${scrollWidth - clientWidth} пикселей `
        + `(scrollWidth ${scrollWidth} при clientWidth ${clientWidth}, экран ${PHONE_WIDTH})`,
      ).toBe(clientWidth);
    } else {
      // Экран уезжает и у владельца, без единой погашенной кнопки. Погашенное
      // обязано не добавлять к этому ничего — ширина та же, что у открытого.
      cleanup();
      await show(rightsFrom(true, []));
      await waitFor(() => expect(document.querySelectorAll('.locked-action').length).toBe(0));
      const open = await measurePage(await settled(document.body));
      expect(
        open.scrollWidth,
        `${name}: числится уезжающим вбок (${known}), а у владельца помещается `
        + `(${open.scrollWidth} при ${open.clientWidth}). Починили — уберите строку из KNOWN_WIDE.`,
      ).toBeGreaterThan(open.clientWidth);
      expect(
        scrollWidth,
        `${name}: погашенное добавило ${scrollWidth - open.scrollWidth} пикселей к открытому `
        + `(${scrollWidth} против ${open.scrollWidth})`,
      ).toBe(open.scrollWidth);
    }

    const spilled = await measureOverflow(html, '.locked-action__reason');
    expect(
      spilled,
      `${name}: текст причины шире своей коробки — вылез и обрезан окном`,
    ).toEqual([]);

    const widths = await measureWidths(html, '.locked-action__reason');
    expect(widths.length, `${name}: причин в замере нет`).toBe(count);
    const squeezed = widths.filter((width) => width < 150);
    expect(
      squeezed,
      `${name}: причина сжата в столбик, а не перенесена: ширины ${widths.join(', ')} `
      + `при экране ${PHONE_WIDTH}`,
    ).toEqual([]);
  }, 30_000);

  /**
   * Погашенный раздел: вместо экрана — причина, и у «Пересчёта» она самая
   * длинная (все три права, открывающие раздел). Меряется разметка, которую
   * рисует сам `HomeScreen`, а не её копия.
   */
  it('погашенный раздел «Пересчёт» помещается в экран 390', async () => {
    render(<SessionProvider><HomeScreen /></SessionProvider>);
    const rail = await waitFor(() => {
      const found = [...document.querySelectorAll('.rail__item--locked')]
        .find((b) => (b.textContent ?? '').trim() === 'Пересчёт') as HTMLButtonElement | undefined;
      expect(found, 'раздел «Пересчёт» не погашен').toBeTruthy();
      return found!;
    });
    fireEvent.click(rail);
    await waitFor(() => expect(document.querySelector('.locked-section')?.textContent)
      .toContain('Сводить расхождения пересчёта'));

    await settled(document.body);
    const html = document.querySelector('.locked-section')!.outerHTML;
    const { scrollWidth, clientWidth } = await measurePage(html);
    expect(
      scrollWidth,
      `Погашенный раздел уводит страницу вбок на ${scrollWidth - clientWidth} пикселей `
      + `(scrollWidth ${scrollWidth} при clientWidth ${clientWidth}, экран ${PHONE_WIDTH})`,
    ).toBe(clientWidth);
  }, 30_000);
});

/**
 * Экраны, уезжающие вбок и без погашенного, — очередь работы, а не разрешение,
 * и меряются наоборот (как `KNOWN_WIDE` у сторожа ширины): починка валит тест
 * требованием убрать строку.
 */
const KNOWN_WIDE: Record<string, string> = {
  // Ряд вкладок карточки клиента («Сделки», «Возвраты», «Платежи»,
  // «Движения по счёту») выходит до 403 при 390 — у владельца так же.
  // Не 0044: найдено этим замером, названо в PR 508 отдельной находкой.
  'лицевой счёт в карточке клиента': 'вкладки карточки клиента, 403 при 390',
};

async function openAccount(rights: Rights): Promise<void> {
  render(<CustomersScreen rights={rights} company="t_1" memberId={7} onOpenDeal={() => {}} />);
  await waitFor(() => expect(screen.getByText(CUSTOMER.name)).toBeTruthy());
  fireEvent.click(screen.getByText(CUSTOMER.name));
  fireEvent.click(await screen.findByRole('button', { name: 'Пополнить / Списать' }));
}

/**
 * Слова прав — как в справочнике сервера (`Permission`): ширину причины
 * задают они, и короче настоящих их брать нельзя.
 */
const ACCESS_CATALOG = [{ title: 'Склад', permissions: [
  { key: 'part.edit', title: 'Править позиции и цены', hint: null },
  { key: 'stock.move', title: 'Перевозить товар между складами', hint: null },
  { key: 'stock.write_off', title: 'Списывать товар', hint: null },
  { key: 'photos.manage', title: 'Добавлять и удалять снимки', hint: null },
  { key: 'export.table', title: 'Скачивать склад таблицей', hint: null },
  { key: 'marketplace.manage', title: 'Заводить, выключать и удалять выгрузки', hint: null },
  { key: 'marketplace.configure', title: 'Настраивать отбор и цены выгрузки', hint: null },
  { key: 'customer.account.correct', title: 'Исправлять остаток лицевого счёта', hint: null },
  { key: 'inventory.count', title: 'Считать товар на пересчёте', hint: null },
  { key: 'inventory.journal.view', title: 'Смотреть журнал пересчётов', hint: null },
  { key: 'inventory.reconcile', title: 'Сводить расхождения пересчёта', hint: null },
] }];

const CUSTOMER = {
  id: 1, name: 'Автосервис на Русской', phone: null, email: null, customerType: 'PERSON',
  note: null, publicNote: null, inn: null, companyName: null, balance: 1000,
};

function respond(url: string): Response {
  if (url.includes('/api/auth/csrf')) return new Response(null, { status: 204 });
  if (url.includes('/api/auth/me')) {
    return json({
      memberId: 5, login: 'smotrit', displayName: 'Иван', role: 'VIEWER', roleName: 'Без прав',
      companySchema: 't_000441', owner: false, permissions: [],
    });
  }
  if (url.endsWith('/api/access/catalog')) return json(ACCESS_CATALOG);
  if (url.includes('/api/marketplace-accounts')) {
    return json(url.includes('deleted=true') ? [] : [FEED]);
  }
  if (url.includes('/api/catalog/vehicles')) {
    return json({ brands: [], models: [], generations: [], modifications: [] });
  }
  if (url.includes('/api/parts/catalog') || url.includes('/api/wheels')) {
    return json({ total: 0, warehouses: [], filterable: [], rows: [] });
  }
  if (url.includes('/api/customers/directory')) return json({ items: [CUSTOMER], total: 1 });
  if (url.includes('/account')) return json({ customerId: 1, balance: 1000, entries: [] });
  if (url.includes('/api/customers/')) return json(CUSTOMER);
  if (url.includes('/api/deals/returns')) return json({ items: [], total: 0, totalAmount: '0' });
  if (url.endsWith('/api/intake/reference')) {
    return json({ loadedAt: new Date().toISOString(), warehouses: [], supplies: [],
      donors: [], partNames: [], expectedParts: [] });
  }
  return json([]);
}

const WAREHOUSES: Warehouse[] = [{ id: 2, name: 'Основной' }, { id: 3, name: 'Дальний' }];

const FEED = {
  id: 1, marketplace: 'DROM', title: 'Дром · низкая цена размещения',
  status: 'ACTIVE', hasCredentials: false, plaintextSecret: false, hasFeed: true,
  feedFileName: null, productLine: 'PART', settings: { pricePercent: 0, priceRounding: 0, photoLimit: 10 },
  packetId: null, lastError: null, deliveryStatus: null, lastDownloadAt: null, deletedAt: null,
  priceFrom: null, priceTo: null, conditions: [], warehouseIds: [],
  kindIds: [], kindsExcluded: false, brandIds: [], brandsExcluded: false,
  filterColumns: {}, filterWords: {},
};

function row(): CatalogRow {
  return {
    id: 42, number: 42, code: 'B-40219', title: 'Фара левая Toyota Camry',
    qualityGrade: null, condition: null,
    brand: null, model: null, generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: null, donorCode: null,
    price: 12500, installationPrice: null, color: null, description: null, note: null,
    manufacturer: null, marking: null, section: null, cellCode: 'А-01-1',
    sideLr: null, sideFr: null,
    qty: 1, oem: null, crosses: null, photoUrl: null, supply: null, equipment: null,
    partName: null, published: null, barcode: null, legacyCode: null, dromAdNumber: null,
    videoUrl: null, textBlock: null, weightKg: null, dimensions: null,
    packageDimensions: null, packageWeightKg: null,
    createdAt: null, updatedAt: null, updatedByName: null,
    priceChangedAt: null, priceChangedByName: null, photoCount: 0,
    stock: { '2': 1 },
  };
}

/** Разметка, когда перестала меняться, — тот же приём, что у сторожа ширины. */
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
