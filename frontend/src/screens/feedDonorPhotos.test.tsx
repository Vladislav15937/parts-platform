import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Снимки машины-донора уходят в объявление выбранных наименований.
 *
 * <p><b>Зачем.</b> Для двигателя и коробки состояние машины — половина
 * объявления: покупатель смотрит, откуда снято, какой пробег, цел ли кузов.
 * У фары те же фотографии лишние, и выборочность — это и есть смысл
 * настройки: дописывающая снимки донора всем, она была бы хуже отсутствующей.
 *
 * <p>Проверяется то, что видит человек: отмеченное наименование уезжает
 * на сервер, уже отмеченное видно в полях при открытии, у колёсной выгрузки
 * блока нет вовсе — и, главное, сохранение снимков донора не стирает
 * соседние настройки выгрузки. Последнее — тот же сторож, что у числа
 * снимков и обеих приписок: сервер кладёт настройки слиянием по составу
 * объекта, и запрос с одним полем записал бы соседним `null`.
 */
describe('снимки машины-донора в объявлении', () => {
  let saved: { url: string; method: string; body: unknown } | null = null;
  /** Чем отвечает список выгрузок. Сбрасывается: иначе тесты зависят от порядка. */
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
      // Справочник эталонов: из него владелец и выбирает наименование.
      // Ветка стоит раньше общей, потому что адрес начинается так же.
      if (url.includes('/api/part-names/kinds/all')) {
        return json([
          { id: 5, categoryId: 1, name: 'Двигатель' },
          { id: 9, categoryId: 2, name: 'Фара' },
        ]);
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

  it('отмеченное наименование уезжает вместе с остальными настройками', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    // Выбор наименований есть и у отбора выгрузки, и здесь, а поле поиска
    // у них называется одинаково — поэтому ищем внутри своего набора.
    const block = donorBlock();
    fireEvent.change(within(block).getByLabelText('Найти'),
      { target: { value: 'двигат' } });
    fireEvent.click(await within(block).findByRole('button', { name: 'Двигатель' }));
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить снимки донора' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved?.method).toBe('PUT');
    expect(saved?.url).toContain('/api/marketplace-accounts/7/settings');
    expect(saved?.body, 'сохранение снимков донора стёрло соседние настройки выгрузки')
      .toEqual({
        pricePercent: '-20',
        priceRounding: null,
        photoLimit: '4',
        installationNote: false,
        installationTemplate: 'Стоимость установки на нашем автосервисе: {цена} р.',
        expectedGoods: false,
        expectedGoodsNote: null,
        donorPhotoKinds: [5],
      });
  });

  it('уже отмеченное видно в полях, а не только в базе', async () => {
    // Иначе владелец с пятью прайс-листами не знает, у какого из них снимки
    // машины уже дописываются, — и решает это заново каждый раз.
    stored = { settings: settings({ donorPhotoKinds: [9] }) };
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    // Названия эталонов приезжают отдельным запросом, поэтому ждём их.
    expect(await within(donorBlock()).findByText('Фара')).toBeTruthy();
  });

  it('пустой выбор уезжает пустым списком: «никому» — это состояние, а не пропуск', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Сохранить снимки донора' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect((saved?.body as { donorPhotoKinds: unknown }).donorPhotoKinds).toEqual([]);
  });

  it('направление отбора тут не предлагается: «кроме этих» здесь не бывает', async () => {
    // Показанный переключатель, который ничего не меняет, читается как
    // сломанный. У отбора выгрузки он нужен («двигатели сюда не выгружать»),
    // здесь — нет: «дописать всем, кроме фары» это снимки машины
    // у девятисот наименований.
    stored = { settings: settings({ donorPhotoKinds: [5] }) };
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    const block = donorBlock();
    // Сначала убедимся, что выбранное наименование и правда показано:
    // иначе отсутствие «Направления» ничего не значит — его не бывает
    // и при пустом выборе.
    expect(await within(block).findByText('Двигатель')).toBeTruthy();
    expect(within(block).queryByLabelText('Направление')).toBeNull();
  });

  it('у колёсной выгрузки блока нет вовсе: донора у колеса не бывает', async () => {
    // Прайс шин донора не отбирает, и показанная настройка была бы
    // обещанием, которого нет.
    stored = { productLine: 'WHEEL' };
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    expect(screen.queryByRole('button', { name: 'Сохранить снимки донора' })).toBeNull();
    expect(screen.queryByText('Снимки машины-донора — каким наименованиям дописывать'))
      .toBeNull();
  });

  /** Набор «Снимки машины-донора»: у отбора выгрузки поля называются так же. */
  function donorBlock(): HTMLElement {
    const legend = screen.getByText('Снимки машины-донора — каким наименованиям дописывать');
    const block = legend.closest('fieldset');
    expect(block, 'блока снимков донора на экране нет').not.toBeNull();
    return block as HTMLElement;
  }

  /** Настройки выгрузки, как их отдаёт сервер: числами, а не строками. */
  function settings(overrides: Record<string, unknown> = {}) {
    return {
      pricePercent: -20,
      priceRounding: null,
      photoLimit: 4,
      installationNote: false,
      installationTemplate: null,
      expectedGoods: false,
      expectedGoodsNote: null,
      donorPhotoKinds: null,
      ...overrides,
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
      ...stored,
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
