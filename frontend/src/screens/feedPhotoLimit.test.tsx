import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Число снимков в объявлении задаёт владелец выгрузки.
 *
 * <p><b>Зачем.</b> Предел в десять был зашит в сборку прайса, то есть правка
 * его означала релиз. Площадки считают снимки по-разному, и продавцы ими
 * по-разному пользуются: где-то десять лишние, где-то мало.
 *
 * <p>Проверяется связка целиком: набранное владельцем уезжает на сервер тем же,
 * что он видел, а сохранённое возвращается в поле. И главное — что сохранение
 * числа снимков не стирает наценку: сервер кладёт настройки слиянием по составу
 * объекта, и запрос с одним полем записал бы соседним `null`. Узнать об этом
 * можно было бы только с чужого сайта, по цене без комиссии.
 */
describe('число снимков в объявлении', () => {
  let saved: { url: string; method: string; body: unknown } | null = null;

  beforeEach(() => {
    saved = null;
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

  it('уезжает на сервер вместе с наценкой, а не вместо неё', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Число фотографий в объявлении'),
      { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить число снимков' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved?.method).toBe('PUT');
    expect(saved?.url).toContain('/api/marketplace-accounts/7/settings');
    // Наценка обязана уехать в том же теле: настройки кладутся слиянием
    // по составу объекта, и без неё прайс-лист потерял бы свои −20 %.
    expect(saved?.body, 'сохранение числа снимков стёрло наценку выгрузки')
      .toEqual({ pricePercent: '-20', priceRounding: null, photoLimit: '3' });
  });

  it('ноль уезжает нулём, а не пустотой', async () => {
    // Ноль и пусто на сервере значат разное: ноль — «без ограничения»,
    // пусто — «не задано», то есть прежние десять. Превратив одно
    // в другое здесь, экран отдал бы владельцу не то, что тот выбрал.
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Число фотографий в объявлении'),
      { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить число снимков' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect((saved?.body as { photoLimit: unknown }).photoLimit).toBe('0');
  });

  it('заданное число видно в поле, а не только в базе', async () => {
    // Иначе владелец с пятью прайс-листами не знает, у какого из них
    // снимки уже ограничены, — и решает это заново каждый раз.
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    const field = screen.getByLabelText('Число фотографий в объявлении') as HTMLInputElement;
    expect(field.value).toBe('4');
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
      settings: { pricePercent: -20, priceRounding: null, photoLimit: 4 },
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
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
