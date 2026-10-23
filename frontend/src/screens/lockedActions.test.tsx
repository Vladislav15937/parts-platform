import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

import { forgetCatalog } from '../access/rights';
import { rightsWith } from '../test/rights';
import type { CatalogRow, Warehouse } from '../inventory/catalog';
import { CatalogScreen } from './CatalogScreen';
import { FeedsScreen } from './FeedsScreen';
import { PartCard } from './PartCard';
import { WheelsScreen } from './WheelsScreen';

/**
 * Действие без права внутри экрана — погашено и называет причину (задача 0044).
 *
 * <p>Решение владельца 23.10.2026, вопрос 3: «погашен с причиной». До 0044
 * эти кнопки прятались ролью, и сотрудник не знал, что действие бывает и
 * у кого просить. Таблицей: каждое место, где кнопку раньше прятали, —
 * своей строкой, чтобы подделка «спрятать» краснила все, а не одно.
 * «Поправить остаток» у продавца и в карточке клиента — в своих файлах
 * (`sellerPaymentSource.test.tsx`, `customersScreen.test.tsx`), там до них
 * идти через поиск клиента.
 */
describe('погашенное внутри экранов', () => {
  beforeEach(() => {
    forgetCatalog();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/api/access/catalog')) {
        return json([{ title: 'Склад', permissions: [
          { key: 'part.edit', title: 'Править позиции и цены', hint: null },
          { key: 'stock.move', title: 'Перевозить товар между складами', hint: null },
          { key: 'stock.write_off', title: 'Списывать товар', hint: null },
          { key: 'photos.manage', title: 'Добавлять и удалять снимки', hint: null },
          { key: 'export.table', title: 'Скачивать склад таблицей', hint: null },
          { key: 'marketplace.manage', title: 'Заводить, выключать и удалять выгрузки', hint: null },
          { key: 'marketplace.configure', title: 'Настраивать отбор и цены выгрузки', hint: null },
        ] }]);
      }
      if (url.includes('/api/marketplace-accounts')) {
        return json(url.includes('deleted=true') ? [] : [FEED]);
      }
      if (url.includes('/api/catalog/vehicles')) {
        return json({ brands: [], models: [], generations: [], modifications: [] });
      }
      if (url.includes('/api/parts/catalog') || url.includes('/api/wheels')) {
        return json({ total: 0, warehouses: [], filterable: [], rows: [] });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  const CASES: Array<[string, () => void, string, string]> = [
    ['карточка: «Изменить»', () => card(), 'Изменить', 'Править позиции и цены'],
    ['карточка: «Перевезти»', () => card(), 'Перевезти', 'Перевозить товар между складами'],
    ['карточка: «Списать»', () => card(), 'Списать', 'Списывать товар'],
    ['карточка: снимки', () => card(), 'Добавить снимок', 'Добавлять и удалять снимки'],
    ['склад: «Правка списком»', () => render(<CatalogScreen rights={rightsWith()} />),
      'Правка списком', 'Править позиции и цены'],
    ['склад: «Применимость подробно»', () => render(<CatalogScreen rights={rightsWith()} />),
      'Применимость подробно', 'Править позиции и цены'],
    ['колёса: «Правка списком»', () => render(<WheelsScreen canIntake={false} rights={rightsWith()} />),
      'Правка списком', 'Править позиции и цены'],
    ['выгрузки: «Новая выгрузка»',
      () => render(<FeedsScreen rights={rightsWith('marketplace.view')} />),
      'Новая выгрузка', 'Заводить, выключать и удалять выгрузки'],
    ['выгрузки: «Сохранить отбор»',
      () => render(<FeedsScreen rights={rightsWith('marketplace.view')} />),
      'Сохранить отбор', 'Настраивать отбор и цены выгрузки'],
    // Отбор настраивать можно, а заводить и выключать — нет: в карточке
    // погашены ключ, ссылка и состояние, а не спрятаны (разбор PR 508).
    ['выгрузки: «Ключ, ссылка и состояние»',
      () => render(<FeedsScreen rights={rightsWith('marketplace.view', 'marketplace.configure')} />),
      'Ключ, ссылка и состояние', 'Заводить, выключать и удалять выгрузки'],
  ];

  it.each(CASES)('%s — погашено и названо', async (_name, show, label, title) => {
    show();
    const button = await waitFor(() => {
      const found = locked(label);
      expect(found, `«${label}» спрятана, а не погашена`).toBeTruthy();
      return found!;
    });
    expect(button.disabled, `«${label}» без права нажимается`).toBe(true);
    await waitFor(() => expect(button.parentElement?.textContent,
      `у «${label}» не названа причина`).toContain(`Нет права «${title}»`));
  });
});

function card(): void {
  render(<PartCard row={row()} warehouses={WAREHOUSES} rights={rightsWith()}
                   onClose={() => {}} onChanged={() => {}} />);
}

/** Погашенная кнопка действия — та, что рисует `LockedAction`. */
function locked(label: string): HTMLButtonElement | undefined {
  return [...document.querySelectorAll('.locked-action button')]
    .find((b) => (b.textContent ?? '').trim() === label) as HTMLButtonElement | undefined;
}

const WAREHOUSES: Warehouse[] = [{ id: 2, name: 'Основной' }, { id: 3, name: 'Дальний' }];

const FEED = {
  id: 1, marketplace: 'DROM', title: 'Дром: основной',
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

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
