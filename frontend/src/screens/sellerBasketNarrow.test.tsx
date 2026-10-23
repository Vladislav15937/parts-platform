import { rightsAs } from '../test/rights';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';
import { closeBrowser, measureLayout, openBrowser, type Box } from '../test/narrowViewport';

/**
 * Выдача и набранная корзина продавца на телефоне 360 и 390 px (задача 0328).
 *
 * <p><b>Как это выглядело для человека.</b> Продавец у прилавка набирает
 * корзину — и страница уезжает вбок (398 px при 360 и при 390): поле цены
 * в корзине по умолчанию шириной в двадцать знаков, рядом «убрать», блок
 * не сжимается, и колонке наименования не остаётся ничего — 0 px. В выдаче
 * та же колонка 33–72 px: наименование шло по слову в строку, а с номером
 * производителя (0327) строка вырастала до 420 px, в корзине — до 588.
 * Строку надо было листать, чтобы прочитать одну позицию.
 *
 * <p><b>Почему отдельный тест, а не `narrowViewport.test.tsx`.</b> Тот
 * меряет разделы в том виде, в каком они открываются, — без выдачи и без
 * корзины, и поломку корзины не видел вовсе (замерено разбором PR 466).
 * Здесь экран доведён до набранной корзины настоящими нажатиями.
 *
 * <p><b>Что меряется.</b> Страница не шире экрана; колонка наименования
 * не уже {@link MIN_INFO}; цена, поле цены и кнопки — внутри экрана и в цель
 * пальцем; строка с номером не выше строки без номера больше чем на одну
 * строку текста, с номером на пределе хранения — на две (показано 40 знаков
 * и «…», а не тысяча). Наименование длиной в абзац меряется на ширину,
 * не на высоту: длинное название длинно законно.
 */

/**
 * Колонка наименования не уже этого. 200 px при шрифте 18 px — это
 * «Фара левая Toyota» одной строкой: уже — и наименование снова идёт
 * по слову в строку, как было до правки (33–72 px).
 */
const MIN_INFO = 200;

/** Строка текста на телефоне: шрифт 18 px, межстрочный интервал браузера. */
const LINE = 28;

/** Цель для пальца, как у `.button--ghost` — меньшая из кнопок строки. */
const MIN_TARGET = 44;

const BASE = 'Фара левая Toyota Camry 2007';
const LONG_TITLE = 'Фара левая Toyota Camry 2007–2011 ксенон в сборе с корректором, '
  + 'блоком розжига и лампой, оригинал Koito, без сколов и трещин, крепления целые';

/** Варианты строк — в этом порядке они стоят и в выдаче, и в корзине. */
const VARIANTS: { name: string; title: string; oem: string | null }[] = [
  { name: 'без номера', title: BASE, oem: null },
  { name: 'обычный номер', title: BASE, oem: '84140-12530' },
  { name: 'номер без разделителей', title: BASE, oem: '8414012530' },
  { name: 'номер на пределе хранения без пробелов', title: BASE, oem: 'A'.repeat(1000) },
  { name: 'очень длинное наименование', title: LONG_TITLE, oem: '84140-12530' },
];

