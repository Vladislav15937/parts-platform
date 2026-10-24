import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { forgetCatalog } from '../access/rights';
import { rightsAs, rightsWith } from '../test/rights';
import { CatalogScreen } from './CatalogScreen';
import {
  PHONE_WIDTH, closeBrowser, measureLayout, measureOverflow, openBrowser,
} from '../test/narrowViewport';

/**
 * Печать этикеток со «Склада» на телефоне 390 px (задача 0371).
 *
 * <p>Сторож ширины (`narrowViewport.test.tsx`) меряет «Склад» таким, каким
 * он открывается, — без режима отметки. А панель отметки с этой задачи несёт
 * ещё одну кнопку и, без права, причину видимым текстом до сотни знаков,
 * а окно печати — три длинных подписи режимов и поле числа. Здесь меряются
 * именно эти состояния настоящим Chrome: страница не шире экрана, и ни один
 * элемент панели и окна не вылез за его край.
 */
describe('печать этикеток со склада на телефоне', () => {
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

  it('панель отметки с погашенной печатью и причиной — в экране', async () => {
    stubApi(1001);
    render(<CatalogScreen rights={rightsWith('part.edit')} />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(2));
    fireEvent.click(button('Правка списком')!);
    fireEvent.click(button('Выбрать весь отбор (1 001)')!);

    await expectInside(['.filter-row > *', '.locked-action__reason'], 1);
  }, 30_000);

  it('окно «Печать этикеток» с полем числа — в экране', async () => {
    stubApi(2);
    render(<CatalogScreen rights={rightsAs('OWNER')} />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(2));
    fireEvent.click(button('Правка списком')!);
    for (const tr of document.querySelectorAll('tbody tr')) fireEvent.click(tr);
    fireEvent.click(button('Напечатать этикетки')!);
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByLabelText('Указанное количество на товар'));

    await expectInside(['.modal', '.modal label', '.modal button'], 6);
  }, 30_000);
});

/** Страница не шире экрана, и каждый названный элемент — внутри него. */
async function expectInside(parts: string[], atLeast: number): Promise<void> {
  const html = await settled(document.body);
  const { scrollWidth, clientWidth, boxes } = await measureLayout(html, parts);
  const found = parts.reduce((sum, part) => sum + (boxes[part]?.length ?? 0), 0);
  expect(found, 'мерить нечего — экран не в том состоянии').toBeGreaterThanOrEqual(atLeast);
  const outside = parts.flatMap((part) => (boxes[part] ?? [])
    .filter((box) => box.right > clientWidth || box.left < 0)
    .map((box) => `${part}: ${box.left}…${box.right}`));
  expect(outside, `вылезло за экран ${PHONE_WIDTH}`).toEqual([]);
  expect(scrollWidth, `страница уехала вбок: ${scrollWidth} при ${clientWidth}`).toBe(clientWidth);
  // Коробка в экране ещё не значит, что текст в коробке: окно держит свою
  // ширину, а неразрывное слово вылезает из заголовка за его край.
  const spilled = await measureOverflow(html, parts.map((part) => `${part}, ${part} *`).join(', '));
  expect(spilled, 'текст вылез из своей коробки').toEqual([]);
}

function stubApi(total: number): void {
  forgetCatalog();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://localhost');
    if (url.pathname === '/api/parts/catalog') {
      return json({ total, warehouses: [], filterable: [], rows: ROWS });
    }
    if (url.pathname === '/api/access/catalog') {
      // Слова прав — той длины, что у сервера: причина ширину и задаёт.
      return json([{ key: 'part.cell', section: 'Склад',
        title: 'Раскладывать по полкам и печатать этикетки' }]);
    }
    if (url.pathname.startsWith('/api/me/settings')) return json({ value: null });
    return json([]);
  }));
}

/** Наименования той длины, что у клиента. */
const ROWS = [1, 2].map((id) => ({
  id, number: 100 + id, code: `ABC00${id}`, qty: id + 1, price: 12500, condition: 'USED',
  title: 'Фара левая Toyota Land Cruiser Prado 150 2015 (б/у)',
  stock: { 1: id + 1 }, reserved: {}, photoCount: 0,
}));

function button(text: string): HTMLElement | undefined {
  return [...document.querySelectorAll('button')].find((b) => b.textContent === text);
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

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
