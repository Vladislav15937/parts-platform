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
  detected: {
    NAME: 2, PRICE: 5, QUANTITY: 6, AVAILABILITY: 7, OEM: 11, DESCRIPTION: 17, PHOTOS: 21,
    DROM_AD_NUMBER: 0,
    // Поля карточки Дрома (задача 0277).
    CONDITION: 8, AUTHENTICITY: 9, MANUFACTURER: 10, OEM_SUBSTITUTES: 12,
    SIDE_LR: 13, SIDE_FR: 14, SIDE_UD: 15,
  },
  missingRequired: [],
  rows: [ROW],
  format: 'DROM_TRANSFER',
};

describe('файл переноса с Дрома', () => {
  let answer: unknown;
  // Сколько снимков в очереди: ноль до загрузки, четырнадцать после, —
  // как на сервере, где их ставит в очередь сама загрузка.
  let queued: number;

  beforeEach(() => {
    answer = PREVIEW;
    queued = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/import/excel/preview')) {
        return json(answer);
      }
      if (url.endsWith('/api/import/excel')) {
        queued = 14;
        return json({ imported: 1, skipped: [] });
      }
      return json({ done: 0, failed: 0, pending: queued, total: 0, broken: 0 });
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
    expect(mapping('Наличие')).toBe('7');
    expect(mapping('Фото'), 'снимки клиента не сопоставлены — экран назвал бы их непереносимыми')
      .toBe('21');
    expect(mapping('Номер объявления Дрома')).toBe('0');
    // Поля карточки Дрома (задача 0277): каждое своим словом и своей колонкой.
    expect(mapping('Состояние')).toBe('8');
    expect(mapping('Оригинальность')).toBe('9');
    expect(mapping('Производитель')).toBe('10');
    expect(mapping('Номера замен')).toBe('12');
    expect(mapping('Сторона')).toBe('13');
    expect(mapping('Положение')).toBe('14');
    expect(mapping('Верх/низ')).toBe('15');
    expect(screen.queryByText(/Не сопоставлено:/)).toBeNull();
  });

  it('говорит до загрузки, что пустое количество станет одной штукой (задача 0279)', async () => {
    pick();
    await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());

    expect(screen.getByText(/количество пустое — такие позиции заведутся по 1 шт\./))
      .toBeTruthy();
    expect(screen.getByText(/не «В наличии», загрузка пропустит/)).toBeTruthy();
  });

  it('описание клиента переносится описанием, а не примечанием (задача 0275)', async () => {
    pick();
    await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());

    expect(mapping('Примечание'), 'описание объявления легло во внутренний комментарий')
      .toBe('');
    expect(mapping('Описание'), 'описание клиента не сопоставлено — экран назвал бы его непереносимым')
      .toBe('17');
    expect(columnHead('Описание')).toContain('→ Описание');
    expect(columnHead('Описание')).not.toContain('не переносится');
    expect(columnHead('Описание')).not.toContain('Примечание');
  });

  it('называет каждую колонку, которую загрузка пропустит, и только их', async () => {
    pick();
    await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());

    const skipped = screen.queryByText(/Не переносятся/)?.textContent ?? '';
    for (const title of ['Применимость', 'Гарантия', 'Доставка и оплата']) {
      expect(skipped, `о колонке «${title}» экран промолчал`).toContain(title);
    }
    // «Фото» стоит в файле последней, и в перечне её выдала бы точка, а не
    // запятая, — поэтому отдельно и целиком.
    expect(skipped, 'снимки названы непереносимыми').not.toContain('Фото');
    for (const title of ['Номер объявления Дрома', 'Наименование', 'Цена', 'Количество', 'Наличие',
      'Состояние', 'Оригинальность', 'Производитель', 'Номера замен', 'Сторона', 'Положение',
      'Верх/низ', 'Описание']) {
      expect(skipped, `читаемая «${title}» названа непереносимой`).not.toContain(`${title},`);
    }
    // Ровно 7 из 22: пятнадцать колонок договора импорт читает сегодня
    // (семь — с задачи 0277, описание — с 0275).
    expect((skipped.split(':')[1] ?? '').split(',').length).toBe(7);
    expect(columnHead('Наименование')).toContain('→ Наименование');
    expect(columnHead('Фото')).toContain('→ Фото');
  });

  it('после загрузки счётчик снимков сразу показывает поставленное в очередь', async () => {
    render(<ImportScreen reference={reference([{ id: 7, name: 'Исакова' }])} canImport />);
    await waitFor(() => expect(screen.getByText(/ждёт 0/)).toBeTruthy());
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['xlsx'], 'perenos-s-droma.xlsx')] } });
    await waitFor(() => expect(document.querySelector('table.preview')).toBeTruthy());

    // Первый выбор склада — загрузки таблицы; второй ниже — у переноса колёс.
    const warehouse = screen.getAllByText('— выберите склад —')[0]!
      .closest('select') as HTMLSelectElement;
    fireEvent.change(warehouse, { target: { value: '7' } });
    fireEvent.click(screen.getByText('Загрузить склад'));

    await waitFor(() => expect(screen.getByText(/Заведено новых: 1/)).toBeTruthy());
    expect(screen.getByText(/Ссылки на снимки поставлены в очередь/)).toBeTruthy();
    // Счётчик, оставшийся на «ждёт 0», говорит владельцу «переносить нечего» —
    // так у клиента прежней системы и осталось восемь снимков из ста девяноста тысяч.
    await waitFor(() => expect(screen.getByText(/ждёт 14/)).toBeTruthy());
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

function reference(warehouses: { id: number; name: string }[] = []): never {
  return { warehouses, supplies: [], donors: [], cells: [], partNames: [] } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
