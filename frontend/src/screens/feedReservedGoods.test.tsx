import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Что попадает в файл Дрома — как у Bazon (задача 0339, решения владельца
 * 19.10.2026 «Всё да, всё как в базон»), видимая человеку часть.
 *
 * <p>Переключатель «Выгружать товары в резерве» с полем приставки — выключен,
 * пока владелец не включит; подписи обоих переключателей дословно Bazon:
 * клиент переходит оттуда и ищет эти слова. Цена 0 уходит в файл, и экран
 * называет такие позиции красной строкой «с нулевой ценой: N» рядом
 * со счётчиком — узнать о незаполненной цене надо до площадки.
 */
describe('состав файла Дрома на экране «Выгрузки»', () => {
  let saved: unknown = null;
  let counted: unknown = null;
  let answer: Record<string, unknown> = { parts: 12, zeroPrice: 0 };
  let stored: Record<string, unknown> = {};

  beforeEach(() => {
    saved = null;
    counted = null;
    answer = { parts: 12, zeroPrice: 0 };
    stored = {};
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/filter/count')) {
        counted = JSON.parse(String(init?.body ?? 'null'));
        return json(answer);
      }
      if (url.includes('/settings')) {
        saved = JSON.parse(String(init?.body ?? 'null'));
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

  it('подписи переключателей — дословно Bazon', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    expect(screen.getByLabelText('Выгружать товары в резерве')).toBeTruthy();
    expect(screen.getByText(
      'Если товар находится в резерве, то информация об этом добавится в начало объявления',
      { normalizer: (s) => s.replace(/\s+/g, ' ').trim() })).toBeTruthy();
    expect(screen.getByLabelText(
      'Выгружать товары, по которым ожидается поступление')).toBeTruthy();
    expect(screen.getByText(
      'Если товар находится в перемещении, то информация об этом добавится в начало объявления',
      { normalizer: (s) => s.replace(/\s+/g, ' ').trim() })).toBeTruthy();
  });

  it('резерв выключен, пока не включат, и поле приставки погашено', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    const box = screen.getByLabelText('Выгружать товары в резерве') as HTMLInputElement;
    const field = screen.getByLabelText('Приставка о резерве') as HTMLInputElement;
    expect(box.checked, 'резерв выгружается без решения владельца').toBe(false);
    expect(field.disabled, 'приставка правится при выключенном переключателе').toBe(true);
    expect(field.value).toBe('');
  });

  it('включённый резерв и приставка уезжают вместе с соседними настройками', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Выгружать товары в резерве'));
    fireEvent.change(screen.getByLabelText('Приставка о резерве'),
      { target: { value: 'В резерве — уточняйте' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить товар в резерве' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved, 'сохранение резерва стёрло соседние настройки выгрузки').toEqual({
      pricePercent: '-20',
      priceRounding: null,
      photoLimit: '4',
      installationNote: false,
      installationTemplate: 'Стоимость установки на нашем автосервисе: {цена} р.',
      expectedGoods: true,
      expectedGoodsNote: null,
      reservedGoods: true,
      reservedGoodsNote: 'В резерве — уточняйте',
      donorPhotoKinds: [],
    });
  });

  it('сохранённый резерв видно в полях', async () => {
    stored = { settings: { ...settings(), reservedGoods: true, reservedGoodsNote: 'Под заказ' } };
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    expect((screen.getByLabelText('Выгружать товары в резерве') as HTMLInputElement).checked)
      .toBe(true);
    expect((screen.getByLabelText('Приставка о резерве') as HTMLInputElement).value)
      .toBe('Под заказ');
  });

  it('счётчик спрошен с резервом, а не без него', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Выгружать товары в резерве'));
    fireEvent.click(screen.getByRole('button', { name: 'Посчитать' }));

    await waitFor(() => expect(counted).not.toBeNull());
    expect((counted as { reservedGoods: unknown }).reservedGoods,
      'счётчик спрошен без резерва — он обещает меньше, чем уедет').toBe(true);
  });

  it('цена 0 в файле — красной строкой «с нулевой ценой: N» рядом со счётчиком', async () => {
    answer = { parts: 1240, zeroPrice: 4 };
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Посчитать' }));

    const counter = await screen.findByText('В прайс попадёт позиций: 1240');
    const zero = screen.getByText('с нулевой ценой: 4');
    expect(zero.className, 'строка о нулевой цене не красная').toContain('note--error');
    expect(counter.nextElementSibling, 'строка о нулевой цене не рядом со счётчиком')
      .toBe(zero);
  });

  it('без нулевых цен красной строки нет', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Посчитать' }));

    await screen.findByText('В прайс попадёт позиций: 12');
    expect(screen.queryByText(/с нулевой ценой/)).toBeNull();
  });

  it('колёсной выгрузке переключателя резерва не показывают', async () => {
    stored = { title: 'Дром: шины', productLine: 'WHEEL' };
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: шины')).toBeTruthy());

    expect(screen.queryByLabelText('Выгружать товары в резерве')).toBeNull();
  });

  function settings() {
    return {
      pricePercent: -20,
      priceRounding: null,
      photoLimit: 4,
      installationNote: null,
      installationTemplate: null,
      expectedGoods: true,
      expectedGoodsNote: null,
    };
  }

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
      settings: settings(),
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
