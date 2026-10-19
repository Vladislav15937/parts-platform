import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ImportScreen } from './ImportScreen';

/**
 * Колёсные строки файла переноса в отчёте загрузки (задача 0291): отдельной
 * строкой «Шины и диски: N позиций», перекладка уже перенесённых — своей,
 * и что в свойствах не разобралось — со строкой файла и полем.
 */
describe('шины и диски в отчёте загрузки', () => {
  let answer: unknown;

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/import/excel/preview')) {
        return json({
          header: ['Номер объявления Дрома', 'Наименование', 'Прочие поля'],
          detected: { DROM_AD_NUMBER: 0, NAME: 1, CHARACTERISTICS: 2 },
          missingRequired: [], rows: [['100000101', 'Автошина Dunlop', 'marking [marking]: 265/65 R19']],
          format: 'DROM_TRANSFER',
        });
      }
      if (url.endsWith('/api/import/excel')) {
        return json(answer);
      }
      return json({ done: 0, failed: 0, pending: 0, total: 0, broken: 0 });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('называет колёсные позиции отдельной строкой и неразобранное — по полю', async () => {
    answer = {
      imported: 8, skipped: [], quantityDefaulted: 3,
      wheels: { created: 5, converted: 0, notes: [
        { row: 349, adNumber: '100000101',
          text: 'Размер: в наименовании 235/55 R19, в «Прочих полях» 265/65 R19 — взят из «Прочих полей»' },
      ] },
    };
    await upload();

    await waitFor(() => expect(text()).toContain('Шины и диски: 5 позиций'));
    expect(text()).toContain('Строка 349, № 100000101: Размер: в наименовании 235/55 R19');
    expect(text()).not.toContain('переложено');
  });

  it('перекладку называет своими словами', async () => {
    answer = {
      imported: 0, skipped: [], quantityDefaulted: 0,
      wheels: { created: 0, converted: 5, notes: [] },
    };
    await upload();

    await waitFor(() => expect(text()).toContain('Шины и диски: переложено из запчастей 5 позиций'));
    expect(text(), 'перекладку назвали заведением').not.toContain('Шины и диски: 0');
  });

  it('без колёсных строк о них молчит', async () => {
    answer = { imported: 3, skipped: [], quantityDefaulted: 0 };
    await upload();

    await waitFor(() => expect(text()).toContain('Заведено новых: 3'));
    expect(text()).not.toContain('Шины и диски: ');
  });
});

async function upload(): Promise<void> {
  render(<ImportScreen reference={reference()} canImport />);
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  fireEvent.change(input, { target: { files: [new File(['xlsx'], 'perenos.xlsx')] } });
  await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());
  const select = [...document.querySelectorAll('select')]
    .find((s) => s.querySelector('option')?.textContent === '— выберите склад —');
  fireEvent.change(select!, { target: { value: '7' } });
  fireEvent.click(screen.getByText('Загрузить склад'));
}

/** Текст экрана одной строкой: числа приходят с неразрывными пробелами разрядов. */
function text(): string {
  return (document.body.textContent ?? '').replace(/\s+/g, ' ');
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