/** Сколько строк текста сверх строки без номера позволено варианту. */
const EXTRA_LINES: Record<string, number> = {
  'обычный номер': 1,
  'номер без разделителей': 1,
  'номер на пределе хранения без пробелов': 3,
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('выдача и корзина продавца на телефоне', () => {
  beforeAll(async () => {
    await openBrowser();
  }, 60_000);

  afterAll(async () => {
    await closeBrowser();
  });

  it.each([360, 390])('%i px: без прокрутки вбок, колонка читаема, строка не в полтора экрана',
    async (width) => {
      stubSeller();
      const { container } = render(
        <SellerScreen canSell rights={rightsAs('SELLER')} company="t" memberId={1} />);
      await search();
      // Выдача — в том виде, в каком она открылась.
      const found = await measure(await settled(container), width);

      // Корзина набирается настоящими нажатиями: без неё сторож ширины
      // видит только раздел, как он открывается, — и корзины не видел вовсе.
      for (let i = 0; i < VARIANTS.length; i += 1) {
        const add = [...document.querySelectorAll('button')]
          .find((b) => b.textContent === 'в сделку');
        expect(add, 'кнопки «в сделку» не осталось').toBeTruthy();
        fireEvent.click(add!);
      }
      await screen.findByText('В сделку');
      const basket = await measure(await settled(container), width);
      expect(basket.boxes['.stock-action input']?.length, 'поля цены в корзине нет')
        .toBe(VARIANTS.length);

      const rowsFound = (found.boxes['.stock-row'] ?? []);
      const rowsBasket = (basket.boxes['.stock-row'] ?? []).slice(VARIANTS.length);
      const infoFound = found.boxes['.stock-info'] ?? [];
      const infoBasket = (basket.boxes['.stock-info'] ?? []).slice(VARIANTS.length);
      expect(rowsFound.length, 'в выдаче не по строке на вариант').toBe(VARIANTS.length);
      expect(rowsBasket.length, 'в корзине не по строке на вариант').toBe(VARIANTS.length);

      // Таблица замера — в вывод прогона: её переносят в PR «до и после».
      console.log(`ширина ${width}: scrollWidth выдачи ${found.scrollWidth}, с корзиной `
        + `${basket.scrollWidth}, clientWidth ${basket.clientWidth}\n${VARIANTS.map((v, i) =>
          `  ${v.name}: выдача ${rowsFound[i]?.height} (колонка ${infoFound[i]?.width}),`
          + ` корзина ${rowsBasket[i]?.height} (колонка ${infoBasket[i]?.width})`).join('\n')}`);

      for (const [state, layout] of [['выдача', found], ['с корзиной', basket]] as const) {
        expect(layout.scrollWidth,
          `${state}: страница уехала вбок — ${layout.scrollWidth} при ${layout.clientWidth}`)
          .toBeLessThanOrEqual(layout.clientWidth);

        (layout.boxes['.stock-info'] ?? []).forEach((info, i) => {
          expect(info.width, `${state}: колонка наименования №${i + 1} сжата`)
            .toBeGreaterThanOrEqual(MIN_INFO);
        });

        const inside = (box: Box) => box.left >= 0 && box.right <= layout.clientWidth;
        for (const key of ['.stock-action', '.stock-price', '.stock-action input']) {
          (layout.boxes[key] ?? []).forEach((box, i) => {
            expect(inside(box),
              `${state}: ${key} №${i + 1} за краем экрана — ${box.left}–${box.right}`)
              .toBe(true);
          });
        }
        // Цена ушла под наименование — но к правому краю строки, где её
        // ищут глазами, а не в начало второй строки под текстом.
        (layout.boxes['.stock-action'] ?? []).forEach((box, i) => {
          const row = layout.boxes['.stock-row']?.[i];
          expect(box.right, `${state}: цена №${i + 1} не у правого края строки`)
            .toBe(row?.right);
        });
        (layout.boxes['.stock-action button'] ?? []).forEach((box, i) => {
          expect(inside(box), `${state}: кнопка №${i + 1} за краем экрана`).toBe(true);
          expect(box.height, `${state}: кнопка №${i + 1} мельче пальца`)
            .toBeGreaterThanOrEqual(MIN_TARGET);
          expect(box.width, `${state}: кнопка №${i + 1} сжата`)
            .toBeGreaterThanOrEqual(MIN_TARGET);
        });
      }

      for (const [list, boxes] of [['выдача', rowsFound], ['корзина', rowsBasket]] as const) {
        const base = boxes[0]!.height;
        VARIANTS.forEach((variant, i) => {
          const extra = EXTRA_LINES[variant.name];
          if (extra === undefined) return;
          expect(boxes[i]!.height,
            `${list}, ${variant.name}: ${boxes[i]!.height} px против ${base} без номера`)
            .toBeLessThanOrEqual(base + extra * LINE);
        });
      }
    }, 30_000);
});

function measure(html: string, width: number) {
  return measureLayout(html, [
    '.stock-row', '.stock-info', '.stock-action',
    '.stock-action button', '.stock-action input', '.stock-price',
  ], width);
}

// ---------- заглушки ----------

function stubSeller(): void {
  const rows = VARIANTS.map((variant, i) => ({
    partId: i + 1, number: 340 + i, publicCode: `7584A8FEAE3${i}`, title: variant.title,
    price: '12500', status: 'IN_STOCK', warehouseId: 2, warehouseName: 'Ткацкая',
    cellCode: 'А-12', qty: '1', qtyReserved: '0', qtyAvailable: '1', expected: false,
    expectedOn: null, condition: 'CONTRACT', primaryOem: variant.oem,
  }));
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/parts/stock')) {
      return json({ total: rows.length, facets: { vehicles: [], grades: [] }, rows });
    }
    if (url.includes('/warehouses')) {
      return json([{ id: 2, branchId: 1, name: 'Ткацкая', branchName: 'Филиал', cells: 0,
        isActive: true }]);
    }
    return json([]);
  }));
}

async function search(): Promise<void> {
  const input = document.querySelector('input') as HTMLInputElement;
  fireEvent.change(input, { target: { value: 'фара' } });
  fireEvent.click(screen.getByRole('button', { name: 'Найти' }));
  await waitFor(() => expect(document.querySelectorAll('.stock-row').length)
    .toBe(VARIANTS.length));
}

/** Разметка, когда экран догрузился: ждём, пока она перестанет меняться. */
async function settled(container: HTMLElement): Promise<string> {
  let previous = '';
  for (let step = 0; step < 40; step += 1) {
    await act(async () => {
      await new Promise((resolve) => { setTimeout(resolve, 10); });
    });
    const html = container.innerHTML;
    if (html !== '' && html === previous) return html;
    previous = html;
  }
  return container.innerHTML;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
