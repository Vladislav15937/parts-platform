import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartCard } from './PartCard';
import type { CatalogRow, Warehouse } from '../inventory/catalog';

/**
 * Этикетка одной детали печатается из её карточки.
 *
 * <p><b>Зачем.</b> Этикетку перепечатывают поштучно и постоянно: отклеилась,
 * порвалась, залили маслом. Пачкой их печатают один раз — при приёмке партии.
 * А напечатать одну было нельзя вовсе: экран «Этикетки» отправляет в печать
 * **всю** выдачу поиска, выбрать из неё одну нечем, и «фара» на живом складе
 * находит 745 строк. Владелец печатал пачку и выбрасывал лишние наклейки.
 *
 * <p>Проверяется то, что видит человек: действие в карточке, ровно одна
 * этикетка в предпросмотре и её содержимое — код, наименование, цена.
 */
describe('печать этикетки из карточки позиции', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/photos')) {
        return json([]);
      }
      if (url.includes('/applicability')) {
        return json([]);
      }
      return json([]);
    }));
    vi.stubGlobal('print', vi.fn());
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('кладовщик печатает наклейку открытой позиции — одну', async () => {
    render(<PartCard {...props(row())} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Печать штрих-кода' }));

    // Ровно одна: лист «Этикеток» уносит в печать всю выдачу поиска, здесь
    // печатается открытая позиция и только она.
    const labels = await screen.findAllByRole('img', { name: /Штрихкод/ });
    expect(labels.length, 'этикеток на листе не одна').toBe(1);
    expect(labels[0]?.getAttribute('aria-label')).toBe('Штрихкод B-40219');
    // Что на самой наклейке: код, наименование и цена этой позиции.
    // Пробел в числе неразрывный — сводим, чтобы сравнивать читаемое.
    const label = labels[0]?.closest('article')?.textContent?.replace(/ /g, ' ');
    expect(label, 'на этикетке нет наименования').toContain('Фара левая Toyota Camry');
    expect(label, 'на этикетке нет цены').toContain('12 500 ₽');
    expect(label, 'на этикетке нет кода').toContain('B-40219');
    // Без неё принтер ужимает штрихкод, и сканер перестаёт его брать.
    expect(screen.getByText(/58×40 мм/), 'не сказано, какой размер выставить в диалоге')
      .toBeTruthy();
    expect(window.print, 'печать не отправлена').toHaveBeenCalled();
  });

  it('лист не накапливает: у второй позиции печатается вторая', async () => {
    const { rerender } = render(<PartCard {...props(row())} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Печать штрих-кода' }));
    await screen.findAllByRole('img', { name: /Штрихкод/ });

    const second = { ...row(), id: 77, code: 'B-77012', title: 'Бампер передний' };
    rerender(<PartCard {...props(second)} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Печать штрих-кода' }));

    const labels = await screen.findAllByRole('img', { name: /Штрихкод/ });
    expect(labels.length, 'к новой этикетке подклеилась прежняя').toBe(1);
    expect(labels[0]?.getAttribute('aria-label')).toBe('Штрихкод B-77012');
  });

  it('позиция без кода не печатает и говорит почему', async () => {
    render(<PartCard {...props({ ...row(), code: null })} />);

    const button = await screen.findByRole('button', { name: 'Печать штрих-кода' });
    expect((button as HTMLButtonElement).disabled, 'кнопка нажимается на нечего печатать')
      .toBe(true);
    expect(screen.getByText(/номера товара/), 'причина не названа').toBeTruthy();
  });

  it('продавцу этикетки не показываются', async () => {
    render(<PartCard {...props(row())} role="SELLER" />);

    await waitFor(() => expect(screen.getByText('Фара левая Toyota Camry')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Печать штрих-кода' }),
      'кнопка показана роли, которой печать закрыта').toBeNull();
  });
});

const WAREHOUSES: Warehouse[] = [{ id: 2, name: 'Ткацкая' }];

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
    id: 42,
    code: 'B-40219',
    title: 'Фара левая Toyota Camry',
    qualityGrade: null,
    condition: null,
    brand: 'Toyota',
    model: 'Camry',
    generation: null,
    yearFrom: null,
    yearTo: null,
    body: null,
    engine: null,
    year: null,
    donorCode: null,
    price: 12500,
    installationPrice: null,
    color: null,
    description: null,
    note: null,
    manufacturer: null,
    marking: null,
    section: null,
    cellCode: null,
    sideLr: null,
    sideFr: null,
    qty: 1,
    oem: null,
    crosses: null,
    photoUrl: null,
    supply: null,
    equipment: null,
    partName: null,
    published: null,
    barcode: null,
    legacyCode: null,
    videoUrl: null,
    textBlock: null,
    weightKg: null,
    dimensions: null,
    packageDimensions: null,
    packageWeightKg: null,
    createdAt: null,
    updatedAt: null,
    updatedByName: null,
    priceChangedAt: null,
    priceChangedByName: null,
    photoCount: 0,
    stock: { '2': 1 },
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
