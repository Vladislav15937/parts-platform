import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { rightsAs } from '../test/rights';
import { CustomersScreen } from './CustomersScreen';
import {
  PHONE_WIDTH, closeBrowser, measureLayout, openBrowser,
} from '../test/narrowViewport';

/**
 * Карточка клиента на телефоне 390 px — с открытым «Пополнить / Списать».
 *
 * <p><b>Как это выглядело для человека.</b> Владелец открывает клиента —
 * и страница уезжает вбок на 13 пикселей: ряд вкладок карточки («Клиент ·
 * Сделки · Возвраты · Платежи») доходил до 403 при 390. Найдено замером
 * разбора PR 508 (задача 0044) с раскрытым «Пополнить / Списать», но
 * раскрытие тут ни при чём — уезжает и карточка, как открылась (мелочь
 * из ответа владельца 24.10.2026). Сторож ширины
 * (`narrowViewport.test.tsx`) меряет раздел «Клиенты» списком, как он
 * открывается: карточки он не видит вовсе.
 *
 * <p><b>Что меряется.</b> Настоящим Chrome в оболочке приложения
 * (`test/narrowViewport.ts`): страница не шире экрана, каждая вкладка
 * и каждый элемент ряда «сумма · источник · Положить · Выдать» — внутри
 * экрана. Замер стоит, только когда ряд действительно раскрыт и источник
 * платежа в нём есть: пустой экран прошёл бы любое утверждение о ширине.
 */
describe('карточка клиента на телефоне', () => {
  beforeAll(async () => {
    await openBrowser();
  }, 60_000);

  afterAll(async () => {
    await closeBrowser();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  /** Карточка как открылась и с раскрытым рядом «сумма · источник · Положить · Выдать». */
  const STATES: Array<[string, boolean]> = [
    ['как открылась', false],
    ['с открытым «Пополнить / Списать»', true],
  ];

  it.each(STATES)('%s — не уводит страницу вбок', async (_name, acting) => {
    stubApi();
    render(<CustomersScreen rights={rightsAs('OWNER')} company="t_1" memberId={7} onOpenDeal={() => {}} />);
    await waitFor(() => expect(screen.getByText(CUSTOMER.name)).toBeTruthy());
    fireEvent.click(screen.getByText(CUSTOMER.name));
    const topUp = await screen.findByRole('button', { name: 'Пополнить / Списать' });
    if (acting) {
      fireEvent.click(topUp);
      await screen.findByRole('button', { name: 'Положить' });
      await waitFor(() => expect(screen.getByRole('combobox', { name: 'Источник платежа' }))
        .toBeTruthy());
    }

    const html = await settled(document.body);
    const PARTS = ['.tabs .tab', '.card .row > *'];
    const { scrollWidth, clientWidth, boxes } = await measureLayout(html, PARTS);

    const tabs = boxes['.tabs .tab'] ?? [];
    expect(tabs.length, 'вкладок карточки на странице нет — мерить нечего').toBe(4);
    expect(boxes['.card .row > *']?.length ?? 0, 'ряд «Пополнить / Списать» не в том состоянии')
      .toBe(acting ? 4 : 0);

    const outside = PARTS.flatMap((part) => (boxes[part] ?? [])
      .filter((box) => box.right > clientWidth || box.left < 0)
      .map((box) => `${part}: ${box.left}…${box.right}`));
    expect(outside, `элементы карточки вылезли за экран ${PHONE_WIDTH}`).toEqual([]);
    expect(
      scrollWidth,
      `карточка клиента уводит страницу вбок на ${scrollWidth - clientWidth} пикселей `
      + `(scrollWidth ${scrollWidth} при clientWidth ${clientWidth}, экран ${PHONE_WIDTH})`,
    ).toBe(clientWidth);
    // Обратная сторона: вкладки переносятся рядами, а не сжимаются. Сжатые
    // в одну строку, они садятся по 73 пикселя, и «Возвраты» ломается
    // по буквам (min-height держит высоту, поэтому меряется ширина). Ширину
    // перенесённой вкладки задаёт ряд, а не шрифт: три в ряд — около 100,
    // две — около 155, одна — во всю строку.
    expect(
      tabs.filter((box) => box.width < MIN_TAB).map((box) => box.width),
      `вкладка сжата уже ${MIN_TAB} пикселей, а не перенесена`,
    ).toEqual([]);
  }, 30_000);
});

/** Ширина вкладки не меньше этого: перенесённая — от 100, сжатая — 73. */
const MIN_TAB = 90;

/** Клиент с длинным названием — как у живого: ширину задаёт содержимое. */
const CUSTOMER = {
  id: 1, name: 'Автосервис на Русской', phone: '+7 914 000-00-00', email: null,
  customerType: 'PERSON', note: null, publicNote: null, inn: null, companyName: null, balance: 1000,
};

/** Источники платежа — с названиями той длины, что заводят клиенты. */
const SOURCES = [
  { id: 1, name: 'Наличные в кассе на Русской', type: 'CASH', archived: false },
  { id: 2, name: 'Перевод на карту владельца', type: 'CARD', archived: false },
];

function stubApi(): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/payment-sources')) return json(SOURCES);
    if (url.includes('/api/customers/directory')) return json({ items: [CUSTOMER], total: 1 });
    if (url.includes('/account')) return json({ customerId: 1, balance: 1000, entries: [] });
    if (url.includes('/api/customers/')) return json(CUSTOMER);
    return json([]);
  }));
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Разметка, когда экран перестал меняться: ответы заглушки доехали. */
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
