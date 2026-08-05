import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Ссылку на прайс можно посмотреть, не сломав её.
 *
 * <p>Показывалась она только сразу после выдачи или смены — то есть узнать
 * её потом было нельзя вовсе. Владелец завёл выгрузку, отдал адрес
 * техспециалисту площадки, через неделю адрес спросили снова: посмотреть
 * негде, а единственная кнопка — «Сменить ссылку», которая, как честно
 * написано рядом, выгрузку останавливает. Чтобы узнать ссылку, приходилось
 * её сломать.
 */
describe('ссылка на прайс', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/feed-url')) {
        // POST — смена ссылки; GET — та, что уже выдана.
        return json({ path: init?.method === 'POST' ? '/feeds/drom/co/novaya.xml'
                                                    : '/feeds/drom/co/tekushchaya.xml' });
      }
      if (url.includes('/api/marketplace-accounts')) {
        return json([{ id: 1, title: 'Дром: основной', marketplace: 'DROM',
                       productLine: 'PART', hasFeed: true, packetId: null,
                       priceFrom: null, priceTo: null, conditions: [], warehouseIds: [],
                       kindIds: [], kindsExcluded: false, brandIds: [], brandsExcluded: false }]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('показывается при раскрытии, а не только после смены', async () => {
    render(<FeedsScreen role="OWNER" />);
    const details = await waitFor(() => {
      const found = document.querySelector('details');
      expect(found).toBeTruthy();
      return found!;
    });

    // До раскрытия ссылки нет — и это верно: её незачем держать на виду.
    expect(document.body.textContent).not.toContain('tekushchaya.xml');

    (details as HTMLDetailsElement).open = true;
    fireEvent(details, new Event('toggle'));

    await waitFor(() => expect(document.body.textContent)
      .toContain('tekushchaya.xml'));
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
