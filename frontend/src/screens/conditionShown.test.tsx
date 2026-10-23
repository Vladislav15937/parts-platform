import { rightsAs } from '../test/rights';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';
import { LabelsScreen } from './LabelsScreen';
import { PartCard } from './PartCard';
import { SellerScreen } from './SellerScreen';
import { partLabel } from '../labels/labels';
import { COLUMNS, type CatalogRow } from '../inventory/catalog';

/**
 * Состояние позиции видно своим словом, а не пометкой в наименовании (задача 0314).
 *
 * <p><b>Как это выглядело для человека.</b> «Новая», «б/у», «восстановленная»
 * и «контрактная» — разный товар и разная цена, а на складе, у продавца
 * и на этикетке их называла только пометка в хвосте наименования
 * («Фара … (б/у)»). Пометка из наименования уходит (0315, 0316) и уже сегодня
 * врёт после смены состояния (0304). Поэтому наименования в заглушках здесь
 * заведены **без** пометки: состояние обязано прийти полем.
 */
describe('состояние позиции отдельно от наименования', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  describe('склад', () => {
    it('новая раскладка: «Состояние» стоит колонкой сразу за «Запчастью»', async () => {
      stubCatalog(null);
      render(<CatalogScreen rights={rightsAs('OWNER')} />);
      await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(2));

      const headers = [...document.querySelectorAll('thead th')]
        .map((th) => th.textContent ?? '');
      const title = headers.findIndex((h) => h.startsWith('Запчасть'));
      expect(title, 'колонки «Запчасть» нет').toBeGreaterThanOrEqual(0);
      expect(headers[title + 1] ?? '', `за «Запчастью» стоит не «Состояние»: ${headers.join(' | ')}`)
        .toMatch(/^Состояние/);

      expect(cellTexts(), 'в колонке нет состояния словом').toContain('контрактная');
      // Колонка включена — рядом с наименованием слово не повторяется.
      expect(document.querySelectorAll('.title-aside').length,
        'состояние дублируется рядом с наименованием при включённой колонке').toBe(0);
    });

    it('сохранённая раскладка без колонки: состояние рядом с наименованием', async () => {
      stubCatalog(['number', 'title', 'price']);
      render(<CatalogScreen rights={rightsAs('OWNER')} />);
      await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(2));

      const headers = [...document.querySelectorAll('thead th')].map((th) => th.textContent ?? '');
      expect(headers.some((h) => h.startsWith('Состояние')),
        'память раскладки молча получила колонку — это настройка человека').toBe(false);

      const asides = [...document.querySelectorAll('.title-aside')].map((n) => n.textContent);
      expect(asides, 'у сохранённой раскладки состояние пропало из строки')
        .toEqual(['контрактная', 'новая']);
      // Отдельным элементом внутри той же клетки: наименование остаётся
      // своим текстом — его и сортирует, и отбирает сервер.
      const aside = document.querySelector('.title-aside')!;
      expect(aside.closest('td')?.firstChild?.textContent)
        .toBe('Генератор Toyota Corolla');
    });

    it('сохранённая раскладка с колонкой: рядом с наименованием не дублируется', async () => {
      stubCatalog(['number', 'title', 'condition', 'price']);
      render(<CatalogScreen rights={rightsAs('OWNER')} />);
      await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(2));

      expect(document.querySelectorAll('.title-aside').length).toBe(0);
      expect(cellTexts()).toContain('новая');
    });

    it('сортировка по «Запчасти» осталась сортировкой по наименованию', () => {
      const title = COLUMNS.find((c) => c.key === 'title')!;
      expect(title.sort).toBe('title');
      expect(title.value({ title: 'Генератор', condition: 'USED' } as CatalogRow))
        .toBe('Генератор');
    });
  });

  describe('продавец', () => {
    it('«контрактная» и «новая» различимы в строке выдачи и в корзине', async () => {
      stubSeller();
      render(<SellerScreen canSell rights={rightsAs('SELLER')} company="test" memberId={1} />);

      const input = document.querySelector('input') as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'генератор' } });
      fireEvent.click(screen.getByRole('button', { name: 'Найти' }));
      await waitFor(() => expect(document.querySelectorAll('.stock-row').length).toBe(2));

      const rows = [...document.querySelectorAll('.stock-row .stock-info')]
        .map((node) => node.textContent ?? '');
      expect(rows[0], 'контрактную не отличить от новой').toContain('Генератор · контрактная');
      expect(rows[1], 'новую не отличить от контрактной').toContain('Генератор · новая');

      fireEvent.click([...document.querySelectorAll('button')]
        .find((b) => b.textContent === 'в сделку')!);
      const basket = await screen.findByText('В сделку');
      expect(basket.nextElementSibling?.textContent ?? '', 'в корзине состояние пропало')
        .toContain('Генератор · контрактная');
    });
  });

  describe('этикетка', () => {
    it.each([
      ['NEW', 'новая'],
      ['USED', 'б/у'],
      ['REFURBISHED', 'восстановленная'],
      ['CONTRACT', 'контрактная'],
    ])('%s печатается словом «%s» рядом с ценой', (code, word) => {
      const label = partLabel('A1', 'Генератор', 12500, code);
      expect(label.title, 'состояние приклеилось к наименованию').toBe('Генератор');
      expect(label.note?.replace(/ /g, ' ')).toBe(`${word} · 12 500 ₽`);
    });

    it('без цены остаётся одно слово, без состояния — одна цена', () => {
      expect(partLabel('A1', 'Генератор', null, 'USED').note).toBe('б/у');
      expect(partLabel('A1', 'Генератор', 100, null).note).toBe('100 ₽');
      expect(partLabel('A1', 'Генератор', null, null).note).toBeNull();
    });

    it('этикетка из карточки несёт состояние', async () => {
      vi.stubGlobal('fetch', vi.fn(async () => json([])));
      vi.stubGlobal('print', vi.fn());
      render(<PartCard row={catalogRow(1, 'REFURBISHED')} warehouses={[{ id: 2, name: 'Ткацкая' }]}
        rights={rightsAs('STOREKEEPER')} onClose={() => {}} onChanged={() => {}} />);

      fireEvent.click(await screen.findByRole('button', { name: 'Печать штрих-кода' }));
      const label = (await screen.findByRole('img', { name: /Штрихкод/ }))
        .closest('article')?.textContent ?? '';
      expect(label, 'на этикетке из карточки нет состояния').toContain('восстановленная');
    });

    it('этикетки пачкой несут состояние каждой позиции', async () => {
      stubSeller();
      render(<LabelsScreen canPrint />);

      fireEvent.click(screen.getByRole('button', { name: 'Детали' }));
      const field = await waitFor(() =>
        screen.getByPlaceholderText('фара камри, № 347, бампер приора'));
      fireEvent.change(field, { target: { value: 'генератор' } });
      fireEvent.click(screen.getByRole('button', { name: 'Найти' }));

      await waitFor(() => expect(document.querySelectorAll('article.label').length).toBe(2));
      const notes = [...document.querySelectorAll('article.label .label-note')]
        .map((n) => (n.textContent ?? '').replace(/ /g, ' '));
      expect(notes, 'на этикетках пачкой нет состояния')
        .toEqual(['контрактная · 9 000 ₽', 'новая · 15 000 ₽']);
    });
  });
});

