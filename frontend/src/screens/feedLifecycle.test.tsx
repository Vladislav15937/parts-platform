import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Выгрузку переименовывают, выключают и удаляют — с экрана, а не запросом.
 *
 * <p>Прайс-лист закрывают на сезон или отказываются от него совсем.
 * Пока выключить его было нечем, единственным обходным путём была смена
 * ссылки — то есть поломка адреса, уже прописанного в кабинете площадки,
 * без единого слова о том, почему он перестал работать.
 *
 * <p>Здесь проверяется то, что видит человек: какие кнопки есть, что уходит
 * на сервер и — главное — <b>называет ли подтверждение то, что удаляют</b>.
 * Выгрузок у владельца пять, они различаются только названием, и «Удалить?»
 * без имени — это вопрос, на который нельзя ответить.
 */
describe('выключение и удаление выгрузки', () => {
  let calls: Array<{ method: string; url: string; body: unknown }> = [];
  let status = 'ACTIVE';

  beforeEach(() => {
    calls = [];
    status = 'ACTIVE';

    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      if (url.includes('/api/marketplace-accounts')) {
        if (method !== 'GET') {
          const body = init?.body === undefined || init?.body === null
            ? null
            : JSON.parse(String(init.body));
          calls.push({ method, url, body });
          if (url.endsWith('/status')) {
            status = (body as { status: string }).status;
          }
          return method === 'DELETE' ? empty() : json(feed());
        }
        // Удалённые приходят отдельным ответом: в обычном списке их нет
        // ни строкой, ни в счётчиках.
        if (url.includes('deleted=true')) {
          calls.push({ method, url, body: null });
          return json([{ ...feed(), id: 7, title: 'Дром: зимняя',
                         deletedAt: '2026-09-01T10:00:00Z',
                         lastDownloadAt: '2026-08-30T21:00:00Z' }]);
        }
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

  function feed() {
    return {
      id: 1, title: 'Дром: основной', marketplace: 'DROM', status,
      productLine: 'PART', hasFeed: true, feedFileName: 'drom-parts.xml',
      deletedAt: null, lastDownloadAt: null,
      priceFrom: null, priceTo: null, conditions: [], warehouseIds: [],
      kindIds: [], kindsExcluded: false, brandIds: [], brandsExcluded: false,
    };
  }

  it('переименование уходит на сервер и ссылку не трогает', async () => {
    render(<FeedsScreen role="OWNER" />);

    const field = await waitFor(() => screen.getByDisplayValue('Дром: основной'));
    fireEvent.change(field, { target: { value: 'Дром: низкая цена' } });
    fireEvent.click(screen.getByText('Переименовать'));

    await waitFor(() => expect(calls.filter((c) => c.url.endsWith('/title')))
      .toEqual([{ method: 'PUT', url: '/api/marketplace-accounts/1/title',
                  body: { title: 'Дром: низкая цена' } }]));

    // Ссылку не запрашиваем и не меняем: её прописывает в кабинете площадки
    // техспециалист руками, и правка названия не должна выглядеть как повод
    // идти к нему заново.
    expect(calls.some((c) => c.url.includes('/feed-url') && c.method !== 'GET'))
      .toBe(false);
  });

  it('выключение уходит состоянием, а экран говорит, что это значит', async () => {
    render(<FeedsScreen role="OWNER" />);

    fireEvent.click(await waitFor(() => screen.getByText('Выключить выгрузку')));

    await waitFor(() => expect(calls.filter((c) => c.url.endsWith('/status')))
      .toEqual([{ method: 'PUT', url: '/api/marketplace-accounts/1/status',
                  body: { status: 'PAUSED' } }]));

    // Выключенная выглядела бы работающей: отбор на месте, ссылка на месте,
    // отметка о заборе на месте — а прайс площадка не забирает вовсе.
    await waitFor(() => expect(screen.getByText(/Выгрузка выключена/)).toBeTruthy());
    expect(screen.getByText('Включить выгрузку')).toBeTruthy();
  });

  it('удаление спрашивает подтверждение и называет, что удаляется', async () => {
    render(<FeedsScreen role="OWNER" />);

    fireEvent.click(await waitFor(() => screen.getByText('Удалить выгрузку')));

    // Ничего не ушло: пока подтверждения нет, удаления нет.
    expect(calls.filter((c) => c.method === 'DELETE')).toEqual([]);

    // Выгрузок у владельца пять, и различаются они только названием:
    // «Удалить?» без имени — вопрос, на который нельзя ответить.
    expect(screen.getByText(/Удалить выгрузку «Дром: основной»/)).toBeTruthy();
    expect(screen.getByText(/drom-parts\.xml/)).toBeTruthy();

    fireEvent.click(screen.getByText('Да, удалить «Дром: основной»'));

    await waitFor(() => expect(calls.filter((c) => c.method === 'DELETE'))
      .toEqual([{ method: 'DELETE', url: '/api/marketplace-accounts/1', body: null }]));
  });

  it('отмена подтверждения не удаляет ничего', async () => {
    render(<FeedsScreen role="OWNER" />);

    fireEvent.click(await waitFor(() => screen.getByText('Удалить выгрузку')));
    fireEvent.click(screen.getByText('Отмена'));

    await waitFor(() => expect(screen.getByText('Удалить выгрузку')).toBeTruthy());
    expect(calls.filter((c) => c.method === 'DELETE')).toEqual([]);
  });

  it('удалённые видны отдельно и помнят, когда у них забирали прайс', async () => {
    render(<FeedsScreen role="OWNER" />);

    const section = await waitFor(() => {
      const found = Array.from(document.querySelectorAll('details'))
        .find((d) => d.querySelector('summary')?.textContent === 'Удалённые выгрузки');
      expect(found).toBeTruthy();
      return found!;
    });
    fireEvent.click(section.querySelector('summary')!);

    // Ради этого удаление и сделано пометкой: «эта выгрузка вообще работала
    // и когда её последний раз забирали» спрашивают уже после того, как
    // прайс-лист закрыли.
    await waitFor(() => expect(screen.getByText('Дром: зимняя')).toBeTruthy());
    expect(screen.getByText(/Удалена 01 сен/)).toBeTruthy();
    expect(screen.getByText(/Скачан 31 авг|Скачан 30 авг/)).toBeTruthy();
  });

  it('управляющему выключение и удаление не показываются', async () => {
    render(<FeedsScreen role="MANAGER" />);

    // Ждём саму карточку: отрицание, проверенное до загрузки, проходит само
    // собой — на экране в этот момент нет ничего вообще.
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());

    expect(screen.queryByText('Удалить выгрузку')).toBeNull();
    expect(screen.queryByText('Выключить выгрузку')).toBeNull();
    expect(screen.queryByText('Переименовать')).toBeNull();
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function empty(): Response {
  return new Response(null, { status: 204 });
}
