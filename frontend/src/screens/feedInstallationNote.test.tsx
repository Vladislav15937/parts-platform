import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Приписку про стоимость установки владелец включает с экрана.
 *
 * <p><b>Зачем.</b> Поле «Цена установки» есть в карточке и даже в отборе
 * выгрузки, а до объявления не доезжало ни одной строкой: услуга заведена,
 * стоит денег и невидима там, где её покупают. Настройка без места, откуда
 * ею воспользоваться, — это отсутствующая возможность, сколько бы её ни
 * поддерживал сервер.
 *
 * <p>И то же, чем болел предел снимков: настройки уезжают <b>все разом</b>,
 * потому что сервер кладёт их слиянием по составу объекта. Кнопка,
 * отправляющая одну приписку, стёрла бы наценку прайс-листа — и узнать
 * об этом можно было бы только с чужого сайта, по цене без комиссии.
 */
describe('приписка про стоимость установки', () => {
  let saved: { url: string; method: string; body: unknown } | null = null;
  /** Что отдаёт сервер в списке выгрузок: тест задаёт своё до отрисовки. */
  let stored: Record<string, unknown> = {};

  beforeEach(() => {
    saved = null;
    stored = {};
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
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

  it('включается флажком и уезжает вместе с наценкой, а не вместо неё', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Дописывать стоимость установки к описанию'));
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить приписку' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved?.method).toBe('PUT');
    expect(saved?.url).toContain('/api/marketplace-accounts/7/settings');
    expect(saved?.body, 'сохранение приписки стёрло соседние настройки выгрузки')
      .toEqual({
        pricePercent: '-20',
        priceRounding: null,
        photoLimit: '4',
        installationNote: true,
        installationTemplate: 'Стоимость установки на нашем автосервисе: {цена} р.',
      });
  });

  it('поле текста открывается заполненным, а не пустым', async () => {
    // Включённая приписка с пустым текстом ничего не допишет, и владелец
    // решит, что переключатель не работает. Заодно формулировка — та же,
    // что у системы, с которой клиенты переходят.
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    const field = screen.getByLabelText('Текст приписки') as HTMLInputElement;
    expect(field.value).toBe('Стоимость установки на нашем автосервисе: {цена} р.');
  });

  it('свой текст владельца уезжает как набран', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Дописывать стоимость установки к описанию'));
    fireEvent.change(screen.getByLabelText('Текст приписки'),
      { target: { value: 'Поставим за {цена} ₽' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить приписку' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect((saved?.body as { installationTemplate: unknown }).installationTemplate)
      .toBe('Поставим за {цена} ₽');
  });

  it('сохранённое состояние видно в поле, а не только в базе', async () => {
    // Иначе владелец с пятью прайс-листами не знает, у какого из них
    // приписка уже включена, — и решает это заново каждый раз.
    stored = {
      title: 'Дром: с установкой',
      settings: {
        pricePercent: null,
        priceRounding: null,
        photoLimit: null,
        installationNote: true,
        installationTemplate: 'Поставим за {цена} ₽',
      },
    };

    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: с установкой')).toBeTruthy());

    const box = screen.getByLabelText(
      'Дописывать стоимость установки к описанию') as HTMLInputElement;
    const field = screen.getByLabelText('Текст приписки') as HTMLInputElement;
    expect(box.checked).toBe(true);
    expect(field.value).toBe('Поставим за {цена} ₽');
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