function cellTexts(): string[] {
  return [...document.querySelectorAll('tbody td')].map((td) => td.textContent ?? '');
}

function stubCatalog(visible: string[] | null): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/me/settings')) {
      return json({ value: visible === null ? null : { visible } });
    }
    if (url.includes('/api/catalog/vehicles') || url.includes('/api/intake/donors')
        || url.includes('/values')) {
      return json([]);
    }
    return json({
      total: 2,
      warehouses: [],
      filterable: [],
      rows: [catalogRow(1, 'CONTRACT'), catalogRow(2, 'NEW')],
    });
  }));
}

function stubSeller(): void {
  const stock = (partId: number, condition: string, price: string) => ({
    partId, number: partId, publicCode: `G${partId}`, title: 'Генератор', price,
    status: 'IN_STOCK', warehouseId: 2, warehouseName: 'Ткацкая', cellCode: null,
    qty: '1', qtyReserved: '0', qtyAvailable: '1', expected: false, expectedOn: null,
    condition,
  });
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/parts/stock')) {
      return json({
        total: 2,
        facets: { vehicles: [], grades: [] },
        rows: [stock(1, 'CONTRACT', '9000'), stock(2, 'NEW', '15000')],
      });
    }
    if (url.includes('/warehouses')) {
      return json([{ id: 2, branchId: 1, name: 'Ткацкая', branchName: 'Филиал', cells: 0,
        isActive: true }]);
    }
    return json([]);
  }));
}

function catalogRow(id: number, condition: string): CatalogRow {
  return {
    id, number: id, code: `G-${id}`, title: 'Генератор Toyota Corolla',
    qualityGrade: null, condition,
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
  } as unknown as CatalogRow;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
