import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';
import { closeBrowser, measureLayout, openBrowser, type Box } from '../test/narrowViewport';

/**
 * Состав открытой сделки («Найти сделку клиента» → клиент → сделка)
 * на телефоне 360 и 390 px (задача 0332).
 *
 * <p><b>Как это выглядело для человека.</b> Продавец у прилавка открывает
 * сделку клиента — для возврата или выдачи — и состав уезжает вбок: 373 px
 * при 360 (замер разбора PR 470, и до 0328, и после). Строка состава —
 * `label.stock-info.pick`, а у `.pick` раскладка flex-рядом: наименование,
 * слово состояния, номер производителя и хвост «№ · шт · статус» были
 * отдельными элементами ряда, переносились каждый внутри себя, но не друг
 * под друга, и последний вылезал за край колонки.
 *
 * <p><b>Почему отдельный тест.</b> `narrowViewport.test.tsx` меряет разделы,
 * как они открываются, а `sellerBasketNarrow.test.tsx` — выдачу и корзину:
 * открытой сделки не видит ни тот, ни другой. Здесь экран доведён до неё
 * настоящими нажатиями, и замер стоит только тогда, когда на странице
 * действительно состав этой сделки.
 *
 * <p><b>Что меряется.</b> Страница не шире экрана; каждый кусок строки
 * (флажок, наименование, слово состояния, номер, хвост) и цена — внутри
 * экрана; колонка не уже {@link MIN_INFO}; строка с номером не выше строки
 * без номера больше чем на одну строку текста, с номером на пределе
 * хранения — на четыре: показанные 40 знаков «A» сами шире колонки
 * в полтора-два раза и с хвостом «№ · шт · статус» дают три-четыре строки
 * (замерено: +63 px на 360 и +84 на 390 — ровно три строки, впритык;
 * шрифты раннера другие, и порог в три был бы лотереей). До правки эта
 * строка была 903 px — порог ловит её с запасом. Длинное наименование и длинная
 * услуга меряются на ширину, не на высоту.
 */

/**
 * Колонка наименования не уже этого — основа `.stock-info` (10rem, 0328):
 * меньше неё колонке рядом с ценой не оставляют, строка переносится. В составе
 * цена одна и короткая, поэтому колонка стоит рядом с ней — 192 px на 360,
 * 222 на 390; сжатая откатом 0328 колонка была 60–90.
 */
const MIN_INFO = 160;

/** Строка текста на телефоне: шрифт 18 px, межстрочный интервал браузера. */
const LINE = 28;

const BASE = 'Фара левая Toyota Camry 2007';
const LONG_TITLE = 'Фара левая Toyota Camry 2007–2011 ксенон в сборе с корректором, '
  + 'блоком розжига и лампой, оригинал Koito, без сколов и трещин, крепления целые';

interface Variant {
  name: string;
  title: string;
  oem: string | null;
  number: number | null;
  status: string;
}

/** Позиции состава — в этом порядке они стоят на экране. */
const ITEMS: Variant[] = [
  { name: 'без номера производителя', title: BASE, oem: null, number: 341, status: 'RESERVED' },
  { name: 'без номера позиции', title: BASE, oem: '84140-12530', number: null, status: 'RESERVED' },
  { name: 'обычный номер', title: BASE, oem: '84140-12530', number: 342, status: 'RESERVED' },
  { name: 'номер без разделителей', title: BASE, oem: '8414012530', number: 343, status: 'RESERVED' },
  { name: 'номер на пределе хранения без пробелов', title: BASE, oem: 'A'.repeat(1000),
    number: 344, status: 'RESERVED' },
  { name: 'очень длинное наименование', title: LONG_TITLE, oem: '84140-12530', number: 345,
    status: 'RESERVED' },
  // Выданная при отложенных — без флажка: выбор идёт по отложенным.
  { name: 'выдана, без флажка', title: BASE, oem: '84140-12530', number: 346, status: 'ISSUED' },
];

const SERVICES = [
  { id: 1, name: 'Доставка', quantity: '1', price: '500' },
  { id: 2, name: 'Доставка транспортной компанией до терминала в другом городе '
    + 'с обрешёткой и страховкой груза', quantity: '1', price: '1500' },
];

/** Сколько строк текста сверх строки без номера позволено позиции. */
const EXTRA_LINES: Record<string, number> = {
  'без номера позиции': 1,
  'обычный номер': 1,
  'номер без разделителей': 1,
  'номер на пределе хранения без пробелов': 4,
  'выдана, без флажка': 1,
};

