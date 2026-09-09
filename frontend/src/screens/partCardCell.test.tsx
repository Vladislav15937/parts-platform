import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartCard } from './PartCard';
import type { CatalogRow, Warehouse } from '../inventory/catalog';

/**
 * Адрес полки в карточке позиции: видно, где лежит, и переставляется туда же.
 *
 * <p><b>Зачем.</b> Кладовщик открывал карточку, чтобы узнать, куда идти,
 * и узнавал только склад и остаток: код ячейки не показывался нигде, кроме
 * ленты правок. Переставить деталь было нечем вовсе — «Перевезти» требует
 * другого склада-приёмника, а у клиента с одним складом такого нет.
 *
 * <p>Проверяется то, что видит человек: строка «Основной · А-01-1», выбор
 * полки того же склада и уехавший на сервер адрес — а не то, что компонент
 * отрисовался.
 */
describe('адрес полки в карточке позиции', () => {
  let sent: Array<{ url: string; method: string; body: unknown }>;
  let cells: unknown;
  let shelves: unknown;

  beforeEach(() => {
    sent = [];
    cells = [{ warehouseId: 2, cellId: 5, cellCode: 'А-01-1', qty: 1 }];
    shelves = [
      { id: 5, code: 'А-01-1', zone: null, active: true },
      { id: 6, code: 'А-02-1', zone: null, active: true },
    ];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      sent.push({
        url,
        method: init?.method ?? 'GET',
        body: init?.body === undefined ? null : JSON.parse(String(init.body)),
      });
      if (url.endsWith('/cells') && url.includes('/api/parts/')) {
        return json(cells);
      }
      if (url.includes('/api/organization/warehouses/')) {
        return json(shelves);
      }
      if (url.endsWith('/cell')) {
        return json({ warehouseId: 2, cellId: 6, cellCode: 'А-02-1', qty: 1 });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('карточка называет полку рядом со складом и остатком', async () => {
    render(<PartCard {...props(row())} />);

    // До машины и номеров: кладовщик открывает карточку затем, чтобы узнать,
    // куда идти, а не какой у донора привод.
    expect(await screen.findByText('Основной · А-01-1'), 'адрес не показан').toBeTruthy();
  });

  it('без заведённой ячейки написано «без адреса», а не прочерк', async () => {
    // Прочерк читается как «не знаем», а мы знаем: полку просто не задавали.
    cells = [{ warehouseId: 2, cellId: null, cellCode: null, qty: 1 }];
    render(<PartCard {...props(row())} />);

    expect(await screen.findByText('Основной · без адреса'),
      '«без адреса» не написано').toBeTruthy();
  });

  it('кладовщик переставляет деталь на другую полку того же склада', async () => {
    render(<PartCard {...props(row())} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Переставить на полку' }));

    const choice = await screen.findByRole('combobox', { name: /Ячейка/ });
    await waitFor(() => expect(screen.getByRole('option', { name: 'А-02-1' })).toBeTruthy());
    fireEvent.change(choice, { target: { value: '6' } });
    fireEvent.click(screen.getByRole('button', { name: 'Переставить' }));

    await waitFor(() => {
      const put = sent.find((r) => r.method === 'PUT');
      expect(put, 'адрес на сервер не уехал').toBeTruthy();
      // Склад назван явно: у позиции на двух складах две полки, и менять
      // их обеим разом значит соврать про ту, к которой никто не подходил.
      expect(put?.body).toEqual({ warehouseId: 2, cellId: 6 });
      expect(put?.url).toContain('/api/parts/42/cell');
    });
  });

  it('склад без заведённых ячеек объясняет себя, а не показывает пустой список',
    async () => {
      shelves = [];
      render(<PartCard {...props(row())} />);

      fireEvent.click(await screen.findByRole('button', { name: 'Переставить на полку' }));

      expect(await screen.findByText(/ячейки не заведены/),
        'пустой выбор ничего не объясняет').toBeTruthy();
      expect(screen.queryByRole('combobox', { name: /Ячейка/ }),
        'предложен выбор из ничего').toBeNull();
    });

  it('продавцу перестановка не показывается', async () => {
    render(<PartCard {...props(row())} role="SELLER" />);

    await waitFor(() => expect(screen.getByText('Основной · А-01-1')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Переставить на полку' }),
      'кнопка показана роли, которой перестановка закрыта').toBeNull();
  });
});

const WAREHOUSES: Warehouse[] = [{ id: 2, name: 'Основной' }];

function props(row: CatalogRow) {
  return {
    row,
    warehouses: WAREHOUSES,
    role: 'STOREKEEPER',
    onClose: () => {},
    onChanged: () => {},
  };
}

function row(): CatalogRow {
  return {
    id: 42, code: 'B-40219', title: 'Фара левая Toyota Camry',
    qualityGrade: null, condition: null,
    brand: null, model: null, generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: null, donorCode: null,
    price: 12500, installationPrice: null, color: null, description: null, note: null,
    manufacturer: null, marking: null, section: null, cellCode: 'А-01-1',
    sideLr: null, sideFr: null,
    qty: 1, oem: null, crosses: null, photoUrl: null, supply: null, equipment: null,
    partName: null, published: null, barcode: null, legacyCode: null,
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
