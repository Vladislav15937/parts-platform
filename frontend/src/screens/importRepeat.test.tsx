import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ImportScreen } from './ImportScreen';

/**
 * Повторная загрузка файла переноса на экране «Импорт» (задача 0282).
 *
 * <p>Владелец грузит тот же файл второй раз ради колонок, которые импорт
 * научился читать. Экран обязан сказать про каждый исход отдельно — сколько
 * заведено, сколько дозаполнено и чем, сколько без изменений и где файл
 * разошёлся с позицией, — а не «Заведено новых: 0», которое читается как
 * «ничего не произошло». Строки расхождений собирает сервер (их слова
 * закреплены проверкой его ответа), экран показывает их как есть.
 */
const PREVIEW = {
  header: ['Номер объявления Дрома', 'Наименование', 'Фото'],
  detected: { DROM_AD_NUMBER: 0, NAME: 1, PHOTOS: 2 },
  missingRequired: [],
  rows: [['300000001', 'Фара левая', 'https://example.test/1.jpg']],
  format: 'DROM_TRANSFER',
};

function discrepancy(n: number) {
  return {
    adNumber: String(300000000 + n),
    field: 'Цена',
    text: `№ ${300000000 + n}, поле «Цена»: в файле «16000», у позиции «17500» (оставлено как есть)`,
  };
}

describe('повторная загрузка файла переноса', () => {
  let report: unknown;

  beforeEach(() => {
    report = {
      imported: 0,
      skipped: [],
      quantityDefaulted: 0,
      repeat: {
        refilled: 2410,
        fields: [
          { field: 'Состояние', positions: 2410, links: 0 },
          { field: 'Снимки', positions: 1987, links: 6120 },
        ],
        unchanged: 487,
        discrepancyCount: 2,
        discrepancies: [discrepancy(1), discrepancy(2)],
        withoutAdNumber: 0,
      },
    };
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

  it('называет заведённое, дозаполненное по полям, нетронутое и расхождения', async () => {
    await loadFile();

    expect(text()).toContain('Заведено новых: 0');
    expect(text()).toContain(
      'Дозаполнено: 2 410 позиций. По полям: Состояние — 2 410, '
      + 'Снимки — 1 987 (6 120 ссылок).');
    expect(text()).toContain('Без изменений: 487');
    expect(text()).toContain('Расхождений: 2.');
    expect(screen.getByText(
      '№ 300000001, поле «Цена»: в файле «16000», у позиции «17500» (оставлено как есть)'))
      .toBeTruthy();
    expect(text(), 'обрезки нет, а экран говорит о ней').not.toContain('показано');
    // Снимки встали в очередь повтором — о переносе файлов надо сказать и тут.
    expect(screen.getByText(/Ссылки на снимки поставлены в очередь/)).toBeTruthy();
  });

  it('обрезанный список расхождений говорит, что он обрезан', async () => {
    const shown = Array.from({ length: 200 }, (_, i) => discrepancy(i));
    report = {
      imported: 0, skipped: [], quantityDefaulted: 0,
      repeat: {
        refilled: 0, fields: [], unchanged: 210, discrepancyCount: 210,
        discrepancies: shown, withoutAdNumber: 0,
      },
    };
    await loadFile();

    expect(text()).toContain('Расхождений: 210 — показано 200 из 210');
    expect(document.querySelectorAll('ul.suggestions li').length).toBe(200);
  });

  it('строки без номера объявления названы, а не пропущены молча', async () => {
    report = {
      imported: 3, skipped: [], quantityDefaulted: 0,
      repeat: {
        refilled: 0, fields: [], unchanged: 0, discrepancyCount: 0,
        discrepancies: [], withoutAdNumber: 3,
      },
    };
    await loadFile();

    expect(text()).toContain('Без номера объявления: 3 — заведены как новые.');
  });

  it('ответ прежней версии без итога повтора не роняет экран', async () => {
    report = { imported: 2, skipped: [] };
    await loadFile();

    expect(text()).toContain('Заведено новых: 2');
    expect(text()).not.toContain('Дозаполнено');
  });
});

async function loadFile(): Promise<void> {
  render(<ImportScreen reference={reference()} canImport />);
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  fireEvent.change(input, { target: { files: [new File(['xlsx'], 'perenos.xlsx')] } });
  await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());
  const select = [...document.querySelectorAll('select')]
    .find((s) => s.querySelector('option')?.textContent === '— выберите склад —');
  fireEvent.change(select!, { target: { value: '7' } });
  fireEvent.click(screen.getByText('Загрузить склад'));
  await waitFor(() => expect(text()).toContain('Заведено новых'));
}

/** Текст экрана одной строкой: числа приходят с неразрывными пробелами разрядов. */
function text(): string {
  return (document.body.textContent ?? '').replace(/\s+/g, (s) => (s.includes(' ') ? ' ' : ' '));
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
