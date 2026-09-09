import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Товар, по которому ожидается поступление, владелец выгружает с экрана.
 *
 * <p><b>Зачем.</b> Решение владельца продукта от 5 сентября 2026: такой товар
 * выгружаем, но с припиской — он лежит в контейнере, продавать его можно,
 * покупателю лишь надо сказать, что придётся подождать. Настройка без места,
 * откуда ею воспользоваться, — это отсутствующая возможность, сколько бы её
 * ни поддерживал сервер.
 *
 * <p><b>И это единственная настройка, меняющая состав прайса, а не его вид.</b>
 * Поэтому проверяется ещё и счётчик: в этом модуле он врал уже дважды —
 * на колёсах и на марках из применимости, — и оба раза успокаивающе, обещая
 * не то число, которое уедет площадке.
 */
describe('товар в пути в прайсе', () => {
  let saved: { url: string; method: string; body: unknown } | null = null;
  let counted: unknown = null;
  /** Что отдаёт сервер в списке выгрузок: тест задаёт своё до отрисовки. */
  let stored: Record<string, unknown> = {};

  beforeEach(() => {
    saved = null;
    counted = null;
    stored = {};
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/filter/count')) {
        counted = JSON.parse(String(init?.body ?? 'null'));
        return json({ parts: 12 });
      }
      if (url.includes('/settings')) {
        saved = {
          url,
          method: init?.method ?? 'GET',
          body: JSON.parse(String(init?.body ?? 'null')),
        };
        return json(feed());
      }
      if (url.includes('/api/marketplace-accounts')) {
        return json([feed(stored)]);
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

  it('включается флажком и уезжает вместе с соседними настройками', async () => {
    // Сервер кладёт настройки слиянием по составу объекта: запрос с одним
    // правленым полем записал бы соседним null, и сохранив товар в пути,
    // владелец потерял бы наценку прайс-листа — узнал бы об этом с чужого
    // сайта, по цене без комиссии площадки.
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.click(screen.getByLabelText(
      'Выгружать товары, по которым ожидается поступление'));
    fireEvent.change(screen.getByLabelText('Текст приписки о поступлении'),
      { target: { value: 'Ожидается поступление' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить товар в пути' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved?.method).toBe('PUT');
    expect(saved?.url).toContain('/api/marketplace-accounts/7/settings');
    expect(saved?.body, 'сохранение товара в пути стёрло соседние настройки выгрузки')
      .toEqual({
        pricePercent: '-20',
        priceRounding: null,
        photoLimit: '4',
        installationNote: false,
        installationTemplate: 'Стоимость установки на нашем автосервисе: {цена} р.',
        expectedGoods: true,
        expectedGoodsNote: 'Ожидается поступление',
      });
  });

  it('поле текста открывается пустым: слова выбирает владелец', async () => {
    // Умолчания здесь нет намеренно. Подставленный нами текст уехал бы
    // к покупателю от имени разборки, которая его не писала; пустой текст
    // при включённом переключателе сервер отбивает словами.
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    const field = screen.getByLabelText(
      'Текст приписки о поступлении') as HTMLInputElement;
    expect(field.value).toBe('');
  });

  it('счётчик считает с учётом товара в пути, а не без него', async () => {
    // Счётчик, не знающий условия, обещает не то число, которое уедет
    // площадке. Переключатель живёт в настройках сборки, то есть в отборе
    // его нет, — и приехать в счёт он обязан отдельно.
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.click(screen.getByLabelText(
      'Выгружать товары, по которым ожидается поступление'));
    fireEvent.click(screen.getByRole('button', { name: 'Посчитать' }));

    await waitFor(() => expect(counted).not.toBeNull());
    expect((counted as { expectedGoods: unknown }).expectedGoods,
      'счётчик спрошен без переключателя — он обещает меньше, чем уедет')
      .toBe(true);
  });

  it('сохранённое состояние видно в поле, а не только в базе', async () => {
    // Иначе владелец с пятью прайс-листами не знает, у какого из них
    // ожидаемый товар уже выгружается, — и решает это заново каждый раз.
    stored = {
      title: 'Дром: с контейнером',
      settings: {
        pricePercent: null,
        priceRounding: null,
        photoLimit: null,
        installationNote: null,
        installationTemplate: null,
        expectedGoods: true,
        expectedGoodsNote: 'Ожидается поступление, срок — три недели',
      },
    };

    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: с контейнером')).toBeTruthy());

    const box = screen.getByLabelText(
      'Выгружать товары, по которым ожидается поступление') as HTMLInputElement;
    const field = screen.getByLabelText(
      'Текст приписки о поступлении') as HTMLInputElement;
    expect(box.checked).toBe(true);
    expect(field.value).toBe('Ожидается поступление, срок — три недели');
  });

  it('колёсной выгрузке переключателя не показывают', async () => {
    // В прайсе шин и дисков элемента описания нет вовсе — приписку дописать
    // некуда, и такой товар уехал бы объявлением о детали, которой нет
    // на складе. Показанный переключатель был бы обещанием, которого нет.
    stored = { title: 'Дром: шины', productLine: 'WHEEL' };

    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: шины')).toBeTruthy());

    expect(screen.queryByLabelText(
      'Выгружать товары, по которым ожидается поступление')).toBeNull();
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
      feedFileName: null,
      lastError: null,
      lastDownloadAt: null,
      settings: {
        pricePercent: -20,
        priceRounding: null,
        photoLimit: 4,
        installationNote: null,
        installationTemplate: null,
        expectedGoods: null,
        expectedGoodsNote: null,
      },
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
});
