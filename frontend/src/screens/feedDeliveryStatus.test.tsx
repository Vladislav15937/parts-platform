import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';
import type { DeliveryStatus } from '../publishing/feeds';

/**
 * Итог последней отправки на Дром виден на карточке словами (задача 0268).
 *
 * <p>Сервер отдаёт состояние и код; слова — один словарь на экране. Красная
 * плашка с `ERROR_REASON_…` человеку ничего не говорит, а «принято» до первой
 * отправки было бы ложью.
 */
describe('итог последней отправки на карточке выгрузки', () => {
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
             brandIds: [], brandsExcluded: false, ...extra };
  }

  const refused = (code: string): DeliveryStatus =>
    ({ state: 'REFUSED', at: '2026-10-12T07:05:00Z', code: code as DeliveryStatus['code'] });

  async function show(extra: Record<string, unknown>) {
    feeds = [feed(extra)];
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Дром: основной')).toBeTruthy());
  }

  it('отказ «прайс-лист не подключён» говорит словами, с номером, а код прячет в «Ответ Дрома»', async () => {
    await show({ lastError: 'ERROR_REASON_PACKET_NOT_FOUND',
                 deliveryStatus: refused('PACKET_NOT_FOUND') });

    const line = screen.getByText(/прайс-лист не подключён к API/);
    expect(line.textContent).toContain('номер 4242');
    expect(line.className).toContain('note--error');
    // Код Дрома виден только внутри раскрывающегося блока, не красной строкой.
    const raw = screen.getByText('ERROR_REASON_PACKET_NOT_FOUND');
    expect(raw.closest('details')?.textContent).toContain('Ответ Дрома');
    expect(document.querySelector('.note--error')?.textContent)
      .not.toContain('ERROR_REASON');
  });

  it('до первой отправки не пишет «принято» и называет, чего не хватает', async () => {
    await show({ deliveryStatus: { state: 'WAITING', at: null, code: null } });
    expect(screen.getByText(/Подключено у нас/)).toBeTruthy();
    expect(screen.queryByText(/принята Дромом/)).toBeNull();
    expect(screen.queryByText('Ответ Дрома')).toBeNull();
  });

  it('нет номера — об этом сказано', async () => {
    await show({ packetId: null, deliveryStatus: { state: 'NO_PACKET', at: null, code: null } });
    expect(screen.getByText(/не указан номер прайс-листа/)).toBeTruthy();
  });

  it('принято — со временем', async () => {
    await show({ deliveryStatus: { state: 'ACCEPTED', at: '2026-10-12T07:05:00Z', code: null } });
    expect(screen.getByText(/Последняя дельта принята Дромом — \d\d \S+, \d\d:\d\d\./)).toBeTruthy();
  });

  it('сетевой сбой — спокойная строка, красной остаётся только плашка отказа с действием', async () => {
    await show({ lastError: 'Нет ответа от Дрома: timeout', deliveryStatus: refused('NO_ANSWER') });
    const line = screen.getByText(/Отправка повторится сама/);
    expect(line.className).not.toContain('note--error');
  });

  it('у выключенной выгрузки строки отправки нет', async () => {
    await show({ status: 'PAUSED', deliveryStatus: null });
    expect(screen.queryByText(/Дельты не уходят|Подключено у нас|принята Дромом/)).toBeNull();
  });

  it('у Авито вывод прежний: сырая красная строка', async () => {
    await show({ marketplace: 'AVITO', lastError: 'Ошибка фида', deliveryStatus: null });
    expect(screen.getByText('Ошибка фида').className).toContain('note--error');
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
