import { rightsAs } from '../test/rights';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Карточка выгрузки помнит, что часть изменений не ушла на Дром (задача 0271).
 *
 * <p>Итог последней отправки (0268) честно говорит «принято» про малую дельту,
 * пришедшую после неотправленной, — а крупные изменения так и не уехали.
 * Плашка стоит рядом с итогом и гаснет по ответу сервера, а не по следующей
 * дельте. Жёлтая, не красная: это не отказ, а ожидание полного забора.
 */
describe('плашка «часть изменений не ушла» на карточке выгрузки', () => {
  let feeds: unknown[];

  beforeEach(() => {
    feeds = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/marketplace-accounts')) {
        return json(feeds);
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

  function feed(extra: Record<string, unknown>) {
    return { id: 7, title: 'Дром: основной', marketplace: 'DROM', status: 'ACTIVE',
             productLine: 'PART', hasFeed: true, hasCredentials: true, packetId: '4242',
             lastError: null, lastDownloadAt: null, priceFrom: null, priceTo: null,
             conditions: [], warehouseIds: [], kindIds: [], kindsExcluded: false,
             brandIds: [], brandsExcluded: false,
             deliveryStatus: { state: 'ACCEPTED', at: '2026-10-12T09:40:00Z', code: null },
             ...extra };
  }

  const tooBig = (limitBytes: number, fullPullDays = 3) =>
    ({ at: '2026-10-12T07:05:00Z', code: 'TOO_BIG', limitBytes, fullPullDays });

  async function show(extra: Record<string, unknown>) {
    feeds = [feed(extra)];
    render(<FeedsScreen rights={rightsAs('OWNER')} />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());
  }

  it('после лимита и принятой малой дельты стоят обе строки, и плашка жёлтая', async () => {
    await show({ unsent: tooBig(5 * 1024 * 1024) });

    expect(screen.getByText(/Последняя дельта принята Дромом/)).toBeTruthy();
    const line = screen.getByText(/Часть изменений не ушла на Дром/);
    expect(line.textContent).toMatch(
      /^Часть изменений не ушла на Дром \(\d\d \S+, \d\d:\d\d\): пакет больше лимита 5 МБ\. /);
    expect(line.textContent).toContain(
      'Эти цены и остатки обновятся при ближайшем полном заборе прайса — не позже чем через трое суток.');
    expect(line.textContent).toContain('когда прайс будет скачан Дромом');
    expect(line.className).toContain('note--warn');
    expect(line.className).not.toContain('note--error');
    // Внутреннего на экране нет: ни кода, ни числа байт, ни «ошибки».
    expect(line.textContent).not.toMatch(/TOO_BIG|байт|5242880|ошибк|не принято|обновлено/i);
  });

  it('предел и число суток — из ответа сервера, а не копии на экране', async () => {
    await show({ unsent: tooBig(10 * 1024 * 1024, 1) });

    const line = screen.getByText(/Часть изменений не ушла на Дром/);
    expect(line.textContent).toContain('пакет больше лимита 10 МБ');
    expect(line.textContent).toContain('не позже чем через одни сутки');
  });

  it('отказ «прайс-лист не подключён» называет причину словами карточки', async () => {
    await show({ unsent: { at: '2026-10-12T07:05:00Z', code: 'PACKET_NOT_FOUND',
                           limitBytes: null, fullPullDays: 3 } });

    const line = screen.getByText(/Часть изменений не ушла на Дром/);
    expect(line.textContent).toContain(': прайс-лист не подключён к API.');
    expect(line.textContent).not.toContain('PACKET_NOT_FOUND');
  });

  it('неверный ключ — своими словами', async () => {
    await show({ unsent: { at: '2026-10-12T07:05:00Z', code: 'AUTH_FAILED',
                           limitBytes: null, fullPullDays: 3 } });

    expect(screen.getByText(/Часть изменений не ушла на Дром/).textContent)
      .toContain(': Дром не принял ключ синхронизации.');
  });

  it('нет неотправленного — плашки нет; ответ прежнего сервера без поля — тоже', async () => {
    await show({ unsent: null });
    expect(screen.queryByText(/Часть изменений не ушла/)).toBeNull();
    cleanup();

    await show({});
    expect(screen.queryByText(/Часть изменений не ушла/)).toBeNull();
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
