import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ImportScreen } from './ImportScreen';

/**
 * Пропуски с одним номером строки показываются оба (задача 0281).
 *
 * <p>Повреждения листа «Продолжение» файла переноса с Дрома идут в тот же
 * список, что пропуски первого листа, с номером строки **своего** листа —
 * и он совпадает с номером строки первого. Ключ по номеру строки давал
 * двух соседей с одним ключом, а React такие «дублирует и/или выбрасывает»
 * (`frontend/CLAUDE.md`, ключ — общий ресурс соседей): одно из двух
 * сообщений могло пропасть.
 */
const PREVIEW = {
  header: ['Номер объявления Дрома', 'Наименование', 'Цена'],
  detected: { DROM_AD_NUMBER: 0, NAME: 1, PRICE: 2 },
  missingRequired: [],
  rows: [['125696381', 'Карданный вал', '16000']],
  format: 'DROM_TRANSFER',
};

const REPORT = {
  imported: 1,
  skipped: [
    { row: 2, reason: 'не в наличии: «Под заказ»' },
    { row: 2, reason: 'Лист «Продолжение»: часть для объявления № 555 (колонка «Применимость») '
      + 'не имеет строки на листе «Объявления»; пропущена' },
  ],
  quantityDefaulted: 1,
};

describe('пропуски с одинаковым номером строки', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/import/excel/preview')) {
        return json(PREVIEW);
      }
      if (url.endsWith('/api/import/excel')) {
        return json(REPORT);
      }
      return json({ done: 0, failed: 0, pending: 0, total: 0, broken: 0 });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('показывает оба и не даёт React двух соседей с одним ключом', async () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(<ImportScreen reference={reference()} canImport />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['xlsx'], 'perenos.xlsx')] } });
    await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());
    const select = [...document.querySelectorAll('select')]
      .find((s) => s.querySelector('option')?.textContent === '— выберите склад —');
    fireEvent.change(select!, { target: { value: '7' } });
    fireEvent.click(screen.getByText('Загрузить склад'));

    await waitFor(() => expect(screen.getByText(/Пропущено строк: 2/)).toBeTruthy());
    expect(screen.getByText(/не в наличии: «Под заказ»/)).toBeTruthy();
    expect(screen.getByText(/Лист «Продолжение»: часть для объявления № 555/)).toBeTruthy();
    const sameKey = errors.mock.calls.filter((call) => call.map(String).join(' ')
      .includes('Encountered two children with the same key'));
    expect(sameKey, 'у двух пропусков один ключ — React вправе выбросить одного').toEqual([]);
  });
});

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
