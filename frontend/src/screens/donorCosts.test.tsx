import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { DonorScreen } from './DonorScreen';

/**
 * Затраты раскрываются под своей же строкой.
 *
 * <p><b>Зачем.</b> Блок стоял после таблицы, а у переехавшего клиента
 * в ней 441 машина: замерено живьём — строка на 17 995 пикселе, открытый
 * блок на 27 756, то есть одиннадцать экранов ниже. Владелец нажимал
 * «Затраты» и не видел ничего, кроме сменившейся надписи на кнопке;
 * решить он мог только одно — что кнопка не работает.
 *
 * <p>Та же порода, что накладка снимков на витрине и панель выбранного
 * в правке списком: результат нажатия обязан быть виден там, где нажали.
 */
describe('затраты по машине', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/catalog/vehicles')) {
        return json({ brands: [], models: [], generations: [] });
      }
      if (url.includes('/costs')) {
        return json([]);
      }
      if (url.includes('/api/intake/donors')) {
        return json(Array.from({ length: 30 }, (_, i) => ({
          id: i + 1, code: String(i + 1), brand: 'Toyota', model: 'RAV4',
          year: 2019, vin: null, status: 'DISMANTLED', note: null,
        })));
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('открываются внутри таблицы, а не за её концом', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(30));

    const rows = [...document.querySelectorAll('tbody tr')];
    const tenth = rows[9]!;
    fireEvent.click([...tenth.querySelectorAll('button')]
      .find((b) => b.textContent === 'Затраты')!);

    // Блок обязан оказаться следующей строкой той же таблицы: за её концом
    // он у клиента с 441 машиной уезжает на одиннадцать экранов.
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(31));
    const after = [...document.querySelectorAll('tbody tr')][10]!;
    expect(after.textContent, 'затраты открылись не под своей строкой')
      .toContain('Затраты');
    expect(after.previousElementSibling, 'блок оторван от строки машины')
      .toBe(tenth);
  });
});

function reference(): never {
  return { warehouses: [], supplies: [], donors: [], cells: [], partNames: [] } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