/** Куски строки состава, каждый из которых обязан быть внутри экрана. */
const PIECES = [
  '.stock-info', '.stock-info > *', '.stock-info .oem-word', '.stock-price',
  '.stock-action', '.stock-info input[type="checkbox"]',
];

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('состав открытой сделки на телефоне', () => {
  beforeAll(async () => {
    await openBrowser();
  }, 60_000);

  afterAll(async () => {
    await closeBrowser();
  });

  it.each([360, 390])('%i px: без прокрутки вбок, каждый кусок строки в экране', async (width) => {
    stubDeal();
    const { container } = render(
      <SellerScreen canSell role="SELLER" company="t" memberId={1} />);
    await openDeal();
    const html = await settled(container);

    // Сторож обязан видеть именно состав открытой сделки, а не что-то,
    // что случайно оказалось на экране вместо неё.
    expect(html, 'сделка не открылась').toContain('Сделка №19');
    const layout = await measureLayout(html, ['.stock-row', ...PIECES], width);
    const rows = layout.boxes['.stock-row'] ?? [];
    expect(rows.length, 'в составе не по строке на позицию и услугу')
      .toBe(ITEMS.length + SERVICES.length);
    expect(layout.boxes['.stock-info input[type="checkbox"]']?.length,
      'флажков не по одному на отложенную позицию')
      .toBe(ITEMS.filter((v) => v.status === 'RESERVED').length);

    const infos = layout.boxes['.stock-info'] ?? [];
    // Таблица замера — в вывод прогона: её переносят в PR «до и после».
    console.log(`ширина ${width}: scrollWidth ${layout.scrollWidth}, clientWidth `
      + `${layout.clientWidth}\n${[...ITEMS.map((v) => v.name), ...SERVICES.map((s) =>
        `услуга «${s.name.slice(0, 20)}»`)].map((name, i) =>
        `  ${name}: строка ${rows[i]?.height} px, колонка ${infos[i]?.width}`
        + ` (${infos[i]?.left}–${infos[i]?.right})`).join('\n')}`);

    expect(layout.scrollWidth,
      `состав уехал вбок — ${layout.scrollWidth} при ${layout.clientWidth}`)
      .toBeLessThanOrEqual(layout.clientWidth);

    const inside = (box: Box) => box.left >= 0 && box.right <= layout.clientWidth;
    for (const key of PIECES) {
      (layout.boxes[key] ?? []).forEach((box, i) => {
        expect(inside(box), `${key} №${i + 1} за краем экрана — ${box.left}–${box.right}`)
          .toBe(true);
      });
    }
    infos.forEach((info, i) => {
      expect(info.width, `колонка наименования №${i + 1} сжата`)
        .toBeGreaterThanOrEqual(MIN_INFO);
    });
    // Цена — у правого края строки, где её ищут глазами (0328).
    (layout.boxes['.stock-action'] ?? []).forEach((box, i) => {
      expect(box.right, `цена №${i + 1} не у правого края строки`).toBe(rows[i]?.right);
    });

    const base = rows[0]!.height;
    ITEMS.forEach((variant, i) => {
      const extra = EXTRA_LINES[variant.name];
      if (extra === undefined) return;
      expect(rows[i]!.height,
        `${variant.name}: ${rows[i]!.height} px против ${base} без номера`)
        .toBeLessThanOrEqual(base + extra * LINE);
    });
  }, 30_000);
});

// ---------- заглушки ----------

function stubDeal(): void {
  const deal = {
    id: 19, number: 19, customerId: 1, customerName: 'Иванов Пётр',
    managerId: null, managerName: null, stage: null,
    status: 'RESERVED', reservedUntil: null,
    totalAmount: '89500', paidAmount: '0', debt: '89500',
    createdAt: '2026-09-01T10:00:00Z', issuedAt: null, warehouseId: null,
    marketplace: null, externalOrderNo: null, replyDeadline: null,
    orderAcceptedAt: null, deliveryNote: null,
    items: ITEMS.map((v, i) => ({
      id: i + 1, partId: 10 + i, number: v.number, title: v.title, condition: 'CONTRACT',
      primaryOem: v.oem, quantity: '1', price: '12500', discount: null, warehouseId: 2,
      status: v.status,
    })),
    services: SERVICES,
  };
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/customers/1/account')) {
      return json({ customerId: 1, balance: 0, entries: [] });
    }
    if (url.includes('/api/customers?')) {
      return json([{ id: 1, name: 'Иванов Пётр', phone: '+79990001122',
        email: null, customerType: 'PERSON' }]);
    }
    if (url.includes('/api/deals?customerId')) {
      return json([deal]);
    }
    if (url.includes('/api/deals/19')) {
      return json(deal);
    }
    return json([]);
  }));
}

/** Тот же путь, что у продавца: кнопка, клиент по имени, его сделка. */
async function openDeal(): Promise<void> {
  fireEvent.click(buttonBy((t) => t === 'Найти сделку клиента')!);
  const input = [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'имя или телефон')!;
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')!.set!;
  setter.call(input, 'Иванов');
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await waitFor(() => expect(buttonBy((t) => t.includes('Иванов'))).toBeTruthy());
  fireEvent.click(buttonBy((t) => t.includes('Иванов'))!);
  await waitFor(() => expect(buttonBy((t) => t.includes('№19'))).toBeTruthy());
  fireEvent.click(buttonBy((t) => t.includes('№19'))!);
  await waitFor(() => expect(document.querySelectorAll('.stock-row').length)
    .toBe(ITEMS.length + SERVICES.length));
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

function buttonBy(match: (text: string) => boolean): HTMLButtonElement | undefined {
  return [...document.querySelectorAll('button')]
    .find((b) => match((b.textContent ?? '').trim())) as HTMLButtonElement | undefined;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
