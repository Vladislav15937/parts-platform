import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ImportScreen } from './ImportScreen';

/**
 * Файл переноса с Дрома на экране «Импорт» (задача 0272).
 *
 * <p>Экран обязан сопоставить колонки сам и не называть сопоставленной
 * колонку, которую загрузка отбросит. Раньше «Описание» по синониму ложилось
 * в «Примечание» — внутренний комментарий, которого покупатель не видит, —
 * а снимки, номер объявления и состояние уходили молча: экран о них не
 * говорил ничего, и молчание читалось как «перенесётся».
 *
 * <p>Ответ сервера — тот, что отдаёт разбор образца
 * {@code src/test/resources/migration/excel/drom-transfer-sample.xlsx}
 * (проверено {@code DromTransferFileTest.previewMapsReadColumnsOnly}).
 */
const HEADER = [
  'Номер объявления Дрома', 'Ссылка', 'Наименование', 'Вид детали', 'Раздел', 'Цена',
  'Количество', 'Наличие', 'Состояние', 'Оригинальность', 'Производитель', 'Номер',
  'Номера замен', 'Сторона', 'Положение', 'Верх/низ', 'Применимость', 'Описание',
  'Гарантия', 'Самовывоз', 'Доставка и оплата', 'Фото',
];

const ROW = [
  '125696381', 'https://baza.drom.ru/barnaul/sell_spare_parts/kardannyj-val-125696381.html',
  'Карданный вал передний (Пробег 130т. км)Toyota Land Cruiser Prado 120',
  'Карданный вал Lexus GX400', 'Автозапчасти → Трансмиссия', '16000', '', 'В наличии', 'Б/у',
  'Оригинал', 'Toyota', '3714060380', 'HLKA0017, NWNTY011', '', 'Переднее', '',
  'Lexus GX400 | GRJ158 | ', 'Подходит на автомобиль с левым и правым рулем.',
  'Обговаривается индивидуально', 'Самовывоз — улица Георгия Исакова 9',
  'Доставка по городу Барнаул — 300 р.', 'https://static.baza.drom.ru/drom/1737520136727_default',
];

const PREVIEW = {
  header: HEADER,
  detected: { NAME: 2, PRICE: 5, QUANTITY: 6, OEM: 11 },
  missingRequired: [],
  rows: [ROW],
  format: 'DROM_TRANSFER',
};

describe('файл переноса с Дрома', () => {
  let answer: unknown;

  beforeEach(() => {
    answer = PREVIEW;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/import/excel/preview')) {
        return json(answer);
      }
      return json({ done: 0, failed: 0, pending: 0, total: 0, broken: 0 });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('говорит, что колонки сопоставлены сами, и ничего не просит выбрать', async () => {
    pick();

    await waitFor(() => expect(screen.getByText(/файл переноса с Дрома/)).toBeTruthy());
    expect(mapping('Наименование')).toBe('2');
    expect(mapping('Цена')).toBe('5');
    expect(mapping('Количество')).toBe('6');
    expect(mapping('Номер детали')).toBe('11');
    expect(screen.queryByText(/Не сопоставлено:/)).toBeNull();
  });

  it('описание клиента не выдаётся за примечание', async () => {
    pick();
    await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());

    expect(mapping('Примечание'), 'описание объявления легло во внутренний комментарий')
      .toBe('');
    expect(columnHead('Описание')).toContain('не переносится');
    expect(columnHead('Описание')).not.toContain('Примечание');
  });

  it('называет каждую колонку, которую загрузка пропустит, и только их', async () => {
    pick();
    await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());

    const skipped = screen.queryByText(/Не переносятся/)?.textContent ?? '';
    for (const title of ['Номер объявления Дрома', 'Состояние', 'Описание', 'Фото',
      'Применимость', 'Доставка и оплата']) {
      expect(skipped, `о колонке «${title}» экран промолчал`).toContain(title);
    }
    for (const title of ['Наименование', 'Цена', 'Количество']) {
      expect(skipped, `читаемая «${title}» названа непереносимой`).not.toContain(`${title},`);
    }
    // Ровно 18 из 22: четыре колонки договора импорт читает сегодня.
    expect(skipped.split(':')[1].split(',').length).toBe(18);
    expect(columnHead('Наименование')).toContain('→ Наименование');
  });

  it('ответ прежней версии без признака формата не роняет экран', async () => {
    const older: Partial<typeof PREVIEW> = { ...PREVIEW };
    delete older.format;
    answer = older;
    pick();

    await waitFor(() => expect(screen.getByText(/Проверьте до запуска/)).toBeTruthy());
    expect(screen.queryByText(/файл переноса с Дрома/)).toBeNull();
  });
});

function pick(): void {
  render(<ImportScreen reference={reference()} canImport />);
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  const file = new File(['xlsx'], 'perenos-s-droma.xlsx');
  fireEvent.change(input, { target: { files: [file] } });
}

/** Номер колонки, выбранной для поля, — как его держит выбор на экране. */
function mapping(label: string): string {
  const row = [...document.querySelectorAll('.mapping-row')]
    .find((li) => li.querySelector('span')?.textContent?.startsWith(label));
  expect(row, `строки «${label}» в сопоставлении нет`).toBeTruthy();
  return (row!.querySelector('select') as HTMLSelectElement).value;
}

function columnHead(title: string): string {
  const th = [...document.querySelectorAll('table.preview th')]
    .find((cell) => cell.firstChild?.textContent === title);
  expect(th, `колонки «${title}» в таблице нет`).toBeTruthy();
  return th!.textContent ?? '';
}

function reference(): never {
  return { warehouses: [], supplies: [], donors: [], cells: [], partNames: [] } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
