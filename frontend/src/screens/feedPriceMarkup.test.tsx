import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Наценка или скидка на прайс-лист задаётся с экрана.
 *
 * <p><b>Зачем.</b> Площадка берёт комиссию, и продавцы закладывают её в цену
 * объявления: у живого клиента на прайсе Авито стоит −20 %. Пока задать это
 * было негде, заложить комиссию можно было только испортив цену товара —
 * то есть подняв её и для прилавка, и для звонка по телефону.
 *
 * <p>Проверяется связка целиком: заданное владельцем уезжает на сервер тем же,
 * что он видел, а сохранённое возвращается в поля. Настройка, которую экран
 * показывает, но не отправляет, — это прайс, уехавший прежней ценой, и узнать
 * об этом можно только с чужого сайта.
 */
describe('наценка на прайс-лист', () => {
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
        return json(feed({ settings: { pricePercent: 10, priceRounding: 10, photoLimit: null } }));
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

  it('уезжает на сервер тем же, что владелец набрал', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Наценка или скидка, %'),
      { target: { value: '10' } });
    fireEvent.change(screen.getByLabelText('Округлять до'), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить цену прайса' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved?.method).toBe('PUT');
    // Отдельным путём от отбора: отбор меняет состав прайса, наценка — цену
    // в нём, и сохранять их одной кнопкой значит не знать, что не сохранилось.
    expect(saved?.url).toContain('/api/marketplace-accounts/7/settings');
    expect(saved?.body, 'наценка не доехала до сервера — прайс уедет прежней ценой')
      .toEqual({ pricePercent: '10', priceRounding: '10', photoLimit: null });
  });

  it('стёртое поле означает «как на складе», а не ноль', async () => {
    // Пустое значение — «без ограничения», а не «ничего»: то же правило,
    // что у границ цены в отборе. Ноль и пусто на сервере различаются,
    // и превращать одно в другое здесь нельзя.
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Наценка или скидка, %'),
      { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить цену прайса' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved?.body).toEqual({ pricePercent: null, priceRounding: null, photoLimit: null });
  });

  it('заданная наценка видна в полях, а не только в базе', async () => {
    // Иначе владелец с пятью прайс-листами не знает, у какого из них
    // комиссия уже заложена, — а поставив её второй раз, уедет с +21 %.
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    const percent = screen.getByLabelText('Наценка или скидка, %') as HTMLInputElement;
    expect(percent.value).toBe('-20');
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
      settings: { pricePercent: -20, priceRounding: null, photoLimit: null },
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
