import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Свои условия отбора владелец заводит сам, с экрана.
 *
 * <p><b>Зачем.</b> Зашитых условий было шесть — цена, состояние, склады,
 * наименования, марки, — и каждое седьмое означало релиз. Витрину склада
 * владелец к этому времени отбирает по двадцати девяти колонкам; выгрузка
 * не брала из них ни одной, и разложить склад по прайс-листам иначе, чем
 * по цене, было нечем.
 *
 * <p>Проверяется связка целиком: колонки приходят с сервера, выбранное
 * уезжает в сохранение и в счётчик, а поставленное условие видно и снимается.
 * Список колонок здесь тот же, что у витрины, — второй разошёлся бы с ней
 * на первой правке, и экран предлагал бы отбор, которого сервер не знает.
 */
describe('свои условия выгрузки', () => {
  let saved: unknown = null;
  let counted: unknown = null;

  beforeEach(() => {
    saved = null;
    counted = null;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const body = init?.body === undefined ? null : JSON.parse(String(init.body));

      if (url.includes('/filter/count')) {
        counted = body;
        return json({ parts: 15 });
      }
      if (url.includes('/filter')) {
        saved = body;
        return json(feed({ filterWords: { manufacturer: 'toki' } }));
      }
      if (url.includes('/api/parts/catalog')) {
        // Отвечает не мгновенно, и это часть проверки. Мок, отвечающий
        // в микрозадаче, прячет гонку: select уже нарисован, опции ещё нет,
        // и подстановка значения молча ничего не делает. Локально так всё
        // и проходило, а покраснело на раннере CI. С задержкой тот же промах
        // падает всегда, а не раз в двенадцать прогонов.
        await new Promise((r) => setTimeout(r, 50));
        // Список отбираемых колонок — с сервера, как и на витрине.
        return json({ total: 0, rows: [], warehouses: [],
                      filterable: ['manufacturer', 'section'] });
      }
      if (url.includes('/api/marketplace-accounts')) {
        return json([feed()]);
      }
      if (url.includes('/api/catalog/vehicles')) {
        return json({ brands: [], models: [], generations: [], modifications: [] });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('уезжает в счётчик и в сохранение, а потом снимается', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    // Колонка выбирается из того, что назвал сервер.
    //
    // Ждать надо появления самой опции, а не поля: select рисуется сразу,
    // а список колонок приезжает запросом, и до его ответа в нём только
    // «— выберите колонку —». Подстановка значения, которому нет опции, —
    // в jsdom пустая операция: значение остаётся пустым, условие
    // не добавляется, и падает уже проверка значка ниже. Локально мок
    // успевает ответить в микрозадаче, на раннере CI — не всегда;
    // ровно так этот тест и покраснел, зелёный двенадцать прогонов подряд.
    const column = await screen.findByLabelText('Колонка');
    await waitFor(() =>
      expect(within(column).getByRole('option', { name: 'Производитель' })).toBeTruthy());
    fireEvent.change(column, { target: { value: 'manufacturer' } });
    fireEvent.change(screen.getByLabelText('Содержит'), { target: { value: 'toki' } });
    fireEvent.click(screen.getByRole('button', { name: 'Добавить' }));

    // Условие видно на экране: невидимое нельзя ни проверить, ни снять.
    await waitFor(() => expect(screen.getByText(/Производитель: «toki»/)).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Посчитать' }));
    await waitFor(() => expect(counted).not.toBeNull());
    expect((counted as { words: Record<string, string> }).words,
      'счётчик считает без условия, которое владелец видит на экране')
      .toEqual({ manufacturer: 'toki' });

    fireEvent.click(screen.getByRole('button', { name: 'Сохранить отбор' }));
    await waitFor(() => expect(saved).not.toBeNull());
    expect((saved as { words: Record<string, string> }).words,
      'условие не доехало до сервера — прайс уедет прежним')
      .toEqual({ manufacturer: 'toki' });

    // Снимается нажатием на сам значок, как на витрине склада.
    fireEvent.click(screen.getByText(/Производитель: «toki»/));
    await waitFor(() => expect(screen.queryByText(/Производитель: «toki»/)).toBeNull());
  });
});

function feed(overrides: Record<string, unknown> = {}) {
  return {
    id: 7,
    title: 'Дром: основной',
    marketplace: 'DROM',
    status: 'ACTIVE',
    productLine: 'PART',
    hasFeed: true,
    hasCredentials: false,
    plaintextSecret: false,
    lastError: null,
    priceFrom: null,
    priceTo: null,
    conditions: [],
    warehouseIds: [],
    kindIds: [],
    kindsExcluded: false,
    brandIds: [],
    brandsExcluded: false,
    filterColumns: {},
    filterWords: {},
    ...overrides,
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
