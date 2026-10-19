import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';
import { PartCard } from './PartCard';
import { PartHistoryView } from './PartHistoryView';
import type { CatalogRow, PartHistory, Warehouse } from '../inventory/catalog';

/**
 * Отложенное под клиента видно в списке и карточке склада (задача 0367).
 *
 * <p>Клетка склада «0 (1)»: свободно ноль, в скобках отдельным элементом один
 * в резерве; строка с резервом подсвечена; у позиции без остатка в карточке
 * красная плашка. До правки клетка показывала полный остаток «1» без скобок.
 */
const WAREHOUSES: Warehouse[] = [{ id: 2, name: 'Основной' }];

describe('резерв и отсутствие на складе', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('клетка склада: свободно и в скобках отложено, строка подсвечена', async () => {
    stubCatalog([
      row(1, { qty: 1, stock: { '2': 1 }, reserved: { '2': 1 } }),
      row(2, { qty: 3, stock: { '2': 3 }, reserved: { '2': 1 } }),
      row(3, { qty: 2, stock: { '2': 2 }, reserved: { '2': 0 } }),
    ]);
    render(<CatalogScreen role="OWNER" />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(3));

    const cells = [...document.querySelectorAll('tbody tr')]
      .map((tr) => tr.lastElementChild?.textContent?.replace(/\s+/g, ' ').trim());
    expect(cells).toEqual(['0 (1)', '2 (1)', '2']);

    // Скобки — отдельный элемент своего цвета, а не часть числа.
    const held = [...document.querySelectorAll('tbody .stock-held')]
      .map((n) => n.textContent?.trim());
    expect(held).toEqual(['(1)', '(1)']);

    const flagged = [...document.querySelectorAll('tbody tr')]
      .map((tr) => tr.classList.contains('row--held'));
    expect(flagged).toEqual([true, true, false]);
  });

  it('склад без записи об остатке — прочерк', async () => {
    stubCatalog([row(1, { qty: 0, stock: {}, reserved: {} })]);
    render(<CatalogScreen role="OWNER" />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(1));
    expect(document.querySelector('tbody tr')?.lastElementChild?.textContent).toBe('—');
  });

  it('карточка: «0 (1)» у склада и плашка «Товар отсутствует» только без остатка', async () => {
    stubCard();
    render(<PartCard {...props(row(1, { qty: 1, stock: { '2': 1 }, reserved: { '2': 1 } }))} />);
    await waitFor(() => expect(
      document.querySelector('.card-view__stock b')?.textContent?.replace(/\s+/g, ' ').trim(),
    ).toBe('0 (1)'));
    expect(screen.queryByText('Товар отсутствует')).toBeNull();
    cleanup();

    render(<PartCard {...props(row(2, { qty: 0, stock: { '2': 0 }, reserved: { '2': 0 } }))} />);
    expect(await screen.findByText('Товар отсутствует')).toBeTruthy();
  });

  it('движение: резерв без знака, выдача со знаком', () => {
    const history: PartHistory = {
      changes: [],
      movements: [
        move({ type: 'Отложено под клиента', qty: 1, document: 'Сделка №50001',
          status: 'Резерв', held: true }),
        move({ type: 'Продажа', qty: -1, document: 'Сделка №50003', status: 'Выдан' }),
      ],
    };
    render(<PartHistoryView history={history} tab="movements" onTab={() => {}} />);
    expect(screen.getByText('Сделка №50001')).toBeTruthy();
    expect(screen.getByText('Резерв')).toBeTruthy();
    expect(screen.getByText('Выдан')).toBeTruthy();
    const qtys = [...document.querySelectorAll('td.num')].map((td) => td.textContent);
    expect(qtys).toEqual(['1', '-1']);
  });
});

function move(over: Partial<PartHistory['movements'][number]>): PartHistory['movements'][number] {
  return {
    at: '2026-01-08T10:00:00Z', type: 'Продажа', qty: 1, document: null, status: null,
    warehouse: 'Основной', reason: null, author: null, ...over,
  };
}

function props(r: CatalogRow) {
  return { row: r, warehouses: WAREHOUSES, role: 'OWNER', onClose: () => {}, onChanged: () => {} };
}

function row(id: number, over: Partial<CatalogRow>): CatalogRow {
  return {
    id, number: id, code: `G-${id}`, title: 'Фара левая',
    qualityGrade: null, condition: null,
    brand: null, model: null, generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: null, donorCode: null,
    price: 10000, installationPrice: null, color: null, description: null, note: null,
    manufacturer: null, marking: null, section: null, cellCode: null,
    sideLr: null, sideFr: null, qty: 1, oem: null, crosses: null, photoUrl: null,
    supply: null, equipment: null, partName: null, published: null, barcode: null,
    legacyCode: null, dromAdNumber: null, videoUrl: null, textBlock: null,
    weightKg: null, dimensions: null, packageDimensions: null, packageWeightKg: null,
    createdAt: null, updatedAt: null, updatedByName: null,
    priceChangedAt: null, priceChangedByName: null, photoCount: 0, stock: {},
    ...over,
  } as unknown as CatalogRow;
}

function stubCard(): void {
  vi.stubGlobal('fetch', vi.fn(async () => json([])));
}

function stubCatalog(rows: CatalogRow[]): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/me/settings')) {
      return json({ value: null });
    }
    if (url.includes('/api/catalog/vehicles') || url.includes('/api/intake/donors')
        || url.includes('/values')) {
      return json([]);
    }
    return json({ total: rows.length, warehouses: WAREHOUSES, filterable: [], rows });
  }));
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
