import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ImportScreen } from './ImportScreen';

/**
 * Повреждения листа «Продолжение» видны словами, где бы они ни стояли
 * в ответе (задача 0281).
 *
 * <p>Сервер дописывает их после строк первого листа, а список строк экран
 * режет двадцатью: у файла с Дрома «не в наличии» десятки, и повреждение
 * уходило за предел. Они же несут номер строки **своего** листа, и раньше
 * ключ по номеру давал двух соседей с одним ключом.
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

/** Двадцать пять «не в наличии» — у файла с Дрома их десятки — и потом повреждение листа. */
const CONTINUATION_REASON = 'Лист «Продолжение»: у объявления № 125696381 колонка «Применимость» — '
  + 'нет части 2 из 3; значение взято до разрыва';
const REPORT = {
  imported: 1,
  skipped: [
    ...Array.from({ length: 25 }, (_, i) => ({ row: i + 2, reason: 'не в наличии: «Под заказ»' })),
    { row: 2, reason: CONTINUATION_REASON, sheet: 'Продолжение' },
  ],
  quantityDefaulted: 1,
};

describe('повреждения листа «Продолжение» в отчёте загрузки', () => {
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

  it('показывает повреждение отдельно и целиком, даже если оно за двадцатым пунктом', async () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(<ImportScreen reference={reference()} canImport />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['xlsx'], 'perenos.xlsx')] } });
    await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());
    const select = [...document.querySelectorAll('select')]
      .find((s) => s.querySelector('option')?.textContent === '— выберите склад —');
    fireEvent.change(select!, { target: { value: '7' } });
    fireEvent.click(screen.getByText('Загрузить склад'));

    await waitFor(() => expect(screen.getByText(/Пропущено строк: 25/)).toBeTruthy());
    // Повреждение стоит в ответе двадцать шестым — за пределом списка строк.
    expect(screen.getByText(`строка 2 листа «Продолжение» — ${CONTINUATION_REASON}`),
      'повреждение листа ушло за предел списка — значение обрезано молча').toBeTruthy();
    expect(screen.getByText(/Лист «Продолжение» склеен не целиком: 1/)).toBeTruthy();
    expect(screen.getAllByText(/^строка \d+ — не в наличии/), 'предел списка строк сдвинулся')
      .toHaveLength(20);
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
