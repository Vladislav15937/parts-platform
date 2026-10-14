import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ImportScreen } from './ImportScreen';

/**
 * Замена, равная основному номеру, называется строкой отчёта (задача 0298).
 *
 * <p>В файле клиента 1 338 строк с «Номерами замен», на складе 1 337: у одной
 * позиции единственная замена совпала с основным номером, и уникальность номера
 * у позиции её не завела. Информации не потеряно, но без строки отчёта сверка
 * «в файле против на складе» не сходилась без чтения кода.
 */
const PREVIEW = {
  header: ['Наименование', 'Цена'],
  detected: { NAME: 0, PRICE: 1 },
  missingRequired: [],
  rows: [['Бардачок центральной консоли', '4000']],
  format: null,
};

describe('замена, совпавшая с основным номером', () => {
  let report: unknown;

  beforeEach(() => {
    report = { imported: 2, skipped: [], quantityDefaulted: 0, substituteIsPrimary: 1 };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/import/excel/preview')) {
        return json(PREVIEW);
      }
      if (url.endsWith('/api/import/excel')) {
        return json(report);
      }
      return json({ done: 0, failed: 0, pending: 0, total: 0, broken: 0 });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('называет число позиций словами, а не молчит', async () => {
    await load();
    expect(screen.getByText(/Номер замены совпадает с основным — не заведён: 1 позиция/))
      .toBeTruthy();
  });

  it('склоняет существительное', async () => {
    report = { imported: 9, skipped: [], substituteIsPrimary: 3 };
    await load();
    expect(screen.getByText(/не заведён: 3 позиции/)).toBeTruthy();
  });

  it('ответ без числа ничего не говорит', async () => {
    report = { imported: 2, skipped: [] };
    await load();
    expect(screen.queryByText(/Номер замены совпадает/)).toBeNull();
  });
});

async function load(): Promise<void> {
  render(<ImportScreen reference={reference()} canImport />);
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  fireEvent.change(input, { target: { files: [new File(['xlsx'], 'sklad.xlsx')] } });
  await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());
  const select = [...document.querySelectorAll('select')]
    .find((s) => s.querySelector('option')?.textContent === '— выберите склад —');
  fireEvent.change(select!, { target: { value: '7' } });
  fireEvent.click(screen.getByText('Загрузить склад'));
  await waitFor(() => expect(screen.getByText(/Заведено новых:/)).toBeTruthy());
}

function reference(): never {
  return {
    warehouses: [{ id: 7, name: 'Ткацкая' }], supplies: [], donors: [], cells: [], partNames: [],
  } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
