import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ImportScreen } from './ImportScreen';

/**
 * Таблица без количества на экране «Импорт» (задача 0279).
 *
 * <p>До задачи количество было обязательным: без колонки экран писал
 * «Не сопоставлено: Количество» и гасил «Загрузить склад», а сервер отбивал
 * загрузку. Файл переноса с Дрома при этом не загружался никогда — количества
 * на карточке Дрома нет вовсе. Теперь позиция заводится по одной штуке,
 * и экран говорит это до загрузки словами критерия, а после — числом.
 */
const PREVIEW = {
  header: ['Наименование', 'Цена'],
  detected: { NAME: 0, PRICE: 1 },
  missingRequired: [],
  rows: [['Стойка передняя Прадо', '4000'], ['Рычаг задний Прадо', '1500']],
  format: null,
};

describe('таблица без количества', () => {
  let report: unknown;

  beforeEach(() => {
    report = { imported: 2, skipped: [], quantityDefaulted: 2 };
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

  it('говорит словами критерия, что будет по 1 шт., и даёт загрузить', async () => {
    pick();
    await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());

    expect(screen.getByText('Количества в файле нет — каждая позиция заведётся по 1 шт.'))
      .toBeTruthy();
    expect(screen.queryByText(/Не сопоставлено:/), 'количество снова обязательно')
      .toBeNull();
    // «Наличие» — поле файла с Дрома, обычной таблице его не предлагаем.
    expect(screen.queryByText('Наличие')).toBeNull();

    chooseWarehouse();
    expect(loadButton().disabled, 'кнопка погашена без колонки количества').toBe(false);
  });

  it('после загрузки называет, сколько позиций заведено по одной штуке', async () => {
    pick();
    await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());
    chooseWarehouse();
    fireEvent.click(loadButton());

    await waitFor(() => expect(screen.getByText(/Загружено позиций: 2/)).toBeTruthy());
    expect(screen.getByText(/По 1 шт\. заведено позиций: 2/)).toBeTruthy();
  });

  it('ответ прежней версии без числа по одной штуке не роняет итог', async () => {
    report = { imported: 2, skipped: [] };
    pick();
    await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());
    chooseWarehouse();
    fireEvent.click(loadButton());

    await waitFor(() => expect(screen.getByText(/Загружено позиций: 2/)).toBeTruthy());
    expect(screen.queryByText(/По 1 шт\. заведено/)).toBeNull();
  });
});

function pick(): void {
  render(<ImportScreen reference={reference()} canImport />);
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  fireEvent.change(input, { target: { files: [new File(['xlsx'], 'sklad.xlsx')] } });
}

function chooseWarehouse(): void {
  const select = [...document.querySelectorAll('select')]
    .find((s) => s.querySelector('option')?.textContent === '— выберите склад —');
  expect(select, 'выбора склада нет').toBeTruthy();
  fireEvent.change(select!, { target: { value: '7' } });
}

function loadButton(): HTMLButtonElement {
  return screen.getByText('Загрузить склад') as HTMLButtonElement;
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
